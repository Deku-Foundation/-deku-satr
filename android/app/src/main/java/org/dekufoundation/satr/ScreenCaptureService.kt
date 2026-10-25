package org.dekufoundation.satr

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.SegmentationMask
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import java.nio.ByteOrder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var overlayController: OverlayController? = null
    private var segmenter: Segmenter? = null
    private var inferenceExecutor: ExecutorService? = null
    private val inferenceBusy = AtomicBoolean(false)

    @Volatile private var projectionStopped = false
    @Volatile private var stopping = false
    @Volatile private var captureGeneration = 0L
    @Volatile private var captureDensityDpi = 0
    @Volatile private var lastSuccessfulFrameMs = 0L
    @Volatile private var lastFrameAcceptedMs = 0L
    @Volatile private var terminalError = false
    private var sessionStartedAtMs = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onCapturedContentResize(width: Int, height: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                captureHandler?.post { handleCapturedContentResize(width, height) }
            }
        }

        override fun onStop() {
            projectionStopped = true
            SessionStatus.publish(
                SatrSessionSnapshot(
                    phase = SatrPhase.STOPPED,
                    detail = "أوقف Android مشاركة الشاشة. لا توجد فلترة فعّالة.",
                ),
            )
            stopSelf()
        }
    }

    private val staleFrameWatchdog = object : Runnable {
        override fun run() {
            if (projection == null) return
            val lastSuccess = lastSuccessfulFrameMs
            val now = SystemClock.elapsedRealtime()
            if (lastSuccess > 0L &&
                now - lastSuccess > STALE_FRAME_LIMIT_MS &&
                SessionStatus.current().phase != SatrPhase.FAIL_CLOSED
            ) {
                failClosed("تأخرت نتائج التحليل؛ غُطّيت الشاشة حتى استعادة المعالجة.")
            }
            captureHandler?.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY

        val command = intent
        if (command == null) {
            publishStartError("لم تصل بيانات جلسة التقاط الشاشة.")
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = command.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = readProjectionIntent(command)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            publishStartError("لم يمنح المستخدم موافقة التقاط الشاشة.")
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        startForegroundCompat(buildNotification())
        sessionStartedAtMs = SystemClock.elapsedRealtime()
        SessionStatus.publish(
            SatrSessionSnapshot(
                phase = SatrPhase.STARTING,
                detail = "يجري إعداد الالتقاط والنموذج المضمّن؛ لم تُثبت الحماية بعد.",
            ),
        )

        return try {
            startCaptureSession(
                resultCode = resultCode,
                resultData = resultData,
                width = command.getIntExtra(EXTRA_CAPTURE_WIDTH, 0),
                height = command.getIntExtra(EXTRA_CAPTURE_HEIGHT, 0),
                densityDpi = command.getIntExtra(
                    EXTRA_DENSITY_DPI,
                    resources.displayMetrics.densityDpi,
                ),
            )
            START_NOT_STICKY
        } catch (_: Exception) {
            publishStartError("تعذر بدء المعالجة. لم تُطبّق الفلترة.")
            stopSelf()
            START_NOT_STICKY
        }
    }

    private fun startCaptureSession(
        resultCode: Int,
        resultData: Intent,
        width: Int,
        height: Int,
        densityDpi: Int,
    ) {
        stopping = false
        terminalError = false
        inferenceBusy.set(false)
        lastFrameAcceptedMs = 0L
        lastSuccessfulFrameMs = SystemClock.elapsedRealtime()
        captureDensityDpi = densityDpi
        captureGeneration += 1L
        require(width > 0 && height > 0) { "Invalid capture dimensions." }

        overlayController = OverlayController(applicationContext) { stopSelf() }
        check(overlayController?.show() == true) {
            "The user-visible overlay could not be shown."
        }

        val options = SelfieSegmenterOptions.Builder()
            .setDetectorMode(SelfieSegmenterOptions.STREAM_MODE)
            .build()
        segmenter = Segmentation.getClient(options)
        inferenceExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "DekuSatrInference").apply { isDaemon = true }
        }

        captureThread = HandlerThread("DekuSatrCapture").apply { start() }
        captureHandler = Handler(captureThread!!.looper)
        imageReader = createImageReader(width, height, captureGeneration)

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, resultData)
            ?: throw IllegalStateException("MediaProjection was not created.")
        projectionStopped = false
        projection?.registerCallback(projectionCallback, Handler(mainLooper))

        virtualDisplay = projection?.createVirtualDisplay(
            "DekuSatrCapture",
            width,
            height,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            captureHandler,
        ) ?: throw IllegalStateException("Virtual display could not be created.")

        captureHandler?.postDelayed(staleFrameWatchdog, WATCHDOG_INTERVAL_MS)
    }

    private fun createImageReader(width: Int, height: Int, generation: Long): ImageReader {
        return ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_IMAGES).apply {
            setOnImageAvailableListener(
                { reader -> onFrameAvailable(reader, generation) },
                captureHandler,
            )
        }
    }

    private fun handleCapturedContentResize(contentWidth: Int, contentHeight: Int) {
        if (stopping || contentWidth <= 0 || contentHeight <= 0) return
        val oldReader = imageReader ?: return
        val size = try {
            CaptureSizePolicy.downsample(contentWidth, contentHeight)
        } catch (_: IllegalArgumentException) {
            failClosed("تعذر تحديد أبعاد الشاشة الجديدة.")
            stopSelf()
            return
        }
        if (size.width == oldReader.width && size.height == oldReader.height) return

        val nextGeneration = captureGeneration + 1L
        synchronized(this) { captureGeneration = nextGeneration }
        lastSuccessfulFrameMs = SystemClock.elapsedRealtime()
        failClosed("تغيّر اتجاه الشاشة أو حجمها؛ يجري ضبط الفلترة مجدداً.")

        val nextReader = try {
            createImageReader(size.width, size.height, nextGeneration)
        } catch (_: Exception) {
            failClosed("تعذر إعادة تهيئة إطار الشاشة بعد تغيير الحجم.")
            stopSelf()
            return
        }

        try {
            val display = virtualDisplay ?: throw IllegalStateException("Virtual display is unavailable.")
            display.resize(size.width, size.height, captureDensityDpi)
            display.setSurface(nextReader.surface)
            imageReader = nextReader
            oldReader.setOnImageAvailableListener(null, null)
            oldReader.close()
        } catch (_: Exception) {
            nextReader.setOnImageAvailableListener(null, null)
            nextReader.close()
            failClosed("تعذر ضبط أبعاد الالتقاط الجديدة؛ أوقف الجلسة وأعد تشغيلها.")
            stopSelf()
        }
    }

    private fun onFrameAvailable(reader: ImageReader) {
        if (stopping) return
        val image = try {
            reader.acquireLatestImage()
        } catch (_: IllegalStateException) {
            null
        } ?: return

        val now = SystemClock.elapsedRealtime()
        if (stopping ||
            now - lastFrameAcceptedMs < MIN_FRAME_INTERVAL_MS ||
            !inferenceBusy.compareAndSet(false, true)
        ) {
            image.close()
            return
        }
        lastFrameAcceptedMs = now

        val frame = try {
            imageToBitmap(image)
        } catch (_: Exception) {
            inferenceBusy.set(false)
            if (stopping) closeSegmenterIfSafe()
            else failClosed("تعذر قراءة إطار الشاشة؛ غُطّيت الشاشة احتياطياً.")
            return
        } finally {
            image.close()
        }

        if (stopping) {
            frame.recycle()
            inferenceBusy.set(false)
            closeSegmenterIfSafe()
            return
        }

        if (isNearUniformBlack(frame)) {
            frame.recycle()
            inferenceBusy.set(false)
            if (stopping) closeSegmenterIfSafe()
            else failClosed("لم تصل صورة شاشة قابلة للتحليل؛ غُطّيت الشاشة احتياطياً.")
            return
        }

        val startedAt = SystemClock.elapsedRealtime()
        try {
            val input = InputImage.fromBitmap(frame, 0)
            val currentSegmenter = segmenter
                ?: throw IllegalStateException("The segmentation engine is unavailable.")
            currentSegmenter.process(input)
                .addOnCompleteListener { task ->
                    if (stopping) {
                        if (!frame.isRecycled) frame.recycle()
                        inferenceBusy.set(false)
                        closeSegmenterIfSafe()
                        return@addOnCompleteListener
                    }

                    val worker = inferenceExecutor
                    if (worker == null) {
                        if (!frame.isRecycled) frame.recycle()
                        inferenceBusy.set(false)
                        closeSegmenterIfSafe()
                        return@addOnCompleteListener
                    }

                    try {
                        worker.execute {
                            try {
                                if (stopping) return@execute
                                if (!task.isSuccessful) {
                                    failClosed("فشل تحليل الإطار؛ غُطّيت الشاشة حتى عودة المعالجة.")
                                    return@execute
                                }
                                val mask = task.result
                                    ?: throw IllegalStateException("The segmentation mask was empty.")
                                val values = copyConfidence(mask)
                                val rendered = PrivacyRenderer.render(
                                    source = frame,
                                    confidence = values,
                                    maskWidth = mask.width,
                                    maskHeight = mask.height,
                                    blurRadius = FilterSettings.blurRadius,
                                )
                                val activeOverlay = overlayController
                                if (activeOverlay == null) {
                                    if (!rendered.overlay.isRecycled) rendered.overlay.recycle()
                                    return@execute
                                }

                                val latencyMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
                                var published = false
                                synchronized(this) {
                                    if (!stopping) {
                                        val previous = SessionStatus.current()
                                        val detected = rendered.coveragePercent > MIN_PERSON_COVERAGE_PERCENT
                                        val next = previous.copy(
                                            phase = if (detected) SatrPhase.FILTERING else SatrPhase.NO_PERSON_DETECTED,
                                            detail = if (detected) {
                                                "تمويه تجريبي مطبّق على المنطقة التي رصدها النموذج."
                                            } else {
                                                "لم يرصد النموذج شخصاً بوضوح؛ قد يفوّت أشخاصاً صغيرين أو بعيدين."
                                            },
                                            processedFrames = previous.processedFrames + 1,
                                            lastInferenceMs = latencyMs,
                                            maskCoveragePercent = rendered.coveragePercent,
                                            modelReady = true,
                                        )
                                        lastSuccessfulFrameMs = SystemClock.elapsedRealtime()
                                        activeOverlay.updateFilterFrame(rendered.overlay)
                                        activeOverlay.updateSession(next)
                                        SessionStatus.publish(next)
                                        published = true
                                    }
                                }
                                if (!published && !rendered.overlay.isRecycled) rendered.overlay.recycle()
                            } catch (_: Exception) {
                                if (!stopping) {
                                    failClosed("تعذر إنشاء قناع التمويه؛ غُطّيت الشاشة احتياطياً.")
                                }
                            } finally {
                                if (!frame.isRecycled) frame.recycle()
                                inferenceBusy.set(false)
                                if (stopping) closeSegmenterIfSafe()
                            }
                        }
                    } catch (_: java.util.concurrent.RejectedExecutionException) {
                        if (!frame.isRecycled) frame.recycle()
                        inferenceBusy.set(false)
                        closeSegmenterIfSafe()
                    }
                }
        } catch (_: Exception) {
            if (!frame.isRecycled) frame.recycle()
            inferenceBusy.set(false)
            if (stopping) closeSegmenterIfSafe()
            else failClosed("تعذر تشغيل النموذج؛ غُطّيت الشاشة احتياطياً.")
        }
    }

    private fun copyConfidence(mask: SegmentationMask): FloatArray {
        val pixelCount = mask.width.toLong() * mask.height.toLong()
        require(
            mask.width > 0 &&
                mask.height > 0 &&
                pixelCount in 1..MAX_MASK_PIXELS.toLong(),
        ) { "Invalid model mask dimensions." }
        val count = pixelCount.toInt()
        val buffer = mask.buffer.duplicate().order(ByteOrder.nativeOrder())
        buffer.rewind()
        require(buffer.remaining().toLong() >= count.toLong() * Float.SIZE_BYTES) {
            "The model returned an incomplete mask."
        }
        return FloatArray(count) { buffer.float.coerceIn(0f, 1f) }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        require(image.planes.isNotEmpty()) { "Captured frame has no pixel plane." }
        val width = image.width
        val height = image.height
        val pixelCountLong = width.toLong() * height.toLong()
        require(
            width > 0 &&
                height > 0 &&
                pixelCountLong in 1..MAX_FRAME_PIXELS.toLong(),
        ) { "Captured frame dimensions are outside the processing budget." }

        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        require(pixelStride >= 4 && rowStride > 0) {
            "Unexpected pixel layout."
        }
        val buffer = plane.buffer
        val finalByteExclusive =
            (height - 1L) * rowStride + (width - 1L) * pixelStride + 4L
        require(finalByteExclusive <= buffer.limit().toLong()) {
            "Captured frame buffer is incomplete."
        }

        // PixelFormat.RGBA_8888 is read byte-by-byte and converted into Android ARGB ints.
        // This avoids depending on platform-native Bitmap byte order or row-padding assumptions.
        val pixels = IntArray(pixelCountLong.toInt())
        for (y in 0 until height) {
            val rowOffset = y * rowStride
            val pixelRowOffset = y * width
            for (x in 0 until width) {
                val offset = rowOffset + x * pixelStride
                val red = buffer.get(offset).toInt() and 0xFF
                val green = buffer.get(offset + 1).toInt() and 0xFF
                val blue = buffer.get(offset + 2).toInt() and 0xFF
                val alpha = buffer.get(offset + 3).toInt() and 0xFF
                pixels[pixelRowOffset + x] =
                    (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            try {
                bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            } catch (error: Exception) {
                bitmap.recycle()
                throw error
            }
        }
    }

    private fun isNearUniformBlack(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return true
        val samples = intArrayOf(
            bitmap.getPixel(width / 2, height / 2),
            bitmap.getPixel(width / 4, height / 4),
            bitmap.getPixel(width * 3 / 4, height / 4),
            bitmap.getPixel(width / 4, height * 3 / 4),
            bitmap.getPixel(width * 3 / 4, height * 3 / 4),
        )
        return samples.all { pixel ->
            (pixel ushr 16 and 0xFF) < 8 &&
                (pixel ushr 8 and 0xFF) < 8 &&
                (pixel and 0xFF) < 8
        }
    }

    private fun failClosed(detail: String) {
        if (stopping) return
        val current = SessionStatus.current()
        val next = current.copy(
            phase = SatrPhase.FAIL_CLOSED,
            detail = detail,
        )
        SessionStatus.publish(next)
        overlayController?.showFailClosed(detail)
        overlayController?.updateSession(next)
    }

    private fun publishStartError(detail: String) {
        terminalError = true
        SessionStatus.publish(
            SatrSessionSnapshot(
                phase = SatrPhase.ERROR,
                detail = detail,
            ),
        )
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            OPEN_PENDING_INTENT_REQUEST,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            STOP_PENDING_INTENT_REQUEST,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(R.drawable.ic_satr)
            .setContentTitle("دِكو سَتر · جلسة التقاط نشطة")
            .setContentText("تمويه تجريبي محلي؛ قد يفوت النموذج أشخاصاً.")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(null, "إيقاف الجلسة", stopPendingIntent).build(),
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "جلسة الالتقاط والخصوصية",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "مؤشر ظاهر لجلسة التقاط الشاشة وإيقافها."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun readProjectionIntent(source: Intent?): Intent? {
        if (source == null) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            source.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            source.getParcelableExtra(EXTRA_RESULT_DATA)
        }
    }

    private fun closeSegmenterIfSafe() {
        val current = synchronized(this) {
            if (inferenceBusy.get()) null else segmenter.also { segmenter = null }
        } ?: return
        runCatching { current.close() }
    }

    private fun cleanup() {
        synchronized(this) { stopping = true }
        captureHandler?.removeCallbacks(staleFrameWatchdog)
        imageReader?.setOnImageAvailableListener(null, null)
        overlayController?.remove()
        overlayController = null

        val currentProjection = projection
        projection = null
        if (currentProjection != null) {
            try {
                currentProjection.unregisterCallback(projectionCallback)
            } catch (_: IllegalArgumentException) {
                // The system may already have revoked the projection.
            }
        }

        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        captureHandler = null
        captureThread?.quitSafely()
        captureThread = null

        val currentExecutor = inferenceExecutor
        inferenceExecutor = null
        currentExecutor?.shutdown()
        closeSegmenterIfSafe()

        if (currentProjection != null && !projectionStopped) {
            try {
                currentProjection.stop()
            } catch (_: RuntimeException) {
                // Android may already have stopped the capture session.
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (!terminalError) {
            SessionStatus.publish(
                SatrSessionSnapshot(
                    phase = SatrPhase.STOPPED,
                    detail = "الجلسة متوقفة. لا توجد فلترة فعّالة.",
                ),
            )
        }
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "org.dekufoundation.satr.action.START_CAPTURE"
        const val ACTION_STOP = "org.dekufoundation.satr.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        const val EXTRA_CAPTURE_WIDTH = "capture_width"
        const val EXTRA_CAPTURE_HEIGHT = "capture_height"
        const val EXTRA_DENSITY_DPI = "capture_density_dpi"

        private const val CHANNEL_ID = "deku_satr_capture"
        private const val NOTIFICATION_ID = 410
        private const val OPEN_PENDING_INTENT_REQUEST = 410
        private const val STOP_PENDING_INTENT_REQUEST = 411
        private const val MAX_IMAGES = 2
        private const val MIN_FRAME_INTERVAL_MS = 250L
        private const val STALE_FRAME_LIMIT_MS = 1_500L
        private const val WATCHDOG_INTERVAL_MS = 500L
        private const val MAX_MASK_PIXELS = 2_000_000
        private const val MAX_FRAME_PIXELS = 500_000
        private const val MIN_PERSON_COVERAGE_PERCENT = 1
    }
}    private fun onFrameAvailable(reader: ImageReader, frameGeneration: Long) {
        if (stopping || frameGeneration != captureGeneration) {
            runCatching { reader.acquireLatestImage()?.close() }
            return
        }
        val image = try {
            reader.acquireLatestImage()
        } catch (_: IllegalStateException) {
            null
        } ?: return

        val now = SystemClock.elapsedRealtime()
        if (stopping ||
            frameGeneration != captureGeneration ||
            now - lastFrameAcceptedMs < MIN_FRAME_INTERVAL_MS ||
            !inferenceBusy.compareAndSet(false, true)
        ) {
            image.close()
            return
        }
        lastFrameAcceptedMs = now

        val frame = try {
            imageToBitmap(image)
        } catch (_: Exception) {
            inferenceBusy.set(false)
            if (stopping) closeSegmenterIfSafe()
            else if (frameGeneration == captureGeneration) {
                failClosed("تعذر قراءة إطار الشاشة؛ غُطّيت الشاشة احتياطياً.")
            }
            return
        } finally {
            image.close()
        }

        if (stopping || frameGeneration != captureGeneration) {
            frame.recycle()
            inferenceBusy.set(false)
            closeSegmenterIfSafe()
            return
        }

        if (isNearUniformBlack(frame)) {
            frame.recycle()
            inferenceBusy.set(false)
            if (stopping) closeSegmenterIfSafe()
            else if (frameGeneration == captureGeneration) {
                failClosed("لم تصل صورة شاشة قابلة للتحليل؛ غُطّيت الشاشة احتياطياً.")
            }
            return
        }

        val startedAt = SystemClock.elapsedRealtime()
        try {
            val input = InputImage.fromBitmap(frame, 0)
            val currentSegmenter = segmenter
                ?: throw IllegalStateException("The segmentation engine is unavailable.")
            currentSegmenter.process(input)
                .addOnCompleteListener { task ->
                    if (stopping || frameGeneration != captureGeneration) {
                        if (!frame.isRecycled) frame.recycle()
                        inferenceBusy.set(false)
                        if (stopping) closeSegmenterIfSafe()
                        return@addOnCompleteListener
                    }

                    val worker = inferenceExecutor
                    if (worker == null) {
                        if (!frame.isRecycled) frame.recycle()
                        inferenceBusy.set(false)
                        closeSegmenterIfSafe()
                        return@addOnCompleteListener
                    }

                    try {
                        worker.execute {
                            try {
                                if (stopping || frameGeneration != captureGeneration) return@execute
                                if (!task.isSuccessful) {
                                    failClosed("فشل تحليل الإطار؛ غُطّيت الشاشة حتى عودة المعالجة.")
                                    return@execute
                                }
                                val mask = task.result
                                    ?: throw IllegalStateException("The segmentation mask was empty.")
                                val values = copyConfidence(mask)
                                val rendered = PrivacyRenderer.render(
                                    source = frame,
                                    confidence = values,
                                    maskWidth = mask.width,
                                    maskHeight = mask.height,
                                    blurRadius = FilterSettings.blurRadius,
                                )
                                val activeOverlay = overlayController
                                if (activeOverlay == null) {
                                    if (!rendered.overlay.isRecycled) rendered.overlay.recycle()
                                    return@execute
                                }

                                val latencyMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
                                var published = false
                                synchronized(this) {
                                    if (!stopping && frameGeneration == captureGeneration) {
                                        val previous = SessionStatus.current()
                                        val detected = rendered.coveragePercent > MIN_PERSON_COVERAGE_PERCENT
                                        val next = previous.copy(
                                            phase = if (detected) SatrPhase.FILTERING else SatrPhase.NO_PERSON_DETECTED,
                                            detail = if (detected) {
                                                "تمويه تجريبي مطبّق على المنطقة التي رصدها النموذج."
                                            } else {
                                                "لم يرصد النموذج شخصاً بوضوح؛ قد يفوّت أشخاصاً صغيرين أو بعيدين."
                                            },
                                            processedFrames = previous.processedFrames + 1,
                                            lastInferenceMs = latencyMs,
                                            maskCoveragePercent = rendered.coveragePercent,
                                            modelReady = true,
                                        )
                                        lastSuccessfulFrameMs = SystemClock.elapsedRealtime()
                                        activeOverlay.updateFilterFrame(rendered.overlay)
                                        activeOverlay.updateSession(next)
                                        SessionStatus.publish(next)
                                        published = true
                                    }
                                }
                                if (!published && !rendered.overlay.isRecycled) rendered.overlay.recycle()
                            } catch (_: Exception) {
                                if (!stopping && frameGeneration == captureGeneration) {
                                    failClosed("تعذر إنشاء قناع التمويه؛ غُطّيت الشاشة احتياطياً.")
                                }
                            } finally {
                                if (!frame.isRecycled) frame.recycle()
                                inferenceBusy.set(false)
                                if (stopping) closeSegmenterIfSafe()
                            }
                        }
                    } catch (_: java.util.concurrent.RejectedExecutionException) {
                        if (!frame.isRecycled) frame.recycle()
                        inferenceBusy.set(false)
                        if (stopping) closeSegmenterIfSafe()
                    }
                }
        } catch (_: Exception) {
            if (!frame.isRecycled) frame.recycle()
            inferenceBusy.set(false)
            if (stopping) closeSegmenterIfSafe()
            else if (frameGeneration == captureGeneration) {
                failClosed("تعذر تشغيل النموذج؛ غُطّيت الشاشة احتياطياً.")
            }
        }
    }

    private fun copyConfidence(mask: SegmentationMask): FloatArray {
        val pixelCount = mask.width.toLong() * mask.height.toLong()
        require(
            mask.width > 0 &&
                mask.height > 0 &&
                pixelCount in 1..MAX_MASK_PIXELS.toLong(),
        ) { "Invalid model mask dimensions." }
        val count = pixelCount.toInt()
        val buffer = mask.buffer.duplicate().order(ByteOrder.nativeOrder())
        buffer.rewind()
        require(buffer.remaining().toLong() >= count.toLong() * Float.SIZE_BYTES) {
            "The model returned an incomplete mask."
        }
        return FloatArray(count) { buffer.float.coerceIn(0f, 1f) }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        require(image.planes.isNotEmpty()) { "Captured frame has no pixel plane." }
        val width = image.width
        val height = image.height
        val pixelCountLong = width.toLong() * height.toLong()
        require(
            width > 0 &&
                height > 0 &&
                pixelCountLong in 1..MAX_FRAME_PIXELS.toLong(),
        ) { "Captured frame dimensions are outside the processing budget." }

        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        require(pixelStride >= 4 && rowStride > 0) {
            "Unexpected pixel layout."
        }
        val buffer = plane.buffer
        val finalByteExclusive =
            (height - 1L) * rowStride + (width - 1L) * pixelStride + 4L
        require(finalByteExclusive <= buffer.limit().toLong()) {
            "Captured frame buffer is incomplete."
        }

        // PixelFormat.RGBA_8888 is read byte-by-byte and converted into Android ARGB ints.
        // This avoids depending on platform-native Bitmap byte order or row-padding assumptions.
        val pixels = IntArray(pixelCountLong.toInt())
        for (y in 0 until height) {
            val rowOffset = y * rowStride
            val pixelRowOffset = y * width
            for (x in 0 until width) {
                val offset = rowOffset + x * pixelStride
                val red = buffer.get(offset).toInt() and 0xFF
                val green = buffer.get(offset + 1).toInt() and 0xFF
                val blue = buffer.get(offset + 2).toInt() and 0xFF
                val alpha = buffer.get(offset + 3).toInt() and 0xFF
                pixels[pixelRowOffset + x] =
                    (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            try {
                bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            } catch (error: Exception) {
                bitmap.recycle()
                throw error
            }
        }
    }

    private fun isNearUniformBlack(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return true
        val samples = intArrayOf(
            bitmap.getPixel(width / 2, height / 2),
            bitmap.getPixel(width / 4, height / 4),
            bitmap.getPixel(width * 3 / 4, height / 4),
            bitmap.getPixel(width / 4, height * 3 / 4),
            bitmap.getPixel(width * 3 / 4, height * 3 / 4),
        )
        return samples.all { pixel ->
            (pixel ushr 16 and 0xFF) < 8 &&
                (pixel ushr 8 and 0xFF) < 8 &&
                (pixel and 0xFF) < 8
        }
    }

    private fun failClosed(detail: String) {
        if (stopping) return
        val current = SessionStatus.current()
        val next = current.copy(
            phase = SatrPhase.FAIL_CLOSED,
            detail = detail,
        )
        SessionStatus.publish(next)
        overlayController?.showFailClosed(detail)
        overlayController?.updateSession(next)
    }

    private fun publishStartError(detail: String) {
        terminalError = true
        SessionStatus.publish(
            SatrSessionSnapshot(
                phase = SatrPhase.ERROR,
                detail = detail,
            ),
        )
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            OPEN_PENDING_INTENT_REQUEST,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            STOP_PENDING_INTENT_REQUEST,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(R.drawable.ic_satr)
            .setContentTitle("دِكو سَتر · جلسة التقاط نشطة")
            .setContentText("تمويه تجريبي محلي؛ قد يفوت النموذج أشخاصاً.")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(null, "إيقاف الجلسة", stopPendingIntent).build(),
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "جلسة الالتقاط والخصوصية",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "مؤشر ظاهر لجلسة التقاط الشاشة وإيقافها."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun readProjectionIntent(source: Intent?): Intent? {
        if (source == null) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            source.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            source.getParcelableExtra(EXTRA_RESULT_DATA)
        }
    }

    private fun closeSegmenterIfSafe() {
        val current = synchronized(this) {
            if (inferenceBusy.get()) null else segmenter.also { segmenter = null }
        } ?: return
        runCatching { current.close() }
    }

    private fun cleanup() {
        synchronized(this) { stopping = true }
        captureHandler?.removeCallbacks(staleFrameWatchdog)
        imageReader?.setOnImageAvailableListener(null, null)
        overlayController?.remove()
        overlayController = null

        val currentProjection = projection
        projection = null
        if (currentProjection != null) {
            try {
                currentProjection.unregisterCallback(projectionCallback)
            } catch (_: IllegalArgumentException) {
                // The system may already have revoked the projection.
            }
        }

        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        captureHandler = null
        captureThread?.quitSafely()
        captureThread = null

        val currentExecutor = inferenceExecutor
        inferenceExecutor = null
        currentExecutor?.shutdown()
        closeSegmenterIfSafe()

        if (currentProjection != null && !projectionStopped) {
            try {
                currentProjection.stop()
            } catch (_: RuntimeException) {
                // Android may already have stopped the capture session.
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (!terminalError) {
            SessionStatus.publish(
                SatrSessionSnapshot(
                    phase = SatrPhase.STOPPED,
                    detail = "الجلسة متوقفة. لا توجد فلترة فعّالة.",
                ),
            )
        }
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "org.dekufoundation.satr.action.START_CAPTURE"
        const val ACTION_STOP = "org.dekufoundation.satr.action.STOP_CAPTURE"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        const val EXTRA_CAPTURE_WIDTH = "capture_width"
        const val EXTRA_CAPTURE_HEIGHT = "capture_height"
        const val EXTRA_DENSITY_DPI = "capture_density_dpi"

        private const val CHANNEL_ID = "deku_satr_capture"
        private const val NOTIFICATION_ID = 410
        private const val OPEN_PENDING_INTENT_REQUEST = 410
        private const val STOP_PENDING_INTENT_REQUEST = 411
        private const val MAX_IMAGES = 2
        private const val MIN_FRAME_INTERVAL_MS = 250L
        private const val STALE_FRAME_LIMIT_MS = 1_500L
        private const val WATCHDOG_INTERVAL_MS = 500L
        private const val MAX_MASK_PIXELS = 2_000_000
        private const val MAX_FRAME_PIXELS = 500_000
        private const val MIN_PERSON_COVERAGE_PERCENT = 1
    }
}

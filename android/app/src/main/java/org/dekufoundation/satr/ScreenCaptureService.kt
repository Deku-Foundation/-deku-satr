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
import android.util.DisplayMetrics
import android.view.Display
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
    @Volatile private var segmenter: LrasppPersonSegmenter? = null
    private var inferenceExecutor: ExecutorService? = null
    private val inferenceBusy = AtomicBoolean(false)

    @Volatile private var projectionStopped = false
    @Volatile private var stopping = false
    @Volatile private var captureGeneration = 0L
    @Volatile private var captureDensityDpi = 0
    @Volatile private var lastSuccessfulFrameMs = 0L
    @Volatile private var displayListenerRegistered = false
    @Volatile private var lastFrameAcceptedMs = 0L
    @Volatile private var terminalError = false
    private var sessionStartedAtMs = 0L
    private var personFramesInARow = 0
    private var emptyFramesInARow = 0
    private var lastGoodConfidence: FloatArray? = null
    private var lastGoodMaskWidth = 0
    private var lastGoodMaskHeight = 0
    private var lastGoodMaskAtMs = 0L

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

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit

        override fun onDisplayChanged(displayId: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
            if (displayId != Display.DEFAULT_DISPLAY || stopping) return
            captureHandler?.post {
                if (stopping) return@post
                val dimensions = defaultDisplayDimensions() ?: return@post
                handleCapturedContentResize(dimensions.first, dimensions.second)
            }
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
        synchronized(this) {
            personFramesInARow = 0
            emptyFramesInARow = 0
            lastGoodConfidence = null
            lastGoodMaskWidth = 0
            lastGoodMaskHeight = 0
            lastGoodMaskAtMs = 0L
        }
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
        // Keep the display covered while the quantized model is loaded off the main thread.
        failClosed("يجري تحميل نموذج تقسيم الأشخاص؛ ستبدأ الفلترة بعد جاهزيته.")

        inferenceExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "DekuSatrInference").apply { isDaemon = true }
        }
        val modelExecutor = inferenceExecutor
            ?: throw IllegalStateException("The inference executor could not be created.")
        modelExecutor.execute {
            try {
                val loadedModel = LrasppPersonSegmenter(applicationContext)
                synchronized(this) {
                    if (stopping) loadedModel.close() else segmenter = loadedModel
                }
            } catch (_: Exception) {
                failClosed("تعذر تحميل نموذج تقسيم الأشخاص؛ أوقفت الجلسة.")
                publishStartError("تعذر تحميل النموذج؛ لم تُفعّل الفلترة.")
                stopSelf()
            }
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

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val manager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            manager.registerDisplayListener(displayListener, captureHandler)
            displayListenerRegistered = true
        }
        captureHandler?.postDelayed(staleFrameWatchdog, WATCHDOG_INTERVAL_MS)
    }

    private fun defaultDisplayDimensions(): Pair<Int, Int>? {
        val manager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = manager.getDisplay(Display.DEFAULT_DISPLAY) ?: return null
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) return null
        return metrics.widthPixels to metrics.heightPixels
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
        synchronized(this) {
            lastGoodConfidence = null
            lastGoodMaskWidth = 0
            lastGoodMaskHeight = 0
            lastGoodMaskAtMs = 0L
        }
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

    private fun onFrameAvailable(reader: ImageReader, frameGeneration: Long) {
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
            now - lastFrameAcceptedMs < FilterSettings.processingProfile.frameIntervalMs ||
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
                failClosed("تعذر قراءة إطار الشاشة؛ غُطّيت الشاشة احتياطياً.", frameGeneration)
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

        val worker = inferenceExecutor
        val currentSegmenter = segmenter
        if (currentSegmenter == null) {
            // Startup has already installed an opaque fail-closed overlay; drop frames until ready.
            frame.recycle()
            inferenceBusy.set(false)
            return
        }
        if (worker == null) {
            frame.recycle()
            inferenceBusy.set(false)
            failClosed("محرك تقسيم الأشخاص غير متاح؛ غُطّيت الشاشة احتياطياً.", frameGeneration)
            return
        }
        val startedAt = SystemClock.elapsedRealtime()
        try {
            worker.execute {
                try {
                    if (stopping || frameGeneration != captureGeneration) return@execute
                    val mask = currentSegmenter.process(frame)
                    val freshValues = mask.confidence
                    val threshold = FilterSettings.confidenceThreshold
                    val expansion = FilterSettings.maskExpansionRadius
                    val freshCoverage = PersonMaskMath.coveragePercent(
                        PersonMaskMath.thresholdAndDilate(
                            confidence = freshValues,
                            width = mask.width,
                            height = mask.height,
                            threshold = threshold,
                            radiusX = expansion,
                            radiusY = expansion,
                        ),
                    )
                    val frameNow = SystemClock.elapsedRealtime()
                    val values = synchronized(this) {
                        if (freshCoverage > MIN_PERSON_COVERAGE_PERCENT) {
                            lastGoodConfidence = freshValues.copyOf()
                            lastGoodMaskWidth = mask.width
                            lastGoodMaskHeight = mask.height
                            lastGoodMaskAtMs = frameNow
                            freshValues
                        } else {
                            val cached = lastGoodConfidence
                            val reusePrevious = MaskHoldPolicy.shouldReusePreviousMask(
                                freshCoveragePercent = freshCoverage,
                                minimumCoveragePercent = MIN_PERSON_COVERAGE_PERCENT,
                                previousMaskAvailable = cached != null,
                                dimensionsMatch = lastGoodMaskWidth == mask.width &&
                                    lastGoodMaskHeight == mask.height,
                                ageMs = frameNow - lastGoodMaskAtMs,
                                maximumAgeMs = MASK_HOLD_MS,
                            )
                            if (reusePrevious) cached!!.copyOf() else freshValues
                        }
                    }
                    val rendered = PrivacyRenderer.render(
                        source = frame,
                        confidence = values,
                        maskWidth = mask.width,
                        maskHeight = mask.height,
                        blurRadius = FilterSettings.blurRadius,
                        threshold = FilterSettings.confidenceThreshold,
                        maskExpansionRadius = expansion,
                        effect = FilterSettings.effect,
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
                            val detectedNow = rendered.coveragePercent > MIN_PERSON_COVERAGE_PERCENT
                            if (detectedNow) {
                                personFramesInARow = (personFramesInARow + 1).coerceAtMost(3)
                                emptyFramesInARow = 0
                            } else {
                                emptyFramesInARow = (emptyFramesInARow + 1).coerceAtMost(4)
                                personFramesInARow = 0
                            }
                            val detected = if (previous.phase == SatrPhase.FILTERING) {
                                emptyFramesInARow < EMPTY_FRAMES_TO_CLEAR_MASK
                            } else {
                                personFramesInARow >= PERSON_FRAMES_TO_CONFIRM
                            }
                            val next = previous.copy(
                                phase = if (detected) SatrPhase.FILTERING else SatrPhase.NO_PERSON_DETECTED,
                                detail = if (detected) {
                                    "يُطبّق التمويه على المناطق التي يرصدها نموذج LR-ASPP."
                                } else {
                                    "لم تُرصد منطقة شخص بوضوح؛ قد يفوّت النموذج الأجسام الصغيرة."
                                },
                                processedFrames = previous.processedFrames + 1,
                                lastInferenceMs = latencyMs,
                                maskCoveragePercent = rendered.coveragePercent,
                                modelReady = true,
                            )
                            lastSuccessfulFrameMs = SystemClock.elapsedRealtime()
                            val shouldUpdateMask =
                                detected ||
                                    rendered.coveragePercent > MIN_PERSON_COVERAGE_PERCENT ||
                                    emptyFramesInARow >= EMPTY_FRAMES_TO_CLEAR_MASK
                            if (shouldUpdateMask) {
                                activeOverlay.updateFilterFrame(rendered.overlay, rendered.coveragePercent)
                            } else if (!rendered.overlay.isRecycled) {
                                rendered.overlay.recycle()
                            }
                            activeOverlay.updateSession(next)
                            SessionStatus.publish(next)
                            published = true
                        }
                    }
                    if (!published && !rendered.overlay.isRecycled) rendered.overlay.recycle()
                } catch (_: Exception) {
                    if (!stopping && frameGeneration == captureGeneration) {
                        failClosed("تعذر إنشاء قناع الأشخاص؛ غُطّيت الشاشة احتياطياً.", frameGeneration)
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

    private fun failClosed(detail: String, expectedGeneration: Long? = null) {
        synchronized(this) {
            if (stopping) return
            if (expectedGeneration != null && expectedGeneration != captureGeneration) return

            val current = SessionStatus.current()
            val next = current.copy(
                phase = SatrPhase.FAIL_CLOSED,
                detail = detail,
            )
            SessionStatus.publish(next)
            // Queue the full-cover UI update while still holding the state lock so a stale
            // error cannot appear above a newer successful frame.
            overlayController?.showFailClosed(detail)
            overlayController?.updateSession(next)
        }
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
            .setContentTitle("ستر بلور · جلسة التقاط نشطة")
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
        if (displayListenerRegistered) {
            runCatching {
                (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                    .unregisterDisplayListener(displayListener)
            }
            displayListenerRegistered = false
        }
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
        private const val MASK_HOLD_MS = 650L
        private const val STALE_FRAME_LIMIT_MS = 3_500L
        private const val WATCHDOG_INTERVAL_MS = 500L
        private const val MAX_MASK_PIXELS = 2_000_000
        private const val MAX_FRAME_PIXELS = 600_000
        private const val MIN_PERSON_COVERAGE_PERCENT = 1
        private const val PERSON_FRAMES_TO_CONFIRM = 2
        private const val EMPTY_FRAMES_TO_CLEAR_MASK = 3
    }
}
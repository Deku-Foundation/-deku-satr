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
    @Volatile private var lastSuccessfulFrameMs = 0L
    @Volatile private var lastFrameAcceptedMs = 0L
    @Volatile private var terminalError = false
    private var sessionStartedAtMs = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
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
        imageReader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            MAX_IMAGES,
        ).apply {
            setOnImageAvailableListener({ reader -> onFrameAvailable(reader) }, captureHandler)
        }

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

    private fun onFrameAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (_: IllegalStateException) {
            null
        } ?: return

        val now = SystemClock.elapsedRealtime()
        if (now - lastFrameAcceptedMs < MIN_FRAME_INTERVAL_MS ||
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
            failClosed("تعذر قراءة إطار الشاشة؛ غُطّيت الشاشة احتياطياً.")
            return
        } finally {
            image.close()
        }

        if (isNearUniformBlack(frame)) {
            frame.recycle()
            inferenceBusy.set(false)
            failClosed("لم تصل صورة شاشة قابلة للتحليل؛ غُطّيت الشاشة احتياطياً.")
            return
        }

        val startedAt = SystemClock.elapsedRealtime()
        try {
            val input = InputImage.fromBitmap(frame, 0)
            val currentSegmenter = segmenter
                ?: throw IllegalStateException("The segmentation engine is unavailable.")
            currentSegmenter.process(input)
                .addOnCompleteListener(inferenceExecutor!!) { task ->
                    try {
                        if (!task.isSuccessful) {
                            failClosed("فشل تحليل الإطار؛ غُطّيت الشاشة حتى عودة المعالجة.")
                            return@addOnCompleteListener
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
                        val latency = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L)
                        lastSuccessfulFrameMs = SystemClock.elapsedRealtime()

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
                            lastInferenceMs = latency,
                            maskCoveragePercent = rendered.coveragePercent,
                            modelReady = true,
                        )
                        overlayController?.updateFilterFrame(rendered.overlay)
                        overlayController?.updateSession(next)
                        SessionStatus.publish(next)
                    } catch (_: Exception) {
                        failClosed("تعذر إنشاء قناع التمويه؛ غُطّيت الشاشة احتياطياً.")
                    } finally {
                        if (!frame.isRecycled) frame.recycle()
                        inferenceBusy.set(false)
                    }
                }
        } catch (_: Exception) {
            if (!frame.isRecycled) frame.recycle()
            inferenceBusy.set(false)
            failClosed("تعذر تشغيل النموذج؛ غُطّيت الشاشة احتياطياً.")
        }
    }

    private fun copyConfidence(mask: SegmentationMask): FloatArray {
        val count = mask.width * mask.height
        require(mask.width > 0 && mask.height > 0 && count <= MAX_MASK_PIXELS) {
            "Invalid model mask dimensions."
        }
        val buffer = mask.buffer.duplicate().order(ByteOrder.nativeOrder())
        buffer.rewind()
        require(buffer.remaining() >= count * Float.SIZE_BYTES) {
            "The model returned an incomplete mask."
        }
        return FloatArray(count) { buffer.float.coerceIn(0f, 1f) }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        require(image.planes.isNotEmpty()) { "Captured frame has no pixel plane." }
        val plane = image.planes[0]
        require(plane.pixelStride >= 4 && plane.rowStride > 0) {
            "Unexpected pixel layout."
        }
        val paddedWidth = plane.rowStride / plane.pixelStride
        require(paddedWidth >= image.width) { "Captured row is shorter than the frame." }

        val padded = Bitmap.createBitmap(
            paddedWidth,
            image.height,
            Bitmap.Config.ARGB_8888,
        )
        return try {
            val buffer = plane.buffer
            buffer.rewind()
            padded.copyPixelsFromBuffer(buffer)
            if (paddedWidth == image.width) {
                padded
            } else {
                Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also {
                    padded.recycle()
                }
            }
        } catch (error: Exception) {
            padded.recycle()
            throw error
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

    private fun cleanup() {
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

        inferenceExecutor?.shutdownNow()
        inferenceExecutor = null
        try {
            segmenter?.close()
        } catch (_: Exception) {
            // Teardown must continue even if the model runtime is already closed.
        } finally {
            segmenter = null
        }

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
        private const val MIN_PERSON_COVERAGE_PERCENT = 1
    }
}

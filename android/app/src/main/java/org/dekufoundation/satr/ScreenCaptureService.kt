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
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock

class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null
    private var overlayController: OverlayController? = null
    private var projectionStopped = false
    private var lastSampleTimeMs = 0L
    private var sampledFrameCount = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            projectionStopped = true
            stopSelf()
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
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = command.getIntExtra(EXTRA_RESULT_CODE, 0)
        val resultData = readProjectionIntent(command)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        startForegroundCompat(buildNotification())

        return try {
            startCaptureSession(
                resultCode = resultCode,
                resultData = resultData,
                width = command.getIntExtra(EXTRA_CAPTURE_WIDTH, 0),
                height = command.getIntExtra(EXTRA_CAPTURE_HEIGHT, 0),
                densityDpi = command.getIntExtra(EXTRA_DENSITY_DPI, resources.displayMetrics.densityDpi),
            )
            START_NOT_STICKY
        } catch (_: Exception) {
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
        check(overlayController?.show() == true) { "The user-visible overlay could not be shown." }

        captureThread = HandlerThread("DekuSatrCapture").apply { start() }
        captureHandler = Handler(captureThread!!.looper)
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                image.close()

                // This milestone proves that frames arrive. It deliberately does not read
                // pixel planes, run inference, persist frames, or send network data.
                val now = SystemClock.elapsedRealtime()
                if (now - lastSampleTimeMs >= MIN_SAMPLE_INTERVAL_MS) {
                    lastSampleTimeMs = now
                    sampledFrameCount += 1
                }
            }, captureHandler)
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
            .setContentTitle("Deku Satr · جلسة التقاط نشطة")
            .setContentText("الطمس الذكي غير مفعّل في هذه النسخة.")
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
        overlayController?.remove()
        overlayController = null

        val currentProjection = projection
        projection = null
        if (currentProjection != null) {
            try {
                currentProjection.unregisterCallback(projectionCallback)
            } catch (_: IllegalArgumentException) {
                // A revoked projection may already have detached its callback.
            }
        }

        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        captureHandler = null
        captureThread?.quitSafely()
        captureThread = null

        if (currentProjection != null && !projectionStopped) {
            try {
                currentProjection.stop()
            } catch (_: RuntimeException) {
                // The system may have stopped the projection already.
            }
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
        const val EXTRA_DENSITY_DPI = "density_dpi"

        private const val CHANNEL_ID = "deku_satr_capture"
        private const val NOTIFICATION_ID = 410
        private const val STOP_PENDING_INTENT_REQUEST = 411
        private const val MIN_SAMPLE_INTERVAL_MS = 100L
    }
}

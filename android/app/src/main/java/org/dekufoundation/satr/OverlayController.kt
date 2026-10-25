package org.dekufoundation.satr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

class OverlayController(
    context: Context,
    private val onStopRequested: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var filterView: FilterSurfaceView? = null
    private var filterParams: WindowManager.LayoutParams? = null
    private var controlPanel: LinearLayout? = null
    private var controlTitle: TextView? = null
    private var controlDetail: TextView? = null
    private var controlButton: TextView? = null
    private var controlParams: WindowManager.LayoutParams? = null
    private var lastPanelUpdateMs = 0L
    private var lastPanelPhase: SatrPhase? = null
    private var lastPanelDetail: String? = null

    fun show(): Boolean {
        if (!Settings.canDrawOverlays(appContext)) return false
        if (filterView != null && controlPanel != null) return true

        val filter = FilterSurfaceView(appContext)
        val filterParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = NORMAL_FILTER_ALPHA
            title = "Deku Satr privacy mask"
        }

        return try {
            windowManager.addView(filter, filterParams)
            filterView = filter
            this.filterParams = filterParams
            addControlPanel()
            true
        } catch (_: SecurityException) {
            remove()
            false
        } catch (_: WindowManager.BadTokenException) {
            remove()
            false
        }
    }

    fun updateFilterFrame(bitmap: Bitmap) {
        mainHandler.post {
            val currentView = filterView
            if (currentView == null) {
                if (!bitmap.isRecycled) bitmap.recycle()
                return@post
            }
            currentView.showMask(bitmap)
            setFilterWindowAlpha(NORMAL_FILTER_ALPHA)
        }
    }

    fun showFailClosed(detail: String) {
        mainHandler.post {
            setFilterWindowAlpha(FAIL_CLOSED_ALPHA)
            filterView?.showFailClosed()
            controlTitle?.text = "ستر بلور · إخفاء احتياطي"
            controlDetail?.text = detail.take(120)
            controlDetail?.setTextColor(Color.rgb(255, 215, 140))
        }
    }

    fun updateSession(snapshot: SatrSessionSnapshot) {
        mainHandler.post {
            val now = SystemClock.elapsedRealtime()
            val phaseChanged = snapshot.phase != lastPanelPhase
            val detailChanged = snapshot.detail != lastPanelDetail
            val refreshPanel = phaseChanged || detailChanged || now - lastPanelUpdateMs >= PANEL_METRICS_REFRESH_MS

            if (phaseChanged || lastPanelPhase == null) {
                controlTitle?.text = when (snapshot.phase) {
                    SatrPhase.STARTING -> "ستر بلور · جارٍ التحضير"
                    SatrPhase.FILTERING -> "ستر بلور · تمويه تجريبي"
                    SatrPhase.NO_PERSON_DETECTED -> "ستر بلور · لا توجد منطقة واضحة"
                    SatrPhase.FAIL_CLOSED -> "ستر بلور · إخفاء احتياطي"
                    SatrPhase.ERROR -> "ستر بلور · حدث خطأ"
                    else -> "ستر بلور · جلسة الالتقاط"
                }
            }

            if (refreshPanel) {
                controlDetail?.text = buildString {
                    append(snapshot.detail.take(70))
                    snapshot.lastInferenceMs?.let { append(" · ").append(it).append(" ms") }
                }
                controlDetail?.setTextColor(
                    if (snapshot.phase == SatrPhase.FAIL_CLOSED || snapshot.phase == SatrPhase.ERROR) {
                        Color.rgb(255, 215, 140)
                    } else {
                        Color.rgb(230, 239, 233)
                    },
                )
                lastPanelUpdateMs = now
            }
            lastPanelPhase = snapshot.phase
            lastPanelDetail = snapshot.detail
            // The mask view is invalidated by updateFilterFrame(); phase-only updates do not need
            // a second invalidation of the full-screen surface.
        }
    }

    fun remove() {
        mainHandler.post {
            val panel = controlPanel
            val filter = filterView
            filter?.dispose()
            try {
                if (panel != null) windowManager.removeView(panel)
            } catch (_: IllegalArgumentException) {
                // Already removed by Android.
            } catch (_: WindowManager.BadTokenException) {
                // Display was torn down.
            }
            try {
                if (filter != null) windowManager.removeView(filter)
            } catch (_: IllegalArgumentException) {
                // Already removed by Android.
            } catch (_: WindowManager.BadTokenException) {
                // Display was torn down.
            } finally {
                controlPanel = null
                filterView = null
                filterParams = null
                controlTitle = null
                controlDetail = null
                controlButton = null
                controlParams = null
            }
        }
    }

    private fun addControlPanel() {
        val panel = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(14), dp(10), dp(14), dp(9))
            background = GradientDrawable().apply {
                setColor(Color.rgb(18, 55, 42))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), Color.rgb(31, 122, 88))
            }
            elevation = dp(9).toFloat()
            contentDescription = "عناصر تحكم جلسة دِكو سَتر"
        }

        val title = TextView(appContext).apply {
            text = "ستر بلور · جارٍ التحضير"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(dp(2), dp(1), dp(2), dp(3))
            contentDescription = "حالة الفلترة؛ اسحب هذا العنوان لتحريك اللوحة"
            setOnTouchListener(DragTouchListener())
        }
        val detail = TextView(appContext).apply {
            text = "لا تغلق هذا المؤشر أثناء الالتقاط"
            textSize = 10.5f
            setTextColor(Color.rgb(230, 239, 233))
            setPadding(dp(2), dp(1), dp(2), dp(5))
        }
        val stop = TextView(appContext).apply {
            text = "إيقاف الجلسة"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            minHeight = dp(48)
            minWidth = dp(112)
            background = GradientDrawable().apply {
                setColor(Color.rgb(157, 53, 56))
                cornerRadius = dp(12).toFloat()
            }
            isFocusable = true
            isClickable = true
            contentDescription = "إيقاف جلسة التقاط الشاشة والفلترة"
            setOnClickListener { onStopRequested() }
        }

        panel.addView(title)
        panel.addView(detail)
        panel.addView(stop)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = dp(10)
            y = dp(106)
            this.title = "Deku Satr controls"
        }

        windowManager.addView(panel, params)
        controlPanel = panel
        controlTitle = title
        controlDetail = detail
        controlButton = stop
        controlParams = params
    }

    private inner class DragTouchListener : View.OnTouchListener {
        private var startRawX = 0f
        private var startRawY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(view: View, event: MotionEvent): Boolean {
            val params = controlParams ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    dragging = true
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return false
                    val dx = (event.rawX - startRawX).roundToInt()
                    val dy = (event.rawY - startRawY).roundToInt()
                    params.x = (startX - dx).coerceAtLeast(0)
                    params.y = (startY + dy).coerceAtLeast(0)
                    try {
                        controlPanel?.let { windowManager.updateViewLayout(it, params) }
                    } catch (_: IllegalArgumentException) {
                        dragging = false
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    return true
                }
            }
            return false
        }
    }

    private fun setFilterWindowAlpha(alpha: Float) {
        val filter = filterView ?: return
        val params = filterParams ?: return
        if (params.alpha == alpha) return
        params.alpha = alpha
        try {
            windowManager.updateViewLayout(filter, params)
        } catch (_: IllegalArgumentException) {
            // The projection or overlay may already have been torn down.
        } catch (_: WindowManager.BadTokenException) {
            // Android may revoke overlay access while capture is active.
        }
    }

    companion object {
        // Android 12+ blocks untrusted touches passing through opaque app overlays.
        // Fail-closed intentionally restores full opacity and may require the user to tap Stop.
        private const val NORMAL_FILTER_ALPHA = 0.8f
        private const val FAIL_CLOSED_ALPHA = 1.0f
        private const val PANEL_METRICS_REFRESH_MS = 900L
    }

    private fun dp(value: Int): Int = (value * appContext.resources.displayMetrics.density).toInt()

    private class FilterSurfaceView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        @Volatile private var maskBitmap: Bitmap? = null
        @Volatile private var failClosed = false

        init {
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "طبقة تغبيش الأشخاص؛ لا تعترض اللمس"
        }

        fun showMask(bitmap: Bitmap) {
            val previous = maskBitmap
            maskBitmap = bitmap
            failClosed = false
            invalidate()
            if (previous != null && previous !== bitmap && !previous.isRecycled) {
                previous.recycle()
            }
        }

        fun showFailClosed() {
            val previous = maskBitmap
            maskBitmap = null
            failClosed = true
            invalidate()
            if (previous != null && !previous.isRecycled) previous.recycle()
        }

        fun dispose() {
            val previous = maskBitmap
            maskBitmap = null
            failClosed = true
            if (previous != null && !previous.isRecycled) previous.recycle()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (failClosed) {
                canvas.drawColor(Color.rgb(18, 55, 42))
                return
            }
            val bitmap = maskBitmap ?: return
            if (bitmap.isRecycled) return
            canvas.drawBitmap(
                bitmap,
                null,
                android.graphics.Rect(0, 0, width, height),
                paint,
            )
        }
    }
}

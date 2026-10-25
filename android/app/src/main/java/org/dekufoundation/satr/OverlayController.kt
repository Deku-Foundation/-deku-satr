package org.dekufoundation.satr

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class OverlayController(
    private val context: Context,
    private val onStopRequested: () -> Unit,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: View? = null

    fun show(): Boolean {
        if (!Settings.canDrawOverlays(context)) return false
        if (root != null) return true

        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.rgb(20, 35, 54))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.rgb(73, 112, 127))
            }
            elevation = dp(8).toFloat()
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        panel.addView(TextView(context).apply {
            text = "دِكو سَتر · الالتقاط نشط"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        panel.addView(TextView(context).apply {
            text = "الطمس الذكي غير مفعّل"
            textSize = 11f
            setTextColor(Color.rgb(255, 215, 140))
            setPadding(0, dp(3), 0, dp(4))
        })
        panel.addView(Button(context).apply {
            text = "إيقاف الجلسة"
            textSize = 12f
            setOnClickListener { onStopRequested() }
        })

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
            y = dp(110)
        }

        return try {
            windowManager.addView(panel, params)
            root = panel
            true
        } catch (_: SecurityException) {
            false
        } catch (_: WindowManager.BadTokenException) {
            false
        }
    }

    fun remove() {
        val view = root ?: return
        try {
            windowManager.removeView(view)
        } catch (_: IllegalArgumentException) {
            // Android can remove overlays when a display/session ends.
        } finally {
            root = null
        }
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}

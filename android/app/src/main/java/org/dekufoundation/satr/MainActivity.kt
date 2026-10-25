package org.dekufoundation.satr

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var startButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
    }

    private fun buildScreen() {
        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(24), dp(28), dp(24), dp(28))
            setBackgroundColor(Color.rgb(246, 248, 251))
        }

        root.addView(TextView(this).apply {
            text = "دِكو سَتر"
            textSize = 28f
            setTextColor(Color.rgb(20, 35, 54))
            typeface = Typeface.DEFAULT_BOLD
        }, matchWidthWrapHeight())

        root.addView(TextView(this).apply {
            text = "حماية بصرية محلية — نسخة Android التجريبية"
            textSize = 16f
            setTextColor(Color.rgb(28, 116, 106))
            setPadding(0, dp(8), 0, dp(18))
        }, matchWidthWrapHeight())

        root.addView(TextView(this).apply {
            text = "هذه المرحلة تختبر موافقة التقاط الشاشة، الخدمة المرئية، والنافذة العائمة عبر التطبيقات. محرك اكتشاف الأشخاص والطمس لم يُفعّل بعد."
            textSize = 15f
            setTextColor(Color.rgb(57, 70, 88))
            setLineSpacing(dp(3).toFloat(), 1.0f)
        }, matchWidthWrapHeight())

        root.addView(TextView(this).apply {
            text = "الخصوصية"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(20, 35, 54))
            setPadding(0, dp(24), 0, dp(8))
        }, matchWidthWrapHeight())

        root.addView(TextView(this).apply {
            text = "لا توجد صلاحية كاميرا أو ميكروفون. جلسة الالتقاط تحتاج موافقتك كل مرة، وتتوقف عند إيقاف المشاركة. هذه النسخة لا تحفظ لقطات الشاشة ولا ترسل صوراً إلى الشبكة."
            textSize = 14f
            setTextColor(Color.rgb(81, 93, 110))
            setLineSpacing(dp(3).toFloat(), 1.0f)
        }, matchWidthWrapHeight())

        root.addView(Button(this).apply {
            text = "السماح بالنافذة العائمة"
            setOnClickListener { requestOverlayPermission() }
        }, matchWidthWrapHeight().apply { topMargin = dp(22) })

        startButton = Button(this).apply {
            text = "بدء جلسة اختبار الالتقاط"
            setOnClickListener { startCaptureFlow() }
        }
        root.addView(startButton, matchWidthWrapHeight().apply { topMargin = dp(8) })

        root.addView(Button(this).apply {
            text = "إيقاف الجلسة"
            setOnClickListener {
                startService(Intent(this@MainActivity, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_STOP
                })
                setStatus("طُلب إيقاف الجلسة.")
            }
        }, matchWidthWrapHeight().apply { topMargin = dp(8) })

        statusText = TextView(this).apply {
            text = "الحالة: متوقف. لا توجد حماية فعالة الآن."
            textSize = 14f
            setTextColor(Color.rgb(133, 80, 22))
            setPadding(0, dp(18), 0, 0)
            setLineSpacing(dp(3).toFloat(), 1.0f)
        }
        root.addView(statusText, matchWidthWrapHeight())

        root.addView(TextView(this).apply {
            text = "تنبيه مهم: قد تفشل بعض التطبيقات أو الفيديوهات المحمية أو الشاشات الآمنة في الالتقاط. لا تعتبر هذه الأداة ضماناً كاملاً."
            textSize = 13f
            setTextColor(Color.rgb(105, 113, 124))
            setPadding(0, dp(18), 0, 0)
            setLineSpacing(dp(3).toFloat(), 1.0f)
        }, matchWidthWrapHeight())

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun startCaptureFlow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            setStatus("اسمح بالإشعارات أولاً حتى تظهر جلسة الالتقاط وإمكانية إيقافها.")
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            setStatus("اسمح بالنافذة العائمة ثم ارجع واضغط بدء الجلسة.")
            requestOverlayPermission()
            return
        }

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        @Suppress("DEPRECATION")
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            setStatus("إذن النافذة العائمة متاح. يمكنك بدء جلسة الالتقاط.")
            return
        }
        startActivity(Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + packageName),
        ))
    }

    @Deprecated("Platform activity-result callback kept compatible with the minimum SDK.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            setStatus("لم تمنح إذن التقاط الشاشة. لم تبدأ أي جلسة.")
            return
        }

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val size = CaptureSizePolicy.downsample(metrics.widthPixels, metrics.heightPixels)
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenCaptureService.EXTRA_CAPTURE_WIDTH, size.width)
            putExtra(ScreenCaptureService.EXTRA_CAPTURE_HEIGHT, size.height)
            putExtra(ScreenCaptureService.EXTRA_DENSITY_DPI, metrics.densityDpi)
        }
        try {
            startForegroundService(serviceIntent)
            setStatus("أُرسل طلب البدء. تظهر علامة الالتقاط فقط؛ الطمس الذكي غير مفعّل.")
        } catch (error: Exception) {
            setStatus("تعذر بدء الخدمة: " + error.javaClass.simpleName + ". لم يتم تطبيق أي طمس.")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_NOTIFICATIONS) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startCaptureFlow()
        } else {
            setStatus("لا تبدأ الجلسة من دون السماح بالإشعارات؛ يلزم وجود مؤشر واضح وإمكانية الإيقاف.")
        }
    }

    override fun onResume() {
        super.onResume()
        if (::statusText.isInitialized && Settings.canDrawOverlays(this)) {
            startButton.isEnabled = true
        }
    }

    private fun setStatus(message: String) {
        if (::statusText.isInitialized) statusText.text = "الحالة: " + message
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun matchWidthWrapHeight(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    companion object {
        private const val REQUEST_CAPTURE = 4101
        private const val REQUEST_NOTIFICATIONS = 4102
    }
}

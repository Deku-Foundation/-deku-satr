package org.dekufoundation.satr

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private var snapshot by mutableStateOf(SessionStatus.current())
    private var overlayAllowed by mutableStateOf(false)
    private var notificationsAllowed by mutableStateOf(true)
    private var unsubscribeStatus: (() -> Unit)? = null
    private var postPermissionContinuation: (() -> Unit)? = null

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            if (result.resultCode != Activity.RESULT_OK || data == null) {
                SessionStatus.publish(
                    SatrSessionSnapshot(
                        phase = SatrPhase.STOPPED,
                        detail = "لم تمنح إذن التقاط الشاشة؛ لم تبدأ الفلترة.",
                    ),
                )
                return@registerForActivityResult
            }
            startCaptureService(result.resultCode, data)
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notificationsAllowed = granted || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
            if (granted) {
                postPermissionContinuation?.invoke()
            } else {
                SessionStatus.publish(
                    SatrSessionSnapshot(
                        phase = SatrPhase.WAITING_PERMISSION,
                        detail = "يحتاج التطبيق إلى مؤشر إشعار ظاهر حتى تتمكن من إيقاف الجلسة.",
                    ),
                )
            }
            postPermissionContinuation = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        overlayAllowed = Settings.canDrawOverlays(this)
        notificationsAllowed =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        setContent {
            SatrTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    SatrDashboard(
                        snapshot = snapshot,
                        overlayAllowed = overlayAllowed,
                        notificationsAllowed = notificationsAllowed,
                        onGrantOverlay = ::requestOverlayPermission,
                        onStart = ::beginCaptureFlow,
                        onStop = ::requestStop,
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        unsubscribeStatus = SessionStatus.subscribe { value ->
            runOnUiThread { snapshot = value }
        }
    }

    override fun onStop() {
        unsubscribeStatus?.invoke()
        unsubscribeStatus = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        overlayAllowed = Settings.canDrawOverlays(this)
        notificationsAllowed =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            overlayAllowed = true
            return
        }
        SessionStatus.publish(
            SatrSessionSnapshot(
                phase = SatrPhase.WAITING_PERMISSION,
                detail = "اسمح بالنافذة العائمة ثم ارجع واضغط بدء الجلسة.",
            ),
        )
        val permissionIntent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        runCatching { startActivity(permissionIntent) }
            .onFailure {
                SessionStatus.publish(
                    SatrSessionSnapshot(
                        phase = SatrPhase.ERROR,
                        detail = "تعذر فتح إعداد إذن النافذة العائمة.",
                    ),
                )
            }
    }

    private fun beginCaptureFlow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            postPermissionContinuation = ::beginCaptureFlow
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        notificationsAllowed = true

        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        SessionStatus.publish(
            SatrSessionSnapshot(
                phase = SatrPhase.WAITING_PERMISSION,
                detail = "وافق على مشاركة الشاشة في نافذة Android التالية لبدء الجلسة.",
            ),
        )
        captureLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
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

        SessionStatus.publish(
            SatrSessionSnapshot(
                phase = SatrPhase.STARTING,
                detail = "يجري بدء الالتقاط وتحميل النموذج المضمّن.",
            ),
        )
        try {
            startForegroundService(serviceIntent)
        } catch (_: Exception) {
            SessionStatus.publish(
                SatrSessionSnapshot(
                    phase = SatrPhase.ERROR,
                    detail = "تعذر بدء خدمة التقاط الشاشة. لم تُطبّق الفلترة.",
                ),
            )
        }
    }

    private fun requestStop() {
        try {
            startService(Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_STOP
            })
        } catch (_: Exception) {
            SessionStatus.publish(
                SatrSessionSnapshot(
                    phase = SatrPhase.ERROR,
                    detail = "تعذر إرسال أمر الإيقاف؛ أوقف مشاركة الشاشة من إشعار Android أيضاً.",
                ),
            )
        }
    }
}

private val SatrLightColors = lightColorScheme(
    primary = Color(0xFF116F68),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD0F0EB),
    onPrimaryContainer = Color(0xFF062E2C),
    secondary = Color(0xFF263F55),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE9F4),
    onSecondaryContainer = Color(0xFF142B3D),
    background = Color(0xFFF3F7F7),
    onBackground = Color(0xFF14242E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14242E),
    surfaceVariant = Color(0xFFE7EFEF),
    onSurfaceVariant = Color(0xFF52666B),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

private val SatrDarkColors = darkColorScheme(
    primary = Color(0xFF83D8CB),
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF07534D),
    onPrimaryContainer = Color(0xFFB0F2E7),
    secondary = Color(0xFFB8CFE1),
    onSecondary = Color(0xFF223747),
    secondaryContainer = Color(0xFF304A5B),
    onSecondaryContainer = Color(0xFFD4E9F9),
    background = Color(0xFF0C171D),
    onBackground = Color(0xFFE0E9EA),
    surface = Color(0xFF13232B),
    onSurface = Color(0xFFE0E9EA),
    surfaceVariant = Color(0xFF20343B),
    onSurfaceVariant = Color(0xFFB5C8CA),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

@Composable
private fun SatrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) SatrDarkColors else SatrLightColors,
        content = content,
    )
}

@Composable
private fun SatrDashboard(
    snapshot: SatrSessionSnapshot,
    overlayAllowed: Boolean,
    notificationsAllowed: Boolean,
    onGrantOverlay: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val running = snapshot.phase in setOf(
        SatrPhase.STARTING,
        SatrPhase.FILTERING,
        SatrPhase.NO_PERSON_DETECTED,
        SatrPhase.FAIL_CLOSED,
    )
    var blurStrength by remember { mutableFloatStateOf(FilterSettings.blurRadius.toFloat()) }
    val scrollState = rememberScrollState()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top))
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom))
                .verticalScroll(scrollState)
                .padding(horizontal = 18.dp)
                .padding(top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SatrHeader()
            HeroPanel()
            SessionCard(snapshot)
            ControlCard(
                running = running,
                snapshot = snapshot,
                blurStrength = blurStrength,
                onBlurStrengthChange = {
                    blurStrength = it
                    FilterSettings.blurRadius = it.roundToInt()
                },
                onStart = onStart,
                onStop = onStop,
            )
            PermissionsCard(
                overlayAllowed = overlayAllowed,
                notificationsAllowed = notificationsAllowed,
                onGrantOverlay = onGrantOverlay,
            )
            ModelCard(snapshot)
            SafetyCard()
            HowItWorksCard()
            Text(
                text = "دِكو سَتر · معالجة محلية · إصدار تجريبي 0.3.0",
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun SatrHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.ic_satr),
                contentDescription = "شعار دِكو سَتر",
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    "دِكو سَتر",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    "حماية بصرية محلية",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        StatusPill(
            label = "على الجهاز",
            container = MaterialTheme.colorScheme.primaryContainer,
            foreground = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun HeroPanel() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF112B3A), Color(0xFF176E66), Color(0xFF1B8278)),
                ),
            )
            .padding(horizontal = 22.dp, vertical = 22.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(37.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.13f)),
                    contentAlignment = Alignment.Center,
                ) {
                    ShieldGlyph(Modifier.size(21.dp), Color(0xFFB9F1E7))
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    "جلسة اختيارية تتحكم بها أنت",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFFB9F1E7),
                )
            }
            Text(
                text = "ستر بصري\nبقرارٍ واضح",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                lineHeight = 35.sp,
            )
            Text(
                text = "يعالج التطبيق إطار الشاشة محلياً ثم يرسم تمويهاً تجريبياً على المناطق التي يرصدها النموذج.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFE2F1F1),
                lineHeight = 23.sp,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeroTag("نموذج مضمّن")
                HeroTag("لا رفع للصور")
                HeroTag("إيقاف فوري")
            }
        }
    }
}

@Composable
private fun HeroTag(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = Color.White.copy(alpha = 0.13f),
        contentColor = Color.White,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SessionCard(snapshot: SatrSessionSnapshot) {
    val running = snapshot.phase in setOf(
        SatrPhase.STARTING,
        SatrPhase.FILTERING,
        SatrPhase.NO_PERSON_DETECTED,
        SatrPhase.FAIL_CLOSED,
    )
    val (label, statusColor, statusFill) = when (snapshot.phase) {
        SatrPhase.STARTING, SatrPhase.WAITING_PERMISSION ->
            Triple("قيد التجهيز", Color(0xFF9A5C08), Color(0xFFFFF0D1))
        SatrPhase.FILTERING ->
            Triple("تمويه تجريبي", Color(0xFF126B61), Color(0xFFDDF6EE))
        SatrPhase.NO_PERSON_DETECTED ->
            Triple("لم يرصد شخصاً بوضوح", Color(0xFF8A5A04), Color(0xFFFFF0D1))
        SatrPhase.FAIL_CLOSED ->
            Triple("إخفاء احتياطي", Color(0xFFB3261E), Color(0xFFFFE2DE))
        SatrPhase.ERROR ->
            Triple("تعذر البدء", Color(0xFFB3261E), Color(0xFFFFE2DE))
        SatrPhase.STOPPED ->
            Triple("متوقف", MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceVariant)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Text("حالة الجلسة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "لا تظهر «حماية فعّالة» لمجرد أن الالتقاط بدأ.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusPill(label, statusFill, statusColor)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MetricCard(
                    modifier = Modifier.weight(1f),
                    label = "إطارات محللة",
                    value = snapshot.processedFrames.toString(),
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    label = "زمن آخر إطار",
                    value = snapshot.lastInferenceMs?.let { "$it ms" } ?: "—",
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    label = "مساحة القناع",
                    value = snapshot.maskCoveragePercent?.let { "$it%" } ?: "—",
                )
            }

            if (snapshot.phase == SatrPhase.STARTING || snapshot.phase == SatrPhase.WAITING_PERMISSION) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Text(
                text = snapshot.detail,
                style = MaterialTheme.typography.bodyMedium,
                color = when (snapshot.phase) {
                    SatrPhase.FAIL_CLOSED, SatrPhase.ERROR -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                lineHeight = 21.sp,
            )

            if (running) {
                Text(
                    "الجلسة تجريبية: قد يفوت النموذج أشخاصاً أو يترك أجزاءً غير مطموسة.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun MetricCard(modifier: Modifier = Modifier, label: String, value: String) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 2,
                lineHeight = 15.sp,
            )
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ControlCard(
    running: Boolean,
    snapshot: SatrSessionSnapshot,
    blurStrength: Float,
    onBlurStrengthChange: (Float) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("التحكم بالفلترة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "ابدأ من هنا، وستظل نافذة الإيقاف ظاهرة فوق التطبيقات أثناء الجلسة.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp,
            )

            if (!running) {
                Button(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(17.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    ShieldGlyph(Modifier.size(19.dp), MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(10.dp))
                    Text("بدء الجلسة التجريبية", fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(17.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("إيقاف الجلسة الآن", fontWeight = FontWeight.Bold)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("قوة التمويه", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text("تُطبّق عند الإطار التالي", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusPill(
                    label = blurStrength.roundToInt().toString(),
                    container = MaterialTheme.colorScheme.primaryContainer,
                    foreground = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Slider(
                value = blurStrength,
                onValueChange = onBlurStrengthChange,
                valueRange = FilterSettings.MIN_BLUR_RADIUS.toFloat()..FilterSettings.MAX_BLUR_RADIUS.toFloat(),
                steps = FilterSettings.MAX_BLUR_RADIUS - FilterSettings.MIN_BLUR_RADIUS - 1,
                enabled = running,
            )
            Text(
                if (running) "تُحدّث القوة في الذاكرة ولا تحفظ لقطات الشاشة."
                else "يمكن ضبط القوة أثناء الجلسة بعد منح الأذونات.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PermissionsCard(
    overlayAllowed: Boolean,
    notificationsAllowed: Boolean,
    onGrantOverlay: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("الأذونات والخصوصية", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            PermissionLine(
                title = "النافذة العائمة",
                detail = if (overlayAllowed) "مسموح بها" else "يلزم السماح من إعدادات Android",
                granted = overlayAllowed,
            )
            PermissionLine(
                title = "إشعار الجلسة",
                detail = if (notificationsAllowed) "مسموح أو غير مطلوب على هذا الإصدار" else "يلزم السماح لإظهار الإيقاف",
                granted = notificationsAllowed,
            )
            PermissionLine(
                title = "التقاط الشاشة",
                detail = "يطلب Android موافقتك عند بدء كل جلسة",
                granted = false,
                neutral = true,
            )
            if (!overlayAllowed) {
                OutlinedButton(
                    onClick = onGrantOverlay,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(15.dp),
                ) {
                    Text("السماح بالنافذة العائمة")
                }
            }
        }
    }
}

@Composable
private fun PermissionLine(
    title: String,
    detail: String,
    granted: Boolean,
    neutral: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(
                    when {
                        neutral -> MaterialTheme.colorScheme.surfaceVariant
                        granted -> Color(0xFFDDF6EE)
                        else -> Color(0xFFFFF0D1)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                when {
                    neutral -> "i"
                    granted -> "✓"
                    else -> "!"
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = when {
                    neutral -> MaterialTheme.colorScheme.onSurfaceVariant
                    granted -> Color(0xFF126B61)
                    else -> Color(0xFF8A5A04)
                },
            )
        }
        Spacer(Modifier.width(11.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ModelCard(snapshot: SatrSessionSnapshot) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(43.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    NeuralGlyph(Modifier.size(23.dp), MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(11.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("نموذج الأشخاص", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("ML Kit Selfie Segmentation", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusPill(
                    label = if (snapshot.modelReady) "جاهز" else "مضمّن",
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    foreground = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusPill("مضمّن داخل APK", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                StatusPill("Beta · 16.0.0-beta6", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "حزمة النموذج تزيد حجم التطبيق بنحو 4.5 MB وفق وثائق Google. لا يلزم تنزيل أوزان النموذج عند التشغيل، ولا تُطلب صلاحية الإنترنت من التطبيق.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp,
            )
        }
    }
}

@Composable
private fun SafetyCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF0D1)),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(28.dp).clip(CircleShape).background(Color(0xFF8A5A04)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("!", color = Color.White, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(9.dp))
                Text("حدود النموذج", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFF613E00))
            }
            Text(
                "هذا نموذج تقسيم أشخاص موجّه أساساً للصور الشخصية والفيديو. قد يفوت الأشخاص الصغار أو البعيدين أو المحجوبين، وقد لا يمكن التقاط بعض النوافذ الآمنة أو فيديو DRM. لا تعتبره ضماناً كاملاً للستر.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF613E00),
                lineHeight = 22.sp,
            )
            Text(
                "لتمرير اللمس في Android 12 وما بعده، تكون طبقة التمويه العادية بعتامة 80%؛ قد يبقى أثر من الصورة الأصلية. عند فشل التحليل، تصبح التغطية معتمة بالكامل وقد تمنع لمس التطبيق أسفلها حتى تضغط إيقاف.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF613E00),
                lineHeight = 20.sp,
            )
        }
    }
}

@Composable
private fun HowItWorksCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Text("كيف تعمل الجلسة؟", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            StepLine("١", "توافق على مشاركة الشاشة من نافذة Android.")
            StepLine("٢", "يحلّل نموذج مضمّن إطاراً منخفض الدقة محلياً.")
            StepLine("٣", "تُرسم طبقة تمويه فوق المناطق المكتشفة مع زر إيقاف ظاهر.")
            Text(
                "لا تحفظ الإطارات أو الأقنعة في ملفات. إذا فشل التحليل، يحاول التطبيق تغطية الشاشة مؤقتاً حتى تعود المعالجة أو توقف الجلسة.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp,
            )
        }
    }
}

@Composable
private fun StepLine(number: String, title: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier.size(30.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(number, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(11.dp))
        Text(
            title,
            modifier = Modifier.padding(top = 4.dp).weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            lineHeight = 21.sp,
        )
    }
}

@Composable
private fun StatusPill(label: String, container: Color, foreground: Color) {
    Surface(
        shape = RoundedCornerShape(50),
        color = container,
        contentColor = foreground,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun ShieldGlyph(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier) {
        val path = Path().apply {
            moveTo(size.width * 0.5f, size.height * 0.08f)
            lineTo(size.width * 0.86f, size.height * 0.22f)
            lineTo(size.width * 0.81f, size.height * 0.62f)
            quadraticTo(size.width * 0.73f, size.height * 0.82f, size.width * 0.5f, size.height * 0.94f)
            quadraticTo(size.width * 0.27f, size.height * 0.82f, size.width * 0.19f, size.height * 0.62f)
            lineTo(size.width * 0.14f, size.height * 0.22f)
            close()
        }
        drawPath(path, color = color, style = Stroke(width = size.minDimension * 0.11f, cap = StrokeCap.Round))
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(size.width * 0.33f, size.height * 0.5f),
            end = androidx.compose.ui.geometry.Offset(size.width * 0.46f, size.height * 0.63f),
            strokeWidth = size.minDimension * 0.1f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(size.width * 0.46f, size.height * 0.63f),
            end = androidx.compose.ui.geometry.Offset(size.width * 0.69f, size.height * 0.39f),
            strokeWidth = size.minDimension * 0.1f,
            cap = StrokeCap.Round,
        )
    }
}

@Composable
private fun NeuralGlyph(modifier: Modifier = Modifier, color: Color) {
    Canvas(modifier) {
        val points = listOf(
            androidx.compose.ui.geometry.Offset(size.width * 0.25f, size.height * 0.25f),
            androidx.compose.ui.geometry.Offset(size.width * 0.75f, size.height * 0.25f),
            androidx.compose.ui.geometry.Offset(size.width * 0.5f, size.height * 0.5f),
            androidx.compose.ui.geometry.Offset(size.width * 0.25f, size.height * 0.75f),
            androidx.compose.ui.geometry.Offset(size.width * 0.75f, size.height * 0.75f),
        )
        drawLine(color, points[0], points[2], strokeWidth = 2.dp.toPx())
        drawLine(color, points[1], points[2], strokeWidth = 2.dp.toPx())
        drawLine(color, points[2], points[3], strokeWidth = 2.dp.toPx())
        drawLine(color, points[2], points[4], strokeWidth = 2.dp.toPx())
        points.forEach { drawCircle(color, radius = size.minDimension * 0.105f, center = it) }
    }
}

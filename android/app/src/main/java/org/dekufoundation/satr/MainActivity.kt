package org.dekufoundation.satr

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private var snapshot by mutableStateOf(SessionStatus.current())
    private var overlayAllowed by mutableStateOf(false)
    private var notificationsAllowed by mutableStateOf(true)
    private var showWelcome by mutableStateOf(false)
    private var processedImage by mutableStateOf<Bitmap?>(null)
    private var mediaBusy by mutableStateOf(false)
    private var mediaMessage by mutableStateOf("اختر صورة لتحليلها محلياً.")
    private var mediaCoverage by mutableIntStateOf(0)
    private var pendingExportBitmap: Bitmap? = null
    private val mediaExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "SatrMediaProcessor").apply { isDaemon = true } }
    private var unsubscribeStatus: (() -> Unit)? = null
    private var pendingUiSnapshot: SatrSessionSnapshot? = null
    private var uiFlushScheduled = false
    private var lastUiRenderMs = 0L
    private val uiHandler = Handler(Looper.getMainLooper())
    private val flushPendingSnapshot = Runnable {
        uiFlushScheduled = false
        pendingUiSnapshot?.let { snapshot = it }
        pendingUiSnapshot = null
        lastUiRenderMs = SystemClock.elapsedRealtime()
    }

    private val imagePicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) processPickedImage(uri) }
    private val exportPicker = registerForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        val bitmap = pendingExportBitmap
        pendingExportBitmap = null
        if (uri == null || bitmap == null) mediaMessage = "لم تُحفظ نسخة؛ بقي الأصل كما هو."
        else {
            mediaMessage = "يجري حفظ النسخة…"
            mediaExecutor.execute {
                val saved = runCatching { contentResolver.openOutputStream(uri)?.use { output -> check(bitmap.compress(Bitmap.CompressFormat.JPEG, 94, output)) } ?: error("Destination unavailable.") }
                runOnUiThread { mediaMessage = if (saved.isSuccess) "حُفظت نسخة معالجة؛ بقي الأصل دون تغيير." else "تعذر حفظ النسخة. اختر موقعاً آخر وحاول مجدداً." }
            }
        }
    }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        FilterSettings.initialize(applicationContext)
        showWelcome = !getSharedPreferences("satr_ui", Context.MODE_PRIVATE).getBoolean("welcome_seen", false)
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
                        onOpenNotificationSettings = ::openNotificationSettings,
                        onSaveFilterSettings = ::saveFilterSettings,
                        onStart = ::beginCaptureFlow,
                        onStop = ::requestStop,
                        onUpdateTarget = ::updatePersonTarget,
                        processedImage = processedImage,
                        mediaBusy = mediaBusy,
                        mediaMessage = mediaMessage,
                        mediaCoverage = mediaCoverage,
                        onPickImage = ::pickImage,
                        onSaveImage = ::saveProcessedImage,
                        showWelcome = showWelcome,
                        onWelcomeDone = ::completeWelcome,
                        onOpenSource = ::openSource,
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        unsubscribeStatus = SessionStatus.subscribe(::publishSnapshotToUi)
    }

    override fun onDestroy() {
        mediaExecutor.shutdownNow()
        processedImage?.let { if (!it.isRecycled) it.recycle() }
        processedImage = null
        super.onDestroy()
    }

    override fun onStop() {
        unsubscribeStatus?.invoke()
        unsubscribeStatus = null
        uiHandler.removeCallbacks(flushPendingSnapshot)
        uiFlushScheduled = false
        pendingUiSnapshot = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        overlayAllowed = Settings.canDrawOverlays(this)
        notificationsAllowed =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun publishSnapshotToUi(value: SatrSessionSnapshot) {
        uiHandler.post {
            val now = SystemClock.elapsedRealtime()
            val current = snapshot
            val phaseOrErrorChanged = value.phase != current.phase ||
                value.detail != current.detail ||
                value.modelReady != current.modelReady

            if (phaseOrErrorChanged || now - lastUiRenderMs >= UI_METRICS_REFRESH_MS) {
                uiHandler.removeCallbacks(flushPendingSnapshot)
                uiFlushScheduled = false
                pendingUiSnapshot = null
                snapshot = value
                lastUiRenderMs = now
                return@post
            }

            pendingUiSnapshot = value
            if (!uiFlushScheduled) {
                uiFlushScheduled = true
                val delay = (UI_METRICS_REFRESH_MS - (now - lastUiRenderMs)).coerceAtLeast(1L)
                uiHandler.postDelayed(flushPendingSnapshot, delay)
            }
        }
    }

    private fun completeWelcome() {
        getSharedPreferences("satr_ui", Context.MODE_PRIVATE).edit().putBoolean("welcome_seen", true).apply()
        showWelcome = false
    }

    private fun openSource() {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Deku-Foundation/deku-satr"))) }
            .onFailure { mediaMessage = "تعذر فتح صفحة المشروع؛ يمكنك زيارة مستودع Deku-Foundation/deku-satr." }
    }

    private fun pickImage() { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    private fun saveProcessedImage() {
        val bitmap = processedImage
        if (bitmap == null || mediaBusy) { mediaMessage = "اختر صورة وانتظر اكتمال المعالجة قبل الحفظ."; return }
        pendingExportBitmap = bitmap
        exportPicker.launch("satr-blur-${System.currentTimeMillis()}.jpg")
    }

    private fun processPickedImage(uri: Uri) {
        mediaBusy = true
        mediaMessage = "يجري تحليل الصورة على الهاتف…"
        mediaExecutor.execute {
            val result = runCatching { ImagePrivacyProcessor.process(this, uri, FilterSettings.effect, FilterSettings.blurRadius, FilterSettings.confidenceThreshold, FilterSettings.maskExpansionRadius) }
            runOnUiThread {
                mediaBusy = false
                result.onSuccess { output ->
                    val previous = processedImage
                    processedImage = output.bitmap
                    mediaCoverage = output.coveragePercent
                    mediaMessage = if (output.coveragePercent > 0) "اكتمل الفحص محلياً. راجع الحواف قبل مشاركة الصورة." else "لم يرصد النموذج شخصاً بوضوح؛ قد تكون الصورة غير مغطاة بالكامل."
                    if (previous != null && previous !== output.bitmap) uiHandler.postDelayed({ if (processedImage !== previous && !previous.isRecycled) previous.recycle() }, 700L)
                }.onFailure { mediaMessage = "تعذر تحليل الصورة. جرّب صورة أوضح؛ لم يُعدّل الملف الأصلي." }
            }
        }
    }

    private fun saveFilterSettings() {
        FilterSettings.persist(this)
    }

    private fun updatePersonTarget(mode: PersonTargetMode, selectedIds: Set<Long>) {
        val safeMode = if (mode == PersonTargetMode.MASK_SELECTED_ONLY && selectedIds.isEmpty()) {
            PersonTargetMode.MASK_ALL
        } else mode
        startService(Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_UPDATE_TARGETS
            putExtra(ScreenCaptureService.EXTRA_TARGET_MODE, safeMode.name)
            putExtra(ScreenCaptureService.EXTRA_SELECTED_TRACK_IDS, selectedIds.joinToString(","))
        })
    }

    private fun openNotificationSettings() {
        val notificationIntent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        }
        runCatching { startActivity(notificationIntent) }
            .onFailure {
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
                    )
                }
            }
    }

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            overlayAllowed = true
            return
        }
        SessionStatus.publish(
            SatrSessionSnapshot(
                phase = SatrPhase.STOPPED,
                detail = "فعّل إذن النافذة العائمة ثم ارجع واضغط بدء التمويه.",
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
        notificationsAllowed =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        // POST_NOTIFICATIONS is not a prerequisite for MediaProjection/foreground-service startup.
        // If denied, Android may omit the drawer entry; keep the in-app/overlay Stop controls.
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        SessionStatus.publish(
            SatrSessionSnapshot(
                phase = SatrPhase.WAITING_PERMISSION,
                detail = "وافق على التقاط الشاشة كاملة في نافذة Android لفلترة المحتوى عبر التطبيقات.",
            ),
        )
        // Cross-app filtering needs the whole display, not one selected app window.
        // Android still shows its normal consent UI before capture begins.
        val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        captureLauncher.launch(captureIntent)
    }

    private fun startCaptureService(resultCode: Int, data: Intent) {
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val displayWidth: Int
        val displayHeight: Int
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            displayWidth = bounds.width()
            displayHeight = bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            displayWidth = metrics.widthPixels
            displayHeight = metrics.heightPixels
        }
        val size = CaptureSizePolicy.downsample(displayWidth, displayHeight, FilterSettings.processingProfile.maxEdge)
        val densityDpi = resources.configuration.densityDpi
        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenCaptureService.EXTRA_CAPTURE_WIDTH, size.width)
            putExtra(ScreenCaptureService.EXTRA_CAPTURE_HEIGHT, size.height)
            putExtra(ScreenCaptureService.EXTRA_DENSITY_DPI, densityDpi)
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
                    detail = "تعذر إرسال أمر الإيقاف؛ استخدم زر الإيقاف العائم أو إشعار Android.",
                ),
            )
        }
    }

    private companion object {
        const val UI_METRICS_REFRESH_MS = 900L
    }
}

private val SatrLightColors = lightColorScheme(
    primary = Color(0xFF1F7A58),
    onPrimary = Color(0xFFF4F7F3),
    primaryContainer = Color(0xFFDCEBDD),
    onPrimaryContainer = Color(0xFF12372A),
    secondary = Color(0xFFD9B45A),
    onSecondary = Color(0xFF12372A),
    secondaryContainer = Color(0xFFF3E7C1),
    onSecondaryContainer = Color(0xFF12372A),
    background = Color(0xFFF4F7F3),
    onBackground = Color(0xFF12372A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF12372A),
    surfaceVariant = Color(0xFFE6EFE9),
    onSurfaceVariant = Color(0xFF4A6358),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

private val SatrDarkColors = darkColorScheme(
    primary = Color(0xFFD9B45A),
    onPrimary = Color(0xFF12372A),
    primaryContainer = Color(0xFF1F7A58),
    onPrimaryContainer = Color(0xFFF4F7F3),
    secondary = Color(0xFFA8D5BE),
    onSecondary = Color(0xFF12372A),
    secondaryContainer = Color(0xFF294C3A),
    onSecondaryContainer = Color(0xFFE6EFE9),
    background = Color(0xFF0D1913),
    onBackground = Color(0xFFEAF2EC),
    surface = Color(0xFF15271D),
    onSurface = Color(0xFFEAF2EC),
    surfaceVariant = Color(0xFF243B2D),
    onSurfaceVariant = Color(0xFFB8CBBB),
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
 snapshot:SatrSessionSnapshot, overlayAllowed:Boolean, notificationsAllowed:Boolean,
 onGrantOverlay:()->Unit, onOpenNotificationSettings:()->Unit, onSaveFilterSettings:()->Unit,
 onStart:()->Unit, onStop:()->Unit, onUpdateTarget:(PersonTargetMode, Set<Long>)->Unit,
 processedImage:Bitmap?, mediaBusy:Boolean, mediaMessage:String,
 mediaCoverage:Int, onPickImage:()->Unit, onSaveImage:()->Unit,
 showWelcome:Boolean, onWelcomeDone:()->Unit, onOpenSource:()->Unit,
) {
 val running=snapshot.phase in setOf(SatrPhase.STARTING,SatrPhase.FILTERING,SatrPhase.NO_PERSON_DETECTED,SatrPhase.FAIL_CLOSED)
 var selectedTab by rememberSaveable { mutableIntStateOf(if (showWelcome) -1 else 0) }
 var blurStrength by rememberSaveable { mutableFloatStateOf(FilterSettings.blurStrengthPercent.toFloat()) }
 var confidenceThreshold by rememberSaveable { mutableFloatStateOf(FilterSettings.confidenceThreshold) }
 var maskExpansion by rememberSaveable { mutableFloatStateOf(FilterSettings.maskExpansionRadius.toFloat()) }
 var effectName by rememberSaveable { mutableStateOf(FilterSettings.effect.name) }
 var profileName by rememberSaveable { mutableStateOf(FilterSettings.processingProfile.name) }
 val effect=runCatching { PrivacyEffect.valueOf(effectName) }.getOrDefault(PrivacyEffect.BLUR)
 val profile=runCatching { ProcessingProfile.valueOf(profileName) }.getOrDefault(ProcessingProfile.BALANCED)
 val scrollState=rememberScrollState()
 Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.background) {
  Scaffold(containerColor=MaterialTheme.colorScheme.background,bottomBar={
   if (selectedTab >= 0) NavigationBar { listOf("الرئيسية","المعرض","المحرر","الإعدادات").forEachIndexed { i,label ->
    NavigationBarItem(selected=selectedTab==i,onClick={selectedTab=i},icon={Text(listOf("⌂","▦","✎","⚙")[i],style=MaterialTheme.typography.titleLarge)},label={Text(label,maxLines=1)})
   } }
  }) { padding ->
   Box(Modifier.fillMaxSize().padding(padding),contentAlignment=Alignment.TopCenter) {
    Column(Modifier.widthIn(max=680.dp).fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top)).verticalScroll(scrollState).padding(horizontal=18.dp).padding(top=12.dp,bottom=24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
     SatrHeader()
     when(selectedTab) {
      -1 -> WelcomeScreen(onContinue = { selectedTab = 0; onWelcomeDone() }, onOpenSource = onOpenSource)
      0 -> {
       Text("الرئيسية",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
       HeroPanel(); SessionCard(snapshot)
       ControlCard(running,snapshot.phase==SatrPhase.WAITING_PERMISSION,onStart,onStop)
       Text("تُعالج إطارات الشاشة على الهاتف. لا تُحفظ لقطات الشاشة.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.fillMaxWidth(),textAlign=TextAlign.Center)
      }
      1 -> {
       Text("المعرض",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
       Text("افحص صورة تختارها بنفسك. المعالجة محلية ولا تستبدل الملف الأصلي.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
       MediaPreviewCard(processedImage,mediaBusy,mediaMessage,mediaCoverage)
       Button(onClick=onPickImage,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),shape=RoundedCornerShape(16.dp)){Text("اختيار صورة")}
       OutlinedButton(onClick={selectedTab=2},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),shape=RoundedCornerShape(16.dp)){Text("فتح المحرر")}
       OutlinedButton(onClick=onStart,modifier=Modifier.fillMaxWidth().heightIn(min=52.dp),shape=RoundedCornerShape(16.dp)){Text("حماية الفيديو والمحتوى أثناء العرض")}
       Text("تُفلتر الفيديوهات والتطبيقات الأخرى أثناء عرضها عبر جلسة حماية الشاشة. اختيار صورة هنا للتحرير المنفصل.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
      }
      2 -> {
       Text("المحرر",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
       Text("اختر صورة، عاين النتيجة، ثم احفظ نسخة في موقع تختاره. يبقى الأصل دون تغيير.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
       MediaPreviewCard(processedImage,mediaBusy,mediaMessage,mediaCoverage)
       Row(horizontalArrangement=Arrangement.spacedBy(10.dp),modifier=Modifier.fillMaxWidth()){
        Button(onClick=onPickImage,modifier=Modifier.weight(1f).heightIn(min=52.dp),shape=RoundedCornerShape(16.dp)){Text("اختيار صورة")}
        OutlinedButton(onClick=onSaveImage,enabled=processedImage!=null&&!mediaBusy,modifier=Modifier.weight(1f).heightIn(min=52.dp),shape=RoundedCornerShape(16.dp)){Text("حفظ نسخة")}
       }
       FilterSettingsCard(effect,{effectName=it.name;FilterSettings.effect=it;onSaveFilterSettings()},blurStrength,{blurStrength=it;FilterSettings.blurStrengthPercent=it.roundToInt()},confidenceThreshold,{confidenceThreshold=it;FilterSettings.confidenceThreshold=it},maskExpansion,{maskExpansion=it;FilterSettings.maskExpansionRadius=it.roundToInt()},onSaveFilterSettings){
        FilterSettings.resetToDefaults();blurStrength=FilterSettings.blurStrengthPercent.toFloat();confidenceThreshold=FilterSettings.confidenceThreshold;maskExpansion=FilterSettings.maskExpansionRadius.toFloat();effectName=FilterSettings.effect.name;profileName=FilterSettings.processingProfile.name;onSaveFilterSettings()
       }
      }
      else -> {
       Text("الإعدادات",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
       Text("اختر التوازن بين سرعة المعالجة وتفاصيل القناع. حجم الالتقاط يُطبّق عند بدء جلسة جديدة.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
       ProcessingProfileCard(profile){profileName=it.name;FilterSettings.processingProfile=it;onSaveFilterSettings()}
        PersonModelCard()
       PersonTargetControls(snapshot, onUpdateTarget)
       FilterSettingsCard(effect,{effectName=it.name;FilterSettings.effect=it;onSaveFilterSettings()},blurStrength,{blurStrength=it;FilterSettings.blurStrengthPercent=it.roundToInt()},confidenceThreshold,{confidenceThreshold=it;FilterSettings.confidenceThreshold=it},maskExpansion,{maskExpansion=it;FilterSettings.maskExpansionRadius=it.roundToInt()},onSaveFilterSettings){
        FilterSettings.resetToDefaults();blurStrength=FilterSettings.blurStrengthPercent.toFloat();confidenceThreshold=FilterSettings.confidenceThreshold;maskExpansion=FilterSettings.maskExpansionRadius.toFloat();effectName=FilterSettings.effect.name;profileName=FilterSettings.processingProfile.name;onSaveFilterSettings()
       }
       PermissionsCard(overlayAllowed,notificationsAllowed,onGrantOverlay,onOpenNotificationSettings)
       ModelCard(snapshot);SafetyCard();HowItWorksCard()
      }
     }
     Text("ستر بلور · SATR BLUR · من Deku Foundation · إصدار تجريبي 0.6.2",modifier=Modifier.fillMaxWidth().padding(top=4.dp),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=TextAlign.Center)
    }
   }
  }
 }
}

@Composable
private fun WelcomeScreen(onContinue: () -> Unit, onOpenSource: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_satr),
            contentDescription = "شعار ستر بلور",
            modifier = Modifier.size(92.dp),
        )
        Text(
            "طمس محلي للصور ومحتوى الشاشة",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
        )
        Text(
            "اختر صورة لمعالجتها أو ابدأ جلسة لحماية الشاشة. تبقى الصور على الهاتف، ويمكنك اختيار التمويه أو البكسلة أو التغطية.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        listOf(
            "كل شيء على الجهاز" to "تُعالج الصور والإطارات محلياً.",
            "بلا حسابات ولا تتبّع" to "لا رفع للصور ولا تحليلات داخل التطبيق.",
            "مفتوح المصدر" to "يمكن مراجعة الكود والمساهمة فيه.",
        ).forEach { (title, detail) ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(16.dp)) {
            Text("ابدأ")
        }
        OutlinedButton(onClick = onOpenSource, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(16.dp)) {
            Text("اطّلع على الكود المصدري")
        }
        Text(
            "قد يفوت النموذج بعض الأشخاص. راجع النتيجة قبل مشاركة أي صورة، واختبر الفلترة على جهازك.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MediaPreviewCard(bitmap:Bitmap?,busy:Boolean,message:String,coveragePercent:Int) {
 Card(Modifier.fillMaxWidth(),shape=RoundedCornerShape(22.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface)){
  Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   if(bitmap!=null&&!bitmap.isRecycled){
    Image(bitmap=bitmap.asImageBitmap(),contentDescription="معاينة الصورة بعد تطبيق سياسة الإخفاء",modifier=Modifier.fillMaxWidth().heightIn(max=360.dp).clip(RoundedCornerShape(14.dp)),contentScale=ContentScale.Fit)
    Text("تغطية القناع: $coveragePercent٪",style=MaterialTheme.typography.labelLarge)
   } else Box(Modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant),contentAlignment=Alignment.Center){
    Image(painter=painterResource(R.drawable.ic_satr),contentDescription=null,modifier=Modifier.size(68.dp))
   }
   if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
   Text(message,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
  }
 }
}

@Composable
private fun ProcessingProfileCard(profile: ProcessingProfile, onChange: (ProcessingProfile) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("أداء المعالجة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            listOf(
                ProcessingProfile.LOW_LATENCY to "استجابة أسرع",
                ProcessingProfile.POWER_SAVER to "توفير الطاقة",
                ProcessingProfile.BALANCED to "متوازن",
                ProcessingProfile.DETAIL to "تفاصيل أعلى",
            ).forEach { (option, label) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            when (option) {
                                ProcessingProfile.LOW_LATENCY -> "360 px · إطار واحد؛ تفاصيل أقل للأشخاص البعيدين"
                                ProcessingProfile.POWER_SAVER -> "480 px · دون تقسيم الإطار"
                                ProcessingProfile.BALANCED -> "640 px · تقسيم الإطار الطويل"
                                ProcessingProfile.DETAIL -> "720 px · حمل أعلى؛ ليست ضماناً لدقة أكبر"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (option == profile) {
                        StatusPill("محدد", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                    } else {
                        OutlinedButton(onClick = { onChange(option) }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("اختيار")
                        }
                    }
                }
            }
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
                contentDescription = "شعار ستر بلور",
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)),
            )
            Spacer(Modifier.width(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    "ستر بلور",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    "من Deku Foundation",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        StatusPill(
            label = "معالجة محلية",
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
                    listOf(Color(0xFF12372A), Color(0xFF1F7A58), Color(0xFF245C43)),
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
                    ShieldGlyph(Modifier.size(21.dp), Color(0xFFD9B45A))
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    "تحكم واضح في جلسة التمويه",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFFD9B45A),
                )
            }
            Text(
                text = "طمس الأشخاص\nعلى الشاشة",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                lineHeight = 35.sp,
            )
            Text(
                text = "يعالج الشاشة على الهاتف ويطمس الأشخاص الذين يرصدهم. قد يفوت النموذج أشخاصاً صغاراً أو بعيدين.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFF4F7F3),
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
    waitingForPermission: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("التحكم بالجلسة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "ابدأ الجلسة بعد تفعيل النافذة العائمة وموافقة Android على مشاركة الشاشة.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 21.sp,
            )
            if (running) {
                Button(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("إيقاف الجلسة", fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = onStart,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    enabled = !waitingForPermission,
                ) {
                    ShieldGlyph(Modifier.size(19.dp), MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(10.dp))
                    Text(if (waitingForPermission) "بانتظار موافقة Android…" else "بدء التمويه", fontWeight = FontWeight.Bold)
                }
            }
            Text(
                "يمكن إيقاف الجلسة من هنا أو من اللوحة العائمة.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FilterSettingsCard(
    effect: PrivacyEffect,
    onEffectChange: (PrivacyEffect) -> Unit,
    blurStrength: Float,
    onBlurStrengthChange: (Float) -> Unit,
    confidenceThreshold: Float,
    onConfidenceThresholdChange: (Float) -> Unit,
    maskExpansion: Float,
    onMaskExpansionChange: (Float) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("طريقة الإخفاء", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(PrivacyEffect.BLUR to "تمويه", PrivacyEffect.PIXELATE to "بكسلة", PrivacyEffect.COVER to "تغطية").forEach { (option, label) ->
                    val selected = effect == option
                    if (selected) {
                        Button(onClick = { onEffectChange(option) }, modifier = Modifier.weight(1f).height(48.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp), shape = RoundedCornerShape(14.dp)) { Text(label, maxLines = 1) }
                    } else {
                        OutlinedButton(onClick = { onEffectChange(option) }, modifier = Modifier.weight(1f).height(48.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp), shape = RoundedCornerShape(14.dp)) { Text(label, maxLines = 1) }
                    }
                }
            }
            if (effect != PrivacyEffect.COVER) {
            Text(if (effect == PrivacyEffect.BLUR) "قوة التمويه" else "حجم البكسلة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (effect == PrivacyEffect.BLUR) "قوة التمويه" else "حجم البكسلة", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                StatusPill(
                    label = "${blurStrength.roundToInt()}%",
                    container = MaterialTheme.colorScheme.primaryContainer,
                    foreground = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Slider(
                value = blurStrength,
                onValueChange = onBlurStrengthChange,
                valueRange = 10f..100f,
                steps = 8,
                onValueChangeFinished = onSave,
            )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("حساسية اكتشاف الأشخاص", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                StatusPill(
                    label = "${(confidenceThreshold * 100).roundToInt()}%",
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    foreground = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Slider(
                value = confidenceThreshold,
                onValueChange = onConfidenceThresholdChange,
                valueRange = FilterSettings.MIN_CONFIDENCE_THRESHOLD..FilterSettings.MAX_CONFIDENCE_THRESHOLD,
                steps = 9,
                onValueChangeFinished = onSave,
            )
            Text(
                "الحساسية الأقل قد تشمل مساحة أكبر من الخلفية؛ الحساسية الأعلى قد تفوّت أجزاء من الشخص.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 19.sp,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("توسيع حواف القناع", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                StatusPill(
                    label = "${maskExpansion.roundToInt()} px",
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    foreground = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = maskExpansion,
                onValueChange = onMaskExpansionChange,
                valueRange = 0f..6f,
                steps = 5,
                onValueChangeFinished = onSave,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("إعادة الضبط") }
                Button(
                    onClick = onSave,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("حفظ الإعدادات") }
            }
        }
    }
}

@Composable
private fun PersonModelCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("نموذج الرصد", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "النموذج الأساسي: RTMDet-Ins-Tiny (24.03 MB). يولّد قناعاً مستقلاً لكل شخص ويعمل على الجهاز.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 21.sp,
            )
            StatusPill("RTMDet-Tiny محدد", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                "لا يُحمّل عند فتح الإعدادات؛ يبدأ تحميله داخل خدمة الجلسة. يستخدم مسار CPU الموثوق بدلاً من NNAPI، ويعرض خطأً واضحاً إذا تعذّر التحميل.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 20.sp,
            )
        }
    }
}

@Composable
private fun PersonTargetControls(
    snapshot: SatrSessionSnapshot,
    onUpdateTarget: (PersonTargetMode, Set<Long>) -> Unit,
) {
    val selected = snapshot.selectedTrackIds
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("التصحيح والاختيار اليدوي", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            StatusPill(
                label = when (snapshot.personTargetMode) {
                    PersonTargetMode.MASK_ALL -> "الوضع: طمس الجميع"
                    PersonTargetMode.MASK_SELECTED_ONLY -> "الوضع: طمس المحدد فقط"
                    PersonTargetMode.MASK_ALL_EXCEPT_SELECTED -> "الوضع: استثناء المحدد من التمويه"
                },
                container = MaterialTheme.colorScheme.primaryContainer,
                foreground = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text("ابدأ جلسة باستخدام RTMDet-Ins-Tiny لتظهر الأشخاص المكتشفون. حدّد من تريد طمسه أو استثناؤه؛ يتغير القناع في الجلسة الحالية فقط.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 21.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { onUpdateTarget(PersonTargetMode.MASK_ALL, emptySet()) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("طمس الجميع") }
                OutlinedButton(
                    onClick = { if (selected.isNotEmpty()) onUpdateTarget(PersonTargetMode.MASK_SELECTED_ONLY, selected) },
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) { Text("طمس المحدد") }
            }
            OutlinedButton(
                onClick = { onUpdateTarget(PersonTargetMode.MASK_ALL_EXCEPT_SELECTED, selected) },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("طمس الجميع عدا المحدد") }
            if (snapshot.personModelProfile != PersonModelProfile.RTMDET_INS_TINY) {
                Text("الاختيار الفردي غير متاح في النموذج الخفيف. اختر RTMDet-Ins-Tiny ثم أعد تشغيل جلسة الحماية.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (snapshot.detectedPeople.isEmpty()) {
                Text("لم تُرصد أشخاص في الإطار الحالي بعد. افتح محتوى تظهر فيه أشخاص وانتظر ظهور القائمة.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("الأشخاص المرصودون", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                snapshot.detectedPeople.forEach { person ->
                    val isSelected = person.trackId in selected
                    val position = "المركز " + (person.box.centerX * 100).roundToInt() + "٪ أفقياً، " + (person.box.centerY * 100).roundToInt() + "٪ عمودياً"
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("شخص #" + person.trackId, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                            Text(position + " · ثقة الرصد " + (person.confidence * 100).roundToInt() + "٪", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedButton(
                            onClick = {
                                val updated = if (isSelected) selected - person.trackId else selected + person.trackId
                                val nextMode = if (snapshot.personTargetMode == PersonTargetMode.MASK_SELECTED_ONLY && updated.isEmpty()) PersonTargetMode.MASK_ALL else snapshot.personTargetMode
                                onUpdateTarget(nextMode, updated)
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(if (isSelected) "إلغاء التحديد" else "تحديد") }
                    }
                }
            }
            Text("التصحيح هنا لا يدرّب أوزان النموذج تلقائياً. أرقام الأشخاص مؤقتة وقد تتغير عند اختفاء الشخص أو تغيّر المشهد؛ راجع الاختيار قبل الاعتماد عليه.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 19.sp)
        }
    }
}

@Composable
private fun PermissionsCard(
    overlayAllowed: Boolean,
    notificationsAllowed: Boolean,
    onGrantOverlay: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("الأذونات", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            PermissionLine(
                title = "النافذة العائمة",
                detail = if (overlayAllowed) "مسموح" else "يلزم تفعيلها لإظهار القناع فوق التطبيقات",
                granted = overlayAllowed,
            )
            PermissionLine(
                title = "إشعارات الجلسة",
                detail = if (notificationsAllowed) "مسموحة" else "اختيارية لبدء الجلسة، لكن الإشعار قد لا يظهر في درج الإشعارات",
                granted = notificationsAllowed,
            )
            PermissionLine(
                title = "مشاركة الشاشة",
                detail = "تظهر موافقة Android عند بدء كل جلسة",
                granted = false,
                neutral = true,
            )
            if (!overlayAllowed) {
                OutlinedButton(
                    onClick = onGrantOverlay,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(15.dp),
                ) { Text("فتح إعداد النافذة العائمة") }
            }
            if (!notificationsAllowed) {
                OutlinedButton(
                    onClick = onOpenNotificationSettings,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(15.dp),
                ) { Text("فتح إعدادات الإشعارات") }
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
                    Text("نموذج تقسيم الأشخاص", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("ONNX Runtime · RTMDet-Ins-Tiny", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusPill(
                    label = if (snapshot.modelReady) "جاهز" else "يُحمّل عند البدء",
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    foreground = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusPill("محلي بالكامل", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                StatusPill("نحو 24 MB", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "يولّد أقنعة منفصلة للأشخاص لتسمح بالاختيار اليدوي. يُنزّل الوزن المثبّت ويتحقق البناء من SHA-256 قبل تضمينه؛ لا يحتاج التطبيق إلى الإنترنت أثناء التشغيل. الأداء والدقة ما زالا يحتاجان اختباراً على أجهزة حقيقية.",
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
                "النموذج الخفيف ينتج قناعاً مشتركاً للأشخاص، أما نموذج RTMDet-Ins-Tiny فينتج أقنعة منفصلة لكنه أكبر وأثقل. قد يفوّت كلاهما الأشخاص الصغار أو البعيدين أو المحجوبين. لا يمكن اعتبار التغطية كاملة قبل اختبارات أجهزة حقيقية؛ وقد يمنع Android التقاط بعض النوافذ الآمنة أو فيديو DRM.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF613E00),
                lineHeight = 22.sp,
            )
            Text(
                "لتمرير اللمس في Android 12 وما بعده، تكون طبقة التمويه العادية بعتامة 80%؛ قد يبقى أثر من الصورة الأصلية. عند اختيار التغطية الصلبة أو فشل التحليل، تصبح الطبقة معتمة وقد تمنع التفاعل مع التطبيق أسفلها حتى تضغط إيقاف.",
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

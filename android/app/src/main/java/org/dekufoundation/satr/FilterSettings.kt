package org.dekufoundation.satr

import android.content.Context
import android.content.SharedPreferences
import kotlin.math.roundToInt

enum class PrivacyEffect { BLUR, PIXELATE, COVER }
enum class ProcessingProfile(val maxEdge: Int, val frameIntervalMs: Long, val tileWideFrames: Boolean) {
    LOW_LATENCY(360, 80L, false),
    POWER_SAVER(480, 200L, false),
    BALANCED(640, 180L, true),
    DETAIL(720, 140L, true),
}

object FilterSettings {
    const val MIN_BLUR_RADIUS = 4
    const val MAX_BLUR_RADIUS = 36
    const val DEFAULT_BLUR_STRENGTH_PERCENT = 72
    const val MIN_CONFIDENCE_THRESHOLD = 0.25f
    const val MAX_CONFIDENCE_THRESHOLD = 0.75f
    const val DEFAULT_CONFIDENCE_THRESHOLD = 0.42f
    const val DEFAULT_MASK_EXPANSION_RADIUS = 3
    private const val PREFS_NAME = "deku_satr_filter_settings"

    @Volatile
    private var preferences: SharedPreferences? = null

    @Volatile
    var effect: PrivacyEffect = PrivacyEffect.BLUR

    @Volatile
    var processingProfile: ProcessingProfile = ProcessingProfile.BALANCED

    @Volatile
    var blurStrengthPercent: Int = DEFAULT_BLUR_STRENGTH_PERCENT
        set(value) {
            field = value.coerceIn(10, 100)
            val normalized = (field - 10) / 90f
            blurRadius = (
                MIN_BLUR_RADIUS +
                    (MAX_BLUR_RADIUS - MIN_BLUR_RADIUS) * normalized
                ).roundToInt().coerceIn(MIN_BLUR_RADIUS, MAX_BLUR_RADIUS)
        }

    @Volatile
    var blurRadius: Int = 27
        private set

    @Volatile
    var confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD
        set(value) {
            field = if (value.isFinite()) {
                value.coerceIn(MIN_CONFIDENCE_THRESHOLD, MAX_CONFIDENCE_THRESHOLD)
            } else {
                DEFAULT_CONFIDENCE_THRESHOLD
            }
        }

    @Volatile
    var maskExpansionRadius: Int = DEFAULT_MASK_EXPANSION_RADIUS
        set(value) {
            field = value.coerceIn(0, 6)
        }

    fun initialize(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        preferences = prefs
        effect = runCatching { PrivacyEffect.valueOf(prefs.getString("privacy_effect", PrivacyEffect.BLUR.name) ?: PrivacyEffect.BLUR.name) }.getOrDefault(PrivacyEffect.BLUR)
        processingProfile = runCatching { ProcessingProfile.valueOf(prefs.getString("processing_profile", ProcessingProfile.BALANCED.name) ?: ProcessingProfile.BALANCED.name) }.getOrDefault(ProcessingProfile.BALANCED)
        blurStrengthPercent = prefs.getInt("blur_strength_percent", DEFAULT_BLUR_STRENGTH_PERCENT)
        confidenceThreshold = prefs.getFloat("confidence_threshold", DEFAULT_CONFIDENCE_THRESHOLD)
        maskExpansionRadius = prefs.getInt("mask_expansion_radius", DEFAULT_MASK_EXPANSION_RADIUS)
    }

    fun resetToDefaults() {
        effect = PrivacyEffect.BLUR
        processingProfile = ProcessingProfile.BALANCED
        blurStrengthPercent = DEFAULT_BLUR_STRENGTH_PERCENT
        confidenceThreshold = DEFAULT_CONFIDENCE_THRESHOLD
        maskExpansionRadius = DEFAULT_MASK_EXPANSION_RADIUS
    }

    fun persist(context: Context) {
        val prefs = preferences
            ?: context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).also {
                preferences = it
            }
        prefs.edit()
            .putString("privacy_effect", effect.name)
            .putString("processing_profile", processingProfile.name)
            .putInt("blur_strength_percent", blurStrengthPercent)
            .putFloat("confidence_threshold", confidenceThreshold)
            .putInt("mask_expansion_radius", maskExpansionRadius)
            .apply()
    }
}

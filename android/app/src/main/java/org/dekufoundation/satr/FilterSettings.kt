package org.dekufoundation.satr

object FilterSettings {
    @Volatile
    var blurRadius: Int = 16
        set(value) {
            field = value.coerceIn(MIN_BLUR_RADIUS, MAX_BLUR_RADIUS)
        }

    const val MIN_BLUR_RADIUS = 4
    const val MAX_BLUR_RADIUS = 18
}

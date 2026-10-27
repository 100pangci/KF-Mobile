package work.miaola.kf

/** Pure gesture arbitration so tests do not need an Android device. */
object GalleryGesturePolicy {
    const val SCALE_EPSILON = 0.02f
    const val VERTICAL_RATIO = 1.15f
    const val DISTANCE_FRACTION = 0.22f
    const val MIN_VELOCITY = 1_100f

    fun scaleIsNeutral(scale: Float): Boolean = kotlin.math.abs(scale - 1f) <= SCALE_EPSILON

    fun canStartVerticalDismiss(
        scale: Float,
        dx: Float,
        dy: Float,
        touchSlop: Float,
        pointerCount: Int = 1,
        pinching: Boolean = false,
    ): Boolean {
        if (pinching || pointerCount != 1 || !scaleIsNeutral(scale)) return false
        if (kotlin.math.abs(dx) < touchSlop && dy < touchSlop) return false
        return dy > touchSlop && dy > kotlin.math.abs(dx) * VERTICAL_RATIO
    }

    fun shouldDismiss(distance: Float, height: Float, velocity: Float): Boolean {
        if (height <= 0f) return false
        return distance > height * DISTANCE_FRACTION ||
            velocity > MIN_VELOCITY && distance > height * 0.06f
    }
}

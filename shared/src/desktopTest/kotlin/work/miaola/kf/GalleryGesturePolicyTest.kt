package work.miaola.kf

import kotlin.test.*

class GalleryGesturePolicyTest {
    @Test fun neutralScaleIsTolerantButZoomedImagesNeverStartVerticalDismiss() {
        assertTrue(GalleryGesturePolicy.scaleIsNeutral(1f))
        assertTrue(GalleryGesturePolicy.scaleIsNeutral(1.01f))
        assertFalse(GalleryGesturePolicy.scaleIsNeutral(1.1f))
        val slop = 8f
        assertTrue(GalleryGesturePolicy.canStartVerticalDismiss(1f, 2f, 30f, slop))
        assertFalse(GalleryGesturePolicy.canStartVerticalDismiss(1.5f, 2f, 30f, slop))
        assertFalse(GalleryGesturePolicy.canStartVerticalDismiss(1f, 40f, 20f, slop))
        assertFalse(GalleryGesturePolicy.canStartVerticalDismiss(1f, 2f, -30f, slop))
        assertFalse(GalleryGesturePolicy.canStartVerticalDismiss(1f, 2f, 30f, slop, pointerCount = 2))
        assertFalse(GalleryGesturePolicy.canStartVerticalDismiss(1f, 2f, 30f, slop, pinching = true))
    }

    @Test fun dismissNeedsDistanceOrEnoughVelocityAndCancelReturnsToCenter() {
        assertFalse(GalleryGesturePolicy.shouldDismiss(80f, 1000f, 200f))
        assertTrue(GalleryGesturePolicy.shouldDismiss(260f, 1000f, 0f))
        assertTrue(GalleryGesturePolicy.shouldDismiss(90f, 1000f, 1300f))
        assertFalse(GalleryGesturePolicy.shouldDismiss(20f, 1000f, 1300f))
        assertFalse(GalleryGesturePolicy.shouldDismiss(90f, 1000f, -1300f))
        assertFalse(GalleryGesturePolicy.shouldDismiss(300f, 0f, 3000f))
    }
}

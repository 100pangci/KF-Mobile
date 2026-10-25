package work.miaola.kf.nav

import androidx.activity.BackEventCompat
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import kotlin.math.roundToInt

/**
 * Short, directional motion adapted from VNventory's ShelfMotion. Only the
 * destination content moves; the theme background underneath never scales.
 */
object KfMotion {
    const val Quick = 160
    const val Standard = 240
    const val Navigation = 300
    const val Back = 180
    const val BackRecovery = 140
    const val Chrome = 120
    val Easing = FastOutSlowInEasing
    val BackEasing = LinearOutSlowInEasing

    fun forwardEnter(direction: Int): EnterTransition =
        fadeIn(tween(Navigation, easing = Easing)) +
            slideInHorizontally(tween(Navigation, easing = Easing)) { it / 10 * direction }

    fun forwardExit(direction: Int): ExitTransition =
        fadeOut(tween(Quick, easing = Easing)) +
            slideOutHorizontally(tween(Quick, easing = Easing)) { -it / 20 * direction }

    fun backEnter(direction: Int): EnterTransition =
        fadeIn(tween(Back, easing = BackEasing)) +
            slideInHorizontally(tween(Back, easing = BackEasing)) { -it / 24 * direction }

    fun backExit(direction: Int): ExitTransition =
        fadeOut(tween(Back, easing = BackEasing)) +
            slideOutHorizontally(tween(Back, easing = BackEasing)) { it / 8 * direction }

    fun predictableBackEnter(swipeEdge: Int): EnterTransition {
        val direction = if (swipeEdge == BackEventCompat.EDGE_RIGHT) -1 else 1
        return fadeIn(tween(Back, easing = LinearEasing)) +
            slideInHorizontally(tween(Back, easing = LinearEasing)) { -it / 24 * direction }
    }

    fun predictableBackExit(swipeEdge: Int): ExitTransition {
        val direction = if (swipeEdge == BackEventCompat.EDGE_RIGHT) -1 else 1
        return fadeOut(tween(Back, easing = LinearEasing)) +
            slideOutHorizontally(tween(Back, easing = LinearEasing)) { it / 8 * direction }
    }

    /** Fraction of the system gesture left when the finger is released. */
    fun finishDuration(progress: Float): Int = (Back * (1f - progress.coerceIn(0f, 1f))).roundToInt()
}

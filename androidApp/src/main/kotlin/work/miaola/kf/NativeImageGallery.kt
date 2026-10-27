package work.miaola.kf

import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.widget.ImageView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable as ComposeAnimatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun NativeImageGallery(origin: String, gallery: ImageGallery, onClose: () -> Unit, save: (String) -> Unit, message: String? = null) {
    val pager = rememberPagerState(initialPage = gallery.initialIndex) { gallery.urls.size }
    var zoomedPage by remember { mutableIntStateOf(-1) }
    var currentScale by remember { mutableFloatStateOf(1f) }
    var chrome by remember { mutableStateOf(true) }
    var chromeBeforeDrag by remember { mutableStateOf(true) }
    var dragY by remember { mutableFloatStateOf(0f) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val scope = rememberCoroutineScope()
    val latestScale by rememberUpdatedState(currentScale)
    val latestChrome by rememberUpdatedState(chrome)
    val dismissProgress = if (viewport.height > 0) (dragY / viewport.height).coerceIn(0f, 1f) else 0f
    val backgroundAlpha = (1f - dismissProgress * 0.85f).coerceIn(0.15f, 1f)

    fun animateDrag(target: Float, spec: androidx.compose.animation.core.AnimationSpec<Float>, then: () -> Unit = {}) {
        scope.launch {
            val value = ComposeAnimatable(dragY)
            value.animateTo(target, spec) { dragY = this.value }
            then()
        }
    }

    fun resetDrag(restoreChrome: Boolean) {
        animateDrag(0f, spring(stiffness = 700f)) {
            if (restoreChrome) chrome = chromeBeforeDrag
        }
    }

    BackHandler(onBack = onClose)
    LaunchedEffect(pager.currentPage) {
        dragY = 0f
        currentScale = 1f
        chrome = true
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = backgroundAlpha)).systemBarsPadding()
        .onSizeChanged { viewport = it }) {
        HorizontalPager(
            pager,
            Modifier.fillMaxSize().graphicsLayer {
                translationY = dragY
                val dragScale = 1f - dismissProgress * 0.08f
                scaleX = dragScale
                scaleY = dragScale
            }
                .pointerInput(pager.currentPage, zoomedPage) {
                    if (zoomedPage == pager.currentPage) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        var started = false
                        var cancelled = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val pointerCount = event.changes.count { it.pressed }
                            if (pointerCount > 1) {
                                cancelled = true
                                started = false
                                break
                            }
                            val dx = change.position.x - down.position.x
                            val dy = change.position.y - down.position.y
                            if (!started && GalleryGesturePolicy.canStartVerticalDismiss(
                                    latestScale, dx, dy, viewConfiguration.touchSlop, pointerCount)) {
                                started = true
                                chromeBeforeDrag = latestChrome
                                chrome = false
                            }
                            if (started) {
                                val deltaY = change.position.y - change.previousPosition.y
                                if (deltaY > 0f) dragY = (dragY + deltaY).coerceAtLeast(0f)
                                tracker.addPosition(change.uptimeMillis, change.position)
                                change.consume()
                            } else if (abs(dx) > viewConfiguration.touchSlop) {
                                // The horizontal pager owns this gesture.
                                break
                            }
                        }
                        if (started) {
                            val velocity = tracker.calculateVelocity().y
                            val dismiss = GalleryGesturePolicy.shouldDismiss(dragY, viewport.height.toFloat(), velocity)
                            if (dismiss) {
                                animateDrag(viewport.height.toFloat().coerceAtLeast(1f), tween(180)) { onClose() }
                            } else {
                                resetDrag(restoreChrome = true)
                            }
                        } else if (cancelled) {
                            resetDrag(restoreChrome = true)
                        }
                    }
                },
            userScrollEnabled = zoomedPage != pager.currentPage,
        ) { index ->
            ZoomableNativeImage(origin, gallery.urls[index], onTap = { chrome = !chrome },
                onScale = { scale -> if (index == pager.currentPage) currentScale = scale },
                zoomed = { zoomed -> if (zoomed) zoomedPage = index else if (zoomedPage == index) zoomedPage = -1 })
        }
        if (chrome) Row(Modifier.fillMaxWidth().height(56.dp).align(Alignment.TopCenter)
            .background(Color.Black.copy(alpha = 0.6f)), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "关闭图片", tint = Color.White) }
            Text("${pager.currentPage + 1} / ${gallery.urls.size}", Modifier.weight(1f), color = Color.White)
            IconButton(onClick = { save(gallery.urls[pager.currentPage]) }) { Icon(Icons.Default.Download, "保存原图", tint = Color.White) }
        }
        if (chrome) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.6f)), horizontalAlignment = Alignment.CenterHorizontally) {
            message?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = Color.White, style = MaterialTheme.typography.bodySmall) }
            Text("双指缩放 · 双击放大 · 未放大时下滑关闭", Modifier.padding(16.dp), color = Color.LightGray,
                style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ZoomableNativeImage(
    origin: String,
    url: String,
    onTap: () -> Unit,
    onScale: (Float) -> Unit,
    zoomed: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var drawable by remember(origin, url) { mutableStateOf<Drawable?>(null) }
    var failed by remember(origin, url) { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    var scale by remember(url) { mutableFloatStateOf(1f) }
    var offset by remember(url) { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val onZoomed by rememberUpdatedState(zoomed)
    val onSingleTap by rememberUpdatedState(onTap)
    val onScaleChanged by rememberUpdatedState(onScale)
    LaunchedEffect(scale) { onZoomed(!GalleryGesturePolicy.scaleIsNeutral(scale)); onScaleChanged(scale) }
    LaunchedEffect(origin, url, attempt) {
        failed = false
        drawable = withContext(Dispatchers.IO) {
            try { forumImageBytes(context, origin, url)?.let { decodeForumDrawable(context, it) } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { null }
        }
        failed = drawable == null
    }
    val transform = rememberTransformableState { change, pan, _ ->
        scale = (scale * change).coerceIn(1f, 8f)
        val maxX = viewport.width * (scale - 1f) / 2f
        val maxY = viewport.height * (scale - 1f) / 2f
        offset = if (GalleryGesturePolicy.scaleIsNeutral(scale)) Offset.Zero else Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY))
    }
    Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged { viewport = it }, contentAlignment = Alignment.Center) {
        val image = drawable
        if (image != null) AndroidView(factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "帖子图片" } },
            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }
                .transformable(transform, canPan = { !GalleryGesturePolicy.scaleIsNeutral(scale) })
                .pointerInput(url) { detectTapGestures(onTap = { onSingleTap() }, onDoubleTap = { scale = if (!GalleryGesturePolicy.scaleIsNeutral(scale)) 1f else 2.5f; offset = Offset.Zero }) },
            onReset = null,
            onRelease = { (it.drawable as? Animatable)?.stop(); it.setImageDrawable(null) },
            update = { view -> if (view.drawable !== image) { (view.drawable as? Animatable)?.stop(); view.setImageDrawable(image); (image as? Animatable)?.start() } })
        else if (failed) Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("图片加载失败，可重试或保存原图", color = Color.White)
            TextButton(onClick = { attempt++ }) { Text("重试") }
        } else CircularProgressIndicator(color = Color.White)
    }
}

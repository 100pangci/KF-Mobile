package work.miaola.kf

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Animatable
import android.content.Context
import android.text.Html
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.text.style.ImageSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private class InlineDrawable : Drawable(), Drawable.Callback {
    var image: Drawable? = null
        set(value) { field = value; value?.callback = this }
    override fun draw(canvas: Canvas) { image?.draw(canvas) }
    override fun setAlpha(alpha: Int) { image?.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { image?.colorFilter = colorFilter }
    @Deprecated("Android API") override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun invalidateDrawable(who: Drawable) = invalidateSelf()
    override fun scheduleDrawable(who: Drawable, what: Runnable, at: Long) = scheduleSelf(what, at)
    override fun unscheduleDrawable(who: Drawable, what: Runnable) = unscheduleSelf(what)
}

private class RichTextView(context: Context) : TextView(context) {
    val inlineImages = mutableListOf<InlineDrawable>()
    var onContentSettled: (() -> Unit)? = null
    init {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setHorizontallyScrolling(false)
        isSingleLine = false
    }
    fun releaseImages() {
        inlineImages.forEach { (it.image as? Animatable)?.stop(); it.image?.callback = null; it.callback = null }
        inlineImages.clear()
    }
    fun remeasure() {
        requestLayout()
        (parent as? View)?.requestLayout()
        onContentSettled?.invoke()
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Rich text is always full width. A recycled AndroidView can receive a
        // stale wrap-content pass and collapse to one character per line; force
        // the available width so a tap is not needed to trigger relayout.
        val size = View.MeasureSpec.getSize(widthMeasureSpec)
        val mode = View.MeasureSpec.getMode(widthMeasureSpec)
        val forced = if (mode != View.MeasureSpec.UNSPECIFIED && size > 0) {
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
        } else widthMeasureSpec
        super.onMeasure(forced, heightMeasureSpec)
    }
    override fun onDetachedFromWindow() { inlineImages.forEach { (it.image as? Animatable)?.stop() }; super.onDetachedFromWindow() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); inlineImages.forEach { (it.image as? Animatable)?.start() } }
}

@Composable
fun NativeRichText(html: String, origin: String, fontScale: Float, onLink: (String) -> Unit, onImage: (String, List<String>) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val onClick by rememberUpdatedState(onLink)
    val imageClick by rememberUpdatedState(onImage)
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    val maxWidth = (LocalWindowInfo.current.containerSize.width - with(LocalDensity.current) { 64.dp.roundToPx() }).coerceAtLeast(160)
    val density = LocalDensity.current
    var layoutVersion by remember { mutableIntStateOf(0) }
    AndroidView(
        factory = { RichTextView(it).apply { setTextIsSelectable(true); movementMethod = LinkMovementMethod.getInstance(); setPadding(0, 0, 0, 0); setLineSpacing(4 * resources.displayMetrics.density, 1.15f) } },
        modifier = Modifier.fillMaxWidth().layout { measurable, constraints ->
            // Async inline images change the TextView height after the first
            // pass. Read the epoch so Compose measures the AndroidView again
            // instead of leaving a half-clipped line until the next tap.
            layoutVersion
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        },
        onReset = { view -> view.releaseImages(); view.tag = null; view.text = "" },
        onRelease = { it.releaseImages() },
        update = { view ->
            view.textSize = 16 * fontScale; view.setTextColor(color); view.setLinkTextColor(linkColor)
            view.onContentSettled = { layoutVersion++ }
            val identity = "$origin@$maxWidth@$fontScale@${density.density}@${density.fontScale}@$html"
            if (view.tag != identity) {
                view.releaseImages()
                view.tag = identity
                var images = 0
                val content = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT, { source ->
                    val initial = if (isForumEmoticon(source)) (36 * density.density * density.fontScale * fontScale).toInt() else (24 * density.density).toInt()
                    val placeholder = InlineDrawable().apply {
                        setBounds(0, 0, initial, initial)
                        callback = object : Drawable.Callback {
                            override fun invalidateDrawable(who: Drawable) { view.invalidate() }
                            override fun scheduleDrawable(who: Drawable, what: Runnable, at: Long) { view.postDelayed(what, (at - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0)) }
                            override fun unscheduleDrawable(who: Drawable, what: Runnable) { view.removeCallbacks(what) }
                        }
                    }
                    view.inlineImages.add(placeholder)
                    if (++images <= 40 && source.startsWith("https://")) scope.launch {
                        val image = withContext(Dispatchers.IO) {
                            runCatching {
                                // Only exact-origin images receive the scoped session.
                                // External images use a separate cookie-free client.
                                val bytes = forumImageBytes(context, origin, source) ?: return@runCatching null
                                decodeForumDrawable(context, bytes)
                            }.getOrNull()
                        }
                        if (image != null && view.tag == identity && placeholder in view.inlineImages) {
                            val size = inlineImageSize(source, image.intrinsicWidth, image.intrinsicHeight, maxWidth, density.density, fontScale * density.fontScale)
                            image.setBounds(0, 0, size.width, size.height)
                            placeholder.image = image; placeholder.setBounds(0, 0, size.width, size.height)
                            if (view.isAttachedToWindow) (image as? Animatable)?.start()
                            view.text = view.text
                            view.invalidate()
                            view.post { if (view.tag == identity) view.remeasure() }
                        }
                    }
                    placeholder
                }, null)
                val text = SpannableStringBuilder(content)
                text.getSpans(0, text.length, URLSpan::class.java).forEach { span ->
                    val start = text.getSpanStart(span); val end = text.getSpanEnd(span); text.removeSpan(span)
                    text.setSpan(object : ClickableSpan() { override fun onClick(widget: View) { onClick(span.url) } }, start, end, 0)
                }
                val imageSpans = text.getSpans(0, text.length, ImageSpan::class.java)
                val gallery = imageSpans.mapNotNull { it.source }.filter { it.startsWith("https://") && !isForumEmoticon(it) }.distinct()
                imageSpans.forEach { span ->
                    val source = span.source ?: return@forEach
                    val start = text.getSpanStart(span); val end = text.getSpanEnd(span)
                    text.getSpans(start, end, ClickableSpan::class.java).forEach { old ->
                        val left = text.getSpanStart(old); val right = text.getSpanEnd(old)
                        text.removeSpan(old)
                        fun delegate() = object : ClickableSpan() { override fun onClick(widget: View) = old.onClick(widget) }
                        if (left < start) text.setSpan(delegate(), left, start, 0)
                        if (right > end) text.setSpan(delegate(), end, right, 0)
                    }
                    text.setSpan(object : ClickableSpan() {
                        override fun onClick(widget: View) { imageClick(source, if (isForumEmoticon(source)) listOf(source) else gallery) }
                    }, start, end, 0)
                }
                view.text = text
                view.requestLayout()
                view.post { if (view.tag == identity) view.remeasure() }
            }
        },
    )
}

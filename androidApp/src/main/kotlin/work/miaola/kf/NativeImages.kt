package work.miaola.kf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.io.OutputStream

private val publicImages = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
    .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()

// Only stable forum smile assets are cached. Never cache account-specific
// attachment bytes across sign-out or account changes.
private val smileBytes = object : LruCache<String, ByteArray>(4 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ByteArray) = value.size
}

// Avatars are visible again after the header scrolls back into view. Keep the
// decoded bitmap in memory so a scroll does not trigger another download.
private val avatarBitmaps = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}

internal suspend fun forumImageBytes(context: Context, origin: String, url: String): ByteArray? = withContext(Dispatchers.IO) {
    if (!url.startsWith("https://")) return@withContext null
    val key = "$origin@$url"
    if (isForumEmoticon(url)) smileBytes.get(key)?.let { return@withContext it }
    val service = (context.applicationContext as KfApplication).services.create(origin)
    val bytes = if (service.sameOrigin(url)) service.imageBytes(url) else {
        publicImages.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful || !response.header("Content-Type").orEmpty().startsWith("image/")) null
            else response.body?.byteStream()?.readBounded(8 * 1024 * 1024 + 1)?.takeIf { it.size <= 8 * 1024 * 1024 }
        }
    }
    if (bytes != null && isForumEmoticon(url)) smileBytes.put(key, bytes)
    bytes
}

internal fun decodeForumBitmap(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / sample > 1800 || bounds.outHeight / sample > 1800) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

internal suspend fun saveOriginalImage(context: Context, origin: String, url: String, output: OutputStream) = withContext(Dispatchers.IO) {
    val service = (context.applicationContext as KfApplication).services.create(origin)
    if (service.sameOrigin(url)) service.download(url, output)
    else {
        require(url.startsWith("https://")) { "只允许 HTTPS 图片" }
        publicImages.newCall(Request.Builder().url(url).build()).execute().use { response ->
            require(response.isSuccessful && response.header("Content-Type").orEmpty().startsWith("image/")) { "图片下载失败或需要跨域跳转" }
            response.body?.byteStream()?.use { it.copyTo(output) } ?: error("图片为空")
        }
    }
}

internal fun decodeForumDrawable(context: Context, bytes: ByteArray): Drawable? {
    if (Build.VERSION.SDK_INT >= 28) {
        // AnimatedImageDrawable keeps GIF forum expressions animated.
        return ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            var sample = 1
            while (info.size.width / sample > 1800 || info.size.height / sample > 1800) sample *= 2
            decoder.setTargetSampleSize(sample)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }
    return decodeForumBitmap(bytes)?.let { BitmapDrawable(context.resources, it) }
}

@Composable
fun NativeForumImage(origin: String, url: String, description: String, modifier: Modifier, contentScale: ContentScale = ContentScale.Fit, cache: LruCache<String, Bitmap>? = null) {
    val context = LocalContext.current
    val key = "$origin@$url"
    var bitmap by remember(origin, url) { mutableStateOf(cache?.get(key)) }
    LaunchedEffect(origin, url) {
        if (bitmap != null) return@LaunchedEffect
        val loaded = withContext(Dispatchers.IO) {
            try { forumImageBytes(context, origin, url)?.let(::decodeForumBitmap) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { null }
        }
        if (loaded != null) {
            cache?.put(key, loaded)
            bitmap = loaded
        }
    }
    val value = bitmap
    if (value != null) Image(value.asImageBitmap(), description, modifier, contentScale = contentScale)
    else androidx.compose.foundation.layout.Box(modifier, contentAlignment = androidx.compose.ui.Alignment.Center) {
        Text(description, maxLines = 2)
    }
}

@Composable
fun NativeForumAvatar(origin: String, url: String, description: String, modifier: Modifier) =
    NativeForumImage(origin, url, description, modifier, contentScale = ContentScale.Crop, cache = avatarBitmaps)

package work.miaola.kf

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import work.miaola.kf.nav.KfRoot
import work.miaola.kf.ui.kfBackground

class MainActivity : ComponentActivity() {
    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var controller: AppController
    private var picker: FilePickerRequest? = null
    private var pendingSave: NativeDownloadRequest? = null
    private var lastSessionCheck = android.os.SystemClock.elapsedRealtime()
    private val pickMultiple = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { receivedFiles(it) }
    private val pickSingle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> receivedFiles(listOfNotNull(uri)) }
    private val pickAvatar = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> receivedAvatar(uri) }
    private val saveFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val request = pendingSave; pendingSave = null
        if (uri != null && request != null) screenScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        if (request.isImage) saveOriginalImage(this@MainActivity, controller.origin, request.url, output)
                        else (application as KfApplication).services.create(controller.origin).download(request.url, output)
                    } ?: error("无法写入所选位置")
                }
                controller.reportPlatformResult("已保存到所选位置")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: AttachmentPageRequired) { controller.presentNativePage(e.page); controller.reportPlatformResult("附件需要权限或购买，请核对详情") }
            catch (e: Exception) { controller.reportPlatformResult(e.message ?: "保存失败", true) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = application as KfApplication
        controller = AppController(graph.store, graph.services, screenScope, browser = { origin, url ->
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                .onFailure { controller.reportPlatformResult("没有可处理该链接的外部应用", true) }
        }, defaultConfigUrl = BuildConfig.DOMAIN_CONFIG_URL)
        setContent {
            val dark = when (controller.theme) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.DARK -> true; ThemeMode.LIGHT -> false }
            val background = kfBackground(dark)
            SideEffect {
                window.setBackgroundDrawable(ColorDrawable(background.toArgb()))
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            LaunchedEffect(controller.filePickerRequest) {
                controller.filePickerRequest?.let { request ->
                    picker = request
                    if (request.multiple) pickMultiple.launch(request.mimeTypes.toTypedArray()) else pickSingle.launch(request.mimeTypes.toTypedArray())
                }
            }
            LaunchedEffect(controller.downloadRequest) {
                controller.downloadRequest?.let { request ->
                    pendingSave = request; controller.consumeDownloadRequest()
                    saveFile.launch(request.suggestedName.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(140))
                }
            }
            LaunchedEffect(controller.avatarPickerRequested) {
                if (controller.avatarPickerRequested) {
                    controller.consumeAvatarPickerRequest()
                    pickAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            }
            Box(Modifier.fillMaxSize().background(background)) {
                KfRoot(
                    controller,
                    forumImage = { url, label, modifier -> NativeForumImage(controller.origin, url, label, modifier) },
                    forumAvatar = { url, label, modifier -> NativeForumAvatar(controller.origin, url, label, modifier) },
                ) { html, origin, fontScale, onLink, onImage -> NativeRichText(html, origin, fontScale, onLink, onImage) }
                controller.gallery?.let { NativeImageGallery(controller.origin, it, controller::closeImages, controller::saveImage, controller.error ?: controller.toast) }
                controller.pendingAvatar?.let { file ->
                    AvatarPreviewDialog(file, uploading = controller.avatarBusy,
                        onConfirm = controller::confirmAvatarUpload, onDismiss = controller::cancelAvatarUpload)
                }
            }
        }
    }

    override fun onDestroy() {
        controller.close(); screenScope.cancel()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        val now = android.os.SystemClock.elapsedRealtime()
        if (::controller.isInitialized && controller.filePickerRequest == null && pendingSave == null && controller.gallery == null && !controller.busy && !controller.purchaseBusy && !controller.siteBusy && now - lastSessionCheck >= 60_000) {
            lastSessionCheck = now
            controller.resumeSession()
        }
    }

    override fun onPause() {
        if (::controller.isInitialized) {
            controller.flushDraft()
            controller.flushRecovery()
        }
        super.onPause()
    }

    private fun receivedFiles(uris: List<Uri>) {
        val request = picker ?: return
        picker = null
        screenScope.launch {
            try {
                require(uris.size <= 5) { "一次最多选择 5 个附件" }
                val files = withContext(Dispatchers.IO) { uris.take(5).map { uri ->
                    val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: "attachment.bin"
                    val bytes = contentResolver.openInputStream(uri)?.use { it.readBounded(1024 * 1024 + 1) } ?: error("无法读取附件")
                    require(bytes.size <= 1024 * 1024) { "附件超过 1 MB，请按站点限制选择文件" }
                    LocalUpload(java.util.UUID.randomUUID().toString(), name, contentResolver.getType(uri) ?: "application/octet-stream", bytes)
                } }
                controller.receiveFiles(request, files)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { controller.receiveFiles(request, emptyList(), e.message ?: "选择文件失败") }
        }
    }

    private fun receivedAvatar(uri: Uri?) {
        if (uri == null) { controller.receiveAvatar(null); return }
        screenScope.launch {
            try {
                val upload = withContext(Dispatchers.IO) {
                    val mime = contentResolver.getType(uri)?.lowercase() ?: ""
                    require(mime in setOf("image/jpeg", "image/jpg", "image/png", "image/webp")) {
                        "请选择 JPG、PNG 或 WebP 图片"
                    }
                    val sourceBytes = contentResolver.openInputStream(uri)?.use { it.readBounded(8 * 1024 * 1024 + 1) }
                        ?: error("无法读取图片")
                    require(sourceBytes.size <= 8 * 1024 * 1024) { "图片超过 8 MB，请选择更小的图片" }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size, bounds)
                    require(bounds.outWidth in 1..8192 && bounds.outHeight in 1..8192) { "图片尺寸无效" }
                    var sample = 1
                    while (bounds.outWidth / (sample * 2) >= 640 && bounds.outHeight / (sample * 2) >= 640) sample *= 2
                    val decoded = BitmapFactory.decodeByteArray(sourceBytes, 0, sourceBytes.size,
                        BitmapFactory.Options().apply { inSampleSize = sample })
                        ?: error("无法解码图片")
                    try {
                        val targetRatio = 140f / 200f
                        val currentRatio = decoded.width.toFloat() / decoded.height.toFloat()
                        val cropW: Int
                        val cropH: Int
                        if (currentRatio > targetRatio) {
                            cropH = decoded.height; cropW = (decoded.height * targetRatio).toInt()
                        } else {
                            cropW = decoded.width; cropH = (decoded.width / targetRatio).toInt()
                        }
                        val cropped = Bitmap.createBitmap(decoded, (decoded.width - cropW) / 2,
                            (decoded.height - cropH) / 2, cropW.coerceAtLeast(1), cropH.coerceAtLeast(1))
                        val scaled = Bitmap.createScaledBitmap(cropped, 140, 200, true)
                        val output = ByteArrayOutputStream()
                        require(scaled.compress(Bitmap.CompressFormat.JPEG, 90, output)) { "无法压缩图片" }
                        if (scaled !== cropped && !cropped.isRecycled) cropped.recycle()
                        if (scaled !== decoded && !decoded.isRecycled) decoded.recycle()
                        LocalUpload(java.util.UUID.randomUUID().toString(), "avatar.jpg", "image/jpeg", output.toByteArray())
                    } catch (e: Exception) {
                        decoded.recycle(); throw e
                    }
                }
                controller.receiveAvatar(upload)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { controller.receiveAvatar(null, e.message ?: "无法处理头像图片") }
        }
    }
}

@Composable
private fun AvatarPreviewDialog(
    file: LocalUpload,
    uploading: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val bitmap = remember(file.id) {
        runCatching { BitmapFactory.decodeByteArray(file.bytes, 0, file.bytes.size)?.asImageBitmap() }.getOrNull()
    }
    AlertDialog(
        onDismissRequest = { if (!uploading) onDismiss() },
        title = { Text("预览新头像") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (bitmap != null) Image(bitmap, "头像预览", Modifier.width(140.dp).height(200.dp), contentScale = ContentScale.Crop)
                Text("将上传为 140 × 200 JPG。资料页会在上传成功后自动刷新。",
                    style = MaterialTheme.typography.bodySmall)
                Text("确认后才会发送；如果网络结果不确定，不会自动重试。",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = !uploading) { Text(if (uploading) "正在上传…" else "上传头像") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !uploading) { Text("取消") } },
    )
}

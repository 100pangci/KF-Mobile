package work.miaola.kf

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
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
            Box(Modifier.fillMaxSize().background(background)) {
                KfRoot(
                    controller,
                    forumImage = { url, label, modifier -> NativeForumImage(controller.origin, url, label, modifier) },
                    forumAvatar = { url, label, modifier -> NativeForumAvatar(controller.origin, url, label, modifier) },
                ) { html, origin, fontScale, onLink, onImage -> NativeRichText(html, origin, fontScale, onLink, onImage) }
                controller.gallery?.let { NativeImageGallery(controller.origin, it, controller::closeImages, controller::saveImage, controller.error ?: controller.toast) }
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
}

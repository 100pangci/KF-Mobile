package work.miaola.kf

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

fun AppController.toggleBookmark() {
        val p = page ?: return
        bookmarks = if (bookmarks.any { it.url == p.url }) bookmarks.filterNot { it.url == p.url }
        else (listOf(SiteLink(p.title, p.url)) + bookmarks).take(200)
        saveLinks("bookmarks", bookmarks)
    }

fun AppController.showImages(selected: String, images: List<String>) {
        val list = images.filter { it.startsWith("https://") }.distinct().take(100)
        val usable = if (selected in list) list else listOf(selected).filter { it.startsWith("https://") }
        if (usable.isNotEmpty()) { galleryNotice = null; gallery = ImageGallery(usable, usable.indexOf(selected).coerceAtLeast(0)) }
    }

fun AppController.receiveFiles(request: FilePickerRequest, files: List<LocalUpload>, failure: String? = null) {
        filePickerRequest = null
        failure?.let { error = it; return }
        if (request.target == "editor") {
            val form = editor ?: return
            if (editorFiles.size + files.size > form.uploadLimit) { error = "最多选择 ${form.uploadLimit} 个附件"; return }
            if (form.uploadExtensions.isNotEmpty() && files.any { it.name.substringAfterLast('.', "").lowercase() !in form.uploadExtensions }) { error = "所选附件类型不符合站点限制"; return }
            val merged = editorFiles + files.map { it.copy(slot = nextUploadSlot++) }
            if (merged.any { it.size > form.uploadMaxBytes }) { error = "附件超过站点大小限制"; return }
            editorFiles = merged
            markDraftChanged()
        } else formFiles = formFiles + ("${request.target}@${request.fieldId}" to files)
    }

fun AppController.requestFiles(target: String, fieldId: String = "", multiple: Boolean = true, mimeTypes: List<String> = listOf("*/*")) {
        if (!busy && !siteBusy && !purchaseBusy) filePickerRequest = FilePickerRequest(target, fieldId, multiple, mimeTypes)
    }

fun AppController.openSiteDestination(destination: SiteDestination) {
        if (siteBusy || purchaseBusy) return
        val path = destination.url.substringBefore('?').substringBefore('#').lowercase()
        if (destination.effect != SiteEffect.UNSUPPORTED && destination.url.startsWith("https://") && listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".avif", ".bmp").any { path.endsWith(it) }) {
            showImages(destination.url, listOf(destination.url)); return
        }
        if (destination.effect == SiteEffect.CHANGE && !SiteActionRisk.needsConfirmation(destination)) {
            if (destination.url.substringBefore('?').endsWith("kf_tidfavor.php")) favoriteThread(destination)
            else startSiteAction(null, destination)
            return
        }
        when (destination.effect) {
            SiteEffect.READ -> {
                if (destination.url.substringBefore('?').endsWith("login.php")) { showLogin = true; return }
                load(destination.url)
            }
            SiteEffect.UNSUPPORTED -> error = "这个操作暂未支持"
            else -> pendingSiteDestination = destination
        }
    }

fun AppController.openExternal(url: String) {
        if (siteBusy || purchaseBusy) return
        pendingSiteDestination = SiteDestination(url, SiteEffect.EXTERNAL, "在外部浏览器打开", "此链接将在外部应用打开，不附加论坛登录状态。")
    }

    // Kept as an internal source-compatible name; no WebView is opened.
fun AppController.openBrowser(url: String = page?.url ?: "index.php") {
        if (siteBusy || purchaseBusy) return
        val destination = service.destination(url)
        if (destination.url.substringBefore('#') == page?.url?.substringBefore('#')) showSiteTools = true else openSiteDestination(destination)
    }

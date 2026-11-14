package work.miaola.kf

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

fun AppController.checkEditorSubmission() {
        val message = editor?.recipientField != null
        editor = null; showEmoticonPicker = false
        load(if (message) "$origin/message.php?action=sendbox" else page?.url ?: "index.php", false)
    }

fun AppController.removeEditorFile(id: String) {
        editorFiles.firstOrNull { it.id == id }?.let { draftContent = draftContent.replace("[upload=${it.slot}]", "") }
        editorFiles = editorFiles.filterNot { it.id == id }
        markDraftChanged()
    }

fun AppController.dismissEditor() {
        editor?.let { recovery.removeDraft(it.sourceUrl) }
        draftJob?.cancel()
        editor = null; error = null; showEmoticonPicker = false
        draftTitle = ""; draftContent = ""; draftRecipient = ""; draftCategory = ""; draftKeywords = ""
        draftAttachmentNotice = null; draftRestored = false; pendingMentionName = null
        editorFiles = emptyList()
    }

fun AppController.submit() {
        if (busy || writeBusy ||
            pendingBatch != null || pendingReward != null || pendingAvatar != null || pendingSiteDestination != null || pendingSiteSubmission != null) return
        val form = editor ?: return
        if (submitUncertain) { error = "提交结果待确认，请先核对"; return }
        work {
            require(draftTitle.isNotBlank()) { "标题不能为空" }
            require(service.encodedLength(draftTitle) <= form.titleMaxBytes) { "标题超出 ${form.titleMaxBytes} 字节" }
            require(service.encodedLength(draftContent) in form.minBytes..form.maxBytes) { "正文需要 ${form.minBytes}–${form.maxBytes} 个 GBK 字节" }
            require(!form.categoryRequired || draftCategory !in listOf("", "0")) { "请选择帖子分类" }
            require(form.recipientField == null || draftRecipient.isNotBlank()) { "请输入收件人" }
            val values = mutableMapOf(form.titleField to draftTitle, form.contentField to draftContent)
            if (form.categories.isNotEmpty()) values["p_type"] = draftCategory
            form.recipientField?.let { values[it] = draftRecipient }
            if ("diy_guanjianci" in form.fields) values["diy_guanjianci"] = mergeMentionKeywords(draftKeywords, draftContent)
            when (val result = service.submitWithFiles(form, values, editorFiles)) {
                is SubmitResult.Success -> {
                    if (!it()) return@work
                    recovery.removeDraft(form.sourceUrl)
                    editor = null; editorFiles = emptyList(); draftContent = ""; draftTitle = ""; toast = result.message
                    val submitted = service.fetch(result.url ?: page?.url ?: "index.php")
                    if (!it()) return@work
                    applyFreshPage(submitted)
                    page = submitted; rememberCachedPage(submitted, result.url)
                }
                is SubmitResult.Rejected -> if (it()) error = result.message
                is SubmitResult.Uncertain -> if (it()) { submitUncertain = true; error = result.message }
            }
        }
    }

fun AppController.confirmMessageSend() {
        if (busy || siteBusy || purchaseBusy || batchBusy || rewardBusy || avatarBusy) return
        pendingMessageConfirm = false
        submit()
    }

fun AppController.requestEditorSubmit() {
        val form = editor ?: return
        if (busy || siteBusy || purchaseBusy || batchBusy || rewardBusy || avatarBusy) return
        if (form.recipientField != null && draftRecipient.isNotBlank() && !submitUncertain) pendingMessageConfirm = true else submit()
    }

fun AppController.openEditor(url: String) {
        if (busy || writeBusy) return
        if (resolve(url).effect != SiteEffect.READ) { toast = "请从发帖或回复入口打开编辑器"; return }
        if (!authenticated) { loginReturnUrl = page?.url?.let(::loginDestination); showLogin = true; return }
        work(editorRequest = true, loadsPage = true) {
            val form = service.editor(url)
            if (!it()) return@work
            editor = form
            editorFiles = emptyList()
            nextUploadSlot = 1
            showEmoticonPicker = false
            val saved = recovery.loadDraft(form.sourceUrl) ?: recovery.loadDraft(url)
            if (saved != null && saved.sourceUrl != form.sourceUrl) {
                recovery.saveDraft(saved.copy(sourceUrl = form.sourceUrl))
                recovery.removeDraft(saved.sourceUrl)
            }
            draftAttachmentNotice = null
            draftRestored = saved != null
            draftTitle = saved?.title ?: form.fields[form.titleField].orEmpty()
            draftContent = saved?.body ?: form.fields[form.contentField].orEmpty()
            draftRecipient = saved?.recipient ?: form.fields[form.recipientField].orEmpty()
            draftCategory = saved?.category ?: form.fields["p_type"].orEmpty()
            draftKeywords = saved?.keywords ?: form.fields["diy_guanjianci"].orEmpty()
            if (saved != null && saved.attachments.isNotEmpty()) {
                val names = saved.attachments.take(3).joinToString("、") { it.name }
                draftAttachmentNotice = "有 ${saved.attachments.size} 个附件需要重新选择（$names${if (saved.attachments.size > 3) " 等" else ""}）；原附件内容不会从上次会话恢复。"
            }
            pendingMentionName?.let { name ->
                pendingMentionName = null
                if (!draftContent.contains("@$name")) draftContent = "@$name " + draftContent
                if ("diy_guanjianci" in form.fields) draftKeywords = mergeMentionKeywords(draftKeywords, draftContent)
                scheduleDraftSave()
            }
            submitUncertain = false
        }
    }

fun AppController.openEditorWithMention(url: String, displayName: String) {
        if (displayName.isBlank()) { openEditor(url); return }
        pendingMentionName = displayName.trim().take(32)
        openEditor(url)
    }

fun AppController.flushRecovery() {
        recoveryJob?.cancel()
        runCatching { recovery.saveState(recoveryState) }
    }

fun AppController.flushDraft() {
        draftJob?.cancel()
        val form = editor ?: return
        runCatching {
            recovery.saveDraft(EditorDraft(
                sourceUrl = form.sourceUrl,
                title = draftTitle,
                body = draftContent,
                recipient = draftRecipient,
                category = draftCategory,
                keywords = draftKeywords,
                attachments = editorFiles.map { DraftAttachmentMeta(it.name.take(160), it.mimeType.take(80), it.size, it.slot) },
                updatedAt = currentEpochMillis(),
            ))
        }
    }

internal fun AppController.scheduleDraftSave() {
        val form = editor ?: return
        draftJob?.cancel()
        draftJob = scope.launch {
            delay(350)
            val draft = EditorDraft(
                sourceUrl = form.sourceUrl,
                title = draftTitle,
                body = draftContent,
                recipient = draftRecipient,
                category = draftCategory,
                keywords = draftKeywords,
                attachments = editorFiles.map { DraftAttachmentMeta(it.name.take(160), it.mimeType.take(80), it.size, it.slot) },
                updatedAt = currentEpochMillis(),
            )
            recovery.saveDraft(draft)
        }
    }

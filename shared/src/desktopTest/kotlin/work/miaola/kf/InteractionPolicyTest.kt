@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package work.miaola.kf

import kotlinx.coroutines.test.*
import kotlin.test.*

class InteractionPolicyTest {
    private val origin = "https://kf.miaola.work"
    private fun form(action: String = "$origin/diy_read_tui.php", id: String = "rpc:recommend") = SiteForm(
        id, "推荐此帖", "$origin/read.php?tid=1", action, "POST",
        listOf(FormValue("tid", "1"), FormValue("safeid", "synthetic")), emptyList(), listOf(SiteSubmit("推荐", null, "")))

    @Test fun onlyKnownLowRiskActionsSkipConfirmation() {
        assertFalse(SiteActionRisk.needsConfirmation(SiteDestination("$origin/kf_tidfavor.php?action=favor&tid=1", SiteEffect.CHANGE)))
        assertTrue(SiteActionRisk.needsConfirmation(SiteDestination("$origin/kf_tidfavor.php?action=del&tid=1", SiteEffect.CHANGE)))
        assertTrue(SiteActionRisk.needsConfirmation(SiteDestination("$origin/kf_tidfavor.php?action=del&action=favor&tid=1", SiteEffect.CHANGE)))
        listOf("message.php?action=del&mid=1", "login.php?action=quit", "job.php?action=buytopic&tid=1", "hack.php?H_name=bank&action=virement", "unknown.php?action=save", "kf_tidfavor.php?action=favor&tid=1&delid[]=2").forEach {
            assertTrue(SiteActionRisk.needsConfirmation(SiteDestination("$origin/$it", SiteEffect.CHANGE, "收藏")), it)
        }
        val recommend = form()
        assertFalse(SiteActionRisk.needsConfirmation(SiteSubmission(recommend, recommend.hidden, emptyList(), recommend.submits.single())))
        val impostor = form(action = "$origin/hack.php?H_name=bank")
        assertTrue(SiteActionRisk.needsConfirmation(SiteSubmission(impostor, impostor.hidden, emptyList(), impostor.submits.single())))
    }

    @Test fun recommendingIsDirectAndDoesNotNavigateAwayFromReader() = runTest {
        val factory = DemoFactory()
        val c = AppController(MemoryStore(), factory, backgroundScope, { _, _ -> })
        runCurrent(); c.load("read.php?tid=1"); runCurrent()
        val before = c.page
        val form = form()
        c.requestSiteSubmit(form, emptyMap(), form.submits.single()); runCurrent()
        assertEquals(1, factory.services.getValue(c.origin).siteSubmissions)
        assertNull(c.pendingSiteSubmission); assertNull(c.pendingNativePage)
        assertSame(before, c.page); assertEquals("已推荐", c.toast)
        factory.services.getValue(c.origin).siteResult = SiteActionResult.Response(before!!.copy(notice = "今天已经推荐过此帖"))
        c.requestSiteSubmit(form, emptyMap(), form.submits.single()); runCurrent()
        assertEquals("今天已经推荐过此帖", c.toast)
        c.openSiteDestination(c.resolve("message.php?action=del&mid=1")); runCurrent()
        assertNotNull(c.pendingSiteDestination)
        assertEquals(0, factory.services.getValue(c.origin).executeCount)
        c.close()
    }

    @Test fun editorOnlyDiscardsOnActualChangesIncludingAttachmentsAndRecipient() = runTest {
        val c = AppController(MemoryStore(), DemoFactory(), backgroundScope, { _, _ -> })
        runCurrent(); c.openEditor("post.php?fid=1"); runCurrent()
        assertFalse(c.editorDirty)
        c.requestBack(); assertNull(c.editor); assertFalse(c.confirmDiscard)
        c.openEditor("post.php?fid=1"); runCurrent()
        c.draftRecipient = "收件人"
        assertTrue(c.editorDirty)
        c.requestBack(); assertTrue(c.confirmDiscard); assertNotNull(c.editor)
        c.confirmDiscard = false; c.draftRecipient = ""
        c.receiveFiles(FilePickerRequest("editor", "", true), listOf(LocalUpload("one", "one.png", "image/png", byteArrayOf(1))))
        assertTrue(c.editorDirty)
        c.removeEditorFile("one"); assertFalse(c.editorDirty)
        c.close()
    }

    @Test fun profilePreferencesSaveDirectlyButPasswordAndUnknownHiddenActionsStayGated() {
        val fields = listOf(SiteField("signature", "prohonor", "签名", SiteFieldKind.TEXT),
            SiteField("password", "propwd", "新密码", SiteFieldKind.PASSWORD))
        val settings = SiteForm("profile.php:0", "修改资料", "$origin/profile.php?action=modify", "$origin/profile.php?action=modify", "POST",
            listOf(FormValue("step", "2")), fields, listOf(SiteSubmit("保存", null, "")))
        fun submission(form: SiteForm, password: String = "") = SiteSubmission(form, form.hidden + listOf(FormValue("prohonor", "示例"), FormValue("propwd", password)), emptyList(), form.submits.single())
        assertFalse(SiteActionRisk.needsConfirmation(submission(settings)))
        assertTrue(SiteActionRisk.needsConfirmation(submission(settings, "synthetic")))
        assertTrue(SiteActionRisk.needsConfirmation(submission(settings.copy(hidden = listOf(FormValue("job", "delete"))))))
    }

    @Test fun editorCannotBeUsedToFetchAMutation() = runTest {
        val c = AppController(MemoryStore(), DemoFactory(), backgroundScope, { _, _ -> })
        runCurrent()
        c.openEditor("post.php?action=reply&step=2&tid=1"); runCurrent()
        assertNull(c.editor)
        assertFalse(c.loadingEditor)
        c.close()
    }

    @Test fun favoriteManagementReturnsToTheFolderWithoutOpeningGenericReceipt() = runTest {
        val factory = DemoFactory()
        val c = AppController(MemoryStore(), factory, backgroundScope, { _, _ -> })
        runCurrent()
        val url = "${c.origin}/profile.php?action=favor"
        c.load(url); runCurrent()
        val form = SiteForm("favorites", "管理收藏", url, url, "POST", emptyList(),
            listOf(SiteField("entry", "delid[]", "示例收藏", SiteFieldKind.CHECKBOX, emptyList(), listOf(FormOption("1", "示例收藏")))),
            listOf(SiteSubmit("删除", null, "")))
        c.requestSiteSubmit(form, mapOf("entry" to listOf("1")), form.submits.single()); runCurrent()
        assertNotNull(c.pendingSiteSubmission)
        c.confirmSiteAction(); runCurrent()
        assertEquals(url, c.page?.url); assertNull(c.pendingNativePage); assertFalse(c.showSiteTools)
        assertEquals(1, factory.services.getValue(c.origin).siteSubmissions)
        c.close()
    }

    @Test fun uncertainPrivateMessageCannotBeSentAgain() = runTest {
        val demo = DemoService(origin).apply { result = SubmitResult.Uncertain("请先查看发件箱核对") }
        val client = object : ForumService by demo {
            override suspend fun editor(url: String) = EditorForm(url, "$origin/message.php", mapOf("step" to "2"),
                "msg_title", recipientField = "pwuser", minBytes = 1)
        }
        val factory = object : ServiceFactory {
            override fun create(origin: String) = client
            override suspend fun catalog(url: String): String = error("offline")
        }
        val c = AppController(MemoryStore(), factory, backgroundScope, { _, _ -> })
        runCurrent(); c.openEditor("$origin/message.php?action=write"); runCurrent()
        c.draftTitle = "示例消息"; c.draftContent = "示例正文"; c.draftRecipient = "示例收件人"
        c.submit(); runCurrent(); c.submit(); runCurrent()
        assertTrue(c.submitUncertain); assertEquals(1, demo.submissions)
        c.close()
    }
}

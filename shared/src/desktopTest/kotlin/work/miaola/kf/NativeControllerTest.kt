@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package work.miaola.kf

import kotlin.test.*
import kotlinx.coroutines.test.*

class NativeControllerTest {
    @Test fun internalSiteRoutesDoNotLaunchBrowserAndExternalLinksRequireConfirmation() = runTest {
        val opened = mutableListOf<String>()
        val c = AppController(MemoryStore(), DemoFactory(), backgroundScope, browser = { _, url -> opened.add(url) })
        runCurrent()
        c.openBrowser("profile.php?action=show&uid=1"); runCurrent()
        assertTrue(opened.isEmpty())
        c.openBrowser("https://example.com/resource")
        assertTrue(opened.isEmpty()); assertEquals(SiteEffect.EXTERNAL, c.pendingSiteDestination?.effect)
        c.confirmSiteAction(); assertEquals(listOf("https://example.com/resource"), opened)
        c.close()
    }
    @Test fun mutationLinksAreNotFetchedAsReadOnlyNavigation() = runTest {
        val c = AppController(MemoryStore(), DemoFactory(), backgroundScope, browser = { _, _ -> error("Unexpected browser") })
        runCurrent(); val before = c.page
        c.navigate("profile.php?action=modify&step=2"); runCurrent()
        assertEquals(before, c.page); assertEquals(SiteEffect.CHANGE, c.pendingSiteDestination?.effect)
        c.dismissSiteAction(); c.close()
    }
    @Test fun attachmentSlotsStayStableAndDeletingAttachmentRemovesItsCode() = runTest {
        val c = AppController(MemoryStore(), DemoFactory(), backgroundScope, browser = { _, _ -> })
        runCurrent(); c.openEditor("post.php?fid=1"); runCurrent()
        val request = FilePickerRequest("editor", "", true)
        c.receiveFiles(request, listOf(LocalUpload("one", "one.png", "image/png", byteArrayOf(1)), LocalUpload("two", "two.png", "image/png", byteArrayOf(2))))
        assertEquals(listOf(1, 2), c.editorFiles.map { it.slot })
        c.draftContent = "[upload=1] [upload=2]"
        c.removeEditorFile("one")
        assertEquals(listOf(2), c.editorFiles.map { it.slot }); assertFalse(c.draftContent.contains("[upload=1]"))
        c.receiveFiles(request, listOf(LocalUpload("three", "three.png", "image/png", byteArrayOf(3))))
        assertEquals(listOf(2, 3), c.editorFiles.map { it.slot })
        c.close()
    }
    @Test fun imageClickOpensGalleryAndBackClosesItWithoutLeavingThread() = runTest {
        val c = AppController(MemoryStore(), DemoFactory(), backgroundScope, browser = { _, _ -> })
        runCurrent(); c.load("read.php?tid=1"); runCurrent()
        val current = c.page
        val urls = listOf("https://kf.miaola.work/a.jpg", "https://kf.miaola.work/b.jpg")
        c.showImages(urls[1], urls + urls[0])
        assertEquals(2, c.gallery?.urls?.size); assertEquals(1, c.gallery?.initialIndex)
        c.requestBack(); assertNull(c.gallery); assertEquals(current, c.page); assertTrue(c.canBack)
        c.close()
    }
    @Test fun domainSwitchClearsImageAndUploadState() = runTest {
        val c = AppController(MemoryStore(), DemoFactory(), backgroundScope, browser = { _, _ -> })
        runCurrent(); c.showImages("https://kf.miaola.work/a.jpg", listOf("https://kf.miaola.work/a.jpg"))
        c.switchDomain("https://0.kf.miaola.work"); runCurrent()
        assertNull(c.gallery); assertTrue(c.editorFiles.isEmpty()); assertTrue(c.formFiles.isEmpty())
        c.close()
    }

    @Test fun backgroundConnectionResetStaysSilentAndKeepsSession() = runTest {
        val factory = DemoFactory()
        val c = AppController(MemoryStore(), factory, backgroundScope, browser = { _, _ -> })
        runCurrent()
        factory.services.getValue(c.origin).fetchFailure = java.io.IOException("Connection reset")
        c.resumeSession(); runCurrent()
        assertTrue(c.authenticated)
        assertFalse(c.showLogin)
        assertNull(c.error)
        c.close()
    }

    @Test fun foregroundLoadShowsFriendlyTextNotSocketError() = runTest {
        val factory = DemoFactory()
        val c = AppController(MemoryStore(), factory, backgroundScope, browser = { _, _ -> })
        runCurrent()
        factory.services.getValue(c.origin).fetchFailure = java.io.IOException("Connection reset")
        c.load("thread.php?fid=1"); runCurrent()
        val error = c.error.orEmpty()
        assertFalse(error.contains("Connection", true))
        assertFalse(error.contains("reset", true))
        c.close()
    }

    @Test fun rawSocketTextIsNeverShownToUsers() {
        val message = friendlyError(java.io.IOException("Connection reset"))
        assertFalse(message.contains("Connection", true))
        assertFalse(message.contains("reset", true))
        assertEquals("登录已失效，请重新登录", friendlyError(IllegalStateException("登录已失效，请重新登录")))
        val chineseSocket = friendlyError(java.net.SocketTimeoutException("连接超时"))
        assertFalse(chineseSocket.contains("连接超时"))
        assertFalse(friendlyError(java.io.IOException("连接被重置")).contains("连接被重置"))
    }
}

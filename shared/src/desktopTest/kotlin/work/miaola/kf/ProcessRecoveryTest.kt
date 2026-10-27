@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package work.miaola.kf

import kotlinx.coroutines.test.*
import kotlin.test.*

class ProcessRecoveryTest {
    private val origin = "https://kf.miaola.work"
    private val parser = ForumHtmlParser(origin)

    @Test fun draftRoundTripsContentButNeverHiddenFieldsOrBytes() {
        val store = MemoryStore()
        val recovery = RecoveryStore(store, origin)
        recovery.saveDraft(EditorDraft(
            sourceUrl = "$origin/post.php?action=reply&fid=1&tid=2",
            title = "合成标题",
            body = "合成正文",
            recipient = "合成收件人",
            category = "1",
            keywords = "合成关键词",
            attachments = listOf(DraftAttachmentMeta("合成图片.png", "image/png", 1234, 1)),
            updatedAt = currentEpochMillis(),
        ))
        val restored = assertNotNull(recovery.loadDraft("$origin/post.php?action=reply&fid=1&tid=2"))
        assertEquals("合成正文", restored.body)
        assertEquals("合成收件人", restored.recipient)
        assertEquals(1, restored.attachments.size)
        assertFalse(store.values.toString().contains("verify"))
        assertFalse(store.values.toString().contains("cookies@"))
        assertEquals(null, recovery.loadDraft("$origin/post.php?action=reply&fid=1&tid=3"))
    }

    @Test fun staleOrWrongOriginDraftsAndStatesAreRejected() {
        val store = MemoryStore()
        val recovery = RecoveryStore(store, origin)
        recovery.saveDraft(EditorDraft(sourceUrl = "$origin/post.php?fid=1", body = "旧草稿",
            updatedAt = currentEpochMillis() - RecoveryStore.MAX_DRAFT_AGE_MS - 1))
        assertNull(recovery.loadDraft("$origin/post.php?fid=1"))
        val state = RecoveryState(origin = origin, destination = DestinationRecovery("READER", "$origin/read.php?tid=1"),
            timelines = mapOf("$origin/read.php?tid=1" to TimelineRecovery("$origin/read.php?tid=1",
                listOf("$origin/read.php?tid=1", "https://evil.test/read.php?page=2"), anchorIndex = 4)))
        recovery.saveState(state)
        val loaded = assertNotNull(recovery.loadState())
        assertEquals(listOf("$origin/read.php?tid=1"), loaded.timelines.values.single().loadedPageUrls)
        assertEquals("READER", loaded.destination.kind)
        assertNull(RecoveryStore(store, "https://0.kf.miaola.work").loadState())
    }

    @Test fun uncertainManagementPageSurvivesControllerRecreationWithoutReplayingMutation() = runTest {
        val store = MemoryStore()
        val source = "$origin/message.php?action=receivebox&page=2"
        RecoveryStore(store, origin).saveState(RecoveryState(origin = origin, pendingManagement = listOf(source),
            updatedAt = currentEpochMillis()))
        val demo = DemoService(origin)
        var writes = 0
        val client = object : ForumService by demo {
            override suspend fun submitSite(submission: SiteSubmission): SiteActionResult {
                writes++
                return SiteActionResult.Response(demo.home)
            }
        }
        val factory = object : ServiceFactory {
            override fun create(origin: String) = client
            override suspend fun catalog(url: String) = error("offline")
        }
        val controller = AppController(store, factory, backgroundScope, { _, _ -> })
        runCurrent()
        assertEquals(source, controller.managementRecoveryFor(source))
        assertEquals(0, writes)
        controller.close()
    }

    @Test fun restoredDraftWarnsThatAttachmentsMustBeSelectedAgain() = runTest {
        val store = MemoryStore()
        val url = "$origin/post.php?action=reply&fid=1&tid=2"
        RecoveryStore(store, origin).saveDraft(EditorDraft(sourceUrl = url, title = "草稿标题", body = "草稿正文",
            attachments = listOf(DraftAttachmentMeta("旧附件.png", "image/png", 8, 1)), updatedAt = currentEpochMillis()))
        val demo = DemoService(origin)
        val factory = object : ServiceFactory {
            override fun create(origin: String) = demo
            override suspend fun catalog(url: String) = error("offline")
        }
        val c = AppController(store, factory, backgroundScope, { _, _ -> })
        runCurrent(); c.openEditor(url); runCurrent()
        assertTrue(c.draftRestored)
        assertEquals("草稿标题", c.draftTitle)
        assertEquals("草稿正文", c.draftContent)
        assertTrue(c.editorFiles.isEmpty())
        assertNotNull(c.draftAttachmentNotice)
        assertTrue(c.draftAttachmentNotice!!.contains("重新选择"))
        c.close()
    }

    @Test fun loadedPagesAndAnchorCanBeRebuiltWithoutFetchingTheWholeThread() = runTest {
        val demo = DemoService(origin)
        val root = demo.home.copy(url = "$origin/read.php?tid=1", next = "$origin/read.php?tid=1&page=2")
        var requests = mutableListOf<String>()
        val client = object : ForumService by demo {
            override suspend fun fetch(url: String): PageData {
                if (url.startsWith(root.url)) {
                    requests.add(url)
                    val page = Regex("page=([0-9]+)").find(url)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    return root.copy(url = url, next = if (page < 5) "$origin/read.php?tid=1&page=${page + 1}" else null,
                        posts = listOf(ForumPost("pid$page", "作者", "", "$page 楼", "", "<p>第 $page 页</p>")))
                }
                return demo.fetch(url)
            }
        }
        val factory = object : ServiceFactory {
            override fun create(origin: String) = client
            override suspend fun catalog(url: String) = error("offline")
        }
        val store = MemoryStore()
        val first = AppController(store, factory, backgroundScope, { _, _ -> })
        runCurrent(); first.load(root.url); runCurrent()
        val timeline = first.timelineFor(first.page!!)
        first.loadNext(first.page!!); runCurrent()
        first.loadNext(first.page!!); runCurrent()
        first.rememberAnchor(root.url, 3, 120, "pid2")
        advanceTimeBy(600); runCurrent()
        assertEquals(3, timeline.loadedPageUrls.size)
        first.close()

        requests = mutableListOf()
        val second = AppController(store, factory, backgroundScope, { _, _ -> })
        runCurrent(); second.ensurePage(root.url); runCurrent()
        advanceTimeBy(1000); runCurrent()
        val restored = second.timelineFor(second.page!!)
        assertEquals(listOf("$origin/read.php?tid=1", "$origin/read.php?tid=1&page=2", "$origin/read.php?tid=1&page=3"),
            restored.loadedPageUrls)
        assertEquals("pid2", second.anchorFor(root.url)?.anchorKey)
        assertEquals(3, second.anchorFor(root.url)?.anchorIndex)
        assertTrue(requests.size <= 3)
        second.close()
    }
}

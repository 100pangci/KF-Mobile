@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package work.miaola.kf

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/** All mutations here are fake; no live identifiers, HTML or network. */
class ManagementRecoveryTest {
    private val origin = "https://kf.miaola.work"
    private val parser = ForumHtmlParser(origin)
    private fun source(favorites: Boolean) = if (favorites) "$origin/profile.php?action=favor&type=1&page=2" else "$origin/message.php?action=sendbox&page=2"
    private fun page(url: String): PageData {
        val parsed = parser.parse("""<div class='dcol' style='width:900px'><form method='post' action='${if (url.contains("profile.php")) "profile.php?action=favor" else "message.php"}'>
            <input name='verify' type='hidden' value='synthetic'><table><tr><th>标题</th><th>选定</th></tr>
            <tr><td><a href='read.php?tid=1'>合成项目</a></td><td><input name='delid[]' type='checkbox' value='1'></td></tr></table>
            <input type='submit' value='删除'></form></div>""", url)
        return parsed.copy(authenticated = true, authStatus = AuthStatus.AUTHENTICATED, next = url.replace("page=2", "page=3"),
            nativePage = parsed.nativePage!!.copy(messageList = url.contains("message.php")))
    }
    private class Scenario(val first: PageData, private val demo: DemoService) : ForumService by demo {
        var writes = 0
        var failRead = false
        var readResult: PageData = first
        var response: SiteActionResult = SiteActionResult.Response(demo.home)
        var lateTail = false
        var lateRead = false
        val reads = mutableListOf<String>()
        override suspend fun fetch(url: String): PageData = when (url) {
            first.url -> { reads.add(url); if (failRead) error("合成读回失败"); if (lateRead) withContext(NonCancellable) { delay(500); readResult } else readResult }
            first.next -> if (lateTail) withContext(NonCancellable) {
                delay(500); first.copy(url = url, next = null, topics = listOf(SiteLink("过期尾页", "${demo.origin}/read.php?tid=2")))
            } else first.copy(url = url, next = null)
            else -> demo.fetch(url)
        }
        override suspend fun submitSite(submission: SiteSubmission): SiteActionResult { writes++; return response }
    }
    private fun controller(client: ForumService, scope: CoroutineScope) = AppController(MemoryStore(), object : ServiceFactory {
        override fun create(origin: String) = client
        override suspend fun catalog(url: String): String = error("offline")
    }, scope, { _, _ -> })
    private fun submit(c: AppController, form: SiteForm) {
        c.requestSiteSubmit(form, form.fields.associate { it.id to it.options.map { option -> option.value } }, form.submits.single())
        if (c.pendingBatch != null) c.confirmBatch() else c.confirmSiteAction()
    }

    @Test fun responseWithFailedReadbackInvalidatesTailsAndBlocksRepeatUntilReadOnlyRecovery() = runTest {
        listOf(false, true).forEach { favorites ->
            val first = page(source(favorites)); val client = Scenario(first, DemoService(origin))
            val c = controller(client, backgroundScope)
            runCurrent(); c.load(first.url); runCurrent()
            val state = c.timelineFor(c.page!!)
            c.loadNext(first); runCurrent(); assertEquals(2, state.nativePages.size)
            client.failRead = true
            val form = first.nativePage!!.forms.single()
            submit(c, form); runCurrent()
            assertTrue(state.managementStale); assertEquals(1, state.nativePages.size); assertNull(state.next)
            assertNull(c.pageFor(first.next!!)); assertNotNull(c.managementRecoveryFor(first.url))
            assertFalse(c.toast == "操作已完成")
            assertIs<BatchTransactionResult.Uncertain>(c.batchResult)
            submit(c, form); runCurrent(); c.loadNext(first, retry = true); runCurrent()
            assertEquals(1, client.writes)
            c.recoverManagement(first.url); runCurrent(); assertNotNull(c.managementRecoveryFor(first.url))
            client.failRead = false
            c.recoverManagement(first.url); runCurrent()
            assertNull(c.managementRecoveryFor(first.url)); assertFalse(state.managementStale)
            assertEquals(1, client.writes); assertEquals(first.url, client.reads.last())
            assertTrue(c.toast!!.contains("未重复提交")); assertEquals(first.url, c.pageFor(first.url)?.url)
            c.close()
        }
    }

    @Test fun uncertainWriteClearsSelectionContextButNeverAutomaticallyReadsOrReplays() = runTest {
        val first = page(source(false)); val client = Scenario(first, DemoService(origin)).apply { response = SiteActionResult.Uncertain("合成未知结果") }
        val c = controller(client, backgroundScope)
        runCurrent(); c.load(first.url); runCurrent()
        val reads = client.reads.size
        submit(c, first.nativePage!!.forms.single()); runCurrent()
        assertEquals(reads, client.reads.size); assertEquals(1, c.managementRevision)
        assertNotNull(c.managementRecoveryFor(first.url)); assertNull(c.pendingSiteSubmission)
        val stale = c.timelineFor(first)
        assertTrue(stale.managementStale)
        c.loadNext(first, retry = true); runCurrent(); assertEquals(reads, client.reads.size)
        c.recoverManagement(first.url); runCurrent()
        assertEquals(reads + 1, client.reads.size); assertEquals(1, client.writes)
        c.close()
    }

    @Test fun cancellationDuringDirectManagedMutationPersistsReadOnlyRecoveryGate() = runTest {
        val store = MemoryStore()
        val first = page(source(false))
        val demo = DemoService(origin)
        val entered = CompletableDeferred<Unit>()
        val client = object : ForumService by Scenario(first, demo) {
            override suspend fun submitSite(submission: SiteSubmission): SiteActionResult {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val c = AppController(store, object : ServiceFactory {
            override fun create(origin: String) = client
            override suspend fun catalog(url: String): String = error("offline")
        }, backgroundScope, { _, _ -> })
        runCurrent()
        c.load(first.url); runCurrent()
        val form = first.nativePage!!.forms.single().copy(fields = emptyList())
        c.requestSiteSubmit(form, emptyMap(), form.submits.single()); runCurrent()
        c.confirmSiteAction(); runCurrent(); entered.await()
        c.close(); runCurrent()

        assertNotNull(c.managementRecoveryFor(first.url))
        assertTrue(RecoveryStore(store, origin).loadPendingBatch()?.sourcePages.orEmpty()
            .any { isSamePageList(it, first.url) })
        val restarted = AppController(store, object : ServiceFactory {
            override fun create(origin: String) = client
            override suspend fun catalog(url: String): String = error("offline")
        }, backgroundScope, { _, _ -> })
        runCurrent()
        assertNotNull(restarted.managementRecoveryFor(first.url))
        restarted.close()
    }

    @Test fun rejectedFreshRevalidationKeepsOtherPageSelectionsAndLoadedData() = runTest {
        val first = page(source(false)); val client = Scenario(first, DemoService(origin)).apply { response = SiteActionResult.Rejected("所选项目已变化，未提交") }
        val c = controller(client, backgroundScope)
        runCurrent(); c.load(first.url); runCurrent(); val state = c.timelineFor(c.page!!)
        c.loadNext(first); runCurrent()
        submit(c, first.nativePage!!.forms.single()); runCurrent()
        assertEquals(0, c.managementRevision); assertTrue(c.managementRecoveries.isEmpty())
        assertEquals(2, state.nativePages.size); assertFalse(state.managementStale)
        c.close()
    }

    @Test fun recoveryRejectsOtherListAndLoginWallWithoutUnlockingWrites() = runTest {
        val first = page(source(false)); val client = Scenario(first, DemoService(origin)).apply { response = SiteActionResult.Uncertain("合成未知结果") }
        val c = controller(client, backgroundScope)
        runCurrent(); c.load(first.url); runCurrent(); submit(c, first.nativePage!!.forms.single()); runCurrent()
        client.readResult = first.copy(url = "$origin/message.php?action=receivebox")
        c.recoverManagement(first.url); runCurrent(); assertNotNull(c.managementRecoveryFor(first.url))
        client.readResult = first.copy(nativePage = NativeSitePage("非列表响应"), topics = emptyList())
        c.recoverManagement(first.url); runCurrent(); assertNotNull(c.managementRecoveryFor(first.url))
        client.readResult = first.copy(authenticated = false, authStatus = AuthStatus.UNKNOWN)
        c.recoverManagement(first.url); runCurrent(); assertNotNull(c.managementRecoveryFor(first.url))
        client.readResult = PageData("$origin/login.php", "登录", false, authStatus = AuthStatus.LOGIN_REQUIRED)
        c.recoverManagement(first.url); runCurrent()
        assertTrue(c.showLogin); assertNotNull(c.managementRecoveryFor(first.url)); assertEquals(1, client.writes)
        c.close()
    }

    @Test fun cancelledRecoveryCannotUnlockOrReplaceANewerDestination() = runTest {
        val first = page(source(false)); val client = Scenario(first, DemoService(origin)).apply { response = SiteActionResult.Uncertain("合成未知结果") }
        val c = controller(client, backgroundScope)
        runCurrent(); c.load(first.url); runCurrent(); submit(c, first.nativePage!!.forms.single()); runCurrent()
        client.lateRead = true
        c.recoverManagement(first.url); runCurrent(); c.load("read.php?tid=1"); runCurrent()
        advanceTimeBy(501); runCurrent()
        assertTrue(c.page!!.url.contains("read.php")); assertNotNull(c.managementRecoveryFor(first.url)); assertEquals(1, client.writes)
        c.close()
    }

    @Test fun mutationInvalidatesAnUncooperativePreWriteAppend() = runTest {
        val first = page(source(false)); val client = Scenario(first, DemoService(origin)).apply { lateTail = true; response = SiteActionResult.Uncertain("合成未知结果") }
        val c = controller(client, backgroundScope)
        runCurrent(); c.load(first.url); runCurrent(); val state = c.timelineFor(c.page!!)
        c.loadNext(first); runCurrent(); submit(c, first.nativePage!!.forms.single()); runCurrent()
        advanceTimeBy(501); runCurrent()
        assertTrue(state.managementStale); assertFalse(state.topics.any { it.title == "过期尾页" }); assertNull(c.pageFor(first.next!!))
        c.close()
    }

    @Test fun revalidationRejectsAmbiguousCheckboxesReadonlyChangesAndSingleSelectDowngrades() {
        val old = page(source(false)).nativePage!!.forms.single()
        val submitted = SiteSubmission(old, buildSiteFormValues(old, old.fields.associate { it.id to listOf("1") }, old.submits.single()), emptyList(), old.submits.single())
        val field = old.fields.single()
        val ambiguous = old.copy(fields = listOf(field, field.copy(id = "another")))
        val changedReadonly = old.copy(fields = listOf(field.copy(editable = false)))
        listOf(ambiguous, changedReadonly, old.copy(sourceUrl = source(true))).forEach { fresh ->
            assertFailsWith<IllegalArgumentException> { revalidatedSiteValues(submitted, fresh, fresh.submits.single()) }
        }
        val multi = old.copy(fields = listOf(field.copy(kind = SiteFieldKind.SELECT, multiple = true, options = listOf(FormOption("1", "一"), FormOption("2", "二")))))
        val intent = SiteSubmission(multi, buildSiteFormValues(multi, mapOf(field.id to listOf("1", "2")), multi.submits.single()), emptyList(), multi.submits.single())
        val single = multi.copy(fields = multi.fields.map { it.copy(multiple = false) })
        assertFailsWith<IllegalArgumentException> { revalidatedSiteValues(intent, single, single.submits.single()) }
    }
}

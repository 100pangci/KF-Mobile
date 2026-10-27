@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package work.miaola.kf

import kotlinx.coroutines.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.*

class BatchTransactionTest {
    private val origin = "https://kf.miaola.work"
    private val parser = ForumHtmlParser(origin)

    private fun form(page: Int, values: List<String>): SiteForm {
        val inputs = values.joinToString("") { "<label><input type='checkbox' name='delid[]' value='$it'>项目 $it</label>" }
        return parser.parse("""<div class='dcol' style='width:900px'><table><tr><th>标题</th><th>选择</th></tr></table>
            <form action='message.php' method='post'><input name='towhere' type='hidden' value='receivebox'>
            <input name='verify' type='hidden' value='synthetic-$page'>$inputs<input type='submit' value='删除'></form></div>""",
            "$origin/message.php?action=receivebox&page=$page").nativePage!!.forms.single()
    }

    private fun fresh(source: String, remaining: List<String> = emptyList()): PageData {
        val inputs = remaining.joinToString("") { "<label><input type='checkbox' name='delid[]' value='$it'>项目 $it</label>" }
        val parsed = parser.parse("""<div class='dcol' style='width:900px'><table><tr><th>标题</th><th>收件人</th></tr></table>
            <form action='message.php' method='post'><input name='towhere' type='hidden' value='receivebox'>
            <input name='verify' type='hidden' value='fresh'>$inputs<input type='submit' value='删除'></form></div>""", source)
        return parsed.copy(authenticated = true, authStatus = AuthStatus.AUTHENTICATED,
            nativePage = parsed.nativePage!!.copy(messageList = true))
    }

    private fun controller(client: ForumService, store: MemoryStore = MemoryStore(), scope: TestScope): AppController =
        AppController(store, object : ServiceFactory {
            override fun create(origin: String) = client
            override suspend fun catalog(url: String) = error("offline")
        }, scope.backgroundScope, { _, _ -> })

    private fun request(page: Int, values: List<String>, verifyAbsent: Boolean = true): BatchPageRequest =
        assertNotNull(buildBatchPageRequest(form(page, values), values.toSet(), verifyAbsent = verifyAbsent))

    @Test fun allPagesCanCompleteAndEachSourceIsSubmittedOnce() = runTest {
        val client = object : ForumService by DemoService(origin) {
            var writes = mutableListOf<String>()
            override suspend fun submitSite(submission: SiteSubmission): SiteActionResult {
                writes.add(submission.form.sourceUrl)
                return SiteActionResult.Response(fresh(submission.form.sourceUrl))
            }
        }
        val c = controller(client, scope = this); runCurrent()
        c.requestBatchSiteSubmit(listOf(request(1, listOf("1", "2")), request(2, listOf("3"))))
        assertNotNull(c.pendingBatch)
        c.confirmBatch(); runCurrent()
        val result = assertIs<BatchTransactionResult.Completed>(c.batchResult)
        assertEquals(3, result.results().count { it.outcome == OperationOutcome.SUCCESS })
        assertEquals(2, client.writes.size)
        assertEquals(2, c.managementRevision)
        c.close()
    }

    @Test fun genericMutationReceiptRequiresExplicitSourceListReadback() = runTest {
        val demo = DemoService(origin)
        var readbacks = 0
        val client = object : ForumService by demo {
            override suspend fun submitSite(submission: SiteSubmission): SiteActionResult =
                SiteActionResult.Response(demo.home.copy(notice = "操作完成"))

            override suspend fun fetch(url: String): PageData {
                if (url.contains("message.php?action=receivebox")) {
                    readbacks++
                    return fresh(url)
                }
                return demo.fetch(url)
            }
        }
        val c = controller(client, scope = this); runCurrent()
        c.requestBatchSiteSubmit(listOf(request(1, listOf("1"))))
        c.confirmBatch(); runCurrent()
        assertIs<BatchTransactionResult.Completed>(c.batchResult)
        assertEquals(1, readbacks)
        c.close()
    }

    @Test fun partialFailureKeepsFailedItemsAndContinuesOtherPages() = runTest {
        val client = object : ForumService by DemoService(origin) {
            var writes = 0
            override suspend fun submitSite(submission: SiteSubmission): SiteActionResult {
                writes++
                return SiteActionResult.Response(fresh(submission.form.sourceUrl, if (submission.form.sourceUrl.contains("page=1")) listOf("1") else emptyList()))
            }
        }
        val c = controller(client, scope = this); runCurrent()
        c.requestBatchSiteSubmit(listOf(request(1, listOf("1", "2")), request(2, listOf("3")))); c.confirmBatch(); runCurrent()
        val result = assertIs<BatchTransactionResult.Partial>(c.batchResult)
        val results = result.results()
        assertEquals(2, results.count { it.outcome == OperationOutcome.SUCCESS })
        assertEquals(1, results.count { it.outcome == OperationOutcome.REJECTED })
        assertEquals("1", results.first { it.outcome == OperationOutcome.REJECTED }.item.stableId)
        assertEquals(2, client.writes)
        c.close()
    }

    @Test fun pageBoundaryShiftCannotBeMisreadAsEverySelectedItemDeleted() = runTest {
        val client = object : ForumService by DemoService(origin) {
            override suspend fun submitSite(submission: SiteSubmission): SiteActionResult =
                SiteActionResult.Response(fresh(submission.form.sourceUrl).copy(
                    url = submission.form.sourceUrl.replace("page=2", "page=1")))
        }
        val c = controller(client, scope = this); runCurrent()
        c.requestBatchSiteSubmit(listOf(request(2, listOf("2", "3"))))
        c.confirmBatch(); runCurrent()
        val result = assertIs<BatchTransactionResult.Uncertain>(c.batchResult)
        assertEquals(2, result.results().count { it.outcome == OperationOutcome.UNCERTAIN })
        assertTrue(result.results().all { it.message.contains("分页边界") })
        assertNotNull(c.managementRecoveryFor("$origin/message.php?action=receivebox&page=2"))
        c.close()
    }

    @Test fun uncertainStopsRemainingPagesAndNeverRetries() = runTest {
        val client = object : ForumService by DemoService(origin) {
            var writes = 0
            override suspend fun submitSite(submission: SiteSubmission): SiteActionResult {
                writes++
                return SiteActionResult.Uncertain("合成未知回执")
            }
        }
        val c = controller(client, scope = this); runCurrent()
        c.requestBatchSiteSubmit(listOf(request(1, listOf("1")), request(2, listOf("2")))); c.confirmBatch(); runCurrent()
        val result = assertIs<BatchTransactionResult.Uncertain>(c.batchResult)
        assertEquals(1, client.writes)
        assertEquals(1, result.results().count { it.outcome == OperationOutcome.UNCERTAIN })
        assertEquals(1, result.results().count { it.outcome == OperationOutcome.NOT_ATTEMPTED })
        assertNotNull(c.managementRecoveryFor("$origin/message.php?action=receivebox&page=1"))
        c.close()
    }

    @Test fun allFailuresAndDuplicateRequestsAreReportedWithoutWrites() = runTest {
        val client = object : ForumService by DemoService(origin) {
            var writes = 0
            override suspend fun submitSite(submission: SiteSubmission): SiteActionResult {
                writes++; return SiteActionResult.Rejected("站点拒绝")
            }
        }
        val c = controller(client, scope = this); runCurrent()
        val req = request(1, listOf("1"))
        c.requestBatchSiteSubmit(listOf(req, req))
        assertNull(c.pendingBatch); assertNotNull(c.error)
        c.requestBatchSiteSubmit(listOf(request(1, listOf("1")), request(2, listOf("1"))))
        c.confirmBatch(); runCurrent()
        assertIs<BatchTransactionResult.Failed>(c.batchResult)
        assertEquals(2, client.writes)
        c.close()
    }

    @Test fun staleBatchMarkerFromProcessDeathForcesReadOnlyRecoveryAndBlocksReplay() = runTest {
        val store = MemoryStore()
        RecoveryStore(store, origin).savePendingBatch(PendingBatch(
            sourcePages = listOf("$origin/message.php?action=receivebox&page=1"),
            itemIds = listOf("$origin/message.php?action=receivebox&page=1#1"), createdAt = currentEpochMillis()))
        val client = object : ForumService by DemoService(origin) {
            var writes = 0
            override suspend fun submitSite(submission: SiteSubmission): SiteActionResult { writes++; return SiteActionResult.Response(fresh(submission.form.sourceUrl)) }
        }
        val c = controller(client, store, this); runCurrent()
        assertNotNull(c.managementRecoveryFor("$origin/message.php?action=receivebox&page=1"))
        c.requestBatchSiteSubmit(listOf(request(1, listOf("1"))))
        assertNull(c.pendingBatch); assertNotNull(c.error); assertEquals(0, client.writes)
        c.close()
    }

    @Test fun nonAbsentVerificationIsExplicitlyUncertainInsteadOfClaimingSuccess() = runTest {
        val client = object : ForumService by DemoService(origin) {
            override suspend fun submitSite(submission: SiteSubmission): SiteActionResult = SiteActionResult.Response(fresh(submission.form.sourceUrl))
        }
        val c = controller(client, scope = this); runCurrent()
        c.requestBatchSiteSubmit(listOf(request(1, listOf("1"), verifyAbsent = false))); c.confirmBatch(); runCurrent()
        val result = assertIs<BatchTransactionResult.Uncertain>(c.batchResult)
        assertEquals(OperationOutcome.UNCERTAIN, result.results().single().outcome)
        assertTrue(result.results().single().message.contains("人工核对"))
        c.close()
    }

    @Test fun cancellationDuringWriteKeepsPendingMarkerAndNeverReplaysAfterRecreation() = runTest {
        val store = MemoryStore()
        val entered = CompletableDeferred<Unit>()
        var writes = 0
        val client = object : ForumService by DemoService(origin) {
            override suspend fun submitSite(submission: SiteSubmission): SiteActionResult {
                writes++
                entered.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
        }
        val c = controller(client, store, this)
        runCurrent()
        c.requestBatchSiteSubmit(listOf(request(1, listOf("1")), request(2, listOf("2"))))
        c.confirmBatch()
        runCurrent()
        entered.await()
        c.close()
        runCurrent()

        val pending = assertNotNull(RecoveryStore(store, origin).loadPendingBatch())
        assertEquals(listOf("$origin/message.php?action=receivebox&page=1"), pending.sourcePages)
        assertEquals(1, writes)

        val restarted = controller(client, store, this)
        runCurrent()
        assertNotNull(restarted.managementRecoveryFor("$origin/message.php?action=receivebox&page=1"))
        assertEquals(1, writes)
        restarted.close()
    }
}

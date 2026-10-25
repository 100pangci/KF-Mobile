@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package work.miaola.kf

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

/** Minimal, entirely synthetic structures for the follow-up P1 fixes. */
class PriorityRegressionTest {
    private val origin = "https://kf.miaola.work"
    private val parser = ForumHtmlParser(origin)
    private fun main(body: String) = "<div class='dcol' style='width:900px'>$body</div>"
    private fun factory(client: ForumService) = object : ServiceFactory {
        override fun create(origin: String) = client
        override suspend fun catalog(url: String): String = error("offline")
    }
    private fun bulk(page: Int, value: String, token: String = "synthetic") = parser.parse(main("""
        <form action='message.php' method='post'><input name='towhere' type='hidden' value='receivebox'>
        <input name='verify' type='hidden' value='$token'><label><input type='checkbox' name='delid[]' value='$value'>示例消息</label>
        <input type='submit' value='删除'></form>"""), "$origin/message.php?action=receivebox&page=$page").nativePage!!.forms.single()
    private fun submission(form: SiteForm) = SiteSubmission(form,
        buildSiteFormValues(form, form.fields.associate { it.id to it.options.map { option -> option.value } }, form.submits.single()), emptyList(), form.submits.single())

    @Test fun blocklistHasDedicatedSemanticBindingAndPreservesOtherContent() {
        val native = parser.parse(main("""<p>按站点说明填写。</p><form action='message.php' method='post'>
            <input name='action' type='hidden' value='banned'><input name='step' type='hidden' value='2'>
            <textarea name='banidinfo'>示例甲
示例乙</textarea><input name='future' value='其他字段'><input type='submit' value='保存'></form>
            <table><tr><td>额外项目</td><td>额外值</td></tr><tr><td>说明</td><td>保留</td></tr></table>"""), "$origin/message.php?action=banned").nativePage!!
        val model = assertNotNull(native.blocklist)
        val form = native.forms.single { it.id == model.formId }
        assertEquals("示例甲\n示例乙", form.fields.single { it.id == model.fieldId }.initial.single())
        assertEquals(2, form.fields.size); assertTrue(native.tables.isNotEmpty()); assertTrue(native.sections.isNotEmpty())
        assertNull(parser.parse(main("<textarea name='banidinfo'>x</textarea>"), "$origin/unknown.php").nativePage!!.blocklist)
    }

    @Test fun progressNeedsAnExplicitNumericScoreNotTheThresholdInRules() {
        val html = main("<div>当前进度分：<span>12</span><br>规则：达到 200 分可继续。</div><p>额外说明</p>")
        val native = parser.parse(html, "$origin/kf_lv200.php").nativePage!!
        assertEquals(listOf(NativeInfo("当前进度分", "12")), native.progress!!.stats)
        assertTrue(native.progress.status.isEmpty()); assertTrue(native.sections.isNotEmpty())
        assertNull(parser.parse(main("<p>规则：当前进度分达到 200 时可继续。</p>"), "$origin/kf_lv200.php").nativePage!!.progress)
    }

    @Test fun rankingFooterAndMixedFaqRowsRemainAvailableToFallback() {
        val table = NativeTable(listOf(listOf(NativeCell("排名"), NativeCell("用户"), NativeCell("数据")),
            listOf(NativeCell("1"), NativeCell("示例会员"), NativeCell("10")), listOf(NativeCell("附加规则", colspan = 3))), header = true)
        assertTrue(table.records(true).isEmpty()); assertEquals(3, table.rows.size)
        val faq = parser.parse(main("<table><tr><td>规则</td><td>完整说明 <a href='faq.php?id=1'>帮助</a></td></tr><tr><td>补充</td><td>保留文本</td></tr></table>"), "$origin/faq.php").nativePage!!
        assertTrue(faq.tables.single().rows.first().last().text.contains("完整说明"))
        assertEquals(1, faq.tables.single().rows.first().last().actions.size)
    }

    @Test fun messageLinksAreParameterOrderIndependentAndEmptyTrackingStillHasListSemantics() {
        val header = "<tr><th>ID</th><th>标题</th><th>发件人</th><th>收件人</th><th>时间</th><th>已读</th></tr>"
        val page = parser.parse(main("<table>$header<tr><td>1</td><td><a href='message.php?mid=1&amp;action=read'>示例消息</a></td><td>示例发件人</td><td>示例收件人</td><td>2026-01-01</td><td>否</td></tr></table>"), "$origin/message.php")
        assertEquals(1, page.topics.size); assertEquals(1, page.nativePage!!.messages.size); assertTrue(page.nativePage.messages.single().unread)
        val empty = parser.parse(main("<table>$header</table>"), "$origin/message.php?action=scout").nativePage!!
        assertTrue(empty.messages.isEmpty())
        assertTrue(empty.hasMessageList)
    }

    @Test fun profileSettingsDoNotDiscardUnrelatedRowsOrTables() {
        val native = parser.parse(main("""<form method='post' action='profile.php?action=modify'><table class='log1'>
            <tr><td>签名</td><td><input name='prohonor'></td></tr><tr><td>新规则</td><td>必须保留的说明</td></tr>
            </table><input type='submit' value='保存'></form><p>新增说明</p>"""), "$origin/profile.php?action=modify").nativePage!!
        assertEquals(1, native.forms.size)
        assertTrue((native.sections.joinToString { it.html } + native.tables.flatMap { it.rows }.flatten().joinToString { it.text }).contains("必须保留的说明"))
    }

    @Test fun profileKeepsLongAdditionalFactsAndDoesNotCollapseDuplicateLabels() {
        val facts = (1..35).joinToString("<br>") { "字段$it：合成值$it" } + "<br>字段35：另一个合成值"
        val native = parser.parse(main("<table class='log1'><tr><td>用户名称：示例会员<br>$facts</td><td><img class='pic' src='avatar.png'></td></tr></table><table class='log1'><tr><td>额外表格</td><td>说明</td></tr><tr><td>新项目</td><td>保留</td></tr></table>"), "$origin/profile.php?action=show&uid=1").nativePage!!
        assertEquals(37, native.profile!!.info.size)
        assertEquals(2, native.profile.info.count { it.label == "字段35" })
        assertTrue(native.tables.isNotEmpty())
    }

    @Test fun pageSelectionDoesNotConfuseIdenticalFieldIdsAcrossSourcePages() {
        val first = bulk(1, "1"); val second = bulk(2, "2")
        assertEquals(first.fields.single().id, second.fields.single().id)
        val selection = SourcePageSelection()
        selection.choose(first, setOf(first.fields.single().id))
        assertTrue(selection.fieldIds(second).isEmpty())
        selection.choose(second, setOf(second.fields.single().id)); assertEquals(2, selection.count)
        selection.clear(first); assertEquals(1, selection.count); assertTrue(selection.fieldIds(first).isEmpty())
        assertEquals(setOf(second.fields.single().id), selection.fieldIds(second))
        assertTrue(selection.fieldIds(second.copy(fields = second.fields.map { it.copy(options = listOf(FormOption("3", "新项目"))) })).isEmpty())
    }

    @Test fun revalidationUsesFreshTokensAndRejectsMissingChoicesAndChangedOperations() {
        val old = bulk(2, "2", "old-synthetic"); val fresh = bulk(2, "2", "new-synthetic")
        val values = revalidatedSiteValues(submission(old), fresh, fresh.submits.single())
        assertEquals("new-synthetic", values.single { it.name == "verify" }.value)
        assertEquals(listOf("2"), values.filter { it.name == "delid[]" }.map { it.value })
        assertFailsWith<IllegalArgumentException> { revalidatedSiteValues(submission(old), bulk(2, "3"), fresh.submits.single()) }
        assertFailsWith<IllegalArgumentException> { revalidatedSiteValues(submission(old), fresh.copy(actionUrl = "$origin/other.php"), fresh.submits.single()) }
        assertFailsWith<IllegalArgumentException> { revalidatedSiteValues(submission(old), fresh.copy(hidden = listOf(FormValue("towhere", "sendbox"))), fresh.submits.single()) }
    }

    @Test fun missingSelectionBlocksTheNetworkWriteRatherThanSendingAnEmptyDelete() = runBlocking {
        var writes = 0
        val service = HttpForumService(origin, MemoryStore(), Interceptor { chain ->
            val req = chain.request(); if (req.method != "GET") writes++
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("test")
                .body(main("<form method='post' action='message.php'><input name='towhere' type='hidden' value='receivebox'><input name='verify' type='hidden' value='fresh-synthetic'><input name='delid[]' type='checkbox' value='3'><input type='submit' value='删除'></form>").toResponseBody()).build()
        })
        try { assertIs<SiteActionResult.Rejected>(service.submitSite(submission(bulk(2, "2")))); assertEquals(0, writes) }
        finally { service.close() }
    }

    @Test fun pageTwoSubmissionUsesOnlyItsOwnFreshFormAndToken() = runBlocking {
        val requests = mutableListOf<String>(); var body = ""
        val service = HttpForumService(origin, MemoryStore(), Interceptor { chain ->
            val req = chain.request(); requests.add("${req.method}:${req.url.queryParameter("page")}")
            if (req.method == "POST") { val buffer = Buffer(); req.body!!.writeTo(buffer); body = buffer.readUtf8() }
            val html = if (req.method == "GET") main("<form action='message.php' method='post'><input name='towhere' type='hidden' value='receivebox'><input name='verify' type='hidden' value='page-two-fresh-synthetic'><input name='delid[]' type='checkbox' value='2'><input type='submit' value='删除'></form>") else "<p>合成回执</p>"
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("test").body(html.toResponseBody()).build()
        })
        try {
            val first = bulk(1, "1", "page-one-synthetic"); val second = bulk(2, "2", "page-two-old-synthetic")
            val selection = SourcePageSelection(); selection.choose(first, setOf(first.fields.single().id)); selection.choose(second, setOf(second.fields.single().id))
            assertEquals(2, selection.count)
            assertIs<SiteActionResult.Response>(service.submitSite(submission(second)))
            assertEquals(listOf("GET:2", "POST:null"), requests)
            assertTrue(body.contains("verify=page-two-fresh-synthetic")); assertTrue(body.contains("delid%5B%5D=2"))
            assertFalse(body.contains("page-one")); assertFalse(body.contains("delid%5B%5D=1")); assertFalse(body.contains("page-two-old"))
        } finally { service.close() }
    }

    @Test fun nextPageRedirectBackToFirstIsAnExplicitFooterFailure() = runTest {
        val demo = DemoService(origin)
        val first = demo.home.copy(url = "$origin/thread.php?fid=1", next = "$origin/thread.php?fid=1&page=2")
        val client = object : ForumService by demo {
            override suspend fun fetch(url: String) = if (url == first.url || url == first.next) first else demo.fetch(url)
        }
        val c = AppController(MemoryStore(), factory(client), backgroundScope, { _, _ -> })
        runCurrent(); c.load(first.url); runCurrent(); val state = c.timelineFor(c.page!!)
        c.loadNext(first); runCurrent()
        assertNotNull(state.failure); assertFalse(state.loading); assertEquals(first.next, state.next)
        assertEquals(first.topics.size, state.topics.size); c.close()
    }

    @Test fun uncertainMessageIsBlockedEvenAfterRecreatingServiceAndChangingHiddenTokens() = runBlocking {
        val store = MemoryStore(); var writes = 0
        val transport = Interceptor { writes++; throw java.io.IOException("offline") }
        val form = EditorForm("$origin/message.php?action=write", "$origin/message.php", mapOf("verify" to "old-synthetic"), "msg_title", recipientField = "pwuser")
        val values = mapOf("pwuser" to "示例收件人", "msg_title" to "示例标题", "atc_content" to "示例正文")
        val first = HttpForumService(origin, store, transport)
        try { assertIs<SubmitResult.Uncertain>(first.submit(form, values)) } finally { first.close() }
        val second = HttpForumService(origin, store, transport)
        try {
            assertIs<SubmitResult.Uncertain>(second.submit(form.copy(fields = mapOf("verify" to "new-synthetic")), values))
            assertEquals(1, writes)
            assertFalse(store.values.toString().contains("示例收件人")); assertFalse(store.values.toString().contains("示例正文"))
        } finally { second.close() }
    }

    @Test fun cancelledPageAndEditorCannotReplaceNewerDestination() = runTest {
        val demo = DemoService(origin)
        val client = object : ForumService by demo {
            override suspend fun fetch(url: String): PageData = if (url.contains("thread.php")) withContext(NonCancellable) {
                delay(500); demo.home.copy(url = "$origin/thread.php?fid=1", title = "旧列表")
            } else demo.fetch(url)
            override suspend fun editor(url: String) = withContext(NonCancellable) { delay(500); demo.editor(url) }
        }
        val c = AppController(MemoryStore(), factory(client), backgroundScope, { _, _ -> })
        runCurrent(); c.load("thread.php?fid=1"); runCurrent(); c.load("read.php?tid=1"); runCurrent()
        advanceTimeBy(501); runCurrent(); assertTrue(c.page!!.url.contains("read.php"))
        c.openEditor("post.php?fid=1"); runCurrent(); c.requestBack(); advanceTimeBy(501); runCurrent()
        assertNull(c.editor); assertTrue(c.page!!.url.contains("read.php")); c.close()
    }

    @Test fun refreshRedirectToAnotherReadListDoesNotPoisonTheSourceCache() = runTest {
        val demo = DemoService(origin)
        val first = demo.home.copy(url = "$origin/thread.php?fid=1")
        var redirect = false
        val client = object : ForumService by demo {
            override suspend fun fetch(url: String) = if (url == first.url) first.copy(url = if (redirect) "$origin/thread.php?fid=2" else first.url) else demo.fetch(url)
        }
        val c = AppController(MemoryStore(), factory(client), backgroundScope, { _, _ -> })
        runCurrent(); c.load(first.url); runCurrent(); val state = c.timelineFor(c.page!!)
        redirect = true; c.refreshTimeline(first); runCurrent()
        assertEquals(first.url, state.first.url); assertEquals(first.url, c.pageFor(first.url)?.url); assertNotNull(c.toast); c.close()
    }

    @Test fun deletionReturnsToOriginalMailboxPageAndDropsOldTails() = runTest {
        listOf("receivebox", "sendbox", "scout").forEach { box ->
        val demo = DemoService(origin)
        val first = demo.home.copy(url = "$origin/message.php?action=$box&page=2", next = "$origin/message.php?action=$box&page=3", nativePage = NativeSitePage("消息", messageList = true))
        var deleted = false
        val client = object : ForumService by demo {
            override suspend fun fetch(url: String) = when (url) {
                first.url -> first.copy(topics = if (deleted) emptyList() else first.topics)
                first.next -> first.copy(url = url, next = null)
                "message.php?action=read&mid=1" -> first.copy(url = "$origin/message.php?action=read&mid=1", topics = emptyList(), next = null)
                else -> demo.fetch(url)
            }
            override suspend fun executeSite(destination: SiteDestination): SiteActionResult { deleted = true; return SiteActionResult.Response(demo.home) }
        }
        val c = AppController(MemoryStore(), factory(client), backgroundScope, { _, _ -> })
        runCurrent(); c.load(first.url); runCurrent(); val state = c.timelineFor(c.page!!)
        c.loadNext(first); runCurrent(); assertEquals(2, state.nativePages.size)
        c.load("message.php?action=read&mid=1"); runCurrent()
        c.openSiteDestination(c.resolve("message.php?action=del&towhere=$box&delids=1")); c.confirmSiteAction(); runCurrent()
        assertEquals(first.url, c.page?.url); assertEquals(first.url, c.pendingNativePage)
        assertTrue(state.topics.isEmpty()); assertNull(c.pageFor(first.next!!)); assertSame(state, c.timelineFor(c.page!!)); c.close()
        }
    }

    @Test fun unverifiedTrackingDetailsAndBankLogsStillRequireManualVerification() {
        listOf("message.php?action=readscout&mid=1", "hack.php?H_name=bank&action=log", "hack.php?H_name=bank&action=log&to=1").forEach { route ->
            assertEquals(SiteEffect.CHANGE, SiteRoutePolicy(origin).classify(route).effect)
            assertFalse(AuditReadPolicy(origin).allows(route))
        }
    }

    @Test fun explicitlyConfirmedTrackingDetailDoesNotPretendToBeADeletionReceipt() = runTest {
        val demo = DemoService(origin)
        val scout = demo.home.copy(url = "$origin/message.php?action=scout")
        val detail = scout.copy(url = "$origin/message.php?action=readscout&mid=1", topics = emptyList(), nativePage = NativeSitePage("跟踪详情", sections = listOf(NativeSection("", "合成详情"))))
        var scoutReads = 0
        val client = object : ForumService by demo {
            override suspend fun fetch(url: String) = if (url == scout.url) { scoutReads++; scout } else demo.fetch(url)
            override suspend fun executeSite(destination: SiteDestination) = SiteActionResult.Response(detail)
        }
        val c = AppController(MemoryStore(), factory(client), backgroundScope, { _, _ -> })
        runCurrent(); c.load(scout.url); runCurrent()
        c.openSiteDestination(c.resolve(detail.url)); runCurrent(); assertNotNull(c.pendingSiteDestination)
        c.confirmSiteAction(); runCurrent()
        assertEquals(1, scoutReads); assertEquals(detail.url, c.pendingNativePage); assertEquals(scout.url, c.pageFor(scout.url)?.url)
        c.close()
    }
}

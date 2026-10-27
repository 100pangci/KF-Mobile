package work.miaola.kf

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlin.test.*
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class MessageReceiptSafetyTest {
    private val origin = "https://kf.miaola.work"
    private val form = EditorForm("$origin/message.php?action=write", "$origin/message.php", mapOf("verify" to "synthetic"), "msg_title", recipientField = "pwuser")
    private val values = mapOf("pwuser" to "合成收件人", "msg_title" to "合成标题", "atc_content" to "合成正文")
    private fun sendbox(rows: String = "") = """<div id='kf_topuser'>合成会员</div>
        <div id='kf_information'><a href='login.php?action=quit'>退出</a></div>
        <div class='dcol' style='width:900px'><table><tr><th>ID</th><th>标题</th><th>发件人</th><th>收件人</th><th>时间</th><th>已读</th></tr>
        $rows</table></div>"""

    @Test fun arbitraryReadReceiptsAndEchoedEditorsCannotClearPrivateMessageProtection() = runBlocking {
        listOf("<meta http-equiv='refresh' content='0;url=index.php'>", "<meta http-equiv='refresh' content='0;url=message.php?action=sendbox'>", "<textarea>合成回显</textarea>").forEach { html ->
            var writes = 0; val store = MemoryStore()
            val service = HttpForumService(origin, store, Interceptor { chain ->
                val request = chain.request()
                val body = if (request.method == "POST") { writes++; html } else sendbox()
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("test").body(body.toResponseBody()).build()
            })
            try {
                assertIs<SubmitResult.Uncertain>(service.submit(form, values))
                assertIs<SubmitResult.Uncertain>(service.submit(form.copy(fields = mapOf("verify" to "fresh-synthetic")), values))
                assertEquals(1, writes); assertEquals(1, store.values.keys.count { it.startsWith("message_pending@") })
            } finally { service.close() }
        }
    }

    @Test fun simultaneousCallsCheckAndSetTheDigestUnderOneLock() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1); var writes = 0
        val service = HttpForumService(origin, MemoryStore(), Interceptor { chain ->
            if (chain.request().method == "GET") return@Interceptor Response.Builder().request(chain.request())
                .protocol(Protocol.HTTP_1_1).code(200).message("test").body(sendbox().toResponseBody()).build()
            writes++; entered.complete(Unit)
            check(release.await(10, TimeUnit.SECONDS))
            throw java.io.IOException("synthetic")
        })
        try {
            val first = async { service.submit(form, values) }
            withTimeout(10_000) { entered.await() }
            val second = async(start = CoroutineStart.UNDISPATCHED) { service.submit(form, values) }
            release.countDown()
            assertIs<SubmitResult.Uncertain>(first.await()); assertIs<SubmitResult.Uncertain>(second.await()); assertEquals(1, writes)
        } finally { release.countDown(); service.close() }
    }

    @Test fun unverifiedPrivateMessageAttachmentsCannotBypassTheDigestPath() = runBlocking {
        var requests = 0
        val service = HttpForumService(origin, MemoryStore(), Interceptor { requests++; error("No network expected") })
        try {
            assertIs<SubmitResult.Rejected>(service.submitWithFiles(form.copy(uploadEnabled = true), values, listOf(LocalUpload("synthetic", "test.png", "image/png", byteArrayOf(1)))))
            assertEquals(0, requests)
        } finally { service.close() }
    }

    @Test fun unreadableSendboxBaselineRejectsBeforePostAndClearsPreparingMarker() = runBlocking<Unit> {
        val store = MemoryStore()
        var posts = 0
        val service = HttpForumService(origin, store, Interceptor { chain ->
            if (chain.request().method == "POST") posts++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("test")
                .body("<div class='dcol' style='width:900px'><p>未知页面</p></div>".toResponseBody()).build()
        })
        try {
            assertIs<SubmitResult.Rejected>(service.submit(form, values))
            assertEquals(0, posts)
            assertFalse(store.values.keys.any { it.startsWith("message_pending@") })
        } finally { service.close() }
    }

    @Test fun servicesSharingAStoreCannotSendTheSameIntentInParallel() = runBlocking {
        val store = MemoryStore(); val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1); var writes = 0
        val transport = Interceptor { chain ->
            if (chain.request().method == "GET") return@Interceptor Response.Builder().request(chain.request())
                .protocol(Protocol.HTTP_1_1).code(200).message("test").body(sendbox().toResponseBody()).build()
            writes++; entered.complete(Unit); check(release.await(10, TimeUnit.SECONDS)); throw java.io.IOException("synthetic")
        }
        val first = HttpForumService(origin, store, transport); val second = HttpForumService(origin, store, transport)
        try {
            val sending = async { first.submit(form, values) }
            withTimeout(10_000) { entered.await() }
            assertIs<SubmitResult.Uncertain>(second.submit(form, values))
            release.countDown(); assertIs<SubmitResult.Uncertain>(sending.await()); assertEquals(1, writes)
        } finally { release.countDown(); first.close(); second.close() }
    }

    @Test fun cancellationLeavesTheDurableMessageMarkerIntact() = runBlocking {
        val store = MemoryStore(); val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1); var writes = 0
        val service = HttpForumService(origin, store, Interceptor { chain ->
            if (chain.request().method == "GET") return@Interceptor Response.Builder().request(chain.request())
                .protocol(Protocol.HTTP_1_1).code(200).message("test").body(sendbox().toResponseBody()).build()
            writes++; entered.complete(Unit); check(release.await(10, TimeUnit.SECONDS)); throw java.io.IOException("synthetic")
        })
        try {
            val sending = launch { service.submit(form, values) }
            withTimeout(10_000) { entered.await() }; sending.cancel(); release.countDown(); sending.join()
            assertIs<SubmitResult.Uncertain>(service.submit(form, values)); assertEquals(1, writes)
            assertEquals(1, store.values.keys.count { it.startsWith("message_pending@") })
        } finally { release.countDown(); service.close() }
    }

    @Test fun forumPostReceiptsKeepTheirExistingSuccessAndRejectionPaths() = runBlocking<Unit> {
        var echo = false
        val service = HttpForumService(origin, MemoryStore(), Interceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("test")
                .body((if (echo) "<textarea>合成回显</textarea>" else "<meta http-equiv='refresh' content='0;url=read.php?tid=1'>").toResponseBody()).build()
        })
        try {
            val post = form.copy(sourceUrl = "$origin/post.php?fid=1", action = "$origin/post.php", recipientField = null)
            assertIs<SubmitResult.Success>(service.submit(post, values))
            echo = true; assertIs<SubmitResult.Rejected>(service.submit(post, values))
        } finally { service.close() }
    }

    @Test fun messageDetailActionsAndReplyAreIndependentOfQueryParameterOrder() {
        val parser = ForumHtmlParser(origin)
        val detail = parser.parse("""<div class='dcol' style='width:900px'><table>
            <tr><td>发件人:</td><td>合成发件人</td></tr><tr><td>标题:</td><td>合成标题</td></tr><tr><td>时间:</td><td>2026-01-01</td></tr>
            <tr><td>内容:</td><td>合成正文 <a href='message.php?mid=2&amp;action=read'>引用消息</a></td></tr>
            <tr><td>选项:</td><td><a href='message.php?remid=1&amp;action=write'>回复</a><a href='message.php?delids=1&amp;towhere=sendbox&amp;action=del'>删除</a></td></tr>
            <tr><td>页尾:</td><td><a href='message.php?action=sendbox'>返回发件箱</a></td></tr>
            </table></div>""", "$origin/message.php?mid=1&action=read").nativePage!!.message!!
        assertTrue(detail.html.contains("合成正文")); assertEquals("合成发件人", detail.sender)
        assertEquals(SiteEffect.READ, detail.reply?.effect); assertEquals(2, detail.actions.size)
        assertEquals(SiteEffect.CHANGE, detail.actions.last().effect)
    }

    @Test fun receiptRequiresANewMatchingSendboxRowNotJustHttp200() {
        val row = NativeMessageSummary("$origin/message.php?action=read&mid=9", "合成标题", "我", "合成收件人", "2026-01-01", false)
        val sendbox = PageData("$origin/message.php?action=sendbox", "发件箱", true,
            nativePage = NativeSitePage("发件箱", messages = listOf(row)))
        val confirmed = messageSendResult("合成标题", "合成收件人", sendbox, false, previousMessageUrls = emptySet())
        assertIs<MessageSendResult.Confirmed>(confirmed)
        assertFalse(confirmed.evidence.contains("uid", true))
        val old = messageSendResult("合成标题", "合成收件人", sendbox, false, previousMessageUrls = setOf(row.url))
        assertIs<MessageSendResult.Uncertain>(old)
        assertIs<MessageSendResult.Rejected>(messageSendResult("合成标题", "合成收件人", sendbox, true,
            responseNotice = "收件人不存在", previousMessageUrls = setOf(row.url)))
        assertIs<MessageSendResult.Uncertain>(messageSendResult("合成标题", "合成收件人", sendbox, true,
            responseNotice = "收件人不存在", previousMessageUrls = emptySet()))
        assertIs<MessageSendResult.Confirmed>(messageSendResult("合成标题", "合成收件人", sendbox, true,
            responseNotice = "请检查内容", previousMessageUrls = emptySet()))
    }

    @Test fun privateMessageSuccessUsesSendboxReadbackAndRejectionUsesExplicitServerText() = runBlocking<Unit> {
        val list = sendbox("<tr><td>1</td><td><a href='message.php?action=read&amp;mid=9'>合成标题</a></td><td>我</td><td>合成收件人</td><td>2026-01-01</td><td>否</td></tr>")
        val empty = sendbox()
        var posted = false
        val service = HttpForumService(origin, MemoryStore(), Interceptor { chain ->
            val request = chain.request()
            when {
                request.method == "POST" -> {
                    posted = true
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(302).message("redirect")
                        .header("Location", "message.php?action=sendbox").body("".toResponseBody()).build()
                }
                request.url.toString().contains("action=sendbox") ->
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("test")
                        .body((if (posted) list else empty).toResponseBody()).build()
                else -> Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("test")
                    .body(empty.toResponseBody()).build()
            }
        })
        try {
            val success = service.submit(form, values)
            assertIs<SubmitResult.Success>(success)
            assertTrue(success.message.contains("发件箱"))
        } finally { service.close() }
    }
}

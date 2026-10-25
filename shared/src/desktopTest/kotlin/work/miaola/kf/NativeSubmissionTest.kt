package work.miaola.kf

import kotlin.test.*
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.MediaType.Companion.toMediaType
import okio.Buffer
import java.io.IOException

class NativeSubmissionTest {
    private val origin = "https://kf.miaola.work"
    private val source = "$origin/hack.php?H_name=bank"
    private val html = """<div class="dcol" style="width:900px"><form action="hack.php?H_name=bank" method="post"><input name="action" type="hidden" value="virement"><input name="verify" type="hidden" value="fresh-token"><input name="pwuser"><input name="to_money"><textarea name="memo"></textarea><input type="submit" name="submit" value="转账"></form></div>"""
    private fun response(request: Request, text: String, code: Int = 200, location: String? = null): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
        .apply { if (location != null) header("Location", location) }
        .body(gbkBytes(text).toResponseBody("text/html; charset=gbk".toMediaType())).build()
    private fun submission(service: HttpForumService): SiteSubmission {
        val form = service.parser.parse(html, source).nativePage!!.forms.single()
        val state = form.fields.associate { field -> field.id to listOf(when (field.name) { "pwuser" -> "测试会员"; "to_money" -> "1.5"; "memo" -> "测试备注"; else -> "" }) }
        val submit = form.submits.single()
        return SiteSubmission(form, buildSiteFormValues(form, state, submit), emptyList(), submit)
    }
    @Test fun revalidatesFormThenPostsOnceWithGbkAndFreshVerify() = runBlocking {
        val requests = mutableListOf<Request>(); var body = ""
        val service = HttpForumService(origin, MemoryStore(), Interceptor { chain ->
            val req = chain.request(); requests.add(req)
            if (req.method == "GET") response(req, html)
            else {
                val out = Buffer(); req.body!!.writeTo(out); body = out.readUtf8()
                response(req, "<div>转账成功</div>")
            }
        })
        try {
            assertIs<SiteActionResult.Response>(service.submitSite(submission(service)))
            assertEquals(listOf("GET", "POST"), requests.map { it.method })
            assertTrue(body.contains("verify=fresh-token"))
            assertTrue(body.contains("pwuser=%B2%E2%CA%D4%BB%E1%D4%B1"))
            assertEquals(source, requests.last().header("Referer"))
        } finally { service.close() }
    }
    @Test fun uncertainPostIsReportedOnceAndStaysManuallyRetryable() = runBlocking {
        val store = MemoryStore(); var posts = 0
        val service = HttpForumService(origin, store, Interceptor { chain ->
            val req = chain.request()
            if (req.method == "GET") response(req, html) else { posts++; throw IOException("simulated network failure") }
        })
        try {
            val request = submission(service)
            assertIs<SiteActionResult.Uncertain>(service.submitSite(request))
            assertEquals(1, posts)
            assertIs<SiteActionResult.Uncertain>(service.submitSite(request))
            assertEquals(2, posts)
            assertFalse(store.values.toString().contains("测试会员"))
        } finally { service.close() }
    }
    @Test fun mutationRedirectCannotTriggerAnotherMutation() = runBlocking {
        var calls = 0
        val service = HttpForumService(origin, MemoryStore(), Interceptor { chain ->
            calls++; response(chain.request(), "", 302, "$origin/job.php?action=buytopic&tid=1&pid=tpc")
        })
        try {
            assertIs<SiteActionResult.Response>(service.executeSite(SiteDestination("$origin/diy_test.php?action=save", SiteEffect.CHANGE)))
            assertEquals(1, calls)
        } finally { service.close() }
    }

    @Test fun transientConnectionResetIsRetriedSilently() = runBlocking {
        var calls = 0
        val service = HttpForumService(origin, MemoryStore(), Interceptor { chain ->
            calls++
            if (calls == 1) throw IOException("Connection reset")
            response(chain.request(), "<title>恢复后的页面 - 绯月</title><div class='dcol'><p>内容已恢复</p></div>")
        })
        try {
            val page = service.fetch("index.php")
            assertEquals(2, calls)
            assertTrue(page.title.contains("恢复后的页面"))
        } finally { service.close() }
    }
}

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

    private val ratingSource = "$origin/kf_fw_1wkfb.php?do=1&safeid=f4a8577&ptid=1086708"
    private val ratingHtml = """<div class="dcol" style="width:900px"><table><form name="mail1" action="kf_fw_1wkfb.php?" method="get">
<input name="safeid" type="hidden" value="f4a8577" /><input name="do" type="hidden" value="1" />
<input name="ptid" type="hidden" value="1086708" /><input name="pbad" type="hidden" value="1" />
<tr><td>本次评分于[2026-11-01 17:14]建立，请在[15分钟]内提交评分。</td></tr>
<tr><td><input name="psize" type="text" maxlength="20" /> <select name="psizegb"><option value="1" selected />MB(小文件)</option><option value="2" />GB(大文件)</option></select></td></tr>
<tr><td><select name="pnew"><option value="1" selected />无</option><option value="2" />首发/补档</option></select></td></tr>
<tr><td><select name="pgod"><option value="1" selected />无</option><option value="2" />自购/自扫/自镜像</option></select></td></tr>
<tr><td><textarea name="ptext"></textarea></td></tr>
<tr><td><input name="submit" type="submit" value="确认提交" /></td></tr>
</form></table></div>"""
    private fun ratingSubmission(service: HttpForumService): SiteSubmission {
        val form = service.parser.parse(ratingHtml, ratingSource).nativePage!!.forms.single()
        val state = form.fields.associate { field ->
            field.id to listOf(when (field.name) {
                "psize" -> "2.64"; "psizegb" -> "2"; "pnew" -> "1"; "pgod" -> "1"; "ptext" -> "测试评分" else -> ""
            })
        }
        val submit = form.submits.single()
        return SiteSubmission(form, buildSiteFormValues(form, state, submit), emptyList(), submit)
    }
    @Test fun ratingEntryReReadsTheSessionThenSubmitsOnce() = runBlocking {
        val requests = mutableListOf<Request>()
        val service = HttpForumService(origin, MemoryStore(), Interceptor { chain ->
            val req = chain.request(); requests.add(req)
            if (req.url.queryParameter("psize") == null) response(req, ratingHtml)
            else response(req, "<div class=\"dcol\" style=\"width:900px\"><p>评分提交成功，等待审核。</p></div>")
        })
        try {
            val request = ratingSubmission(service)
            assertEquals("帖子评分", request.form.title)
            assertEquals(listOf("资源体积", "体积单位", "首发/补档", "自购认定", "附言"),
                request.form.fields.map { it.label })
            assertEquals(listOf("MB(小文件)", "GB(大文件)"),
                request.form.fields.first { it.name == "psizegb" }.options.map { it.label })
            assertTrue(request.form.warning.contains("站点审核"))
            val result = service.submitSite(request)
            assertIs<SiteActionResult.Response>(result)
            assertEquals(2, requests.size, "The rating form must be re-read exactly once before the write.")
            val sent = requests.last()
            assertEquals("GET", sent.method)
            assertEquals("/kf_fw_1wkfb.php", sent.url.encodedPath)
            assertEquals("1", sent.url.queryParameter("do"))
            assertEquals("f4a8577", sent.url.queryParameter("safeid"))
            assertEquals("1086708", sent.url.queryParameter("ptid"))
            assertEquals("1", sent.url.queryParameter("pbad"))
            assertEquals("2.64", sent.url.queryParameter("psize"))
            assertEquals("2", sent.url.queryParameter("psizegb"))
            assertEquals(ratingSource, sent.header("Referer"))
        } finally { service.close() }
    }
    @Test fun changedRatingSessionIsRejectedBeforeTheWrite() = runBlocking {
        var writes = 0
        val service = HttpForumService(origin, MemoryStore(), Interceptor { chain ->
            val req = chain.request()
            if (req.url.queryParameter("psize") == null) response(req, ratingHtml.replace("f4a8577", "fffffff"))
            else { writes++; response(req, "<div>评分提交成功</div>") }
        })
        try {
            val result = service.submitSite(ratingSubmission(service))
            assertIs<SiteActionResult.Rejected>(result)
            assertTrue(result.message.contains("评分会话"), "Unexpected refusal: ${result.message}")
            assertEquals(0, writes, "A refreshed form from another session must never be submitted.")
        } finally { service.close() }
    }
    @Test fun ratingFormRejectsAnEmptyOrNonNumericVolume() {
        val form = ForumHtmlParser(origin).parse(ratingHtml, ratingSource).nativePage!!.forms.single()
        val submit = form.submits.single()
        fun values(size: String) = buildSiteFormValues(form, form.fields.associate { field ->
            field.id to listOf(if (field.name == "psize") size else field.initial.firstOrNull().orEmpty())
        }, submit)
        assertFailsWith<IllegalArgumentException> { values("") }
        assertFailsWith<IllegalArgumentException> { values("2.64GB") }
        assertFailsWith<IllegalArgumentException> { values("0") }
        assertEquals("2.64", values("2.64").first { it.name == "psize" }.value)
    }
    @Test fun ordinaryChangeSourceFormsStayNonReloadable() = runBlocking {
        var calls = 0
        val service = HttpForumService(origin, MemoryStore(), Interceptor { chain ->
            calls++; response(chain.request(), ratingHtml)
        })
        try {
            val form = service.parser.parse(ratingHtml, "$origin/job.php?action=buy&tid=1").nativePage!!.forms.single()
            val state = form.fields.associate { field ->
                field.id to listOf(if (field.name == "psize") "2.64" else field.initial.firstOrNull().orEmpty())
            }
            val submit = form.submits.single()
            val request = SiteSubmission(form, buildSiteFormValues(form, state, submit), emptyList(), submit)
            assertIs<SiteActionResult.Rejected>(service.submitSite(request))
            assertEquals(0, calls, "A CHANGE-source form must be refused before any network re-read.")
        } finally { service.close() }
    }
}

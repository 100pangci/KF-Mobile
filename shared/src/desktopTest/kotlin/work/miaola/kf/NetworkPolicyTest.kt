package work.miaola.kf

import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class NetworkPolicyTest {
    private val origin = "https://kf.miaola.work"
    @Test fun gbkEncoding() {
        assertEquals("pwuser=%B2%E2%CA%D4%D3%C3%BB%A7&pwpwd=a%21%2B%26+", gbkForm(linkedMapOf("pwuser" to "测试用户", "pwpwd" to "a!+& ")).toString(Charsets.US_ASCII))
        assertEquals(8, gbkBytes("测试用户").size)
        assertFailsWith<IllegalArgumentException> { gbkBytes("不可编码😀") }
    }
    @Test fun cookiesNeverCrossOriginsOrPorts() {
        val jar = OriginCookieJar(origin, MemoryStore())
        val base = origin.toHttpUrl()
        jar.saveFromResponse(base, listOf(Cookie.Builder().name("session").value("synthetic").domain("miaola.work").path("/").build()))
        assertEquals(1, jar.loadForRequest(base).size)
        assertTrue(jar.loadForRequest("https://1.kf.miaola.work/".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://kf.miaola.work:444/".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("http://kf.miaola.work/".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest(base).single().hostOnly)
        assertTrue(jar.loadForRequest(base).single().secure)
    }
    @Test fun persistenceDeletionAndIsolation() {
        val store = MemoryStore()
        val base = origin.toHttpUrl()
        val jar = OriginCookieJar(origin, store)
        jar.saveFromResponse(base, listOf(Cookie.Builder().name("session").value("synthetic").hostOnlyDomain(base.host).path("/").build()))
        assertEquals(1, OriginCookieJar(origin, store).loadForRequest(base).size)
        assertTrue(OriginCookieJar("https://0.kf.miaola.work", store).forWebView().isEmpty())
        jar.importWebView(null)
        assertEquals(1, jar.loadForRequest(base).size, "An empty browser snapshot is not a logout")
        jar.clear()
        assertNull(store.get("cookies@$origin"))
    }
    @Test fun utf8PayloadWithLegacyGbkHeaderIsDecodedCorrectly() {
        val utf8 = "<html><body>中文页面</body></html>".toByteArray(Charsets.UTF_8)
        assertEquals("<html><body>中文页面</body></html>", decodePageBytes(utf8, "GBK", "gbk"))
        val gbk = gbkBytes("中文页面")
        assertTrue(decodePageBytes(gbk, "GBK", "gbk").contains("中文页面"))
    }
    @Test fun readsRetryTransientFailuresButSubmissionsAreNeverReplayed() = runBlocking {
        var reads = 0
        val readTransport = Interceptor { chain ->
            reads++
            if (reads < 3) throw IOException("timeout")
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("<html><body><div class='dcol' style='width:900px'>ok</div></body></html>"
                    .toResponseBody("text/html; charset=gbk".toMediaType())).build()
        }
        val service = HttpForumService(origin, MemoryStore(), readTransport)
        service.fetch("index.php")
        assertEquals(3, reads)

        var posts = 0
        val writeTransport = Interceptor { chain -> posts++; throw IOException("timeout") }
        val writeService = HttpForumService(origin, MemoryStore(), writeTransport)
        assertFailsWith<IOException> { writeService.raw("message.php", mapOf("a" to "b")) }
        assertEquals(1, posts)
    }
    @Test fun expiredCookiesAreNotRestored() {
        val jar = OriginCookieJar(origin, MemoryStore())
        val base = origin.toHttpUrl()
        jar.saveFromResponse(base, listOf(Cookie.Builder().name("session").value("old").hostOnlyDomain(base.host).path("/").expiresAt(1).build()))
        assertTrue(jar.loadForRequest(base).isEmpty())
    }
    @Test fun blockedUrls() {
        val parser = ForumHtmlParser(origin)
        assertEquals("$origin/login.php", parser.resolve("login.php?"))
        listOf("//evil.test/login.php", "https://kf.miaola.work:444/", "http://kf.miaola.work/", "https://user:secret@kf.miaola.work/").forEach { assertNull(parser.resolve(it)) }
    }

    @Test fun knownReadPathsDoNotAuthorizeUnknownOrDuplicateMutationParameters() {
        val routes = SiteRoutePolicy(origin)
        listOf("index.php?do=claim", "thread.php?fid=1&submit=1", "read.php?tid=1&buy=1", "faq.php?action=del",
            "show.php?uid=1&ok=1", "kf_no1.php?del=1", "profile.php?action=show&do=save",
            "message.php?action=write&do=delete", "post.php?action=reply&step=1", "fyg_sjcdwj.php?go=fight&xl=1",
            "read.php?tid=1&%64o=save", "read.php?tid=1&tid=2", "nested/read.php?tid=1").forEach {
            assertEquals(SiteEffect.CHANGE, routes.classify(it).effect, "Unsafe read classification")
        }
        assertEquals(SiteEffect.READ, routes.classify("read.php?spid=1&tid=1").effect)
    }

    @Test fun readRedirectNeverExecutesMutationAndGetMutationsNeverRetry() = runBlocking {
        var calls = 0
        val redirected = HttpForumService(origin, MemoryStore(), Interceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("test")
                .header("Location", "$origin/kf_growup.php?ok=1").body("".toResponseBody()).build()
        })
        try { assertFails { redirected.fetch("index.php") }; assertEquals(1, calls) } finally { redirected.close() }
        calls = 0
        val mutation = HttpForumService(origin, MemoryStore(), Interceptor { calls++; throw IOException("offline") })
        try { assertFails { mutation.raw("kf_growup.php?ok=1") }; assertEquals(1, calls) } finally { mutation.close() }
    }

    @Test fun multipartRedirectDoesNotReplayThePostBody() = runBlocking {
        val methods = mutableListOf<String>()
        val service = HttpForumService(origin, MemoryStore(), Interceptor { chain ->
            val req = chain.request(); methods.add(req.method)
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(if (methods.size == 1) 302 else 200).message("test")
                .apply { if (methods.size == 1) header("Location", "$origin/index.php") }.body("ok".toResponseBody()).build()
        })
        try {
            service.raw("profile.php?action=modify", customBody = "synthetic".toRequestBody())
            assertEquals(listOf("POST", "GET"), methods)
        } finally { service.close() }
    }
    @Test fun onlineCacheBuiltInFallback() = runBlocking {
        val store = MemoryStore()
        var payload = Json.encodeToString(DomainCatalog.BuiltIn)
        var offline = false
        val fake = object : ServiceFactory {
            override fun create(origin: String): ForumService = error("unused")
            override suspend fun catalog(url: String): String { if (offline) error("offline"); return payload }
        }
        val repo = DomainRepository(store, fake)
        val url = "https://raw.githubusercontent.com/owner/repo/main/domains.json"
        assertEquals(CatalogSource.BUILT_IN, repo.local().source)
        assertEquals(CatalogSource.ONLINE, repo.refresh(url).source)
        offline = true
        assertEquals(CatalogSource.CACHE, repo.refresh(url).source)
        offline = false; payload = "not json"
        assertEquals(CatalogSource.CACHE, repo.refresh(url).source)
        store.remove("domain_catalog"); offline = true
        assertEquals(CatalogSource.BUILT_IN, repo.refresh(url).source)
    }
}

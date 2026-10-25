package work.miaola.kf

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assume.assumeTrue

/** Read-only checks, plus one separately authorized/opt-in login roundtrip. */
class LiveSiteTest {
    private val root = Path.of(System.getProperty("kf.projectRoot"))
    @Test fun authorizedLoginRoundtrip() = runBlocking {
        assumeTrue("Login requires separate explicit opt-in", System.getenv("KF_LOGIN_TEST") == "true")
        val username = System.getenv("KF_USERNAME") ?: error("Missing login test username")
        val password = System.getenv("KF_PASSWORD") ?: error("Missing login test password")
        val store = MemoryStore()
        val first = HttpForumService("https://kf.miaola.work", store)
        try {
            assertTrue(first.login(username, password).authenticated)
            val second = HttpForumService("https://kf.miaola.work", store)
            try { assertTrue(second.fetch("index.php").authenticated) } finally { second.close() }
            assertFalse(store.values.values.any { it.contains(password) }, "Password must not be persisted")
        } finally { first.clearSession(); first.close() }
    }

    @Test fun capturedPurchaseAndEmoticonAssetsParseWithoutSpending() {
        val resource = root.resolve(".secrets/resource-topic-3.html")
        val faces = root.resolve(".secrets/face.js")
        assumeTrue("Optional private resource capture", Files.exists(resource) && Files.exists(faces))
        val parser = ForumHtmlParser("https://kf.miaola.work")
        val page = parser.parse(Files.readString(resource), "https://kf.miaola.work/read.php?tid=1086397")
        val offer = page.posts.flatMap { it.purchases }.single()
        assertEquals("1", offer.price)
        assertEquals("KFB", offer.currency)
        assertFalse(offer.toString().contains("verify="))
        val catalog = parser.parseEmoticons(Files.readString(faces), Files.readString(root.resolve(".secrets/newtopic.html")), "https://kf.miaola.work/post.php?fid=52")
        assertEquals(48, catalog.size)
        assertEquals("[s:57]", catalog.first { it.id == 57 }.code)
    }
    @Test fun capturedPrivatePagesParseWithoutPublishingFixtures() {
        assumeTrue("Local private captures are optional", Files.exists(root.resolve(".secrets/home.html")))
        val parser = ForumHtmlParser("https://kf.miaola.work")
        val loginCapture = root.resolve(".secrets/login.html")
        if (Files.exists(loginCapture)) {
            val (_, fields) = parser.loginFields(Files.readString(loginCapture), "https://kf.miaola.work/login.php")
            assertTrue("pwuser" in fields && "pwpwd" in fields)
            assertEquals("2", fields["step"])
        }
        fun page(name: String, path: String) = parser.parse(Files.readString(root.resolve(".secrets/$name.html")), "https://kf.miaola.work/$path")
        val home = page("home", "index.php")
        assertTrue(home.authenticated)
        assertTrue(home.boards.size >= 20)
        assertTrue(home.topics.size >= 30)
        assertEquals(10, home.feeds.first { it.id == "recommended" }.topics.size)
        assertEquals(10, home.feeds.first { it.id == "new-topics" }.topics.size)
        val forum = page("forum", "thread.php?fid=52")
        assertTrue(forum.topics.size >= 20)
        val topic = page("topic", "read.php?tid=1")
        assertTrue(topic.posts.size >= 10)
        assertTrue(topic.posts.all { it.author.isNotBlank() && it.html.isNotBlank() })
        val form = parser.parseEditor(Files.readString(root.resolve(".secrets/newtopic.html")), "https://kf.miaola.work/post.php?fid=52")
        assertEquals("new", form.fields["action"])
        assertEquals("2", form.fields["step"])
        assertEquals("52", form.fields["fid"])
        assertTrue(form.fields["verify"].orEmpty().isNotBlank())
        assertTrue(form.categories.size > 1)
        assertTrue(page("messages", "message.php").topics.isNotEmpty())
        assertTrue(page("favorites", "profile.php?action=favor").topics.isNotEmpty())
        assertTrue(page("favorites", "profile.php?action=favor").nativePage!!.forms.any { it.fields.any { f -> f.name == "delid[]" } })
        val profile = page("profile", "profile.php?action=show&uid=1").nativePage!!.profile
        assertNotNull(profile); assertTrue(profile.info.size >= 5)
    }

    @Test fun capturedNativeFormsAreReadOnlyParsedNotSubmitted() {
        val bank = root.resolve(".secrets/native-bank.html")
        assumeTrue("Optional authorized native-page captures", Files.exists(bank))
        val parser = ForumHtmlParser("https://kf.miaola.work")
        val nativeBank = parser.parse(Files.readString(bank), "https://kf.miaola.work/hack.php?H_name=bank").nativePage!!
        assertEquals(2, nativeBank.forms.size)
        assertTrue(nativeBank.forms.any { it.fields.any { f -> f.name == "to_money" } })
        val rank = parser.parse(Files.readString(root.resolve(".secrets/native-rank.html")), "https://kf.miaola.work/kf_no1.php").nativePage!!
        assertTrue(rank.tables.any { it.rows.size >= 100 && it.rows.first().size >= 5 })
        val help = parser.parse(Files.readString(root.resolve(".secrets/native-help.html")), "https://kf.miaola.work/faq.php").nativePage!!
        assertTrue(help.sections.isNotEmpty() || help.tables.isNotEmpty())
        val modify = parser.parse(Files.readString(root.resolve(".secrets/native-modify.html")), "https://kf.miaola.work/profile.php?action=modify").nativePage!!
        assertTrue(modify.forms.any { it.fields.any { f -> f.name == "oldpwd" } })
        val blocked = parser.parse(Files.readString(root.resolve(".secrets/native-blocked.html")), "https://kf.miaola.work/message.php?action=banned").nativePage!!
        assertTrue(blocked.forms.any { it.fields.any { f -> f.name == "banidinfo" } })
    }

    @Test fun readOnlyLiveSession() = runBlocking {
        assumeTrue("Set KF_LIVE_TEST=true to enable real read-only requests", System.getenv("KF_LIVE_TEST") == "true")
        val origin = "https://kf.miaola.work"
        val base = "$origin/".toHttpUrl()
        val service = HttpForumService(origin, MemoryStore())
        val file = root.resolve(".secrets/kf.miaola.work.cookies.txt")
        assumeTrue("A locally authorized session is required", Files.exists(file))
        val cookies = Files.readAllLines(file).mapNotNull { line ->
            if (line.startsWith('#') && !line.startsWith("#HttpOnly_")) return@mapNotNull null
            val parts = line.removePrefix("#HttpOnly_").split('\t')
            if (parts.size != 7) return@mapNotNull null
            if (parts[4].toLongOrNull()?.let { it != 0L && it * 1000 <= System.currentTimeMillis() } == true) return@mapNotNull null
            Cookie.Builder().name(parts[5]).value(parts[6]).hostOnlyDomain(base.host).path(parts[2]).secure().build()
        }
        service.cookieJar.saveFromResponse(base, cookies)
        try {
            val home = service.fetch("index.php")
            assertTrue(home.authenticated, "Authorized session is no longer valid")
            assertTrue(home.boards.isNotEmpty())
            repeat(30) {
                val bridge = BrowserSessionSync(service.cookieJar)
                bridge.prepare(); bridge.sync(null); bridge.activate(); bridge.sync("")
                bridge.beginClose(null); bridge.closed(); bridge.authenticatedPage("stale=ignored")
            }
            assertTrue(service.fetch("index.php").authenticated, "Read-only session must survive repeated empty/late browser snapshots")
            val forum = service.fetch("thread.php?fid=52")
            assertTrue(forum.topics.size >= 10)
            val topic = service.fetch(forum.topics.first().url)
            assertTrue(topic.posts.isNotEmpty())
            val editor = service.editor("post.php?fid=52")
            assertEquals("new", editor.fields["action"])
            assertTrue(editor.fields["verify"].orEmpty().isNotBlank())
            assertTrue(editor.emoticons.size > 7, "Live editor should load the real catalog, not only fallback codes")
            val smile = service.imageBytes(editor.emoticons.first().imageUrl)
            assertNotNull(smile, "The actual forum smile URL must load without a WebView")
            assertTrue(smile.size > 20 && smile.take(3).toByteArray().toString(Charsets.US_ASCII) == "GIF")
        } finally { service.clearSession(); service.close() }
    }
}

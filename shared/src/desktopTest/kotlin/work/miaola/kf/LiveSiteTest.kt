package work.miaola.kf

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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

    /**
     * One low-risk, per-day write against a dedicated test account, gated by
     * both the live-write flag and an explicit account-name confirmation. The
     * existing local cookie is never used to write unless its signed-in
     * username equals KF_TEST_ACCOUNT. If the account already signed in today,
     * the test sends no request and passes as a read-only check.
     */
    @Test fun authorizedLowRiskWriteRoundtrip() = runBlocking {
        assumeTrue(
            "Set KF_LIVE_WRITE_TEST=true and KF_TEST_ACCOUNT=<test account> locally",
            System.getenv("KF_LIVE_WRITE_TEST") == "true" && System.getenv("CI").isNullOrBlank(),
        )
        val confirmedAccount = System.getenv("KF_TEST_ACCOUNT") ?: error("Missing KF_TEST_ACCOUNT confirmation")
        val origin = "https://kf.miaola.work"
        val base = "$origin/".toHttpUrl()
        val service = HttpForumService(origin, MemoryStore())
        val file = LocalAuditCookies.file(root, base.host)
        assumeTrue("A locally authorized session is required", Files.exists(file))
        service.cookieJar.saveFromResponse(base, LocalAuditCookies.load(Files.readString(file), origin))
        try {
            val home = service.fetch("index.php")
            assertTrue(home.authenticated, "Local authenticated cookie is expired or invalid.")
            assertTrue(
                home.username == confirmedAccount,
                "Refusing to write: the signed-in account is not the explicitly confirmed test account.",
            )
            val before = service.fetch("kf_growup.php")
            val growth = before.nativePage?.growth
                ?: fail("The growth page did not expose a structured sign-in state; no write was sent.")
            when (growth.claimStatus) {
                StatusKind.COMPLETED -> {
                    println("Live write check: test account already signed in today; no write was sent.")
                    return@runBlocking
                }
                StatusKind.AVAILABLE -> Unit
                else -> fail("Sign-in status is not a confirmed available state (${growth.claimStatus}); no write was sent.")
            }
            val claimUrl = growth.claimUrl
                ?: fail("No confirmed sign-in URL; no write was sent. This test only covers the link-based claim.")
            val destination = service.destination(claimUrl, "签到")
            assertEquals(SiteEffect.CHANGE, destination.effect)
            val result = service.executeSite(destination)
            assertIs<SiteActionResult.Response>(result)
            val after = service.fetch("kf_growup.php")
            assertEquals(
                StatusKind.COMPLETED,
                after.nativePage?.growth?.claimStatus,
                "The write was sent but the fresh page does not confirm completion; do not retry.",
            )
        } finally { service.clearSession(); service.close() }
    }

    /**
     * Reversible real write check for the explicitly confirmed test account:
     * add one favorite from a reader page, read the favorites list back, then
     * remove it through the site's own inverse control (or the favorites
     * management form) and read both pages back. Every write is single-shot;
     * no retry is sent. Gated by KF_LIVE_WRITE_TEST plus an exact account-name
     * confirmation, so the local cookie cannot be used as a test account by
     * accident.
     */
    @Test fun authorizedReversibleFavoriteRoundtrip() = runBlocking {
        assumeTrue(
            "Set KF_LIVE_WRITE_TEST=true and KF_TEST_ACCOUNT=<test account> locally",
            System.getenv("KF_LIVE_WRITE_TEST") == "true" && System.getenv("CI").isNullOrBlank(),
        )
        val confirmedAccount = System.getenv("KF_TEST_ACCOUNT") ?: error("Missing KF_TEST_ACCOUNT confirmation")
        val origin = "https://kf.miaola.work"
        val base = "$origin/".toHttpUrl()
        val service = HttpForumService(origin, MemoryStore())
        val file = LocalAuditCookies.file(root, base.host)
        assumeTrue("A locally authorized session is required", Files.exists(file))
        service.cookieJar.saveFromResponse(base, LocalAuditCookies.load(Files.readString(file), origin))
        try {
            val home = service.fetch("index.php")
            assertTrue(home.authenticated, "Local authenticated cookie is expired or invalid.")
            assertTrue(
                home.username == confirmedAccount,
                "Refusing to write: the signed-in account is not the explicitly confirmed test account.",
            )
            val favoritesUrl = "$origin/profile.php?action=favor"
            suspend fun favoriteIds(): Set<String> {
                val page = service.fetch(favoritesUrl)
                val entries = page.nativePage?.favorites?.entries.orEmpty().mapNotNull {
                    it.topic.url.toHttpUrlOrNull()?.queryParameter("tid")
                }
                val removable = page.nativePage?.forms.orEmpty().flatMap { it.fields }
                    .filter { it.name == "delid[]" }.flatMap { it.options }.map { it.value }
                return (entries + removable).toSet()
            }
            val initial = favoriteIds()
            val forum = service.fetch("thread.php?fid=52")
            var tid: String? = null
            var topicUrl: String? = null
            var favorite: SiteDestination? = null
            for (url in forum.topics.map { it.url }.distinct().take(20)) {
                val candidateTid = url.toHttpUrlOrNull()?.queryParameter("tid") ?: continue
                if (candidateTid in initial) continue
                val reader = service.fetch(url)
                val link = reader.favorite ?: continue
                if (link.effect != SiteEffect.CHANGE) continue
                tid = candidateTid; topicUrl = url; favorite = link
                break
            }
            val writeTid = tid ?: fail("No unfavorited topic with a site-provided favorite link; no write was sent.")
            val writeTopic = topicUrl ?: fail("No topic URL for the favorite check; no write was sent.")
            val favoriteLink = favorite ?: fail("No favorite link for the favorite check; no write was sent.")
            service.executeSite(favoriteLink)
            assertTrue(writeTid in favoriteIds(), "The site did not confirm the new favorite; no retry was sent.")

            suspend fun removeFavorite(): SiteActionResult {
                val fresh = service.fetch(favoritesUrl)
                val form = fresh.nativePage?.forms?.firstOrNull { candidate ->
                    candidate.fields.any { it.name == "delid[]" && it.options.any { option -> option.value == writeTid } }
                } ?: fail("The new favorite has no removal form; check the favorites page manually.")
                val submit = form.submits.firstOrNull() ?: fail("The favorites form has no submit control.")
                val state = form.fields.associate { field ->
                    field.id to when {
                        field.name == "delid[]" && field.options.any { it.value == writeTid } -> listOf(writeTid)
                        field.name == "job" && field.options.any { it.value == "clear" } -> listOf("clear")
                        else -> field.initial
                    }
                }
                val values = try { buildSiteFormValues(form, state, submit) } catch (e: IllegalArgumentException) {
                    fail(e.message ?: "The favorites removal form changed; no retry was sent.")
                }
                return service.submitSite(SiteSubmission(form, values, emptyList(), submit))
            }

            val raw = service.raw(writeTopic)
            val inverse = service.parser.document(raw.html, raw.url)
                .select("a[href*='kf_tidfavor.php']")
                .firstOrNull { a ->
                    val target = service.parser.resolve(a.attr("href"), raw.url)?.toHttpUrlOrNull()
                        ?: return@firstOrNull false
                    target.queryParameter("tid") == writeTid &&
                        target.queryParameter("action") != "favor" &&
                        a.text().contains("取消收藏")
                }?.let { service.parser.resolve(it.attr("href"), raw.url) }
            var removal = if (inverse != null) {
                service.executeSite(service.destination(inverse, "取消收藏"))
            } else null
            if (removal !is SiteActionResult.Response || writeTid in favoriteIds()) {
                removal = removeFavorite()
            }
            assertIs<SiteActionResult.Response>(removal)
            assertFalse(writeTid in favoriteIds(), "The reversible favorite was not removed; check the favorites page manually.")
            assertNotNull(service.fetch(writeTopic).favorite, "The reader must show the favorite link again after removal.")
        } finally { service.clearSession(); service.close() }
        Unit
    }

    @Test fun capturedPurchaseAndEmoticonAssetsParseWithoutSpending() {
        val resource = root.resolve(".secrets/resource-topic-3.html")
        val faces = root.resolve(".secrets/face.js")
        assumeTrue("Optional private resource capture", Files.exists(resource) && Files.exists(faces))
        val parser = ForumHtmlParser("https://kf.miaola.work")
        val captured = Files.readString(resource)
        val doc = parser.document(captured, "https://kf.miaola.work/")
        val action = doc.select("[onclick],a[href]").map { it.attr("onclick") + it.attr("href") }.first { it.contains("action=buytopic") }
        val tid = Regex("[?&]tid=([0-9]+)").find(action)?.groupValues?.get(1) ?: error("Captured purchase has no thread binding")
        val page = parser.parse(captured, "https://kf.miaola.work/read.php?tid=$tid")
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
        assumeTrue("Set KF_LIVE_TEST=true locally to enable real read-only requests", System.getenv("KF_LIVE_TEST") == "true" && System.getenv("CI").isNullOrBlank())
        val origin = "https://kf.miaola.work"
        val base = "$origin/".toHttpUrl()
        val service = HttpForumService(origin, MemoryStore())
        val file = LocalAuditCookies.file(root, base.host)
        assumeTrue("A locally authorized session is required", Files.exists(file))
        val cookies = LocalAuditCookies.load(Files.readString(file), origin)
        service.cookieJar.saveFromResponse(base, cookies)
        try {
            val home = service.fetch("index.php")
            assertTrue(home.authenticated, "Local authenticated cookie is expired or invalid.")
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
            val growth = service.fetch("kf_growup.php")
            val liveGrowth = growth.nativePage?.growth
            assertNotNull(liveGrowth, "The live growth page must expose a structured sign-in state.")
            assertNotEquals(
                StatusKind.UNKNOWN,
                liveGrowth.claimStatus,
                "The live sign-in status must be recognizable from the site's own wording.",
            )
            // show.php is linked from reader author boxes. Its permission page
            // has no detail table, so verify the layout only against the
            // signed-in account's own (read-only) detail page.
            val selfProfile = service.fetch("profile.php?action=show")
            val liveProfile = selfProfile.nativePage?.profile
            assertNotNull(liveProfile, "The signed-in profile must use the native profile layout.")
            assertTrue(liveProfile.info.isNotEmpty(), "The live profile must expose structured account facts.")
            val detailLink = liveProfile.links.firstOrNull {
                it.url.toHttpUrlOrNull()?.encodedPath == "/show.php"
            }
            assertNotNull(detailLink, "The signed-in profile must link to its author detail page.")
            val authorSearch = liveProfile.links.firstOrNull { link ->
                val target = link.url.toHttpUrlOrNull() ?: return@firstOrNull false
                target.encodedPath == "/search.php" && target.queryParameter("authorid") != null
            }
            assertNotNull(authorSearch, "The signed-in profile must expose its author-result search link.")
            val authorResults = service.fetch(authorSearch.url)
            assertNotEquals(
                AuthStatus.LOGIN_REQUIRED,
                authorResults.authStatus,
                "The author-result GET must be readable with the local session.",
            )
            assertTrue(
                authorResults.topics.isNotEmpty() || authorResults.posts.isNotEmpty(),
                "The author-result page must parse into native topics or posts, not an empty fallback.",
            )
            // The reader's author box links to profile.php?action=show&uid&sf,
            // the sf token being required for a non-self profile. Verify that
            // a real page from another author parses into the same native
            // profile layout as the signed-in account.
            val selfUid = authorSearch.url.toHttpUrlOrNull()?.queryParameter("authorid")
            val otherAuthorUrl = topic.posts.firstNotNullOfOrNull { post ->
                val target = post.authorUrl.toHttpUrlOrNull() ?: return@firstNotNullOfOrNull null
                post.authorUrl.takeIf {
                    target.encodedPath == "/profile.php" && target.queryParameter("action") == "show" &&
                        target.queryParameter("uid").orEmpty().isNotBlank() &&
                        target.queryParameter("uid") != selfUid &&
                        target.queryParameter("sf").orEmpty().isNotBlank()
                }
            }
            assertNotNull(otherAuthorUrl, "A topic post must expose a signed non-self author profile link.")
            val otherProfile = service.fetch(otherAuthorUrl)
            val otherNative = otherProfile.nativePage
            assertNotNull(otherNative, "A non-self profile.php page must parse as a native page.")
            val otherProfileData = otherNative.profile
            assertNotNull(otherProfileData, "A non-self profile.php page must use the real profile layout.")
            assertTrue(otherProfileData.info.size >= 5, "The non-self live profile must expose structured account facts.")
            assertNotEquals(
                liveProfile.name,
                otherProfileData.name,
                "The non-self profile must not resolve to the signed-in account.",
            )
            val authorDetails = service.fetch(detailLink.url)
            val authorPage = authorDetails.nativePage
            assertNotNull(authorPage, "show.php must be parsed as a native page.")
            assertTrue(authorPage.unsupported.isEmpty(), "show.php must not expose unknown controls.")
            // This account's group may be denied the exhibition area; in that
            // case the site's own permission notice must still render natively.
            if (authorPage.profile != null) {
                assertTrue(authorPage.profile.info.isNotEmpty(), "The author detail page must expose structured account facts.")
            } else {
                assertTrue(authorPage.blocks.isNotEmpty(), "The permission page must render as native structured content.")
            }
        } finally { service.clearSession(); service.close() }
    }
}

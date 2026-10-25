package work.miaola.kf

import kotlin.test.*
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl

class BrowserSessionSyncTest {
    private val origin = "https://kf.miaola.work"
    private val base = "$origin/".toHttpUrl()
    private fun authenticatedJar(store: MemoryStore = MemoryStore()) = OriginCookieJar(origin, store).apply {
        saveFromResponse(base, listOf(Cookie.Builder().name("auth").value("original").hostOnlyDomain(base.host).path("/")
            .httpOnly().secure().expiresAt(System.currentTimeMillis() + 86400000).build()))
    }
    @Test fun nullEmptyAndPartialHeadersCannotDeleteLogin() {
        val jar = authenticatedJar()
        listOf(null, "", " ", "lastvisit=42").forEach(jar::importWebView)
        assertEquals("original", jar.loadForRequest(base).first { it.name == "auth" }.value)
    }
    @Test fun prepareAndLateCallbacksCannotOverwriteSession() {
        val jar = authenticatedJar()
        val bridge = BrowserSessionSync(jar)
        assertTrue(bridge.prepare().isNotEmpty())
        bridge.sync("auth=stale-during-clear")
        assertEquals("original", jar.loadForRequest(base).single().value)
        assertTrue(bridge.activate())
        bridge.beginClose(null)
        bridge.sync("auth=late")
        bridge.authenticatedPage("auth=late-js")
        bridge.closed()
        bridge.sync(null)
        assertEquals("original", jar.loadForRequest(base).single().value)
    }
    @Test fun closingBeforeCookieInjectionCompletesDoesNotEraseLogin() {
        val jar = authenticatedJar()
        val bridge = BrowserSessionSync(jar)
        bridge.prepare(); bridge.beginClose(null)
        assertFalse(bridge.activate())
        assertEquals("original", jar.loadForRequest(base).single().value)
    }
    @Test fun repeatedCompatibilityVisitsKeepPersistedLogin() {
        val store = MemoryStore()
        val jar = authenticatedJar(store)
        repeat(30) { iteration ->
            val bridge = BrowserSessionSync(jar)
            bridge.prepare(); bridge.sync(null); bridge.activate()
            bridge.sync("lastvisit=$iteration"); bridge.sync(null)
            bridge.beginClose(null); bridge.closed(); bridge.sync("auth=late")
        }
        val restored = OriginCookieJar(origin, store)
        assertEquals("original", restored.loadForRequest(base).first { it.name == "auth" }.value)
    }
    @Test fun validUpdatesPreserveHttpOnlyExpiryAndPath() {
        val jar = authenticatedJar()
        val old = jar.loadForRequest(base).single()
        jar.importWebView("auth=updated")
        val updated = jar.loadForRequest(base).single()
        assertEquals("updated", updated.value)
        assertTrue(updated.httpOnly)
        assertTrue(updated.secure)
        assertEquals(old.expiresAt, updated.expiresAt)
        assertEquals(old.path, updated.path)
    }
    @Test fun rootSnapshotDoesNotModifyOtherCookiePaths() {
        val jar = authenticatedJar()
        jar.saveFromResponse("$origin/messages/".toHttpUrl(), listOf(Cookie.Builder().name("auth").value("scoped").hostOnlyDomain(base.host).path("/messages").build()))
        jar.importWebView("auth=new-root")
        val scoped = jar.loadForRequest("$origin/messages/".toHttpUrl()).first { it.path == "/messages" }
        assertEquals("scoped", scoped.value)
    }
    @Test fun explicitLogoutAndFreshWebLoginStillWork() {
        val jar = authenticatedJar()
        val bridge = BrowserSessionSync(jar)
        bridge.prepare(); bridge.activate(); bridge.explicitLogout()
        bridge.sync("auth=stale")
        assertTrue(jar.loadForRequest(base).isEmpty())
        bridge.authenticatedPage("auth=new-login")
        assertEquals("new-login", jar.loadForRequest(base).single().value)
    }
    @Test fun explicitServerExpiryStillDeletesCookie() {
        val jar = authenticatedJar()
        jar.saveFromResponse(base, listOf(Cookie.Builder().name("auth").value("").hostOnlyDomain(base.host).path("/").expiresAt(1).build()))
        assertTrue(jar.loadForRequest(base).isEmpty())
    }
}

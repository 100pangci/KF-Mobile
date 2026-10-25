package work.miaola.kf

/** Main-thread lifecycle gate, independent of Android for deterministic tests. */
class BrowserSessionSync(private val jar: OriginCookieJar) {
    enum class Phase { NEW, PREPARING, ACTIVE, CLOSING, CLOSED }
    var phase: Phase = Phase.NEW; private set
    private var logoutRequested = false

    fun prepare(): List<String> {
        check(phase == Phase.NEW)
        phase = Phase.PREPARING
        // Snapshot BEFORE CookieManager's temporary store is cleared.
        return jar.forWebView()
    }

    fun activate(): Boolean {
        if (phase != Phase.PREPARING) return false
        phase = Phase.ACTIVE
        return true
    }

    fun sync(header: String?) {
        if (phase == Phase.ACTIVE && !logoutRequested) jar.importWebView(header)
    }

    fun explicitLogout() {
        if (phase != Phase.ACTIVE) return
        logoutRequested = true
        jar.clear()
    }

    fun authenticatedPage(header: String?) {
        if (phase != Phase.ACTIVE) return
        logoutRequested = false
        jar.importWebView(header)
    }

    fun beginClose(header: String?) {
        if (phase == Phase.CLOSING || phase == Phase.CLOSED) return
        sync(header)
        phase = Phase.CLOSING
    }

    fun closed() { phase = Phase.CLOSED }
}

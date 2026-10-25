package work.miaola.kf

import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Every instance belongs to exactly one origin, including port and scheme. */
class OriginCookieJar(private val origin: String, private val store: KeyValueStore) : CookieJar {
    private val base = origin.toHttpUrl()
    private val key = "cookies@$origin"
    private val cookies = mutableListOf<Cookie>()
    init {
        val values = runCatching { Json.decodeFromString<List<String>>(store.get(key) ?: "[]") }.getOrDefault(emptyList())
        cookies.addAll(values.mapNotNull { Cookie.parse(base, it) }.map { hostOnly(it) }.filter { it.expiresAt > System.currentTimeMillis() })
    }
    private fun same(url: HttpUrl) = url.scheme == base.scheme && url.host == base.host && url.port == base.port
    private fun builder(cookie: Cookie): Cookie.Builder = Cookie.Builder().name(cookie.name).value(cookie.value)
        .hostOnlyDomain(base.host).path(cookie.path).secure().apply {
            if (cookie.persistent) expiresAt(cookie.expiresAt)
            if (cookie.httpOnly) httpOnly()
        }
    private fun hostOnly(cookie: Cookie) = builder(cookie).build()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (!same(url)) return
        cookies.filter { it.matches(url) || it.expiresAt <= System.currentTimeMillis() }.forEach { incoming ->
            this.cookies.removeAll { it.name == incoming.name && it.path == incoming.path }
            if (incoming.expiresAt > System.currentTimeMillis()) this.cookies.add(hostOnly(incoming))
        }
        persist()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (!same(url)) return emptyList()
        cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
        return cookies.filter { it.matches(url) }
    }

    @Synchronized
    fun clear() { cookies.clear(); store.remove(key) }

    @Synchronized
    fun forWebView(): List<String> = cookies.filter { it.expiresAt > System.currentTimeMillis() }.map { it.toString() }

    @Synchronized
    fun importWebView(header: String?) {
        // CookieManager exposes a request header, not a complete Set-Cookie
        // snapshot. Missing/HttpOnly/path-scoped cookies and temporary empty
        // reads must NEVER be interpreted as server-side deletion or logout.
        if (header.isNullOrBlank()) return
        // Preserve expiry/HttpOnly metadata for existing cookies; browser updates
        // provide only name/value pairs, not their original Set-Cookie attributes.
        header.split(';').forEach { part ->
            val pair = part.trim().split('=', limit = 2)
            if (pair.size == 2) {
                val matching = cookies.filter { it.name == pair[0] && it.matches(base) }
                // A request header cannot identify a cookie's path. Do not
                // overwrite unrelated scoped cookies using a root snapshot.
                if (matching.size > 1) return@forEach
                val old = matching.singleOrNull()
                if (old == null && cookies.any { it.name == pair[0] }) return@forEach
                val updated = runCatching {
                    (old?.let { builder(it) } ?: Cookie.Builder().name(pair[0]).hostOnlyDomain(base.host).path("/").secure())
                        .value(pair[1]).build()
                }.getOrNull()
                if (updated != null) {
                    cookies.removeAll { it.name == updated.name && it.path == updated.path }
                    cookies.add(updated)
                }
            }
        }
        persist()
    }

    private fun persist() = store.put(key, Json.encodeToString(cookies.map { it.toString() }))
}

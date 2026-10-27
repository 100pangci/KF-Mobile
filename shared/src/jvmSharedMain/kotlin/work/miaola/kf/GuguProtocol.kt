package work.miaola.kf

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.concurrent.TimeUnit

/**
 * Official external Gugu game. It is intentionally a separate origin/client:
 * forum cookies are never forwarded. Only read-only endpoints are called.
 */
internal class GuguProtocol {
    private val gameOrigin = "https://www.momozhen.com"
    private val gameHost = "www.momozhen.com"
    private val cookieJar = MemoryCookieJar()
    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()

    suspend fun load(loginUrl: String): GuguSnapshot? = withContext(Dispatchers.IO) {
        val parsed = runCatching { loginUrl.toHttpUrl() }.getOrNull() ?: return@withContext null
        if (parsed.scheme != "https" || parsed.host != gameHost || parsed.username.isNotEmpty() ||
            parsed.password.isNotEmpty() || parsed.encodedPath != "/fyg_login.php" ||
            parsed.queryParameter("m") != "li" || parsed.queryParameterNames.any { it !in setOf("m", "u", "s") } ||
            parsed.queryParameter("u").orEmpty().isBlank() || parsed.queryParameter("s").orEmpty().isBlank()) {
            return@withContext null
        }
        var current = loginUrl
        var html = ""
        repeat(6) {
            val page = fetch(current) ?: return@withContext null
            html = page.first
            val location = page.second
            if (location == null) {
                val parsed = parseIndex(html)
                if (parsed != null) return@withContext parsed
                // The signed login lands on a "next" page before the game
                // dashboard. Fetch the dashboard once, still read-only.
                val index = fetch("$gameOrigin/fyg_index.php")?.first ?: return@withContext null
                return@withContext parseIndex(index)
            }
            if (!allowedRedirect(location)) return@withContext null
            current = location
        }
        null
    }

    private fun allowedRedirect(raw: String): Boolean {
        val url = runCatching { raw.toHttpUrl() }.getOrNull() ?: return false
        if (url.scheme != "https" || url.host != gameHost || url.username.isNotEmpty() || url.password.isNotEmpty()) return false
        return url.encodedPath in setOf("/fyg_login.php", "/next.php", "/fyg_index.php")
    }

    private fun fetch(url: String): Pair<String, String?>? {
        val request = Request.Builder().url(url).header("User-Agent", "KFMobile/0.1 (Android; native client)")
            .header("Accept", "text/html,application/xhtml+xml").build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.code in listOf(301, 302, 303, 307, 308)) {
                    val location = response.header("Location") ?: return null
                    val resolved = response.request.url.resolve(location)?.toString() ?: return null
                    "" to resolved
                } else {
                    if (!response.isSuccessful) return null
                    val body = response.body ?: return null
                    val bytes = body.byteStream().readBounded(4 * 1024 * 1024 + 1)
                    if (bytes.size > 4 * 1024 * 1024) return null
                    val text = decodePageBytes(bytes, body.contentType()?.charset()?.name(), null)
                    text to null
                }
            }
        } catch (_: Exception) { null }
    }

    private fun readPage(f: Int): String? {
        if (f !in setOf(6, 7, 8)) return null
        val body = "f=$f".toRequestBody("application/x-www-form-urlencoded".toMediaType())
        val request = Request.Builder().url("$gameOrigin/fyg_read.php").post(body)
            .header("User-Agent", "KFMobile/0.1 (Android; native client)")
            .header("Referer", "$gameOrigin/fyg_equip.php").build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val bytes = response.body?.byteStream()?.readBounded(4 * 1024 * 1024 + 1) ?: return null
                decodePageBytes(bytes, response.body?.contentType()?.charset()?.name(), null)
            }
        } catch (_: Exception) { null }
    }

    private fun parseIndex(indexHtml: String): GuguSnapshot? {
        val beach = fetch("$gameOrigin/fyg_beach.php")?.first.orEmpty()
        val equipped = readPage(6)
        val inventory = readPage(7)
        val cards = readPage(8)
        return buildSnapshot(indexHtml, beach, equipped, inventory, cards)
    }

    internal fun parseSnapshotForTest(
        indexHtml: String,
        beachHtml: String,
        equippedHtml: String?,
        inventoryHtml: String?,
        cardsHtml: String?,
    ): GuguSnapshot? = buildSnapshot(indexHtml, beachHtml, equippedHtml, inventoryHtml, cardsHtml)

    private fun buildSnapshot(
        indexHtml: String,
        beachHtml: String,
        equippedHtml: String?,
        inventoryHtml: String?,
        cardsHtml: String?,
    ): GuguSnapshot? {
        val doc = Jsoup.parse(indexHtml, "$gameOrigin/fyg_index.php")
        val character = parseCharacter(doc) ?: return null
        val equipped = equippedHtml?.let { parseItems(it, true) }.orEmpty()
        val inventory = inventoryHtml?.let { parseItems(it, false) }.orEmpty()
        val cards = cardsHtml?.let { parseItems(it, false) }.orEmpty()
        val cooldowns = parseCooldowns(beachHtml)
        return GuguSnapshot(
            character = character,
            equipped = equipped,
            inventory = inventory,
            cards = cards,
            cooldowns = cooldowns,
        )
    }

    private fun parseCharacter(doc: Document): GuguCharacter? {
        val name = doc.select("button[onclick*=fyg_index]").firstOrNull { it.text().isNotBlank() }?.text()?.trim().orEmpty()
        fun value(label: String): String = doc.select("p").firstOrNull { p ->
            p.text().trim().startsWith(label)
        }?.selectFirst("span.pull-right")?.text()?.trim().orEmpty()
        val rank = value("段位")
        val level = value("等级")
        val shells = value("贝壳")
        if (name.isBlank() && rank.isBlank() && level.isBlank()) return null
        fun monthly(label: String): String = doc.select("p").firstOrNull { it.text().trim().startsWith(label) }
            ?.selectFirst("span.pull-right")?.text()?.trim().orEmpty()
        return GuguCharacter(
            name = name.ifBlank { "咕咕镇角色" },
            rank = rank,
            level = level,
            shells = shells,
            monthlyWins = monthly("本月战斗"),
            monthlyNpc = monthly("对NPC战斗"),
            monthlyPvp = monthly("对玩家战斗"),
        )
    }

    private fun parseItems(html: String, equipped: Boolean): List<GuguItem> {
        val doc = Jsoup.parse(html, "$gameOrigin/fyg_equip.php")
        return doc.select("button.fyg_colpzbg, button[class*=fyg_colpz], button[onclick^=zbtip]").mapIndexedNotNull { index, button ->
            val onclick = button.attr("onclick")
            val title = button.attr("title")
            val titleDoc = Jsoup.parseBodyFragment(title, "$gameOrigin/")
            val name = Regex("<br\\s*/?>\\s*(?:<[^>]+>\\s*)*([^<]+)\\s*$", RegexOption.IGNORE_CASE).find(title)
                ?.groupValues?.get(1)?.trim()
                ?: Regex("</span>\\s*([^<]+)\\s*$", RegexOption.IGNORE_CASE).find(title)?.groupValues?.get(1)?.trim()
                ?: titleDoc.text().trim().take(120)
            if (name.isBlank()) return@mapIndexedNotNull null
            val id = Regex("zbtip\\s*\\(\\s*'([^']+)'").find(onclick)?.groupValues?.get(1)
                ?: "item:$index:${name.hashCode()}"
            val level = Regex("Lv\\.\\s*(?:<[^>]+>\\s*)*([0-9]+)").find(title)?.groupValues?.get(1).orEmpty()
            val quality = when (button.attr("data-tip-class")) {
                "popover-success" -> "优秀"
                "popover-primary" -> "精良"
                "popover-info" -> "普通"
                "popover-warning" -> "稀有"
                "popover-danger" -> "传说"
                else -> ""
            }
            val icon = Regex("background-image\\s*:\\s*url\\(([^)]+)\\)").find(button.attr("style"))
                ?.groupValues?.get(1)?.trim()?.removeSurrounding("'")?.removeSurrounding("\"")
                ?: button.selectFirst("img[src]")?.attr("src")
            val iconUrl = icon?.let { runCatching { it.toHttpUrlOrNull()?.toString() ?: "$gameOrigin/$it" }.getOrNull() }
                ?.takeIf { it.startsWith("$gameOrigin/") }.orEmpty()
            val stats = Jsoup.parseBodyFragment(button.attr("data-content"), "$gameOrigin/").select("p")
                .map { it.text().replace(Regex("\\s+"), " ").trim() }
                .filter { it.isNotBlank() }.take(8)
            GuguItem(id, name.take(120), level, quality, iconUrl, stats, equipped)
        }.distinctBy { it.id }.take(200)
    }

    private fun parseCooldowns(html: String): List<GuguCooldown> {
        if (html.isBlank()) return emptyList()
        val text = Jsoup.parse(html).text().replace(Regex("\\s+"), " ")
        val remaining = Regex("距离下次随机[^0-9]{0,20}([0-9]+)\\s*分钟").find(text)?.groupValues?.get(1)
        return listOfNotNull(remaining?.let { GuguCooldown("随机物品刷新", "$it 分钟", "沙滩物品最多保留 24 小时") })
    }

    private class MemoryCookieJar : CookieJar {
        private val cookies = mutableListOf<Cookie>()
        @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookies.forEach { incoming ->
                this.cookies.removeAll { it.name == incoming.name && it.path == incoming.path }
                if (incoming.expiresAt > System.currentTimeMillis()) this.cookies.add(incoming)
            }
        }
        @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> =
            cookies.filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() }
    }
}

private fun String.toHttpUrlOrNull(): HttpUrl? = runCatching { toHttpUrl() }.getOrNull()

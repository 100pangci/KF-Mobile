package work.miaola.kf

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Local development only. Never persist the jar or include source text in diagnostics. */
internal object LocalAuditCookies {
    fun file(root: Path, host: String): Path = root.resolve("cookie.txt").takeIf(Files::isRegularFile)
        ?: root.resolve(".secrets/$host.cookies.txt")

    fun load(text: String, origin: String): List<Cookie> {
        val base = origin.toHttpUrl()
        if (!text.contains('\t')) {
            val header = text.trim().removePrefix("Cookie:").trim()
            return header.split(';').mapNotNull { pair ->
                val name = pair.substringBefore('=').trim()
                if ('=' !in pair || !name.matches(Regex("[a-zA-Z0-9_-]+"))) null
                else runCatching { Cookie.Builder().name(name).value(pair.substringAfter('=').trim())
                    .hostOnlyDomain(base.host).path("/").secure().build() }.getOrNull()
            }
        }
        return text.lineSequence().mapNotNull { line ->
            if (line.startsWith('#') && !line.startsWith("#HttpOnly_")) return@mapNotNull null
            val fields = line.removePrefix("#HttpOnly_").split('\t')
            if (fields.size != 7) return@mapNotNull null
            val domain = fields[0].removePrefix(".")
            if (base.host != domain && !(fields[1] == "TRUE" && base.host.endsWith(".$domain"))) return@mapNotNull null
            val expires = fields[4].toLongOrNull() ?: return@mapNotNull null
            if (expires != 0L && expires <= System.currentTimeMillis() / 1000) return@mapNotNull null
            runCatching { Cookie.Builder().name(fields[5]).value(fields[6]).hostOnlyDomain(base.host)
                .path(fields[2]).secure().apply {
                    if (expires > 0) expiresAt(expires * 1000)
                    if (line.startsWith("#HttpOnly_")) httpOnly()
                }.build() }.getOrNull()
        }.toList()
    }
}

/** Narrower than application navigation. No search issuance, download, JS or form submission. */
internal class AuditReadPolicy(private val origin: String) {
    fun allows(raw: String): Boolean {
        val base = origin.toHttpUrl()
        val u = base.resolve(raw) ?: return false
        if (u.scheme != base.scheme || u.host != base.host || u.port != base.port ||
            u.username.isNotBlank() || u.password.isNotBlank()) return false
        if (SiteRoutePolicy(origin).classify(u.toString()).effect != SiteEffect.READ) return false
        if (u.queryParameterNames.any { u.queryParameterValues(it).size != 1 }) return false
        val allowed = when (u.encodedPath) {
            "/", "/index.php", "/search.php" -> emptySet()
            "/thread.php" -> setOf("fid", "page", "type", "search", "orderway", "asc", "digest")
            "/read.php" -> setOf("tid", "page", "fid", "fpage", "toread", "sf", "uid", "ordertype", "spid")
            "/profile.php" -> setOf("action", "uid", "type", "page", "sf")
            "/message.php" -> setOf("action", "mid", "page", "touid", "remid", "towhere")
            "/post.php" -> setOf("action", "fid", "tid", "pid", "article")
            "/hack.php" -> setOf("H_name", "action", "to", "page")
            "/kf_no1.php" -> setOf("no1", "page")
            "/faq.php" -> setOf("id", "hid", "action")
            "/guanjianci.php" -> setOf("gjc", "page", "uid")
            "/kf_share.php", "/show.php" -> setOf("uid", "page", "fid", "tid", "action")
            "/kf_growup.php", "/kf_lv200.php", "/kf_fw_1wkfb.php", "/kf_fw_ig_index.php" -> setOf("ping", "page", "uid", "do", "safeid", "id")
            "/fyg_sjcdwj.php" -> setOf("go", "xl")
            else -> return false
        }
        if (!u.queryParameterNames.all { it in allowed }) return false
        val action = u.queryParameter("action")
        if (u.encodedPath == "/faq.php" && action !in listOf(null, "", "faq")) return false
        if (u.encodedPath == "/hack.php" && !(u.queryParameter("H_name") == "bank" &&
                (action == null || action == "log") && u.queryParameter("to") in listOf(null, "", "0", "1"))) return false
        if (u.encodedPath == "/fyg_sjcdwj.php" && u.queryParameterNames.isNotEmpty() &&
            !(u.queryParameter("go") == "play" && u.queryParameter("xl") in listOf("1", "2"))) return false
        if (u.encodedPath == "/kf_fw_1wkfb.php" && u.queryParameter("do") != null && u.queryParameter("do") != "2") return false
        if (u.encodedPath in listOf("/show.php", "/kf_share.php") && action != null) return false
        return true
    }

    fun redacted(raw: String): String {
        val u = raw.toHttpUrlOrNull() ?: return "<invalid>"
        // Unknown path segments can contain identifiers too.
        val path = u.encodedPath.takeIf { it.matches(Regex("/[a-zA-Z0-9_]+\\.php|/")) } ?: "/<redacted>"
        return path + u.queryParameterNames.sorted().joinToString("&", prefix = if (u.querySize > 0) "?" else "") {
            if (it.matches(Regex("[a-zA-Z_][a-zA-Z0-9_\\[\\]]{0,40}"))) "$it=<redacted>" else "<redacted>"
        }
    }

    fun type(raw: String): String {
        val u = raw.toHttpUrl()
        val action = u.queryParameter("action").orEmpty()
        val known = setOf("show", "modify", "favor", "receivebox", "sendbox", "scout", "banned", "read", "write", "new", "reply", "quote")
        val variant = when {
            action in known -> action
            u.encodedPath == "/kf_no1.php" -> u.queryParameter("no1").orEmpty().takeIf { it in setOf("sm", "cz", "ft", "gx") }.orEmpty()
            u.encodedPath == "/kf_fw_1wkfb.php" -> u.queryParameter("ping").orEmpty().takeIf { it in setOf("1", "2", "3", "4") }.orEmpty()
            else -> ""
        }
        return u.encodedPath.substringAfterLast('/') + if (variant.isNotBlank()) ":$variant" else ""
    }
}

internal fun auditScreen(page: PageData): String = when {
    page.url.toHttpUrl().encodedPath == "/post.php" || page.url.contains("action=write") -> "EditorScreen"
    page.posts.isNotEmpty() -> "ReaderScreen"
    page.nativePage?.message != null -> "MessageDetailScreen"
    page.nativePage?.blocklist != null -> "BlocklistScreen"
    page.nativePage?.progress != null -> "ProgressScreen"
    page.nativePage?.profile != null -> "ProfileScreen"
    page.nativePage?.favorites != null -> "FavoritesScreen"
    page.nativePage?.bank?.balances?.isNotEmpty() == true -> "BankScreen"
    page.nativePage?.growth != null -> "GrowthScreen"
    page.url.toHttpUrl().encodedPath == "/message.php" && page.url.contains("action=scout") && page.nativePage?.hasMessageList == true -> "ListingScreen(messages)"
    page.url.toHttpUrl().encodedPath == "/profile.php" && page.url.contains("action=modify") && page.nativePage?.forms?.size == 1 -> "ProfileSettingsScreen"
    page.url.toHttpUrl().encodedPath == "/faq.php" -> "FaqScreen"
    page.url.toHttpUrl().encodedPath == "/kf_no1.php" && page.nativePage?.tables?.any { it.records(true).isNotEmpty() } == true -> "RecordScreen(ranking)"
    page.url.toHttpUrl().encodedPath in setOf("/kf_fw_1wkfb.php", "/guanjianci.php", "/kf_share.php") && page.nativePage?.tables?.any { it.records().isNotEmpty() } == true -> "RecordScreen"
    page.url.toHttpUrl().encodedPath in setOf("/kf_lv200.php", "/guanjianci.php") && page.nativePage?.forms?.isEmpty() == true -> "RecordScreen(status)"
    page.url.toHttpUrl().encodedPath == "/index.php" -> "FeedScreen / BoardsScreen / AccountScreen"
    page.url.toHttpUrl().encodedPath == "/search.php" -> "SearchScreen"
    page.url.toHttpUrl().encodedPath == "/thread.php" || page.url.contains("action=favor") -> "ListingScreen"
    page.url.toHttpUrl().encodedPath == "/message.php" && !page.url.contains("action=read") && !page.url.contains("action=banned") && !page.url.contains("action=scout") -> "ListingScreen(messages)"
    else -> "NativeSiteScreen"
}

internal val AuditJson = Json { prettyPrint = true }
internal fun writeAuditJson(path: Path, value: JsonElement) {
    Files.createDirectories(path.parent)
    Files.writeString(path, AuditJson.encodeToString(JsonElement.serializer(), value))
}

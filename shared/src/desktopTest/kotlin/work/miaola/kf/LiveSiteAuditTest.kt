package work.miaola.kf

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Explicit local opt-in, no credentials, no writes, no live data in JUnit output. */
class LiveSiteAuditTest {
    @Test fun discoverAuthenticatedReadRoutes() = runBlocking {
        assumeTrue(System.getenv("KF_SITE_AUDIT") == "true" && System.getenv("CI").isNullOrBlank())
        val root = Path.of(System.getProperty("kf.projectRoot"))
        val origin = "https://kf.miaola.work"
        val policy = AuditReadPolicy(origin)
        val cookies = LocalAuditCookies.file(root, origin.toHttpUrl().host)
        require(Files.isRegularFile(cookies)) { "Local authenticated cookie file is missing." }
        val output = root.resolve(".secrets/site-audit")
        Files.createDirectories(output)
        val client = HttpForumService(origin, MemoryStore(), Interceptor { chain ->
            check(chain.request().method == "GET" && policy.allows(chain.request().url.toString())) { "Audit blocked a non-read request." }
            chain.proceed(chain.request())
        })
        client.cookieJar.saveFromResponse(origin.toHttpUrl(), LocalAuditCookies.load(Files.readString(cookies), origin))
        val inventory = mutableListOf<JsonElement>()
        val unsupported = mutableListOf<JsonElement>()
        val skipped = linkedMapOf<String, JsonElement>()
        val queue = ArrayDeque(listOf("$origin/index.php"))
        val visited = mutableSetOf<String>()
        val types = mutableMapOf<String, Int>()
        val captures = mutableListOf<JsonElement>()
        var sessionVerified = false
        try {
            while (queue.isNotEmpty() && inventory.size < 100) {
                val url = queue.removeFirst().substringBefore('#')
                if (!visited.add(url)) continue
                if (!policy.allows(url)) {
                    val safe = policy.redacted(url)
                    skipped[safe] = buildJsonObject {
                        put("route", safe); put("effect", client.destination(url).effect.name)
                        put("status", "manual verification required")
                    }
                    continue
                }
                val type = policy.type(url)
                if ((types[type] ?: 0) >= if (type == "read.php") 4 else 2) continue
                types[type] = (types[type] ?: 0) + 1
                val raw = try { client.raw(url, followRedirects = false) } catch (_: Exception) {
                    if (!sessionVerified) error("Local authenticated session could not be verified (network failure).")
                    inventory.add(buildJsonObject { put("route", policy.redacted(url)); put("page_type", type); put("status", "request failed") })
                    continue
                }
                if (raw.redirectUrl != null) {
                    if (!sessionVerified && raw.redirectUrl.toHttpUrl().encodedPath == "/login.php") error("Local authenticated cookie is expired or invalid.")
                    inventory.add(buildJsonObject {
                        put("route", policy.redacted(url)); put("page_type", type); put("status", "redirect")
                        put("target", policy.redacted(raw.redirectUrl)); put("effect", client.destination(url).effect.name)
                        put("screen", "redirect; no content parsed")
                    })
                    queue.add(raw.redirectUrl)
                    continue
                }
                val page = client.parser.parse(raw.html, raw.url)
                if (!sessionVerified && !page.authenticated || page.authStatus == AuthStatus.LOGIN_REQUIRED) {
                    error("Local authenticated cookie is expired or invalid.")
                }
                if (page.authenticated) sessionVerified = true
                val doc = client.parser.document(raw.html, raw.url)
                val native = page.nativePage
                val id = "page-${inventory.size.toString().padStart(3, '0')}"
                // Private local captures only; URLs/titles/content never enter reports.
                Files.writeString(output.resolve("$id.html"), raw.html)
                captures.add(buildJsonObject { put("capture", id); put("page_type", type); put("route", policy.redacted(url)) })
                inventory.add(buildJsonObject {
                    put("route", policy.redacted(url)); put("page_type", type)
                    put("title", native?.title?.takeIf { type.substringBefore(':') in setOf("profile.php", "message.php", "hack.php", "kf_no1.php", "kf_growup.php", "kf_fw_1wkfb.php", "kf_fw_ig_index.php", "kf_lv200.php", "fyg_sjcdwj.php", "guanjianci.php", "faq.php") } ?: "<content title redacted>")
                    put("authenticated", page.authenticated); put("parser", when { page.posts.isNotEmpty() -> "posts"; page.topics.isNotEmpty() -> "topics"; else -> "NativeSitePage" })
                    put("topics", page.topics.size); put("posts", page.posts.size); put("native_page", native != null)
                    put("forms", native?.forms?.size ?: 0); put("tables", native?.tables?.size ?: 0)
                    put("sections", native?.sections?.size ?: 0); put("links", native?.links?.size ?: 0)
                    put("unsupported", native?.unsupported?.size ?: 0)
                    put("js_controls", doc.select("[onclick], a[href^=javascript:]").size)
                    put("embedded_content", doc.select("iframe,object,embed").size)
                    put("effect", client.destination(url).effect.name); put("screen", auditScreen(page))
                    if (type.startsWith("post.php") || type == "message.php:write") {
                        val editor = runCatching { client.parser.parseEditor(raw.html, raw.url) }.getOrNull()
                        put("editor_parsed", editor != null)
                        put("editor_fields", editor?.fields?.size ?: 0)
                    }
                })
                val main = doc.select(".dcol").lastOrNull { it.attr("style").contains("900px") } ?: doc.body()
                main.select("[onclick],a[href^=javascript:]").forEach { control ->
                    val js = control.attr("onclick")
                    val (category, reason, adapter) = when {
                        js.contains("UpLoadFace") -> Triple("D", "ajaxface protocol", "AvatarUploadAdapter")
                        Regex("^(?:window\\.)?location(?:\\.href)?\\s*=\\s*['\"]").containsMatchIn(js.trim()) -> Triple("A", "literal navigation (policy gated)", "SiteDestination")
                        control.id() == "read_tui" -> Triple("C", "recommendation protocol", "rpc:recommend")
                        control.attr("name") == "chkall" -> Triple("C", "local selection", "selection mode")
                        js.contains("checkset(") -> Triple("E", "legacy popup preference", "native notification preferences (deferred)")
                        js.contains("toggle(") -> Triple("C", "local disclosure", "expandable section")
                        js.contains("jgjg(") || js.contains("jgtz(") -> Triple("D", "reward/game Ajax protocol", "RewardProtocolAdapter (deferred)")
                        type.startsWith("fyg_") -> Triple("D", "dynamic game protocol", "defer game adapter")
                        control.closest("form") != null && control.attr("type") == "submit" -> Triple("B", "form submit (not executed)", "SiteForm")
                        else -> Triple("E", "unknown script (not executed)", "manual verification required")
                    }
                    unsupported.add(buildJsonObject {
                        put("page_type", type); put("label", when { js.contains("UpLoadFace") -> "头像上传"; control.id() == "read_tui" -> "推荐"; else -> "<label redacted>" })
                        put("control_type", control.tagName()); put("category", category); put("reason", reason); put("suggested_adapter", adapter)
                    })
                }
                val searchEntries = doc.select("form[action]").mapNotNull { form ->
                    val target = client.parser.resolve(form.attr("action"), raw.url)
                    target?.takeIf { it.toHttpUrl().encodedPath == "/search.php" && it.toHttpUrl().querySize == 0 }
                }
                val discovered = searchEntries + doc.select("a[href]").mapNotNull { client.parser.resolve(it.attr("href"), raw.url) } +
                    native?.links.orEmpty().map { it.url } + listOfNotNull(page.composeUrl, page.next) + page.posts.map { it.quoteUrl }.filter { it.isNotBlank() }
                // Menu and task entries before repetitive thread/profile links.
                val menu = page.menu.map { it.url }
                menu.reversed().filterNot { it in visited }.forEach { queue.addFirst(it) }
                discovered.distinct().filterNot { it in visited }.forEach(queue::addLast)
                delay(180)
            }
            writeAuditJson(output.resolve("route-inventory.json"), buildJsonObject {
                put("pages", JsonArray(inventory)); put("skipped", JsonArray(skipped.values.toList()))
                put("page_classes", types.size)
                put("cookie_names", JsonArray(client.cookieJar.loadForRequest(origin.toHttpUrl()).map { JsonPrimitive(it.name) }))
            })
            writeAuditJson(output.resolve("captures.json"), JsonArray(captures))
            writeAuditJson(output.resolve("unsupported-controls.json"), JsonArray(unsupported.distinct()))
            assertTrue(sessionVerified, "Local authenticated session could not be verified.")
        } finally { client.clearSession(); client.close() }
    }
}

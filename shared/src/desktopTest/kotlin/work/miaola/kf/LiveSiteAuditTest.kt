package work.miaola.kf

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
        // Every same-origin route shape seen anywhere in the crawl is recorded,
        // even when the per-type sample cap is reached. After the queue drains,
        // one page of each still-unsampled shape is fetched, so "not sampled"
        // cannot silently mean "never looked at".
        val discoveredShapes = linkedMapOf<String, MutableSet<String>>()
        val parsedTypes = mutableSetOf<String>()
        val attemptedTypes = mutableSetOf<String>()
        val forcedUrls = mutableSetOf<String>()
        val pendingTypes = ArrayDeque<String>()
        val originHost = origin.toHttpUrl().host
        fun record(url: String) {
            val parsed = url.toHttpUrlOrNull() ?: return
            if (parsed.host != originHost) return
            discoveredShapes.getOrPut(policy.type(url)) { mutableSetOf() }.add(policy.redacted(url))
        }
        record("$origin/index.php")
        var sessionVerified = false
        var unknownControls = 0
        var unmappedSkips = 0
        try {
            while ((queue.isNotEmpty() || pendingTypes.isNotEmpty()) && inventory.size < 220) {
                val fromPending = queue.isEmpty()
                val url = (if (fromPending) pendingTypes.removeFirst() else queue.removeFirst()).substringBefore('#')
                if (fromPending) {
                    val type = policy.type(url)
                    if (type in parsedTypes || type in attemptedTypes) continue
                    forcedUrls.add(url)
                }
                val firstVisit = visited.add(url)
                if (!firstVisit && url !in forcedUrls) continue
                if (!policy.allows(url)) {
                    val safe = policy.redacted(url)
                    val parsedUrl = url.toHttpUrl()
                    val adapter = if (parsedUrl.host != originHost) "外部应用打开确认" else when (parsedUrl.encodedPath) {
                        "/login.php" -> "AccountScreen 退出确认"
                        "/diy_index_bg.php" -> "HomeSkin 设置"
                        "/kf_growup.php" -> "GrowthScreen 颜色动作"
                        "/search.php" -> "SearchScreen 作者结果"
                        "/kf_tidfavor.php" -> "PageData.favorite 确认"
                        "/masingle.php", "/operate.php" -> "Reader 页面管理表单"
                        "/kf_lv200.php" -> "ProgressScreen 管理表单"
                        "/kf_fw_1wkfb.php" -> "评分入口（确认后进入评分页）"
                        "/message.php" -> "消息详情/批量删除"
                        "/kf_fw_ig_halo.php" -> "RewardAction 光环购买"
                        "/hack.php" -> "BankScreen 表单"
                        else -> "manual verification required"
                    }
                    if (adapter == "manual verification required") unmappedSkips++
                    skipped[safe] = buildJsonObject {
                        put("route", safe); put("page_type", if (parsedUrl.host == originHost) policy.type(url) else "<external>")
                        put("effect", client.destination(url).effect.name)
                        put("adapter", adapter)
                        put("status", "native owner; confirmation required")
                    }
                    continue
                }
                val type = policy.type(url)
                if ((types[type] ?: 0) >= if (type == "read.php") 8 else 3) {
                    if (type !in parsedTypes && type !in attemptedTypes) pendingTypes.add(url)
                    continue
                }
                types[type] = (types[type] ?: 0) + 1
                attemptedTypes.add(type)
                val raw = try { client.raw(url, followRedirects = false) } catch (_: Exception) {
                    delay(300)
                    try { client.raw(url, followRedirects = false) } catch (_: Exception) {
                        if (!sessionVerified) error("Local authenticated session could not be verified (network failure).")
                        inventory.add(buildJsonObject { put("route", policy.redacted(url)); put("page_type", type); put("status", "request failed") })
                        continue
                    }
                }
                if (raw.redirectUrl != null) {
                    if (!sessionVerified && raw.redirectUrl.toHttpUrl().encodedPath == "/login.php") error("Local authenticated cookie is expired or invalid.")
                    inventory.add(buildJsonObject {
                        put("route", policy.redacted(url)); put("page_type", type); put("status", "redirect")
                        put("target", policy.redacted(raw.redirectUrl)); put("effect", client.destination(url).effect.name)
                        put("screen", "redirect; no content parsed")
                    })
                    record(raw.redirectUrl)
                    queue.add(raw.redirectUrl)
                    continue
                }
                val page = client.parser.parse(raw.html, raw.url)
                if (!sessionVerified && !page.authenticated || page.authStatus == AuthStatus.LOGIN_REQUIRED) {
                    error("Local authenticated cookie is expired or invalid.")
                }
                if (page.authenticated) sessionVerified = true
                parsedTypes.add(type)
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
                    val js = control.attr("onclick").ifBlank { control.attr("href") }
                        .removePrefix("javascript:").trim()
                    val (category, reason, adapter) = when {
                        js.contains("UpLoadFace") -> Triple("D", "ajaxface protocol", "AvatarUploadAdapter")
                        Regex("^(?:window\\.)?location(?:\\.href)?\\s*=\\s*['\"]").containsMatchIn(js.trim()) -> Triple("A", "literal navigation (policy gated)", "SiteDestination")
                        control.id() == "read_tui" -> Triple("C", "recommendation protocol", "rpc:recommend")
                        control.attr("name") == "chkall" || js.contains("checkset(") || js.contains("CheckAll(") ->
                            Triple("C", "local selection", "selection mode")
                        js.contains("showDefault(") ||
                            ((type == "read.php" || type.startsWith("post.php")) && control.text().trim().matches(Regex("""^\[.+\]$"""))) ->
                            Triple("C", "editor emoticon catalog", "native editor")
                        js.contains("flex(") -> Triple("C", "editor layout toggle", "adaptive native editor")
                        js.contains("toggle(") -> Triple("C", "local disclosure", "expandable section")
                        js.contains("jgjg(") || js.contains("jgtz(") ->
                            Triple("D", "reward/game Ajax protocol", "RewardAction (single-step)")
                        Regex("""^(?:sywp|cswp|dkhz|cdzb|rlzb_qr|gmwp)\([^)]*\)$""").containsMatchIn(js.trim()) ->
                            Triple("D", "reward subpage Ajax protocol", "RewardAction (subpage)")
                        js.contains("postreply(") || js.contains("cztz(") ->
                            Triple("A", "native reply entry", "EditorScreen / SiteDestination")
                        js.contains("cztzyx(") || js.contains("readadmove(") || js.contains("rpidnk(") || js.contains("radtip(") ->
                            Triple("B", "reader admin script (not executed)", "SiteForm")
                        js.startsWith("if(this.width") && js.contains("window.open(") ->
                            Triple("C", "image viewer", "native image gallery")
                        js.isBlank() || js == ";" -> Triple("C", "local placeholder", "no action")
                        js.contains("history.go(-1)") -> Triple("A", "native back navigation", "system back")
                        js.contains("scroll(0,0)") -> Triple("C", "scroll to top", "native list scroll")
                        type.startsWith("fyg_") -> Triple("D", "dynamic game protocol", "GuguProtocol")
                        control.closest("form") != null && control.attr("type") == "submit" -> Triple("B", "form submit (not executed)", "SiteForm")
                        else -> Triple("E", "unknown script (not executed)", "manual verification required")
                    }
                    if (category == "E") unknownControls++
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
                (menu + discovered).forEach(::record)
                menu.reversed().filterNot { it in visited }.forEach { queue.addFirst(it) }
                discovered.distinct().filterNot { it in visited }.forEach(queue::addLast)
                delay(180)
            }
            writeAuditJson(output.resolve("route-inventory.json"), buildJsonObject {
                put("pages", JsonArray(inventory)); put("skipped", JsonArray(skipped.values.toList()))
                put("page_classes", types.size)
                put("discovered_page_classes", discoveredShapes.size)
                put("cookie_names", JsonArray(client.cookieJar.loadForRequest(origin.toHttpUrl()).map { JsonPrimitive(it.name) }))
            })
            writeAuditJson(output.resolve("captures.json"), JsonArray(captures))
            writeAuditJson(output.resolve("unsupported-controls.json"), JsonArray(unsupported.distinct()))
            val sampledTypes = inventory.mapNotNull { it.jsonObject["page_type"]?.jsonPrimitive?.content }.toSet()
            val skippedTypes = skipped.values.mapNotNull { it.jsonObject["page_type"]?.jsonPrimitive?.content }.toSet()
            val coveredTypes = sampledTypes + skippedTypes
            val uncovered = discoveredShapes.keys.filterNot { it in coveredTypes }.sorted()
            writeAuditJson(output.resolve("route-shapes.json"), buildJsonObject {
                put("shapes", JsonArray(discoveredShapes.map { (type, routes) ->
                    buildJsonObject {
                        put("page_type", type); put("routes", JsonArray(routes.sorted().map(::JsonPrimitive)))
                        put("sampled", type in sampledTypes)
                        put("skipped", type in skippedTypes)
                        put("covered", type in coveredTypes)
                    }
                }))
                put("uncovered", JsonArray(uncovered.map(::JsonPrimitive)))
            })
            assertTrue(uncovered.isEmpty(), "Discovered route shapes were neither sampled nor explicitly skipped: $uncovered")
            assertEquals(0, unmappedSkips, "Skipped routes have no concrete native owner; inspect route-inventory.json.")
            assertEquals(0, unknownControls, "Unknown script controls remain; inspect unsupported-controls.json.")
            assertTrue(sessionVerified, "Local authenticated session could not be verified.")
        } finally { client.clearSession(); client.close() }
    }
}

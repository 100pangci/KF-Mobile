@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package work.miaola.kf

import androidx.compose.material3.Text
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import work.miaola.kf.ui.KfApp
import kotlin.test.*

/** Real local captures -> the production parser -> shared Compose, with NO network. */
class LiveAuditRenderTest {
    @Test fun localCapturedScreensInBothThemesAndPhoneWidths() {
        assumeTrue(System.getenv("KF_AUDIT_RENDER") == "true" && System.getenv("CI").isNullOrBlank())
        val root = Path.of(System.getProperty("kf.projectRoot"))
        val directory = root.resolve(".secrets/site-audit")
        assumeTrue(Files.isRegularFile(directory.resolve("captures.json")))
        val origin = "https://kf.miaola.work"
        val parser = ForumHtmlParser(origin)
        val manifest = Json.parseToJsonElement(Files.readString(directory.resolve("captures.json"))).jsonArray
        val captures = manifest.groupBy { it.jsonObject.getValue("page_type").jsonPrimitive.content }.mapValues { (_, entries) ->
            val entry = entries.first()
            val obj = entry.jsonObject
            obj.getValue("capture").jsonPrimitive.content
        }
        fun source(type: String): String {
            val path = type.substringBefore(':')
            val variant = type.substringAfter(':', "")
            return "$origin/$path" + when {
                path == "hack.php" -> "?H_name=bank"
                path == "read.php" -> "?tid=1"
                path == "thread.php" -> "?fid=1"
                path == "post.php" -> "?fid=1"
                path == "kf_no1.php" && variant.isNotEmpty() -> "?no1=$variant"
                path == "kf_fw_1wkfb.php" && variant.isNotEmpty() -> "?ping=$variant"
                variant.isNotEmpty() -> "?action=$variant"
                else -> ""
            }
        }
        val parsed = captures.mapValues { (type, id) -> parser.parse(Files.readString(directory.resolve("$id.html")), source(type)) }
        val home = parsed.getValue("index.php")
        val editorHtml = captures["post.php"]?.let { Files.readString(directory.resolve("$it.html")) }
        val form = editorHtml?.let { runCatching { parser.parseEditor(it, "$origin/post.php?fid=1") }.getOrNull() }
        val fake = object : ForumService {
            override val origin = "https://kf.miaola.work"
            override suspend fun fetch(url: String): PageData {
                val target = origin.toHttpUrl().resolve(url)!!
                val type = AuditReadPolicy(origin).type(target.toString())
                return (parsed[type] ?: parsed[target.encodedPath.substringAfterLast('/')] ?: home).copy(url = target.toString())
            }
            override fun destination(url: String, title: String) = SiteRoutePolicy(origin).classify(url, title)
            override suspend fun editor(url: String) = requireNotNull(form) { "No editor capture available" }
            override suspend fun login(username: String, password: String): PageData = error("Offline rendering cannot log in")
            override suspend fun search(keyword: String, author: String): PageData = error("Offline rendering cannot search")
            override suspend fun submit(form: EditorForm, values: Map<String, String>): SubmitResult = error("Offline rendering cannot submit")
            override fun encodedLength(value: String) = gbkBytes(value).size
            override fun clearSession() = Unit
            override fun close() = Unit
        }
        val factory = object : ServiceFactory {
            override fun create(origin: String) = fake
            override suspend fun catalog(url: String): String = error("Offline")
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = AppController(MemoryStore(), factory, scope, { _, _ -> })
        val output = directory.resolve("screenshots")
        Files.createDirectories(output)
        val rendered = mutableListOf<JsonElement>()
        fun render(name: String) {
            listOf(360, 412).forEach { width -> listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { theme ->
                c.updateTheme(theme)
                val scene = ImageComposeScene(width = width, height = 915, density = Density(1f)) {
                    KfApp(c) { html, _, _, _, _ -> Text(org.jsoup.Jsoup.parse(html).wholeText()) }
                }
                try {
                    scene.render(0).close(); scene.render(250_000_000).close()
                    scene.render(1_000_000_000).use { image ->
                        val bytes = image.encodeToData(EncodedImageFormat.PNG)?.bytes
                        assertNotNull(bytes); assertTrue(bytes.size > 1000, "Screen rendered empty")
                        Files.write(output.resolve("$name-${theme.name.lowercase()}-$width.png"), bytes)
                    }
                } finally { scene.close() }
            } }
            rendered.add(buildJsonObject { put("screen", name); put("rendered", true) })
        }
        try {
            render("feed")
            c.selectTab(HomeTab.BOARDS); render("boards")
            c.selectTab(HomeTab.ACCOUNT); render("account")
            c.selectTab(HomeTab.FEED)
            val targets = linkedMapOf("topic-list" to "thread.php", "reader" to "read.php", "messages" to "message.php",
                "message-detail" to "message.php:read", "message-tracking" to "message.php:scout", "blocklist" to "message.php:banned", "profile" to "profile.php:show", "favorites" to "profile.php:favor",
                "profile-settings" to "profile.php:modify", "bank" to "hack.php", "growth" to "kf_growup.php",
                "ranking" to "kf_no1.php", "rating" to "kf_fw_1wkfb.php:3", "mentions" to "guanjianci.php", "faq" to "faq.php",
                "reward" to "kf_fw_ig_index.php", "progress" to "kf_lv200.php")
            targets.forEach { (name, type) -> if (type in parsed) {
                if (type == "message.php") c.selectTab(HomeTab.MESSAGES)
                c.load(source(type)); render(name)
            } }
            c.showSettings = true; render("settings"); c.showSettings = false
            if (form != null) { c.openEditor(form.sourceUrl); render("editor"); c.dismissEditor() }
            writeAuditJson(directory.resolve("screenshot-index.json"), buildJsonObject {
                put("screens", JsonArray(rendered)); put("widths", JsonArray(listOf(JsonPrimitive(360), JsonPrimitive(412))))
                put("note", "Offline real captures; rich text rendered as text, avatars use placeholders. Synthetic source IDs, no live mutations or image requests.")
            })
        } finally { c.close(); scope.cancel() }
    }
}

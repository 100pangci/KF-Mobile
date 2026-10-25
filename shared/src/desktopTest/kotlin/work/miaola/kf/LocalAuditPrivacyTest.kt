package work.miaola.kf

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Local only: compare private markers in memory; diagnostics contain counts only. */
class LocalAuditPrivacyTest {
    @Test fun privateCaptureMarkersAreAbsentFromPublicRepositoryFiles() {
        assumeTrue(System.getenv("KF_AUDIT_RENDER") == "true" && System.getenv("CI").isNullOrBlank())
        val root = Path.of(System.getProperty("kf.projectRoot"))
        val directory = root.resolve(".secrets/site-audit")
        val origin = "https://kf.miaola.work"
        val parser = ForumHtmlParser(origin)
        val markers = mutableMapOf<String, MutableSet<String>>()
        val skinPreferences = mutableSetOf<String>()
        fun add(value: String, kind: String) { markers.getOrPut(value) { mutableSetOf() }.add(kind) }
        val cookiePath = LocalAuditCookies.file(root, origin.toHttpUrl().host)
        LocalAuditCookies.load(Files.readString(cookiePath), origin).filter { it.value.length >= 6 }.forEach {
            // A documented non-authentication skin preference may be a common
            // programming word. Count such collisions separately, never print
            // the value or suppress authentication/session cookies this way.
            if (it.name.endsWith("_skinco")) skinPreferences.add(it.value) else add(it.value, "cookie")
        }
        val manifest = Json.parseToJsonElement(Files.readString(directory.resolve("captures.json"))).jsonArray
        manifest.forEach { entry ->
            val obj = entry.jsonObject
            val type = obj.getValue("page_type").jsonPrimitive.content
            val source = "$origin/${type.substringBefore(':')}" + if (':' in type) "?action=${type.substringAfter(':')}" else ""
            val raw = Files.readString(directory.resolve(obj.getValue("capture").jsonPrimitive.content + ".html"))
            val doc = parser.document(raw, source)
            doc.select("#kf_topuser").map { it.text().trim() }.filter { it.length >= 3 }.forEach { add(it, "username") }
            doc.select("a[href]").mapNotNull { parser.resolve(it.attr("href"), source)?.toHttpUrl() }.forEach { url ->
                listOf("uid", "tid", "mid", "safeid", "verify").forEach { name -> url.queryParameter(name)?.takeIf { it.length >= 6 }?.let { add(it, name) } }
            }
            doc.select("input[name=verify],input[name=safeid],input#safeid").map { it.attr("value") }.filter { it.length >= 6 }.forEach { add(it, "token") }
            Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").findAll(doc.text()).map { it.value }
                .filterNot { it.endsWith("@example.com") }.forEach { add(it, "email") }
            if (type == "message.php:read") {
                val message = parser.parse(raw, source).nativePage?.message
                message?.let { parser.document(it.html, source).text().trim().takeIf { text -> text.length >= 12 }?.let { add(it, "message") } }
            }
            if (type == "hack.php") parser.parse(raw, "$origin/hack.php?H_name=bank").nativePage?.bank?.balances
                ?.map { it.value }?.filter { it.length >= 6 }?.forEach { add(it, "balance") }
        }
        val process = ProcessBuilder("git", "ls-files", "--cached", "--others", "--exclude-standard", "-z").directory(root.toFile()).start()
        val paths = process.inputStream.readBytes().toString(Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }
        check(process.waitFor() == 0) { "Unable to enumerate public files for privacy scan." }
        val textExtensions = setOf("kt", "kts", "md", "py", "json", "xml", "yml", "yaml", "txt", "properties", "toml")
        var matchingFiles = 0
        var scanned = 0
        var preferenceCollisions = 0
        val findings = mutableListOf<JsonElement>()
        paths.forEach { name ->
            val path = root.resolve(name)
            if (!Files.isRegularFile(path) || name.substringAfterLast('.') !in textExtensions) return@forEach
            scanned++
            val text = Files.readString(path)
            if (skinPreferences.any { text.contains(it) }) preferenceCollisions++
            val kinds = markers.filterKeys { text.contains(it) }.values.flatten().distinct()
            if (kinds.isNotEmpty()) {
                matchingFiles++
                findings.add(buildJsonObject { put("file", name); put("kinds", JsonArray(kinds.map(::JsonPrimitive))) })
            }
        }
        writeAuditJson(directory.resolve("privacy-scan.json"), buildJsonObject {
            put("scanned_files", scanned); put("matching_files", matchingFiles)
            put("private_files_tracked", paths.count { it.startsWith(".secrets/") || it == "cookie.txt" || it.endsWith(".cookies.txt") })
            put("findings", JsonArray(findings))
            put("non_auth_skin_preference_collisions", preferenceCollisions)
        })
        assertEquals(0, matchingFiles, "Private markers were found; details are intentionally suppressed.")
        assertTrue(paths.none { it.startsWith(".secrets/") || it == "cookie.txt" || it.endsWith(".cookies.txt") }, "Private file is tracked.")
    }
}

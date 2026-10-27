package work.miaola.kf

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import kotlin.test.Test

/** Opt-in, offline protocol evidence. No identifiers, values or free text in output. */
class LocalAuditStructureTest {
    @Test fun capturedProtocolShapes() {
        assumeTrue(System.getenv("KF_AUDIT_RENDER") == "true" && System.getenv("CI").isNullOrBlank())
        val root = Path.of(System.getProperty("kf.projectRoot"), ".secrets/site-audit")
        val manifest = Json.parseToJsonElement(Files.readString(root.resolve("captures.json"))).jsonArray
        val origin = "https://kf.miaola.work"
        val parser = ForumHtmlParser(origin)
        val policy = AuditReadPolicy(origin)
        val wanted = setOf("message.php", "message.php:receivebox", "message.php:sendbox", "message.php:read", "message.php:banned", "message.php:scout", "hack.php", "guanjianci.php", "kf_lv200.php", "kf_fw_ig_index.php")
        val labels = setOf("进度分", "当前进度分", "今日进度分", "总进度分", "剩余", "已完成", "未完成", "已领取", "未领取", "banidinfo")
        val entries = manifest.filter { it.jsonObject["page_type"]?.jsonPrimitive?.content in wanted }.map { entry ->
            val type = entry.jsonObject.getValue("page_type").jsonPrimitive.content
            val source = "$origin/${type.substringBefore(':')}" + when {
                type == "hack.php" -> "?H_name=bank"
                ':' in type -> "?action=${type.substringAfter(':')}"
                else -> ""
            }
            val doc = parser.document(Files.readString(root.resolve(entry.jsonObject.getValue("capture").jsonPrimitive.content + ".html")), source)
            val native = parser.parse(doc.outerHtml(), source).nativePage!!
            val main = doc.select(".dcol").lastOrNull { it.attr("style").contains("900px") } ?: doc.body()
            buildJsonObject {
                put("type", type)
                put("forms", JsonArray(native.forms.map { form -> buildJsonObject {
                    put("method", form.method); put("target", policy.redacted(form.actionUrl))
                    put("fields", JsonArray(form.fields.map { JsonPrimitive("${it.name}:${it.kind}") }))
                    put("hidden_names", JsonArray(form.hidden.map { JsonPrimitive(it.name) }))
                } }))
                put("blocks", JsonArray(native.blocks.map { block -> JsonPrimitive(when (block) {
                    is NativeBlock.Table -> "table:${block.table.style}:${block.table.rows.size}x${block.table.rows.maxOfOrNull { it.size }}"
                    is NativeBlock.Section -> "section:${block.text.length}"
                    is NativeBlock.Links -> "links:${block.links.size}"
                }) }))
                put("known_labels", JsonArray(labels.filter { main.text().contains(it) }.map(::JsonPrimitive)))
                put("progress_stats", native.progress?.stats?.size ?: 0)
                put("message_count", native.messages.size)
                put("message_unread_count", native.messages.count { it.unread })
                put("message_sender_count", native.messages.count { it.sender.isNotBlank() })
                put("message_recipient_count", native.messages.count { it.recipient.isNotBlank() })
                put("message_time_count", native.messages.count { it.time.isNotBlank() })
                put("message_person_link_count", native.messages.count { it.personUrl.isNotBlank() })
                val readMarkers = setOf("是", "否", "已读", "未读", "已阅读", "未阅读")
                put("read_state_rows", main.select("tr").count { row ->
                    val cells = row.select(">td")
                    cells.size >= 6 && cells[5].text().trim() in readMarkers ||
                        type == "message.php:scout" && cells.size >= 7 && cells[6].text().trim() in readMarkers
                })
                put("message_detail", native.message != null)
                put("message_reply", native.message?.reply != null)
                put("participant_labels", JsonArray(setOf("发件人", "收件人", "标题", "时间", "内容", "选项").filter { label ->
                    main.select("tr").any { row -> row.select(">td,>th").firstOrNull()?.text()?.trim()?.trimEnd(':', '：') == label }
                }.map(::JsonPrimitive)))
                put("mentions_record_tables", if (type == "guanjianci.php") native.tables.count { it.records().isNotEmpty() } else 0)
                if (type == "kf_lv200.php") put("score_template", JsonArray(main.select("span,b,strong").filter { it.text().trim().matches(Regex("[-+]?[0-9]+(?:\\.[0-9]+)?")) }.map { el -> buildJsonObject {
                    val before = el.previousSibling()?.toString().orEmpty()
                    put("preceded_by_label", before.contains("当前进度分"))
                    put("suffix_shape", before.takeLast(12).map { char -> when { char.isDigit() -> '#'; char.isLetter() -> 'X'; char.isWhitespace() -> ' '; else -> char } }.joinToString(""))
                } }))
                put("elements", JsonArray(main.select("b,strong,span,div,p,td").filter { el ->
                    el.children().isEmpty() && el.text().isNotBlank()
                }.take(100).map { el -> buildJsonObject {
                    put("tag", el.tagName()); put("numeric", el.text().matches(Regex("[0-9.,% +/\\-]+")))
                    put("labels", JsonArray(labels.filter { el.text().contains(it) }.map(::JsonPrimitive)))
                } }))
                put("links", JsonArray(main.select("a[href]").mapNotNull { a -> parser.resolve(a.attr("href"), source) }.distinct().map { target -> buildJsonObject {
                    put("route", policy.redacted(target)); put("effect", SiteRoutePolicy(origin).classify(target).effect.name)
                    put("tracking_detail", target.contains("action=readscout"))
                    put("bank_log", target.contains("action=log"))
                } }))
            }
        }
        writeAuditJson(root.resolve("protocol-shapes.json"), JsonArray(entries))
    }
}

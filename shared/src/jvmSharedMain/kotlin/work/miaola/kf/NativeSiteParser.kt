package work.miaola.kf

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/** HTML is a transport format only. Controls become native Compose models. */
class NativeSiteParser(private val origin: String, private val html: ForumHtmlParser) {
    private val routes = SiteRoutePolicy(origin)
    fun parse(doc: Document, sourceUrl: String): NativeSitePage {
        val source = sourceUrl.toHttpUrl()
        val path = source.pathSegments.lastOrNull().orEmpty()
        val action = source.queryParameter("action").orEmpty()
        val mainOriginal = doc.select(".dcol").lastOrNull { it.attr("style").contains("900px") }
            ?: doc.selectFirst("#main, #content, #alldiv") ?: doc.body()
        val main = mainOriginal.clone()
        main.select("script,style,.topmenuo,#kf_information,.rightboxa,#cnzz_stat_icon_1257832900").remove()
        main.select("img[src*=ys/top]").forEach { it.parent()?.remove() }
        val title = when (path) {
            "profile.php" -> when (action) { "modify" -> "修改资料"; "favor" -> "收藏管理"; else -> "会员资料" }
            "message.php" -> when (action) { "read" -> "私信内容"; "banned" -> "屏蔽列表"; "scout" -> "消息跟踪"; "sendbox" -> "发件箱"; else -> "消息管理" }
            "hack.php" -> when {
                action == "log" -> "银行日志"
                source.queryParameter("H_name") == "bank" -> "贡献与银行"
                else -> "站点功能"
            }
            "kf_no1.php" -> "会员排行"
            "kf_growup.php" -> "签到与成长"
            "kf_fw_1wkfb.php" -> "评分与记录"
            "kf_fw_ig_index.php" -> "争夺奖励"
            "kf_lv200.php" -> "进度分"
            "fyg_sjcdwj.php" -> "咕咕镇"
            "guanjianci.php" -> "被 @ 记录"
            "faq.php" -> "帮助与规则"
            "register.php" -> "注册账号"
            "login.php" -> "账号登录"
            else -> doc.title().substringBefore(" - ").trim().ifBlank { "站点功能" }
        }
        val forms = mainOriginal.select("form").filterNot { it.attr("action").startsWith("search.php") || it.attr("name") == "delatc" || it.attr("name") == "preview" }
            .mapIndexedNotNull { index, form -> parseForm(form, sourceUrl, "$path:$index", title) }
            .toMutableList()
        if (path == "read.php") {
            val tid = doc.selectFirst("#tui_tid")?.attr("value").orEmpty()
            val safe = doc.selectFirst("#safeid")?.attr("value").orEmpty()
            if (tid == source.queryParameter("tid") && tid.matches(Regex("[0-9]+")) && safe.isNotBlank() && doc.selectFirst("#read_tui") != null) {
                forms.add(SiteForm("rpc:recommend", "推荐此帖", sourceUrl, "$origin/diy_read_tui.php", "POST",
                    listOf(FormValue("tid", tid), FormValue("safeid", safe)), emptyList(), listOf(SiteSubmit("推荐此帖", null, "")),
                    warning = "推荐按站点规则执行，可能消耗余额或次数。请确认，不会自动连续推荐。"))
            }
        }
        val profile = if (path == "profile.php" && action == "show") parseProfile(mainOriginal, sourceUrl) else null
        val growth = if (path == "kf_growup.php") parseGrowth(mainOriginal, sourceUrl) else null
        val work = main.clone()
        work.select("form label").remove()
        work.select("form").unwrap()
        val unsupported = mutableListOf<String>()
        if (path == "fyg_sjcdwj.php") unsupported.add("咕咕镇战斗、背包及动态脚本界面仍需专项原生适配")
        val buttonLinks = work.select("input[type=button], button, a[onclick]").mapNotNull { control ->
            val label = control.attr("value").ifBlank { control.text() }.trim().take(100)
            if (label.isBlank()) return@mapNotNull null
            val js = control.attr("onclick").removePrefix("javascript:").trim()
            if (js.startsWith("UpLoadFace(")) { unsupported.add("头像上传（ajaxface 脚本尚待适配）"); return@mapNotNull null }
            if (control.id() == "read_tui" && forms.any { it.id == "rpc:recommend" }) return@mapNotNull null
            if (control.hasAttr("href") && !control.attr("href").startsWith("javascript:", true)) return@mapNotNull null
            if (control.tagName() == "button" && control.attr("type").lowercase() !in listOf("button", "reset") && control.closest("form") != null) return@mapNotNull null
            val target = Regex("(?:window\\.)?location(?:\\.href)?\\s*=\\s*(['\"])([^'\"]+)\\1\\s*;?")
                .matchEntire(js)?.groupValues?.get(2)
            if (target != null) return@mapNotNull routes.classify(html.resolve(target, sourceUrl) ?: target, label)
            if (control.attr("name") !in listOf("chkall")) unsupported.add(label)
            null
        }
        if (profile != null) work.selectFirst("table.log1")?.remove()
        if (path == "profile.php" && action == "modify") work.select("table.log1 tr").forEach { row ->
            // Field widgets already have a dedicated editor; don't also discard
            // new explanatory rows or unrelated tables introduced by the server.
            if (row.select("input,textarea,select,button").isNotEmpty()) row.remove()
        }
        work.select("input,textarea,select,button,iframe,object,embed").remove()
        work.select("a[onclick],a[href^=javascript:]").forEach { it.remove() }
        var messageBody: String? = null
        var message: NativeMessageDetail? = null
        val messageActions = mutableListOf<SiteDestination>()
        if (path == "message.php") {
            // The mailbox quota + folder navigation table is duplicated by the
            // native tabs, so drop it structurally before blocks are built.
            work.select("table").forEach { table ->
                val links = table.select("a[href]").map { it.attr("href") }
                if (links.any { it.contains("message.php?action=receivebox") } &&
                    links.any { it.contains("message.php?action=sendbox") }) table.remove()
            }
            if (action == "read") {
                val rows = work.select("tr").toList()
                val optionIndex = rows.indexOfLast { row ->
                    val cells = row.select("> td")
                    val links = cells.getOrNull(1)?.select("a[href]").orEmpty()
                    cells.size == 2 && cells[0].text().trim().trimEnd(':', '：') in setOf("选项", "操作") && links.isNotEmpty() && links.all { a ->
                        html.resolve(a.attr("href"), sourceUrl)?.toHttpUrl()?.let {
                            it.encodedPath == "/message.php" && it.queryParameter("action") in setOf("write", "unread", "del", "receivebox", "sendbox", "scout")
                        } == true
                    }
                }
                if (optionIndex > 0) {
                    val optionRow = rows[optionIndex]
                    optionRow.select("> td").getOrNull(1)?.select("a[href]")?.forEach { a ->
                        val text = a.text().trim()
                        val target = html.resolve(a.attr("href"), sourceUrl) ?: return@forEach
                        if (text.isNotBlank()) messageActions.add(routes.classify(target, text))
                    }
                    // The stable detail template places content immediately
                    // before options. A long title is not the message body.
                    val bodyRow = rows.subList(0, optionIndex)
                        .filter { it.select("> td").size == 2 }
                        .lastOrNull()
                    bodyRow?.select("> td")?.getOrNull(1)?.clone()?.let { cell ->
                        cell.select("script,style").remove()
                        messageBody = cell.html().let { html.cleanContent(it, sourceUrl) }
                    }
                    val facts = rows.subList(0, optionIndex).filter { it !== bodyRow && it.select("> td").size == 2 }
                        .map { it.select("> td")[1].text().trim() }
                    if (messageBody != null && facts.size >= 3) {
                        message = NativeMessageDetail(facts[1], facts[0], facts[2], messageBody.orEmpty(), messageActions.toList(),
                            messageActions.firstOrNull { it.effect == SiteEffect.READ && it.url.toHttpUrl().queryParameter("action") == "write" })
                    }
                    bodyRow?.remove()
                    optionRow.remove()
                }
            }
        }
        val extractedTables = mutableSetOf<Element>()
        val blocks = buildBlocks(work, sourceUrl, extractedTables).map { block ->
            if (block is NativeBlock.Table && path == "thread.php" &&
                block.table.rows.firstOrNull()?.any { cell -> cell.link?.contains("post.php") == true } == true) {
                val data = block.table.rows.drop(1)
                val header = when (data.maxOfOrNull { row -> row.sumOf { it.colspan } }) {
                    4 -> listOf(
                        NativeCell("状态", bold = true), NativeCell("主题", bold = true),
                        NativeCell("回复/查看", bold = true), NativeCell("最后发表", bold = true),
                    )
                    else -> emptyList()
                }
                block.copy(table = block.table.copy(rows = if (header.isEmpty()) data else listOf(header) + data, header = header.isNotEmpty()))
            } else block
        }.toMutableList()
        if (!messageBody.isNullOrBlank()) {
            val index = blocks.indexOfFirst { it is NativeBlock.Table }
            blocks.add(if (index >= 0) index + 1 else 0, NativeBlock.Section("", messageBody.orEmpty()))
        }
        if (messageActions.isNotEmpty()) blocks.add(NativeBlock.Links(messageActions.distinctBy { it.url }))
        val tables = blocks.filterIsInstance<NativeBlock.Table>().map { it.table }
        val sections = blocks.filterIsInstance<NativeBlock.Section>().map { NativeSection(it.title, it.html) }
        val links = mutableListOf<SiteDestination>()
        val seen = mutableSetOf<String>()
        fun addLink(link: SiteDestination?) {
            if (link == null || link.url.isBlank() || !seen.add(link.url) || links.size >= 60) return
            links.add(link)
        }
        profile?.links?.forEach(::addLink)
        buttonLinks.forEach(::addLink)
        work.select("a[href]").filter { a -> a.closest("table")?.let { it !in extractedTables } ?: true }.forEach { a ->
            val href = a.attr("href")
            val label = a.text().trim().ifBlank { a.attr("title") }.take(120)
            if (label.isBlank() || href.startsWith('#') || href.startsWith("javascript:", true)) return@forEach
            addLink(routes.classify(html.resolve(href, sourceUrl) ?: href, label))
        }
        val usefulLinks = links.filterNot { link ->
            (path == "index.php" && link.url.toHttpUrlOrNull()?.pathSegments?.lastOrNull() in listOf("read.php", "thread.php")) ||
                link.url.toHttpUrlOrNull()?.let { it.encodedPath == "/kf_fw_1wkfb.php" && it.queryParameter("do") == "1" } == true
        }
        val messages = if (path == "message.php" && action in setOf("", "receivebox", "sendbox", "scout")) {
            mainOriginal.select("a[href*='message.php?']").mapNotNull { a ->
                val target = html.resolve(a.attr("href"), sourceUrl)?.toHttpUrl() ?: return@mapNotNull null
                if (target.queryParameter("action") !in setOf("read", "readscout")) return@mapNotNull null
                val cells = a.closest("tr")?.select("> td").orEmpty()
                if (cells.size < 6) return@mapNotNull null
                // Tracking adds a selection column before its read marker.
                val readCell = cells[if (action == "scout" && cells.size >= 7) 6 else 5]
                NativeMessageSummary(target.toString(), a.text(), cells[2].text(), cells[3].text(), cells[4].text(),
                    readCell.text().trim().let { it == "否" || it.contains("未读") || it.contains("未阅读") },
                    cells[if (action in setOf("sendbox", "scout")) 3 else 2].selectFirst("a[href]")?.let { html.resolve(it.attr("href"), sourceUrl) }.orEmpty())
            }.distinctBy { it.url }
        } else emptyList()
        val favorites = if (path == "profile.php" && action == "favor") parseFavorites(mainOriginal, sourceUrl, forms) else null
        val bank = if (path == "hack.php" && source.queryParameter("H_name") == "bank" && action.isEmpty()) parseBank(mainOriginal, sourceUrl) else null
        val blocklist = if (path == "message.php" && action == "banned") forms.firstNotNullOfOrNull { form ->
            form.fields.singleOrNull { it.name == "banidinfo" && it.kind == SiteFieldKind.MULTILINE }?.let { NativeBlocklist(form.id, it.id) }
        } else null
        val progress = if (path == "kf_lv200.php") parseProgress(mainOriginal) else null
        val statusTimeline = when {
            path == "kf_fw_1wkfb.php" -> parseStatusTimeline(mainOriginal, sourceUrl)
            path == "kf_growup.php" && growth != null -> NativeStatusTimeline("签到与成长", listOf(
                StatusTimelineEntry(
                    title = "每日签到",
                    status = growth.claimStatus,
                    description = when {
                        growth.claimStatus == StatusKind.COMPLETED -> "站点已明确标记今日完成"
                        growth.claimStatus == StatusKind.AVAILABLE -> "站点提供签到入口"
                        growth.claimStatus == StatusKind.WAITING -> "站点状态为等待处理"
                        growth.claimStatus == StatusKind.EXPIRED -> "站点明确标记已过期"
                        growth.claimStatus == StatusKind.FAILED -> "站点明确标记失败"
                        else -> "站点未提供可判断的状态"
                    },
                    action = growth.claimUrl?.let { routes.classify(it, "签到") }?.takeIf { it.effect != SiteEffect.READ },
                )
            ))
            else -> null
        }
        val bankLog = if (path == "hack.php" && action == "log") parseBankLog(mainOriginal, sourceUrl) else null
        val rewards = parseRewardActions(path, mainOriginal, sourceUrl)
        val messageList = path == "message.php" && action in setOf("", "receivebox", "sendbox", "scout") && mainOriginal.select("tr").any { row ->
            row.select(">td,>th").map { it.text().trim() }.containsAll(listOf("标题", "发件人", "收件人"))
        }
        return NativeSitePage(title, profile, sections, usefulLinks, forms, unsupported.distinct(), tables, blocks, growth,
            messages, message, favorites, bank, blocklist, progress, messageList,
            statusTimeline = statusTimeline, bankLog = bankLog, rewards = rewards)
    }

    /**
     * Walk the page in document order. Section text and tables become separate
     * native blocks so the layout does not collapse into one prose dump.
     */
    private fun buildBlocks(root: Element, url: String, extractedTables: MutableSet<Element>): List<NativeBlock> {
        val blocks = mutableListOf<NativeBlock>()
        val buffer = Element("div")
        var heading = ""

        fun append(node: Node) { buffer.appendChild(node.clone()) }
        fun flush() {
            val text = buffer.text().trim()
            val raw = buffer.html()
            val linkOnly = buffer.select("a[href]").isNotEmpty() &&
                buffer.clone().apply { select("a[href]").remove() }.text().replace(Regex("[\\s·|\\[\\]（）()]+"), "").isBlank()
            if (raw.isNotBlank() && text.isNotBlank() && !linkOnly) {
                var cleaned = html.cleanContent(restyleInline(buffer.clone()).html(), url)
                    .replace(Regex("^(?:\\s*<br\\s*/?>)+", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("(?:\\s*<br\\s*/?>)+$", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("(?:\\s*<br\\s*/?>\\s*){2,}", RegexOption.IGNORE_CASE), "<br>")
                val compact = text.length <= 120 && buffer.select("a,img,hr,fieldset,table").isEmpty() &&
                    buffer.select("div,p").size <= 2
                if (cleaned.isNotBlank()) {
                    blocks.add(NativeBlock.Section(heading, cleaned, compact, text))
                }
            } else if (heading.isNotBlank() && raw.isBlank()) {
                blocks.add(NativeBlock.Section(heading, "", true, heading))
            }
            buffer.empty(); heading = ""
        }

        fun walk(element: Element) {
            element.childNodes().forEach { node ->
                when (node) {
                    is TextNode -> append(node)
                    !is Element -> Unit
                    else -> {
                        val tag = node.tagName()
                        when {
                            tag in setOf("script", "style", "iframe", "object", "embed") -> Unit
                            tag == "table" -> {
                                val nested = node.select("table").any { it !== node && qualifies(it) }
                                if (!nested && qualifies(node)) {
                                    val table = parseTable(node, url)
                                    if (table != null) {
                                        extractedTables.add(node)
                                        val caption = (if (buffer.text().isBlank()) heading.ifBlank { table.caption } else table.caption)
                                        flush()
                                        blocks.add(NativeBlock.Table(if (caption.isBlank()) table else table.copy(caption = caption)))
                                        heading = ""
                                    } else walk(node)
                                } else walk(node)
                            }
                            headingOf(node) != null -> {
                                flush()
                                heading = headingOf(node).orEmpty()
                            }
                            node.tagName() == "form" -> walk(node)
                            tag == "br" -> buffer.appendChild(Element("br"))
                            tag in setOf("tr", "td", "th", "tbody", "thead", "tfoot") -> {
                                val before = buffer.text().length
                                walk(node)
                                if (tag in setOf("tr", "td", "th") && buffer.text().length > before) buffer.appendChild(Element("br"))
                            }
                            node.select("table").isNotEmpty() || node.select("div,p,center,h1,h2,h3,h4,td,th").any { headingOf(it) != null } -> walk(node)
                            else -> append(node)
                        }
                    }
                }
            }
        }
        walk(root)
        flush()
        return blocks
    }

    private fun directRows(table: Element): List<Element> =
        table.select("> tbody > tr, > tr").ifEmpty { table.select("tr") }

    private fun qualifies(table: Element): Boolean {
        val rows = directRows(table)
        if (rows.isEmpty()) return false
        val cells = rows.map { row -> row.select("> td, > th") }
        val maxCols = cells.maxOfOrNull { row -> row.sumOf { it.attr("colspan").toIntOrNull()?.coerceIn(1, 12) ?: 1 } } ?: 0
        if (maxCols < 2) return false
        if (rows.size == 1 && maxCols >= 3) {
            val row = cells.first()
            return row.isNotEmpty() && row.all { it.text().trim().length <= 32 } && table.select("a[href]").size >= 2
        }
        if (rows.size == 1) return false
        if (rows.size <= 2 && maxCols > 1 && cells.any { row ->
                row.size > 1 && row.count { it.text().isNotBlank() || it.selectFirst("a[href]") != null } == 1
            }) return false
        val nested = table.select("table").filter { it !== table }
        return nested.none { qualifies(it) }
    }

    private fun parseTable(table: Element, url: String): NativeTable? {
        val navLinks = mutableListOf<SiteDestination>()
        val rowElements = directRows(table).filterNot { row ->
            val cells = row.select("> td, > th")
            val first = cells.firstOrNull { it.text().isNotBlank() || it.selectFirst("a[href]") != null } ?: return@filterNot false
            val othersBlank = cells.all { it === first || (it.text().isBlank() && it.selectFirst("a[href]") == null) }
            val anchors = first.select("a[href]")
            val remainder = first.clone().apply { select("a[href]").remove() }.text().replace(Regex("[\\s·|\\[\\]（）()]+"), "")
            if (othersBlank && anchors.size >= 2 && remainder.isBlank()) {
                anchors.forEach { a ->
                    val label = a.text().trim().take(60)
                    val target = html.resolve(a.attr("href"), url) ?: return@forEach
                    if (label.isNotBlank()) navLinks.add(routes.classify(target, label))
                }
                true
            } else false
        }
        var rows = rowElements.map { row ->
            row.select("> td, > th").take(12).map { cell ->
                val colspan = cell.attr("colspan").toIntOrNull()?.coerceIn(1, 12) ?: 1
                val link = cell.selectFirst("a[href]")?.let { a -> html.resolve(a.attr("href"), url) }
                val text = cell.text().replace(Regex("\\s+"), " ").trim()
                    .ifBlank { cell.selectFirst("img")?.attr("alt").orEmpty() }
                NativeCell(
                    text = text,
                    link = link,
                    color = cellColor(cell),
                    background = cellBackground(cell),
                    bold = cell.tagName() == "th" || cell.hasClass("kf_share2") ||
                        Regex("font-weight\\s*:\\s*(bold|[6-9]00)", RegexOption.IGNORE_CASE).containsMatchIn(cell.attr("style")) ||
                        cell.selectFirst("b,strong") != null,
                    colspan = colspan,
                    align = when (Regex("text-align\\s*:\\s*(center|right)", RegexOption.IGNORE_CASE).find(cell.attr("style"))?.groupValues?.get(1)?.lowercase()) {
                        "center" -> NativeAlign.CENTER; "right" -> NativeAlign.END; else -> NativeAlign.START
                    },
                    actions = cell.select("a[href]").mapNotNull { a ->
                        html.resolve(a.attr("href"), url)?.let { routes.classify(it, a.text().trim()) }
                    }.filter { it.title.isNotBlank() }.distinctBy { it.url },
                )
            }
        }.filter { row -> row.any { it.text.isNotBlank() || it.link != null } }
        if (rows.isEmpty()) return null
        val columns = rows.maxOf { row -> row.sumOf { it.colspan } }.coerceIn(1, 12)
        rows.firstOrNull()?.let { first ->
            val span = first.sumOf { it.colspan }
            if (first.size == 1 && span >= columns && first.first().text.isNotBlank()) {
                val caption = first.first().text
                navLinks.addAll(first.first().actions)
                rows = rows.drop(1)
                if (rows.isEmpty()) return NativeTable(emptyList(), caption = caption, style = NativeTableStyle.NAV, links = navLinks)
                return finishTable(rows, caption, columns, navLinks)
            }
        }
        return finishTable(rows, "", columns, navLinks)
    }

    private fun finishTable(input: List<List<NativeCell>>, caption: String, columns: Int, links: List<SiteDestination>): NativeTable {
        val rows = input.toMutableList()
        val flat = rows.flatten()
        val style = when {
            rows.isEmpty() && links.isNotEmpty() -> NativeTableStyle.NAV
            flat.count { it.background != null } >= 3 && flat.count { it.link != null } >= 3 -> NativeTableStyle.PALETTE
            rows.size == 1 && columns >= 2 && rows.first().all { it.text.length <= 32 } && rows.first().count { it.link != null } >= 2 -> NativeTableStyle.NAV
            columns == 2 && (caption.isNotBlank() || rows.size >= 2) && rows.all { it.size <= 2 } -> NativeTableStyle.DEFINITION
            else -> NativeTableStyle.GRID
        }
        val header = if (style == NativeTableStyle.GRID) rows.firstOrNull()?.let { first ->
            val filled = first.count { it.text.isNotBlank() || it.link != null }
            first.size == columns && filled >= columns - 1 && filled >= 2 &&
                (first.any { it.bold || it.color != null } || (rows.size >= 2 && first.none { cell -> cell.text.any(Char::isDigit) }))
        } ?: false else false
        val data = if (header) rows.drop(1) else rows
        val cleaned = if (style == NativeTableStyle.DEFINITION) data.map { row ->
            row.mapIndexed { index, cell -> if (index == 1 && isFormPrompt(cell.text)) cell.copy(text = "") else cell }
        } else data
        val finalRows = if (header) listOf(rows.first()) + cleaned else cleaned
        return NativeTable(finalRows, header, caption, style, links.distinctBy { it.url })
    }

    private fun isFormPrompt(text: String): Boolean {
        val value = text.trim()
        return value.length in 1..40 && (value.endsWith(":") || value.endsWith("："))
    }

    private fun cellColor(cell: Element): String? {
        val style = Regex("(?:^|;)\\s*color\\s*:\\s*(#[0-9a-fA-F]{3,8}|[a-zA-Z]{3,20})").find(cell.attr("style"))?.groupValues?.get(1)
        val styled = cell.selectFirst("[style*=color]")?.attr("style")
            ?.let { Regex("color\\s*:\\s*(#[0-9a-fA-F]{3,8}|[a-zA-Z]{3,20})").find(it)?.groupValues?.get(1) }
        val font = cell.selectFirst("font[color]")?.attr("color")
        return normalizeColor(style ?: styled ?: font)
    }

    private fun cellBackground(cell: Element): String? {
        var current: Element? = cell
        repeat(3) {
            val el = current ?: return@repeat
            val raw = el.attr("bgcolor").ifBlank {
                Regex("(?:^|;)\\s*background(?:-color)?\\s*:\\s*(#[0-9a-fA-F]{3,8}|[a-zA-Z]{3,20})")
                    .find(el.attr("style"))?.groupValues?.get(1).orEmpty()
            }
            val color = normalizeColor(raw)
            if (color != null && color !in setOf("#ffffff", "#fff", "#fffffff")) return color
            current = el.parent()
        }
        return null
    }

    private fun normalizeColor(raw: String?): String? {
        val value = raw?.trim()?.lowercase() ?: return null
        if (value.matches(Regex("#[0-9a-f]{3,8}"))) return value
        return mapOf(
            "red" to "#ff0000", "blue" to "#0000ff", "green" to "#008000", "black" to "#000000",
            "gray" to "#808080", "grey" to "#808080", "purple" to "#800080", "orange" to "#ffa500",
        )[value]
    }

    private fun headingOf(element: Element): String? {
        if (element.tagName() !in setOf("div", "p", "center", "td", "th", "h1", "h2", "h3", "h4")) return null
        val large = Regex("font-size\\s*:\\s*(1[6-9]|[2-9][0-9])px", RegexOption.IGNORE_CASE).containsMatchIn(element.attr("style"))
        val structural = element.tagName() in setOf("h1", "h2", "h3", "h4") ||
            element.hasClass("k_f18") || element.hasClass("k_f16") ||
            (element.tagName() in setOf("td", "th") && large)
        if (!structural) return null
        val text = element.text().replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty() || text.length > 40 || text.any { it in "。！？；" }) return null
        if (element.select("a,img,table").isNotEmpty()) return null
        return text
    }

    /** Android's native HTML renderer understands font/big but not CSS, so map
     *  a small allowlist of visual declarations to equivalent tags. */
    private fun restyleInline(root: Element): Element {
        root.select("[style]").reversed().forEach { element ->
            val style = element.attr("style")
            element.removeAttr("style")
            val color = Regex("(?:^|;)\\s*color\\s*:\\s*(#[0-9a-fA-F]{3,8}|[a-zA-Z]{3,20})").find(style)?.groupValues?.get(1)
                ?.takeIf { it.startsWith("#") || it.lowercase() in setOf("red", "blue", "green", "black", "gray", "grey", "purple", "orange") }
            val bold = Regex("font-weight\\s*:\\s*(bold|[6-9]00)").containsMatchIn(style)
            val size = Regex("font-size\\s*:\\s*([0-9]{1,2})px").find(style)?.groupValues?.get(1)?.toIntOrNull()
            var body = element.html()
            when {
                size != null && size >= 20 -> body = "<big><big>$body</big></big>"
                size != null && size >= 16 -> body = "<big>$body</big>"
            }
            if (bold) body = "<b>$body</b>"
            if (color != null) body = "<font color=\"$color\">$body</font>"
            element.html(body)
        }
        return root
    }

    private fun parseGrowth(main: Element, url: String): NativeGrowth? {
        fun Element.hasStyle(fragment: String) = attr("style").filterNot { it.isWhitespace() }.contains(fragment)
        val header = main.select("div").firstOrNull { it.hasStyle("line-height:60px") }
        val boldValues = header?.select("b")?.map { it.text().trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val level = boldValues.firstOrNull().orEmpty()
        val experience = boldValues.lastOrNull().takeIf { boldValues.size > 1 }.orEmpty()
        val coefficient = Regex("[0-9]+").findAll(header?.text().orEmpty())
            .map { it.value }
            .firstOrNull { it != level && it != experience }
            .orEmpty()
        val progress = main.select("span").firstOrNull { span ->
            span.hasStyle("font-weight:bold") && span.text().contains("%")
        }?.text()?.let { Regex("([0-9]{1,3})\\s*%").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val colors = main.select("table").asSequence()
            .map { table -> table.select("td").mapNotNull { cell ->
                val color = cellBackground(cell) ?: return@mapNotNull null
                val href = cell.selectFirst("a[href]")?.attr("href") ?: return@mapNotNull null
                val target = html.resolve(href, url) ?: return@mapNotNull null
                NativeColorChoice(color, target, cell.text().trim())
            } }
            .firstOrNull { it.size >= 3 }
            .orEmpty()
            .distinctBy { it.color to it.url }
        val status = main.select("div").firstOrNull { it.hasStyle("line-height:48px") }
        val claimAnchor = status?.selectFirst("a[href]")?.takeIf { !it.attr("href").trim().startsWith("javascript:", true) }
        val claimUrl = claimAnchor?.attr("href")?.let { html.resolve(it, url) }
        val hasClaimForm = status?.selectFirst("form") != null
        val statusText = status?.text().orEmpty()
        val claimed = Regex("已签到|已领取|今日完成|已完成").containsMatchIn(statusText)
        val claimStatus = when {
            claimed -> StatusKind.COMPLETED
            claimUrl != null || hasClaimForm -> StatusKind.AVAILABLE
            Regex("已过期|过期").containsMatchIn(statusText) -> StatusKind.EXPIRED
            Regex("失败|未通过").containsMatchIn(statusText) -> StatusKind.FAILED
            Regex("待处理|等待").containsMatchIn(statusText) -> StatusKind.WAITING
            else -> StatusKind.UNKNOWN
        }
        if (level.isBlank() && coefficient.isBlank() && experience.isBlank() && colors.isEmpty()) return null
        return NativeGrowth(level, coefficient, experience, progress, colors, claimed, claimUrl, hasClaimForm, claimStatus)
    }

    private fun parseProfile(main: Element, url: String): NativeProfile? {
        val table = main.selectFirst("table.log1") ?: return null
        val avatar = table.selectFirst("img.pic")?.attr("src")?.let { html.resolve(it, url) }.orEmpty()
        // Each profile cell is a series of plain "label: value" lines. The
        // centre cell holds the account facts, so use its fixed line order for
        // the header and the numeric summary instead of matching site copy.
        val cells = table.select("td").filter { it.selectFirst("img.pic") == null }.map { td ->
            val entries = mutableListOf<NativeInfo>()
            td.wholeText().split('\n').map { it.trim() }.forEach { line ->
                val index = line.indexOf('：').takeIf { it >= 0 } ?: line.indexOf(':')
                if (index < 0) return@forEach
                val label = line.substring(0, index).trim()
                val value = line.substring(index + 1).trim()
                if (label.length in 1..20 && value.isNotBlank()) entries.add(NativeInfo(label, value))
            }
            entries
        }
        val core = cells.maxByOrNull { it.size }.orEmpty()
        val name = core.getOrNull(0)?.value.orEmpty().ifBlank { core.getOrNull(1)?.value.orEmpty() }.ifBlank { "会员资料" }
        val headline = if (core.size >= 8) core.getOrNull(2) else null
        val stats = if (core.size >= 8) listOfNotNull(core.getOrNull(3), core.getOrNull(6), core.getOrNull(7)) else emptyList()
        val info = cells.flatten().distinct()
        val links = table.select("a[href]").mapNotNull { a ->
            val label = a.text().trim().take(40).ifBlank { a.attr("title").take(40) }
            if (label.isBlank()) null else html.resolve(a.attr("href"), url)?.let { routes.classify(it, label) }
        }.distinctBy { it.url }
        return NativeProfile(name, avatar, info, links, headline, stats)
    }

    private fun parseFavorites(main: Element, url: String, forms: List<SiteForm>): NativeFavorites {
        val bulk = forms.firstOrNull { form -> form.fields.any { it.name == "delid[]" } }
        val entries = main.select("a[href*='read.php?']").mapNotNull { a ->
            val target = html.resolve(a.attr("href"), url)?.toHttpUrl() ?: return@mapNotNull null
            if (target.queryParameter("tid") == null) return@mapNotNull null
            val row = a.closest("tr") ?: return@mapNotNull null
            val selectionValue = row.select("input").firstOrNull { it.attr("name") == "delid[]" }?.attr("value")
            val field = bulk?.fields?.firstOrNull { it.name == "delid[]" && it.options.any { option -> option.value == selectionValue } }
            NativeFavoriteEntry(SiteLink(a.text(), target.toString(), row.select("> td").drop(1).take(2).joinToString(" · ") { it.text() }), field?.id)
        }.distinctBy { topicIdentity(it.topic.url) }
        val categories = main.select("a[href*='profile.php?']").mapNotNull { a ->
            val target = html.resolve(a.attr("href"), url)?.toHttpUrl() ?: return@mapNotNull null
            if (target.queryParameter("action") != "favor" || target.queryParameterNames.any { it !in setOf("action", "type") }) return@mapNotNull null
            routes.classify(target.toString(), a.text())
        }.distinctBy { it.url }
        return NativeFavorites(entries, categories)
    }

    private fun parseBank(main: Element, url: String): NativeBank {
        val balances = mutableListOf<NativeInfo>()
        val rules = mutableListOf<String>()
        // Strip controls first: amounts are account facts, not form initial values.
        val table = main.selectFirst("table.bank1")?.clone()
        table?.select("input,textarea,select,button,script,style")?.remove()
        table?.select("td")?.forEach { cell ->
            cell.wholeText().split('\n', '|', '｜').map { it.trim() }.filter { it.isNotBlank() }.forEach { line ->
                val pair = line.split(Regex("[：:]"), limit = 2)
                if (pair.size == 2 && pair[0].length <= 24 && pair[1].contains(Regex("[0-9]")) &&
                    pair[0].let { it.contains("贡献") || it.contains("所持") || it.contains("存款") || it.contains("利息") }) {
                    val amount = Regex("^[-+]?[0-9]+(?:,[0-9]{3})*(?:\\.[0-9]+)?\\s*(?:KFB|%|点|贡献)?", RegexOption.IGNORE_CASE).find(pair[1].trim())?.value?.trim()
                    if (amount != null) {
                        balances.add(NativeInfo(pair[0], amount))
                        val explanation = pair[1].trim().removePrefix(amount).trim()
                        if (explanation.isNotBlank()) rules.add("${pair[0]}：$explanation")
                    } else rules.add(line)
                } else if (line.length > 20) rules.add(line)
            }
        }
        val links = table?.select("a[href]")?.mapNotNull { a -> html.resolve(a.attr("href"), url)?.let { routes.classify(it, a.text()) } }.orEmpty()
        return NativeBank(balances.distinctBy { it.label }, rules.distinct(), links)
    }

    private fun parseProgress(main: Element): NativeProgress? {
        // Observed template: a numeric span following the explicit score label.
        // Do not derive eligibility, thresholds or timelines from prose/rules.
        val score = main.select("span,b,strong").firstOrNull { element ->
            element.text().trim().matches(Regex("[-+]?[0-9]+(?:\\.[0-9]+)?")) &&
                Regex("\\s*当前进度分\\s*[：:]?\\s*").matches(
                    generateSequence(element.previousSibling()) { it.previousSibling() }.takeWhile { it is TextNode }
                        .toList().asReversed().joinToString("") { (it as TextNode).text() })
        } ?: return null
        // No normal status/timeline template has been verified. These words
        // can also appear as examples in rules; keep them in fallback prose,
        // never promote them to account eligibility or reward state.
        return NativeProgress(listOf(NativeInfo("当前进度分", score.text().trim())))
    }

    private fun parseStatusTimeline(main: Element, sourceUrl: String): NativeStatusTimeline? {
        val entries = mutableListOf<StatusTimelineEntry>()
        main.select("tr").forEach { row ->
            val cells = row.select("> td, > th")
            if (cells.size < 3) return@forEach
            val time = cells[0].text().trim()
            if (!time.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}(?:\\s+[0-9]{2}:[0-9]{2})?"))) return@forEach
            val link = cells[1].selectFirst("a[href]")
            val title = link?.text()?.trim().orEmpty().ifBlank { cells[1].text().trim().take(120) }
            if (title.isBlank()) return@forEach
            val rowText = cells.drop(2).joinToString(" · ") { it.text().trim() }
            val allLinks = row.select("a[href]").mapNotNull { a ->
                val href = html.resolve(a.attr("href"), sourceUrl) ?: return@mapNotNull null
                val label = a.text().trim().take(40)
                if (label.isBlank() || label == title) null else routes.classify(href, label)
            }.distinctBy { it.url }
            val action = allLinks.firstOrNull { it.url.contains("kf_fw_1wkfb.php?do=1") }
                ?: allLinks.firstOrNull { it.url.contains("kf_fw_1wkfb.php?do=2") }
                ?: allLinks.firstOrNull { it.effect == SiteEffect.CHANGE }
            val status = when {
                rowText.contains("已完成") || rowText.contains("已领取") -> StatusKind.COMPLETED
                rowText.contains("已过期") || rowText.contains("过期") -> StatusKind.EXPIRED
                rowText.contains("失败") || rowText.contains("未通过") || rowText.contains("驳回") -> StatusKind.FAILED
                rowText.contains("待检查") || rowText.contains("待审核") || rowText.contains("待处理") -> StatusKind.WAITING
                action?.url?.contains("kf_fw_1wkfb.php?do=1") == true || rowText.contains("可领取") -> StatusKind.AVAILABLE
                else -> StatusKind.UNKNOWN
            }
            val related = link?.let { a -> html.resolve(a.attr("href"), sourceUrl) }.orEmpty()
            entries.add(StatusTimelineEntry(title, status, time, rowText, related, action))
        }
        val distinct = entries.distinctBy { it.time to it.title to it.status }.take(100)
        return if (distinct.isEmpty()) null else NativeStatusTimeline("评分与奖励记录", distinct)
    }

    private fun parseBankLog(main: Element, sourceUrl: String): NativeBankLog {
        val table = main.selectFirst("table.bank1")
        val entries = mutableListOf<NativeBankLogEntry>()
        val unparsed = mutableListOf<List<String>>()
        val links = mutableListOf<SiteDestination>()
        val incoming = runCatching { sourceUrl.toHttpUrl().queryParameter("to") == "1" }.getOrDefault(false)
        table?.select("a[href]")?.forEach { a ->
            html.resolve(a.attr("href"), sourceUrl)?.let { routes.classify(it, a.text()) }?.let(links::add)
        }
        table?.select("tr")?.forEach { row ->
            val cells = row.select("> td, > th")
            if (cells.size < 4) return@forEach
            val id = cells[0].text().trim()
            val type = cells[1].text().trim()
            val time = cells[3].text().trim()
            if (id == "ID") return@forEach
            if (!time.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}(?:\\s+[0-9]{2}:[0-9]{2})?"))) {
                if (cells.any { it.text().isNotBlank() } && cells.size >= 5) unparsed.add(cells.take(5).map { it.text().trim() })
                return@forEach
            }
            val descriptionCell = cells[2]
            val bold = descriptionCell.select("b").map { it.text().trim() }.filter { it.isNotBlank() }
            val plain = descriptionCell.clone().apply { select("b").remove() }.wholeText()
            val amountMatch = Regex("金额\\s*[：:]\\s*([0-9]+(?:\\.[0-9]+)?)\\s*([^，,。\\s]*)").find(plain)
            val memo = Regex("附言\\s*[：:]\\s*(.*)").find(plain)?.groupValues?.get(1)?.trim().orEmpty()
            val transfer = type.contains("转帐") || type.contains("转账")
            val direction = when {
                !transfer -> BankDirection.UNKNOWN
                incoming -> BankDirection.IN
                else -> BankDirection.OUT
            }
            val counterparty = when (direction) {
                BankDirection.IN -> bold.firstOrNull().orEmpty()
                BankDirection.OUT -> bold.getOrNull(1).orEmpty()
                BankDirection.UNKNOWN -> ""
            }
            entries.add(NativeBankLogEntry(
                id = id,
                time = time,
                type = type,
                direction = direction,
                counterparty = counterparty,
                amount = amountMatch?.groupValues?.get(1).orEmpty(),
                currency = amountMatch?.groupValues?.get(2)?.trim().orEmpty(),
                memo = memo,
                description = descriptionCell.wholeText().replace(Regex("\\s+"), " ").trim().take(500),
            ))
        }
        return NativeBankLog(
            label = if (incoming) "收到的转账（只读）" else "我的转账记录（只读）",
            entries = entries.take(100),
            unparsedRows = unparsed.take(20),
            links = links.distinctBy { it.url },
        )
    }

    private fun parseRewardActions(path: String, main: Element, sourceUrl: String): List<RewardAction> {
        val result = mutableListOf<RewardAction>()
        if (path == "kf_fw_1wkfb.php") {
            main.select("a[href]").forEach { a ->
                val target = html.resolve(a.attr("href"), sourceUrl) ?: return@forEach
                val url = target.toHttpUrlOrNull() ?: return@forEach
                if (url.encodedPath != "/kf_fw_1wkfb.php" || url.queryParameter("do") != "1" ||
                    url.queryParameterNames != setOf("do", "safeid", "ptid") ||
                    url.queryParameterNames.any { url.queryParameterValues(it).size != 1 }) return@forEach
                val safeid = url.queryParameter("safeid").orEmpty()
                val ptid = url.queryParameter("ptid").orEmpty()
                if (!safeid.matches(Regex("[a-zA-Z0-9]{1,64}")) || !ptid.matches(Regex("[0-9]{1,20}"))) return@forEach
                result.add(RewardAction(
                    id = "claim:$ptid",
                    title = a.text().trim().take(80).ifBlank { "获取评分奖励" },
                    protocol = RewardProtocol.GET_LINK,
                    endpoint = target,
                    method = "GET",
                    fields = emptyList(),
                    sourceUrl = sourceUrl,
                ))
            }
        }
        if (path == "kf_fw_ig_index.php") {
            val safeid = Regex("data\\s*:\\s*['\"]safeid=([a-zA-Z0-9]{1,64})['\"]").find(main.outerHtml())
                ?.groupValues?.get(1)
                ?: main.selectFirst("input[name=safeid]")?.attr("value")?.takeIf { it.matches(Regex("[a-zA-Z0-9]{1,64}")) }
            if (safeid != null && main.selectFirst("button[onclick*=jgjg]") != null) {
                // The site implements "until end" as a client-side setTimeout
                // loop. Only expose one server step; never auto-repeat.
                result.add(RewardAction(
                    id = "battle-step",
                    title = "战斗（前进一层楼）",
                    protocol = RewardProtocol.AJAX_POST,
                    endpoint = "$origin/kf_fw_ig_intel.php",
                    method = "POST",
                    fields = listOf(FormValue("safeid", safeid)),
                    sourceUrl = sourceUrl,
                ))
            }
        }
        return result
    }

    private fun parseForm(form: Element, url: String, id: String, pageTitle: String): SiteForm? {
        val target = html.resolve(form.attr("action").ifBlank { url }, url) ?: return null
        val source = url.toHttpUrl()
        val profileModify = source.pathSegments.lastOrNull() == "profile.php" && source.queryParameter("action") == "modify"
        val method = form.attr("method").ifBlank { "GET" }.uppercase()
        if (method !in listOf("GET", "POST")) return null
        val controls = html.controls(form).filterNot { it.hasAttr("disabled") }
        val hidden = mutableListOf<FormValue>()
        val fields = mutableListOf<SiteField>()
        val submits = mutableListOf<SiteSubmit>()
        val radioNames = mutableSetOf<String>()
        controls.forEachIndexed { index, e ->
            val name = e.attr("name")
            val type = e.attr("type").lowercase()
            val value = e.attr("value")
            when {
                type == "hidden" -> hidden.add(FormValue(name, value))
                type == "submit" || (e.tagName() == "button" && type !in listOf("button", "reset")) -> submits.add(SiteSubmit(value.ifBlank { e.text() }.ifBlank { "提交" }, name, value))
                type in listOf("button", "reset", "image") -> Unit
                type == "radio" -> if (radioNames.add(name)) {
                    val group = controls.filter { it.attr("name") == name && it.attr("type").equals("radio", true) }
                    fields.add(SiteField("$id:$index", name, fieldLabel(e, name), SiteFieldKind.RADIO,
                        group.filter { it.hasAttr("checked") }.map { it.attr("value") }, group.map { FormOption(it.attr("value"), nearbyLabel(it).ifBlank { it.attr("value") }) }))
                }
                type == "checkbox" -> fields.add(SiteField("$id:$index", name, fieldLabel(e, name), SiteFieldKind.CHECKBOX,
                    if (e.hasAttr("checked")) listOf(value.ifEmpty { "on" }) else emptyList(), listOf(FormOption(value.ifEmpty { "on" }, fieldLabel(e, name)))))
                e.tagName() == "select" -> {
                    val options = e.select("option").filterNot { it.hasAttr("disabled") }.map { FormOption(if (it.hasAttr("value")) it.attr("value") else it.text(), it.text()) }
                    val selected = e.select("option[selected]").map { if (it.hasAttr("value")) it.attr("value") else it.text() }
                    fields.add(SiteField("$id:$index", name, fieldLabel(e, name), SiteFieldKind.SELECT,
                        selected.ifEmpty { if (!e.hasAttr("multiple")) options.take(1).map { it.value } else emptyList() }, options,
                        e.hasAttr("required"), multiple = e.hasAttr("multiple")))
                }
                else -> {
                    val kind = when { type == "password" -> SiteFieldKind.PASSWORD; type == "file" -> SiteFieldKind.FILE; type == "number" -> SiteFieldKind.NUMBER; e.tagName() == "textarea" -> SiteFieldKind.MULTILINE; else -> SiteFieldKind.TEXT }
                    // Avatar upload uses a separate ajaxface protocol; hide its
                    // size hints until the upload handler is adapted.
                    if (profileModify && (name == "upload" || name.startsWith("uploadurl") || name == "facetype")) return@forEachIndexed
                    // This template uploads avatars through a separate ajaxface
                    // protocol, not the normal profile-save form. Do not offer a
                    // misleading file input until that handler is adapted.
                    if (kind == SiteFieldKind.FILE && name == "upload" && url.toHttpUrl().pathSegments.lastOrNull() == "profile.php") return@forEachIndexed
                    fields.add(SiteField("$id:$index", name, fieldLabel(e, name), kind,
                        when (kind) { SiteFieldKind.FILE -> emptyList(); SiteFieldKind.PASSWORD -> listOf(""); else -> listOf(if (e.tagName() == "textarea") e.wholeText() else value) },
                        required = e.hasAttr("required"), maxLength = e.attr("maxlength").toIntOrNull(), multiple = e.hasAttr("multiple"), accept = e.attr("accept"), editable = !e.hasAttr("readonly")))
                }
            }
        }
        // Hidden-only forms are server bookkeeping, not readable controls.
        if (fields.isEmpty()) return null
        form.select("input[type=submit]:not([name]),button:not([name]):not([type=button]):not([type=reset])").forEach { e ->
            submits.add(SiteSubmit(e.attr("value").ifBlank { e.text() }.ifBlank { "提交" }, null, ""))
        }
        val purpose = hidden.firstOrNull { it.name == "action" }?.value.orEmpty()
        val job = hidden.firstOrNull { it.name == "job" }?.value.orEmpty()
        val title = when {
            profileModify -> "修改资料"
            purpose == "virement" -> "转账贡献"
            purpose == "draw" -> "取款（KFB）"
            purpose == "save" -> "存款（KFB）"
            fields.any { it.name == "drawmoney" } -> "取款（KFB）"
            fields.any { it.name == "banidinfo" } -> "保存屏蔽列表"
            fields.any { it.name == "oldpwd" } || fields.any { it.name == "propwd" } -> "修改密码"
            job == "addtype" -> "新建收藏目录"
            fields.any { it.name == "delid[]" } -> "批量管理（先选择项目）"
            else -> pageTitle
        }
        if (job == "addtype") {
            fields.indices.forEach { index -> if (fields[index].name == "type") fields[index] = fields[index].copy(label = "新目录名") }
        }
        return SiteForm(id, title, url, target, method, hidden, fields, submits.ifEmpty { listOf(SiteSubmit("提交", null, "")) },
            form.attr("enctype").equals("multipart/form-data", true),
            if (target.contains("H_name=bank") || purpose in listOf("virement", "draw", "save")) "请核对收款人、金额与规则。" else "提交会修改站点数据。")
    }

    private fun fieldLabel(e: Element, name: String): String {
        e.id().takeIf { it.isNotBlank() }?.let { id -> e.ownerDocument()?.select("label")?.firstOrNull { it.attr("for") == id }?.text()?.takeIf { it.isNotBlank() }?.let { return it } }
        if (name == "delid[]") return e.closest("tr")?.selectFirst("a[href]")?.text()?.take(100).orEmpty().ifBlank { "选择项目" }
        val previous = e.closest("td")?.previousElementSibling()?.text()?.trim()?.take(80).orEmpty()
        if (previous.isNotBlank()) return previous.trimEnd('：', ':')
        return e.attr("title").ifBlank { e.attr("placeholder") }.take(80).ifBlank {
            mapOf("oldpwd" to "原密码", "propwd" to "新密码", "check_pwd" to "确认新密码", "proemail" to "邮箱", "prohonor" to "个性称号",
                "pwuser" to "对方用户名", "to_money" to "转帐数额（贡献）", "memo" to "附言", "drawmoney" to "取款金额（点）", "btype" to "账户类型",
                "type" to "分类", "job" to "管理操作", "action" to "操作", "upload" to "上传头像", "banidinfo" to "屏蔽名单")[name] ?: nearbyLabel(e).take(80).ifBlank { name }
        }
    }

    private fun nearbyLabel(e: Element): String {
        val after = generateSequence(e.nextSibling()) { it.nextSibling() }.takeWhile { it !is Element || it.tagName() !in listOf("input", "select", "br") }
            .take(4).joinToString(" ") { if (it is TextNode) it.text() else (it as? Element)?.text().orEmpty() }.trim()
        return after
    }
}

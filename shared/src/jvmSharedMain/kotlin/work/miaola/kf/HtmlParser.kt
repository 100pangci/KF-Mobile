package work.miaola.kf

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.FormElement
import org.jsoup.safety.Safelist

class ForumHtmlParser(private val origin: String) {
    private val routes = SiteRoutePolicy(origin)
    private val base = origin.toHttpUrl()
    fun resolve(raw: String, from: String = "$origin/"): String? {
        val url = from.toHttpUrl().resolve(raw) ?: return null
        val canonical = if (url.encodedQuery == "") url.newBuilder().query(null).build() else url
        return canonical.takeIf { it.scheme == "https" && it.host == base.host && it.port == base.port && it.username.isEmpty() && it.password.isEmpty() }?.toString()
    }

    fun document(html: String, url: String) = Jsoup.parse(html, url)
    fun loggedIn(doc: Document): Boolean = doc.select("#kf_information a[href]").any {
        val url = resolve(it.attr("href"), doc.baseUri())?.toHttpUrl()
        url?.pathSegments?.lastOrNull() == "login.php" && url.queryParameter("action") == "quit"
    }

    private fun link(element: Element, group: String = "", subtitle: String = ""): SiteLink? {
        val url = resolve(element.attr("href"), element.baseUri()) ?: return null
        val title = element.selectFirst(".righttita")?.text()
            ?: element.attr("title").trim().removeSurrounding("《", "》").takeIf { it.isNotBlank() }
            ?: element.text()
        return title.takeIf { it.isNotBlank() }?.let { SiteLink(it, url, subtitle, group) }
    }

    /**
     * Home feed links sometimes have no .righttita node, so the fallback text
     * contains "标题02分钟前". Keep the title clean and expose the trailing
     * relative time as the subtitle for the header capsule.
     */
    private fun feedLink(link: SiteLink): SiteLink {
        val title = link.title.trim()
        val explicit = link.subtitle.trim()
        if (explicit.isNotEmpty() && title.endsWith(explicit)) {
            val clean = title.dropLast(explicit.length).trim()
            if (clean.isNotEmpty()) return link.copy(title = clean)
        }
        return link
    }

    fun parse(html: String, url: String): PageData {
        val doc = document(html, url)
        val path = url.toHttpUrl().pathSegments.lastOrNull()
        val title = doc.title().substringBefore(" - ").substringBefore('|').trim().ifEmpty { "绯月" }
        val authenticated = loggedIn(doc)
        val username = if (authenticated) doc.selectFirst("#kf_topuser")?.text()?.trim().orEmpty() else ""
        val boards = doc.select("a[href*='thread.php?fid=']").mapNotNull { a ->
            val group = a.parents().firstOrNull { it.hasClass("topmenuo3") }?.ownText().orEmpty()
            link(a, group)
        }.filter { it.url.toHttpUrl().queryParameterNames == setOf("fid") }.distinctBy { it.url }
        val menu = doc.select("#kf_information a[href]").mapNotNull { link(it) }
            .filterNot { it.url.toHttpUrl().queryParameter("action") == "quit" }
        val topics = when (path) {
            "thread.php" -> doc.select(".threadtit1 a[href*='read.php?']").filter { resolve(it.attr("href"), url)?.toHttpUrl()?.queryParameter("tid") != null }.mapNotNull { a ->
                val row = a.closest("tr")
                val category = a.parent()?.selectFirst("a[href*='type=']")?.text().orEmpty()
                val author = row?.selectFirst("a[href*='profile.php?action=show']")?.text().orEmpty()
                val stats = row?.selectFirst(".b_tit6")?.text().orEmpty()
                link(a, category, listOf(author, stats).filter { it.isNotBlank() }.joinToString(" · "))
            }
            "message.php" -> contentRoot(doc).select("a[href*='message.php?']").filter { a ->
                resolve(a.attr("href"), url)?.toHttpUrl()?.let { it.encodedPath == "/message.php" && it.queryParameter("action") in setOf("read", "readscout") } == true
            }.mapNotNull { a ->
                val cells = a.closest("tr")?.children().orEmpty()
                link(a, "私信", cells.drop(2).take(3).joinToString(" · ") { it.text() }.take(120))
            }
            "profile.php" -> if (url.toHttpUrl().queryParameter("action") == "favor")
                contentRoot(doc).select("a[href*='read.php?']").filter { resolve(it.attr("href"), url)?.toHttpUrl()?.queryParameter("tid") != null }.mapNotNull { a ->
                    val cells = a.closest("tr")?.select("> td").orEmpty()
                    link(a, "收藏", cells.drop(1).take(2).joinToString(" · ") { it.text() })
                } else emptyList()
            "search.php" -> listingLinks(doc, "搜索结果")
            "index.php", "" -> {
                val main = doc.select(".dcol").filter { it.select("a[href*='read.php?tid=']").size > 20 }
                if (main.isEmpty()) listingLinks(doc, "最新讨论")
                else main.flatMapIndexed { index, col ->
                    col.select("a[href*='read.php?tid=']").mapNotNull { a ->
                        val row = a.parent()
                        link(a, if (index == 0) "最新讨论" else "热门讨论", row?.selectFirst(".k_fr")?.text().orEmpty())
                    }
                }
            }
            else -> emptyList()
        }.distinctBy { it.url.toHttpUrl().queryParameter("tid") ?: it.url }
        val posts = doc.select(".readtext[id^=pid]").mapNotNull { parsePost(it, url) }
        val currentPage = url.toHttpUrl().queryParameter("page")?.toIntOrNull() ?: 1
        val pageLinks = doc.select("a[href]").mapNotNull { anchor ->
            val target = resolve(anchor.attr("href"), url) ?: return@mapNotNull null
            val targetUrl = target.toHttpUrl()
            if (targetUrl.pathSegments.lastOrNull() != path || !isSamePageList(url, target)) return@mapNotNull null
            val number = targetUrl.queryParameter("page")?.toIntOrNull() ?: return@mapNotNull null
            target to number
        }.distinctBy { it.first }
        val next = pageLinks.filter { it.second > currentPage }.minByOrNull { it.second }?.first
        val previous = pageLinks.filter { it.second < currentPage }.maxByOrNull { it.second }?.first
        val compose = when (path) {
            "message.php" -> doc.selectFirst("a[href*='message.php?action=write']")
            "thread.php" -> doc.select("a[href*='post.php']").firstOrNull { !it.attr("href").contains("action=reply") }
            "read.php" -> doc.selectFirst("a[href*='post.php?action=reply']")
            else -> null
        }?.let { resolve(it.attr("href"), url) }
        val notice = if (posts.isEmpty() && topics.isEmpty()) responseMessage(doc) else ""
        val loginForm = doc.select("form").any { form -> controls(form).any { it.attr("name") == "pwuser" } && controls(form).any { it.attr("name") == "pwpwd" } }
        val status = when {
            authenticated -> AuthStatus.AUTHENTICATED
            loginForm -> AuthStatus.LOGIN_REQUIRED
            else -> AuthStatus.UNKNOWN
        }
        val feeds = if (path == "index.php" || path.isNullOrEmpty()) homeFeeds(doc) else emptyList()
        val native = NativeSiteParser(origin, this).parse(doc, url)
        val logout = doc.select("#kf_information a[href]").firstOrNull { a ->
            resolve(a.attr("href"), url)?.toHttpUrl()?.let { it.pathSegments.lastOrNull() == "login.php" && it.queryParameter("action") == "quit" } == true
        }?.let { SiteDestination(resolve(it.attr("href"), url)!!, SiteEffect.CHANGE, "服务端退出登录", "将向站点提交退出请求，并清除当前设备的登录界面。") }
        val favorite = doc.select("a[href*='kf_tidfavor.php']").firstOrNull { a ->
            val target = resolve(a.attr("href"), url) ?: return@firstOrNull false
            target.toHttpUrl().queryParameter("action") == "favor"
        }?.let { a ->
            val target = resolve(a.attr("href"), url) ?: return@let null
            routes.classify(target, "收藏本帖").let { if (it.effect == SiteEffect.CHANGE) it.copy(title = "收藏本帖", explanation = "将把本帖加入站点的收藏夹，不会保存在本机。") else null }
        }
        val boardFilters = if (path == "thread.php") contentRoot(doc).select("a[href*='thread.php?']").mapNotNull { a ->
            val target = resolve(a.attr("href"), url)?.toHttpUrl() ?: return@mapNotNull null
            if (target.queryParameter("fid") != url.toHttpUrl().queryParameter("fid") ||
                target.queryParameterNames.none { it in setOf("type", "digest", "orderway", "asc") } ||
                routes.classify(target.toString()).effect != SiteEffect.READ) return@mapNotNull null
            link(a)
        }.distinctBy { it.url }.take(30) else emptyList()
        return PageData(url, title, authenticated, username, boards, topics, posts, menu, next, previous, compose, notice, status, feeds, native, logout, favorite,
            boardFilters = boardFilters)
    }

    private fun homeFeeds(doc: Document): List<HomeFeedSection> {
        val result = mutableListOf<HomeFeedSection>()
        doc.select(".rightboxa")
            .filter { box -> box.select("a[href*='read.php?tid=']").isNotEmpty() }
            .take(2)
            .forEachIndexed { index, box ->
                val heading = box.selectFirst(".k_f18")?.text().orEmpty()
                val id = if (index == 0) "recommended" else "new-topics"
                val topics = box.select("a[href*='read.php?tid=']").mapNotNull { a ->
                    link(a, heading, a.selectFirst(".k_fr")?.text().orEmpty())?.let(::feedLink)
                }.distinctBy { it.url.toHttpUrl().queryParameter("tid") }
                result.add(HomeFeedSection(id, heading.ifBlank { id }, topics))
            }
        val columns = doc.select(".dcol").filter { it.select("a[href*='read.php?tid=']").size > 20 }
            .filter { col -> col.select(".dcol").none { it != col && it.select("a[href*='read.php?tid=']").size > 20 } }
        val replies = columns.flatMap { col -> col.select("a[href*='read.php?tid=']").mapNotNull { a ->
            link(a, "最新回复", a.parent()?.selectFirst(".k_fr")?.text().orEmpty())?.let(::feedLink)
        } }.distinctBy { it.url.toHttpUrl().queryParameter("tid") }
        if (replies.isNotEmpty()) result.add(HomeFeedSection("latest-replies", "最新回复", replies))
        return result
    }

    fun parseEmoticons(script: String, editorHtml: String, sourceUrl: String): List<ForumEmoticon> {
        val imagePath = Regex("\\bvar\\s+imgpath\\s*=\\s*['\"]([^'\"]+)['\"]").find(editorHtml)?.groupValues?.get(1) ?: return emptyList()
        val root = resolve(imagePath.trimEnd('/') + "/post/smile/", sourceUrl) ?: return emptyList()
        val labels = mapOf(57 to "高兴", 41 to "大笑", 44 to "锤地", 42 to "不语", 36 to "喝茶", 38 to "睡觉", 43 to "受伤")
        val groups = Regex("facedb\\[['\"]([^'\"]+)['\"]]\\s*=\\s*['\"]([^'\"]+)['\"]").findAll(script)
            .associate { it.groupValues[1] to it.groupValues[2] }
        return Regex("face\\[([0-9]+)]\\s*=\\s*\\[\\s*(['\"])([^'\"]+)\\2\\s*,\\s*(['\"])([^'\"]*)\\4\\s*]").findAll(script).mapNotNull { match ->
            val id = match.groupValues[1].toIntOrNull()?.takeIf { it in 0..99999 } ?: return@mapNotNull null
            val path = match.groupValues[3]
            if (path.contains("..") || !path.matches(Regex("[a-zA-Z0-9_/-]+\\.(gif|png|jpg|jpeg|webp)", RegexOption.IGNORE_CASE))) return@mapNotNull null
            val url = resolve(root + path, sourceUrl) ?: return@mapNotNull null
            ForumEmoticon(id, match.groupValues[5].ifBlank { labels[id] ?: "表情 $id" }, url, groups[path.substringBefore('/')] ?: "站点表情")
        }.distinctBy { it.id }.take(500).toList()
    }

    private fun contentRoot(doc: Document): Element = doc.select(".dcol").lastOrNull { it.attr("style").contains("900px") } ?: doc.body()

    private fun listingLinks(doc: Document, group: String): List<SiteLink> =
        contentRoot(doc).select("a[href*='read.php?']").filter { resolve(it.attr("href"), doc.baseUri())?.toHttpUrl()?.queryParameter("tid") != null }
            .filterNot { it.closest(".rightdiv") != null }
            .mapNotNull { link(it, group) }

    private fun parsePost(post: Element, pageUrl: String): ForumPost? {
        val author = post.selectFirst(".readidms a[href*='profile.php']")
        val content = post.selectFirst("td[width=990] > div") ?: post.selectFirst("td:last-child > div") ?: return null
        val clone = content.clone()
        val header = clone.children().firstOrNull { it.select("a.readcza").isNotEmpty() && !it.id().startsWith("floor") }
        val headerText = header?.text().orEmpty()
        val floor = header?.selectFirst("span")?.text().orEmpty()
        val time = Regex("\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}").find(headerText)?.value.orEmpty()
        val quote = post.selectFirst("a[href*='post.php?action=quote']")?.let { resolve(it.attr("href"), post.baseUri()) }.orEmpty()
        val avatar = post.selectFirst("td[width=190] img.pic, .readidms img.pic")
            ?.let { resolve(it.attr("src"), post.baseUri()) }.orEmpty()
        clone.select("[id^=floor], script, style").remove()
        header?.remove()
        // The site's <base href="/"> changes Element.baseUri(). Use the actual
        // response URL, not the HTML base, when binding a purchase to its tid.
        val purchases = clone.select("fieldset").mapNotNull { purchaseOffer(it, post.id(), pageUrl) }.distinctBy { it.id }
        val purchaseLocked = clone.select("input[onclick], button[onclick], a[href]")
            .any { it.attr("onclick").contains("action=buytopic") || it.attr("href").contains("action=buytopic") }
        // Replace the old JS-only locked fieldset with an explicit native card.
        clone.select("fieldset").filter { purchaseOffer(it, post.id(), pageUrl) != null }.forEach { it.remove() }
        // Site helpers (keywords, replies, ratings, my other threads) are
        // metadata, not prose: lift them out of the floor content.
        val keywords = mutableListOf<SiteLink>()
        val meta = mutableListOf<PostMeta>()
        val replies = mutableListOf<PostReply>()
        clone.select("fieldset").forEach { fieldset ->
            val title = fieldset.selectFirst("legend")?.text()?.trim().orEmpty()
            if (title.isEmpty()) return@forEach
            val keywordLinks = fieldset.select("a[href*='guanjianci.php']")
            if (keywordLinks.isNotEmpty()) {
                keywordLinks.mapNotNullTo(keywords) { a ->
                    val url = resolve(a.attr("href"), post.baseUri()) ?: return@mapNotNullTo null
                    SiteLink(a.text().trim(), url, group = "关键词")
                }
                return@forEach
            }
            val body = fieldset.clone().apply { selectFirst("legend")?.remove() }
            val content = cleanContent(body.html(), post.baseUri())
            if (content.isBlank()) return@forEach
            // Quote boxes are classless <fieldset> elements; site-added
            // keyword/rating/related-post boxes all use class="read_fds".
            if (!fieldset.hasClass("read_fds")) {
                if (replies.size < 8) {
                    val target = fieldset.selectFirst("a[href]")?.let { a -> resolve(a.attr("href"), post.baseUri()) }
                    replies.add(PostReply("引用", content, target))
                }
                return@forEach
            }
            if (meta.size >= 6) return@forEach
            meta.add(PostMeta(title, content))
        }
        clone.select("fieldset").filter { it.selectFirst("legend") != null }.forEach { it.remove() }
        val actions = post.select("a.readcza[href], #floor0 a[href], .readtext[id^=pid] a.readcza[href]").mapNotNull { a ->
            val href = a.attr("href")
            if (href.startsWith("javascript:", true) || href.startsWith("#")) return@mapNotNull null
            if (href.contains("action=quote")) return@mapNotNull null
            val url = resolve(href, post.baseUri()) ?: return@mapNotNull null
            val label = a.text().trim().take(30).ifBlank { a.attr("title").take(30) }
            if (label.isBlank()) null else routes.classify(url, label)
        }.distinctBy { it.url }.take(10)
        return ForumPost(post.id(), author?.text().orEmpty().ifEmpty { "用户" },
            author?.let { resolve(it.attr("href"), post.baseUri()) }.orEmpty(), floor, time, cleanContent(clone.html(), post.baseUri()), quote, purchases, purchaseLocked,
            keywords = keywords.distinctBy { it.url },
            meta = meta,
            actions = actions,
            replies = replies.distinctBy { it.html },
            avatar = avatar)
    }

    private fun purchaseOffer(fieldset: Element, postId: String, threadUrl: String): PurchaseOffer? {
        val legend = fieldset.selectFirst("legend")?.clone() ?: return null
        legend.select("select, input, button, a").remove()
        val price = Regex("此帖售价\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(KFB|贡献值?|积分)(?:\\s|$)", RegexOption.IGNORE_CASE)
            .find(legend.text()) ?: return null
        val expectedPid = postId.removePrefix("pid")
        for (control in fieldset.select("input[onclick], button[onclick], a[href]")) {
            val target = if (control.hasAttr("href")) control.attr("href") else {
                // Parse only a literal location assignment. Never execute JS.
                val js = control.attr("onclick").trim()
                Regex("(?:window\\.)?location(?:\\.href)?\\s*=\\s*(['\"])([^'\"]+)\\1\\s*;?").matchEntire(js)?.groupValues?.get(2) ?: continue
            }
            val action = resolve(target, threadUrl)?.toHttpUrl() ?: continue
            val source = resolve(threadUrl)?.toHttpUrl() ?: continue
            val tid = action.queryParameter("tid") ?: continue
            val pid = action.queryParameter("pid") ?: continue
            if (action.pathSegments.lastOrNull() != "job.php" || action.queryParameter("action") != "buytopic") continue
            if (!tid.matches(Regex("[0-9]+")) || tid != source.queryParameter("tid") || source.pathSegments.lastOrNull() != "read.php") continue
            if (pid != expectedPid || (pid != "tpc" && !pid.matches(Regex("[0-9]+")))) continue
            if (action.queryParameterNames.any { action.queryParameterValues(it).size != 1 }) continue
            val amount = price.groupValues[1]
            if (amount.length > 20) continue
            return PurchaseOffer("$tid:$pid", postId, amount, price.groupValues[2].uppercase(), action.toString(), source.toString())
        }
        return null
    }

    fun cleanContent(html: String, baseUri: String): String {
        val safe = Safelist.relaxed().addAttributes("img", "src", "alt", "width", "height").addAttributes("font", "color", "size")
            .addProtocols("a", "href", "https", "http", "magnet").addTags("fieldset", "legend", "del", "font", "big", "center", "hr")
        val clean = Jsoup.clean(html, baseUri, safe)
        val doc = Jsoup.parseBodyFragment(clean, baseUri)
        // Resource access is delegated to the platform; never execute page code.
        doc.select("img").forEach { it.attr("src", it.absUrl("src")) }
        doc.select("a").forEach { it.attr("href", it.absUrl("href")) }
        return doc.body().html()
    }

    fun loginFields(html: String, url: String): Pair<String, Map<String, String>> {
        val doc = document(html, url)
        val form = doc.select("form").firstOrNull { form -> controls(form).any { it.attr("name") == "pwuser" } && controls(form).any { it.attr("name") == "pwpwd" } }
            ?: error("未找到登录表单，请打开更多登录选项核对")
        require(form.attr("method").equals("post", true)) { "登录表单协议已变化" }
        return (resolve(form.attr("action"), url) ?: error("已阻止跨域登录表单")) to formFields(form)
    }

    fun parseEditor(html: String, url: String): EditorForm {
        val doc = document(html, url)
        val form = doc.select("form").firstOrNull { form -> controls(form).any { it.tagName() == "textarea" && it.attr("name") == "atc_content" } }
            ?: error("暂时无法打开编辑器，请检查发帖权限或稍后重试")
        val fields = formFields(form).toMutableMap()
        // The legacy post template closes a form inside a table. HTML parsers
        // may foster-parent its trailing controls outside the form element.
        val supplement = listOf("step", "action", "fid", "tid", "pid", "article", "special", "atc_downrvrc", "atc_desc", "Submit")
        supplement.forEach { key ->
            if (key !in fields) doc.selectFirst("input[name=$key]")?.let { fields[key] = it.attr("value") }
        }
        val message = controls(form).any { it.attr("name") == "msg_title" }
        val categories = controls(form).firstOrNull { it.tagName() == "select" && it.attr("name") == "p_type" }
            ?.select("option")?.map { FormOption(it.attr("value"), it.text()) }.orEmpty()
        val required = Regex("var\\s+cate\\s*=\\s*['\"]1['\"]").containsMatchIn(html)
        return EditorForm(url, resolve(form.attr("action"), url) ?: error("已阻止跨域提交"), fields,
            if (message) "msg_title" else "atc_title", categories = categories,
            recipientField = if (message) "pwuser" else null, minBytes = if (message) 1 else 12,
            categoryRequired = required,
            uploadEnabled = doc.selectFirst("input[type=file][name=attachment_]") != null,
            uploadLimit = Regex("var\\s+attachnum\\s*=\\s*['\"]?([0-9]+)").find(html)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 5) ?: 5,
            uploadExtensions = Regex("var\\s+allow_ext\\s*=\\s*['\"]([^'\"]+)['\"]").find(html)?.groupValues?.get(1)?.trim()?.split(Regex("\\s+"))?.filter { it.isNotBlank() }.orEmpty())
    }

    // HTML5 form association survives foster parenting of legacy table forms;
    // searching descendants alone silently loses the username/password inputs.
    fun controls(form: Element): List<Element> = ((form as? FormElement)?.elements()
        ?: form.select("input, textarea, select, button")).filter { it.hasAttr("name") }

    fun formFields(form: Element): Map<String, String> = buildMap {
        controls(form).forEach { e ->
            val type = e.attr("type").lowercase()
            if (type in listOf("button", "file", "reset") || (type in listOf("checkbox", "radio") && !e.hasAttr("checked"))) return@forEach
            val value = when (e.tagName()) {
                "textarea" -> e.wholeText()
                "select" -> (e.selectFirst("option[selected]") ?: e.selectFirst("option"))?.attr("value").orEmpty()
                else -> e.attr("value")
            }
            put(e.attr("name"), value)
        }
    }

    fun responseMessage(doc: Document): String {
        val copy = doc.clone()
        copy.select("script, style, .topmenuo, .rightdiv, #kf_information, #cnzz_stat_icon_1257832900").remove()
        val text = copy.select(".dcol").lastOrNull()?.text().orEmpty().ifBlank { copy.body().text() }
        return text.trim().take(350)
    }

    fun refreshTarget(doc: Document): String? = doc.select("meta[http-equiv=refresh]").firstOrNull()?.attr("content")
        ?.let { Regex("url\\s*=\\s*['\"]?([^'\"]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
        ?.let { resolve(it.trim(), doc.baseUri()) }
}

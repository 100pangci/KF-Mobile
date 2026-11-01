package work.miaola.kf

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.TimeUnit

/**
 * Official external Gugu game. It is intentionally a separate origin/client:
 * forum cookies are never forwarded. Reads use fixed game endpoints; writes
 * are limited to actions discovered on the current game page and always need
 * an explicit user confirmation in the controller.
 */
internal class GuguProtocol {
    private val gameOrigin = "https://www.momozhen.com"
    private val gameHost = "www.momozhen.com"
    private val cookieJar = MemoryCookieJar()
    private var safeid: String? = null
    private var actions: Map<String, GuguAction> = emptyMap()
    private var menuActions: Map<String, GuguAction> = emptyMap()
    private val numericTarget = Regex("[0-9]{1,12}")
    private val menuCall = Regex("""^omenu\(\s*'?([0-9]{1,6})'?\s*(?:,\s*'?([0-9A-Za-z_]{1,16})'?\s*)?\)$""")
    private val clickCall = Regex("""^oclick\(\s*'?([0-9]{1,6})'?\s*,\s*'?([0-9]{1,12})'?\s*(?:,\s*'?[0-9]{1,12}'?\s*)?\)$""")
    private val clickExtraCall = Regex("""^oclickth\(\s*'?([0-9]{1,6})'?\s*,\s*'?([0-9]{1,12})'?\s*,\s*'?([0-9]{1,12})'?\s*(?:,\s*'?[0-9]{1,12}'?\s*)?\)$""")
    private val giftCall = Regex("""^giftop\(\s*'?([0-9]{1,12})'?\s*\)$""")
    private val directCall = Regex("""^(stpick|puton|puti|stdel|puto|qhhsf|ocuz|upcard)\(\s*'?([0-9]{1,12})'?\s*\)$""")
    private val pierceCall = Regex("""^pirl\(\s*'?([0-9]{1,12})'?\s*\)$""")
    private val maxUpCall = Regex("""^cmaxup\(\s*'?([0-9]{1,12})'?\s*\)$""")
    private val statCall = Regex("""^updstat\(\s*'?([0-9]{1,12})'?\s*\)$""")
    private val haloSaveCall = Regex("""^halosave\(\s*\)$""")
    private val renameCall = Regex("""^upnewname\(\s*\)$""")
    private val diceCall = Regex("""^tzzz\(\s*'?([0-9]{1,3})'?\s*\)$""")
    private val statInputIds = listOf("sjll", "sjmj", "sjzl", "sjtp", "sjjs", "sjyz")
    private val directCommands = mapOf(
        "stpick" to "1", "puton" to "3", "upcard" to "5", "stdel" to "7",
        "puti" to "21", "puto" to "22", "qhhsf" to "23", "ocuz" to "33",
    )
    private val choiceCommand = Regex("[0-9]{1,6}")
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
        if (f !in setOf(1, 2, 5, 6, 7, 8, 9, 10, 12, 16, 17, 18, 20, 22, 23, 24, 25)) return null
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
        // Extra game pages that the web client loads from its own navigation.
        // They are read with the same separate cookie jar; no forum cookies.
        val stat = fetch("$gameOrigin/fyg_stat.php")?.first.orEmpty()
        val shop = fetch("$gameOrigin/fyg_shop.php")?.first.orEmpty()
        val ulog = fetch("$gameOrigin/fyg_ulog.php")?.first.orEmpty()
        val pk = readPage(12)
        val keys = fetch("$gameOrigin/fyg_llpw.php")?.first.orEmpty()
        // Flip board, gift list, wish pool and gem workshop are loaded by the
        // web client from these same-origin pages. They are read only here;
        // any control is exposed later only when its exact script call is known.
        val flip = readPage(22).orEmpty()
        val gifts = readPage(10).orEmpty()
        val halo = readPage(5).orEmpty()
        val statAlloc = readPage(23).orEmpty()
        val strengthen = readPage(24).orEmpty()
        val dice = readPage(16).orEmpty()
        val wish = fetch("$gameOrigin/fyg_wish.php")?.first.orEmpty()
        val gem = fetch("$gameOrigin/fyg_gem.php")?.first.orEmpty()
        return buildSnapshot(indexHtml, beach, equipped, inventory, cards, stat, shop, ulog, pk.orEmpty(), keys,
            flip, gifts, wish, gem, halo, statAlloc, strengthen, dice)
    }

    internal fun parseSnapshotForTest(
        indexHtml: String,
        beachHtml: String,
        equippedHtml: String?,
        inventoryHtml: String?,
        cardsHtml: String?,
        statHtml: String = "",
        shopHtml: String = "",
        ulogHtml: String = "",
        pkHtml: String = "",
        keysHtml: String = "",
        flipHtml: String = "",
        giftsHtml: String = "",
        wishHtml: String = "",
        gemHtml: String = "",
        haloHtml: String = "",
        statAllocHtml: String = "",
        strengthenHtml: String = "",
        diceHtml: String = "",
    ): GuguSnapshot? = buildSnapshot(indexHtml, beachHtml, equippedHtml, inventoryHtml, cardsHtml, statHtml, shopHtml, ulogHtml, pkHtml, keysHtml,
        flipHtml, giftsHtml, wishHtml, gemHtml, haloHtml, statAllocHtml, strengthenHtml, diceHtml)

    private fun buildSnapshot(
        indexHtml: String,
        beachHtml: String,
        equippedHtml: String?,
        inventoryHtml: String?,
        cardsHtml: String?,
        statHtml: String = "",
        shopHtml: String = "",
        ulogHtml: String = "",
        pkHtml: String = "",
        keysHtml: String = "",
        flipHtml: String = "",
        giftsHtml: String = "",
        wishHtml: String = "",
        gemHtml: String = "",
        haloHtml: String = "",
        statAllocHtml: String = "",
        strengthenHtml: String = "",
        diceHtml: String = "",
    ): GuguSnapshot? {
        val doc = Jsoup.parse(indexHtml, "$gameOrigin/fyg_index.php")
        val character = parseCharacter(doc) ?: return null
        val equipped = equippedHtml?.let { parseItems(it, true) }.orEmpty()
        val inventory = inventoryHtml?.let { parseItems(it, false) }.orEmpty()
        val cards = cardsHtml?.let { parseItems(it, false, cards = true) }.orEmpty()
        val cooldowns = parseCooldowns(beachHtml)
        val pageActions = (
            beachActions(beachHtml) +
                pkActions(pkHtml) +
                keyActions(keysHtml) +
                scriptActions(indexHtml, "咕咕镇首页") +
                scriptActions(flipHtml, "翻牌") +
                scriptActions(giftsHtml, "礼物") +
                scriptActions(wishHtml, "许愿池") +
                scriptActions(gemHtml, "宝石工坊") +
                scriptActions(strengthenHtml, "装备强化") +
                scriptActions(diceHtml, "骰子战争") +
                statPanelActions(statAllocHtml, "争夺等级") +
                haloPanelActions(haloHtml, "光环天赋")
            ).distinctBy { it.id }.take(80)
        val shopItems = parseShop(shopHtml)
        safeid = Regex("""safeid\s*[=:]\s*['"]?([0-9a-zA-Z]{4,32})""").find(indexHtml)?.groupValues?.get(1)
        val all = (pageActions + equipped.flatMap { it.actions } + inventory.flatMap { it.actions } +
            cards.flatMap { it.actions } + shopItems.mapNotNull { it.action }).associateBy { it.id }
        actions = all
        menuActions = emptyMap()
        return GuguSnapshot(
            character = character,
            equipped = equipped,
            inventory = inventory,
            cards = cards,
            cooldowns = cooldowns,
            winRates = parseWinRates(statHtml),
            shop = shopItems,
            updateLog = parseUpdateLog(ulogHtml),
            actions = pageActions,
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
            monthlyNpcLevel = monthly("战胜NPC最大等级"),
            monthlyPvp = monthly("对玩家战斗"),
        )
    }

    private fun parseItems(html: String, equipped: Boolean, cards: Boolean = false): List<GuguItem> {
        val doc = Jsoup.parse(html, "$gameOrigin/fyg_equip.php")
        return doc.select("button.fyg_colpzbg, button[class*=fyg_colpz], button[onclick^=zbtip], div[onclick^=xxcard]").mapIndexedNotNull { index, button ->
            val onclick = button.attr("onclick")
            val title = button.attr("title")
            val titleDoc = Jsoup.parseBodyFragment(title, "$gameOrigin/")
            val name = Regex("<br\\s*/?>\\s*(?:<[^>]+>\\s*)*([^<]+)\\s*$", RegexOption.IGNORE_CASE).find(title)
                ?.groupValues?.get(1)?.trim()
                ?: Regex("</span>\\s*([^<]+)\\s*$", RegexOption.IGNORE_CASE).find(title)?.groupValues?.get(1)?.trim()
                ?: titleDoc.text().trim().take(120)
            val cardId = Regex("xxcard\\s*\\(\\s*'?([0-9]{1,12})'?\\s*\\)").find(onclick)?.groupValues?.get(1)
            val fallbackName = if (cards) button.selectFirst(".fyg_f24")?.text()?.trim().orEmpty() else ""
            val shownName = name.ifBlank { fallbackName }.replace(Regex("\\s+x[0-9]{1,6}$"), "").trim()
            if (shownName.isBlank()) return@mapIndexedNotNull null
            val menu = normalizedCall(onclick)?.let { menuCall.matchEntire(it) }
            val menuM = menu?.groupValues?.get(1).orEmpty()
            val menuId = menu?.groupValues?.get(2).orEmpty()
            val id = if (menu != null) "menu:$menuM:${menuId.ifBlank { "_" }}"
                else Regex("zbtip\\s*\\(\\s*'([^']+)'").find(onclick)?.groupValues?.get(1)
                    ?: cardId
                    ?: "item:$index:${shownName.hashCode()}"
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
            val actionsForItem = if (menu != null) listOf(menuAction(menuM, menuId, shownName, "背包 / 仓库"))
                else itemActions(id, equipped, cards)
            GuguItem(id, shownName.take(120), level, quality, iconUrl, stats, equipped, actionsForItem)
        }.distinctBy { it.id }.take(200)
    }

    private fun itemActions(id: String, equipped: Boolean, cards: Boolean): List<GuguAction> {
        if (!id.matches(Regex("[0-9A-Za-z:_-]{1,64}"))) return emptyList()
        if (cards) return listOf(GuguAction(
            id = "card:$id",
            kind = GuguActionKind.CARD_EQUIP,
            label = "出战",
            confirmation = "确认让这张角色卡片出战？",
            detail = "服务器会切换当前出战卡片。",
        ))
        return if (equipped) listOf(
            GuguAction(
                id = "unequip:$id",
                kind = GuguActionKind.UNEQUIP_ITEM,
                label = "卸下",
                confirmation = "确认卸下这件装备？",
                detail = "装备会回到仓库，具体结果以服务器刷新为准。",
            ),
            GuguAction(
                id = "delete:$id",
                kind = GuguActionKind.DELETE_ITEM,
                label = "丢弃",
                confirmation = "确认丢弃这件装备？",
                detail = "该操作可能不可逆。客户端提交后只读回状态，不会自动重试。",
                destructive = true,
            ),
        ) else listOf(
            GuguAction(
                id = "equip:$id",
                kind = GuguActionKind.EQUIP_ITEM,
                label = "装备",
                confirmation = "确认装备这件物品？",
                detail = "服务器可能替换当前装备。",
            ),
            GuguAction(
                id = "delete:$id",
                kind = GuguActionKind.DELETE_ITEM,
                label = "丢弃",
                confirmation = "确认丢弃这件物品？",
                detail = "该操作可能不可逆。客户端提交后只读回状态，不会自动重试。",
                destructive = true,
            ),
            GuguAction(
                id = "pierce:$id",
                kind = GuguActionKind.PIERCE_ITEM,
                label = "熔炼",
                confirmation = "确认熔炼这件物品？",
                detail = "请输入本次投入数量；服务器返回结果后只读刷新，不自动连续操作。",
                destructive = true,
                inputLabel = "投入数量",
                inputHint = "只能填写非负整数",
                inputDefault = "1",
            ),
        )
    }

    /**
     * Reads only exact, visible script controls from a game page. The wire
     * endpoint for each call is defined by the site's own JS (`omenu` opens
     * fyg_menu.php; `oclick`/`oclickth` post to fyg_click.php; `giftop` opens
     * one gift). Unknown calls and commands that need extra UI-only fields are
     * left unexposed instead of being guessed.
     */
    private fun scriptActions(html: String, context: String): List<GuguAction> {
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parse(html, "$gameOrigin/")
        return doc.select("[onclick], a[href^=javascript:]").mapNotNull { element ->
            val raw = element.attr("onclick").ifBlank { element.attr("href") }
            val call = normalizedCall(raw) ?: return@mapNotNull null
            menuCall.matchEntire(call)?.let { match ->
                return@mapNotNull menuAction(match.groupValues[1], match.groupValues[2], controlLabel(element, "选项"), context)
            }
            giftCall.matchEntire(call)?.let { match ->
                val target = match.groupValues[1]
                val label = controlLabel(element, "礼物")
                return@mapNotNull GuguAction(
                    id = "gift:$target",
                    kind = GuguActionKind.GIFT_OPEN,
                    label = "打开礼物「$label」",
                    confirmation = "确认打开礼物「$label」？",
                    detail = "$context 中的单次礼物操作；提交后只读取服务器返回，不会自动重试。",
                    destructive = true,
                )
            }
            pierceCall.matchEntire(call)?.let { match ->
                return@mapNotNull pierceAction(match.groupValues[1], controlLabel(element, "熔炼"), context)
            }
            maxUpCall.matchEntire(call)?.let { match ->
                return@mapNotNull maxUpAction(match.groupValues[1], controlLabel(element, "满级"), context)
            }
            renameCall.matchEntire(call)?.let {
                val input = doc.getElementById("mdnewname") ?: return@mapNotNull null
                if (input.hasAttr("disabled") || input.hasAttr("readonly") ||
                    input.attr("type").equals("hidden", ignoreCase = true)) return@mapNotNull null
                return@mapNotNull renameAction(input.attr("value").trim().take(32), context)
            }
            diceCall.matchEntire(call)?.let { match ->
                val rounds = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                if (rounds !in 1..999) return@mapNotNull null
                return@mapNotNull diceAction(rounds, controlLabel(element, "骰子战争"), context)
            }
            directCall.matchEntire(call)?.let { match ->
                val command = directCommands[match.groupValues[1]] ?: return@mapNotNull null
                if (!choiceCommandAllowed(command)) return@mapNotNull null
                return@mapNotNull choiceAction(command, match.groupValues[2], "",
                    controlLabel(element, "选项"), context, extra = false)
            }
            clickExtraCall.matchEntire(call)?.let { match ->
                val command = match.groupValues[1]
                // The captured c=9 form is `pirl(id)` plus a separate yz count;
                // an extra id2 is not part of that wire, so do not guess one.
                if (command == "9") return@mapNotNull null
                if (!choiceCommandAllowed(command)) return@mapNotNull null
                return@mapNotNull choiceAction(command, match.groupValues[2], match.groupValues[3],
                    controlLabel(element, "选项"), context, extra = true)
            }
            clickCall.matchEntire(call)?.let { match ->
                val command = match.groupValues[1]
                if (command == "9") {
                    return@mapNotNull pierceAction(match.groupValues[2], controlLabel(element, "熔炼"), context)
                }
                if (!choiceCommandAllowed(command)) return@mapNotNull null
                return@mapNotNull choiceAction(command, match.groupValues[2], "",
                    controlLabel(element, "选项"), context, extra = false)
            }
            null
        }.distinctBy { it.id }.take(60).toList()
    }

    private fun menuAction(m: String, id2: String, label: String, context: String): GuguAction {
        val clean = label.ifBlank { "操作" }.take(60)
        return GuguAction(
            id = "menu:$m:${id2.ifBlank { "_" }}",
            kind = GuguActionKind.MENU_OPEN,
            label = "打开「$clean」菜单",
            confirmation = "确认打开「$clean」的操作菜单？",
            detail = "$context 提供的菜单；打开菜单只读取可选项，不会直接改变游戏状态。",
        )
    }

    private fun choiceAction(command: String, id: String, id2: String, label: String, context: String, extra: Boolean): GuguAction {
        val clean = label.ifBlank { "选项" }.take(60)
        return GuguAction(
            id = if (extra) "choicex:$command:$id:$id2" else "choice:$command:$id",
            kind = if (extra) GuguActionKind.MENU_CHOICE_EXTRA else GuguActionKind.MENU_CHOICE,
            label = clean,
            confirmation = "确认执行「$clean」？",
            detail = "$context 中的单次操作；提交后只读取服务器返回，不会自动重试。",
            destructive = true,
        )
    }

    private fun pierceAction(id: String, label: String, context: String): GuguAction {
        val clean = label.ifBlank { "熔炼" }.take(60)
        return GuguAction(
            id = "pierce:$id",
            kind = GuguActionKind.PIERCE_ITEM,
            label = clean,
            confirmation = "确认熔炼「$clean」？",
            detail = "$context 中的熔炼操作；请输入本次投入数量，提交后只读刷新，不自动连续操作。",
            destructive = true,
            inputLabel = "投入数量",
            inputHint = "只能填写非负整数",
            inputDefault = "1",
        )
    }

    private fun maxUpAction(id: String, label: String, context: String): GuguAction {
        val clean = label.ifBlank { "满级" }.take(60)
        return GuguAction(
            id = "maxup:$id",
            kind = GuguActionKind.MAX_UP,
            label = "满级提升「$clean」",
            confirmation = "确认使用「$clean」的满级提升？",
            detail = "$context 中的单次卡片满级操作；提交后只读取服务器返回，不会自动重试。",
            destructive = true,
        )
    }

    private fun renameAction(currentName: String, context: String): GuguAction {
        return GuguAction(
            id = "rename:6",
            kind = GuguActionKind.RENAME,
            label = "修改角色名",
            confirmation = "确认修改当前角色名？",
            detail = "$context 只在站点自身的改名输入可用时提供；服务器返回后只读刷新，不会自动重试。",
            destructive = true,
            fields = listOf(GuguActionField(
                name = "name",
                label = "新角色名",
                hint = "1 到 32 个字符，不能包含控制字符",
                defaultValue = currentName,
                numeric = false,
                maxLength = 32,
            )),
        )
    }

    private fun diceAction(rounds: Int, label: String, context: String): GuguAction {
        val clean = label.ifBlank { "骰子战争" }.take(60)
        return GuguAction(
            id = "dice:$rounds",
            kind = GuguActionKind.DICE_BATTLE,
            label = "骰子战争（单次）",
            confirmation = "确认进行一场骰子战斗？每场消耗 1 颗星晶。",
            detail = "$context 的「$clean」在网页端会连续请求 $rounds 次；客户端只提交一次，不会自动继续战斗。",
            destructive = true,
        )
    }

    /**
     * The attribute tab renders six fixed input ids and one exact `updstat(id)`
     * control. Parse that structure only; a redesigned tab yields no action.
     */
    private fun statPanelActions(html: String, context: String): List<GuguAction> {
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parse(html, "$gameOrigin/")
        val id = doc.select("[onclick], a[href^=javascript:]").asSequence().mapNotNull { element ->
            val call = normalizedCall(element.attr("onclick").ifBlank { element.attr("href") })
                ?: return@mapNotNull null
            statCall.matchEntire(call)?.groupValues?.get(1)
        }.firstOrNull() ?: return emptyList()
        val inputs = statInputIds.map { doc.getElementById(it) ?: return emptyList() }
        val fields = inputs.mapIndexed { index, input ->
            GuguActionField(
                name = "add0${index + 1}",
                label = input.attr("placeholder").ifBlank { input.attr("aria-label") }
                    .ifBlank { "属性 ${index + 1}" }.take(40),
                hint = "按网页字段顺序提交；只能填写非负整数",
                defaultValue = input.attr("value").trim().take(9).ifBlank { "0" },
                numeric = true,
                maxLength = 9,
            )
        }
        return listOf(GuguAction(
            id = "stat:$id",
            kind = GuguActionKind.STAT_ALLOCATE,
            label = "分配属性",
            confirmation = "确认提交这组属性分配？",
            detail = "$context 的六项属性会按网页字段顺序提交；服务器返回后只读刷新，不自动重试。",
            destructive = true,
            fields = fields,
        ))
    }

    /**
     * The halo tab renders `input[name=tfcheckbox]` values and one exact
     * `halosave()` control. Only checked values are prefilled; the user can
     * edit the comma-separated list before the explicit confirmation.
     */
    private fun haloPanelActions(html: String, context: String): List<GuguAction> {
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parse(html, "$gameOrigin/")
        val save = doc.select("[onclick], a[href^=javascript:]").any { element ->
            val call = normalizedCall(element.attr("onclick").ifBlank { element.attr("href") })
            call != null && haloSaveCall.matchEntire(call) != null
        }
        if (!save) return emptyList()
        val boxes = doc.select("input[name=tfcheckbox]")
        if (boxes.isEmpty()) return emptyList()
        val selected = boxes.filter { it.hasAttr("checked") }
            .map { it.attr("value").trim() }
            .filter { it.matches(numericTarget) }
            .distinct().joinToString(",")
        return listOf(GuguAction(
            id = "halo:save",
            kind = GuguActionKind.HALO_SAVE,
            label = "保存光环天赋",
            confirmation = "确认保存当前光环天赋选择？",
            detail = "$context 只会提交一次天赋 ID 列表；空列表表示不选择任何天赋，提交后只读刷新。",
            destructive = true,
            fields = listOf(GuguActionField(
                name = "arr",
                label = "天赋 ID（逗号分隔）",
                hint = "只接受数字，多个 ID 用英文逗号分隔",
                defaultValue = selected,
                numeric = false,
                maxLength = 200,
            )),
        ))
    }

    /**
     * Generic `oclick`/`oclickth` commands whose captured wire is exactly
     * `c` + `id` (+ optional `id2`). Commands with dedicated wires (c=2,4,6,
     * 8,9,10,17) and page-level controls are handled separately.
     */
    private val genericChoiceCommands = setOf("1", "3", "5", "7", "21", "22", "23",
        "30", "31", "33", "34", "35")

    private fun choiceCommandAllowed(command: String): Boolean =
        command.matches(choiceCommand) && command in genericChoiceCommands

    private fun controlLabel(element: Element, fallback: String): String {
        val raw = element.attr("title")
            .ifBlank { element.attr("aria-label") }
            .ifBlank { element.text() }
        val text = Jsoup.parseBodyFragment(raw, "$gameOrigin/").text()
            .replace(Regex("\\s+"), " ").trim()
            .replace(Regex("\\s+x[0-9]{1,6}$"), "")
        return text.ifBlank { fallback }.take(60)
    }

    private fun normalizedCall(raw: String): String? {
        var value = raw.trim()
        if (value.startsWith("javascript:", ignoreCase = true)) {
            value = value.substring("javascript:".length).trim()
        }
        value = value.trimEnd(';').trim()
        if (value.length !in 4..180 || value.contains(';')) return null
        return value
    }

    private fun parseMenuChoices(html: String): List<GuguAction> =
        scriptActions(html, "操作菜单").take(40)

    internal fun parseMenuForTest(html: String): List<GuguAction> = parseMenuChoices(html)

    private fun parseCooldowns(html: String): List<GuguCooldown> {
        if (html.isBlank()) return emptyList()
        val text = Jsoup.parse(html).text().replace(Regex("\\s+"), " ")
        val remaining = Regex("距离下次随机[^0-9]{0,20}([0-9]+)\\s*分钟").find(text)?.groupValues?.get(1)
        return listOfNotNull(remaining?.let { GuguCooldown("随机物品刷新", "$it 分钟", "沙滩物品最多保留 24 小时") })
    }

    /**
     * The site renders the win-rate matrix as a flat sequence of Bootstrap
     * columns: a header row of card names, then "name (attack)" /
     * "name (defence)" labels each followed by one cell per card. Parse only
     * that ordered structure; a redesigned page simply yields no table.
     */
    private fun parseWinRates(html: String): GuguWinRateTable? {
        if (html.isBlank()) return null
        val doc = Jsoup.parse(html, "$gameOrigin/fyg_stat.php")
        val panel = doc.select("div.panel").firstOrNull { panel ->
            panel.selectFirst("div.panel-heading")?.text()?.contains("胜率统计") == true
        } ?: return null
        val cards = panel.select("div.col-md-1.bg-primary").map { it.text().trim() }.filter { it.isNotBlank() }
        if (cards.size < 2) return null
        val rank = panel.select("div.col-md-2.text-danger").firstOrNull()?.text()?.trim()
            ?.substringBefore("段位")?.trim().orEmpty()
        val rows = linkedMapOf<String, Pair<MutableList<String>, MutableList<String>>>()
        var label: String? = null
        var attacking = true
        var values = mutableListOf<String>()
        fun flush() {
            val name = label ?: return
            val card = name.substringBefore('(').substringBefore('（').trim()
            if (card.isBlank()) return
            val entry = rows.getOrPut(card) { mutableListOf<String>() to mutableListOf() }
            val target = if (attacking) entry.first else entry.second
            target.addAll(values)
        }
        panel.select("div.col-md-1, div.col-md-2.bg-danger").forEach { cell ->
            if (cell.hasClass("col-md-2")) {
                flush()
                label = cell.text().trim()
                attacking = !label.contains("防守")
                values = mutableListOf()
            } else if (label != null) {
                values.add(cell.text().trim())
            }
        }
        flush()
        if (rows.isEmpty()) return null
        val note = doc.select("div.panel").firstOrNull { panel ->
            panel.selectFirst("div.panel-heading")?.text()?.contains("全站战绩统计") == true
        }?.selectFirst("div.panel-body")?.text()?.trim().orEmpty()
        return GuguWinRateTable(
            rank = rank,
            cards = cards.take(20),
            rows = rows.map { (card, pair) ->
                GuguWinRateRow(card, pair.first.take(cards.size), pair.second.take(cards.size))
            }.take(30),
            note = note,
        )
    }

    private fun parseShop(html: String): List<GuguShopItem> {
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parse(html, "$gameOrigin/fyg_shop.php")
        return doc.select("button[onclick^=zshopts]").mapNotNull { button ->
            val rawName = button.selectFirst("span.fyg_f18")?.text()?.trim()
                ?: button.text().substringBefore("价格").trim()
            if (rawName.isBlank()) return@mapNotNull null
            val price = button.selectFirst("span.text-primary")?.text()?.trim().orEmpty()
            val target = Regex("zshopts\\s*\\(\\s*'?([0-9]{1,12})'?\\s*\\)").find(button.attr("onclick"))?.groupValues?.get(1)
            GuguShopItem(
                name = rawName.removePrefix("[日限]").trim(),
                price = price,
                dailyLimit = rawName.startsWith("[日限]"),
                action = target?.let {
                    GuguAction(
                        id = "shop:$it",
                        kind = GuguActionKind.SHOP_BUY,
                        label = "购买",
                        confirmation = "确认购买“${rawName.removePrefix("[日限]").trim()}”？",
                        detail = "价格：$price。将消耗游戏内货币；客户端不会自动重复购买。",
                        destructive = true,
                    )
                },
            )
        }.distinctBy { it.name to it.price }.take(50)
    }

    private fun beachActions(html: String): List<GuguAction> {
        if (html.isBlank()) return emptyList()
        val result = mutableListOf<GuguAction>()
        if (Regex("""onclick\s*=\s*["']sttzok\s*\(\s*\)""").containsMatchIn(html)) result.add(GuguAction(
            id = "beach:clear",
            kind = GuguActionKind.BEACH_CLEAR,
            label = "清理沙滩",
            confirmation = "确认清理沙滩？",
            detail = "沙滩上所有装备会消失，并按服务器规则返还锻造石。该操作可能不可逆。",
            destructive = true,
        ))
        if (Regex("""onclick\s*=\s*["']gx_sxstok\s*\(\s*\)""").containsMatchIn(html)) result.add(GuguAction(
            id = "beach:refresh",
            kind = GuguActionKind.BEACH_REFRESH,
            label = "强制刷新",
            confirmation = "确认强制刷新沙滩？",
            detail = "会消耗随机装备箱并刷新一批新装备；原有装备不会消失。",
            destructive = true,
        ))
        return result
    }

    private fun pkActions(html: String): List<GuguAction> {
        if (html.isBlank()) return emptyList()
        val result = Regex("""jgjg\s*\(\s*'?([0-9]{1,12})'?\s*\)""").findAll(html)
            .map { match ->
                val target = match.groupValues[1]
                GuguAction(
                    id = "pk:$target",
                    kind = GuguActionKind.PK_STEP,
                    label = "挑战 $target",
                    confirmation = "确认挑战目标 $target？",
                    detail = "仅执行一次单步争夺；客户端不会自动循环“前进至结束”。",
                    destructive = true,
                )
            }.distinctBy { it.id }.take(20).toMutableList()
        if (Regex("""onclick\s*=\s*["']gx_sxds\s*\(\s*\)""").containsMatchIn(html)) result.add(GuguAction(
            id = "pk:refresh",
            kind = GuguActionKind.PK_REFRESH,
            label = "刷新对手",
            confirmation = "确认刷新争夺对手？",
            detail = "服务器可能消耗次数或道具，具体规则以站点为准。",
            destructive = true,
        ))
        if (Regex("""onclick\s*=\s*["']gox\s*\(\s*\)""").containsMatchIn(html)) result.add(GuguAction(
            id = "pk:go",
            kind = GuguActionKind.PK_GO,
            label = "继续争夺",
            confirmation = "确认继续争夺？",
            detail = "只执行一次站点提供的继续操作，不会自动循环。",
            destructive = true,
        ))
        return result
    }

    private fun keyActions(html: String): List<GuguAction> {
        if (html.isBlank()) return emptyList()
        val map = mapOf(
            "1" to Triple(GuguActionKind.KEY_GENERATE, "生成新的密钥", "旧密钥会立即失效。"),
            "2" to Triple(GuguActionKind.KEY_EXTEND, "当前密钥延期 7 天", "延长现有临时登录密钥的有效期。"),
            "3" to Triple(GuguActionKind.KEY_SHOP_ALLOW, "允许密钥进入商店", "允许使用临时密钥的人进入商店。"),
            "4" to Triple(GuguActionKind.KEY_SHOP_DENY, "禁止密钥进入商店", "禁止使用临时密钥的人进入商店。"),
        )
        return map.mapNotNull { (num, value) ->
            if (!Regex("""okll\s*\(\s*$num\s*\)""").containsMatchIn(html)) return@mapNotNull null
            GuguAction(
                id = "key:$num",
                kind = value.first,
                label = value.second,
                confirmation = "确认${value.second}？",
                detail = value.third,
                destructive = num == "1",
            )
        }
    }

    private fun parseUpdateLog(html: String): List<GuguLogEntry> {
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parse(html, "$gameOrigin/fyg_ulog.php")
        val panel = doc.select("div.panel").firstOrNull { panel ->
            panel.selectFirst("div.panel-heading")?.text()?.contains("更新日志") == true
        } ?: return emptyList()
        return panel.select("div.alert").mapNotNull { alert ->
            val date = alert.selectFirst("h3")?.text()?.trim().orEmpty()
            if (date.isBlank()) return@mapNotNull null
            val body = alert.clone().apply { select("h3").remove() }
            val lines = body.html().split(Regex("(?i)<br\\s*/?>"))
                .map { fragment -> Jsoup.parseBodyFragment(fragment, "$gameOrigin/").text().trim() }
                .filter { it.isNotBlank() }
                .take(60)
            GuguLogEntry(date, lines)
        }.take(20)
    }

    /**
     * Executes exactly one operation that was present in the last snapshot.
     * The action id is re-checked against that snapshot; request targets and
     * the session safeid are never accepted from the UI. There is no retry and
     * no client-side battle loop.
     */
    internal suspend fun perform(request: GuguActionRequest): GuguActionResult = withContext(Dispatchers.IO) {
        val known = (actions[request.action.id] ?: menuActions[request.action.id])
            ?: return@withContext GuguActionResult.Rejected("该操作已过期，请重新读取咕咕镇")
        if (known.kind != request.action.kind) return@withContext GuguActionResult.Rejected("操作类型与当前页面不一致")
        if (known.kind != GuguActionKind.MENU_OPEN) menuActions = emptyMap()
        val parts = known.id.split(':')
        val target = parts.getOrNull(1).orEmpty()
        val extra = parts.getOrNull(2).orEmpty()
        val trailing = parts.getOrNull(3).orEmpty()
        val needsSafeid = known.kind !in setOf(
            GuguActionKind.KEY_GENERATE, GuguActionKind.KEY_EXTEND,
            GuguActionKind.KEY_SHOP_ALLOW, GuguActionKind.KEY_SHOP_DENY,
            GuguActionKind.MENU_OPEN,
        )
        val validTarget = when (known.kind) {
            GuguActionKind.MENU_OPEN -> target.matches(choiceCommand) &&
                (extra == "_" || extra.matches(Regex("[0-9A-Za-z_]{1,16}")))
            GuguActionKind.MENU_CHOICE -> choiceCommandAllowed(target) && extra.matches(numericTarget)
            GuguActionKind.MENU_CHOICE_EXTRA ->
                choiceCommandAllowed(target) && extra.matches(numericTarget) && trailing.matches(numericTarget)
            GuguActionKind.GIFT_OPEN -> target.matches(numericTarget)
            GuguActionKind.EQUIP_ITEM, GuguActionKind.UNEQUIP_ITEM, GuguActionKind.DELETE_ITEM,
            GuguActionKind.PIERCE_ITEM, GuguActionKind.CARD_EQUIP, GuguActionKind.SHOP_BUY,
            GuguActionKind.PK_STEP, GuguActionKind.STAT_ALLOCATE, GuguActionKind.MAX_UP ->
                target.matches(numericTarget)
            GuguActionKind.HALO_SAVE -> target == "save"
            GuguActionKind.RENAME -> target == "6"
            GuguActionKind.DICE_BATTLE -> target.matches(Regex("[0-9]{1,3}"))
            else -> true
        }
        if (!validTarget) return@withContext GuguActionResult.Rejected("服务器返回的操作目标无效，已取消")
        val sid = safeid.orEmpty()
        if (needsSafeid && !sid.matches(Regex("[0-9a-zA-Z]{4,32}"))) {
            return@withContext GuguActionResult.Rejected("游戏会话缺少安全令牌，请重新进入咕咕镇")
        }
        val input = request.input.trim()
        if (known.kind == GuguActionKind.PIERCE_ITEM && !input.matches(Regex("[0-9]{1,9}"))) {
            return@withContext GuguActionResult.Rejected("投入数量必须是非负整数")
        }
        val values = request.values.mapValues { it.value.trim() }
        if (known.kind == GuguActionKind.STAT_ALLOCATE &&
            (1..6).any { !values["add0$it"].orEmpty().matches(Regex("[0-9]{1,9}")) }) {
            return@withContext GuguActionResult.Rejected("六项属性都必须是非负整数")
        }
        if (known.kind == GuguActionKind.HALO_SAVE) {
            val arr = values["arr"].orEmpty()
            if (arr.length > 200 || (arr.isNotEmpty() && !arr.matches(Regex("[0-9]{1,12}(,[0-9]{1,12})*")))) {
                return@withContext GuguActionResult.Rejected("天赋 ID 只能是用英文逗号分隔的数字")
            }
        }
        if (known.kind == GuguActionKind.RENAME) {
            val name = values["name"].orEmpty()
            if (name.length !in 1..32 || name.any { it.code < 0x20 || it.code == 0x7f }) {
                return@withContext GuguActionResult.Rejected("角色名必须是 1 到 32 个可见字符")
            }
        }
        val wire = wireFor(known, input, values, sid)
        val outcome = postForm("$gameOrigin/${wire.path}", wire.params)
            ?: return@withContext GuguActionResult.Uncertain("操作结果无法确认；客户端不会自动重试，请重新读取状态核对")
        if (outcome.second != null) {
            return@withContext GuguActionResult.Uncertain("服务器要求跳转，客户端未自动跟随；请重新读取状态核对")
        }
        if (known.kind == GuguActionKind.MENU_OPEN) {
            val choices = parseMenuChoices(outcome.first)
            if (choices.isEmpty()) {
                menuActions = emptyMap()
                return@withContext GuguActionResult.Rejected("服务器没有返回可执行菜单选项；请重新读取咕咕镇")
            }
            menuActions = choices.associateBy { it.id }
            return@withContext GuguActionResult.Choices(GuguMenu(known.label, choices))
        }
        val clean = Jsoup.parse(outcome.first).text().replace(Regex("\\s+"), " ").trim().take(240)
        val refreshed = try {
            fetch("$gameOrigin/fyg_index.php")?.first?.let(::parseIndex)
        } catch (_: Exception) { null }
        val failure = listOf("失败", "不足", "不能", "无法", "已满", "错误", "禁止", "未登录", "请先",
            "超过", "不可", "无效", "没有", "已失效", "不存在").any { clean.contains(it) }
        when {
            clean == "ok" || clean == "1" -> GuguActionResult.Success("操作成功", refreshed)
            known.kind == GuguActionKind.PIERCE_ITEM && clean.matches(Regex("[0-9]{1,12}")) ->
                GuguActionResult.Success("熔炼已提交，服务器返回编号 $clean", refreshed)
            clean.contains("成功") -> GuguActionResult.Success(clean.ifBlank { "操作成功" }, refreshed)
            failure -> GuguActionResult.Rejected(clean.ifBlank { "服务器拒绝了该操作" })
            clean.isBlank() -> GuguActionResult.Uncertain("服务器未返回可确认的结果；请重新读取状态核对", refreshed)
            else -> GuguActionResult.Uncertain("已提交，站点返回：$clean", refreshed)
        }
    }

    private fun wireFor(known: GuguAction, input: String, values: Map<String, String>, sid: String): Wire {
        val parts = known.id.split(':')
        val target = parts.getOrNull(1).orEmpty()
        val extra = parts.getOrNull(2).orEmpty()
        val trailing = parts.getOrNull(3).orEmpty()
        return when (known.kind) {
            GuguActionKind.EQUIP_ITEM -> Wire("fyg_click.php", listOf("c" to "3", "id" to target, "safeid" to sid))
            GuguActionKind.UNEQUIP_ITEM -> Wire("fyg_click.php", listOf("c" to "21", "id" to target, "safeid" to sid))
            GuguActionKind.DELETE_ITEM -> Wire("fyg_click.php", listOf("c" to "7", "id" to target, "safeid" to sid))
            GuguActionKind.PIERCE_ITEM -> Wire("fyg_click.php", listOf("c" to "9", "id" to target, "yz" to input, "safeid" to sid))
            GuguActionKind.CARD_EQUIP -> Wire("fyg_click.php", listOf("c" to "5", "id" to target, "safeid" to sid))
            GuguActionKind.BEACH_CLEAR -> Wire("fyg_click.php", listOf("c" to "20", "safeid" to sid))
            GuguActionKind.BEACH_REFRESH -> Wire("fyg_click.php", listOf("c" to "12", "safeid" to sid))
            GuguActionKind.SHOP_BUY -> Wire("fyg_shop_click.php", listOf("c" to target, "safeid" to sid))
            GuguActionKind.PK_STEP -> Wire("fyg_v_intel.php", listOf("id" to target, "safeid" to sid))
            GuguActionKind.PK_REFRESH -> Wire("fyg_click.php", listOf("c" to "13", "safeid" to sid))
            GuguActionKind.PK_GO -> Wire("fyg_click.php", listOf("c" to "16", "safeid" to sid))
            GuguActionKind.KEY_GENERATE -> Wire("fyg_llpw_c.php", listOf("iu" to "1"))
            GuguActionKind.KEY_EXTEND -> Wire("fyg_llpw_c.php", listOf("iu" to "2"))
            GuguActionKind.KEY_SHOP_ALLOW -> Wire("fyg_llpw_c.php", listOf("iu" to "3"))
            GuguActionKind.KEY_SHOP_DENY -> Wire("fyg_llpw_c.php", listOf("iu" to "4"))
            GuguActionKind.MENU_OPEN -> Wire("fyg_menu.php",
                listOf("m" to target, "id" to if (extra == "_") "undefined" else extra))
            GuguActionKind.MENU_CHOICE -> Wire("fyg_click.php",
                listOf("c" to target, "id" to extra, "safeid" to sid))
            GuguActionKind.MENU_CHOICE_EXTRA -> Wire("fyg_click.php",
                listOf("c" to target, "id" to extra, "id2" to trailing, "safeid" to sid))
            GuguActionKind.GIFT_OPEN -> Wire("fyg_click.php",
                listOf("c" to "8", "id" to target, "safeid" to sid))
            GuguActionKind.MAX_UP -> Wire("fyg_click.php",
                listOf("c" to "10", "num" to target, "safeid" to sid))
            GuguActionKind.STAT_ALLOCATE -> Wire("fyg_click.php",
                listOf("c" to "2", "id" to target) +
                    (1..6).map { "add0$it" to values["add0$it"].orEmpty() } +
                    listOf("safeid" to sid))
            GuguActionKind.HALO_SAVE -> Wire("fyg_click.php",
                listOf("c" to "4", "arr" to values["arr"].orEmpty(), "safeid" to sid))
            GuguActionKind.RENAME -> Wire("fyg_click.php",
                listOf("c" to "6", "name" to values["name"].orEmpty(), "safeid" to sid))
            GuguActionKind.DICE_BATTLE -> Wire("fyg_s_int.php", listOf("safeid" to sid))
        }
    }

    internal fun wireForTest(action: GuguAction, input: String = "1",
                             values: Map<String, String> = emptyMap(), sid: String = "test1234"): Wire =
        wireFor(action, input, values, sid)

    internal data class Wire(val path: String, val params: List<Pair<String, String>>)

    private fun postForm(url: String, params: List<Pair<String, String>>): Pair<String, String?>? {
        val form = FormBody.Builder().apply {
            params.forEach { (name, value) -> add(name, value) }
        }.build()
        val request = Request.Builder().url(url).post(form)
            .header("User-Agent", "KFMobile/0.1 (Android; native client)")
            .header("Referer", "$gameOrigin/fyg_index.php")
            .header("Accept", "text/html,application/xhtml+xml").build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.code in listOf(301, 302, 303, 307, 308)) {
                    val location = response.header("Location") ?: return null
                    val resolved = response.request.url.resolve(location)?.toString() ?: return null
                    "" to resolved
                }
                if (!response.isSuccessful) return null
                val body = response.body ?: return null
                val bytes = body.byteStream().readBounded(4 * 1024 * 1024 + 1)
                if (bytes.size > 4 * 1024 * 1024) return null
                decodePageBytes(bytes, body.contentType()?.charset()?.name(), null) to null
            }
        } catch (_: Exception) { null }
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

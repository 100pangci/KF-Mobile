package work.miaola.kf

import kotlinx.serialization.Serializable

interface KeyValueStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

@Serializable
data class Domain(val id: String, val name: String, val origin: String)

@Serializable
data class DomainCatalog(
    val schemaVersion: Int = 1,
    val defaultDomainId: String,
    val domains: List<Domain>,
) {
    fun validated(): DomainCatalog {
        require(schemaVersion == 1) { "不支持的域名配置版本" }
        require(domains.isNotEmpty() && domains.size <= 50) { "域名数量无效" }
        require(domains.map { it.id }.distinct().size == domains.size) { "域名 ID 重复" }
        val normalized = domains.map {
            require(it.id.matches(Regex("[a-zA-Z0-9_-]{1,64}")) && it.name.isNotBlank() && it.name.length <= 80) { "域名名称或 ID 无效" }
            it.copy(origin = normalizeOrigin(it.origin))
        }
        require(normalized.map { it.origin }.distinct().size == normalized.size) { "域名地址重复" }
        require(normalized.any { it.id == defaultDomainId }) { "默认域名不存在" }
        return copy(domains = normalized)
    }

    companion object {
        val BuiltIn = DomainCatalog(defaultDomainId = "miaola", domains =
            listOf(Domain("miaola", "喵拉 · 主站", "https://kf.miaola.work")) +
                (0..9).map { Domain("miaola-$it", "喵拉 · 节点 $it", "https://$it.kf.miaola.work") })
    }
}

fun normalizeOrigin(value: String): String {
    val origin = value.trim().trimEnd('/').lowercase()
    val pattern = Regex("https://([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+)(?::([0-9]{1,5}))?")
    val match = pattern.matchEntire(origin)
    require(match != null) { "请输入仅含域名和可选端口的 HTTPS 地址" }
    val port = match.groupValues[2]
    require(port.isEmpty() || port.toInt() in 1..65535) { "端口无效" }
    return if (port == "443") origin.removeSuffix(":443") else origin
}

enum class CatalogSource(val label: String) { ONLINE("在线配置"), CACHE("本地缓存"), BUILT_IN("内置配置") }
data class CatalogResult(val catalog: DomainCatalog, val source: CatalogSource, val warning: String? = null)

data class SiteLink(val title: String, val url: String, val subtitle: String = "", val group: String = "")
data class HomeFeedSection(val id: String, val title: String, val topics: List<SiteLink>)
data class PostMeta(val title: String, val html: String)
data class PostReply(val title: String, val html: String, val targetUrl: String? = null)

data class ForumPost(
    val id: String,
    val author: String,
    val authorUrl: String,
    val floor: String,
    val time: String,
    val html: String,
    val quoteUrl: String = "",
    val purchases: List<PurchaseOffer> = emptyList(),
    val purchaseLocked: Boolean = false,
    val keywords: List<SiteLink> = emptyList(),
    val meta: List<PostMeta> = emptyList(),
    val actions: List<SiteDestination> = emptyList(),
    val replies: List<PostReply> = emptyList(),
    val avatar: String = "",
)

data class PurchaseOffer(
    val id: String,
    val postId: String,
    val price: String,
    val currency: String,
    val actionUrl: String,
    val threadUrl: String,
) {
    // The action URL includes a per-session verify token. Never print it.
    override fun toString() = "PurchaseOffer(id=$id, price=$price, currency=$currency)"
}

fun purchasePendingKey(origin: String, id: String) = "purchase_pending@$origin#$id"

sealed class PurchaseResult {
    data class Success(val page: PageData, val message: String) : PurchaseResult()
    data class PriceChanged(val offer: PurchaseOffer) : PurchaseResult()
    data class Rejected(val message: String) : PurchaseResult()
    data class Uncertain(val message: String) : PurchaseResult()
}
data class PageData(
    val url: String,
    val title: String,
    val authenticated: Boolean,
    val username: String = "",
    val boards: List<SiteLink> = emptyList(),
    val topics: List<SiteLink> = emptyList(),
    val posts: List<ForumPost> = emptyList(),
    val menu: List<SiteLink> = emptyList(),
    val next: String? = null,
    val previous: String? = null,
    val composeUrl: String? = null,
    val notice: String = "",
    val authStatus: AuthStatus = if (authenticated) AuthStatus.AUTHENTICATED else AuthStatus.UNKNOWN,
    val feeds: List<HomeFeedSection> = emptyList(),
    val nativePage: NativeSitePage? = null,
    val serverLogout: SiteDestination? = null,
    val favorite: SiteDestination? = null,
    val dailyBonus: SiteDestination? = null,
    val boardFilters: List<SiteLink> = emptyList(),
)

enum class AuthStatus { AUTHENTICATED, LOGIN_REQUIRED, UNKNOWN }
data class ForumEmoticon(val id: Int, val label: String, val imageUrl: String, val group: String = "默认表情") {
    val code get() = "[s:$id]"
}
data class EditorInsertion(val text: String, val cursor: Int)
fun insertEditorText(text: String, start: Int, end: Int, insertion: String): EditorInsertion {
    val low = minOf(start, end).coerceIn(0, text.length)
    val high = maxOf(start, end).coerceIn(low, text.length)
    return EditorInsertion(text.substring(0, low) + insertion + text.substring(high), low + insertion.length)
}
fun wrapEditorSelection(text: String, start: Int, end: Int, open: String, close: String): EditorInsertion {
    val low = minOf(start, end).coerceIn(0, text.length)
    val high = maxOf(start, end).coerceIn(low, text.length)
    val selected = text.substring(low, high)
    val result = text.substring(0, low) + open + selected + close + text.substring(high)
    return EditorInsertion(result, low + open.length + selected.length + if (selected.isNotEmpty()) close.length else 0)
}

data class InlineImageSize(val width: Int, val height: Int)
fun isForumEmoticon(url: String) = url.substringBefore('?').contains("/post/smile/", ignoreCase = true)
fun inlineImageSize(url: String, width: Int, height: Int, maxWidth: Int, density: Float, fontScale: Float): InlineImageSize {
    val w = width.coerceAtLeast(1)
    val h = height.coerceAtLeast(1)
    val limit = maxWidth.coerceAtLeast(1)
    if (isForumEmoticon(url)) {
        val extent = (36 * density * fontScale).toInt().coerceAtLeast(1).coerceAtMost(limit)
        return if (w >= h) InlineImageSize(extent, (h.toLong() * extent / w).toInt().coerceAtLeast(1))
        else InlineImageSize((w.toLong() * extent / h).toInt().coerceAtLeast(1), extent)
    }
    val target = w.coerceAtMost(limit)
    return InlineImageSize(target, (h.toLong() * target / w).toInt().coerceAtLeast(1))
}

data class FormOption(val value: String, val label: String)
data class EditorForm(
    val sourceUrl: String,
    val action: String,
    val fields: Map<String, String>,
    val titleField: String,
    val contentField: String = "atc_content",
    val categories: List<FormOption> = emptyList(),
    val recipientField: String? = null,
    val minBytes: Int = 12,
    val maxBytes: Int = 50000,
    val titleMaxBytes: Int = 100,
    val categoryRequired: Boolean = false,
    val emoticons: List<ForumEmoticon> = emptyList(),
    val uploadEnabled: Boolean = false,
    val uploadLimit: Int = 5,
    val uploadMaxBytes: Int = 1024 * 1024,
    val uploadExtensions: List<String> = emptyList(),
)

sealed class SubmitResult {
    data class Success(val url: String?, val message: String) : SubmitResult()
    data class Rejected(val message: String) : SubmitResult()
    data class Uncertain(val message: String) : SubmitResult()
}

interface ForumService {
    val origin: String
    suspend fun fetch(url: String): PageData
    suspend fun login(username: String, password: String): PageData
    suspend fun search(keyword: String, author: String = ""): PageData
    suspend fun editor(url: String): EditorForm
    suspend fun submit(form: EditorForm, values: Map<String, String>): SubmitResult
    suspend fun purchase(offer: PurchaseOffer): PurchaseResult = PurchaseResult.Rejected("暂不支持购买，请先查看站点记录")
    fun destination(url: String, title: String = ""): SiteDestination = SiteDestination(url, SiteEffect.READ, title)
    suspend fun submitSite(submission: SiteSubmission): SiteActionResult = SiteActionResult.Rejected("当前服务未接入该表单")
    suspend fun executeSite(destination: SiteDestination): SiteActionResult = SiteActionResult.Rejected("当前操作尚未适配")
    suspend fun submitWithFiles(form: EditorForm, values: Map<String, String>, files: List<LocalUpload>): SubmitResult =
        if (files.isEmpty()) submit(form, values) else SubmitResult.Rejected("当前服务未接入附件上传")
    fun encodedLength(value: String): Int
    fun clearSession()
    fun close()
}

interface ServiceFactory {
    fun create(origin: String): ForumService
    suspend fun catalog(url: String): String
}

enum class HomeTab(val title: String) { FEED("发现"), BOARDS("板块"), MESSAGES("消息"), ACCOUNT("我的") }
enum class ThemeMode(val title: String) { SYSTEM("跟随系统"), LIGHT("浅色"), DARK("深色") }

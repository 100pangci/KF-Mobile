package work.miaola.kf

import androidx.compose.runtime.*

/** UI aggregation only: the URL cache continues to hold unmodified server pages. */
class PageTimeline(first: PageData) {
    var first by mutableStateOf(first); private set
    val topics = mutableStateListOf<SiteLink>()
    val posts = mutableStateListOf<ForumPost>()
    val messages = mutableStateListOf<NativeMessageSummary>()
    val nativePages = mutableStateListOf<NativeSitePage>()
    val feedTopics = mutableStateMapOf<String, List<SiteLink>>()
    private val pages = mutableListOf<PageData>()
    private val visited = mutableSetOf<String>()
    private val topicIds = mutableSetOf<String>()
    private val postIds = mutableSetOf<String>()
    val loadedPageUrls: List<String> get() = pages.map { it.url }
    var next by mutableStateOf<String?>(null); private set
    var lastUrl: String = first.url; private set
    var loading by mutableStateOf(false); internal set
    var refreshing by mutableStateOf(false); internal set
    var failure by mutableStateOf<String?>(null); internal set
    var managementStale by mutableStateOf(false); private set
    internal var requestId = 0

    init { reset(first) }

    fun reset(page: PageData) {
        managementStale = false
        first = page
        pages.clear(); visited.clear(); topicIds.clear(); postIds.clear()
        topics.clear(); posts.clear(); messages.clear(); nativePages.clear(); failure = null
        feedTopics.clear()
        append(page, page.url)
    }

    /** Update action/purchase context without throwing away already read floors. */
    fun updateFirst(page: PageData) {
        if (first === page) return
        val tail = pages.drop(1)
        reset(page)
        tail.forEach { append(it, it.url) }
    }

    fun canLoad(url: String) = !managementStale && !loading && !refreshing && url !in visited

    /** Keep the visible root for reading, but never reuse old page boundaries. */
    fun invalidateManagement() {
        requestId++
        loading = false; refreshing = false
        reset(first)
        managementStale = true
        next = null
    }

    fun updatePage(page: PageData) {
        if (first.url.substringBefore('#') == page.url.substringBefore('#')) {
            updateFirst(page)
            return
        }
        val index = pages.indexOfFirst { it.url.substringBefore('#') == page.url.substringBefore('#') }
        if (index < 0) return
        val snapshot = pages.toMutableList().apply { set(index, page) }
        reset(snapshot.first())
        snapshot.drop(1).forEach { append(it, it.url) }
    }

    fun append(page: PageData, requested: String) {
        lastUrl = page.url
        pages.add(page)
        visited.add(requested.substringBefore('#'))
        visited.add(page.url.substringBefore('#'))
        topics.addAll(page.topics.filter { topicIds.add(topicIdentity(it.url)) })
        posts.addAll(page.posts.filter { postIds.add(it.id) })
        messages.addAll(page.nativePage?.messages.orEmpty().filter { message -> messages.none { topicIdentity(it.url) == topicIdentity(message.url) } })
        page.nativePage?.let(nativePages::add)
        page.feeds.forEach { section ->
            feedTopics[section.id] = (feedTopics[section.id].orEmpty() + section.topics).distinctBy { topicIdentity(it.url) }
        }
        next = page.next?.substringBefore('#')?.takeUnless { it in visited }
        failure = null
    }
}

fun topicIdentity(url: String): String {
    val id = Regex("[?&](tid|mid)=([^&#]+)").find(url)
    return if (id == null) url.substringBefore('#') else "${id.groupValues[1]}:${id.groupValues[2]}"
}

/** Only follow a page-number change within the same list, never a parsed action. */
fun isPageContinuation(source: String, target: String): Boolean {
    val number = Regex("[?&]page=([0-9]+)(?:&|$)").find(target.substringBefore('#'))
        ?.groupValues?.get(1)?.toIntOrNull() ?: return false
    val current = Regex("[?&]page=([0-9]+)").find(source)?.groupValues?.get(1)?.toIntOrNull() ?: 1
    return number > current && isSamePageList(source, target)
}

fun firstPageUrl(url: String): String {
    val base = url.substringBefore('#').substringBefore('?')
    val query = url.substringAfter('?', "").substringBefore('#').split('&')
        .filter { it.isNotBlank() && it.substringBefore('=') != "page" }.joinToString("&")
    return base + if (query.isBlank()) "" else "?$query"
}

fun isSamePageList(source: String, target: String): Boolean {
    fun identity(url: String): Pair<String, List<String>> {
        val base = url.substringBefore('?').substringBefore('#')
        val query = url.substringAfter('?', "").substringBefore('#').split('&').filter {
            it.isNotBlank() && it.substringBefore('=') != "page" &&
                // fpage is the board page to return to, not reader ordering.
                !(base.endsWith("/read.php") && it.substringBefore('=') == "fpage" && it.substringAfter('=').toIntOrNull() != null)
        }.toMutableList()
        // The site's inbox links spell out the default action on later pages.
        // Compare semantics, but send the original URL bytes unchanged.
        if (base.endsWith("/message.php")) {
            query.removeAll { it in setOf("action=", "action=receivebox") }
            if (query.none { it.substringBefore('=') == "action" }) query.add("action=receivebox")
        }
        return base to query.sorted()
    }
    return identity(source) == identity(target)
}

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package work.miaola.kf

import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.*
import kotlin.test.*
import work.miaola.kf.ui.searchCacheKey

class PageTimelineTest {
    private val origin = "https://kf.miaola.work"
    private val first = PageData("$origin/read.php?tid=1", "帖子", true,
        topics = listOf(SiteLink("A", "$origin/read.php?tid=1")),
        posts = listOf(post("1")), next = "$origin/read.php?tid=1&page=2")
    private fun post(id: String) = ForumPost(id, "作者", "", id, "", "<p>$id</p>")

    @Test fun aggregationDeduplicatesWithoutReorderingOrChangingCachedPage() {
        val state = PageTimeline(first)
        state.append(first.copy(url = first.next!!, topics = listOf(SiteLink("A", "$origin/read.php?tid=1&page=2"), SiteLink("B", "$origin/read.php?tid=2")),
            posts = listOf(post("1"), post("2"), post("3")), next = first.next), first.next)
        assertEquals(listOf("1", "2", "3"), state.posts.map { it.id })
        assertEquals(listOf("A", "B"), state.topics.map { it.title })
        assertNull(state.next)
        assertEquals(1, first.posts.size)
        state.updateFirst(first.copy(title = "已更新"))
        assertEquals(3, state.posts.size)
    }

    @Test fun paginationCannotFollowActionsOtherListsOrOtherServers() {
        assertTrue(isPageContinuation(first.url, first.next!!))
        assertFalse(isPageContinuation(first.url, "$origin/read.php?tid=2&page=2"))
        assertFalse(isPageContinuation(first.url, "$origin/read.php?tid=1&page=2&action=delete"))
        assertFalse(isPageContinuation(first.url, "https://example.com/read.php?tid=1&page=2"))
        assertFalse(isPageContinuation(first.next, first.next))
        assertFalse(isPageContinuation(first.url, "${first.url}&page=1"))
        assertTrue(isPageContinuation("$origin/search.php?searchid=abc", "$origin/search.php?searchid=abc&page=2"))
        assertEquals("$origin/thread.php?fid=1", firstPageUrl("$origin/thread.php?fid=1&page=3#floor"))
        assertTrue(isSamePageList("$origin/read.php?tid=1", "$origin/read.php?page=2&tid=1"))
        assertTrue(isPageContinuation("$origin/message.php", "$origin/message.php?action=receivebox&page=2"))
        assertFalse(isPageContinuation("$origin/message.php?action=sendbox", "$origin/message.php?action=receivebox&page=2"))
        assertTrue(isPageContinuation(first.url, "${first.url}&fpage=1&page=2"))
        assertFalse(isPageContinuation(first.url, "${first.url}&fpage=unknown&page=2"))
    }

    @Test fun searchKeysTrimButNeverCollideOnSeparators() {
        assertEquals(searchCacheKey(" 绯月 ", " 作者 "), searchCacheKey("绯月", "作者"))
        assertNotEquals(searchCacheKey("a|b", "c"), searchCacheKey("a", "b|c"))
    }

    @Test fun appendedMessagesRetainReadStateAndDoNotDuplicate() {
        val a = NativeMessageSummary("$origin/message.php?action=read&mid=1", "一封消息", "发件人", "收件人", "2026-01-01", true)
        val b = a.copy(url = "$origin/message.php?action=read&mid=2", unread = false)
        val page = PageData("$origin/message.php", "私信", true, nativePage = NativeSitePage("私信", messages = listOf(a)))
        val timeline = PageTimeline(page)
        timeline.append(page.copy(url = "$origin/message.php?page=2", nativePage = NativeSitePage("私信", messages = listOf(a.copy(url = "$origin/message.php?mid=1&action=read"), b))), "$origin/message.php?page=2")
        assertEquals(listOf(true, false), timeline.messages.map { it.unread })
        timeline.updateFirst(page.copy(title = "已刷新"))
        assertEquals(2, timeline.messages.size)
        assertEquals(2, timeline.nativePages.size)
    }

    @Test fun repeatedRequestsAreGuardedAndFailuresOnlyRetryExplicitly() = runTest {
        var requests = 0
        var fail = true
        val demo = DemoService(origin)
        val client = object : ForumService by demo {
            override suspend fun fetch(url: String): PageData {
                if (url == first.next) {
                    requests++; delay(100)
                    if (fail) error("测试失败")
                    return first.copy(url = url, posts = listOf(post("2")), next = null)
                }
                return demo.fetch(url)
            }
        }
        val factory = object : ServiceFactory {
            override fun create(origin: String) = client
            override suspend fun catalog(url: String) = error("offline")
        }
        val c = AppController(MemoryStore(), factory, backgroundScope, { _, _ -> })
        runCurrent()
        val state = c.timelineFor(first)
        repeat(10) { c.loadNext(first) }
        advanceTimeBy(101); runCurrent()
        assertEquals(1, requests); assertNotNull(state.failure)
        c.loadNext(first); runCurrent(); assertEquals(1, requests)
        fail = false; c.loadNext(first, retry = true)
        repeat(10) { c.loadNext(first, retry = true) }
        advanceTimeBy(101); runCurrent()
        assertEquals(2, requests)
        assertEquals(listOf("1", "2"), state.posts.map { it.id })
        assertNull(state.next)
        assertSame(state, c.timelineFor(first))
        c.close()
    }

    @Test fun returningToTabsReusesDataAndReselectDoesNotRequest() = runTest {
        val factory = DemoFactory()
        val c = AppController(MemoryStore(), factory, backgroundScope, { _, _ -> })
        runCurrent()
        c.selectTab(HomeTab.MESSAGES); runCurrent()
        c.selectTab(HomeTab.FEED); runCurrent()
        val count = factory.services.getValue(c.origin).fetches
        c.selectTab(HomeTab.MESSAGES); runCurrent()
        c.selectTab(HomeTab.MESSAGES); runCurrent()
        assertEquals(count, factory.services.getValue(c.origin).fetches)
        assertEquals(1, c.reselectCount(HomeTab.MESSAGES))
        c.close()
    }

    private fun factory(client: ForumService) = object : ServiceFactory {
        override fun create(origin: String) = client
        override suspend fun catalog(url: String) = error("offline")
    }

    @Test fun refreshingOnlyReloadsFirstPageWithoutBlockingOrDroppingContentOnFailure() = runTest {
        val demo = DemoService(origin)
        val list = first.copy(url = "$origin/thread.php?fid=1", posts = emptyList(), next = "$origin/thread.php?fid=1&page=2")
        var firstRequests = 0
        var fail = false
        val client = object : ForumService by demo {
            override suspend fun fetch(url: String): PageData = when (url) {
                list.url -> { firstRequests++; delay(100); if (fail) error("测试失败"); list }
                list.next -> list.copy(url = url, topics = listOf(SiteLink("B", "$origin/read.php?tid=2")), next = null)
                else -> demo.fetch(url)
            }
        }
        val c = AppController(MemoryStore(), factory(client), backgroundScope, { _, _ -> })
        runCurrent(); c.ensurePage(list.url); advanceTimeBy(101); runCurrent()
        val state = c.timelineFor(c.page!!)
        c.loadNext(list); runCurrent(); assertEquals(2, state.topics.size)
        fail = true; c.refreshTimeline(list); runCurrent()
        assertTrue(state.refreshing); assertFalse(c.busy); assertEquals(2, state.topics.size)
        advanceTimeBy(101); runCurrent()
        assertEquals(2, state.topics.size); assertNotNull(c.toast)
        fail = false; c.refreshTimeline(list); advanceTimeBy(101); runCurrent()
        assertFalse(state.refreshing); assertEquals(1, state.topics.size); assertEquals(3, firstRequests)
        assertEquals(list.next, state.next)
        c.close()
    }

    @Test fun nextPageLoginWallDoesNotReplaceReaderOrAppendLoginContent() = runTest {
        val demo = DemoService(origin)
        val client = object : ForumService by demo {
            override suspend fun fetch(url: String) = when (url) {
                first.url -> first
                first.next -> PageData("$origin/login.php", "登录", false, authStatus = AuthStatus.LOGIN_REQUIRED)
                else -> demo.fetch(url)
            }
        }
        val c = AppController(MemoryStore(), factory(client), backgroundScope, { _, _ -> })
        runCurrent(); c.ensurePage(first.url); runCurrent()
        c.loadNext(first); runCurrent()
        assertTrue(c.showLogin); assertEquals(first.url, c.page?.url)
        assertEquals(1, c.timelineFor(first).posts.size); assertNull(c.pageFor(first.next!!))
        c.close()
    }

    @Test fun purchasesInAppendedFloorsUseOriginalOfferAndKeepReaderPositionContext() = runTest {
        val demo = DemoService(origin)
        val offer = PurchaseOffer("1:2", "2", "1", "KFB", "$origin/job.php?action=buytopic&tid=1&pid=2", first.next!!)
        val second = first.copy(url = first.next, posts = listOf(post("2").copy(purchases = listOf(offer), purchaseLocked = true)), next = null)
        val client = object : ForumService by demo {
            override suspend fun fetch(url: String) = when (url) { first.url -> first; second.url -> second; else -> demo.fetch(url) }
            override suspend fun purchase(offer: PurchaseOffer) = PurchaseResult.Success(second.copy(posts = listOf(post("2"))), "购买成功")
        }
        val c = AppController(MemoryStore(), factory(client), backgroundScope, { _, _ -> })
        runCurrent(); c.ensurePage(first.url); runCurrent(); c.loadNext(first); runCurrent()
        c.requestPurchase(offer); assertEquals(offer, c.pendingPurchase)
        c.confirmPurchase(); runCurrent()
        assertEquals(first.url, c.page?.url)
        assertTrue(c.timelineFor(first).posts.last().purchases.isEmpty())
        assertEquals(listOf("1", "2"), c.timelineFor(first).posts.map { it.id })
        c.close()
    }

    @Test fun refreshInvalidatesEvenAnUncooperativeOldNextPageResponse() = runTest {
        val demo = DemoService(origin)
        val client = object : ForumService by demo {
            override suspend fun fetch(url: String): PageData = when (url) {
                first.url -> first
                first.next -> withContext(NonCancellable) { delay(500); first.copy(url = url, posts = listOf(post("stale")), next = null) }
                else -> demo.fetch(url)
            }
        }
        val c = AppController(MemoryStore(), factory(client), backgroundScope, { _, _ -> })
        runCurrent(); c.ensurePage(first.url); runCurrent()
        val state = c.timelineFor(first)
        c.loadNext(first); runCurrent()
        c.refreshTimeline(first); runCurrent()
        advanceTimeBy(501); runCurrent()
        assertEquals(listOf("1"), state.posts.map { it.id })
        assertFalse(state.loading); assertFalse(state.refreshing)
        assertEquals(first.next, state.next)
        c.close()
    }

    @Test fun paginationCacheCannotEvictTheRetainedDestinationRoot() = runTest {
        val demo = DemoService(origin)
        val client = object : ForumService by demo {
            override suspend fun fetch(url: String): PageData {
                if (url.startsWith(first.url)) {
                    val number = Regex("page=([0-9]+)").find(url)?.groupValues?.get(1)?.toInt() ?: 1
                    return first.copy(url = url, posts = listOf(post(number.toString())), next = if (number < 40) "${first.url}&page=${number + 1}" else null)
                }
                return demo.fetch(url)
            }
        }
        val c = AppController(MemoryStore(), factory(client), backgroundScope, { _, _ -> })
        runCurrent(); c.ensurePage(first.url); runCurrent()
        val root = c.page!!
        c.ensurePage("index.php"); c.activatePage(c.home!!)
        repeat(39) { c.loadNext(root); runCurrent() }
        assertEquals(40, c.timelineFor(root).posts.size)
        assertSame(root, c.pageFor(first.url))
        assertNull(c.timelineFor(root).next)
        c.close()
    }

    @Test fun serverSwitchDropsTimelinesAndLateResponsesCannotRepopulateThem() = runTest {
        val clients = mutableMapOf<String, ForumService>()
        val factory = object : ServiceFactory {
            override fun create(origin: String): ForumService = clients.getOrPut(origin) {
                val demo = DemoService(origin)
                object : ForumService by demo {
                    override suspend fun fetch(url: String): PageData = when (url) {
                        first.url -> first
                        first.next -> withContext(NonCancellable) { delay(500); first.copy(url = url, posts = listOf(post("old-server")), next = null) }
                        else -> demo.fetch(url)
                    }
                }
            }
            override suspend fun catalog(url: String) = error("offline")
        }
        val c = AppController(MemoryStore(), factory, backgroundScope, { _, _ -> })
        runCurrent(); c.ensurePage(first.url); runCurrent()
        c.loadNext(first); runCurrent()
        c.switchDomain("https://0.kf.miaola.work"); runCurrent()
        advanceTimeBy(501); runCurrent()
        assertNull(c.pageFor(first.url))
        assertTrue(c.page!!.url.startsWith("https://0.kf.miaola.work/"))
        c.close()
    }
}

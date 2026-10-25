@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package work.miaola.kf

import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.flow.collect

class DemoService(override val origin: String) : ForumService {
    var delayMillis = 0L
    var closed = false
    var cleared = false
    var submissions = 0
    var siteSubmissions = 0
    var profileFetches = 0
    var fetches = 0
    var searches = 0
    var executed: SiteDestination? = null
    var executeCount = 0
    var result: SubmitResult = SubmitResult.Success(null, "已提交")
    var homeOverride: PageData? = null
    var topicOverride: PageData? = null
    var fetchFailure: Exception? = null
    val home = PageData("$origin/index.php", "绯月", true, "测试用户",
        boards = listOf("Galgame 综合讨论", "动漫与音乐", "自由讨论区", "个人日记区").mapIndexed { i, name -> SiteLink(name, "$origin/thread.php?fid=${i + 1}") },
        topics = listOf("最近玩到了什么让你念念不忘的游戏？", "把日常里的微小快乐，好好收藏起来", "十月新作闲聊：期待下一段冒险", "来分享一首循环播放的音乐吧").mapIndexed { i, name -> SiteLink(name, "$origin/read.php?tid=${i + 1}", "示例作者 · 10 分钟前", if (i < 2) "最新讨论" else "热门讨论") },
        menu = listOf(SiteLink("我的资料", "$origin/profile.php?action=show&uid=1"), SiteLink("收藏夹", "$origin/profile.php?action=favor"))).let { page ->
            page.copy(feeds = listOf(HomeFeedSection("recommended", "最新被推帖", page.topics.take(2).mapIndexed { i, topic -> topic.copy(group = "最新被推帖", subtitle = "${10 + i}分钟前") }),
                HomeFeedSection("new-topics", "最新主题帖", page.topics.drop(2).mapIndexed { i, topic -> topic.copy(group = "最新主题帖", subtitle = "${20 + i}分钟前") }),
                HomeFeedSection("latest-replies", "最新回复", page.topics.mapIndexed { i, topic -> topic.copy(group = "最新回复", subtitle = "${30 + i}分钟前") })))
        }
    var siteResult: SiteActionResult = SiteActionResult.Response(home)
    var executeResult: SiteActionResult = SiteActionResult.Response(home)
    var dailyClaimed = false
    override suspend fun fetch(url: String): PageData {
        fetches++
        delay(delayMillis)
        fetchFailure?.let { throw it }
        return when {
            url.contains("profile.php?action=show") -> home.copy(url = url, title = "会员资料",
                nativePage = NativeSitePage("会员资料", profile = NativeProfile("测试用户", "$origin/avatar.png", listOf(
                    NativeInfo("会员等级", "初回限定版"), NativeInfo("发帖数量", "12 帖"),
                    NativeInfo("论坛货币", "3456 KFB"), NativeInfo("贡献数值", "7.8"),
                ), headline = NativeInfo("会员等级", "初回限定版"), stats = listOf(
                    NativeInfo("发帖数量", "12 帖"), NativeInfo("论坛货币", "3456 KFB"), NativeInfo("贡献数值", "7.8"),
                )))).also { profileFetches++ }
            url.substringBefore('?').substringAfterLast('/') == "read.php" -> topicOverride ?: PageData("$origin/read.php?tid=1", "最近玩到了什么让你念念不忘的游戏？", true, "测试用户",
                posts = listOf(ForumPost("pidtpc", "示例作者", "", "楼主", "2026-10-24 12:00", "<p>这是离线界面样例，不是真实帖子。</p><p>有时候让人记住的，并不是宏大的结局，而是故事里平凡又温柔的瞬间。</p>", "$origin/post.php?action=quote&tid=1"),
                    ForumPost("pid2", "路过的同好", "", "1楼", "2026-10-24 12:15", "<p>同感。慢下来读故事，也是一件很幸福的事。</p>")), composeUrl = "$origin/post.php?action=reply&fid=1&tid=1")
                    .let { page -> page.copy(posts = page.posts.mapIndexed { index, post ->
                        if (index == 0) post.copy(
                            keywords = listOf(SiteLink("示例关键词", "$origin/guanjianci.php?gjc=x")),
                            meta = listOf(PostMeta("本帖最近评分记录", "<p>[示范]-1 进度分</p>")),
                            replies = listOf(PostReply("引用", "<p>回 13楼(9027) 的帖子</p>")),
                            actions = listOf(SiteDestination("$origin/post.php?action=modify&fid=1&tid=1&pid=tpc", SiteEffect.READ, "编辑帖子")),
                        ) else post
                    }) }
            url.contains("profile.php?action=favor") -> home.copy(url = url, title = "收藏夹",
                topics = listOf(SiteLink("示例收藏主题", "$origin/read.php?tid=9", "", "收藏")))
            url.contains("message.php") -> home.copy(url = "$origin/message.php", title = "私信", topics = listOf(SiteLink("欢迎来到绯月", "$origin/message.php?action=read&mid=1", "示例消息 · SYSTEM", "私信")), composeUrl = "$origin/message.php?action=write")
            url.contains("kf_growup.php") -> home.copy(url = url, title = "签到与成长",
                nativePage = NativeSitePage("签到与成长", growth = NativeGrowth(
                    level = "1", coefficient = "2", experience = "3", progress = 4,
                    claimed = dailyClaimed,
                    claimUrl = if (dailyClaimed) null else "$origin/kf_growup.php?ok=1&safeid=synthetic",
                )))
            url.contains("thread.php") -> home.copy(url = "$origin/thread.php?fid=1", title = "Galgame 综合讨论", composeUrl = "$origin/post.php?fid=1")
            else -> homeOverride ?: home
        }
    }
    override suspend fun login(username: String, password: String) = home
    override suspend fun search(keyword: String, author: String): PageData {
        searches++
        return home.copy(url = "$origin/search.php", title = "搜索结果")
    }
    override suspend fun editor(url: String): EditorForm {
        delay(delayMillis)
        return EditorForm("$origin/post.php?fid=1", "$origin/post.php", mapOf("step" to "2", "action" to "new", "verify" to "synthetic"), "atc_title", categories = listOf(FormOption("0", "选择分类"), FormOption("1", "自由讨论")), categoryRequired = true,
            emoticons = listOf(ForumEmoticon(57, "高兴", "$origin/123/post/smile/em/em48.gif"), ForumEmoticon(41, "大笑", "$origin/123/post/smile/em/em32.gif")), uploadEnabled = true)
    }
    override suspend fun submit(form: EditorForm, values: Map<String, String>): SubmitResult { submissions++; return result }
    override suspend fun submitSite(submission: SiteSubmission): SiteActionResult {
        siteSubmissions++
        delay(delayMillis)
        return siteResult
    }
    override suspend fun executeSite(destination: SiteDestination): SiteActionResult {
        executeCount++
        executed = destination
        if (destination.url.contains("kf_growup.php")) dailyClaimed = true
        delay(delayMillis)
        return executeResult
    }
    override fun encodedLength(value: String) = gbkBytes(value).size
    override fun destination(url: String, title: String) = SiteRoutePolicy(origin).classify(url, title)
    override fun clearSession() { cleared = true }
    override fun close() { closed = true }
}

class DemoFactory : ServiceFactory {
    val services = mutableMapOf<String, DemoService>()
    override fun create(origin: String) = services.getOrPut(origin) { DemoService(origin) }
    override suspend fun catalog(url: String): String = error("offline")
}

class AppControllerTest {
    @Test fun domainSwitchClearsAccountAndCancelsOldWork() = runTest {
        val factory = DemoFactory()
        val store = MemoryStore()
        val c = AppController(store, factory, backgroundScope, browser = { _, _ -> })
        runCurrent()
        assertTrue(c.authenticated)
        val old = factory.services.getValue(c.origin)
        old.delayMillis = 1000
        c.load("read.php?tid=1"); runCurrent()
        c.switchDomain("https://0.kf.miaola.work")
        assertNull(c.home)
        assertNull(c.page)
        assertTrue(old.closed)
        runCurrent()
        assertEquals("https://0.kf.miaola.work/index.php", c.page?.url)
        advanceTimeBy(2000); runCurrent()
        assertEquals("https://0.kf.miaola.work", c.origin)
        assertFalse(c.busy)
        c.close()
    }
    @Test fun uncertainSubmitIsNotRepeated() = runTest {
        val factory = DemoFactory()
        val c = AppController(MemoryStore(), factory, backgroundScope, browser = { _, _ -> })
        runCurrent(); c.openEditor("post.php?fid=1"); runCurrent()
        c.draftTitle = "测试标题"; c.draftContent = "用于测试的正文内容"; c.draftCategory = "1"
        val service = factory.services.getValue(c.origin)
        service.result = SubmitResult.Uncertain("请核对")
        c.submit(); runCurrent()
        assertTrue(c.submitUncertain)
        c.submit(); runCurrent()
        assertEquals(1, service.submissions)
        assertNotNull(c.editor)
        c.close()
    }
    @Test fun invalidEditorDoesNotSubmit() = runTest {
        val factory = DemoFactory()
        val c = AppController(MemoryStore(), factory, backgroundScope, browser = { _, _ -> })
        runCurrent(); c.openEditor("post.php?fid=1"); runCurrent()
        c.draftTitle = "测试标题"; c.draftContent = "用于测试的正文内容"
        c.submit(); runCurrent()
        assertEquals(0, factory.services.getValue(c.origin).submissions)
        assertNotNull(c.error)
        c.close()
    }
    @Test fun readerHistoryAndBookmarksAreDomainScoped() = runTest {
        val store = MemoryStore()
        val c = AppController(store, DemoFactory(), backgroundScope, browser = { _, _ -> })
        runCurrent(); c.load("read.php?tid=1"); runCurrent(); c.toggleBookmark()
        assertEquals(1, c.history.size)
        assertEquals(1, c.bookmarks.size)
        c.switchDomain("https://1.kf.miaola.work"); runCurrent()
        assertTrue(c.bookmarks.isEmpty())
        assertTrue(c.history.isEmpty())
        c.switchDomain("https://kf.miaola.work"); runCurrent()
        assertEquals(1, c.bookmarks.size)
        c.close()
    }
    @Test fun cancelDoesNotHideNewLoadingIndicator() = runTest {
        val factory = DemoFactory()
        val c = AppController(MemoryStore(), factory, backgroundScope, browser = { _, _ -> })
        runCurrent()
        factory.services.getValue(c.origin).delayMillis = 1000
        c.load("read.php?tid=1"); runCurrent()
        c.load("thread.php?fid=1"); runCurrent()
        assertTrue(c.busy)
        advanceTimeBy(1001); runCurrent()
        assertFalse(c.busy)
        assertTrue(c.page?.url.orEmpty().contains("thread.php"))
        c.close()
    }
    @Test fun accountLoadsProfileAvatarAndOpensMessagesAsMessagesTab() = runTest {
        val factory = DemoFactory()
        val c = AppController(MemoryStore(), factory, backgroundScope, browser = { _, _ -> })
        runCurrent()
        assertEquals("${c.origin}/avatar.png", c.accountAvatar)
        assertEquals("7.8", c.accountProfile?.info?.firstOrNull { it.label == "贡献数值" }?.value)
        c.selectTab(HomeTab.ACCOUNT); runCurrent()
        assertEquals(1, factory.services.getValue(c.origin).profileFetches)
        c.navigate("message.php?action=receivebox"); runCurrent()
        assertEquals(HomeTab.MESSAGES, c.tab)
        assertTrue(c.page?.url.orEmpty().contains("message.php"))
        c.requestBack(); runCurrent()
        assertEquals(HomeTab.ACCOUNT, c.tab)
        c.close()
    }

    @Test fun messageTabDoesNotShowHomeFeedWhileLoading() = runTest {
        val factory = DemoFactory()
        val c = AppController(MemoryStore(), factory, backgroundScope, browser = { _, _ -> })
        runCurrent()
        factory.services.getValue(c.origin).delayMillis = 1000
        c.selectTab(HomeTab.MESSAGES)
        assertNull(c.page)
        advanceTimeBy(1001); runCurrent()
        assertTrue(c.page?.url.orEmpty().contains("message.php"))
        c.close()
    }

    @Test fun messageFormSubmitsWithoutAppConfirmationOrPendingGuard() = runTest {
        val factory = DemoFactory()
        val c = AppController(MemoryStore(), factory, backgroundScope, browser = { _, _ -> })
        runCurrent()
        val form = SiteForm("m:1", "批量管理", "${c.origin}/message.php", "${c.origin}/message.php", "POST", emptyList(),
            listOf(SiteField("f", "delid[]", "第一封", SiteFieldKind.CHECKBOX, listOf("1"), listOf(FormOption("1", "第一封")))),
            listOf(SiteSubmit("执行", null, "")))
        c.requestSiteSubmit(form, mapOf("f" to listOf("1")), form.submits.single()); runCurrent()
        assertNull(c.pendingSiteSubmission)
        assertEquals(1, factory.services.getValue(c.origin).siteSubmissions)
        c.close()
    }

    @Test fun loginWallIsNotReusedFromDestinationCache() = runTest {
        val factory = DemoFactory()
        val c = AppController(MemoryStore(), factory, backgroundScope, browser = { _, _ -> })
        runCurrent()
        val service = factory.services.getValue(c.origin)
        service.topicOverride = PageData("${c.origin}/login.php", "登录", false, authStatus = AuthStatus.LOGIN_REQUIRED)
        c.ensurePage("read.php?tid=1"); runCurrent()
        assertTrue(c.showLogin)
        assertNull(c.pageFor("read.php?tid=1"))
        c.close()
    }

    @Test fun siteFavoritesFeedTheBookmarkTab() = runTest {
        val factory = DemoFactory()
        val c = AppController(MemoryStore(), factory, backgroundScope, browser = { _, _ -> })
        runCurrent()
        c.loadFavorites(force = true); runCurrent()
        assertFalse(c.favoritesBusy)
        assertNull(c.favoritesError)
        assertEquals("示例收藏主题", c.siteFavorites?.topics?.single()?.title)
        c.close()
    }

    @Test fun favoriteThreadUsesTheSiteActionAndNeverTouchesLocalBookmarks() = runTest {
        val factory = DemoFactory()
        val c = AppController(MemoryStore(), factory, backgroundScope, browser = { _, _ -> })
        runCurrent()
        val destination = SiteDestination("${c.origin}/kf_tidfavor.php?action=favor&tid=1", SiteEffect.CHANGE, "收藏本帖")
        c.favoriteThread(destination); runCurrent()
        assertEquals(destination.url, factory.services.getValue(c.origin).executed?.url)
        assertFalse(c.siteBusy)
        assertTrue(c.bookmarks.isEmpty())
        c.close()
    }

    @Test fun dailyCheckInRunsOnceAndRefreshesHome() = runTest {
        val factory = DemoFactory()
        val c = AppController(MemoryStore(), factory, backgroundScope, browser = { _, _ -> })
        runCurrent()
        val service = factory.services.getValue(c.origin)
        service.delayMillis = 1000
        val before = service.fetches
        c.claimDailyReward()
        c.claimDailyReward()
        assertTrue(c.dailyBusy)
        advanceTimeBy(5001); runCurrent()
        assertFalse(c.dailyBusy)
        assertTrue(c.dailySigned)
        assertEquals(before + 4, service.fetches)
        assertNotNull(c.toast)
        c.close()
    }

    @Test fun dailyStatusRefreshFollowsTheSiteState() = runTest {
        val factory = DemoFactory()
        val c = AppController(MemoryStore(), factory, backgroundScope, browser = { _, _ -> })
        runCurrent()
        val service = factory.services.getValue(c.origin)
        service.dailyClaimed = true
        c.refreshDailyStatus(); runCurrent()
        assertTrue(c.dailySigned)
        service.dailyClaimed = false
        c.refreshDailyStatus(); runCurrent()
        assertFalse(c.dailySigned)
        c.close()
    }

    @Test fun doubleTapCannotSubmitTheSameSiteActionTwice() = runTest {
        val factory = DemoFactory()
        val c = AppController(MemoryStore(), factory, backgroundScope, browser = { _, _ -> })
        runCurrent()
        val service = factory.services.getValue(c.origin)
        service.delayMillis = 1000
        val form = SiteForm("test", "测试操作", "${c.origin}/profile.php", "${c.origin}/profile.php", "POST",
            emptyList(), emptyList(), listOf(SiteSubmit("提交", null, "")))
        c.requestSiteSubmit(form, emptyMap(), form.submits.single()); runCurrent()
        c.confirmSiteAction()
        c.confirmSiteAction()
        advanceTimeBy(1001); runCurrent()
        assertEquals(1, service.siteSubmissions)
        assertFalse(c.siteBusy)
        c.close()
    }

    @Test fun doubleTapCannotFavoriteTwice() = runTest {
        val factory = DemoFactory()
        val c = AppController(MemoryStore(), factory, backgroundScope, browser = { _, _ -> })
        runCurrent()
        val service = factory.services.getValue(c.origin)
        service.delayMillis = 1000
        val destination = SiteDestination("${c.origin}/kf_tidfavor.php?action=favor&tid=1", SiteEffect.CHANGE, "收藏本帖")
        c.favoriteThread(destination)
        c.favoriteThread(destination)
        advanceTimeBy(1001); runCurrent()
        assertEquals(1, service.executeCount)
        assertFalse(c.siteBusy)
        c.close()
    }

    @Test fun uncertainSiteActionReportsButStaysUsable() = runTest {
        val factory = DemoFactory()
        val c = AppController(MemoryStore(), factory, backgroundScope, browser = { _, _ -> })
        runCurrent()
        val service = factory.services.getValue(c.origin)
        service.siteResult = SiteActionResult.Uncertain("结果不明")
        val form = SiteForm("test", "测试操作", "${c.origin}/profile.php", "${c.origin}/profile.php", "POST",
            emptyList(), emptyList(), listOf(SiteSubmit("提交", null, "")))
        c.requestSiteSubmit(form, emptyMap(), form.submits.single()); runCurrent()
        c.confirmSiteAction(); runCurrent()
        assertNotNull(c.error)
        assertFalse(c.siteBusy)
        service.siteResult = SiteActionResult.Response(service.home)
        c.clearError()
        c.requestSiteSubmit(form, emptyMap(), form.submits.single()); runCurrent()
        c.confirmSiteAction(); runCurrent()
        assertEquals(2, service.siteSubmissions)
        c.close()
    }

    @Test fun readerAvatarProfileIsFetchedOnceAndCached() = runTest {
        val factory = DemoFactory()
        val c = AppController(MemoryStore(), factory, backgroundScope, browser = { _, _ -> })
        runCurrent()
        val author = "${c.origin}/profile.php?action=show&uid=2"
        val before = factory.services.getValue(c.origin).profileFetches
        c.loadAvatar(author); runCurrent()
        assertEquals(before + 1, factory.services.getValue(c.origin).profileFetches)
        assertEquals("${c.origin}/avatar.png", c.avatarFor(author))
        c.loadAvatar(author); runCurrent()
        assertEquals(before + 1, factory.services.getValue(c.origin).profileFetches)
        c.close()
    }

    @Test fun destinationPageSnapshotsSurviveWithoutRefetch() = runTest {
        val factory = DemoFactory()
        val c = AppController(MemoryStore(), factory, backgroundScope, browser = { _, _ -> })
        runCurrent()
        c.ensurePage("read.php?tid=1"); runCurrent()
        val afterReader = factory.services.getValue(c.origin).fetches
        assertEquals("${c.origin}/read.php?tid=1", c.pageFor("read.php?tid=1")?.url)
        c.ensurePage("thread.php?fid=1"); runCurrent()
        assertEquals("${c.origin}/thread.php?fid=1", c.pageFor("thread.php?fid=1")?.url)
        c.ensurePage("read.php?tid=1"); runCurrent()
        assertEquals(afterReader + 1, factory.services.getValue(c.origin).fetches)
        c.activatePage(c.pageFor("read.php?tid=1")!!)
        assertEquals("${c.origin}/read.php?tid=1", c.page?.url)
        c.close()
    }

    @Test fun searchDestinationReusesItsResult() = runTest {
        val factory = DemoFactory()
        val c = AppController(MemoryStore(), factory, backgroundScope, browser = { _, _ -> })
        runCurrent()
        c.ensureSearch("search:compose|", "compose", ""); runCurrent()
        assertEquals(1, factory.services.getValue(c.origin).searches)
        c.ensureSearch("search:compose|", "compose", ""); runCurrent()
        assertEquals(1, factory.services.getValue(c.origin).searches)
        assertEquals("${c.origin}/search.php", c.page?.url)
        c.close()
    }

    @Test fun cachedDestinationDoesNotStealPredictiveBackContext() = runTest {
        val factory = DemoFactory()
        val c = AppController(MemoryStore(), factory, backgroundScope, browser = { _, _ -> })
        runCurrent()
        c.ensurePage("read.php?tid=1"); runCurrent()
        val reader = c.pageFor("read.php?tid=1")!!
        c.activatePage(reader)
        // A composed-but-not-resumed feed destination may warm its cache, but
        // must not change the action page while a back gesture can cancel.
        c.ensurePage("index.php")
        assertEquals(reader.url, c.page?.url)
        c.close()
    }

    @Test fun activePageCanFollowTheCurrentDestination() = runTest {
        val factory = DemoFactory()
        val c = AppController(MemoryStore(), factory, backgroundScope, browser = { _, _ -> })
        runCurrent()
        c.ensurePage("read.php?tid=1"); runCurrent()
        val reader = c.pageFor("read.php?tid=1")!!
        c.ensurePage("thread.php?fid=1"); runCurrent()
        assertNotEquals(reader.url, c.page?.url)
        c.activatePage(reader)
        assertEquals(reader.url, c.page?.url)
        c.close()
    }

    @Test fun hardwareBackAvailabilityIsObservableNotJustPlainListState() = runTest {
        val c = AppController(MemoryStore(), DemoFactory(), backgroundScope, browser = { _, _ -> })
        runCurrent()
        val observed = mutableListOf<Boolean>()
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { snapshotFlow { c.canBack }.collect { observed.add(it) } }
        c.load("read.php?tid=1"); runCurrent(); Snapshot.sendApplyNotifications(); runCurrent()
        assertTrue(c.canBack)
        assertEquals(listOf(false, true), observed)
        c.requestBack(); Snapshot.sendApplyNotifications(); runCurrent()
        assertFalse(c.canBack)
        assertTrue(c.page?.url.orEmpty().endsWith("index.php"))
        assertEquals(listOf(false, true, false), observed)
        observer.cancel(); c.close()
    }
    @Test fun unknownServerPageDoesNotLogOutVerifiedAccount() = runTest {
        val factory = DemoFactory()
        val c = AppController(MemoryStore(), factory, backgroundScope, browser = { _, _ -> })
        runCurrent()
        val original = c.home
        factory.services.getValue(c.origin).homeOverride = PageData("${c.origin}/index.php", "临时页面", false)
        c.resumeSession(); runCurrent()
        assertEquals(original, c.home)
        assertTrue(c.authenticated)
        assertFalse(c.showLogin)
        assertNull(c.sessionNotice)
        c.close()
    }
    @Test fun networkFailureDoesNotDeleteOrHideAccount() = runTest {
        val factory = DemoFactory()
        val c = AppController(MemoryStore(), factory, backgroundScope, browser = { _, _ -> })
        runCurrent()
        factory.services.getValue(c.origin).fetchFailure = java.io.IOException("暂时无法连接")
        c.resumeSession(); runCurrent()
        assertTrue(c.authenticated); assertFalse(c.showLogin); assertNull(c.error)
        assertFalse(factory.services.getValue(c.origin).cleared)
        c.close()
    }
    @Test fun actualLoginPageDoesRequestLogin() = runTest {
        val factory = DemoFactory()
        val c = AppController(MemoryStore(), factory, backgroundScope, browser = { _, _ -> })
        runCurrent()
        factory.services.getValue(c.origin).homeOverride = PageData("${c.origin}/login.php", "登录", false, authStatus = AuthStatus.LOGIN_REQUIRED)
        c.resumeSession(); runCurrent()
        assertFalse(c.authenticated); assertTrue(c.showLogin)
        c.close()
    }
    @Test fun returningFromCompatibilityPageRefreshesNativeReader() = runTest {
        val factory = DemoFactory()
        val c = AppController(MemoryStore(), factory, backgroundScope, browser = { _, _ -> })
        runCurrent(); c.load("read.php?tid=1"); runCurrent()
        val service = factory.services.getValue(c.origin)
        service.topicOverride = c.page!!.copy(title = "已刷新内容")
        c.resumeSession(); runCurrent()
        assertEquals("已刷新内容", c.page?.title)
        assertTrue(c.canBack)
        c.close()
    }
    @Test fun loginAfterExpirationReturnsToOriginalThreadInsteadOfHome() = runTest {
        val factory = DemoFactory()
        val c = AppController(MemoryStore(), factory, backgroundScope, browser = { _, _ -> })
        runCurrent()
        val service = factory.services.getValue(c.origin)
        service.topicOverride = PageData("${c.origin}/login.php", "登录", false, authStatus = AuthStatus.LOGIN_REQUIRED)
        c.load("read.php?tid=1"); runCurrent()
        assertTrue(c.showLogin); assertFalse(c.authenticated)
        service.topicOverride = null
        c.login("测试用户", "synthetic-password"); runCurrent()
        assertTrue(c.page?.url.orEmpty().contains("read.php?tid=1"))
        assertFalse(c.showLogin); assertTrue(c.canBack)
        c.requestBack()
        assertTrue(c.page?.url.orEmpty().endsWith("index.php"))
        c.close()
    }
    @Test fun backDuringThreadLoadingCancelsNavigationInsteadOfExitingApp() = runTest {
        val factory = DemoFactory()
        val c = AppController(MemoryStore(), factory, backgroundScope, browser = { _, _ -> })
        runCurrent()
        factory.services.getValue(c.origin).delayMillis = 1000
        c.load("read.php?tid=1")
        assertTrue(c.canBack, "Back must be available before network completion")
        runCurrent(); c.requestBack(); advanceTimeBy(1001); runCurrent()
        assertTrue(c.page?.url.orEmpty().endsWith("index.php")); assertFalse(c.canBack)
        assertFalse(c.busy); c.close()
    }
    @Test fun backDuringEditorLoadingStaysOnThreadAndPickerBackKeepsDraft() = runTest {
        val factory = DemoFactory()
        val c = AppController(MemoryStore(), factory, backgroundScope, browser = { _, _ -> })
        runCurrent(); c.load("read.php?tid=1"); runCurrent()
        val service = factory.services.getValue(c.origin)
        service.delayMillis = 1000
        c.openEditor("post.php?fid=1"); runCurrent(); c.requestBack(); advanceTimeBy(1001); runCurrent()
        assertNull(c.editor); assertTrue(c.page?.url.orEmpty().contains("read.php"))
        service.delayMillis = 0
        c.openEditor("post.php?fid=1"); runCurrent(); c.draftContent = "保留草稿"
        c.showEmoticonPicker = true; c.requestBack()
        assertFalse(c.showEmoticonPicker); assertNotNull(c.editor); assertEquals("保留草稿", c.draftContent)
        c.close()
    }
}

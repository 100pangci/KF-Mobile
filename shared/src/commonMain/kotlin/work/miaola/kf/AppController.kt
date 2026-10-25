package work.miaola.kf

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class AppController(
    val store: KeyValueStore,
    private val factory: ServiceFactory,
    private val scope: CoroutineScope,
    private val browser: (String, String) -> Unit,
    defaultConfigUrl: String = "",
) {
    private val domains = DomainRepository(store, factory)
    var catalog by mutableStateOf(domains.local()); private set
    var configUrl by mutableStateOf(store.get("config_url") ?: defaultConfigUrl); private set
    var origin by mutableStateOf(runCatching { normalizeOrigin(store.get("selected_origin") ?: catalog.catalog.domains.first { it.id == catalog.catalog.defaultDomainId }.origin) }.getOrDefault("https://kf.miaola.work")); private set
    var theme by mutableStateOf(runCatching { ThemeMode.valueOf(store.get("theme") ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM)); private set
    var page by mutableStateOf<PageData?>(null); private set
    var home by mutableStateOf<PageData?>(null); private set
    var tab by mutableStateOf(HomeTab.FEED); private set
    var busy by mutableStateOf(false); private set
    var loadingEditor by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var toast by mutableStateOf<String?>(null); private set
    var showSettings by mutableStateOf(false)
    var showLogin by mutableStateOf(false)
    var editor by mutableStateOf<EditorForm?>(null); private set
    var draftTitle by mutableStateOf("")
    var draftContent by mutableStateOf("")
    var draftRecipient by mutableStateOf("")
    var draftCategory by mutableStateOf("")
    var draftKeywords by mutableStateOf("")
    var submitUncertain by mutableStateOf(false); private set
    var confirmDiscard by mutableStateOf(false)
    var pendingPurchase by mutableStateOf<PurchaseOffer?>(null); private set
    var purchaseBusy by mutableStateOf(false); private set
    var uncertainPurchases by mutableStateOf<Set<String>>(emptySet()); private set
    var sessionNotice by mutableStateOf<String?>(null); private set
    var showEmoticonPicker by mutableStateOf(false)
    var showSiteTools by mutableStateOf(false)
    var showTopicFilters by mutableStateOf(false)
    var dailyBusy by mutableStateOf(false); private set
    var dailySigned by mutableStateOf(store.get("daily_signed@$origin") == localDayKey()); private set
    var pendingNativePage by mutableStateOf<String?>(null); private set
    var pendingSiteDestination by mutableStateOf<SiteDestination?>(null); private set
    var pendingSiteSubmission by mutableStateOf<SiteSubmission?>(null); private set
    var siteBusy by mutableStateOf(false); private set
    var managementRevision by mutableStateOf(0); private set
    var filePickerRequest by mutableStateOf<FilePickerRequest?>(null); private set
    var downloadRequest by mutableStateOf<NativeDownloadRequest?>(null); private set
    var editorFiles by mutableStateOf<List<LocalUpload>>(emptyList()); private set
    var formFiles by mutableStateOf<Map<String, List<LocalUpload>>>(emptyMap()); private set
    var gallery by mutableStateOf<ImageGallery?>(null); private set
    var accountAvatar by mutableStateOf<String?>(null); private set
    var accountProfile by mutableStateOf<NativeProfile?>(null); private set
    var fontScale by mutableStateOf(store.get("font_scale")?.toFloatOrNull()?.coerceIn(0.85f, 1.4f) ?: 1f); private set
    var bookmarks by mutableStateOf(readBookmarks()); private set
    var history by mutableStateOf(readHistory()); private set
    var siteFavorites by mutableStateOf<PageData?>(null); private set
    var favoritesBusy by mutableStateOf(false); private set
    var favoritesError by mutableStateOf<String?>(null); private set
    var lastSearchKeyword by mutableStateOf(""); private set
    var lastSearchAuthor by mutableStateOf(""); private set
    private var service = factory.create(origin)
    private var requestJob: Job? = null
    private var requestVersion = 0
    private var loadingUrl: String? = null
    private var catalogJob: Job? = null
    private var favoritesJob: Job? = null
    private var dailyStatusJob: Job? = null
    private var purchaseJob: Job? = null
    private var siteJob: Job? = null
    private var avatarJob: Job? = null
    private val profileAvatars = mutableStateMapOf<String, String>()
    private val profileAvatarLoading = mutableSetOf<String>()
    private val profileAvatarLimit = Semaphore(2)
    private var loginReturnUrl: String? = null
    private var nextUploadSlot = 1
    private var messageListUrl: String? = null
    private val backStack = mutableStateListOf<Pair<PageData?, HomeTab>>()
    // Destinations keep their own page snapshot so predictive back can finish
    // over the correct page without re-fetching the thread.
    private val pageCache = mutableStateMapOf<String, PageData>()
    private val timelines = mutableMapOf<String, PageTimeline>()
    private val timelineJobs = mutableMapOf<String, Job>()
    private val tabReselections = mutableStateMapOf<HomeTab, Int>()
    fun reselectTab(tab: HomeTab) { tabReselections[tab] = reselectCount(tab) + 1 }
    fun reselectCount(tab: HomeTab) = tabReselections[tab] ?: 0

    fun timelineFor(first: PageData): PageTimeline {
        timelines[first.url]?.let { return it }
        val state = PageTimeline(first)
        timelines[first.url] = state
        // Bound destination retention, not the number of pages in the active
        // reader. Appending 40 pages must not evict that reader's first page.
        while (timelines.size > 32) {
            val oldest = timelines.keys.firstOrNull { it != first.url && it != page?.url && it != home?.url && it != siteFavorites?.url } ?: break
            timelineJobs.remove(oldest)?.cancel()
            timelines.remove(oldest)
        }
        return state
    }

    private fun safeTimelineUrl(url: String): Boolean {
        val destination = resolve(url)
        return destination.effect == SiteEffect.READ ||
            (destination.effect == SiteEffect.CHANGE && destination.url.startsWith("$origin/search.php?") &&
                work.miaola.kf.ui.KfDestinations.isSearchNavigation(destination.url))
    }

    fun loadNext(first: PageData, retry: Boolean = false) {
        if (purchaseBusy || siteBusy || pendingPurchase != null) return
        val state = timelineFor(first)
        val next = state.next ?: return
        if (!state.canLoad(next) || (state.failure != null && !retry)) return
        // Search result URLs are GETs but intentionally not classified as general
        // read navigation. Only a page-number change on the issued result is safe.
        if (!isPageContinuation(state.lastUrl, next) || !safeTimelineUrl(state.first.url) || !safeTimelineUrl(next)) {
            state.failure = "无法继续加载，请刷新后重试"
            return
        }
        val client = service
        val expectedOrigin = origin
        state.loading = true
        state.failure = null
        val requestId = ++state.requestId
        timelineJobs[first.url] = scope.launch {
            try {
                val result = client.fetch(next)
                if (!isActive || origin != expectedOrigin || requestId != state.requestId) return@launch
                if (handleTimelineLogin(result, first.url)) {
                    state.failure = "请登录后重试"
                } else if (!isSamePageList(state.first.url, result.url) || !isPageContinuation(state.lastUrl, result.url) ||
                    Regex("[?&]page=([0-9]+)").find(next)?.groupValues?.get(1) != Regex("[?&]page=([0-9]+)").find(result.url)?.groupValues?.get(1)) {
                    state.failure = "内容已变化，请刷新后重试"
                } else {
                    refreshPurchaseState(result)
                    rememberCachedPage(result, next)
                    state.append(result, next)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (origin == expectedOrigin && requestId == state.requestId) state.failure = friendlyError(e) }
            finally { if (requestId == state.requestId) state.loading = false }
        }
    }

    fun refreshTimeline(first: PageData, search: Pair<String, String>? = null) {
        if (!safeTimelineUrl(first.url)) { toast = "请重新打开页面后刷新"; return }
        val state = timelineFor(first)
        if (state.refreshing || purchaseBusy || siteBusy) return
        val requestId = ++state.requestId
        timelineJobs.remove(first.url)?.cancel()
        state.loading = false
        state.refreshing = true
        val client = service
        val expectedOrigin = origin
        timelineJobs[first.url] = scope.launch {
            try {
                val result = if (search != null) client.search(search.first, search.second)
                    else client.fetch(if (first.posts.isEmpty()) firstPageUrl(first.url) else first.url)
                if (!isActive || origin != expectedOrigin || requestId != state.requestId || handleTimelineLogin(result, first.url)) return@launch
                if (!safeTimelineUrl(result.url) || (search == null && !isSamePageList(first.url, result.url))) {
                    toast = "内容已变化，请重新打开页面"; return@launch
                }
                rememberCachedPage(result, first.url)
                // Search receipts can issue a new result URL. Keep this screen's
                // aggregation and search alias attached to the new receipt.
                pageCache.entries.filter { it.key.startsWith("search:") && it.value.url == first.url }
                    .map { it.key }.forEach { pageCache[it] = result }
                timelines[result.url] = state
                state.reset(result)
                if (home?.url == first.url) home = result
                if (siteFavorites?.url == first.url) siteFavorites = result
                if (page?.url == first.url) page = result
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (origin == expectedOrigin && requestId == state.requestId) toast = friendlyError(e) }
            finally { if (requestId == state.requestId) state.refreshing = false }
        }
    }

    private fun handleTimelineLogin(result: PageData, returnUrl: String): Boolean {
        if (result.authStatus != AuthStatus.LOGIN_REQUIRED) return false
        loginReturnUrl = loginDestination(returnUrl)
        home = home?.copy(authenticated = false, authStatus = AuthStatus.LOGIN_REQUIRED)
        showLogin = true
        sessionNotice = "登录已失效，请重新登录"
        return true
    }

    private fun clearTimelines() {
        timelineJobs.values.forEach { it.cancel() }
        timelineJobs.clear(); timelines.clear()
    }
    val authenticated get() = home?.authenticated == true
    val editorDirty: Boolean get() {
        val form = editor ?: return false
        return draftTitle != form.fields[form.titleField].orEmpty() ||
            draftContent != form.fields[form.contentField].orEmpty() ||
            draftRecipient != form.fields[form.recipientField].orEmpty() ||
            draftCategory != form.fields["p_type"].orEmpty() ||
            draftKeywords != form.fields["diy_guanjianci"].orEmpty() || editorFiles.isNotEmpty()
    }
    val canBack get() = gallery != null || showSiteTools || pendingSiteDestination != null || pendingSiteSubmission != null || loadingEditor || pendingPurchase != null || editor != null || showSettings || showLogin || backStack.isNotEmpty()

    init { store.put("selected_origin", origin); refreshCatalog(); load("index.php", push = false) }

    private fun work(editorRequest: Boolean = false, task: suspend (() -> Boolean) -> Unit) {
        val version = ++requestVersion
        val expectedOrigin = origin
        loadingEditor = editorRequest
        requestJob?.cancel()
        requestJob = scope.launch {
            busy = true; error = null
            try { task { version == requestVersion && expectedOrigin == origin && isActive } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (version == requestVersion) error = friendlyError(e) }
            finally { if (version == requestVersion) { busy = false; loadingEditor = false; loadingUrl = null } }
        }
    }

    private fun cancelRequest() { requestVersion++; requestJob?.cancel(); busy = false; loadingEditor = false; loadingUrl = null }

    private fun rememberNavigationSource() {
        val source = page to tab
        if (!busy || backStack.lastOrNull() != source) backStack.add(source)
    }

    private fun rememberCachedPage(result: PageData, requestedUrl: String? = null, allowLoginWall: Boolean = false) {
        // A login wall is not a destination snapshot: retry after the session
        // is restored instead of showing the wall again from cache.
        val sourcePath = (requestedUrl ?: result.url).substringBefore('?').substringAfterLast('/')
        if (!allowLoginWall && result.authStatus == AuthStatus.LOGIN_REQUIRED && sourcePath != "login.php") return
        pageCache[result.url] = result
        timelines.values.distinct().forEach { it.updatePage(result) }
        if (requestedUrl != null && requestedUrl != result.url) pageCache[requestedUrl] = result
        while (pageCache.size > 32) {
            val current = page?.url
            val oldest = pageCache.keys.firstOrNull {
                it != current && it != home?.url && !it.contains("search.php") && !it.startsWith("search:")
            }
            if (oldest == null) break
            pageCache.remove(oldest)
        }
    }

    /** A destination's own page, retained across predictive back and tab switches. */
    fun pageFor(url: String): PageData? = pageCache[url] ?: pageCache[url.substringBefore('#')]
        ?: timelines[url.substringBefore('#')]?.first
        ?: timelines[resolve(url).url.substringBefore('#')]?.first

    /**
     * Loads a destination page once. Returning to a destination reuses its
     * snapshot instead of issuing another request or resetting scroll state.
     */
    fun ensurePage(url: String, force: Boolean = false) {
        if (purchaseBusy || siteBusy) return
        if (!force && pageFor(url) != null) return
        if (!force && busy && loadingUrl == url) return
        load(url, push = false)
    }

    fun activatePage(active: PageData) {
        if (page?.url != active.url || page !== active) page = active
        rememberMailbox(active)
    }

    private fun rememberMailbox(active: PageData) {
        if (active.url.substringBefore('?').endsWith("/message.php") &&
            (work.miaola.kf.ui.KfDestinations.fromUrl(active.url) == work.miaola.kf.ui.KfDestinationKind.MESSAGES ||
                active.url.contains("action=scout") && active.nativePage?.hasMessageList == true))
            messageListUrl = active.url
    }

    fun resolve(url: String, title: String = ""): SiteDestination = service.destination(url, title)

    fun dismissLogin() {
        showLogin = false
        error = null
    }

    fun load(url: String = page?.url ?: "index.php", push: Boolean = true) {
        if (purchaseBusy || siteBusy) return
        val route = service.destination(url)
        if (route.effect != SiteEffect.READ) { openSiteDestination(route); return }
        if (push) rememberNavigationSource()
        loadingUrl = url
        work {
            val result = service.fetch(url)
            if (!it()) return@work
            refreshPurchaseState(result)
            page = result
            rememberMailbox(result)
            rememberCachedPage(result, url)
            showSiteTools = false
            if (url.substringBefore('?').substringAfterLast('/') == "index.php" && result.authStatus != AuthStatus.UNKNOWN) home = result
            when (result.authStatus) {
                AuthStatus.AUTHENTICATED -> {
                    sessionNotice = null; showLogin = false
                    if (home?.authenticated == false) home = home?.copy(authenticated = true, authStatus = AuthStatus.AUTHENTICATED)
                    if (accountAvatar == null) refreshAccountAvatar()
                }
                AuthStatus.LOGIN_REQUIRED -> {
                    loginReturnUrl = loginDestination(url)
                    home = home?.copy(authenticated = false, authStatus = AuthStatus.LOGIN_REQUIRED)
                    showLogin = true; sessionNotice = "登录已失效，请重新登录"
                }
                AuthStatus.UNKNOWN -> Unit
            }
            if (result.posts.isNotEmpty()) {
                history = (listOf(SiteLink(result.title, result.url)) + history.filterNot { it.url == result.url }).take(80)
                saveLinks("history", history)
            }
        }
    }

    fun navigate(url: String) {
        if (siteBusy || purchaseBusy) return
        val destination = service.destination(url)
        if (destination.effect != SiteEffect.READ) { openSiteDestination(destination); return }
        val path = url.substringBefore('?').substringAfterLast('/')
        when {
            path == "post.php" || (path == "message.php" && url.contains("action=write")) -> openEditor(url)
            path in listOf("read.php", "thread.php", "index.php") -> load(url)
            path == "message.php" && !url.contains("action=read") && !url.contains("action=del") -> { rememberNavigationSource(); tab = HomeTab.MESSAGES; page = null; load(url, push = false) }
            path == "profile.php" && url.contains("action=favor") -> load(url)
            else -> openSiteDestination(service.destination(url))
        }
    }

    fun setActiveTab(value: HomeTab) {
        if (tab != value) tab = value
    }

    fun selectTab(value: HomeTab) {
        if (purchaseBusy || siteBusy) return
        if (tab == value) { reselectTab(value); return }
        cancelRequest(); error = null
        backStack.clear(); tab = value; editor = null; showSettings = false; showSiteTools = false
        when (value) {
            HomeTab.FEED -> { page = home; if (home == null) load("index.php", false) }
            HomeTab.BOARDS -> { page = home; if (home == null) load("index.php", false) }
            HomeTab.MESSAGES -> { page = pageFor("message.php"); if (page == null) load("message.php", false) }
            HomeTab.ACCOUNT -> { page = home; refreshAccountAvatar() }
        }
    }

    fun refreshAccountAvatar(force: Boolean = false) {
        if (!authenticated) return
        if (!force && accountProfile != null) return
        if (avatarJob?.isActive == true) return
        val profileUrl = home?.menu?.firstOrNull { it.url.contains("profile.php?action=show") }?.url ?: return
        val expectedOrigin = origin
        avatarJob = scope.launch {
            val profile = try {
                service.fetch(profileUrl).nativePage?.profile
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (origin == expectedOrigin && authenticated && profile != null) {
                accountProfile = profile
                if (!profile.avatar.isNullOrBlank()) accountAvatar = profile.avatar
            }
        }
    }

    /** Reader avatars are read-only profile lookups, capped and cached in memory. */
    fun avatarFor(authorUrl: String): String? = profileAvatars[avatarKey(authorUrl)]

    fun loadAvatar(authorUrl: String) {
        if (authorUrl.isBlank() || !authorUrl.startsWith(origin)) return
        val key = avatarKey(authorUrl)
        if (profileAvatars.containsKey(key) || !profileAvatarLoading.add(key)) return
        val expectedOrigin = origin
        val client = service
        scope.launch {
            try {
                val avatar = try {
                    profileAvatarLimit.withPermit {
                        client.fetch(authorUrl).nativePage?.profile?.avatar.orEmpty()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    ""
                }
                if (origin == expectedOrigin && avatar.isNotBlank()) profileAvatars[key] = avatar
            } finally {
                profileAvatarLoading.remove(key)
            }
        }
    }

    private fun avatarKey(authorUrl: String): String =
        Regex("[?&]uid=([0-9]+)").find(authorUrl)?.groupValues?.get(1)?.let { "uid:$it" } ?: authorUrl

    private fun favoritesUrl(): String =
        home?.menu?.firstOrNull { it.url.contains("profile.php") && it.url.contains("action=favor") }?.url
            ?: "$origin/profile.php?action=favor"

    /** Site favorites power the bookmark tab; local-only bookmarks are no longer exposed. */
    fun loadFavorites(force: Boolean = false) {
        if (!authenticated) {
            siteFavorites = null
            favoritesError = null
            return
        }
        if (!force && siteFavorites != null) return
        if (favoritesJob?.isActive == true) return
        val expectedOrigin = origin
        val client = service
        favoritesBusy = true
        favoritesError = null
        favoritesJob = scope.launch {
            try {
                val result = client.fetch(favoritesUrl())
                if (origin == expectedOrigin) {
                    if (result.authStatus == AuthStatus.LOGIN_REQUIRED) {
                        favoritesError = "登录状态已失效，请重新登录"
                        showLogin = true
                    } else {
                        siteFavorites = result
                        rememberCachedPage(result)
                        timelines[result.url]?.reset(result)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (origin == expectedOrigin) favoritesError = friendlyError(e)
            } finally {
                if (origin == expectedOrigin) favoritesBusy = false
            }
        }
    }

    /** Read the same claimed marker the website uses, without claiming. */
    fun refreshDailyStatus() {
        if (!authenticated || dailyBusy || dailyStatusJob?.isActive == true) return
        val expectedOrigin = origin
        val client = service
        dailyStatusJob = scope.launch {
            try {
                val page = client.fetch("kf_growup.php")
                if (origin != expectedOrigin || !authenticated) return@launch
                if (page.authStatus == AuthStatus.LOGIN_REQUIRED) {
                    dailySigned = false
                    return@launch
                }
                val claimed = page.nativePage?.growth?.claimed ?: return@launch
                dailySigned = claimed
                if (claimed) store.put("daily_signed@$origin", localDayKey())
                else store.remove("daily_signed@$origin")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep the last known state when the status cannot be read.
            }
        }
    }

    /**
     * The site's "神秘奖励" page is the daily check-in. Open it in the
     * background, submit an obvious claim form when present, refresh the
     * account balance, and never navigate to the color-customisation page.
     */
    fun claimDailyReward() {
        if (dailyBusy || busy || siteBusy || purchaseBusy) return
        if (dailySigned) {
            toast = "今天已经签过了，明天再来"
            return
        }
        if (!authenticated) {
            loginReturnUrl = page?.url?.let(::loginDestination)
            showLogin = true
            return
        }
        dailyBusy = true
        error = null
        val expectedOrigin = origin
        val client = service
        val beforeKfb = kfbAmount(accountProfile)
        scope.launch {
            try {
                val page = try {
                    client.fetch("kf_growup.php")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = friendlyError(e)
                    return@launch
                }
                if (origin != expectedOrigin || !authenticated) return@launch
                if (page.authStatus == AuthStatus.LOGIN_REQUIRED) {
                    dailySigned = false
                    loginReturnUrl = page.url.takeIf { it.contains("kf_growup.php") }
                    showLogin = true
                    return@launch
                }
                val growth = page.nativePage?.growth
                var notice = page.notice
                var claimed = growth?.claimed == true
                val claimUrl = growth?.claimUrl
                if (!claimed && claimUrl != null) {
                    val destination = client.destination(claimUrl)
                    when (destination.effect) {
                        SiteEffect.READ -> client.fetch(destination.url)
                        SiteEffect.CHANGE -> when (val result = client.executeSite(destination)) {
                            is SiteActionResult.Response -> notice = result.page.notice.ifBlank { notice }
                            is SiteActionResult.Rejected -> { error = result.message; return@launch }
                            is SiteActionResult.Uncertain -> { error = result.message; return@launch }
                        }
                        else -> { error = "签到入口暂不支持"; return@launch }
                    }
                } else if (!claimed && growth?.claimForm == true) {
                    val form = page.nativePage.forms.firstOrNull { it.sourceUrl.contains("kf_growup.php") }
                    val submit = form?.submits?.firstOrNull()
                    if (form == null || submit == null) {
                        error = "未找到签到入口"
                        return@launch
                    }
                    val values = buildSiteFormValues(form, form.fields.associate { it.id to it.initial }, submit)
                    when (val result = client.submitSite(SiteSubmission(form, values, emptyList(), submit))) {
                        is SiteActionResult.Response -> notice = result.page.notice.ifBlank { notice }
                        is SiteActionResult.Rejected -> { error = result.message; return@launch }
                        is SiteActionResult.Uncertain -> { error = result.message; return@launch }
                    }
                }
                if (!claimed) {
                    // The reward page renders a fresh status after the claim.
                    val statusPage = try { client.fetch("kf_growup.php") } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                    if (statusPage != null) {
                        notice = statusPage.notice.ifBlank { notice }
                        claimed = statusPage.nativePage?.growth?.claimed == true
                    }
                }
                if (origin != expectedOrigin || !authenticated) return@launch
                if (claimed) {
                    dailySigned = true
                    store.put("daily_signed@$origin", localDayKey())
                } else {
                    error = "未获取到签到状态，请稍后重试"
                    return@launch
                }
                val fresh = client.fetch("index.php")
                if (origin != expectedOrigin || !authenticated) return@launch
                home = fresh
                rememberCachedPage(fresh, "index.php")
                val profileUrl = fresh.menu.firstOrNull { it.url.contains("profile.php?action=show") }?.url
                val profile = try {
                    profileUrl?.let { client.fetch(it).nativePage?.profile }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (origin != expectedOrigin) return@launch
                if (profile != null) {
                    accountProfile = profile
                    if (!profile.avatar.isNullOrBlank()) accountAvatar = profile.avatar
                }
                val afterKfb = kfbAmount(profile)
                toast = when {
                    notice.isNotBlank() -> notice
                    beforeKfb > 0 && afterKfb > beforeKfb -> "签到成功，KFB +${afterKfb - beforeKfb}"
                    else -> "签到完成"
                }
                sessionNotice = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = friendlyError(e)
            } finally {
                dailyBusy = false
            }
        }
    }

    private fun kfbAmount(profile: NativeProfile?): Long = profile?.info
        ?.firstOrNull { info -> info.value.contains("KFB", true) }
        ?.value?.replace(",", "")?.let { Regex("[0-9]+").find(it)?.value?.toLongOrNull() }
        ?: 0L

    fun favoriteThread(destination: SiteDestination?) {
        if (destination == null || purchaseBusy || siteBusy || dailyBusy) return
        if (SiteActionRisk.needsConfirmation(destination)) { pendingSiteDestination = destination; return }
        if (!authenticated) {
            loginReturnUrl = page?.url?.let(::loginDestination)
            showLogin = true
            return
        }
        if (destination.effect != SiteEffect.CHANGE) return
        siteBusy = true
        error = null
        val expectedOrigin = origin
        val client = service
        val threadUrl = page?.takeIf { it.posts.isNotEmpty() || it.url.contains("read.php") }?.url
        siteJob = scope.launch {
            try {
                when (val result = client.executeSite(destination)) {
                    is SiteActionResult.Response -> {
                        siteFavorites = null
                        if (result.page.authStatus == AuthStatus.LOGIN_REQUIRED || result.page.url.contains("login.php")) {
                            showLogin = true
                            error = "登录状态已失效，请重新登录"
                        } else {
                            toast = result.page.notice.ifBlank { SiteActionRisk.successMessage(destination) }
                            // Re-read the reader so its favorite entry matches the
                            // site immediately (the site swaps add/remove links).
                            if (threadUrl != null && page?.url == threadUrl) {
                                val refreshed = try {
                                    client.fetch(threadUrl)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Exception) {
                                    null
                                }
                                if (origin == expectedOrigin && refreshed != null && page?.url == threadUrl) {
                                    refreshPurchaseState(refreshed)
                                    page = refreshed
                                    rememberCachedPage(refreshed, threadUrl)
                                }
                            }
                        }
                    }
                    is SiteActionResult.Rejected -> error = result.message
                    is SiteActionResult.Uncertain -> error = result.message
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (origin == expectedOrigin) error = friendlyError(e)
            } finally {
                siteBusy = false
            }
        }
    }

    private fun clearFavorites() {
        favoritesJob?.cancel()
        siteFavorites = null
        favoritesError = null
        favoritesBusy = false
    }

    fun login(username: String, password: String) {
        if (busy) return
        if (username.isBlank() || password.isEmpty()) { error = "请输入用户名和密码"; return }
        work {
            val result = service.login(username.trim(), password)
            if (!it()) return@work
            val destination = loginReturnUrl
            loginReturnUrl = null
            pageCache.clear()
            clearTimelines()
            home = result
            rememberCachedPage(result, "index.php")
            showLogin = false; sessionNotice = null; toast = "登录成功"
            accountAvatar = null; accountProfile = null; refreshAccountAvatar()
            if (destination == null) { page = result; backStack.clear(); tab = HomeTab.FEED }
            else {
                if (backStack.isEmpty()) backStack.add(result to HomeTab.FEED)
                val restored = service.fetch(destination)
                if (!it()) return@work
                refreshPurchaseState(restored); page = restored; rememberCachedPage(restored, destination)
            }
        }
    }

    fun resumeSession() {
        // Background session checks are silent: a flaky connection must not
        // look like a logout or throw a raw socket error at the reader.
        work {
            val result = try {
                service.fetch("index.php")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@work
            }
            if (!it()) return@work
            if (result.authStatus == AuthStatus.UNKNOWN) {
                return@work
            }
            if (!result.authenticated) loginReturnUrl = page?.url?.let(::loginDestination)
            home = result
            rememberCachedPage(result, "index.php")
            if (showLogin || page == null || page?.url?.substringBefore('?')?.endsWith("index.php") == true) page = result
            showLogin = !result.authenticated
            sessionNotice = if (result.authenticated) null else "登录已失效，请重新登录"
            if (result.authenticated && accountAvatar == null) refreshAccountAvatar()
            if (result.authenticated && editor == null && page?.posts?.isNotEmpty() == true) {
                val refreshed = service.fetch(page!!.url)
                if (!it()) return@work
                refreshPurchaseState(refreshed)
                page = refreshed
                rememberCachedPage(refreshed)
            }
        }
    }

    private fun loginDestination(url: String): String? = url.takeIf {
        it.substringBefore('?').substringAfterLast('/') in listOf("read.php", "thread.php", "message.php", "profile.php")
            && !it.contains("action=del") && !it.contains("action=write")
    }

    private fun visiblePosts(): List<ForumPost> = page?.let { timelines[it.url]?.posts ?: it.posts }.orEmpty()

    private fun refreshPurchaseState(result: PageData) {
        val previous = visiblePosts().flatMap { it.purchases }
        previous.forEach { offer ->
            result.posts.firstOrNull { isSamePageList(offer.threadUrl, result.url) && it.id == offer.postId && it.purchases.isEmpty() && !it.purchaseLocked }?.let {
                store.remove(purchasePendingKey(origin, offer.id))
            }
        }
        val offers = visiblePosts().filterNot { old -> result.posts.any { it.id == old.id } }.flatMap { it.purchases } + result.posts.flatMap { it.purchases }
        uncertainPurchases = offers.filter { store.get(purchasePendingKey(origin, it.id)) != null }.map { it.id }.toSet()
    }

    fun requestPurchase(offer: PurchaseOffer) {
        if (purchaseBusy || busy) return
        if (!authenticated) { loginReturnUrl = page?.url?.let(::loginDestination); showLogin = true; return }
        if (visiblePosts().flatMap { it.purchases }.none { it == offer }) return
        if (offer.id in uncertainPurchases) { error = "购买结果待确认，请先刷新"; return }
        page?.url?.let { url ->
            timelines[url]?.let { state ->
                if (state.loading) {
                    state.requestId++
                    timelineJobs.remove(url)?.cancel()
                    state.loading = false
                }
            }
        }
        pendingPurchase = offer
    }

    fun dismissPurchase() { if (!purchaseBusy) pendingPurchase = null }

    fun confirmPurchase() {
        val offer = pendingPurchase ?: return
        if (purchaseBusy || busy) return
        purchaseBusy = true; error = null
        purchaseJob = scope.launch {
            try {
                when (val result = service.purchase(offer)) {
                    is PurchaseResult.Success -> {
                        refreshPurchaseState(result.page)
                        val activeUrl = page?.url
                        rememberCachedPage(result.page)
                        if (activeUrl == result.page.url || activeUrl == null) page = result.page
                        pendingPurchase = null
                        uncertainPurchases = uncertainPurchases - offer.id
                        toast = result.message
                    }
                    is PurchaseResult.PriceChanged -> { pendingPurchase = result.offer; toast = "售价或币种已变化，请核对后重新确认" }
                    is PurchaseResult.Rejected -> { pendingPurchase = null; error = result.message }
                    is PurchaseResult.Uncertain -> {
                        pendingPurchase = null; uncertainPurchases = uncertainPurchases + offer.id; error = result.message
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                pendingPurchase = null
                if (store.get(purchasePendingKey(origin, offer.id)) != null) {
                    uncertainPurchases = uncertainPurchases + offer.id
                    error = "购买结果无法确认，请先刷新或查看站点记录，避免重复扣费"
                } else error = "购买信息复核失败，未提交购买，请刷新后重试"
            } finally { purchaseBusy = false }
        }
    }

    fun search(keyword: String, author: String = "", cacheKey: String? = null, push: Boolean = true) {
        if (keyword.isBlank() && author.isBlank()) return
        lastSearchKeyword = keyword.trim(); lastSearchAuthor = author.trim()
        if (push) rememberNavigationSource()
        work {
            val result = service.search(keyword.trim(), author.trim())
            if (!it()) return@work
            if (handleTimelineLogin(result, page?.url ?: "index.php")) return@work
            page = result
            rememberCachedPage(result)
            if (cacheKey != null) pageCache[cacheKey] = result
        }
    }

    /** Search destinations reuse their result; a new keyword still costs one request. */
    fun ensureSearch(cacheKey: String, keyword: String, author: String) {
        if (pageCache[cacheKey] != null) return
        if (busy) return
        search(keyword, author, cacheKey, push = false)
    }

    fun ensureSearchResult(url: String) {
        if (pageFor(url) != null || busy) return
        val destination = resolve(url)
        if (!work.miaola.kf.ui.KfDestinations.isSearchNavigation(destination.url) ||
            !destination.url.startsWith("$origin/search.php?")) return
        work {
            val result = service.fetch(destination.url)
            if (!it()) return@work
            if (!handleTimelineLogin(result, url)) rememberCachedPage(result, url)
        }
    }

    fun openEditor(url: String) {
        if (busy || purchaseBusy) return
        if (resolve(url).effect != SiteEffect.READ) { toast = "请从发帖或回复入口打开编辑器"; return }
        if (!authenticated) { loginReturnUrl = page?.url?.let(::loginDestination); showLogin = true; return }
        work(editorRequest = true) {
            val form = service.editor(url)
            if (!it()) return@work
            editor = form
            editorFiles = emptyList()
            nextUploadSlot = 1
            showEmoticonPicker = false
            draftTitle = form.fields[form.titleField].orEmpty()
            draftContent = form.fields[form.contentField].orEmpty()
            draftRecipient = form.fields[form.recipientField].orEmpty()
            draftCategory = form.fields["p_type"].orEmpty()
            draftKeywords = form.fields["diy_guanjianci"].orEmpty()
            submitUncertain = false
        }
    }

    fun submit() {
        if (busy) return
        val form = editor ?: return
        if (submitUncertain) { error = "提交结果待确认，请先核对"; return }
        work {
            require(draftTitle.isNotBlank()) { "标题不能为空" }
            require(service.encodedLength(draftTitle) <= form.titleMaxBytes) { "标题超出 ${form.titleMaxBytes} 字节" }
            require(service.encodedLength(draftContent) in form.minBytes..form.maxBytes) { "正文需要 ${form.minBytes}–${form.maxBytes} 个 GBK 字节" }
            require(!form.categoryRequired || draftCategory !in listOf("", "0")) { "请选择帖子分类" }
            require(form.recipientField == null || draftRecipient.isNotBlank()) { "请输入收件人" }
            val values = mutableMapOf(form.titleField to draftTitle, form.contentField to draftContent)
            if (form.categories.isNotEmpty()) values["p_type"] = draftCategory
            form.recipientField?.let { values[it] = draftRecipient }
            if ("diy_guanjianci" in form.fields) values["diy_guanjianci"] = draftKeywords
            when (val result = service.submitWithFiles(form, values, editorFiles)) {
                is SubmitResult.Success -> {
                    if (!it()) return@work
                    editor = null; editorFiles = emptyList(); draftContent = ""; draftTitle = ""; toast = result.message
                    val submitted = service.fetch(result.url ?: page?.url ?: "index.php")
                    if (!it()) return@work
                    page = submitted; rememberCachedPage(submitted, result.url)
                }
                is SubmitResult.Rejected -> if (it()) error = result.message
                is SubmitResult.Uncertain -> if (it()) { submitUncertain = true; error = result.message }
            }
        }
    }

    fun dismissEditor() {
        editor = null; error = null; showEmoticonPicker = false
        draftTitle = ""; draftContent = ""; draftRecipient = ""; draftCategory = ""; draftKeywords = ""
        editorFiles = emptyList()
    }
    fun requestBack() {
        if (purchaseBusy || siteBusy) return
        if (editor != null && busy && !loadingEditor) return
        if (gallery != null) { gallery = null; return }
        if (pendingSiteSubmission != null || pendingSiteDestination != null) { dismissSiteAction(); return }
        if (showSiteTools) { showSiteTools = false; return }
        if (showSettings || showLogin) { back(); return }
        if (showEmoticonPicker) { showEmoticonPicker = false; return }
        if (pendingPurchase != null) { pendingPurchase = null; return }
        if (loadingEditor) { cancelRequest(); return }
        if (editorDirty) confirmDiscard = true
        else back()
    }
    fun back() {
        when {
            showSettings -> showSettings = false
            showLogin -> { showLogin = false; error = null }
            editor != null -> dismissEditor()
            backStack.isNotEmpty() -> {
                cancelRequest(); error = null
                val old = backStack.removeAt(backStack.lastIndex); page = old.first; tab = old.second
            }
        }
    }

    fun switchDomain(value: String) {
        if (purchaseBusy || siteBusy) return
        val normalized = try { normalizeOrigin(value) } catch (e: Exception) { error = e.message; return }
        if (origin == normalized) return
        cancelRequest(); service.close(); editor = null; draftContent = ""; draftTitle = ""; draftRecipient = ""
        avatarJob?.cancel(); accountAvatar = null; accountProfile = null
        profileAvatars.clear(); profileAvatarLoading.clear()
        draftCategory = ""; draftKeywords = ""
        pendingPurchase = null; uncertainPurchases = emptySet()
        showEmoticonPicker = false; sessionNotice = null
        loginReturnUrl = null
        clearFavorites()
        clearNativeTransientState()
        origin = normalized; store.put("selected_origin", origin)
        dailySigned = store.get("daily_signed@$origin") == localDayKey()
        pageCache.clear()
        clearTimelines()
        service = factory.create(origin); page = null; home = null; backStack.clear(); busy = false
        bookmarks = readBookmarks(); history = readHistory(); tab = HomeTab.FEED
        showSettings = false; showLogin = false; load("index.php", false)
    }

    fun refreshCatalog(url: String = configUrl) {
        configUrl = url.trim(); store.put("config_url", configUrl)
        catalogJob?.cancel()
        catalogJob = scope.launch { catalog = domains.refresh(configUrl) }
    }

    fun updateTheme(value: ThemeMode) { theme = value; store.put("theme", value.name) }
    fun updateFontScale(value: Float) { fontScale = value; store.put("font_scale", value.toString()) }
    // Kept as an internal source-compatible name; no WebView is opened.
    fun openBrowser(url: String = page?.url ?: "index.php") {
        if (siteBusy || purchaseBusy) return
        val destination = service.destination(url)
        if (destination.url.substringBefore('#') == page?.url?.substringBefore('#')) showSiteTools = true else openSiteDestination(destination)
    }
    fun openExternal(url: String) {
        if (siteBusy || purchaseBusy) return
        pendingSiteDestination = SiteDestination(url, SiteEffect.EXTERNAL, "在外部浏览器打开", "此链接将在外部应用打开，不附加论坛登录状态。")
    }
    fun openSiteDestination(destination: SiteDestination) {
        if (siteBusy || purchaseBusy) return
        val path = destination.url.substringBefore('?').substringBefore('#').lowercase()
        if (destination.effect != SiteEffect.UNSUPPORTED && destination.url.startsWith("https://") && listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".avif", ".bmp").any { path.endsWith(it) }) {
            showImages(destination.url, listOf(destination.url)); return
        }
        if (destination.effect == SiteEffect.CHANGE && !SiteActionRisk.needsConfirmation(destination)) {
            if (destination.url.substringBefore('?').endsWith("kf_tidfavor.php")) favoriteThread(destination)
            else startSiteAction(null, destination)
            return
        }
        when (destination.effect) {
            SiteEffect.READ -> {
                if (destination.url.substringBefore('?').endsWith("login.php")) { showLogin = true; return }
                load(destination.url)
            }
            SiteEffect.UNSUPPORTED -> error = "这个操作暂未支持"
            else -> pendingSiteDestination = destination
        }
    }
    fun requestSiteSubmit(form: SiteForm, state: Map<String, List<String>>, submit: SiteSubmit) {
        if (busy || siteBusy || purchaseBusy || dailyBusy) return
        try {
            val values = buildSiteFormValues(form, state, submit)
            val files = form.fields.filter { it.kind == SiteFieldKind.FILE }.flatMap { field ->
                formFiles["${form.id}@${field.id}"].orEmpty().map { FilePart(field.name, it) }
            }
            val submission = SiteSubmission(form, values, files, submit)
            if (!SiteActionRisk.needsConfirmation(submission)) {
                pendingSiteSubmission = null
                startSiteAction(submission, null)
            } else pendingSiteSubmission = submission
        } catch (e: Exception) { error = e.message ?: "请检查表单" }
    }
    fun dismissSiteAction() { if (!siteBusy) { pendingSiteSubmission = null; pendingSiteDestination = null } }
    fun confirmSiteAction() {
        val form = pendingSiteSubmission
        val link = pendingSiteDestination
        if (form == null && link == null) return
        startSiteAction(form, link)
    }

    private fun startSiteAction(form: SiteSubmission?, link: SiteDestination?) {
        if (siteBusy || purchaseBusy || dailyBusy) return
        cancelRequest()
        if (link?.effect == SiteEffect.EXTERNAL) { pendingSiteDestination = null; browser(origin, link.url); return }
        if (link?.effect == SiteEffect.DOWNLOAD) {
            pendingSiteDestination = null
            downloadRequest = NativeDownloadRequest(link.url, link.url.substringBefore('?').substringAfterLast('/').ifBlank { "attachment" })
            return
        }
        siteBusy = true; error = null
        val direct = if (form != null) !SiteActionRisk.needsConfirmation(form) else link?.let { !SiteActionRisk.needsConfirmation(it) } == true
        val actionPage = page
        val messageFlow = form?.form?.sourceUrl?.substringBefore('?')?.endsWith("message.php") == true ||
            link?.url?.substringBefore('?')?.endsWith("message.php") == true
        val favoritesFlow = form?.form?.sourceUrl?.let { it.substringBefore('?').endsWith("profile.php") && it.contains("action=favor") } == true
        val messageSource = if (messageFlow && (form != null || Regex("[?&]action=(del|unread)(?:&|$)").containsMatchIn(link?.url.orEmpty()))) {
            val candidate = form?.form?.sourceUrl ?: messageListUrl
            val requestedBox = Regex("[?&]towhere=(receivebox|sendbox|scout)(?:&|$)").find(link?.url.orEmpty())?.groupValues?.get(1)
            when {
                form?.form?.fields?.any { it.name == "banidinfo" } == true -> form.form.sourceUrl
                candidate != null && resolve(candidate).effect == SiteEffect.READ &&
                    (requestedBox == null || candidate.contains("action=$requestedBox") || requestedBox == "receivebox" && !candidate.contains("action=")) -> candidate
                requestedBox != null -> "$origin/message.php?action=$requestedBox"
                else -> null
            }
        } else null
        val managedReturn = when {
            favoritesFlow -> actionPage?.url?.takeIf { isSamePageList(it, form.form.sourceUrl) } ?: form.form.sourceUrl
            messageFlow -> messageSource
            else -> null
        }
        siteJob = scope.launch {
            try {
                val response = if (form != null) service.submitSite(form) else service.executeSite(link!!)
                val result = if (managedReturn != null && response is SiteActionResult.Response && response.page.authStatus != AuthStatus.LOGIN_REQUIRED) {
                    // Read back the source list, not a legacy receipt/action URL.
                    val fresh = try { service.fetch(managedReturn) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                    if (fresh != null) SiteActionResult.Response(fresh, response.uncertainReceipt) else response
                } else response
                when (result) {
                    is SiteActionResult.Response -> {
                        val stillHere = page?.url == actionPage?.url
                        val managedFresh = managedReturn != null && isSamePageList(managedReturn, result.page.url)
                        if (managedFresh) {
                            managementRevision++
                            // Deleting/moving changes page boundaries. Old tails,
                            // selection contexts and in-flight appends are invalid.
                            val affected = timelines.values.distinct().filter { isSamePageList(it.first.url, managedReturn) }
                            val retained = affected.firstOrNull { it.first.url == managedReturn || it.first.url == result.page.url }
                            affected.forEach { state -> state.requestId++; state.loading = false; state.refreshing = false; state.reset(result.page) }
                            timelines.keys.filter { key -> isSamePageList(key, managedReturn) }.toList().forEach { key -> timelineJobs.remove(key)?.cancel(); timelines.remove(key) }
                            retained?.let { timelines[result.page.url] = it; timelines[managedReturn] = it }
                            pageCache.keys.filter { key -> isSamePageList(resolve(key).url, managedReturn) }.toList().forEach(pageCache::remove)
                            if (stillHere) page = result.page
                            rememberCachedPage(result.page, managedReturn)
                            if (stillHere && messageFlow) pendingNativePage = managedReturn
                        } else if (!direct && stillHere) { rememberNavigationSource(); page = result.page }
                        if (favoritesFlow) {
                            siteFavorites = null
                        }
                        rememberCachedPage(result.page)
                        if (messageFlow && !managedFresh && stillHere && result.page.url != actionPage?.url) pendingNativePage = result.page.url
                        showSiteTools = !direct && !messageFlow && !favoritesFlow
                        if (showSiteTools) pendingNativePage = result.page.url
                        if (result.page.authStatus == AuthStatus.AUTHENTICATED) {
                            showLogin = false
                            if (result.page.url.substringBefore('?').endsWith("index.php")) { home = result.page; showSiteTools = false }
                        }
                        if (result.page.authStatus == AuthStatus.LOGIN_REQUIRED) {
                            home = result.page; backStack.clear(); editor = null; showLogin = true; showSiteTools = false
                            pendingNativePage = null
                            toast = result.page.notice.takeIf { it.isNotBlank() }
                            return@launch
                        }
                        formFiles = emptyMap()
                        toast = when {
                            result.uncertainReceipt != null -> "操作已提交，请刷新核对结果"
                            direct -> result.page.notice.ifBlank { SiteActionRisk.successMessage(link) }
                            else -> result.page.notice.ifBlank { "操作已完成" }
                        }
                    }
                    is SiteActionResult.Rejected -> error = result.message
                    is SiteActionResult.Uncertain -> error = result.message
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = friendlyError(e) }
            finally { pendingSiteSubmission = null; pendingSiteDestination = null; siteBusy = false }
        }
    }
    fun requestFiles(target: String, fieldId: String = "", multiple: Boolean = true, mimeTypes: List<String> = listOf("*/*")) {
        if (!busy && !siteBusy && !purchaseBusy) filePickerRequest = FilePickerRequest(target, fieldId, multiple, mimeTypes)
    }
    fun receiveFiles(request: FilePickerRequest, files: List<LocalUpload>, failure: String? = null) {
        filePickerRequest = null
        failure?.let { error = it; return }
        if (request.target == "editor") {
            val form = editor ?: return
            if (editorFiles.size + files.size > form.uploadLimit) { error = "最多选择 ${form.uploadLimit} 个附件"; return }
            if (form.uploadExtensions.isNotEmpty() && files.any { it.name.substringAfterLast('.', "").lowercase() !in form.uploadExtensions }) { error = "所选附件类型不符合站点限制"; return }
            val merged = editorFiles + files.map { it.copy(slot = nextUploadSlot++) }
            if (merged.any { it.size > form.uploadMaxBytes }) { error = "附件超过站点大小限制"; return }
            editorFiles = merged
        } else formFiles = formFiles + ("${request.target}@${request.fieldId}" to files)
    }
    fun removeEditorFile(id: String) {
        editorFiles.firstOrNull { it.id == id }?.let { draftContent = draftContent.replace("[upload=${it.slot}]", "") }
        editorFiles = editorFiles.filterNot { it.id == id }
    }
    fun openNativeLoginOptions() {
        rememberNavigationSource()
        work {
            val result = service.fetch("login.php")
            if (!it()) return@work
            page = result; rememberCachedPage(result, "login.php")
            showLogin = false; showSiteTools = true; pendingNativePage = "login.php"
        }
    }
    fun checkEditorSubmission() {
        val message = editor?.recipientField != null
        editor = null; showEmoticonPicker = false
        load(if (message) "$origin/message.php?action=sendbox" else page?.url ?: "index.php", false)
    }
    fun showImages(selected: String, images: List<String>) {
        val list = images.filter { it.startsWith("https://") }.distinct().take(100)
        val usable = if (selected in list) list else listOf(selected).filter { it.startsWith("https://") }
        if (usable.isNotEmpty()) gallery = ImageGallery(usable, usable.indexOf(selected).coerceAtLeast(0))
    }
    fun closeImages() { gallery = null }
    fun saveImage(url: String) { error = null; downloadRequest = NativeDownloadRequest(url, url.substringBefore('?').substringAfterLast('/').ifBlank { "image.jpg" }, true) }
    fun consumeDownloadRequest() { downloadRequest = null }
    fun reportPlatformResult(message: String, failed: Boolean = false) { if (failed) error = message else toast = message }
    fun presentNativePage(result: PageData) {
        rememberNavigationSource(); page = result; rememberCachedPage(result, allowLoginWall = true); showSiteTools = true
        pendingNativePage = result.url; gallery = null
        if (result.authStatus == AuthStatus.LOGIN_REQUIRED) home = home?.copy(authenticated = false, authStatus = AuthStatus.LOGIN_REQUIRED)
    }
    fun consumeNativePage() { pendingNativePage = null }
    private fun clearNativeTransientState() {
        lastSearchKeyword = ""; lastSearchAuthor = ""
        messageListUrl = null
        showSiteTools = false; pendingNativePage = null; pendingSiteDestination = null; pendingSiteSubmission = null
        showTopicFilters = false
        dailyStatusJob?.cancel(); dailyStatusJob = null
        dailyBusy = false
        filePickerRequest = null; formFiles = emptyMap(); editorFiles = emptyList(); gallery = null
    }
    fun clearMessage() { toast = null }
    fun dismissSessionNotice() { sessionNotice = null }
    fun clearError() { error = null }
    fun logout() {
        if (purchaseBusy || siteBusy) return
        cancelRequest(); service.close(); service.clearSession(); home = null; page = null; editor = null
        store.remove("daily_signed@$origin"); dailySigned = false
        avatarJob?.cancel(); accountAvatar = null; accountProfile = null
        profileAvatars.clear(); profileAvatarLoading.clear()
        draftContent = ""; draftTitle = ""; draftRecipient = ""
        draftCategory = ""; draftKeywords = ""
        backStack.clear(); pageCache.clear(); showLogin = true; busy = false
        clearTimelines()
        loginReturnUrl = null; pendingPurchase = null; showEmoticonPicker = false
        clearFavorites()
        clearNativeTransientState()
        toast = "已退出本机登录"
    }

    fun toggleBookmark() {
        val p = page ?: return
        bookmarks = if (bookmarks.any { it.url == p.url }) bookmarks.filterNot { it.url == p.url }
        else (listOf(SiteLink(p.title, p.url)) + bookmarks).take(200)
        saveLinks("bookmarks", bookmarks)
    }

    // URL/title lists are stored in the encrypted platform store, scoped by origin.
    private fun readLinks(key: String): List<SiteLink> = store.get("$key@$origin")?.lineSequence()?.mapNotNull {
        val parts = it.split('\t', limit = 2)
        if (parts.size == 2) SiteLink(parts[1], parts[0]) else null
    }?.toList().orEmpty()
    private fun readBookmarks() = readLinks("bookmarks")
    private fun readHistory() = readLinks("history")
    private fun saveLinks(key: String, links: List<SiteLink>) = store.put("$key@$origin", links.joinToString("\n") { "${it.url.replace("\n", "").replace("\t", "")}\t${it.title.replace("\n", " ").replace("\t", " ")}" })
    fun clearHistory() { history = emptyList(); store.remove("history@$origin") }
    fun close() { cancelRequest(); clearTimelines(); purchaseJob?.cancel(); siteJob?.cancel(); favoritesJob?.cancel(); catalogJob?.cancel(); avatarJob?.cancel(); service.close() }
}

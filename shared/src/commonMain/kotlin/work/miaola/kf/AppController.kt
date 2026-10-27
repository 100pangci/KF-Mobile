package work.miaola.kf

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    var draftAttachmentNotice by mutableStateOf<String?>(null); private set
    var draftRestored by mutableStateOf(false); private set
    var pendingMentionName by mutableStateOf<String?>(null); private set
    var pendingMessageConfirm by mutableStateOf(false); private set
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
    var managementRecoveries by mutableStateOf<Set<String>>(emptySet()); private set
    var pendingBatch by mutableStateOf<List<BatchPageRequest>?>(null); private set
    var batchBusy by mutableStateOf(false); private set
    var batchResult by mutableStateOf<BatchTransactionResult?>(null); private set
    var pendingReward by mutableStateOf<RewardAction?>(null); private set
    var rewardBusy by mutableStateOf(false); private set
    var pendingAvatar by mutableStateOf<LocalUpload?>(null); private set
    var avatarPickerRequested by mutableStateOf(false); private set
    var avatarBusy by mutableStateOf(false); private set
    var gugu by mutableStateOf<GuguSnapshot?>(null); private set
    var guguBusy by mutableStateOf(false); private set
    var guguError by mutableStateOf<String?>(null); private set
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
    private var recovery = RecoveryStore(store, origin)
    private var recoveryState = recovery.loadState() ?: RecoveryState(origin = origin)
    private var recoveryJob: Job? = null
    private var draftJob: Job? = null
    private var restoreJob: Job? = null
    private var batchJob: Job? = null
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
        if (managementRecoveryFor(first.url) != null) state.invalidateManagement()
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
        if (managementRecoveryFor(first.url) != null) return
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
                    rememberTimeline(state)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (origin == expectedOrigin && requestId == state.requestId) state.failure = friendlyError(e) }
            finally { if (requestId == state.requestId) state.loading = false }
        }
    }

    fun refreshTimeline(first: PageData, search: Pair<String, String>? = null) {
        managementRecoveryFor(first.url)?.let { recoverManagement(it); return }
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
                rememberTimeline(state)
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
    val canBack get() = gallery != null || showSiteTools || pendingSiteDestination != null || pendingSiteSubmission != null || pendingBatch != null || pendingReward != null || pendingAvatar != null || pendingMessageConfirm || loadingEditor || pendingPurchase != null || editor != null || showSettings || showLogin || backStack.isNotEmpty()

    init {
        store.put("selected_origin", origin)
        managementRecoveries = recoveryState.pendingManagement.toSet()
        recovery.loadPendingBatch()?.let { pending ->
            // A process death during a multi-page write leaves the receipt
            // unknown. Never replay it; force a read-only reconciliation.
            managementRecoveries = managementRecoveries + pending.sourcePages
        }
        refreshCatalog(); load("index.php", push = false)
    }

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
        timelines.values.distinct().filterNot { it.managementStale }.forEach { it.updatePage(result) }
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
        managementRecoveryFor(url)?.let { if (force || pageFor(url) == null) recoverManagement(it); return }
        if (!force && pageFor(url) != null) return
        if (!force && busy && loadingUrl == url) return
        // A restored reader may point at page 4. Rebuild from its saved root
        // so floor order and the saved anchor remain meaningful.
        val snapshot = recoveredTimelineFor(url)
        if (!force && snapshot != null && pageNumber(url) > 1 && pageFor(snapshot.rootUrl) == null &&
            isSamePageList(snapshot.rootUrl, url)) {
            load(snapshot.rootUrl, push = false)
            return
        }
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

    private fun pageNumber(url: String): Int =
        Regex("[?&]page=([0-9]+)(?:&|$)").find(url.substringBefore('#'))?.groupValues?.get(1)?.toIntOrNull() ?: 1

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
            val state = timelineFor(result)
            if (state.first.url != result.url) state.reset(result)
            restoreContinuation(result)
            rememberTimeline(state)
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
                val growth = page.nativePage?.growth ?: return@launch
                when (growth.claimStatus) {
                    StatusKind.COMPLETED -> {
                        dailySigned = true
                        store.put("daily_signed@$origin", localDayKey())
                    }
                    StatusKind.AVAILABLE -> {
                        dailySigned = false
                        store.remove("daily_signed@$origin")
                    }
                    // Unknown / waiting / failed are not evidence that the
                    // daily claim is available or already completed.
                    StatusKind.WAITING, StatusKind.EXPIRED, StatusKind.FAILED, StatusKind.UNKNOWN -> Unit
                }
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
        if (dailyBusy || busy || siteBusy || purchaseBusy || batchBusy || rewardBusy || avatarBusy ||
            pendingSiteDestination != null || pendingSiteSubmission != null || pendingBatch != null || pendingReward != null || pendingAvatar != null) return
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
                var claimed = growth?.claimStatus == StatusKind.COMPLETED
                var claimReceiptUncertain = false
                val claimUrl = growth?.claimUrl
                if (!claimed && claimUrl != null) {
                    val destination = client.destination(claimUrl)
                    when (destination.effect) {
                        SiteEffect.READ -> client.fetch(destination.url)
                        SiteEffect.CHANGE -> when (val result = client.executeSite(destination)) {
                            is SiteActionResult.Response -> {
                                notice = result.page.notice.ifBlank { notice }
                                claimReceiptUncertain = result.uncertainReceipt != null
                            }
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
                            is SiteActionResult.Response -> {
                                notice = result.page.notice.ifBlank { notice }
                                claimReceiptUncertain = result.uncertainReceipt != null
                            }
                        is SiteActionResult.Rejected -> { error = result.message; return@launch }
                        is SiteActionResult.Uncertain -> { error = result.message; return@launch }
                    }
                }
                if (!claimed) {
                    // The reward page renders a fresh status after the claim.
                    val statusPage = try { client.fetch("kf_growup.php") } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                    if (statusPage != null) {
                        notice = statusPage.notice.ifBlank { notice }
                        claimed = statusPage.nativePage?.growth?.claimStatus == StatusKind.COMPLETED
                        if (statusPage.nativePage?.growth?.claimStatus == StatusKind.AVAILABLE) claimReceiptUncertain = true
                    }
                }
                if (origin != expectedOrigin || !authenticated) return@launch
                if (claimed) {
                    dailySigned = true
                    store.put("daily_signed@$origin", localDayKey())
                } else {
                    error = if (claimReceiptUncertain) {
                        "服务器可能已执行签到，但当前状态无法确认。请刷新签到页面核对，不要重复领取。"
                    } else {
                        "站点没有提供可确认的签到状态；请刷新状态后再决定是否操作。"
                    }
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
        if (destination == null || purchaseBusy || siteBusy || dailyBusy || batchBusy || rewardBusy || avatarBusy || pendingBatch != null || pendingReward != null || pendingAvatar != null || pendingPurchase != null) return
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
        val currentAction = runCatching {
            destination.url.substringAfter('?', "").substringBefore('#').split('&')
                .firstOrNull { it.substringBefore('=') == "action" }?.substringAfter('=')
        }.getOrNull()
        val expectedAction = when (currentAction) {
            "favor" -> "unfavor"
            "unfavor" -> "favor"
            else -> null
        }
        siteJob = scope.launch {
            try {
                when (val result = client.executeSite(destination)) {
                    is SiteActionResult.Response -> {
                        siteFavorites = null
                        if (result.page.authStatus == AuthStatus.LOGIN_REQUIRED || result.page.url.contains("login.php")) {
                            showLogin = true
                            error = "登录状态已失效，请重新登录"
                        } else {
                            // Re-read the reader so its favorite entry matches the
                            // site immediately. HTTP/page return alone is not a
                            // business receipt; require the opposite action link.
                            var confirmed = false
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
                                    val actions = listOfNotNull(refreshed.favorite) +
                                        refreshed.nativePage?.links.orEmpty() +
                                        refreshed.posts.flatMap { it.actions }
                                    confirmed = expectedAction != null && actions.any { action ->
                                        action.url.contains("kf_tidfavor.php") &&
                                            action.url.substringAfter('?', "").substringBefore('#').split('&')
                                                .any { it == "action=$expectedAction" }
                                    }
                                }
                            }
                            if (confirmed) {
                                toast = result.page.notice.ifBlank { SiteActionRisk.successMessage(destination) }
                            } else {
                                error = result.uncertainReceipt
                                    ?: "服务器已返回页面，但收藏状态未能核对。请刷新主题后再决定是否重试。"
                            }
                        }
                    }
                    is SiteActionResult.Rejected -> error = result.message
                    is SiteActionResult.Uncertain -> error = result.message
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (origin == expectedOrigin) error = "收藏请求结果无法确认，请刷新主题核对，不要自动重试。"
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
        if (purchaseBusy || busy || siteBusy || batchBusy || rewardBusy || avatarBusy || pendingBatch != null || pendingReward != null || pendingAvatar != null) return
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
        if (purchaseBusy || busy || siteBusy || batchBusy || rewardBusy || avatarBusy || pendingBatch != null || pendingReward != null || pendingAvatar != null) return
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

    // ---- Process-recovery state (reconstructable only) -------------------

    fun rememberDestination(kind: String, url: String? = null, query: String = "", author: String = "") {
        val next = DestinationRecovery(kind, url.orEmpty().take(2_048), query.take(200), author.take(200))
        if (recoveryState.destination != next) {
            recoveryState = recoveryState.copy(destination = next)
            runCatching { recovery.saveState(recoveryState) }
        }
    }

    fun recoveredDestination(): DestinationRecovery? = recoveryState.destination.takeIf { it.kind.isNotBlank() }

    fun anchorFor(rootUrl: String): TimelineRecovery? = recoveryState.timelines.entries
        .firstOrNull { isSamePageList(it.key, rootUrl) || isSamePageList(it.value.rootUrl, rootUrl) }?.value

    fun rememberAnchor(rootUrl: String, index: Int, offset: Int, key: String = "") {
        if (rootUrl.isBlank()) return
        val previous = recoveryState.timelines[rootUrl]
        val value = TimelineRecovery(rootUrl, previous?.loadedPageUrls.orEmpty(), index.coerceAtLeast(0),
            offset, key.take(160), currentEpochMillis())
        if (previous?.anchorIndex == value.anchorIndex && previous.anchorOffset == value.anchorOffset &&
            previous.anchorKey == value.anchorKey) return
        recoveryState = recoveryState.copy(timelines = recoveryState.timelines + (rootUrl to value))
        scheduleRecoveryPersist()
    }

    private fun rememberTimeline(state: PageTimeline) {
        val root = state.first.url
        val pages = state.loadedPageUrls.distinct().take(RecoveryStore.MAX_RESTORED_PAGES)
        val previous = recoveryState.timelines[root]
        recoveryState = recoveryState.copy(timelines = recoveryState.timelines + (root to TimelineRecovery(
            rootUrl = root,
            loadedPageUrls = pages,
            anchorIndex = previous?.anchorIndex ?: 0,
            anchorOffset = previous?.anchorOffset ?: 0,
            anchorKey = previous?.anchorKey.orEmpty(),
            updatedAt = currentEpochMillis(),
        )))
        scheduleRecoveryPersist()
    }

    private fun scheduleRecoveryPersist() {
        recoveryJob?.cancel()
        recoveryJob = scope.launch {
            delay(500)
            runCatching { recovery.saveState(recoveryState) }
        }
    }

    private fun recoveredTimelineFor(url: String): TimelineRecovery? = recoveryState.timelines.entries
        .firstOrNull { isSamePageList(it.key, url) || isSamePageList(it.value.rootUrl, url) }?.value

    private fun restoreContinuation(first: PageData) {
        val snapshot = recoveredTimelineFor(first.url) ?: return
        if (snapshot.loadedPageUrls.size <= 1) return
        val state = timelineFor(first)
        if (state.loadedPageUrls.size > 1 || state.loading || state.refreshing) return
        val expected = origin
        val client = service
        val pages = snapshot.loadedPageUrls.drop(1)
            .filter { candidate -> isSamePageList(snapshot.rootUrl, candidate) }
            .take(RecoveryStore.MAX_RESTORED_PAGES - 1)
        if (pages.isEmpty()) return
        restoreJob?.cancel()
        restoreJob = scope.launch {
            var previous = first
            for (candidate in pages) {
                if (origin != expected || page?.url?.let { !isSamePageList(it, first.url) } == true) return@launch
                val cached = pageFor(candidate)
                if (cached != null) { previous = cached; continue }
                val result = try { client.fetch(candidate) } catch (e: CancellationException) { throw e
                } catch (_: Exception) { break }
                if (origin != expected || handleTimelineLogin(result, snapshot.rootUrl)) return@launch
                if (!isSamePageList(first.url, result.url) || !isPageContinuation(previous.url, result.url)) break
                refreshPurchaseState(result)
                rememberCachedPage(result, candidate)
                state.append(result, candidate)
                previous = result
            }
            rememberTimeline(state)
        }
    }

    fun updateDraftTitle(value: String) { draftTitle = value; scheduleDraftSave() }
    fun updateDraftContent(value: String) { draftContent = value; scheduleDraftSave() }
    fun updateDraftRecipient(value: String) { draftRecipient = value; scheduleDraftSave() }
    fun updateDraftCategory(value: String) { draftCategory = value; scheduleDraftSave() }
    fun updateDraftKeywords(value: String) { draftKeywords = value; scheduleDraftSave() }
    fun markDraftChanged() = scheduleDraftSave()

    private fun scheduleDraftSave() {
        val form = editor ?: return
        draftJob?.cancel()
        draftJob = scope.launch {
            delay(350)
            recovery.saveDraft(EditorDraft(
                sourceUrl = form.sourceUrl,
                title = draftTitle,
                body = draftContent,
                recipient = draftRecipient,
                category = draftCategory,
                keywords = draftKeywords,
                attachments = editorFiles.map { DraftAttachmentMeta(it.name.take(160), it.mimeType.take(80), it.size, it.slot) },
                updatedAt = currentEpochMillis(),
            ))
        }
    }

    fun flushDraft() {
        draftJob?.cancel()
        val form = editor ?: return
        runCatching {
            recovery.saveDraft(EditorDraft(
                sourceUrl = form.sourceUrl,
                title = draftTitle,
                body = draftContent,
                recipient = draftRecipient,
                category = draftCategory,
                keywords = draftKeywords,
                attachments = editorFiles.map { DraftAttachmentMeta(it.name.take(160), it.mimeType.take(80), it.size, it.slot) },
                updatedAt = currentEpochMillis(),
            ))
        }
    }

    fun flushRecovery() {
        recoveryJob?.cancel()
        runCatching { recovery.saveState(recoveryState) }
    }

    fun openEditorWithMention(url: String, displayName: String) {
        if (displayName.isBlank()) { openEditor(url); return }
        pendingMentionName = displayName.trim().take(32)
        openEditor(url)
    }

    fun requestAvatarPicker() {
        if (!busy && !siteBusy && !avatarBusy && !purchaseBusy && !batchBusy && !rewardBusy &&
            pendingSiteDestination == null && pendingSiteSubmission == null && pendingBatch == null && pendingReward == null && pendingPurchase == null) {
            avatarPickerRequested = true
        }
    }

    fun consumeAvatarPickerRequest() { avatarPickerRequested = false }

    fun receiveAvatar(file: LocalUpload?, failure: String? = null) {
        avatarPickerRequested = false
        failure?.let { error = it; pendingAvatar = null; return }
        if (file == null) return
        pendingAvatar = file
    }

    fun cancelAvatarUpload() { if (!avatarBusy) { pendingAvatar = null; avatarPickerRequested = false } }

    fun openEditor(url: String) {
        if (busy || purchaseBusy || siteBusy || batchBusy || rewardBusy || avatarBusy) return
        if (resolve(url).effect != SiteEffect.READ) { toast = "请从发帖或回复入口打开编辑器"; return }
        if (!authenticated) { loginReturnUrl = page?.url?.let(::loginDestination); showLogin = true; return }
        work(editorRequest = true) {
            val form = service.editor(url)
            if (!it()) return@work
            editor = form
            editorFiles = emptyList()
            nextUploadSlot = 1
            showEmoticonPicker = false
            val saved = recovery.loadDraft(form.sourceUrl) ?: recovery.loadDraft(url)
            if (saved != null && saved.sourceUrl != form.sourceUrl) {
                recovery.saveDraft(saved.copy(sourceUrl = form.sourceUrl))
                recovery.removeDraft(saved.sourceUrl)
            }
            draftAttachmentNotice = null
            draftRestored = saved != null
            draftTitle = saved?.title ?: form.fields[form.titleField].orEmpty()
            draftContent = saved?.body ?: form.fields[form.contentField].orEmpty()
            draftRecipient = saved?.recipient ?: form.fields[form.recipientField].orEmpty()
            draftCategory = saved?.category ?: form.fields["p_type"].orEmpty()
            draftKeywords = saved?.keywords ?: form.fields["diy_guanjianci"].orEmpty()
            if (saved != null && saved.attachments.isNotEmpty()) {
                val names = saved.attachments.take(3).joinToString("、") { it.name }
                draftAttachmentNotice = "有 ${saved.attachments.size} 个附件需要重新选择（$names${if (saved.attachments.size > 3) " 等" else ""}）；原附件内容不会从上次会话恢复。"
            }
            pendingMentionName?.let { name ->
                pendingMentionName = null
                if (!draftContent.contains("@$name")) draftContent = "@$name " + draftContent
                if ("diy_guanjianci" in form.fields) draftKeywords = mergeMentionKeywords(draftKeywords, draftContent)
                scheduleDraftSave()
            }
            submitUncertain = false
        }
    }

    fun requestEditorSubmit() {
        val form = editor ?: return
        if (busy || siteBusy || purchaseBusy || batchBusy || rewardBusy || avatarBusy) return
        if (form.recipientField != null && draftRecipient.isNotBlank() && !submitUncertain) pendingMessageConfirm = true else submit()
    }

    fun confirmMessageSend() {
        if (busy || siteBusy || purchaseBusy || batchBusy || rewardBusy || avatarBusy) return
        pendingMessageConfirm = false
        submit()
    }

    fun dismissMessageConfirm() { pendingMessageConfirm = false }

    fun submit() {
        if (busy || siteBusy || purchaseBusy || batchBusy || rewardBusy || avatarBusy ||
            pendingBatch != null || pendingReward != null || pendingAvatar != null || pendingSiteDestination != null || pendingSiteSubmission != null) return
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
            if ("diy_guanjianci" in form.fields) values["diy_guanjianci"] = mergeMentionKeywords(draftKeywords, draftContent)
            when (val result = service.submitWithFiles(form, values, editorFiles)) {
                is SubmitResult.Success -> {
                    if (!it()) return@work
                    recovery.removeDraft(form.sourceUrl)
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
        editor?.let { recovery.removeDraft(it.sourceUrl) }
        draftJob?.cancel()
        editor = null; error = null; showEmoticonPicker = false
        draftTitle = ""; draftContent = ""; draftRecipient = ""; draftCategory = ""; draftKeywords = ""
        draftAttachmentNotice = null; draftRestored = false; pendingMentionName = null
        editorFiles = emptyList()
    }
    fun requestBack() {
        if (purchaseBusy || siteBusy) return
        if (editor != null && busy && !loadingEditor) return
        if (gallery != null) { gallery = null; return }
        if (pendingMessageConfirm) { dismissMessageConfirm(); return }
        if (pendingBatch != null) { dismissBatch(); return }
        if (pendingReward != null) { dismissReward(); return }
        if (pendingAvatar != null) { cancelAvatarUpload(); return }
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
        if (purchaseBusy || siteBusy || batchBusy || rewardBusy || avatarBusy) return
        val normalized = try { normalizeOrigin(value) } catch (e: Exception) { error = e.message; return }
        if (origin == normalized) return
        cancelRequest(); service.close(); editor = null; draftContent = ""; draftTitle = ""; draftRecipient = ""
        draftJob?.cancel(); recoveryJob?.cancel(); restoreJob?.cancel(); batchJob?.cancel()
        draftCategory = ""; draftKeywords = ""; draftAttachmentNotice = null; draftRestored = false
        pendingMentionName = null; pendingMessageConfirm = false
        avatarJob?.cancel(); accountAvatar = null; accountProfile = null
        profileAvatars.clear(); profileAvatarLoading.clear()
        pendingPurchase = null; uncertainPurchases = emptySet()
        showEmoticonPicker = false; sessionNotice = null
        loginReturnUrl = null
        clearFavorites()
        clearNativeTransientState()
        origin = normalized; store.put("selected_origin", origin)
        recovery = RecoveryStore(store, origin)
        recoveryState = recovery.loadState() ?: RecoveryState(origin = origin)
        managementRecoveries = recoveryState.pendingManagement.toSet()
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
        if (busy || siteBusy || purchaseBusy || dailyBusy || batchBusy || rewardBusy || avatarBusy || pendingBatch != null || pendingReward != null || pendingAvatar != null) return
        if (managementRecoveryFor(form.sourceUrl) != null) { error = "请先重新加载并核对列表，再选择项目"; return }
        try {
            val selectedBulkValues = form.fields.filter { it.name == "delid[]" }
                .flatMap { field -> field.options.filter { option -> option.value in state[field.id].orEmpty() } }
                .map { it.value }.toSet()
            if (selectedBulkValues.isNotEmpty()) {
                val built = buildBatchPageRequest(form, selectedBulkValues, state)
                if (built == null) { error = "所选项目无法安全核对，未提交"; return }
                val values = buildSiteFormValues(form, state, submit)
                val operation = values.lastOrNull { it.name in setOf("job", "action") }?.value
                requestBatchSiteSubmit(listOf(built.copy(values = values, submit = submit,
                    verifyAbsent = operation !in setOf("change", "move", "unread"))))
                return
            }
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
        if (batchBusy || rewardBusy || avatarBusy || purchaseBusy || dailyBusy || pendingBatch != null || pendingReward != null || pendingAvatar != null || pendingPurchase != null) return
        startSiteAction(form, link)
    }

    private fun startSiteAction(form: SiteSubmission?, link: SiteDestination?) {
        if (siteBusy || purchaseBusy || dailyBusy || batchBusy || rewardBusy || avatarBusy || pendingBatch != null || pendingReward != null || pendingAvatar != null || pendingPurchase != null) return
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
        if (managedReturn != null && managementRecoveryFor(managedReturn) != null) {
            pendingSiteSubmission = null; pendingSiteDestination = null; siteBusy = false
            error = "请先重新加载并核对列表，再选择项目"
            return
        }
        val managedRecoveryStore = recovery
        if (managedReturn != null) {
            managedRecoveryStore.savePendingBatch(PendingBatch(
                sourcePages = listOf(managedReturn),
                itemIds = form?.values?.filter { it.name == "delid[]" }?.map { "$managedReturn#${it.value}" }.orEmpty(),
                createdAt = currentEpochMillis(),
            ))
        }
        // Stop old appends before the write; even a cancellation-ignoring
        // transport must not append a pre-write snapshot after the response.
        if (managedReturn != null) timelines.values.distinct().filter { isSamePageList(it.first.url, managedReturn) }.forEach { state ->
            state.requestId++; state.loading = false; state.refreshing = false
        }
        siteJob = scope.launch {
            try {
                val response = if (form != null) service.submitSite(form) else service.executeSite(link!!)
                if (managedReturn != null && response !is SiteActionResult.Rejected) invalidateManagement(managedReturn)
                val result = if (managedReturn != null && response is SiteActionResult.Response && response.page.authStatus != AuthStatus.LOGIN_REQUIRED) {
                    // Read back the source list, not a legacy receipt/action URL.
                    val fresh = try { service.fetch(managedReturn) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                    if (fresh != null && fresh.authStatus == AuthStatus.LOGIN_REQUIRED) SiteActionResult.Response(fresh)
                    else if (fresh != null && validManagementReadback(managedReturn, fresh))
                        SiteActionResult.Response(fresh, response.uncertainReceipt)
                    else {
                        error = "操作已返回，但列表未能重新加载；请先核对，勿重复提交"
                        return@launch
                    }
                } else response
                when (result) {
                    is SiteActionResult.Response -> {
                        val stillHere = page?.url == actionPage?.url
                        val managedFresh = managedReturn != null && isSamePageList(managedReturn, result.page.url)
                        if (managedFresh) {
                            if (result.uncertainReceipt == null) {
                                clearSelectionRecovery(managedReturn)
                                managedRecoveryStore.clearPendingBatchSource(managedReturn)
                            } else {
                                // A structurally valid list is useful to inspect,
                                // but does not by itself prove a multi-item change.
                                invalidateManagement(managedReturn)
                            }
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
                            managedFresh -> response.let { (it as? SiteActionResult.Response)?.page?.notice }.orEmpty().ifBlank { "列表已重新加载，请核对操作结果" }
                            direct -> result.page.notice.ifBlank { SiteActionRisk.successMessage(link) }
                            else -> result.page.notice.ifBlank { "操作已完成" }
                        }
                    }
                    is SiteActionResult.Rejected -> {
                        managedReturn?.let(managedRecoveryStore::clearPendingBatchSource)
                        error = result.message
                    }
                    is SiteActionResult.Uncertain -> {
                        val bank = form?.form?.actionUrl?.contains("H_name=bank") == true ||
                            link?.url?.contains("H_name=bank") == true
                        error = if (bank) "服务器可能已执行该银行操作，但回执无法确认。请打开银行日志只读核对，不要自动重试。"
                        else result.message
                    }
                }
            } catch (e: CancellationException) {
                // The request may already have crossed the wire. Keep both the
                // durable marker and the in-memory read-only recovery gate.
                if (managedReturn != null) invalidateManagement(managedReturn)
                throw e
            }
            catch (e: Exception) {
                if (managedReturn != null) invalidateManagement(managedReturn)
                error = friendlyError(e)
            }
            finally { pendingSiteSubmission = null; pendingSiteDestination = null; siteBusy = false }
        }
    }

    fun managementRecoveryFor(url: String): String? = managementRecoveries.firstOrNull { isSamePageList(resolve(url).url, it) }

    private fun validManagementReadback(source: String, fresh: PageData): Boolean {
        if (fresh.authStatus != AuthStatus.AUTHENTICATED || resolve(fresh.url).effect != SiteEffect.READ || !isSamePageList(source, fresh.url)) return false
        val native = fresh.nativePage
        return when {
            source.substringBefore('?').endsWith("profile.php") -> native?.favorites != null || fresh.topics.isNotEmpty()
            source.contains("action=banned") -> native?.blocklist != null
            else -> native?.hasMessageList == true || fresh.topics.isNotEmpty()
        }
    }

    private fun invalidateManagement(source: String) {
        managementRevision++
        managementRecoveries = managementRecoveries + source
        recoveryState = recoveryState.copy(
            pendingManagement = (recoveryState.pendingManagement + source).distinct().takeLast(8),
        )
        scheduleRecoveryPersist()
        val snapshot = pageCache[source] ?: page?.takeIf { it.url == source }
        timelines.values.distinct().filter { isSamePageList(it.first.url, source) }.forEach { it.invalidateManagement() }
        pageCache.keys.filter { isSamePageList(resolve(it).url, source) }.toList().forEach(pageCache::remove)
        // A non-paginated management page (e.g. blocklist) still needs to show
        // the recovery affordance instead of an empty destination.
        snapshot?.let { pageCache[source] = it }
        if (siteFavorites?.url?.let { isSamePageList(it, source) } == true) siteFavorites = null
    }

    /** Read only. Never replay a delete/move, even if the previous result was unknown. */
    fun recoverManagement(source: String) {
        val target = managementRecoveryFor(source) ?: return
        if (busy || siteBusy || purchaseBusy || resolve(target).effect != SiteEffect.READ) return
        work { current ->
            val fresh = service.fetch(target)
            if (!current() || handleTimelineLogin(fresh, target)) return@work
            require(validManagementReadback(target, fresh)) { "未获取到可核对的列表，请重新加载来源信箱或收藏分类" }
            timelines.values.distinct().filter { isSamePageList(it.first.url, target) }.forEach { it.reset(fresh) }
            clearSelectionRecovery(target)
            recovery.clearPendingBatchSource(target)
            rememberCachedPage(fresh, target)
            if (page?.url?.let { isSamePageList(it, target) } == true) page = fresh
            managementRevision++
            toast = "列表已重新加载，请核对结果并重新选择；未重复提交操作"
        }
    }
    fun requestFiles(target: String, fieldId: String = "", multiple: Boolean = true, mimeTypes: List<String> = listOf("*/*")) {
        if (!busy && !siteBusy && !purchaseBusy) filePickerRequest = FilePickerRequest(target, fieldId, multiple, mimeTypes)
    }

    // ---- Cross-page batch transaction ------------------------------------

    fun requestBatchSiteSubmit(requests: List<BatchPageRequest>) {
        if (busy || siteBusy || purchaseBusy || batchBusy || dailyBusy || rewardBusy || avatarBusy || pendingBatch != null || pendingSiteDestination != null || pendingSiteSubmission != null || pendingReward != null || pendingAvatar != null || pendingPurchase != null) return
        if (requests.isEmpty() || requests.size > 8) { error = "批量操作的项目数量无效"; return }
        if (requests.map { it.form.sourceUrl }.distinct().size != requests.size) {
            error = "同一来源页只能提交一次，请重新选择"; return
        }
        if (requests.drop(1).any { !isSamePageList(requests.first().form.sourceUrl, it.form.sourceUrl) }) {
            error = "所选项目不属于同一个列表，未提交"; return
        }
        val identities = requests.flatMap { request -> request.items.map { it.identity } }
        if (identities.isEmpty() || identities.distinct().size != identities.size) {
            error = "批量项目重复，请重新选择"; return
        }
        if (requests.any { managementRecoveryFor(it.form.sourceUrl) != null }) {
            error = "有来源页待核对，请先重新加载列表"; return
        }
        if (requests.any { it.items.isEmpty() }) { error = "没有可提交的项目"; return }
        pendingBatch = requests
    }

    fun dismissBatch() { if (!batchBusy) pendingBatch = null }
    fun dismissBatchResult() { batchResult = null }

    fun confirmBatch() {
        val requests = pendingBatch ?: return
        if (batchBusy || busy || siteBusy || purchaseBusy || dailyBusy || rewardBusy || avatarBusy ||
            pendingSiteDestination != null || pendingSiteSubmission != null || pendingReward != null || pendingAvatar != null || pendingPurchase != null) return
        pendingBatch = null
        executeBatch(requests)
    }

    private fun executeBatch(requests: List<BatchPageRequest>) {
        batchBusy = true
        batchResult = null
        error = null
        val expectedOrigin = origin
        val client = service
        val transactionRecovery = recovery
        transactionRecovery.savePendingBatch(PendingBatch(sourcePages = requests.map { it.form.sourceUrl },
            itemIds = requests.flatMap { request -> request.items.map { it.identity } }, createdAt = currentEpochMillis()))
        cancelRequest()
        batchJob = scope.launch {
            val results = mutableListOf<BatchItemResult>()
            val uncertainSources = linkedSetOf<String>()
            var stop = false
            var currentIndex = -1
            var interrupted = false
            try {
                for ((index, request) in requests.withIndex()) {
                    currentIndex = index
                    if (origin != expectedOrigin) break
                    if (stop) {
                        results += request.items.map { BatchItemResult(it, OperationOutcome.NOT_ATTEMPTED, "前一项结果无法确认，本次不再提交") }
                        continue
                    }
                    // A transport that ignores cancellation must not append a
                    // pre-write snapshot over a post-write list.
                    timelines.values.distinct().filter { isSamePageList(it.first.url, request.form.sourceUrl) }.forEach { state ->
                        state.requestId++; state.loading = false; state.refreshing = false
                    }
                    val response = try {
                        client.submitSite(SiteSubmission(request.form, request.values, emptyList(), request.submit))
                    } catch (e: CancellationException) { throw e
                    } catch (_: Exception) {
                        SiteActionResult.Uncertain("请求中断，无法确认服务器是否执行")
                    }
                    when (response) {
                        is SiteActionResult.Rejected -> {
                            results += request.items.map { BatchItemResult(it, OperationOutcome.REJECTED, response.message) }
                            clearSelectionRecovery(request.form.sourceUrl)
                        }
                        is SiteActionResult.Uncertain -> {
                            invalidateManagement(request.form.sourceUrl)
                            uncertainSources.add(request.form.sourceUrl)
                            results += request.items.map { BatchItemResult(it, OperationOutcome.UNCERTAIN, response.message) }
                            stop = true
                        }
                        is SiteActionResult.Response -> {
                            if (response.page.authStatus == AuthStatus.LOGIN_REQUIRED) {
                                invalidateManagement(request.form.sourceUrl)
                                uncertainSources.add(request.form.sourceUrl)
                                results += request.items.map { BatchItemResult(it, OperationOutcome.UNCERTAIN, "登录状态已失效，无法确认提交结果") }
                                stop = true
                            } else {
                                invalidateManagement(request.form.sourceUrl)
                                // Prefer a response that is already a verified
                                // copy of the source list. Otherwise GET exactly
                                // the source page; never infer completion from
                                // the mutation response page or HTTP status.
                                val readback = if (validManagementReadback(request.form.sourceUrl, response.page)) {
                                    response.page
                                } else try {
                                    client.fetch(request.form.sourceUrl)
                                } catch (e: CancellationException) { throw e
                                } catch (_: Exception) { null }
                                if (readback == null || !validManagementReadback(request.form.sourceUrl, readback)) {
                                    uncertainSources.add(request.form.sourceUrl)
                                    results += request.items.map { BatchItemResult(it, OperationOutcome.UNCERTAIN, "操作已返回，但来源列表未能安全读回") }
                                    stop = true
                                    continue
                                }
                                if (pageNumber(readback.url) != pageNumber(request.form.sourceUrl)) {
                                    uncertainSources.add(request.form.sourceUrl)
                                    results += request.items.map { BatchItemResult(it, OperationOutcome.UNCERTAIN,
                                        "删除后分页边界发生变化，无法仅凭当前页确认项目状态") }
                                    stop = true
                                    continue
                                }
                                val remaining = readback.nativePage?.forms.orEmpty()
                                    .filter { isSamePageList(it.sourceUrl, request.form.sourceUrl) }
                                    .flatMap { form -> form.fields.filter { field -> field.name == "delid[]" } }
                                    .flatMap { field -> field.options.map { option -> option.value } }.toSet()
                                request.items.forEach { item ->
                                    val stillThere = item.stableId in remaining
                                    if (!request.verifyAbsent) {
                                        uncertainSources.add(request.form.sourceUrl)
                                        results += BatchItemResult(item, OperationOutcome.UNCERTAIN,
                                            "请求已提交并读回列表；目标状态需人工核对")
                                    } else {
                                        val success = !stillThere
                                        results += BatchItemResult(item,
                                            if (success) OperationOutcome.SUCCESS else OperationOutcome.REJECTED,
                                            if (success) "" else "项目仍在列表中，可能未执行")
                                    }
                                }
                                if (request.verifyAbsent) {
                                    clearSelectionRecovery(request.form.sourceUrl)
                                    transactionRecovery.clearPendingBatchSource(request.form.sourceUrl)
                                }
                                val freshState = timelineFor(readback)
                                freshState.reset(readback)
                                rememberCachedPage(readback, request.form.sourceUrl)
                                if (page?.url?.let { isSamePageList(it, request.form.sourceUrl) } == true) page = readback
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                interrupted = true
                requests.getOrNull(currentIndex)?.let { request ->
                    invalidateManagement(request.form.sourceUrl)
                    uncertainSources.add(request.form.sourceUrl)
                    val known = results.map { it.item.identity }.toSet()
                    request.items.filterNot { it.identity in known }.forEach { item ->
                        results += BatchItemResult(item, OperationOutcome.UNCERTAIN, "操作中断，服务器可能已执行；请先核对来源列表")
                    }
                }
                requests.drop((currentIndex + 1).coerceAtLeast(0)).forEach { request ->
                    request.items.forEach { item ->
                        results += BatchItemResult(item, OperationOutcome.NOT_ATTEMPTED, "前一项中断，本项未自动提交")
                    }
                }
                throw cancelled
            } catch (_: Exception) {
                interrupted = true
                requests.getOrNull(currentIndex)?.let { request ->
                    invalidateManagement(request.form.sourceUrl)
                    uncertainSources.add(request.form.sourceUrl)
                    val known = results.map { it.item.identity }.toSet()
                    request.items.filterNot { it.identity in known }.forEach { item ->
                        results += BatchItemResult(item, OperationOutcome.UNCERTAIN, "处理回执时发生错误，服务器可能已执行")
                    }
                }
                requests.drop((currentIndex + 1).coerceAtLeast(0)).forEach { request ->
                    request.items.forEach { item ->
                        results += BatchItemResult(item, OperationOutcome.NOT_ATTEMPTED, "前一项无法确认，本项未自动提交")
                    }
                }
            } finally {
                if (origin == expectedOrigin) {
                    if (uncertainSources.isNotEmpty()) {
                        transactionRecovery.savePendingBatch(PendingBatch(
                            sourcePages = uncertainSources.toList(),
                            itemIds = results.filter { it.outcome == OperationOutcome.UNCERTAIN }.map { it.item.identity },
                            createdAt = currentEpochMillis(),
                        ))
                    } else if (!interrupted) {
                        transactionRecovery.clearPendingBatch()
                    }
                    if (results.isNotEmpty()) batchResult = classifyBatchResult(results)
                    batchBusy = false
                    if (batchResult != null) toast = batchResult?.summary()
                }
            }
        }
    }

    private fun clearSelectionRecovery(source: String) {
        managementRecoveries = managementRecoveries.filterNot { isSamePageList(it, source) }.toSet()
        recoveryState = recoveryState.copy(
            pendingManagement = recoveryState.pendingManagement.filterNot { isSamePageList(it, source) },
        )
        scheduleRecoveryPersist()
    }

    // ---- Reward / avatar / gugu protocols --------------------------------

    fun requestReward(action: RewardAction) {
        if (rewardBusy || busy || siteBusy || purchaseBusy || batchBusy || dailyBusy || avatarBusy || pendingBatch != null || pendingSiteDestination != null || pendingSiteSubmission != null || pendingAvatar != null || pendingPurchase != null || pendingMessageConfirm) return
        if (resolve(action.sourceUrl).effect != SiteEffect.READ) { error = "奖励来源页面不可信"; return }
        pendingReward = action
    }

    fun dismissReward() { if (!rewardBusy) pendingReward = null }

    fun confirmReward() {
        val action = pendingReward ?: return
        if (rewardBusy || busy || siteBusy || purchaseBusy || batchBusy || dailyBusy || avatarBusy || pendingBatch != null || pendingSiteDestination != null || pendingSiteSubmission != null || pendingAvatar != null || pendingPurchase != null) return
        pendingReward = null
        rewardBusy = true
        error = null
        val expected = origin
        scope.launch {
            try {
                when (val result = service.executeReward(action)) {
                    is RewardExecutionResult.Confirmed -> toast = result.message
                    is RewardExecutionResult.Rejected -> error = result.message
                    is RewardExecutionResult.Uncertain -> error = result.message
                }
                if (origin == expected && action.readbackUrl.startsWith("$origin/")) {
                    // Read-only refresh gives the user a chance to verify the
                    // status; a failure here does not rewrite the receipt.
                    runCatching { service.fetch(action.readbackUrl) }.getOrNull()?.let { fresh ->
                        if (origin == expected) {
                            pageCache[fresh.url] = fresh
                            timelines[fresh.url]?.reset(fresh)
                            if (page?.url == action.readbackUrl || page?.url == fresh.url) page = fresh
                        }
                    }
                }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { error = "奖励操作结果无法确认，请刷新奖励页面核对；不会自动重试" }
            finally { if (origin == expected) rewardBusy = false }
        }
    }

    fun confirmAvatarUpload() {
        val file = pendingAvatar ?: return
        if (siteBusy || purchaseBusy || batchBusy || rewardBusy || dailyBusy || busy || pendingBatch != null || pendingSiteDestination != null || pendingSiteSubmission != null || pendingReward != null || pendingPurchase != null || pendingMessageConfirm) return
        val form = page?.nativePage?.forms?.firstOrNull { form ->
            form.sourceUrl.substringBefore('?').endsWith("profile.php") &&
                form.sourceUrl.contains("action=modify") && form.multipart &&
                form.hidden.any { it.name == "facetype" }
        }
        if (form == null || avatarBusy) { error = "请从修改资料页面重新选择头像"; pendingAvatar = null; return }
        avatarBusy = true
        error = null
        val expected = origin
        val client = service
        scope.launch {
            try {
                when (val result = client.uploadAvatar(AvatarUploadRequest(form, file, 140, 200))) {
                    is AvatarUploadResult.Uploaded -> {
                        pendingAvatar = null
                        val profileUrl = home?.menu?.firstOrNull { it.url.contains("profile.php?action=show") }?.url ?: page?.url
                        if (profileUrl == null) {
                            error = "头像文件已上传，但无法读取资料页确认最终显示；请刷新资料后核对，不要自动重传。"
                            return@launch
                        }
                        val fresh = try { client.fetch(profileUrl) } catch (e: CancellationException) { throw e
                        } catch (_: Exception) { null }
                        if (origin != expected) return@launch
                        val shownAvatar = fresh?.nativePage?.profile?.avatar.orEmpty().substringBefore('?')
                        val uploadedAvatar = result.avatarUrl.substringBefore('?')
                        if (fresh != null) {
                            pageCache[fresh.url] = fresh
                            timelines[fresh.url]?.reset(fresh)
                            accountProfile = fresh.nativePage?.profile ?: accountProfile
                            accountAvatar = fresh.nativePage?.profile?.avatar ?: accountAvatar
                        }
                        if (shownAvatar.isNotBlank() && shownAvatar == uploadedAvatar) {
                            toast = "新头像已在资料页显示"
                        } else {
                            error = "头像文件已上传，但资料页尚未显示对应新头像；资料保存状态未确认，请刷新核对后再决定下一步。"
                        }
                    }
                    is AvatarUploadResult.Rejected -> { pendingAvatar = null; error = result.message }
                    is AvatarUploadResult.Uncertain -> { pendingAvatar = null; error = result.message }
                }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { pendingAvatar = null; error = "头像上传结果无法确认，请刷新资料页核对；不会自动重试" }
            finally { if (origin == expected) avatarBusy = false }
        }
    }

    fun loadGugu(force: Boolean = false) {
        if (guguBusy || (!force && gugu != null)) return
        guguBusy = true
        guguError = null
        val expected = origin
        scope.launch {
            try {
                val snapshot = service.gugu()
                if (origin == expected) {
                    if (snapshot == null) guguError = "咕咕镇登录入口暂不可用，请先在网页版确认游戏入口。"
                    else gugu = snapshot
                }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                if (origin == expected) guguError = "咕咕镇暂时无法读取；游戏数据不会在本机缓存。"
            } finally { if (origin == expected) guguBusy = false }
        }
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
            markDraftChanged()
        } else formFiles = formFiles + ("${request.target}@${request.fieldId}" to files)
    }
    fun removeEditorFile(id: String) {
        editorFiles.firstOrNull { it.id == id }?.let { draftContent = draftContent.replace("[upload=${it.slot}]", "") }
        editorFiles = editorFiles.filterNot { it.id == id }
        markDraftChanged()
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
        pendingBatch = null; batchBusy = false; batchResult = null
        pendingReward = null; rewardBusy = false
        pendingAvatar = null; avatarBusy = false
        gugu = null; guguBusy = false; guguError = null
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
        if (purchaseBusy || siteBusy || batchBusy || rewardBusy || avatarBusy || dailyBusy) return
        cancelRequest(); service.close(); service.clearSession(); home = null; page = null; editor = null
        draftJob?.cancel(); recoveryJob?.cancel(); restoreJob?.cancel(); batchJob?.cancel()
        store.remove("daily_signed@$origin"); dailySigned = false
        avatarJob?.cancel(); accountAvatar = null; accountProfile = null
        profileAvatars.clear(); profileAvatarLoading.clear()
        draftContent = ""; draftTitle = ""; draftRecipient = ""
        draftCategory = ""; draftKeywords = ""; draftAttachmentNotice = null; draftRestored = false
        pendingMentionName = null; pendingMessageConfirm = false
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
    fun close() { cancelRequest(); clearTimelines(); purchaseJob?.cancel(); siteJob?.cancel(); favoritesJob?.cancel(); catalogJob?.cancel(); avatarJob?.cancel(); recoveryJob?.cancel(); draftJob?.cancel(); restoreJob?.cancel(); batchJob?.cancel(); service.close() }
}

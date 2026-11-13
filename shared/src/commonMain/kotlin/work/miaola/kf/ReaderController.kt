package work.miaola.kf

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import work.miaola.kf.ui.KfDestinationKind
import work.miaola.kf.ui.KfDestinations

internal fun AppController.clearNativeTransientState() {
        pendingBatch = null; batchBusy = false; batchResult = null
        pendingReward = null; rewardBusy = false
        pendingAvatar = null; avatarBusy = false
        gugu = null; guguBusy = false; guguError = null
        guguActionBusy = false; pendingGuguAction = null; guguActionInput = ""
        guguActionValues = emptyMap()
        guguMenu = null
        messageExportBusy = false; fileSaveRequest = null
        lastSearchKeyword = ""; lastSearchAuthor = ""
        messageListUrl = null
        showSiteTools = false; pendingNativePage = null; pendingSiteDestination = null; pendingSiteSubmission = null
        showTopicFilters = false
        dailyStatusJob?.cancel(); dailyStatusJob = null
        dailyBusy = false
        filePickerRequest = null; formFiles = emptyMap(); editorFiles = emptyList(); gallery = null
    }

fun AppController.presentNativePage(result: PageData) {
        rememberNavigationSource(); page = result; rememberCachedPage(result, allowLoginWall = true); showSiteTools = true
        pendingNativePage = result.url; gallery = null
        if (result.authStatus == AuthStatus.LOGIN_REQUIRED) home = home?.copy(authenticated = false, authStatus = AuthStatus.LOGIN_REQUIRED)
    }

    /** Read only. Never replay a delete/move, even if the previous result was unknown. */
fun AppController.recoverManagement(source: String) {
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

internal fun AppController.invalidateManagement(source: String) {
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

internal fun AppController.validManagementReadback(source: String, fresh: PageData): Boolean {
        if (fresh.authStatus != AuthStatus.AUTHENTICATED || resolve(fresh.url).effect != SiteEffect.READ || !isSamePageList(source, fresh.url)) return false
        val native = fresh.nativePage
        return when {
            source.substringBefore('?').endsWith("profile.php") -> native?.favorites != null || fresh.topics.isNotEmpty()
            source.contains("action=banned") -> native?.blocklist != null
            else -> native?.hasMessageList == true || fresh.topics.isNotEmpty()
        }
    }

fun AppController.back() {
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

fun AppController.requestBack() {
        if (purchaseBusy || siteBusy) return
        if (editor != null && busy && !loadingEditor) return
        backLayers.firstOrNull { it.available() }?.let { layer -> layer.dismiss(); return }
        if (editorDirty) confirmDiscard = true
        else back()
    }

internal fun AppController.restoreContinuation(first: PageData) {
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
            // A refresh resets the same PageTimeline in place; a stale restore
            // loop must not append pages after that reset.
            val requestId = state.requestId
            var previous = first
            for (candidate in pages) {
                if (requestId != state.requestId) return@launch
                if (origin != expected || page?.url?.let { !isSamePageList(it, first.url) } == true) return@launch
                val cached = pageFor(candidate)
                if (cached != null) { previous = cached; continue }
                val result = try { client.fetch(candidate) } catch (e: CancellationException) { throw e
                } catch (_: Exception) { break }
                if (requestId != state.requestId) return@launch
                if (origin != expected || handleTimelineLogin(result, snapshot.rootUrl)) return@launch
                if (!isSamePageList(first.url, result.url) || !isPageContinuation(previous.url, result.url)) break
                refreshPurchaseState(result)
                rememberCachedPage(result, candidate)
                state.append(result, candidate)
                previous = result
            }
            if (requestId == state.requestId) rememberTimeline(state)
        }
    }

internal fun AppController.scheduleRecoveryPersist() {
        recoveryJob?.cancel()
        recoveryJob = scope.launch {
            delay(500)
            runCatching { recovery.saveState(recoveryState) }
        }
    }

internal fun AppController.rememberTimeline(state: PageTimeline) {
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

fun AppController.rememberAnchor(rootUrl: String, index: Int, offset: Int, key: String = "") {
        if (rootUrl.isBlank()) return
        val previous = recoveryState.timelines[rootUrl]
        val value = TimelineRecovery(rootUrl, previous?.loadedPageUrls.orEmpty(), index.coerceAtLeast(0),
            offset, key.take(160), currentEpochMillis())
        if (previous?.anchorIndex == value.anchorIndex && previous.anchorOffset == value.anchorOffset &&
            previous.anchorKey == value.anchorKey) return
        recoveryState = recoveryState.copy(timelines = recoveryState.timelines + (rootUrl to value))
        scheduleRecoveryPersist()
    }

fun AppController.rememberDestination(kind: String, url: String? = null, query: String = "", author: String = "") {
        val next = DestinationRecovery(kind, url.orEmpty().take(2_048), query.take(200), author.take(200))
        if (recoveryState.destination != next) {
            recoveryState = recoveryState.copy(destination = next)
            runCatching { recovery.saveState(recoveryState) }
        }
    }

fun AppController.ensureSearchResult(url: String) {
        if (pageFor(url) != null || busy) return
        val destination = resolve(url)
        if (!KfDestinations.isSearchNavigation(destination.url) ||
            !destination.url.startsWith("$origin/search.php?")) return
        work {
            val result = service.fetch(destination.url)
            if (!it()) return@work
            if (!handleTimelineLogin(result, url)) rememberCachedPage(result, url)
        }
    }

    /** Search destinations reuse their result; a new keyword still costs one request. */
fun AppController.ensureSearch(cacheKey: String, keyword: String, author: String) {
        if (pageCache[cacheKey] != null) return
        if (busy) return
        search(keyword, author, cacheKey, push = false)
    }

fun AppController.search(keyword: String, author: String = "", cacheKey: String? = null, push: Boolean = true) {
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

internal fun AppController.clearFavorites() {
        favoritesJob?.cancel()
        siteFavorites = null
        favoritesError = null
        favoritesBusy = false
    }

fun AppController.favoriteThread(destination: SiteDestination?) {
        if (destination == null || writeBusy || pendingBatch != null || pendingReward != null || pendingAvatar != null || pendingPurchase != null) return
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

fun AppController.loadAvatar(authorUrl: String) {
        if (authorUrl.isBlank() || !authorUrl.startsWith(origin)) return
        val key = avatarKey(authorUrl)
        if (profileAvatars.containsKey(key) || key in profileAvatarMisses || !profileAvatarLoading.add(key)) return
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
                if (origin == expectedOrigin) {
                    // Remember misses too: scrolling must not refetch the same
                    // profile on every recomposition. Pull-to-refresh clears them.
                    if (avatar.isNotBlank()) profileAvatars[key] = avatar else profileAvatarMisses.add(key)
                }
            } finally {
                profileAvatarLoading.remove(key)
            }
        }
    }

fun AppController.selectTab(value: HomeTab) {
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

fun AppController.setActiveTab(value: HomeTab) {
        if (tab != value) tab = value
    }

fun AppController.navigate(url: String) {
        if (siteBusy || purchaseBusy) return
        val destination = service.destination(url)
        if (destination.effect != SiteEffect.READ) { openSiteDestination(destination); return }
        // Route through the shared classifier so the controller, the Android
        // shell and the desktop harness cannot disagree about what a URL is.
        when (KfDestinations.fromUrl(url)) {
            KfDestinationKind.EDITOR -> openEditor(url)
            KfDestinationKind.FEED, KfDestinationKind.LISTING, KfDestinationKind.READER,
            KfDestinationKind.SEARCH, KfDestinationKind.GUGU -> load(url)
            KfDestinationKind.MESSAGES -> { rememberNavigationSource(); tab = HomeTab.MESSAGES; page = null; load(url, push = false) }
            KfDestinationKind.MESSAGE, KfDestinationKind.PROFILE, KfDestinationKind.NATIVE,
            KfDestinationKind.LOGIN -> openSiteDestination(destination)
            KfDestinationKind.BOARDS, KfDestinationKind.ACCOUNT, KfDestinationKind.SETTINGS -> load(url)
        }
    }

fun AppController.load(url: String = page?.url ?: "index.php", push: Boolean = true) {
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

internal fun AppController.rememberMailbox(active: PageData) {
        if (active.url.substringBefore('?').endsWith("/message.php") &&
            (KfDestinations.fromUrl(active.url) == KfDestinationKind.MESSAGES ||
                active.url.contains("action=scout") && active.nativePage?.hasMessageList == true))
            messageListUrl = active.url
    }

fun AppController.activatePage(active: PageData) {
        if (page?.url != active.url || page !== active) page = active
        rememberMailbox(active)
    }

fun AppController.ensurePage(url: String, force: Boolean = false) {
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

internal fun AppController.applyFreshPage(fresh: PageData) {
        pageCache.keys.filter { isSamePageList(it, fresh.url) }.toList().forEach(pageCache::remove)
        pageCache[fresh.url] = fresh
        timelines.values.distinct().filter { isSamePageList(it.first.url, fresh.url) }.forEach { it.reset(fresh) }
        if (page?.url?.let { isSamePageList(it, fresh.url) } == true) page = fresh
        if (home?.url?.let { isSamePageList(it, fresh.url) } == true) home = fresh
        if (siteFavorites?.url?.let { isSamePageList(it, fresh.url) } == true) siteFavorites = fresh
    }

internal fun AppController.rememberCachedPage(result: PageData, requestedUrl: String? = null, allowLoginWall: Boolean = false) {
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

internal fun AppController.rememberNavigationSource() {
        val source = page to tab
        if (!busy || backStack.lastOrNull() != source) backStack.add(source)
    }

internal fun AppController.clearTimelines() {
        timelineJobs.values.forEach { it.cancel() }
        timelineJobs.clear(); timelines.clear()
    }

internal fun AppController.handleTimelineLogin(result: PageData, returnUrl: String): Boolean {
        if (result.authStatus != AuthStatus.LOGIN_REQUIRED) return false
        loginReturnUrl = loginDestination(returnUrl)
        home = home?.copy(authenticated = false, authStatus = AuthStatus.LOGIN_REQUIRED)
        showLogin = true
        sessionNotice = "登录已失效，请重新登录"
        return true
    }

fun AppController.refreshTimeline(first: PageData, search: Pair<String, String>? = null) {
        managementRecoveryFor(first.url)?.let { recoverManagement(it); return }
        if (!safeTimelineUrl(first.url)) { toast = "请重新打开页面后刷新"; return }
        val state = timelineFor(first)
        if (state.refreshing || purchaseBusy || siteBusy) return
        // An explicit refresh is the retry path for avatars that previously
        // resolved to nothing.
        profileAvatarMisses.clear()
        val requestId = ++state.requestId
        timelineJobs.remove(first.url)?.cancel()
        restoreJob?.cancel()
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

fun AppController.loadNext(first: PageData, retry: Boolean = false) {
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

internal fun AppController.safeTimelineUrl(url: String): Boolean {
        val destination = resolve(url)
        return destination.effect == SiteEffect.READ ||
            (destination.effect == SiteEffect.CHANGE && destination.url.startsWith("$origin/search.php?") &&
                KfDestinations.isSearchNavigation(destination.url))
    }

fun AppController.timelineFor(first: PageData): PageTimeline {
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

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
    internal val factory: ServiceFactory,
    internal val scope: CoroutineScope,
    internal val browser: (String, String) -> Unit,
    defaultConfigUrl: String = "",
) {
    internal val domains = DomainRepository(store, factory)
    var catalog by mutableStateOf(domains.local()); internal set
    var configUrl by mutableStateOf(store.get("config_url") ?: defaultConfigUrl); internal set
    /** Config refresh has no page to show progress in; surface it as feedback. */
    var catalogBusy by mutableStateOf(false); internal set
    internal var catalogRefreshVersion = 0L
    /** Number of active batch-management screens; their pinned bar owns the bottom area. */
    var managementScreenCount by mutableStateOf(0); internal set
    val managementActive: Boolean get() = managementScreenCount > 0
    var origin by mutableStateOf(runCatching { normalizeOrigin(store.get("selected_origin") ?: catalog.catalog.domains.first { it.id == catalog.catalog.defaultDomainId }.origin) }.getOrDefault("https://kf.miaola.work")); internal set
    var theme by mutableStateOf(runCatching { ThemeMode.valueOf(store.get("theme") ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM)); internal set
    var page by mutableStateOf<PageData?>(null); internal set
    var home by mutableStateOf<PageData?>(null); internal set
    var tab by mutableStateOf(HomeTab.FEED); internal set
    var busy by mutableStateOf(false); internal set
    var loadingEditor by mutableStateOf(false); internal set
    private var errorState by mutableStateOf<String?>(null)
    /**
     * Operation feedback. Displayed once by the snackbar host, which then
     * consumes it; page-load failures are mirrored into [pageError] so an
     * unrelated message can never become a page's "load failed" reason.
     */
    var error: String?
        get() = errorState
        internal set(value) {
            if (value != null) errorRevision++
            errorState = value
        }
    internal var errorRevision by mutableStateOf(0L); private set
    /** Last page-load failure only. Cleared when the next request starts. */
    var pageError by mutableStateOf<String?>(null); internal set
    private var toastState by mutableStateOf<String?>(null)
    var toast: String?
        get() = toastState
        internal set(value) {
            if (value != null) toastRevision++
            toastState = value
        }
    internal var toastRevision by mutableStateOf(0L); private set
    var showSettings by mutableStateOf(false)
    var showLogin by mutableStateOf(false)
    var editor by mutableStateOf<EditorForm?>(null); internal set
    var draftTitle by mutableStateOf("")
    var draftContent by mutableStateOf("")
    var draftRecipient by mutableStateOf("")
    var draftCategory by mutableStateOf("")
    var draftKeywords by mutableStateOf("")
    var draftAttachmentNotice by mutableStateOf<String?>(null); internal set
    var draftRestored by mutableStateOf(false); internal set
    var pendingMentionName by mutableStateOf<String?>(null); internal set
    var pendingMessageConfirm by mutableStateOf(false); internal set
    var submitUncertain by mutableStateOf(false); internal set
    var confirmDiscard by mutableStateOf(false)
    var pendingPurchase by mutableStateOf<PurchaseOffer?>(null); internal set
    var purchaseBusy by mutableStateOf(false); internal set
    var uncertainPurchases by mutableStateOf<Set<String>>(emptySet()); internal set
    var sessionNotice by mutableStateOf<String?>(null); internal set
    var showEmoticonPicker by mutableStateOf(false)
    var showSiteTools by mutableStateOf(false)
    var showTopicFilters by mutableStateOf(false)
    var dailyBusy by mutableStateOf(false); internal set
    var dailySigned by mutableStateOf(store.get("daily_signed@$origin") == localDayKey()); internal set
    var pendingNativePage by mutableStateOf<String?>(null); internal set
    var pendingSiteDestination by mutableStateOf<SiteDestination?>(null); internal set
    var pendingSiteSubmission by mutableStateOf<SiteSubmission?>(null); internal set
    var siteBusy by mutableStateOf(false); internal set
    var managementRevision by mutableStateOf(0); internal set
    var managementRecoveries by mutableStateOf<Set<String>>(emptySet()); internal set
    var pendingBatch by mutableStateOf<List<BatchPageRequest>?>(null); internal set
    var batchBusy by mutableStateOf(false); internal set
    var batchResult by mutableStateOf<BatchTransactionResult?>(null); internal set
    var pendingReward by mutableStateOf<RewardAction?>(null); internal set
    var rewardBusy by mutableStateOf(false); internal set
    var pendingAvatar by mutableStateOf<LocalUpload?>(null); internal set
    var avatarPickerRequested by mutableStateOf(false); internal set
    var avatarBusy by mutableStateOf(false); internal set
    var gugu by mutableStateOf<GuguSnapshot?>(null); internal set
    var guguBusy by mutableStateOf(false); internal set
    var guguError by mutableStateOf<String?>(null); internal set
    var guguActionBusy by mutableStateOf(false); internal set
    var pendingGuguAction by mutableStateOf<GuguAction?>(null); internal set
    var guguActionInput by mutableStateOf(""); internal set
    var guguActionValues by mutableStateOf<Map<String, String>>(emptyMap()); internal set
    var guguMenu by mutableStateOf<GuguMenu?>(null); internal set
    var messageExportBusy by mutableStateOf(false); internal set
    var fileSaveRequest by mutableStateOf<NativeFileSave?>(null); internal set
    var filePickerRequest by mutableStateOf<FilePickerRequest?>(null); internal set
    var downloadRequest by mutableStateOf<NativeDownloadRequest?>(null); internal set
    var editorFiles by mutableStateOf<List<LocalUpload>>(emptyList()); internal set
    var formFiles by mutableStateOf<Map<String, List<LocalUpload>>>(emptyMap()); internal set
    var gallery by mutableStateOf<ImageGallery?>(null); internal set
    /** Gallery-local note. A snackbar error must not leak into the photo viewer. */
    var galleryNotice by mutableStateOf<String?>(null); internal set
    var accountAvatar by mutableStateOf<String?>(null); internal set
    var accountProfile by mutableStateOf<NativeProfile?>(null); internal set
    var fontScale by mutableStateOf(store.get("font_scale")?.toFloatOrNull()?.coerceIn(0.85f, 1.4f) ?: 1f); internal set
    var bookmarks by mutableStateOf(readBookmarks()); internal set
    var history by mutableStateOf(readHistory()); internal set
    var siteFavorites by mutableStateOf<PageData?>(null); internal set
    var favoritesBusy by mutableStateOf(false); internal set
    var favoritesError by mutableStateOf<String?>(null); internal set
    var lastSearchKeyword by mutableStateOf(""); internal set
    var lastSearchAuthor by mutableStateOf(""); internal set
    internal var service = factory.create(origin)
    internal var recovery = RecoveryStore(store, origin)
    internal var recoveryState = recovery.loadState() ?: RecoveryState(origin = origin)
    internal var recoveryJob: Job? = null
    internal var draftJob: Job? = null
    internal var restoreJob: Job? = null
    internal var batchJob: Job? = null
    private var requestJob: Job? = null
    private var requestVersion = 0
    internal var loadingUrl: String? = null
    internal var catalogJob: Job? = null
    internal var favoritesJob: Job? = null
    internal var dailyStatusJob: Job? = null
    internal var purchaseJob: Job? = null
    internal var siteJob: Job? = null
    internal var avatarJob: Job? = null
    internal var dailyClaimJob: Job? = null
    /**
     * Every write path consults one gate. Adding a new mutation must not mean
     * re-enumerating every individual busy flag at each call site.
     */
    internal val writeBusy: Boolean
        get() = siteBusy || purchaseBusy || batchBusy || rewardBusy || avatarBusy ||
            dailyBusy || messageExportBusy || guguActionBusy
    internal val profileAvatars = mutableStateMapOf<String, String>()
    internal val profileAvatarLoading = mutableSetOf<String>()
    /** Authors already checked without a usable avatar; retried on explicit refresh. */
    internal val profileAvatarMisses = mutableSetOf<String>()
    internal val profileAvatarLimit = Semaphore(2)
    internal var loginReturnUrl: String? = null
    internal var nextUploadSlot = 1
    internal var messageListUrl: String? = null
    // Desktop-harness navigation state. The Android shell owns the real back
    // stack in Navigation Compose and only calls requestBack() for the editor
    // guard; [canBack] and [backStack] are what the headless harness renders.
    internal val backStack = mutableStateListOf<Pair<PageData?, HomeTab>>()
    // Destinations keep their own page snapshot so predictive back can finish
    // over the correct page without re-fetching the thread.
    internal val pageCache = mutableStateMapOf<String, PageData>()
    internal val timelines = mutableMapOf<String, PageTimeline>()
    internal val timelineJobs = mutableMapOf<String, Job>()
    internal val tabReselections = mutableStateMapOf<HomeTab, Int>()
    fun reselectTab(tab: HomeTab) { tabReselections[tab] = reselectCount(tab) + 1 }
    fun reselectCount(tab: HomeTab) = tabReselections[tab] ?: 0

    val authenticated get() = home?.authenticated == true
    val editorDirty: Boolean get() {
        val form = editor ?: return false
        return draftTitle != form.fields[form.titleField].orEmpty() ||
            draftContent != form.fields[form.contentField].orEmpty() ||
            draftRecipient != form.fields[form.recipientField].orEmpty() ||
            draftCategory != form.fields["p_type"].orEmpty() ||
            draftKeywords != form.fields["diy_guanjianci"].orEmpty() || editorFiles.isNotEmpty()
    }
    /**
     * One ordered list of dismissible layers. Both [canBack] and [requestBack]
     * derive from it, so a new overlay cannot be added to one and forgotten in
     * the other. Order is the same as the visible stacking order.
     */
    internal val backLayers: List<BackLayer>
        get() = listOf(
            BackLayer({ gallery != null }) { closeImages() },
            BackLayer({ pendingMessageConfirm }) { dismissMessageConfirm() },
            BackLayer({ pendingBatch != null }) { dismissBatch() },
            BackLayer({ pendingReward != null }) { dismissReward() },
            BackLayer({ pendingAvatar != null }) { cancelAvatarUpload() },
            BackLayer({ pendingSiteSubmission != null || pendingSiteDestination != null }) { dismissSiteAction() },
            BackLayer({ showSiteTools }) { showSiteTools = false },
            BackLayer({ showSettings }) { showSettings = false },
            BackLayer({ showLogin }) { showLogin = false; error = null },
            BackLayer({ showEmoticonPicker }) { showEmoticonPicker = false },
            BackLayer({ pendingPurchase != null }) { pendingPurchase = null },
            BackLayer({ loadingEditor }) { cancelRequest() },
        )

    val canBack: Boolean
        get() = !purchaseBusy && !siteBusy &&
            (backLayers.any { it.available() } ||
                (editor != null && !(busy && !loadingEditor)) ||
                backStack.isNotEmpty())

    internal class BackLayer(val available: () -> Boolean, val dismiss: () -> Unit)

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

    /** [loadsPage] writes [pageError] as well, for the destination's retry UI. */
    internal fun work(editorRequest: Boolean = false, loadsPage: Boolean = false, task: suspend (() -> Boolean) -> Unit) {
        val version = ++requestVersion
        val expectedOrigin = origin
        loadingEditor = editorRequest
        requestJob?.cancel()
        requestJob = scope.launch {
            busy = true; error = null; if (loadsPage) pageError = null
            try { task { version == requestVersion && expectedOrigin == origin && isActive } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (version == requestVersion) {
                    error = friendlyError(e)
                    if (loadsPage) pageError = error
                }
            }
            finally { if (version == requestVersion) { busy = false; loadingEditor = false; loadingUrl = null } }
        }
    }

    internal fun cancelRequest() { requestVersion++; requestJob?.cancel(); busy = false; loadingEditor = false; loadingUrl = null }

    /** A destination's own page, retained across predictive back and tab switches. */
    fun pageFor(url: String): PageData? = pageCache[url] ?: pageCache[url.substringBefore('#')]
        ?: timelines[url.substringBefore('#')]?.first
        ?: timelines[resolve(url).url.substringBefore('#')]?.first

    fun resolve(url: String, title: String = ""): SiteDestination = service.destination(url, title)

    internal fun pageNumber(url: String): Int =
        Regex("[?&]page=([0-9]+)(?:&|$)").find(url.substringBefore('#'))?.groupValues?.get(1)?.toIntOrNull() ?: 1

    /** Reader avatars are read-only profile lookups, capped and cached in memory. */
    fun avatarFor(authorUrl: String): String? = profileAvatars[avatarKey(authorUrl)]

    internal fun avatarKey(authorUrl: String): String =
        Regex("[?&]uid=([0-9]+)").find(authorUrl)?.groupValues?.get(1)?.let { "uid:$it" } ?: authorUrl

    internal fun favoritesUrl(): String =
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

    internal fun kfbAmount(profile: NativeProfile?): Long = profile?.info
        ?.firstOrNull { info -> info.value.contains("KFB", true) }
        ?.value?.replace(",", "")?.let { Regex("[0-9]+").find(it)?.value?.toLongOrNull() }
        ?: 0L

    internal fun visiblePosts(): List<ForumPost> = page?.let { timelines[it.url]?.posts ?: it.posts }.orEmpty()

    fun dismissPurchase() { if (!purchaseBusy) pendingPurchase = null }

    // ---- Process-recovery state (reconstructable only) -------------------

    fun recoveredDestination(): DestinationRecovery? = recoveryState.destination.takeIf { it.kind.isNotBlank() }

    fun anchorFor(rootUrl: String): TimelineRecovery? = recoveryState.timelines.entries
        .firstOrNull { isSamePageList(it.key, rootUrl) || isSamePageList(it.value.rootUrl, rootUrl) }?.value

    internal fun recoveredTimelineFor(url: String): TimelineRecovery? = recoveryState.timelines.entries
        .firstOrNull { isSamePageList(it.key, url) || isSamePageList(it.value.rootUrl, url) }?.value

    fun updateDraftTitle(value: String) { draftTitle = value; scheduleDraftSave() }
    fun updateDraftContent(value: String) { draftContent = value; scheduleDraftSave() }
    fun updateDraftRecipient(value: String) { draftRecipient = value; scheduleDraftSave() }
    fun updateDraftCategory(value: String) { draftCategory = value; scheduleDraftSave() }
    fun updateDraftKeywords(value: String) { draftKeywords = value; scheduleDraftSave() }
    fun markDraftChanged() = scheduleDraftSave()

    fun consumeAvatarPickerRequest() { avatarPickerRequested = false }

    fun cancelAvatarUpload() { if (!avatarBusy) { pendingAvatar = null; avatarPickerRequested = false } }

    fun dismissMessageConfirm() { pendingMessageConfirm = false }

    fun updateTheme(value: ThemeMode) { theme = value; store.put("theme", value.name) }
    /** Slider motion only updates memory; the value is persisted when it settles. */
    fun updateFontScale(value: Float) { fontScale = value }

    fun commitFontScale() { store.put("font_scale", fontScale.toString()) }
    fun dismissSiteAction() { if (!siteBusy) { pendingSiteSubmission = null; pendingSiteDestination = null } }

    fun managementRecoveryFor(url: String): String? = managementRecoveries.firstOrNull { isSamePageList(resolve(url).url, it) }

    // ---- Cross-page batch transaction ------------------------------------

    fun dismissBatch() { if (!batchBusy) pendingBatch = null }
    fun dismissBatchResult() { batchResult = null }

    // ---- Reward / avatar / gugu protocols --------------------------------

    fun dismissReward() { if (!rewardBusy) pendingReward = null }

    fun consumeFileSaveRequest() { fileSaveRequest = null }

    fun clearGuguError() { guguError = null }

    fun guguActionValue(name: String): String = guguActionValues[name].orEmpty()

    fun closeImages() { gallery = null; galleryNotice = null }
    fun saveImage(url: String) { error = null; downloadRequest = NativeDownloadRequest(url, url.substringBefore('?').substringAfterLast('/').ifBlank { "image.jpg" }, true) }
    fun consumeDownloadRequest() { downloadRequest = null }
    fun reportPlatformResult(message: String, failed: Boolean = false) {
        if (failed) {
            error = message
            if (gallery != null) galleryNotice = message
        } else toast = message
    }
    fun consumeNativePage() { pendingNativePage = null }
    fun clearMessage() { toast = null }
    fun dismissSessionNotice() { sessionNotice = null }
    fun clearError() { error = null }
    /** Called by the snackbar host once the message has actually been shown. */
    internal fun consumeError(revision: Long) { if (revision == errorRevision) errorState = null }
    internal fun consumeToast(revision: Long) { if (revision == toastRevision) toastState = null }

    // URL/title lists are stored in the encrypted platform store, scoped by origin.
    internal fun readLinks(key: String): List<SiteLink> = store.get("$key@$origin")?.lineSequence()?.mapNotNull {
        val parts = it.split('\t', limit = 2)
        if (parts.size == 2) SiteLink(parts[1], parts[0]) else null
    }?.toList().orEmpty()
    internal fun readBookmarks() = readLinks("bookmarks")
    internal fun readHistory() = readLinks("history")
    internal fun saveLinks(key: String, links: List<SiteLink>) = store.put("$key@$origin", links.joinToString("\n") { "${it.url.replace("\n", "").replace("\t", "")}\t${it.title.replace("\n", " ").replace("\t", " ")}" })
    fun clearHistory() { history = emptyList(); store.remove("history@$origin") }
    fun close() { cancelRequest(); clearTimelines(); purchaseJob?.cancel(); siteJob?.cancel(); favoritesJob?.cancel(); catalogJob?.cancel(); avatarJob?.cancel(); recoveryJob?.cancel(); draftJob?.cancel(); restoreJob?.cancel(); batchJob?.cancel(); service.close() }
}
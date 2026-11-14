@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package work.miaola.kf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import work.miaola.kf.*

/**
 * Desktop render/test harness. Android ships [work.miaola.kf.nav.KfRoot] and
 * Navigation Compose as its only shell; this state-driven shell exists so the
 * shared screens can be rendered headlessly without an emulator.
 */
@Composable
fun KfApp(c: AppController, forumImage: ForumImage = { _, label, modifier -> Box(modifier, contentAlignment = Alignment.Center) { Text(label, style = MaterialTheme.typography.labelSmall) } }, forumAvatar: ForumImage = forumImage, richContent: RichContent) {
    KfTheme(c.theme) {
        val snack = remember { SnackbarHostState() }
        var search by remember { mutableStateOf(false) }
        var logout by remember { mutableStateOf(false) }
        val savedScreens = rememberSaveableStateHolder()
        val actions = remember(c) {
            KfActions(
                open = c::navigate,
                openSettings = { c.showSettings = true },
                openLogin = { c.showLogin = true },
                openSearch = { search = true },
                openNative = { url ->
                    when {
                        url == c.page?.url -> c.showSiteTools = true
                        url.substringBefore('?').endsWith("login.php") -> c.openNativeLoginOptions()
                        else -> c.openBrowser(url)
                    }
                },
                selectTab = c::selectTab,
                back = { if (search) search = false else c.requestBack() },
            )
        }
        KfOperationFeedback(c, snack, c.page != null || c.editor != null)
        val isOverlay = search || c.showSettings || c.showLogin || c.editor != null
        val pageUrl = c.page?.url.orEmpty()
        val pageKind = KfDestinations.fromUrl(pageUrl)
        val nativePage = KfDestinations.isNativePage(c.page) || c.page?.nativePage?.favorites != null
        val nativeTitle = c.page?.nativePage?.title?.takeIf { c.showSiteTools || nativePage }
        val readerTitle = c.page?.takeIf { it.posts.isNotEmpty() }?.title
        val detailDestination = c.showSiteTools || nativePage ||
            c.page?.posts?.isNotEmpty() == true || KfDestinations.isDetailDestination(pageUrl)
        val showBottomBar = !isOverlay && !detailDestination
        val shellKind = when {
            c.editor != null -> KfDestinationKind.EDITOR
            c.showSettings -> KfDestinationKind.SETTINGS
            c.showLogin -> KfDestinationKind.LOGIN
            detailDestination -> pageKind
            c.tab == HomeTab.ACCOUNT -> KfDestinationKind.ACCOUNT
            c.tab == HomeTab.MESSAGES -> KfDestinationKind.MESSAGES
            c.tab == HomeTab.BOARDS -> KfDestinationKind.BOARDS
            else -> KfDestinationKind.FEED
        }
        val shellTitle = when {
            c.showSettings -> "偏好与连接"
            c.showSiteTools -> nativeTitle ?: "更多功能"
            c.showLogin -> "登录绯月"
            c.editor != null -> "写点什么"
            nativeTitle != null -> nativeTitle
            readerTitle != null -> readerTitle
            else -> "绯月"
        }
        Scaffold(
            topBar = {
                if (!search) KfTopBar(
                    title = shellTitle,
                    root = showBottomBar,
                    showBack = c.canBack,
                    editor = c.editor != null,
                    kind = shellKind,
                    c = c,
                    page = c.page,
                    actions = actions,
                    onBack = actions.back,
                    onRefresh = { c.page?.let(c::refreshTimeline) },
                )
            },
            bottomBar = {
                if (showBottomBar) KfBottomBar(shellKind, actions)
            },
            floatingActionButton = {
                if (!c.managementActive && !isOverlay && c.page?.composeUrl != null && (c.page?.posts?.isNotEmpty() == true ||
                    c.page?.url?.substringBefore('?')?.endsWith("thread.php") == true ||
                    c.page?.url?.let { it.substringBefore('?').endsWith("message.php") && !it.contains("action=read") && !it.contains("action=banned") && !it.contains("action=scout") } == true)) ExtendedFloatingActionButton(
                    onClick = { c.page?.composeUrl?.let(actions.open) },
                    icon = { Icon(Icons.Default.Edit, null) },
                    text = { Text(if (c.page?.posts?.isNotEmpty() == true) "回复" else if (c.tab == HomeTab.MESSAGES || c.page?.url?.contains("message.php") == true) "写私信" else "发主题") },
                )
            },
            snackbarHost = { SnackbarHost(snack) },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {
                KfStatusBanners(c)
                val screenKey = if (search) "${c.origin}|search" else "${c.origin}|${c.tab}|${c.page?.url}|${c.showSettings}|${c.showLogin}|${c.editor?.sourceUrl}"
                savedScreens.SaveableStateProvider(screenKey) { when {
                    search -> SearchScreen(c, actions)
                    c.showSettings -> SettingsScreen(c)
                    c.showSiteTools -> NativeSiteScreen(c, c.page, actions, richContent, forumImage, forumAvatar)
                    c.showLogin -> LoginScreen(c, actions)
                    c.editor != null -> EditorScreen(c, forumImage)
                    c.tab == HomeTab.ACCOUNT && (c.page == c.home || c.page == null) -> AccountScreen(c, actions, forumAvatar, onLogout = { logout = true })
                    c.page == null -> FeedScreen(c, actions, c.home)
                    KfDestinations.isNativePage(c.page) -> NativeSiteScreen(c, c.page, actions, richContent, forumImage, forumAvatar)
                    pageKind == KfDestinationKind.READER || c.page?.posts?.isNotEmpty() == true -> ReaderScreen(c, actions, c.page, forumAvatar, richContent, showTitle = false)
                    pageKind == KfDestinationKind.LISTING -> ListingScreen(c, actions, c.page, isMessages = false, rich = richContent)
                    pageKind == KfDestinationKind.GUGU -> GuguScreen(c, actions)
                    pageKind == KfDestinationKind.MESSAGES -> ListingScreen(c, actions, c.page, isMessages = true, rich = richContent)
                    pageKind == KfDestinationKind.SEARCH -> ListingScreen(c, actions, c.page, isMessages = false, rich = richContent)
                    c.tab == HomeTab.BOARDS -> BoardsScreen(c, actions, c.home ?: c.page)
                    c.tab == HomeTab.MESSAGES -> ListingScreen(c, actions, c.page, isMessages = true, rich = richContent)
                    else -> FeedScreen(c, actions, c.page)
                } }
            }
        }
        KfGlobalDialogs(c, logout = logout, onLogoutChange = { logout = it })
    }
}

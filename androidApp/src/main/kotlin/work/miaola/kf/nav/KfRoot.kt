@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package work.miaola.kf.nav

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import work.miaola.kf.AppController
import work.miaola.kf.HomeTab
import work.miaola.kf.PageData
import work.miaola.kf.SiteDestination
import work.miaola.kf.SiteEffect
import work.miaola.kf.ui.*
import work.miaola.kf.ui.KfDestinations
import work.miaola.kf.ui.KfDestinationKind

@Composable
fun KfRoot(
    c: AppController,
    forumImage: ForumImage,
    forumAvatar: ForumImage,
    richContent: RichContent,
) {
    key(c.origin) { KfRootForServer(c, forumImage, forumAvatar, richContent) }
}

@Composable
private fun KfRootForServer(
    c: AppController,
    forumImage: ForumImage,
    forumAvatar: ForumImage,
    richContent: RichContent,
) {
    KfTheme(c.theme) {
        val nav = rememberNavController()
        val backStackEntry by nav.currentBackStackEntryAsState()
        val location = backStackEntry?.kfLocation() ?: KfLocation(KfDestinationKind.FEED)
        val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
        val snackbar = remember { SnackbarHostState() }
        var logout by remember { mutableStateOf(false) }
        // A pristine editor lets NavHost own predictive back. Clear its form
        // only after the current entry actually changes, never on a cancelled
        // predictive gesture or when an underlying destination is composed.
        DisposableEffect(backStackEntry) {
            val leavingEditor = location.kind == KfDestinationKind.EDITOR
            onDispose {
                if (leavingEditor && nav.currentBackStackEntry?.kfLocation()?.kind !in setOf(KfDestinationKind.EDITOR, KfDestinationKind.LOGIN) &&
                    !c.editorDirty && !c.busy) c.dismissEditor()
            }
        }

        fun go(route: String) {
            nav.navigate(route) { launchSingleTop = true }
        }

        fun replace(route: String) {
            nav.navigate(route) {
                popUpTo(nav.currentDestination?.id ?: 0) { inclusive = true }
                launchSingleTop = true
            }
        }

        fun goRoot(route: String, restore: Boolean = true) {
            nav.navigate(route) {
                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = restore
            }
        }

        var startupRestoreDone by remember { mutableStateOf(false) }
        LaunchedEffect(c.home, c.page) {
            if (startupRestoreDone || c.home == null) return@LaunchedEffect
            startupRestoreDone = true
            val saved = c.recoveredDestination() ?: return@LaunchedEffect
            if (location.kind != KfDestinationKind.FEED || saved.kind == KfDestinationKind.FEED.name ||
                saved.kind in setOf(KfDestinationKind.SETTINGS.name, KfDestinationKind.LOGIN.name)) return@LaunchedEffect
            when (saved.kind) {
                KfDestinationKind.BOARDS.name -> goRoot(KfRoutes.BOARDS)
                KfDestinationKind.ACCOUNT.name -> goRoot(KfRoutes.ACCOUNT)
                KfDestinationKind.MESSAGES.name -> goRoot(KfRoutes.messages(saved.url.ifBlank { "message.php" }), restore = false)
                KfDestinationKind.LISTING.name -> if (saved.url.isNotBlank()) go(KfRoutes.listing(saved.url))
                KfDestinationKind.READER.name -> if (saved.url.isNotBlank()) go(KfRoutes.reader(saved.url))
                KfDestinationKind.MESSAGE.name -> if (saved.url.isNotBlank()) go(KfRoutes.message(saved.url))
                KfDestinationKind.PROFILE.name -> if (saved.url.isNotBlank()) go(KfRoutes.profile(saved.url))
                KfDestinationKind.NATIVE.name -> if (saved.url.isNotBlank()) go(KfRoutes.native(saved.url))
                KfDestinationKind.EDITOR.name -> if (saved.url.isNotBlank()) go(KfRoutes.editor(saved.url))
                KfDestinationKind.SEARCH.name -> go(
                    if (saved.url.isNotBlank()) KfRoutes.searchResult(saved.url)
                    else KfRoutes.search(saved.query, saved.author)
                )
                KfDestinationKind.GUGU.name -> go(KfRoutes.GUGU)
            }
        }
        LaunchedEffect(startupRestoreDone, location.kind, location.url, location.query, location.author) {
            if (!startupRestoreDone) return@LaunchedEffect
            if (location.kind !in setOf(KfDestinationKind.SETTINGS, KfDestinationKind.LOGIN)) {
                c.rememberDestination(location.kind.name, location.url, location.query, location.author)
            }
        }

        fun open(url: String) {
            if (url.isBlank() || c.siteBusy || c.purchaseBusy || c.editor != null && c.busy) return
            val target = c.resolve(url)
            // Never wrap a read-only message box in a high-risk action dialog.
            val sameSite = target.effect in setOf(SiteEffect.READ, SiteEffect.CHANGE, SiteEffect.DOWNLOAD)
            if (sameSite && KfDestinations.isSearchNavigation(url)) {
                // Keep legacy GBK query bytes intact. Nav arguments are UTF-8,
                // but an existing forum search URL is already encoded by the site.
                if (target.url.substringAfter('?', "").substringBefore('#').isNotBlank()) go(KfRoutes.searchResult(target.url))
                else go(KfRoutes.search("", ""))
                return
            }
            if (target.effect == SiteEffect.READ && KfDestinations.isReadOnlyMessage(target.url)) {
                when (KfDestinations.fromUrl(url)) {
                    KfDestinationKind.EDITOR -> go(KfRoutes.editor(url))
                    KfDestinationKind.MESSAGE -> go(KfRoutes.message(url))
                    else -> {
                        val mailbox = nav.previousBackStackEntry?.takeIf { it.destination.route == KfRoutes.MESSAGES }
                        when {
                            nav.currentDestination?.route == KfRoutes.MESSAGE && mailbox != null -> nav.navigate(KfRoutes.messages(url)) {
                                popUpTo(mailbox.destination.id) { inclusive = true }
                                launchSingleTop = true
                            }
                            nav.currentDestination?.route == KfRoutes.MESSAGES -> replace(KfRoutes.messages(url))
                            else -> goRoot(KfRoutes.messages(url), restore = false)
                        }
                    }
                }
                return
            }
            if (target.effect != SiteEffect.READ) {
                c.openSiteDestination(target)
                return
            }
            when (KfDestinations.fromUrl(url)) {
                KfDestinationKind.FEED -> goRoot(KfRoutes.FEED)
                KfDestinationKind.BOARDS -> goRoot(KfRoutes.BOARDS)
                KfDestinationKind.ACCOUNT -> goRoot(KfRoutes.ACCOUNT)
                KfDestinationKind.LISTING -> go(KfRoutes.listing(url))
                KfDestinationKind.READER -> go(KfRoutes.reader(url))
                KfDestinationKind.EDITOR -> go(KfRoutes.editor(url))
                KfDestinationKind.SEARCH -> go(KfRoutes.search("", ""))
                KfDestinationKind.MESSAGES -> goRoot(KfRoutes.messages(url), restore = false)
                KfDestinationKind.MESSAGE -> go(KfRoutes.message(url))
                KfDestinationKind.PROFILE -> go(KfRoutes.profile(url))
                KfDestinationKind.LOGIN -> go(KfRoutes.LOGIN)
                KfDestinationKind.SETTINGS -> go(KfRoutes.SETTINGS)
                KfDestinationKind.GUGU -> go(KfRoutes.GUGU)
                KfDestinationKind.NATIVE -> go(KfRoutes.native(url))
            }
        }

        fun openNative(url: String) {
            if (url.isBlank()) return
            c.ensurePage(url)
            go(KfRoutes.native(url))
        }

        val actions = remember(c, nav) {
            KfActions(
                open = ::open,
                openSettings = { go(KfRoutes.SETTINGS) },
                openLogin = { go(KfRoutes.LOGIN) },
                openSearch = { go(KfRoutes.search("", "")) },
                openNative = ::openNative,
                selectTab = { tab ->
                    if (!c.siteBusy && !c.purchaseBusy && !(c.editor != null && c.busy)) {
                    if (nav.currentBackStackEntry?.kfLocation()?.kind == when (tab) {
                        HomeTab.FEED -> KfDestinationKind.FEED
                        HomeTab.BOARDS -> KfDestinationKind.BOARDS
                        HomeTab.MESSAGES -> KfDestinationKind.MESSAGES
                        HomeTab.ACCOUNT -> KfDestinationKind.ACCOUNT
                    }) c.reselectTab(tab) else when (tab) {
                        HomeTab.FEED -> goRoot(KfRoutes.FEED)
                        HomeTab.BOARDS -> goRoot(KfRoutes.BOARDS)
                        HomeTab.MESSAGES -> goRoot(KfRoutes.messages("message.php"))
                        HomeTab.ACCOUNT -> goRoot(KfRoutes.ACCOUNT)
                    }
                    }
                },
                back = {
                    if (nav.previousBackStackEntry != null) nav.popBackStack()
                },
            )
        }

        val page: PageData? = when (location.kind) {
            KfDestinationKind.FEED -> c.home ?: c.pageFor("index.php")
            KfDestinationKind.BOARDS, KfDestinationKind.ACCOUNT -> c.home
            KfDestinationKind.SEARCH -> c.pageFor(location.url ?: KfRoutes.searchKey(location.query, location.author))
            KfDestinationKind.SETTINGS, KfDestinationKind.LOGIN, KfDestinationKind.EDITOR -> c.page
            else -> location.url?.let(c::pageFor)
        }

        val title = when (location.kind) {
            KfDestinationKind.FEED -> "绯月"
            KfDestinationKind.BOARDS -> "板块"
            KfDestinationKind.ACCOUNT -> "我的"
            KfDestinationKind.SETTINGS -> "偏好与连接"
            KfDestinationKind.LOGIN -> "登录绯月"
            KfDestinationKind.EDITOR -> "写点什么"
            KfDestinationKind.MESSAGES -> page?.title ?: "消息"
            KfDestinationKind.SEARCH -> page?.title ?: "搜索"
            KfDestinationKind.MESSAGE, KfDestinationKind.PROFILE, KfDestinationKind.NATIVE ->
                page?.nativePage?.title ?: page?.title ?: "更多功能"
            KfDestinationKind.GUGU -> "咕咕镇"
            KfDestinationKind.LISTING -> if (page?.nativePage?.favorites != null) "收藏" else page?.title ?: "话题"
            KfDestinationKind.READER -> page?.title ?: "阅读"
        }
        val canGoBack = location.kind != KfDestinationKind.FEED &&
            location.kind != KfDestinationKind.BOARDS &&
            location.kind != KfDestinationKind.MESSAGES &&
            location.kind != KfDestinationKind.ACCOUNT
        val showBottomBar = location.kind.isTopLevel

        fun back() {
            when (location.kind) {
                KfDestinationKind.EDITOR -> {
                    if (c.busy && !c.loadingEditor) return
                    if (c.loadingEditor) {
                        c.requestBack()
                        nav.popBackStack()
                    } else if (c.editorDirty) {
                        c.confirmDiscard = true
                    } else if (c.editor != null) {
                        c.dismissEditor()
                    } else {
                        nav.popBackStack()
                    }
                }
                else -> if (nav.previousBackStackEntry != null) nav.popBackStack()
            }
        }

        fun refresh() {
            if (page != null && location.kind in setOf(KfDestinationKind.FEED, KfDestinationKind.BOARDS, KfDestinationKind.LISTING, KfDestinationKind.MESSAGES, KfDestinationKind.READER)) {
                c.refreshTimeline(page)
                return
            }
            when (location.kind) {
                KfDestinationKind.FEED, KfDestinationKind.BOARDS, KfDestinationKind.ACCOUNT -> c.load("index.php", false)
                else -> {
                    val url = location.url ?: page?.url
                    if (url != null) c.ensurePage(url, force = true)
                }
            }
        }

        // Only the current destination owns the controller's action page.
        // Underlying destinations may be composed during a predictive gesture,
        // but must not steal bookmark/purchase context before the pop commits.
        LaunchedEffect(location.kind, location.url, page, c.page) {
            page?.let(c::activatePage)
        }

        LaunchedEffect(location.kind, location.url) {
            when (location.kind) {
                KfDestinationKind.FEED -> c.setActiveTab(HomeTab.FEED)
                KfDestinationKind.BOARDS -> c.setActiveTab(HomeTab.BOARDS)
                KfDestinationKind.MESSAGES -> c.setActiveTab(HomeTab.MESSAGES)
                KfDestinationKind.ACCOUNT -> c.setActiveTab(HomeTab.ACCOUNT)
                else -> Unit
            }
        }

        KfOperationFeedback(c, snackbar, page != null || c.editor != null)

        // Login expiry is raised by the controller; present it as a destination.
        // login.php opened as a native form is not the app login prompt.
        LaunchedEffect(c.showLogin, location.kind, location.url) {
            val nativeLoginForm = location.kind == KfDestinationKind.NATIVE &&
                location.url.orEmpty().substringBefore('?').endsWith("login.php")
            if (c.showLogin && !nativeLoginForm) nav.navigate(KfRoutes.LOGIN) { launchSingleTop = true }
        }
        LaunchedEffect(c.pendingNativePage) {
            c.pendingNativePage?.let { url ->
                c.consumeNativePage()
                if (c.resolve(url).effect == SiteEffect.READ && KfDestinations.isReadOnlyMessage(c.resolve(url).url)) open(url)
                else nav.navigate(KfRoutes.native(url)) { launchSingleTop = true }
            }
        }

        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    if (location.kind != KfDestinationKind.SEARCH) {
                    KfTopBar(
                        title = title,
                        root = location.kind.isTopLevel,
                        showBack = canGoBack,
                        kind = location.kind,
                        c = c,
                        page = page,
                        actions = actions,
                        onBack = ::back,
                        onRefresh = ::refresh,
                    )
                    }
                },
                bottomBar = {
                    AnimatedVisibility(
                        visible = showBottomBar,
                        enter = expandVertically(tween(KfMotion.Chrome, easing = KfMotion.BackEasing)) + fadeIn(tween(KfMotion.Chrome)),
                        exit = shrinkVertically(tween(KfMotion.Chrome, easing = KfMotion.BackEasing)) + fadeOut(tween(KfMotion.Chrome)),
                    ) {
                        KfBottomBar(location.kind, actions)
                    }
                },
                floatingActionButton = {
                    val composeUrl = page?.composeUrl
                    if (composeUrl != null && location.kind in setOf(KfDestinationKind.LISTING, KfDestinationKind.READER, KfDestinationKind.MESSAGES)) ExtendedFloatingActionButton(
                        onClick = { open(composeUrl) },
                        icon = { Icon(Icons.Default.Edit, null) },
                        text = {
                            Text(
                                when {
                                    page.posts.isNotEmpty() -> "回复"
                                    location.kind == KfDestinationKind.MESSAGES || page.url.contains("message.php") -> "写私信"
                                    else -> "发主题"
                                }
                            )
                        },
                    )
                },
                snackbarHost = { SnackbarHost(snackbar) },
            ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.background)) {
                    KfStatusBanners(c)
                    NavHost(
                        navController = nav,
                        startDestination = KfRoutes.FEED,
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                        enterTransition = { KfMotion.forwardEnter(direction) },
                        exitTransition = { KfMotion.forwardExit(direction) },
                        // Navigation Compose drives these during the predictive
                        // gesture: the hand-off uses the same pop/keyframes.
                        popEnterTransition = { KfMotion.backEnter(direction) },
                        popExitTransition = { KfMotion.backExit(direction) },
                    ) {
                        composable(KfRoutes.FEED) {
                            val feed = c.home ?: c.pageFor("index.php")
                            LaunchedEffect(Unit) { c.ensurePage("index.php") }
                            FeedScreen(c, actions, feed)
                        }
                        composable(KfRoutes.BOARDS) {
                            LaunchedEffect(Unit) { if (c.home == null) c.ensurePage("index.php") }
                            BoardsScreen(c, actions, c.home)
                        }
                        composable(KfRoutes.ACCOUNT) {
                            LaunchedEffect(Unit) { if (c.home == null) c.ensurePage("index.php"); c.refreshAccountAvatar() }
                            AccountScreen(c, actions, forumAvatar, onLogout = { logout = true })
                        }
                        composable(KfRoutes.SETTINGS) {
                            DisposableEffect(Unit) { onDispose { c.showSettings = false } }
                            SettingsScreen(c)
                        }
                        composable(KfRoutes.GUGU) {
                            GuguScreen(c, actions)
                        }
                        composable(KfRoutes.LOGIN) {
                            var authenticatedOnEntry by remember { mutableStateOf(c.authenticated) }
                            DisposableEffect(Unit) { onDispose { c.dismissLogin() } }
                            LaunchedEffect(c.authenticated) {
                                if (!authenticatedOnEntry && c.authenticated) {
                                    authenticatedOnEntry = true
                                    val after = c.page
                                    if (after == null || KfDestinations.fromUrl(after.url) == KfDestinationKind.FEED) {
                                        nav.navigate(KfRoutes.FEED) {
                                            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                            launchSingleTop = true
                                        }
                                    } else {
                                        nav.popBackStack()
                                    }
                                }
                            }
                            LoginScreen(c, actions)
                        }
                        composable(
                            KfRoutes.LISTING,
                            arguments = listOf(navArgument("url") { type = NavType.StringType }),
                        ) { entry ->
                            val url = entry.arguments?.getString("url").orEmpty()
                            val listPage = c.pageFor(url)
                            LaunchedEffect(url) { c.ensurePage(url) }
                            if (listPage == null) KfLoading(c, onRetry = { c.ensurePage(url, force = true) }) else ListingScreen(c, actions, listPage, isMessages = false, rich = richContent)
                        }
                        composable(
                            KfRoutes.READER,
                            arguments = listOf(navArgument("url") { type = NavType.StringType }),
                        ) { entry ->
                            val url = entry.arguments?.getString("url").orEmpty()
                            val readerPage = c.pageFor(url)
                            LaunchedEffect(url) { c.ensurePage(url) }
                            if (readerPage == null) KfLoading(c, onRetry = { c.ensurePage(url, force = true) })
                            else ReaderScreen(c, actions, readerPage, forumAvatar, richContent)
                        }
                        composable(
                            KfRoutes.MESSAGES,
                            arguments = listOf(navArgument("url") { type = NavType.StringType }),
                        ) { entry ->
                            val url = entry.arguments?.getString("url").orEmpty().ifBlank { "message.php" }
                            val messagePage = c.pageFor(url)
                            LaunchedEffect(url) { c.ensurePage(url) }
                            if (messagePage == null) KfLoading(c, onRetry = { c.ensurePage(url, force = true) })
                            else ListingScreen(c, actions, messagePage, isMessages = true, rich = richContent)
                        }
                        composable(
                            KfRoutes.MESSAGE,
                            arguments = listOf(navArgument("url") { type = NavType.StringType }),
                        ) { entry ->
                            KfNativeDestination(c, entry.arguments?.getString("url").orEmpty(), actions, richContent, forumImage, forumAvatar)
                        }
                        composable(
                            KfRoutes.PROFILE,
                            arguments = listOf(navArgument("url") { type = NavType.StringType }),
                        ) { entry ->
                            KfNativeDestination(c, entry.arguments?.getString("url").orEmpty(), actions, richContent, forumImage, forumAvatar)
                        }
                        composable(
                            KfRoutes.NATIVE,
                            arguments = listOf(navArgument("url") { type = NavType.StringType }),
                        ) { entry ->
                            DisposableEffect(Unit) { onDispose { c.showSiteTools = false } }
                            KfNativeDestination(c, entry.arguments?.getString("url").orEmpty(), actions, richContent, forumImage, forumAvatar)
                        }
                        composable(
                            KfRoutes.EDITOR,
                            arguments = listOf(navArgument("url") { type = NavType.StringType }),
                        ) { entry ->
                            val sourceUrl = entry.arguments?.getString("url").orEmpty()
                            var seenEditor by remember { mutableStateOf(c.editor != null) }
                            val focusManager = LocalFocusManager.current
                            val keyboard = LocalSoftwareKeyboardController.current
                            val imeVisible = WindowInsets.isImeVisible
                            LaunchedEffect(sourceUrl, c.authenticated) {
                                if (c.editor?.sourceUrl != sourceUrl) c.openEditor(sourceUrl)
                            }
                            LaunchedEffect(c.editor) {
                                if (c.editor != null) seenEditor = true
                                else if (seenEditor) nav.popBackStack()
                            }
                            BackHandler(enabled = c.editorDirty || c.loadingEditor || c.busy) {
                                if (imeVisible) { focusManager.clearFocus(); keyboard?.hide() } else back()
                            }
                            if (c.editor?.sourceUrl == sourceUrl) EditorScreen(c, forumImage)
                            else KfLoading(c, message = "正在准备编辑器…", onRetry = { c.openEditor(sourceUrl) })
                        }
                        composable(
                            KfRoutes.SEARCH,
                            arguments = listOf(
                                navArgument("q") { type = NavType.StringType; defaultValue = "" },
                                navArgument("author") { type = NavType.StringType; defaultValue = "" },
                                navArgument("url") { type = NavType.StringType; defaultValue = "" },
                            ),
                        ) { entry ->
                            val keyword = entry.arguments?.getString("q").orEmpty()
                            val author = entry.arguments?.getString("author").orEmpty()
                            SearchScreen(c, actions, keyword, author, entry.arguments?.getString("url").orEmpty())
                        }
                    }
                }
            }
        }

        KfGlobalDialogs(c, logout = logout, onLogoutChange = { logout = it })
    }
}

@Composable
private fun KfNativeDestination(
    c: AppController,
    url: String,
    actions: KfActions,
    richContent: RichContent,
    forumImage: ForumImage,
    forumAvatar: ForumImage,
) {
    val page = c.pageFor(url)
    LaunchedEffect(url) { c.ensurePage(url) }
    LaunchedEffect(page) {
        // Native login options are a form page, not a request for our login screen.
        if (page != null && url.substringBefore('?').endsWith("login.php")) c.showLogin = false
    }
    if (page == null) KfLoading(c, onRetry = { c.ensurePage(url, force = true) })
    else NativeSiteScreen(c, page, actions, richContent, forumImage, forumAvatar)
}

@Composable
private fun KfLoading(c: AppController, message: String = "正在连接绯月…", onRetry: () -> Unit) {
    KfPageLoadState(c.busy, c.error, onRetry, message)
}

@Composable
private fun KfTopBar(
    title: String,
    root: Boolean,
    showBack: Boolean,
    kind: KfDestinationKind,
    c: AppController,
    page: PageData?,
    actions: KfActions,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    TopAppBar(
        title = {
            Column {
                Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (root) Text("SCARLET MOON", style = MaterialTheme.typography.labelSmall, letterSpacing = 2.sp, color = MaterialTheme.colorScheme.primary)
            }
        },
        navigationIcon = {
            if (kind == KfDestinationKind.EDITOR) TextButton(onClick = onBack) { Text("取消") }
            else if (showBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            else Icon(Icons.Default.NightsStay, null, Modifier.padding(start = 20.dp, end = 8.dp), tint = MaterialTheme.colorScheme.primary)
        },
        actions = {
            KfPageActions(c, kind, page, actions, onRefresh)
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
private fun KfBottomBar(kind: KfDestinationKind, actions: KfActions) {
    val tabs = listOf(
        Triple(HomeTab.FEED, Icons.Default.Explore, KfDestinationKind.FEED),
        Triple(HomeTab.BOARDS, Icons.Default.Dashboard, KfDestinationKind.BOARDS),
        Triple(HomeTab.MESSAGES, Icons.Default.MailOutline, KfDestinationKind.MESSAGES),
        Triple(HomeTab.ACCOUNT, Icons.Default.PersonOutline, KfDestinationKind.ACCOUNT),
    )
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        tabs.forEach { (tab, icon, tabKind) ->
            NavigationBarItem(
                selected = kind == tabKind,
                onClick = { actions.selectTab(tab) },
                icon = { Icon(icon, tab.title) },
                label = { Text(tab.title) },
            )
        }
    }
}

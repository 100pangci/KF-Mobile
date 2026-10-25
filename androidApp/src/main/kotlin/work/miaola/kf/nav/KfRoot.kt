@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

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
    KfTheme(c.theme) {
        val nav = rememberNavController()
        val backStackEntry by nav.currentBackStackEntryAsState()
        val location = backStackEntry?.kfLocation() ?: KfLocation(KfDestinationKind.FEED)
        val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
        val snackbar = remember { SnackbarHostState() }
        var search by remember { mutableStateOf(false) }
        var logout by remember { mutableStateOf(false) }
        var menu by remember { mutableStateOf(false) }

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

        fun open(url: String) {
            if (url.isBlank()) return
            // Never wrap a read-only message box in a high-risk action dialog.
            val sameSite = url.startsWith(c.origin) || (!url.contains("://") && !url.startsWith("//"))
            if (sameSite && KfDestinations.isReadOnlyMessage(url)) {
                when (KfDestinations.fromUrl(url)) {
                    KfDestinationKind.EDITOR -> go(KfRoutes.editor(url))
                    KfDestinationKind.MESSAGE -> go(KfRoutes.message(url))
                    else -> if (nav.currentDestination?.route in setOf(KfRoutes.MESSAGES, KfRoutes.MESSAGE)) {
                        replace(KfRoutes.messages(url))
                    } else {
                        goRoot(KfRoutes.messages(url), restore = false)
                    }
                }
                return
            }
            val target = c.resolve(url)
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
                KfDestinationKind.SEARCH -> go(KfRoutes.listing(url))
                KfDestinationKind.MESSAGES -> goRoot(KfRoutes.messages(url), restore = false)
                KfDestinationKind.MESSAGE -> go(KfRoutes.message(url))
                KfDestinationKind.PROFILE -> go(KfRoutes.profile(url))
                KfDestinationKind.LOGIN -> go(KfRoutes.LOGIN)
                KfDestinationKind.SETTINGS -> go(KfRoutes.SETTINGS)
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
                openSearch = { search = true },
                openNative = ::openNative,
                selectTab = { tab ->
                    when (tab) {
                        HomeTab.FEED -> goRoot(KfRoutes.FEED)
                        HomeTab.BOARDS -> goRoot(KfRoutes.BOARDS)
                        HomeTab.MESSAGES -> goRoot(KfRoutes.messages("message.php"))
                        HomeTab.ACCOUNT -> goRoot(KfRoutes.ACCOUNT)
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
            KfDestinationKind.SEARCH -> c.pageFor(KfRoutes.searchKey(location.query, location.author))
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
                page?.nativePage?.title ?: page?.title ?: "站点功能"
            KfDestinationKind.LISTING -> page?.title ?: "话题"
            KfDestinationKind.READER -> page?.title ?: "阅读"
        }
        val canGoBack = location.kind != KfDestinationKind.FEED &&
            location.kind != KfDestinationKind.BOARDS &&
            location.kind != KfDestinationKind.MESSAGES &&
            location.kind != KfDestinationKind.ACCOUNT
        val showBottomBar = location.kind.isTopLevel
        val showSearch = location.kind !in listOf(KfDestinationKind.SETTINGS, KfDestinationKind.LOGIN, KfDestinationKind.EDITOR)

        fun back() {
            when (location.kind) {
                KfDestinationKind.EDITOR -> {
                    if (c.loadingEditor) {
                        c.requestBack()
                        nav.popBackStack()
                    } else if (c.editor != null && (c.draftTitle.isNotBlank() || c.draftContent.isNotBlank())) {
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
            when (location.kind) {
                KfDestinationKind.FEED, KfDestinationKind.BOARDS, KfDestinationKind.ACCOUNT -> c.load("index.php", false)
                else -> {
                    val url = location.url ?: page?.url
                    if (url != null) c.ensurePage(url, force = true)
                }
            }
        }

        fun openPageNative() {
            val url = when (location.kind) {
                KfDestinationKind.FEED, KfDestinationKind.BOARDS, KfDestinationKind.ACCOUNT -> c.home?.url ?: "index.php"
                else -> location.url ?: page?.url ?: "index.php"
            }
            openNative(url)
        }

        // Only the current destination owns the controller's action page.
        // Underlying destinations may be composed during a predictive gesture,
        // but must not steal bookmark/purchase context before the pop commits.
        LaunchedEffect(location.kind, location.url, page?.url) {
            page?.let(c::activatePage)
        }

        LaunchedEffect(location.kind, location.url) {
            menu = false
            when (location.kind) {
                KfDestinationKind.FEED -> c.setActiveTab(HomeTab.FEED)
                KfDestinationKind.BOARDS -> c.setActiveTab(HomeTab.BOARDS)
                KfDestinationKind.MESSAGES -> c.setActiveTab(HomeTab.MESSAGES)
                KfDestinationKind.ACCOUNT -> c.setActiveTab(HomeTab.ACCOUNT)
                else -> Unit
            }
        }

        LaunchedEffect(c.toast) {
            c.toast?.let {
                snackbar.showSnackbar(it)
                c.clearMessage()
            }
        }

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
                nav.navigate(KfRoutes.native(url)) { launchSingleTop = true }
            }
        }

        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    KfTopBar(
                        title = title,
                        root = location.kind.isTopLevel,
                        showBack = canGoBack,
                        showSearch = showSearch,
                        page = page,
                        favorite = page?.favorite,
                        onBack = ::back,
                        onSearch = { search = true },
                        onSettings = { go(KfRoutes.SETTINGS) },
                        menuOpen = menu,
                        onMenuOpen = { menu = true },
                        onMenuDismiss = { menu = false },
                        onRefresh = { menu = false; refresh() },
                        onNative = { menu = false; openPageNative() },
                        onFavorite = {
                            menu = false
                            c.favoriteThread(page?.favorite)
                        },
                    )
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
                    if (composeUrl != null) ExtendedFloatingActionButton(
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
                            if (listPage == null) KfLoading(c, onRetry = { c.ensurePage(url, force = true) }) else ListingScreen(c, actions, listPage, isMessages = false)
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
                            else ListingScreen(c, actions, messagePage, isMessages = true)
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
                            LaunchedEffect(sourceUrl, c.authenticated) {
                                if (c.editor?.sourceUrl != sourceUrl) c.openEditor(sourceUrl)
                            }
                            LaunchedEffect(c.editor) {
                                if (c.editor != null) seenEditor = true
                                else if (seenEditor) nav.popBackStack()
                            }
                            BackHandler { back() }
                            if (c.editor?.sourceUrl == sourceUrl) EditorScreen(c, forumImage)
                            else KfLoading(c, message = "正在准备编辑器…", onRetry = { c.openEditor(sourceUrl) })
                        }
                        composable(
                            KfRoutes.SEARCH,
                            arguments = listOf(
                                navArgument("q") { type = NavType.StringType; defaultValue = "" },
                                navArgument("author") { type = NavType.StringType; defaultValue = "" },
                            ),
                        ) { entry ->
                            val keyword = entry.arguments?.getString("q").orEmpty()
                            val author = entry.arguments?.getString("author").orEmpty()
                            val key = KfRoutes.searchKey(keyword, author)
                            val result = c.pageFor(key)
                            var attempted by remember(key) { mutableStateOf(false) }
                            LaunchedEffect(key, c.busy) {
                                if (result == null && !c.busy && !attempted) {
                                    attempted = true
                                    c.ensureSearch(key, keyword, author)
                                }
                            }
                            if (result == null) KfLoading(c, message = "正在搜索…", onRetry = { c.ensureSearch(key, keyword, author) })
                            else ListingScreen(c, actions, result, isMessages = false)
                        }
                    }
                }
            }
        }

        if (search) SearchDialog(
            onDismiss = { search = false },
            onSearch = { keyword, author ->
                search = false
                nav.navigate(KfRoutes.search(keyword, author)) { launchSingleTop = true }
            },
        )
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
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (c.busy) CircularProgressIndicator() else Icon(Icons.Default.Forum, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Text(if (c.busy) message else c.error ?: "这里还没有可显示的内容", style = MaterialTheme.typography.bodyMedium)
            if (!c.busy) TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

@Composable
private fun KfTopBar(
    title: String,
    root: Boolean,
    showBack: Boolean,
    showSearch: Boolean,
    page: PageData?,
    favorite: SiteDestination?,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    menuOpen: Boolean,
    onMenuOpen: () -> Unit,
    onMenuDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onNative: () -> Unit,
    onFavorite: () -> Unit,
) {
    TopAppBar(
        title = {
            Column {
                Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (root) Text("SCARLET MOON", style = MaterialTheme.typography.labelSmall, letterSpacing = 2.sp, color = MaterialTheme.colorScheme.primary)
            }
        },
        navigationIcon = {
            if (showBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            else Icon(Icons.Default.NightsStay, null, Modifier.padding(start = 20.dp, end = 8.dp), tint = MaterialTheme.colorScheme.primary)
        },
        actions = {
            if (showSearch) {
                IconButton(onClick = onSearch) { Icon(Icons.Default.Search, "搜索") }
                IconButton(onClick = onSettings) { Icon(Icons.Default.Tune, "设置") }
                Box {
                    IconButton(onClick = onMenuOpen) { Icon(Icons.Default.MoreVert, "更多") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = onMenuDismiss) {
                        DropdownMenuItem(text = { Text("刷新") }, onClick = onRefresh, leadingIcon = { Icon(Icons.Default.Refresh, null) })
                        DropdownMenuItem(text = { Text("本页原生功能") }, onClick = onNative, leadingIcon = { Icon(Icons.Default.Widgets, null) })
                        if (favorite != null) DropdownMenuItem(
                            text = { Text("收藏本帖") },
                            onClick = onFavorite,
                            leadingIcon = { Icon(Icons.Default.BookmarkAdd, null) },
                        )
                    }
                }
            }
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
                onClick = { if (kind != tabKind) actions.selectTab(tab) },
                icon = { Icon(icon, tab.title) },
                label = { Text(tab.title) },
            )
        }
    }
}

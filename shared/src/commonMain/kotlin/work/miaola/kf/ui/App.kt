@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package work.miaola.kf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import work.miaola.kf.*

typealias RichContent = @Composable (html: String, origin: String, fontScale: Float, onLink: (String) -> Unit, onImage: (String, List<String>) -> Unit) -> Unit
typealias ForumImage = @Composable (url: String, label: String, modifier: Modifier) -> Unit

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
        val nativeTitle = c.page?.nativePage?.title?.takeIf { c.showSiteTools || isNativeSitePage(c.page) || c.page?.nativePage?.favorites != null }
        val readerTitle = c.page?.takeIf { it.posts.isNotEmpty() }?.title
        val detailDestination = c.showSiteTools || isNativeSitePage(c.page) || c.page?.nativePage?.favorites != null ||
            c.page?.posts?.isNotEmpty() == true ||
            c.page?.url?.substringBefore('?')?.endsWith("thread.php") == true ||
            c.page?.url?.substringBefore('?')?.endsWith("search.php") == true ||
            c.page?.url?.contains("action=read") == true
        val showBottomBar = !isOverlay && !detailDestination
        Scaffold(
            topBar = {
                if (!search) {
                TopAppBar(
                    title = {
                        Column {
                            Text(when {
                                c.showSettings -> "偏好与连接"
                                c.showSiteTools -> nativeTitle ?: "更多功能"
                                c.showLogin -> "登录绯月"
                                c.editor != null -> "写点什么"
                                nativeTitle != null -> nativeTitle
                                readerTitle != null -> readerTitle
                                else -> "绯月"
                            }, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!isOverlay && nativeTitle == null && readerTitle == null) Text("SCARLET MOON", style = MaterialTheme.typography.labelSmall, letterSpacing = 2.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    },
                    navigationIcon = {
                        if (c.editor != null) TextButton(onClick = actions.back) { Text("取消") }
                        else if (c.canBack) IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
                        else Icon(Icons.Default.NightsStay, null, Modifier.padding(start = 20.dp, end = 8.dp), tint = MaterialTheme.colorScheme.primary)
                    },
                    actions = {
                        val kind = when {
                            c.editor != null -> KfDestinationKind.EDITOR
                            c.showSettings -> KfDestinationKind.SETTINGS
                            c.showLogin -> KfDestinationKind.LOGIN
                            detailDestination -> KfDestinations.fromUrl(c.page?.url.orEmpty())
                            c.tab == HomeTab.ACCOUNT -> KfDestinationKind.ACCOUNT
                            c.tab == HomeTab.MESSAGES -> KfDestinationKind.MESSAGES
                            c.tab == HomeTab.BOARDS -> KfDestinationKind.BOARDS
                            else -> KfDestinationKind.FEED
                        }
                        KfPageActions(c, kind, c.page, actions) { c.page?.let(c::refreshTimeline) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
                }
            },
            bottomBar = {
                if (showBottomBar) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    HomeTab.entries.forEach { tab ->
                        NavigationBarItem(selected = c.tab == tab, onClick = { actions.selectTab(tab) },
                            icon = { Icon(tabIcon(tab), tab.title) }, label = { Text(tab.title) })
                    }
                }
            },
            floatingActionButton = {
                if (!isOverlay && c.page?.composeUrl != null && (c.page?.posts?.isNotEmpty() == true ||
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
                    isNativeSitePage(c.page) -> NativeSiteScreen(c, c.page, actions, richContent, forumImage, forumAvatar)
                    c.page?.posts?.isNotEmpty() == true -> ReaderScreen(c, actions, c.page, forumAvatar, richContent)
                    c.page?.url?.substringBefore('?')?.endsWith("thread.php") == true -> ListingScreen(c, actions, c.page, isMessages = false, rich = richContent)
                    c.page?.url?.substringBefore('?')?.endsWith("profile.php") == true -> ListingScreen(c, actions, c.page, isMessages = false, rich = richContent)
                    c.page?.url?.substringBefore('?')?.endsWith("search.php") == true -> ListingScreen(c, actions, c.page, isMessages = false)
                    c.page?.url?.substringBefore('?')?.endsWith("message.php") == true -> ListingScreen(c, actions, c.page, isMessages = true, rich = richContent)
                    c.tab == HomeTab.BOARDS -> BoardsScreen(c, actions, c.home ?: c.page)
                    c.tab == HomeTab.MESSAGES -> ListingScreen(c, actions, c.page, isMessages = true, rich = richContent)
                    else -> FeedScreen(c, actions, c.page)
                } }
            }
        }
        KfGlobalDialogs(c, logout = logout, onLogoutChange = { logout = it })
    }
}

private fun isNativeSitePage(page: PageData?): Boolean {
    val url = page?.url ?: return false
    val path = url.substringBefore('?').substringAfterLast('/')
    return when (path) {
        "index.php", "read.php", "search.php" -> false
        "message.php" -> url.contains("action=read") || url.contains("action=banned") || url.contains("action=scout")
        "profile.php" -> !url.contains("action=favor")
        "thread.php" -> false
        else -> page.nativePage != null
    }
}

private fun tabIcon(tab: HomeTab): ImageVector = when (tab) {
    HomeTab.FEED -> Icons.Default.Explore
    HomeTab.BOARDS -> Icons.Default.Dashboard
    HomeTab.MESSAGES -> Icons.Default.MailOutline
    HomeTab.ACCOUNT -> Icons.Default.PersonOutline
}

@Composable
private fun FeedHeader() {
    Text("游戏 · 动漫 · 生活", Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun FeedScreen(c: AppController, actions: KfActions, page: PageData? = c.page) {
    if (page == null) { KfPageLoadState(c.busy, c.error, { c.ensurePage("index.php", true) }); return }
    val sections = page.feeds
    val ordered = remember(sections) { sections.sortedBy { section ->
        when (section.id) {
            "latest-replies" -> 0
            "recommended" -> 1
            "new-topics" -> 2
            else -> 3
        }
    } }
    var sectionId by rememberSaveable { mutableStateOf(ordered.firstOrNull()?.id.orEmpty()) }
    val active = ordered.firstOrNull { it.id == sectionId } ?: ordered.firstOrNull()
    val savedSections = rememberSaveableStateHolder()
    savedSections.SaveableStateProvider(active?.id ?: "default") {
    val state = rememberLazyListState()
    val timeline = ListInteractions(c, state, page, HomeTab.FEED)
    val topics = if (ordered.isEmpty()) timeline?.topics ?: page.topics else timeline?.feedTopics?.get(active?.id) ?: active?.topics.orEmpty()
    RefreshableList(timeline, { c.refreshTimeline(page) }) {
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 100.dp)) {
        item { FeedHeader() }
        if (ordered.isNotEmpty()) item {
            SecondaryTabRow(selectedTabIndex = ordered.indexOf(active).coerceAtLeast(0), containerColor = MaterialTheme.colorScheme.background) {
                ordered.forEach { section ->
                    Tab(selected = section.id == active?.id, onClick = { sectionId = section.id },
                        text = { Text(when (section.id) { "recommended" -> "推荐"; "new-topics" -> "新主题"; "latest-replies" -> "最新回复"; else -> section.title }) })
                }
            }
        }
        if (topics.isEmpty()) item { EmptyState(false, page.notice, onRetry = { c.refreshTimeline(page) }) }
        items(topics, key = { topicIdentity(it.url) }) { TopicCard(it, actions.open, timeInHeader = true) }
        item("next") { NextPageFooter(c, page, timeline) }
    }
    }
    }
}

@Composable
private fun TopicCard(link: SiteLink, onClick: (String) -> Unit, timeInHeader: Boolean = false) {
    val time = link.subtitle.trim().takeIf { timeInHeader && it.isNotBlank() }
    val hasHeader = link.group.isNotBlank() || time != null
    Column(Modifier.fillMaxWidth().clickable { onClick(link.url) }) {
        Column(Modifier.padding(horizontal = 2.dp, vertical = 11.dp)) {
            if (hasHeader) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (link.group.isNotBlank()) Text(link.group, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                time?.let {
                    Spacer(Modifier.weight(1f))
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(link.title, Modifier.padding(top = if (hasHeader) 4.dp else 0.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (link.subtitle.isNotBlank() && time == null) Text(link.subtitle, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
    }
}



@Composable
fun BoardsScreen(c: AppController, actions: KfActions, page: PageData? = c.home) {
    if (page == null) { KfPageLoadState(c.busy, c.error, { c.ensurePage("index.php", true) }); return }
    val state = rememberLazyListState()
    val timeline = ListInteractions(c, state, page, HomeTab.BOARDS, enabled = false)
    var filter by rememberSaveable { mutableStateOf("") }
    val boards = remember(page.boards, filter) { page.boards.filter { it.title.contains(filter, true) } }
    RefreshableList(timeline, { c.refreshTimeline(page) }) {
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("找一个喜欢的角落", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), placeholder = { Text("筛选板块") }, leadingIcon = { Icon(Icons.Default.Search, null) }, shape = RoundedCornerShape(20.dp), singleLine = true) }
        if (boards.isEmpty()) item { EmptyState(false, if (filter.isBlank()) "暂未加载到板块" else "没有匹配的板块", { c.refreshTimeline(page) }) }
        items(boards, key = { it.url }) { board ->
            Card(onClick = { actions.open(board.url) }, Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) { Icon(Icons.AutoMirrored.Filled.Chat, null, Modifier.padding(12.dp)) }
                    Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                        Text(board.title, fontWeight = FontWeight.Medium)
                        Text("进入板块", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, null)
                }
            }
        }
    }
    }
}

@Composable
fun ListingScreen(c: AppController, actions: KfActions, page: PageData?, isMessages: Boolean, search: Pair<String, String>? = null, rich: RichContent? = null) {
    if (page?.nativePage?.favorites != null) { FavoritesScreen(c, page, actions, rich); return }
    val state = rememberLazyListState()
    val messageTitle = when { page?.url?.contains("action=sendbox") == true -> "发件箱"; page?.url?.contains("action=scout") == true -> "消息跟踪"; else -> "收件箱" }
    var menu by remember { mutableStateOf(false) }
    var managing by rememberSaveable(page?.url) { mutableStateOf(false) }
    val timeline = ListInteractions(c, state, page, if (isMessages) HomeTab.MESSAGES else null, enabled = !managing)
    val topics = timeline?.topics ?: page?.topics.orEmpty()
    val messages = timeline?.messages ?: page?.nativePage?.messages.orEmpty()
    val sources = timeline?.managementForms().orEmpty()
    var activeSource by rememberSaveable(page?.url) { mutableStateOf(page?.url.orEmpty()) }
    val bulkForm = sources.firstOrNull { it.sourceUrl == activeSource } ?: sources.firstOrNull()
    val bulkFields = bulkForm?.fields?.filter { it.name == "delid[]" }.orEmpty()
    val deleteAction = bulkForm?.fields?.firstOrNull { it.name == "action" && it.kind == SiteFieldKind.RADIO && it.options.any { option -> option.value == "del" } }
    val selection = remember(page?.url) { SourcePageSelection() }
    LaunchedEffect(c.managementRevision) { selection.clear() }
    val selected = bulkForm?.let(selection::fieldIds).orEmpty()
    if (c.showTopicFilters && page?.url?.substringBefore('?')?.endsWith("thread.php") == true) ModalBottomSheet(onDismissRequest = { c.showTopicFilters = false }) {
        Text("筛选主题", Modifier.padding(24.dp), style = MaterialTheme.typography.titleLarge)
        LazyColumn(Modifier.heightIn(max = 480.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (page.boardFilters.isEmpty()) item { Text("当前版块没有其他筛选项", Modifier.padding(24.dp)) }
            items(page.boardFilters, key = { it.url }) { filter -> ListItem(headlineContent = { Text(filter.title) }, modifier = Modifier.clickable { c.showTopicFilters = false; actions.open(filter.url) }) }
        }
    }
    RefreshableList(timeline, { page?.let { c.refreshTimeline(it, search) } }, enabled = !managing) {
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(16.dp, 6.dp, 16.dp, 110.dp)) {
        item {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(if (isMessages) { if (managing) "消息管理" else messageTitle } else page?.title ?: "话题", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        if (isMessages && managing) "勾选后批量删除 · ${bulkFields.size} 条" else "${if (isMessages && messages.isNotEmpty()) messages.size else topics.size} 条",
                        Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isMessages) Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.MoreVert, "消息操作") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("消息跟踪") }, leadingIcon = { Icon(Icons.Default.TrackChanges, null) }, onClick = { menu = false; actions.open("message.php?action=scout") })
                        DropdownMenuItem(text = { Text("写新消息") }, leadingIcon = { Icon(Icons.Default.Edit, null) }, onClick = { menu = false; page?.composeUrl?.let(actions.open) })
                        DropdownMenuItem(text = { Text("屏蔽列表") }, leadingIcon = { Icon(Icons.Default.Block, null) }, onClick = { menu = false; actions.open("message.php?action=banned") })
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                        DropdownMenuItem(text = { Text("批量管理") }, leadingIcon = { Icon(Icons.Default.Checklist, null) }, onClick = { menu = false; managing = true })
                    }
                }
            }
        }
        if (isMessages) item { MessageTabs(page?.url.orEmpty(), managing, actions) { managing = it } }
        if (!managing && topics.isEmpty() && (!isMessages || messages.isEmpty())) item { EmptyState(c.busy && page == null, page?.notice.orEmpty(), { page?.let { c.refreshTimeline(it, search) } }) }
        if (isMessages) {
            if (managing) {
                item { SourcePagePicker(sources, bulkForm, selection.count, !c.siteBusy) { activeSource = it } }
                if (bulkForm == null) {
                    item {
                        Text(if (c.siteBusy) "正在提交…" else "当前页面没有可批量管理的消息", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("已选 ${selected.size} / ${bulkFields.size}", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { selection.choose(bulkForm, bulkFields.map { it.id }.toSet()) }, enabled = !c.siteBusy) { Text("全选本页") }
                            TextButton(onClick = { selection.clear(bulkForm) }, enabled = !c.siteBusy) { Text("清空本页") }
                        }
                    }
                    items(bulkFields, key = { it.id }) { field ->
                        val checked = field.id in selected
                        Surface(Modifier.fillMaxWidth().clickable(enabled = !c.siteBusy) { selection.choose(bulkForm, if (checked) selected - field.id else selected + field.id) },
                            shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))) {
                            Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked, { value -> selection.choose(bulkForm, if (value) selected + field.id else selected - field.id) }, enabled = !c.siteBusy)
                                Text(field.label, Modifier.weight(1f).padding(start = 4.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(field.options.firstOrNull()?.value.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }
                    item {
                        Button(onClick = {
                            val chosen = selected
                            val values = bulkForm.fields.associate { field ->
                                 field.id to if (field.name == "delid[]") {
                                     if (field.id in chosen) field.options.map { it.value } else emptyList()
                                 } else if (field.id == deleteAction?.id) listOf("del") else field.initial
                            }
                            c.requestSiteSubmit(bulkForm, values, bulkForm.submits.first())
                        }, enabled = selected.isNotEmpty() && !c.siteBusy, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                            Text(if (c.siteBusy) "正在提交…" else "删除所选（${selected.size}）")
                        }
                    }
                }
            } else {
                if (messages.isNotEmpty()) items(messages, key = { topicIdentity(it.url) }) { MessageSummaryRow(it, page?.url?.let { it.contains("action=sendbox") || it.contains("action=scout") } == true, actions.open) }
                else items(topics, key = { topicIdentity(it.url) }) { MessageRow(it, actions.open) }
            }
        } else {
            if (page?.url?.contains("action=favor") == true && page.nativePage?.forms?.isNotEmpty() == true) item {
                FilledTonalButton(onClick = { actions.openNative(page.url) }, modifier = Modifier.fillMaxWidth()) { Text("管理收藏与分类") }
            }
            val nativeTable = page?.nativePage?.tables?.firstOrNull { it.style == NativeTableStyle.GRID && it.rows.size > 1 }
            if (topics.isEmpty() && nativeTable != null) item { SiteTableCard(nativeTable, actions.open) }
            else items(topics, key = { topicIdentity(it.url) }) { TopicCard(it, actions.open) }
        }
        if (!managing && page != null) item("next") { NextPageFooter(c, page, timeline) }
        if (isMessages && page?.nativePage != null && rich != null) item { TaskPageExtras(c, page.nativePage, actions, rich) }
    }
    }
}

@Composable
private fun MessageTabs(url: String, managing: Boolean, actions: KfActions, onManaging: (Boolean) -> Unit) {
    val selected = when { managing -> 3; url.contains("action=scout") -> 2; url.contains("action=sendbox") -> 1; else -> 0 }
    SecondaryTabRow(selectedTabIndex = selected, containerColor = MaterialTheme.colorScheme.background) {
        listOf("收件箱", "发件箱", "跟踪", "管理").forEachIndexed { index, label ->
            Tab(
                selected = selected == index,
                onClick = {
                    when (index) {
                        0 -> { onManaging(false); actions.open("message.php?action=receivebox") }
                        1 -> { onManaging(false); actions.open("message.php?action=sendbox") }
                        2 -> { onManaging(false); actions.open("message.php?action=scout") }
                        else -> onManaging(true)
                    }
                },
                text = { Text(label) },
            )
        }
    }
}

@Composable
private fun MessageRow(link: SiteLink, onClick: (String) -> Unit) {
    Surface(Modifier.fillMaxWidth().clickable { onClick(link.url) }, shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.MailOutline, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(link.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val parts = link.subtitle.split(" · ").filter { it.isNotBlank() }
                if (parts.isNotEmpty()) Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(parts.dropLast(1).joinToString(" · "), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(parts.last(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
            }
            Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
fun ReaderScreen(c: AppController, actions: KfActions, page: PageData?, forumAvatar: ForumImage, richContent: RichContent) {
    val page = page ?: return
    val state = rememberLazyListState()
    val timeline = ListInteractions(c, state, page)
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(16.dp, 6.dp, 16.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SelectionContainer { Text(page.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        }
        items(timeline?.posts ?: page.posts, key = { it.id }) { post ->
            Column(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 2.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val avatar = post.avatar.ifBlank { c.avatarFor(post.authorUrl).orEmpty() }
                        LaunchedEffect(post.authorUrl) { c.loadAvatar(post.authorUrl) }
                        Surface(Modifier.size(36.dp).clickable(enabled = post.authorUrl.isNotEmpty()) { actions.open(post.authorUrl) }, shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(post.author.take(1), fontWeight = FontWeight.Bold)
                                if (avatar.isNotBlank()) forumAvatar(avatar, post.author.take(1), Modifier.fillMaxSize())
                            }
                        }
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(post.author, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(post.time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(post.floor, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        PostMenu(post, c, actions)
                    }
                    Spacer(Modifier.height(16.dp))
                    if (post.replies.isNotEmpty()) PostQuote(post, c, actions, richContent)
                    richContent(post.html, c.origin, c.fontScale, actions.open, c::showImages)
                    post.purchases.forEach { offer ->
                        Surface(Modifier.fillMaxWidth().padding(top = 10.dp), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Lock, null, Modifier.size(18.dp))
                                    Text("付费内容", Modifier.padding(start = 6.dp), fontWeight = FontWeight.Bold)
                                }
                                Text("${offer.price} ${offer.currency} · 购买后可见", style = MaterialTheme.typography.bodyMedium)
                                if (offer.id in c.uncertainPurchases) {
                                    Text("结果待确认，请先刷新核对。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                                    OutlinedButton(onClick = { c.load(offer.threadUrl, false) }, enabled = !c.busy) { Text("刷新核对") }
                                } else {
                                    Button(onClick = { c.requestPurchase(offer) }, enabled = !c.busy && !c.purchaseBusy) {
                                        Icon(Icons.Default.ShoppingCart, null, Modifier.size(18.dp)); Text(" 购买 · ${offer.price} ${offer.currency}")
                                    }
                                }
                            }
                        }
                    }
                    if (post.keywords.isNotEmpty() || post.meta.isNotEmpty()) {
                        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (post.keywords.isNotEmpty()) PostInset("关键词", Icons.Default.Tag) {
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    post.keywords.forEachIndexed { index, keyword ->
                                        if (index > 0) Text("·", color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall)
                                        Text(keyword.title, Modifier.clickable { actions.open(keyword.url) }.padding(vertical = 2.dp),
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }
                            post.meta.forEach { block ->
                                PostInset(block.title, Icons.Default.Info) {
                                    richContent(block.html, c.origin, c.fontScale, actions.open, c::showImages)
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
        item("next") { NextPageFooter(c, page, timeline) }
    }
}

@Composable
private fun PostQuote(post: ForumPost, c: AppController, actions: KfActions, richContent: RichContent) {
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        post.replies.forEach { reply ->
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FormatQuote, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("回复", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box(Modifier.padding(top = 4.dp)) {
                        richContent(reply.html, c.origin, c.fontScale, actions.open, c::showImages)
                    }
                    reply.targetUrl?.takeIf { it.isNotBlank() }?.let { target ->
                        Text("查看原楼层", Modifier.padding(top = 4.dp).clickable { actions.open(target) },
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun PostMenu(post: ForumPost, c: AppController, actions: KfActions) {
    if (post.quoteUrl.isBlank() && post.actions.isEmpty()) return
    var open by remember(post.id) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.MoreVert, "帖子操作", Modifier.size(20.dp)) }
        DropdownMenu(open, { open = false }, modifier = Modifier.widthIn(min = 200.dp, max = 300.dp)) {
            if (post.quoteUrl.isNotBlank()) DropdownMenuItem(
                text = { Text("引用回复") }, leadingIcon = { Icon(Icons.Default.FormatQuote, null) },
                onClick = { open = false; actions.open(post.quoteUrl) },
            )
            post.actions.forEach { action ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(action.title)
                            if (action.effect == SiteEffect.CHANGE && SiteActionRisk.needsConfirmation(action)) Text("需要确认", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    leadingIcon = { Icon(if (action.effect == SiteEffect.READ) Icons.AutoMirrored.Filled.ArrowForward else Icons.Default.TouchApp, null) },
                    onClick = { open = false; actions.open(action.url) },
                )
            }
        }
    }
}

@Composable
private fun PostInset(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                Text(title, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
internal fun Pagination(page: PageData?, open: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        OutlinedButton(onClick = { page?.previous?.let(open) }, enabled = page?.previous != null) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(16.dp)); Text(" 上一页") }
        OutlinedButton(onClick = { page?.next?.let(open) }, enabled = page?.next != null) { Text("下一页 "); Icon(Icons.AutoMirrored.Filled.ArrowForward, null, Modifier.size(16.dp)) }
    }
}

@Composable
private fun EmptyState(loading: Boolean, text: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (loading) CircularProgressIndicator(Modifier.size(32.dp)) else Icon(Icons.Default.Forum, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (loading) "正在连接绯月…" else text.ifBlank { "这里还没有可显示的内容" }, Modifier.padding(vertical = 16.dp), style = MaterialTheme.typography.bodyMedium)
        if (!loading) TextButton(onClick = onRetry) { Text("重新加载") }
    }
}

@Composable
fun LoginScreen(c: AppController, actions: KfActions) {
    var username by remember(c.origin) { mutableStateOf("") }
    var password by remember(c.origin) { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var consent by remember(c.origin) { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Icon(Icons.Default.NightsStay, null, Modifier.size(64.dp).padding(bottom = 12.dp), tint = MaterialTheme.colorScheme.primary)
            Text("欢迎回来。", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text("用熟悉的账号，继续未完的讨论。", Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("即将登录的服务器", style = MaterialTheme.typography.labelMedium)
                    Text(c.origin, Modifier.padding(vertical = 8.dp), fontWeight = FontWeight.Bold)
                    Text("非官方代理。凭证只发给这个域名。", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = actions.openSettings) { Text("选择其他域名") }
                }
            }
        }
        item { OutlinedTextField(username, { username = it }, Modifier.fillMaxWidth(), label = { Text("用户名") }, leadingIcon = { Icon(Icons.Default.PersonOutline, null) }, singleLine = true, enabled = !c.busy, shape = RoundedCornerShape(16.dp)) }
        item { OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("密码") }, leadingIcon = { Icon(Icons.Default.LockOpen, null) }, singleLine = true, enabled = !c.busy,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, "显示或隐藏密码") } }, shape = RoundedCornerShape(16.dp)) }
        item {
            Row(Modifier.clickable { consent = !consent }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(consent, { consent = it }); Text("信任此域名并登录", style = MaterialTheme.typography.bodySmall)
            }
        }
        item { Button(onClick = { val value = password; password = ""; c.login(username, value) }, Modifier.fillMaxWidth().height(54.dp), enabled = consent && !c.busy && username.isNotBlank() && password.isNotEmpty()) { Text(if (c.busy) "正在登录…" else "登录", style = MaterialTheme.typography.titleMedium) } }
        item { Text("密码不会保存；会话加密存储在手机本地。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { Box {
            var more by remember { mutableStateOf(false) }
            TextButton(onClick = { more = true }) { Text("更多登录选项"); Icon(Icons.Default.ExpandMore, null, Modifier.size(18.dp)) }
            DropdownMenu(more, { more = false }) {
                DropdownMenuItem(text = { Text("登录选项 / 验证码") }, leadingIcon = { Icon(Icons.Default.VerifiedUser, null) }, onClick = { more = false; actions.openNative("login.php") })
                DropdownMenuItem(text = { Text("注册账号") }, leadingIcon = { Icon(Icons.Default.PersonAdd, null) }, onClick = { more = false; c.openExternal("${c.origin}/register.php") })
            }
        } }
    }
}

@Composable
fun AccountScreen(c: AppController, actions: KfActions, forumAvatar: ForumImage, onLogout: () -> Unit) {
    var section by rememberSaveable { mutableStateOf("入口") }
    val savedSections = rememberSaveableStateHolder()
    savedSections.SaveableStateProvider(section) {
    val state = rememberLazyListState()
    val timeline = ListInteractions(c, state, if (section == "书签") c.siteFavorites else null, HomeTab.ACCOUNT)
    val profile = c.accountProfile
    val initial = c.home?.username?.take(1).orEmpty().ifBlank { "月" }
    val stats = profile?.stats.orEmpty()
    val menuLinks = c.home?.menu.orEmpty()
        .filterNot {
            it.url.contains("action=quit") || it.url.contains("message.php") ||
                it.url.contains("profile.php?action=favor") || it.url.contains("kf_growup.php")
        }
        .distinctBy { it.url }
    LaunchedEffect(c.authenticated) {
        if (c.authenticated) c.refreshDailyStatus()
    }
    LaunchedEffect(section, c.authenticated) {
        if (section == "书签" && c.authenticated) c.loadFavorites()
    }
    RefreshableList(timeline, { c.siteFavorites?.let(c::refreshTimeline) ?: c.loadFavorites(true) }, enabled = section == "书签" && c.authenticated) {
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(Modifier.size(64.dp), shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(initial, style = MaterialTheme.typography.headlineMedium)
                                if (!c.accountAvatar.isNullOrBlank()) forumAvatar(c.accountAvatar.orEmpty(), "", Modifier.fillMaxSize())
                            }
                        }
                        Column(Modifier.weight(1f).padding(start = 16.dp)) {
                            Text(c.home?.username.orEmpty().ifBlank { "尚未登录" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(profile?.headline?.value ?: c.origin.removePrefix("https://"), style = MaterialTheme.typography.bodySmall)
                            Text(if (c.authenticated) "会话已验证" else "登录后访问更多内容", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelMedium)
                        }
                        Button(
                            onClick = { if (c.authenticated) c.claimDailyReward() else actions.openLogin() },
                            enabled = if (c.authenticated) !c.dailyBusy && !c.dailySigned else true,
                            modifier = Modifier.widthIn(min = 100.dp).heightIn(min = 58.dp),
                            shape = RoundedCornerShape(18.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    when {
                                        c.dailyBusy -> "签到中…"
                                        !c.authenticated -> "登录"
                                        c.dailySigned -> "已签到"
                                        else -> "签到"
                                    },
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    when {
                                        c.dailySigned -> "明天再来"
                                        c.authenticated -> "领 KFB"
                                        else -> "进入账号"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                    if (stats.isNotEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f))
                        Row(Modifier.fillMaxWidth().padding(vertical = 13.dp)) {
                            stats.forEach { info ->
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(info.value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(info.label, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f))
                                }
                            }
                        }
                    }
                }
            }
        }
        item { SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            listOf("入口", "书签", "历史").forEachIndexed { index, label ->
                SegmentedButton(selected = section == label, onClick = { section = label }, shape = SegmentedButtonDefaults.itemShape(index, 3), label = { Text(label) })
            }
        } }
        when (section) {
            "书签" -> {
                if (!c.authenticated) {
                    item { Text("登录后查看站点收藏夹", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    item { Button(onClick = actions.openLogin, Modifier.fillMaxWidth()) { Text("登录账号") } }
                } else {
                    val favorites = c.siteFavorites
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("收藏 · ${timeline?.topics?.size ?: favorites?.topics?.size ?: 0} 条", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (c.favoritesBusy && favorites == null) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                    c.favoritesError?.let { message -> item {
                        Text(message, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { c.loadFavorites(force = true) }) { Text("重试") }
                    } }
                    if (favorites != null && favorites.topics.isEmpty() && !c.favoritesBusy) {
                        item { Text("还没有收藏过帖子。阅读时点“收藏本帖”即可加入站点收藏夹。", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    items(timeline?.topics ?: favorites?.topics.orEmpty(), key = { topicIdentity(it.url) }) { TopicCard(it, actions.open) }
                    if (favorites != null) item("next") { NextPageFooter(c, favorites, timeline) }
                    item {
                        OutlinedButton(onClick = { actions.open(favorites?.url ?: "profile.php?action=favor") }, Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.Bookmarks, null, Modifier.size(18.dp))
                            Text(" 管理收藏与分类")
                        }
                    }
                }
            }
            "历史" -> {
                item { Row(verticalAlignment = Alignment.CenterVertically) { Text("最近阅读 · 仅保存在本机", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium); TextButton(onClick = c::clearHistory) { Text("清空") } } }
                items(c.history, key = { it.url }) { TopicCard(it, actions.open) }
            }
            else -> {
                if (!c.authenticated) item { Button(onClick = actions.openLogin, Modifier.fillMaxWidth()) { Text("登录账号") } }
                if (menuLinks.isNotEmpty()) {
                    item { SectionTitle("我的站点", Icons.Default.Dashboard) }
                    items(menuLinks, key = { it.url }) { link ->
                        ListItem(headlineContent = { Text(link.title) }, supportingContent = { Text(accountSubtitle(link.url)) },
                            leadingContent = { Icon(siteIcon(link.url), null, tint = MaterialTheme.colorScheme.primary) }, modifier = Modifier.clickable { actions.open(link.url) })
                    }
                }
                item { SectionTitle("客户端", Icons.Default.Tune) }
                item { ListItem(headlineContent = { Text("偏好与连接") }, supportingContent = { Text("主题、字号与服务器") }, leadingContent = { Icon(Icons.Default.Tune, null, tint = MaterialTheme.colorScheme.primary) }, modifier = Modifier.clickable { actions.openSettings() }) }
                if (c.authenticated) item { ListItem(headlineContent = { Text("清除本机登录") }, leadingContent = { Icon(Icons.AutoMirrored.Filled.ExitToApp, null, tint = MaterialTheme.colorScheme.primary) }, modifier = Modifier.clickable(onClick = onLogout)) }
                c.home?.serverLogout?.takeIf { c.authenticated }?.let { destination -> item {
                    ListItem(headlineContent = { Text("服务端退出登录") }, leadingContent = { Icon(Icons.AutoMirrored.Filled.ExitToApp, null, tint = MaterialTheme.colorScheme.primary) }, modifier = Modifier.clickable { c.openSiteDestination(destination) })
                } }
                item { Text("KF Mobile 0.3.1\n非官方客户端", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
    }
    }
}

private fun accountSubtitle(url: String): String = when {
    url.contains("message.php") -> "收件箱、发件箱与私信"
    url.contains("profile.php?action=show") -> "会员资料与统计"
    url.contains("profile.php?action=modify") -> "头像、签名与密码"
    url.contains("profile.php?action=favor") -> "收藏主题与分类"
    url.contains("kf_fw_1wkfb") -> "评分记录与奖励"
    url.contains("kf_growup") -> "每日奖励与等级"
    url.contains("guanjianci") -> "被 @ 的记录"
    url.contains("kf_no1") -> "会员排行榜"
    url.contains("hack.php") -> "贡献转账与银行"
    url.contains("faq.php") -> "站点规则与帮助"
    else -> "更多账户功能"
}

@Composable
fun SettingsScreen(c: AppController) {
    var remote by rememberSaveable { mutableStateOf(c.configUrl) }
    var custom by rememberSaveable { mutableStateOf("") }
    var pending by remember { mutableStateOf<String?>(null) }
    var invalid by remember { mutableStateOf<String?>(null) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var servers by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionTitle("外观", Icons.Default.Palette) }
        items(ThemeMode.entries, key = { it.name }) { mode ->
            ListItem(headlineContent = { Text(mode.title) }, leadingContent = { RadioButton(c.theme == mode, null) },
                modifier = Modifier.clickable { c.updateTheme(mode) })
        }
        item {
            ListItem(headlineContent = { Text("阅读字号") }, trailingContent = { Text("${(c.fontScale * 100).toInt()}%") })
            Slider(value = c.fontScale, onValueChange = c::updateFontScale, valueRange = 0.85f..1.4f, steps = 10)
        }
        item { HorizontalDivider() }
        item { SectionTitle("连接", Icons.Default.Public) }
        item {
            ListItem(headlineContent = { Text("当前服务器") }, supportingContent = { Text(c.origin) },
                trailingContent = { Icon(Icons.Default.ChevronRight, null) }, modifier = Modifier.clickable { servers = true })
        }
        item { Text("每个服务器独立保存登录状态，不共享 Cookie。", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        item { HorizontalDivider() }
        item {
            ListItem(headlineContent = { Text("高级") }, supportingContent = { Text("在线配置与自定义服务器") },
                trailingContent = { Icon(if (advanced) Icons.Default.ExpandLess else Icons.Default.ExpandMore, "展开或收起高级设置") },
                modifier = Modifier.clickable { advanced = !advanced })
        }
        if (advanced) {
            item { ListItem(headlineContent = { Text("配置来源：${c.catalog.source.label}") }, supportingContent = { Text(c.catalog.warning ?: "在线配置不可用时，自动使用最近缓存或内置列表。") }) }
            item { OutlinedTextField(remote, { remote = it }, Modifier.fillMaxWidth(), label = { Text("GitHub Raw JSON 配置 URL") }, supportingText = { Text("留空使用内置列表") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)) }
            item { FilledTonalButton(onClick = { c.refreshCatalog(remote) }) { Text("更新在线配置") } }
            item { OutlinedTextField(custom, { custom = it; invalid = null }, Modifier.fillMaxWidth(), label = { Text("自定义 HTTPS 域名") }, placeholder = { Text("https://example.com") }, isError = invalid != null, supportingText = { Text(invalid ?: "只使用你信任的域名") }, singleLine = true) }
            item { OutlinedButton(onClick = { try { pending = normalizeOrigin(custom) } catch (e: Exception) { invalid = e.message } }) { Text("使用自定义服务器") } }
            item { Text("备用节点未逐一验证。切换服务器后，登录凭据只发送到选中的域名。", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    if (servers) ModalBottomSheet(onDismissRequest = { servers = false }) {
        Text("切换服务器", Modifier.padding(24.dp), style = MaterialTheme.typography.titleLarge)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 480.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (c.catalog.catalog.domains.none { it.origin == c.origin }) item {
                ListItem(headlineContent = { Text("自定义服务器") }, supportingContent = { Text(c.origin) }, leadingContent = { RadioButton(true, null) })
            }
            items(c.catalog.catalog.domains, key = { it.id }) { domain ->
                ListItem(headlineContent = { Text(domain.name) }, supportingContent = { Text(domain.origin) },
                    leadingContent = { RadioButton(c.origin == domain.origin, null) }, modifier = Modifier.clickable { if (domain.origin != c.origin) { servers = false; pending = domain.origin } })
            }
        }
    }
    pending?.let { target ->
        AlertDialog(onDismissRequest = { pending = null }, title = { Text("切换服务器？") },
            text = { Text("目标：$target\n将使用该域名自己的登录状态。") },
            confirmButton = { TextButton(onClick = { pending = null; c.switchDomain(target) }) { Text("切换") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("取消") } })
    }
}

@Composable
private fun SectionTitle(title: String, icon: ImageVector) {
    Row(Modifier.padding(top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, Modifier.padding(start = 10.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun EditorScreen(c: AppController, forumImage: ForumImage) {
    val form = c.editor ?: return
    var categories by remember { mutableStateOf(false) }
    val showEmoticons = c.showEmoticonPicker
    var body by remember(form) { mutableStateOf(TextFieldValue(c.draftContent, TextRange(c.draftContent.length))) }
    LaunchedEffect(c.draftContent) { if (body.text != c.draftContent) body = TextFieldValue(c.draftContent, TextRange(body.selection.end.coerceAtMost(c.draftContent.length))) }
    val bodyFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var focusBody by remember { mutableStateOf(false) }
    fun applyInsertion(value: EditorInsertion) {
        body = TextFieldValue(value.text, TextRange(value.cursor)); c.draftContent = value.text
        focusBody = true
    }
    LaunchedEffect(focusBody, showEmoticons) {
        if (focusBody && !showEmoticons) {
            if (runCatching { bodyFocus.requestFocus() }.getOrDefault(false)) keyboard?.show()
            focusBody = false
        }
    }
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (form.recipientField != null) item { OutlinedTextField(c.draftRecipient, { c.draftRecipient = it }, Modifier.fillMaxWidth(), label = { Text("收件人") }, singleLine = true, enabled = !c.busy) }
        if (form.categories.isNotEmpty()) item {
            Box {
                OutlinedButton(onClick = { categories = true }, Modifier.fillMaxWidth(), enabled = !c.busy) { Text(form.categories.firstOrNull { it.value == c.draftCategory }?.label ?: "选择分类"); Icon(Icons.Default.ExpandMore, null) }
                DropdownMenu(categories, { categories = false }) { form.categories.forEach { option -> DropdownMenuItem(text = { Text(option.label) }, onClick = { c.draftCategory = option.value; categories = false }) } }
            }
        }
        item { OutlinedTextField(c.draftTitle, { c.draftTitle = it }, Modifier.fillMaxWidth(), label = { Text("标题") }, enabled = !c.busy, shape = RoundedCornerShape(16.dp)) }
        item {
            Column {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    listOf(
                        Triple(Icons.Default.FormatBold, "粗体", "[b]" to "[/b]"),
                        Triple(Icons.Default.FormatQuote, "引用", "[quote]" to "[/quote]"),
                        Triple(Icons.Default.Link, "链接", "[url=https://]" to "[/url]"),
                        Triple(Icons.Default.Image, "图片", "[img]" to "[/img]"),
                    ).forEach { (icon, label, tags) ->
                        IconButton(onClick = { applyInsertion(wrapEditorSelection(body.text, body.selection.start, body.selection.end, tags.first, tags.second)) }, enabled = !c.busy) { Icon(icon, label) }
                    }
                    IconButton(onClick = { focusBody = true; focusManager.clearFocus(); keyboard?.hide(); c.showEmoticonPicker = true }, enabled = !c.busy) { Icon(Icons.Default.InsertEmoticon, "表情") }
                    if (form.uploadEnabled) IconButton(onClick = { c.requestFiles("editor") }, enabled = !c.busy) { Icon(Icons.Default.AttachFile, "附件") }
                }
                TextField(body, { body = it; c.draftContent = it.text }, Modifier.fillMaxWidth().heightIn(min = 250.dp).focusRequester(bodyFocus), placeholder = { Text("写下你的想法…") }, enabled = !c.busy, minLines = 9,
                    colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.background, unfocusedContainerColor = MaterialTheme.colorScheme.background))
            }
        }
        if ("diy_guanjianci" in form.fields) item { OutlinedTextField(c.draftKeywords, { c.draftKeywords = it }, Modifier.fillMaxWidth(), label = { Text("关键词 / @用户") }, supportingText = { Text("按站点规则使用英文逗号分隔，最多 5 个。") }, enabled = !c.busy) }
        item { Text("草稿只在本次编辑中保留。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (form.uploadEnabled) item {
            OutlinedButton(onClick = { c.requestFiles("editor") }, Modifier.fillMaxWidth(), enabled = !c.busy) { Icon(Icons.Default.AttachFile, null); Text(" 选择附件（最多 ${form.uploadLimit} 个）") }
            Text("每个不超过 ${form.uploadMaxBytes / 1024} KB，最多 ${form.uploadLimit} 个。", style = MaterialTheme.typography.bodySmall)
        }
        items(c.editorFiles, key = { it.id }) { file ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(file.name, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { applyInsertion(insertEditorText(body.text, body.selection.start, body.selection.end, " [upload=${file.slot}] ")) }, enabled = !c.busy) { Text("插入") }
                IconButton(onClick = { c.removeEditorFile(file.id) }, enabled = !c.busy) { Icon(Icons.Default.Close, "删除附件") }
            }
        }
        if (c.submitUncertain) item { OutlinedButton(onClick = c::checkEditorSubmission, Modifier.fillMaxWidth()) { Text("核对站点结果") } }
    }
    if (showEmoticons) ModalBottomSheet(onDismissRequest = { c.showEmoticonPicker = false; focusBody = true }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("表情", Modifier.padding(vertical = 10.dp), style = MaterialTheme.typography.titleLarge)
            if (form.emoticons.isEmpty()) Text("表情列表暂不可用，请重新打开编辑器重试。", Modifier.padding(20.dp))
            LazyVerticalGrid(columns = GridCells.Adaptive(68.dp), modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                gridItems(form.emoticons, key = { it.id }) { face ->
                    Surface(onClick = {
                        applyInsertion(insertEditorText(body.text, body.selection.start, body.selection.end, face.code))
                    }, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            forumImage(face.imageUrl, face.label, Modifier.size(40.dp))
                            Text(face.label, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

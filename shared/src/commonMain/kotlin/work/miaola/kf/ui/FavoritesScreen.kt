@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package work.miaola.kf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import work.miaola.kf.*

@Composable
fun FavoritesScreen(c: AppController, page: PageData, actions: KfActions, rich: RichContent? = null) {
    val favorites = page.nativePage?.favorites ?: return
    val timeline = c.timelineFor(page)
    val sources = timeline.managementForms()
    var activeSource by rememberSaveable(page.url) { mutableStateOf(page.url) }
    val form = sources.firstOrNull { it.sourceUrl == activeSource } ?: sources.firstOrNull()
    val activeFavorites = timeline.nativePages.firstOrNull { it.forms.any { f -> f === form } }?.favorites ?: favorites
    val job = form?.fields?.firstOrNull { it.name == "job" && it.kind == SiteFieldKind.RADIO }
    val directory = form?.fields?.firstOrNull { it.name == "type" && it.kind == SiteFieldKind.SELECT }
    var managing by rememberSaveable { mutableStateOf(false) }
    ManagementScope(c, managing)
    val selection = remember(page.url) { SourcePageSelection() }
    LaunchedEffect(c.managementRevision) { selection.clear() }
    val selected = form?.let(selection::fieldIds).orEmpty()
    var categories by remember { mutableStateOf(false) }
    var move by remember { mutableStateOf(false) }
    var target by remember(directory) { mutableStateOf(directory?.initial?.firstOrNull().orEmpty()) }
    val state = rememberLazyListState()
    ListInteractions(c, state, page, enabled = !managing)
    fun batchRequests(operation: String): List<BatchPageRequest> = sources.mapNotNull { candidate ->
        val values = selection.values(candidate)
        if (values.isEmpty()) return@mapNotNull null
        val jobField = candidate.fields.firstOrNull { it.name == "job" && it.options.any { option -> option.value == operation } }
            ?: return@mapNotNull null
        val directoryField = candidate.fields.firstOrNull { it.name == "type" && it.kind == SiteFieldKind.SELECT }
        val overrides = candidate.fields.associate { field -> field.id to when {
            field.id == jobField.id -> listOf(operation)
            field.id == directoryField?.id && operation == "change" -> listOf(target)
            else -> field.initial
        } }
        buildBatchPageRequest(candidate, values, overrides, verifyAbsent = operation == "clear")
    }
    fun submit(operation: String) {
        val operationField = job ?: return
        if (operationField.options.none { it.value == operation }) return
        val requests = batchRequests(operation)
        if (requests.isEmpty() || requests.size != selection.pageCount) {
            c.reportPlatformResult("所选来源页已变化或无法安全复核，未提交", true)
        } else c.requestBatchSiteSubmit(requests)
    }
    RefreshableList(timeline, { c.refreshTimeline(page) }, enabled = !managing) {
        Column(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.weight(1f), state = state, contentPadding = PaddingValues(KfSpacing.ScreenGutter, KfSpacing.Sm, KfSpacing.ScreenGutter, if (managing && selection.count > 0) KfSpacing.BulkBar else if (managing) KfSpacing.Sm else KfSpacing.FabBottom)) {
                item { ManagementRecovery(c, page.url) }
                item { Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { categories = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.FolderOpen, null); Text(" 收藏分类"); Icon(Icons.Default.ExpandMore, null) }
                    TextButton(onClick = { managing = !managing }, enabled = form != null && !c.siteBusy) { Text(if (managing) "完成" else "管理") }
                } }
                if (managing) item { SourcePagePicker(sources, form, selection.count, !c.siteBusy) { activeSource = it } }
                if (managing) item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("共 ${activeFavorites.entries.size} 项", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = { form?.let { selection.choose(it, activeFavorites.entries.mapNotNull { entry -> entry.selectionFieldId }.toSet()) } }, enabled = !c.siteBusy) { Text("全选本页") }
                    TextButton(onClick = { form?.let(selection::clear) }, enabled = !c.siteBusy) { Text("清空本页") }
                } }
                if (managing) {
                    items(activeFavorites.entries, key = { topicIdentity(it.topic.url) }) { entry ->
                        val id = entry.selectionFieldId
                        Row(Modifier.fillMaxWidth().animateItem().clickable(enabled = id != null && !c.siteBusy) {
                            if (id != null && form != null) selection.choose(form, if (id in selected) selected - id else selected + id)
                        }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(id in selected, null, enabled = id != null && !c.siteBusy)
                            FavoriteText(entry.topic, Modifier.weight(1f))
                        }
                        HorizontalDivider()
                    }
                    if (form != null && (job == null || job.options.any { it.value !in setOf("change", "clear") })) item { NativeFormCard(c, form) }
                } else {
                    val topics = timeline.topics
                    if (topics.isEmpty()) item { KfEmptyState(Icons.Default.BookmarkBorder, "还没有收藏主题", "阅读时点“收藏本帖”即可加入站点收藏夹。") }
                    items(topics, key = { topicIdentity(it.url) }) { topic ->
                        Column(Modifier.fillMaxWidth().animateItem().combinedClickable(onClick = { actions.open(topic.url) }, onLongClick = { managing = true })) {
                            FavoriteText(topic, Modifier.padding(vertical = 12.dp)); HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                    item { NextPageFooter(c, page, timeline) }
                }
                if (rich != null) item { TaskPageExtras(c, page.nativePage, actions, rich) }
            }
            // Pinned while managing: the submit action must not require scrolling
            // past every favorite entry.
            if (managing && form != null && selection.count > 0) {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = KfSpacing.ScreenGutter, vertical = KfSpacing.Sm)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("已选 ${selected.size} / ${activeFavorites.entries.size}",
                            Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (selection.pageCount > 1) Text("${selection.pageCount} 页", style = MaterialTheme.typography.labelSmall)
                    }
                    Row(Modifier.padding(top = KfSpacing.Xs), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (job?.options?.any { it.value == "change" } == true && directory != null)
                            OutlinedButton(onClick = {
                                if (selection.pageCount > 1) {
                                    val requests = batchRequests("change")
                                    if (requests.size != selection.pageCount) c.reportPlatformResult("有页面缺少目标分类，未提交", true)
                                    else c.requestBatchSiteSubmit(requests)
                                } else move = true
                            }, enabled = !c.siteBusy && !c.batchBusy && sources.none { c.managementRecoveryFor(it.sourceUrl) != null }, modifier = Modifier.weight(1f)) {
                                Text(if (selection.pageCount > 1) "跨页移动" else "移动分类")
                            }
                        if (job?.options?.any { it.value == "clear" } == true)
                            Button(onClick = {
                                if (selection.pageCount > 1) {
                                    val requests = batchRequests("clear")
                                    if (requests.size != selection.pageCount) c.reportPlatformResult("有页面缺少删除操作，未提交", true)
                                    else c.requestBatchSiteSubmit(requests)
                                } else submit("clear")
                            }, enabled = !c.siteBusy && !c.batchBusy && sources.none { c.managementRecoveryFor(it.sourceUrl) != null }, modifier = Modifier.weight(1f)) {
                                Text(if (c.batchBusy) "正在提交…" else if (selection.pageCount > 1) "跨页删除" else "删除所选")
                            }
                    }
                }
            }
        }
    }
    if (categories) ModalBottomSheet(onDismissRequest = { categories = false }) {
        Text("收藏分类", Modifier.padding(24.dp), style = MaterialTheme.typography.titleLarge)
        LazyColumn(Modifier.heightIn(max = 440.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(favorites.categories, key = { it.url }) { category -> ListItem(headlineContent = { Text(category.title) }, modifier = Modifier.clickable { categories = false; actions.open(category.url) }) }
            val create = page.nativePage.forms.firstOrNull { f -> f.hidden.any { it.name == "job" && it.value == "addtype" } }
            if (create != null) item { NativeFormCard(c, create) }
        }
    }
    if (move && directory != null) ModalBottomSheet(onDismissRequest = { move = false }) {
        Text("移动到分类", Modifier.padding(24.dp), style = MaterialTheme.typography.titleLarge)
        LazyColumn(Modifier.heightIn(max = 400.dp)) { items(directory.options, key = { it.value }) { option ->
            ListItem(headlineContent = { Text(option.label) }, leadingContent = { RadioButton(option.value == target, null) }, modifier = Modifier.clickable { target = option.value })
        } }
        Button(onClick = { move = false; submit("change") }, Modifier.fillMaxWidth().padding(20.dp),
            enabled = !c.siteBusy && !c.batchBusy && selection.count > 0 && sources.none { c.managementRecoveryFor(it.sourceUrl) != null }) { Text("移动所选") }
    }
}

@Composable
private fun FavoriteText(topic: SiteLink, modifier: Modifier) {
    Column(modifier) {
        Text(topic.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (topic.subtitle.isNotBlank()) Text(topic.subtitle, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun MessageSummaryRow(message: NativeMessageSummary, outbox: Boolean, open: (String) -> Unit, modifier: Modifier = Modifier) {
    KfCard(onClick = { open(message.url) }, modifier = modifier, contentPadding = PaddingValues(horizontal = KfSpacing.Lg, vertical = KfSpacing.Md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            InitialAvatar(if (outbox) message.recipient else message.sender)
            Column(Modifier.weight(1f).padding(start = KfSpacing.Md)) {
                Text(
                    message.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (message.unread) FontWeight.Bold else FontWeight.Medium,
                )
                Text(
                    if (outbox) "收件人：${message.recipient}" else message.sender,
                    Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(message.time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            if (message.unread) Icon(Icons.Default.FiberManualRecord, "未读", Modifier.size(10.dp), tint = MaterialTheme.colorScheme.primary)
            else Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
        }
    }
}

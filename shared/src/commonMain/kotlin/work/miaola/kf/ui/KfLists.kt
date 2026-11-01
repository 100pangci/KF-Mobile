@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package work.miaola.kf.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import work.miaola.kf.*

@Composable
internal fun ManagementRecovery(c: AppController, url: String) {
    val source = c.managementRecoveryFor(url) ?: return
    Surface(
        Modifier.fillMaxWidth().padding(vertical = KfSpacing.Sm),
        shape = KfTileShape,
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f)),
    ) {
        Column(Modifier.padding(horizontal = KfSpacing.Lg, vertical = KfSpacing.Md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.WarningAmber, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                Text(
                    "上次操作后的列表待核对",
                    Modifier.padding(start = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            Text(
                "旧选择已清除。重新加载只读取列表，不会重复删除或移动。",
                Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f),
            )
            TextButton(onClick = { c.recoverManagement(source) }, enabled = !c.busy && !c.siteBusy) {
                Text(if (c.busy) "正在核对…" else "重新加载并核对")
            }
        }
    }
}

@Composable
internal fun SourcePagePicker(forms: List<SiteForm>, active: SiteForm?, selectedCount: Int, enabled: Boolean, choose: (String) -> Unit) {
    Column {
        Text("跨页已选 $selectedCount 项 · 逐页提交；提交后页码可能变化，须重新选择", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (forms.size > 1) androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(forms.size) { index -> val form = forms[index]
                FilterChip(selected = form == active, onClick = { choose(form.sourceUrl) }, enabled = enabled, label = { Text("已加载页 ${index + 1}") })
            }
        }
    }
}

@Composable
internal fun ListInteractions(c: AppController, state: LazyListState, page: PageData?, tab: HomeTab? = null, enabled: Boolean = true, anchorKeyAt: ((Int) -> String?)? = null): PageTimeline? {
    val timeline = remember(c.origin, page?.url) { page?.let(c::timelineFor) }
    val latestPage by rememberUpdatedState(page)
    val rootUrl = timeline?.first?.url ?: page?.url.orEmpty()
    var anchorReady by remember(rootUrl) { mutableStateOf(false) }
    LaunchedEffect(timeline, page?.url, state.layoutInfo.totalItemsCount, timeline?.next, timeline?.loadedPageUrls?.size) {
        if (anchorReady) return@LaunchedEffect
        val saved = c.anchorFor(rootUrl)
        if (saved == null) { anchorReady = true; return@LaunchedEffect }
        if (state.layoutInfo.totalItemsCount <= 0) return@LaunchedEffect
        val keyIndex = saved.anchorKey.takeIf { it.isNotBlank() }?.let { key ->
            (0 until state.layoutInfo.totalItemsCount).firstOrNull { anchorKeyAt?.invoke(it) == key }
        }
        val index = if (saved.anchorKey.isNotBlank()) keyIndex else saved.anchorIndex
        if (index != null && index in 0 until state.layoutInfo.totalItemsCount) {
            state.scrollToItem(index, saved.anchorOffset)
            anchorReady = true
        } else if (timeline?.failure != null || timeline?.next == null ||
            timeline.loadedPageUrls.size >= RecoveryStore.MAX_RESTORED_PAGES) {
            // The saved keyed item disappeared or the bounded page rebuild is
            // complete. Allow new anchors only after that safe fallback point.
            anchorReady = true
        }
    }
    LaunchedEffect(timeline, rootUrl) {
        if (timeline == null || rootUrl.isBlank()) return@LaunchedEffect
        snapshotFlow {
            if (!anchorReady) null else state.layoutInfo.visibleItemsInfo.firstOrNull()?.let { info ->
                Triple(info.index, info.offset, anchorKeyAt?.invoke(info.index).orEmpty())
            }
        }.filterNotNull().distinctUntilChanged().collect { (index, offset, key) ->
            c.rememberAnchor(rootUrl, index, offset, key)
        }
    }
    LaunchedEffect(timeline, enabled) {
        if (timeline == null || !enabled) return@LaunchedEffect
        snapshotFlow {
            val layout = state.layoutInfo
            val nearEnd = layout.totalItemsCount > 0 &&
                (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >= layout.totalItemsCount - 4
            Triple(nearEnd, timeline.next, timeline.loading || timeline.refreshing || timeline.failure != null ||
                c.siteBusy || c.purchaseBusy || c.pendingPurchase != null)
        }.distinctUntilChanged().collect { (nearEnd, _, blocked) ->
            if (nearEnd && !blocked) latestPage?.let { c.loadNext(it) }
        }
    }
    if (tab != null) {
        val initial = remember(c.origin, tab) { c.reselectCount(tab) }
        val signal = c.reselectCount(tab)
        LaunchedEffect(signal) {
            if (signal != initial && c.tab == tab && state.layoutInfo.totalItemsCount > 0) state.scrollToItem(0)
        }
    }
    return timeline
}

@Composable
internal fun RefreshableList(timeline: PageTimeline?, onRefresh: () -> Unit, enabled: Boolean = true, content: @Composable BoxScope.() -> Unit) {
    if (enabled) PullToRefreshBox(isRefreshing = timeline?.refreshing == true, onRefresh = onRefresh, modifier = Modifier.fillMaxSize(), content = content)
    else Box(Modifier.fillMaxSize(), content = content)
}

@Composable
internal fun NextPageFooter(c: AppController, page: PageData, timeline: PageTimeline?) {
    if (timeline == null || timeline.next == null) return
    Box(Modifier.fillMaxWidth().padding(vertical = KfSpacing.Lg), contentAlignment = Alignment.Center) {
        when {
            timeline.loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("正在加载更多", Modifier.padding(start = KfSpacing.Sm), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            timeline.failure != null -> TextButton(onClick = { c.loadNext(page, retry = true) }) {
                Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
                Text("加载失败 · 点击重试", Modifier.padding(start = 4.dp))
            }
        }
    }
}

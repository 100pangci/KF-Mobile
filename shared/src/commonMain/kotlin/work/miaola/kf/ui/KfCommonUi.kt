@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package work.miaola.kf.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import work.miaola.kf.*

/** Shared status strip shown above every destination. */
@Composable
fun KfStatusBanners(c: AppController) {
    c.sessionNotice?.let { message ->
        Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                IconButton(onClick = c::dismissSessionNotice) { Icon(Icons.Default.Close, "关闭状态提示") }
            }
        }
    }
}

@Composable
fun KfOperationFeedback(c: AppController, snackbar: SnackbarHostState, hasContent: Boolean) {
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(c.error) { if (hasContent) c.error?.let { snackbar.showSnackbar(it) } }
    LaunchedEffect(c.toast) {
        c.toast?.let { message ->
            if (message in setOf("已收藏", "已取消收藏", "提交成功", "已提交")) haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            snackbar.showSnackbar(message)
            c.clearMessage()
        }
    }
}

@Composable
fun KfPageLoadState(loading: Boolean, error: String?, onRetry: () -> Unit, message: String = "正在加载…") {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (loading) CircularProgressIndicator() else Icon(Icons.Default.CloudOff, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (loading) message else error ?: "这里还没有内容", style = MaterialTheme.typography.bodyMedium)
            if (!loading) TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}

/** Purchase, destructive site action and editor-discard gates. */
@Composable
fun KfGlobalDialogs(c: AppController, logout: Boolean = false, onLogoutChange: (Boolean) -> Unit = {}) {
    if (logout) AlertDialog(onDismissRequest = { onLogoutChange(false) }, title = { Text("清除当前登录？") },
        text = { Text("只清除本机在 ${c.origin} 的登录。") },
        confirmButton = { TextButton(onClick = { onLogoutChange(false); c.logout() }) { Text("清除") } },
        dismissButton = { TextButton(onClick = { onLogoutChange(false) }) { Text("取消") } })

    if (c.confirmDiscard) AlertDialog(onDismissRequest = { c.confirmDiscard = false }, title = { Text("离开编辑？") },
        text = { Text("尚未提交的内容将被丢弃。") },
        confirmButton = { TextButton(onClick = { c.confirmDiscard = false; c.dismissEditor() }) { Text("丢弃") } },
        dismissButton = { TextButton(onClick = { c.confirmDiscard = false }) { Text("继续编辑") } })

    if (c.pendingMessageConfirm) AlertDialog(onDismissRequest = c::dismissMessageConfirm,
        title = { Text("确认私信收件人") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("收件人：${c.draftRecipient}")
                Text("站点按用户名查找，客户端无法确认是否绑定了唯一的 UID，也不会在成功文案中伪造 UID。",
                    style = MaterialTheme.typography.bodySmall)
                Text("发送后只有在发件箱出现新的对应消息时，才会显示“已发送”。",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = c::confirmMessageSend) { Text("确认发送") } },
        dismissButton = { TextButton(onClick = c::dismissMessageConfirm) { Text("返回编辑") } })

    c.pendingBatch?.let { requests ->
        val count = requests.sumOf { it.items.size }
        AlertDialog(onDismissRequest = c::dismissBatch, title = { Text("确认跨页提交？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("将依次提交 ${requests.size} 个来源页，共 $count 项。每个来源页单独复核令牌和项目。")
                    Text("站点没有跨页原子事务：若中途返回不明确，剩余页面会停止，不会自动重试。",
                        style = MaterialTheme.typography.bodySmall)
                    Text("服务器：${c.origin}", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = c::confirmBatch, enabled = !c.batchBusy) { Text(if (c.batchBusy) "正在提交…" else "确认提交") } },
            dismissButton = { TextButton(onClick = c::dismissBatch, enabled = !c.batchBusy) { Text("取消") } })
    }

    c.pendingReward?.let { action ->
        AlertDialog(onDismissRequest = c::dismissReward, title = { Text("确认执行奖励操作？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(action.title)
                    Text(if (action.protocol == RewardProtocol.AJAX_POST)
                        "只执行一个战斗步骤，不会自动连续攻击；结果不明确时不会重试。"
                    else "领取可能消耗资格或次数；提交后会重新读取奖励状态确认。",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("服务器：${c.origin}", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = c::confirmReward, enabled = !c.rewardBusy) { Text(if (c.rewardBusy) "正在执行…" else "确认执行") } },
            dismissButton = { TextButton(onClick = c::dismissReward, enabled = !c.rewardBusy) { Text("取消") } })
    }

    c.batchResult?.let { result ->
        val results = result.results()
        val failed = results.filter { it.outcome != OperationOutcome.SUCCESS }
        AlertDialog(onDismissRequest = c::dismissBatchResult,
            title = { Text(when (result) {
                is BatchTransactionResult.Completed -> "批量操作已完成"
                is BatchTransactionResult.Partial -> "部分项目未完成"
                is BatchTransactionResult.Failed -> "批量操作未完成"
                is BatchTransactionResult.Uncertain -> "部分结果无法确认"
            }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(result.summary())
                    if (failed.isNotEmpty()) {
                        Text("未完成 / 待核对：", style = MaterialTheme.typography.labelMedium)
                        failed.take(8).forEach { item ->
                            Text("· ${item.item.label.ifBlank { item.item.stableId }}：${item.message.ifBlank {
                                when (item.outcome) {
                                    OperationOutcome.REJECTED -> "被站点拒绝"
                                    OperationOutcome.UNCERTAIN -> "结果无法确认"
                                    OperationOutcome.NOT_ATTEMPTED -> "未执行"
                                    OperationOutcome.SUCCESS -> "已完成"
                                }
                            }}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (failed.size > 8) Text("…另有 ${failed.size - 8} 项", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("列表已重新加载；请核对后再重新选择。", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = c::dismissBatchResult) { Text("知道了") } })
    }

    c.pendingPurchase?.let { offer ->
        AlertDialog(onDismissRequest = c::dismissPurchase, icon = { Icon(Icons.Default.ShoppingCart, null) },
            title = { Text(if (c.purchaseBusy) "正在提交购买" else "确认购买付费内容？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("售价 ${offer.price} ${offer.currency}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("服务器：${c.origin}", style = MaterialTheme.typography.bodySmall)
                    Text("按站点规则扣除余额，提交前会复核售价。", style = MaterialTheme.typography.bodyMedium)
                    if (c.purchaseBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = c::confirmPurchase, enabled = !c.purchaseBusy) { Text("支付 ${offer.price} ${offer.currency}") } },
            dismissButton = { TextButton(onClick = c::dismissPurchase, enabled = !c.purchaseBusy) { Text("取消") } })
    }

    if (c.pendingSiteDestination != null || c.pendingSiteSubmission != null) {
        val link = c.pendingSiteDestination
        val form = c.pendingSiteSubmission
        AlertDialog(onDismissRequest = c::dismissSiteAction, title = { Text(if (c.siteBusy) "正在执行" else form?.form?.title ?: link?.title?.ifBlank { "确认操作" } ?: "确认操作") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (link?.effect == SiteEffect.EXTERNAL) "将由外部应用打开链接，不附加论坛 Cookie。" else form?.form?.warning ?: link?.explanation?.ifBlank { "请确认此次操作；可能影响站点状态或余额。" }.orEmpty())
                    Text("服务器：${c.origin}", style = MaterialTheme.typography.bodySmall)
                    form?.let { submission ->
                        submission.form.fields.filter { it.name in listOf("pwuser", "to_money", "drawmoney", "btype", "action", "job") && it.kind != SiteFieldKind.PASSWORD }.forEach { field ->
                            val selected = submission.values.filter { it.name == field.name }.map { it.value }
                            val display = if (field.options.isEmpty()) selected.joinToString() else selected.mapNotNull { value -> field.options.firstOrNull { it.value == value }?.label }.joinToString()
                            if (display.isNotBlank()) Text("${field.label}：$display", style = MaterialTheme.typography.bodyMedium)
                        }
                        val selectedCount = submission.values.count { it.name == "delid[]" }
                        if (selectedCount > 0) Text("已选择 $selectedCount 个项目", style = MaterialTheme.typography.bodyMedium)
                    }
                    if (c.siteBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = c::confirmSiteAction, enabled = !c.siteBusy) { Text("确认执行") } },
            dismissButton = { TextButton(onClick = c::dismissSiteAction, enabled = !c.siteBusy) { Text("取消") } })
    }

}

/** Context belongs to the destination, not to whichever cached page loaded last. */
@Composable
fun KfPageActions(c: AppController, kind: KfDestinationKind, page: PageData?, actions: KfActions, onRefresh: () -> Unit) {
    var menu by remember(kind, page?.url) { mutableStateOf(false) }
    val favoriteListing = page?.nativePage?.favorites != null
    if (kind == KfDestinationKind.EDITOR) {
        TextButton(onClick = c::requestEditorSubmit, enabled = c.editor != null && !c.busy && !c.submitUncertain) {
            if (c.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text(if (c.editor?.recipientField != null) "发送" else "发布")
        }
        return
    }
    if (!favoriteListing && kind in setOf(KfDestinationKind.FEED, KfDestinationKind.BOARDS, KfDestinationKind.LISTING)) {
        IconButton(onClick = actions.openSearch) { Icon(Icons.Default.Search, "搜索") }
    }
    if (kind == KfDestinationKind.LISTING && page?.url?.substringBefore('?')?.endsWith("thread.php") == true) {
        IconButton(onClick = { c.showTopicFilters = true }) { Icon(Icons.Default.FilterList, "筛选主题") }
    }
    if (kind == KfDestinationKind.READER) page?.favorite?.let { favorite ->
        IconButton(onClick = { c.favoriteThread(favorite) }, enabled = !c.siteBusy) {
            if (c.siteBusy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(Icons.Default.BookmarkAdd, favorite.title.ifBlank { "收藏" })
        }
    }
    if (kind == KfDestinationKind.PROFILE) {
        val message = (page?.nativePage?.links.orEmpty() + page?.nativePage?.profile?.links.orEmpty())
            .firstOrNull { it.url.contains("message.php?action=write") }
        if (message != null) IconButton(onClick = { actions.open(message.url) }) { Icon(Icons.Default.MailOutline, "私信") }
    }
    if (kind !in setOf(KfDestinationKind.READER, KfDestinationKind.LISTING, KfDestinationKind.PROFILE,
            KfDestinationKind.NATIVE, KfDestinationKind.MESSAGE, KfDestinationKind.MESSAGES)) return
    val recommend = page?.nativePage?.forms?.firstOrNull { it.id == "rpc:recommend" }
    val tools = !favoriteListing && kind in setOf(KfDestinationKind.READER, KfDestinationKind.LISTING) &&
        (page?.nativePage?.forms?.any { it.id != "rpc:recommend" } == true || page?.nativePage?.links?.isNotEmpty() == true)
    Box {
        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "更多") }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem(text = { Text("刷新") }, leadingIcon = { Icon(Icons.Default.Refresh, null) }, onClick = { menu = false; onRefresh() })
            if (kind == KfDestinationKind.READER && recommend != null) DropdownMenuItem(
                text = { Text("推荐帖子") }, leadingIcon = { Icon(Icons.Default.ThumbUp, null) },
                enabled = !c.siteBusy,
                onClick = { menu = false; c.requestSiteSubmit(recommend, emptyMap(), recommend.submits.first()) })
            if (tools) DropdownMenuItem(text = { Text(if (kind == KfDestinationKind.LISTING) "板块信息与管理" else "更多页面操作") },
                leadingIcon = { Icon(Icons.Default.MoreHoriz, null) }, onClick = { menu = false; actions.openNative(page.url) })
        }
    }
}

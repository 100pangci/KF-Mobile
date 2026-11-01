@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package work.miaola.kf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import work.miaola.kf.*

/** Return false when the task's stable structure is absent: keep the compatibility UI. */
@Composable
internal fun NativeTaskScreen(c: AppController, page: PageData, actions: KfActions, rich: RichContent, avatar: ForumImage): Boolean {
    val native = page.nativePage ?: return false
    val path = page.url.substringBefore('?').substringAfterLast('/')
    when {
        native.bankLog != null -> BankLogScreen(c, native, actions)
        native.message != null -> MessageDetailScreen(c, native, actions, rich)
        native.blocklist != null -> BlocklistScreen(c, native, actions, rich)
        path == "message.php" && page.url.contains("action=scout") && native.hasMessageList -> ListingScreen(c, actions, page, isMessages = true, rich = rich)
        native.profile != null -> ProfileScreen(c, native, actions, rich, avatar)
        path == "profile.php" && page.url.contains("action=modify") && native.forms.size >= 1 -> ProfileSettingsScreen(c, native, actions, rich)
        native.bank != null -> BankScreen(c, native, actions, rich)
        native.statusTimeline != null -> StatusTimelineScreen(c, page, native, actions, rich)
        native.rewards.isNotEmpty() -> RewardScreen(c, page, native, actions, rich)
        native.progress != null -> ProgressScreen(c, page, actions, rich)
        path == "faq.php" -> FaqScreen(c, native, actions, rich)
        path == "kf_no1.php" && native.tables.any { it.records(true).isNotEmpty() } -> RecordScreen(c, page, actions, rich, ranking = true)
        path in setOf("kf_fw_1wkfb.php", "guanjianci.php", "kf_share.php") && native.tables.any { it.records().isNotEmpty() } -> RecordScreen(c, page, actions, rich, ranking = false)
        path in setOf("kf_lv200.php", "guanjianci.php") && native.forms.isEmpty() -> RecordScreen(c, page, actions, rich, ranking = false)
        else -> return false
    }
    return true
}

/**
 * True only when [NativeTaskScreen] will render a screen that owns
 * pull-to-refresh. Keep this precedence in sync with the dispatch above.
 */
internal fun nativeTaskUsesPullToRefresh(page: PageData?): Boolean {
    val current = page ?: return false
    val native = current.nativePage ?: return false
    val path = current.url.substringBefore('?').substringAfterLast('/')
    return when {
        native.bankLog != null -> false
        native.message != null -> false
        native.blocklist != null -> false
        path == "message.php" && current.url.contains("action=scout") && native.hasMessageList -> true
        native.profile != null -> false
        path == "profile.php" && current.url.contains("action=modify") && native.forms.size >= 1 -> false
        native.bank != null -> false
        native.statusTimeline != null -> true
        native.rewards.isNotEmpty() -> false
        native.progress != null -> false
        path == "faq.php" -> false
        path == "kf_no1.php" && native.tables.any { it.records(true).isNotEmpty() } -> true
        path in setOf("kf_fw_1wkfb.php", "guanjianci.php", "kf_share.php") &&
            native.tables.any { it.records().isNotEmpty() } -> true
        path in setOf("kf_lv200.php", "guanjianci.php") && native.forms.isEmpty() -> true
        else -> false
    }
}

@Composable
private fun MessageDetailScreen(c: AppController, native: NativeSitePage, actions: KfActions, rich: RichContent) {
    val message = native.message ?: return
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(KfSpacing.Xl, KfSpacing.Md, KfSpacing.Xl, KfSpacing.ListBottom), verticalArrangement = Arrangement.spacedBy(KfSpacing.Lg)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(KfSpacing.Sm)) {
                if (message.title.isNotBlank() && message.title != native.title) {
                    Text(message.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                KfCard(contentPadding = PaddingValues(horizontal = KfSpacing.Lg, vertical = KfSpacing.Md)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InitialAvatar(message.sender)
                        Column(Modifier.weight(1f).padding(start = KfSpacing.Md)) {
                            Text(message.sender, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(message.time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        item { rich(message.html, c.origin, c.fontScale, actions.open, c::showImages) }
        message.reply?.let { reply -> item { Button(onClick = { actions.open(reply.url) }, Modifier.fillMaxWidth().height(48.dp), shape = KfFieldShape) { Icon(Icons.AutoMirrored.Filled.Reply, null); Text(" 回复") } } }
        item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            message.actions.filterNot { it == message.reply }.forEach { action ->
                TextButton(onClick = { c.openSiteDestination(action) }, enabled = !c.siteBusy) { Text(action.title) }
            }
        } }
        item { TaskPageExtras(c, native.copy(blocks = native.blocks.filterNot {
            it is NativeBlock.Section && it.html == message.html || it is NativeBlock.Links && it.links.all { link -> link in message.actions }
        }), actions, rich) }
    }
}

@Composable
internal fun InitialAvatar(name: String) {
    Surface(Modifier.size(40.dp), shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
        Box(contentAlignment = Alignment.Center) { Text(name.take(1).ifBlank { "月" }, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun BlocklistScreen(c: AppController, native: NativeSitePage, actions: KfActions, rich: RichContent) {
    val model = native.blocklist ?: return
    val form = native.forms.firstOrNull { it.id == model.formId } ?: return
    val field = form.fields.firstOrNull { it.id == model.fieldId } ?: return
    val values = remember(form) { mutableStateMapOf<String, List<String>>().apply { form.fields.forEach { put(it.id, it.initial) } } }
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(KfSpacing.Xl, KfSpacing.Md, KfSpacing.Xl, KfSpacing.ListBottom), verticalArrangement = Arrangement.spacedBy(KfSpacing.Md)) {
        item { ManagementRecovery(c, form.sourceUrl) }
        item { if (native.title.isBlank() || native.title != "屏蔽名单") KfSectionTitle("屏蔽名单", icon = Icons.Default.Block) }
        // Keep the site's exact text syntax. No guessed UID/name conversion.
        native.sections.forEach { section -> item { rich(section.html, c.origin, c.fontScale, actions.open, c::showImages) } }
        item { NativeField(c, form, field.copy(label = "屏蔽名单"), values[field.id].orEmpty()) { values[field.id] = it } }
        items(form.fields.filterNot { it.id == field.id }) { other -> NativeField(c, form, other, values[other.id].orEmpty()) { values[other.id] = it } }
        item { Text("保存前会重新核对页面；填写格式以上方站点说明为准。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        form.submits.forEach { submit -> item { Button(onClick = { c.requestSiteSubmit(form, values.toMap(), submit) }, enabled = !c.siteBusy && c.managementRecoveryFor(form.sourceUrl) == null, modifier = Modifier.fillMaxWidth(), shape = KfFieldShape) {
            if (c.siteBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("保存屏蔽名单")
        } } }
        item { TaskPageExtras(c, native.copy(forms = native.forms.filterNot { it.id == form.id }), actions, rich) }
    }
}

@Composable
private fun StatusTimelineScreen(c: AppController, page: PageData, native: NativeSitePage, actions: KfActions, rich: RichContent) {
    val timeline = native.statusTimeline ?: return
    val state = rememberLazyListState()
    val interactions = ListInteractions(c, state, page)
    RefreshableList(interactions, { c.refreshTimeline(page) }) {
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(KfSpacing.Xl, KfSpacing.Md, KfSpacing.Xl, KfSpacing.ListBottom)) {
            item {
                TaskHeading(
                    timeline.label,
                    native,
                    "状态只按页面明确标签显示；无法判断时标记为“未知”。",
                )
            }
            itemsIndexed(timeline.entries, key = { index, entry -> "$index:${entry.time}:${entry.title}" }) { index, entry ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).animateItem()) {
                    Column(Modifier.width(22.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.padding(top = 7.dp).size(11.dp).clip(CircleShape).background(statusColor(entry.status)))
                        if (index < timeline.entries.lastIndex) Box(Modifier.width(2.dp).fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)))
                    }
                    Column(Modifier.weight(1f).padding(start = 10.dp, bottom = 18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(statusLabel(entry.status), style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold, color = statusColor(entry.status))
                            if (entry.time.isNotBlank()) {
                                Spacer(Modifier.weight(1f))
                                Text(entry.time, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text(entry.title, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (entry.description.isNotBlank() && !entry.description.contains(entry.title)) {
                            Text(entry.description, Modifier.padding(top = 3.dp), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                        if (entry.relatedUrl.isNotBlank()) Text("查看相关主题", Modifier.padding(top = 4.dp)
                            .clickable { actions.open(entry.relatedUrl) }, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                        entry.action?.let { action ->
                            // Rating entries and other change links open the
                            // site's own result page after confirmation; they
                            // are not one-shot reward claims that could be
                            // re-validated and replayed.
                            TextButton(onClick = { c.openSiteDestination(action) },
                                enabled = !c.siteBusy && !c.rewardBusy) {
                                Text(action.title.ifBlank { "处理" })
                            }
                        }
                    }
                }
            }
            item { TaskPageExtras(c, native.copy(statusTimeline = null), actions, rich) }
            item { NextPageFooter(c, page, interactions) }
        }
    }
}

@Composable
private fun statusColor(status: StatusKind): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return when (status) {
        StatusKind.WAITING -> if (dark) Color(0xFFFFB74D) else Color(0xFFB26A00)
        StatusKind.AVAILABLE -> if (dark) Color(0xFF90CAF9) else Color(0xFF1565C0)
        StatusKind.COMPLETED -> if (dark) Color(0xFF81C784) else Color(0xFF2E7D32)
        StatusKind.EXPIRED -> if (dark) Color(0xFFBDBDBD) else Color(0xFF757575)
        StatusKind.FAILED -> if (dark) Color(0xFFEF9A9A) else Color(0xFFC62828)
        StatusKind.UNKNOWN -> if (dark) Color(0xFFBDBDBD) else Color(0xFF757575)
    }
}

private fun statusLabel(status: StatusKind) = when (status) {
    StatusKind.WAITING -> "等待处理"
    StatusKind.AVAILABLE -> "可领取"
    StatusKind.COMPLETED -> "已完成"
    StatusKind.EXPIRED -> "已过期"
    StatusKind.FAILED -> "失败"
    StatusKind.UNKNOWN -> "状态未知"
}

/**
 * The top bar already carries the site page title, so only render a heading
 * when it adds information the bar does not show.
 */
@Composable
private fun TaskHeading(title: String, native: NativeSitePage, subtitle: String? = null) {
    val heading = title.takeIf { it.isNotBlank() && it != native.title }
    if (heading == null && subtitle.isNullOrBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (heading != null) Text(heading, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (!subtitle.isNullOrBlank()) Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BankLogScreen(c: AppController, native: NativeSitePage, actions: KfActions) {
    val log = native.bankLog ?: return
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(KfSpacing.Xl, KfSpacing.Md, KfSpacing.Xl, KfSpacing.ListBottom),
        verticalArrangement = Arrangement.spacedBy(KfSpacing.Md)) {
        item {
            TaskHeading(
                log.label,
                native,
                "只读交易记录，不根据余额变化推断某笔交易成功。",
            )
        }
        if (log.entries.isEmpty()) item { KfEmptyState(Icons.AutoMirrored.Filled.ReceiptLong, "没有可识别的交易记录") }
        items(log.entries, key = { "${it.id}:${it.time}:${it.description.hashCode()}" }) { entry ->
            KfCard(modifier = Modifier.animateItem(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 11.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(entry.type.ifBlank { "记录" }, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text(entry.time, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (entry.amount.isNotBlank()) {
                        val prefix = when (entry.direction) {
                            BankDirection.OUT -> "−"
                            BankDirection.IN -> "+"
                            BankDirection.UNKNOWN -> ""
                        }
                        Text("$prefix${entry.amount} ${entry.currency}".trim(), style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = when (entry.direction) {
                                BankDirection.OUT -> MaterialTheme.colorScheme.error
                                BankDirection.IN -> MaterialTheme.colorScheme.primary
                                BankDirection.UNKNOWN -> MaterialTheme.colorScheme.onSurface
                            })
                    }
                    if (entry.counterparty.isNotBlank()) Text(
                        when (entry.direction) {
                            BankDirection.OUT -> "转给 ${entry.counterparty}"
                            BankDirection.IN -> "来自 ${entry.counterparty}"
                            BankDirection.UNKNOWN -> entry.counterparty
                        }, style = MaterialTheme.typography.bodyMedium)
                    if (entry.memo.isNotBlank()) Text("附言：${entry.memo}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (entry.amount.isBlank()) Text(entry.description, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (log.unparsedRows.isNotEmpty()) item {
            var expanded by rememberSaveable { mutableStateOf(false) }
            TextButton(onClick = { expanded = !expanded }) {
                Text("未识别记录 ${log.unparsedRows.size} 条（保留原样）")
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (expanded) log.unparsedRows.forEach { row ->
                Text(row.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (log.links.isNotEmpty()) item { TaskLinks(log.links, actions) }
    }
}

@Composable
private fun RewardScreen(c: AppController, page: PageData, native: NativeSitePage, actions: KfActions, rich: RichContent) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(KfSpacing.Xl, KfSpacing.Md, KfSpacing.Xl, KfSpacing.ListBottom),
        verticalArrangement = Arrangement.spacedBy(KfSpacing.Md)) {
        item {
            TaskHeading(
                "奖励与争夺",
                native,
                "准备信息来自站点页面和已确认的脚本；实际执行必须由你点按确认，且不会自动连续执行。",
            )
        }
        items(native.rewards, key = { it.id }) { action ->
            KfCard(modifier = Modifier.animateItem(), contentPadding = PaddingValues(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(action.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        action.confirmation.ifBlank {
                            when (action.kind) {
                                "battle-step" -> "一次只执行一个战斗步骤；不会自动刷或连续攻击。"
                                else -> "该操作会改变奖励或道具状态；提交后刷新页面核对，不会自动重试。"
                            }
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { c.requestReward(action) }, enabled = !c.rewardBusy && !c.siteBusy,
                        modifier = Modifier.fillMaxWidth(), shape = KfFieldShape) { Text(if (c.rewardBusy) "正在处理…" else "确认并执行") }
                }
            }
        }
        item { TaskPageExtras(c, native.copy(rewards = emptyList()), actions, rich) }
    }
}

@Composable
private fun ProgressScreen(c: AppController, page: PageData, actions: KfActions, rich: RichContent) {
    val native = page.nativePage ?: return
    val progress = native.progress ?: return
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(KfSpacing.Xl, KfSpacing.Md, KfSpacing.Xl, KfSpacing.ListBottom), verticalArrangement = Arrangement.spacedBy(KfSpacing.Lg)) {
        if (progress.stats.isNotEmpty()) item {
            KfCard {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    progress.stats.forEach { info -> KfStat(info.value, info.label) }
                }
            }
        }
        items(progress.status) { Text(it, style = MaterialTheme.typography.titleSmall) }
        if (progress.penalties.isNotEmpty()) {
            item { KfSectionTitle("扣分记录", icon = Icons.Default.RemoveCircleOutline) }
            items(progress.penalties) { penalty ->
                KfCard(shape = KfTileShape) {
                    Column(
                        Modifier.fillMaxWidth().then(
                            if (penalty.url.isNotBlank()) Modifier.clickable { actions.open(penalty.url) } else Modifier,
                        ),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (penalty.time.isNotBlank()) Text(penalty.time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (penalty.points.isNotBlank()) Text(penalty.points, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                            if (penalty.action.isNotBlank()) Text(penalty.action, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (penalty.title.isNotBlank()) Text(
                            penalty.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (penalty.url.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                        if (penalty.reason.isNotBlank()) Text(
                            "原因：${penalty.reason}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item { TaskLinks(native.links, actions) }
        item { KfSectionTitle("规则与说明", icon = Icons.Default.Info) }
        // No timeline is invented when the response only contains a score.
        native.blocks.forEach { block -> item { when (block) {
            is NativeBlock.Section -> rich(block.html, c.origin, c.fontScale, actions.open, c::showImages)
            is NativeBlock.Table -> SiteTableCard(block.table, actions.open)
            is NativeBlock.Links -> TaskLinks(block.links, actions)
        } } }
        items(native.forms) { NativeFormCard(c, it) }
        if (native.unsupported.isNotEmpty()) item { UnsupportedBlock(native.unsupported) }
    }
}

@Composable
private fun ProfileScreen(c: AppController, native: NativeSitePage, actions: KfActions, rich: RichContent, avatar: ForumImage) {
    val profile = native.profile ?: return
    val details = profile.info.filterNot { it == profile.headline || it in profile.stats }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(KfSpacing.Xl, KfSpacing.Md, KfSpacing.Xl, KfSpacing.ListBottom), verticalArrangement = Arrangement.spacedBy(KfSpacing.Md)) {
        item { KfCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                    if (profile.avatar.isBlank()) InitialAvatar(profile.name) else avatar(profile.avatar, profile.name.take(1), Modifier.size(72.dp))
                }
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(profile.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    profile.headline?.let { Text(it.value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        } }
        if (profile.stats.isNotEmpty()) item { KfCard {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                profile.stats.forEach { stat -> KfStat(stat.value, stat.label) }
            }
        } }
        if (profile.links.isNotEmpty()) item { TaskLinks(profile.links, actions) }
        if (details.isNotEmpty()) item { KfSectionTitle("资料", icon = Icons.Default.Badge) }
        if (details.isNotEmpty()) item { KfCard(contentPadding = PaddingValues(0.dp)) {
            details.forEachIndexed { index, info ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Column(Modifier.padding(horizontal = KfSpacing.Lg, vertical = KfSpacing.Md)) {
                    Text(info.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(info.value, style = MaterialTheme.typography.bodyLarge)
                }
            }
        } }
        item { TaskPageExtras(c, native, actions, rich) }
    }
}

@Composable
private fun ProfileSettingsScreen(c: AppController, native: NativeSitePage, actions: KfActions, rich: RichContent) {
    val form = native.forms.firstOrNull { it.actionUrl.contains("profile.php") } ?: native.forms.first()
    val values = remember(form) { mutableStateMapOf<String, List<String>>().apply { form.fields.forEach { put(it.id, it.initial) } } }
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(KfSpacing.Xl, KfSpacing.Md, KfSpacing.Xl, KfSpacing.ListBottom), verticalArrangement = Arrangement.spacedBy(KfSpacing.Md)) {
        item {
            KfSectionTitle("头像")
            Text("使用 Android 照片选择器；图片会在本机裁剪为站点要求的尺寸，再走单独的 ajaxface 上传协议。",
                Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("上传成功后仍以资料页显示为准；若站点要求继续保存资料，请再点击下方“保存更改”。",
                Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = c::requestAvatarPicker, enabled = !c.avatarBusy && !c.siteBusy,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp), shape = KfFieldShape) {
                Icon(Icons.Default.AccountCircle, null)
                Text(if (c.avatarBusy) " 正在上传…" else " 选择并预览新头像")
            }
            if (c.pendingAvatar != null) Text("已选择：${c.pendingAvatar?.name}，确认后才会发送。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        val groups = form.fields.groupBy(::profileFieldSection)
        listOf("基本资料", "显示", "论坛偏好", "安全", "更多").forEach { group ->
            val fields = groups[group].orEmpty()
            if (fields.isNotEmpty()) {
                item { KfSectionTitle(group) }
                items(fields.filter { it.kind != SiteFieldKind.PASSWORD }, key = { it.id }) { field -> NativeField(c, form, field, values[field.id].orEmpty()) { values[field.id] = it } }
                if (fields.any { it.kind == SiteFieldKind.PASSWORD }) item {
                    var password by rememberSaveable { mutableStateOf(false) }
                    KfExpandableCard(title = "修改密码", expanded = password, onToggle = { password = !password }, subtitle = "不修改时请留空") {
                        fields.filter { it.kind == SiteFieldKind.PASSWORD }.forEach { field -> NativeField(c, form, field, values[field.id].orEmpty()) { values[field.id] = it } }
                    }
                }
            }
        }
        form.submits.forEachIndexed { index, submit -> item { Button(onClick = { c.requestSiteSubmit(form, values.toMap(), submit) }, enabled = !c.siteBusy, modifier = Modifier.fillMaxWidth(), shape = KfFieldShape) {
            if (c.siteBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(if (index == 0) "保存更改" else submit.label)
        } }
        }
        item { TaskPageExtras(c, native.copy(forms = emptyList()), actions, rich) }
    }
}

@Composable
private fun BankScreen(c: AppController, native: NativeSitePage, actions: KfActions, rich: RichContent) {
    val bank = native.bank ?: return
    var active by rememberSaveable { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(KfSpacing.Xl, KfSpacing.Md, KfSpacing.Xl, KfSpacing.ListBottom), verticalArrangement = Arrangement.spacedBy(KfSpacing.Md)) {
        if (bank.balances.isNotEmpty()) item {
            KfCard {
                KfSectionTitle("账户", icon = Icons.Default.AccountBalance)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    bank.balances.forEach { info -> KfStat(info.value, info.label) }
                }
            }
        }
        val logLink = bank.links.firstOrNull { it.url.contains("action=log") }
        logLink?.let { log ->
            item { KfCard(onClick = { actions.open(log.url) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.ReceiptLong, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f).padding(horizontal = KfSpacing.Md)) {
                        Text("查看银行日志（只读）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("余额和日志是两个独立来源；不会根据余额变化推断交易成功。", Modifier.padding(top = 2.dp),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
                }
            } }
        }
        items(native.forms, key = { it.id }) { form ->
            KfExpandableCard(
                title = form.title,
                expanded = active == form.id,
                onToggle = { active = if (active == form.id) null else form.id },
                icon = Icons.Default.AccountBalanceWallet,
            ) {
                val values = remember(form) { mutableStateMapOf<String, List<String>>().apply { form.fields.forEach { put(it.id, it.initial) } } }
                form.fields.forEach { field -> NativeField(c, form, field, values[field.id].orEmpty()) { values[field.id] = it } }
                Text(form.warning, style = MaterialTheme.typography.bodySmall)
                form.submits.forEach { submit -> Button(onClick = { c.requestSiteSubmit(form, values.toMap(), submit) }, enabled = !c.siteBusy, modifier = Modifier.fillMaxWidth(), shape = KfFieldShape) {
                    if (c.siteBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(submit.label)
                } }
            }
        }
        item { TaskPageExtras(c, native.copy(forms = emptyList()), actions, rich) }
        val extraLinks = bank.links.filterNot { it.url == logLink?.url }
        if (extraLinks.isNotEmpty()) item { TaskLinks(extraLinks, actions) }
        if (bank.rules.isNotEmpty()) item {
            var expanded by rememberSaveable { mutableStateOf(false) }
            KfExpandableCard(title = "规则与说明", expanded = expanded, onToggle = { expanded = !expanded }) {
                bank.rules.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@Composable
private fun RecordScreen(c: AppController, page: PageData, actions: KfActions, rich: RichContent, ranking: Boolean) {
    val native = page.nativePage ?: return
    val state = rememberLazyListState()
    val timeline = ListInteractions(c, state, page)
    val loaded = timeline?.nativePages?.toList().orEmpty().ifEmpty { listOf(native) }
    var userFilter by rememberSaveable(page.url) { mutableStateOf("") }
    RefreshableList(timeline, { c.refreshTimeline(page) }) {
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(KfSpacing.ScreenGutter, KfSpacing.Sm, KfSpacing.ScreenGutter, KfSpacing.ListBottom), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (native.links.isNotEmpty()) item { TaskLinks(native.links, actions) }
        if (ranking) item { OutlinedTextField(userFilter, { userFilter = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("查找已加载用户") }, supportingText = { Text("仅筛选已加载排名") }, shape = KfFieldShape) }
        val seenRecords = mutableSetOf<List<String>>()
        val seenSections = mutableSetOf<String>()
        loaded.forEachIndexed { pageIndex, current ->
        if (pageIndex > 0 && current.links.isNotEmpty()) item { TaskLinks(current.links, actions) }
        val blocks = current.blocks.ifEmpty { current.sections.map { NativeBlock.Section(it.title, it.html) } + current.tables.map { NativeBlock.Table(it) } }
        blocks.forEach { block -> when (block) {
            is NativeBlock.Section -> if (seenSections.add(block.html)) item { rich(block.html, c.origin, c.fontScale, actions.open, c::showImages) }
            is NativeBlock.Links -> item { TaskLinks(block.links, actions) }
            is NativeBlock.Table -> {
                val records = block.table.records(ranking).filter { seenRecords.add(it.identity()) }
                if (records.isEmpty()) item {
                    if (block.table.style == NativeTableStyle.NAV) {
                        TaskLinks((block.table.rows.flatten().flatMap { it.actions } + block.table.links).distinctBy { it.url }, actions)
                    } else if (block.table.records(ranking).isEmpty()) SiteTableCard(block.table, actions.open)
                }
                else items(records.filter { !ranking || it.primary.text.contains(userFilter, ignoreCase = true) }) { record ->
                    Column(Modifier.fillMaxWidth().animateItem().padding(vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (ranking) KfPill(record.title, Modifier.padding(end = KfSpacing.Sm),
                                container = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(record.primary.text, Modifier.weight(1f).then(if (record.primary.link != null) Modifier.clickable { actions.open(record.primary.link) } else Modifier),
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            if (ranking && record.primary.text == c.home?.username) Text("你", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                        }
                        if (!ranking) Text(record.title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (ranking) record.details.firstOrNull()?.let { metric ->
                            Text("${metric.label} ${metric.value}", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            (if (ranking) record.details.drop(1) else record.details).forEach { Text("${it.label} ${it.value}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        TaskLinks(record.actions.filterNot { it.url == record.primary.link }, actions)
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        } }
        items(current.forms) { key(current, it) { NativeFormCard(c, it) } }
        if (current.unsupported.isNotEmpty()) item { UnsupportedBlock(current.unsupported) }
        }
        item { NextPageFooter(c, page, timeline) }
    }
    }
}

@Composable
private fun FaqScreen(c: AppController, native: NativeSitePage, actions: KfActions, rich: RichContent) {
    val blocks = native.blocks.ifEmpty { native.sections.map { NativeBlock.Section(it.title, it.html) } + native.tables.map { NativeBlock.Table(it) } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(KfSpacing.Xl, KfSpacing.Md, KfSpacing.Xl, KfSpacing.ListBottom), verticalArrangement = Arrangement.spacedBy(KfSpacing.Lg)) {
        if (native.links.isNotEmpty()) item { TaskLinks(native.links, actions) }
        blocks.forEach { block -> when (block) {
            is NativeBlock.Section -> item {
                var expanded by rememberSaveable(block.html) { mutableStateOf(true) }
                if (block.title.isNotBlank()) {
                    KfExpandableCard(title = block.title, expanded = expanded, onToggle = { expanded = !expanded }) {
                        rich(block.html, c.origin, c.fontScale, actions.open, c::showImages)
                    }
                } else {
                    KfCard(contentPadding = PaddingValues(14.dp)) {
                        rich(block.html, c.origin, c.fontScale, actions.open, c::showImages)
                    }
                }
            }
            is NativeBlock.Links -> item { TaskLinks(block.links, actions) }
            is NativeBlock.Table -> item {
                // Mixed prose/link rows carry rules too, not only navigation.
                if (block.table.style == NativeTableStyle.DEFINITION) KfCard(contentPadding = PaddingValues(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        block.table.rows.forEach { row -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.firstOrNull()?.let { Text(it.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
                            row.drop(1).forEach { Text(it.text, style = MaterialTheme.typography.bodyMedium) }
                            TaskLinks((row.flatMap { it.actions } + row.mapNotNull { cell -> cell.link?.let { url -> c.resolve(url, cell.text) } }).distinctBy { it.url }, actions)
                        } }
                        TaskLinks(block.table.links, actions)
                    }
                } else SiteTableCard(block.table, actions.open)
            }
        } }
        items(native.forms) { NativeFormCard(c, it) }
        if (native.unsupported.isNotEmpty()) item { UnsupportedBlock(native.unsupported) }
    }
}

/** A visible escape hatch for additional server content in every task adapter. */
@Composable
internal fun TaskPageExtras(c: AppController, native: NativeSitePage, actions: KfActions, rich: RichContent) {
    val blocks = native.blocks.ifEmpty { native.sections.map { NativeBlock.Section(it.title, it.html) } + native.tables.map { NativeBlock.Table(it) } }
    if (blocks.isEmpty() && native.forms.isEmpty() && native.links.isEmpty() && native.unsupported.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    KfExpandableCard(
        title = "页面补充信息",
        expanded = expanded,
        onToggle = { expanded = !expanded },
        icon = Icons.Default.MoreHoriz,
    ) {
        TaskLinks(native.links, actions)
        blocks.forEach { block -> when (block) {
            is NativeBlock.Section -> {
                if (block.title.isNotBlank()) KfSectionTitle(block.title)
                rich(block.html, c.origin, c.fontScale, actions.open, c::showImages)
            }
            is NativeBlock.Table -> SiteTableCard(block.table, actions.open)
            is NativeBlock.Links -> TaskLinks(block.links, actions)
        } }
        native.forms.forEach { NativeFormCard(c, it) }
        if (native.unsupported.isNotEmpty()) UnsupportedBlock(native.unsupported)
    }
}

@Composable
internal fun TaskLinks(links: List<SiteDestination>, actions: KfActions) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        links.forEach { link -> TextButton(onClick = { actions.open(link.url) }) { Text(link.title) } }
    }
}

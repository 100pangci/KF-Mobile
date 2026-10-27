@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package work.miaola.kf.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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

@Composable
private fun MessageDetailScreen(c: AppController, native: NativeSitePage, actions: KfActions, rich: RichContent) {
    val message = native.message ?: return
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Text(message.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { ListItem(headlineContent = { Text(message.sender, maxLines = 2, overflow = TextOverflow.Ellipsis) }, supportingContent = { Text(message.time) },
            leadingContent = { InitialAvatar(message.sender) }) }
        item { rich(message.html, c.origin, c.fontScale, actions.open, c::showImages) }
        message.reply?.let { reply -> item { Button(onClick = { actions.open(reply.url) }, Modifier.fillMaxWidth()) { Icon(Icons.AutoMirrored.Filled.Reply, null); Text(" 回复") } } }
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
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ManagementRecovery(c, form.sourceUrl) }
        item { Text("屏蔽名单", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
        // Keep the site's exact text syntax. No guessed UID/name conversion.
        native.sections.forEach { section -> item { rich(section.html, c.origin, c.fontScale, actions.open, c::showImages) } }
        item { NativeField(c, form, field.copy(label = "屏蔽名单"), values[field.id].orEmpty()) { values[field.id] = it } }
        items(form.fields.filterNot { it.id == field.id }) { other -> NativeField(c, form, other, values[other.id].orEmpty()) { values[other.id] = it } }
        item { Text("保存前会重新核对页面；填写格式以上方站点说明为准。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        form.submits.forEach { submit -> item { Button(onClick = { c.requestSiteSubmit(form, values.toMap(), submit) }, enabled = !c.siteBusy && c.managementRecoveryFor(form.sourceUrl) == null, modifier = Modifier.fillMaxWidth()) {
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
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 100.dp)) {
            item {
                Text(timeline.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("状态只按页面明确标签显示；无法判断时标记为“未知”。", Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            itemsIndexed(timeline.entries) { index, entry ->
                Row(Modifier.fillMaxWidth()) {
                    Column(Modifier.width(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.padding(top = 7.dp).size(11.dp).clip(CircleShape).background(statusColor(entry.status)))
                        if (index < timeline.entries.lastIndex) Box(Modifier.width(2.dp).height(54.dp)
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
                            val reward = native.rewards.firstOrNull { it.endpoint == action.url }
                            TextButton(onClick = { if (reward != null) c.requestReward(reward) else c.openSiteDestination(action) },
                                enabled = !c.rewardBusy && !c.siteBusy) {
                                Text(if (reward != null) "查看并领取" else action.title)
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

private fun statusColor(status: StatusKind) = when (status) {
    StatusKind.WAITING -> androidx.compose.ui.graphics.Color(0xFFB26A00)
    StatusKind.AVAILABLE -> androidx.compose.ui.graphics.Color(0xFF1565C0)
    StatusKind.COMPLETED -> androidx.compose.ui.graphics.Color(0xFF2E7D32)
    StatusKind.EXPIRED -> androidx.compose.ui.graphics.Color(0xFF757575)
    StatusKind.FAILED -> androidx.compose.ui.graphics.Color(0xFFC62828)
    StatusKind.UNKNOWN -> androidx.compose.ui.graphics.Color(0xFF757575)
}

private fun statusLabel(status: StatusKind) = when (status) {
    StatusKind.WAITING -> "等待处理"
    StatusKind.AVAILABLE -> "可领取"
    StatusKind.COMPLETED -> "已完成"
    StatusKind.EXPIRED -> "已过期"
    StatusKind.FAILED -> "失败"
    StatusKind.UNKNOWN -> "状态未知"
}

@Composable
private fun BankLogScreen(c: AppController, native: NativeSitePage, actions: KfActions) {
    val log = native.bankLog ?: return
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 100.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(log.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("只读交易记录，不根据余额变化推断某笔交易成功。", Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (log.entries.isEmpty()) item { Text("没有可识别的交易记录。", Modifier.padding(16.dp)) }
        items(log.entries, key = { "${it.id}:${it.time}:${it.description.hashCode()}" }) { entry ->
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 100.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("奖励与争夺", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("准备信息来自站点页面和已确认的脚本；实际执行必须由你点按确认，且不会自动连续执行。",
                Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(native.rewards, key = { it.id }) { action ->
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(action.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        if (action.protocol == RewardProtocol.AJAX_POST) "一次只执行一个战斗步骤；不会自动刷或连续攻击。"
                        else "领取请求可能消耗资格或次数，提交后会重新读取状态确认。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { c.requestReward(action) }, enabled = !c.rewardBusy && !c.siteBusy,
                        modifier = Modifier.fillMaxWidth()) { Text(if (c.rewardBusy) "正在处理…" else "确认并执行") }
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
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        items(progress.stats) { info -> Column {
            Text(info.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(info.value, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        } }
        items(progress.status) { Text(it, style = MaterialTheme.typography.titleSmall) }
        item { TaskLinks(native.links, actions) }
        item { Text("规则与说明", style = MaterialTheme.typography.titleSmall) }
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
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                if (profile.avatar.isBlank()) InitialAvatar(profile.name) else avatar(profile.avatar, profile.name.take(1), Modifier.size(72.dp))
            }
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(profile.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                profile.headline?.let { Text(it.value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        } }
        if (profile.stats.isNotEmpty()) item { FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            profile.stats.forEach { stat -> Column {
                Text(stat.value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(stat.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        } }
        if (profile.links.isNotEmpty()) item { TaskLinks(profile.links, actions) }
        item { HorizontalDivider(); Text("资料", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleSmall) }
        items(profile.info.filterNot { it == profile.headline || it in profile.stats }) { info ->
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(info.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(info.value, style = MaterialTheme.typography.bodyLarge)
            }
        }
        item { TaskPageExtras(c, native, actions, rich) }
    }
}

@Composable
private fun ProfileSettingsScreen(c: AppController, native: NativeSitePage, actions: KfActions, rich: RichContent) {
    val form = native.forms.firstOrNull { it.actionUrl.contains("profile.php") } ?: native.forms.first()
    val values = remember(form) { mutableStateMapOf<String, List<String>>().apply { form.fields.forEach { put(it.id, it.initial) } } }
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("头像", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text("使用 Android 照片选择器；图片会在本机裁剪为站点要求的尺寸，再走单独的 ajaxface 上传协议。",
                Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("上传成功后仍以资料页显示为准；若站点要求继续保存资料，请再点击下方“保存更改”。",
                Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = c::requestAvatarPicker, enabled = !c.avatarBusy && !c.siteBusy,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
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
                item { Text(group, Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
                items(fields.filter { it.kind != SiteFieldKind.PASSWORD }, key = { it.id }) { field -> NativeField(c, form, field, values[field.id].orEmpty()) { values[field.id] = it } }
                if (fields.any { it.kind == SiteFieldKind.PASSWORD }) item {
                    var password by rememberSaveable { mutableStateOf(false) }
                    ListItem(headlineContent = { Text("修改密码") }, supportingContent = { Text("不修改时请留空") },
                        trailingContent = { Icon(if (password) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null) }, modifier = Modifier.clickable { password = !password })
                    if (password) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        fields.filter { it.kind == SiteFieldKind.PASSWORD }.forEach { field -> NativeField(c, form, field, values[field.id].orEmpty()) { values[field.id] = it } }
                    }
                }
            }
        }
        form.submits.forEachIndexed { index, submit -> item { Button(onClick = { c.requestSiteSubmit(form, values.toMap(), submit) }, enabled = !c.siteBusy, modifier = Modifier.fillMaxWidth()) {
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
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (bank.balances.isNotEmpty()) item {
            Text("账户", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                bank.balances.forEach { info -> Column(Modifier.padding(vertical = 8.dp)) {
                    Text(info.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(info.value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                } }
            }
        }
        bank.links.firstOrNull { it.url.contains("action=log") }?.let { log ->
            item { ListItem(headlineContent = { Text("查看银行日志（只读）") },
                supportingContent = { Text("余额和日志是两个独立来源；不会根据余额变化推断交易成功。") },
                leadingContent = { Icon(Icons.AutoMirrored.Filled.ReceiptLong, null) },
                modifier = Modifier.clickable { actions.open(log.url) }) }
        }
        items(native.forms, key = { it.id }) { form ->
            ListItem(headlineContent = { Text(form.title) }, leadingContent = { Icon(Icons.Default.AccountBalanceWallet, null) },
                trailingContent = { Icon(if (active == form.id) Icons.Default.ExpandLess else Icons.Default.ChevronRight, null) },
                modifier = Modifier.clickable { active = if (active == form.id) null else form.id })
            if (active == form.id) {
                val values = remember(form) { mutableStateMapOf<String, List<String>>().apply { form.fields.forEach { put(it.id, it.initial) } } }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    form.fields.forEach { field -> NativeField(c, form, field, values[field.id].orEmpty()) { values[field.id] = it } }
                    Text(form.warning, style = MaterialTheme.typography.bodySmall)
                    form.submits.forEach { submit -> Button(onClick = { c.requestSiteSubmit(form, values.toMap(), submit) }, enabled = !c.siteBusy, modifier = Modifier.fillMaxWidth()) {
                        if (c.siteBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(submit.label)
                    } }
                }
            }
        }
        item { TaskPageExtras(c, native.copy(forms = emptyList()), actions, rich) }
        if (bank.links.isNotEmpty()) item { TaskLinks(bank.links, actions) }
        if (bank.rules.isNotEmpty()) item {
            var expanded by rememberSaveable { mutableStateOf(false) }
            TextButton(onClick = { expanded = !expanded }) { Text("规则与说明"); Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null) }
            if (expanded) bank.rules.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
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
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 80.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (native.links.isNotEmpty()) item { TaskLinks(native.links, actions) }
        if (ranking) item { OutlinedTextField(userFilter, { userFilter = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("查找已加载用户") }, supportingText = { Text("仅筛选已加载排名") }) }
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
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (ranking) Text(record.title, Modifier.width(48.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            Text(record.primary.text, Modifier.weight(1f).then(if (record.primary.link != null) Modifier.clickable { actions.open(record.primary.link) } else Modifier),
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            if (ranking && record.primary.text == c.home?.username) Text("你", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                        }
                        if (!ranking) Text(record.title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (ranking) record.details.firstOrNull()?.let { metric ->
                            Text("${metric.label} ${metric.value}", Modifier.padding(start = 48.dp, top = 4.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        }
                        FlowRow(Modifier.padding(start = if (ranking) 48.dp else 0.dp, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (native.links.isNotEmpty()) item { TaskLinks(native.links, actions) }
        blocks.forEach { block -> when (block) {
            is NativeBlock.Section -> item {
                var expanded by rememberSaveable { mutableStateOf(true) }
                if (block.title.isNotBlank()) ListItem(headlineContent = { Text(block.title, fontWeight = FontWeight.Bold) },
                    trailingContent = { Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null) }, modifier = Modifier.clickable { expanded = !expanded })
                if (expanded) rich(block.html, c.origin, c.fontScale, actions.open, c::showImages)
            }
            is NativeBlock.Links -> item { TaskLinks(block.links, actions) }
            is NativeBlock.Table -> item {
                // Mixed prose/link rows carry rules too, not only navigation.
                if (block.table.style == NativeTableStyle.DEFINITION) Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    block.table.rows.forEach { row -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.firstOrNull()?.let { Text(it.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold) }
                        row.drop(1).forEach { Text(it.text, style = MaterialTheme.typography.bodyMedium) }
                        TaskLinks((row.flatMap { it.actions } + row.mapNotNull { cell -> cell.link?.let { url -> c.resolve(url, cell.text) } }).distinctBy { it.url }, actions)
                    } }
                    TaskLinks(block.table.links, actions)
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
    Column {
        TextButton(onClick = { expanded = !expanded }) { Text("页面补充信息"); Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null) }
        if (expanded) {
            TaskLinks(native.links, actions)
            blocks.forEach { block -> when (block) {
                is NativeBlock.Section -> { if (block.title.isNotBlank()) Text(block.title, style = MaterialTheme.typography.titleSmall); rich(block.html, c.origin, c.fontScale, actions.open, c::showImages) }
                is NativeBlock.Table -> SiteTableCard(block.table, actions.open)
                is NativeBlock.Links -> TaskLinks(block.links, actions)
            } }
            native.forms.forEach { NativeFormCard(c, it) }
            if (native.unsupported.isNotEmpty()) UnsupportedBlock(native.unsupported)
        }
    }
}

@Composable
internal fun TaskLinks(links: List<SiteDestination>, actions: KfActions) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        links.forEach { link -> TextButton(onClick = { actions.open(link.url) }) { Text(link.title) } }
    }
}

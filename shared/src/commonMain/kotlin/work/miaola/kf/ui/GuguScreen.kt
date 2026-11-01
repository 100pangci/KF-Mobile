@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package work.miaola.kf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import work.miaola.kf.AppController
import work.miaola.kf.GuguAction
import work.miaola.kf.GuguItem
import work.miaola.kf.GuguLogEntry
import work.miaola.kf.GuguMenu
import work.miaola.kf.GuguShopItem
import work.miaola.kf.GuguSnapshot
import work.miaola.kf.GuguWinRateTable

@Composable
fun GuguScreen(c: AppController, actions: KfActions) {
    val snapshot = c.gugu
    LaunchedEffect(Unit) { c.loadGugu() }
    if (snapshot == null) {
        Box(Modifier.fillMaxSize()) {
            if (c.guguBusy || c.guguError == null) KfSkeletonScreen(rows = 4)
            else KfEmptyState(
                icon = Icons.Default.SportsEsports,
                title = c.guguError ?: "正在读取咕咕镇…",
                message = "操作需要逐次确认；客户端不会自动连续战斗、购买或重试。",
                actionLabel = "重新读取",
                onAction = { c.loadGugu(force = true) },
            )
        }
        return
    }
    c.pendingGuguAction?.let { action ->
        GuguActionDialog(c, action)
    }
    if (c.pendingGuguAction == null) {
        c.guguMenu?.let { menu -> GuguMenuDialog(c, menu) }
    }
    val character = snapshot.character
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(KfSpacing.ScreenGutter, 10.dp, KfSpacing.ScreenGutter, KfSpacing.ListBottom),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        c.guguError?.takeIf { c.pendingGuguAction == null }?.let { message ->
            item {
                KfCard(color = MaterialTheme.colorScheme.errorContainer) {
                    Text(message, Modifier.padding(KfSpacing.Md), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(onClick = { c.clearGuguError() }) { Text("知道了") }
                }
            }
        }
        item {
            Surface(Modifier.fillMaxWidth(), shape = KfCardShape,
                color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(character.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (character.rank.isNotBlank()) GuguMetric("段位", character.rank)
                        if (character.level.isNotBlank()) GuguMetric("等级", character.level)
                        if (character.shells.isNotBlank()) GuguMetric("贝壳", character.shells)
                    }
                    if (character.monthlyWins.isNotBlank() || character.monthlyNpc.isNotBlank() || character.monthlyNpcLevel.isNotBlank() || character.monthlyPvp.isNotBlank()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f))
                        Text("当月战绩", style = MaterialTheme.typography.labelMedium)
                        if (character.monthlyWins.isNotBlank()) Text("总战斗 ${character.monthlyWins}", style = MaterialTheme.typography.bodySmall)
                        if (character.monthlyNpc.isNotBlank()) Text("对 NPC ${character.monthlyNpc}", style = MaterialTheme.typography.bodySmall)
                        if (character.monthlyNpcLevel.isNotBlank()) Text("战胜 NPC 最高等级 ${character.monthlyNpcLevel}", style = MaterialTheme.typography.bodySmall)
                        if (character.monthlyPvp.isNotBlank()) Text("对玩家 ${character.monthlyPvp}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text(snapshot.readOnlyNote, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { c.loadGugu(force = true) }, enabled = !c.guguBusy) {
                Icon(Icons.Default.Refresh, null, Modifier.size(18.dp)); Text(" 刷新数据")
            }
        }
        if (snapshot.cooldowns.isNotEmpty()) {
            item { KfSectionTitle("冷却", icon = Icons.Default.Timer) }
            items(snapshot.cooldowns) { cooldown ->
                ListItem(headlineContent = { Text(cooldown.label) },
                    supportingContent = { if (cooldown.description.isNotBlank()) Text(cooldown.description) },
                    trailingContent = { Text(cooldown.remaining, fontWeight = FontWeight.Bold) })
            }
        }
        if (snapshot.actions.isNotEmpty()) {
            item { KfSectionTitle("可执行操作", icon = Icons.Default.TouchApp) }
            item {
                KfCard(contentPadding = PaddingValues(KfSpacing.Md)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        snapshot.actions.forEach { action ->
                            GuguActionButton(c, action)
                        }
                        Text("每次只提交一项操作；提交后重新读取游戏状态，不会自动连续执行。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (snapshot.equipped.isNotEmpty()) {
            item { KfSectionTitle("已装备", icon = Icons.Default.Shield) }
            items(snapshot.equipped) { GuguItemRow(it, c) }
        }
        if (snapshot.inventory.isNotEmpty()) {
            item { KfSectionTitle("背包 / 仓库 (${snapshot.inventory.size})", icon = Icons.Default.Inventory2) }
            items(snapshot.inventory.take(80)) { GuguItemRow(it, c) }
            if (snapshot.inventory.size > 80) item {
                Text("仅显示前 80 项，共 ${snapshot.inventory.size} 项。", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (snapshot.cards.isNotEmpty()) {
            item { KfSectionTitle("角色卡片 (${snapshot.cards.size})", icon = Icons.Default.Style) }
            items(snapshot.cards.take(60)) { GuguItemRow(it, c) }
            if (snapshot.cards.size > 60) item {
                Text("仅显示前 60 项，共 ${snapshot.cards.size} 项。", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        snapshot.winRates?.let { table ->
            item { KfSectionTitle("全站战绩统计", icon = Icons.Default.Insights) }
            item { GuguWinRateCard(table) }
        }
        if (snapshot.shop.isNotEmpty()) {
            item { KfSectionTitle("道具商店", icon = Icons.Default.Storefront) }
            item {
                KfCard(contentPadding = PaddingValues(horizontal = KfSpacing.Lg, vertical = KfSpacing.Sm)) {
                    snapshot.shop.forEachIndexed { index, item ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                if (item.dailyLimit) Text("每日限购", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                            Text(item.price, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            item.action?.let { action ->
                                TextButton(onClick = { c.requestGuguAction(action) }, enabled = !c.guguActionBusy) {
                                    Text(action.label)
                                }
                            }
                        }
                    }
                    Text("购买会消耗游戏内货币，每次都需要单独确认；不会自动重复购买或重试。",
                        Modifier.padding(top = KfSpacing.Sm, bottom = KfSpacing.Sm), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (snapshot.updateLog.isNotEmpty()) {
            item { KfSectionTitle("更新日志", icon = Icons.Default.History) }
            items(snapshot.updateLog.take(6)) { entry -> GuguLogCard(entry) }
            if (snapshot.updateLog.size > 6) item {
                Text("仅显示最近 6 条，共 ${snapshot.updateLog.size} 条。", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (snapshot.character.name.isBlank()) item { Text("未识别到角色状态，保留只读说明。") }
    }
}

@Composable
private fun GuguMetric(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun GuguItemRow(item: GuguItem, c: AppController) {
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (item.level.isNotBlank()) Text("Lv.${item.level}", Modifier.padding(end = 8.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(item.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (item.quality.isNotBlank()) Text(item.quality, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (item.stats.isNotEmpty()) Text(item.stats.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
        if (item.actions.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item.actions.forEach { action ->
                AssistChip(
                    onClick = { c.requestGuguAction(action) },
                    enabled = !c.guguActionBusy,
                    label = { Text(action.label) },
                )
            }
        }
    }
}

@Composable
private fun GuguActionButton(c: AppController, action: GuguAction) {
    OutlinedButton(onClick = { c.requestGuguAction(action) }, enabled = !c.guguActionBusy,
        modifier = Modifier.fillMaxWidth()) {
        Text(if (action.destructive) "${action.label}（需确认）" else action.label)
    }
}

@Composable
private fun GuguActionDialog(c: AppController, action: GuguAction) {
    AlertDialog(
        onDismissRequest = { if (!c.guguActionBusy) c.cancelGuguAction() },
        title = { Text(action.label) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(action.confirmation, style = MaterialTheme.typography.bodyMedium)
                if (action.detail.isNotBlank()) Text(action.detail, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (action.inputLabel.isNotBlank()) OutlinedTextField(
                    value = c.guguActionInput,
                    onValueChange = c::updateGuguActionInput,
                    label = { Text(action.inputLabel) },
                    supportingText = { if (action.inputHint.isNotBlank()) Text(action.inputHint) },
                    singleLine = true,
                    enabled = !c.guguActionBusy,
                )
                action.fields.forEach { field ->
                    OutlinedTextField(
                        value = c.guguActionValue(field.name),
                        onValueChange = { c.updateGuguActionValue(field.name, it) },
                        label = { Text(field.label) },
                        supportingText = { if (field.hint.isNotBlank()) Text(field.hint) },
                        singleLine = true,
                        enabled = !c.guguActionBusy,
                    )
                }
                c.guguError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text("提交后不会自动重试；若结果不确定，请重新读取游戏状态核对。",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { c.confirmGuguAction() }, enabled = !c.guguActionBusy) {
                Text(if (c.guguActionBusy) "提交中…" else "确认")
            }
        },
        dismissButton = {
            TextButton(onClick = { c.cancelGuguAction() }, enabled = !c.guguActionBusy) { Text("取消") }
        },
    )
}

/** Choices are returned by the game's menu, then each one still needs confirmation. */
@Composable
private fun GuguMenuDialog(c: AppController, menu: GuguMenu) {
    AlertDialog(
        onDismissRequest = { if (!c.guguActionBusy) c.cancelGuguMenu() },
        title = { Text(menu.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("请选择本次操作；选择后仍会显示确认，不会自动执行。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(menu.actions, key = { it.id }) { action ->
                        OutlinedButton(onClick = { c.chooseGuguMenu(action) },
                            enabled = !c.guguActionBusy, modifier = Modifier.fillMaxWidth()) {
                            Text(action.label)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { c.cancelGuguMenu() }, enabled = !c.guguActionBusy) { Text("关闭") }
        },
    )
}

/** Read-only rendering of the site's card-vs-card win-rate matrix. */
@Composable
private fun GuguWinRateCard(table: GuguWinRateTable) {
    val scroll = rememberScrollState()
    KfCard(contentPadding = PaddingValues(vertical = KfSpacing.Sm)) {
        Column(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
            Row(Modifier.padding(horizontal = KfSpacing.Md), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (table.rank.isBlank()) "卡牌" else "${table.rank}段位",
                    Modifier.width(84.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
                table.cards.forEach { card ->
                    Text(card, Modifier.width(44.dp), style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                }
            }
            table.rows.forEach { row ->
                GuguWinRateLine("${row.card} 进攻", row.attack, table.cards.size)
                GuguWinRateLine("${row.card} 防守", row.defense, table.cards.size)
            }
        }
        if (table.note.isNotBlank()) Text(
            table.note,
            Modifier.padding(horizontal = KfSpacing.Lg, vertical = KfSpacing.Sm),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GuguWinRateLine(label: String, values: List<String>, columns: Int) {
    Row(Modifier.padding(horizontal = KfSpacing.Md), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(84.dp), style = MaterialTheme.typography.labelSmall, maxLines = 1)
        repeat(columns) { index ->
            val value = values.getOrNull(index).orEmpty()
            Text(
                value,
                Modifier.width(44.dp),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                color = guguRateColor(value),
            )
        }
    }
}

private fun guguRateColor(value: String): Color {
    val percent = value.removeSuffix("%").trim().toFloatOrNull() ?: return Color.Unspecified
    return when {
        percent > 90 -> Color(0xFF2E7D32)
        percent > 70 -> Color(0xFF66BB6A)
        percent < 30 -> Color(0xFFE57373)
        else -> Color.Unspecified
    }
}

@Composable
private fun GuguLogCard(entry: GuguLogEntry) {
    var expanded by rememberSaveable(entry.date) { mutableStateOf(false) }
    KfExpandableCard(
        title = entry.date,
        expanded = expanded,
        onToggle = { expanded = !expanded },
        subtitle = entry.lines.firstOrNull()?.take(48),
        icon = Icons.Default.History,
    ) {
        entry.lines.forEach { line ->
            Text(line, style = MaterialTheme.typography.bodySmall)
        }
    }
}

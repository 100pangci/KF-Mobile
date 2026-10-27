@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package work.miaola.kf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import work.miaola.kf.AppController
import work.miaola.kf.GuguItem
import work.miaola.kf.GuguSnapshot

@Composable
fun GuguScreen(c: AppController, actions: KfActions) {
    val snapshot = c.gugu
    LaunchedEffect(Unit) { c.loadGugu() }
    if (snapshot == null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (c.guguBusy) CircularProgressIndicator()
                else Text(c.guguError ?: "正在读取咕咕镇…", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { c.loadGugu(force = true) }, enabled = !c.guguBusy) { Text("重新读取") }
                Text("只读页面；不会执行战斗、使用道具或购买。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    val character = snapshot.character
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 10.dp, 16.dp, 80.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("咕咕镇 · 只读", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(character.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (character.rank.isNotBlank()) GuguMetric("段位", character.rank)
                        if (character.level.isNotBlank()) GuguMetric("等级", character.level)
                        if (character.shells.isNotBlank()) GuguMetric("贝壳", character.shells)
                    }
                    if (character.monthlyWins.isNotBlank() || character.monthlyNpc.isNotBlank() || character.monthlyPvp.isNotBlank()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.15f))
                        Text("当月战绩", style = MaterialTheme.typography.labelMedium)
                        if (character.monthlyWins.isNotBlank()) Text("总战斗 ${character.monthlyWins}", style = MaterialTheme.typography.bodySmall)
                        if (character.monthlyNpc.isNotBlank()) Text("对 NPC ${character.monthlyNpc}", style = MaterialTheme.typography.bodySmall)
                        if (character.monthlyPvp.isNotBlank()) Text("对玩家 ${character.monthlyPvp}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text(snapshot.readOnlyNote, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { c.loadGugu(force = true) }, enabled = !c.guguBusy) {
                Icon(Icons.Default.Refresh, null, Modifier.size(18.dp)); Text(" 刷新只读数据")
            }
        }
        if (snapshot.cooldowns.isNotEmpty()) item {
            GuguSection("冷却")
            snapshot.cooldowns.forEach { cooldown ->
                ListItem(headlineContent = { Text(cooldown.label) },
                    supportingContent = { if (cooldown.description.isNotBlank()) Text(cooldown.description) },
                    trailingContent = { Text(cooldown.remaining, fontWeight = FontWeight.Bold) })
            }
        }
        if (snapshot.equipped.isNotEmpty()) item {
            GuguSection("已装备")
            snapshot.equipped.forEach { GuguItemRow(it) }
        }
        if (snapshot.inventory.isNotEmpty()) item {
            GuguSection("背包 / 仓库 (${snapshot.inventory.size})")
            snapshot.inventory.take(80).forEach { GuguItemRow(it) }
        }
        if (snapshot.cards.isNotEmpty()) item {
            GuguSection("角色卡片 (${snapshot.cards.size})")
            snapshot.cards.take(60).forEach { GuguItemRow(it) }
        }
        if (snapshot.character.name.isBlank()) item { Text("未识别到角色状态，保留只读说明。") }
    }
}

@Composable
private fun GuguSection(title: String) {
    Text(title, Modifier.padding(top = 8.dp, bottom = 4.dp), style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun GuguMetric(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun GuguItemRow(item: GuguItem) {
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
    }
}

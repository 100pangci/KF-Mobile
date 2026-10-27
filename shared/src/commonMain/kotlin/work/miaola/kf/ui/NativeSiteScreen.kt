@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package work.miaola.kf.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import work.miaola.kf.*

@Composable
fun NativeSiteScreen(c: AppController, page: PageData?, actions: KfActions, richContent: RichContent, forumImage: ForumImage, forumAvatar: ForumImage = forumImage) {
    val native = page?.nativePage
    if (native == null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("暂时无法显示内容，请稍后重试", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { page?.let { c.ensurePage(it.url, force = true) } }) { Text("重试") }
            }
        }
        return
    }
    native.growth?.let { growth ->
        GrowthScreen(c, growth, native, actions, richContent)
        return
    }
    if (NativeTaskScreen(c, page, actions, richContent, forumAvatar)) return
    val background = MaterialTheme.colorScheme.background
    val state = rememberLazyListState()
    val messageDetail = page.url.contains("message.php") && page.url.contains("action=read")
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f), background), endY = 680f))) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 2.dp, 16.dp, 110.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item { PageBar(native, actions.open, c::openSiteDestination) }
            native.profile?.let { profile -> item { ProfileCard(profile, c.origin, forumAvatar) } }
            if (native.blocks.isNotEmpty()) {
                items(native.blocks.size) { index ->
                    when (val block = native.blocks[index]) {
                        is NativeBlock.Section -> {
                            if (block.title.isNotBlank() || block.html.isNotBlank()) SectionBlock(block, c, actions, richContent)
                        }
                        is NativeBlock.Table -> SiteTableCard(block.table, actions.open)
                        is NativeBlock.Links -> LinkFlow(block.links, actions.open)
                    }
                }
            } else {
                items(native.sections) { section ->
                    SectionBlock(NativeBlock.Section(section.title, section.html), c, actions, richContent)
                }
                native.tables.forEach { table -> item { SiteTableCard(table, actions.open) } }
            }
            if (native.unsupported.isNotEmpty() && !messageDetail) item { UnsupportedBlock(native.unsupported) }
            if (native.forms.isNotEmpty()) {
                item { SectionLabel("页面操作", Icons.Default.EditNote) }
                items(native.forms, key = { it.id }) { form -> NativeFormCard(c, form) }
            }
            if (page.next != null || page.previous != null) item { Pagination(page, actions.open) }
        }
    }
}

@Composable
private fun GrowthScreen(c: AppController, growth: NativeGrowth, native: NativeSitePage, actions: KfActions, rich: RichContent) {
    val background = MaterialTheme.colorScheme.background
    val state = rememberLazyListState()
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.24f), background), endY = 720f))) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("当前成长", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(growth.level.ifBlank { "—" }, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(" 级", Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (growth.coefficient.isNotBlank()) GrowthMetric("神秘系数", growth.coefficient)
                            if (growth.experience.isNotBlank()) GrowthMetric("成长经验", growth.experience)
                        }
                        growth.progress?.let { progress ->
                            val fraction = (progress.coerceIn(0, 100) / 100f)
                            Column(Modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.14f))) {
                                    Box(Modifier.fillMaxWidth(fraction).height(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                                }
                                Row {
                                    Text("升级经验已完成", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f))
                                    Text("$progress%", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                }
                            }
                        }
                    }
                }
            }
            item {
                Button(
                    onClick = c::claimDailyReward,
                    enabled = !c.dailyBusy && !c.dailySigned && growth.claimStatus == StatusKind.AVAILABLE,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Default.CheckCircle, null, Modifier.size(20.dp))
                    Text(
                        when {
                            c.dailyBusy -> " 签到中…"
                            c.dailySigned || growth.claimed -> " 今日已签到"
                            else -> " 签到领 KFB"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    when {
                        growth.claimStatus == StatusKind.COMPLETED -> "状态：已完成（站点明确标记）"
                        growth.claimStatus == StatusKind.AVAILABLE -> "状态：可领取（站点提供签到入口）"
                        growth.claimStatus == StatusKind.WAITING -> "状态：等待处理"
                        growth.claimStatus == StatusKind.EXPIRED -> "状态：已过期"
                        growth.claimStatus == StatusKind.FAILED -> "状态：失败"
                        else -> "状态：未知（站点未提供可判断的状态）"
                    },
                    Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (growth.colors.isNotEmpty()) item {
                Text("成长颜色", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    growth.colors.forEach { color -> TextButton(onClick = { actions.open(color.url) }, enabled = !c.siteBusy) {
                        Box(Modifier.size(20.dp).clip(CircleShape).background(parseColor(color.color) ?: MaterialTheme.colorScheme.primary))
                        Text(color.label.ifBlank { "选择颜色" }, Modifier.padding(start = 6.dp))
                    } }
                }
            }
            item { TaskPageExtras(c, native, actions, rich) }
        }
    }
}

@Composable
private fun GrowthMetric(label: String, value: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, Modifier.padding(start = 5.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PageBar(native: NativeSitePage, onRead: (String) -> Unit, onAction: (SiteDestination) -> Unit) {
    if (native.links.isEmpty()) return
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        PageMenu(native.links, onRead, onAction)
    }
}

@Composable
private fun PageMenu(links: List<SiteDestination>, onRead: (String) -> Unit, onAction: (SiteDestination) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)) {
            Icon(Icons.Default.MoreVert, null, Modifier.size(18.dp))
            Text("操作", Modifier.padding(start = 2.dp), style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(open, { open = false }, modifier = Modifier.widthIn(min = 240.dp, max = 320.dp)) {
            var lastEffect: SiteEffect? = null
            links.forEach { link ->
                if (lastEffect != null && lastEffect != link.effect) HorizontalDivider(Modifier.padding(vertical = 4.dp))
                lastEffect = link.effect
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(link.title.ifBlank { "更多功能" }, style = MaterialTheme.typography.bodyMedium)
                            if (link.effect != SiteEffect.READ) Text(effectHint(link), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    leadingIcon = { Icon(effectIcon(link.effect), null, Modifier.size(20.dp)) },
                    onClick = {
                        open = false
                        if (link.effect == SiteEffect.READ) onRead(link.url) else onAction(link)
                    },
                )
            }
        }
    }
}

@Composable
private fun ProfileCard(profile: NativeProfile, origin: String, forumImage: ForumImage) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))) {
        Column {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    forumImage(profile.avatar, profile.name.take(1), Modifier.size(64.dp))
                }
                Column(Modifier.weight(1f).padding(start = 16.dp)) {
                    Text(profile.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    profile.headline?.let {
                        Text(it.value, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            profile.info.filterNot { it == profile.headline }.forEachIndexed { index, info ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
                    Text(info.label, Modifier.width(88.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(info.value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun SectionBlock(block: NativeBlock.Section, c: AppController, actions: KfActions, richContent: RichContent) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (block.title.isNotBlank()) SectionTitle(block.title)
        if (block.html.isBlank()) return@Column
        if (block.compact) {
            if (block.text.length in 2..40 && block.text.none { it in "。！？；：:" }) {
                SectionTitle(block.text)
            } else {
                Box(Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp)) {
                    richContent(block.html, c.origin, c.fontScale, actions.open, c::showImages)
                }
            }
        } else {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))) {
                Box(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    richContent(block.html, c.origin, c.fontScale, actions.open, c::showImages)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(15.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.primary))
        Text(title, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SectionLabel(text: String, icon: ImageVector) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Text(text, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun SiteTableCard(table: NativeTable, onLink: (String) -> Unit) {
    if (table.rows.isEmpty() && table.links.isEmpty()) return
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))) {
        Column(Modifier.fillMaxWidth()) {
            if (table.caption.isNotBlank()) {
                val parts = table.caption.split(' ', limit = 2)
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (table.caption.length > 30 && parts.size == 2 && parts[0].length <= 24) {
                        Text(parts[0], style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text(parts[1], Modifier.padding(top = 3.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text(table.caption, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
            if ((table.rows.maxOfOrNull { row -> row.sumOf { it.colspan } } ?: 0) >= 5) {
                Text("左右滑动查看完整表格", Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            when (table.style) {
                NativeTableStyle.DEFINITION -> DefinitionTable(table, onLink)
                NativeTableStyle.NAV -> NavTable(table, onLink)
                NativeTableStyle.PALETTE -> PaletteTable(table, onLink)
                NativeTableStyle.GRID -> GridTable(table, onLink)
            }
            if (table.links.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                LinkFlow(table.links, onLink, Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
            }
        }
    }
}

@Composable
private fun DefinitionTable(table: NativeTable, onLink: (String) -> Unit) {
    table.rows.forEachIndexed { index, row ->
        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
        if (row.size == 1) {
            TableText(row.first(), onLink, Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), MaterialTheme.typography.bodyMedium)
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.Top) {
                TableText(row.first(), onLink, Modifier.width(100.dp), MaterialTheme.typography.bodySmall)
                Box(Modifier.weight(1f)) {
                    if (row.getOrNull(1)?.text.isNullOrBlank() && row.getOrNull(1)?.link == null) {
                        Text("在下方填写", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    } else row.getOrNull(1)?.let { TableText(it, onLink, Modifier.fillMaxWidth(), MaterialTheme.typography.bodyMedium) }
                }
            }
        }
    }
}

@Composable
private fun NavTable(table: NativeTable, onLink: (String) -> Unit) {
    val cells = table.rows.flatten()
    Column {
        cells.chunked(4).forEachIndexed { index, chunk ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                chunk.forEachIndexed { cellIndex, cell ->
                    if (cellIndex > 0) VerticalDivider(Modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    Box(
                        Modifier.weight(1f).fillMaxHeight()
                            .then(if (cell.link != null) Modifier.clickable { onLink(cell.link) } else Modifier)
                            .padding(horizontal = 6.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        TableText(cell, onLink, Modifier.fillMaxWidth(), MaterialTheme.typography.bodySmall, align = NativeAlign.CENTER)
                    }
                }
                repeat(4 - chunk.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun PaletteTable(table: NativeTable, onLink: (String) -> Unit) {
    Column {
        table.rows.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                row.forEachIndexed { cellIndex, cell ->
                    if (cellIndex > 0) VerticalDivider(Modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    val bg = parseColor(cell.background) ?: MaterialTheme.colorScheme.surfaceContainerHigh
                    val fg = if (bg.luminance() > 0.55f) Color(0xFF1A1A1A) else Color.White
                    Box(
                        Modifier.weight(cell.colspan.toFloat()).fillMaxHeight()
                            .background(bg)
                            .then(if (cell.link != null) Modifier.clickable { onLink(cell.link) } else Modifier)
                            .padding(horizontal = 4.dp, vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(cell.text, color = fg, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun GridTable(table: NativeTable, onLink: (String) -> Unit) {
    val columns = table.rows.maxOfOrNull { row -> row.sumOf { it.colspan } }?.coerceIn(1, 12) ?: 1
    val scrollable = columns >= 5
    val state = rememberScrollState()
    val header = if (table.header) table.rows.firstOrNull() else null
    val body = if (table.header) table.rows.drop(1) else table.rows
    Column(Modifier.fillMaxWidth().then(if (scrollable) Modifier.horizontalScroll(state) else Modifier)) {
        header?.let { row ->
            GridRow(row, columns, true, scrollable, onLink)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
        body.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            GridRow(row, columns, false, scrollable, onLink, zebra = index % 2 == 1)
        }
    }
}

@Composable
private fun GridRow(row: List<NativeCell>, columns: Int, header: Boolean, scrollable: Boolean, onLink: (String) -> Unit, zebra: Boolean = false) {
    val background = when {
        header -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        zebra -> MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.35f)
        else -> Color.Transparent
    }
    Row(
        Modifier.fillMaxWidth().then(if (scrollable) Modifier.width((columns * 100).dp) else Modifier)
            .height(IntrinsicSize.Min).background(background).padding(vertical = 9.dp),
        verticalAlignment = Alignment.Top,
    ) {
        row.forEachIndexed { index, cell ->
            if (index > 0) VerticalDivider(Modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
            val modifier = if (scrollable) Modifier.width((100 * cell.colspan).dp) else Modifier.weight(cell.colspan.toFloat())
            Box(modifier.padding(horizontal = 9.dp)) {
                TableText(
                    cell, onLink, Modifier.fillMaxWidth(),
                    if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
                    bold = header || cell.bold,
                    align = cell.align,
                )
            }
        }
        val used = row.sumOf { it.colspan }
        if (!scrollable && used < columns) Spacer(Modifier.weight((columns - used).toFloat()))
    }
}

@Composable
private fun TableText(cell: NativeCell, onLink: (String) -> Unit, modifier: Modifier, style: androidx.compose.ui.text.TextStyle,
                      bold: Boolean = cell.bold, align: NativeAlign = cell.align) {
    val color = parseColor(cell.color)
    Column(modifier) {
    Text(
        text = cell.text,
        modifier = Modifier.then(if (cell.link != null) Modifier.clickable { onLink(cell.link) } else Modifier),
        style = style,
        fontWeight = if (bold || cell.link != null) FontWeight.Medium else FontWeight.Normal,
        color = color ?: if (cell.link != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        textAlign = when (align) { NativeAlign.CENTER -> TextAlign.Center; NativeAlign.END -> TextAlign.End; NativeAlign.START -> TextAlign.Start },
    )
    cell.actions.filterNot { it.url == cell.link }.forEach { action ->
        TextButton(onClick = { onLink(action.url) }) { Text(action.title) }
    }
    }
}

@Composable
private fun LinkFlow(links: List<SiteDestination>, onLink: (String) -> Unit, modifier: Modifier = Modifier.fillMaxWidth()) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        links.forEachIndexed { index, link ->
            if (index > 0) Text("·", color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.bodySmall)
            Text(link.title.ifBlank { "打开" }, Modifier.clickable { onLink(link.url) }.padding(vertical = 2.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
internal fun UnsupportedBlock(items: List<String>) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Block, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("暂不可用", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
            items.forEach { item ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 6.dp).size(4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outline))
                    val label = when {
                        item.contains("咕咕镇战斗") -> "暂不支持咕咕镇的战斗与背包"
                        item.contains("头像上传") -> "暂不支持上传头像"
                        else -> item
                    }
                    Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun NativeFormCard(c: AppController, form: SiteForm) {
    var expanded by remember(form) { mutableStateOf(form.fields.size <= 12) }
    val selectionRevision = if (form.fields.any { it.name == "delid[]" }) c.managementRevision else 0
    val values = remember(form, selectionRevision) { mutableStateMapOf<String, List<String>>().apply {
        form.fields.forEach { put(it.id, if (it.name == "delid[]") emptyList() else it.initial) }
    } }
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))) {
        Column {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.EditNote, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(form.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    if (!expanded && form.fields.isNotEmpty()) Text("${form.fields.size} 个字段 · 点按展开", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, "展开或收起表单")
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ManagementRecovery(c, form.sourceUrl)
                    if (form.fields.count { it.kind == SiteFieldKind.CHECKBOX } > 3) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("共 ${form.fields.count { it.kind == SiteFieldKind.CHECKBOX }} 项", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            TextButton(onClick = { form.fields.filter { it.kind == SiteFieldKind.CHECKBOX }.forEach { values[it.id] = it.options.map { o -> o.value } } }) { Text("全选") }
                            TextButton(onClick = { form.fields.filter { it.kind == SiteFieldKind.CHECKBOX }.forEach { values[it.id] = emptyList() } }) { Text("清空") }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    }
                    form.fields.forEach { field -> NativeField(c, form, field, values[field.id].orEmpty()) { values[field.id] = it } }
                    if (form.id != "rpc:recommend") Text(if (form.warning == "提交会改变站点数据，请核对后确认。") "请核对填写内容后提交。" else form.warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    form.submits.forEachIndexed { index, submit ->
                        val enabled = !c.siteBusy && !c.busy && c.managementRecoveryFor(form.sourceUrl) == null
                        if (index == 0) Button(onClick = { c.requestSiteSubmit(form, values.toMap(), submit) }, enabled = enabled, modifier = Modifier.fillMaxWidth().height(46.dp)) {
                            Text(submit.label)
                        } else OutlinedButton(onClick = { c.requestSiteSubmit(form, values.toMap(), submit) }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                            Text(submit.label)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun NativeField(c: AppController, form: SiteForm, field: SiteField, values: List<String>, update: (List<String>) -> Unit) {
    when (field.kind) {
        SiteFieldKind.TEXT, SiteFieldKind.PASSWORD, SiteFieldKind.MULTILINE, SiteFieldKind.NUMBER -> {
            var visible by remember { mutableStateOf(false) }
            OutlinedTextField(value = values.firstOrNull().orEmpty(), onValueChange = { update(listOf(it)) }, modifier = Modifier.fillMaxWidth(),
                label = { Text(field.label) }, singleLine = field.kind != SiteFieldKind.MULTILINE,
                minLines = if (field.kind == SiteFieldKind.MULTILINE) 3 else 1,
                visualTransformation = if (field.kind == SiteFieldKind.PASSWORD && !visible) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = when (field.kind) { SiteFieldKind.PASSWORD -> KeyboardType.Password; SiteFieldKind.NUMBER -> KeyboardType.Decimal; else -> KeyboardType.Text }),
                trailingIcon = if (field.kind == SiteFieldKind.PASSWORD) { { IconButton(onClick = { visible = !visible }) { Icon(Icons.Default.Visibility, "显示或隐藏") } } } else null,
                enabled = !c.siteBusy && field.editable, shape = RoundedCornerShape(10.dp))
        }
        SiteFieldKind.CHECKBOX -> Row(Modifier.fillMaxWidth().clickable(enabled = !c.siteBusy && field.editable) { update(if (values.isEmpty()) field.options.map { it.value } else emptyList()) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(values.isNotEmpty(), { checked -> update(if (checked) field.options.map { it.value } else emptyList()) }, enabled = !c.siteBusy && field.editable)
            Text(field.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        SiteFieldKind.SELECT -> {
            var open by remember { mutableStateOf(false) }
            if (field.multiple) {
                Text(field.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                field.options.forEach { option -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(option.value in values, { checked -> update(if (checked) values + option.value else values - option.value) })
                    Text(option.label)
                } }
            } else Box {
                OutlinedButton(onClick = { open = true }, Modifier.fillMaxWidth(), enabled = !c.siteBusy, shape = RoundedCornerShape(10.dp)) {
                    Text("${field.label}：${field.options.firstOrNull { it.value == values.firstOrNull() }?.label.orEmpty()}", Modifier.weight(1f), textAlign = TextAlign.Start, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(Icons.Default.ExpandMore, null)
                }
                DropdownMenu(open, { open = false }) { field.options.forEach { option -> DropdownMenuItem(text = { Text(option.label) }, onClick = { update(listOf(option.value)); open = false }) } }
            }
        }
        SiteFieldKind.RADIO -> {
            Text(field.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
            Column(Modifier.fillMaxWidth()) {
                field.options.forEach { option -> Row(Modifier.fillMaxWidth().clickable(enabled = !c.siteBusy && field.editable) { update(listOf(option.value)) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(values.firstOrNull() == option.value, { update(listOf(option.value)) }, enabled = !c.siteBusy && field.editable); Text(option.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                } }
            }
        }
        SiteFieldKind.FILE -> {
            OutlinedButton(onClick = { c.requestFiles(form.id, field.id, field.multiple) }, modifier = Modifier.fillMaxWidth(), enabled = !c.siteBusy, shape = RoundedCornerShape(10.dp)) { Icon(Icons.Default.AttachFile, null, Modifier.size(18.dp)); Text(" ${field.label}", Modifier.weight(1f), textAlign = TextAlign.Start) }
            c.formFiles["${form.id}@${field.id}"].orEmpty().forEach { Text(it.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

private fun effectHint(link: SiteDestination): String = when (link.effect) {
    SiteEffect.READ -> "打开页面"
    SiteEffect.CHANGE -> if (SiteActionRisk.needsConfirmation(link)) "需要确认" else "点按执行"
    SiteEffect.DOWNLOAD -> "下载文件"
    SiteEffect.EXTERNAL -> "外部应用打开"
    SiteEffect.UNSUPPORTED -> "暂不可用"
}

private fun effectIcon(effect: SiteEffect): ImageVector = when (effect) {
    SiteEffect.READ -> Icons.AutoMirrored.Filled.ArrowForward
    SiteEffect.CHANGE -> Icons.Default.TouchApp
    SiteEffect.DOWNLOAD -> Icons.Default.Download
    SiteEffect.EXTERNAL -> Icons.AutoMirrored.Filled.OpenInNew
    SiteEffect.UNSUPPORTED -> Icons.Default.Block
}

internal fun siteIcon(url: String): ImageVector {
    val address = url.substringAfter("://", url)
    val path = (if ('/' in address) address.substringAfter('/') else address).substringBefore('?')
    val action = url.substringAfter("action=", "").substringBefore('&')
    return when {
        path == "hack.php" || path == "kf_growup.php" || path == "job.php" -> Icons.Default.AccountBalance
        path.startsWith("rank") || path.startsWith("kf_rank") -> Icons.Default.Leaderboard
        path.startsWith("kf_fw_") -> Icons.Default.StarRate
        path == "message.php" -> Icons.Default.MailOutline
        path == "profile.php" && action == "favor" -> Icons.Default.BookmarkBorder
        path == "profile.php" -> Icons.Default.PersonOutline
        path.startsWith("fyg_") -> Icons.Default.SportsEsports
        path == "faq.php" || path == "help.php" -> Icons.AutoMirrored.Filled.MenuBook
        path == "search.php" -> Icons.Default.Search
        path == "show.php" -> Icons.Default.AttachFile
        else -> Icons.Default.Widgets
    }
}

private fun parseColor(raw: String?): Color? {
    val value = raw?.trim()?.removePrefix("#") ?: return null
    val hex = when (value.length) {
        3 -> value.map { "$it$it" }.joinToString("")
        6 -> value
        8 -> value.take(6)
        else -> return null
    }
    return runCatching {
        Color(red = hex.substring(0, 2).toInt(16) / 255f, green = hex.substring(2, 4).toInt(16) / 255f, blue = hex.substring(4, 6).toInt(16) / 255f)
    }.getOrNull()
}

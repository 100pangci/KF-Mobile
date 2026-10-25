@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package work.miaola.kf.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import work.miaola.kf.*

/** Shared status strip shown above every destination. */
@Composable
fun KfStatusBanners(c: AppController) {
    if (c.busy || c.siteBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
    c.error?.let { message ->
        Surface(color = MaterialTheme.colorScheme.errorContainer) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f).padding(vertical = 10.dp), style = MaterialTheme.typography.bodySmall)
                IconButton(onClick = c::clearError) { Icon(Icons.Default.Close, "关闭错误提示") }
            }
        }
    }
    c.sessionNotice?.let { message ->
        Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
                IconButton(onClick = c::dismissSessionNotice) { Icon(Icons.Default.Close, "关闭状态提示") }
            }
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
        AlertDialog(onDismissRequest = c::dismissSiteAction, title = { Text(if (c.siteBusy) "正在执行" else form?.form?.title ?: link?.title?.ifBlank { "确认站点操作" } ?: "确认操作") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (link?.effect == SiteEffect.EXTERNAL) "将由外部应用打开链接，不附加论坛 Cookie。" else form?.form?.warning ?: link?.explanation?.ifBlank { "请确认此次操作；可能影响站点状态或余额。" }.orEmpty())
                    Text("服务器：${c.origin}", style = MaterialTheme.typography.bodySmall)
                    form?.let { Text("已填写 ${it.form.fields.count { f -> f.kind != SiteFieldKind.FILE }} 个字段，${it.files.size} 个附件。密码及正文不会在此重复展示。", style = MaterialTheme.typography.bodySmall) }
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

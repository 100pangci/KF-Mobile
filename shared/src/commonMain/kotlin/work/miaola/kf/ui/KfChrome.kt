@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package work.miaola.kf.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import work.miaola.kf.AppController
import work.miaola.kf.HomeTab
import work.miaola.kf.PageData

/**
 * One top-bar and bottom-bar implementation for both shells: the Android
 * Navigation Compose shell and the desktop render/test harness. Keeping the
 * chrome here prevents the two shells from drifting apart.
 */
@Composable
fun KfTopBar(
    title: String,
    root: Boolean,
    showBack: Boolean,
    editor: Boolean,
    kind: KfDestinationKind,
    c: AppController,
    page: PageData?,
    actions: KfActions,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    TopAppBar(
        title = {
            Column {
                Crossfade(targetState = title, animationSpec = tween(KfMotionSpec.Quick), label = "topBarTitle") { value ->
                    Text(value, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (root) Text("SCARLET MOON", style = MaterialTheme.typography.labelSmall,
                    letterSpacing = 2.sp, color = MaterialTheme.colorScheme.primary)
            }
        },
        navigationIcon = {
            if (editor) TextButton(onClick = onBack) { Text("取消") }
            else if (showBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            else Icon(Icons.Default.NightsStay, null, Modifier.padding(start = 20.dp, end = 8.dp),
                tint = MaterialTheme.colorScheme.primary)
        },
        actions = { KfPageActions(c, kind, page, actions, onRefresh) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
fun KfBottomBar(kind: KfDestinationKind, actions: KfActions) {
    val tabs = listOf(
        Triple(HomeTab.FEED, Icons.Default.Explore, KfDestinationKind.FEED),
        Triple(HomeTab.BOARDS, Icons.Default.Dashboard, KfDestinationKind.BOARDS),
        Triple(HomeTab.MESSAGES, Icons.Default.MailOutline, KfDestinationKind.MESSAGES),
        Triple(HomeTab.ACCOUNT, Icons.Default.PersonOutline, KfDestinationKind.ACCOUNT),
    )
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        tabs.forEach { (tab, icon, tabKind) ->
            NavigationBarItem(
                selected = kind == tabKind,
                onClick = { actions.selectTab(tab) },
                icon = { Icon(icon, tab.title) },
                label = { Text(tab.title) },
            )
        }
    }
}

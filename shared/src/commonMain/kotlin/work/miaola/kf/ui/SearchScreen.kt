@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package work.miaola.kf.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import work.miaola.kf.AppController

fun searchCacheKey(keyword: String, author: String) = "search:${keyword.trim().length}:${keyword.trim()}|${author.trim()}"

@Composable
fun SearchScreen(c: AppController, actions: KfActions, initialKeyword: String = "", initialAuthor: String = "", initialResultUrl: String = "") {
    var keyword by rememberSaveable { mutableStateOf(initialKeyword.ifBlank { if (initialAuthor.isBlank() && initialResultUrl.isBlank()) c.lastSearchKeyword else "" }) }
    var author by rememberSaveable { mutableStateOf(initialAuthor.ifBlank { if (initialKeyword.isBlank() && initialResultUrl.isBlank()) c.lastSearchAuthor else "" }) }
    var submittedKeyword by rememberSaveable { mutableStateOf(initialKeyword) }
    var submittedAuthor by rememberSaveable { mutableStateOf(initialAuthor) }
    var submitted by rememberSaveable { mutableStateOf(initialKeyword.isNotBlank() || initialAuthor.isNotBlank() || initialResultUrl.isNotBlank()) }
    var receipt by rememberSaveable { mutableStateOf(initialResultUrl) }
    var filters by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val key = receipt.ifBlank { searchCacheKey(submittedKeyword, submittedAuthor) }
    val result = if (submitted) c.pageFor(key) else null
    var attemptedKey by remember { mutableStateOf<String?>(null) }
    fun search() {
        if (keyword.isBlank() && author.isBlank()) return
        keyboard?.hide()
        focusManager.clearFocus()
        submittedKeyword = keyword.trim(); submittedAuthor = author.trim()
        receipt = ""
        submitted = true
        attemptedKey = null
    }
    LaunchedEffect(Unit) {
        if (!submitted) { focus.requestFocus(); keyboard?.show() }
    }
    LaunchedEffect(c.authenticated) { if (c.authenticated) attemptedKey = null }
    LaunchedEffect(key, submitted, c.busy, attemptedKey) {
        if (submitted && result == null && !c.busy && attemptedKey != key) {
            attemptedKey = key
            if (receipt.isNotBlank()) c.ensureSearchResult(receipt) else c.ensureSearch(key, submittedKeyword, submittedAuthor)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") }
            OutlinedTextField(keyword, { keyword = it }, Modifier.weight(1f).focusRequester(focus),
                placeholder = { Text("搜索绯月") }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
                trailingIcon = { IconButton(onClick = ::search, enabled = !c.busy) { Icon(Icons.Default.Search, "搜索") } })
            IconButton(onClick = { keyboard?.hide(); filters = true }) { Icon(Icons.Default.FilterList, "筛选") }
        }
        if (submittedAuthor.isNotBlank()) Text("作者：$submittedAuthor", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelMedium)
        when {
            result != null -> key(key) { ListingScreen(c, actions, result, false, if (receipt.isBlank()) submittedKeyword to submittedAuthor else null) }
            submitted -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (c.busy) CircularProgressIndicator() else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(c.error ?: "未能获取搜索结果")
                    TextButton(onClick = { if (receipt.isNotBlank()) c.ensureSearchResult(receipt) else c.ensureSearch(key, submittedKeyword, submittedAuthor) }) { Text("重试") }
                }
            }
            else -> Text("搜索帖子，也可以按作者筛选", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (filters) ModalBottomSheet(onDismissRequest = { filters = false }) {
        Column(Modifier.fillMaxWidth().padding(24.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("搜索筛选", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(author, { author = it }, Modifier.fillMaxWidth(), label = { Text("作者（可选）") }, singleLine = true)
            Button(onClick = { filters = false; search() }, Modifier.fillMaxWidth()) { Text("应用并搜索") }
        }
    }
}

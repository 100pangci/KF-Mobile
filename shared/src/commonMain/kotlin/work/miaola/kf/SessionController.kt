package work.miaola.kf

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

fun AppController.logout() {
        if (writeBusy) return
        cancelRequest(); service.close(); service.clearSession(); home = null; page = null; editor = null
        draftJob?.cancel(); recoveryJob?.cancel(); restoreJob?.cancel(); batchJob?.cancel()
        // Drafts and destination anchors are keyed by origin, not by account.
        // A different user on the same device must never inherit them.
        recovery.clearUserData()
        store.remove("daily_signed@$origin"); dailySigned = false
        avatarJob?.cancel(); accountAvatar = null; accountProfile = null
        profileAvatars.clear(); profileAvatarLoading.clear(); profileAvatarMisses.clear()
        draftContent = ""; draftTitle = ""; draftRecipient = ""
        draftCategory = ""; draftKeywords = ""; draftAttachmentNotice = null; draftRestored = false
        pendingMentionName = null; pendingMessageConfirm = false
        backStack.clear(); pageCache.clear(); showLogin = true; busy = false
        clearTimelines()
        loginReturnUrl = null; pendingPurchase = null; showEmoticonPicker = false
        clearFavorites()
        clearNativeTransientState()
        toast = "已退出本机登录"
    }

fun AppController.openNativeLoginOptions() {
        rememberNavigationSource()
        work(loadsPage = true) {
            val result = service.fetch("login.php")
            if (!it()) return@work
            page = result; rememberCachedPage(result, "login.php")
            showLogin = false; showSiteTools = true; pendingNativePage = "login.php"
        }
    }

fun AppController.refreshCatalog(url: String = configUrl) {
        configUrl = url.trim(); store.put("config_url", configUrl)
        catalogJob?.cancel()
        val version = ++catalogRefreshVersion
        catalogBusy = true
        catalogJob = scope.launch {
            try {
                val result = domains.refresh(configUrl)
                catalog = result
                if (url.isNotBlank()) toast = result.warning ?: "配置已更新（${result.source.label}）"
            } finally {
                if (version == catalogRefreshVersion) catalogBusy = false
            }
        }
    }

fun AppController.switchDomain(value: String) {
        if (writeBusy) return
        val normalized = try { normalizeOrigin(value) } catch (e: Exception) { error = e.message; return }
        if (origin == normalized) return
        cancelRequest(); service.close(); editor = null; draftContent = ""; draftTitle = ""; draftRecipient = ""
        draftJob?.cancel(); recoveryJob?.cancel(); restoreJob?.cancel(); batchJob?.cancel()
        draftCategory = ""; draftKeywords = ""; draftAttachmentNotice = null; draftRestored = false
        pendingMentionName = null; pendingMessageConfirm = false
        avatarJob?.cancel(); accountAvatar = null; accountProfile = null
        profileAvatars.clear(); profileAvatarLoading.clear(); profileAvatarMisses.clear()
        pendingPurchase = null; uncertainPurchases = emptySet()
        showEmoticonPicker = false; sessionNotice = null
        loginReturnUrl = null
        clearFavorites()
        clearNativeTransientState()
        origin = normalized; store.put("selected_origin", origin)
        recovery = RecoveryStore(store, origin)
        recoveryState = recovery.loadState() ?: RecoveryState(origin = origin)
        managementRecoveries = recoveryState.pendingManagement.toSet()
        dailySigned = store.get("daily_signed@$origin") == localDayKey()
        pageCache.clear()
        clearTimelines()
        service = factory.create(origin); page = null; home = null; backStack.clear(); busy = false
        bookmarks = readBookmarks(); history = readHistory(); tab = HomeTab.FEED
        showSettings = false; showLogin = false; load("index.php", false)
    }

internal fun AppController.loginDestination(url: String): String? = url.takeIf {
        it.substringBefore('?').substringAfterLast('/') in listOf("read.php", "thread.php", "message.php", "profile.php")
            && !it.contains("action=del") && !it.contains("action=write")
    }

fun AppController.resumeSession() {
        // Background session checks are silent: a flaky connection must not
        // look like a logout or throw a raw socket error at the reader.
        work {
            val result = try {
                service.fetch("index.php")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@work
            }
            if (!it()) return@work
            if (result.authStatus == AuthStatus.UNKNOWN) {
                return@work
            }
            if (!result.authenticated) loginReturnUrl = page?.url?.let(::loginDestination)
            home = result
            rememberCachedPage(result, "index.php")
            if (showLogin || page == null || page?.url?.substringBefore('?')?.endsWith("index.php") == true) page = result
            showLogin = !result.authenticated
            sessionNotice = if (result.authenticated) null else "登录已失效，请重新登录"
            if (result.authenticated && accountAvatar == null) refreshAccountAvatar()
            if (result.authenticated && editor == null && page?.posts?.isNotEmpty() == true) {
                val refreshed = service.fetch(page!!.url)
                if (!it()) return@work
                refreshPurchaseState(refreshed)
                page = refreshed
                rememberCachedPage(refreshed)
            }
        }
    }

fun AppController.login(username: String, password: String) {
        if (busy) return
        if (username.isBlank() || password.isEmpty()) { error = "请输入用户名和密码"; return }
        work(loadsPage = true) {
            val result = service.login(username.trim(), password)
            if (!it()) return@work
            val destination = loginReturnUrl
            loginReturnUrl = null
            pageCache.clear()
            clearTimelines()
            home = result
            rememberCachedPage(result, "index.php")
            showLogin = false; sessionNotice = null; toast = "登录成功"
            accountAvatar = null; accountProfile = null; refreshAccountAvatar()
            if (destination == null) { page = result; backStack.clear(); tab = HomeTab.FEED }
            else {
                if (backStack.isEmpty()) backStack.add(result to HomeTab.FEED)
                val restored = service.fetch(destination)
                if (!it()) return@work
                refreshPurchaseState(restored); page = restored; rememberCachedPage(restored, destination)
            }
        }
    }

fun AppController.refreshAccountAvatar(force: Boolean = false) {
        if (!authenticated) return
        if (!force && accountProfile != null) return
        if (avatarJob?.isActive == true) return
        val profileUrl = home?.menu?.firstOrNull { it.url.contains("profile.php?action=show") }?.url ?: return
        val expectedOrigin = origin
        avatarJob = scope.launch {
            val profile = try {
                service.fetch(profileUrl).nativePage?.profile
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (origin == expectedOrigin && authenticated && profile != null) {
                accountProfile = profile
                if (!profile.avatar.isNullOrBlank()) accountAvatar = profile.avatar
            }
        }
    }

fun AppController.dismissLogin() {
        showLogin = false
        error = null
    }

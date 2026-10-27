package work.miaola.kf.ui

import work.miaola.kf.HomeTab

/**
 * App-level destinations. The Android shell maps each value to a Navigation
 * Compose route; the desktop renderer uses the same values to pick a screen.
 */
enum class KfDestinationKind {
    FEED,
    BOARDS,
    LISTING,
    READER,
    SEARCH,
    MESSAGES,
    MESSAGE,
    ACCOUNT,
    PROFILE,
    SETTINGS,
    LOGIN,
    EDITOR,
    NATIVE,
    GUGU,
}

val KfDestinationKind.isTopLevel: Boolean
    get() = this == KfDestinationKind.FEED ||
        this == KfDestinationKind.BOARDS ||
        this == KfDestinationKind.MESSAGES ||
        this == KfDestinationKind.ACCOUNT

data class KfDestination(
    val kind: KfDestinationKind,
    val url: String? = null,
    val tab: HomeTab = HomeTab.FEED,
) {
    val isTopLevel: Boolean get() = kind.isTopLevel
    val showsBottomBar: Boolean get() = isTopLevel
}

/** Pure URL classification shared by the Android route table and tests. */
object KfDestinations {
    fun isSearchNavigation(url: String): Boolean {
        if (url.substringBefore('?').substringBefore('#').substringAfterLast('/') != "search.php") return false
        val params = url.substringAfter('?', "").substringBefore('#').split('&').filter { it.isNotBlank() }
        val keys = params.map { it.substringBefore('=') }
        if (keys.any { it !in setOf("keyword", "pwuser", "searchid", "page", "step") }) return false
        if ("step" !in keys) return true
        // PHPWind can include step=2 on an already-issued search receipt. Do
        // not treat step=2 + fresh keywords as read-only result navigation.
        return params.filter { it.substringBefore('=') == "step" }.all { it == "step=2" } &&
            params.any { it.startsWith("searchid=") && it.substringAfter('=').isNotBlank() } &&
            keys.none { it in setOf("keyword", "pwuser") }
    }
    /** Inbox, outbox and other message views are read-only navigation. */
    fun isReadOnlyMessage(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        if (path != "message.php") return false
        val query = url.substringAfter('?', "").substringBefore('#')
        val keys = query.split('&').filter { it.isNotBlank() }.map { it.substringBefore('=') }
        if (keys.distinct().size != keys.size || keys.any { it !in setOf("action", "mid", "page", "touid", "remid", "towhere") }) return false
        val action = query.split('&').firstOrNull { it.startsWith("action=") }?.substringAfter('=').orEmpty()
        return action in listOf("", "receivebox", "sendbox", "scout", "banned", "read", "write")
    }

    fun fromUrl(url: String): KfDestinationKind {
        val path = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val action = url.substringAfter('?', "").substringBefore('#').split('&')
            .firstOrNull { it.substringBefore('=') == "action" }?.substringAfter('=', "").orEmpty()
        return when {
            path == "index.php" || path.isBlank() -> KfDestinationKind.FEED
            path == "thread.php" -> KfDestinationKind.LISTING
            path == "read.php" -> KfDestinationKind.READER
            path == "post.php" -> KfDestinationKind.EDITOR
            path == "search.php" -> KfDestinationKind.SEARCH
            path == "message.php" && action == "write" -> KfDestinationKind.EDITOR
            path == "message.php" && action in listOf("read", "banned", "scout") -> KfDestinationKind.MESSAGE
            path == "message.php" -> KfDestinationKind.MESSAGES
            path == "profile.php" && action == "favor" -> KfDestinationKind.LISTING
            path == "profile.php" -> KfDestinationKind.PROFILE
            path == "fyg_sjcdwj.php" -> KfDestinationKind.GUGU
            path == "login.php" -> KfDestinationKind.LOGIN
            else -> KfDestinationKind.NATIVE
        }
    }
}

/** Keep route encoding testable off-device; forum search encoding remains GBK. */
object KfSearchRoute {
    const val pattern = "search?q={q}&author={author}&url={url}"
    fun create(keyword: String = "", author: String = "", resultUrl: String = "") =
        "search?q=${encode(keyword)}&author=${encode(author)}&url=${encode(resultUrl)}"

    private fun encode(value: String): String = buildString {
        value.encodeToByteArray().forEach { byte ->
            val c = byte.toInt() and 255
            if (c in 65..90 || c in 97..122 || c in 48..57 || c in listOf(45, 46, 95, 126)) append(c.toChar())
            else { append('%'); append("0123456789ABCDEF"[c shr 4]); append("0123456789ABCDEF"[c and 15]) }
        }
    }
}

/**
 * Navigation callbacks used by the shared screens. Keeping them in one object
 * means a destination screen never has to know whether it is hosted by the
 * Android NavHost or by the desktop renderer.
 */
class KfActions(
    val open: (String) -> Unit = {},
    val openSettings: () -> Unit = {},
    val openLogin: () -> Unit = {},
    val openSearch: () -> Unit = {},
    val openNative: (String) -> Unit = {},
    val selectTab: (HomeTab) -> Unit = {},
    val back: () -> Unit = {},
)

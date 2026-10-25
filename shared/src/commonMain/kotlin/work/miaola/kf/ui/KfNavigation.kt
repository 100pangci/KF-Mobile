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
    /** Inbox, outbox and other message views are read-only navigation. */
    fun isReadOnlyMessage(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        if (path != "message.php") return false
        val query = url.substringAfter('?', "").substringBefore('#')
        val keys = query.split('&').filter { it.isNotBlank() }.map { it.substringBefore('=') }
        if (keys.any { it in setOf("step", "delid[]") }) return false
        val action = query.split('&').firstOrNull { it.startsWith("action=") }?.substringAfter('=').orEmpty()
        return action in listOf("", "receivebox", "sendbox", "scout", "banned", "read", "write")
    }

    fun fromUrl(url: String): KfDestinationKind {
        val path = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val action = url.substringAfter('?', "").substringBefore('#')
        return when {
            path == "index.php" || path.isBlank() -> KfDestinationKind.FEED
            path == "thread.php" -> KfDestinationKind.LISTING
            path == "read.php" -> KfDestinationKind.READER
            path == "post.php" -> KfDestinationKind.EDITOR
            path == "search.php" -> KfDestinationKind.SEARCH
            path == "message.php" && action.contains("action=write") -> KfDestinationKind.EDITOR
            path == "message.php" && listOf("action=read", "action=banned", "action=scout").any(action::contains) -> KfDestinationKind.MESSAGE
            path == "message.php" -> KfDestinationKind.MESSAGES
            path == "profile.php" -> KfDestinationKind.PROFILE
            path == "login.php" -> KfDestinationKind.LOGIN
            else -> KfDestinationKind.NATIVE
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

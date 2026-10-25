package work.miaola.kf.nav

import android.net.Uri
import androidx.navigation.NavBackStackEntry
import work.miaola.kf.ui.KfDestinationKind

object KfRoutes {
    const val FEED = "feed"
    const val BOARDS = "boards"
    const val ACCOUNT = "account"
    const val SETTINGS = "settings"
    const val LOGIN = "login"
    const val LISTING = "listing?url={url}"
    const val READER = "reader?url={url}"
    const val MESSAGES = "messages?url={url}"
    const val MESSAGE = "message?url={url}"
    const val PROFILE = "profile?url={url}"
    const val NATIVE = "native?url={url}"
    const val EDITOR = "editor?url={url}"
    const val SEARCH = work.miaola.kf.ui.KfSearchRoute.pattern

    fun listing(url: String) = "listing?url=${encode(url)}"
    fun reader(url: String) = "reader?url=${encode(url)}"
    fun messages(url: String) = "messages?url=${encode(url)}"
    fun message(url: String) = "message?url=${encode(url)}"
    fun profile(url: String) = "profile?url=${encode(url)}"
    fun native(url: String) = "native?url=${encode(url)}"
    fun editor(url: String) = "editor?url=${encode(url)}"
    fun search(keyword: String, author: String) = work.miaola.kf.ui.KfSearchRoute.create(keyword, author)
    fun searchResult(url: String) = work.miaola.kf.ui.KfSearchRoute.create(resultUrl = url)

    fun searchKey(keyword: String, author: String) = work.miaola.kf.ui.searchCacheKey(keyword, author)

    private fun encode(value: String) = Uri.encode(value)
}

data class KfLocation(
    val kind: KfDestinationKind,
    val url: String? = null,
    val query: String = "",
    val author: String = "",
)

fun NavBackStackEntry.kfLocation(): KfLocation {
    val route = destination.route.orEmpty()
    fun arg(name: String) = arguments?.getString(name)
    return when (route) {
        KfRoutes.FEED -> KfLocation(KfDestinationKind.FEED)
        KfRoutes.BOARDS -> KfLocation(KfDestinationKind.BOARDS)
        KfRoutes.ACCOUNT -> KfLocation(KfDestinationKind.ACCOUNT)
        KfRoutes.SETTINGS -> KfLocation(KfDestinationKind.SETTINGS)
        KfRoutes.LOGIN -> KfLocation(KfDestinationKind.LOGIN)
        KfRoutes.LISTING -> KfLocation(KfDestinationKind.LISTING, arg("url"))
        KfRoutes.READER -> KfLocation(KfDestinationKind.READER, arg("url"))
        KfRoutes.MESSAGES -> KfLocation(KfDestinationKind.MESSAGES, arg("url"))
        KfRoutes.MESSAGE -> KfLocation(KfDestinationKind.MESSAGE, arg("url"))
        KfRoutes.PROFILE -> KfLocation(KfDestinationKind.PROFILE, arg("url"))
        KfRoutes.NATIVE -> KfLocation(KfDestinationKind.NATIVE, arg("url"))
        KfRoutes.EDITOR -> KfLocation(KfDestinationKind.EDITOR, arg("url"))
        KfRoutes.SEARCH -> KfLocation(KfDestinationKind.SEARCH, arg("url")?.takeIf { it.isNotBlank() }, arg("q").orEmpty(), arg("author").orEmpty())
        else -> KfLocation(KfDestinationKind.FEED)
    }
}

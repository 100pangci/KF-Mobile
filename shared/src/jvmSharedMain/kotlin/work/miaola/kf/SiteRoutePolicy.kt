package work.miaola.kf

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class SiteRoutePolicy(private val origin: String) {
    private val base = origin.toHttpUrlOrNull()!!
    fun classify(raw: String, title: String = ""): SiteDestination {
        val url = base.resolve(raw)
        if (url != null && (url.username.isNotEmpty() || url.password.isNotEmpty())) return SiteDestination(raw, SiteEffect.UNSUPPORTED, title)
        if (url == null || url.scheme != "https" || url.host != base.host || url.port != base.port) {
            val scheme = url?.scheme ?: raw.substringBefore(':', "").lowercase()
            return SiteDestination(url?.toString() ?: raw, if (scheme in listOf("https", "http", "magnet", "mailto")) SiteEffect.EXTERNAL else SiteEffect.UNSUPPORTED, title)
        }
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return SiteDestination(raw, SiteEffect.UNSUPPORTED, title)
        val path = url.pathSegments.lastOrNull().orEmpty()
        val action = url.queryParameter("action").orEmpty()
        val keys = url.queryParameterNames
        fun only(vararg names: String) = keys.all { it in names } && keys.all { url.queryParameterValues(it).size == 1 }
        val rootEndpoint = url.encodedPath == "/$path"
        val effect = when {
            path.substringAfterLast('.', "").lowercase() in listOf("gif", "png", "jpg", "jpeg", "webp", "avif", "bmp", "svg") -> SiteEffect.READ
            path == "job.php" && action == "buytopic" -> SiteEffect.UNSUPPORTED
            path == "job.php" && action in listOf("download", "downloadatt") -> SiteEffect.DOWNLOAD
            !rootEndpoint && path.isNotEmpty() -> SiteEffect.CHANGE
            path == "index.php" && only() -> SiteEffect.READ
            path == "thread.php" && only("fid", "page", "type", "search", "orderway", "asc", "digest") -> SiteEffect.READ
            path == "read.php" && only("tid", "page", "fid", "fpage", "toread", "sf", "uid", "ordertype", "spid") -> SiteEffect.READ
            path == "faq.php" && action in listOf("", "faq") && only("id", "hid", "action") -> SiteEffect.READ
            path == "show.php" && only("uid", "page", "fid", "tid") -> SiteEffect.READ
            path == "kf_no1.php" && only("no1", "page") -> SiteEffect.READ
            path == "kf_share.php" && only("uid", "page", "fid", "tid") -> SiteEffect.READ
            path == "guanjianci.php" && only("gjc", "uid", "page") -> SiteEffect.READ
            path == "profile.php" && action in listOf("", "show", "modify", "favor") && only("action", "uid", "type", "page", "sf") -> SiteEffect.READ
            path == "message.php" && action in listOf("", "receivebox", "sendbox", "scout", "banned", "read", "write") && only("action", "mid", "page", "touid", "remid", "towhere") -> SiteEffect.READ
            path == "post.php" && action in listOf("", "new", "reply", "quote", "modify") && only("action", "fid", "tid", "pid", "article") -> SiteEffect.READ
            path == "search.php" && url.query.isNullOrBlank() -> SiteEffect.READ
            path == "register.php" && url.query.isNullOrBlank() -> SiteEffect.READ
            path == "login.php" && only() -> SiteEffect.READ
            path == "hack.php" && url.queryParameter("H_name") == "bank" && only("H_name") -> SiteEffect.READ
            path in listOf("kf_growup.php", "kf_lv200.php", "kf_fw_1wkfb.php", "kf_fw_ig_index.php") && only("ping", "page", "uid") -> SiteEffect.READ
            path == "fyg_sjcdwj.php" && only() -> SiteEffect.READ
            path.isEmpty() && only() -> SiteEffect.READ
            else -> SiteEffect.CHANGE
        }
        return SiteDestination(url.toString(), effect, title, if (effect == SiteEffect.CHANGE) "会修改站点数据或消耗余额，需确认后执行。" else "")
    }
}

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
            path == "kf_share.php" && only("uid", "page", "fid", "tid", "new", "ti") -> SiteEffect.READ
            path == "guanjianci.php" && only("gjc", "uid", "page") -> SiteEffect.READ
            path == "profile.php" && action in listOf("", "show", "modify", "favor") && only("action", "uid", "type", "page", "sf") -> SiteEffect.READ
            // Tracking details use the same read-only message template as the
            // inbox detail. Only a numeric mid is accepted; the list page is
            // the sole source of these links and never attaches write tokens.
            path == "message.php" && action == "readscout" &&
                url.queryParameter("mid")?.matches(Regex("[0-9]{1,20}")) == true &&
                only("action", "mid") -> SiteEffect.READ
            path == "message.php" && action in listOf("", "receivebox", "sendbox", "scout", "banned", "read", "write") && only("action", "mid", "page", "touid", "remid", "towhere") -> SiteEffect.READ
            path == "post.php" && action in listOf("", "new", "reply", "quote", "modify") && only("action", "fid", "tid", "pid", "article") -> SiteEffect.READ
            path == "search.php" && url.query.isNullOrBlank() -> SiteEffect.READ
            // Profile pages link to a user's topics/replies with a direct GET
            // result page. Only the numeric author id and the site's observed
            // reply area value are accepted; keyword search still uses POST.
            path == "search.php" && only("authorid", "sch_area", "page") &&
                url.queryParameter("authorid")?.matches(Regex("[0-9]{1,20}")) == true &&
                url.queryParameter("sch_area") in listOf(null, "", "0", "2") &&
                url.queryParameter("page").let { it == null || it.matches(Regex("[0-9]{1,5}")) } -> SiteEffect.READ
            path == "register.php" && url.query.isNullOrBlank() -> SiteEffect.READ
            path == "login.php" && only() -> SiteEffect.READ
            path == "hack.php" && url.queryParameter("H_name") == "bank" && only("H_name") -> SiteEffect.READ
            path == "hack.php" && url.queryParameter("H_name") == "bank" &&
                url.queryParameter("action") == "log" && url.queryParameter("to") in listOf(null, "", "0", "1") &&
                only("H_name", "action", "to", "page") -> SiteEffect.READ
            path in listOf("kf_growup.php", "kf_lv200.php", "kf_fw_1wkfb.php", "kf_fw_ig_index.php") && only("ping", "page", "uid") -> SiteEffect.READ
            // Reward sub-pages are plain GET links from the READ-only reward
            // dashboard. My items, halo, ranking and shop carry no action
            // parameters; the help page is readable with its issued safeid.
            path in listOf("kf_fw_ig_mybp.php", "kf_fw_ig_halo.php", "kf_fw_ig_pklist.php", "kf_fw_ig_shop.php") && only() -> SiteEffect.READ
            path == "kf_fw_ig_readme.php" &&
                url.queryParameter("safeid")?.matches(Regex("[0-9a-zA-Z]{1,32}")) == true && only("safeid") -> SiteEffect.READ
            path == "kf_fw_1wkfb.php" && url.queryParameter("do") == "2" &&
                only("do", "safeid", "id", "ping", "page", "uid") -> SiteEffect.READ
            path == "fyg_sjcdwj.php" && (only() || (url.queryParameter("go") == "play" && url.queryParameter("xl") in listOf("1", "2") && only("go", "xl"))) -> SiteEffect.READ
            path.isEmpty() && only() -> SiteEffect.READ
            else -> SiteEffect.CHANGE
        }
        return SiteDestination(url.toString(), effect, title, if (effect == SiteEffect.CHANGE) "会修改站点数据或消耗余额，需确认后执行。" else "")
    }
}

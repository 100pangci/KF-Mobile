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
        val effect = when {
            path.substringAfterLast('.', "").lowercase() in listOf("gif", "png", "jpg", "jpeg", "webp", "avif", "bmp", "svg") -> SiteEffect.READ
            path == "job.php" && action == "buytopic" -> SiteEffect.UNSUPPORTED
            path == "job.php" && action in listOf("download", "downloadatt") -> SiteEffect.DOWNLOAD
            path in listOf("index.php", "thread.php", "read.php", "faq.php", "show.php", "kf_no1.php", "kf_share.php", "guanjianci.php") -> SiteEffect.READ
            path == "profile.php" && action in listOf("", "show", "modify", "favor") && keys.none { it in setOf("job", "step", "delid[]") } -> SiteEffect.READ
            path == "message.php" && action in listOf("", "receivebox", "sendbox", "scout", "banned", "read", "write") && keys.none { it in setOf("step", "delid[]") } -> SiteEffect.READ
            path == "post.php" && action in listOf("", "new", "reply", "quote", "modify") && url.queryParameter("step") != "2" -> SiteEffect.READ
            path == "search.php" && url.query.isNullOrBlank() -> SiteEffect.READ
            path == "register.php" && url.query.isNullOrBlank() -> SiteEffect.READ
            path == "login.php" && action.isEmpty() -> SiteEffect.READ
            path == "hack.php" && url.queryParameter("H_name") == "bank" && action.isEmpty() -> SiteEffect.READ
            path in listOf("kf_growup.php", "kf_lv200.php", "kf_fw_1wkfb.php", "kf_fw_ig_index.php") && keys.all { it in setOf("ping", "page", "uid") } -> SiteEffect.READ
            path == "fyg_sjcdwj.php" && keys.all { it in setOf("go", "xl") } -> SiteEffect.READ
            path.isEmpty() -> SiteEffect.READ
            else -> SiteEffect.CHANGE
        }
        return SiteDestination(url.toString(), effect, title, if (effect == SiteEffect.CHANGE) "会修改站点数据或消耗余额，需确认后执行。" else "")
    }
}

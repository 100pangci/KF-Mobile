package work.miaola.kf

/** Deny by default. Labels are presentation, never authority for skipping a gate. */
object SiteActionRisk {
    private fun path(url: String) = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
    private fun params(url: String) = url.substringAfter('?', "").substringBefore('#').split('&')
        .filter { it.isNotBlank() }.associate { it.substringBefore('=').lowercase() to it.substringAfter('=', "") }

    fun needsConfirmation(destination: SiteDestination): Boolean {
        if (destination.effect == SiteEffect.READ) return false
        if (destination.effect != SiteEffect.CHANGE) return true
        val keys = destination.url.substringAfter('?', "").substringBefore('#').split('&').filter { it.isNotBlank() }.map { it.substringBefore('=').lowercase() }
        if (keys.distinct().size != keys.size) return true
        val query = params(destination.url)
        return when (path(destination.url)) {
            "kf_tidfavor.php" -> query["action"] !in setOf("favor", "unfavor") ||
                query["tid"]?.matches(Regex("[0-9]+")) != true ||
                query.keys.any { it !in setOf("action", "tid", "sf", "safeid", "verify") }
            "kf_growup.php" -> !(query["ok"] == "2" && query["color"]?.toIntOrNull() != null &&
                query.keys.all { it in setOf("ok", "color", "safeid", "verify") })
            else -> true
        }
    }

    fun needsConfirmation(submission: SiteSubmission): Boolean {
        val form = submission.form
        val values = submission.values
        if (values.any { it.name.lowercase() in setOf("delid[]", "delids", "to_money", "drawmoney", "propwd", "oldpwd") && it.value.isNotBlank() }) return true
        val profileSave = path(form.sourceUrl) == "profile.php" && params(form.sourceUrl)["action"] == "modify" &&
            path(form.actionUrl) == "profile.php" && params(form.actionUrl).keys.all { it == "action" } &&
            params(form.actionUrl)["action"] in setOf(null, "modify") && submission.files.isEmpty() &&
            form.hidden.all { it.name in setOf("prooicq", "promsn", "prohomepage", "propublicemail", "facetype", "proreceivemail", "tpskin", "editor", "timedf", "d_type", "time_f", "step", "verify", "safeid") } &&
            form.fields.all { it.name in setOf("oldpwd", "propwd", "check_pwd", "proemail", "prohonor", "t_num", "p_num", "prosign", "progender", "probday", "profrom") }
        if (profileSave && values.none { it.name in setOf("oldpwd", "propwd", "check_pwd") && it.value.isNotBlank() }) return false
        if (form.fields.any { it.kind == SiteFieldKind.PASSWORD }) return true
        return !(form.id == "rpc:recommend" && path(form.sourceUrl) == "read.php" &&
            path(form.actionUrl) == "diy_read_tui.php" && form.fields.isEmpty() && submission.files.isEmpty())
    }

    fun successMessage(destination: SiteDestination?): String = when {
        destination == null -> "已推荐"
        path(destination.url) == "kf_tidfavor.php" -> if (params(destination.url)["action"] == "favor") "已收藏" else "已取消收藏"
        else -> "已更新"
    }
}

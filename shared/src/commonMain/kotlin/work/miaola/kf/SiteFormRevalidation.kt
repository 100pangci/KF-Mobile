package work.miaola.kf

/** Rebind user intent, never positional control IDs or hidden tokens, to a fresh page. */
fun revalidatedSiteValues(submission: SiteSubmission, fresh: SiteForm, clicked: SiteSubmit): List<FormValue> {
    val old = submission.form
    require(fresh.sourceUrl == old.sourceUrl && clicked in fresh.submits) { "表单来源或提交选项已变化，未提交" }
    require(fresh.method == old.method && fresh.actionUrl == old.actionUrl && fresh.multipart == old.multipart) { "表单提交协议已变化，请刷新后重试" }
    require(fresh.fields.map { it.name to it.kind }.sortedBy { it.toString() } == old.fields.map { it.name to it.kind }.sortedBy { it.toString() }) { "表单字段已变化，请重新填写" }
    val volatile = setOf("verify", "safeid")
    require(fresh.hidden.filterNot { it.name in volatile } == old.hidden.filterNot { it.name in volatile }) { "表单操作已变化，请刷新后重试" }
    val userValues = submission.values.drop(old.hidden.size).let {
        if (!submission.submit.name.isNullOrBlank()) it.dropLast(1) else it
    }
    val selected = userValues.groupBy({ it.name }, { it.value })
    // Silently filtering a missing item would submit only part of a destructive
    // selection. Fail the whole source-page request before making a write.
    fresh.fields.groupBy { it.name }.forEach { (name, fields) ->
        if (fields.any { it.kind in setOf(SiteFieldKind.CHECKBOX, SiteFieldKind.RADIO, SiteFieldKind.SELECT) }) {
            val allowed = fields.flatMap { it.options }.map { it.value }.toSet()
            require(selected[name].orEmpty().all { it in allowed }) { "所选项目或选项已变化，未提交，请刷新后重新选择" }
        }
        require(fields.size == 1 || fields.all { it.kind == SiteFieldKind.CHECKBOX }) { "重复字段无法安全复核，请刷新后重试" }
        if (fields.all { it.kind == SiteFieldKind.CHECKBOX }) {
            val options = fields.flatMap { it.options }.map { it.value }
            require(options.distinct().size == options.size) { "重复选项无法安全复核，未提交" }
        }
        fields.forEach { field ->
            val values = selected[name].orEmpty()
            require(field.kind == SiteFieldKind.CHECKBOX || field.multiple || values.size <= 1) { "字段选项数量已变化，未提交" }
            require(field.editable || values == field.initial) { "字段已改为只读，未提交" }
        }
    }
    val state = fresh.fields.associate { field ->
        field.id to selected[field.name].orEmpty().let { values ->
            if (field.kind == SiteFieldKind.CHECKBOX) values.filter { value -> field.options.any { it.value == value } } else values
        }
    }
    return buildSiteFormValues(fresh, state, clicked)
}

package work.miaola.kf

import androidx.compose.runtime.mutableStateMapOf

/** Cross-page intent, scoped by source URL; never store or merge form field IDs/tokens. */
class SourcePageSelection {
    private val selected = mutableStateMapOf<String, Set<String>>()
    private fun key(form: SiteForm) = "${form.sourceUrl}|${form.id}"
    fun fieldIds(form: SiteForm): Set<String> = form.fields.filter { field ->
        field.name == "delid[]" && field.options.any { it.value in selected[key(form)].orEmpty() }
    }.map { it.id }.toSet()
    fun choose(form: SiteForm, ids: Set<String>) {
        selected[key(form)] = form.fields.filter { it.name == "delid[]" && it.id in ids }.flatMap { it.options }.map { it.value }.toSet()
    }
    fun clear(form: SiteForm) { selected.remove(key(form)) }
    fun clear() { selected.clear() }
    val count: Int get() = selected.values.sumOf { it.size }
}

fun PageTimeline.managementForms(): List<SiteForm> = nativePages.flatMap { it.forms }
    .filter { form -> form.fields.any { it.name == "delid[]" } }.distinctBy { it.sourceUrl to it.id }

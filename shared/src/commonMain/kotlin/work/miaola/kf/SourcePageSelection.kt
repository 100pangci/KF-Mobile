package work.miaola.kf

import androidx.compose.runtime.mutableStateMapOf

/** Cross-page intent, scoped by source URL; never store or merge form field IDs/tokens. */
class SourcePageSelection {
    private val selected = mutableStateMapOf<String, Set<String>>()
    private fun key(form: SiteForm) = "${form.sourceUrl}|${form.id}"
    fun fieldIds(form: SiteForm): Set<String> = form.fields.filter { field ->
        field.name == "delid[]" && field.options.any { it.value in selected[key(form)].orEmpty() }
    }.map { it.id }.toSet()
    fun values(form: SiteForm): Set<String> = selected[key(form)].orEmpty()
    fun pages(): Map<String, Set<String>> = selected.toMap()
    val pageCount: Int get() = selected.count { it.value.isNotEmpty() }
    fun choose(form: SiteForm, ids: Set<String>) {
        selected[key(form)] = form.fields.filter { it.name == "delid[]" && it.id in ids }.flatMap { it.options }.map { it.value }.toSet()
    }
    fun clear(form: SiteForm) { selected.remove(key(form)) }
    fun clear() { selected.clear() }
    val count: Int get() = selected.values.sumOf { it.size }
}

fun PageTimeline.managementForms(): List<SiteForm> = nativePages.flatMap { it.forms }
    .filter { form -> form.fields.any { it.name == "delid[]" } }.distinctBy { it.sourceUrl to it.id }

/** Rebind only stable option values; never reuse a positional field ID from another page. */
fun buildBatchPageRequest(
    form: SiteForm,
    selectedValues: Set<String>,
    overrides: Map<String, List<String>> = emptyMap(),
    verifyAbsent: Boolean = true,
): BatchPageRequest? {
    val selectionFields = form.fields.filter { it.name == "delid[]" }
    if (selectedValues.isEmpty() || selectionFields.isEmpty()) return null
    val state = form.fields.associate { field ->
        field.id to (overrides[field.id] ?: if (field.name == "delid[]") {
            field.options.map { it.value }.filter { it in selectedValues }
        } else field.initial)
    }
    val values = runCatching { buildSiteFormValues(form, state, form.submits.first()) }.getOrNull() ?: return null
    val items = selectionFields.flatMap { field -> field.options.filter { it.value in selectedValues }.map { option -> field to option } }
        .map { (field, option) -> BatchTransactionItem(option.value, form.sourceUrl, field.label.take(120)) }
    if (items.isEmpty()) return null
    return BatchPageRequest(form, values, form.submits.first(), items, verifyAbsent)
}

package work.miaola.kf

/** Task semantics over structured parser output, never a second HTML parser. */
data class NativeRecord(val title: String, val primary: NativeCell, val details: List<NativeInfo>, val actions: List<SiteDestination>)

val NativeSitePage.hasMessageList: Boolean get() = messageList || messages.isNotEmpty() || tables.any { table ->
    table.rows.firstOrNull()?.map { it.text }?.containsAll(listOf("标题", "发件人", "收件人")) == true
}

fun NativeRecord.identity() = listOf(title, primary.text, primary.link.orEmpty()) + details.flatMap { listOf(it.label, it.value) } + actions.map { it.url }

fun NativeTable.records(ranking: Boolean = false): List<NativeRecord> {
    if (style != NativeTableStyle.GRID || !header) return emptyList()
    val labels = rows.firstOrNull().orEmpty().map { it.text }
    // Keep the entire table when even one row is not understood. In particular,
    // ranking footers/colspans must not vanish beside successfully parsed rows.
    if (rows.drop(1).any { it.size < 2 || (ranking && it.any { cell -> cell.colspan > 1 }) }) return emptyList()
    return rows.drop(1).mapNotNull { row ->
        if (row.size < 2 || row.any { it.colspan > 1 } && ranking) return@mapNotNull null
        val index = if (ranking) 1 else row.indexOfFirst { it.link?.contains("read.php?") == true }.takeIf { it >= 0 } ?: 1
        val primary = row.getOrNull(index) ?: return@mapNotNull null
        NativeRecord(if (ranking) "#${row.first().text}" else row.first().text, primary,
            row.mapIndexedNotNull { i, cell ->
                if (i == index || (ranking && i == 0) || cell.text.isBlank()) null else NativeInfo(labels.getOrNull(i).orEmpty(), cell.text)
            }, (row.flatMap { it.actions } + links).distinctBy { it.url })
    }
}

fun profileFieldSection(field: SiteField): String = when {
    field.kind == SiteFieldKind.PASSWORD || field.name in setOf("proemail", "propublicemail") -> "安全"
    field.name in setOf("prohonor", "prosign", "progender", "probday", "profrom") -> "基本资料"
    field.name in setOf("tpskin", "font", "color") -> "显示"
    field.name in setOf("t_num", "p_num", "editor", "timedf", "d_type", "time_f", "proreceivemail") -> "论坛偏好"
    else -> "更多"
}

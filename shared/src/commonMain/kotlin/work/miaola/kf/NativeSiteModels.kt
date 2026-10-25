package work.miaola.kf

enum class SiteEffect { READ, CHANGE, DOWNLOAD, EXTERNAL, UNSUPPORTED }
data class SiteDestination(val url: String, val effect: SiteEffect, val title: String = "", val explanation: String = "") {
    override fun toString() = "SiteDestination(effect=$effect, title=$title)"
}
enum class SiteFieldKind { TEXT, PASSWORD, MULTILINE, NUMBER, SELECT, CHECKBOX, RADIO, FILE }
data class FormValue(val name: String, val value: String) {
    override fun toString() = "FormValue(name=$name)"
}
data class SiteField(
    val id: String,
    val name: String,
    val label: String,
    val kind: SiteFieldKind,
    val initial: List<String> = emptyList(),
    val options: List<FormOption> = emptyList(),
    val required: Boolean = false,
    val maxLength: Int? = null,
    val multiple: Boolean = false,
    val accept: String = "",
    val editable: Boolean = true,
) {
    override fun toString() = "SiteField(id=$id, kind=$kind)"
}
data class SiteSubmit(val label: String, val name: String?, val value: String)
data class SiteForm(
    val id: String,
    val title: String,
    val sourceUrl: String,
    val actionUrl: String,
    val method: String,
    val hidden: List<FormValue>,
    val fields: List<SiteField>,
    val submits: List<SiteSubmit>,
    val multipart: Boolean = false,
    val warning: String = "提交会改变站点数据，请核对后确认。",
) {
    override fun toString() = "SiteForm(id=$id, title=$title, method=$method)"
}
data class NativeInfo(val label: String, val value: String)
data class NativeProfile(
    val name: String,
    val avatar: String,
    val info: List<NativeInfo>,
    val links: List<SiteDestination> = emptyList(),
    /** The profile line shown beside the avatar, taken from the header cell. */
    val headline: NativeInfo? = null,
    /** The numeric summary row, taken from fixed positions in the header cell. */
    val stats: List<NativeInfo> = emptyList(),
)
data class NativeSection(val title: String, val html: String)
enum class NativeTableStyle { GRID, DEFINITION, NAV, PALETTE }
enum class NativeAlign { START, CENTER, END }
data class NativeCell(
    val text: String,
    val link: String? = null,
    val color: String? = null,
    val background: String? = null,
    val bold: Boolean = false,
    val colspan: Int = 1,
    val align: NativeAlign = NativeAlign.START,
)
data class NativeTable(
    val rows: List<List<NativeCell>>,
    val header: Boolean = false,
    val caption: String = "",
    val style: NativeTableStyle = NativeTableStyle.GRID,
    val links: List<SiteDestination> = emptyList(),
)
sealed interface NativeBlock {
    data class Section(val title: String, val html: String, val compact: Boolean = false, val text: String = "") : NativeBlock
    data class Table(val table: NativeTable) : NativeBlock
    data class Links(val links: List<SiteDestination>) : NativeBlock
}
data class NativeColorChoice(val color: String, val url: String, val label: String = "")

data class NativeGrowth(
    val level: String = "",
    val coefficient: String = "",
    val experience: String = "",
    val progress: Int? = null,
    val colors: List<NativeColorChoice> = emptyList(),
    val claimed: Boolean = false,
    val claimUrl: String? = null,
    val claimForm: Boolean = false,
)

data class NativeSitePage(
    val title: String,
    val profile: NativeProfile? = null,
    val sections: List<NativeSection> = emptyList(),
    val links: List<SiteDestination> = emptyList(),
    val forms: List<SiteForm> = emptyList(),
    val unsupported: List<String> = emptyList(),
    val tables: List<NativeTable> = emptyList(),
    val blocks: List<NativeBlock> = emptyList(),
    val growth: NativeGrowth? = null,
)

data class LocalUpload(val id: String, val name: String, val mimeType: String, val bytes: ByteArray, val slot: Int = 0) {
    override fun toString() = "LocalUpload(id=$id, bytes=${bytes.size})"
    val size get() = bytes.size
}
data class FilePickerRequest(val target: String, val fieldId: String, val multiple: Boolean, val mimeTypes: List<String> = listOf("*/*"))
data class FilePart(val name: String, val file: LocalUpload)
data class ImageGallery(val urls: List<String>, val initialIndex: Int)
data class NativeDownloadRequest(val url: String, val suggestedName: String, val isImage: Boolean = false)
data class SiteSubmission(val form: SiteForm, val values: List<FormValue>, val files: List<FilePart>, val submit: SiteSubmit) {
    override fun toString() = "SiteSubmission(form=${form.id}, files=${files.size})"
}
sealed class SiteActionResult {
    data class Response(val page: PageData, val uncertainReceipt: String? = null) : SiteActionResult()
    data class Rejected(val message: String) : SiteActionResult()
    data class Uncertain(val message: String, val receipt: String? = null) : SiteActionResult()
}

fun buildSiteFormValues(form: SiteForm, state: Map<String, List<String>>, submit: SiteSubmit): List<FormValue> {
    val values = form.hidden.toMutableList()
    form.fields.filter { it.kind != SiteFieldKind.FILE }.forEach { field ->
        val selected = state[field.id] ?: field.initial
        require(!field.required || selected.any { it.isNotBlank() }) { "请填写${field.label}" }
        require(field.maxLength == null || selected.all { it.length <= field.maxLength }) { "${field.label}超出长度限制" }
        val allowed = field.options.map { it.value }.toSet()
        if (field.kind in listOf(SiteFieldKind.SELECT, SiteFieldKind.CHECKBOX, SiteFieldKind.RADIO)) {
            require(selected.all { it in allowed }) { "${field.label}的选项已变化，请刷新" }
        }
        selected.forEach { values.add(FormValue(field.name, it)) }
    }
    if (!submit.name.isNullOrBlank()) values.add(FormValue(submit.name, submit.value))
    fun value(name: String) = values.lastOrNull { it.name == name }?.value.orEmpty()
    if (form.actionUrl.contains("H_name=bank")) {
        if (form.fields.any { it.name == "pwuser" }) require(value("pwuser").isNotBlank()) { "请填写收款用户名" }
        listOf("to_money", "drawmoney").filter { key -> form.fields.any { it.name == key } }.forEach { key ->
            val amount = value(key).toDoubleOrNull()
            require(amount != null && amount.isFinite() && amount > 0) { "请输入大于零的有效金额" }
        }
    }
    if (value("propwd").isNotEmpty()) {
        require(value("propwd") == value("check_pwd")) { "两次新密码不一致" }
        require(value("oldpwd").isNotEmpty()) { "修改密码需要原密码" }
    }
    return values
}

/** Never surface raw socket text such as "Connection reset" to the reader. */
fun friendlyError(error: Throwable?): String {
    val raw = error?.message.orEmpty().trim()
    val isOurs = raw.isNotEmpty() && raw.any { it in '\u4e00'..'\u9fff' } &&
        listOf("connection", "reset", "timeout", "socket", "ssl", "http", "failed to connect",
            "连接被", "连接失败", "无法连接", "网络异常", "超时", "重置", "拒绝连接", "断流", "unexpected end")
            .none { raw.contains(it, true) }
    return if (isOurs) raw else "网络不太稳定，已自动重试，请稍后再试"
}

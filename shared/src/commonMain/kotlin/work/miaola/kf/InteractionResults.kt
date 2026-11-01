package work.miaola.kf

/**
 * Shared semantics for operations whose server receipt is not a boolean.
 *
 * A mutation is only [Confirmed] when the client has independent evidence
 * (read-back, a matching sent item, or an explicit server receipt).  HTTP 200
 * and a rendered page are never proof by themselves.
 */
enum class OperationOutcome { SUCCESS, REJECTED, UNCERTAIN, NOT_ATTEMPTED }

sealed interface MessageSendResult {
    data class Confirmed(val recipient: String, val evidence: String) : MessageSendResult {
        override fun toString() = "MessageSendResult.Confirmed(evidence=$evidence)"
    }
    data class Rejected(val reason: String) : MessageSendResult
    data class Uncertain(val reason: String) : MessageSendResult
}

data class Mention(val displayName: String, val profileUrl: String, val uid: String? = null) {
    override fun toString() = "Mention(hasProfile=true, hasUid=${uid != null})"
}

enum class StatusKind { WAITING, AVAILABLE, COMPLETED, EXPIRED, FAILED, UNKNOWN }

data class StatusTimelineEntry(
    val title: String,
    val status: StatusKind,
    val time: String = "",
    val description: String = "",
    val relatedUrl: String = "",
    val action: SiteDestination? = null,
)

data class NativeStatusTimeline(val label: String, val entries: List<StatusTimelineEntry>) {
    val hasKnownStatus: Boolean get() = entries.any { it.status != StatusKind.UNKNOWN }
}

enum class BankDirection { IN, OUT, UNKNOWN }

data class NativeBankLogEntry(
    val id: String,
    val time: String,
    val type: String,
    val direction: BankDirection,
    val counterparty: String = "",
    val amount: String = "",
    val currency: String = "",
    val memo: String = "",
    val description: String = "",
) {
    override fun toString() = "NativeBankLogEntry(type=$type, hasTime=${time.isNotBlank()}, hasAmount=${amount.isNotBlank()})"
}

data class NativeBankLog(
    val label: String,
    val entries: List<NativeBankLogEntry>,
    val unparsedRows: List<List<String>> = emptyList(),
    val links: List<SiteDestination> = emptyList(),
)

// ---- Cross-page batch transaction -----------------------------------------

data class BatchTransactionItem(val stableId: String, val sourcePage: String, val label: String = "") {
    val identity: String get() = "$sourcePage#$stableId"
    override fun toString() = "BatchTransactionItem(hasStableId=${stableId.isNotBlank()}, hasSourcePage=${sourcePage.isNotBlank()})"
}

data class BatchItemResult(
    val item: BatchTransactionItem,
    val outcome: OperationOutcome,
    val message: String = "",
) {
    override fun toString() = "BatchItemResult(outcome=$outcome, hasMessage=${message.isNotBlank()})"
}

data class BatchPageRequest(
    val form: SiteForm,
    val values: List<FormValue>,
    val submit: SiteSubmit,
    val items: List<BatchTransactionItem>,
    val verifyAbsent: Boolean = true,
) {
    override fun toString() = "BatchPageRequest(form=${form.id}, items=${items.size}, verifyAbsent=$verifyAbsent)"
}

sealed interface BatchTransactionResult {
    data class Completed(val results: List<BatchItemResult>) : BatchTransactionResult
    data class Partial(val results: List<BatchItemResult>) : BatchTransactionResult
    data class Failed(val results: List<BatchItemResult>) : BatchTransactionResult
    data class Uncertain(val results: List<BatchItemResult>) : BatchTransactionResult
}

fun BatchTransactionResult.results(): List<BatchItemResult> = when (this) {
    is BatchTransactionResult.Completed -> results
    is BatchTransactionResult.Partial -> results
    is BatchTransactionResult.Failed -> results
    is BatchTransactionResult.Uncertain -> results
}

fun BatchTransactionResult.summary(): String {
    val all = results()
    val ok = all.count { it.outcome == OperationOutcome.SUCCESS }
    val failed = all.count { it.outcome == OperationOutcome.REJECTED }
    val uncertain = all.count { it.outcome == OperationOutcome.UNCERTAIN }
    val skipped = all.count { it.outcome == OperationOutcome.NOT_ATTEMPTED }
    return buildString {
        append("已完成 $ok 项")
        if (failed > 0) append("，$failed 项被站点拒绝")
        if (uncertain > 0) append("，$uncertain 项结果无法确认")
        if (skipped > 0) append("，$skipped 项未执行")
    }
}

fun classifyBatchResult(results: List<BatchItemResult>): BatchTransactionResult {
    val outcomes = results.map { it.outcome }
    return when {
        outcomes.all { it == OperationOutcome.SUCCESS } -> BatchTransactionResult.Completed(results)
        outcomes.any { it == OperationOutcome.UNCERTAIN } -> BatchTransactionResult.Uncertain(results)
        outcomes.none { it == OperationOutcome.SUCCESS } -> BatchTransactionResult.Failed(results)
        else -> BatchTransactionResult.Partial(results)
    }
}

// ---- Reward / avatar protocol results -------------------------------------

sealed interface RewardExecutionResult {
    data class Confirmed(val message: String) : RewardExecutionResult
    data class Rejected(val message: String) : RewardExecutionResult
    data class Uncertain(val message: String) : RewardExecutionResult
}

data class RewardAction(
    val id: String,
    val title: String,
    val protocol: RewardProtocol,
    val endpoint: String,
    val method: String,
    val fields: List<FormValue>,
    val sourceUrl: String,
    val readbackUrl: String = sourceUrl,
    val kind: String = if (protocol == RewardProtocol.AJAX_POST) "battle-step" else "reward",
    val confirmation: String = "",
) {
    // Endpoint query values include one-use verification material.
    override fun toString() = "RewardAction(kind=$kind, protocol=$protocol, method=$method, fields=${fields.size})"
}

enum class RewardProtocol { GET_LINK, AJAX_POST }

sealed interface AvatarUploadResult {
    data class Uploaded(val avatarUrl: String, val message: String = "头像已上传") : AvatarUploadResult {
        override fun toString() = "AvatarUploadResult.Uploaded(hasAvatarUrl=${avatarUrl.isNotBlank()})"
    }
    data class Rejected(val message: String) : AvatarUploadResult
    data class Uncertain(val message: String) : AvatarUploadResult
}

data class AvatarUploadRequest(
    val form: SiteForm,
    val upload: LocalUpload,
    val width: Int,
    val height: Int,
)

// ---- Gugu game model ------------------------------------------------------

data class GuguCharacter(
    val name: String,
    val rank: String = "",
    val level: String = "",
    val shells: String = "",
    val monthlyWins: String = "",
    val monthlyNpc: String = "",
    val monthlyNpcLevel: String = "",
    val monthlyPvp: String = "",
) {
    override fun toString() = "GuguCharacter(hasName=${name.isNotBlank()}, level=$level)"
}

data class GuguItem(
    val id: String,
    val name: String,
    val level: String = "",
    val quality: String = "",
    val iconUrl: String = "",
    val stats: List<String> = emptyList(),
    val equipped: Boolean = false,
    val actions: List<GuguAction> = emptyList(),
) {
    override fun toString() = "GuguItem(hasId=${id.isNotBlank()}, leveled=${level.isNotBlank()}, actions=${actions.size})"
}

data class GuguCooldown(val label: String, val remaining: String, val description: String = "")

/** One row of the site's card win-rate matrix: attacking and defending cells. */
data class GuguWinRateRow(
    val card: String,
    val attack: List<String> = emptyList(),
    val defense: List<String> = emptyList(),
)

data class GuguWinRateTable(
    val rank: String = "",
    val cards: List<String> = emptyList(),
    val rows: List<GuguWinRateRow> = emptyList(),
    val note: String = "",
)

data class GuguShopItem(
    val name: String,
    val price: String,
    val dailyLimit: Boolean = false,
    val action: GuguAction? = null,
)

/** A numeric or text field rendered by a game action form. */
data class GuguActionField(
    val name: String,
    val label: String,
    val hint: String = "",
    val defaultValue: String = "",
    val numeric: Boolean = true,
    val maxLength: Int = 64,
) {
    override fun toString() = "GuguActionField(numeric=$numeric)"
}

/** A captured game operation. The numeric target is resolved by the game protocol. */
data class GuguAction(
    val id: String,
    val kind: GuguActionKind,
    val label: String,
    val confirmation: String,
    val detail: String = "",
    val destructive: Boolean = false,
    val inputLabel: String = "",
    val inputHint: String = "",
    val inputDefault: String = "",
    /** Multi-field actions such as attribute allocation and halo talents. */
    val fields: List<GuguActionField> = emptyList(),
) {
    override fun toString() = "GuguAction(kind=$kind, label=$label)"
}

enum class GuguActionKind {
    EQUIP_ITEM,
    UNEQUIP_ITEM,
    DELETE_ITEM,
    PIERCE_ITEM,
    CARD_EQUIP,
    BEACH_CLEAR,
    BEACH_REFRESH,
    SHOP_BUY,
    PK_STEP,
    PK_REFRESH,
    PK_GO,
    KEY_GENERATE,
    KEY_EXTEND,
    KEY_SHOP_ALLOW,
    KEY_SHOP_DENY,
    /** Opens a menu served by the game; opening itself is not a mutation. */
    MENU_OPEN,
    /** Executes one exact `oclick` option returned by a menu. */
    MENU_CHOICE,
    /** Executes one exact `oclickth` option carrying an extra id. */
    MENU_CHOICE_EXTRA,
    /** Opens one gift entry returned by the game's gift list. */
    GIFT_OPEN,
    /** Six-field attribute point allocation. */
    STAT_ALLOCATE,
    /** Halo talent save; the array is a comma-separated list of talent ids. */
    HALO_SAVE,
    /** Card max-level use; the game's `cmaxup` wire uses `num`, not `id`. */
    MAX_UP,
    /** Character rename; exposed only when the site enables its own input. */
    RENAME,
    /** One dice battle request; the site's own control may loop, this client does not. */
    DICE_BATTLE,
}

data class GuguActionRequest(
    val action: GuguAction,
    val input: String = "",
    val values: Map<String, String> = emptyMap(),
) {
    override fun toString() = "GuguActionRequest(kind=${action.kind}, input=${input.isNotBlank()}, fields=${values.size})"
}

/** A short-lived list of exact choices returned by fyg_menu.php. */
data class GuguMenu(val title: String, val actions: List<GuguAction>) {
    override fun toString() = "GuguMenu(actions=${actions.size})"
}

sealed class GuguActionResult {
    data class Success(val message: String, val snapshot: GuguSnapshot? = null) : GuguActionResult()
    data class Rejected(val message: String) : GuguActionResult()
    data class Uncertain(val message: String, val snapshot: GuguSnapshot? = null) : GuguActionResult()
    data class Choices(val menu: GuguMenu, val snapshot: GuguSnapshot? = null) : GuguActionResult()
}

data class GuguLogEntry(val date: String, val lines: List<String> = emptyList())

data class GuguSnapshot(
    val character: GuguCharacter,
    val stats: List<NativeInfo> = emptyList(),
    val equipped: List<GuguItem> = emptyList(),
    val inventory: List<GuguItem> = emptyList(),
    val cards: List<GuguItem> = emptyList(),
    val cooldowns: List<GuguCooldown> = emptyList(),
    val links: List<SiteDestination> = emptyList(),
    val winRates: GuguWinRateTable? = null,
    val shop: List<GuguShopItem> = emptyList(),
    val updateLog: List<GuguLogEntry> = emptyList(),
    /** Page-level actions such as beach cleanup, PK refresh and key settings. */
    val actions: List<GuguAction> = emptyList(),
    val readOnlyNote: String = "咕咕镇操作需要用户确认；客户端不会自动连续战斗、购买或重试。",
    val unparsed: List<String> = emptyList(),
)

/** Client-side battle loop is intentionally not represented as a server action. */
data class GuguReadOnlyState(val loadedAt: Long, val snapshot: GuguSnapshot)

fun mergeMentionKeywords(existing: String, body: String): String {
    val found = Regex("(?<![\\w.])@([\\p{L}\\p{N}_-]{1,16})").findAll(body)
        .map { it.groupValues[1] }
        .toList()
    if (found.isEmpty()) return existing
    val current = existing.split(',', '，').map { it.trim() }.filter { it.isNotBlank() }
    return (current + found).distinctBy { it.lowercase() }.take(5).joinToString(",")
}

fun mentionFromAnchor(text: String, profileUrl: String, precedingText: String = ""): Mention? {
    val visible = text.trim()
    if (visible.isEmpty()) return null
    val at = visible.startsWith("@") || precedingText.trimEnd().endsWith("@")
    if (!at) return null
    val name = visible.removePrefix("@").trim()
    if (name.isEmpty() || !name.matches(Regex("[\\p{L}\\p{N}_-]{1,32}"))) return null
    val uid = Regex("[?&]uid=([0-9]+)").find(profileUrl)?.groupValues?.get(1)
    return Mention(name, profileUrl, uid)
}

/**
 * Confirmation requires a matching row in the sendbox. A redirect, HTTP 200 or
 * an echoed editor is not enough. The caller may pass the POST response when it
 * already is the sendbox; otherwise it fetches the sendbox separately.
 */
fun messageSendResult(
    title: String,
    recipient: String,
    sendbox: PageData?,
    responseHasEditor: Boolean,
    responseNotice: String = "",
    responseUrl: String = "",
    previousMessageUrls: Set<String>? = null,
): MessageSendResult {
    val match = if (previousMessageUrls != null) sendbox?.nativePage?.messages?.firstOrNull { message ->
        message.title.trim() == title.trim() && message.recipient.trim() == recipient.trim() &&
            message.url !in previousMessageUrls
    } else null
    val text = responseNotice.trim()
    val explicitRejection = responseHasEditor && text.isNotBlank() &&
        listOf("不能", "禁止", "无效", "不存在", "发送失败", "错误", "请填写", "没有权限", "收件人不存在")
            .any { text.contains(it) }
    if (match != null && explicitRejection) {
        return MessageSendResult.Uncertain("服务器拒绝提示与发件箱新记录相矛盾；请人工核对，不要重发。")
    }
    if (match != null) return MessageSendResult.Confirmed(recipient.trim(),
        if (responseUrl.contains("action=sendbox")) "发件箱新出现对应标题和收件人" else "重新读取发件箱后找到新的对应标题和收件人")
    if (responseHasEditor) {
        return if (explicitRejection) MessageSendResult.Rejected(text.take(200))
        else MessageSendResult.Uncertain("服务器重新返回了编辑器，但没有明确的拒绝回执。请检查发件箱后再决定是否重试。")
    }
    return MessageSendResult.Uncertain("服务器已接收请求，但无法确认是否发送成功。请检查发件箱后再决定是否重试。")
}

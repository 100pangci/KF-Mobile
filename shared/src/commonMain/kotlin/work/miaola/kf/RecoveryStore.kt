package work.miaola.kf

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DraftAttachmentMeta(
    val name: String,
    val mimeType: String,
    val size: Int,
    val slot: Int,
)

@Serializable
data class EditorDraft(
    val schemaVersion: Int = 1,
    val sourceUrl: String,
    val title: String = "",
    val body: String = "",
    val recipient: String = "",
    val category: String = "",
    val keywords: String = "",
    val attachments: List<DraftAttachmentMeta> = emptyList(),
    val updatedAt: Long = 0,
) {
    override fun toString() = "EditorDraft(sourceUrl=$sourceUrl, titleLength=${title.length}, bodyLength=${body.length}, attachments=${attachments.size})"
}

@Serializable
data class TimelineRecovery(
    val rootUrl: String,
    val loadedPageUrls: List<String> = emptyList(),
    val anchorIndex: Int = 0,
    val anchorOffset: Int = 0,
    val anchorKey: String = "",
    val updatedAt: Long = 0,
)

@Serializable
data class DestinationRecovery(
    val kind: String = "",
    val url: String = "",
    val query: String = "",
    val author: String = "",
) {
    override fun toString() = "DestinationRecovery(kind=$kind, hasUrl=${url.isNotBlank()}, queryLength=${query.length})"
}

@Serializable
data class RecoveryState(
    val schemaVersion: Int = 1,
    val origin: String,
    val destination: DestinationRecovery = DestinationRecovery(),
    val timelines: Map<String, TimelineRecovery> = emptyMap(),
    val pendingManagement: List<String> = emptyList(),
    val updatedAt: Long = 0,
) {
    override fun toString() = "RecoveryState(origin=$origin, timelines=${timelines.size}, pendingManagement=${pendingManagement.size})"
}

@Serializable
data class PendingBatch(
    val schemaVersion: Int = 1,
    val sourcePages: List<String>,
    val itemIds: List<String>,
    val createdAt: Long = 0,
) {
    override fun toString() = "PendingBatch(sourcePages=${sourcePages.size}, items=${itemIds.size})"
}

/**
 * Only reconstructable content is persisted here.  Never tokens, hidden form
 * fields, cookies, HTML payloads, attachment bytes or bank/password forms.
 * The backing [KeyValueStore] is encrypted by the Android implementation.
 */
class RecoveryStore(private val store: KeyValueStore, private val origin: String) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val stateKey = "recovery_v1@" + origin
    private val draftIndexKey = "editor_drafts_v1@" + origin

    fun loadDraft(sourceUrl: String, now: Long = currentEpochMillis()): EditorDraft? {
        val raw = store.get(draftKey(sourceUrl)) ?: return null
        val draft = runCatching { json.decodeFromString<EditorDraft>(raw) }.getOrNull() ?: return null
        if (draft.schemaVersion != 1 || draft.sourceUrl != sourceUrl) return null
        if (draft.updatedAt <= 0 || draft.updatedAt > now + FUTURE_TIMESTAMP_TOLERANCE_MS ||
            now - draft.updatedAt > MAX_DRAFT_AGE_MS) return null
        if (draft.title.length > 2_000 || draft.body.length > MAX_DRAFT_BODY || draft.recipient.length > 200 ||
            draft.keywords.length > 2_000 || draft.attachments.size > 10) return null
        return draft
    }

    fun saveDraft(draft: EditorDraft) {
        require(draft.sourceUrl.isNotBlank())
        if (draft.title.isBlank() && draft.body.isBlank() && draft.recipient.isBlank() &&
            draft.attachments.isEmpty() && draft.keywords.isBlank()) {
            removeDraft(draft.sourceUrl)
            return
        }
        val bounded = draft.copy(
            title = draft.title.take(2_000),
            body = draft.body.take(MAX_DRAFT_BODY),
            recipient = draft.recipient.take(200),
            category = draft.category.take(80),
            keywords = draft.keywords.take(2_000),
            attachments = draft.attachments.take(10),
            updatedAt = if (draft.updatedAt > 0) draft.updatedAt else currentEpochMillis(),
        )
        val key = draftKey(draft.sourceUrl)
        store.put(key, json.encodeToString(bounded))
        indexDraft(key, present = true)
    }

    fun removeDraft(sourceUrl: String) {
        val key = draftKey(sourceUrl)
        store.remove(key)
        indexDraft(key, present = false)
    }

    /** Drafts are keyed by origin only, so logout must remove them explicitly. */
    fun clearDrafts() {
        loadDraftIndex().forEach(store::remove)
        store.remove(draftIndexKey)
    }

    /**
     * Remove everything tied to the signed-in user: drafts, destination and
     * timeline anchors. Pending mutation receipts stay durable for readback.
     */
    fun clearUserData() {
        clearDrafts()
        clearState()
    }

    private fun loadDraftIndex(): Set<String> =
        runCatching { json.decodeFromString<Set<String>>(store.get(draftIndexKey) ?: "[]") }.getOrDefault(emptySet())

    private fun indexDraft(key: String, present: Boolean) {
        val existing = loadDraftIndex()
        val updated = if (present) existing + key else existing - key
        if (updated.isEmpty()) store.remove(draftIndexKey)
        else store.put(draftIndexKey, json.encodeToString(updated))
    }

    fun loadState(): RecoveryState? {
        val raw = store.get(stateKey) ?: return null
        val state = runCatching { json.decodeFromString<RecoveryState>(raw) }.getOrNull() ?: return null
        if (state.schemaVersion != 1 || state.origin != origin) return null
        if (state.updatedAt <= 0 || state.updatedAt > currentEpochMillis() + FUTURE_TIMESTAMP_TOLERANCE_MS ||
            currentEpochMillis() - state.updatedAt > MAX_STATE_AGE_MS) return null
        return state.validated()
    }

    fun saveState(state: RecoveryState) {
        val bounded = state.copy(
            origin = origin,
            // Keep the most recently read timelines, not the first inserted ones.
            timelines = state.timelines.entries.sortedByDescending { it.value.updatedAt }.take(8).associate { (key, value) ->
                key to value.copy(
                    loadedPageUrls = value.loadedPageUrls.take(MAX_RESTORED_PAGES),
                    anchorKey = value.anchorKey.take(160),
                )
            },
            updatedAt = currentEpochMillis(),
        ).validated()
        store.put(stateKey, json.encodeToString(bounded))
    }

    fun clearState() = store.remove(stateKey)

    fun loadPendingBatch(): PendingBatch? {
        val raw = store.get("batch_pending_v1@$origin") ?: return null
        val value = runCatching { json.decodeFromString<PendingBatch>(raw) }.getOrNull() ?: return null
        return value.takeIf { it.schemaVersion == 1 && it.sourcePages.isNotEmpty() && it.sourcePages.size <= 8 }
    }

    fun savePendingBatch(value: PendingBatch) = store.put("batch_pending_v1@$origin", json.encodeToString(value))
    fun clearPendingBatch() = store.remove("batch_pending_v1@$origin")

    /** Keep an uncertain page durable until its user-initiated readback succeeds. */
    fun clearPendingBatchSource(source: String) {
        val pending = loadPendingBatch() ?: return
        val remaining = pending.sourcePages.filterNot { isSamePageList(it, source) }
        if (remaining.isEmpty()) clearPendingBatch()
        else savePendingBatch(pending.copy(sourcePages = remaining))
    }

    private fun draftKey(sourceUrl: String) = "editor_draft_v1@$origin#${sourceUrl.hashCode()}"

    private fun RecoveryState.validated(): RecoveryState {
        fun safe(url: String) = url.startsWith("$origin/") && !url.contains('\n') && url.length <= 2_048
        // The destination may be a reward/rating entry carrying a session-issued
        // safeid. It is reconstructable from the site, so never persist it.
        fun tokenFree(url: String): Boolean {
            val query = url.substringAfter('?', "").substringBefore('#')
            if (query.isBlank()) return true
            val sensitive = setOf("safeid", "verify", "ckcode", "pwpwd", "pwuser", "token")
            return query.split('&').none { it.substringBefore('=').lowercase() in sensitive }
        }
        val safeDestination = destination.takeIf {
            it.url.isBlank() || (safe(it.url) && tokenFree(it.url))
        } ?: DestinationRecovery()
        return copy(
            destination = safeDestination,
            timelines = timelines.filterKeys(::safe).mapValues { (_, value) ->
                value.copy(loadedPageUrls = value.loadedPageUrls.filter(::safe).take(MAX_RESTORED_PAGES),
                    anchorIndex = value.anchorIndex.coerceIn(0, 100_000),
                    anchorOffset = value.anchorOffset.coerceIn(-1_000_000, 1_000_000),
                    anchorKey = value.anchorKey.take(160))
            },
            pendingManagement = pendingManagement.filter(::safe).distinct().take(8),
            updatedAt = updatedAt,
        )
    }

    companion object {
        const val MAX_RESTORED_PAGES = 6
        const val MAX_DRAFT_AGE_MS = 7L * 24 * 60 * 60 * 1000
        const val MAX_STATE_AGE_MS = 3L * 24 * 60 * 60 * 1000
        private const val FUTURE_TIMESTAMP_TOLERANCE_MS = 5L * 60 * 1000
        private const val MAX_DRAFT_BODY = 120_000
    }
}

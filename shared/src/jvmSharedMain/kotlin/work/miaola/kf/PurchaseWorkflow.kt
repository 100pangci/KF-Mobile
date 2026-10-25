package work.miaola.kf

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PurchaseReceipt(val rejection: String? = null)

/** The transaction is called exactly once, only after a fresh price check. */
class PurchaseWorkflow(
    private val origin: String,
    private val store: KeyValueStore,
    private val read: suspend (String) -> PageData,
    private val transact: suspend (PurchaseOffer) -> PurchaseReceipt,
) {
    private val mutex = Mutex()
    suspend fun purchase(confirmed: PurchaseOffer): PurchaseResult = mutex.withLock { execute(confirmed) }

    private suspend fun execute(confirmed: PurchaseOffer): PurchaseResult {
        val before = read(confirmed.threadUrl)
        if (!before.authenticated) return PurchaseResult.Rejected("登录已失效，请先重新登录；尚未提交购买")
        val post = before.posts.firstOrNull { it.id == confirmed.postId }
            ?: return PurchaseResult.Rejected("未找到对应楼层，请重新打开帖子；尚未提交购买")
        val pendingKey = purchasePendingKey(origin, confirmed.id)
        val fresh = post.purchases.firstOrNull { it.id == confirmed.id }
        if (fresh == null) {
            if (post.purchaseLocked) {
                return PurchaseResult.Rejected("购买框已变化，请先在网页中核对；尚未提交购买")
            }
            store.remove(pendingKey)
            return PurchaseResult.Success(before, "内容已解锁，无需再次购买")
        }
        if (store.get(pendingKey) != null) return PurchaseResult.Uncertain("上次购买结果尚未确认。请先刷新或到网页核对，已禁止重复扣费请求。")
        if (fresh.price != confirmed.price || fresh.currency != confirmed.currency) return PurchaseResult.PriceChanged(fresh)

        // Persist before the charge request. A crash, cancellation or timeout
        // must not allow an automatic duplicate transaction after restart.
        store.put(pendingKey, "pending")
        return try {
            val receipt = transact(fresh)
            val after = read(fresh.threadUrl)
            val updated = after.posts.firstOrNull { it.id == fresh.postId }
            if (after.authenticated && updated != null && updated.purchases.none { it.id == fresh.id }
                && !updated.purchaseLocked) {
                store.remove(pendingKey)
                PurchaseResult.Success(after, "购买完成，付费内容已刷新")
            } else if (receipt.rejection != null) {
                store.remove(pendingKey)
                PurchaseResult.Rejected(receipt.rejection)
            } else {
                PurchaseResult.Uncertain("购买请求已送出，但暂时无法确认是否解锁。请刷新或到网页核对，不要重复购买。")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled // Leave the durable pending marker intact.
        } catch (_: Exception) {
            PurchaseResult.Uncertain("连接中断或服务器结果无法确认，购买可能已经完成。已禁止重复请求，请先刷新或到网页核对。")
        }
    }
}

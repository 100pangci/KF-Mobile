package work.miaola.kf

import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import java.io.IOException

class PurchaseWorkflowTest {
    private val origin = "https://kf.miaola.work"
    private val offer = PurchaseOffer("1:tpc", "pidtpc", "1", "KFB", "$origin/job.php?action=buytopic&tid=1&pid=tpc&verify=old", "$origin/read.php?tid=1")
    private fun locked(current: PurchaseOffer = offer) = PageData(offer.threadUrl, "测试资源", true,
        posts = listOf(ForumPost("pidtpc", "作者", "", "楼主", "", "", purchases = listOf(current), purchaseLocked = true)))
    private fun unlocked() = locked().copy(posts = listOf(ForumPost("pidtpc", "作者", "", "楼主", "", "<fieldset><legend>此帖售价 1 KFB，您已购买</legend>可见内容</fieldset>")))

    @Test fun oneChargeThenReadBackAndUnlock() = runBlocking {
        val store = MemoryStore(); var charged = 0; var reads = 0
        val workflow = PurchaseWorkflow(origin, store, { reads++; if (charged == 0) locked() else unlocked() }, { charged++; PurchaseReceipt() })
        assertIs<PurchaseResult.Success>(workflow.purchase(offer))
        assertEquals(1, charged); assertEquals(2, reads)
        assertNull(store.get(purchasePendingKey(origin, offer.id)))
    }
    @Test fun alreadyPurchasedDoesNotChargeAgain() = runBlocking {
        var charged = 0
        val workflow = PurchaseWorkflow(origin, MemoryStore(), { unlocked() }, { charged++; PurchaseReceipt() })
        assertIs<PurchaseResult.Success>(workflow.purchase(offer)); assertEquals(0, charged)
    }
    @Test fun changedPriceRequiresNewConfirmation() = runBlocking {
        var charged = 0
        val updated = offer.copy(price = "2")
        val workflow = PurchaseWorkflow(origin, MemoryStore(), { locked(updated) }, { charged++; PurchaseReceipt() })
        val result = assertIs<PurchaseResult.PriceChanged>(workflow.purchase(offer))
        assertEquals("2", result.offer.price); assertEquals(0, charged)
    }
    @Test fun changedCurrencyAlsoRequiresConfirmation() = runBlocking {
        var charged = 0
        val workflow = PurchaseWorkflow(origin, MemoryStore(), { locked(offer.copy(currency = "贡献")) }, { charged++; PurchaseReceipt() })
        assertIs<PurchaseResult.PriceChanged>(workflow.purchase(offer)); assertEquals(0, charged)
    }
    @Test fun freshVerifyTokenIsUsedInsteadOfStalePageToken() = runBlocking<Unit> {
        var charged = false
        val current = offer.copy(actionUrl = offer.actionUrl.replace("verify=old", "verify=fresh"))
        val workflow = PurchaseWorkflow(origin, MemoryStore(), { if (charged) unlocked() else locked(current) }, {
            assertTrue(it.actionUrl.endsWith("verify=fresh")); charged = true; PurchaseReceipt()
        })
        assertIs<PurchaseResult.Success>(workflow.purchase(offer))
    }
    @Test fun insufficientBalanceIsShownWithoutPretendingSuccess() = runBlocking {
        val store = MemoryStore(); var charged = 0
        val workflow = PurchaseWorkflow(origin, store, { locked() }, { charged++; PurchaseReceipt("余额不足") })
        assertEquals("余额不足", assertIs<PurchaseResult.Rejected>(workflow.purchase(offer)).message)
        assertEquals(1, charged); assertNull(store.get(purchasePendingKey(origin, offer.id)))
    }
    @Test fun ambiguousResponseBlocksRepeatEvenAfterNewWorkflowInstance() = runBlocking {
        val store = MemoryStore(); var charged = 0
        val first = PurchaseWorkflow(origin, store, { locked() }, { charged++; throw IOException("network") })
        assertIs<PurchaseResult.Uncertain>(first.purchase(offer))
        val afterRestart = PurchaseWorkflow(origin, store, { locked() }, { charged++; PurchaseReceipt() })
        assertIs<PurchaseResult.Uncertain>(afterRestart.purchase(offer)); assertEquals(1, charged)
        val afterWebPurchase = PurchaseWorkflow(origin, store, { unlocked() }, { charged++; PurchaseReceipt() })
        assertIs<PurchaseResult.Success>(afterWebPurchase.purchase(offer)); assertEquals(1, charged)
        assertNull(store.get(purchasePendingKey(origin, offer.id)))
    }
    @Test fun cancellationKeepsPendingMarker() = runBlocking<Unit> {
        val store = MemoryStore()
        val workflow = PurchaseWorkflow(origin, store, { locked() }, { throw CancellationException("test") })
        assertFailsWith<CancellationException> { workflow.purchase(offer) }
        assertNotNull(store.get(purchasePendingKey(origin, offer.id)))
    }
    @Test fun missingPostOrLoggedOutDoesNotCharge() = runBlocking {
        var charged = 0
        val workflow = PurchaseWorkflow(origin, MemoryStore(), { locked().copy(posts = emptyList()) }, { charged++; PurchaseReceipt() })
        assertIs<PurchaseResult.Rejected>(workflow.purchase(offer))
        val guest = PurchaseWorkflow(origin, MemoryStore(), { locked().copy(authenticated = false) }, { charged++; PurchaseReceipt() })
        assertIs<PurchaseResult.Rejected>(guest.purchase(offer)); assertEquals(0, charged)
    }
    @Test fun successfulHttpResponseWithoutUnlockIsNotSuccess() = runBlocking<Unit> {
        val store = MemoryStore()
        val workflow = PurchaseWorkflow(origin, store, { locked() }, { PurchaseReceipt() })
        assertIs<PurchaseResult.Uncertain>(workflow.purchase(offer))
        assertNotNull(store.get(purchasePendingKey(origin, offer.id)))
    }
    @Test fun concurrentConfirmationsCannotChargeTwice() = runBlocking {
        var charged = 0
        val workflow = PurchaseWorkflow(origin, MemoryStore(), { if (charged > 0) unlocked() else locked() }, {
            charged++; delay(10); PurchaseReceipt()
        })
        val first = async { workflow.purchase(offer) }
        val second = async { workflow.purchase(offer) }
        assertIs<PurchaseResult.Success>(first.await()); assertIs<PurchaseResult.Success>(second.await())
        assertEquals(1, charged)
    }
}

package work.miaola.kf

import kotlinx.coroutines.runBlocking
import kotlin.test.*
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class SiteMutationReceiptTest {
    private val origin = "https://kf.miaola.work"

    @Test fun genericMutationHttp200IsReturnedForDisplayButNeverAsConfirmedSuccess() = runBlocking<Unit> {
        val service = HttpForumService(origin, MemoryStore(), Interceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("<div class='dcol' style='width:900px'><p>请求已处理</p></div>".toResponseBody()).build()
        })
        try {
            val result = assertIs<SiteActionResult.Response>(service.executeSite(
                SiteDestination("$origin/unknown.php?do=change", SiteEffect.CHANGE, "合成操作")))
            assertNotNull(result.page)
            assertTrue(requireNotNull(result.uncertainReceipt).contains("未验证"))
        } finally { service.close() }
    }

    @Test fun explicitMutationRejectionIsRejectedButNarrative200RemainsUncertain() = runBlocking<Unit> {
        var body = "<div class='dcol' style='width:900px'><p>操作失败：余额不足</p></div>"
        val service = HttpForumService(origin, MemoryStore(), Interceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody()).build()
        })
        try {
            val destination = SiteDestination("$origin/unknown.php?do=change", SiteEffect.CHANGE)
            assertIs<SiteActionResult.Rejected>(service.executeSite(destination))
            body = "<div class='dcol' style='width:900px'><p>相关规则：历史操作失败后可以查看日志。</p></div>"
            val uncertain = assertIs<SiteActionResult.Response>(service.executeSite(destination))
            assertNotNull(uncertain.uncertainReceipt)
        } finally { service.close() }
    }
}

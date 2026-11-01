package work.miaola.kf

import kotlinx.coroutines.runBlocking
import kotlin.test.*
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class RewardSubpageTest {
    private val origin = "https://kf.miaola.work"
    private val parser = ForumHtmlParser(origin)

    private fun page(body: String) = """<div id='kf_topuser'>合成用户</div>
        <div id='kf_information'><a href='login.php?action=quit'>退出</a></div>
        <div class='dcol' style='width:900px'>$body</div>"""

    private val mybp = page("""
        <script>
        function sywp(wpid){ $.ajax({ type: "POST", url: "kf_fw_ig_mybpdt.php", data: "do=1&id="+wpid+"&safeid=synthetic", success: function(msg){} }); }
        function cswp(wpid){ $.ajax({ type: "POST", url: "kf_fw_ig_mybpdt.php", data: "do=2&id="+wpid+"&safeid=synthetic", success: function(msg){} }); }
        function dkhz(hzlv){ $.ajax({ type: "POST", url: "kf_fw_ig_mybpdt.php", data: "do=3&id="+hzlv+"&safeid=synthetic", success: function(msg){} }); }
        function cdzb(zbid){ $.ajax({ type: "POST", url: "kf_fw_ig_mybpdt.php", data: "do=4&id="+zbid+"&safeid=synthetic", success: function(msg){} }); }
        function rlzb_qr(zbid){ if (confirm("熔炼会消失")) { $.ajax({ type: "POST", url: "kf_fw_ig_mybpdt.php", data: "do=5&id="+zbid+"&safeid=synthetic", success: function(msg){} }); } }
        </script>
        <a href="javascript:;" onclick="sywp('7')">使用</a>
        <a href="javascript:;" onclick="cswp('8')">出售</a>
        <a href="javascript:;" onclick="dkhz('1')">打开1个</a>
        <a href="javascript:;" onclick="cdzb('9')">装备</a>
        <a href="javascript:;" onclick="rlzb_qr('9')">熔炼</a>
    """)

    private val shop = page("""
        <script>
        function gmwp(wpid){ $.ajax({ type: "POST", url: "kf_fw_ig_shop.php", data: "buy="+wpid+"&safeid=synthetic", success: function(msg){} }); }
        </script>
        <a href="javascript:;" onclick="gmwp('103')">购买</a>
    """)

    private val halo = page("""
        <a href="kf_fw_ig_halo.php?do=buy&id=1&safeid=synthetic">购买</a>
        <a href="kf_fw_ig_halo.php">返回</a>
    """)

    @Test fun subpageActionsExposeOnlyCapturedWireShapes() {
        val items = parser.parse(mybp, "$origin/kf_fw_ig_mybp.php").nativePage!!
        assertEquals(5, items.rewards.size)
        val open = items.rewards.first { it.kind == "mybp:3" }
        assertEquals("mybp:3:1", open.id)
        assertEquals("打开1个", open.title)
        assertEquals(RewardProtocol.AJAX_POST, open.protocol)
        assertEquals("$origin/kf_fw_ig_mybpdt.php", open.endpoint)
        assertEquals(listOf("do", "id", "safeid"), open.fields.map { it.name })
        assertEquals(listOf("3", "1", "synthetic"), open.fields.map { it.value })
        assertTrue(open.confirmation.isNotBlank())
        assertEquals("熔炼", items.rewards.first { it.kind == "mybp:5" }.title)
        assertTrue(items.unsupported.isEmpty(), "mybp actions must not also be reported as unavailable")

        val shopPage = parser.parse(shop, "$origin/kf_fw_ig_shop.php").nativePage!!
        val buy = shopPage.rewards.single()
        assertEquals("shop:buy:103", buy.id)
        assertEquals("$origin/kf_fw_ig_shop.php", buy.endpoint)
        assertEquals(listOf("buy", "safeid"), buy.fields.map { it.name })
        assertEquals(listOf("103", "synthetic"), buy.fields.map { it.value })
        assertTrue(shopPage.unsupported.isEmpty(), "shop buy must not also be reported as unavailable")

        val haloPage = parser.parse(halo, "$origin/kf_fw_ig_halo.php").nativePage!!
        val haloBuy = haloPage.rewards.single()
        assertEquals("halo:buy:1", haloBuy.id)
        assertEquals(RewardProtocol.GET_LINK, haloBuy.protocol)
        assertEquals("GET", haloBuy.method)
        assertTrue(haloBuy.fields.isEmpty())
        assertFalse(haloPage.links.any { it.url == haloBuy.endpoint }, "halo buy must not leak through generic links")
    }

    @Test fun subpageExecutionSendsTheCapturedWireAndStaysUncertain() = runBlocking<Unit> {
        val requests = mutableListOf<Pair<String, String>>()
        val service = HttpForumService(origin, MemoryStore(), Interceptor { chain ->
            val request = chain.request()
            requests += request.method to request.url.toString()
            val html = when {
                request.url.toString().startsWith("$origin/kf_fw_ig_mybp.php") -> mybp
                request.url.toString().startsWith("$origin/kf_fw_ig_shop.php") -> shop
                request.url.toString().startsWith("$origin/kf_fw_ig_halo.php") -> halo
                else -> "<div>ok</div>"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("test")
                .body(html.toResponseBody()).build()
        })
        try {
            val mybpAction = parser.parse(mybp, "$origin/kf_fw_ig_mybp.php").nativePage!!.rewards.first { it.kind == "mybp:3" }
            assertIs<RewardExecutionResult.Uncertain>(service.executeReward(mybpAction))
            val shopAction = parser.parse(shop, "$origin/kf_fw_ig_shop.php").nativePage!!.rewards.single()
            assertIs<RewardExecutionResult.Uncertain>(service.executeReward(shopAction))
            val haloAction = parser.parse(halo, "$origin/kf_fw_ig_halo.php").nativePage!!.rewards.single()
            assertIs<RewardExecutionResult.Uncertain>(service.executeReward(haloAction))

            assertTrue(requests.any { it.first == "POST" && it.second == "$origin/kf_fw_ig_mybpdt.php" })
            assertTrue(requests.any { it.first == "POST" && it.second == "$origin/kf_fw_ig_shop.php" })
            assertTrue(requests.any { it.first == "GET" && it.second.contains("kf_fw_ig_halo.php?do=buy&id=1") })
        } finally { service.close() }
    }

    @Test fun tamperedSubpageWireIsRejectedBeforeNetwork() = runBlocking<Unit> {
        var requests = 0
        val service = HttpForumService(origin, MemoryStore(), Interceptor { requests++; error("no network") })
        try {
            val action = parser.parse(mybp, "$origin/kf_fw_ig_mybp.php").nativePage!!.rewards.first { it.kind == "mybp:3" }
            assertIs<RewardExecutionResult.Rejected>(service.executeReward(action.copy(endpoint = "$origin/job.php?action=buytopic")))
            assertIs<RewardExecutionResult.Rejected>(service.executeReward(action.copy(kind = "mybp:4")))
            assertIs<RewardExecutionResult.Rejected>(service.executeReward(action.copy(
                fields = listOf(FormValue("do", "3"), FormValue("id", "2"), FormValue("safeid", "synthetic")),
            )))
            assertEquals(0, requests)
        } finally { service.close() }
    }
}

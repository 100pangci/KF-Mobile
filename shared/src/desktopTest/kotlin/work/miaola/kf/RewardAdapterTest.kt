package work.miaola.kf

import kotlinx.coroutines.runBlocking
import kotlin.test.*
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

class RewardAdapterTest {
    private val origin = "https://kf.miaola.work"
    private val parser = ForumHtmlParser(origin)
    private val battleHtml = """<div id='kf_topuser'>合成用户</div><div id='kf_information'><a href='login.php?action=quit'>退出</a></div>
        <div class='dcol' style='width:900px'><script>
        $.ajax({type:"POST",url:"kf_fw_ig_intel.php",data:"safeid=synthetic"});</script>
        <button onclick="jgjg('1')">战斗（前进一层楼）</button>
        <button onclick="jgjg('99')">战斗（前进至结束）</button></div>"""
    private fun rewardPage(includeClaim: Boolean = true, status: String = "有评分记录") = """<div id='kf_topuser'>合成用户</div><div id='kf_information'><a href='login.php?action=quit&amp;verify=synthetic'>退出</a></div><div class='dcol' style='width:900px'>
        <table><tr><td>2026-01-01 10:00</td><td><a href='read.php?tid=1'>合成主题</a></td><td>$status</td>
        <td>${if (includeClaim) "<a href='kf_fw_1wkfb.php?do=1&amp;safeid=synthetic&amp;ptid=1'>获取评分奖励</a>" else ""}</td></tr></table></div>"""

    @Test fun preparationOnlyExposesTheVerifiedSingleStepAndClaimLink() {
        val rating = parser.parse(rewardPage(), "$origin/kf_fw_1wkfb.php").nativePage!!
        val claim = rating.rewards.single()
        assertEquals(RewardProtocol.GET_LINK, claim.protocol)
        assertEquals("GET", claim.method)
        assertEquals("claim:1", claim.id)
        assertFalse(rating.links.any { it.url == claim.endpoint }, "a reward action must not leak through generic links")
        assertEquals(StatusKind.AVAILABLE, rating.statusTimeline!!.entries.single().status)

        val battle = parser.parse(battleHtml, "$origin/kf_fw_ig_index.php").nativePage!!.rewards
        assertEquals(1, battle.size)
        assertEquals("battle-step", battle.single().id)
        assertEquals("POST", battle.single().method)
        assertEquals(listOf("safeid"), battle.single().fields.map { it.name })
    }

    @Test fun claimOnlyConfirmsWhenReadbackNoLongerOffersTheSameEntry() = runBlocking<Unit> {
        assertEquals(StatusKind.COMPLETED,
            parser.parse(rewardPage(includeClaim = false, status = "已领取"), "$origin/kf_fw_1wkfb.php")
                .nativePage?.statusTimeline?.entries?.singleOrNull()?.status)
        var pageReads = 0
        val service = HttpForumService(origin, MemoryStore(), Interceptor { chain ->
            val request = chain.request()
            val html = if (request.method == "GET" && request.url.toString().contains("kf_fw_1wkfb.php?do=1")) {
                "<div class='dcol' style='width:900px'><p>站点回执</p></div>"
            } else {
                pageReads++
                rewardPage(includeClaim = pageReads == 1, status = if (pageReads == 1) "有评分记录" else "已领取")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("test")
                .body(html.toResponseBody()).build()
        })
        try {
            val action = parser.parse(rewardPage(), "$origin/kf_fw_1wkfb.php").nativePage!!.rewards.single()
            assertEquals("$origin/kf_fw_1wkfb.php", action.readbackUrl)
            val result = service.executeReward(action)
            assertIs<RewardExecutionResult.Confirmed>(result, "${result.toString()} pageReads=$pageReads")
        } finally { service.close() }
    }

    @Test fun unchangedClaimOrA200WithoutStateChangeIsUncertainAndAjaxBattleNeverClaimsVictory() = runBlocking<Unit> {
        var reads = 0
        val service = HttpForumService(origin, MemoryStore(), Interceptor { chain ->
            val request = chain.request()
            val html = when {
                request.url.toString().contains("kf_fw_ig_intel.php") -> "<div>战斗日志</div>"
                request.url.toString().contains("kf_fw_ig_index.php") -> { reads++; battleHtml }
                else -> { reads++; rewardPage(includeClaim = true) }
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("test")
                .body(html.toResponseBody()).build()
        })
        try {
            val claim = parser.parse(rewardPage(), "$origin/kf_fw_1wkfb.php").nativePage!!.rewards.single()
            assertIs<RewardExecutionResult.Uncertain>(service.executeReward(claim))
            val battle = parser.parse(battleHtml, "$origin/kf_fw_ig_index.php").nativePage!!.rewards.single()
            assertIs<RewardExecutionResult.Uncertain>(service.executeReward(battle))
            assertEquals(4, reads)
        } finally { service.close() }
    }

    @Test fun malformedOrTamperedRewardProtocolIsRejectedBeforeNetwork() = runBlocking<Unit> {
        var requests = 0
        val service = HttpForumService(origin, MemoryStore(), Interceptor { requests++; error("no network") })
        try {
            val claim = parser.parse(rewardPage(), "$origin/kf_fw_1wkfb.php").nativePage!!.rewards.single()
            assertIs<RewardExecutionResult.Rejected>(service.executeReward(claim.copy(endpoint = "$origin/job.php?action=buytopic")))
            assertIs<RewardExecutionResult.Rejected>(service.executeReward(claim.copy(protocol = RewardProtocol.AJAX_POST,
                endpoint = "$origin/kf_fw_1wkfb.php?do=1&safeid=synthetic&ptid=123", fields = listOf(FormValue("safeid", "synthetic")))))
            assertEquals(0, requests)
        } finally { service.close() }
    }
}

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
        <a href="javascript:;" onclick="jgjg('1')">战斗（前进一层楼）</a>
        <a href="javascript:;" onclick="jgjg('99')">战斗（前进至结束）</a>
        <a href="javascript:;" onclick="$(wdsx).toggle();">打开/关闭个人属性/装备</a>
        <a href="javascript:;" onclick="jgtz('1')">暂停（在本层等待）</a></div>"""
    private fun ratingPage(status: String, includeLink: Boolean = true) = """<div id='kf_topuser'>合成用户</div><div id='kf_information'><a href='login.php?action=quit&amp;verify=synthetic'>退出</a></div><div class='dcol' style='width:900px'>
        <table><tr><td>2026-01-01 10:00</td><td><a href='read.php?tid=1'>合成主题</a></td><td>$status</td>
        <td>${if (includeLink) "<a href='kf_fw_1wkfb.php?do=1&amp;safeid=synthetic&amp;ptid=1'>获取评分奖励</a>" else ""}</td></tr></table></div>"""

    @Test fun preparationOnlyExposesTheVerifiedSingleStepAndRatingEntry() {
        val unrated = parser.parse(ratingPage("未评分帖子"), "$origin/kf_fw_1wkfb.php").nativePage!!
        // The site's do=1 link opens that post's rating workflow; it is not a
        // one-shot reward claim and must never be replayed as one.
        assertTrue(unrated.rewards.isEmpty(), "rating entries must not be advertised as reward claims")
        val entry = unrated.statusTimeline!!.entries.single()
        assertEquals(StatusKind.AVAILABLE, entry.status)
        assertEquals("进入评分", entry.action?.title)
        assertEquals(SiteEffect.CHANGE, entry.action?.effect)
        assertTrue(entry.action?.explanation?.contains("评分会话") == true,
            "the confirmation must explain that the entry opens a rating session")
        assertEquals(0, unrated.unsupported.size)

        val completed = parser.parse(ratingPage("有评分记录"), "$origin/kf_fw_1wkfb.php").nativePage!!
        val completedEntry = completed.statusTimeline!!.entries.single()
        assertEquals(StatusKind.COMPLETED, completedEntry.status)
        assertNull(completedEntry.action, "a completed rating offers no further action on the site")

        val battle = parser.parse(battleHtml, "$origin/kf_fw_ig_index.php").nativePage!!.rewards
        assertEquals(1, battle.size)
        assertEquals("battle-step", battle.single().id)
        assertEquals("POST", battle.single().method)
        assertEquals(listOf("safeid"), battle.single().fields.map { it.name })
        assertTrue(parser.parse(battleHtml, "$origin/kf_fw_ig_index.php").nativePage!!.unsupported.isEmpty(),
            "controls owned by the battle adapter or rendered locally must not be advertised as unavailable")
    }

    @Test fun ratingEntryOpensTheSitesOwnRatingPageThroughTheChangeFlow() = runBlocking<Unit> {
        val requested = mutableListOf<String>()
        val ratingForm = """<div id='kf_topuser'>合成用户</div><div id='kf_information'><a href='login.php?action=quit&amp;verify=synthetic'>退出</a></div>
            <div class='dcol' style='width:900px'><form action='kf_fw_1wkfb.php?do=1' method='post'>
            <input type='hidden' name='safeid' value='synthetic' />
            <input type='hidden' name='ptid' value='1' />
            <select name='fvol'><option value='0'>0</option><option value='1'>1</option></select>
            <textarea name='fpost'></textarea><input type='submit' name='submit' value='提交' /></form></div>"""
        val service = HttpForumService(origin, MemoryStore(), Interceptor { chain ->
            requested.add(chain.request().url.toString())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("test")
                .body(ratingForm.toResponseBody()).build()
        })
        try {
            val page = parser.parse(ratingPage("未评分帖子"), "$origin/kf_fw_1wkfb.php").nativePage!!
            val action = page.statusTimeline!!.entries.single().action!!
            val result = service.executeSite(service.destination(action.url, action.title))
            assertIs<SiteActionResult.Response>(result)
            assertEquals(1, requested.size)
            assertTrue(requested.single().contains("kf_fw_1wkfb.php?do=1"))
            assertTrue(result.page.nativePage?.forms.orEmpty().any { form ->
                form.hidden.any { it.name == "ptid" } || form.fields.any { it.name == "ptid" }
            }, "the site's own rating form must be parsed for native rendering")
        } finally { service.close() }
    }

    @Test fun unchangedBattleStepIsUncertainAndNeverClaimsVictory() = runBlocking<Unit> {
        var reads = 0
        val service = HttpForumService(origin, MemoryStore(), Interceptor { chain ->
            reads++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("test")
                .body(battleHtml.toResponseBody()).build()
        })
        try {
            val battle = parser.parse(battleHtml, "$origin/kf_fw_ig_index.php").nativePage!!.rewards.single()
            assertIs<RewardExecutionResult.Uncertain>(service.executeReward(battle))
            assertEquals(2, reads)
        } finally { service.close() }
    }

    @Test fun malformedBattleProtocolIsRejectedBeforeNetwork() = runBlocking<Unit> {
        var requests = 0
        val service = HttpForumService(origin, MemoryStore(), Interceptor { requests++; error("no network") })
        try {
            val battle = parser.parse(battleHtml, "$origin/kf_fw_ig_index.php").nativePage!!.rewards.single()
            assertIs<RewardExecutionResult.Rejected>(service.executeReward(battle.copy(endpoint = "$origin/job.php?action=buytopic")))
            assertIs<RewardExecutionResult.Rejected>(service.executeReward(battle.copy(fields = listOf(FormValue("other", "x")))))
            assertEquals(0, requests)
        } finally { service.close() }
    }
}
package work.miaola.kf

import kotlin.test.*

class StatusTimelineTest {
    private val origin = "https://kf.miaola.work"
    private val parser = ForumHtmlParser(origin)

    @Test fun rewardRowsMapOnlyExplicitStatusesAndKeepUnknownUnknown() {
        val html = """<div class='dcol' style='width:900px'><table>
            <tr><td>2026-01-01 10:00</td><td><a href='read.php?tid=1'>已完成奖励</a></td><td>已完成</td></tr>
            <tr><td>2026-01-02 10:00</td><td><a href='read.php?tid=2'>待处理奖励</a></td><td>待检查</td></tr>
            <tr><td>2026-01-03 10:00</td><td><a href='read.php?tid=3'>可领取奖励</a></td><td><a href='kf_fw_1wkfb.php?do=1&amp;safeid=synthetic&amp;ptid=9'>获取评分奖励</a></td></tr>
            <tr><td>2026-01-04 10:00</td><td><a href='read.php?tid=4'>普通记录</a></td><td>有评分记录</td></tr>
            </table></div>"""
        val entries = parser.parse(html, "$origin/kf_fw_1wkfb.php").nativePage!!.statusTimeline!!.entries
        assertEquals(listOf(StatusKind.COMPLETED, StatusKind.WAITING, StatusKind.AVAILABLE, StatusKind.UNKNOWN),
            entries.map { it.status })
        assertEquals("9", entries[2].action?.url?.let { Regex("ptid=([0-9]+)").find(it)?.groupValues?.get(1) })
    }

    @Test fun completedDetailLinkIsReadOnlyAndClaimLinkRemainsAChange() {
        val policy = SiteRoutePolicy(origin)
        assertEquals(SiteEffect.READ, policy.classify("$origin/kf_fw_1wkfb.php?do=2&safeid=synthetic&id=9").effect)
        assertEquals(SiteEffect.CHANGE, policy.classify("$origin/kf_fw_1wkfb.php?do=1&safeid=synthetic&ptid=9").effect)
        assertFalse(AuditReadPolicy(origin).allows("$origin/kf_fw_1wkfb.php?do=1&safeid=synthetic&ptid=9"))
        assertTrue(AuditReadPolicy(origin).allows("$origin/kf_fw_1wkfb.php?do=2&safeid=synthetic&id=9"))
    }

    @Test fun growthTimelineUsesClaimedMarkerInsteadOfInventingACompletedReward() {
        val html = """<div class='dcol' style='width:900px'>
            <div style='line-height:60px'><b>3</b> 级 <b>12</b></div>
            <div style='line-height:48px'><a href='kf_growup.php?ok=1&amp;safeid=synthetic'>签到</a></div>
            </div>"""
        val native = parser.parse(html, "$origin/kf_growup.php").nativePage!!
        assertEquals(StatusKind.AVAILABLE, native.statusTimeline!!.entries.single().status)
    }
}

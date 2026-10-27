package work.miaola.kf

import kotlin.test.*

class BankLogParserTest {
    private val origin = "https://kf.miaola.work"
    private val parser = ForumHtmlParser(origin)
    private fun page(rows: String, url: String = "$origin/hack.php?H_name=bank&action=log") = parser.parse(
        "<div class='dcol' style='width:900px'><table class='bank1'><tr><td colspan='5'>" +
            "<a href='hack.php?H_name=bank'>返回</a></td></tr>" +
            "<tr><td>ID</td><td>类型</td><td>描述</td><td>操作日期</td><td>IP</td></tr>$rows</table></div>", url)

    @Test fun outgoingAndIncomingLogsKeepExplicitDirectionAndAmount() {
        val rows = """
            <tr><td>1</td><td>转帐</td><td><b>合成甲</b>使用转帐功能，转帐给<b>合成乙</b>金额：5贡献，转帐附言：谢谢</td><td>2026-01-02 03:04</td><td>203.0.113.1</td></tr>
            <tr><td>2</td><td>转帐</td><td><b>合成丙</b>使用转帐功能，转帐给<b>合成甲</b>金额：0.5贡献，转帐附言：</td><td>2026-01-01 10:00</td><td>203.0.113.2</td></tr>"""
        val out = page(rows).nativePage!!.bankLog!!
        assertEquals(BankDirection.OUT, out.entries[0].direction)
        assertEquals("合成乙", out.entries[0].counterparty)
        assertEquals("5", out.entries[0].amount)
        assertEquals("贡献", out.entries[0].currency)
        assertEquals("谢谢", out.entries[0].memo)
        val incoming = page(rows, "$origin/hack.php?H_name=bank&action=log&to=1").nativePage!!.bankLog!!
        assertEquals(BankDirection.IN, incoming.entries[0].direction)
        assertEquals("合成甲", incoming.entries[0].counterparty)
        assertEquals("合成丙", incoming.entries[1].counterparty)
        assertEquals("0.5", incoming.entries[1].amount)
        assertTrue(out.label.contains("只读"))
    }

    @Test fun missingFieldsStayUnknownAndMalformedRowsArePreserved() {
        val rows = """
            <tr><td>1</td><td>转帐</td><td><b>合成甲</b>使用转帐功能，转帐给<b>合成乙</b>，没有金额</td><td>2026-01-02 03:04</td><td>203.0.113.1</td></tr>
            <tr><td>2</td><td>其他</td><td>无法识别的记录</td><td>不是日期</td><td>203.0.113.1</td></tr>"""
        val log = page(rows).nativePage!!.bankLog!!
        assertEquals("", log.entries.single().amount)
        assertEquals(BankDirection.OUT, log.entries.single().direction)
        assertEquals(1, log.unparsedRows.size)
        assertTrue(log.unparsedRows.single().joinToString().contains("无法识别"))
    }

    @Test fun bankLogIsReadOnlyButMutationParametersRemainChanges() {
        val policy = SiteRoutePolicy(origin)
        assertEquals(SiteEffect.READ, policy.classify("$origin/hack.php?H_name=bank&action=log").effect)
        assertEquals(SiteEffect.READ, policy.classify("$origin/hack.php?H_name=bank&action=log&to=1").effect)
        assertEquals(SiteEffect.READ, policy.classify("$origin/hack.php?H_name=bank&action=log&page=2").effect)
        assertEquals(SiteEffect.CHANGE, policy.classify("$origin/hack.php?H_name=bank&action=virement").effect)
        assertEquals(SiteEffect.CHANGE, policy.classify("$origin/hack.php?H_name=bank&action=log&to=2").effect)
        assertFalse(AuditReadPolicy(origin).allows("$origin/hack.php?H_name=bank&action=virement"))
        assertTrue(AuditReadPolicy(origin).allows("$origin/hack.php?H_name=bank&action=log&to=1"))
    }
}

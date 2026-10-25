package work.miaola.kf

import kotlin.test.*

class NativeTaskParserTest {
    private val origin = "https://kf.miaola.work"
    private val parser = ForumHtmlParser(origin)
    private fun main(body: String) = "<div class='dcol' style='width:900px'>$body</div><div class='rightdiv'><a href='read.php?tid=999'>侧栏</a></div>"

    @Test fun favoritesAreBoundToCheckboxesRegardlessOfUrlParameterOrder() {
        val body = """<a href='profile.php?action=favor&amp;type=1'>示例目录</a>
            <form action='profile.php?action=favor' method='post'><table>
            <tr><th>主题</th><th>版块</th><th>作者</th><th></th></tr>
            <tr><td><a href='read.php?fid=2&amp;tid=1&amp;sf=synthetic'>真正的收藏</a></td><td>板块</td><td>作者</td><td><input name='delid[]' type='checkbox' value='1'></td></tr>
            </table><input name='job' type='radio' value='change'>移动<select name='type'><option value='1'>示例目录</option></select>
            <input name='job' type='radio' value='clear'>删除<input type='submit' value='执行'></form>"""
        val page = parser.parse(main(body), "$origin/profile.php?action=favor")
        assertEquals(listOf("真正的收藏"), page.topics.map { it.title })
        val favorites = page.nativePage!!.favorites!!
        assertEquals("示例目录", favorites.categories.single().title)
        val field = favorites.entries.single().selectionFieldId
        assertNotNull(field)
        assertTrue(page.nativePage.forms.single().fields.any { it.id == field && it.name == "delid[]" })
        assertTrue(parser.parse(main(body), "$origin/profile.php?action=show&uid=1").topics.isEmpty())
        assertTrue(parser.parse(main(body), "$origin/kf_growup.php").topics.isEmpty())
    }

    @Test fun messageHasStructuredParticipantsReadStateAndShortBodyNotLongestCell() {
        val listing = """<table><tr><th>ID</th><th>标题</th><th>发件人</th><th>收件人</th><th>时间</th><th>已读</th></tr>
            <tr><td>1</td><td><a href='message.php?action=read&amp;mid=1'>示例消息</a></td><td><a href='profile.php?action=show&amp;uid=2'>发件人</a></td><td>收件人</td><td>2026-01-01</td><td>否</td></tr></table>"""
        val summary = parser.parse(main(listing), "$origin/message.php").nativePage!!.messages.single()
        assertEquals("发件人", summary.sender); assertEquals("收件人", summary.recipient); assertTrue(summary.unread)
        val tracking = listing.replace("action=read", "action=readscout").replace("<td>否</td>", "<td>选择</td><td>否</td><td></td>")
        val tracked = parser.parse(main(tracking), "$origin/message.php?action=scout").nativePage!!.messages.single()
        assertEquals("收件人", tracked.recipient); assertTrue(tracked.unread)
        assertEquals(SiteEffect.CHANGE, SiteRoutePolicy(origin).classify(tracked.url).effect, "Unverified tracking detail must stay conservative")
        val detail = """<table><tr><td>发件人</td><td>示例发件人</td></tr>
            <tr><td>标题</td><td>这是比消息正文长得多的标题，不应当用它替换正文。</td></tr><tr><td>时间</td><td>2026-01-01</td></tr>
            <tr><td>正文</td><td><b>短正文</b></td></tr>
            <tr><td>操作</td><td><a href='message.php?action=write&amp;remid=1'>回复</a><a href='message.php?action=del&amp;mid=1'>删除</a></td></tr></table>"""
        val message = parser.parse(main(detail), "$origin/message.php?action=read&mid=1").nativePage!!.message!!
        assertEquals("示例发件人", message.sender); assertTrue(message.html.contains("<b>短正文</b>"))
        assertFalse(message.html.contains("标题")); assertNotNull(message.reply)
        assertEquals(SiteEffect.CHANGE, message.actions.last().effect)
    }

    @Test fun nextPageMustBelongToTheSameList() {
        val body = "<a href='read.php?tid=2&amp;page=2'>别的帖子</a><a href='read.php?tid=1&amp;page=3'>当前帖子</a>"
        assertEquals("$origin/read.php?tid=1&page=3", parser.parse(body, "$origin/read.php?tid=1").next)
    }

    @Test fun rankingAndRecordPresentationsKeepEveryMetricAndAction() {
        val body = """<table><tr><th>排名</th><th>用户</th><th>KFB</th><th>贡献</th><th>帖子</th><th>等级</th></tr>
            <tr><td>1</td><td><a href='profile.php?action=show&amp;uid=1'>示例会员</a></td><td>100</td><td>2</td><td>3</td><td>4</td></tr></table>"""
        val table = parser.parse(main(body), "$origin/kf_no1.php").nativePage!!.tables.single()
        val record = table.records(true).single()
        assertEquals("#1", record.title); assertEquals("示例会员", record.primary.text)
        assertEquals(listOf("KFB", "贡献", "帖子", "等级"), record.details.map { it.label })
        assertNotNull(record.primary.link)
        val actions = parser.parse(main("<table><tr><th>日期</th><th>操作</th><th>状态</th></tr><tr><td>2026-01-01</td><td><a href='read.php?tid=1'>帖子</a><a href='kf_fw_1wkfb.php?do=1'>领取</a></td><td>未领取</td></tr></table>"), "$origin/kf_fw_1wkfb.php").nativePage!!.tables.single().records().single().actions
        assertEquals(2, actions.size); assertEquals(SiteEffect.CHANGE, actions.last().effect)
    }

    @Test fun unknownPagesKeepTheirStructuredFallback() {
        val page = parser.parse(main("<form method='post'><input name='unknown'><input type='submit'></form><button onclick='unknownProtocol()'>未知操作</button>"), "$origin/unknown.php").nativePage!!
        assertEquals(1, page.forms.size); assertTrue(page.unsupported.isNotEmpty())
        assertNull(page.message); assertNull(page.favorites); assertNull(page.bank)
    }

    @Test fun bankMetricsSeparateAmountsFromLongRules() {
        val page = parser.parse(main("<table class='bank1'><tr><td>银行</td><td>当前所持：100KFB<br>定期存款：20KFB（存款规则说明）<br>定期利息：0.07%</td></tr></table>"), "$origin/hack.php?H_name=bank").nativePage!!
        assertEquals(listOf("100KFB", "20KFB", "0.07%"), page.bank!!.balances.map { it.value })
        assertTrue(page.bank.rules.any { it.contains("存款规则说明") })
    }

    @Test fun boardFiltersComeFromSameBoardReadLinksOnly() {
        val page = parser.parse(main("<a href='thread.php?fid=1&amp;type=2'>讨论</a><a href='thread.php?fid=2&amp;type=2'>其他版块</a><a href='thread.php?fid=1&amp;type=2&amp;del=1'>危险</a>"), "$origin/thread.php?fid=1")
        assertEquals(listOf("讨论"), page.boardFilters.map { it.title })
    }
}

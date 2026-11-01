package work.miaola.kf

import kotlin.test.*
import okio.Buffer
import java.nio.file.Files
import java.nio.file.Path

class NativeSiteTest {
    private val origin = "https://kf.miaola.work"
    private val parser = ForumHtmlParser(origin)
    @Test fun homeKeepsRecommendedAndNewTopicsIndependent() {
        val html = """<div class="rightboxa"><span class="k_f18">最新被推帖</span><a href="read.php?tid=1"><span class="righttita">推荐主题</span><span class="k_fr">5分钟前</span></a></div>
            <div class="rightboxa"><span class="k_f18">最新主题帖</span><a href="read.php?tid=1"><span class="righttita">同一主题也刚发布</span><span class="k_fr">10分钟前</span></a><a href="read.php?tid=2"><span class="righttita">新主题</span></a></div>"""
        val feeds = parser.parse(html, "$origin/index.php").feeds
        assertEquals(listOf("最新被推帖", "最新主题帖"), feeds.map { it.title })
        assertEquals(1, feeds[0].topics.size); assertEquals(2, feeds[1].topics.size)
        assertEquals("5分钟前", feeds.first().topics.first().subtitle)
        assertEquals("推荐主题", feeds.first().topics.first().title)
    }
    @Test fun homeRelativeTimeLeavesTitleAndBecomesSubtitle() {
        val html = """<div class="rightboxa"><span class="k_f18">最新主题帖</span><a href="read.php?tid=1" title="聊天 双十一求推荐手机"><span class="k_fr">02分钟前</span></a></div>
            <div class="rightboxa"><span class="k_f18">最新被推帖</span><a href="read.php?tid=2">日记 固态硬盘出0e了<span class="k_fr">234分钟前</span></a></div>"""
        val topics = parser.parse(html, "$origin/index.php").feeds.flatMap { it.topics }
        val plain = topics.first { it.title.startsWith("聊天") }
        assertEquals("聊天 双十一求推荐手机", plain.title)
        assertEquals("02分钟前", plain.subtitle)
        val nested = topics.first { it.title.startsWith("日记") }
        assertEquals("日记 固态硬盘出0e了", nested.title)
        assertEquals("234分钟前", nested.subtitle)
    }
    @Test fun growupPageBecomesStructuredGrowthData() {
        val html = """<div class="dcol" style="width:900px">
            <div style="line-height:60px;font-size:14px;text-align:center;color:#C30">当前神秘 [<b>100</b>] 级 | 神秘系数 3 | 当前成长经验值 [<b>3000</b>] 点</div>
            <div style="text-align:center"><span style="color:#804000;font-size:14px;font-weight:bold">升级经验已完成 35%</span></div>
            <div style="width:860px;border:1px solid #9999ff"><table width="860"><tr>
            <td style="background-color:#6161FD;text-align:center"><a href="kf_growup.php?ok=2&amp;color=1" style="color:#FFFFFF">选择该颜色</a></td>
            <td style="background-color:#009CFF;text-align:center"><a href="kf_growup.php?ok=2&amp;color=2" style="color:#FFFFFF">选择该颜色</a></td>
            <td style="background-color:#CED83C;text-align:center"><a href="kf_growup.php?ok=2&amp;color=3" style="color:#FFFFFF">选择该颜色</a></td>
            </tr></table></div></div>"""
        val native = parser.parse(html, "$origin/kf_growup.php").nativePage!!
        val growth = native.growth!!
        assertEquals("签到与成长", native.title)
        assertEquals("100", growth.level)
        assertEquals("3", growth.coefficient)
        assertEquals("3000", growth.experience)
        assertEquals(35, growth.progress)
        assertEquals(3, growth.colors.size)
        assertEquals("#6161fd", growth.colors.first().color)
        assertTrue(growth.colors.first().url.contains("color=1"))
    }

    @Test fun growthStatusComesFromTheActionHrefNotVisibleText() {
        fun html(statusHref: String, statusText: String) = """<div class="dcol" style="width:900px">
            <div style="line-height:60px;font-size:14px;text-align:center;color:#C30">A [<b>10</b>] B 3 C [<b>20</b>] D</div>
            <div><span style="color:#804000;font-size:14px;font-weight:bold">40%</span></div>
            <div style="line-height:48px;font-size:20px;text-align:center;border:1px solid #666">$statusText<a href="$statusHref">$statusText</a></div>
            <table><tr>
            <td style="background-color:#6161FD"><a href="kf_growup.php?ok=2&amp;color=1">c1</a></td>
            <td style="background-color:#009CFF"><a href="kf_growup.php?ok=2&amp;color=2">c2</a></td>
            <td style="background-color:#CED83C"><a href="kf_growup.php?ok=2&amp;color=3">c3</a></td>
            </tr></table></div>"""
        val claimed = parser.parse(html("javascript:;", "STATUS"), "$origin/kf_growup.php").nativePage!!.growth!!
        assertFalse(claimed.claimed)
        assertEquals(StatusKind.UNKNOWN, claimed.claimStatus)
        assertNull(claimed.claimUrl)
        // Actual site copy once today's reward has been collected. There is no
        // anchor in this state; the plain status line is the completion marker.
        val done = parser.parse(
            html("javascript:;", "今天的每日奖励已经领过了，请明天继续。"),
            "$origin/kf_growup.php",
        ).nativePage!!.growth!!
        assertTrue(done.claimed)
        assertEquals(StatusKind.COMPLETED, done.claimStatus)
        assertNull(done.claimUrl)
        val open = parser.parse(html("kf_growup.php?ok=1&amp;safeid=x", "STATUS"), "$origin/kf_growup.php").nativePage!!.growth!!
        assertFalse(open.claimed)
        assertEquals(StatusKind.AVAILABLE, open.claimStatus)
        assertNotNull(open.claimUrl)
        assertEquals("10", open.level)
        assertEquals("3", open.coefficient)
        assertEquals("20", open.experience)
        assertEquals(40, open.progress)
    }

    @Test fun progressPenaltiesUseTheDedicatedDeductionBlockOnly() {
        val html = """<div class="dcol" style="width:900px">
            <div class="k_ale k_tr"><span class="k_butt k_blk"><span class="k_f32">当前进度分：<span class="k_f32">150</span></span><br>
            未达 90分 权限-只能停留在首页<br>规则示例：已完成、未领取</span></div>
            <div class="k_ale k_tr"><span class="k_butt k_blk"><br>
            <span class="k_f18">我的扣分记录（异议请至<a href="thread.php?fid=4">管理区</a>发帖）</span><br><hr>
            <span class="k_f14" style="color:#ff3333;">[4年前][扣40进度分][回帖]<a href="read.php?tid=954069&amp;sf=aa7#16457896">《示例主题》</a></span><br>扣分原因：广告等_第1条（）<br><hr>
            <span class="k_f14" style="color:#ff3333;">[2年前][扣10进度分][主题]<a href="read.php?tid=2">《另一个主题》</a></span><br>扣分原因：重复发帖<br><hr>
            </span></div>
            <p>规则正文里的 已完成 与 未领取 不应成为状态</p></div>"""
        val progress = parser.parse(html, "$origin/kf_lv200.php").nativePage!!.progress!!
        assertEquals("150", progress.stats.single().value)
        assertEquals(2, progress.penalties.size)
        val first = progress.penalties[0]
        assertEquals("4年前", first.time)
        assertEquals("扣40进度分", first.points)
        assertEquals("回帖", first.action)
        assertEquals("《示例主题》", first.title)
        assertEquals("广告等_第1条（）", first.reason)
        assertTrue(first.url.contains("read.php?tid=954069"))
        assertEquals("重复发帖", progress.penalties[1].reason)
        assertEquals("《另一个主题》", progress.penalties[1].title)
    }

    @Test fun progressPageWithoutDeductionBlockKeepsScoreOnly() {
        val html = """<div class="dcol" style="width:900px">
            <div class="k_ale k_tr"><span>当前进度分：<span class="k_f32">120</span><br>达到 120分 权限-可发表帖子</span></div></div>"""
        val progress = parser.parse(html, "$origin/kf_lv200.php").nativePage!!.progress!!
        assertEquals("120", progress.stats.single().value)
        assertTrue(progress.penalties.isEmpty())
    }

    @Test fun normalMentionResultTableKeepsTimeTypeTitleAndAuthorLinks() {
        val html = """<div class="dcol" style="width:900px">
            <table class="kf_share1"><tr><td colspan="7">含有关键词 “示例” 的内容</td></tr></table>
            <table class="kf_share1">
            <tr><td width="80" class="kf_share2"><span style="color:#FF0000;">时间</span></td>
            <td width="100" class="kf_share2"><span style="color:#FF0000;">类型</span></td>
            <td width="500"><span style="color:#FF0000;">&nbsp;标题</span></td>
            <td width="100" class="kf_share2"><span style="color:#FF0000;">发表人</span></td></tr>
            <tr><td>2026-11-01 09:00</td><td>回复</td>
            <td><a href="read.php?tid=123&amp;pid=456">《示例主题》</a></td>
            <td><a href="profile.php?uid=789">示例用户</a></td></tr>
            </table></div>"""
        val page = parser.parse(html, "$origin/guanjianci.php?gjc=示例").nativePage!!
        val table = page.tables.first { it.header }
        assertEquals(listOf("时间", "类型", "标题", "发表人"), table.rows.first().map { it.text })
        val record = table.rows[1]
        assertEquals("2026-11-01 09:00", record[0].text)
        assertEquals("回复", record[1].text)
        assertEquals("《示例主题》", record[2].text)
        assertTrue(record[2].link.orEmpty().contains("read.php?tid=123"))
        assertEquals("示例用户", record[3].text)
        assertTrue(record[3].link.orEmpty().contains("profile.php?uid=789"))
    }

    @Test fun genericPageKeepsReadOnlyDisplayValuesButNotEditableFields() {
        val html = """<div class="dcol" style="width:900px">
            <table class="kf_fw_ig3"><tr>
            <td><input size="26" value="1317攻击力" type="text" readonly></td>
            <td><input size="26" value="0/6100生命值" type="text" readonly></td>
            <td><input type="text" name="keyword" placeholder="搜索"></td>
            </tr></table>
            <div class="line">战斗说明</div></div>"""
        val native = parser.parse(html, "$origin/kf_fw_ig_index.php").nativePage!!
        val text = buildString {
            native.blocks.forEach { block -> when (block) {
                is NativeBlock.Section -> append(block.text.ifBlank { block.html }).append(' ')
                is NativeBlock.Table -> block.table.rows.flatten().forEach { append(it.text).append(' ') }
                is NativeBlock.Links -> Unit
            } }
            native.links.forEach { append(it.title).append(' ') }
        }
        assertTrue(text.contains("1317攻击力"), "readonly value must survive generic cleanup")
        assertTrue(text.contains("0/6100生命值"), "readonly value must survive generic cleanup")
        assertFalse(text.contains("keyword"), "editable inputs must not leak as content")
    }

    @Test fun readerExposesSiteFavoriteAction() {
        val html = """<div class="readtext" id="pidtpc"><table><tr><td width="190"><div class="readidms"><a href="profile.php?action=show&amp;uid=1">作者</a></div></td>
            <td width="990"><div><p>正文</p></div></td></tr></table></div>
            <a href="kf_tidfavor.php?action=favor&amp;tid=1&amp;sf=abc">收藏本帖</a>"""
        val page = parser.parse(html, "$origin/read.php?tid=1")
        assertEquals("收藏本帖", page.favorite?.title)
        assertEquals(SiteEffect.CHANGE, page.favorite?.effect)
        assertTrue(page.favorite?.url.orEmpty().contains("kf_tidfavor.php"))
    }

    @Test fun profileIsNativeStructuredData() {
        val html = """<div class="dcol" style="width:900px"><table class="log1"><tr><td>示例会员 详细信息</td></tr><tr><td><img class="pic" src="avatars/demo.png"></td><td>用户名称：示例会员<br>会员等级：初回限定版<br>论坛货币：100 KFB</td></tr>
            <tr><td>[<a href="search.php?authorid=1">用户主题</a>] [<a href="search.php?authorid=1&amp;sch_area=2">用户回复</a>]</td></tr></table></div>"""
        val native = parser.parse(html, "$origin/profile.php?action=show&uid=1").nativePage!!
        assertEquals("示例会员", native.profile?.name)
        assertTrue(native.profile!!.info.any { it.label == "会员等级" })
        assertTrue(native.profile.avatar.startsWith(origin))
        assertEquals(SiteEffect.READ, native.profile.links.single { it.title == "用户主题" }.effect)
        assertEquals(SiteEffect.READ, native.profile.links.single { it.title == "用户回复" }.effect)
    }

    @Test fun profileHeaderAndStatsComeFromTheCentreCellOrder() {
        val facts = listOf(
            "用户名称：示例会员", "数字序列：123456", "会员等级：初回限定版", "发帖数量：12 帖",
            "神秘等级：100 级", "神秘系数：3", "论坛货币：3456 KFB", "贡献数值：7.8",
            "注册时间：2024-01-01", "在线时间：3 小时", "日均发帖：0 篇",
        ).joinToString("<br>")
        val html = """<div class="dcol" style="width:900px"><table class="log1">
            <tr><td>示例会员 详细信息</td><td></td><td></td></tr>
            <tr><td><img class="pic" src="avatars/demo.png"></td><td>$facts</td><td>个性签名：示例</td></tr>
            </table></div>"""
        val profile = parser.parse(html, "$origin/profile.php?action=show&uid=1").nativePage!!.profile!!
        assertEquals("示例会员", profile.name)
        assertEquals("初回限定版", profile.headline?.value)
        assertEquals(listOf("12 帖", "3456 KFB", "7.8"), profile.stats.map { it.value })
    }
    @Test fun readerAuthorDetailPageUsesTheNativeProfileLayout() {
        val html = """<div class="dcol" style="width:900px"><table class="log1" cellspacing="0"><tr><td>示例会员 详细信息</td><td></td><td></td></tr>
            <tr><td><img class="pic" src="1793519200/upload/95/1.jpg" width="140" height="200"></td>
            <td>用户名称：示例会员<br>数字序列：1<br>会员等级：初回限定版<br>发帖数量：5 帖<br>论坛货币：100 KFB<br>注册时间：2024-01-01<br>在线时间：2 小时</td>
            <td valign="top">个性签名：示例签名</td></tr>
            <tr><td>[<a href="message.php?action=write&amp;touid=1">发送短信</a>] [<a href="search.php?authorid=1">用户主题</a>]<br>
            [<a href="show.php?uid=1">所传附件</a>] [<a href="search.php?authorid=1&amp;sch_area=2" id="sch_post">用户回复</a>]</td></tr></table></div>"""
        val native = parser.parse(html, "$origin/show.php?uid=1").nativePage!!
        val profile = native.profile!!
        assertEquals("示例会员", profile.name)
        assertTrue(profile.avatar.startsWith(origin))
        assertTrue(profile.info.any { it.label == "论坛货币" && it.value == "100 KFB" })
        assertEquals(setOf("发送短信", "用户主题", "用户回复"), profile.links.map { it.title }.toSet())
        assertFalse(profile.links.any { it.url == "$origin/show.php?uid=1" }, "the self link must not repeat the current page")
        assertTrue(native.unsupported.isEmpty())
        assertTrue(native.blocks.none { it is NativeBlock.Section && it.html.contains("用户名称：示例会员") },
            "the detail table must not also be dumped as a raw section")
    }

    @Test fun homeQuickEntriesRemainInNativeLinksButTopicsDoNot() {
        val html = """<div class="dcol" style="width:900px">
            <a href="kf_growup.php">7174801KFB | 181.9贡献</a>
            <a href="kf_fw_1wkfb.php">自助评分</a>
            <a href="kf_fw_ig_index.php">争夺奖励</a>
            <a href="kf_lv200.php">进度分：150</a>
            <a href="read.php?tid=1">主题</a>
        </div>"""
        val page = parser.parse(html, "$origin/index.php")
        val paths = page.nativePage!!.links.map { it.url.substringBefore('?').substringAfterLast('/') }.toSet()
        assertTrue(paths.containsAll(listOf("kf_growup.php", "kf_fw_1wkfb.php", "kf_fw_ig_index.php", "kf_lv200.php")))
        assertFalse("read.php" in paths)
    }
    @Test fun topNavigationToolsStayReachableWithoutDuplicatingBoards() {
        val html = """<ul>
            <li class="topmenuo3"><a href="index.php">绯月首页</a></li>
            <li class="topmenuo3"><a href="javascript:;">Gal讨论</a><ul class="topmenuo2"><li><a href="thread.php?fid=102">Galgame推荐</a></li></ul></li>
            <li class="topmenuo3"><a href="kf_share.php">最新共享</a></li>
            <li class="topmenuo3"><a href="javascript:;">游戏介绍</a><ul class="topmenuo2"><li><a href="https://fan2d.top/subjects" target="_blank">游戏介绍</a></li></ul></li>
        </ul>
        <table width="1200"><tr>
            <td><a href="fyg_sjcdwj.php?go=play&amp;xl=2">咕咕镇</a></td>
            <td><a href="fyg_sjcdwj.php?go=play&amp;xl=1">咕咕线2</a></td>
            <td><a href="https://galge.fun/" target="_blank">2DFan</a></td>
        </tr></table>"""
        val page = parser.parse(html, "$origin/index.php")
        assertEquals(listOf("最新共享", "游戏介绍", "咕咕镇", "2DFan"), page.siteLinks.map { it.title })
        assertEquals("$origin/kf_share.php", page.siteLinks.first().url)
        assertEquals("$origin/fyg_sjcdwj.php?go=play&xl=2", page.siteLinks[2].url)
        assertEquals("https://fan2d.top/subjects", page.siteLinks[1].url)
        assertEquals(SiteEffect.READ, SiteRoutePolicy(origin).classify(page.siteLinks.first().url).effect)
        assertEquals(SiteEffect.EXTERNAL, SiteRoutePolicy(origin).classify(page.siteLinks[1].url).effect)
    }
    @Test fun checkboxArrayAndRadioInputsPreserveBrowserSemantics() {
        val html = """<div class="dcol" style="width:900px"><form action="message.php" method="post"><input type="hidden" name="towhere" value="receivebox">
            <label><input type="checkbox" name="delid[]" value="1">第一封</label><label><input type="checkbox" name="delid[]" value="2">第二封</label>
            <input type="radio" name="action" value="del" checked>删除<input type="radio" name="action" value="down">下载<input type="submit" value="执行"></form></div>"""
        val form = parser.parse(html, "$origin/message.php").nativePage!!.forms.single()
        assertEquals(2, form.fields.count { it.kind == SiteFieldKind.CHECKBOX })
        assertEquals(1, form.fields.count { it.kind == SiteFieldKind.RADIO })
        val state = form.fields.associate { f -> f.id to if (f.kind == SiteFieldKind.CHECKBOX) f.options.map { it.value } else f.initial }
        val values = buildSiteFormValues(form, state, form.submits.single())
        assertEquals(listOf("1", "2"), values.filter { it.name == "delid[]" }.map { it.value })
        assertEquals(listOf("del"), values.filter { it.name == "action" }.map { it.value })
        assertTrue(gbkFormValues(values).toString(Charsets.US_ASCII).contains("delid%5B%5D=1&delid%5B%5D=2"))
    }
    @Test fun emptyOptionValuesAndPasswordsAreNotReplacedWithLabelsOrServerSecrets() {
        val html = """<form action="profile.php?action=modify" method="post"><input name="oldpwd" type="password" value="do-not-reuse"><select name="type"><option value="">请选择</option><option value="1">分类一</option></select><input type="submit" name="save" value="保存"></form>"""
        val form = parser.parse(html, "$origin/profile.php?action=modify").nativePage!!.forms.single()
        assertEquals(listOf(""), form.fields.first { it.name == "oldpwd" }.initial)
        assertEquals("", form.fields.first { it.name == "type" }.options.first().value)
        assertFalse(form.toString().contains("do-not-reuse"))
    }
    @Test fun crossOriginFormsCannotBeSubmitted() {
        val html = "<form action='https://evil.test/collect' method='post'><input name='pwuser'><input type='submit'></form>"
        assertTrue(parser.parse(html, "$origin/profile.php").nativePage!!.forms.isEmpty())
    }
    @Test fun scriptOnlyButtonsAreReportedInsteadOfSilentlyOpeningAWebView() {
        val html = "<button onclick=\"startUnknownGame(1)\">出战</button><input type='button' value='下一步' onclick=\"location.href='faq.php?id=2'\">"
        val native = parser.parse(html, "$origin/fyg_sjcdwj.php").nativePage!!
        assertTrue("出战" in native.unsupported)
        assertEquals(SiteEffect.READ, native.links.first { it.title == "下一步" }.effect)
    }
    @Test fun readerScriptHelpersForVisibleActionsAreNotReportedAsUnsupported() {
        val html = """<a href="post.php?action=reply&fid=1&tid=2">回复帖子</a>
            <a href="javascript:;" onclick="postreply('回 0楼(demo) 的帖子','demo');">回复</a>
            <a href="javascript:;" onclick="cztz(floor0)">菜单</a>
            <a href="javascript:;" onclick="cztzyx('2','3')">提交优秀</a>
            <a href="javascript:;" onclick="startUnknownGame(1)">出战</a>"""
        val native = parser.parse(html, "$origin/read.php?tid=2").nativePage!!
        assertFalse(native.unsupported.any { it == "回复" || it == "菜单" })
        assertTrue("提交优秀" in native.unsupported)
        assertTrue("出战" in native.unsupported)
    }
    @Test fun nativeOwnedLocalAndEditorControlsAreNotReportedAsUnsupported() {
        val message = parser.parse(
            """<div class="dcol" style="width:900px"><a href="javascript:;" onclick="return checkset();">清空</a></div>""",
            "$origin/message.php",
        ).nativePage!!
        assertTrue(message.unsupported.isEmpty(), "native selection already offers a clear action")

        val reader = parser.parse(
            """<div class="dcol" style="width:900px">
                <a href="post.php?action=reply&fid=1&tid=2">回复帖子</a>
                <a href="javascript:;" onclick="insertSmilie('[高兴]');">[高兴]</a>
                <a href="javascript:;" onclick="postreply('回 0楼(demo) 的帖子','demo');">回复</a>
            </div>""",
            "$origin/read.php?tid=2",
        ).nativePage!!
        assertTrue(reader.unsupported.isEmpty(), "the native editor owns the reply emoticon catalog")

        val editor = parser.parse(
            """<div class="dcol" style="width:900px"><a href="javascript:;" onclick="showDefault();">[更多]</a></div>""",
            "$origin/post.php?fid=52",
        ).nativePage!!
        assertTrue(editor.unsupported.isEmpty(), "the native editor already exposes the full emoticon catalog")
    }
    @Test fun chineseQueryValuesAreReencodedForTheGbkSite() {
        val mentions = parser.resolve("guanjianci.php?gjc=示例", "$origin/index.php")!!
        assertTrue(mentions.contains("gjc=%CA%BE%C0%FD"), mentions)
        assertEquals("$origin/read.php?tid=1", parser.resolve("read.php?tid=1", "$origin/index.php"))
        assertEquals("$origin/guanjianci.php?gjc=%CA%BE%C0%FD",
            parser.resolve("guanjianci.php?gjc=%CA%BE%C0%FD", "$origin/index.php"))
        val mixed = parser.resolve("read.php?tid=1&keyword=中文", "$origin/index.php")!!
        assertTrue(mixed.contains("tid=1"), mixed)
        assertTrue(mixed.contains("keyword=%D6%D0%CE%C4"), mixed)
    }

    @Test fun routePolicyDoesNotTreatMutationsAsReadOnlyNavigation() {
        val routes = SiteRoutePolicy(origin)
        assertEquals(SiteEffect.READ, routes.classify("profile.php?action=modify").effect)
        assertEquals(SiteEffect.READ, routes.classify("message.php?action=read&mid=1").effect)
        assertEquals(SiteEffect.READ, routes.classify("kf_share.php?new=1").effect)
        assertEquals(SiteEffect.READ, routes.classify("kf_share.php?ti=16&new=1").effect)
        assertEquals(SiteEffect.CHANGE, routes.classify("kf_share.php?new=1&del=1").effect)
        assertEquals(SiteEffect.READ, routes.classify("search.php?authorid=688495").effect)
        assertEquals(SiteEffect.READ, routes.classify("search.php?authorid=688495&sch_area=2").effect)
        assertEquals(SiteEffect.READ, routes.classify("search.php?authorid=688495&sch_area=2&page=2").effect)
        assertEquals(SiteEffect.CHANGE, routes.classify("search.php?authorid=abc").effect)
        assertEquals(SiteEffect.CHANGE, routes.classify("search.php?authorid=1&keyword=x").effect)
        assertEquals(SiteEffect.CHANGE, routes.classify("search.php?authorid=1&sch_area=1").effect)
        assertEquals(SiteEffect.CHANGE, routes.classify("message.php?action=del&mid=1").effect)
        assertEquals(SiteEffect.CHANGE, routes.classify("diy_index_bg.php?id=999&safeid=synthetic").effect)
        assertEquals(SiteEffect.DOWNLOAD, routes.classify("job.php?action=download&aid=1").effect)
        assertEquals(SiteEffect.UNSUPPORTED, routes.classify("job.php?action=buytopic&tid=1").effect)
        assertEquals(SiteEffect.EXTERNAL, routes.classify("//example.com/image.jpg").effect)
        assertEquals(SiteEffect.UNSUPPORTED, routes.classify("https://user:password@example.com/").effect)
    }
    @Test fun multipartFieldsAreGbkAndDuplicateNamesAreKept() {
        val file = LocalUpload("test", "图片.png", "image/png", byteArrayOf(1, 2, 3))
        val body = nativeMultipart(listOf(FormValue("caption", "中文"), FormValue("delid[]", "1"), FormValue("delid[]", "2")), listOf(FilePart("attachment_1", file)))
        val buffer = Buffer(); body.writeTo(buffer)
        val bytes = buffer.readByteArray()
        val gbk = gbkBytes("中文")
        assertTrue(bytes.indices.any { i -> i + gbk.size <= bytes.size && bytes.copyOfRange(i, i + gbk.size).contentEquals(gbk) })
        val ascii = bytes.toString(Charsets.ISO_8859_1)
        assertEquals(2, Regex("name=\"delid\\[\\]\"").findAll(ascii).count())
        assertTrue(ascii.contains("filename=\"upload-1.png\""))
        assertTrue(ascii.contains("name=\"attachment_1\""))
    }
    @Test fun recommendationUsesFreshHiddenFieldsNotAnEvaluatedScript() {
        val html = "<input id='safeid' value='synthetic'><input id='tui_tid' value='1'><a id='read_tui' onclick='read_tui()'>推荐</a>"
        val form = parser.parse(html, "$origin/read.php?tid=1").nativePage!!.forms.single { it.id == "rpc:recommend" }
        assertEquals("$origin/diy_read_tui.php", form.actionUrl)
        assertEquals("POST", form.method)
        assertTrue(form.hidden.any { it.name == "safeid" })
    }
    @Test fun keywordsRatingsAndPostActionsLeaveTheProseBody() {
        val html = """<div class="readtext" id="pidtpc"><table><tr><td width="190"><div class="readidms"><a href="profile.php?action=show&amp;uid=1">作者</a></div></td>
            <td width="990"><div><div id="floor0"><a class="readcza" href="post.php?action=modify&amp;fid=5&amp;tid=1&amp;pid=tpc">编辑帖子</a>
            <a class="readcza" href="masingle.php?action=shield&amp;fid=5&amp;tid=1&amp;pid=tpc">[管理]屏蔽</a></div>
            <div><span>楼主</span><span>2026-10-24 12:00</span></div><p>这是正文，不应该被操作按钮污染。</p>
            <fieldset class="read_fds"><legend>关键词</legend><a href="guanjianci.php?gjc=示例">示例关键词</a></fieldset>
            <fieldset class="read_fds"><legend>本帖最近评分记录</legend><b>[违规处罚]-10进度分</b></fieldset>
            <fieldset class="read_fds"><legend>我的其他帖子（本版内）</legend><a href="read.php?tid=2">另一篇帖子</a></fieldset>
            </div></td></tr></table></div>"""
        val post = parser.parse(html, "$origin/read.php?tid=1").posts.single()
        assertTrue(post.html.contains("这是正文"))
        assertFalse(post.html.contains("本帖最近评分记录"))
        assertFalse(post.html.contains("我的其他帖子"))
        assertEquals(listOf("示例关键词"), post.keywords.map { it.title })
        assertEquals(listOf("本帖最近评分记录", "我的其他帖子（本版内）"), post.meta.map { it.title })
        assertTrue(post.actions.any { it.title == "编辑帖子" })
        assertTrue(post.actions.any { it.title == "[管理]屏蔽" && it.effect == SiteEffect.CHANGE })
    }

    @Test fun rankingTableKeepsGridAndInlineStylesBecomeSafeTags() {
        val html = """<div class="dcol" style="width:900px">
            <div style="font-size:18px;color:#FF0080;font-weight:bold">我的神秘系数</div>
            <table class="kf_no11"><tr><td><span style="color:#FF0000">名次</span></td><td>用户</td><td>KFB</td></tr>
            <tr><td>1</td><td><a href="profile.php?action=show&amp;uid=2">第一名</a></td><td>100</td></tr>
            <tr><td>2</td><td>第二名</td><td>90</td></tr></table></div>"""
        val page = parser.parse(html, "$origin/kf_no1.php").nativePage!!
        val table = page.tables.single()
        assertEquals(3, table.rows.size)
        assertEquals(3, table.rows.first().size)
        assertEquals("第一名", table.rows[1][1].text)
        assertEquals("$origin/profile.php?action=show&uid=2", table.rows[1][1].link)
        assertTrue(table.header)
        val body = page.sections.single().html
        assertTrue(body.contains("<font color=\"#FF0080\">"))
        assertTrue(body.contains("<big>"))
        assertTrue(body.contains("<b>"))
        assertFalse(body.contains("font-size"))
        assertFalse(body.contains("<table"))
        assertFalse(body.contains("名次"), "Extracted table must not be duplicated in prose")
    }
    @Test fun smallLayoutLinksMoveToPageActionsInsteadOfAGrid() {
        val html = """<div class="dcol" style="width:900px"><table><tr><td><a href="kf_no1.php?no1=sm">排行</a></td><td><a href="kf_no1.php?no1=cz">KFB</a></td></tr></table></div>"""
        val page = parser.parse(html, "$origin/kf_no1.php").nativePage!!
        assertTrue(page.tables.isEmpty())
        assertEquals(listOf("排行", "KFB"), page.links.map { it.title })
    }

    @Test fun bankLikeTableKeepsCaptionAndDropsFormPromptsFromValues() {
        val html = """<div class="dcol" style="width:900px"><table class="bank1"><tr><td colspan="2" style="font-weight:bold;font-size:18px">转账(贡献)取款(KFB)</td></tr>
            <tr><td>转账贡献</td><td>对方用户名：<input name="pwuser"></td></tr>
            <tr><td>取KFB</td><td>当前所持：100KFB</td></tr></table><div>表格之后</div></div>"""
        val page = parser.parse(html, "$origin/hack.php?H_name=bank").nativePage!!
        val table = page.tables.single()
        assertEquals(NativeTableStyle.DEFINITION, table.style)
        assertEquals("转账(贡献)取款(KFB)", table.caption)
        assertEquals("", table.rows[0][1].text)
        assertTrue(table.rows[1][1].text.contains("当前所持"))
        assertTrue(page.blocks[0] is NativeBlock.Table)
        assertTrue((page.blocks[1] as NativeBlock.Section).html.contains("表格之后"))
    }

    @Test fun navigationStripInsideTableBecomesLinksNotAStrayRow() {
        val html = """<div class="dcol" style="width:900px"><table><tr><td colspan="3"><a href="hack.php?H_name=bank&amp;action=log">我的操作记录</a> |
            <a href="hack.php?H_name=bank&amp;action=log&amp;to=1">对我的操作记录</a> | <a href="hack.php?H_name=bank">返回KFB银行首页</a></td></tr>
            <tr><td>ID</td><td>类型</td><td>描述</td></tr><tr><td>1</td><td>转帐</td><td>一笔记录</td></tr></table></div>"""
        val table = parser.parse(html, "$origin/hack.php?H_name=bank&action=log").nativePage!!.tables.single()
        assertEquals(3, table.links.size)
        assertEquals("ID", table.rows.first().first().text)
        assertTrue(table.header)
    }

    @Test fun colourPaletteKeepsBackgroundsAndLinks() {
        val html = """<div class="dcol" style="width:900px"><table><tr>
            <td style="background-color:#FF0000"><a href="kf_growup.php?ok=2&amp;color=1">选择该颜色</a></td>
            <td style="background-color:#00FF00"><a href="kf_growup.php?ok=2&amp;color=2">选择该颜色</a></td>
            <td style="background-color:#0000FF"><a href="kf_growup.php?ok=2&amp;color=3">选择该颜色</a></td></tr>
            <tr><td style="background-color:#FFFF00"><a href="kf_growup.php?ok=2&amp;color=4">选择该颜色</a></td>
            <td style="background-color:#00FFFF"><a href="kf_growup.php?ok=2&amp;color=5">选择该颜色</a></td>
            <td style="background-color:#FF00FF"><a href="kf_growup.php?ok=2&amp;color=6">选择该颜色</a></td></tr></table></div>"""
        val table = parser.parse(html, "$origin/kf_growup.php").nativePage!!.tables.single()
        assertEquals(NativeTableStyle.PALETTE, table.style)
        assertEquals("#ff0000", table.rows.first().first().background)
        assertNotNull(table.rows.first().first().link)
    }

    @Test fun pureLinkSectionsMoveToPageActionsInsteadOfAnExtraCard() {
        val html = """<div class="dcol" style="width:900px"><table class="log1"><tr><td>示例会员 详细信息</td></tr>
            <tr><td><img class="pic" src="avatar.png"></td><td>用户名称：示例会员<br>会员等级：初回限定版</td></tr></table>
            <a href="message.php?action=write&amp;touid=1">发送短信</a></div>"""
        val page = parser.parse(html, "$origin/profile.php?action=show&uid=1").nativePage!!
        assertTrue(page.blocks.isEmpty())
        assertTrue(page.links.any { it.title == "发送短信" })
    }

    @Test fun tableBlocksStayInOriginalDocumentOrder() {
        val html = """<div class="dcol" style="width:900px"><div>表格之前</div><table><tr><td>姓名</td><td>数值</td></tr><tr><td>示例</td><td>1</td></tr></table><div>表格之后</div></div>"""
        val blocks = parser.parse(html, "$origin/kf_no1.php").nativePage!!.blocks
        assertEquals(3, blocks.size)
        assertTrue((blocks[0] as NativeBlock.Section).html.contains("表格之前"))
        assertTrue(blocks[1] is NativeBlock.Table)
        assertTrue((blocks[2] as NativeBlock.Section).html.contains("表格之后"))
    }

    @Test fun boardPagerRowIsNotRenderedAsData() {
        val html = """<div class="dcol" style="width:900px"><div>自由讨论区 版规</div><table><tr><td>首页 上一页 2 3 下一页</td><td><a href="post.php?fid=5">发表主题</a></td></tr>
            <tr><td>新</td><td><a href="read.php?tid=1">示例主题</a></td><td>13 687</td><td>示例作者 10-23</td></tr>
            <tr><td>新</td><td><a href="read.php?tid=2">另一条主题</a></td><td>2 90</td><td>示例同好 10-22</td></tr></table></div>"""
        val page = parser.parse(html, "$origin/thread.php?fid=5").nativePage!!
        val table = page.tables.single()
        assertEquals(3, table.rows.size)
        assertTrue(table.header)
        assertEquals("状态", table.rows.first().first().text)
        assertEquals("新", table.rows[1].first().text)
        assertFalse(table.rows.any { row -> row.any { it.text.contains("发表主题") } })
    }

    @Test fun messageReadBodyAndOptionsAreNativeBlocks() {
        val html = """<div class="dcol" style="width:900px">
            <div style="width:860px;border:1px solid #9999ff"><table><tr><td>信箱状态：目前有短消息 <b>113</b> 条</td></tr>
            <tr><td><a href="message.php?action=receivebox">收件箱</a> | <a href="message.php?action=sendbox">发件箱</a></td></tr></table></div>
            <table class="log1"><tr><td>发件人:</td><td>示例会员</td></tr>
            <tr><td>标题:</td><td>示例私信</td></tr><tr><td>时间:</td><td>2026-10-24 12:00</td></tr>
            <tr><td>内容:</td><td><fieldset><legend>Quote:</legend>引用内容</fieldset><br>这是私信正文。</td></tr>
            <tr><td>选项:</td><td>[<a href="message.php?action=write&amp;remid=1">回复</a>]
            [<a href="message.php?action=unread&amp;mid=1">标记未读</a>]
            [<a href="message.php?action=del&amp;towhere=receivebox&amp;delids=1">删除</a>]</td></tr></table></div>"""
        val page = parser.parse(html, "$origin/message.php?action=read&mid=1").nativePage!!
        assertTrue(page.blocks.any { it is NativeBlock.Section && it.html.contains("这是私信正文") })
        assertFalse(page.blocks.any { it is NativeBlock.Section && it.html.contains("113") })
        val table = page.tables.single()
        assertEquals(listOf("发件人", "标题", "时间"), table.rows.map { it.first().text.trimEnd(':', '：') })
        val options = page.blocks.filterIsInstance<NativeBlock.Links>().single().links
        assertEquals(listOf("回复", "标记未读", "删除"), options.map { it.title })
    }

    @Test fun quotedReplyIsSeparatedFromPostMetadata() {
        val html = """<div class="readtext" id="pidtpc"><table><tr><td width="190"><div class="readidms"><a href="profile.php?action=show&amp;uid=1">作者</a></div></td>
            <td width="990"><div><div><span>楼主</span><span>2026-10-24 12:00</span></div><p>正文。</p>
            <fieldset><legend>Quote:</legend>回 13楼(9027) 的帖子</fieldset>
            <fieldset class="read_fds"><legend>关键词</legend><a href="guanjianci.php?gjc=示例">示例</a></fieldset>
            <fieldset class="read_fds"><legend>本帖最近评分记录</legend><b>[违规处罚]-10进度分</b></fieldset>
            </div></td></tr></table></div>"""
        val post = parser.parse(html, "$origin/read.php?tid=1").posts.single()
        assertEquals(listOf("引用"), post.replies.map { it.title })
        assertTrue(post.replies.single().html.contains("回 13楼"))
        assertEquals(listOf("本帖最近评分记录"), post.meta.map { it.title })
        assertFalse(post.html.contains("Quote:"))
    }

    @Test fun twoRowRecordTableIsStillNative() {
        val html = """<div class="dcol" style="width:900px"><table><tr><td>时间</td><td>关于我的评分记录</td><td>记录</td><td></td></tr>
            <tr><td>2024-05-02 22:03</td><td>对 <a href="read.php?tid=1">示例回帖</a> 的认定</td><td colspan="2">优秀帖奖励</td></tr></table></div>"""
        val table = parser.parse(html, "$origin/kf_fw_1wkfb.php?ping=3").nativePage!!.tables.single()
        assertEquals(2, table.rows.size)
        assertTrue(table.header)
    }

    @Test fun profileModifyKeepsOneNativeFormAndHidesAvatarUploadInternals() {
        val html = """<div class="dcol" style="width:900px"><form action="profile.php?action=modify" enctype="multipart/form-data" method="post"><table class="log1">
            <tr><td style="font-weight:bold;font-size:18px">账号设置</td><td></td></tr>
            <tr><td>原密码</td><td><input type="password" name="oldpwd"></td></tr>
            <tr><td>新密码</td><td><input type="password" name="propwd"></td></tr>
            <tr><td>论坛头像</td><td><input name="upload" type="file"><input name="uploadurl[1]" value="140"><input name="uploadurl[2]" value="200"></td></tr>
            <tr><td>个性签名</td><td><input name="prohonor" value="示例签名"></td></tr>
            <tr><td></td><td><input type="submit" value="确认修改"></td></tr></table></form></div>"""
        val page = parser.parse(html, "$origin/profile.php?action=modify").nativePage!!
        assertTrue(page.tables.isEmpty())
        assertTrue(page.unsupported.none { it.contains("头像上传") }, "dedicated avatar uploader must not be advertised as unsupported")
        val form = page.forms.single()
        assertEquals("修改资料", form.title)
        assertEquals(listOf("oldpwd", "propwd", "prohonor"), form.fields.map { it.name })
    }
    @Test fun readPageTurnsAdminScriptControlsIntoConfirmedNativeForms() {
        val html = """<div class="dcol" style="width:900px">
            <input type="hidden" id="safeid" value="safe9876">
            <input type="hidden" id="tui_tid" value="1086358">
            <div id="read_tui"></div>
            <div style="display:none" id="floor1">
            <a href="javascript:;" id="cztztpc" onclick="cztzyx('1086358','tpc')">提交优秀</a>
            <a href="javascript:;" id="cztz17964438" onclick="cztzyx('1086358','17964438')">提交优秀</a>
            </div>
            <a href="javascript:;" onclick="readadmove('1086358')">移入无限制区</a>
            <a href="javascript:;" onclick="rpidnk('1086358','17964438')">屏蔽回帖</a>
            <a href="javascript:;" onclick="radtip('1','1086358','17964438')">管理提示</a>
            <script>function cztzyx(tid,pid){$.ajax({type:"POST",url:"diy_read_cztz.php",data:"tid="+tid+"&pid="+pid+"&safeid=abc12345"});}</script>
            </div>"""
        val page = parser.parse(html, "$origin/read.php?tid=1086358").nativePage!!
        assertEquals(
            listOf("rpc:recommend", "rpc:cztz:tpc", "rpc:cztz:17964438", "rpc:admove", "rpc:hide:17964438", "rpc:tip:1:17964438"),
            page.forms.map { it.id },
        )
        val topic = page.forms.first { it.id == "rpc:cztz:tpc" }
        assertEquals("$origin/diy_read_cztz.php", topic.actionUrl)
        assertEquals(listOf("tid", "pid", "safeid"), topic.hidden.map { it.name })
        assertEquals(listOf("1086358", "tpc", "abc12345"), topic.hidden.map { it.value },
            "the inline script's session token is what the web control actually posts")
        assertTrue(topic.fields.isEmpty())
        assertEquals("提交优秀", topic.submits.single().label)
        val move = page.forms.first { it.id == "rpc:admove" }
        assertEquals("$origin/diy_admin_read.php", move.actionUrl)
        assertEquals(listOf("tid", "safeid"), move.hidden.map { it.name })
        assertEquals("safe9876", move.hidden.last().value)
        assertEquals("$origin/kf_fw_0ladmin.php", page.forms.first { it.id == "rpc:hide:17964438" }.actionUrl)
        assertEquals(listOf("addo", "tid", "pid"), page.forms.first { it.id == "rpc:tip:1:17964438" }.hidden.map { it.name })
        assertTrue(page.unsupported.none { it in setOf("提交优秀", "移入无限制区", "屏蔽回帖", "管理提示") })
    }

    @Test fun readAdminFormsRequireMatchingTopicTrustedArgsAndSessionToken() {
        val html = """<div class="dcol" style="width:900px">
            <input type="hidden" id="tui_tid" value="1086358">
            <a href="javascript:;" onclick="cztzyx('999','tpc')">提交优秀</a>
            <a href="javascript:;" onclick="readadmove('999')">移入无限制区</a>
            <a href="javascript:;" onclick="rpidnk('1086358','17964438')">屏蔽回帖</a>
            <a href="javascript:;" onclick="rpitnk('1086358','1')">伪操作</a>
            </div>"""
        val page = parser.parse(html, "$origin/read.php?tid=1086358").nativePage!!
        assertTrue(page.forms.isEmpty(), "without a trusted token or matching tid no write control may appear")
        assertTrue(page.unsupported.any { it.contains("伪操作") })
    }

    @Test fun readAdminFormsRejectConcatenatedScriptPayloads() {
        val html = """<div class="dcol" style="width:900px">
            <input type="hidden" id="safeid" value="safe9876">
            <a href="javascript:;" onclick="cztzyx('1086358','tpc');alert(1)">提交优秀</a>
            <a href="javascript:readadmove('1086358')">移入无限制区</a>
            </div>"""
        val page = parser.parse(html, "$origin/read.php?tid=1086358").nativePage!!
        assertEquals(listOf("rpc:admove"), page.forms.map { it.id })
    }

    @Test fun androidUsesNavigationComposePredictiveBackWithoutWebView() {
        val root = Path.of(System.getProperty("kf.projectRoot"))
        assertFalse(Files.readString(root.resolve("androidApp/src/main/AndroidManifest.xml")).contains("BrowserActivity"))
        assertTrue(Files.readString(root.resolve("androidApp/src/main/AndroidManifest.xml")).contains("enableOnBackInvokedCallback=\"true\""))
        val activity = Files.readString(root.resolve("androidApp/src/main/kotlin/work/miaola/kf/MainActivity.kt"))
        assertFalse(activity.contains("PredictiveBackHandler"))
        assertFalse(activity.contains("graphicsLayer"), "Navigation chrome must not scale the whole app")
        val shell = Files.readString(root.resolve("androidApp/src/main/kotlin/work/miaola/kf/nav/KfRoot.kt"))
        assertTrue(shell.contains("NavHost("))
        assertTrue(shell.contains("popEnterTransition"))
        assertTrue(shell.contains("popExitTransition"))
        assertTrue(shell.contains("BackHandler"), "Only the editor draft guard may intercept back")
        assertFalse(shell.contains("LaunchedEffect(c.showSettings)"), "Settings must not retrigger its own destination")
        assertTrue(shell.contains("pendingNativePage"))
        Files.walk(root.resolve("androidApp/src/main/kotlin")).use { paths ->
            paths.filter { it.toString().endsWith(".kt") }.forEach { file ->
                val source = Files.readString(file)
                assertFalse(source.contains("WebView("), file.fileName.toString())
                assertFalse(source.contains("android.webkit.WebView"), file.fileName.toString())
            }
        }
    }
}

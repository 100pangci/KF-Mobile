package work.miaola.kf

import kotlin.test.*

class HtmlParserTest {
    private val parser = ForumHtmlParser("https://kf.miaola.work")
    private val authenticated = "<div id='kf_topuser'>测试用户</div><div id='kf_information'><a href='message.php'>消息</a><a href='login.php?action=quit&amp;verify=synthetic'>退出</a></div>"
    @Test fun loginParsingAndSessionMarker() {
        val html = "<form action='login.php?' method='post'><input name='step' type='hidden' value='2'><input name='verify' value='synthetic'><input name='pwuser'><input name='pwpwd' type='password'><input name='submit' type='submit' value='登录'></form>"
        val (url, fields) = parser.loginFields(html, "https://kf.miaola.work/login.php")
        assertEquals("https://kf.miaola.work/login.php", url)
        assertEquals("synthetic", fields["verify"])
        assertTrue(parser.loggedIn(parser.document(authenticated, url)))
        assertFalse(parser.loggedIn(parser.document(html, url)))
        assertFails { parser.loginFields(html.replace("action='login.php?'", "action='https://evil.test/login.php'"), url) }
    }
    @Test fun threadListingDoesNotUseSidebar() {
        val html = "<title>示例板块 - 绯月</title>$authenticated<div class='rightdiv'><a href='read.php?tid=999'>侧栏</a></div><table><tr><td><div class='threadtit1'><a href='thread.php?fid=5&amp;type=1'>讨论</a><a href='read.php?tid=1&amp;sf=abc'>示例主题</a></div></td><td class='b_tit6'>3 20</td><td><a href='profile.php?action=show&amp;uid=1'>作者</a></td></tr></table><a href='thread.php?fid=5&amp;page=2'>下一页</a>"
        val page = parser.parse(html, "https://kf.miaola.work/thread.php?fid=5")
        assertEquals(1, page.topics.size)
        assertEquals("示例主题", page.topics.single().title)
        assertTrue(page.topics.single().subtitle.contains("作者"))
        assertNotNull(page.next)
        assertTrue(page.authenticated)
        assertEquals(1, page.menu.size)
    }
    @Test fun tableFormControlsSurviveFosterParenting() {
        val html = """<table><tr><td>登录</td></tr><form name="login" action="login.php?" method="post">
            <input type="hidden" name="step" value="2"><input type="hidden" name="verify" value="synthetic">
            <tr><td><input name="pwuser"></td></tr><tr><td><input name="pwpwd" type="password"></td></tr>
            <tr><td><input name="submit" type="submit" value="登录"></td></tr></form></table>"""
        val (_, fields) = parser.loginFields(html, "https://kf.miaola.work/login.php")
        assertTrue("pwuser" in fields)
        assertTrue("pwpwd" in fields)
        assertEquals("2", fields["step"])
        assertEquals("synthetic", fields["verify"])
        assertEquals("登录", fields["submit"])
    }
    @Test fun postContentAndSanitization() {
        val html = """<title>示例主题|板块 - 绯月</title>$authenticated
            <div class="readtext" id="pidtpc"><table><tr><td width="190"><div class="readidms"><a href="profile.php?action=show&amp;uid=1">作者</a></div></td>
            <td width="990"><div><div id="floor0"><a class="readcza" href="post.php?action=quote&amp;fid=5&amp;tid=1">引用</a></div>
            <div><span>楼主</span><span>2026-10-24 12:00</span><a class="readcza" href="javascript:void(0)">菜单</a></div>
            <p>正文<b>加粗</b><img src="/sample.png" onerror="alert(1)"></p><script>alert(1)</script><a href="javascript:alert(1)">坏链接</a>
            </div></td></tr></table></div>"""
        val page = parser.parse(html, "https://kf.miaola.work/read.php?tid=1")
        assertEquals(1, page.posts.size)
        val post = page.posts.single()
        assertEquals("作者", post.author)
        assertEquals("楼主", post.floor)
        assertEquals("2026-10-24 12:00", post.time)
        assertTrue(post.html.contains("正文"))
        assertTrue(post.html.contains("<b>加粗</b>"))
        assertFalse(post.html.contains("script"))
        assertFalse(post.html.contains("onerror"))
        assertFalse(post.html.contains("javascript:"))
    }
    @Test fun editorFieldsAndCategoryValidation() {
        val html = """<form action="post.php?" method="post"><input name="verify" value="synthetic"><input name="atc_title"><textarea name="atc_content">[quote]示例[/quote]</textarea>
            <select name="p_type"><option value="0">选择分类</option><option value="1">讨论</option></select></form>
            <input name="step" value="2"><input name="action" value="new"><input name="fid" value="52"><script>var cate = '1';</script>"""
        val form = parser.parseEditor(html, "https://kf.miaola.work/post.php?fid=52")
        assertEquals("synthetic", form.fields["verify"])
        assertEquals("2", form.fields["step"])
        assertEquals("new", form.fields["action"])
        assertEquals(2, form.categories.size)
        assertTrue(form.categoryRequired)
        assertEquals("[quote]示例[/quote]", form.fields["atc_content"])
    }
    @Test fun messageFormAndRefresh() {
        val html = "<form action='message.php' method='post'><input name='pwuser'><input name='msg_title'><textarea name='atc_content'></textarea><input type='checkbox' name='ifsave' value='1'></form>"
        val form = parser.parseEditor(html, "https://kf.miaola.work/message.php?action=write")
        assertEquals("msg_title", form.titleField)
        assertEquals("pwuser", form.recipientField)
        assertNull(form.fields["ifsave"])
        assertEquals("https://kf.miaola.work/index.php", parser.refreshTarget(parser.document("<meta http-equiv='refresh' content='0;url=index.php'>", "https://kf.miaola.work/login.php")))
        assertNull(parser.refreshTarget(parser.document("<meta http-equiv='refresh' content='0;url=https://evil.test/'>", "https://kf.miaola.work/login.php")))
    }
    @Test fun missingLogoutMarkerIsUnknownNotProofOfExpiredLogin() {
        assertEquals(AuthStatus.UNKNOWN, parser.parse("<title>维护提示</title><p>暂时无法连接</p>", "https://kf.miaola.work/index.php").authStatus)
        val html = "<form action='login.php' method='post'><input name='pwuser'><input name='pwpwd' type='password'></form>"
        assertEquals(AuthStatus.LOGIN_REQUIRED, parser.parse(html, "https://kf.miaola.work/login.php").authStatus)
        assertFalse(parser.loggedIn(parser.document("<div class='readtext'><a href='login.php?action=quit'>帖子中的链接</a></div>", "https://kf.miaola.work/read.php?tid=1")))
    }
    @Test fun emoticonCatalogUsesRealIdsAndNeverExecutesJs() {
        val js = "var facedb=new Array(); facedb['em']='默认表情'; face[57]=['em/em48.gif','']; face[41]=['em/em32.gif','']; face[58]=['https://evil.test/x.gif','bad']; face[59]=['../secret.gif','bad']; alert('not executed');"
        val faces = parser.parseEmoticons(js, "<script>var imgpath='123';</script>", "https://kf.miaola.work/post.php?fid=1")
        assertEquals(2, faces.size)
        assertEquals("[s:57]", faces.first().code)
        assertEquals("高兴", faces.first().label)
        assertEquals("https://kf.miaola.work/123/post/smile/em/em48.gif", faces.first().imageUrl)
        assertEquals("默认表情", faces.first().group)
    }
    @Test fun saleFieldsetBecomesNativePurchaseOffer() {
        val html = """<base href="https://kf.miaola.work/">$authenticated<div class="readtext" id="pidtpc"><table><tr><td width="190"><div class="readidms"><a href="profile.php?action=show&amp;uid=1">作者</a></div></td>
            <td width="990"><div><p>资源介绍</p><fieldset><legend>此帖售价 1 KFB <select name="buyers"><option>资源区不显示购买名单</option></select>
            <input type="button" value="愿意购买,支付KFB" onclick='location.href="job.php?action=buytopic&amp;tid=1&amp;pid=tpc&amp;verify=synthetic"'></legend><b>购买后可见本内容</b></fieldset></div></td></tr></table></div>"""
        val post = parser.parse(html, "https://kf.miaola.work/read.php?tid=1").posts.single()
        assertEquals(1, post.purchases.size)
        assertEquals("1", post.purchases.single().price)
        assertEquals("KFB", post.purchases.single().currency)
        assertTrue(post.purchaseLocked)
        assertTrue(post.html.contains("资源介绍"))
        assertFalse(post.html.contains("verify="))
        assertFalse(post.purchases.single().toString().contains("synthetic"))
        val crossOrigin = html.replace("job.php?action=buytopic", "https://evil.test/job.php?action=buytopic")
        assertTrue(parser.parse(crossOrigin, "https://kf.miaola.work/read.php?tid=1").posts.single().purchases.isEmpty())
        val otherPost = html.replace("tid=1&amp;pid=tpc", "tid=2&amp;pid=tpc")
        assertTrue(parser.parse(otherPost, "https://kf.miaola.work/read.php?tid=1").posts.single().purchases.isEmpty())
    }
}

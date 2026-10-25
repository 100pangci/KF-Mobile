@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package work.miaola.kf

import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import work.miaola.kf.ui.KfApp
import work.miaola.kf.ui.KfActions
import work.miaola.kf.ui.KfTheme
import work.miaola.kf.ui.SearchScreen
import work.miaola.kf.ui.searchCacheKey

/** Headless rendering of shared Compose UI with clearly synthetic, offline data. */
class UiRenderTest {
    @Test fun searchScreenRendersResultsInline() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val c = AppController(MemoryStore(), DemoFactory(), scope, browser = { _, _ -> })
        c.updateTheme(ThemeMode.LIGHT)
        c.ensureSearch(searchCacheKey("绯月", "作者"), "绯月", "作者")
        val scene = ImageComposeScene(width = 412, height = 915, density = Density(1f)) {
            KfTheme(c.theme) { Surface { SearchScreen(c, KfActions(), "绯月", "作者") } }
        }
        try {
            scene.render(0).close(); scene.render(250_000_000).close()
            val image = scene.render(1_000_000_000)
            try {
                val bytes = image.encodeToData(EncodedImageFormat.PNG)?.bytes
                assertNotNull(bytes); assertTrue(bytes.size > 5000)
                val output = Path.of(System.getProperty("kf.projectRoot"), "shared", "build", "reports", "ui", "search-light.png")
                Files.createDirectories(output.parent); Files.write(output, bytes)
            } finally { image.close() }
        } finally { scene.close(); c.close(); scope.cancel() }
    }

    @Test fun phoneScreensRenderInLightAndDark() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val factory = DemoFactory()
        val c = AppController(MemoryStore(), factory, scope, browser = { _, _ -> })
        val output = Path.of(System.getProperty("kf.projectRoot"), "shared", "build", "reports", "ui")
        Files.createDirectories(output)
        fun render(name: String) {
            val scene = ImageComposeScene(width = 412, height = 915, density = Density(1f)) {
                KfApp(c) { html, _, _, _, _ -> Text(org.jsoup.Jsoup.parse(html).wholeText()) }
            }
            try {
                // Advance animation/frame effects deterministically.
                scene.render(0).close()
                scene.render(250_000_000).close()
                val image = scene.render(1_000_000_000)
                val bytes = image.encodeToData(EncodedImageFormat.PNG)?.bytes
                assertNotNull(bytes)
                assertTrue(bytes.size > 5000, "Rendered screen unexpectedly empty")
                Files.write(output.resolve("$name.png"), bytes)
                image.close()
            } finally { scene.close() }
        }
        try {
            c.updateTheme(ThemeMode.LIGHT); render("feed-light")
            c.selectTab(HomeTab.BOARDS); render("boards-light")
            c.selectTab(HomeTab.FEED); c.load("read.php?tid=1"); render("reader-light")
            c.updateTheme(ThemeMode.DARK); render("reader-dark")
            c.showSettings = true; render("settings-dark"); c.showSettings = false
            c.updateTheme(ThemeMode.LIGHT); c.openEditor("post.php?fid=1"); render("editor-light")
            c.showEmoticonPicker = true; render("emoticons-light"); c.showEmoticonPicker = false; c.dismissEditor()
            val offer = PurchaseOffer("1:tpc", "pidtpc", "1", "KFB", "${c.origin}/job.php?action=buytopic&tid=1&pid=tpc&verify=synthetic", "${c.origin}/read.php?tid=1")
            c.load("read.php?tid=1")
            factory.services.getValue(c.origin).topicOverride = c.page!!.copy(posts = listOf(c.page!!.posts.first().copy(purchases = listOf(offer), purchaseLocked = true)))
            c.load("read.php?tid=1"); render("purchase-light")
            c.requestPurchase(offer); render("purchase-confirm-light"); c.dismissPurchase()
            c.selectTab(HomeTab.MESSAGES); render("messages-light")
            c.selectTab(HomeTab.ACCOUNT); render("account-light")
            factory.services.getValue(c.origin).dailyClaimed = true
            c.refreshDailyStatus(); render("account-signed-light")
            val parser = ForumHtmlParser(c.origin)
            val nativeProfile = "<div class='dcol' style='width:900px'><table class='log1'><tr><td>示例会员 详细信息</td></tr><tr><td><img class='pic' src='avatar.png'></td><td>用户名称：示例会员<br>会员等级：初回限定版<br>论坛货币：100 KFB<br>注册时间：2026-01-01</td></tr></table><a href='message.php?action=write&amp;touid=1'>发送私信</a></div>"
            c.presentNativePage(parser.parse(nativeProfile, "${c.origin}/profile.php?action=show&uid=1")); render("native-profile-light")
            val modify = "<div class='dcol' style='width:900px'><form action='profile.php?action=modify' enctype='multipart/form-data' method='post'><table class='log1'><tr><td style='font-weight:bold;font-size:18px'>账号设置</td><td></td></tr><tr><td>原密码</td><td><input type='password' name='oldpwd'></td></tr><tr><td>新密码</td><td><input type='password' name='propwd'></td></tr><tr><td>E-MAIL</td><td><input name='proemail' value='example@example.com'></td></tr><tr><td>个性签名</td><td><input name='prohonor' value='示例签名'></td></tr><tr><td>主题列表每页个数</td><td><select name='t_num'><option value='20'>20</option><option selected value='30'>30</option></select></td></tr><tr><td></td><td><input type='submit' value='确认修改'></td></tr></table></form></div>"
            c.presentNativePage(parser.parse(modify, "${c.origin}/profile.php?action=modify")); render("native-profile-modify-light")
            val bank = "<div class='dcol' style='width:900px'><div style='width:860px'><table class='bank1'><tr><td colspan='2' style='font-weight:bold;font-size:18px;color:#6666FF'>转账(贡献)取款(KFB)</td></tr><tr><td>转账贡献<br>(手续费0%)<br><a href='hack.php?H_name=bank&amp;action=log'><font color='blue'>查看日志</font></a></td><td><span style='color:#ff0000'>你所拥有的贡献：7.8 | 注意：你转给对方的是贡献</span><br>对方用户名：<input name='pwuser'><br>转帐数额：<input name='to_money'></td></tr><tr><td>取KFB</td><td>当前所持：100KFB<br>活期存款：0KFB<br>定期存款：0KFB<br>定期利息：0.07%</td></tr></table></div><form action='hack.php?H_name=bank' method='post'><input name='action' type='hidden' value='virement'><label>对方用户名<input name='pwuser' placeholder='对方用户名'></label><label>转帐数额<input name='to_money' placeholder='转帐数额'></label><textarea name='memo' placeholder='附言'></textarea><input type='submit' value='提 交'></form></div>"
            c.presentNativePage(parser.parse(bank, "${c.origin}/hack.php?H_name=bank")); render("native-bank-light")
            val growup = "<div class='dcol' style='width:900px'><div style='line-height:60px;font-size:14px;text-align:center;color:#C30'>当前神秘 [<b>100</b>] 级 | 神秘系数 3 | 当前成长经验值 [<b>3000</b>] 点</div><div style='text-align:center'><span style='color:#804000;font-size:14px;font-weight:bold'>升级经验已完成 35%</span></div><div style='width:860px;border:1px solid #9999ff'><table width='860'><tr><td style='background-color:#6161FD;text-align:center'><a href='kf_growup.php?ok=2&amp;color=1' style='color:#FFFFFF'>选择该颜色</a></td><td style='background-color:#009CFF;text-align:center'><a href='kf_growup.php?ok=2&amp;color=2' style='color:#FFFFFF'>选择该颜色</a></td><td style='background-color:#CED83C;text-align:center'><a href='kf_growup.php?ok=2&amp;color=3' style='color:#FFFFFF'>选择该颜色</a></td><td style='background-color:#5AD465;text-align:center'><a href='kf_growup.php?ok=2&amp;color=4' style='color:#FFFFFF'>选择该颜色</a></td><td style='background-color:#FF696C;text-align:center'><a href='kf_growup.php?ok=2&amp;color=5' style='color:#FFFFFF'>选择该颜色</a></td></tr><tr><td style='background-color:#009966;text-align:center'><a href='kf_growup.php?ok=2&amp;color=6' style='color:#FFFFFF'>选择该颜色</a></td><td style='background-color:#9966CC;text-align:center'><a href='kf_growup.php?ok=2&amp;color=7' style='color:#FFFFFF'>选择该颜色</a></td><td style='background-color:#FFCC99;text-align:center'><a href='kf_growup.php?ok=2&amp;color=8' style='color:#FFFFFF'>选择该颜色</a></td><td style='background-color:#FF9999;text-align:center'><a href='kf_growup.php?ok=2&amp;color=9' style='color:#FFFFFF'>选择该颜色</a></td><td style='background-color:#C68BF9;text-align:center'><a href='kf_growup.php?ok=2&amp;color=10' style='color:#FFFFFF'>选择该颜色</a></td></tr></table></div></div>"
            factory.services.getValue(c.origin).dailyClaimed = false
            c.refreshDailyStatus()
            c.presentNativePage(parser.parse(growup, "${c.origin}/kf_growup.php")); render("native-growup-light")
            val growupClaimed = growup.replace("<div style='line-height:60px", "<div style='line-height:48px;text-align:center;border:1px solid #666'><a href='javascript:;'>STATUS</a></div><div style='line-height:60px")
            factory.services.getValue(c.origin).dailyClaimed = true
            c.refreshDailyStatus()
            c.presentNativePage(parser.parse(growupClaimed, "${c.origin}/kf_growup.php")); render("native-growup-signed-light")
            val rating = "<div class='dcol' style='width:900px'><div style='width:860px;border:1px solid #9999ff'><table class='adp1'><tr><td style='font-weight:bold;font-size:18px;color:#6666FF'>自助发布奖励</td><td></td></tr><tr><td colspan='2'>记录：<a href='kf_fw_1wkfb.php?ping=3'>关于我的评分记录</a> | <a href='kf_fw_1wkfb.php?ping=4'>已完成的评分记录</a></td></tr></table><table class='adp1'><tr><td>时间</td><td>最近发布的分享帖</td><td>记录</td><td></td></tr><tr><td>2026-10-20 08:39</td><td><a href='read.php?tid=1'>[示例] 最近发布的分享帖</a></td><td><font color='#006600'>有评分记录</font></td><td><a href='kf_fw_1wkfb.php?do=1'>获取评分奖励</a></td></tr><tr><td>2026-10-19 08:42</td><td><a href='read.php?tid=2'>[示例] 另一条分享帖</a></td><td><font color='#006600'>有评分记录</font></td><td><a href='kf_fw_1wkfb.php?do=1'>获取评分奖励</a></td></tr></table></div></div>"
            c.presentNativePage(parser.parse(rating, "${c.origin}/kf_fw_1wkfb.php")); render("native-rating-light")
            val messageList = "<div class='dcol' style='width:900px'><div style='width:860px;border:1px solid #9999ff'><table><tr><td style='text-align:center'>信箱状态：目前有短消息 <b>113</b> 条；最多可存消息 <b>200</b> 条</td></tr><tr><td style='text-align:center'><a href='message.php?action=receivebox'>收件箱</a> | <a href='message.php?action=sendbox'>发件箱</a> | <a href='message.php?action=scout'>消息跟踪</a> | <a href='message.php?action=write'>写新消息</a> | <a href='message.php?action=banned'>屏蔽列表</a></td></tr></table></div><form action='message.php' method='post'><table class='thread1'><tr><td>ID</td><td>标题</td><td>发件人</td><td>收件人</td><td>时间</td><td>已读</td><td>选定</td></tr><tr><td>1</td><td><a href='message.php?action=read&amp;mid=1'>一封示例私信</a></td><td><a href='profile.php?action=show&amp;uid=2'>示例会员</a></td><td>示例用户</td><td>2026-08-08 23:55</td><td>是</td><td><input name='delid[]' type='checkbox' value='1'></td></tr><tr><td>2</td><td><a href='message.php?action=read&amp;mid=2'>另一封示例私信</a></td><td><a href='profile.php?action=show&amp;uid=3'>示例同好</a></td><td>示例用户</td><td>2026-08-04 18:40</td><td>是</td><td><input name='delid[]' type='checkbox' value='2'></td></tr></table><input type='hidden' name='towhere' value='receivebox'><input type='submit' value='执行'></form></div>"
            c.presentNativePage(parser.parse(messageList, "${c.origin}/message.php?action=receivebox")); render("native-messages-light")
            val favor = "<div class='dcol' style='width:900px'><div style='width:860px'><table class='log1'><tr><td valign='top' width='23%'><form action='profile.php?action=favor' method='post'><input name='job' type='hidden' value='addtype'><table><tr><td><b>收藏夹目录</b></td></tr><tr><th><table><tr><td>-- <a href='profile.php?action=favor'>全部收藏</a></td></tr><tr><td>-- <a href='profile.php?action=favor&amp;type=0'>默认分类</a></td></tr></table></th></tr><tr><td><input name='type' size='6' type='text'><input type='submit' value='新目录名'></td></tr></table></form></td><td valign='top'><form action='profile.php?action=favor' method='post'><table><tr><td>标题</td><td width='20%'>版块</td><td width='15%'>作者</td><td width='5%'></td></tr><tr><td><a href='read.php?tid=1'>示例收藏主题</a></td><td><a href='thread.php?fid=5'>自由讨论区</a></td><td><a href='profile.php?action=show&amp;uid=2'>示例作者</a></td><td><input name='delid[]' type='checkbox' value='1'></td></tr><tr><td><a href='read.php?tid=2'>另一条收藏主题</a></td><td><a href='thread.php?fid=56'>个人日记</a></td><td><a href='profile.php?action=show&amp;uid=3'>示例同好</a></td><td><input name='delid[]' type='checkbox' value='2'></td></tr></table></form></td></tr></table></div></div>"
            c.presentNativePage(parser.parse(favor, "${c.origin}/profile.php?action=favor")); render("native-favor-light")
            val board = "<div class='dcol' style='width:900px'><div>自由讨论区 · 版规与说明</div><table><tr><td>首页 上一页 2 3 下一页</td><td>发表主题</td></tr><tr><td>新</td><td><a href='read.php?tid=1'>示例主题：今天聊点什么</a></td><td>13 687</td><td>示例作者 10-23</td></tr><tr><td>新</td><td><a href='read.php?tid=2'>另一条示例主题</a></td><td>2 90</td><td>示例同好 10-22</td></tr><tr><td></td><td><a href='read.php?tid=3'>第三条示例主题</a></td><td>7 210</td><td>示例会员 10-21</td></tr></table></div>"
            c.presentNativePage(parser.parse(board, "${c.origin}/thread.php?fid=5")); render("native-board-light")
            val message = "<div class='dcol' style='width:900px'><h2>一封示例私信</h2><p>发件人：示例会员</p><p>这是离线渲染样例，不是真实用户消息。</p><a href='message.php?action=write&amp;touid=1'>回复消息</a></div>"
            c.presentNativePage(parser.parse(message, "${c.origin}/message.php?action=read&mid=1")); render("native-message-light")
            val rank = "<div class='dcol' style='width:900px'><div style='font-size:18px;color:#FF0080;font-weight:bold'>神秘系数排行</div><table class='kf_no11'><tr><td width='25%'><a href='kf_no1.php?no1=sm' style='color:#000000;font-weight:bold'>神秘系数排名</a></td><td width='25%'><a href='kf_no1.php?no1=cz' style='color:#000000;font-weight:bold'>KFB排名</a></td><td width='25%'><a href='kf_no1.php?no1=ft' style='color:#000000;font-weight:bold'>本月发帖排名</a></td><td width='25%'><a href='kf_no1.php?no1=gx' style='color:#000000;font-weight:bold'>贡献属性排名</a></td></tr></table><table class='kf_no11'><tr><td><span style='color:#FF0000'>名次</span></td><td>用户</td><td>KFB</td><td>贡献</td></tr><tr><td>1</td><td>示例会员</td><td>100</td><td>9.9</td></tr><tr><td>2</td><td>示例同好</td><td>90</td><td>8.8</td></tr><tr><td>3</td><td>示范用户</td><td>80</td><td>7.7</td></tr></table></div>"
            c.presentNativePage(parser.parse(rank, "${c.origin}/kf_no1.php")); render("native-rank-light")
            c.selectTab(HomeTab.FEED)
            c.showLogin = true; render("login-light")
        } finally { c.close(); scope.cancel() }
    }
}

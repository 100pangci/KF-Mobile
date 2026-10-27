@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package work.miaola.kf

import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import org.jetbrains.skia.EncodedImageFormat
import work.miaola.kf.ui.*
import kotlin.test.*

/** Public render fixtures are always synthetic, including boundary cases. */
class UiEdgeRenderTest {
    @Test fun longEmptyErrorLoadingAndFallbackScreensRemainRenderable() {
        val output = Path.of(System.getProperty("kf.projectRoot"), "shared/build/reports/ui/edge")
        Files.createDirectories(output)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val factory = DemoFactory()
        val c = AppController(MemoryStore(), factory, scope, { _, _ -> })
        val service = factory.services.getValue(c.origin)
        fun render(name: String, content: @Composable () -> Unit) {
            listOf(360, 412).forEach { width -> listOf(ThemeMode.LIGHT, ThemeMode.DARK).forEach { theme ->
                c.updateTheme(theme)
                val scene = ImageComposeScene(width = width, height = 915, density = Density(1f)) {
                    KfTheme(theme) { Surface { content() } }
                }
                try {
                    scene.render(0).close(); scene.render(250_000_000).close()
                    scene.render(1_000_000_000).use { image ->
                        val bytes = image.encodeToData(EncodedImageFormat.PNG)?.bytes
                        assertNotNull(bytes); assertTrue(bytes.size > 1000)
                        Files.write(output.resolve("$name-${theme.name.lowercase()}-$width.png"), bytes)
                    }
                } finally { scene.close() }
            } }
        }
        val rich: RichContent = { html, _, _, _, _ -> Text(org.jsoup.Jsoup.parse(html).wholeText()) }
        try {
            val longName = "很长的示例用户名".repeat(24)
            val page = service.home.copy(topics = (1..100).map { SiteLink("这是长标题边界样例".repeat(24), "${c.origin}/read.php?tid=$it", "$longName · 100 / 1000", "示例板块") }, feeds = emptyList())
            render("long-topic-list") { ListingScreen(c, KfActions(), page, false) }
            val reader = page.copy(url = "${c.origin}/read.php?tid=1", topics = emptyList(), posts = listOf(ForumPost("pid1", longName, "", "100楼", "2026-01-01", "<p>短正文</p>")))
            render("long-reader-author") { ReaderScreen(c, KfActions(), reader, { _, _, _ -> }, rich) }
            render("empty-list") { ListingScreen(c, KfActions(), page.copy(url = "${c.origin}/thread.php?fid=2", topics = emptyList()), false) }
            render("loading") { KfPageLoadState(true, null, {}) }
            render("error") { KfPageLoadState(false, "连接暂不可用", {}) }
            val fields = (1..24).map { SiteField("field-$it", "field$it", "示例字段 $it", SiteFieldKind.TEXT) }
            val form = SiteForm("synthetic", "多字段表单", "${c.origin}/unknown.php", "${c.origin}/unknown.php", "POST", emptyList(), fields, listOf(SiteSubmit("保存", null, "")))
            val table = NativeTable(listOf((1..8).map { NativeCell("列 $it", bold = true) }) + (1..12).map { row -> (1..8).map { NativeCell("示例 $row-$it") } }, header = true)
            val native = page.copy(url = "${c.origin}/unknown.php", nativePage = NativeSitePage("兼容性样例", forms = listOf(form), tables = listOf(table)))
            render("wide-table-form") { NativeSiteScreen(c, native, KfActions(), rich, { _, _, _ -> }) }
            val parser = ForumHtmlParser(c.origin)
            val blocklist = parser.parse("<div class='dcol' style='width:900px'><p>按站点说明逐项填写；这是合成名单。</p><form method='post' action='message.php'><input name='action' type='hidden' value='banned'><input name='step' type='hidden' value='2'><textarea name='banidinfo'>示例甲\n示例乙</textarea><input type='submit' value='保存'></form></div>", "${c.origin}/message.php?action=banned")
            assertNotNull(blocklist.nativePage?.blocklist)
            render("blocklist") { NativeSiteScreen(c, blocklist, KfActions(), rich, { _, _, _ -> }) }
            val progress = parser.parse("<div class='dcol' style='width:900px'><p>当前进度分：<span>12</span></p><p>这是合成规则说明，不代表实际账户状态。</p></div>", "${c.origin}/kf_lv200.php")
            assertNotNull(progress.nativePage?.progress)
            render("progress") { NativeSiteScreen(c, progress, KfActions(), rich, { _, _, _ -> }) }
            val faq = parser.parse("<div class='dcol' style='width:900px'><table><tr><td>规则</td><td>不能因同一行包含链接而隐藏的说明 <a href='faq.php?id=1'>帮助</a><a href='faq.php?id=2'>补充</a></td></tr><tr><td>详细说明</td><td>${"合成长规则。".repeat(100)}</td></tr></table></div>", "${c.origin}/faq.php")
            render("faq-mixed-links") { NativeSiteScreen(c, faq, KfActions(), rich, { _, _, _ -> }) }
            // Recovery UI is synthetic and mutations are intercepted by DemoService.
            val managementHtml = """<div class='dcol' style='width:900px'><form method='post' action='message.php'>
                <input name='verify' type='hidden' value='synthetic'><table><tr><th>标题</th><th>选定</th></tr>
                <tr><td><a href='read.php?tid=1'>合成待核对项目</a></td><td><input name='delid[]' type='checkbox' value='1'></td></tr></table><input type='submit' value='删除'></form></div>"""
            service.siteResult = SiteActionResult.Uncertain("合成结果未知，未执行服务器操作")
            listOf(false, true).forEach { favorites ->
                val url = if (favorites) "${c.origin}/profile.php?action=favor" else "${c.origin}/message.php?action=sendbox"
                val messageHtml = managementHtml.replace("<tr><th>标题</th><th>选定</th></tr>", "<tr><th>ID</th><th>标题</th><th>发件人</th><th>收件人</th><th>时间</th><th>已读</th><th>选定</th></tr>")
                    .replace("<tr><td><a href='read.php?tid=1'>合成待核对项目</a></td><td>", "<tr><td>1</td><td><a href='message.php?action=read&amp;mid=1'>合成待核对项目</a></td><td>合成发件人</td><td>合成收件人</td><td>2026-01-01</td><td>是</td><td>")
                val managed = parser.parse(if (favorites) managementHtml else messageHtml, url)
                if (!favorites) assertEquals(1, managed.nativePage!!.messages.size)
                c.presentNativePage(managed)
                val managedForm = managed.nativePage!!.forms.single()
                c.requestSiteSubmit(managedForm, managedForm.fields.associate { it.id to listOf("1") }, managedForm.submits.single())
                if (c.pendingBatch != null) c.confirmBatch() else c.confirmSiteAction()
                assertNotNull(c.managementRecoveryFor(url))
                render(if (favorites) "favorites-recovery" else "messages-recovery") { ListingScreen(c, KfActions(), managed, !favorites, rich = rich) }
            }
        } finally { c.close(); scope.cancel() }
    }
}

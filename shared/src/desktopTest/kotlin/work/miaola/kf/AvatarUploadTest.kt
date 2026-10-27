package work.miaola.kf

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.test.*
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer

class AvatarUploadTest {
    private val origin = "https://kf.miaola.work"
    private val parser = ForumHtmlParser(origin)
    private val form = parser.parse("""<div class='dcol' style='width:900px'>
        <form id='creator' name='creator' action='profile.php?action=modify' method='post' enctype='multipart/form-data'>
        <input type='hidden' name='prooicq' value=''><input type='hidden' name='facetype' value='3'>
        <table><tr><td>头像宽度</td><td><input name='uploadurl[1]' value='140'></td></tr>
        <tr><td>头像高度</td><td><input name='uploadurl[2]' value='200'></td></tr>
        <tr><td>签名</td><td><input name='prohonor' value='合成签名'></td></tr>
        <tr><td>原密码</td><td><input type='password' name='oldpwd' value=''></td></tr></table>
        <input type='submit' name='prosubmit' value='确认修改'></form></div>""",
        "$origin/profile.php?action=modify").nativePage!!.forms.single()
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(32)
    private val profileHtml = """<div id='kf_topuser'>合成会员</div>
        <div id='kf_information'><a href='login.php?action=quit'>退出</a></div>
        <div class='dcol' style='width:900px'><form id='creator' name='creator' action='profile.php?action=modify' method='post' enctype='multipart/form-data'>
        <input type='hidden' name='prooicq' value=''><input type='hidden' name='facetype' value='3'>
        <table><tr><td>头像宽度</td><td><input name='uploadurl[1]' value='140'></td></tr>
        <tr><td>头像高度</td><td><input name='uploadurl[2]' value='200'></td></tr>
        <tr><td>签名</td><td><input name='prohonor' value='合成签名'></td></tr>
        <tr><td>原密码</td><td><input type='password' name='oldpwd' value=''></td></tr></table>
        <input type='submit' name='prosubmit' value='确认修改'></form></div>"""

    @Test fun multipartUploadUsesDedicatedAjaxfaceAndParsesSuccessCallback() = runBlocking<Unit> {
        var bodyText = ""
        val service = HttpForumService(origin, MemoryStore(), Interceptor { chain ->
            if (chain.request().method == "GET") return@Interceptor Response.Builder().request(chain.request())
                .protocol(Protocol.HTTP_1_1).code(200).message("test").body(profileHtml.toResponseBody()).build()
            val buffer = Buffer(); chain.request().body?.writeTo(buffer); bodyText = buffer.readUtf8()
            val html = "<html><script>parent.facepath('179/upload/95/','avatar.jpg','success',140,200);</script></html>"
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("test")
                .body(html.toResponseBody()).build()
        })
        try {
            val result = service.uploadAvatar(AvatarUploadRequest(form,
                LocalUpload("synthetic", "avatar.png", "image/png", png), 140, 200))
            assertIs<AvatarUploadResult.Uploaded>(result)
            assertEquals("$origin/179/upload/95/avatar.jpg", result.avatarUrl)
            assertTrue(bodyText.contains("name=\"upload\""))
            assertTrue(bodyText.contains("name=\"uploadurl[1]\""))
            assertTrue(bodyText.contains("140"))
            assertFalse(bodyText.contains("facepath"))
        } finally { service.close() }
    }

    @Test fun rejectedCallbackAndUnreadableResponseStayDistinct() = runBlocking<Unit> {
        listOf(
            "<script>parent.facepath('x/','a.jpg','undefined_action',140,200);</script>" to AvatarUploadResult.Rejected::class,
            "<p>HTTP 200 但没有任何可核对回调</p>" to AvatarUploadResult.Uncertain::class,
        ).forEach { (html, expected) ->
            val service = HttpForumService(origin, MemoryStore(), Interceptor { chain ->
                val body = if (chain.request().method == "GET") profileHtml else html
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("test")
                    .body(body.toResponseBody()).build()
            })
            try {
                val result = service.uploadAvatar(AvatarUploadRequest(form,
                    LocalUpload("synthetic", "avatar.png", "image/png", png), 140, 200))
                assertTrue(expected.isInstance(result), "expected $expected, got $result")
            } finally { service.close() }
        }
    }

    @Test fun invalidImageMagicAndOversizeAreRejectedBeforeAnyNetwork() = runBlocking<Unit> {
        var requests = 0
        val service = HttpForumService(origin, MemoryStore(), Interceptor { requests++; error("no network") })
        try {
            val bad = LocalUpload("bad", "avatar.png", "image/png", "not an image".toByteArray())
            assertIs<AvatarUploadResult.Rejected>(service.uploadAvatar(AvatarUploadRequest(form, bad, 140, 200)))
            assertIs<AvatarUploadResult.Rejected>(service.uploadAvatar(AvatarUploadRequest(form,
                LocalUpload("big", "avatar.png", "image/png", png + ByteArray(3 * 1024 * 1024)), 140, 200)))
            assertEquals(0, requests)
        } finally { service.close() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun uploadedFileIsNotReportedAsSavedUntilProfileReadbackShowsTheNewAvatar() = runTest {
        val modifyUrl = "$origin/profile.php?action=modify"
        val showUrl = "$origin/profile.php?action=show&uid=1"
        val profileForm = SiteForm("profile", "修改资料", modifyUrl, "$origin/profile.php?action=modify", "POST",
            listOf(FormValue("facetype", "3")), listOf(SiteField("honor", "prohonor", "签名", SiteFieldKind.TEXT)),
            listOf(SiteSubmit("保存", "prosubmit", "确认修改")), multipart = true)
        val demo = DemoService(origin)
        val client = object : ForumService by demo {
            var uploads = 0
            override suspend fun fetch(url: String): PageData = when {
                url == "index.php" -> demo.home
                url == modifyUrl -> demo.home.copy(url = modifyUrl, nativePage = NativeSitePage("修改资料", forms = listOf(profileForm)))
                url == showUrl -> demo.home.copy(url = showUrl, nativePage = NativeSitePage("资料",
                    profile = NativeProfile("合成会员", "$origin/avatar-old.jpg", emptyList())))
                else -> demo.fetch(url)
            }
            override suspend fun uploadAvatar(request: AvatarUploadRequest): AvatarUploadResult {
                uploads++
                return AvatarUploadResult.Uploaded("$origin/avatar-new.jpg")
            }
        }
        val factory = object : ServiceFactory {
            override fun create(origin: String) = client
            override suspend fun catalog(url: String) = error("offline")
        }
        val c = AppController(MemoryStore(), factory, backgroundScope, { _, _ -> })
        runCurrent(); c.load(modifyUrl); runCurrent()
        c.receiveAvatar(LocalUpload("synthetic", "avatar.jpg", "image/jpeg", byteArrayOf(1)))
        c.confirmAvatarUpload(); runCurrent()
        assertEquals(1, client.uploads)
        assertNotNull(c.error)
        assertTrue(c.error!!.contains("尚未显示"))
        assertNull(c.toast)
        c.close()
    }
}

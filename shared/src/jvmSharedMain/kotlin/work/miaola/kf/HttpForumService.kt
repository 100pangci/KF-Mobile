package work.miaola.kf

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MultipartBody
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit

private val Gbk: Charset = Charset.forName("GBK")

/** The forum occasionally serves UTF-8 bytes with a GBK declaration. Trust
 *  the payload when it is unambiguously UTF-8, not only the stale header. */
internal fun decodePageBytes(bytes: ByteArray, declaredName: String?, metaName: String?): String {
    fun strict(charset: Charset): String? = runCatching {
        charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }.getOrNull()
    val declared = listOfNotNull(declaredName, metaName)
        .mapNotNull { runCatching { Charset.forName(it) }.getOrNull() }
        .firstOrNull()
    val utf8 = strict(Charsets.UTF_8)
    if (utf8 != null && utf8.count { it.code in 0x4E00..0x9FFF } >= 3 &&
        (declared == null || declared.name().startsWith("GB", true))) return utf8
    declared?.let { strict(it) }?.let { return it }
    return strict(Charset.forName("GB18030")) ?: bytes.toString(Charsets.UTF_8)
}

/** Avoid InputStream.readNBytes, which is unavailable on older Android APIs. */
fun java.io.InputStream.readBounded(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(minOf(limit, 8192))
    val buffer = ByteArray(8192)
    while (out.size() < limit) {
        val count = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (count < 0) break
        if (count > 0) out.write(buffer, 0, count)
    }
    return out.toByteArray()
}
fun gbkBytes(value: String): ByteArray = try {
    val bytes = Gbk.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
    ByteArray(bytes.remaining()).also { bytes.get(it) }
} catch (_: Exception) { throw IllegalArgumentException("内容含有 GBK 不支持的字符（如部分 emoji），请移除后重试") }

fun gbkForm(fields: Map<String, String>): ByteArray = gbkFormValues(fields.map { FormValue(it.key, it.value) })

fun gbkFormValues(fields: List<FormValue>): ByteArray {
    fun encode(value: String): String = buildString {
        gbkBytes(value).forEach { byte ->
            val c = byte.toInt() and 255
            when {
                c in 65..90 || c in 97..122 || c in 48..57 || c in listOf(45, 95, 46, 42) -> append(c.toChar())
                c == 32 -> append('+')
                else -> { append('%'); append("0123456789ABCDEF"[c shr 4]); append("0123456789ABCDEF"[c and 15]) }
            }
        }
    }
    return fields.joinToString("&") { "${encode(it.name)}=${encode(it.value)}" }.toByteArray(Charsets.US_ASCII)
}

fun nativeMultipart(values: List<FormValue>, files: List<FilePart>): okhttp3.RequestBody = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
    values.forEach { field ->
        require(field.name.matches(Regex("[a-zA-Z0-9_\\[\\]-]+"))) { "不支持的表单字段名" }
        addPart(Headers.headersOf("Content-Disposition", "form-data; name=\"${field.name}\""), gbkBytes(field.value).toRequestBody(null))
    }
    files.forEachIndexed { index, part ->
        require(part.name.matches(Regex("[a-zA-Z0-9_\\[\\]-]+"))) { "不支持的附件字段" }
        val extension = part.file.name.substringAfterLast('.', "bin").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,12}")) } ?: "bin"
        // ASCII multipart filenames avoid legacy GBK/UTF-8 header ambiguity.
        addFormDataPart(part.name, "upload-${index + 1}.$extension", part.file.bytes.toRequestBody(part.file.mimeType.toMediaTypeOrNull()))
    }
}.build()

data class RawPage(val url: String, val html: String, val redirectUrl: String? = null)
class AttachmentPageRequired(val page: PageData) : Exception("附件需要原生详情确认")

class HttpServiceFactory(private val store: KeyValueStore) : ServiceFactory {
    private val services = mutableMapOf<String, HttpForumService>()
    @Synchronized override fun create(origin: String): HttpForumService = services.getOrPut(normalizeOrigin(origin)) { HttpForumService(normalizeOrigin(origin), store) }
    override suspend fun catalog(url: String): String = withContext(Dispatchers.IO) {
        require(isGitHubConfigUrl(url)) { "无效的 GitHub 配置地址" }
        // A separate client with no cookie jar, credentials or redirects.
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(8, TimeUnit.SECONDS).build()
        client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { response ->
            require(response.isSuccessful) { "在线配置暂不可用" }
            val body = response.body ?: error("配置内容为空")
            require(body.contentLength() <= 65536) { "配置文件过大" }
            val bytes = body.byteStream().readBounded(65537)
            require(bytes.size <= 65536) { "配置文件过大" }
            bytes.toString(Charsets.UTF_8)
        }
    }
}

class HttpForumService(override val origin: String, private val store: KeyValueStore, transport: okhttp3.Interceptor? = null) : ForumService {
    val cookieJar = OriginCookieJar(origin, store)
    val parser = ForumHtmlParser(origin)
    private val base = origin.toHttpUrl()
    private var faceScript: String? = null
    private val purchaseMutex = Mutex()
    private val siteMutex = Mutex()
    private val client = OkHttpClient.Builder().cookieJar(cookieJar).followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS).apply { if (transport != null) addInterceptor(transport) }.build()

    fun sameOrigin(url: String): Boolean {
        val u = runCatching { url.toHttpUrl() }.getOrNull() ?: return false
        return u.scheme == base.scheme && u.host == base.host && u.port == base.port && u.username.isEmpty() && u.password.isEmpty()
    }
    private fun resolve(url: String) = parser.resolve(url) ?: error("已阻止向其他域名发送会话，请先在设置中切换域名")

    /** GET-only transient retry: POSTs are never replayed silently. */
    private fun execute(request: Request, retries: Int): okhttp3.Response {
        var attempt = 0
        while (true) {
            try {
                val response = client.newCall(request).execute()
                if (response.code in 502..504 && attempt < retries) {
                    response.close()
                    throw IOException("transient gateway")
                }
                return response
            } catch (e: IOException) {
                if (attempt >= retries) throw e
                attempt++
                Thread.sleep(180L * attempt)
            }
        }
    }

    suspend fun raw(url: String, fields: Map<String, String>? = null, followRedirects: Boolean = true, referer: String = "$origin/", customBody: okhttp3.RequestBody? = null): RawPage = withContext(Dispatchers.IO) {
        var target = resolve(url)
        require(sameOrigin(referer)) { "请求来源域名不匹配" }
        var payload = fields
        repeat(6) {
            val req = Request.Builder().url(target).header("User-Agent", "KFMobile/0.1 (Android; native client)")
                .header("Referer", referer).header("Accept", "text/html,application/xhtml+xml")
            payload?.let { req.header("Origin", origin).post(gbkForm(it).toRequestBody("application/x-www-form-urlencoded".toMediaType())) }
            customBody?.let { req.header("Origin", origin).post(it) }
            execute(req.build(), if (payload == null && customBody == null) 2 else 0).use { response ->
                if (response.code in listOf(301, 302, 303, 307, 308)) {
                    val location = response.header("Location") ?: error("服务器跳转缺少目标地址")
                    val redirected = parser.resolve(location, target) ?: error("已阻止跨域跳转，请在设置中选择目标域名")
                    if (!followRedirects) return@withContext RawPage(response.request.url.toString(), "", redirected)
                    target = redirected
                    require((payload == null && customBody == null) || response.code !in listOf(307, 308)) { "为防止重复操作，已停止自动重发提交" }
                    payload = null
                } else {
                    require(response.isSuccessful) { "服务器响应 ${response.code}，请稍后重试" }
                    val body = response.body ?: error("服务器返回空页面")
                    val bytes = body.byteStream().readBounded(8 * 1024 * 1024 + 1)
                    require(bytes.size <= 8 * 1024 * 1024) { "页面过大，请使用网页模式" }
                    val declared = body.contentType()?.charset()
                    val meta = Regex("charset\\s*=\\s*['\"]?([a-zA-Z0-9_-]+)").find(bytes.take(4096).toByteArray().toString(Charsets.ISO_8859_1))?.groupValues?.get(1)
                    return@withContext RawPage(response.request.url.toString(), decodePageBytes(bytes, declared?.name(), meta))
                }
            }
        }
        error("页面跳转次数过多")
    }

    override suspend fun fetch(url: String): PageData {
        val raw = raw(url)
        return parser.parse(raw.html, raw.url)
    }

    override suspend fun login(username: String, password: String): PageData {
        val login = raw("login.php")
        val (action, defaults) = parser.loginFields(login.html, login.url)
        val result = raw(action, defaults + mapOf("pwuser" to username, "pwpwd" to password))
        val home = fetch("index.php")
        require(home.authenticated) { parser.responseMessage(parser.document(result.html, result.url)).ifBlank { "登录未成功，请检查账号，或使用网页完成验证码" } }
        return home
    }

    override suspend fun search(keyword: String, author: String): PageData {
        val page = raw("index.php")
        val doc = parser.document(page.html, page.url)
        val form = doc.select("form").firstOrNull { it.attr("action").startsWith("search.php") && parser.controls(it).any { e -> e.attr("name") == "keyword" } }
            ?: error("未找到搜索表单，请使用网页搜索")
        val action = parser.resolve(form.attr("action"), page.url) ?: error("搜索地址无效")
        val fields = parser.formFields(form) + mapOf("keyword" to keyword, "pwuser" to author)
        val result = raw(action, fields)
        val resultDoc = parser.document(result.html, result.url)
        val target = parser.refreshTarget(resultDoc)
        return if (target != null) fetch(target) else parser.parse(result.html, result.url)
    }

    override suspend fun editor(url: String): EditorForm {
        val raw = raw(url)
        val form = parser.parseEditor(raw.html, raw.url)
        val faces = try {
            val script = faceScript ?: raw("data/bbscache/face.js").html.also { faceScript = it }
            parser.parseEmoticons(script, raw.html, raw.url)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { emptyList() }
        val fallback = listOf(57 to "高兴", 41 to "大笑", 44 to "锤地", 42 to "不语", 36 to "喝茶", 38 to "睡觉", 43 to "受伤")
            .map { (id, name) -> ForumEmoticon(id, name, "", "常用表情") }
        return form.copy(emoticons = faces.ifEmpty { fallback })
    }

    override suspend fun submit(form: EditorForm, values: Map<String, String>): SubmitResult {
        require(sameOrigin(form.action) && sameOrigin(form.sourceUrl)) { "提交域名不匹配" }
        gbkForm(form.fields + values) // Validate encoding before making any request.
        return try {
            val raw = raw(form.action, form.fields + values)
            val doc = parser.document(raw.html, raw.url)
            val text = parser.responseMessage(doc)
            val target = parser.refreshTarget(doc)
            val accepted = when {
                raw.url.toHttpUrl().pathSegments.lastOrNull() == "read.php" -> raw.url
                target != null && destination(target).effect == SiteEffect.READ -> target
                else -> null
            }
            when {
                // A redirect or server-side refresh into a readable thread is
                // the server's own confirmation; no page copy is inspected.
                accepted != null -> SubmitResult.Success(accepted, "提交成功")
                doc.select("textarea").isNotEmpty() -> SubmitResult.Rejected(text.ifBlank { "站点未接受提交，请检查内容与权限" })
                else -> SubmitResult.Uncertain("服务器返回的提交结果无法确认，请到网页核对后再操作。" + text.take(120))
            }
        } catch (e: CancellationException) { throw e }
        catch (_: IOException) { SubmitResult.Uncertain("网络中断，提交可能已送达。请先到网页核对，不要重复提交。") }
        catch (_: Exception) { SubmitResult.Uncertain("服务器未返回可确认的提交结果。请先到网页核对，不要重复提交。") }
    }

    override suspend fun submitWithFiles(form: EditorForm, values: Map<String, String>, files: List<LocalUpload>): SubmitResult {
        if (files.isEmpty()) return submit(form, values)
        require(form.uploadEnabled && files.size <= form.uploadLimit) { "此页面不支持这些附件" }
        require(files.all { it.size <= form.uploadMaxBytes }) { "附件超过站点大小限制" }
        if (form.uploadExtensions.isNotEmpty()) require(files.all { it.name.substringAfterLast('.', "").lowercase() in form.uploadExtensions }) { "附件类型不符合站点限制" }
        val fields = (form.fields + values).map { FormValue(it.key, it.value) }.toMutableList()
        val parts = files.mapIndexed { index, file ->
            val slot = file.slot.takeIf { it > 0 } ?: index + 1
            fields.add(FormValue("atc_downrvrc$slot", "0"))
            val name = file.name.take(100).let { runCatching { gbkBytes(it); it }.getOrDefault("附件$slot") }
            fields.add(FormValue("atc_desc$slot", name))
            FilePart("attachment_$slot", file)
        }
        require(sameOrigin(form.action)) { "提交域名不匹配" }
        return try {
            val response = raw(form.action, followRedirects = false, referer = form.sourceUrl, customBody = nativeMultipart(fields, parts))
            val doc = parser.document(response.html, response.url)
            val message = parser.responseMessage(doc)
            val target = response.redirectUrl ?: parser.refreshTarget(doc)
            when {
                target != null && destination(target).effect == SiteEffect.READ -> SubmitResult.Success(target, "站点已接收提交，请核对发布结果")
                doc.select("textarea").isNotEmpty() -> SubmitResult.Rejected(message.ifBlank { "站点未接受本次提交" })
                else -> SubmitResult.Uncertain("附件随帖子提交，结果尚未确认。请先查看帖子，不要重复发送。")
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { SubmitResult.Uncertain("连接中断，附件和帖子可能已送达，请先核对，避免重复提交。") }
    }

    override fun destination(url: String, title: String): SiteDestination = SiteRoutePolicy(origin).classify(url, title)

    override suspend fun submitSite(submission: SiteSubmission): SiteActionResult = siteMutex.withLock {
        require(sameOrigin(submission.form.sourceUrl) && sameOrigin(submission.form.actionUrl)) { "已阻止跨域表单" }
        val sourceEffect = destination(submission.form.sourceUrl).effect
        if (sourceEffect !in listOf(SiteEffect.READ, SiteEffect.DOWNLOAD)) return@withLock SiteActionResult.Rejected("此表单来源包含操作参数，不能自动重读；请从功能首页重新进入")
        val fresh = if (sourceEffect == SiteEffect.DOWNLOAD) submission.form else {
            val refreshed = fetch(submission.form.sourceUrl)
            refreshed.nativePage?.forms?.firstOrNull { it.id == submission.form.id }
                ?: return@withLock SiteActionResult.Rejected("表单已变化，请刷新后重试")
        }
        require(fresh.fields.map { it.name to it.kind } == submission.form.fields.map { it.name to it.kind }) { "表单字段已变化，请重新填写" }
        val clicked = fresh.submits.firstOrNull { it.name == submission.submit.name && it.value == submission.submit.value }
            ?: return@withLock SiteActionResult.Rejected("提交选项已变化，请刷新")
        val userValues = submission.values.drop(submission.form.hidden.size).filterNot { it.name == clicked.name }
        val state = fresh.fields.associate { field ->
            field.id to userValues.filter { it.name == field.name }.map { it.value }.let { values ->
                if (field.options.isNotEmpty()) values.filter { value -> field.options.any { it.value == value } } else values
            }
        }
        val values = buildSiteFormValues(fresh, state, clicked)
        val body = if (fresh.multipart || submission.files.isNotEmpty()) nativeMultipart(values, submission.files)
            else gbkFormValues(values).toRequestBody("application/x-www-form-urlencoded".toMediaType())
        if (fresh.method == "GET") {
            require(submission.files.isEmpty()) { "GET 表单不能上传附件" }
            val base = fresh.actionUrl.toHttpUrl()
            val query = listOfNotNull(base.encodedQuery?.takeIf { it.isNotBlank() }, gbkFormValues(values).toString(Charsets.US_ASCII).takeIf { it.isNotBlank() }).joinToString("&")
            performNativeChange(base.newBuilder().encodedQuery(query).build().toString(), fresh.sourceUrl, null)
        } else performNativeChange(fresh.actionUrl, fresh.sourceUrl, body)
    }

    override suspend fun executeSite(destination: SiteDestination): SiteActionResult = siteMutex.withLock {
        require(sameOrigin(destination.url)) { "已阻止跨域操作" }
        require(destination.effect == SiteEffect.CHANGE) { "此目标不是已确认的站点操作" }
        val requested = destination.url.toHttpUrl()
        val target = if (requested.pathSegments.lastOrNull() == "login.php" && requested.queryParameter("action") == "quit") {
            val current = fetch("index.php")
            if (current.authStatus == AuthStatus.LOGIN_REQUIRED) return@withLock SiteActionResult.Response(current)
            current.serverLogout?.url ?: return@withLock SiteActionResult.Rejected("未找到当前会话的退出入口")
        } else destination.url
        performNativeChange(target, "$origin/", null)
    }

    private suspend fun performNativeChange(url: String, source: String, body: okhttp3.RequestBody?): SiteActionResult {
        return try {
            val raw = raw(url, followRedirects = false, referer = source, customBody = body)
            val doc = parser.document(raw.html, raw.url)
            val target = raw.redirectUrl ?: parser.refreshTarget(doc)
            if (target != null && destination(target).effect == SiteEffect.READ) SiteActionResult.Response(fetch(target))
            else SiteActionResult.Response(parser.parse(raw.html, raw.url))
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { SiteActionResult.Uncertain("请求结果无法确认，不会自动重复提交；请刷新后核对。") }
    }

    override fun encodedLength(value: String): Int = gbkBytes(value).size
    override fun clearSession() = cookieJar.clear()
    override fun close() { client.dispatcher.cancelAll() }

    override suspend fun purchase(offer: PurchaseOffer): PurchaseResult = purchaseMutex.withLock {
        require(sameOrigin(offer.threadUrl) && sameOrigin(offer.actionUrl)) { "已阻止跨域购买" }
        val source = offer.threadUrl.toHttpUrl()
        val action = offer.actionUrl.toHttpUrl()
        val tid = source.queryParameter("tid")
        val pid = offer.postId.removePrefix("pid")
        require(source.pathSegments.lastOrNull() == "read.php" && tid?.matches(Regex("[0-9]+")) == true
            && (pid == "tpc" || pid.matches(Regex("[0-9]+"))) && offer.id == "$tid:$pid"
            && action.pathSegments.lastOrNull() == "job.php" && action.queryParameter("action") == "buytopic"
            && action.queryParameter("tid") == tid && action.queryParameter("pid") == pid) { "购买目标与当前帖子不匹配" }
        PurchaseWorkflow(origin, store, read = { url ->
            // A quote/readback must never follow a redirect into a transaction.
            val page = raw(url, followRedirects = false)
            require(page.redirectUrl == null) { "帖子发生跳转，请重新打开后核对登录和购买信息" }
            parser.parse(page.html, page.url)
        }, transact = { fresh ->
            // The site's button performs a state-changing GET. Never replay it
            // or follow its redirects; read back the original thread instead.
            val result = raw(fresh.actionUrl, followRedirects = false, referer = fresh.threadUrl)
            val doc = parser.document(result.html, result.url)
            val accepted = result.redirectUrl != null || parser.refreshTarget(doc) != null
            val message = parser.responseMessage(doc)
            PurchaseReceipt(if (accepted) null else message.ifBlank { "站点拒绝了此次购买" })
        }).purchase(offer)
    }

    suspend fun imageBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        var target = resolve(url)
        repeat(5) {
            execute(Request.Builder().url(target).header("Referer", "$origin/").build(), 1).use { response ->
                if (response.code in listOf(301, 302, 303, 307, 308)) {
                    target = parser.resolve(response.header("Location") ?: return@withContext null, target) ?: return@withContext null
                    if (destination(target).effect !in listOf(SiteEffect.READ, SiteEffect.DOWNLOAD)) return@withContext null
                } else {
                    if (!response.isSuccessful || !response.header("Content-Type").orEmpty().startsWith("image/")) return@withContext null
                    val bytes = response.body?.byteStream()?.readBounded(8 * 1024 * 1024 + 1) ?: return@withContext null
                    return@withContext bytes.takeIf { it.size <= 8 * 1024 * 1024 }
                }
            }
        }
        null
    }

    suspend fun download(url: String, output: java.io.OutputStream) = withContext(Dispatchers.IO) {
        var target = resolve(url)
        repeat(6) {
            client.newCall(Request.Builder().url(target).header("Referer", "$origin/").build()).execute().use { response ->
                if (response.code in listOf(301, 302, 303, 307, 308)) {
                    target = parser.resolve(response.header("Location") ?: error("下载跳转无效"), target) ?: error("跨域下载请在外部浏览器确认")
                    require(destination(target).effect in listOf(SiteEffect.READ, SiteEffect.DOWNLOAD)) { "下载跳转涉及站点操作，已停止自动执行" }
                } else {
                    require(response.isSuccessful) { "下载失败：${response.code}" }
                    if (response.header("Content-Type").orEmpty().contains("text/html")) {
                        val body = response.body ?: error("附件详情为空")
                        val bytes = body.byteStream().readBounded(2 * 1024 * 1024)
                        val charset = body.contentType()?.charset() ?: Charset.forName("GB18030")
                        throw AttachmentPageRequired(parser.parse(bytes.toString(charset), response.request.url.toString()))
                    }
                    response.body?.byteStream()?.use { it.copyTo(output) } ?: error("下载内容为空")
                    return@withContext
                }
            }
        }
        error("下载跳转过多")
    }
}

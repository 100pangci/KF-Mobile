package work.miaola.kf

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assume.assumeTrue

/**
 * Opt-in, explicit full-write coverage for the account the user confirmed as a
 * test account. Every test is separately gated by KF_LIVE_FULL_WRITE_TEST and
 * the exact KF_TEST_ACCOUNT name. Each action is sent once, then read back;
 * none of these tests retries a write.
 */
class LiveWriteCoverageTest {
    private val root = Path.of(System.getProperty("kf.projectRoot"))

    private fun assumeFullWrite() = assumeTrue(
        "Set KF_LIVE_FULL_WRITE_TEST=true and KF_TEST_ACCOUNT=<test account> locally",
        System.getenv("KF_LIVE_FULL_WRITE_TEST") == "true" && System.getenv("CI").isNullOrBlank(),
    )

    private fun confirmedAccount(): String = System.getenv("KF_TEST_ACCOUNT")
        ?: error("Missing KF_TEST_ACCOUNT confirmation")

    private suspend fun withConfirmedService(block: suspend (HttpForumService, String) -> Unit) {
        val account = confirmedAccount()
        val origin = "https://kf.miaola.work"
        val base = "$origin/".toHttpUrl()
        val service = HttpForumService(origin, MemoryStore())
        val file = LocalAuditCookies.file(root, base.host)
        assumeTrue("A locally authorized session is required", Files.exists(file))
        service.cookieJar.saveFromResponse(base, LocalAuditCookies.load(Files.readString(file), origin))
        try {
            val home = service.fetch("index.php")
            assertTrue(home.authenticated, "Local authenticated cookie is expired or invalid.")
            assertTrue(
                home.username == account,
                "Refusing to write: the signed-in account is not the explicitly confirmed test account.",
            )
            block(service, account)
        } finally {
            service.clearSession()
            service.close()
        }
    }

    @Test fun ratingEntryStartsTheLiveRatingWorkflow() = runBlocking {
        assumeFullWrite()
        withConfirmedService { service, _ ->
            // The dashboard is a deliberately sampled list: re-read it a few times
            // until a 未评分帖子 row is sampled, then enter its rating workflow
            // through the same change-confirmation path the UI uses.
            var entry: StatusTimelineEntry? = null
            repeat(8) {
                if (entry == null) {
                    val page = service.fetch("kf_fw_1wkfb.php")
                    entry = page.nativePage?.statusTimeline?.entries.orEmpty().firstOrNull {
                        it.status == StatusKind.AVAILABLE && it.action?.url?.contains("kf_fw_1wkfb.php?do=1") == true
                    }
                }
            }
            if (entry == null) {
                println("Rating entry: no 未评分帖子 row is currently sampled; no write was sent.")
                return@withConfirmedService
            }
            val selected = entry
            val tid = Regex("[?&]tid=([0-9]+)").find(selected.relatedUrl)?.groupValues?.get(1)
                ?: fail("The rating row has no numeric topic id; no write was sent.")
            val action = selected.action ?: fail("The rating row lost its action; no write was sent.")
            assertEquals(SiteEffect.CHANGE, action.effect, "The rating entry must keep its change confirmation.")
            val result = service.executeSite(service.destination(action.url, action.title))
            assertFalse(result is SiteActionResult.Uncertain,
                "The rating entry returned no usable page; check the dashboard manually.")
            if (result is SiteActionResult.Rejected) {
                // The site's only explicit refusal is "该帖已经有评分记录", which
                // means the write was rejected without changing state.
                assertTrue(result.message.contains("评分记录"), "Unexpected refusal: ${result.message}")
                println("Rating entry: the site reports this topic already has a rating record; no state changed.")
                return@withConfirmedService
            }
            assertIs<SiteActionResult.Response>(result)
            val native = result.page.nativePage
                ?: fail("The live rating entry did not parse into a native page; check it manually.")
            // Entering `do=1` starts a 15-minute rating session. The response is
            // the site's rating form, not a pending-record row, so verify the
            // session notice and the native form rather than the ping=2 list.
            val form = native.forms.firstOrNull { candidate ->
                candidate.hidden.any { it.name == "do" && it.value == "1" } &&
                    candidate.hidden.any { it.name == "ptid" && it.value == tid } &&
                    candidate.fields.any { it.name == "psize" }
            } ?: fail("The live rating entry no longer parses into the native rating form; check it manually.")
            // The generic screen must own this page. If the entry page grew a
            // timeline or reward list, NativeTaskScreen would hide the form.
            assertNull(native.statusTimeline, "The rating form page is being dispatched as a record timeline.")
            assertTrue(native.rewards.isEmpty(), "The rating form page is being dispatched as a reward page.")
            assertEquals("帖子评分", form.title)
            assertEquals("GET", form.method)
            assertTrue(form.actionUrl.toHttpUrlOrNull()?.encodedPath == "/kf_fw_1wkfb.php",
                "The rating form action changed away from the entry endpoint.")
            val responseText = buildString {
                append(result.page.notice)
                native.sections.forEach { append(' ').append(it.html) }
                native.tables.forEach { table -> table.rows.forEach { row -> row.forEach { append(' ').append(it.text) } } }
                native.blocks.forEach { block -> if (block is NativeBlock.Section) append(' ').append(block.text) }
            }
            assertTrue(responseText.contains("本次评分") && responseText.contains("提交评分"),
                "The live rating page no longer states the rating session deadline; check it manually.")
            assertTrue(form.hidden.any { it.name == "safeid" && it.value.isNotBlank() },
                "The live rating form no longer carries its session token.")
            // The submission itself is covered by NativeSubmissionTest; this
            // live test stops after entering the session so it never posts a
            // rating based on unverified download contents.
            println("Rating entry: session started for tid=$tid and the native form parsed; no rating was submitted.")
        }
    }

    @Test fun ratingSubmissionHasLiveSideEffect() = runBlocking {
        assumeFullWrite()
        withConfirmedService { service, _ ->
            // Only rate a topic whose size and [自购] flag are declared in its
            // own title. Nothing else is inferred; the form's "无" defaults are
            // left for the two facts the title does not state. One submission
            // is sent, then the entry is re-read to prove the record exists.
            var candidate: StatusTimelineEntry? = null
            var facts: Pair<String, String>? = null
            repeat(20) {
                if (candidate == null) {
                    val page = service.fetch("kf_fw_1wkfb.php")
                    val entry = page.nativePage?.statusTimeline?.entries.orEmpty().firstOrNull {
                        it.status == StatusKind.AVAILABLE &&
                            it.action?.effect == SiteEffect.CHANGE &&
                            it.title.contains("[自购]") &&
                            !it.title.contains("[合购]") &&
                            ratingFacts(it.title) != null
                    }
                    if (entry != null) {
                        candidate = entry
                        facts = ratingFacts(entry.title)
                    }
                }
            }
            val entry = candidate ?: run {
                println("Rating submission: no [自购] row with a title size bracket is currently sampled; no write was sent.")
                return@withConfirmedService
            }
            val (size, unit) = facts ?: fail("The sampled title lost its size bracket; no write was sent.")
            val ptid = entry.action!!.url.toHttpUrlOrNull()?.queryParameter("ptid")
                ?: fail("The rating entry has no numeric topic id; no write was sent.")
            val entered = service.executeSite(service.destination(entry.action.url, entry.action.title))
            assertFalse(entered is SiteActionResult.Uncertain,
                "The rating entry returned no usable page; check the dashboard manually.")
            val enteredPage = (entered as? SiteActionResult.Response)?.page
                ?: fail("The rating entry was refused: ${(entered as SiteActionResult.Rejected).message}")
            val form = enteredPage.nativePage?.forms?.firstOrNull { candidateForm ->
                candidateForm.hidden.any { it.name == "ptid" && it.value == ptid } &&
                    candidateForm.fields.any { it.name == "psize" }
            } ?: fail("The live rating entry no longer parses into the native rating form; no rating was submitted.")
            val unitValue = if (unit in setOf("GB", "G")) "2" else "1"
            val state = form.fields.associate { field ->
                field.id to listOf(when (field.name) {
                    "psize" -> size
                    "psizegb" -> unitValue
                    "pnew" -> "1" // The title does not state 首发/补档; do not claim it.
                    "pgod" -> "2" // The title declares [自购].
                    "ptext" -> "资源体积与[自购]标注按帖子标题填写（$size$unit）。"
                    else -> field.initial.firstOrNull().orEmpty()
                })
            }
            val submit = form.submits.singleOrNull() ?: fail("The live rating form lost its submit control; no rating was submitted.")
            val values = try { buildSiteFormValues(form, state, submit) } catch (e: IllegalArgumentException) {
                fail(e.message ?: "The live rating form changed; no rating was submitted.")
            }
            val submitted = service.submitSite(SiteSubmission(form, values, emptyList(), submit))
            when (submitted) {
                is SiteActionResult.Response -> Unit
                is SiteActionResult.Rejected -> fail("The site refused the rating before writing: ${submitted.message}")
                is SiteActionResult.Uncertain -> fail("The rating submission outcome is unknown; check the rating records manually and do not resend.")
            }
            // A redirect to a READ page is a page receipt, not proof. Re-enter
            // the same session URL: once a record exists the site answers with
            // "该帖已经有评分记录" and no form. No second rating is submitted.
            val readback = service.fetch(form.sourceUrl)
            val readbackText = pageText(readback)
            assertTrue(readbackText.contains("已经有评分记录"),
                "The rating record is not visible after submission; check the topic manually. Receipt: ${pageText(submitted.page).take(200)}")
            assertTrue(readback.nativePage?.forms.orEmpty().none { it.fields.any { field -> field.name == "psize" } },
                "The rating entry still offers a form after submission; check the topic manually.")
            println("Rating submission: tid=$ptid size=$size$unit, [自购]; the site readback reports an existing rating record.")
        }
    }

    private fun ratingFacts(title: String): Pair<String, String>? {
        val match = Regex("""\[([0-9]{1,7}(?:\.[0-9]{1,3})?)\s*(GB|G|MB|M)]""").findAll(title).lastOrNull()
            ?: return null
        return match.groupValues[1] to match.groupValues[2]
    }

    private fun pageText(page: PageData): String = buildString {
        append(page.notice)
        page.nativePage?.sections?.forEach { append(' ').append(it.html) }
        page.nativePage?.tables?.forEach { table -> table.rows.forEach { row -> row.forEach { append(' ').append(it.text) } } }
        page.nativePage?.blocks?.forEach { block -> if (block is NativeBlock.Section) append(' ').append(block.text) }
    }

    @Test fun haloPurchaseHasLiveSideEffect() = runBlocking {
        assumeFullWrite()
        withConfirmedService { service, _ ->
            suspend fun kfb() = service.fetch("profile.php?action=show").nativePage?.profile?.info
                ?.firstOrNull { it.label == "论坛货币" }?.value
            suspend fun contribution() = service.fetch("profile.php?action=show").nativePage?.profile?.info
                ?.firstOrNull { it.label == "贡献数值" }?.value
            val beforeKfb = kfb()
            val beforeContribution = contribution()
            val page = service.fetch("kf_fw_ig_halo.php")
            val actions = page.nativePage?.rewards.orEmpty().filter { it.kind == "halo:buy" }
            val action = actions.firstOrNull { it.endpoint.toHttpUrlOrNull()?.queryParameter("id") == "1" }
                ?: actions.firstOrNull() ?: fail("No halo purchase control is currently offered; no write was sent.")
            val result = service.executeReward(action)
            assertFalse(result is RewardExecutionResult.Rejected, "The site rejected the halo purchase; no retry was sent.")
            val afterKfb = kfb()
            val afterContribution = contribution()
            val id = action.endpoint.toHttpUrlOrNull()?.queryParameter("id")
            if (id == "1") {
                assertNotEquals(beforeKfb, afterKfb, "The 100-KFB halo purchase did not change the KFB balance.")
            } else {
                assertTrue(
                    beforeKfb != afterKfb || beforeContribution != afterContribution,
                    "The halo purchase did not change KFB or contribution balance.",
                )
            }
            val after = service.fetch("kf_fw_ig_halo.php")
            assertTrue(after.nativePage?.rewards.orEmpty().any { it.kind == "halo:buy" },
                "The halo page must still expose its real purchase controls after the readback.")
        }
    }

    @Test fun siteSkinChangeAndRestore() = runBlocking {
        assumeFullWrite()
        withConfirmedService { service, _ ->
            val before = service.fetch("index.php")
            val current = before.homeSkins.firstOrNull { it.selected }
                ?: fail("The homepage did not expose a selected site skin; no write was sent.")
            val alternate = before.homeSkins.firstOrNull { it.id != current.id && it.id != "999" }
                ?: fail("No alternate site skin is offered; no write was sent.")
            val changed = service.executeSite(service.destination(alternate.url, "更换站点背景"))
            assertIs<SiteActionResult.Response>(changed)
            val afterChange = service.fetch("index.php")
            assertEquals(alternate.id, afterChange.homeSkins.firstOrNull { it.selected }?.id,
                "The site did not confirm the skin change.")
            val restoreLink = afterChange.homeSkins.firstOrNull { it.id == current.id }
                ?: fail("The previous skin is no longer offered; check the homepage background manually.")
            val restored = service.executeSite(service.destination(restoreLink.url, "恢复站点背景"))
            assertIs<SiteActionResult.Response>(restored)
            val afterRestore = service.fetch("index.php")
            assertEquals(current.id, afterRestore.homeSkins.firstOrNull { it.selected }?.id,
                "The original site skin was not restored; check the homepage background manually.")
        }
    }

    @Test fun messageSendAndBulkDeleteRoundtrip() = runBlocking {
        assumeFullWrite()
        withConfirmedService { service, account ->
            val profile = service.fetch("profile.php?action=show").nativePage?.profile
                ?: fail("The signed-in profile did not parse; no write was sent.")
            val searchLink = profile.links.firstOrNull {
                val target = it.url.toHttpUrlOrNull() ?: return@firstOrNull false
                target.encodedPath == "/search.php" && target.queryParameter("authorid")?.matches(Regex("[0-9]{1,20}")) == true
            } ?: fail("The profile did not expose a numeric author id; no write was sent.")
            val selfUid = searchLink.url.toHttpUrlOrNull()?.queryParameter("authorid")
                ?: fail("The author id link was invalid; no write was sent.")
            val marker = "KF写回验证${System.currentTimeMillis() % 100000}"
            val editor = service.editor("message.php?action=write&touid=$selfUid")
            val recipient = editor.recipientField ?: fail("The message editor has no recipient field; no write was sent.")
            val sent = service.submit(editor, mapOf(
                recipient to account,
                editor.titleField to marker,
                editor.contentField to "自动写回验证，发送后立即删除。",
            ))
            // A self-addressed message has no outgoing copy on this site (the
            // sendbox stays empty), so a correct client reports Uncertain even
            // though the inbox row exists. Either outcome must be followed by
            // an inbox readback, never by a resend.
            assertTrue(
                sent is SubmitResult.Success || sent is SubmitResult.Uncertain,
                "The send must be confirmed or reported uncertain; got $sent",
            )
            val inbox = service.fetch("message.php?action=receivebox")
            assertTrue(
                inbox.nativePage?.messages.orEmpty().any { it.title.contains(marker) },
                "The self-addressed message is not visible in the receivebox.",
            )
            val form = inbox.nativePage?.forms?.firstOrNull { candidate ->
                candidate.fields.any { it.name == "delid[]" && it.label.contains("KF写回验证") }
            } ?: fail("The receivebox did not expose a delete control for the test message; check the inbox manually.")
            // Remove every marker this test (or an earlier interrupted run)
            // created; never touch a message without the test prefix.
            val targets = form.fields.filter { it.name == "delid[]" && it.label.contains("KF写回验证") }
            val submit = form.submits.firstOrNull() ?: fail("The receivebox delete form has no submit control.")
            val state = form.fields.associate { field ->
                field.id to if (field in targets) field.options.map { it.value } else field.initial
            }
            val values = try { buildSiteFormValues(form, state, submit) } catch (e: IllegalArgumentException) {
                fail(e.message ?: "The receivebox delete form changed; no retry was sent.")
            }
            val deleted = service.submitSite(SiteSubmission(form, values, emptyList(), submit))
            assertIs<SiteActionResult.Response>(deleted)
            val after = service.fetch("message.php?action=receivebox")
            assertFalse(
                after.nativePage?.messages.orEmpty().any { it.title.contains("KF写回验证") },
                "A test message was not deleted; check the receivebox manually.",
            )
        }
    }
}
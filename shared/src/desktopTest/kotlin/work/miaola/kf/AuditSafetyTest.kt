package work.miaola.kf

import kotlin.test.*

class AuditSafetyTest {
    private val origin = "https://kf.miaola.work"
    private val policy = AuditReadPolicy(origin)
    @Test fun unknownMutationParametersAndDownloadsNeverEnterTheCrawler() {
        listOf("index.php?do=claim", "read.php?tid=1&buy=1", "thread.php?fid=1&submit=1",
            "profile.php?action=favor&job=del", "message.php?action=del&mid=1", "post.php?fid=1&step=2",
            "kf_growup.php?ok=1", "kf_growup.php?ok=2&color=1", "hack.php?H_name=bank&action=save",
            "job.php?action=download&aid=1", "fyg_sjcdwj.php?go=fight", "faq.php?action=delete",
            "message.php?action=read&action=del&mid=1", "https://evil.test/index.php").forEach {
            assertFalse(policy.allows(it), "Unsafe route accepted")
        }
        listOf("index.php", "thread.php?fid=1", "read.php?tid=1", "message.php?action=write",
            "profile.php?action=modify", "post.php?action=quote&tid=1&pid=2&fid=1", "hack.php?H_name=bank").forEach {
            assertTrue(policy.allows(it), "Read route blocked")
        }
    }
    @Test fun reportsNeverRetainQueryValuesOrDynamicPathSegments() {
        assertEquals("/read.php?tid=<redacted>&verify=<redacted>", policy.redacted("$origin/read.php?tid=123&verify=private"))
        assertEquals("/<redacted>", policy.redacted("$origin/user/private"))
        assertFalse(policy.type("$origin/profile.php?action=unknown-private").contains("private"))
    }
    @Test fun cookieImportIsOriginScopedAndExpiredCookiesAreDropped() {
        val text = "kf.miaola.work\tFALSE\t/\tTRUE\t0\tsession\tsynthetic\n" +
            "evil.test\tFALSE\t/\tTRUE\t0\tother\tsynthetic\n" +
            "kf.miaola.work\tFALSE\t/\tTRUE\t1\texpired\tsynthetic\n"
        assertEquals(listOf("session"), LocalAuditCookies.load(text, origin).map { it.name })
        assertEquals(listOf("session"), LocalAuditCookies.load("Cookie: session=synthetic", origin).map { it.name })
    }
}

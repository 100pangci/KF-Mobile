package work.miaola.kf

import kotlin.test.*
import kotlinx.serialization.json.Json

class DomainCatalogTest {
    @Test fun builtInIsValid() {
        assertEquals(11, DomainCatalog.BuiltIn.validated().domains.size)
    }
    @Test fun originsAreCanonical() {
        assertEquals("https://kf.miaola.work", normalizeOrigin(" HTTPS://KF.MIAOLA.WORK:443/ "))
        assertEquals("https://example.com:8443", normalizeOrigin("https://example.com:8443"))
    }
    @Test fun rejectUnsafeOrigins() {
        listOf("http://example.com", "https://user:pass@example.com", "https://example.com/path", "https://example.com?x=1",
            "https://example.com#x", "javascript:alert(1)", "https://example.com:99999", "https://example.com:0",
            "https://example.com\\@evil.com", "https://localhost", "https://-example.com").forEach {
            assertFailsWith<IllegalArgumentException>(it) { normalizeOrigin(it) }
        }
    }
    @Test fun rejectBadCatalogs() {
        val valid = DomainCatalog.BuiltIn
        assertFails { valid.copy(schemaVersion = 2).validated() }
        assertFails { valid.copy(domains = emptyList()).validated() }
        assertFails { valid.copy(defaultDomainId = "missing").validated() }
        assertFails { valid.copy(domains = valid.domains + valid.domains.first()).validated() }
        assertFails { valid.copy(domains = listOf(Domain("x", "", "https://example.com"))).validated() }
    }
    @Test fun githubUrlValidation() {
        assertTrue(isGitHubConfigUrl("https://raw.githubusercontent.com/owner/repo/main/config/domains.json"))
        listOf("https://raw.githubusercontent.com.evil.test/a/b/main/domains.json", "https://github.com/a/b", "http://raw.githubusercontent.com/a/b/main/x",
            "https://raw.githubusercontent.com/a/b/main/x?token=secret", "https://raw.githubusercontent.com/a/b/../x").forEach { assertFalse(isGitHubConfigUrl(it), it) }
    }
    @Test fun catalogRoundtrip() {
        assertEquals(DomainCatalog.BuiltIn, Json.decodeFromString<DomainCatalog>(Json.encodeToString(DomainCatalog.BuiltIn)).validated())
    }
}

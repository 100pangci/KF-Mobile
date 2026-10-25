package work.miaola.kf

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

class DomainRepository(private val store: KeyValueStore, private val factory: ServiceFactory) {
    private val json = Json { ignoreUnknownKeys = true }
    fun local(): CatalogResult {
        val cached = store.get("domain_catalog")?.let {
            runCatching { json.decodeFromString<DomainCatalog>(it).validated() }.getOrNull()
        }
        return if (cached != null) CatalogResult(cached, CatalogSource.CACHE)
        else CatalogResult(DomainCatalog.BuiltIn, CatalogSource.BUILT_IN)
    }

    suspend fun refresh(url: String): CatalogResult {
        if (url.isBlank()) return local().copy(warning = "尚未设置 GitHub 配置地址，使用本地列表")
        return try {
            require(isGitHubConfigUrl(url)) { "请使用 raw.githubusercontent.com 的 HTTPS 配置地址" }
            val raw = factory.catalog(url)
            require(raw.length <= 65536) { "配置文件过大" }
            val catalog = json.decodeFromString<DomainCatalog>(raw).validated()
            store.put("domain_catalog", json.encodeToString(catalog))
            CatalogResult(catalog, CatalogSource.ONLINE)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            local().copy(warning = "在线配置不可用，已回退${local().source.label}")
        }
    }
}

fun isGitHubConfigUrl(url: String): Boolean =
    Regex("https://raw\\.githubusercontent\\.com/[a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+/[^?#\\s]+")
        .matches(url) && !url.contains("/../")

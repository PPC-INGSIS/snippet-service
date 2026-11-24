package snippetsearcher.snippets.asset

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Reemplazo del asset service para los tests: guarda en memoria.
 *
 * Con `failWith` se le pide que falle, para probar qué pasa cuando el servicio no responde.
 */
class FakeAssetClient : AssetClient {
    private val contents = ConcurrentHashMap<UUID, String>()

    /** Si no es null, put() falla con este mensaje en vez de guardar. */
    var failWith: String? = null

    override fun put(
        snippetId: UUID,
        content: String,
    ): SaveContentResult {
        failWith?.let { return SaveContentResult.Failed(it) }

        contents[snippetId] = content
        return SaveContentResult.Saved
    }

    override fun get(snippetId: UUID): ContentResult {
        failWith?.let { return ContentResult.Failed(it) }

        return contents[snippetId]?.let { ContentResult.Found(it) } ?: ContentResult.NotFound
    }

    fun clear() {
        contents.clear()
        failWith = null
    }

    fun count() = contents.size
}

@TestConfiguration(proxyBeanMethods = false)
class FakeAssetConfiguration {
    @Bean
    @Primary
    fun fakeAssetClient() = FakeAssetClient()
}

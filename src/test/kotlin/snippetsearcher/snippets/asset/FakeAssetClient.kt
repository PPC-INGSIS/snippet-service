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

    /** Si no es null, put(), get() y delete() fallan con este mensaje en vez de actuar. */
    var failWith: String? = null

    /** Si no es null, solo delete() falla: guarda bien y no puede borrar, para probar una limpieza que falla. */
    var failDeletesWith: String? = null

    /** Cuántas veces se pidió un contenido, para probar quién llama al asset service y quién no. */
    var gets = 0
        private set

    override fun put(
        snippetId: UUID,
        content: String,
    ): SaveContentResult {
        failWith?.let { return SaveContentResult.Failed(it) }

        contents[snippetId] = content
        return SaveContentResult.Saved
    }

    override fun get(snippetId: UUID): ContentResult {
        gets++
        failWith?.let { return ContentResult.Failed(it) }

        return contents[snippetId]?.let { ContentResult.Found(it) } ?: ContentResult.NotFound
    }

    override fun delete(snippetId: UUID): DeleteContentResult {
        (failWith ?: failDeletesWith)?.let { return DeleteContentResult.Failed(it) }

        contents.remove(snippetId)
        return DeleteContentResult.Deleted
    }

    fun clear() {
        contents.clear()
        failWith = null
        failDeletesWith = null
        gets = 0
    }

    fun count() = contents.size
}

@TestConfiguration(proxyBeanMethods = false)
class FakeAssetConfiguration {
    @Bean
    @Primary
    fun fakeAssetClient() = FakeAssetClient()
}

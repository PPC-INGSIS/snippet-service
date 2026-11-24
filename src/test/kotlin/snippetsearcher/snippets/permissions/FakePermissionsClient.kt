package snippetsearcher.snippets.permissions

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Reemplazo de Permisos para los tests: guarda los dueños en memoria.
 *
 * Con `failWith` se le pide que falle, para probar qué pasa cuando Permisos no responde.
 */
class FakePermissionsClient : PermissionsClient {
    private val owners = ConcurrentHashMap<UUID, String>()

    /** Si no es null, registerOwner falla con este mensaje en vez de registrar. */
    var failWith: String? = null

    override fun registerOwner(
        snippetId: UUID,
        ownerId: String,
    ): RegisterOwnerResult {
        failWith?.let { return RegisterOwnerResult.Failed(it) }

        owners[snippetId] = ownerId
        return RegisterOwnerResult.Registered
    }

    fun ownerOf(snippetId: UUID): String? = owners[snippetId]

    fun count() = owners.size

    fun clear() {
        owners.clear()
        failWith = null
    }
}

@TestConfiguration(proxyBeanMethods = false)
class FakePermissionsConfiguration {
    @Bean
    @Primary
    fun fakePermissionsClient() = FakePermissionsClient()
}

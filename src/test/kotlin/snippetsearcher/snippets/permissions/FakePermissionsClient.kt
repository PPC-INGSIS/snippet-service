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
    // snippetId -> (userId -> rol)
    private val accesos = ConcurrentHashMap<UUID, MutableMap<String, String>>()

    /** Si no es null, registerOwner falla con este mensaje en vez de registrar. */
    var failWith: String? = null

    override fun registerOwner(
        snippetId: UUID,
        ownerId: String,
    ): RegisterAccessResult = registrar(snippetId, ownerId, "OWNER")

    override fun registerReader(
        snippetId: UUID,
        userId: String,
    ): RegisterAccessResult = registrar(snippetId, userId, "READ")

    private fun registrar(
        snippetId: UUID,
        userId: String,
        rol: String,
    ): RegisterAccessResult {
        failWith?.let { return RegisterAccessResult.Failed(it) }

        // Igual que Permisos: la clave es (snippet, usuario), asi que no se duplica
        accesos.computeIfAbsent(snippetId) { ConcurrentHashMap() }[userId] = rol
        return RegisterAccessResult.Registered
    }

    fun ownerOf(snippetId: UUID): String? = accesos[snippetId]?.entries?.firstOrNull { it.value == "OWNER" }?.key

    fun rolDe(
        snippetId: UUID,
        userId: String,
    ): String? = accesos[snippetId]?.get(userId)

    fun accesosDe(snippetId: UUID): Int = accesos[snippetId]?.size ?: 0

    fun count() = accesos.size

    fun clear() {
        accesos.clear()
        failWith = null
    }
}

@TestConfiguration(proxyBeanMethods = false)
class FakePermissionsConfiguration {
    @Bean
    @Primary
    fun fakePermissionsClient() = FakePermissionsClient()
}

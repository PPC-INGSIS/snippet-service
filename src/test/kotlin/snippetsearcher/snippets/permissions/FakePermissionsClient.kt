package snippetsearcher.snippets.permissions

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Reemplazo de Permisos para los tests: guarda los accesos en memoria.
 *
 * Con `failWith` se le pide que falle, para probar qué pasa cuando Permisos no responde.
 */
class FakePermissionsClient : PermissionsClient {
    // snippetId -> (userId -> rol)
    private val accesos = ConcurrentHashMap<UUID, MutableMap<String, String>>()

    /** Si no es null, todos los métodos fallan con este mensaje en vez de actuar. */
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

        // Igual que Permisos: la clave es (snippet, usuario) y, si ya existe, responde 409 y no toca
        // el rol. Por eso putIfAbsent: si el dueño comparte consigo mismo, sigue siendo OWNER
        accesos.computeIfAbsent(snippetId) { ConcurrentHashMap() }.putIfAbsent(userId, rol)
        return RegisterAccessResult.Registered
    }

    override fun deleteAccesses(snippetId: UUID): DeleteAccessesResult {
        failWith?.let { return DeleteAccessesResult.Failed(it) }

        accesos.remove(snippetId)
        return DeleteAccessesResult.Deleted
    }

    override fun roleOf(
        snippetId: UUID,
        userId: String,
    ): RoleResult {
        failWith?.let { return RoleResult.Failed(it) }

        return accesos[snippetId]?.get(userId)?.let { RoleResult.Found(it) } ?: RoleResult.None
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

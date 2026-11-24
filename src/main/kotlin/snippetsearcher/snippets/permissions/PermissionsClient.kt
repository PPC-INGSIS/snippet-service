package snippetsearcher.snippets.permissions

import java.util.UUID

/**
 * Lo que Snippets necesita de Permisos.
 *
 * Snippets no sabe quién puede qué: guarda el `ownerId` para sus propias consultas, pero
 * la pregunta "¿este usuario puede hacer esto?" la responde Permisos, que es el que tiene
 * la tabla de accesos.
 */
interface PermissionsClient {
    fun registerOwner(
        snippetId: UUID,
        ownerId: String,
    ): RegisterOwnerResult
}

sealed interface RegisterOwnerResult {
    data object Registered : RegisterOwnerResult

    /** No se pudo registrar: el servicio falló o no contestó. */
    data class Failed(
        val message: String,
    ) : RegisterOwnerResult
}

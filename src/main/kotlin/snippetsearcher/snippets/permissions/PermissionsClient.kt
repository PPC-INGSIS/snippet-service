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
    /** El que creó el snippet: puede todo. */
    fun registerOwner(
        snippetId: UUID,
        ownerId: String,
    ): RegisterAccessResult

    /** Alguien con quien se compartió: solo lee. */
    fun registerReader(
        snippetId: UUID,
        userId: String,
    ): RegisterAccessResult

    /** Saca todos los accesos del snippet: el del dueño y los compartidos. */
    fun deleteAccesses(snippetId: UUID): DeleteAccessesResult
}

sealed interface DeleteAccessesResult {
    data object Deleted : DeleteAccessesResult

    /** No se pudo borrar: el servicio falló o no contestó. */
    data class Failed(
        val message: String,
    ) : DeleteAccessesResult
}

sealed interface RegisterAccessResult {
    data object Registered : RegisterAccessResult

    /** No se pudo registrar: el servicio falló o no contestó. */
    data class Failed(
        val message: String,
    ) : RegisterAccessResult
}

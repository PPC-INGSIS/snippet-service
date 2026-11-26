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

    /** Qué puede hacer ese usuario sobre ese snippet, según Permisos. */
    fun roleOf(
        snippetId: UUID,
        userId: String,
    ): RoleResult

    /** Los snippets que le compartieron a ese usuario (rol READ), según Permisos. */
    fun sharedWith(userId: String): SharedSnippetsResult
}

sealed interface SharedSnippetsResult {
    data class Found(
        val snippetIds: List<UUID>,
    ) : SharedSnippetsResult

    /** No se pudo saber: el servicio falló o no contestó. Distinto de "no le compartieron nada". */
    data class Failed(
        val message: String,
    ) : SharedSnippetsResult
}

sealed interface RoleResult {
    /** El rol tal como lo guarda Permisos: OWNER o READ. */
    data class Found(
        val role: String,
    ) : RoleResult

    /** No tiene ningún acceso. Es una respuesta, no una falla. */
    data object None : RoleResult

    /** No se pudo saber: el servicio falló o no contestó. Distinto de "no tiene acceso". */
    data class Failed(
        val message: String,
    ) : RoleResult
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

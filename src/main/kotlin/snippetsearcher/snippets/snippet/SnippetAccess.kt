package snippetsearcher.snippets.snippet

import org.springframework.stereotype.Service
import snippetsearcher.snippets.permissions.PermissionsClient
import snippetsearcher.snippets.permissions.RoleResult
import java.util.UUID

/**
 * Quién puede leer un snippet: el dueño, o alguien con quien se compartió.
 *
 * Una clase aparte y no un método de SnippetService: es la regla que usan ver el snippet y listar
 * sus tests, y así vive en un solo lugar sin que TestCaseService dependa de todo SnippetService.
 */
@Service
class SnippetAccess(
    private val repository: SnippetRepository,
    private val permissionsClient: PermissionsClient,
) {
    /**
     * Si el usuario puede leer el snippet, sin traer el contenido.
     *
     * Al dueño se lo reconoce por la columna, sin preguntarle a Permisos: es el caso común y así ver
     * lo propio no depende de otro servicio. Si no es el dueño, Permisos decide.
     */
    fun readable(
        userId: String,
        id: UUID,
    ): ReadAccess = repository.findByIdAndOwnerId(id, userId)?.let { ReadAccess.Granted(it) } ?: shared(userId, id)

    private fun shared(
        userId: String,
        id: UUID,
    ): ReadAccess {
        val snippet = repository.findById(id).orElse(null) ?: return ReadAccess.Denied

        return when (val role = permissionsClient.roleOf(id, userId)) {
            // OWNER también: Permisos es el que decide, aunque hoy el dueño ya entró por la columna
            is RoleResult.Found ->
                if (role.role in READABLE_ROLES) ReadAccess.Granted(snippet) else ReadAccess.Denied
            RoleResult.None -> ReadAccess.Denied
            // El detalle ya quedó en el log del cliente
            is RoleResult.Failed -> ReadAccess.Unknown(role.message)
        }
    }

    private companion object {
        val READABLE_ROLES = setOf("OWNER", "READ")
    }
}

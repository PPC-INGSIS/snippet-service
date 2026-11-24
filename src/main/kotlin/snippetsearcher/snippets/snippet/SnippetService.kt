package snippetsearcher.snippets.snippet

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import snippetsearcher.snippets.asset.AssetClient
import snippetsearcher.snippets.asset.ContentResult
import snippetsearcher.snippets.asset.SaveContentResult
import snippetsearcher.snippets.permissions.PermissionsClient
import snippetsearcher.snippets.permissions.RegisterAccessResult
import snippetsearcher.snippets.printscript.PrintScriptClient
import snippetsearcher.snippets.printscript.ValidationResult
import java.util.UUID

@Service
class SnippetService(
    private val repository: SnippetRepository,
    private val printScriptClient: PrintScriptClient,
    private val assetClient: AssetClient,
    private val permissionsClient: PermissionsClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun create(
        ownerId: String,
        newSnippet: NewSnippet,
    ): CreateSnippetResult =
        when (val validation = printScriptClient.validate(newSnippet.version, newSnippet.content)) {
            is ValidationResult.Rejected -> CreateSnippetResult.Rejected(validation.message)
            is ValidationResult.Checked ->
                if (validation.errors.isNotEmpty()) {
                    CreateSnippetResult.Invalid(validation.errors)
                } else {
                    store(ownerId, newSnippet)
                }
        }

    /**
     * Busca el snippet del dueño y le suma el contenido, que vive en el asset service.
     *
     * Primero la fila: si no es del usuario no hace falta molestar al asset service.
     */
    fun find(
        ownerId: String,
        id: UUID,
    ): FindSnippetResult {
        val snippet = repository.findByIdAndOwnerId(id, ownerId) ?: return FindSnippetResult.NotFound

        return when (val content = assetClient.get(id)) {
            is ContentResult.Found -> FindSnippetResult.Found(snippet, content.content)
            // Crear guarda el contenido antes que la fila, así que una fila sin contenido no debería existir.
            // Si pasa, el log es el único rastro: al usuario le llega un error genérico
            ContentResult.NotFound -> {
                log.warn("El snippet {} existe pero su contenido no está en el asset service", id)
                FindSnippetResult.ContentMissing
            }
            is ContentResult.Failed -> FindSnippetResult.ContentUnavailable(content.message)
        }
    }

    /**
     * Le da acceso de lectura a otro usuario.
     *
     * Solo el dueño puede compartir: la consulta ya filtra por dueño, así que si el
     * snippet no es suyo ni se consulta a Permisos. Compartir dos veces con el mismo
     * usuario no falla ni duplica, porque la clave en Permisos es (snippet, usuario)
     * y el 409 cuenta como registrado.
     */
    fun share(
        ownerId: String,
        id: UUID,
        targetUserId: String,
    ): ShareSnippetResult {
        if (targetUserId.isBlank()) return ShareSnippetResult.BlankTargetUser

        val snippet =
            repository.findByIdAndOwnerId(id, ownerId) ?: run {
                log.info("No se compartió el snippet {}: no existe o no es de este usuario", id)
                return ShareSnippetResult.NotFound
            }

        return when (val registrado = permissionsClient.registerReader(id, targetUserId)) {
            is RegisterAccessResult.Failed -> {
                log.warn("No se compartió el snippet {}: Permisos no registró el acceso", id)
                ShareSnippetResult.PermissionsUnavailable(registrado.message)
            }
            is RegisterAccessResult.Registered -> {
                // Cambio de estado: alguien mas puede leer este snippet
                log.info("Snippet {} compartido con {}", id, targetUserId)
                ShareSnippetResult.Shared(snippet)
            }
        }
    }

    /**
     * Contenido, dueño y recién al final la fila.
     *
     * **Lo que hace visible al snippet es la fila en Postgres**: todo lo que se escriba
     * antes, si algo falla después, queda huérfano y nadie lo ve. Al revés, una fila cuyo
     * contenido o cuyo dueño no existen se rompe recién cuando alguien la lee, y hasta
     * entonces nadie se entera.
     *
     * El precio son datos huérfanos: un archivo en el bucket o un acceso en Permisos que
     * no referencia a ningún snippet. Es basura, mucho más barata que una fila rota.
     */
    private fun store(
        ownerId: String,
        newSnippet: NewSnippet,
    ): CreateSnippetResult {
        val id = UUID.randomUUID()

        when (val saved = assetClient.put(id, newSnippet.content)) {
            is SaveContentResult.Failed -> return CreateSnippetResult.ContentNotSaved(saved.message)
            is SaveContentResult.Saved -> Unit
        }

        when (val registered = permissionsClient.registerOwner(id, ownerId)) {
            is RegisterAccessResult.Failed -> return CreateSnippetResult.OwnerNotRegistered(registered.message)
            is RegisterAccessResult.Registered -> Unit
        }

        val snippet =
            Snippet(
                id = id,
                ownerId = ownerId,
                name = newSnippet.name,
                description = newSnippet.description,
                language = newSnippet.language,
                version = newSnippet.version,
            )
        return CreateSnippetResult.Created(repository.save(snippet))
    }
}

// Lo que manda el usuario para crear un snippet. El dueño va aparte porque no lo elige él: sale del token
data class NewSnippet(
    val name: String,
    val description: String,
    val language: String,
    val version: String,
    val content: String,
)

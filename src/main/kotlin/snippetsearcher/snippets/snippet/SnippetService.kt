package snippetsearcher.snippets.snippet

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import snippetsearcher.snippets.asset.AssetClient
import snippetsearcher.snippets.asset.ContentResult
import snippetsearcher.snippets.asset.SaveContentResult
import snippetsearcher.snippets.permissions.PermissionsClient
import snippetsearcher.snippets.permissions.RegisterOwnerResult
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
            is RegisterOwnerResult.Failed -> return CreateSnippetResult.OwnerNotRegistered(registered.message)
            is RegisterOwnerResult.Registered -> Unit
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

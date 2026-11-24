package snippetsearcher.snippets.snippet

import org.springframework.stereotype.Service
import snippetsearcher.snippets.asset.AssetClient
import snippetsearcher.snippets.asset.SaveContentResult
import snippetsearcher.snippets.printscript.PrintScriptClient
import snippetsearcher.snippets.printscript.ValidationResult
import java.util.UUID

@Service
class SnippetService(
    private val repository: SnippetRepository,
    private val printScriptClient: PrintScriptClient,
    private val assetClient: AssetClient,
) {
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
     * Guarda primero el contenido y después la fila.
     *
     * El orden es lo que importa: si el asset service falla, no llegamos a escribir en la
     * base y no queda un snippet sin cuerpo. Al revés, una fila cuyo contenido no existe
     * se rompe recién al leerla, y hasta entonces nadie se entera.
     *
     * El precio es que si falla el save, el contenido queda huérfano en el bucket: basura
     * que nadie referencia, bastante más barata que una fila rota.
     */
    private fun store(
        ownerId: String,
        newSnippet: NewSnippet,
    ): CreateSnippetResult {
        val id = UUID.randomUUID()

        return when (val saved = assetClient.put(id, newSnippet.content)) {
            is SaveContentResult.Failed -> CreateSnippetResult.ContentNotSaved(saved.message)
            is SaveContentResult.Saved -> {
                val snippet =
                    Snippet(
                        id = id,
                        ownerId = ownerId,
                        name = newSnippet.name,
                        description = newSnippet.description,
                        language = newSnippet.language,
                        version = newSnippet.version,
                    )
                CreateSnippetResult.Created(repository.save(snippet))
            }
        }
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

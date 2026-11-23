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
        name: String,
        description: String,
        language: String,
        version: String,
        content: String,
    ): CreateSnippetResult =
        when (val validation = printScriptClient.validate(version, content)) {
            is ValidationResult.Rejected -> CreateSnippetResult.Rejected(validation.message)
            is ValidationResult.Checked ->
                if (validation.errors.isNotEmpty()) {
                    CreateSnippetResult.Invalid(validation.errors)
                } else {
                    store(name, description, language, version, content)
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
        name: String,
        description: String,
        language: String,
        version: String,
        content: String,
    ): CreateSnippetResult {
        val id = UUID.randomUUID()

        return when (val saved = assetClient.put(id, content)) {
            is SaveContentResult.Failed -> CreateSnippetResult.ContentNotSaved(saved.message)
            is SaveContentResult.Saved ->
                CreateSnippetResult.Created(repository.save(Snippet(id, name, description, language, version)))
        }
    }
}

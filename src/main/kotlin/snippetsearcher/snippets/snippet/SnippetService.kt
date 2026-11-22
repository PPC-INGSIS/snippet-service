package snippetsearcher.snippets.snippet

import org.springframework.stereotype.Service
import snippetsearcher.snippets.printscript.PrintScriptClient
import snippetsearcher.snippets.printscript.ValidationResult
import java.util.UUID

@Service
class SnippetService(
    private val repository: SnippetRepository,
    private val printScriptClient: PrintScriptClient,
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
                    val snippet =
                        Snippet(
                            UUID.randomUUID(),
                            ownerId,
                            newSnippet.name,
                            newSnippet.description,
                            newSnippet.language,
                            newSnippet.version,
                        )
                    CreateSnippetResult.Created(repository.save(snippet))
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

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
                    val snippet = Snippet(UUID.randomUUID(), name, description, language, version)
                    CreateSnippetResult.Created(repository.save(snippet))
                }
        }
}

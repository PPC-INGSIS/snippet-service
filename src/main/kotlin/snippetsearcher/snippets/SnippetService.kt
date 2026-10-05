package snippetsearcher.snippets

import org.springframework.stereotype.Service
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
    ): CreateSnippetResult {
        val errors = printScriptClient.validate(version, content)
        if (errors.isNotEmpty()) {
            return CreateSnippetResult.Invalid(errors)
        }

        val snippet = Snippet(UUID.randomUUID(), name, description, language, version)
        return CreateSnippetResult.Created(repository.save(snippet))
    }
}

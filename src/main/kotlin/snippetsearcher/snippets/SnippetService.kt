package snippetsearcher.snippets

import org.springframework.stereotype.Service
import java.util.UUID

@Service
class SnippetService(
    private val repository: SnippetRepository,
) {
    fun create(
        name: String,
        description: String,
        language: String,
        version: String,
    ): Snippet {
        val snippet = Snippet(UUID.randomUUID(), name, description, language, version)
        return repository.save(snippet)
    }
}

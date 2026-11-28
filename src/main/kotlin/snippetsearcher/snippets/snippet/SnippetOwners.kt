package snippetsearcher.snippets.snippet

import org.springframework.stereotype.Component
import snippetsearcher.snippets.testcase.TestRunSnippetOwner
import java.util.UUID

/**
 * De quién es un snippet. Es lo único que necesitan los trabajos en segundo plano del paquete de
 * tests, así que acá se implementa su puerto y no se les expone el repositorio entero.
 */
@Component
class SnippetOwners(
    private val repository: SnippetRepository,
) : TestRunSnippetOwner {
    override fun ownerOf(snippetId: UUID): String? = repository.findById(snippetId).orElse(null)?.ownerId
}

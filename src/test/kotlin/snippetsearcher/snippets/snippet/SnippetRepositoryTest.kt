package snippetsearcher.snippets.snippet

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import snippetsearcher.snippets.TestcontainersConfiguration
import java.util.UUID
import kotlin.test.assertEquals

@Import(TestcontainersConfiguration::class)
@SpringBootTest
class SnippetRepositoryTest(
    @Autowired private val repository: SnippetRepository,
) {
    @Test
    fun `guarda un snippet y lo recupera por id`() {
        val snippet = Snippet(UUID.randomUUID(), "Saludo", "Pide un nombre", "printscript", "1.1")

        repository.save(snippet)
        val found = repository.findById(snippet.id).orElseThrow()

        assertEquals("Saludo", found.name)
        assertEquals("Pide un nombre", found.description)
        assertEquals("printscript", found.language)
        assertEquals("1.1", found.version)
    }

    @Test
    fun `actualiza los datos de un snippet`() {
        val snippet = repository.save(Snippet(UUID.randomUUID(), "Saludo", "v1", "printscript", "1.0"))

        snippet.name = "Saludo final"
        snippet.description = "v2"
        snippet.language = "printscript"
        snippet.version = "1.1"
        repository.save(snippet)
        val found = repository.findById(snippet.id).orElseThrow()

        assertEquals("Saludo final", found.name)
        assertEquals("v2", found.description)
        assertEquals("1.1", found.version)
    }
}

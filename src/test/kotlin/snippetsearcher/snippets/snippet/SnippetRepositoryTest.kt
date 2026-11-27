package snippetsearcher.snippets.snippet

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import snippetsearcher.snippets.TestcontainersConfiguration
import java.util.UUID
import kotlin.test.assertEquals

@Import(TestcontainersConfiguration::class)
@SpringBootTest
class SnippetRepositoryTest(
    @Autowired private val repository: SnippetRepository,
    @Autowired private val jdbc: JdbcTemplate,
) {
    @Test
    fun `guarda un snippet y lo recupera por id`() {
        val snippet =
            Snippet(
                id = UUID.randomUUID(),
                ownerId = "auth0|ana",
                name = "Saludo",
                description = "Pide un nombre",
                language = "printscript",
                version = "1.1",
            )

        repository.save(snippet)
        val found = repository.findById(snippet.id).orElseThrow()

        assertEquals("auth0|ana", found.ownerId)
        assertEquals("Saludo", found.name)
        assertEquals("Pide un nombre", found.description)
        assertEquals("printscript", found.language)
        assertEquals("1.1", found.version)
    }

    @Test
    fun `actualiza los datos de un snippet`() {
        val snippet =
            repository.save(
                Snippet(
                    id = UUID.randomUUID(),
                    ownerId = "auth0|ana",
                    name = "Saludo",
                    description = "v1",
                    language = "printscript",
                    version = "1.0",
                ),
            )

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

    // Por SQL y sin la columna, como las filas que ya existían antes de la migración
    @Test
    fun `un snippet insertado sin estado de linteo queda pending`() {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO snippets (id, owner_id, name, description, language, version) VALUES (?, ?, ?, ?, ?, ?)",
            id,
            "auth0|ana",
            "Viejo",
            "de antes",
            "printscript",
            "1.1",
        )

        assertEquals(LintStatus.PENDING, repository.findById(id).orElseThrow().lintStatus)
    }
}

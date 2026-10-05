package snippetsearcher.snippets

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@Import(TestcontainersConfiguration::class)
@SpringBootTest
class SnippetServiceTest(
    @Autowired private val service: SnippetService,
    @Autowired private val repository: SnippetRepository,
) {
    @Test
    fun `crea un snippet y lo deja guardado`() {
        val created = service.create("Saludo", "Pide un nombre", "printscript", "1.1")

        val found = repository.findById(created.id).orElseThrow()

        assertEquals("Saludo", found.name)
        assertEquals("Pide un nombre", found.description)
        assertEquals("printscript", found.language)
        assertEquals("1.1", found.version)
    }

    @Test
    fun `cada snippet creado recibe un id distinto`() {
        val first = service.create("Uno", "primero", "printscript", "1.1")
        val second = service.create("Dos", "segundo", "printscript", "1.1")

        assertNotEquals(first.id, second.id)
    }
}

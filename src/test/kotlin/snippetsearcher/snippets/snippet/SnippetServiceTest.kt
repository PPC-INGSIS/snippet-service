package snippetsearcher.snippets.snippet

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import snippetsearcher.snippets.TestcontainersConfiguration
import snippetsearcher.snippets.printscript.FakePrintScriptClient
import snippetsearcher.snippets.printscript.FakePrintScriptConfiguration
import snippetsearcher.snippets.printscript.ValidationError
import snippetsearcher.snippets.printscript.ValidationResult
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

@Import(TestcontainersConfiguration::class, FakePrintScriptConfiguration::class)
@SpringBootTest
class SnippetServiceTest(
    @Autowired private val service: SnippetService,
    @Autowired private val repository: SnippetRepository,
    @Autowired private val printScript: FakePrintScriptClient,
) {
    @BeforeEach
    fun codigoValidoPorDefecto() {
        printScript.result = ValidationResult.Checked(emptyList())
    }

    @Test
    fun `crea un snippet valido y lo deja guardado`() {
        val result = service.create("Saludo", "Pide un nombre", "printscript", "1.1", "println(1);")

        val created = assertIs<CreateSnippetResult.Created>(result)
        val found = repository.findById(created.snippet.id).orElseThrow()
        assertEquals("Saludo", found.name)
        assertEquals("Pide un nombre", found.description)
        assertEquals("printscript", found.language)
        assertEquals("1.1", found.version)
    }

    @Test
    fun `cada snippet creado recibe un id distinto`() {
        val first =
            assertIs<CreateSnippetResult.Created>(service.create("Uno", "primero", "printscript", "1.1", "println(1);"))
        val second =
            assertIs<CreateSnippetResult.Created>(service.create("Dos", "segundo", "printscript", "1.1", "println(2);"))

        assertNotEquals(first.snippet.id, second.snippet.id)
    }

    @Test
    fun `no guarda un snippet con codigo invalido`() {
        printScript.result = ValidationResult.Checked(listOf(ValidationError("Falta el punto y coma", 1, 11)))
        val before = repository.count()

        val result = service.create("Roto", "No compila", "printscript", "1.1", "println(1)")

        val invalid = assertIs<CreateSnippetResult.Invalid>(result)
        assertEquals("Falta el punto y coma", invalid.errors.single().message)
        assertEquals(before, repository.count())
    }

    @Test
    fun `no guarda un snippet si PrintScript rechaza el pedido`() {
        printScript.result = ValidationResult.Rejected("La versión '1.3' no existe")
        val before = repository.count()

        val result = service.create("Saludo", "Pide un nombre", "printscript", "1.3", "println(1);")

        val rejected = assertIs<CreateSnippetResult.Rejected>(result)
        assertEquals("La versión '1.3' no existe", rejected.message)
        assertEquals(before, repository.count())
    }
}

package snippetsearcher.snippets.snippet

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import snippetsearcher.snippets.TestcontainersConfiguration
import snippetsearcher.snippets.asset.ContentResult
import snippetsearcher.snippets.asset.FakeAssetClient
import snippetsearcher.snippets.asset.FakeAssetConfiguration
import snippetsearcher.snippets.printscript.FakePrintScriptClient
import snippetsearcher.snippets.printscript.FakePrintScriptConfiguration
import snippetsearcher.snippets.printscript.ValidationError
import snippetsearcher.snippets.printscript.ValidationResult
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

@Import(TestcontainersConfiguration::class, FakePrintScriptConfiguration::class, FakeAssetConfiguration::class)
@SpringBootTest
class SnippetServiceTest(
    @Autowired private val service: SnippetService,
    @Autowired private val repository: SnippetRepository,
    @Autowired private val printScript: FakePrintScriptClient,
    @Autowired private val asset: FakeAssetClient,
) {
    private val owner = "auth0|ana"

    private val saludo =
        NewSnippet(
            name = "Saludo",
            description = "Pide un nombre",
            language = "printscript",
            version = "1.1",
            content = "println(1);",
        )

    @BeforeEach
    fun codigoValidoPorDefecto() {
        printScript.result = ValidationResult.Checked(emptyList())
        asset.clear()
    }

    @Test
    fun `crea un snippet valido y lo deja guardado con su dueno`() {
        val result = service.create(owner, saludo)

        val created = assertIs<CreateSnippetResult.Created>(result)
        val found = repository.findById(created.snippet.id).orElseThrow()
        assertEquals("auth0|ana", found.ownerId)
        assertEquals("Saludo", found.name)
        assertEquals("Pide un nombre", found.description)
        assertEquals("printscript", found.language)
        assertEquals("1.1", found.version)
    }

    @Test
    fun `cada snippet creado recibe un id distinto`() {
        val first = assertIs<CreateSnippetResult.Created>(service.create(owner, saludo))
        val second = assertIs<CreateSnippetResult.Created>(service.create(owner, saludo.copy(name = "Otro")))

        assertNotEquals(first.snippet.id, second.snippet.id)
    }

    @Test
    fun `no guarda un snippet con codigo invalido`() {
        printScript.result = ValidationResult.Checked(listOf(ValidationError("Falta el punto y coma", 1, 11)))
        val before = repository.count()

        val result = service.create(owner, saludo.copy(content = "println(1)"))

        val invalid = assertIs<CreateSnippetResult.Invalid>(result)
        assertEquals("Falta el punto y coma", invalid.errors.single().message)
        assertEquals(before, repository.count())
    }

    @Test
    fun `no guarda un snippet si PrintScript rechaza el pedido`() {
        printScript.result = ValidationResult.Rejected("La versión '1.3' no existe")
        val before = repository.count()

        val result = service.create(owner, saludo.copy(version = "1.3"))

        val rejected = assertIs<CreateSnippetResult.Rejected>(result)
        assertEquals("La versión '1.3' no existe", rejected.message)
        assertEquals(before, repository.count())
    }

    @Test
    fun `el contenido queda guardado en el asset service y no en la base`() {
        val result = service.create(owner, saludo)

        val created = assertIs<CreateSnippetResult.Created>(result)
        val content = assertIs<ContentResult.Found>(asset.get(created.snippet.id))
        assertEquals("println(1);", content.content)
    }

    // El criterio del ticket: no puede quedar un snippet sin contenido
    @Test
    fun `si el asset service falla no se guarda el snippet`() {
        asset.failWith = "connection refused"
        val before = repository.count()

        val result = service.create(owner, saludo)

        val failed = assertIs<CreateSnippetResult.ContentNotSaved>(result)
        assertEquals("connection refused", failed.message)
        assertEquals(before, repository.count())
    }

    // Si el código no compila no se llama al asset service: no hay contenido que guardar
    @Test
    fun `un snippet invalido no escribe contenido`() {
        printScript.result = ValidationResult.Checked(listOf(ValidationError("Falta el punto y coma", 1, 11)))

        service.create(owner, saludo.copy(content = "println(1)"))

        assertEquals(0, asset.count())
    }
}

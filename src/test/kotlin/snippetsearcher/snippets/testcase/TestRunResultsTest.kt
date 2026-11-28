package snippetsearcher.snippets.testcase

import com.jayway.jsonpath.JsonPath
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import snippetsearcher.snippets.TestcontainersConfiguration
import snippetsearcher.snippets.asset.FakeAssetConfiguration
import snippetsearcher.snippets.permissions.FakePermissionsConfiguration
import snippetsearcher.snippets.printscript.ExecutionResult
import snippetsearcher.snippets.printscript.FakePrintScriptClient
import snippetsearcher.snippets.printscript.FakePrintScriptConfiguration
import snippetsearcher.snippets.snippet.CreateSnippetResult
import snippetsearcher.snippets.snippet.NewSnippet
import snippetsearcher.snippets.snippet.SnippetService
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Lo que la API cuenta del último resultado de cada test.
 *
 * Aparte de `TestRunJobTest` porque es otra cosa: ahí se prueba el trabajo y acá lo que se puede
 * consultar después, que es el tercer criterio de la US 16. Mismas propiedades que ese test, así que
 * Spring reusa su contexto.
 */
@Import(
    TestcontainersConfiguration::class,
    FakePrintScriptConfiguration::class,
    FakeAssetConfiguration::class,
    FakePermissionsConfiguration::class,
)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "snippets.test-runs.stream=snippets.test-runs.job",
        "snippets.test-runs.group=snippets-job",
    ],
)
class TestRunResultsTest(
    @Autowired private val snippets: SnippetService,
    @Autowired private val testCases: TestCaseRepository,
    @Autowired private val job: TestRunJob,
    @Autowired private val ledger: TestRunLedger,
    @Autowired private val printScript: FakePrintScriptClient,
    @Autowired private val mockMvc: MockMvc,
) {
    // El fake lo comparten las dos clases de tests (mismo contexto de Spring): sin cargarlo acá, lo
    // que imprime el snippet depende de qué test corrió antes
    @BeforeEach
    fun cargarElFake() {
        printScript.executionResult = ExecutionResult.Ran(emptyList(), null)
    }

    @Test
    fun `el listado de tests muestra el ultimo resultado y si esta pendiente`() {
        val id = guardarSnippet()
        guardarTest(id, espera = emptyList())
        ledger.enqueue(id)
        job.run(id)

        val cuerpo = listar(id)

        assertEquals(true, JsonPath.read(cuerpo, "$[0].result.passed"), "el resultado no viaja en el listado")
        assertEquals(false, JsonPath.read(cuerpo, "$[0].pending"), "el trabajo terminó: no está pendiente")

        // Encolado de nuevo: lo que dice result puede quedar viejo en cualquier momento
        ledger.enqueue(id)
        assertEquals(true, JsonPath.read(listar(id), "$[0].pending"), "un trabajo encolado está pendiente")
    }

    @Test
    fun `un test que nunca corrio no trae resultado`() {
        val id = guardarSnippet()
        guardarTest(id, espera = emptyList())

        val cuerpo = listar(id)

        assertNull(JsonPath.read<Any?>(cuerpo, "$[0].result"), "un test sin correr no puede traer resultado")
        assertEquals(false, JsonPath.read(cuerpo, "$[0].pending"))
    }

    @Test
    fun `editar un test olvida su resultado`() {
        val id = guardarSnippet()
        val test = guardarTest(id, espera = emptyList())
        ledger.enqueue(id)
        job.run(id)
        assertEquals(true, JsonPath.read(listar(id), "$[0].result.passed"))

        editar(id, test, """{"name":"un test","input":[],"output":["otra cosa"]}""")

        // El resultado guardado era de un test que esperaba otra cosa: mostrar "pasó" sería mentir
        assertNull(ledger.resultsOf(listOf(test))[test])
        assertNull(JsonPath.read<Any?>(listar(id), "$[0].result"))
    }

    private fun editar(
        snippetId: UUID,
        testCaseId: UUID,
        cuerpo: String,
    ) {
        mockMvc
            .put("/snippets/$snippetId/tests/$testCaseId") {
                with(jwt().jwt { it.subject(DUENO) }.authorities(SimpleGrantedAuthority("SCOPE_write:snippets")))
                contentType = MediaType.APPLICATION_JSON
                content = cuerpo
            }.andExpect { status { isOk() } }
    }

    private fun listar(snippetId: UUID): String =
        mockMvc
            .get("/snippets/$snippetId/tests") {
                with(jwt().jwt { it.subject(DUENO) }.authorities(SimpleGrantedAuthority("SCOPE_read:snippets")))
            }.andReturn()
            .response
            .contentAsString

    private fun guardarSnippet(): UUID {
        val creado = snippets.create(DUENO, NewSnippet("Saludo", "Imprime 1", "printscript", "1.1", "println(1);"))
        return assertIs<CreateSnippetResult.Created>(creado).snippet.id
    }

    private fun guardarTest(
        snippetId: UUID,
        espera: List<String>,
    ): UUID =
        testCases
            .save(TestCase(UUID.randomUUID(), snippetId, "un test", emptyList(), espera, Instant.now()))
            .id

    private companion object {
        const val DUENO = "auth0|ana"
    }
}

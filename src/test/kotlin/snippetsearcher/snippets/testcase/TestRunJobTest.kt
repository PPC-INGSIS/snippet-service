package snippetsearcher.snippets.testcase

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import snippetsearcher.snippets.TestcontainersConfiguration
import snippetsearcher.snippets.asset.FakeAssetConfiguration
import snippetsearcher.snippets.language.ValidationResult
import snippetsearcher.snippets.permissions.FakePermissionsConfiguration
import snippetsearcher.snippets.printscript.ExecutionResult
import snippetsearcher.snippets.printscript.FakePrintScriptClient
import snippetsearcher.snippets.printscript.FakePrintScriptConfiguration
import snippetsearcher.snippets.snippet.CreateSnippetResult
import snippetsearcher.snippets.snippet.NewSnippet
import snippetsearcher.snippets.snippet.SnippetService
import snippetsearcher.snippets.snippet.UpdateSnippetResult
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Correr los tests de un snippet en segundo plano al actualizarlo (US 16).
 *
 * El primer test va por la cola de verdad —el Redis del Testcontainer, el productor y el consumidor—
 * para probar que el cableado existe. Los demás llaman a [TestRunJob] derecho: lo que prueban es qué
 * pasa cuando algo falla, y eso tiene que ser determinístico y no depender de cuándo llega un mensaje.
 *
 * Stream y grupo propios: cada contexto de Spring registra su consumidor en el grupo, y en un grupo de
 * Redis cada mensaje le llega a uno solo. Sin esto, el consumidor de otro contexto se lleva el trabajo
 * y el primer test queda esperando un resultado que corrió en otro lado.
 */
@Import(
    TestcontainersConfiguration::class,
    FakePrintScriptConfiguration::class,
    FakeAssetConfiguration::class,
    FakePermissionsConfiguration::class,
)
@SpringBootTest
@TestPropertySource(
    properties = [
        "snippets.test-runs.stream=snippets.test-runs.job",
        "snippets.test-runs.group=snippets-job",
    ],
)
class TestRunJobTest(
    @Autowired private val snippets: SnippetService,
    @Autowired private val testCases: TestCaseRepository,
    @Autowired private val runs: SnippetTestRunRepository,
    @Autowired private val job: TestRunJob,
    @Autowired private val ledger: TestRunLedger,
    @Autowired private val printScript: FakePrintScriptClient,
) {
    @BeforeEach
    fun cargarLosFakes() {
        printScript.result = ValidationResult.Checked(emptyList())
        printScript.executionResult = ExecutionResult.Ran(listOf("1"), null)
    }

    @Test
    fun `actualizar un snippet corre sus tests y deja el resultado de cada uno`() {
        val id = guardarSnippet()
        val quePasa = guardarTest(id, "el que pasa", espera = listOf("1"))
        val queFalla = guardarTest(id, "el que falla", espera = listOf("otra cosa"))

        val resultado = snippets.update(DUENO, id, cambios("println(1);"))

        assertIs<UpdateSnippetResult.Updated>(resultado, "no se actualizó el snippet")
        // Corre en otro thread: el update no lo espera, que es justamente el punto de la US
        esperar { runs.findById(id).orElse(null)?.status == TestRunStatus.FINISHED }

        assertTrue(resultadoDe(quePasa).passed, "el test que coincide tendría que pasar")
        // Y el que falla no frenó al otro: los dos tienen resultado
        assertFalse(resultadoDe(queFalla).passed, "el test que no coincide tendría que fallar")
        assertEquals(listOf("1"), resultadoDe(queFalla).outputs, "sin los outputs no se puede comparar")
    }

    @Test
    fun `un update rechazado no encola nada`() {
        val id = guardarSnippet()
        guardarTest(id, "un test", espera = listOf("1"))
        printScript.result = ValidationResult.Rejected("la versión 9.9 no existe")

        val resultado = snippets.update(DUENO, id, cambios("println(1);", version = "9.9"))

        assertIs<UpdateSnippetResult.Rejected>(resultado, "el update tendría que haber sido rechazado")
        assertNull(runs.findById(id).orElse(null), "se encoló un trabajo para un cambio que no quedó")
    }

    @Test
    fun `si PrintScript no contesta el trabajo queda para reintentar`() {
        val id = guardarSnippet()
        val test = guardarTest(id, "un test", espera = listOf("1"))
        printScript.executionResult = ExecutionResult.Failed("PrintScript no respondió")

        ledger.enqueue(id)
        job.run(id)

        val run = assertNotNull(runs.findById(id).orElse(null))
        assertEquals(TestRunStatus.QUEUED, run.status, "tendría que quedar encolado de nuevo")
        assertEquals(1, run.attempts, "tendría que contar el intento")
        assertNull(run.startedAt, "un trabajo que no está corriendo no tiene lease")
        // Un trabajo que falla no deja un test "fallido": no se corrió nada
        assertNull(ledger.resultsOf(listOf(test))[test], "guardó el resultado de un test que no corrió")
    }

    @Test
    fun `cuando se agotan los intentos el trabajo queda fallido y no se vuelve a correr`() {
        val id = guardarSnippet()
        guardarTest(id, "un test", espera = listOf("1"))
        printScript.executionResult = ExecutionResult.Failed("PrintScript no respondió")

        ledger.enqueue(id)
        repeat(MAX_ATTEMPTS) { job.run(id) }

        assertEquals(TestRunStatus.FAILED, runs.findById(id).orElse(null)?.status)

        // Y un mensaje que llegue después no lo vuelve a intentar: un snippet que falla siempre no
        // puede quedarse reintentando para siempre ni tapar a los demás
        printScript.executionResult = ExecutionResult.Ran(listOf("1"), null)
        job.run(id)

        val run = assertNotNull(runs.findById(id).orElse(null))
        assertEquals(TestRunStatus.FAILED, run.status)
        assertEquals(MAX_ATTEMPTS, run.attempts)
    }

    @Test
    fun `un test que no se puede correr queda asentado como fallido y el trabajo sigue`() {
        val id = guardarSnippet()
        val test = guardarTest(id, "un test", espera = listOf("1"))
        // Rechazado, no caído: el código no se puede ejecutar así como está y reintentar da lo mismo
        printScript.executionResult = ExecutionResult.Rejected("el código es demasiado grande")

        ledger.enqueue(id)
        job.run(id)

        assertEquals(TestRunStatus.FINISHED, runs.findById(id).orElse(null)?.status)
        val resultado = resultadoDe(test)
        assertFalse(resultado.passed)
        assertEquals("el código es demasiado grande", resultado.error, "sin el motivo nadie sabe qué pasó")
    }

    @Test
    fun `un snippet borrado antes de que llegue el mensaje no rompe nada`() {
        val id = guardarSnippet()
        ledger.enqueue(id)
        snippets.delete(DUENO, id)

        job.run(id)

        // La fila del trabajo se fue con el snippet por la FK
        assertNull(runs.findById(id).orElse(null))
    }

    private fun guardarSnippet(): UUID {
        val creado = snippets.create(DUENO, cambios("println(1);"))
        return assertIs<CreateSnippetResult.Created>(creado).snippet.id
    }

    private fun guardarTest(
        snippetId: UUID,
        nombre: String,
        espera: List<String>,
    ): UUID =
        testCases
            .save(TestCase(UUID.randomUUID(), snippetId, nombre, emptyList(), espera, Instant.now()))
            .id

    private fun resultadoDe(testCaseId: UUID): TestCaseResult =
        assertNotNull(ledger.resultsOf(listOf(testCaseId))[testCaseId], "no quedó el resultado del test")

    private fun cambios(
        contenido: String,
        version: String = "1.1",
    ) = NewSnippet("Saludo", "Imprime 1", "printscript", version, contenido)

    // El trabajo corre en otro thread: se espera a que la condición se cumpla, con tope
    private fun esperar(condicion: () -> Boolean) {
        val limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < limite) {
            if (condicion()) return
            Thread.sleep(50)
        }
        assertTrue(condicion(), "el trabajo no terminó en 10 s")
    }

    private companion object {
        const val DUENO = "auth0|ana"

        // El default de snippets.test-runs.max-attempts
        const val MAX_ATTEMPTS = 3
    }
}

package snippetsearcher.snippets.testcase

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import snippetsearcher.snippets.TestcontainersConfiguration
import snippetsearcher.snippets.asset.FakeAssetClient
import snippetsearcher.snippets.asset.FakeAssetConfiguration
import snippetsearcher.snippets.permissions.FakePermissionsClient
import snippetsearcher.snippets.permissions.FakePermissionsConfiguration
import snippetsearcher.snippets.printscript.ExecutionResult
import snippetsearcher.snippets.printscript.FakePrintScriptClient
import snippetsearcher.snippets.printscript.FakePrintScriptConfiguration
import snippetsearcher.snippets.snippet.Snippet
import snippetsearcher.snippets.snippet.SnippetRepository
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Correr un test: lo que se prueba acá es la comparación y quién puede correrlo.
 *
 * PrintScript es el fake: lo que importa no es que ejecute PrintScript de verdad —eso ya está
 * testeado en printscript-service— sino qué hacemos con lo que devuelve.
 */
@Import(
    TestcontainersConfiguration::class,
    FakePrintScriptConfiguration::class,
    FakeAssetConfiguration::class,
    FakePermissionsConfiguration::class,
)
@SpringBootTest
class TestRunServiceTest(
    @Autowired private val runner: TestRunService,
    @Autowired private val repository: SnippetRepository,
    @Autowired private val asset: FakeAssetClient,
    @Autowired private val printScript: FakePrintScriptClient,
    @Autowired private val permissions: FakePermissionsClient,
) {
    @BeforeEach
    fun limpiarLosFakes() {
        asset.clear()
        printScript.clear()
        permissions.clear()
    }

    @Test
    fun `un test cuyos outputs coinciden pasa`() {
        val id = guardar(dueno)
        printScript.executionResult = ExecutionResult.Ran(listOf("8"), null)

        val result = assertIs<RunTestResult.Ran>(runner.run(dueno, id, TestRun(listOf("5", "3"), listOf("8"))))

        assertTrue(result.passed)
        assertEquals(listOf("8"), result.outputs)
        assertEquals(listOf("8"), result.expected)
        assertNull(result.error)
    }

    // Falla, pero devuelve los dos lados: sin lo que imprimió, el que escribió el test no tiene con qué comparar
    @Test
    fun `un test cuyos outputs no coinciden falla y devuelve los dos lados`() {
        val id = guardar(dueno)
        printScript.executionResult = ExecutionResult.Ran(listOf("9"), null)

        val result = assertIs<RunTestResult.Ran>(runner.run(dueno, id, TestRun(emptyList(), listOf("8"))))

        assertFalse(result.passed)
        assertEquals(listOf("9"), result.outputs)
        assertEquals(listOf("8"), result.expected)
    }

    // "Coinciden en orden": los mismos outputs en otro orden no son el mismo resultado
    @Test
    fun `los mismos outputs en otro orden fallan`() {
        val id = guardar(dueno)
        printScript.executionResult = ExecutionResult.Ran(listOf("uno", "dos"), null)

        val result = assertIs<RunTestResult.Ran>(runner.run(dueno, id, TestRun(emptyList(), listOf("dos", "uno"))))

        assertFalse(result.passed)
    }

    @Test
    fun `menos outputs de los esperados falla`() {
        val id = guardar(dueno)
        printScript.executionResult = ExecutionResult.Ran(listOf("uno"), null)

        val result = assertIs<RunTestResult.Ran>(runner.run(dueno, id, TestRun(emptyList(), listOf("uno", "dos"))))

        assertFalse(result.passed)
    }

    /**
     * Si el snippet se cortó antes de terminar, el test no pasa aunque lo que llegó a imprimir
     * coincida: lo que imprimió es lo de hasta ahí, no el resultado del snippet.
     */
    @Test
    fun `si el snippet se corto por falta de inputs el test no pasa aunque los outputs coincidan`() {
        val id = guardar(dueno)
        printScript.executionResult = ExecutionResult.Ran(listOf("8"), "El snippet pidió 2 inputs y el test tenía 1")

        val result = assertIs<RunTestResult.Ran>(runner.run(dueno, id, TestRun(listOf("5"), listOf("8"))))

        assertFalse(result.passed)
        assertEquals("El snippet pidió 2 inputs y el test tenía 1", result.error)
    }

    @Test
    fun `manda el contenido y la version del snippet, y los inputs del test`() {
        val id = guardar(dueno)

        runner.run(dueno, id, TestRun(listOf("5", "3"), listOf("8")))

        assertEquals("println(1);", printScript.lastContent)
        assertEquals("1.1", printScript.lastVersion)
        assertEquals(listOf("5", "3"), printScript.lastInputs)
    }

    @Test
    fun `el snippet de otro usuario no se puede correr`() {
        val id = guardar(dueno)

        val result = runner.run("auth0|beto", id, TestRun(emptyList(), emptyList()))

        assertIs<RunTestResult.SnippetNotFound>(result)
        // No se gastó una llamada a PrintScript en algo que el usuario no puede leer
        assertNull(printScript.lastContent)
    }

    @Test
    fun `un snippet que no existe no se puede correr`() {
        val result = runner.run(dueno, UUID.randomUUID(), TestRun(emptyList(), emptyList()))

        assertIs<RunTestResult.SnippetNotFound>(result)
    }

    // El acceso de lectura alcanza: no hace falta ser el dueño (US 6 para los snippets compartidos)
    @Test
    fun `quien tiene el snippet compartido puede correr sus tests`() {
        val id = guardar(dueno)
        permissions.registerReader(id, "auth0|beto")
        printScript.executionResult = ExecutionResult.Ran(listOf("8"), null)

        val result = assertIs<RunTestResult.Ran>(runner.run("auth0|beto", id, TestRun(emptyList(), listOf("8"))))

        assertTrue(result.passed)
    }

    @Test
    fun `si PrintScript no contesta no se sabe si el test pasa`() {
        val id = guardar(dueno)
        printScript.executionResult = ExecutionResult.Failed("connection refused")

        assertIs<RunTestResult.Unavailable>(runner.run(dueno, id, TestRun(emptyList(), emptyList())))
    }

    @Test
    fun `si PrintScript rechaza el pedido el resultado es un rechazo, no una falla`() {
        val id = guardar(dueno)
        printScript.executionResult = ExecutionResult.Rejected("La versión '1.3' no existe")

        val result = assertIs<RunTestResult.Rejected>(runner.run(dueno, id, TestRun(emptyList(), emptyList())))

        assertEquals("La versión '1.3' no existe", result.message)
    }

    @Test
    fun `si no se puede leer el contenido no se corre nada`() {
        val id = guardar(dueno)
        asset.failWith = "connection refused"

        assertIs<RunTestResult.Unavailable>(runner.run(dueno, id, TestRun(emptyList(), emptyList())))
        assertNull(printScript.lastContent)
    }

    private val dueno = "auth0|ana"

    // Un snippet guardado con su contenido en el asset service, listo para correr
    private fun guardar(ownerId: String): UUID {
        val id = UUID.randomUUID()
        asset.put(id, "println(1);")
        repository.save(Snippet(id, ownerId, "Saludo", "Pide un nombre", "printscript", "1.1"))
        return id
    }
}

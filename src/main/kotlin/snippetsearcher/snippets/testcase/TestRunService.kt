package snippetsearcher.snippets.testcase

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import snippetsearcher.snippets.printscript.ExecutionResult
import snippetsearcher.snippets.printscript.PrintScriptClient
import snippetsearcher.snippets.snippet.FindSnippetResult
import snippetsearcher.snippets.snippet.SnippetService
import java.util.UUID

/**
 * Corre un test: ejecuta el snippet con los inputs del test y compara lo que imprimió.
 *
 * Aparte de `TestCaseService` a propósito: ese guarda tests, este ejecuta snippets. Lo único
 * que comparten es el snippet, y el día que correr tests se complique (varios tests de una,
 * en segundo plano) no arrastra al CRUD.
 *
 * Quien tiene acceso de lectura puede correr los tests, no solo el dueño: eso lo resuelve
 * `SnippetService.find`, que ya decide si el usuario puede leer el snippet —por la columna de
 * dueño o por Permisos— y además trae el contenido. Acá no se vuelve a decidir nada de eso.
 */
@Service
class TestRunService(
    private val snippets: SnippetService,
    private val printScript: PrintScriptClient,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run(
        userId: String,
        snippetId: UUID,
        test: TestRun,
    ): RunTestResult =
        when (val found = snippets.find(userId, snippetId)) {
            is FindSnippetResult.Found -> execute(found, test)

            FindSnippetResult.NotFound -> RunTestResult.SnippetNotFound

            // Ya quedó en el log de SnippetService: acá solo se traduce
            FindSnippetResult.ContentMissing -> RunTestResult.ContentMissing

            is FindSnippetResult.ContentUnavailable -> RunTestResult.Unavailable(found.message)

            is FindSnippetResult.AccessUnknown -> RunTestResult.Unavailable(found.message)
        }

    /**
     * La versión sale del snippet y no del test: el test no elige con qué versión correr.
     *
     * Un test pasa solo si el snippet terminó. Si se cortó a mitad de camino —pidió un input que
     * el test no tenía, se pasó del límite de output— los outputs de hasta ahí podrían coincidir
     * por casualidad con los esperados, y eso no es un test que pasa.
     */
    private fun execute(
        found: FindSnippetResult.Found,
        test: TestRun,
    ): RunTestResult =
        when (val execution = printScript.execute(found.snippet.version, found.content, test.inputs)) {
            is ExecutionResult.Rejected -> RunTestResult.Rejected(execution.message)

            is ExecutionResult.Failed -> RunTestResult.Unavailable(execution.message)

            is ExecutionResult.Ran -> {
                val passed = execution.error == null && execution.outputs == test.expectedOutputs
                log.info("Test corrido en el snippet {}: {}", found.snippet.id, if (passed) "pasó" else "falló")
                RunTestResult.Ran(passed, execution.outputs, test.expectedOutputs, execution.error)
            }
        }
}

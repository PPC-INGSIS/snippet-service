package snippetsearcher.snippets.testcase

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.util.UUID

/**
 * El estado de los trabajos de tests y el resultado de cada test. Solo la base, nada de ejecutar.
 *
 * Es la tabla de tareas de la teórica (enfoques #4 y #5) y **la fuente de verdad**: la librería de la
 * cátedra confirma cada mensaje al entregarlo (`receiveAutoAck`), así que un consumidor que se cae no
 * lo recupera. Lo que quedó sin hacer se sabe mirando acá, no preguntándole a Redis.
 *
 * Aparte de [TestRunJob] por dos razones. Una de diseño: esto es el registro y eso es el trabajo. Y
 * una concreta: `TestRunJob` necesita `TestRunService`, que necesita `SnippetService`, que es justo
 * quien encola. Con todo en una clase, el grafo de beans queda circular y Spring no arranca.
 */
@Service
class TestRunLedger(
    private val runs: SnippetTestRunRepository,
    private val results: TestCaseResultRepository,
    transactionManager: PlatformTransactionManager,
    @Value("\${snippets.test-runs.max-attempts}") private val maxAttempts: Int,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Programática y no con @Transactional: estos métodos se llaman desde `TestRunJob.run`, y cada
    // uno tiene que commitear por su cuenta para que los resultados parciales se vean en el momento
    private val transactions = TransactionTemplate(transactionManager)

    /** Deja asentado que hay tests por correr. Se escribe **antes** de publicar el mensaje. */
    fun enqueue(snippetId: UUID) {
        transactions.executeWithoutResult {
            val run = runs.findById(snippetId).orElse(null)
            if (run == null) {
                runs.save(SnippetTestRun(snippetId, TestRunStatus.QUEUED, 0, null, clock.instant()))
            } else {
                // Un update nuevo reinicia los intentos: lo que falló antes era con otro contenido
                run.status = TestRunStatus.QUEUED
                run.attempts = 0
                run.startedAt = null
                run.updatedAt = clock.instant()
            }
        }
    }

    /** Pasa el trabajo a RUNNING y cuenta el intento. False si no hay trabajo que hacer. */
    fun take(snippetId: UUID): Boolean =
        transactions.execute {
            val run = runs.findById(snippetId).orElse(null) ?: return@execute false
            if (run.status == TestRunStatus.FAILED) {
                log.info("No se corrieron los tests del snippet {}: el trabajo ya estaba agotado", snippetId)
                return@execute false
            }
            run.status = TestRunStatus.RUNNING
            run.attempts += 1
            run.startedAt = clock.instant()
            run.updatedAt = clock.instant()
            true
        }

    fun finish(snippetId: UUID) {
        transactions.executeWithoutResult {
            val run = runs.findById(snippetId).orElse(null) ?: return@executeWithoutResult
            run.status = TestRunStatus.FINISHED
            run.startedAt = null
            run.updatedAt = clock.instant()
        }
    }

    /**
     * El trabajo no se pudo hacer: queda para reintentar, salvo que se hayan agotado los intentos.
     *
     * Volver a QUEUED no republica el mensaje —ese ya se confirmó solo al entregarse— sino que deja la
     * fila lista para que la vuelva a encolar el barrido de #36. Hasta entonces el estado dice la
     * verdad: hay tests que no corrieron.
     */
    fun retryLater(
        snippetId: UUID,
        motivo: String,
    ) {
        val agotado =
            transactions.execute {
                val run = runs.findById(snippetId).orElse(null) ?: return@execute false
                run.startedAt = null
                run.updatedAt = clock.instant()
                val agotado = run.attempts >= maxAttempts
                run.status = if (agotado) TestRunStatus.FAILED else TestRunStatus.QUEUED
                agotado
            }

        if (agotado) {
            log.warn("Se agotaron los {} intentos con los tests del snippet {}: {}", maxAttempts, snippetId, motivo)
        } else {
            log.info("Quedaron para reintentar los tests del snippet {}: {}", snippetId, motivo)
        }
    }

    /** Guarda cómo salió un test. Se sobreescribe: es el último resultado, no un historial. */
    fun save(
        testCaseId: UUID,
        passed: Boolean,
        outputs: List<String>,
        error: String?,
    ) {
        transactions.executeWithoutResult {
            val existente = results.findById(testCaseId).orElse(null)
            if (existente == null) {
                results.save(TestCaseResult(testCaseId, passed, outputs, error, clock.instant()))
            } else {
                existente.passed = passed
                existente.outputs = outputs
                existente.error = error
                existente.ranAt = clock.instant()
            }
        }
    }

    /**
     * Olvida el resultado de un test.
     *
     * Se llama al editarlo: si cambiaron sus inputs o lo que espera, el resultado guardado es de otro
     * test. Mostrar "pasó" al lado de un test que acaba de cambiar es peor que no mostrar nada.
     */
    fun forget(testCaseId: UUID) {
        transactions.executeWithoutResult { results.deleteById(testCaseId) }
    }

    /** Si los resultados de este snippet pueden quedar viejos en cualquier momento. */
    fun pending(snippetId: UUID): Boolean = runs.findById(snippetId).orElse(null)?.status?.pendiente ?: false

    fun resultsOf(testCaseIds: List<UUID>): Map<UUID, TestCaseResult> =
        results.findAllById(testCaseIds).associateBy { it.testCaseId }
}

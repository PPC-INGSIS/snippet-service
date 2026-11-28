package snippetsearcher.snippets.testcase

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Corre todos los tests de un snippet y guarda cómo salió cada uno.
 *
 * Es el trabajo en sí, sin Redis: lo llama el consumidor, y así se prueba sin cola. Lo que decide de
 * verdad está acá:
 *
 * **Un test que falla no es un trabajo que falla.** Si el snippet imprimió otra cosa, eso es un
 * resultado: se guarda y no se reintenta nunca. Si lo que falla es la máquina de al lado —PrintScript
 * caído, el contenido que no está— no hay resultado, y el trabajo queda para reintentar. Confundir las
 * dos cosas hace que un test que legítimamente falla se reintente para siempre.
 *
 * **Resultados parciales.** Cada test se guarda al terminar, en su propia transacción, no todos juntos
 * al final: si el trabajo se corta por la mitad, lo que ya corrió se ve. Es el upside del enfoque #3
 * de la teórica, y es también por qué no hay una transacción que envuelva todo: duraría lo que duren N
 * ejecuciones en PrintScript.
 *
 * **Corre como el dueño del snippet.** No hay usuario en este hilo: el acceso lo resuelve la columna
 * de dueño, así que no hay que preguntarle nada a Permisos, que es lo único que necesitaría el token
 * de un usuario. A PrintScript se llega con el token propio de Snippets (#50).
 */
@Service
class TestRunJob(
    private val testCases: TestCaseRepository,
    private val runner: TestRunService,
    private val owners: TestRunSnippetOwner,
    private val ledger: TestRunLedger,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Corre los tests del snippet.
     *
     * No lanza nada: lo llama un consumidor de Redis, y una excepción que se escape de ahí **corta la
     * suscripción** y el consumidor deja de procesar todo lo demás.
     */
    fun run(snippetId: UUID) {
        val ownerId = owners.ownerOf(snippetId)
        if (ownerId == null) {
            // Se borró entre el update y el mensaje. La fila del trabajo se fue con él por la FK
            log.info("No se corrieron los tests del snippet {}: ya no existe", snippetId)
            return
        }

        if (!ledger.take(snippetId)) return

        val tests = testCases.findBySnippetIdOrderByCreatedAtAscIdAsc(snippetId)
        // Para en la primera falla de afuera: si PrintScript no está, los que siguen tampoco van a correr
        val fallaDeAfuera = tests.firstNotNullOfOrNull { correr(ownerId, snippetId, it) }

        if (fallaDeAfuera == null) {
            ledger.finish(snippetId)
            log.info("Corrieron {} test(s) del snippet {}", tests.size, snippetId)
        } else {
            ledger.retryLater(snippetId, fallaDeAfuera)
        }
    }

    /**
     * Corre un test y guarda su resultado. Devuelve el motivo si **el trabajo** no se pudo hacer.
     *
     * Un test que no pasa devuelve null: el trabajo salió bien, el resultado es que el test falló.
     */
    private fun correr(
        ownerId: String,
        snippetId: UUID,
        test: TestCase,
    ): String? =
        when (val resultado = runner.run(ownerId, snippetId, TestRun(test.inputs, test.outputs))) {
            is RunTestResult.Ran -> {
                ledger.save(test.id, resultado.passed, resultado.outputs, resultado.error)
                null
            }
            // El snippet no se puede correr así como está (la versión no existe, el código es muy
            // grande): reintentar da lo mismo y el usuario ya vio el error al guardar. Queda asentado
            // como test fallido, con el motivo, y el trabajo sigue con los demás
            is RunTestResult.Rejected -> {
                ledger.save(test.id, passed = false, outputs = emptyList(), error = resultado.message)
                null
            }
            RunTestResult.SnippetNotFound -> "el snippet dejó de estar accesible"
            RunTestResult.ContentMissing -> "falta el contenido del snippet"
            is RunTestResult.Unavailable -> resultado.message
        }
}

/**
 * De quién es un snippet, para correr sus tests sin un usuario en el pedido.
 *
 * Un puerto chico en vez de depender de `SnippetRepository` entero: el trabajo solo necesita el dueño,
 * y así este paquete no arrastra el de snippets.
 */
fun interface TestRunSnippetOwner {
    fun ownerOf(snippetId: UUID): String?
}

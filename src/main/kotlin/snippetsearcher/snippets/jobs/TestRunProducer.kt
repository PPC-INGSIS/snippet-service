package snippetsearcher.snippets.jobs

import org.austral.ingsis.redis.RedisStreamProducer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Component
import snippetsearcher.snippets.testcase.TestRunLedger
import snippetsearcher.snippets.testcase.TestRunQueue
import java.util.UUID

/**
 * Encola el trabajo de correr los tests de un snippet: lo asienta en la base y publica el mensaje.
 *
 * **En ese orden, y los dos después del commit del update.** La fila del trabajo es lo que hace que el
 * trabajo exista: si se publicara el mensaje primero y la base fallara, un consumidor podría tomar un
 * trabajo que no está asentado en ningún lado, y no hay forma de despublicar un mensaje.
 *
 * Si falla el publicar, el trabajo queda igual en QUEUED y lo levanta el barrido de #36: es
 * exactamente el mismo caso que un consumidor que se muere, así que no hay nada especial que hacer.
 */
@Component
class TestRunProducer(
    @Value("\${snippets.test-runs.stream}") stream: String,
    redis: RedisTemplate<String, String>,
    private val ledger: TestRunLedger,
) : RedisStreamProducer(stream, redis),
    TestRunQueue {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun enqueue(snippetId: UUID) {
        ledger.enqueue(snippetId)
        // El mensaje es el id y nada más: el consumidor lee el snippet de la base, así que no puede
        // trabajar sobre datos viejos que viajaron en el mensaje
        runCatching { emit<String>(snippetId.toString()) }
            .onFailure { log.warn("No se pudo publicar el trabajo de los tests del snippet {}", snippetId, it) }
    }
}

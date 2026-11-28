package snippetsearcher.snippets.jobs

import org.austral.ingsis.redis.RedisStreamConsumer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.connection.stream.ObjectRecord
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.stream.StreamReceiver
import org.springframework.stereotype.Component
import snippetsearcher.snippets.testcase.TestRunJob
import java.util.UUID

/**
 * Toma los trabajos de correr los tests de un snippet.
 *
 * Es solo transporte: lee el id del mensaje y le pasa el trabajo a [TestRunJob].
 *
 * **Nada puede escaparse de `onMessage`.** La librería de la cátedra se suscribe con
 * `Flux.subscribe(consumer)` sin manejador de error: una excepción que se escape no corta este mensaje
 * sino **la suscripción entera**, y el consumidor deja de procesar todo lo que venga después. De ahí el
 * `runCatching`, que es lo único que hace que la falla de un snippet no frene a los demás.
 *
 * Y por eso el `TestRunJob` tampoco lanza: lo que acá se atrapa es el imprevisto, no el camino normal.
 */
@Component
class TestRunConsumer(
    @Value("\${snippets.test-runs.stream}") stream: String,
    @Value("\${snippets.test-runs.group}") group: String,
    redis: RedisTemplate<String, String>,
    private val job: TestRunJob,
) : RedisStreamConsumer<String>(stream, group, redis) {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun options(): StreamReceiver.StreamReceiverOptions<String, ObjectRecord<String, String>> =
        StreamReceiver.StreamReceiverOptions
            .builder()
            .targetType(String::class.java)
            .build()

    override fun onMessage(record: ObjectRecord<String, String>) {
        runCatching {
            val snippetId = UUID.fromString(record.value)
            job.run(snippetId)
        }.onFailure { log.error("No se pudo procesar el trabajo de tests '{}'", record.value, it) }
    }
}

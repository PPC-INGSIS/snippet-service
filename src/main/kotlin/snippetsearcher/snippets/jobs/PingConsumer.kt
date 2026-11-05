package snippetsearcher.snippets.jobs

import org.austral.ingsis.redis.RedisStreamConsumer
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.connection.stream.ObjectRecord
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.data.redis.stream.StreamReceiver
import org.springframework.stereotype.Component
import java.util.concurrent.CopyOnWriteArrayList

// Del spike de Redis: se reemplaza en el PR de la cola de trabajos
@Component
class PingConsumer(
    @Value("\${spike.ping.stream}") stream: String,
    @Value("\${spike.ping.group}") group: String,
    redis: RedisTemplate<String, String>,
) : RedisStreamConsumer<String>(stream, group, redis) {
    private val logger = LoggerFactory.getLogger(PingConsumer::class.java)

    val received = CopyOnWriteArrayList<String>()

    override fun options(): StreamReceiver.StreamReceiverOptions<String, ObjectRecord<String, String>> =
        StreamReceiver.StreamReceiverOptions
            .builder()
            .targetType(String::class.java)
            .build()

    // Si una excepción se escapa de acá, la librería corta la suscripción
    override fun onMessage(record: ObjectRecord<String, String>) {
        runCatching {
            logger.info("Ping recibido: {}", record.value)
            received.add(record.value)
        }.onFailure { exception -> logger.error("No se pudo procesar el ping", exception) }
    }
}

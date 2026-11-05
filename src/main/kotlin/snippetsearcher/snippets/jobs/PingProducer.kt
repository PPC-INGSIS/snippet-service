package snippetsearcher.snippets.jobs

import org.austral.ingsis.redis.RedisStreamProducer
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Component

// Del spike de Redis: se reemplaza en el PR de la cola de trabajos
@Component
class PingProducer(
    @Value("\${spike.ping.stream}") stream: String,
    redis: RedisTemplate<String, String>,
) : RedisStreamProducer(stream, redis) {
    fun emit(text: String) {
        // Con el tipo explícito llama al emit de la librería y no a este mismo
        emit<String>(text)
    }
}

package snippetsearcher.snippets.jobs

import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import snippetsearcher.snippets.TestcontainersConfiguration
import java.time.Duration
import java.util.UUID
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class)
@SpringBootTest
class PingRoundTripTest(
    @Autowired private val producer: PingProducer,
    @Autowired private val consumer: PingConsumer,
) {
    @Test
    fun `el consumidor recibe el mensaje que emite el productor`() {
        val text = "ping-${UUID.randomUUID()}"

        producer.emit(text)

        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            assertTrue(text in consumer.received)
        }
    }
}

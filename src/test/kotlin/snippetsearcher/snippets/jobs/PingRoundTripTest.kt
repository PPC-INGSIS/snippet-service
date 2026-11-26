package snippetsearcher.snippets.jobs

import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import snippetsearcher.snippets.TestcontainersConfiguration
import java.time.Duration
import java.util.UUID
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class)
@SpringBootTest
/**
 * Stream y grupo propios, y es lo que hace que este test sea determinista.
 *
 * Cada contexto de Spring de la suite trae su propio `PingConsumer`, y todos se registraban en el
 * mismo grupo sobre el mismo Redis. En un grupo de consumidores cada mensaje le llega a **uno
 * solo**, así que el ping que emitía este test podía ser consumido por el consumidor de otro
 * contexto y este test fallaba sin que nadie tocara Redis. Con un stream propio, el único que lee
 * lo que este test emite es su propio consumidor.
 */
@TestPropertySource(
    properties = ["spike.ping.stream=spike.ping.roundtrip", "spike.ping.group=snippets-roundtrip"],
)
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

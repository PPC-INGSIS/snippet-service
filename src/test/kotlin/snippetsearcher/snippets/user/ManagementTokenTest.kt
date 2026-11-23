package snippetsearcher.snippets.user

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClientException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ManagementTokenTest {
    // Un servidor HTTP mínimo que hace de Auth0: cada pedido devuelve un token distinto (token-1, token-2...)
    private val server = HttpServer.create(InetSocketAddress(0), 0)
    private var requests = 0
    private var receivedBody = ""
    private var delay: Duration = Duration.ZERO
    private val clock = MovableClock(Instant.parse("2026-11-22T12:00:00Z"))

    @BeforeEach
    fun start() {
        server.createContext("/oauth/token") { exchange ->
            Thread.sleep(delay.toMillis())
            requests++
            receivedBody = exchange.requestBody.readAllBytes().decodeToString()
            val bytes =
                """{"access_token":"token-$requests","expires_in":86400,"token_type":"Bearer"}"""
                    .toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    private fun newToken(timeout: Duration = Duration.ofSeconds(5)) =
        ManagementToken(
            "http://localhost:${server.address.port}",
            "https://test.invalid/api/v2/",
            "test-client",
            "test-secret",
            clock,
            timeout,
        )

    @Test
    fun `pide el token con client credentials y la audiencia de la Management API`() {
        assertEquals("token-1", newToken().value())

        assertTrue(receivedBody.contains(""""grant_type":"client_credentials""""))
        assertTrue(receivedBody.contains(""""client_id":"test-client""""))
        assertTrue(receivedBody.contains(""""client_secret":"test-secret""""))
        assertTrue(receivedBody.contains(""""audience":"https://test.invalid/api/v2/""""))
    }

    @Test
    fun `reutiliza el token hasta un minuto antes de que venza`() {
        val token = newToken()

        token.value()
        clock.advance(Duration.ofHours(24).minusSeconds(61))

        assertEquals("token-1", token.value())
        assertEquals(1, requests)
    }

    @Test
    fun `pide otro en el ultimo minuto antes de que venza`() {
        val token = newToken()

        token.value()
        clock.advance(Duration.ofHours(24).minusSeconds(60))

        assertEquals("token-2", token.value())
        assertEquals(2, requests)
    }

    @Test
    fun `si Auth0 no responde a tiempo, falla en vez de quedarse esperando`() {
        delay = Duration.ofSeconds(1)
        val token = newToken(timeout = Duration.ofMillis(100))

        val error = assertFailsWith<RestClientException> { token.value() }
        assertIs<SocketTimeoutException>(error.rootCause)
    }

    @Test
    fun `el toString de la respuesta no muestra el token`() {
        val response = TokenResponse("token-secreto", 86400)

        assertFalse(response.toString().contains("token-secreto"))
    }
}

// Un reloj que el test adelanta a mano, para no esperar 24 h
private class MovableClock(
    private var now: Instant,
) : Clock() {
    fun advance(duration: Duration) {
        now = now.plus(duration)
    }

    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}

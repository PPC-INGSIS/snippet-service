package snippetsearcher.snippets.security

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Duration
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CallerAccessTokenTest {
    // Un servidor HTTP mínimo que hace de Auth0: cada pedido devuelve un token distinto
    private val server = HttpServer.create(InetSocketAddress(0), 0)
    private var requests = 0
    private var receivedBody = ""

    @BeforeEach
    fun start() {
        server.createContext("/oauth/token") { exchange ->
            requests++
            receivedBody = exchange.requestBody.readAllBytes().decodeToString()
            val bytes =
                """{"access_token":"token-de-servicio-$requests","expires_in":86400,"token_type":"Bearer"}"""
                    .toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    // El contexto es por hilo y los tests comparten hilo: si no se limpia, un test le pasa el usuario al siguiente
    @AfterEach
    fun stop() {
        SecurityContextHolder.clearContext()
        server.stop(0)
    }

    private fun caller() =
        CallerAccessToken(
            UserAccessToken(),
            ServiceAccessToken(
                "http://localhost:${server.address.port}",
                "https://snippet-searcher-api",
                "snippets-m2m",
                "secreto",
                Clock.systemUTC(),
                Duration.ofSeconds(5),
            ),
        )

    private fun loguearUsuario() {
        val jwt =
            Jwt
                .withTokenValue("token-del-usuario")
                .header("alg", "none")
                .subject("auth0|ana")
                .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
    }

    @Test
    fun `adentro de un pedido manda el token del usuario y no le pide nada a Auth0`() {
        loguearUsuario()

        assertEquals("token-del-usuario", caller().value())
        assertEquals(0, requests)
    }

    @Test
    fun `sin usuario pide el token de Snippets con client credentials y la audiencia de la API`() {
        assertEquals("token-de-servicio-1", caller().value())

        assertTrue(receivedBody.contains(""""grant_type":"client_credentials""""))
        assertTrue(receivedBody.contains(""""client_id":"snippets-m2m""""))
        assertTrue(receivedBody.contains(""""audience":"https://snippet-searcher-api""""))
    }

    @Test
    fun `sin usuario reutiliza el token de Snippets en vez de pedirlo en cada llamada`() {
        val token = caller()

        token.value()
        token.value()

        assertEquals(1, requests)
    }

    // Como el consumidor de Redis: corre en su propio hilo, que no ve el usuario del pedido que lo disparó
    @Test
    fun `en otro hilo usa el token de Snippets aunque el pedido que lo lanzo tenga usuario`() {
        loguearUsuario()
        val token = caller()

        var enOtroHilo = ""
        thread { enOtroHilo = token.value() }.join()

        assertEquals("token-de-servicio-1", enOtroHilo)
        assertEquals("token-del-usuario", token.value())
    }
}

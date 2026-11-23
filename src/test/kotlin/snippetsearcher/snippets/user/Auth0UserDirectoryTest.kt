package snippetsearcher.snippets.user

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClientException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class Auth0UserDirectoryTest {
    // Un servidor HTTP mínimo que hace de Auth0: da el token y responde la búsqueda
    private val server = HttpServer.create(InetSocketAddress(0), 0)
    private var usersResponse = """{"start":0,"limit":5,"length":0,"total":0,"users":[]}"""
    private var receivedQuery = ""
    private var receivedAuthorization = ""
    private var usersDelay: Duration = Duration.ZERO

    @BeforeEach
    fun start() {
        server.createContext("/oauth/token") { exchange ->
            respond(exchange, """{"access_token":"token-1","expires_in":86400,"token_type":"Bearer"}""")
        }
        server.createContext("/api/v2/users") { exchange ->
            Thread.sleep(usersDelay.toMillis())
            receivedQuery = exchange.requestURI.rawQuery
            receivedAuthorization = exchange.requestHeaders.getFirst("Authorization")
            respond(exchange, usersResponse)
        }
        server.start()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    private fun respond(
        exchange: com.sun.net.httpserver.HttpExchange,
        body: String,
    ) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun directory(timeout: Duration = Duration.ofSeconds(5)): Auth0UserDirectory {
        val url = "http://localhost:${server.address.port}"
        val token = ManagementToken(url, "https://test.invalid/api/v2/", "test-client", "test-secret")
        return Auth0UserDirectory(url, token, timeout)
    }

    // Los parámetros que llegaron, decodificados de a uno (un & escapado no corta el valor)
    private fun receivedParams(): Map<String, String> =
        receivedQuery.split("&").associate { pair ->
            val (key, value) = pair.split("=", limit = 2)
            URLDecoder.decode(key, Charsets.UTF_8) to URLDecoder.decode(value, Charsets.UTF_8)
        }

    @Test
    fun `busca por el comienzo del nombre o del mail en la pagina pedida`() {
        directory().search("an", 1, 5)

        val params = receivedParams()
        assertEquals("name:an* OR email:an*", params["q"])
        assertEquals("v3", params["search_engine"])
        assertEquals("1", params["page"])
        assertEquals("5", params["per_page"])
        assertEquals("true", params["include_totals"])
        assertEquals("user_id,name,email", params["fields"])
    }

    @Test
    fun `convierte la respuesta de Auth0 en una pagina con el total`() {
        usersResponse =
            """
            {"start":5,"limit":5,"length":2,"total":12,"users":[
              {"user_id":"auth0|ana","name":"Ana","email":"ana@mail.com"},
              {"user_id":"auth0|juli","email":"juli@mail.com"}
            ]}
            """.trimIndent()

        val page = directory().search("", 1, 5)

        assertEquals(UserPage(listOf(User("auth0|ana", "Ana"), User("auth0|juli", "juli@mail.com")), 12), page)
    }

    @Test
    fun `pide los usuarios con el token de la Management API`() {
        directory().search("an", 0, 5)

        assertEquals("Bearer token-1", receivedAuthorization)
    }

    @Test
    fun `sin nombre no filtra`() {
        directory().search("", 0, 10)

        assertFalse(receivedParams().containsKey("q"))
    }

    @Test
    fun `escapa la sintaxis de Lucene para buscar el texto tal cual`() {
        directory().search("juan (pe+rez", 0, 5)

        assertEquals("""name:juan\ \(pe\+rez* OR email:juan\ \(pe\+rez*""", receivedParams()["q"])
    }

    @Test
    fun `si Auth0 no responde la busqueda a tiempo, falla en vez de quedarse esperando`() {
        usersDelay = Duration.ofSeconds(1)

        val error =
            assertFailsWith<RestClientException> { directory(timeout = Duration.ofMillis(100)).search("an", 0, 5) }
        assertIs<SocketTimeoutException>(error.rootCause)
    }
}

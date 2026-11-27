package snippetsearcher.snippets.user

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClientException
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.http.HttpTimeoutException
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

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

    @Test
    fun `namesOf pide todos los ids en una sola consulta, entre comillas`() {
        usersResponse = """[{"user_id":"auth0|ana","name":"Ana Pérez"},{"user_id":"auth0|beto","name":"Beto"}]"""

        val nombres = directory().namesOf(listOf("auth0|ana", "auth0|beto"))

        assertEquals(mapOf("auth0|ana" to "Ana Pérez", "auth0|beto" to "Beto"), nombres)
        // Entre comillas porque el | del sub es un operador de Lucene
        val query = URLDecoder.decode(receivedQuery, "UTF-8")
        assertTrue("""user_id:("auth0|ana" OR "auth0|beto")""" in query, query)
    }

    @Test
    fun `namesOf sin ids no consulta nada`() {
        receivedQuery = "no-se-llamo"

        assertEquals(emptyMap(), directory().namesOf(emptyList()))
        assertEquals("no-se-llamo", receivedQuery)
    }

    /**
     * Si Auth0 falla, devuelve lo que pudo —nada— en vez de tirar.
     *
     * Es la decisión que sostiene el criterio "si el directorio de usuarios falla, el listado
     * responde 200 igual": un nombre que no se pudo resolver no puede tirar abajo la tabla entera.
     */
    @Test
    fun `namesOf con Auth0 caido devuelve un mapa vacio en vez de tirar`() {
        server.stop(0)

        assertEquals(emptyMap(), directory().namesOf(listOf("auth0|ana")))
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
        // El cliente HTTP del JDK, como el resto de los clientes (timedRestClient): su timeout es HttpTimeoutException
        assertIs<HttpTimeoutException>(error.rootCause)
    }
}

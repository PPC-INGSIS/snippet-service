package snippetsearcher.snippets.permissions

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * El cliente contra un servidor de verdad, no un mock de objetos: así se prueba la ruta,
 * el cuerpo, el header del token y cómo traducimos cada status.
 */
class HttpPermissionsClientTest {
    private lateinit var server: HttpServer
    private lateinit var client: HttpPermissionsClient

    private var status = 201
    private var responseBody = ""
    private var delay: Duration = Duration.ZERO

    private var receivedMethod: String? = null
    private var receivedPath: String? = null
    private var receivedQuery: String? = null
    private var receivedBody: String? = null
    private var receivedAuth: String? = null

    @BeforeEach
    fun levantarServidor() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange -> responder(exchange) }
        server.start()
        client = clienteCon(READ_TIMEOUT)
    }

    @AfterEach
    fun bajarServidor() {
        server.stop(0)
    }

    private fun clienteCon(readTimeout: Duration) =
        HttpPermissionsClient(
            "http://127.0.0.1:${server.address.port}",
            CONNECT_TIMEOUT,
            readTimeout,
        ) { TOKEN }

    private fun responder(exchange: HttpExchange) {
        receivedMethod = exchange.requestMethod
        receivedPath = exchange.requestURI.path
        receivedQuery = exchange.requestURI.query
        receivedBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        receivedAuth = exchange.requestHeaders.getFirst("Authorization")
        if (!delay.isZero) Thread.sleep(delay.toMillis())

        val bytes = responseBody.toByteArray(StandardCharsets.UTF_8)
        if (bytes.isNotEmpty()) exchange.responseHeaders.add("Content-Type", "application/json")
        // -1 es "sin cuerpo": con 0 el servidor queda esperando que escribamos algo
        exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    @Test
    fun `registra al dueno con rol OWNER`() {
        val snippetId = UUID.randomUUID()

        val result = client.registerOwner(snippetId, "auth0|ana")

        assertIs<RegisterAccessResult.Registered>(result)
        assertEquals("POST", receivedMethod)
        assertEquals("/permissions", receivedPath)
        assertTrue("\"snippetId\":\"$snippetId\"" in receivedBody!!, receivedBody!!)
        assertTrue("\"userId\":\"auth0|ana\"" in receivedBody!!, receivedBody!!)
        assertTrue("\"role\":\"OWNER\"" in receivedBody!!, receivedBody!!)
    }

    // El fake escribe su propio "READ": sin este test, cambiar la constante del cliente no rompería nada
    @Test
    fun `registra al lector con rol READ`() {
        val snippetId = UUID.randomUUID()

        val result = client.registerReader(snippetId, "auth0|beto")

        assertIs<RegisterAccessResult.Registered>(result)
        assertEquals("POST", receivedMethod)
        assertEquals("/permissions", receivedPath)
        assertTrue("\"snippetId\":\"$snippetId\"" in receivedBody!!, receivedBody!!)
        assertTrue("\"userId\":\"auth0|beto\"" in receivedBody!!, receivedBody!!)
        assertTrue("\"role\":\"READ\"" in receivedBody!!, receivedBody!!)
    }

    // Permisos valida el token igual que nosotros: sin reenviarlo responde 401
    @Test
    fun `reenvia el token del usuario`() {
        client.registerOwner(UUID.randomUUID(), "auth0|ana")

        assertEquals("Bearer $TOKEN", receivedAuth)
    }

    /**
     * El 409 de Permisos es "ese usuario ya tiene un rol sobre ese snippet".
     *
     * Para nosotros el resultado es el mismo que haberlo creado: el dueño quedó. Si lo
     * tratáramos como falla, un reintento fallaría por culpa del intento que sí funcionó.
     */
    @Test
    fun `un 409 cuenta como registrado`() {
        status = 409

        assertIs<RegisterAccessResult.Registered>(client.registerOwner(UUID.randomUUID(), "auth0|ana"))
    }

    @Test
    fun `un error del servidor devuelve Failed`() {
        status = 500

        val result = client.registerOwner(UUID.randomUUID(), "auth0|ana")

        assertIs<RegisterAccessResult.Failed>(result)
        assertTrue(result.message.isNotBlank())
    }

    // El mensaje que ve el cliente no puede traer la URL ni el puerto internos
    @Test
    fun `el mensaje de error no filtra la direccion de Permisos`() {
        status = 500

        val result = assertIs<RegisterAccessResult.Failed>(client.registerOwner(UUID.randomUUID(), "auth0|ana"))

        assertTrue("127.0.0.1" !in result.message, "filtra el host: ${result.message}")
        assertTrue(server.address.port.toString() !in result.message, "filtra el puerto")
    }

    @Test
    fun `sin servidor del otro lado devuelve Failed y no tira`() {
        server.stop(0)

        assertIs<RegisterAccessResult.Failed>(client.registerOwner(UUID.randomUUID(), "auth0|ana"))
    }

    @Test
    fun `deleteAccesses pide borrar a la ruta de Permisos con el token`() {
        status = 204
        val snippetId = UUID.randomUUID()

        val result = client.deleteAccesses(snippetId)

        assertIs<DeleteAccessesResult.Deleted>(result)
        assertEquals("DELETE", receivedMethod)
        assertEquals("/permissions/$snippetId", receivedPath)
        assertEquals("Bearer $TOKEN", receivedAuth)
    }

    @Test
    fun `deleteAccesses con un error del servidor devuelve Failed`() {
        status = 500

        val result = client.deleteAccesses(UUID.randomUUID())

        assertIs<DeleteAccessesResult.Failed>(result)
        assertTrue(result.message.isNotBlank())
    }

    @Test
    fun `deleteAccesses sin servidor del otro lado devuelve Failed y no tira`() {
        server.stop(0)

        assertIs<DeleteAccessesResult.Failed>(client.deleteAccesses(UUID.randomUUID()))
    }

    @Test
    fun `roleOf pide el rol con el userId como query param y el token`() {
        status = 200
        val snippetId = UUID.randomUUID()
        responseBody = """{"snippetId":"$snippetId","userId":"auth0|beto","role":"READ"}"""

        val result = client.roleOf(snippetId, "auth0|beto")

        assertEquals("READ", assertIs<RoleResult.Found>(result).role)
        assertEquals("GET", receivedMethod)
        assertEquals("/permissions/$snippetId", receivedPath)
        assertEquals("userId=auth0|beto", receivedQuery)
        assertEquals("Bearer $TOKEN", receivedAuth)
    }

    // El 404 de Permisos es "no tiene acceso", no una falla
    @Test
    fun `roleOf con un 404 devuelve None`() {
        status = 404

        assertIs<RoleResult.None>(client.roleOf(UUID.randomUUID(), "auth0|beto"))
    }

    @Test
    fun `roleOf con un error del servidor devuelve Failed`() {
        status = 500

        assertIs<RoleResult.Failed>(client.roleOf(UUID.randomUUID(), "auth0|beto"))
    }

    @Test
    fun `roleOf sin servidor del otro lado devuelve Failed y no tira`() {
        server.stop(0)

        assertIs<RoleResult.Failed>(client.roleOf(UUID.randomUUID(), "auth0|beto"))
    }

    @Test
    fun `sharedWith pide los accesos READ del usuario y devuelve los ids`() {
        status = 200
        val compartido = UUID.randomUUID()
        responseBody = """[{"snippetId":"$compartido","userId":"auth0|beto","role":"READ"}]"""

        val result = client.sharedWith("auth0|beto")

        assertEquals(listOf(compartido), assertIs<SharedSnippetsResult.Found>(result).snippetIds)
        assertEquals("GET", receivedMethod)
        assertEquals("/permissions", receivedPath)
        assertEquals("userId=auth0|beto&role=READ", receivedQuery)
        assertEquals("Bearer $TOKEN", receivedAuth)
    }

    @Test
    fun `sharedWith sin accesos devuelve una lista vacia`() {
        status = 200
        responseBody = "[]"

        assertEquals(emptyList(), assertIs<SharedSnippetsResult.Found>(client.sharedWith("auth0|beto")).snippetIds)
    }

    @Test
    fun `sharedWith con un error del servidor devuelve Failed`() {
        status = 500

        assertIs<SharedSnippetsResult.Failed>(client.sharedWith("auth0|beto"))
    }

    // Un Permisos que atiende y no contesta no puede colgar al thread para siempre
    @Test
    fun `un Permisos lento corta por read timeout y devuelve Failed`() {
        delay = Duration.ofSeconds(2)
        val impaciente = clienteCon(Duration.ofMillis(200))

        assertIs<RoleResult.Failed>(impaciente.roleOf(UUID.randomUUID(), "auth0|ana"))
    }

    companion object {
        private const val TOKEN = "token-del-usuario"
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)
        private val READ_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}

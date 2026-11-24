package snippetsearcher.snippets.asset

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
 * El cliente HTTP contra un servidor de verdad, no un mock de objetos.
 *
 * Levanta un servidor del JDK que imita al asset service: así se prueba la URL que
 * armamos, el cuerpo que mandamos, el charset y cómo traducimos cada status. Un mock del
 * RestClient probaría que llamamos al mock, no que hablamos bien HTTP.
 *
 * El servidor responde `text/event-stream` sin charset porque es lo que contesta el
 * contenedor de la cátedra, verificado a mano contra la imagen real.
 */
class HttpAssetClientTest {
    private lateinit var server: HttpServer
    private lateinit var client: HttpAssetClient

    /** Lo que el servidor va a responder al próximo pedido. */
    private var status = 200
    private var body = ""
    private var contentType = "text/event-stream"
    private var delay: Duration = Duration.ZERO

    /** Lo que el servidor recibió, para verificar qué mandamos. */
    private var receivedMethod: String? = null
    private var receivedPath: String? = null
    private var receivedBody: String? = null

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
        HttpAssetClient("http://127.0.0.1:${server.address.port}", CONNECT_TIMEOUT, readTimeout)

    private fun responder(exchange: HttpExchange) {
        receivedMethod = exchange.requestMethod
        receivedPath = exchange.requestURI.path
        receivedBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)

        if (!delay.isZero) Thread.sleep(delay.toMillis())

        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", contentType)
        // -1 es "sin cuerpo": con 0 el servidor queda esperando que escribamos algo
        exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    @Test
    fun `put manda el contenido a la ruta del asset service`() {
        status = 201
        val snippetId = UUID.randomUUID()

        val result = client.put(snippetId, "println(1);")

        assertIs<SaveContentResult.Saved>(result)
        assertEquals("PUT", receivedMethod)
        assertEquals("/v1/asset/snippets/$snippetId", receivedPath)
        assertEquals("println(1);", receivedBody)
    }

    // 201 si es nuevo, 200 si reemplazó: para nosotros los dos son "guardado"
    @Test
    fun `put trata el 200 igual que el 201`() {
        status = 200

        assertIs<SaveContentResult.Saved>(client.put(UUID.randomUUID(), "println(1);"))
    }

    @Test
    fun `put con un error del servidor devuelve Failed`() {
        status = 500

        val result = client.put(UUID.randomUUID(), "println(1);")

        assertIs<SaveContentResult.Failed>(result)
        assertTrue(result.message.isNotBlank())
    }

    // El mensaje que ve el cliente no puede traer la URL ni el puerto internos
    @Test
    fun `el mensaje de error no filtra la direccion del asset service`() {
        status = 500

        val result = assertIs<SaveContentResult.Failed>(client.put(UUID.randomUUID(), "println(1);"))

        assertTrue("127.0.0.1" !in result.message, "el mensaje filtra el host: ${result.message}")
        assertTrue(server.address.port.toString() !in result.message, "el mensaje filtra el puerto")
    }

    @Test
    fun `put sin servidor del otro lado devuelve Failed y no tira`() {
        server.stop(0)

        assertIs<SaveContentResult.Failed>(client.put(UUID.randomUUID(), "println(1);"))
    }

    @Test
    fun `get devuelve el contenido`() {
        status = 200
        body = "let x: number = 1;"
        val snippetId = UUID.randomUUID()

        val result = client.get(snippetId)

        assertEquals("let x: number = 1;", assertIs<ContentResult.Found>(result).content)
        assertEquals("GET", receivedMethod)
        assertEquals("/v1/asset/snippets/$snippetId", receivedPath)
    }

    /**
     * La regresión del bug que encontramos contra el contenedor real.
     *
     * El asset service responde `text/event-stream` **sin charset**. Leyendo con
     * `body<String>()`, Spring decodifica con ISO-8859-1 y "acción" vuelve "acciÃ³n".
     * Por eso el cliente lee bytes y decodifica UTF-8 él mismo.
     */
    @Test
    fun `get decodifica UTF-8 aunque la respuesta no declare charset`() {
        status = 200
        contentType = "text/event-stream"
        body = """println("acción y ñandú 🙂");"""

        val result = client.get(UUID.randomUUID())

        assertEquals("""println("acción y ñandú 🙂");""", assertIs<ContentResult.Found>(result).content)
    }

    // El 404 es una respuesta, no una falla: distinguirlo importa para no confundir
    // "este snippet no tiene contenido" con "el servicio está caído"
    @Test
    fun `get de algo que no existe devuelve NotFound y no Failed`() {
        status = 404

        assertIs<ContentResult.NotFound>(client.get(UUID.randomUUID()))
    }

    @Test
    fun `get con un error del servidor devuelve Failed`() {
        status = 500

        assertIs<ContentResult.Failed>(client.get(UUID.randomUUID()))
    }

    @Test
    fun `delete pide borrar a la ruta del asset service`() {
        status = 204
        val snippetId = UUID.randomUUID()

        val result = client.delete(snippetId)

        assertIs<DeleteContentResult.Deleted>(result)
        assertEquals("DELETE", receivedMethod)
        assertEquals("/v1/asset/snippets/$snippetId", receivedPath)
    }

    // El 404 es "ya no está": un reintento de un borrado que sí se hizo no puede fallar
    @Test
    fun `delete de algo que no existe devuelve Deleted`() {
        status = 404

        assertIs<DeleteContentResult.Deleted>(client.delete(UUID.randomUUID()))
    }

    @Test
    fun `delete con un error del servidor devuelve Failed`() {
        status = 500

        assertIs<DeleteContentResult.Failed>(client.delete(UUID.randomUUID()))
    }

    @Test
    fun `delete sin servidor del otro lado devuelve Failed y no tira`() {
        server.stop(0)

        assertIs<DeleteContentResult.Failed>(client.delete(UUID.randomUUID()))
    }

    @Test
    fun `el contenido viaja en UTF-8`() {
        status = 201
        val conAcentos = """println("acción y ñandú");"""

        client.put(UUID.randomUUID(), conAcentos)

        assertEquals(conAcentos, receivedBody)
    }

    // Un asset service que atiende y no contesta no puede colgar al thread para siempre
    @Test
    fun `un servidor lento corta por read timeout y devuelve Failed`() {
        status = 200
        delay = Duration.ofSeconds(2)
        val impaciente = clienteCon(Duration.ofMillis(200))

        val result = impaciente.get(UUID.randomUUID())

        assertIs<ContentResult.Failed>(result)
    }

    companion object {
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)
        private val READ_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}

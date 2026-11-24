package snippetsearcher.snippets.printscript

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.HttpClientErrorException
import snippetsearcher.snippets.security.AccessToken
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HttpPrintScriptClientTest {
    // Un servidor HTTP mínimo que hace de printscript-service
    private val server = HttpServer.create(InetSocketAddress(0), 0)
    private var status = 200
    private var response = """{"errors":[]}"""
    private var contentType = "application/json"
    private var receivedQuery = ""
    private var receivedBody = ""
    private var receivedAuthorization: String? = null

    @BeforeEach
    fun start() {
        server.createContext("/validate") { exchange ->
            receivedQuery = exchange.requestURI.query
            receivedBody = exchange.requestBody.readAllBytes().decodeToString()
            receivedAuthorization = exchange.requestHeaders.getFirst("Authorization")
            val bytes = response.toByteArray()
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    private fun client() =
        HttpPrintScriptClient("http://localhost:${server.address.port}", AccessToken { "token-de-prueba" })

    @Test
    fun `manda la version y el codigo, y sin errores devuelve lista vacia`() {
        val result = client().validate("1.1", "println(1);")

        val checked = assertIs<ValidationResult.Checked>(result)
        assertTrue(checked.errors.isEmpty())
        assertEquals("version=1.1", receivedQuery)
        assertEquals("println(1);", receivedBody)
    }

    @Test
    fun `manda el token en el header Authorization`() {
        client().validate("1.1", "println(1);")

        assertEquals("Bearer token-de-prueba", receivedAuthorization)
    }

    @Test
    fun `convierte los errores de la respuesta`() {
        response = """{"errors":[{"message":"Se esperaba ')'","line":1,"column":15}]}"""

        val result = client().validate("1.1", "println(1;")

        val checked = assertIs<ValidationResult.Checked>(result)
        assertEquals(ValidationError("Se esperaba ')'", 1, 15), checked.errors.single())
    }

    @Test
    fun `un 400 de PrintScript vuelve como pedido rechazado con su mensaje`() {
        status = 400
        contentType = "application/problem+json"
        response =
            """
            {
              "title": "Bad Request",
              "status": 400,
              "detail": "La versión '1.3' no existe",
              "instance": "/validate"
            }
            """.trimIndent()

        val result = client().validate("1.3", "println(1);")

        val rejected = assertIs<ValidationResult.Rejected>(result)
        assertEquals("La versión '1.3' no existe", rejected.message)
    }

    @Test
    fun `un 400 sin detail vuelve como pedido rechazado con el texto por defecto`() {
        status = 400
        contentType = "application/problem+json"
        response = """{"title":"Bad Request","status":400}"""

        val result = client().validate("1.1", "println(1);")

        val rejected = assertIs<ValidationResult.Rejected>(result)
        assertEquals("PrintScript rechazó el pedido", rejected.message)
    }

    @Test
    fun `un 400 con detail en blanco vuelve como pedido rechazado con el texto por defecto`() {
        status = 400
        contentType = "application/problem+json"
        response = """{"title":"Bad Request","status":400,"detail":"   "}"""

        val result = client().validate("1.1", "println(1);")

        val rejected = assertIs<ValidationResult.Rejected>(result)
        assertEquals("PrintScript rechazó el pedido", rejected.message)
    }

    @Test
    fun `un 400 en HTML vuelve como pedido rechazado con el texto por defecto`() {
        status = 400
        contentType = "text/html"
        response = "<html>Bad Request</html>"

        val result = client().validate("1.1", "println(1);")

        val rejected = assertIs<ValidationResult.Rejected>(result)
        assertEquals("PrintScript rechazó el pedido", rejected.message)
    }

    @Test
    fun `un 400 con JSON mal formado vuelve como pedido rechazado con el texto por defecto`() {
        status = 400
        contentType = "application/problem+json"
        response = "<html>Bad Request</html>"

        val result = client().validate("1.1", "println(1);")

        val rejected = assertIs<ValidationResult.Rejected>(result)
        assertEquals("PrintScript rechazó el pedido", rejected.message)
    }

    @Test
    fun `un 413 de PrintScript vuelve como pedido rechazado con su mensaje`() {
        status = 413
        contentType = "application/problem+json"
        response =
            """
            {
              "title": "Payload Too Large",
              "status": 413,
              "detail": "El código supera el tamaño máximo de 64 KB",
              "instance": "/validate"
            }
            """.trimIndent()

        val result = client().validate("1.1", "println(1);")

        val rejected = assertIs<ValidationResult.Rejected>(result)
        assertEquals("El código supera el tamaño máximo de 64 KB", rejected.message)
    }

    @Test
    fun `un 413 sin detail vuelve como pedido rechazado con el texto por defecto`() {
        status = 413
        contentType = "application/problem+json"
        response = """{"title":"Payload Too Large","status":413}"""

        val result = client().validate("1.1", "println(1);")

        val rejected = assertIs<ValidationResult.Rejected>(result)
        assertEquals("PrintScript rechazó el pedido", rejected.message)
    }

    @Test
    fun `un 413 en HTML vuelve como pedido rechazado con el texto por defecto`() {
        status = 413
        contentType = "text/html"
        response = "<html>Request Entity Too Large</html>"

        val result = client().validate("1.1", "println(1);")

        val rejected = assertIs<ValidationResult.Rejected>(result)
        assertEquals("PrintScript rechazó el pedido", rejected.message)
    }

    @Test
    fun `otro 4xx de PrintScript sigue saliendo como excepcion`() {
        status = 415
        contentType = "application/problem+json"
        response = """{"title":"Unsupported Media Type","status":415}"""

        val exception =
            assertFailsWith<HttpClientErrorException> {
                client().validate("1.1", "println(1);")
            }

        assertEquals(415, exception.statusCode.value())
    }
}

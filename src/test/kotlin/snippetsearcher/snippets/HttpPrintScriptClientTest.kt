package snippetsearcher.snippets

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HttpPrintScriptClientTest {
    // Un servidor HTTP mínimo que hace de printscript-service
    private val server = HttpServer.create(InetSocketAddress(0), 0)
    private var status = 200
    private var response = """{"errors":[]}"""
    private var receivedQuery = ""
    private var receivedBody = ""

    @BeforeEach
    fun start() {
        server.createContext("/validate") { exchange ->
            receivedQuery = exchange.requestURI.query
            receivedBody = exchange.requestBody.readAllBytes().decodeToString()
            val bytes = response.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    private fun client() = HttpPrintScriptClient("http://localhost:${server.address.port}")

    @Test
    fun `manda la version y el codigo, y sin errores devuelve lista vacia`() {
        val result = client().validate("1.1", "println(1);")

        val checked = assertIs<ValidationResult.Checked>(result)
        assertTrue(checked.errors.isEmpty())
        assertEquals("version=1.1", receivedQuery)
        assertEquals("println(1);", receivedBody)
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
        response = """{"message":"La versión '1.3' no existe"}"""

        val result = client().validate("1.3", "println(1);")

        val rejected = assertIs<ValidationResult.Rejected>(result)
        assertEquals("La versión '1.3' no existe", rejected.message)
    }
}

package snippetsearcher.snippets.printscript

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import snippetsearcher.snippets.language.RuleDefinition
import snippetsearcher.snippets.language.RuleKind
import snippetsearcher.snippets.language.RulesResult
import snippetsearcher.snippets.language.ValidationError
import snippetsearcher.snippets.language.ValidationResult
import snippetsearcher.snippets.language.VersionsResult
import snippetsearcher.snippets.security.AccessToken
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpPrintScriptClientTest {
    // Un servidor HTTP mínimo que hace de printscript-service
    private val server = HttpServer.create(InetSocketAddress(0), 0)
    private var status = 200
    private var response = """{"errors":[]}"""
    private var contentType = "application/json"
    private var delay: Duration = Duration.ZERO
    private var receivedQuery = ""
    private var receivedBody = ""
    private var receivedAuthorization: String? = null
    private var receivedUri: URI? = null

    @BeforeEach
    fun start() {
        server.createContext("/validate") { exchange ->
            receivedQuery = exchange.requestURI.query
            receivedBody = exchange.requestBody.readAllBytes().decodeToString()
            respond(exchange)
        }
        server.createContext("/versions") { exchange -> respond(exchange) }
        server.createContext("/rules") { exchange ->
            receivedUri = exchange.requestURI
            respond(exchange)
        }
        server.start()
    }

    private fun respond(exchange: HttpExchange) {
        receivedAuthorization = exchange.requestHeaders.getFirst("Authorization")
        if (!delay.isZero) Thread.sleep(delay.toMillis())
        val bytes = response.toByteArray()
        exchange.responseHeaders.add("Content-Type", contentType)
        // -1 es "sin cuerpo": con 0 el servidor manda la respuesta en chunks
        exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    @AfterEach
    fun stop() {
        server.stop(0)
    }

    private fun client(readTimeout: Duration = READ_TIMEOUT) =
        HttpPrintScriptClient(
            "http://localhost:${server.address.port}",
            CONNECT_TIMEOUT,
            readTimeout,
            AccessToken { "token-de-prueba" },
        )

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

    /**
     * Hay errores sin posición: PrintScript manda `line` y `column` en `null` cuando el código
     * desborda el parser por tener demasiados niveles de anidamiento (printscript-service#42).
     *
     * Con la posición no nula, esta respuesta ni se podía deserializar: el fallo de Jackson no es
     * un `RestClientException`, así que no lo atrapaba ninguno de los catch del cliente y el pedido
     * terminaba en un 500, perdiendo el mensaje que PrintScript sí había mandado.
     */
    @Test
    fun `un error sin posicion se convierte igual`() {
        response =
            """
            {"errors":[{"message":"El código tiene demasiados niveles de anidamiento","line":null,"column":null}]}
            """.trimIndent()

        val result = client().validate("1.1", "println(((((1)))));")

        val checked = assertIs<ValidationResult.Checked>(result)
        assertEquals(
            ValidationError("El código tiene demasiados niveles de anidamiento", null, null),
            checked.errors.single(),
        )
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

    @Test
    fun `versions devuelve los ids de las versiones`() {
        response = """{"versions":[{"id":"1.0"},{"id":"1.1"}],"latest":"1.1"}"""

        val result = client().versions()

        assertEquals(VersionsResult.Found(listOf("1.0", "1.1")), result)
    }

    @Test
    fun `versions con una lista vacia devuelve Found sin versiones`() {
        response = """{"versions":[],"latest":"1.1"}"""

        assertEquals(VersionsResult.Found(emptyList()), client().versions())
    }

    @Test
    fun `versions manda el token en el header Authorization`() {
        response = """{"versions":[],"latest":"1.1"}"""

        client().versions()

        assertEquals("Bearer token-de-prueba", receivedAuthorization)
    }

    @Test
    fun `versions con un 500 de PrintScript devuelve Failed`() {
        status = 500
        contentType = "application/problem+json"
        response = """{"title":"Internal Server Error","status":500}"""

        assertIs<VersionsResult.Failed>(client().versions())
    }

    @Test
    fun `versions con un 200 sin body devuelve Failed`() {
        response = ""

        assertIs<VersionsResult.Failed>(client().versions())
    }

    @Test
    fun `rules de formateo pide GET rules format sin version`() {
        response = """{"version":"1.1","rules":[]}"""

        client().rules(RuleKind.FORMAT)

        assertEquals("/rules/format", receivedUri?.path)
        assertNull(receivedUri?.query)
    }

    @Test
    fun `rules de linteo pide GET rules lint sin version`() {
        response = """{"version":"1.1","rules":[]}"""

        client().rules(RuleKind.LINT)

        assertEquals("/rules/lint", receivedUri?.path)
        assertNull(receivedUri?.query)
    }

    @Test
    fun `rules convierte el id, la descripcion, el since y el default de cada regla`() {
        response =
            """
            {"version":"1.1","rules":[
              {"id":"if-indent","description":"Espacios de indentación dentro de un if","since":"1.1",
               "schema":{"type":"integer","minimum":0,"maximum":8,"default":4}}
            ]}
            """.trimIndent()

        val result = client().rules(RuleKind.FORMAT)

        val expected = RuleDefinition("if-indent", "Espacios de indentación dentro de un if", "1.1", 4)
        assertEquals(RulesResult.Found(listOf(expected)), result)
    }

    @Test
    fun `rules deja en null el default de un flag que no lo trae`() {
        response =
            """
            {"version":"1.1","rules":[
              {"id":"line-break-after-statement","description":"Salto de línea después de cada sentencia",
               "since":"1.0","schema":{"type":"boolean","const":true}}
            ]}
            """.trimIndent()

        val result = client().rules(RuleKind.FORMAT)

        val expected =
            RuleDefinition("line-break-after-statement", "Salto de línea después de cada sentencia", "1.0", null)
        assertEquals(RulesResult.Found(listOf(expected)), result)
    }

    @Test
    fun `rules manda el token en el header Authorization`() {
        response = """{"version":"1.1","rules":[]}"""

        client().rules(RuleKind.LINT)

        assertEquals("Bearer token-de-prueba", receivedAuthorization)
    }

    @Test
    fun `rules con un 500 de PrintScript devuelve Failed`() {
        status = 500
        contentType = "application/problem+json"
        response = """{"title":"Internal Server Error","status":500}"""

        assertIs<RulesResult.Failed>(client().rules(RuleKind.FORMAT))
    }

    @Test
    fun `rules con un 200 sin body devuelve Failed`() {
        response = ""

        assertIs<RulesResult.Failed>(client().rules(RuleKind.FORMAT))
    }

    // Los warn que deja el cliente mientras corre el bloque: así se prueba que la falla queda en el log
    private fun warnsDuring(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(HttpPrintScriptClient::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.filter { it.level == Level.WARN }
    }

    @Test
    fun `un 500 de PrintScript se loguea y sigue saliendo como excepcion`() {
        status = 500
        response = ""

        val warns =
            warnsDuring {
                assertFailsWith<HttpServerErrorException> { client().validate("1.1", "println(1);") }
            }

        assertEquals("No se pudo validar el snippet en PrintScript", warns.single().formattedMessage)
    }

    @Test
    fun `PrintScript caido se loguea y sigue saliendo como excepcion`() {
        server.stop(0)

        val warns =
            warnsDuring {
                assertFailsWith<ResourceAccessException> { client().validate("1.1", "println(1);") }
            }

        assertEquals("No se pudo validar el snippet en PrintScript", warns.single().formattedMessage)
    }

    @Test
    fun `un rechazo de PrintScript no se loguea como falla`() {
        status = 400
        response = """{"detail":"La versión '9.9' no existe"}"""
        contentType = "application/problem+json"

        val warns = warnsDuring { client().validate("9.9", "println(1);") }

        assertTrue(warns.isEmpty())
    }

    // Un PrintScript que atiende y no contesta no puede colgar al thread que llama
    @Test
    fun `versions con un PrintScript lento corta por read timeout y devuelve Failed`() {
        response = """{"versions":[{"id":"1.1"}],"latest":"1.1"}"""
        delay = Duration.ofSeconds(1)

        assertIs<VersionsResult.Failed>(client(Duration.ofMillis(200)).versions())
    }

    @Test
    fun `validate con un PrintScript lento corta por read timeout y sale como excepcion`() {
        delay = Duration.ofSeconds(1)

        assertFailsWith<ResourceAccessException> { client(Duration.ofMillis(200)).validate("1.1", "println(1);") }
    }

    companion object {
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(2)
        private val READ_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}

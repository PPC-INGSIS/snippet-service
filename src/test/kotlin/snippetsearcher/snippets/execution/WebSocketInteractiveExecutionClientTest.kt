package snippetsearcher.snippets.execution

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.context.TestPropertySource
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.handler.TextWebSocketHandler
import snippetsearcher.snippets.TestcontainersConfiguration
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * El cliente de verdad contra un WebSocket de verdad.
 *
 * Los tests del relay usan el cliente falso, así que sin esto el único código que habla por la red
 * quedaría sin probar: justo el que tiene el header del handshake y la forma del `start`, que son
 * las dos cosas que no se notan hasta que están desplegadas.
 *
 * El PrintScript falso es otro endpoint de esta misma app, y el cliente se construye a mano con el
 * puerto: no hace falta que Spring lo inyecte para probarlo.
 */
@Import(TestcontainersConfiguration::class, FakePrintScriptEndpoint::class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    // Stream y grupo propios: este contexto trae su propio PingConsumer y en un grupo de Redis
    // cada mensaje le llega a uno solo, así que con el grupo compartido le robaría el ping a PingRoundTripTest
    properties = ["spike.ping.stream=spike.ping.cliente", "spike.ping.group=snippets-cliente"],
)
class WebSocketInteractiveExecutionClientTest(
    @Autowired private val json: ObjectMapper,
    @Autowired private val printScript: FakePrintScriptEndpoint.Handler,
    @LocalServerPort private val port: Int,
) {
    @Test
    fun `manda el token en el handshake y el start con el codigo y la version`() {
        printScript.clear()

        val result = cliente().start(ExecutionStart("token-1", "println(1);", "1.1"), {}, {})

        assertIs<StartExecutionResult.Started>(result)
        // El token va en el header del handshake: acá sí se puede, porque el cliente lo abre Snippets
        assertEquals("Bearer token-1", printScript.handshakeAuthorization())
        val start = json.readValue(printScript.recibe(), Map::class.java)
        assertEquals("start", start["type"])
        assertEquals("println(1);", start["code"])
        assertEquals("1.1", start["version"])
    }

    @Test
    fun `reenvia el input con la forma del protocolo`() {
        printScript.clear()
        val execution =
            assertIs<StartExecutionResult.Started>(
                cliente().start(ExecutionStart("token-1", "println(1);", "1.1"), {}, {}),
            ).execution
        printScript.recibe() // el start

        execution.input("Juan")

        val input = json.readValue(printScript.recibe(), Map::class.java)
        assertEquals("input", input["type"])
        assertEquals("Juan", input["value"])
    }

    @Test
    fun `los mensajes de PrintScript llegan tal como vinieron`() {
        printScript.clear()
        val recibidos = LinkedBlockingQueue<String>()

        assertIs<StartExecutionResult.Started>(
            cliente().start(ExecutionStart("token-1", "println(1);", "1.1"), { recibidos += it }, {}),
        )
        printScript.recibe()
        printScript.emitir("""{"type":"output","text":"hola"}""")

        assertEquals("""{"type":"output","text":"hola"}""", recibidos.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun `avisa cuando PrintScript cierra`() {
        printScript.clear()
        val cierres = LinkedBlockingQueue<Boolean>()

        assertIs<StartExecutionResult.Started>(
            cliente().start(ExecutionStart("token-1", "println(1);", "1.1"), {}, { cierres += true }),
        )
        printScript.recibe()
        printScript.cerrar()

        assertNotNull(cierres.poll(5, TimeUnit.SECONDS), "no se avisó que PrintScript cerró")
    }

    // Sin PrintScript del otro lado el resultado es Failed, no una excepción que tire abajo el relay
    @Test
    fun `si no hay nadie del otro lado devuelve Failed y no tira`() {
        val cliente = WebSocketInteractiveExecutionClient("ws://localhost:1/no-existe", json)

        val result = cliente.start(ExecutionStart("token-1", "println(1);", "1.1"), {}, {})

        val failed = assertIs<StartExecutionResult.Failed>(result)
        assertTrue(failed.message.isNotBlank())
        // El mensaje no filtra la dirección interna
        assertTrue("localhost" !in failed.message, failed.message)
    }

    private fun cliente() =
        WebSocketInteractiveExecutionClient(
            "ws://localhost:$port${FakePrintScriptEndpoint.PREFIX}",
            json,
        )
}

/**
 * Un PrintScript falso: el mismo endpoint `/execute/interactive` que el de verdad, servido por esta app.
 */
@TestConfiguration(proxyBeanMethods = false)
@EnableWebSocket
class FakePrintScriptEndpoint : WebSocketConfigurer {
    // Propiedad y no parámetro: la config no puede pedir por constructor un bean que ella define
    private val handler = Handler()

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(handler, "$PREFIX/execute/interactive").setAllowedOrigins("*")
    }

    @Bean
    fun fakePrintScriptHandler() = handler

    /**
     * El PrintScript falso entra sin token.
     *
     * La cadena del servicio exige `read:snippets` en todo GET, y el handshake de un WebSocket es un
     * GET: sin esto, el cliente recibe un 401 antes de conectarse. Que haga falta es justamente la
     * prueba de que la regla también protege los WebSockets.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    fun fakePrintScriptSecurity(http: HttpSecurity): SecurityFilterChain =
        http
            .securityMatcher("$PREFIX/**")
            .authorizeHttpRequests { it.anyRequest().permitAll() }
            .csrf { it.disable() }
            .build()

    class Handler : TextWebSocketHandler() {
        private val mensajes = LinkedBlockingQueue<String>()
        private val handshakes = LinkedBlockingQueue<String>()

        @Volatile
        private var session: WebSocketSession? = null

        override fun afterConnectionEstablished(session: WebSocketSession) {
            this.session = session
            handshakes += session.handshakeHeaders.getFirst("Authorization").orEmpty()
        }

        override fun handleTextMessage(
            session: WebSocketSession,
            message: TextMessage,
        ) {
            mensajes += message.payload
        }

        fun recibe(): String = assertNotNull(mensajes.poll(5, TimeUnit.SECONDS), "PrintScript no recibió nada en 5 s")

        fun handshakeAuthorization(): String =
            assertNotNull(handshakes.poll(5, TimeUnit.SECONDS), "no llegó ningún handshake")

        fun emitir(payload: String) = session?.sendMessage(TextMessage(payload))

        fun cerrar() = session?.close(CloseStatus.NORMAL)

        fun clear() {
            mensajes.clear()
            handshakes.clear()
            session = null
        }
    }

    companion object {
        const val PREFIX = "/fake-printscript"
    }
}

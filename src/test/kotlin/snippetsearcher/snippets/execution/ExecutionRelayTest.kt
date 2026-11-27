package snippetsearcher.snippets.execution

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.handler.TextWebSocketHandler
import snippetsearcher.snippets.TestcontainersConfiguration
import snippetsearcher.snippets.asset.FakeAssetClient
import snippetsearcher.snippets.asset.FakeAssetConfiguration
import snippetsearcher.snippets.permissions.FakePermissionsClient
import snippetsearcher.snippets.permissions.FakePermissionsConfiguration
import snippetsearcher.snippets.printscript.FakePrintScriptConfiguration
import snippetsearcher.snippets.snippet.Snippet
import snippetsearcher.snippets.snippet.SnippetRepository
import java.net.URI
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * El relay contra un WebSocket de verdad del lado del browser y un PrintScript falso del otro.
 *
 * Es un `@SpringBootTest` con servidor real y no un MockMvc porque lo que se prueba es justamente
 * el WebSocket: el handshake sin token, el mensaje de `auth` y el ir y venir de mensajes. PrintScript
 * va falso porque lo que importa acá no es que ejecute, sino que los mensajes lleguen a donde van.
 */
@Import(
    TestcontainersConfiguration::class,
    FakePrintScriptConfiguration::class,
    FakeAssetConfiguration::class,
    FakePermissionsConfiguration::class,
    FakeInteractiveExecutionConfiguration::class,
    FakeJwtConfiguration::class,
)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = [
        // Corto, para probar que una conexión que no autentica se cierra sin esperar los 5 s de prod
        "execution.auth-timeout=2s",
        // Tope chico para poder probarlo. El @AfterEach cierra todo y espera a que se devuelvan los
        // lugares: el contador vive en el contexto, así que una sesión que queda abierta deja al
        // usuario ocupado para el test que viene
        "snippets.execution.max-per-user=2",
        // Stream y grupo propios. Esta clase levanta otro contexto de Spring —con servidor real— y
        // por lo tanto otro PingConsumer. En un grupo de consumidores de Redis cada mensaje le llega
        // a uno solo, así que con el grupo compartido este consumidor le robaba el ping a
        // PingRoundTripTest y ese test fallaba sin que nadie tocara Redis.
        "spike.ping.stream=spike.ping.relay",
        "spike.ping.group=snippets-relay",
    ],
)
class ExecutionRelayTest(
    @Autowired private val repository: SnippetRepository,
    @Autowired private val asset: FakeAssetClient,
    @Autowired private val permissions: FakePermissionsClient,
    @Autowired private val executions: FakeInteractiveExecutionClient,
    @Autowired private val slots: UserExecutions,
    @LocalServerPort private val port: Int,
) {
    private val abiertos = CopyOnWriteArrayList<Browser>()

    @BeforeEach
    fun limpiarLosFakes() {
        asset.clear()
        permissions.clear()
        executions.clear()
    }

    @AfterEach
    fun cerrarLasConexiones() {
        abiertos.forEach { runCatching { it.cerrar() } }
        abiertos.clear()
        // Los lugares se devuelven en el servidor, que se entera del cierre un instante después
        esperar { slots.openFor(ana) == 0 && slots.openFor(beto) == 0 }
    }

    @Test
    fun `un snippet que pide dos inputs se completa respondiendo uno por vez`() {
        val id = guardar(ana)
        val browser = conectar(id)
        browser.manda("""{"type":"auth","token":"$TOKEN_DE_ANA"}""")
        esperarQueArranque()

        // PrintScript pide el primer input y el browser lo contesta
        executions.emit("""{"type":"output","text":"Nombre: "}""")
        executions.emit("""{"type":"input_request"}""")
        assertEquals("""{"type":"output","text":"Nombre: "}""", browser.recibe())
        assertEquals("""{"type":"input_request"}""", browser.recibe())
        browser.manda("""{"type":"input","value":"Juan"}""")

        // Y el segundo
        executions.emit("""{"type":"input_request"}""")
        assertEquals("""{"type":"input_request"}""", browser.recibe())
        browser.manda("""{"type":"input","value":"25"}""")

        executions.emit("""{"type":"finished","error":null}""")
        assertEquals("""{"type":"finished","error":null}""", browser.recibe())
        esperar { executions.inputs.size == 2 }
        assertEquals(listOf("Juan", "25"), executions.inputs.toList())
    }

    @Test
    fun `le manda a PrintScript el contenido y la version del snippet, con el token del usuario`() {
        val id = guardar(ana)

        conectar(id).manda("""{"type":"auth","token":"$TOKEN_DE_ANA"}""")
        esperarQueArranque()

        val start = executions.starts.single()
        assertEquals("println(1);", start.code)
        assertEquals("1.1", start.version)
        // El token del usuario se reenvía, igual que en los pedidos HTTP
        assertEquals(TOKEN_DE_ANA, start.token)
    }

    // El handshake entra sin token a propósito, así que lo que protege es que nada pase antes del auth
    @Test
    fun `un mensaje que no sea auth como primero cierra la conexion y no ejecuta nada`() {
        val browser = conectar(guardar(ana))

        browser.manda("""{"type":"input","value":"Juan"}""")

        assertTrue("rejected" in browser.recibe())
        assertNotNull(browser.cierre())
        assertTrue(executions.starts.isEmpty())
    }

    @Test
    fun `un token invalido cierra la conexion sin ejecutar nada`() {
        val browser = conectar(guardar(ana))

        browser.manda("""{"type":"auth","token":"cualquier-cosa"}""")

        assertTrue("rejected" in browser.recibe())
        assertEquals(RECHAZO, browser.cierre()?.code)
        assertTrue(executions.starts.isEmpty())
    }

    @Test
    fun `un token sin el scope cierra la conexion sin ejecutar nada`() {
        val browser = conectar(guardar(ana))

        browser.manda("""{"type":"auth","token":"$TOKEN_SIN_SCOPE"}""")

        assertTrue("rejected" in browser.recibe())
        assertNotNull(browser.cierre())
        assertTrue(executions.starts.isEmpty())
    }

    @Test
    fun `un token sin sub cierra la conexion sin ejecutar nada`() {
        val browser = conectar(guardar(ana))

        browser.manda("""{"type":"auth","token":"$TOKEN_SIN_SUB"}""")

        assertNotNull(browser.cierre())
        assertTrue(executions.starts.isEmpty())
    }

    @Test
    fun `un usuario sin acceso al snippet no lo puede ejecutar`() {
        val browser = conectar(guardar(ana))

        browser.manda("""{"type":"auth","token":"$TOKEN_DE_BETO"}""")

        assertTrue("rejected" in browser.recibe())
        assertNotNull(browser.cierre())
        assertTrue(executions.starts.isEmpty())
    }

    // Ejecutar es leer: alcanza con el acceso de lectura, igual que ver el snippet o correr sus tests
    @Test
    fun `quien tiene el snippet compartido lo puede ejecutar`() {
        val id = guardar(ana)
        permissions.registerReader(id, "auth0|beto")

        conectar(id).manda("""{"type":"auth","token":"$TOKEN_DE_BETO"}""")

        esperarQueArranque()
    }

    @Test
    fun `detras de nginx, con el prefijo de la API, el id del snippet se lee igual`() {
        val id = guardar(ana)
        // Lo que manda nginx: con este header Spring le vuelve a poner el /api a la URI de la sesión
        val nginx = WebSocketHttpHeaders().apply { add("X-Forwarded-Prefix", "/api") }

        conectar(id, nginx).manda("""{"type":"auth","token":"$TOKEN_DE_ANA"}""")
        esperarQueArranque()

        assertEquals("println(1);", executions.starts.single().code)
    }

    @Test
    fun `un snippet que no existe cierra la conexion`() {
        val browser = conectar(UUID.randomUUID())

        browser.manda("""{"type":"auth","token":"$TOKEN_DE_ANA"}""")

        assertTrue("rejected" in browser.recibe())
        assertNotNull(browser.cierre())
    }

    @Test
    fun `cerrar el browser corta la ejecucion en PrintScript`() {
        val browser = conectar(guardar(ana))
        browser.manda("""{"type":"auth","token":"$TOKEN_DE_ANA"}""")
        esperarQueArranque()

        browser.cerrar()

        esperar { executions.closed }
        assertTrue(executions.closed, "no se canceló la ejecución en PrintScript")
    }

    @Test
    fun `si PrintScript corta, el browser recibe el finished y la conexion se cierra`() {
        val browser = conectar(guardar(ana))
        browser.manda("""{"type":"auth","token":"$TOKEN_DE_ANA"}""")
        esperarQueArranque()

        executions.emit("""{"type":"finished","error":{"message":"No se recibió un input en 60 s"}}""")
        executions.finish()

        assertTrue("finished" in browser.recibe())
        assertNotNull(browser.cierre(), "la conexión con el browser quedó abierta")
    }

    @Test
    fun `si PrintScript no esta, se rechaza en vez de dejar la conexion colgada`() {
        executions.failWith = "connection refused"
        val browser = conectar(guardar(ana))

        browser.manda("""{"type":"auth","token":"$TOKEN_DE_ANA"}""")

        assertTrue("rejected" in browser.recibe())
        assertNotNull(browser.cierre())
    }

    // Una conexión que nunca autentica ocuparía un lugar sin haber probado quién es
    @Test
    fun `una conexion que no autentica a tiempo se cierra`() {
        val browser = conectar(guardar(ana))

        assertNotNull(browser.cierre(timeoutSegundos = 6), "la conexión sin autenticar quedó abierta")
        assertTrue(executions.starts.isEmpty())
    }

    @Test
    fun `un input antes de que arranque la ejecucion no llega a PrintScript`() {
        val browser = conectar(guardar(ana))
        browser.manda("""{"type":"auth","token":"$TOKEN_SIN_SCOPE"}""")
        browser.cierre()

        assertTrue(executions.inputs.isEmpty())
    }

    @Test
    fun `un usuario en el tope no puede abrir otra ejecucion, y no se abre nada en PrintScript`() {
        val id = guardar(ana)
        repeat(slots.maxPerUser) {
            conectar(id).manda("""{"type":"auth","token":"$TOKEN_DE_ANA"}""")
        }
        esperar { executions.starts.size == slots.maxPerUser }

        val deMas = conectar(id)
        deMas.manda("""{"type":"auth","token":"$TOKEN_DE_ANA"}""")

        assertTrue("rejected" in deMas.recibe())
        // El motivo está en el mensaje y no en el código: medido, un cierre con 1013 llega como
        // 1002, que diría algo falso. Lo que la UI muestra es el texto del rejected
        assertEquals(RECHAZO, deMas.cierre()?.code)
        // Y no se gastó una conexión a PrintScript en algo que ya se sabía que no iba a entrar
        assertEquals(slots.maxPerUser, executions.starts.size)
    }

    @Test
    fun `al cerrar una ejecucion el mismo usuario puede abrir otra`() {
        val id = guardar(ana)
        val primeros = (1..slots.maxPerUser).map { conectar(id) }
        primeros.forEach { it.manda("""{"type":"auth","token":"$TOKEN_DE_ANA"}""") }
        esperar { executions.starts.size == slots.maxPerUser }

        primeros.first().cerrar()
        esperar { slots.openFor(ana) == slots.maxPerUser - 1 }

        conectar(id).manda("""{"type":"auth","token":"$TOKEN_DE_ANA"}""")
        esperar { executions.starts.size == slots.maxPerUser + 1 }
    }

    // El tope es por usuario: que uno lo llene no puede dejar a los demás sin ejecutar
    @Test
    fun `otro usuario puede ejecutar aunque el primero este en el tope`() {
        val id = guardar(ana)
        permissions.registerReader(id, beto)
        repeat(slots.maxPerUser) {
            conectar(id).manda("""{"type":"auth","token":"$TOKEN_DE_ANA"}""")
        }
        esperar { executions.starts.size == slots.maxPerUser }

        conectar(id).manda("""{"type":"auth","token":"$TOKEN_DE_BETO"}""")

        esperar { executions.starts.size == slots.maxPerUser + 1 }
        assertEquals(1, slots.openFor(beto))
    }

    /**
     * El tope de PrintScript es otro y es global: cuando lo alcanza, manda su `rejected` y cierra.
     *
     * Eso tiene que llegarle al browser sin que el relay lo interprete, porque es el único lugar
     * donde el usuario puede ver por qué no pudo ejecutar.
     */
    @Test
    fun `si PrintScript rechaza por su tope global, el browser recibe el motivo`() {
        val browser = conectar(guardar(ana))
        browser.manda("""{"type":"auth","token":"$TOKEN_DE_ANA"}""")
        esperarQueArranque()

        val rechazo = """{"type":"rejected","detail":"Se alcanzó el máximo de conexiones abiertas (16)"}"""
        executions.emit(rechazo)
        executions.finish()

        assertEquals(rechazo, browser.recibe())
        assertNotNull(browser.cierre())
    }

    /**
     * Si PrintScript cierra mientras el `start` todavía no volvió, la ejecución igual se cierra.
     *
     * Es la carrera que marcó Ignacio en la review: `afterConnectionClosed` saca el relay del mapa
     * y cierra `relay.execution`, pero durante el `start` ese campo todavía es `null`. Sin el
     * arreglo, la ejecución queda guardada en un relay que ya no está en ningún lado y nadie la
     * cierra: se queda ocupando uno de los lugares de PrintScript hasta que se reinicie.
     */
    @Test
    fun `si PrintScript cierra antes de que el start vuelva, la ejecucion no queda abierta`() {
        executions.closeBeforeReturning = true

        conectar(guardar(ana)).manda("""{"type":"auth","token":"$TOKEN_DE_ANA"}""")

        esperarQueArranque()
        esperar { executions.closed }
        assertTrue(executions.closed, "la ejecución quedó abierta contra PrintScript")
    }

    /**
     * Una ejecución que se cierra mientras arrancaba igual devuelve el lugar.
     *
     * Es la misma carrera que el test de arriba, mirada desde el tope: si la ejecución queda sin
     * cerrar, `Releasing.close()` no corre nunca y el usuario pierde un lugar **para siempre**.
     * Tres veces y no puede volver a ejecutar hasta que se reinicie Snippets.
     */
    @Test
    fun `si la ejecucion se cierra mientras arrancaba, el lugar se devuelve igual`() {
        executions.closeBeforeReturning = true

        conectar(guardar(ana)).manda("""{"type":"auth","token":"$TOKEN_DE_ANA"}""")

        esperarQueArranque()
        esperar { slots.openFor(ana) == 0 }
        assertEquals(0, slots.openFor(ana), "el usuario perdió un lugar")
    }

    private val ana = "auth0|ana"
    private val beto = "auth0|beto"

    private fun guardar(ownerId: String): UUID {
        val id = UUID.randomUUID()
        asset.put(id, "println(1);")
        repository.save(Snippet(id, ownerId, "Saludo", "Pide un nombre", "printscript", "1.1"))
        return id
    }

    private fun esperarQueArranque() = esperar { executions.starts.isNotEmpty() }

    // Los mensajes van y vienen en otros threads: se espera a que la condición se cumpla, con tope
    private fun esperar(condicion: () -> Boolean) {
        val limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < limite) {
            if (condicion()) return
            Thread.sleep(20)
        }
        assertTrue(condicion(), "no se cumplió la condición esperada en 5 s")
    }

    private fun conectar(
        snippetId: UUID,
        headers: WebSocketHttpHeaders = WebSocketHttpHeaders(),
    ): Browser {
        val mensajes = LinkedBlockingQueue<String>()
        val cierres = LinkedBlockingQueue<CloseStatus>()

        val handler =
            object : TextWebSocketHandler() {
                override fun handleTextMessage(
                    session: WebSocketSession,
                    message: TextMessage,
                ) {
                    mensajes += message.payload
                }

                override fun afterConnectionClosed(
                    session: WebSocketSession,
                    status: CloseStatus,
                ) {
                    cierres += status
                }
            }

        val session =
            StandardWebSocketClient()
                .execute(handler, headers, URI("ws://localhost:$port/snippets/$snippetId/execution"))
                .get(5, TimeUnit.SECONDS)

        return Browser(session, mensajes, cierres).also { abiertos += it }
    }

    private companion object {
        // 1008, el código con el que se cierra todo rechazo
        const val RECHAZO = 1008
    }

    private class Browser(
        private val session: WebSocketSession,
        private val mensajes: LinkedBlockingQueue<String>,
        private val cierres: LinkedBlockingQueue<CloseStatus>,
    ) {
        fun manda(payload: String) = session.sendMessage(TextMessage(payload))

        fun recibe(): String = assertNotNull(mensajes.poll(5, TimeUnit.SECONDS), "no llegó ningún mensaje en 5 s")

        fun cierre(timeoutSegundos: Long = 5): CloseStatus? = cierres.poll(timeoutSegundos, TimeUnit.SECONDS)

        fun cerrar() = session.close()
    }
}

package snippetsearcher.snippets.execution

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * El tramo browser ⇄ Snippets de la ejecución interactiva. La UI nunca habla directo con PrintScript.
 *
 * **La autenticación va en el primer mensaje y no en el handshake**: al abrir un WebSocket el
 * browser no puede mandar headers, así que no hay dónde poner el Bearer. Hasta que el token no esté
 * validado y el acceso al snippet chequeado, no se ejecuta nada ni se abre nada contra PrintScript.
 * Una conexión que no autentica en `execution.auth-timeout` se cierra: si no, ocuparía un lugar sin
 * haber probado quién es.
 *
 * **Los mensajes se pasan sin interpretarlos.** De PrintScript al browser va el JSON tal como vino;
 * del browser a PrintScript solo se reenvía `input`, porque el `start` lo arma Snippets con el
 * contenido y la versión del snippet, que el browser no elige.
 *
 * Esta clase es solo transporte: quién puede ejecutar qué lo decide `ExecutionStarter`.
 */
@Component
class ExecutionRelayHandler(
    private val starter: ExecutionStarter,
    private val messages: BrowserMessages,
    @Value("\${execution.auth-timeout}") private val authTimeout: Duration,
) : TextWebSocketHandler() {
    private val log = LoggerFactory.getLogger(javaClass)

    private val relays = ConcurrentHashMap<String, Relay>()

    // Un solo thread: lo único que corre acá es cerrar las conexiones que no autenticaron
    private val timeouts = Executors.newSingleThreadScheduledExecutor()

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val relay = Relay()
        relays[session.id] = relay
        relay.authTimeout =
            timeouts.schedule({
                if (relay.execution == null) {
                    log.info("Se cerró una conexión que no autenticó en {}", authTimeout)
                    reject(session, "No se recibió el mensaje 'auth' a tiempo")
                }
            }, authTimeout.toMillis(), TimeUnit.MILLISECONDS)
    }

    override fun handleTextMessage(
        session: WebSocketSession,
        message: TextMessage,
    ) {
        val relay = relays[session.id] ?: return
        val execution = relay.execution

        if (execution == null) {
            val token =
                messages.tokenDe(message.payload)
                    ?: return reject(session, "El primer mensaje tiene que ser 'auth'")
            autenticar(session, relay, token)
        } else {
            val input =
                messages.inputDe(message.payload)
                    ?: return reject(session, "Ya autenticado: solo se acepta 'input'")
            execution.input(input)
        }
    }

    // El scheduler es nuestro, así que se apaga con la app: si no, su thread queda vivo
    @PreDestroy
    fun apagarLosTimeouts() {
        timeouts.shutdownNow()
    }

    override fun afterConnectionClosed(
        session: WebSocketSession,
        status: CloseStatus,
    ) {
        // Cerrar el browser corta la ejecución en PrintScript: la conexión de allá se cierra con esta
        relays.remove(session.id)?.let { relay ->
            relay.authTimeout?.cancel(false)
            relay.execution?.close()
        }
    }

    private fun autenticar(
        session: WebSocketSession,
        relay: Relay,
        token: String,
    ) {
        // Acá se cancela y no al final: el auth ya llegó, así que el timeout no tiene más nada que
        // hacer. Si esperara a que el start vuelva, podría dispararse en el medio, ver la ejecución
        // todavía en null y cerrar una conexión que estaba arrancando bien
        relay.authTimeout?.cancel(false)

        val snippetId = snippetIdDe(session) ?: return reject(session, "La URL no trae un id de snippet válido")

        val started =
            starter.start(
                token,
                snippetId,
                onMessage = { enviar(session, it) },
                // PrintScript terminó o cortó: ya mandó su finished, acá solo se cierra
                onClose = { cerrar(session) },
            )

        when (started) {
            is StartResult.Rejected -> reject(session, started.detail)

            /*
             * El motivo va en el mensaje y no en el código de cierre.
             *
             * El ticket pedía cerrar con 1013 ("probá más tarde"), que es el código correcto, pero
             * no sobrevive: medido, un cierre con 1013 le llega al cliente como 1002 ("error de
             * protocolo"), mientras 1008 y 1011 llegan tal cual. Un 1002 diría algo falso, así que
             * se cierra con 1008 como el resto de los rechazos y la distinción viaja en el texto,
             * que es lo que la UI tiene que mostrar igual.
             */
            is StartResult.TooManyExecutions ->
                reject(session, "Ya tenés ${started.max} ejecuciones abiertas. Cerrá una para empezar otra")

            is StartResult.Started -> {
                relay.execution = started.execution
                /*
                 * Arrancar tarda: `find` puede preguntarle a Permisos y después se abre el
                 * WebSocket con PrintScript. En ese rato el browser puede cerrar, o PrintScript
                 * puede terminar un snippet corto y cerrar.
                 *
                 * Cuando eso pasa, `afterConnectionClosed` ya sacó el relay del mapa y buscó la
                 * ejecución para cerrarla, pero todavía era null. Publicarla y recién después
                 * mirar si el relay sigue en el mapa cierra el agujero: o la vio y la cerró, o
                 * acá se ve que ya no está y se cierra. En el peor caso se cierra dos veces, que
                 * es inofensivo. Sin esto, la ejecución queda ocupando un lugar de PrintScript
                 * hasta que se reinicie.
                 */
                if (relays[session.id] !== relay) started.execution.close()
            }
        }
    }

    /** Lo que ve el browser cuando no se puede ejecutar: el mismo `rejected` del protocolo, y cierre. */
    private fun reject(
        session: WebSocketSession,
        detail: String,
    ) {
        enviar(session, messages.rejected(detail))
        cerrar(session, POLICY_VIOLATION)
    }

    private fun enviar(
        session: WebSocketSession,
        payload: String,
    ) {
        if (!session.isOpen) return
        // Sincronizado porque los mensajes de PrintScript llegan en su propio thread, no en el del browser
        synchronized(session) {
            runCatching { session.sendMessage(TextMessage(payload)) }
                .onFailure { log.debug("No se pudo enviar un mensaje al browser", it) }
        }
    }

    private fun cerrar(
        session: WebSocketSession,
        status: CloseStatus = CloseStatus.NORMAL,
    ) {
        runCatching { session.close(status) }
            .onFailure { log.debug("No se pudo cerrar la conexión con el browser", it) }
    }

    /*
     * El id sale de la ruta, /snippets/{id}/execution: el handler se registra con un patrón, no con
     * @PathVariable. Se toma el tramo anterior a "execution" y no una posición fija porque la ruta
     * puede traer un prefijo adelante: detrás de nginx, X-Forwarded-Prefix hace que Spring le vuelva a
     * poner el /api a la URI de la sesión, y el id deja de estar en el mismo lugar.
     */
    private fun snippetIdDe(session: WebSocketSession): UUID? {
        val tramos = session.uri?.path?.split("/") ?: return null
        val id = tramos.getOrNull(tramos.lastIndexOf("execution") - 1) ?: return null
        return runCatching { UUID.fromString(id) }.getOrNull()
    }

    private class Relay {
        /** Null hasta que el token se validó y PrintScript aceptó: es también el "ya autenticado". */
        @Volatile var execution: InteractiveExecution? = null

        @Volatile var authTimeout: ScheduledFuture<*>? = null
    }

    private companion object {
        // 1008: el mismo código que usa PrintScript para un pedido que no acepta
        val POLICY_VIOLATION = CloseStatus(1008)
    }
}

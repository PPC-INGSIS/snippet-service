package snippetsearcher.snippets.execution

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.handler.TextWebSocketHandler
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.URI
import java.util.concurrent.ExecutionException

/**
 * La ejecución interactiva de PrintScript por WebSocket.
 *
 * El handshake lleva el token del usuario en el header `Authorization`, igual que los pedidos HTTP:
 * acá sí se puede, porque el cliente lo abre Snippets y no un browser.
 */
@Component
class WebSocketInteractiveExecutionClient(
    @Value("\${printscript.ws-url}") private val baseUrl: String,
    private val json: ObjectMapper,
) : InteractiveExecutionClient {
    private val log = LoggerFactory.getLogger(javaClass)

    private val client = StandardWebSocketClient()

    override fun start(
        request: ExecutionStart,
        onMessage: (String) -> Unit,
        onClose: () -> Unit,
    ): StartExecutionResult {
        // WebSocketHttpHeaders y no HttpHeaders: son los headers del handshake
        val headers = WebSocketHttpHeaders().apply { setBearerAuth(request.token) }

        val handler =
            object : TextWebSocketHandler() {
                override fun handleTextMessage(
                    session: WebSocketSession,
                    message: TextMessage,
                ) = onMessage(message.payload)

                override fun afterConnectionClosed(
                    session: WebSocketSession,
                    status: CloseStatus,
                ) = onClose()
            }

        return try {
            // execute() espera el handshake: si PrintScript no está, falla acá y no deja una sesión a medias
            val session = client.execute(handler, headers, URI.create("$baseUrl$PATH")).get()
            session.sendMessage(TextMessage(json.writeValueAsString(Start(request.code, request.version))))
            StartExecutionResult.Started(Session(session))
        } catch (exception: ExecutionException) {
            // El detalle va al log y no al resultado: trae la URL y el puerto internos de PrintScript
            log.warn("No se pudo abrir la ejecución interactiva en PrintScript", exception)
            StartExecutionResult.Failed(FAILED)
        } catch (exception: IOException) {
            log.warn("No se pudo mandar el start a PrintScript", exception)
            StartExecutionResult.Failed(FAILED)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            log.warn("Se interrumpió la apertura de la ejecución interactiva", exception)
            StartExecutionResult.Failed(FAILED)
        }
    }

    private inner class Session(
        private val session: WebSocketSession,
    ) : InteractiveExecution {
        /**
         * Si el envío falla, la conexión ya no sirve: se cierra en vez de propagar el error.
         *
         * Cerrar de este lado dispara el `onClose` del handler, así que el relay se entera por el
         * mismo camino que cuando la ejecución termina sola, y no hay un segundo camino de error.
         */
        override fun input(value: String) {
            try {
                session.sendMessage(TextMessage(json.writeValueAsString(Input(value))))
            } catch (exception: IOException) {
                log.warn("No se pudo mandar un input a PrintScript; se corta la ejecución", exception)
                close()
            }
        }

        override fun close() {
            // Cerrar de este lado cancela la ejecución en PrintScript, que es lo que se quiere
            runCatching { session.close() }
                .onFailure { log.debug("No se pudo cerrar la ejecución en PrintScript", it) }
        }
    }

    // Los dos mensajes que manda el cliente, con los nombres del protocolo de PrintScript
    private data class Start(
        val code: String,
        val version: String,
        val type: String = "start",
    )

    private data class Input(
        val value: String,
        val type: String = "input",
    )

    private companion object {
        const val PATH = "/execute/interactive"
        const val FAILED = "PrintScript no respondió"
    }
}

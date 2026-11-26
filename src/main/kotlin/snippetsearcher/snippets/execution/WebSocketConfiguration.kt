package snippetsearcher.snippets.execution

import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry

@Configuration
@EnableWebSocket
class WebSocketConfiguration(
    private val handler: ExecutionRelayHandler,
) : WebSocketConfigurer {
    /**
     * El `*` es el id del snippet: `/snippets/{id}/execution`.
     *
     * `setAllowedOrigins("*")` y no una lista: la UI y la API salen del mismo dominio por nginx, así
     * que el origen que llega es el propio. El chequeo que importa es el del token en el primer
     * mensaje, no el del origen, que un cliente que no sea un browser igual puede falsear.
     */
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(handler, "/snippets/*/execution").setAllowedOrigins("*")
    }
}

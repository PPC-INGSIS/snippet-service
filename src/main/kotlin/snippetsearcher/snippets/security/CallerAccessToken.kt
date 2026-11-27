package snippetsearcher.snippets.security

import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component

/**
 * El token que sale hacia PrintScript: el del usuario si hay uno en este hilo, y si no, el de Snippets.
 *
 * Es el @Primary entre los tres AccessToken, así que HttpPrintScriptClient no sabe de dónde sale el
 * token: sumar los trabajos sin usuario no lo cambió. Permisos no lo usa: recibe UserAccessToken,
 * porque el token de servicio nunca le sirve.
 *
 * El de servicio aparece en cualquier hilo sin SecurityContext. No solo el consumidor de Redis: también
 * los mensajes del WebSocket de ejecución, que llegan fuera del filtro de Spring Security (el token del
 * usuario viaja en el primer mensaje y no queda en el contexto).
 */
@Primary
@Component
class CallerAccessToken(
    private val user: UserAccessToken,
    private val service: ServiceAccessToken,
) : AccessToken {
    override fun value(): String = user.currentOrNull() ?: service.value()
}

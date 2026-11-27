package snippetsearcher.snippets.security

import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component

/**
 * El token que sale hacia nuestros servicios: el del usuario si hay uno en este hilo, y si no, el de Snippets.
 *
 * Es el que reciben los clientes (@Primary entre los tres AccessToken), así que no saben de dónde sale
 * el token: sumar los trabajos sin usuario no los cambió.
 *
 * Adentro de un pedido siempre hay usuario, porque todos los endpoints exigen token. El de servicio
 * aparece solo en hilos que no vienen de un pedido, como el consumidor de Redis.
 */
@Primary
@Component
class CallerAccessToken(
    private val user: UserAccessToken,
    private val service: ServiceAccessToken,
) : AccessToken {
    override fun value(): String = user.currentOrNull() ?: service.value()
}

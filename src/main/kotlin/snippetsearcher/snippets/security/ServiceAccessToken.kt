package snippetsearcher.snippets.security

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import snippetsearcher.snippets.auth0.ClientCredentialsToken
import java.time.Clock
import java.time.Duration

/**
 * El token propio de Snippets, para llamar a nuestros servicios cuando no hay un usuario: los trabajos
 * en segundo plano (linteo y formateo automáticos, correr los tests al modificar).
 *
 * - Flujo Client Credentials con la audiencia de nuestra API: "sin usuario, un servicio habla con otra
 *   API" (presentación de Auth0, diap. 41).
 * - Solo tiene `read:snippets`, que es lo que pide PrintScript. Con Permisos no sirve: autoriza por el
 *   `sub`, y el de este token (`<client-id>@clients`) no es dueño de nada. Los trabajos sin usuario no
 *   le hablan a Permisos: ya saben de qué snippet se trata y de quién es (#50).
 */
@Component
class ServiceAccessToken(
    @Value("\${auth0.service.url}") url: String,
    @Value("\${auth0.service.audience}") audience: String,
    @Value("\${auth0.service.client-id}") clientId: String,
    @Value("\${auth0.service.client-secret}") clientSecret: String,
    clock: Clock = Clock.systemUTC(),
    timeout: Duration = AUTH0_TIMEOUT,
) : AccessToken {
    private val token = ClientCredentialsToken(url, audience, clientId, clientSecret, clock, timeout)

    override fun value(): String = token.value()

    private companion object {
        // Lo mismo que se le espera a Auth0 para la Management API
        val AUTH0_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}

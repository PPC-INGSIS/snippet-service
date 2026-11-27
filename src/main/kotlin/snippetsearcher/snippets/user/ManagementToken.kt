package snippetsearcher.snippets.user

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import snippetsearcher.snippets.auth0.ClientCredentialsToken
import java.time.Clock
import java.time.Duration

// Token de la Management API de Auth0. Cómo se pide y cuándo se renueva vive en ClientCredentialsToken
@Component
class ManagementToken(
    @Value("\${auth0.management.url}") url: String,
    @Value("\${auth0.management.audience}") audience: String,
    @Value("\${auth0.management.client-id}") clientId: String,
    @Value("\${auth0.management.client-secret}") clientSecret: String,
    clock: Clock = Clock.systemUTC(),
    timeout: Duration = AUTH0_TIMEOUT,
) {
    private val token = ClientCredentialsToken(url, audience, clientId, clientSecret, clock, timeout)

    fun value(): String = token.value()
}

package snippetsearcher.snippets.user

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import java.time.Clock
import java.time.Duration
import java.time.Instant

// Token de la Management API de Auth0 (flujo Client Credentials).
// Se pide una vez y se reutiliza hasta que está por vencer.
@Component
class ManagementToken(
    @Value("\${auth0.management.url}") url: String,
    @Value("\${auth0.management.audience}") private val audience: String,
    @Value("\${auth0.management.client-id}") private val clientId: String,
    @Value("\${auth0.management.client-secret}") private val clientSecret: String,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val restClient = RestClient.create(url)
    private var current: IssuedToken? = null

    // Synchronized: si llegan dos búsquedas juntas con el token vencido, se pide uno solo
    @Synchronized
    fun value(): String {
        val token = current?.takeIf { clock.instant() < it.renewAt } ?: request().also { current = it }
        return token.value
    }

    private fun request(): IssuedToken {
        // Un Map y no una data class: el toString de una data class mostraría el secreto si alguien la loguea
        val body =
            mapOf(
                "grant_type" to "client_credentials",
                "client_id" to clientId,
                "client_secret" to clientSecret,
                "audience" to audience,
            )
        val response =
            restClient
                .post()
                .uri("/oauth/token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body<TokenResponse>()
                ?: error("Auth0 respondió sin cuerpo al pedir el token de la Management API")

        val renewAt = clock.instant().plusSeconds(response.expiresIn).minus(RENEW_BEFORE_EXPIRY)
        return IssuedToken(response.accessToken, renewAt)
    }

    private class IssuedToken(
        val value: String,
        val renewAt: Instant,
    )

    companion object {
        // Margen para no usar un token que vence en el camino a Auth0
        private val RENEW_BEFORE_EXPIRY: Duration = Duration.ofMinutes(1)
    }
}

// Respuesta de POST /oauth/token
data class TokenResponse(
    @param:JsonProperty("access_token") val accessToken: String,
    @param:JsonProperty("expires_in") val expiresIn: Long,
)

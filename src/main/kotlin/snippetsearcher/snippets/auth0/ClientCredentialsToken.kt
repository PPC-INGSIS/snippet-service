package snippetsearcher.snippets.auth0

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.http.MediaType
import org.springframework.web.client.body
import snippetsearcher.snippets.http.timedRestClient
import java.time.Clock
import java.time.Duration
import java.time.Instant

// Cuánto se espera a Auth0 como máximo, para conectarse y para cada respuesta
private const val AUTH0_TIMEOUT_SECONDS = 5L

internal val AUTH0_TIMEOUT: Duration = Duration.ofSeconds(AUTH0_TIMEOUT_SECONDS)

/**
 * Un token de Auth0 pedido con el flujo Client Credentials, para una audiencia.
 *
 * Se pide una vez y se reutiliza hasta que está por vencer. No es un @Component: cada token que
 * necesita Snippets lo arma con su audiencia y sus credenciales.
 */
class ClientCredentialsToken(
    url: String,
    private val audience: String,
    private val clientId: String,
    private val clientSecret: String,
    private val clock: Clock = Clock.systemUTC(),
    timeout: Duration = AUTH0_TIMEOUT,
) {
    // Sin el token del usuario: a Auth0 se le habla con las credenciales de la app
    private val restClient = timedRestClient(url, timeout, timeout)
    private var current: IssuedToken? = null

    // Synchronized: si llegan dos pedidos juntos con el token vencido, se pide uno solo.
    // Los demás esperan, por eso el pedido a Auth0 tiene timeout: si se cuelga, no los frena a todos
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
                ?: error("Auth0 respondió sin cuerpo al pedir un token para $audience")

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

// Respuesta de POST /oauth/token. Clase común y no data class: el toString de una data class
// mostraría el access token si alguien la loguea
class TokenResponse(
    @param:JsonProperty("access_token") val accessToken: String,
    @param:JsonProperty("expires_in") val expiresIn: Long,
)

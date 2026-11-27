package snippetsearcher.snippets.user

import org.springframework.web.client.RestClient
import snippetsearcher.snippets.http.timedRestClient
import java.time.Duration

// Cuánto se espera a Auth0 como máximo, para conectarse y para cada respuesta
private const val AUTH0_TIMEOUT_SECONDS = 5L

internal val AUTH0_TIMEOUT: Duration = Duration.ofSeconds(AUTH0_TIMEOUT_SECONDS)

// Sin el token del usuario: a la Management API se le habla con el token propio de Snippets
internal fun auth0RestClient(
    url: String,
    timeout: Duration,
): RestClient = timedRestClient(url, timeout, timeout)

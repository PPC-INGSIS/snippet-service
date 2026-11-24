package snippetsearcher.snippets.user

import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

// Cuánto se espera a Auth0 como máximo, para conectarse y para cada respuesta
private const val AUTH0_TIMEOUT_SECONDS = 5L

internal val AUTH0_TIMEOUT: Duration = Duration.ofSeconds(AUTH0_TIMEOUT_SECONDS)

// RestClient con techo de espera: si Auth0 se cuelga, el pedido falla en vez de esperar para siempre
internal fun auth0RestClient(
    url: String,
    timeout: Duration,
): RestClient {
    val factory =
        SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(timeout)
            setReadTimeout(timeout)
        }
    return RestClient.builder().baseUrl(url).requestFactory(factory).build()
}

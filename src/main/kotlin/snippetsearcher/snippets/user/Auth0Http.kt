package snippetsearcher.snippets.user

import org.springframework.web.client.RestClient
import snippetsearcher.snippets.http.timedRestClient
import java.time.Duration

// Sin el token del usuario: a la Management API se le habla con el token propio de Snippets
internal fun auth0RestClient(
    url: String,
    timeout: Duration,
): RestClient = timedRestClient(url, timeout, timeout)

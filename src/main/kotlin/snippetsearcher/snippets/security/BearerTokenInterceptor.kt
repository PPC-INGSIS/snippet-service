package snippetsearcher.snippets.security

import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse

// Le agrega el token a cada pedido que sale de un RestClient. Va en los clientes de nuestros servicios,
// que validan el token (presentación de Auth0, diap. 48)
class BearerTokenInterceptor(
    private val accessToken: AccessToken,
) : ClientHttpRequestInterceptor {
    override fun intercept(
        request: HttpRequest,
        body: ByteArray,
        execution: ClientHttpRequestExecution,
    ): ClientHttpResponse {
        request.headers.setBearerAuth(accessToken.value())
        return execution.execute(request, body)
    }
}

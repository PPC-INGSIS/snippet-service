package snippetsearcher.snippets.http

import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import snippetsearcher.snippets.security.AccessToken
import snippetsearcher.snippets.security.BearerTokenInterceptor
import java.net.http.HttpClient
import java.time.Duration

/**
 * El RestClient para hablar con otro servicio, siempre con techo de espera.
 *
 * Sin timeouts, un servicio colgado cuelga al thread que lo llama. Los dos son distintos: connect es
 * "no me atienden" y read es "me atienden y no contestan". El connect va en el HttpClient porque el
 * request factory solo expone el de lectura.
 *
 * Con [accessToken], cada pedido lleva el token del usuario: nuestros servicios lo validan
 * (presentación de Auth0, diap. 48). Sin él, el pedido sale sin header: el asset service de la
 * cátedra y Auth0, que se autentica con su propio token, no lo necesitan.
 */
fun timedRestClient(
    baseUrl: String,
    connectTimeout: Duration,
    readTimeout: Duration,
    accessToken: AccessToken? = null,
): RestClient {
    val builder =
        RestClient
            .builder()
            .baseUrl(baseUrl)
            .requestFactory(
                JdkClientHttpRequestFactory(
                    HttpClient.newBuilder().connectTimeout(connectTimeout).build(),
                ).apply { setReadTimeout(readTimeout) },
            )
    accessToken?.let { builder.requestInterceptor(BearerTokenInterceptor(it)) }
    return builder.build()
}

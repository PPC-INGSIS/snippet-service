package snippetsearcher.snippets.permissions

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import snippetsearcher.snippets.security.AccessToken
import snippetsearcher.snippets.security.BearerTokenInterceptor
import java.net.http.HttpClient
import java.time.Duration
import java.util.UUID

@Component
class HttpPermissionsClient(
    @Value("\${permissions.url}") baseUrl: String,
    @Value("\${permissions.connect-timeout}") connectTimeout: Duration,
    @Value("\${permissions.read-timeout}") readTimeout: Duration,
    accessToken: AccessToken,
) : PermissionsClient {
    private val log = LoggerFactory.getLogger(javaClass)

    // Permisos valida el token igual que nosotros, así que hay que reenviar el del
    // usuario. Y con timeouts: sin eso, un Permisos colgado cuelga al thread que llama.
    private val restClient =
        RestClient
            .builder()
            .baseUrl(baseUrl)
            .requestInterceptor(BearerTokenInterceptor(accessToken))
            .requestFactory(
                JdkClientHttpRequestFactory(
                    HttpClient.newBuilder().connectTimeout(connectTimeout).build(),
                ).apply { setReadTimeout(readTimeout) },
            ).build()

    override fun registerOwner(
        snippetId: UUID,
        ownerId: String,
    ): RegisterAccessResult = register(snippetId, ownerId, OWNER)

    override fun registerReader(
        snippetId: UUID,
        userId: String,
    ): RegisterAccessResult = register(snippetId, userId, READ)

    // Los dos son el mismo POST: lo unico que cambia es el rol
    private fun register(
        snippetId: UUID,
        userId: String,
        role: String,
    ): RegisterAccessResult =
        try {
            restClient
                .post()
                .uri("/permissions")
                .contentType(MediaType.APPLICATION_JSON)
                .body(RegisterAccessRequest(snippetId, userId, role))
                .retrieve()
                .toBodilessEntity()

            RegisterAccessResult.Registered
        } catch (ignored: HttpClientErrorException.Conflict) {
            // 409 es "ya estaba registrado". Para nosotros el resultado es el mismo que
            // haberlo creado: el dueño quedó. Así un reintento no falla por el primero.
            RegisterAccessResult.Registered
        } catch (exception: RestClientException) {
            // El detalle va al log y no a la respuesta: el mensaje de la excepción suele
            // traer la URL y el puerto internos.
            log.warn("No se pudo registrar el acceso {} al snippet {} en Permisos", role, snippetId, exception)
            RegisterAccessResult.Failed(FAILED_MESSAGE)
        }

    companion object {
        private const val OWNER = "OWNER"
        private const val READ = "READ"
        private const val FAILED_MESSAGE = "Permisos no respondió"
    }
}

// El cuerpo que espera POST /permissions
data class RegisterAccessRequest(
    val snippetId: UUID,
    val userId: String,
    val role: String,
)

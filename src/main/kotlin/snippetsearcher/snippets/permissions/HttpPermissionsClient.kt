package snippetsearcher.snippets.permissions

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClientException
import org.springframework.web.client.body
import snippetsearcher.snippets.http.timedRestClient
import snippetsearcher.snippets.security.UserAccessToken
import java.time.Duration
import java.util.UUID

@Component
class HttpPermissionsClient(
    @Value("\${permissions.url}") baseUrl: String,
    @Value("\${permissions.connect-timeout}") connectTimeout: Duration,
    @Value("\${permissions.read-timeout}") readTimeout: Duration,
    // El del usuario y no el AccessToken @Primary: Permisos autoriza por el sub, y el del token de servicio
    // no es dueño de nada. Sin usuario en el hilo falla con ese error, y no con un 403 que parece una caída
    accessToken: UserAccessToken,
) : PermissionsClient {
    private val log = LoggerFactory.getLogger(javaClass)

    // Permisos valida el token igual que nosotros, así que hay que reenviar el del usuario
    private val restClient = timedRestClient(baseUrl, connectTimeout, readTimeout, accessToken)

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
            // 409 es "ese usuario ya tiene un rol sobre ese snippet". Para nosotros el resultado
            // es el mismo que haberlo creado: el acceso quedó. Así un reintento no falla por el primero.
            RegisterAccessResult.Registered
        } catch (exception: RestClientException) {
            // El detalle va al log y no a la respuesta: el mensaje de la excepción suele
            // traer la URL y el puerto internos.
            log.warn("No se pudo registrar el acceso {} al snippet {} en Permisos", role, snippetId, exception)
            RegisterAccessResult.Failed(FAILED_MESSAGE)
        }

    override fun deleteAccesses(snippetId: UUID): DeleteAccessesResult =
        try {
            // Permisos responde 204 aunque no hubiera accesos, así que no hay un 404 que distinguir
            restClient
                .delete()
                .uri("/permissions/{snippetId}", snippetId)
                .retrieve()
                .toBodilessEntity()

            DeleteAccessesResult.Deleted
        } catch (exception: RestClientException) {
            log.warn("No se pudieron borrar los accesos del snippet {} en Permisos", snippetId, exception)
            DeleteAccessesResult.Failed(FAILED_MESSAGE)
        }

    override fun roleOf(
        snippetId: UUID,
        userId: String,
    ): RoleResult =
        try {
            // El userId va como query param: es el sub de Auth0 y puede traer "|", que en el path rompe
            val response =
                restClient
                    .get()
                    .uri("/permissions/{snippetId}?userId={userId}", snippetId, userId)
                    .retrieve()
                    .body<PermissionResponse>()

            response?.let { RoleResult.Found(it.role) } ?: run {
                log.warn("Permisos respondió el rol del snippet {} sin body", snippetId)
                RoleResult.Failed(FAILED_MESSAGE)
            }
        } catch (ignored: HttpClientErrorException.NotFound) {
            // El 404 de Permisos es "no tiene acceso": una respuesta, no una falla
            RoleResult.None
        } catch (exception: RestClientException) {
            log.warn("No se pudo consultar el acceso al snippet {} en Permisos", snippetId, exception)
            RoleResult.Failed(FAILED_MESSAGE)
        }

    override fun sharedWith(userId: String): SharedSnippetsResult =
        try {
            val accesses =
                restClient
                    .get()
                    .uri("/permissions?userId={userId}&role={role}", userId, READ)
                    .retrieve()
                    .body<List<AccessResponse>>()

            // Sin accesos Permisos responde una lista vacía, no 404: el null es un body que no llegó
            accesses?.let { list -> SharedSnippetsResult.Found(list.map { it.snippetId }) } ?: run {
                log.warn("Permisos respondió los snippets compartidos sin body")
                SharedSnippetsResult.Failed(FAILED_MESSAGE)
            }
        } catch (exception: RestClientException) {
            log.warn("No se pudieron consultar los snippets compartidos en Permisos", exception)
            SharedSnippetsResult.Failed(FAILED_MESSAGE)
        }

    companion object {
        private const val OWNER = "OWNER"
        private const val READ = "READ"
        private const val FAILED_MESSAGE = "Permisos no respondió"
    }
}

// Respuesta de GET /permissions/{snippetId}. Solo se usa el rol
data class PermissionResponse(
    val role: String,
)

// Cada elemento de GET /permissions?userId=. Solo se usa el snippet
data class AccessResponse(
    val snippetId: UUID,
)

// El cuerpo que espera POST /permissions
data class RegisterAccessRequest(
    val snippetId: UUID,
    val userId: String,
    val role: String,
)

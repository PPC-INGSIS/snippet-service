package snippetsearcher.snippets.asset

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.body
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID

@Component
class HttpAssetClient(
    @Value("\${asset.url}") baseUrl: String,
) : AssetClient {
    // Con timeout: sin esto, un asset service colgado cuelga también al thread que lo llama
    private val restClient =
        RestClient
            .builder()
            .baseUrl(baseUrl)
            .requestFactory(JdkClientHttpRequestFactory().apply { setReadTimeout(READ_TIMEOUT) })
            .build()

    override fun put(
        snippetId: UUID,
        content: String,
    ): SaveContentResult =
        try {
            restClient
                .put()
                .uri("/v1/asset/{container}/{key}", CONTAINER, snippetId)
                .contentType(MediaType("text", "plain", StandardCharsets.UTF_8))
                .body(content)
                .retrieve()
                .toBodilessEntity()

            SaveContentResult.Saved
        } catch (exception: RestClientException) {
            // Un 4xx, un 5xx o el servicio sin responder: para el que llama es lo mismo,
            // no se guardó. El detalle va en el mensaje, no en el tipo.
            SaveContentResult.Failed(exception.describe())
        }

    override fun get(snippetId: UUID): ContentResult =
        try {
            val content =
                restClient
                    .get()
                    .uri("/v1/asset/{container}/{key}", CONTAINER, snippetId)
                    .retrieve()
                    .body<String>()

            ContentResult.Found(content.orEmpty())
        } catch (ignored: HttpClientErrorException.NotFound) {
            // El 404 es una respuesta, no una falla: ese snippet no tiene contenido
            ContentResult.NotFound
        } catch (exception: RestClientException) {
            ContentResult.Failed(exception.describe())
        }

    private fun RestClientException.describe() = message ?: "El asset service no respondió"

    companion object {
        // El asset service agrupa los archivos por "container"; los nuestros van todos juntos
        private const val CONTAINER = "snippets"
        private val READ_TIMEOUT: Duration = Duration.ofSeconds(5)
    }
}

package snippetsearcher.snippets.asset

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.body
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID

@Component
class HttpAssetClient(
    @Value("\${asset.url}") baseUrl: String,
    @Value("\${asset.connect-timeout}") connectTimeout: Duration,
    @Value("\${asset.read-timeout}") readTimeout: Duration,
) : AssetClient {
    private val log = LoggerFactory.getLogger(javaClass)

    // Los dos timeouts: connect es "no me atienden" y read es "me atienden y no contestan".
    // El connect va en el HttpClient porque el request factory solo expone el de lectura.
    private val restClient =
        RestClient
            .builder()
            .baseUrl(baseUrl)
            .requestFactory(
                JdkClientHttpRequestFactory(
                    HttpClient.newBuilder().connectTimeout(connectTimeout).build(),
                ).apply { setReadTimeout(readTimeout) },
            ).build()

    override fun put(
        snippetId: UUID,
        content: String,
    ): SaveContentResult =
        try {
            restClient
                .put()
                .uri(PATH, CONTAINER, snippetId)
                .contentType(MediaType("text", "plain", StandardCharsets.UTF_8))
                .body(content)
                .retrieve()
                .toBodilessEntity()

            SaveContentResult.Saved
        } catch (exception: RestClientException) {
            // Un 4xx, un 5xx o el servicio sin responder: para el que llama es lo mismo,
            // no se guardó. El detalle va al log, no a la respuesta: el mensaje de la
            // excepción suele traer la URL y el puerto internos.
            log.warn("No se pudo guardar el contenido del snippet {}", snippetId, exception)
            SaveContentResult.Failed(FAILED_MESSAGE)
        }

    override fun get(snippetId: UUID): ContentResult =
        try {
            val bytes =
                restClient
                    .get()
                    .uri(PATH, CONTAINER, snippetId)
                    .retrieve()
                    .body<ByteArray>()

            // Bytes y no String a propósito. El asset service responde text/event-stream
            // sin charset, y ante eso Spring decodifica con ISO-8859-1: un contenido con
            // acentos volvía roto. Nosotros lo escribimos en UTF-8, así que lo leemos así.
            ContentResult.Found(String(bytes ?: ByteArray(0), StandardCharsets.UTF_8))
        } catch (ignored: HttpClientErrorException.NotFound) {
            // El 404 es una respuesta, no una falla: ese snippet no tiene contenido
            ContentResult.NotFound
        } catch (exception: RestClientException) {
            log.warn("No se pudo leer el contenido del snippet {}", snippetId, exception)
            ContentResult.Failed(FAILED_MESSAGE)
        }

    override fun delete(snippetId: UUID): DeleteContentResult =
        try {
            restClient
                .delete()
                .uri(PATH, CONTAINER, snippetId)
                .retrieve()
                .toBodilessEntity()

            DeleteContentResult.Deleted
        } catch (ignored: HttpClientErrorException.NotFound) {
            // El 404 es "ya no está": el resultado es el que pedimos, así un reintento no falla
            DeleteContentResult.Deleted
        } catch (exception: RestClientException) {
            log.warn("No se pudo borrar el contenido del snippet {}", snippetId, exception)
            DeleteContentResult.Failed(FAILED_MESSAGE)
        }

    companion object {
        // El asset service agrupa los archivos por "container"; los nuestros van todos juntos
        private const val CONTAINER = "snippets"
        private const val PATH = "/v1/asset/{container}/{key}"

        // Texto fijo: lo que el cliente ve no puede depender de un mensaje interno
        private const val FAILED_MESSAGE = "El asset service no respondió"
    }
}

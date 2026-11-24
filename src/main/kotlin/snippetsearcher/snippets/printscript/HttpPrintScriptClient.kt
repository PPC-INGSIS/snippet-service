package snippetsearcher.snippets.printscript

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.body
import snippetsearcher.snippets.security.AccessToken
import snippetsearcher.snippets.security.BearerTokenInterceptor
import java.nio.charset.StandardCharsets

@Component
class HttpPrintScriptClient(
    @Value("\${printscript.url}") baseUrl: String,
    accessToken: AccessToken,
) : PrintScriptClient {
    private val restClient =
        RestClient
            .builder()
            .baseUrl(baseUrl)
            .requestInterceptor(BearerTokenInterceptor(accessToken))
            .build()

    override fun validate(
        version: String,
        content: String,
    ): ValidationResult =
        try {
            val response =
                restClient
                    .post()
                    .uri("/validate?version={version}", version)
                    .contentType(MediaType("text", "plain", StandardCharsets.UTF_8))
                    .body(content)
                    .retrieve()
                    .body<ValidationResponse>()

            ValidationResult.Checked(response?.errors.orEmpty())
        } catch (exception: HttpClientErrorException) {
            // RestClient convierte los 4xx en excepciones; los rechazos de PrintScript vuelven a ser resultados
            if (exception.statusCode.value() !in REJECTED_STATUSES) throw exception
            ValidationResult.Rejected(rejectionDetail(exception) ?: DEFAULT_REJECTION)
        }

    private fun rejectionDetail(exception: HttpClientErrorException): String? =
        try {
            exception
                .getResponseBodyAs(RejectedResponse::class.java)
                ?.detail
                ?.takeUnless { it.isBlank() }
        } catch (ignored: RestClientException) {
            // El body no es Problem Details (por ejemplo, HTML de un proxy)
            null
        }
}

// Se comparan números y no constantes de HttpStatus: el 413 tiene dos (PAYLOAD_TOO_LARGE, deprecada,
// y CONTENT_TOO_LARGE), así que el número es lo único que no es ambiguo
private const val BAD_REQUEST = 400
private const val CONTENT_TOO_LARGE = 413

// 400: versión inexistente o body vacío. 413: el código supera el tamaño máximo de PrintScript
private val REJECTED_STATUSES = setOf(BAD_REQUEST, CONTENT_TOO_LARGE)

private const val DEFAULT_REJECTION = "PrintScript rechazó el pedido"

// Respuesta 200 de printscript-service
data class ValidationResponse(
    val errors: List<ValidationError>,
)

// Respuesta 400 o 413 de printscript-service, en formato Problem Details (RFC 9457)
data class RejectedResponse(
    val detail: String? = null,
)

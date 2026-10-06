package snippetsearcher.snippets.printscript

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import java.nio.charset.StandardCharsets

@Component
class HttpPrintScriptClient(
    @Value("\${printscript.url}") baseUrl: String,
) : PrintScriptClient {
    private val restClient = RestClient.create(baseUrl)

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
        } catch (exception: HttpClientErrorException.BadRequest) {
            // RestClient convierte el 400 en excepción; acá vuelve a ser un resultado
            val message = exception.getResponseBodyAs(RejectedResponse::class.java)?.message
            ValidationResult.Rejected(message ?: "PrintScript rechazó el pedido")
        }
}

// Respuesta 200 de printscript-service
data class ValidationResponse(
    val errors: List<ValidationError>,
)

// Respuesta 400 de printscript-service
data class RejectedResponse(
    val message: String? = null,
)

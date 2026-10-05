package snippetsearcher.snippets

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
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
    ): List<ValidationError> {
        val response =
            restClient
                .post()
                .uri("/validate?version={version}", version)
                .contentType(MediaType("text", "plain", StandardCharsets.UTF_8))
                .body(content)
                .retrieve()
                .body<ValidationResponse>()

        return response?.errors.orEmpty()
    }
}

// La forma del JSON que responde printscript-service
data class ValidationResponse(
    val errors: List<ValidationError>,
)

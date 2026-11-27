package snippetsearcher.snippets.formatting

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/snippets/{snippetId}/format")
class SnippetFormatController(
    private val service: SnippetFormatService,
) {
    @PostMapping
    fun format(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable snippetId: UUID,
        @RequestBody request: FormatSnippetRequest,
    ): ResponseEntity<out Any> {
        val userId = jwt.subject ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        return when (val result = service.format(userId, snippetId, request.content)) {
            is FormatSnippetResult.Formatted -> ResponseEntity.ok(FormattedSnippetResponse(result.content))

            FormatSnippetResult.NotFound -> ResponseEntity.notFound().build()

            // 500 y no 400: lo inconsistente son nuestros datos (ya quedó en el log). Mismo criterio que
            // ContentMissing en POST /tests/run
            FormatSnippetResult.LanguageMissing -> ResponseEntity.internalServerError().build()

            // 400 con el mensaje del lenguaje: dice qué hay que cambiar en el código, por ejemplo un ';' que falta
            is FormatSnippetResult.Rejected ->
                ResponseEntity.badRequest().body(
                    ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, result.message),
                )

            // 502 y no 500: el pedido estaba bien, falló un servicio nuestro
            is FormatSnippetResult.Unavailable ->
                ResponseEntity
                    .status(HttpStatus.BAD_GATEWAY)
                    .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "No se pudo formatear el snippet"))
        }
    }
}

data class FormatSnippetRequest(
    val content: String,
)

// Un objeto y no el texto suelto: se le pueden sumar campos (por ejemplo, las reglas ignoradas) sin romper a la UI
data class FormattedSnippetResponse(
    val content: String,
)

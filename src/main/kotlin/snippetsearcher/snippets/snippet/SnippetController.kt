package snippetsearcher.snippets.snippet

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import snippetsearcher.snippets.printscript.ValidationError
import java.util.UUID

@RestController
@RequestMapping("/snippets")
class SnippetController(
    private val service: SnippetService,
) {
    @PostMapping
    fun create(
        @RequestBody request: CreateSnippetRequest,
    ): ResponseEntity<out Any> {
        val result =
            service.create(
                request.name,
                request.description,
                request.language,
                request.version,
                request.content,
            )

        return when (result) {
            is CreateSnippetResult.Created ->
                ResponseEntity.status(HttpStatus.CREATED).body(result.snippet.toResponse())
            is CreateSnippetResult.Invalid ->
                ResponseEntity.badRequest().body(InvalidSnippetResponse(result.errors))
            is CreateSnippetResult.Rejected ->
                ResponseEntity.badRequest().body(RejectedSnippetResponse(result.message))

            // 502 y no 400: el pedido estaba bien, falló un servicio nuestro. Con un 4xx
            // el cliente dejaría de reintentar algo que sí puede llegar a funcionar.
            // El detalle no viaja: ya quedó en el log del cliente, y suele traer la URL
            // y el puerto internos.
            is CreateSnippetResult.ContentNotSaved ->
                ResponseEntity
                    .status(HttpStatus.BAD_GATEWAY)
                    .body(UpstreamErrorResponse("No se pudo guardar el contenido del snippet"))
        }
    }
}

data class CreateSnippetRequest(
    val name: String,
    val description: String,
    val language: String,
    val version: String,
    val content: String,
)

data class SnippetResponse(
    val id: UUID,
    val name: String,
    val description: String,
    val language: String,
    val version: String,
)

data class InvalidSnippetResponse(
    val errors: List<ValidationError>,
)

private fun Snippet.toResponse() = SnippetResponse(id, name, description, language, version)

data class RejectedSnippetResponse(
    val message: String,
)

// Mismo cuerpo que RejectedSnippetResponse, otro nombre: este es un 5xx, no culpa del cliente
data class UpstreamErrorResponse(
    val message: String,
)

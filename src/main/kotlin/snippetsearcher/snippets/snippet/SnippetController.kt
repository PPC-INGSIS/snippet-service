package snippetsearcher.snippets.snippet

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
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
    @GetMapping("/{id}")
    fun get(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable id: UUID,
    ): ResponseEntity<out Any> {
        val ownerId = jwt.subject ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        return when (val result = service.find(ownerId, id)) {
            is FindSnippetResult.Found -> ResponseEntity.ok(result.snippet.toDetailResponse(result.content))

            FindSnippetResult.NotFound -> ResponseEntity.notFound().build()

            // 500 y no 502: el asset service contestó bien, lo inconsistente son nuestros datos (ya quedó en el log)
            FindSnippetResult.ContentMissing -> ResponseEntity.internalServerError().build()

            // 502 y no 404: el snippet existe, falló el servicio que guarda su contenido
            is FindSnippetResult.ContentUnavailable ->
                ResponseEntity
                    .status(HttpStatus.BAD_GATEWAY)
                    .body(UpstreamErrorResponse("No se pudo leer el contenido del snippet"))
        }
    }

    @DeleteMapping("/{id}")
    fun delete(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable id: UUID,
    ): ResponseEntity<Void> {
        val ownerId = jwt.subject ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        return when (service.delete(ownerId, id)) {
            DeleteSnippetResult.Deleted -> ResponseEntity.noContent().build()
            DeleteSnippetResult.NotFound -> ResponseEntity.notFound().build()
        }
    }

    @PutMapping("/{id}")
    fun update(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable id: UUID,
        @RequestBody request: UpdateSnippetRequest,
    ): ResponseEntity<out Any> {
        val ownerId = jwt.subject ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        val changes =
            NewSnippet(
                request.name,
                request.description,
                request.language,
                request.version,
                request.content,
            )

        return when (val result = service.update(ownerId, id, changes)) {
            is UpdateSnippetResult.Updated ->
                ResponseEntity.ok(result.snippet.toDetailResponse(request.content))

            UpdateSnippetResult.NotFound -> ResponseEntity.notFound().build()

            is UpdateSnippetResult.Invalid ->
                ResponseEntity.badRequest().body(InvalidSnippetResponse(result.errors))

            is UpdateSnippetResult.Rejected ->
                ResponseEntity.badRequest().body(RejectedSnippetResponse(result.message))

            // 502 y no 400, igual que al crear: el pedido estaba bien, falló un servicio nuestro
            is UpdateSnippetResult.ContentNotSaved ->
                ResponseEntity
                    .status(HttpStatus.BAD_GATEWAY)
                    .body(UpstreamErrorResponse("No se pudo guardar el contenido del snippet"))
        }
    }

    @PostMapping
    fun create(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestBody request: CreateSnippetRequest,
    ): ResponseEntity<out Any> {
        val ownerId = jwt.subject ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        val newSnippet =
            NewSnippet(
                request.name,
                request.description,
                request.language,
                request.version,
                request.content,
            )

        return when (val result = service.create(ownerId, newSnippet)) {
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
            // Mismo criterio que el contenido: fallo un servicio nuestro, no el pedido
            is CreateSnippetResult.OwnerNotRegistered ->
                ResponseEntity
                    .status(HttpStatus.BAD_GATEWAY)
                    .body(UpstreamErrorResponse("No se pudo registrar al dueño del snippet"))

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

// Reemplazo total: los mismos campos que al crear, pero es otro pedido y puede cambiar por su lado
data class UpdateSnippetRequest(
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

// Lo mismo que SnippetResponse más el contenido, que solo viaja al pedir un snippet puntual
data class SnippetDetailResponse(
    val id: UUID,
    val name: String,
    val description: String,
    val language: String,
    val version: String,
    val content: String,
)

data class InvalidSnippetResponse(
    val errors: List<ValidationError>,
)

private fun Snippet.toResponse() = SnippetResponse(id, name, description, language, version)

private fun Snippet.toDetailResponse(content: String) =
    SnippetDetailResponse(id, name, description, language, version, content)

data class RejectedSnippetResponse(
    val message: String,
)

// Mismo cuerpo que RejectedSnippetResponse, otro nombre: este es un 5xx, no culpa del cliente
data class UpstreamErrorResponse(
    val message: String,
)

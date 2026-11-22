package snippetsearcher.snippets.snippet

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
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
        @AuthenticationPrincipal jwt: Jwt,
        @RequestBody request: CreateSnippetRequest,
    ): ResponseEntity<out Any> {
        val newSnippet =
            NewSnippet(
                request.name,
                request.description,
                request.language,
                request.version,
                request.content,
            )

        val ownerId = jwt.subject ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        return when (val result = service.create(ownerId, newSnippet)) {
            is CreateSnippetResult.Created ->
                ResponseEntity.status(HttpStatus.CREATED).body(result.snippet.toResponse())

            is CreateSnippetResult.Invalid ->
                ResponseEntity.badRequest().body(InvalidSnippetResponse(result.errors))

            is CreateSnippetResult.Rejected ->
                ResponseEntity.badRequest().body(RejectedSnippetResponse(result.message))
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

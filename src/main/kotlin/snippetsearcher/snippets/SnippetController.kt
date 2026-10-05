package snippetsearcher.snippets

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
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

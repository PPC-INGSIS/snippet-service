package snippetsearcher.snippets

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/snippets")
class SnippetController(
    private val service: SnippetService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @RequestBody request: CreateSnippetRequest,
    ): SnippetResponse {
        val snippet = service.create(request.name, request.description, request.language, request.version)
        return SnippetResponse(snippet.id, snippet.name, snippet.description, snippet.language, snippet.version)
    }
}

data class CreateSnippetRequest(
    val name: String,
    val description: String,
    val language: String,
    val version: String,
)

data class SnippetResponse(
    val id: UUID,
    val name: String,
    val description: String,
    val language: String,
    val version: String,
)

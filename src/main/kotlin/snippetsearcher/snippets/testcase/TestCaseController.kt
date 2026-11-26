package snippetsearcher.snippets.testcase

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
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
import java.util.UUID

@RestController
@RequestMapping("/snippets/{snippetId}/tests")
class TestCaseController(
    private val service: TestCaseService,
) {
    @GetMapping
    fun list(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable snippetId: UUID,
    ): ResponseEntity<out Any> {
        val ownerId = jwt.subject ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        return when (val result = service.list(ownerId, snippetId)) {
            is ListTestCasesResult.Listed -> ResponseEntity.ok(result.testCases.map { it.toResponse() })
            ListTestCasesResult.SnippetNotFound -> ResponseEntity.notFound().build()
        }
    }

    @PostMapping
    fun create(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable snippetId: UUID,
        @RequestBody request: TestCaseRequest,
    ): ResponseEntity<out Any> {
        val ownerId = jwt.subject ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        return service.create(ownerId, snippetId, request.toData()).toResponse(HttpStatus.CREATED)
    }

    @PutMapping("/{testCaseId}")
    fun update(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable snippetId: UUID,
        @PathVariable testCaseId: UUID,
        @RequestBody request: TestCaseRequest,
    ): ResponseEntity<out Any> {
        val ownerId = jwt.subject ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        return service.update(ownerId, snippetId, testCaseId, request.toData()).toResponse(HttpStatus.OK)
    }

    @DeleteMapping("/{testCaseId}")
    fun delete(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable snippetId: UUID,
        @PathVariable testCaseId: UUID,
    ): ResponseEntity<Void> {
        val ownerId = jwt.subject ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        return when (service.delete(ownerId, snippetId, testCaseId)) {
            DeleteTestCaseResult.Deleted -> ResponseEntity.noContent().build()
            DeleteTestCaseResult.NotFound -> ResponseEntity.notFound().build()
        }
    }
}

private fun SaveTestCaseResult.toResponse(success: HttpStatus): ResponseEntity<out Any> =
    when (this) {
        is SaveTestCaseResult.Saved -> ResponseEntity.status(success).body(testCase.toResponse())
        SaveTestCaseResult.NotFound -> ResponseEntity.notFound().build()
        is SaveTestCaseResult.Invalid ->
            ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, message))
    }

// Los nombres son los de la UI (TestCase en printscript-ui): input y output, en singular aunque sean listas.
// Sin listas, el test no pide inputs o no espera outputs.
data class TestCaseRequest(
    val name: String,
    val input: List<String> = emptyList(),
    val output: List<String> = emptyList(),
)

data class TestCaseResponse(
    val id: UUID,
    val name: String,
    val input: List<String>,
    val output: List<String>,
)

private fun TestCaseRequest.toData() = TestCaseData(name, input, output)

private fun TestCase.toResponse() = TestCaseResponse(id, name, inputs, outputs)

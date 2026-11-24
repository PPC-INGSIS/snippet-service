package snippetsearcher.snippets.user

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/users")
class UserController(
    private val directory: UserDirectory,
) {
    @GetMapping
    fun search(
        @RequestParam(defaultValue = "") name: String,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(name = "page_size", defaultValue = "10") pageSize: Int,
    ): ResponseEntity<out Any> {
        if (page < 0 || pageSize !in 1..MAX_PAGE_SIZE) {
            val problem =
                ProblemDetail.forStatusAndDetail(
                    HttpStatus.BAD_REQUEST,
                    "page tiene que ser 0 o más, y page_size, entre 1 y $MAX_PAGE_SIZE",
                )
            return ResponseEntity.badRequest().body(problem)
        }

        val result = directory.search(name.trim(), page, pageSize)
        return ResponseEntity.ok(UsersResponse(page, pageSize, result.total, result.users))
    }

    companion object {
        // Cuánto puede pedir la UI de una vez: acota lo que le pedimos a Auth0 en cada búsqueda
        const val MAX_PAGE_SIZE = 50
    }
}

// La forma que espera la UI (PaginatedUsers en printscript-ui)
data class UsersResponse(
    val page: Int,
    @get:JsonProperty("page_size") val pageSize: Int,
    val count: Int,
    val users: List<User>,
)

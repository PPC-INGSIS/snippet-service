package snippetsearcher.snippets.user

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.body
import java.time.Duration

// Busca usuarios en Auth0 con la Management API (GET /api/v2/users)
@Component
class Auth0UserDirectory(
    @Value("\${auth0.management.url}") url: String,
    private val token: ManagementToken,
    timeout: Duration = AUTH0_TIMEOUT,
) : UserDirectory {
    private val restClient = auth0RestClient(url, timeout)

    override fun search(
        name: String,
        page: Int,
        pageSize: Int,
    ): UserPage {
        val variables = mapOf("q" to searchQuery(name), "page" to page, "perPage" to pageSize)
        val response =
            restClient
                .get()
                .uri { builder ->
                    builder
                        .path("/api/v2/users")
                        .queryParam("page", "{page}")
                        .queryParam("per_page", "{perPage}")
                        .queryParam("include_totals", "true")
                        .queryParam("fields", "user_id,name,email")
                        .queryParam("include_fields", "true")
                        // Sin nombre no se filtra: la primera página de todos los usuarios
                        .also { if (name.isNotEmpty()) it.queryParam("q", "{q}").queryParam("search_engine", "v3") }
                        .build(variables)
                }.header("Authorization", "Bearer ${token.value()}")
                .retrieve()
                .body<Auth0UsersResponse>()
                ?: error("Auth0 respondió sin cuerpo al buscar usuarios")

        return UserPage(response.users.map { it.toUser() }, response.total)
    }

    // Por el comienzo del nombre o del mail: "ju" encuentra a "Juan" y a "julia@mail.com". Sin distinguir mayúsculas
    private fun searchQuery(name: String): String {
        val term = name.replace(LUCENE_SYNTAX) { "\\" + it.value }
        return "name:$term* OR email:$term*"
    }

    companion object {
        // Caracteres que Lucene interpreta como sintaxis, incluidos los espacios (\s): "juan perez" se
        // busca como un solo término y no como dos. Escapados con \, se buscan como texto
        private val LUCENE_SYNTAX = Regex("""[+\-&|!(){}\[\]^"~*?:\\/\s]""")
    }
}

// Respuesta de GET /api/v2/users con include_totals=true
data class Auth0UsersResponse(
    val total: Int,
    val users: List<Auth0User>,
)

data class Auth0User(
    @param:JsonProperty("user_id") val userId: String,
    val name: String? = null,
    val email: String? = null,
) {
    // Los usuarios de usuario y contraseña tienen el mail como name; los de Google, su nombre
    fun toUser() = User(userId, name ?: email ?: userId)
}

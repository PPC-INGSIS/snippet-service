package snippetsearcher.snippets.user

import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClientException
import org.springframework.web.client.body
import snippetsearcher.snippets.auth0.AUTH0_TIMEOUT
import java.time.Duration

// Busca usuarios en Auth0 con la Management API (GET /api/v2/users)
@Component
class Auth0UserDirectory(
    @Value("\${auth0.management.url}") url: String,
    private val token: ManagementToken,
    timeout: Duration = AUTH0_TIMEOUT,
) : UserDirectory {
    private val log = LoggerFactory.getLogger(javaClass)

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

    /**
     * Los nombres de esos usuarios en una sola consulta a Auth0.
     *
     * Se buscan por `user_id` con la sintaxis de Lucene y entre comillas, porque el sub trae `|`,
     * que para Lucene es un operador. Van todos los ids en el mismo pedido: de a uno sería una
     * llamada a Auth0 por fila del listado.
     *
     * `per_page` de Auth0 llega hasta 100, que es también el tope de página del listado, así que
     * una página nunca necesita más de un pedido. Si alguna vez pidiera más, se corta en 100 y los
     * que sobran quedan sin nombre, que es lo mismo que le pasa a un usuario que ya no está en Auth0.
     *
     * **No tira si Auth0 falla**: devuelve lo que pudo, que puede ser nada. Un nombre que no se pudo
     * resolver no puede tirar abajo el listado de snippets, y para quien llama "Auth0 no contestó" y
     * "ese usuario ya no está" se resuelven igual: mostrando el `sub`. El detalle queda en el log.
     */
    override fun namesOf(userIds: Collection<String>): Map<String, String> {
        val distintos = userIds.toSet().take(MAX_PER_PAGE)
        if (distintos.isEmpty()) return emptyMap()

        val variables = mapOf("q" to idsQuery(distintos), "perPage" to distintos.size)
        return try {
            nombres(variables)
        } catch (exception: RestClientException) {
            // El detalle va al log y no hacia arriba: trae la URL de la Management API
            log.warn("No se pudieron resolver los nombres de {} usuarios en Auth0", distintos.size, exception)
            emptyMap()
        }
    }

    private fun nombres(variables: Map<String, Any>): Map<String, String> {
        val response =
            restClient
                .get()
                .uri { builder ->
                    builder
                        .path("/api/v2/users")
                        .queryParam("page", "0")
                        .queryParam("per_page", "{perPage}")
                        .queryParam("fields", "user_id,name,email")
                        .queryParam("include_fields", "true")
                        .queryParam("q", "{q}")
                        .queryParam("search_engine", "v3")
                        .build(variables)
                }.header("Authorization", "Bearer ${token.value()}")
                .retrieve()
                .body<List<Auth0User>>()
                .orEmpty()

        return response.associate { it.userId to it.toUser().name }
    }

    // user_id:("auth0|ana" OR "auth0|beto"). Entre comillas, el | del sub es texto y no un operador
    private fun idsQuery(userIds: Collection<String>): String =
        userIds.joinToString(" OR ", prefix = "user_id:(", postfix = ")") { "\"${it.replace("\"", "")}\"" }

    // Por el comienzo del nombre o del mail: "ju" encuentra a "Juan" y a "julia@mail.com". Sin distinguir mayúsculas
    private fun searchQuery(name: String): String {
        val term = name.replace(LUCENE_SYNTAX) { "\\" + it.value }
        return "name:$term* OR email:$term*"
    }

    companion object {
        // Caracteres que Lucene interpreta como sintaxis, incluidos los espacios (\s): "juan perez" se
        // busca como un solo término y no como dos. Escapados con \, se buscan como texto
        private val LUCENE_SYNTAX = Regex("""[+\-&|!(){}\[\]^"~*?:\\/\s]""")

        // Lo máximo que acepta per_page de Auth0, y también el tope de página del listado
        private const val MAX_PER_PAGE = 100
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

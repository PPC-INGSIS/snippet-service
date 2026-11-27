package snippetsearcher.snippets.snippet

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.BindParam
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import snippetsearcher.snippets.language.LanguageCatalog
import snippetsearcher.snippets.language.ValidationError
import java.util.UUID

@RestController
@RequestMapping("/snippets")
class SnippetController(
    private val service: SnippetService,
    private val authors: SnippetAuthors,
    private val languages: LanguageCatalog,
) {
    /**
     * Mis snippets. Los parámetros y la forma de la respuesta son las que manda la UI
     * (`listSnippetDescriptors` y `PaginatedSnippets` en printscript-ui): `page` arranca en 0,
     * `page_size` viaja en snake_case y `count` es el total, no cuántos trae esta página.
     */
    @GetMapping
    fun list(
        @AuthenticationPrincipal jwt: Jwt,
        request: ListSnippetsRequest,
    ): ResponseEntity<out Any> {
        val ownerId = jwt.subject ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        if (request.page < 0 || request.pageSize !in 1..MAX_PAGE_SIZE) {
            return badRequest("page tiene que ser 0 o más, y page_size, entre 1 y $MAX_PAGE_SIZE")
        }

        return when (val result = service.list(ownerId, request.toQuery())) {
            is ListSnippetsResult.Listed ->
                ResponseEntity.ok(
                    PaginatedSnippetsResponse(
                        page = request.page,
                        pageSize = request.pageSize,
                        count = result.total,
                        snippets = result.snippets.conAutores(ownerId),
                    ),
                )

            // 400 y no un orden por defecto: si la UI manda un campo mal escrito, que se note
            is ListSnippetsResult.SortFieldNotAllowed ->
                badRequest(
                    "No se puede ordenar por '${result.field}'. " +
                        "Campos permitidos: ${SnippetSortField.fields.joinToString(", ")}",
                )

            is ListSnippetsResult.SortDirectionNotAllowed ->
                badRequest("sortDirection tiene que ser asc o desc, no '${result.direction}'")

            is ListSnippetsResult.RelationNotAllowed ->
                badRequest(
                    "relation tiene que ser ${SnippetRelation.values.joinToString(", ")}, no '${result.relation}'",
                )

            is ListSnippetsResult.ComplianceNotAllowed ->
                badRequest(
                    "compliance tiene que ser ${LintStatus.values.joinToString(", ")}, no '${result.compliance}'",
                )

            // 502: el pedido estaba bien, falló Permisos, que es el que sabe qué te compartieron
            is ListSnippetsResult.SharedUnavailable ->
                ResponseEntity
                    .status(HttpStatus.BAD_GATEWAY)
                    .body(UpstreamErrorResponse("No se pudieron obtener los snippets compartidos"))
        }
    }

    @GetMapping("/{id}")
    fun get(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable id: UUID,
    ): ResponseEntity<out Any> {
        val ownerId = jwt.subject ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        return when (val result = service.find(ownerId, id)) {
            is FindSnippetResult.Found ->
                ResponseEntity.ok(
                    result.snippet.toDetailResponse(
                        autorDe(result.snippet),
                        extensionDe(result.snippet),
                        result.content,
                        ownerId,
                    ),
                )

            FindSnippetResult.NotFound -> ResponseEntity.notFound().build()

            // 502: el snippet existe pero Permisos no contestó, así que no se sabe si se puede leer
            is FindSnippetResult.AccessUnknown ->
                ResponseEntity
                    .status(HttpStatus.BAD_GATEWAY)
                    .body(UpstreamErrorResponse("No se pudo verificar el acceso al snippet"))

            // 500 y no 502: el asset service contestó bien, lo inconsistente son nuestros datos (ya quedó en el log)
            FindSnippetResult.ContentMissing -> ResponseEntity.internalServerError().build()

            // 502 y no 404: el snippet existe, falló el servicio que guarda su contenido
            is FindSnippetResult.ContentUnavailable ->
                ResponseEntity
                    .status(HttpStatus.BAD_GATEWAY)
                    .body(UpstreamErrorResponse("No se pudo leer el contenido del snippet"))
        }
    }

    @PostMapping("/{id}/share")
    fun share(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable id: UUID,
        @RequestBody request: ShareSnippetRequest,
    ): ResponseEntity<out Any> {
        val ownerId = jwt.subject ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        return when (val result = service.share(ownerId, id, request.userId)) {
            // La UI espera el snippet de vuelta, no un cuerpo vacio
            is ShareSnippetResult.Shared ->
                ResponseEntity.ok(
                    result.snippet.toResponse(autorDe(result.snippet), extensionDe(result.snippet), ownerId),
                )

            // 404 y no 403: un 403 le confirmaria a cualquiera que ese id existe
            ShareSnippetResult.NotFound -> ResponseEntity.notFound().build()

            ShareSnippetResult.BlankTargetUser ->
                ResponseEntity.badRequest().body(RejectedSnippetResponse("Falta el userId con quien compartir"))

            // 502: el pedido estaba bien, fallo un servicio nuestro
            is ShareSnippetResult.PermissionsUnavailable ->
                ResponseEntity
                    .status(HttpStatus.BAD_GATEWAY)
                    .body(UpstreamErrorResponse("No se pudo registrar el acceso"))
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
                ResponseEntity.ok(
                    result.snippet.toDetailResponse(
                        autorDe(result.snippet),
                        extensionDe(result.snippet),
                        request.content,
                        ownerId,
                    ),
                )

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
                ResponseEntity.status(
                    HttpStatus.CREATED,
                ).body(result.snippet.toResponse(autorDe(result.snippet), extensionDe(result.snippet), ownerId))

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

    /**
     * Los nombres de los dueños de toda la página en una sola consulta, no uno por fila.
     *
     * Varias filas suelen compartir dueño, así que `namesOf` recibe los ids distintos: una página
     * de 100 snippets de un mismo dueño es una sola consulta a Auth0.
     */
    private fun List<Snippet>.conAutores(callerId: String): List<SnippetResponse> {
        val nombres = authors.namesOf(map { it.ownerId })
        return map { it.toResponse(nombres.getValue(it.ownerId), extensionDe(it), callerId) }
    }

    private fun autorDe(snippet: Snippet) = authors.nameOf(snippet.ownerId)

    /**
     * La extensión sale del catálogo de lenguajes, no del snippet.
     *
     * Es un dato **derivado** del lenguaje: guardarla permitiría un snippet `printscript` con
     * extensión `exe`, obligaría a cada cliente a mandarla y podría desincronizarse al cambiar el
     * lenguaje en un PUT. Derivada, no puede estar mal.
     */
    private fun extensionDe(snippet: Snippet) = languages.extensionOf(snippet.language)

    companion object {
        // Cuánto puede pedir la UI de una vez: la tabla ofrece hasta 100 filas por página
        const val MAX_PAGE_SIZE = 100
    }
}

// Con quien se comparte. El snippet va en la URL
data class ShareSnippetRequest(
    val userId: String,
)

private fun badRequest(detail: String): ResponseEntity<ProblemDetail> =
    ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail))

/**
 * Los parámetros del listado, juntos en un objeto en vez de uno por uno en la firma.
 *
 * Los nombres son los que manda la UI: `page` arranca en 0 y `page_size` viaja en
 * snake_case, de ahí el @BindParam.
 */
data class ListSnippetsRequest(
    val page: Int = 0,
    @param:BindParam("page_size") val pageSize: Int = 10,
    val snippetName: String? = null,
    val language: String? = null,
    val sortBy: String? = null,
    val sortDirection: String? = null,
    // owned, shared o all (por defecto): los propios, los compartidos o los dos
    val relation: String? = null,
    val compliance: String? = null,
)

private fun ListSnippetsRequest.toQuery() =
    SnippetQuery(page, pageSize, snippetName, language, sortBy, sortDirection, relation, compliance)

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
    val extension: String,
    // El nombre del dueño, no su sub: es lo que la UI muestra en la columna Author
    val author: String,
    /*
     * Si el que pide es el dueño. La UI lo usa para no ofrecer borrar, editar ni compartir un
     * snippet que le compartieron. Va calculado y no como el sub del dueño: así la regla de
     * quién es dueño vive acá, y a nadie le llega el identificador de los demás.
     */
    val owned: Boolean,
    val compliance: String,
)

// La forma que espera la UI (PaginatedSnippets en printscript-ui)
data class PaginatedSnippetsResponse(
    val page: Int,
    @get:JsonProperty("page_size") val pageSize: Int,
    val count: Long,
    val snippets: List<SnippetResponse>,
)

// Lo mismo que SnippetResponse más el contenido, que solo viaja al pedir un snippet puntual
data class SnippetDetailResponse(
    val id: UUID,
    val name: String,
    val description: String,
    val language: String,
    val version: String,
    val extension: String,
    val author: String,
    val owned: Boolean,
    val compliance: String,
    val content: String,
)

data class InvalidSnippetResponse(
    val errors: List<ValidationError>,
)

private fun Snippet.toResponse(
    author: String,
    extension: String,
    callerId: String,
) = SnippetResponse(id, name, description, language, version, extension, author, ownerId == callerId, lintStatus.value)

private fun Snippet.toDetailResponse(
    author: String,
    extension: String,
    content: String,
    callerId: String,
) = SnippetDetailResponse(
    id,
    name,
    description,
    language,
    version,
    extension,
    author,
    ownerId == callerId,
    lintStatus.value,
    content,
)

data class RejectedSnippetResponse(
    val message: String,
)

// Mismo cuerpo que RejectedSnippetResponse, otro nombre: este es un 5xx, no culpa del cliente
data class UpstreamErrorResponse(
    val message: String,
)

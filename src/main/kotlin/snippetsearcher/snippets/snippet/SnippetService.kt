package snippetsearcher.snippets.snippet

import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import snippetsearcher.snippets.asset.AssetClient
import snippetsearcher.snippets.asset.ContentResult
import snippetsearcher.snippets.asset.DeleteContentResult
import snippetsearcher.snippets.permissions.DeleteAccessesResult
import snippetsearcher.snippets.permissions.PermissionsClient
import snippetsearcher.snippets.permissions.RegisterAccessResult
import snippetsearcher.snippets.permissions.SharedSnippetsResult
import java.util.UUID

@Service
class SnippetService(
    private val repository: SnippetRepository,
    private val assetClient: AssetClient,
    private val permissionsClient: PermissionsClient,
    private val access: SnippetAccess,
    private val writer: SnippetWriter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Crear y actualizar viven en SnippetWriter: ver su comentario
    fun create(
        ownerId: String,
        newSnippet: NewSnippet,
    ): CreateSnippetResult = writer.create(ownerId, newSnippet)

    /**
     * Un snippet que el usuario puede leer (es el dueño, o se lo compartieron), con su contenido,
     * que vive en el asset service.
     *
     * Al dueño se lo reconoce por la columna, sin preguntarle a Permisos: es el caso común y así
     * ver lo propio no depende de otro servicio. Si no es el dueño, Permisos decide.
     * Sin acceso es 404 igual que si no existiera: un 403 le confirmaría a cualquiera que ese id existe.
     * Primero el acceso y después el contenido: a quien no puede leerlo no hace falta pedírselo al asset service.
     */
    fun find(
        userId: String,
        id: UUID,
    ): FindSnippetResult {
        val snippet =
            when (val read = access.readable(userId, id)) {
                is ReadAccess.Granted -> read.snippet
                ReadAccess.Denied -> {
                    // Resultado de la consulta, no un error: puede no existir o no estar compartido
                    log.info("No se encontró el snippet {} para este usuario", id)
                    return FindSnippetResult.NotFound
                }
                is ReadAccess.Unknown -> return FindSnippetResult.AccessUnknown(read.message)
            }

        return when (val content = assetClient.get(id)) {
            is ContentResult.Found -> FindSnippetResult.Found(snippet, content.content)
            // Crear guarda el contenido antes que la fila, así que una fila sin contenido no debería existir.
            // Si pasa, el log es el único rastro: al usuario le llega un error genérico
            ContentResult.NotFound -> {
                log.warn("El snippet {} existe pero su contenido no está en el asset service", id)
                FindSnippetResult.ContentMissing
            }
            is ContentResult.Failed -> FindSnippetResult.ContentUnavailable(content.message)
        }
    }

    /**
     * Le da acceso de lectura a otro usuario.
     *
     * Solo el dueño puede compartir: la consulta ya filtra por dueño, así que si el
     * snippet no es suyo ni se consulta a Permisos. Compartir dos veces con el mismo
     * usuario no falla ni duplica, porque la clave en Permisos es (snippet, usuario)
     * y el 409 cuenta como registrado.
     */
    fun share(
        ownerId: String,
        id: UUID,
        targetUserId: String,
    ): ShareSnippetResult {
        if (targetUserId.isBlank()) return ShareSnippetResult.BlankTargetUser

        val snippet =
            repository.findByIdAndOwnerId(id, ownerId) ?: run {
                log.info("No se compartió el snippet {}: no existe o no es de este usuario", id)
                return ShareSnippetResult.NotFound
            }

        return when (val registrado = permissionsClient.registerReader(id, targetUserId)) {
            is RegisterAccessResult.Failed -> {
                log.warn("No se compartió el snippet {}: Permisos no registró el acceso", id)
                ShareSnippetResult.PermissionsUnavailable(registrado.message)
            }
            is RegisterAccessResult.Registered -> {
                // Cambio de estado: alguien mas puede leer este snippet
                log.info("Snippet {} compartido con {}", id, targetUserId)
                ShareSnippetResult.Shared(snippet)
            }
        }
    }

    /**
     * Los snippets de los que el usuario es dueño, paginados, filtrados y ordenados.
     *
     * No trae el contenido: el listado muestra nombre, lenguaje y autor, y pedirle el
     * contenido al asset service sería una llamada por fila para algo que no se ve.
     *
     * El dueño no es un filtro más: es la condición que está siempre. Los snippets de otro
     * no se filtran después, no entran en la consulta.
     */
    fun list(
        ownerId: String,
        query: SnippetQuery,
    ): ListSnippetsResult {
        val sortBy = query.sortBy.filtro()
        val field =
            if (sortBy == null) {
                SnippetSortField.NAME
            } else {
                SnippetSortField.from(sortBy) ?: return ListSnippetsResult.SortFieldNotAllowed(sortBy)
            }

        val sortDirection = query.sortDirection.filtro()
        val direction =
            if (sortDirection == null) {
                Sort.Direction.ASC
            } else {
                Sort.Direction
                    .fromOptionalString(sortDirection)
                    .orElse(null) ?: return ListSnippetsResult.SortDirectionNotAllowed(sortDirection)
            }

        // El id como desempate, aunque nadie ordene por id: dos snippets con el mismo nombre
        // quedarían en el orden que Postgres quiera, y ese orden puede cambiar entre dos
        // pedidos, así que la UI podría ver una misma fila en dos páginas y nunca ver otra.
        val sort = Sort.by(direction, field.field).and(Sort.by(Sort.Direction.ASC, "id"))

        val visible =
            when (val result = visibleTo(ownerId, query.relation.filtro())) {
                is Visible.By -> result.filter
                is Visible.Rejected -> return result.reason
            }

        val filters =
            Specification.allOf(
                listOfNotNull(
                    visible,
                    query.name.filtro()?.let { SnippetFilters.nameContains(it) },
                    query.language.filtro()?.let { SnippetFilters.writtenIn(it) },
                ),
            )

        val pagina = repository.findAll(filters, PageRequest.of(query.page, query.pageSize, sort))
        return ListSnippetsResult.Listed(pagina.content, pagina.totalElements)
    }

    /**
     * El filtro de "qué puede ver" según la relación pedida.
     *
     * Los propios salen de la columna y no dependen de Permisos. Los compartidos sí: se le pregunta
     * a Permisos qué snippets tiene con rol READ, y se buscan esos ids en la misma consulta, así el
     * orden, la paginación y el total siguen saliendo de Postgres en un solo pedido.
     * Si Permisos no contesta, no se devuelve solo lo propio: una lista incompleta sin aviso le
     * haría creer al usuario que le sacaron lo compartido. Pidiendo relation=owned anda igual.
     */
    private fun visibleTo(
        userId: String,
        relationValue: String?,
    ): Visible {
        // Sin relation: los propios y los compartidos
        val relation =
            if (relationValue == null) {
                SnippetRelation.ALL
            } else {
                SnippetRelation.from(relationValue)
                    ?: return Visible.Rejected(ListSnippetsResult.RelationNotAllowed(relationValue))
            }

        val owned = SnippetFilters.ownedBy(userId)
        if (relation == SnippetRelation.OWNED) return Visible.By(owned)

        return when (val shared = permissionsClient.sharedWith(userId)) {
            is SharedSnippetsResult.Failed -> Visible.Rejected(ListSnippetsResult.SharedUnavailable(shared.message))
            is SharedSnippetsResult.Found -> {
                val sharedFilter = SnippetFilters.idIn(shared.snippetIds)
                Visible.By(if (relation == SnippetRelation.SHARED) sharedFilter else owned.or(sharedFilter))
            }
        }
    }

    fun update(
        ownerId: String,
        id: UUID,
        changes: NewSnippet,
    ): UpdateSnippetResult = writer.update(ownerId, id, changes)

    /**
     * Borra el snippet del dueño: la fila, el contenido y los accesos.
     *
     * Al revés que al crear: primero la fila, que es lo que hace visible al snippet. Con la fila
     * borrada ya nadie lo ve ni lo puede pedir. Si después falla el asset service o Permisos, lo
     * único que queda son datos huérfanos que nadie alcanza. Se avisa en el log y la respuesta es
     * de éxito: el usuario pidió que desaparezca y desapareció, y reintentar no arregla nada,
     * porque el snippet ya no existe para él.
     */
    fun delete(
        ownerId: String,
        id: UUID,
    ): DeleteSnippetResult {
        val snippet = repository.findByIdAndOwnerId(id, ownerId) ?: return DeleteSnippetResult.NotFound

        repository.delete(snippet)

        if (assetClient.delete(id) is DeleteContentResult.Failed) {
            log.warn("Se borró el snippet {} pero quedó su contenido en el asset service", id)
        }
        if (permissionsClient.deleteAccesses(id) is DeleteAccessesResult.Failed) {
            log.warn("Se borró el snippet {} pero quedaron sus accesos en Permisos", id)
        }

        return DeleteSnippetResult.Deleted
    }
}

// Un parámetro en blanco es lo mismo que no mandarlo: la UI manda el campo de búsqueda
// vacío mientras el usuario no escribió nada
private fun String?.filtro(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

// Lo que manda el usuario para crear un snippet. El dueño va aparte porque no lo elige él: sale del token
data class NewSnippet(
    val name: String,
    val description: String,
    val language: String,
    val version: String,
    val content: String,
)

// El filtro de visibilidad del listado, o por qué no se pudo armar (relation inválida, Permisos caído)
private sealed interface Visible {
    data class By(
        val filter: Specification<Snippet>,
    ) : Visible

    data class Rejected(
        val reason: ListSnippetsResult,
    ) : Visible
}

package snippetsearcher.snippets.snippet

import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import snippetsearcher.snippets.asset.AssetClient
import snippetsearcher.snippets.asset.ContentResult
import snippetsearcher.snippets.asset.DeleteContentResult
import snippetsearcher.snippets.asset.SaveContentResult
import snippetsearcher.snippets.language.LanguageCatalog
import snippetsearcher.snippets.permissions.DeleteAccessesResult
import snippetsearcher.snippets.permissions.PermissionsClient
import snippetsearcher.snippets.permissions.RegisterAccessResult
import snippetsearcher.snippets.permissions.SharedSnippetsResult
import snippetsearcher.snippets.printscript.ValidationResult
import java.util.UUID

@Service
class SnippetService(
    private val repository: SnippetRepository,
    private val languages: LanguageCatalog,
    private val assetClient: AssetClient,
    private val permissionsClient: PermissionsClient,
    private val access: SnippetAccess,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Programática y no con @Transactional: acá hay que decidir el rollback según un resultado,
    // y @Transactional solo revierte si sale una excepción
    private val transactions = TransactionTemplate(transactionManager)

    fun create(
        ownerId: String,
        newSnippet: NewSnippet,
    ): CreateSnippetResult =
        when (val validation = validate(newSnippet)) {
            // El quién y el trace id no van acá: los pone el MDC en cada línea
            is ValidationResult.Rejected -> {
                log.info("Se rechazó el pedido: {}", validation.message)
                CreateSnippetResult.Rejected(validation.message)
            }
            is ValidationResult.Checked ->
                if (validation.errors.isNotEmpty()) {
                    log.info("Snippet con {} errores de código, no se crea", validation.errors.size)
                    CreateSnippetResult.Invalid(validation.errors)
                } else {
                    store(ownerId, newSnippet)
                }
        }

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

    /**
     * Reemplaza todos los datos del snippet del dueño y su contenido.
     *
     * Primero el dueño: a quien no lo es no hay que revisarle el código ni molestar al servicio del lenguaje.
     * La validación va antes y fuera de la transacción, para no retener una conexión de la base
     * mientras se espera al servicio del lenguaje.
     */
    fun update(
        ownerId: String,
        id: UUID,
        changes: NewSnippet,
    ): UpdateSnippetResult {
        if (repository.findByIdAndOwnerId(id, ownerId) == null) return UpdateSnippetResult.NotFound

        return when (val validation = validate(changes)) {
            is ValidationResult.Rejected -> UpdateSnippetResult.Rejected(validation.message)
            is ValidationResult.Checked ->
                if (validation.errors.isNotEmpty()) {
                    UpdateSnippetResult.Invalid(validation.errors)
                } else {
                    replace(ownerId, id, changes)
                }
        }
    }

    private fun validate(snippet: NewSnippet): ValidationResult =
        languages.find(snippet.language)?.validate(snippet.version, snippet.content)
            ?: ValidationResult.Rejected("El lenguaje '${snippet.language}' no existe")

    /**
     * La fila se actualiza y se manda a la base (flush) **antes** de guardar el contenido, y el commit
     * viene al final. Si la base rechaza algo (un dato demasiado largo, por ejemplo), falla antes de
     * tocar el asset service. Si falla el asset service, se revierte la fila: nunca quedan datos nuevos
     * con contenido viejo. Lo único que puede salir mal es el commit mismo, ya con el contenido guardado.
     */
    private fun replace(
        ownerId: String,
        id: UUID,
        changes: NewSnippet,
    ): UpdateSnippetResult =
        checkNotNull(
            transactions.execute<UpdateSnippetResult> { status ->
                val snippet = repository.findByIdAndOwnerId(id, ownerId)
                if (snippet == null) {
                    // Pudo borrarse mientras se validaba el código
                    UpdateSnippetResult.NotFound
                } else {
                    snippet.name = changes.name
                    snippet.description = changes.description
                    snippet.language = changes.language
                    snippet.version = changes.version
                    repository.saveAndFlush(snippet)

                    when (val saved = assetClient.put(id, changes.content)) {
                        is SaveContentResult.Failed -> {
                            status.setRollbackOnly()
                            UpdateSnippetResult.ContentNotSaved(saved.message)
                        }
                        is SaveContentResult.Saved -> UpdateSnippetResult.Updated(snippet)
                    }
                }
            },
        )

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

    /**
     * Contenido, dueño y recién al final la fila.
     *
     * **Lo que hace visible al snippet es la fila en Postgres**: todo lo que se escriba
     * antes, si algo falla después, queda huérfano y nadie lo ve. Al revés, una fila cuyo
     * contenido o cuyo dueño no existen se rompe recién cuando alguien la lee, y hasta
     * entonces nadie se entera.
     *
     * El precio son datos huérfanos: un archivo en el bucket o un acceso en Permisos que
     * no referencia a ningún snippet. Es basura, mucho más barata que una fila rota.
     */
    private fun store(
        ownerId: String,
        newSnippet: NewSnippet,
    ): CreateSnippetResult {
        val id = UUID.randomUUID()

        when (val saved = assetClient.put(id, newSnippet.content)) {
            is SaveContentResult.Failed -> {
                log.warn("No se creó el snippet {}: el asset service no guardó el contenido", id)
                return CreateSnippetResult.ContentNotSaved(saved.message)
            }
            is SaveContentResult.Saved -> Unit
        }

        when (val registered = permissionsClient.registerOwner(id, ownerId)) {
            is RegisterAccessResult.Failed -> {
                // El contenido ya quedó escrito: queda huérfano en el bucket
                log.warn("No se creó el snippet {}: Permisos no registró al dueño. Queda contenido huérfano", id)
                return CreateSnippetResult.OwnerNotRegistered(registered.message)
            }
            is RegisterAccessResult.Registered -> Unit
        }

        val snippet =
            Snippet(
                id = id,
                ownerId = ownerId,
                name = newSnippet.name,
                description = newSnippet.description,
                language = newSnippet.language,
                version = newSnippet.version,
            )
        val guardado = repository.save(snippet)
        // Cambio de estado: se creó algo. El "quién" sale del MDC
        log.info("Snippet creado: id={} lenguaje={} version={}", guardado.id, guardado.language, guardado.version)
        return CreateSnippetResult.Created(guardado)
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

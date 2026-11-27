package snippetsearcher.snippets.snippet

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import snippetsearcher.snippets.asset.AssetClient
import snippetsearcher.snippets.asset.SaveContentResult
import snippetsearcher.snippets.language.LanguageCatalog
import snippetsearcher.snippets.language.ValidationResult
import snippetsearcher.snippets.permissions.PermissionsClient
import snippetsearcher.snippets.permissions.RegisterAccessResult
import java.util.UUID

/**
 * Las escrituras de un snippet: crearlo y actualizarlo.
 *
 * Las dos validan el código con su lenguaje y después escriben en varios lugares (Postgres, el asset
 * service y, al crear, Permisos) con un orden pensado para los casos de falla. SnippetService se queda
 * con las lecturas, compartir y borrar.
 */
@Service
class SnippetWriter(
    private val repository: SnippetRepository,
    private val languages: LanguageCatalog,
    private val assetClient: AssetClient,
    private val permissionsClient: PermissionsClient,
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

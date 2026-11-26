package snippetsearcher.snippets.asset

import java.util.UUID

/**
 * Lo que Snippets necesita del asset service: guardar, leer y borrar el contenido de un snippet.
 *
 * El contenido no vive en Postgres. La base guarda los datos (nombre, lenguaje, versión)
 * y el archivo vive acá, porque un snippet puede tener diez líneas o diez mil.
 */
interface AssetClient {
    fun put(
        snippetId: UUID,
        content: String,
    ): SaveContentResult

    fun get(snippetId: UUID): ContentResult

    fun delete(snippetId: UUID): DeleteContentResult
}

sealed interface DeleteContentResult {
    /** El contenido ya no está: se borró, o no estaba. Para quien llama es lo mismo. */
    data object Deleted : DeleteContentResult

    /** No se pudo borrar: el servicio falló o no contestó. */
    data class Failed(
        val message: String,
    ) : DeleteContentResult
}

sealed interface SaveContentResult {
    data object Saved : SaveContentResult

    /** No se pudo guardar: el servicio falló o no contestó. */
    data class Failed(
        val message: String,
    ) : SaveContentResult
}

sealed interface ContentResult {
    data class Found(
        val content: String,
    ) : ContentResult

    /** No hay contenido para ese snippet. */
    data object NotFound : ContentResult

    /** No se pudo saber: el servicio falló o no contestó. Distinto de "no está". */
    data class Failed(
        val message: String,
    ) : ContentResult
}

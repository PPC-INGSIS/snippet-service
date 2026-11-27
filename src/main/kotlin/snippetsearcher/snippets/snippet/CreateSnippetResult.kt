package snippetsearcher.snippets.snippet

import snippetsearcher.snippets.language.ValidationError

sealed interface CreateSnippetResult {
    data class Created(
        val snippet: Snippet,
    ) : CreateSnippetResult

    // El código tiene errores
    data class Invalid(
        val errors: List<ValidationError>,
    ) : CreateSnippetResult

    // El pedido está mal y el código no se llegó a revisar (por ejemplo, la versión no existe)
    data class Rejected(
        val message: String,
    ) : CreateSnippetResult

    // El código estaba bien, pero el asset service no pudo guardar el contenido.
    // No es culpa de quien pidió: no se guardó nada y el pedido se puede reintentar.
    // El codigo estaba bien y el contenido se guardo, pero Permisos no pudo registrar
    // al dueno. Sin dueno el snippet no se podria compartir ni borrar, asi que no se crea.
    data class OwnerNotRegistered(
        val message: String,
    ) : CreateSnippetResult

    data class ContentNotSaved(
        val message: String,
    ) : CreateSnippetResult
}

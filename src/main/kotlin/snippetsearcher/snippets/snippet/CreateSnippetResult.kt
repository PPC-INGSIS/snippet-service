package snippetsearcher.snippets.snippet

import snippetsearcher.snippets.printscript.ValidationError

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
    data class ContentNotSaved(
        val message: String,
    ) : CreateSnippetResult
}

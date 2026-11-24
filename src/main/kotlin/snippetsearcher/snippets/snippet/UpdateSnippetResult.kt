package snippetsearcher.snippets.snippet

import snippetsearcher.snippets.printscript.ValidationError

sealed interface UpdateSnippetResult {
    data class Updated(
        val snippet: Snippet,
    ) : UpdateSnippetResult

    // No existe, o existe pero es de otro usuario: a propósito no se distingue
    data object NotFound : UpdateSnippetResult

    // El código nuevo tiene errores
    data class Invalid(
        val errors: List<ValidationError>,
    ) : UpdateSnippetResult

    // El pedido está mal y el código no se llegó a revisar (por ejemplo, la versión no existe)
    data class Rejected(
        val message: String,
    ) : UpdateSnippetResult

    // El código estaba bien, pero el asset service no pudo guardar el contenido nuevo.
    // No cambió nada: ni el contenido ni la fila. El pedido se puede reintentar.
    data class ContentNotSaved(
        val message: String,
    ) : UpdateSnippetResult
}

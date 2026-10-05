package snippetsearcher.snippets

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
}

package snippetsearcher.snippets.snippet

sealed interface FindSnippetResult {
    data class Found(
        val snippet: Snippet,
        val content: String,
    ) : FindSnippetResult

    // No existe, o existe pero es de otro usuario: a propósito no se distingue
    data object NotFound : FindSnippetResult

    // La fila existe pero no se pudo traer el contenido: el asset service falló o no lo tiene.
    // No es culpa de quien pidió, y un reintento puede funcionar.
    data class ContentUnavailable(
        val message: String,
    ) : FindSnippetResult
}

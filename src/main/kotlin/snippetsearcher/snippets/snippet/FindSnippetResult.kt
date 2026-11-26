package snippetsearcher.snippets.snippet

sealed interface FindSnippetResult {
    data class Found(
        val snippet: Snippet,
        val content: String,
    ) : FindSnippetResult

    // No existe, o existe y no tenés acceso: a propósito no se distingue
    data object NotFound : FindSnippetResult

    // El snippet existe, no es tuyo, y Permisos no contestó: no se puede saber si te lo compartieron
    data class AccessUnknown(
        val message: String,
    ) : FindSnippetResult

    // La fila existe y el asset service contestó bien, pero no tiene su contenido: son nuestros datos
    // los que quedaron inconsistentes. Reintentar no lo arregla.
    data object ContentMissing : FindSnippetResult

    // La fila existe pero el asset service falló o no contestó. No es culpa de quien pidió,
    // y un reintento puede funcionar.
    data class ContentUnavailable(
        val message: String,
    ) : FindSnippetResult
}

package snippetsearcher.snippets.formatting

sealed interface FormatSnippetResult {
    data class Formatted(
        val content: String,
    ) : FormatSnippetResult

    // No existe, o existe y el usuario no lo puede leer: a propósito no se distingue
    data object NotFound : FormatSnippetResult

    // El lenguaje no lo formateó: el código no se pudo analizar, es muy grande o una regla guardada ya no sirve
    data class Rejected(
        val message: String,
    ) : FormatSnippetResult

    // Permisos o el servicio del lenguaje no contestaron: no es culpa de quien pidió y reintentar sirve
    data class Unavailable(
        val message: String,
    ) : FormatSnippetResult
}

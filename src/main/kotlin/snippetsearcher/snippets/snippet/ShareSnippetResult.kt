package snippetsearcher.snippets.snippet

sealed interface ShareSnippetResult {
    data class Shared(
        val snippet: Snippet,
    ) : ShareSnippetResult

    /**
     * No existe, o existe y es de otro: a propósito no se distingue.
     *
     * Un 403 ("existe pero no es tuyo") le confirmaría a cualquiera qué ids existen.
     */
    data object NotFound : ShareSnippetResult

    /** Sin a quién compartirlo no hay nada que registrar. */
    data object BlankTargetUser : ShareSnippetResult

    /** Permisos falló o no contestó. No es culpa de quien pidió y puede reintentar. */
    data class PermissionsUnavailable(
        val message: String,
    ) : ShareSnippetResult
}

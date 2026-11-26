package snippetsearcher.snippets.snippet

sealed interface DeleteSnippetResult {
    data object Deleted : DeleteSnippetResult

    // No existe, o existe pero es de otro usuario: a propósito no se distingue
    data object NotFound : DeleteSnippetResult
}

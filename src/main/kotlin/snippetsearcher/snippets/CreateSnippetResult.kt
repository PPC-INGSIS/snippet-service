package snippetsearcher.snippets

sealed interface CreateSnippetResult {
    data class Created(
        val snippet: Snippet,
    ) : CreateSnippetResult

    data class Invalid(
        val errors: List<ValidationError>,
    ) : CreateSnippetResult
}

package snippetsearcher.snippets.snippet

sealed interface ListSnippetsResult {
    /**
     * La página pedida más el total de snippets que matchean, que no es lo mismo que
     * `snippets.size`: la UI necesita el total para saber cuántas páginas hay.
     */
    data class Listed(
        val snippets: List<Snippet>,
        val total: Long,
    ) : ListSnippetsResult

    /** Pidió ordenar por un campo que no existe o que no es ordenable. */
    data class SortFieldNotAllowed(
        val field: String,
    ) : ListSnippetsResult

    /** Pidió una relación que no es owned, shared ni all. */
    data class RelationNotAllowed(
        val relation: String,
    ) : ListSnippetsResult

    /** Había que preguntarle a Permisos qué le compartieron y no contestó. */
    data class SharedUnavailable(
        val message: String,
    ) : ListSnippetsResult

    /** Pidió un sentido de orden que no es asc ni desc. */
    data class SortDirectionNotAllowed(
        val direction: String,
    ) : ListSnippetsResult
}

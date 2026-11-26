package snippetsearcher.snippets.snippet

import org.springframework.data.jpa.domain.Specification

/**
 * Los filtros del listado, cada uno por separado, para combinar solo los que vinieron.
 *
 * Specification y no un `@Query` con `:name IS NULL`: un filtro que no vino directamente no
 * llega a la consulta, así que no hay que escribir "o es null" por cada uno ni ayudar a
 * Postgres a adivinar el tipo de un parámetro que siempre viaja en null.
 */
internal object SnippetFilters {
    fun ownedBy(ownerId: String) =
        Specification<Snippet> { snippet, _, criteria ->
            criteria.equal(snippet.get<String>("ownerId"), ownerId)
        }

    /**
     * Contiene, sin distinguir mayúsculas.
     *
     * Lo que escribió el usuario se escapa: en LIKE, `%` y `_` son comodines, así que buscar
     * "a_b" sin escapar también traería "axb", y un "%" solo traería todo.
     */
    fun nameContains(fragment: String) =
        Specification<Snippet> { snippet, _, criteria ->
            criteria.like(criteria.lower(snippet.get("name")), "%${escapeWildcards(fragment.lowercase())}%", ESCAPE)
        }

    /** Igualdad y no "contiene": "printscript" no tiene que matchear otro lenguaje que lo contenga. */
    fun writtenIn(language: String) =
        Specification<Snippet> { snippet, _, criteria ->
            criteria.equal(criteria.lower(snippet.get("language")), language.lowercase())
        }

    private const val ESCAPE = '\\'

    private fun escapeWildcards(value: String) =
        value
            .replace("$ESCAPE", "$ESCAPE$ESCAPE")
            .replace("%", "$ESCAPE%")
            .replace("_", "${ESCAPE}_")
}

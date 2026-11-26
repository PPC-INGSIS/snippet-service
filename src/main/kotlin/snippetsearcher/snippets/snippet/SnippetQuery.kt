package snippetsearcher.snippets.snippet

/**
 * Lo que se puede pedir al listar snippets.
 *
 * El dueño va aparte y no acá: no lo elige quien pregunta, sale del token.
 *
 * `name` y `language` en null (o en blanco) significan "sin filtrar". `sortBy` y
 * `sortDirection` llegan como texto a propósito: los valida el servicio, que es el que
 * sabe qué campos son ordenables, y un valor que no acepta termina en un 400 y no en un 500.
 */
data class SnippetQuery(
    val page: Int,
    val pageSize: Int,
    val name: String? = null,
    val language: String? = null,
    val sortBy: String? = null,
    val sortDirection: String? = null,
)

/**
 * Los campos por los que se puede ordenar.
 *
 * Es un enum y no un String por lo mismo que el rol en Permisos: la lista blanca *es* el
 * dominio. Si el campo viajara como texto hasta la consulta, cualquiera podría ordenar por
 * una columna que no mostramos (`owner_id`, por ejemplo) y un nombre inventado reventaría
 * con un 500 en vez de contestar un 400.
 */
enum class SnippetSortField(
    val field: String,
) {
    NAME("name"),
    LANGUAGE("language"),
    ;

    companion object {
        fun from(value: String): SnippetSortField? = entries.firstOrNull { it.field.equals(value, ignoreCase = true) }

        val fields: List<String> get() = entries.map { it.field }
    }
}

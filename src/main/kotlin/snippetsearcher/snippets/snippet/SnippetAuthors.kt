package snippetsearcher.snippets.snippet

import org.springframework.stereotype.Service
import snippetsearcher.snippets.user.UserDirectory

/**
 * El nombre del dueño de cada snippet, que es lo que la UI muestra en la columna Author.
 *
 * Lo que guardamos es el `sub` de Auth0 (`auth0|abc123`), que no es un nombre. El nombre vive en
 * Auth0 y hay que ir a buscarlo, así que esto es una traducción para mostrar y no parte del snippet.
 */
@Service
class SnippetAuthors(
    private val directory: UserDirectory,
) {
    /**
     * Resuelve todos los dueños de una vez y devuelve un nombre para cada id pedido.
     *
     * **Una consulta por página, no una por fila**: varias filas suelen compartir dueño, así que
     * alcanza con los ids distintos. Una página de 100 snippets de un mismo dueño es una sola
     * consulta a Auth0.
     *
     * Un id que el directorio no devolvió se muestra con su propio `sub`. Pasa en dos casos que
     * acá son el mismo: el usuario ya no está en Auth0, o Auth0 no contestó (el directorio no tira,
     * devuelve lo que pudo). Se muestra el `sub` y no un vacío porque un vacío parece un bug del
     * listado, mientras que un `auth0|...` dice qué pasó y sigue identificando al dueño.
     */
    fun namesOf(ownerIds: Collection<String>): Map<String, String> {
        val distintos = ownerIds.toSet()
        if (distintos.isEmpty()) return emptyMap()

        val nombres = directory.namesOf(distintos)
        return distintos.associateWith { nombres[it] ?: it }
    }

    /** El nombre de un solo dueño, para las respuestas de un snippet puntual. */
    fun nameOf(ownerId: String): String = namesOf(setOf(ownerId)).getValue(ownerId)
}

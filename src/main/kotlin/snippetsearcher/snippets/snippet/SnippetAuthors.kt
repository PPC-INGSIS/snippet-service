package snippetsearcher.snippets.snippet

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import snippetsearcher.snippets.user.UserDirectory
import java.time.Duration

/**
 * El nombre del dueño de cada snippet, que es lo que la UI muestra en la columna Author.
 *
 * Lo que guardamos es el `sub` de Auth0 (`auth0|abc123`), que no es un nombre. El nombre vive en
 * Auth0 y hay que ir a buscarlo, así que esto es una traducción para mostrar y no parte del snippet.
 *
 * **Los nombres se recuerdan unos minutos.** Sin caché, cada carga del listado era una consulta a
 * la Management API de Auth0, que está limitada por tenant, y crear o actualizar un snippet pagaba
 * una llamada más por un dato que es solo de presentación. Un nombre de usuario casi no cambia: lo
 * peor que puede pasar es mostrar uno viejo por lo que dure el TTL.
 */
@Service
class SnippetAuthors(
    private val directory: UserDirectory,
    @Value("\${snippets.authors.cache-ttl}") ttl: Duration,
    @Value("\${snippets.authors.cache-size}") size: Long,
) {
    private val nombres: Cache<String, String> =
        Caffeine
            .newBuilder()
            .expireAfterWrite(ttl)
            .maximumSize(size)
            .build()

    /**
     * Resuelve todos los dueños de una vez y devuelve un nombre para cada id pedido.
     *
     * **Una consulta por página, no una por fila**, y al directorio se le piden **solo los que no
     * están en el caché**: varias filas suelen compartir dueño, y entre dos cargas del listado
     * suelen ser los mismos. Una página de 100 snippets de un mismo dueño, pedida dos veces, es
     * una sola consulta a Auth0.
     *
     * Un id que el directorio no devolvió se muestra con su propio `sub`. Pasa en dos casos que
     * acá son el mismo: el usuario ya no está en Auth0, o Auth0 no contestó (el directorio no tira,
     * devuelve lo que pudo). Se muestra el `sub` y no un vacío porque un vacío parece un bug del
     * listado, mientras que un `auth0|...` dice qué pasó y sigue identificando al dueño.
     *
     * **Ese `sub` no se cachea**, solo los nombres que el directorio sí devolvió. Si se cacheara,
     * una caída de Auth0 de un segundo dejaría a todos los snippets mostrando el `sub` por lo que
     * dure el TTL. El precio es que mientras Auth0 esté caído se le vuelve a preguntar en cada
     * pedido, que es lo correcto: lo que falló tiene que poder recuperarse solo.
     */
    fun namesOf(ownerIds: Collection<String>): Map<String, String> {
        val distintos = ownerIds.toSet()
        if (distintos.isEmpty()) return emptyMap()

        val cacheados = nombres.getAllPresent(distintos)
        val faltantes = distintos - cacheados.keys
        val resueltos =
            if (faltantes.isEmpty()) {
                emptyMap()
            } else {
                directory.namesOf(faltantes).also { nombres.putAll(it) }
            }

        return distintos.associateWith { cacheados[it] ?: resueltos[it] ?: it }
    }

    /** El nombre de un solo dueño, para las respuestas de un snippet puntual. */
    fun nameOf(ownerId: String): String = namesOf(setOf(ownerId)).getValue(ownerId)
}

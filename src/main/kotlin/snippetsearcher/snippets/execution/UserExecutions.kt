package snippetsearcher.snippets.execution

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Cuántas ejecuciones interactivas tiene abiertas cada usuario, para que ninguno se quede con todas.
 *
 * PrintScript ya limita las ejecuciones abiertas a la vez, pero ese tope es **global**: no puede
 * separar por usuario porque solo lo llama Snippets, siempre con el mismo token. El que sabe quién
 * abre cada ejecución es Snippets, así que el reparto se hace acá.
 *
 * **El contador vive en memoria**, así que alcanza mientras Snippets corra en una sola instancia.
 * Con varias réplicas cada una contaría por su lado y el tope real sería el tope por réplica. Es el
 * mismo supuesto que ya tiene la ejecución interactiva, que vive en memoria en una sola réplica.
 */
@Component
class UserExecutions(
    @Value("\${snippets.execution.max-per-user}") val maxPerUser: Int,
) {
    private val abiertas = ConcurrentHashMap<String, Int>()

    /**
     * Toma un lugar si queda, y dice si lo consiguió.
     *
     * `compute` es atómico por clave: dos conexiones del mismo usuario que llegan juntas no pueden
     * ver las dos el mismo hueco, que es justamente lo que un `get` y después un `put` permitiría.
     */
    fun acquire(userId: String): Boolean {
        var otorgado = false
        abiertas.compute(userId) { _, actuales ->
            val abiertas = actuales ?: 0
            if (abiertas >= maxPerUser) {
                abiertas
            } else {
                otorgado = true
                abiertas + 1
            }
        }
        return otorgado
    }

    /**
     * Devuelve el lugar. Al llegar a cero se saca la entrada, así el mapa no acumula una por cada
     * usuario que pasó alguna vez.
     */
    fun release(userId: String) {
        abiertas.compute(userId) { _, actuales -> ((actuales ?: 1) - 1).takeIf { it > 0 } }
    }

    /** Cuántas tiene abiertas ese usuario ahora. */
    fun openFor(userId: String): Int = abiertas[userId] ?: 0
}

package snippetsearcher.snippets.execution

import org.slf4j.LoggerFactory
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Service
import snippetsearcher.snippets.snippet.FindSnippetResult
import snippetsearcher.snippets.snippet.SnippetService
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Decide si una ejecución puede arrancar y la arranca.
 *
 * Aparte del handler a propósito: el handler es transporte —parsea mensajes, los manda y cierra la
 * conexión— y esto es la regla de quién puede ejecutar qué. Así el handler no sabe de snippets ni
 * de tokens, y esta regla se puede leer de corrido sin WebSockets en el medio.
 *
 * El acceso lo resuelve `SnippetService.find`, que ya decide si el usuario puede leer el snippet
 * —por la columna de dueño o preguntándole a Permisos— y además trae el contenido, que es
 * justamente lo que hay que mandarle a PrintScript. Ejecutar es leer: alcanza con el acceso de
 * lectura, igual que ver el snippet o correr sus tests.
 */
@Service
class ExecutionStarter(
    private val snippets: SnippetService,
    private val tokens: ExecutionToken,
    private val executions: InteractiveExecutionClient,
    private val slots: UserExecutions,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun start(
        token: String,
        snippetId: UUID,
        onMessage: (String) -> Unit,
        onClose: () -> Unit,
    ): StartResult {
        val verified = tokens.verify(token) ?: return StartResult.Rejected("El token no es válido o no tiene el scope")
        val userId = verified.userId

        val found = asUser(verified.jwt) { snippets.find(userId, snippetId) }
        if (found !is FindSnippetResult.Found) return StartResult.Rejected(motivoDe(found))

        // El lugar se toma acá, después de chequear el acceso y antes de abrir nada contra PrintScript:
        // alguien que no puede leer el snippet no gasta un lugar, y uno que está en el tope no abre
        // una conexión que después habría que cerrar
        if (!slots.acquire(userId)) {
            log.info("Se rechazó una ejecución: {} ya tiene {} abiertas", userId, slots.maxPerUser)
            return StartResult.TooManyExecutions(slots.maxPerUser)
        }

        val request = ExecutionStart(token, found.content, found.snippet.version)
        return when (val started = executions.start(request, onMessage, onClose)) {
            is StartExecutionResult.Failed -> {
                slots.release(userId)
                StartResult.Rejected("No se pudo arrancar la ejecución")
            }
            is StartExecutionResult.Started -> {
                log.info("Ejecución interactiva arrancada para el snippet {}", snippetId)
                StartResult.Started(Releasing(userId, started.execution, slots))
            }
        }
    }

    /**
     * La ejecución de PrintScript, que además devuelve el lugar del usuario al cerrarse.
     *
     * Va envuelta y no suelta porque **todos los caminos terminan en `close()`**: cierra el browser,
     * cierra PrintScript o falla un envío. Si el descuento estuviera en el handler habría que
     * acordarse en cada uno de esos lugares, y el que se olvidara dejaría al usuario sin lugares
     * para siempre. El flag hace que un `close()` repetido no descuente dos veces.
     */
    private class Releasing(
        private val userId: String,
        private val execution: InteractiveExecution,
        private val slots: UserExecutions,
    ) : InteractiveExecution {
        private val released = AtomicBoolean(false)

        override fun input(value: String) = execution.input(value)

        override fun close() {
            execution.close()
            if (released.compareAndSet(false, true)) slots.release(userId)
        }
    }

    /**
     * Corre [block] con el usuario en el SecurityContext del hilo, como si fuera un pedido HTTP.
     *
     * Los mensajes del WebSocket llegan fuera del filtro de Spring Security, así que nadie llenó el
     * contexto. Sin esto, si el snippet no es suyo, la consulta a Permisos sale sin el token del usuario
     * y falla (#98). Es lo que el filtro hace en cada pedido HTTP, hecho a mano donde no llega.
     *
     * El `finally` es obligatorio: los hilos del WebSocket se reusan, y sin limpiar, la próxima conexión
     * que caiga en este hilo arrancaría con la identidad de este usuario.
     */
    private fun <T> asUser(
        jwt: Jwt,
        block: () -> T,
    ): T {
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
        try {
            return block()
        } finally {
            SecurityContextHolder.clearContext()
        }
    }

    private fun motivoDe(found: FindSnippetResult): String =
        when (found) {
            // No existe o no es tuyo: a propósito no se distingue, igual que en el GET
            FindSnippetResult.NotFound -> "No se encontró el snippet"
            FindSnippetResult.ContentMissing -> "No se pudo leer el contenido del snippet"
            is FindSnippetResult.ContentUnavailable -> "No se pudo leer el contenido del snippet"
            is FindSnippetResult.AccessUnknown -> "No se pudo verificar el acceso al snippet"
            is FindSnippetResult.Found -> error("Found no es un motivo de rechazo")
        }
}

sealed interface StartResult {
    data class Started(
        val execution: InteractiveExecution,
    ) : StartResult

    /** No arrancó, y `detail` es lo que se le dice al browser antes de cerrar. */
    data class Rejected(
        val detail: String,
    ) : StartResult

    /**
     * El usuario ya tiene todas las ejecuciones que puede tener abiertas.
     *
     * Aparte de `Rejected` porque no es lo mismo para quien pide: el pedido estaba bien y va a
     * funcionar en cuanto cierre una. Eso se le dice en el texto y no en el código de cierre: el
     * 1013 que correspondería no sobrevive al transporte (llega como 1002), así que el handler
     * cierra con 1008 como el resto de los rechazos y `max` sirve para armar el mensaje.
     */
    data class TooManyExecutions(
        val max: Int,
    ) : StartResult
}

package snippetsearcher.snippets.execution

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import snippetsearcher.snippets.snippet.FindSnippetResult
import snippetsearcher.snippets.snippet.SnippetService
import java.util.UUID

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
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun start(
        token: String,
        snippetId: UUID,
        onMessage: (String) -> Unit,
        onClose: () -> Unit,
    ): StartResult {
        val userId = tokens.userOf(token) ?: return StartResult.Rejected("El token no es válido o no tiene el scope")

        val found = snippets.find(userId, snippetId)
        if (found !is FindSnippetResult.Found) return StartResult.Rejected(motivoDe(found))

        val request = ExecutionStart(token, found.content, found.snippet.version)
        return when (val started = executions.start(request, onMessage, onClose)) {
            is StartExecutionResult.Failed -> StartResult.Rejected("No se pudo arrancar la ejecución")
            is StartExecutionResult.Started -> {
                log.info("Ejecución interactiva arrancada para el snippet {}", snippetId)
                StartResult.Started(started.execution)
            }
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
}

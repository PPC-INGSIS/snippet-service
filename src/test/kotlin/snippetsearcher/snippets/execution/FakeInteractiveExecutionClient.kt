package snippetsearcher.snippets.execution

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Reemplazo de la ejecución interactiva de PrintScript para los tests.
 *
 * Guarda con qué se arrancó y permite empujar mensajes hacia el browser (`emit`) o terminar
 * (`finish`), que es lo que haría PrintScript del otro lado. Así el relay se prueba contra un
 * WebSocket de verdad del lado del browser, sin levantar un PrintScript.
 */
class FakeInteractiveExecutionClient : InteractiveExecutionClient {
    val starts = CopyOnWriteArrayList<ExecutionStart>()
    val inputs = CopyOnWriteArrayList<String>()

    /** Si no es null, start() devuelve Failed con esto: para probar qué pasa cuando PrintScript no está. */
    var failWith: String? = null

    /**
     * Si es true, start() avisa que PrintScript cerró **antes de devolver** la ejecución.
     *
     * Reproduce sin depender del timing una carrera real: un snippet corto (`println(1);`) puede
     * terminar y cerrar mientras el start todavía no volvió. Sin el flag habría que acertar una
     * ventana de milisegundos.
     */
    var closeBeforeReturning = false

    @Volatile
    var closed = false
        private set

    private var onMessage: ((String) -> Unit)? = null
    private var onClose: (() -> Unit)? = null

    override fun start(
        request: ExecutionStart,
        onMessage: (String) -> Unit,
        onClose: () -> Unit,
    ): StartExecutionResult {
        failWith?.let { return StartExecutionResult.Failed(it) }

        starts += request
        this.onMessage = onMessage
        this.onClose = onClose

        if (closeBeforeReturning) onClose()

        return StartExecutionResult.Started(
            object : InteractiveExecution {
                override fun input(value: String) {
                    inputs += value
                }

                override fun close() {
                    closed = true
                }
            },
        )
    }

    /** Un mensaje de PrintScript hacia el browser, tal como vendría por el WebSocket. */
    fun emit(payload: String) = onMessage?.invoke(payload)

    /** PrintScript cerró: terminó la ejecución o se cortó. */
    fun finish() = onClose?.invoke()

    fun clear() {
        starts.clear()
        inputs.clear()
        failWith = null
        closeBeforeReturning = false
        closed = false
        onMessage = null
        onClose = null
    }
}

@TestConfiguration(proxyBeanMethods = false)
class FakeInteractiveExecutionConfiguration {
    @Bean
    @Primary
    fun fakeInteractiveExecutionClient() = FakeInteractiveExecutionClient()
}

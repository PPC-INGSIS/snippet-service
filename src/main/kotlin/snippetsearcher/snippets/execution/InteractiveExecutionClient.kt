package snippetsearcher.snippets.execution

/**
 * Lo que Snippets necesita de la ejecución interactiva de PrintScript.
 *
 * Es una interfaz y no el cliente de WebSocket directo por lo mismo que los otros clientes del
 * servicio: así el relay se puede probar sin levantar un PrintScript de verdad, y el día que el
 * transporte cambie, el relay no se entera.
 */
interface InteractiveExecutionClient {
    /**
     * Arranca una ejecución y devuelve con qué seguir hablándole.
     *
     * No tira: que PrintScript no esté es un resultado y no un error, igual que en los otros
     * clientes del servicio. Quien llama tiene que decidir qué le dice al usuario.
     *
     * `onMessage` recibe cada mensaje **tal como lo mandó PrintScript**, sin interpretarlo: el
     * relay no entiende el protocolo, solo lo pasa. `onClose` avisa que del otro lado se terminó.
     */
    fun start(
        request: ExecutionStart,
        onMessage: (String) -> Unit,
        onClose: () -> Unit,
    ): StartExecutionResult
}

/**
 * Con qué arranca una ejecución.
 *
 * El token es el del usuario, que Snippets le reenvía a PrintScript igual que en los pedidos HTTP.
 */
data class ExecutionStart(
    val token: String,
    val code: String,
    val version: String,
)

sealed interface StartExecutionResult {
    data class Started(
        val execution: InteractiveExecution,
    ) : StartExecutionResult

    /** No se pudo arrancar: PrintScript no contestó o rechazó el handshake. */
    data class Failed(
        val message: String,
    ) : StartExecutionResult
}

/** Una ejecución en curso. */
interface InteractiveExecution {
    /**
     * Le manda un input al snippet, que lo estaba esperando o lo va a encolar.
     *
     * Tampoco tira: si el envío falla, la conexión con PrintScript ya no sirve, así que se cierra
     * y eso llega al relay por `onClose`, que es el mismo camino que si la ejecución terminara.
     */
    fun input(value: String)

    /** Corta la ejecución. Cerrar la conexión del lado que sea la cancela. */
    fun close()
}

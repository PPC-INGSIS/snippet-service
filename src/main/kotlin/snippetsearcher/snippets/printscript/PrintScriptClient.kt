package snippetsearcher.snippets.printscript

// Lo que Snippets necesita del servicio de PrintScript
interface PrintScriptClient {
    fun validate(
        version: String,
        content: String,
    ): ValidationResult

    /**
     * Corre el snippet con esos inputs, en orden, y devuelve lo que imprimió.
     *
     * Los inputs van completos de antemano: correr un test no es interactivo, el que lo
     * escribió ya decidió qué va a contestar cada `readInput`.
     */
    fun execute(
        version: String,
        content: String,
        inputs: List<String>,
    ): ExecutionResult

    fun versions(): VersionsResult
}

sealed interface ExecutionResult {
    /**
     * PrintScript corrió el snippet. `error` no es null cuando se cortó antes de terminar
     * (por ejemplo, pidió un input que el test no tenía): los outputs son los de hasta ahí.
     */
    data class Ran(
        val outputs: List<String>,
        val error: String?,
    ) : ExecutionResult

    /** PrintScript rechazó el pedido sin correr nada: la versión no existe, el código es muy grande. */
    data class Rejected(
        val message: String,
    ) : ExecutionResult

    /** PrintScript falló o no contestó. */
    data class Failed(
        val message: String,
    ) : ExecutionResult
}

sealed interface ValidationResult {
    // PrintScript revisó el código. Lista vacía = válido
    data class Checked(
        val errors: List<ValidationError>,
    ) : ValidationResult

    // PrintScript rechazó el pedido sin revisar el código (por ejemplo, la versión no existe)
    data class Rejected(
        val message: String,
    ) : ValidationResult
}

sealed interface VersionsResult {
    data class Found(
        val versions: List<String>,
    ) : VersionsResult

    // PrintScript falló o no contestó
    data class Failed(
        val message: String,
    ) : VersionsResult
}

data class ValidationError(
    val message: String,
    val line: Int,
    val column: Int,
)

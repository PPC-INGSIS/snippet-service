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

/**
 * Un error del código que mandó el usuario, como lo devuelve PrintScript.
 *
 * La posición es opcional porque hay errores que no la tienen: PrintScript devuelve `line` y
 * `column` en `null` cuando el código desborda el parser por tener demasiados niveles de
 * anidamiento (printscript-service#42), porque ahí no hay una línea que señalar.
 *
 * Con `Int` no nulo, esa respuesta no se podía deserializar y el pedido terminaba en un 500 con un
 * error de Jackson en el log, en vez del 400 con el mensaje que PrintScript sí había mandado.
 */
data class ValidationError(
    val message: String,
    val line: Int?,
    val column: Int?,
)

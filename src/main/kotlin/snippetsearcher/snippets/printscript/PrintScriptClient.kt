package snippetsearcher.snippets.printscript

import snippetsearcher.snippets.language.RuleKind
import snippetsearcher.snippets.language.RulesResult
import snippetsearcher.snippets.language.RulesValidationResult
import snippetsearcher.snippets.language.ValidationResult
import snippetsearcher.snippets.language.VersionsResult

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

    fun rules(kind: RuleKind): RulesResult

    fun validateRules(
        kind: RuleKind,
        rules: Map<String, Any?>,
    ): RulesValidationResult
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

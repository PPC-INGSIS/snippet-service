package snippetsearcher.snippets.language

sealed interface LintResult {
    // El lenguaje linteó el código. Lista vacía = cumple las reglas
    data class Linted(
        val violations: List<LintViolation>,
    ) : LintResult

    // No se pudo lintear: el lenguaje no pudo analizar el código, rechazó el pedido o no contestó
    data class Failed(
        val message: String,
    ) : LintResult
}

// La posición es opcional, como en ValidationError: no siempre hay una línea que señalar
data class LintViolation(
    val rule: String,
    val message: String,
    val line: Int?,
    val column: Int?,
)

package snippetsearcher.snippets.language

// Reintentar sirve para Failed y no para Rejected: ante el mismo pedido, el lenguaje va a rechazarlo igual
sealed interface LintResult {
    // El lenguaje linteó el código. Lista vacía = cumple las reglas
    data class Linted(
        val violations: List<LintViolation>,
    ) : LintResult

    // El lenguaje no lo linteó: versión o reglas inválidas, código muy grande o que no pudo analizar
    data class Rejected(
        val message: String,
    ) : LintResult

    // El servicio del lenguaje falló o no contestó
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

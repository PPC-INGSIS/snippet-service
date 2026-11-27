package snippetsearcher.snippets.language

sealed interface ValidationResult {
    // El lenguaje revisó el código. Lista vacía = válido
    data class Checked(
        val errors: List<ValidationError>,
    ) : ValidationResult

    // El pedido se rechazó sin revisar el código (por ejemplo, el lenguaje o la versión no existen)
    data class Rejected(
        val message: String,
    ) : ValidationResult
}

/**
 * Un error del código que mandó el usuario, como lo devuelve el servicio del lenguaje.
 *
 * La posición es opcional porque hay errores que no la tienen: no siempre hay una línea que señalar.
 */
data class ValidationError(
    val message: String,
    val line: Int?,
    val column: Int?,
)

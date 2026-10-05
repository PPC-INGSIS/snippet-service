package snippetsearcher.snippets.printscript

// Lo que Snippets necesita del servicio de PrintScript
interface PrintScriptClient {
    fun validate(
        version: String,
        content: String,
    ): ValidationResult
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

data class ValidationError(
    val message: String,
    val line: Int,
    val column: Int,
)

package snippetsearcher.snippets.printscript

// Lo que Snippets necesita del servicio de PrintScript
interface PrintScriptClient {
    fun validate(
        version: String,
        content: String,
    ): ValidationResult

    fun versions(): VersionsResult
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

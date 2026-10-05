package snippetsearcher.snippets

// Lo que Snippets necesita del servicio de PrintScript
interface PrintScriptClient {
    // Lista vacía = el código es válido
    fun validate(
        version: String,
        content: String,
    ): List<ValidationError>
}

data class ValidationError(
    val message: String,
    val line: Int,
    val column: Int,
)

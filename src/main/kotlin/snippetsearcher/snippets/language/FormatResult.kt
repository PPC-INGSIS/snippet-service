package snippetsearcher.snippets.language

// Igual que LintResult: reintentar sirve para Failed y no para Rejected
sealed interface FormatResult {
    // El código con las reglas aplicadas. Formatear no guarda nada: qué hacer con esto lo decide quien llama
    data class Formatted(
        val content: String,
    ) : FormatResult

    // El lenguaje no lo formateó: versión o reglas inválidas, código muy grande o que no pudo analizar
    data class Rejected(
        val message: String,
    ) : FormatResult

    // El servicio del lenguaje falló o no contestó
    data class Failed(
        val message: String,
    ) : FormatResult
}

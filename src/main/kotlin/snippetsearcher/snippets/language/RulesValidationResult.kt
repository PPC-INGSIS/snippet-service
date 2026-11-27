package snippetsearcher.snippets.language

sealed interface RulesValidationResult {
    data object Valid : RulesValidationResult

    // rule es null si el lenguaje no dijo cuál de las reglas es la inválida
    data class Invalid(
        val message: String,
        val rule: String?,
    ) : RulesValidationResult

    // El servicio del lenguaje falló o no contestó
    data class Failed(
        val message: String,
    ) : RulesValidationResult
}

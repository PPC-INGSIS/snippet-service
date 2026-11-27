package snippetsearcher.snippets.language

sealed interface RulesResult {
    data class Found(
        val rules: List<RuleDefinition>,
    ) : RulesResult

    // El servicio del lenguaje falló o no contestó
    data class Failed(
        val message: String,
    ) : RulesResult
}

// Any? porque el default es un texto o un número según la regla, y null en las que no tienen (los flags)
data class RuleDefinition(
    val id: String,
    val description: String,
    val since: String,
    val defaultValue: Any?,
)

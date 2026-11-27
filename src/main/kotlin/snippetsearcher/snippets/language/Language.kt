package snippetsearcher.snippets.language

// Un lenguaje en el que se pueden escribir snippets. Agregar uno es agregar un @Component que implemente esto
interface Language {
    val name: String
    val extension: String

    fun versions(): VersionsResult

    fun validate(
        version: String,
        content: String,
    ): ValidationResult

    fun rules(kind: RuleKind): RulesResult

    fun validateRules(
        kind: RuleKind,
        rules: Map<String, Any?>,
    ): RulesValidationResult

    fun lint(
        version: String,
        content: String,
        rules: Map<String, Any>,
    ): LintResult
}

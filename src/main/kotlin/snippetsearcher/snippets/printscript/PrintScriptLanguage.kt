package snippetsearcher.snippets.printscript

import org.springframework.stereotype.Component
import snippetsearcher.snippets.language.Language
import snippetsearcher.snippets.language.LintResult
import snippetsearcher.snippets.language.RuleKind
import snippetsearcher.snippets.language.RulesResult
import snippetsearcher.snippets.language.RulesValidationResult
import snippetsearcher.snippets.language.ValidationResult
import snippetsearcher.snippets.language.VersionsResult

@Component
class PrintScriptLanguage(
    private val client: PrintScriptClient,
) : Language {
    override val name = "printscript"
    override val extension = "ps"

    override fun versions(): VersionsResult = client.versions()

    override fun validate(
        version: String,
        content: String,
    ): ValidationResult = client.validate(version, content)

    override fun rules(kind: RuleKind): RulesResult = client.rules(kind)

    override fun validateRules(
        kind: RuleKind,
        rules: Map<String, Any?>,
    ): RulesValidationResult = client.validateRules(kind, rules)

    override fun lint(
        version: String,
        content: String,
        rules: Map<String, Any>,
    ): LintResult = client.lint(version, content, rules)
}

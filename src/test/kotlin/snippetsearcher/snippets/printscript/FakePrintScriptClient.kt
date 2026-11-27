package snippetsearcher.snippets.printscript

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import snippetsearcher.snippets.language.FormatResult
import snippetsearcher.snippets.language.LintResult
import snippetsearcher.snippets.language.RuleKind
import snippetsearcher.snippets.language.RulesResult
import snippetsearcher.snippets.language.RulesValidationResult
import snippetsearcher.snippets.language.ValidationResult
import snippetsearcher.snippets.language.VersionsResult

// Reemplazo de printscript-service para los tests: responde lo que le carguemos en result y en los demás *Result
class FakePrintScriptClient : PrintScriptClient {
    var result: ValidationResult = ValidationResult.Checked(emptyList())
    var versionsResult: VersionsResult = VersionsResult.Found(emptyList())
    var executionResult: ExecutionResult = ExecutionResult.Ran(emptyList(), null)
    var rulesResult: RulesResult = RulesResult.Found(emptyList())
    var rulesValidationResult: RulesValidationResult = RulesValidationResult.Valid
    var lintResult: LintResult = LintResult.Linted(emptyList())
    var formatResult: FormatResult = FormatResult.Formatted("")

    // El último tipo de reglas pedido: para probar que cada ruta pide el suyo
    var lastRuleKind: RuleKind? = null

    // Las últimas reglas que se mandaron a validar: para probar qué valor se manda de cada una
    var lastValidatedRules: Map<String, Any?>? = null

    // Las últimas reglas con las que se pidió lintear: para probar qué reglas se mandan
    var lastLintRules: Map<String, Any>? = null

    // Las últimas reglas con las que se pidió formatear: para probar qué reglas se mandan
    var lastFormatRules: Map<String, Any>? = null

    // Con qué se pidió correr, lintear o formatear: el contenido del snippet y, al correr, los inputs del test
    var lastInputs: List<String>? = null
    var lastContent: String? = null

    // La última versión con la que se pidió validar, correr, lintear o formatear: para probar cuál se usa
    var lastVersion: String? = null

    override fun validate(
        version: String,
        content: String,
    ): ValidationResult {
        lastVersion = version
        return result
    }

    override fun execute(
        version: String,
        content: String,
        inputs: List<String>,
    ): ExecutionResult {
        lastVersion = version
        lastContent = content
        lastInputs = inputs
        return executionResult
    }

    override fun versions(): VersionsResult = versionsResult

    override fun rules(kind: RuleKind): RulesResult {
        lastRuleKind = kind
        return rulesResult
    }

    override fun validateRules(
        kind: RuleKind,
        rules: Map<String, Any?>,
    ): RulesValidationResult {
        lastValidatedRules = rules
        return rulesValidationResult
    }

    override fun lint(
        version: String,
        content: String,
        rules: Map<String, Any>,
    ): LintResult {
        lastVersion = version
        lastContent = content
        lastLintRules = rules
        return lintResult
    }

    override fun format(
        version: String,
        content: String,
        rules: Map<String, Any>,
    ): FormatResult {
        lastVersion = version
        lastContent = content
        lastFormatRules = rules
        return formatResult
    }

    // Deja el fake como recién creado, para que ningún test dependa de lo que dejó el anterior
    fun clear() {
        result = ValidationResult.Checked(emptyList())
        versionsResult = VersionsResult.Found(emptyList())
        executionResult = ExecutionResult.Ran(emptyList(), null)
        rulesResult = RulesResult.Found(emptyList())
        rulesValidationResult = RulesValidationResult.Valid
        lintResult = LintResult.Linted(emptyList())
        formatResult = FormatResult.Formatted("")
        lastRuleKind = null
        lastValidatedRules = null
        lastLintRules = null
        lastFormatRules = null
        lastVersion = null
        lastContent = null
        lastInputs = null
    }
}

@TestConfiguration(proxyBeanMethods = false)
class FakePrintScriptConfiguration {
    @Bean
    @Primary
    fun fakePrintScriptClient() = FakePrintScriptClient()
}

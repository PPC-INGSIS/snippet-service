package snippetsearcher.snippets.rules

import org.springframework.stereotype.Service
import snippetsearcher.snippets.language.Language
import snippetsearcher.snippets.language.LanguageCatalog
import snippetsearcher.snippets.language.RuleDefinition
import snippetsearcher.snippets.language.RuleKind
import snippetsearcher.snippets.language.RulesResult
import snippetsearcher.snippets.language.RulesValidationResult

@Service
class RuleService(
    private val catalog: LanguageCatalog,
    private val savedRules: SavedRuleRepository,
) {
    fun rules(
        ownerId: String,
        kind: RuleKind,
    ): UserRulesResult {
        val language = language()
        return when (val result = language.rules(kind)) {
            is RulesResult.Found ->
                UserRulesResult.Found(merge(result.rules, savedRules.find(ownerId, language.name, kind)))

            is RulesResult.Failed -> UserRulesResult.Failed
        }
    }

    // El catálogo va primero y lo que se devuelve se arma con él: después de guardar no queda nada que pueda fallar
    fun replace(
        ownerId: String,
        kind: RuleKind,
        rules: List<UserRuleData>,
    ): ReplaceRulesResult {
        val language = language()
        val definitions =
            when (val result = language.rules(kind)) {
                is RulesResult.Found -> result.rules
                is RulesResult.Failed -> return ReplaceRulesResult.Failed
            }
        rejectionFor(language, kind, rules)?.let { return it }

        val saved = rules.map { SavedRule(SavedRuleId(ownerId, language.name, kind, it.id), it.active, it.value) }
        savedRules.replace(ownerId, language.name, kind, saved)
        return ReplaceRulesResult.Replaced(merge(definitions, saved))
    }

    fun activeRules(
        ownerId: String,
        language: String,
        kind: RuleKind,
    ): Map<String, Any> =
        savedRules
            .find(ownerId, language, kind)
            .filter { it.active }
            // Un flag no tiene valor: para que PrintScript lo aplique tiene que llegar en true
            .associate { it.id.ruleId to (it.value ?: true) }

    private fun rejectionFor(
        language: Language,
        kind: RuleKind,
        rules: List<UserRuleData>,
    ): ReplaceRulesResult? {
        val repeated = rules.groupingBy { it.id }.eachCount().entries.firstOrNull { it.value > 1 }?.key
        if (repeated != null) return ReplaceRulesResult.Invalid("La regla '$repeated' está repetida", repeated)

        // Los flags no tienen valor: lo que se valida es si están activos. Una regla con valor obligatorio
        // y value null llega como booleano, y el lenguaje la rechaza
        val choices = rules.associate { it.id to (it.value ?: it.active) }
        return when (val validation = language.validateRules(kind, choices)) {
            RulesValidationResult.Valid -> null
            is RulesValidationResult.Invalid -> ReplaceRulesResult.Invalid(validation.message, validation.rule)
            is RulesValidationResult.Failed -> ReplaceRulesResult.Failed
        }
    }

    // Se recorre el catálogo y no lo guardado: una regla que el lenguaje ya no tiene queda afuera
    private fun merge(
        definitions: List<RuleDefinition>,
        saved: List<SavedRule>,
    ): List<UserRule> {
        val savedById = saved.associateBy { it.id.ruleId }
        return definitions.map { rule ->
            val choice = savedById[rule.id]
            val value = if (choice != null) choice.value else rule.defaultValue
            UserRule(rule.id, rule.description, rule.since, choice?.active ?: false, value)
        }
    }

    private fun language(): Language =
        checkNotNull(catalog.find(LANGUAGE)) { "El lenguaje $LANGUAGE no está en el catálogo" }

    companion object {
        // Hoy es el único lenguaje
        private const val LANGUAGE = "printscript"
    }
}

sealed interface UserRulesResult {
    data class Found(
        val rules: List<UserRule>,
    ) : UserRulesResult

    // Sin mensaje a propósito: el texto que ve el cliente lo decide el controller
    data object Failed : UserRulesResult
}

sealed interface ReplaceRulesResult {
    data class Replaced(
        val rules: List<UserRule>,
    ) : ReplaceRulesResult

    // Una regla no existe, su valor no sirve o está repetida. rule es null si no se sabe cuál
    data class Invalid(
        val message: String,
        val rule: String?,
    ) : ReplaceRulesResult

    // No se guardó nada. Sin mensaje a propósito: el texto que ve el cliente lo decide el controller
    data object Failed : ReplaceRulesResult
}

data class UserRule(
    val id: String,
    val description: String,
    val since: String,
    val active: Boolean,
    val value: Any?,
)

data class UserRuleData(
    val id: String,
    val active: Boolean,
    val value: Any?,
)

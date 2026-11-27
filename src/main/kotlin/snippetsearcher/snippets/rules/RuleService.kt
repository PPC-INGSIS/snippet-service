package snippetsearcher.snippets.rules

import org.springframework.stereotype.Service
import snippetsearcher.snippets.language.LanguageCatalog
import snippetsearcher.snippets.language.RuleKind
import snippetsearcher.snippets.language.RulesResult

@Service
class RuleService(
    private val catalog: LanguageCatalog,
) {
    fun rules(kind: RuleKind): UserRulesResult {
        val language = checkNotNull(catalog.find(LANGUAGE)) { "El lenguaje $LANGUAGE no está en el catálogo" }
        return when (val result = language.rules(kind)) {
            // Todavía no se guarda nada por usuario: todas salen deshabilitadas, con el default del lenguaje
            is RulesResult.Found ->
                UserRulesResult.Found(
                    result.rules.map { UserRule(it.id, it.description, it.since, false, it.defaultValue) },
                )

            is RulesResult.Failed -> UserRulesResult.Failed
        }
    }

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

data class UserRule(
    val id: String,
    val description: String,
    val since: String,
    val active: Boolean,
    val value: Any?,
)

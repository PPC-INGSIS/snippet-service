package snippetsearcher.snippets.snippet

import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component
import org.springframework.transaction.TransactionException
import snippetsearcher.snippets.language.LanguageCatalog
import snippetsearcher.snippets.language.LintResult
import snippetsearcher.snippets.language.RuleKind
import snippetsearcher.snippets.rules.RuleService

@Component
class SnippetLinter(
    private val repository: SnippetRepository,
    private val languages: LanguageCatalog,
    private val rules: RuleService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // Nunca lanza: si no se puede lintear, el snippet queda PENDING y el pedido de quien llamó sale igual
    fun lint(
        snippet: Snippet,
        content: String,
    ) {
        try {
            val status = statusOf(snippet, content)
            repository.updateLintStatus(snippet.id, status)
            snippet.lintStatus = status
        } catch (failure: DataAccessException) {
            databaseFailed(snippet, failure)
        } catch (failure: TransactionException) {
            databaseFailed(snippet, failure)
        }
    }

    // FAILED no se arregla reintentando: el lenguaje no puede lintearlo así como está.
    // PENDING sí: el servicio del lenguaje falló y otro intento puede funcionar
    private fun statusOf(
        snippet: Snippet,
        content: String,
    ): LintStatus {
        val language =
            languages.find(snippet.language) ?: run {
                log.warn("No se linteó el snippet {}: su lenguaje no está en el catálogo", snippet.id)
                return LintStatus.FAILED
            }
        val active = rules.activeRules(snippet.ownerId, snippet.language, RuleKind.LINT)

        return when (val result = language.lint(snippet.version, content, active)) {
            is LintResult.Linted -> if (result.violations.isEmpty()) LintStatus.COMPLIANT else LintStatus.NOT_COMPLIANT
            is LintResult.Rejected -> {
                log.warn("El lenguaje rechazó lintear el snippet {}: {}", snippet.id, result.message)
                LintStatus.FAILED
            }
            is LintResult.Failed -> {
                log.warn("No se pudo lintear el snippet {}: {}", snippet.id, result.message)
                LintStatus.PENDING
            }
        }
    }

    private fun databaseFailed(
        snippet: Snippet,
        failure: RuntimeException,
    ) {
        log.warn("Falló la base al lintear el snippet {}: queda pendiente", snippet.id, failure)
        snippet.lintStatus = LintStatus.PENDING
    }
}

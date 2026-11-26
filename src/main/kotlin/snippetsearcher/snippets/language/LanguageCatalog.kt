package snippetsearcher.snippets.language

import org.springframework.stereotype.Component
import snippetsearcher.snippets.printscript.VersionsResult

@Component
class LanguageCatalog(
    private val languages: List<Language>,
) {
    fun all(): LanguagesResult {
        val available =
            languages.map { language ->
                when (val result = language.versions()) {
                    is VersionsResult.Found -> AvailableLanguage(language.name, language.extension, result.versions)
                    is VersionsResult.Failed -> return LanguagesResult.Failed
                }
            }
        return LanguagesResult.Found(available)
    }
}

sealed interface LanguagesResult {
    data class Found(
        val languages: List<AvailableLanguage>,
    ) : LanguagesResult

    // Sin mensaje a propósito: el texto que ve el cliente lo decide el controller
    data object Failed : LanguagesResult
}

data class AvailableLanguage(
    val name: String,
    val extension: String,
    val versions: List<String>,
)

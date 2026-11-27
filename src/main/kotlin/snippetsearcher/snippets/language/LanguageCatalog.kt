package snippetsearcher.snippets.language

import org.springframework.stereotype.Component
import snippetsearcher.snippets.printscript.VersionsResult

@Component
class LanguageCatalog(
    private val languages: List<Language>,
) {
    /**
     * La extensión de ese lenguaje, o vacío si no está en el catálogo.
     *
     * No pregunta versiones, así que no sale a PrintScript: la extensión es un dato del lenguaje y
     * no cambia. Por eso se puede resolver en cada respuesta sin costo.
     */
    fun extensionOf(language: String): String =
        languages.firstOrNull { it.name.equals(language, ignoreCase = true) }?.extension.orEmpty()

    // Exacto y no ignorando mayúsculas: la UI manda el nombre tal como lo recibió de GET /languages
    fun find(name: String): Language? = languages.firstOrNull { it.name == name }

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

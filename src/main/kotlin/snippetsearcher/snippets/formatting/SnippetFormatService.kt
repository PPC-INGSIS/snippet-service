package snippetsearcher.snippets.formatting

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import snippetsearcher.snippets.language.FormatResult
import snippetsearcher.snippets.language.LanguageCatalog
import snippetsearcher.snippets.language.RuleKind
import snippetsearcher.snippets.rules.RuleService
import snippetsearcher.snippets.snippet.ReadAccess
import snippetsearcher.snippets.snippet.SnippetAccess
import java.util.UUID

/**
 * Formatea un código con las reglas del dueño del snippet, sin guardar nada.
 *
 * - **El contenido llega en el pedido** y no se lee del asset service: se formatea lo que está en el
 *   editor, aunque no esté guardado. Para descargar la versión formateada, la UI manda el contenido guardado.
 * - **La versión sale del snippet**: es la que se eligió al crearlo, igual que al correr un test.
 * - **Las reglas son las del dueño y no las de quien pide.** Quien tiene lectura puede formatear,
 *   porque formatear no cambia nada y puede descargar el snippet, pero ve el estilo del dueño.
 */
@Service
class SnippetFormatService(
    private val access: SnippetAccess,
    private val languages: LanguageCatalog,
    private val rules: RuleService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun format(
        userId: String,
        snippetId: UUID,
        content: String,
    ): FormatSnippetResult {
        val snippet =
            when (val read = access.readable(userId, snippetId)) {
                is ReadAccess.Granted -> read.snippet
                ReadAccess.Denied -> return FormatSnippetResult.NotFound
                is ReadAccess.Unknown -> return FormatSnippetResult.Unavailable(read.message)
            }

        // En SnippetWriter es un 400 porque el lenguaje viene en el pedido. Acá sale del snippet guardado
        val language =
            languages.find(snippet.language) ?: run {
                log.warn("No se formateó el snippet {}: su lenguaje no está en el catálogo", snippet.id)
                return FormatSnippetResult.LanguageMissing
            }

        val active = rules.activeRules(snippet.ownerId, language.name, RuleKind.FORMAT)
        return when (val result = language.format(snippet.version, content, active)) {
            is FormatResult.Formatted -> FormatSnippetResult.Formatted(result.content)
            is FormatResult.Rejected -> FormatSnippetResult.Rejected(result.message)
            is FormatResult.Failed -> FormatSnippetResult.Unavailable(result.message)
        }
    }
}

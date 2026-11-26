package snippetsearcher.snippets.testcase

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import snippetsearcher.snippets.snippet.ReadAccess
import snippetsearcher.snippets.snippet.SnippetAccess
import snippetsearcher.snippets.snippet.SnippetRepository
import java.time.Instant
import java.util.UUID

/**
 * Los tests de un snippet: crear, listar, actualizar y borrar.
 *
 * Listar lo puede hacer quien puede leer el snippet (el dueño o alguien con quien se compartió),
 * con la misma regla que ver el snippet. Crear, actualizar y borrar siguen siendo solo del dueño.
 *
 * Guardar un test no lo ejecuta: se puede guardar uno cuyos outputs no coinciden con lo que haría
 * el snippet (la US 8 lo permite). Correrlo es otro ticket.
 */
@Service
class TestCaseService(
    private val snippets: SnippetRepository,
    private val access: SnippetAccess,
    private val testCases: TestCaseRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun list(
        userId: String,
        snippetId: UUID,
    ): ListTestCasesResult =
        when (val read = access.readable(userId, snippetId)) {
            is ReadAccess.Granted ->
                ListTestCasesResult.Listed(testCases.findBySnippetIdOrderByCreatedAtAscIdAsc(snippetId))
            ReadAccess.Denied -> ListTestCasesResult.SnippetNotFound
            is ReadAccess.Unknown -> ListTestCasesResult.AccessUnknown(read.message)
        }

    fun create(
        ownerId: String,
        snippetId: UUID,
        data: TestCaseData,
    ): SaveTestCaseResult {
        rejectionFor(data)?.let { return it }
        if (!ownsSnippet(ownerId, snippetId)) return SaveTestCaseResult.NotFound

        val testCase =
            testCases.save(
                TestCase(UUID.randomUUID(), snippetId, data.name.trim(), data.inputs, data.outputs, Instant.now()),
            )
        log.info("Test {} creado en el snippet {}", testCase.id, snippetId)
        return SaveTestCaseResult.Saved(testCase)
    }

    fun update(
        ownerId: String,
        snippetId: UUID,
        testCaseId: UUID,
        data: TestCaseData,
    ): SaveTestCaseResult {
        rejectionFor(data)?.let { return it }
        if (!ownsSnippet(ownerId, snippetId)) return SaveTestCaseResult.NotFound

        val testCase = testCases.findByIdAndSnippetId(testCaseId, snippetId) ?: return SaveTestCaseResult.NotFound
        testCase.name = data.name.trim()
        testCase.inputs = data.inputs
        testCase.outputs = data.outputs
        return SaveTestCaseResult.Saved(testCases.save(testCase))
    }

    fun delete(
        ownerId: String,
        snippetId: UUID,
        testCaseId: UUID,
    ): DeleteTestCaseResult {
        if (!ownsSnippet(ownerId, snippetId)) return DeleteTestCaseResult.NotFound

        val testCase = testCases.findByIdAndSnippetId(testCaseId, snippetId) ?: return DeleteTestCaseResult.NotFound
        testCases.delete(testCase)
        log.info("Test {} borrado del snippet {}", testCaseId, snippetId)
        return DeleteTestCaseResult.Deleted
    }

    private fun ownsSnippet(
        ownerId: String,
        snippetId: UUID,
    ) = snippets.findByIdAndOwnerId(snippetId, ownerId) != null

    /** Por qué no sirven estos datos, o null si sirven. Va antes que la base: un pedido mal armado no la toca. */
    private fun rejectionFor(data: TestCaseData): SaveTestCaseResult.Invalid? =
        when {
            data.name.isBlank() -> SaveTestCaseResult.Invalid("Falta el nombre del test")
            data.name.trim().length > MAX_NAME_LENGTH ->
                SaveTestCaseResult.Invalid("El nombre del test no puede superar los $MAX_NAME_LENGTH caracteres")
            else -> null
        }

    companion object {
        // El largo de la columna name
        const val MAX_NAME_LENGTH = 255
    }
}

data class TestCaseData(
    val name: String,
    val inputs: List<String>,
    val outputs: List<String>,
)

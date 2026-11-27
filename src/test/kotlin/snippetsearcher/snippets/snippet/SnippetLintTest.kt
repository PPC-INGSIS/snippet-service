package snippetsearcher.snippets.snippet

import com.jayway.jsonpath.JsonPath
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doThrow
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.transaction.CannotCreateTransactionException
import snippetsearcher.snippets.TestcontainersConfiguration
import snippetsearcher.snippets.asset.FakeAssetClient
import snippetsearcher.snippets.asset.FakeAssetConfiguration
import snippetsearcher.snippets.language.LintResult
import snippetsearcher.snippets.language.LintViolation
import snippetsearcher.snippets.language.RuleKind
import snippetsearcher.snippets.language.ValidationError
import snippetsearcher.snippets.language.ValidationResult
import snippetsearcher.snippets.permissions.FakePermissionsClient
import snippetsearcher.snippets.permissions.FakePermissionsConfiguration
import snippetsearcher.snippets.printscript.FakePrintScriptClient
import snippetsearcher.snippets.printscript.FakePrintScriptConfiguration
import snippetsearcher.snippets.rules.SavedRule
import snippetsearcher.snippets.rules.SavedRuleId
import snippetsearcher.snippets.rules.SavedRuleRepository
import snippetsearcher.snippets.rules.UserRuleData
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

// El repositorio es un spy para poder hacer fallar el guardado del estado. El resto de las llamadas son las reales
@MockitoSpyBean(types = [SnippetRepository::class])
@Import(
    TestcontainersConfiguration::class,
    FakePrintScriptConfiguration::class,
    FakeAssetConfiguration::class,
    FakePermissionsConfiguration::class,
)
@SpringBootTest
@AutoConfigureMockMvc
class SnippetLintTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val printScript: FakePrintScriptClient,
    @Autowired private val repository: SnippetRepository,
    @Autowired private val savedRules: SavedRuleRepository,
    @Autowired private val asset: FakeAssetClient,
    @Autowired private val permissions: FakePermissionsClient,
) {
    // Un dueño por test: las reglas y los snippets de otros tests siguen en la base
    private val ana = "auth0|lint-${UUID.randomUUID()}"

    private val body =
        """
        {"name":"Saludo","description":"Pide un nombre","language":"printscript","version":"1.1","content":"println(1);"}
        """.trimIndent()

    private val updateBody =
        """
        {"name":"Saludo","description":"Pide un nombre","language":"printscript","version":"1.0","content":"println(2);"}
        """.trimIndent()

    private val violation = LintViolation("identifier_format", "Tiene que ser camelCase", 1, 5)

    @BeforeEach
    fun limpiarFakes() {
        printScript.clear()
        asset.clear()
        permissions.clear()
    }

    @Test
    fun `un snippet que incumple una regla activa queda not-compliant`() {
        saveRules(RuleKind.LINT, UserRuleData("identifier_format", true, "camel case"))
        printScript.lintResult = LintResult.Linted(listOf(violation))

        val id = create().andExpect { jsonPath("$.compliance") { value("not-compliant") } }.id()

        assertEquals(LintStatus.NOT_COMPLIANT, statusOf(id))
    }

    @Test
    fun `un snippet que cumple las reglas activas queda compliant`() {
        saveRules(RuleKind.LINT, UserRuleData("identifier_format", true, "camel case"))

        val id = create().andExpect { jsonPath("$.compliance") { value("compliant") } }.id()

        assertEquals(LintStatus.COMPLIANT, statusOf(id))
    }

    @Test
    fun `un dueno sin reglas activas queda compliant y se lintea sin reglas`() {
        saveRules(RuleKind.LINT, UserRuleData("identifier_format", false, "camel case"))

        create().andExpect { jsonPath("$.compliance") { value("compliant") } }

        assertEquals(emptyMap(), printScript.lastLintRules)
    }

    @Test
    fun `se lintea con las reglas activas de linteo del dueno y la version y el contenido del snippet`() {
        saveRules(
            RuleKind.LINT,
            UserRuleData("identifier_format", true, "camel case"),
            UserRuleData("mandatory-variable-or-literal-in-println", true, null),
            UserRuleData("inactiva", false, "x"),
        )
        saveRules(RuleKind.FORMAT, UserRuleData("if-indent", true, 4))
        saveRules(RuleKind.LINT, UserRuleData("de-otro", true, "y"), ownerId = "auth0|otro-${UUID.randomUUID()}")

        create()

        assertEquals(
            mapOf("identifier_format" to "camel case", "mandatory-variable-or-literal-in-println" to true),
            printScript.lastLintRules,
        )
        assertEquals("1.1", printScript.lastVersion)
        assertEquals("println(1);", printScript.lastContent)
    }

    @Test
    fun `si el lenguaje falla al lintear el snippet se crea igual y queda pending`() {
        printScript.lintResult = LintResult.Failed("connection refused")

        val id =
            create()
                .andExpect {
                    status { isCreated() }
                    jsonPath("$.compliance") { value("pending") }
                }.id()

        assertEquals(LintStatus.PENDING, statusOf(id))
    }

    @Test
    fun `si el lenguaje rechaza lintear el snippet se crea igual y queda failed`() {
        printScript.lintResult = LintResult.Rejected("Regla inválida")

        val id =
            create()
                .andExpect {
                    status { isCreated() }
                    jsonPath("$.compliance") { value("failed") }
                }.id()

        assertEquals(LintStatus.FAILED, statusOf(id))
    }

    @Test
    fun `actualizar un snippet compliant con el lenguaje caido lo deja pending`() {
        val id = create().id()
        assertEquals(LintStatus.COMPLIANT, statusOf(id))
        printScript.lintResult = LintResult.Failed("connection refused")

        update(id).andExpect {
            status { isOk() }
            jsonPath("$.compliance") { value("pending") }
        }

        assertEquals(LintStatus.PENDING, statusOf(id))
    }

    @Test
    fun `actualizar relintea con el contenido y la version nuevos`() {
        val id = create().id()
        printScript.lintResult = LintResult.Linted(listOf(violation))

        update(id).andExpect { jsonPath("$.compliance") { value("not-compliant") } }

        assertEquals("println(2);", printScript.lastContent)
        assertEquals("1.0", printScript.lastVersion)
        assertEquals(LintStatus.NOT_COMPLIANT, statusOf(id))
    }

    @Test
    fun `no se lintea si la creacion se rechazo o fallo`() {
        printScript.result = ValidationResult.Checked(listOf(ValidationError("Falta el punto y coma", 1, 11)))
        create().andExpect { status { isBadRequest() } }

        printScript.result = ValidationResult.Checked(emptyList())
        asset.failWith = "connection refused"
        create().andExpect { status { isBadGateway() } }

        assertNull(printScript.lastLintRules)
    }

    @Test
    fun `no se lintea si la actualizacion se rechazo o fallo`() {
        val id = create().id()
        printScript.lastLintRules = null

        printScript.result = ValidationResult.Checked(listOf(ValidationError("Falta el punto y coma", 1, 11)))
        update(id).andExpect { status { isBadRequest() } }

        printScript.result = ValidationResult.Checked(emptyList())
        asset.failWith = "connection refused"
        update(id).andExpect { status { isBadGateway() } }

        assertNull(printScript.lastLintRules)
        assertEquals(LintStatus.COMPLIANT, statusOf(id))
    }

    @Test
    fun `el detalle y el listado traen el estado de linteo`() {
        printScript.lintResult = LintResult.Linted(listOf(violation))
        val id = create().id()

        mockMvc
            .get("/snippets/{id}", id) {
                with(jwt().jwt { it.subject(ana) }.authorities(readSnippets))
            }.andExpect { jsonPath("$.compliance") { value("not-compliant") } }

        mockMvc
            .get("/snippets") {
                with(jwt().jwt { it.subject(ana) }.authorities(readSnippets))
            }.andExpect { jsonPath("$.snippets[0].compliance") { value("not-compliant") } }
    }

    @Test
    fun `si la base falla al guardar el estado el snippet se crea igual y queda pending`() {
        failLintStatusUpdatesWith(DataAccessResourceFailureException("connection reset"))

        val id =
            create()
                .andExpect {
                    status { isCreated() }
                    jsonPath("$.compliance") { value("pending") }
                }.id()

        assertEquals(LintStatus.PENDING, statusOf(id))
    }

    // Sin conexión Spring no lanza DataAccessException sino esta, que no es una DataAccessException
    @Test
    fun `si la base no da conexion al guardar el estado el snippet se crea igual y queda pending`() {
        failLintStatusUpdatesWith(CannotCreateTransactionException("connection refused"))

        val id =
            create()
                .andExpect {
                    status { isCreated() }
                    jsonPath("$.compliance") { value("pending") }
                }.id()

        assertEquals(LintStatus.PENDING, statusOf(id))
    }

    @Test
    fun `el listado con un estado de linteo inventado responde 400 con los valores aceptados`() {
        mockMvc
            .get("/snippets?compliance=casi") {
                with(jwt().jwt { it.subject(ana) }.authorities(readSnippets))
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.detail") { value(containsString("pending, compliant, not-compliant, failed")) }
            }
    }

    private fun create() =
        mockMvc.post("/snippets") {
            with(jwt().jwt { it.subject(ana) }.authorities(writeSnippets))
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    private fun update(id: UUID) =
        mockMvc.put("/snippets/{id}", id) {
            with(jwt().jwt { it.subject(ana) }.authorities(writeSnippets))
            contentType = MediaType.APPLICATION_JSON
            content = updateBody
        }

    private fun ResultActionsDsl.id(): UUID =
        UUID.fromString(JsonPath.read(andReturn().response.contentAsString, "$.id"))

    // Los matchers de Mockito devuelven null y Kotlin no lo acepta acá: el valor después de ?: no se usa
    private fun failLintStatusUpdatesWith(failure: RuntimeException) =
        doThrow(failure).`when`(repository).updateLintStatus(any() ?: UUID(0, 0), any() ?: LintStatus.PENDING)

    private fun statusOf(id: UUID) = repository.findById(id).orElseThrow().lintStatus

    private fun saveRules(
        kind: RuleKind,
        vararg rules: UserRuleData,
        ownerId: String = ana,
    ) = savedRules.replace(
        ownerId,
        LANGUAGE,
        kind,
        rules.map { SavedRule(SavedRuleId(ownerId, LANGUAGE, kind, it.id), it.active, it.value) },
    )

    private companion object {
        const val LANGUAGE = "printscript"
        val writeSnippets = SimpleGrantedAuthority("SCOPE_write:snippets")
        val readSnippets = SimpleGrantedAuthority("SCOPE_read:snippets")
    }
}

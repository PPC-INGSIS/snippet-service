package snippetsearcher.snippets.rules

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import snippetsearcher.snippets.TestcontainersConfiguration
import snippetsearcher.snippets.language.RuleDefinition
import snippetsearcher.snippets.language.RuleKind
import snippetsearcher.snippets.language.RulesResult
import snippetsearcher.snippets.printscript.FakePrintScriptClient
import snippetsearcher.snippets.printscript.FakePrintScriptConfiguration
import kotlin.test.assertEquals

// Listar reglas es leer: los tokens de este test traen read:snippets
private val readSnippets = SimpleGrantedAuthority("SCOPE_read:snippets")

@Import(TestcontainersConfiguration::class, FakePrintScriptConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class RuleControllerTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val printScript: FakePrintScriptClient,
) {
    @BeforeEach
    fun limpiarFake() {
        printScript.clear()
    }

    private val rules =
        RulesResult.Found(
            listOf(
                RuleDefinition("if-indent", "Espacios de indentación dentro de un if", "1.1", 4),
                RuleDefinition(
                    "line-break-after-statement",
                    "Salto de línea después de cada sentencia",
                    "1.0",
                    null,
                ),
            ),
        )

    // STRICT: la clave tiene que ser isActive y no active, y value tiene que estar aunque sea null
    private val expectedJson =
        """
        [
          {"id":"if-indent","name":"Espacios de indentación dentro de un if","since":"1.1",
           "isActive":false,"value":4},
          {"id":"line-break-after-statement","name":"Salto de línea después de cada sentencia","since":"1.0",
           "isActive":false,"value":null}
        ]
        """.trimIndent()

    @Test
    fun `GET rules format responde las reglas de formateo deshabilitadas con su default`() {
        printScript.rulesResult = rules

        mockMvc
            .get("/rules/format") { with(jwt().authorities(readSnippets)) }
            .andExpect {
                status { isOk() }
                content { json(expectedJson, JsonCompareMode.STRICT) }
            }
        assertEquals(RuleKind.FORMAT, printScript.lastRuleKind)
    }

    @Test
    fun `GET rules lint responde las reglas de linteo deshabilitadas con su default`() {
        printScript.rulesResult = rules

        mockMvc
            .get("/rules/lint") { with(jwt().authorities(readSnippets)) }
            .andExpect {
                status { isOk() }
                content { json(expectedJson, JsonCompareMode.STRICT) }
            }
        assertEquals(RuleKind.LINT, printScript.lastRuleKind)
    }

    @Test
    fun `GET rules responde 502 si no se pudieron obtener las reglas`() {
        printScript.rulesResult = RulesResult.Failed("PrintScript no respondió")

        mockMvc
            .get("/rules/format") { with(jwt().authorities(readSnippets)) }
            .andExpect {
                status { isBadGateway() }
                jsonPath("$.detail") { value("No se pudieron obtener las reglas") }
            }
    }

    @Test
    fun `GET rules sin token responde 401`() {
        mockMvc.get("/rules/format").andExpect { status { isUnauthorized() } }
    }
}

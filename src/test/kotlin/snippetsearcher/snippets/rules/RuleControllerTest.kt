package snippetsearcher.snippets.rules

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import snippetsearcher.snippets.TestcontainersConfiguration
import snippetsearcher.snippets.language.RuleDefinition
import snippetsearcher.snippets.language.RuleKind
import snippetsearcher.snippets.language.RulesResult
import snippetsearcher.snippets.language.RulesValidationResult
import snippetsearcher.snippets.printscript.FakePrintScriptClient
import snippetsearcher.snippets.printscript.FakePrintScriptConfiguration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

private val readSnippets = SimpleGrantedAuthority("SCOPE_read:snippets")
private val writeSnippets = SimpleGrantedAuthority("SCOPE_write:snippets")

// Las reglas se guardan en Postgres real: lo que guarda un test lo ve el siguiente, por eso cada test usa
// usuarios nuevos
@Import(TestcontainersConfiguration::class, FakePrintScriptConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class RuleControllerTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val printScript: FakePrintScriptClient,
) {
    private val ana = "auth0|ana-${UUID.randomUUID()}"
    private val beto = "auth0|beto-${UUID.randomUUID()}"

    @BeforeEach
    fun limpiarFake() {
        printScript.clear()
        printScript.rulesResult = rules
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
    private val defaultsJson =
        """
        [
          {"id":"if-indent","name":"Espacios de indentación dentro de un if","since":"1.1",
           "isActive":false,"value":4},
          {"id":"line-break-after-statement","name":"Salto de línea después de cada sentencia","since":"1.0",
           "isActive":false,"value":null}
        ]
        """.trimIndent()

    // Como lo manda la UI, con name y since. Distintos de los de PrintScript: se ignoran
    private val choices =
        """
        [
          {"id":"if-indent","name":"Otro nombre","since":"9.9","isActive":true,"value":2},
          {"id":"line-break-after-statement","name":"Otro nombre","since":"9.9","isActive":true,"value":null}
        ]
        """.trimIndent()

    private val savedJson =
        """
        [
          {"id":"if-indent","name":"Espacios de indentación dentro de un if","since":"1.1",
           "isActive":true,"value":2},
          {"id":"line-break-after-statement","name":"Salto de línea después de cada sentencia","since":"1.0",
           "isActive":true,"value":null}
        ]
        """.trimIndent()

    @Test
    fun `GET rules format responde las reglas de formateo deshabilitadas con su default`() {
        get("/rules/format").andExpect {
            status { isOk() }
            content { json(defaultsJson, JsonCompareMode.STRICT) }
        }
        assertEquals(RuleKind.FORMAT, printScript.lastRuleKind)
    }

    @Test
    fun `GET rules lint responde las reglas de linteo deshabilitadas con su default`() {
        get("/rules/lint").andExpect {
            status { isOk() }
            content { json(defaultsJson, JsonCompareMode.STRICT) }
        }
        assertEquals(RuleKind.LINT, printScript.lastRuleKind)
    }

    @Test
    fun `GET rules responde 502 si no se pudieron obtener las reglas`() {
        printScript.rulesResult = RulesResult.Failed("PrintScript no respondió")

        get("/rules/format").andExpect {
            status { isBadGateway() }
            jsonPath("$.detail") { value("No se pudieron obtener las reglas") }
        }
    }

    @Test
    fun `GET rules sin token responde 401`() {
        mockMvc.get("/rules/format").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `lo guardado con un PUT lo devuelve el GET siguiente`() {
        put("/rules/format", choices).andExpect {
            status { isOk() }
            content { json(savedJson, JsonCompareMode.STRICT) }
        }

        get("/rules/format").andExpect { content { json(savedJson, JsonCompareMode.STRICT) } }
    }

    @Test
    fun `PUT rules lint guarda las reglas de linteo`() {
        put("/rules/lint", choices).andExpect { status { isOk() } }

        get("/rules/lint").andExpect { content { json(savedJson, JsonCompareMode.STRICT) } }
    }

    @Test
    fun `una regla que no existe en PrintScript responde 400 con la regla y no guarda nada`() {
        put("/rules/format", choices)
        printScript.rulesValidationResult = RulesValidationResult.Invalid("La regla 'no-existe' no existe", "no-existe")

        put("/rules/format", """[{"id":"no-existe","isActive":true,"value":null}]""").andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("La regla 'no-existe' no existe") }
            jsonPath("$.rule") { value("no-existe") }
        }

        get("/rules/format").andExpect { content { json(savedJson, JsonCompareMode.STRICT) } }
    }

    @Test
    fun `un 400 sin regla responde sin la propiedad rule`() {
        printScript.rulesValidationResult = RulesValidationResult.Invalid("PrintScript rechazó el pedido", null)

        put("/rules/format", choices).andExpect {
            status { isBadRequest() }
            jsonPath("$.rule") { doesNotExist() }
        }
    }

    @Test
    fun `las reglas de un usuario no cambian las de otro`() {
        put("/rules/format", choices, ana)

        get("/rules/format", beto).andExpect { content { json(defaultsJson, JsonCompareMode.STRICT) } }
    }

    @Test
    fun `guardar reglas de format no cambia las de lint`() {
        put("/rules/format", choices)

        get("/rules/lint").andExpect { content { json(defaultsJson, JsonCompareMode.STRICT) } }
    }

    @Test
    fun `una regla guardada que no viene en el PUT vuelve a su default`() {
        put("/rules/format", choices)

        put("/rules/format", """[{"id":"line-break-after-statement","isActive":true,"value":null}]""")

        get("/rules/format").andExpect {
            jsonPath("$[0].isActive") { value(false) }
            jsonPath("$[0].value") { value(4) }
            jsonPath("$[1].isActive") { value(true) }
        }
    }

    // Cubre el orden del DELETE y los INSERT: si los INSERT fueran primero, el segundo chocaría con la clave
    @Test
    fun `el mismo PUT dos veces seguidas responde 200 las dos veces`() {
        put("/rules/format", choices).andExpect { status { isOk() } }
        put("/rules/format", choices).andExpect { status { isOk() } }
    }

    @Test
    fun `deshabilitar una regla conserva su valor`() {
        put("/rules/format", """[{"id":"if-indent","isActive":true,"value":2}]""")

        put("/rules/format", """[{"id":"if-indent","isActive":false,"value":2}]""")

        get("/rules/format").andExpect {
            jsonPath("$[0].isActive") { value(false) }
            jsonPath("$[0].value") { value(2) }
        }
    }

    @Test
    fun `un flag activo y uno inactivo se guardan y se leen con value null`() {
        printScript.rulesResult =
            RulesResult.Found(
                listOf(
                    RuleDefinition("mandatory-semicolon", "Punto y coma obligatorio", "1.0", null),
                    RuleDefinition("no-println-expressions", "println sin expresiones", "1.0", null),
                ),
            )
        val flags =
            """
            [{"id":"mandatory-semicolon","isActive":true,"value":null},
             {"id":"no-println-expressions","isActive":false,"value":null}]
            """.trimIndent()

        put("/rules/lint", flags)

        get("/rules/lint").andExpect {
            content {
                json(
                    """
                    [{"id":"mandatory-semicolon","name":"Punto y coma obligatorio","since":"1.0",
                      "isActive":true,"value":null},
                     {"id":"no-println-expressions","name":"println sin expresiones","since":"1.0",
                      "isActive":false,"value":null}]
                    """.trimIndent(),
                    JsonCompareMode.STRICT,
                )
            }
        }
    }

    @Test
    fun `ids repetidos responden 400 sin validar en PrintScript`() {
        val repeated =
            """[{"id":"if-indent","isActive":true,"value":2},{"id":"if-indent","isActive":false,"value":3}]"""

        put("/rules/format", repeated).andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("La regla 'if-indent' está repetida") }
            jsonPath("$.rule") { value("if-indent") }
        }
        assertNull(printScript.lastValidatedRules)
    }

    @Test
    fun `PrintScript caido responde 502 y no guarda nada`() {
        put("/rules/format", choices)
        printScript.rulesValidationResult = RulesValidationResult.Failed("PrintScript no respondió")

        put("/rules/format", """[{"id":"if-indent","isActive":false,"value":8}]""").andExpect {
            status { isBadGateway() }
            jsonPath("$.detail") { value("No se pudieron guardar las reglas") }
        }

        get("/rules/format").andExpect { content { json(savedJson, JsonCompareMode.STRICT) } }
    }

    @Test
    fun `PUT rules sin token responde 401`() {
        mockMvc
            .put("/rules/format") {
                contentType = MediaType.APPLICATION_JSON
                content = choices
            }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `PUT rules con solo read_snippets responde 403`() {
        mockMvc
            .put("/rules/format") {
                with(jwt().jwt { it.subject(ana) }.authorities(readSnippets))
                contentType = MediaType.APPLICATION_JSON
                content = choices
            }.andExpect { status { isForbidden() } }
    }

    private fun get(
        path: String,
        subject: String = ana,
    ) = mockMvc.get(path) { with(jwt().jwt { it.subject(subject) }.authorities(readSnippets)) }

    private fun put(
        path: String,
        body: String,
        subject: String = ana,
    ) = mockMvc.put(path) {
        with(jwt().jwt { it.subject(subject) }.authorities(writeSnippets))
        contentType = MediaType.APPLICATION_JSON
        content = body
    }
}

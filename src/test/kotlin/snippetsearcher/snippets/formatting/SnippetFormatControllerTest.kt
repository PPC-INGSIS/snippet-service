package snippetsearcher.snippets.formatting

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import snippetsearcher.snippets.TestcontainersConfiguration
import snippetsearcher.snippets.asset.FakeAssetConfiguration
import snippetsearcher.snippets.language.FormatResult
import snippetsearcher.snippets.language.RuleKind
import snippetsearcher.snippets.permissions.FakePermissionsClient
import snippetsearcher.snippets.permissions.FakePermissionsConfiguration
import snippetsearcher.snippets.printscript.FakePrintScriptClient
import snippetsearcher.snippets.printscript.FakePrintScriptConfiguration
import snippetsearcher.snippets.rules.SavedRule
import snippetsearcher.snippets.rules.SavedRuleId
import snippetsearcher.snippets.rules.SavedRuleRepository
import snippetsearcher.snippets.snippet.Snippet
import snippetsearcher.snippets.snippet.SnippetRepository
import tools.jackson.databind.ObjectMapper
import java.util.UUID
import kotlin.test.assertEquals

// Formatear es un POST, así que el scope que pide es write:snippets (la regla sale del método)
private val writeSnippets = SimpleGrantedAuthority("SCOPE_write:snippets")
private val readSnippets = SimpleGrantedAuthority("SCOPE_read:snippets")

@Import(
    TestcontainersConfiguration::class,
    FakePrintScriptConfiguration::class,
    FakeAssetConfiguration::class,
    FakePermissionsConfiguration::class,
)
@SpringBootTest
@AutoConfigureMockMvc
class SnippetFormatControllerTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val repository: SnippetRepository,
    @Autowired private val savedRules: SavedRuleRepository,
    @Autowired private val printScript: FakePrintScriptClient,
    @Autowired private val permissions: FakePermissionsClient,
    @Autowired private val json: ObjectMapper,
) {
    @BeforeEach
    fun limpiarLosFakes() {
        printScript.clear()
        permissions.clear()
    }

    @Test
    fun `POST format devuelve el codigo formateado`() {
        val id = guardar("auth0|ana")
        printScript.formatResult = FormatResult.Formatted("let a: number = 1;\n")

        formatear(id, "auth0|ana", "let a:number=1;")
            .andExpect {
                status { isOk() }
                jsonPath("$.content") { value("let a: number = 1;\n") }
            }
    }

    // Se formatea lo que está en el editor, aunque no esté guardado, con la versión que se eligió al crearlo
    @Test
    fun `POST format manda el contenido del pedido y la version del snippet`() {
        val id = guardar("auth0|ana", version = "1.0")

        formatear(id, "auth0|ana", "println(2);")
            .andExpect { status { isOk() } }

        assertEquals("println(2);", printScript.lastContent)
        assertEquals("1.0", printScript.lastVersion)
    }

    @Test
    fun `POST format usa las reglas de formateo activas del dueno`() {
        val owner = "auth0|${UUID.randomUUID()}"
        val id = guardar(owner)
        guardarReglas(owner, "if-indent" to 4, "space-before-colon" to null)

        formatear(id, owner, "println(1);")
            .andExpect { status { isOk() } }

        // La regla inactiva no viaja
        assertEquals(mapOf<String, Any>("if-indent" to 4), printScript.lastFormatRules)
    }

    // Formatear no cambia nada y quien tiene lectura puede descargar: ve el snippet con el estilo del dueño
    @Test
    fun `POST format de un snippet compartido responde 200 con las reglas del dueno`() {
        val owner = "auth0|${UUID.randomUUID()}"
        val reader = "auth0|${UUID.randomUUID()}"
        val id = guardar(owner)
        permissions.registerReader(id, reader)
        guardarReglas(owner, "if-indent" to 4)
        guardarReglas(reader, "if-indent" to 2)

        formatear(id, reader, "println(1);")
            .andExpect { status { isOk() } }

        assertEquals(mapOf<String, Any>("if-indent" to 4), printScript.lastFormatRules)
    }

    @Test
    fun `POST format de un snippet de otro usuario responde 404`() {
        val id = guardar("auth0|ana")

        formatear(id, "auth0|beto", "println(1);")
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `POST format de un snippet que no existe responde 404`() {
        formatear(UUID.randomUUID(), "auth0|ana", "println(1);")
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `POST format con un codigo que el lenguaje rechaza responde 400 con su mensaje`() {
        val id = guardar("auth0|ana")
        printScript.formatResult = FormatResult.Rejected("Se esperaba ';'")

        formatear(id, "auth0|ana", "println(1)")
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.detail") { value("Se esperaba ';'") }
            }
    }

    // Las comillas y los saltos de línea son lo primero que se rompe al pasar código por JSON
    @Test
    fun `POST format con comillas y saltos de linea en el codigo los manda tal cual`() {
        val id = guardar("auth0|ana")
        val code = "let saludo: string = \"hola\";\nprintln(saludo);"

        formatear(id, "auth0|ana", code)
            .andExpect {
                status { isOk() }
                jsonPath("$.content") { value(code) }
            }

        assertEquals(code, printScript.lastContent)
    }

    // 500 y no 400: el lenguaje sale del snippet guardado, quien pide no puede arreglarlo
    @Test
    fun `POST format de un snippet con un lenguaje que no esta en el catalogo responde 500`() {
        val id = guardar("auth0|ana", language = "python")

        formatear(id, "auth0|ana", "print(1)")
            .andExpect { status { isInternalServerError() } }
    }

    // 502 y no 500: el pedido estaba bien, falló un servicio nuestro
    @Test
    fun `POST format con PrintScript caido responde 502`() {
        val id = guardar("auth0|ana")
        // Otro texto que el del controller: así se prueba que el detalle interno no llega al cliente
        printScript.formatResult = FormatResult.Failed("connection refused")

        formatear(id, "auth0|ana", "println(1);")
            .andExpect {
                status { isBadGateway() }
                jsonPath("$.detail") { value("No se pudo formatear el snippet") }
            }
    }

    @Test
    fun `POST format de un snippet ajeno con Permisos caido responde 502`() {
        val id = guardar("auth0|ana")
        permissions.failWith = "connection refused"

        formatear(id, "auth0|beto", "println(1);")
            .andExpect { status { isBadGateway() } }
    }

    @Test
    fun `POST format con solo read_snippets responde 403`() {
        val id = guardar("auth0|ana")

        mockMvc
            .post("/snippets/$id/format") {
                with(jwt().jwt { it.subject("auth0|ana") }.authorities(readSnippets))
                contentType = MediaType.APPLICATION_JSON
                content = """{"content":"println(1);"}"""
            }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `POST format con un token sin sub responde 401`() {
        val id = guardar("auth0|ana")

        mockMvc
            .post("/snippets/$id/format") {
                with(jwt().jwt { token -> token.claims { it.remove("sub") } }.authorities(writeSnippets))
                contentType = MediaType.APPLICATION_JSON
                content = """{"content":"println(1);"}"""
            }.andExpect { status { isUnauthorized() } }
    }

    private fun formatear(
        snippetId: UUID,
        userId: String,
        code: String,
    ) = mockMvc.post("/snippets/$snippetId/format") {
        with(jwt().jwt { it.subject(userId) }.authorities(writeSnippets))
        contentType = MediaType.APPLICATION_JSON
        content = json.writeValueAsString(mapOf("content" to code))
    }

    private fun guardar(
        ownerId: String,
        language: String = "printscript",
        version: String = "1.1",
    ): UUID {
        val id = UUID.randomUUID()
        repository.save(Snippet(id, ownerId, "Saludo", "Imprime algo", language, version))
        return id
    }

    // Un valor null es una regla guardada pero inactiva
    private fun guardarReglas(
        ownerId: String,
        vararg rules: Pair<String, Any?>,
    ) {
        val saved =
            rules.map { (rule, value) ->
                SavedRule(SavedRuleId(ownerId, "printscript", RuleKind.FORMAT, rule), value != null, value)
            }
        savedRules.replace(ownerId, "printscript", RuleKind.FORMAT, saved)
    }
}

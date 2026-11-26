package snippetsearcher.snippets.testcase

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
import snippetsearcher.snippets.asset.FakeAssetClient
import snippetsearcher.snippets.asset.FakeAssetConfiguration
import snippetsearcher.snippets.permissions.FakePermissionsClient
import snippetsearcher.snippets.permissions.FakePermissionsConfiguration
import snippetsearcher.snippets.printscript.ExecutionResult
import snippetsearcher.snippets.printscript.FakePrintScriptClient
import snippetsearcher.snippets.printscript.FakePrintScriptConfiguration
import snippetsearcher.snippets.snippet.Snippet
import snippetsearcher.snippets.snippet.SnippetRepository
import java.util.UUID

// Correr un test es un POST, así que el scope que pide es write:snippets (la regla sale del método)
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
class TestRunControllerTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val repository: SnippetRepository,
    @Autowired private val asset: FakeAssetClient,
    @Autowired private val printScript: FakePrintScriptClient,
    @Autowired private val permissions: FakePermissionsClient,
) {
    @BeforeEach
    fun limpiarLosFakes() {
        asset.clear()
        printScript.clear()
        permissions.clear()
    }

    @Test
    fun `POST tests run devuelve si paso, lo que imprimio y lo que se esperaba`() {
        val id = guardar("auth0|ana")
        printScript.executionResult = ExecutionResult.Ran(listOf("8"), null)

        correr(id, "auth0|ana", """{"name":"suma","input":["5","3"],"output":["8"]}""")
            .andExpect {
                status { isOk() }
                jsonPath("$.passed") { value(true) }
                jsonPath("$.outputs[0]") { value("8") }
                jsonPath("$.expected[0]") { value("8") }
            }
    }

    @Test
    fun `POST tests run con outputs distintos responde 200 con passed en false`() {
        val id = guardar("auth0|ana")
        printScript.executionResult = ExecutionResult.Ran(listOf("9"), null)

        correr(id, "auth0|ana", """{"name":"suma","input":[],"output":["8"]}""")
            .andExpect {
                status { isOk() }
                jsonPath("$.passed") { value(false) }
                jsonPath("$.outputs[0]") { value("9") }
            }
    }

    /**
     * La UI corre el test del formulario, que puede no estar guardado y puede no tener nombre:
     * el botón Save exige nombre, el de Test no. Pedirlo acá rompería algo que allá está permitido.
     */
    @Test
    fun `POST tests run sin nombre corre igual`() {
        val id = guardar("auth0|ana")
        printScript.executionResult = ExecutionResult.Ran(listOf("8"), null)

        correr(id, "auth0|ana", """{"input":[],"output":["8"]}""")
            .andExpect {
                status { isOk() }
                jsonPath("$.passed") { value(true) }
            }
    }

    @Test
    fun `POST tests run de un snippet de otro usuario responde 404`() {
        val id = guardar("auth0|ana")

        correr(id, "auth0|beto", """{"input":[],"output":[]}""")
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `POST tests run de un snippet compartido responde 200`() {
        val id = guardar("auth0|ana")
        permissions.registerReader(id, "auth0|beto")
        printScript.executionResult = ExecutionResult.Ran(listOf("8"), null)

        correr(id, "auth0|beto", """{"input":[],"output":["8"]}""")
            .andExpect {
                status { isOk() }
                jsonPath("$.passed") { value(true) }
            }
    }

    // 502 y no 500: el pedido estaba bien, falló un servicio nuestro
    @Test
    fun `POST tests run con PrintScript caido responde 502`() {
        val id = guardar("auth0|ana")
        printScript.executionResult = ExecutionResult.Failed("connection refused")

        correr(id, "auth0|ana", """{"input":[],"output":[]}""")
            .andExpect {
                status { isBadGateway() }
                jsonPath("$.detail") { exists() }
            }
    }

    // 500 y no 502: el asset service contestó bien, lo inconsistente son nuestros datos.
    // Mismo criterio que GET /snippets/{id}, que es el mismo caso
    @Test
    fun `POST tests run de un snippet sin contenido responde 500`() {
        val id = UUID.randomUUID()
        repository.save(Snippet(id, "auth0|ana", "Saludo", "Pide un nombre", "printscript", "1.1"))

        correr(id, "auth0|ana", """{"input":[],"output":[]}""")
            .andExpect { status { isInternalServerError() } }
    }

    @Test
    fun `POST tests run con una version que PrintScript rechaza responde 400`() {
        val id = guardar("auth0|ana")
        printScript.executionResult = ExecutionResult.Rejected("La versión '1.3' no existe")

        correr(id, "auth0|ana", """{"input":[],"output":[]}""")
            .andExpect {
                status { isBadRequest() }
                jsonPath("$.detail") { value("La versión '1.3' no existe") }
            }
    }

    @Test
    fun `POST tests run con solo read_snippets responde 403`() {
        val id = guardar("auth0|ana")

        mockMvc
            .post("/snippets/$id/tests/run") {
                with(jwt().jwt { it.subject("auth0|ana") }.authorities(readSnippets))
                contentType = MediaType.APPLICATION_JSON
                content = """{"input":[],"output":[]}"""
            }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `POST tests run con un token sin sub responde 401`() {
        val id = guardar("auth0|ana")

        mockMvc
            .post("/snippets/$id/tests/run") {
                with(jwt().jwt { token -> token.claims { it.remove("sub") } }.authorities(writeSnippets))
                contentType = MediaType.APPLICATION_JSON
                content = """{"input":[],"output":[]}"""
            }.andExpect { status { isUnauthorized() } }
    }

    private fun correr(
        snippetId: UUID,
        userId: String,
        body: String,
    ) = mockMvc.post("/snippets/$snippetId/tests/run") {
        with(jwt().jwt { it.subject(userId) }.authorities(writeSnippets))
        contentType = MediaType.APPLICATION_JSON
        content = body
    }

    private fun guardar(ownerId: String): UUID {
        val id = UUID.randomUUID()
        asset.put(id, "println(1);")
        repository.save(Snippet(id, ownerId, "Saludo", "Pide un nombre", "printscript", "1.1"))
        return id
    }
}

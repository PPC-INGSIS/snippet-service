package snippetsearcher.snippets.snippet

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
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import snippetsearcher.snippets.TestcontainersConfiguration
import snippetsearcher.snippets.asset.FakeAssetClient
import snippetsearcher.snippets.asset.FakeAssetConfiguration
import snippetsearcher.snippets.permissions.FakePermissionsClient
import snippetsearcher.snippets.permissions.FakePermissionsConfiguration
import snippetsearcher.snippets.printscript.FakePrintScriptClient
import snippetsearcher.snippets.printscript.FakePrintScriptConfiguration
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Lo que puede hacer por HTTP alguien con quien se compartió un snippet: leerlo, y nada más.
 *
 * Mismos @Import que SnippetControllerTest: Spring reusa ese contexto en vez de levantar otro.
 */
@Import(
    TestcontainersConfiguration::class,
    FakePrintScriptConfiguration::class,
    FakeAssetConfiguration::class,
    FakePermissionsConfiguration::class,
)
@SpringBootTest
@AutoConfigureMockMvc
class SharedSnippetControllerTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val printScript: FakePrintScriptClient,
    @Autowired private val repository: SnippetRepository,
    @Autowired private val asset: FakeAssetClient,
    @Autowired private val permissions: FakePermissionsClient,
) {
    private val writeSnippets = SimpleGrantedAuthority("SCOPE_write:snippets")
    private val readSnippets = SimpleGrantedAuthority("SCOPE_read:snippets")

    private val updateBody =
        """
        {"name":"Despedida","description":"Dice chau","language":"printscript","version":"1.0","content":"println(2);"}
        """.trimIndent()

    @BeforeEach
    fun limpiarFakes() {
        printScript.clear()
        asset.clear()
        permissions.clear()
    }

    @Test
    fun `GET snippets por id compartido responde 200 con el contenido`() {
        val id = saved("auth0|ana")
        permissions.registerReader(id, "auth0|beto")

        mockMvc
            .get("/snippets/$id") { with(jwt().jwt { it.subject("auth0|beto") }.authorities(readSnippets)) }
            .andExpect {
                status { isOk() }
                jsonPath("$.name") { value("Saludo") }
                jsonPath("$.content") { value("println(1);") }
            }
    }

    @Test
    fun `GET snippets por id con Permisos caido para quien no es dueno responde 502`() {
        val id = saved("auth0|ana")
        permissions.failWith = "connection refused"

        mockMvc
            .get("/snippets/$id") { with(jwt().jwt { it.subject("auth0|beto") }.authorities(readSnippets)) }
            .andExpect {
                status { isBadGateway() }
                jsonPath("$.message") { exists() }
            }
    }

    @Test
    fun `PUT snippets por id compartido responde 404 y no cambia nada`() {
        val id = saved("auth0|ana")
        permissions.registerReader(id, "auth0|beto")

        mockMvc
            .put("/snippets/$id") {
                with(jwt().jwt { it.subject("auth0|beto") }.authorities(writeSnippets))
                contentType = MediaType.APPLICATION_JSON
                content = updateBody
            }.andExpect { status { isNotFound() } }

        assertEquals("Saludo", repository.findById(id).orElseThrow().name)
    }

    @Test
    fun `DELETE snippets por id compartido responde 404 y no borra nada`() {
        val id = saved("auth0|ana")
        permissions.registerReader(id, "auth0|beto")

        mockMvc
            .delete("/snippets/$id") { with(jwt().jwt { it.subject("auth0|beto") }.authorities(writeSnippets)) }
            .andExpect { status { isNotFound() } }

        assertEquals(true, repository.existsById(id))
    }

    @Test
    fun `GET snippets incluye los que le compartieron`() {
        val id = saved("auth0|ana")
        permissions.registerReader(id, "auth0|beto")

        mockMvc
            .get("/snippets") { with(jwt().jwt { it.subject("auth0|beto") }.authorities(readSnippets)) }
            .andExpect {
                status { isOk() }
                jsonPath("$.count") { value(1) }
                jsonPath("$.snippets[0].id") { value(id.toString()) }
            }
    }

    @Test
    fun `GET snippets con una relation que no existe responde 400`() {
        mockMvc
            .get("/snippets?relation=amigos") {
                with(jwt().jwt { it.subject("auth0|beto") }.authorities(readSnippets))
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.detail") { exists() }
            }
    }

    @Test
    fun `GET snippets con Permisos caido responde 502, y con relation owned anda igual`() {
        permissions.failWith = "connection refused"
        val beto = jwt().jwt { it.subject("auth0|beto") }.authorities(readSnippets)

        mockMvc.get("/snippets") { with(beto) }.andExpect { status { isBadGateway() } }
        mockMvc.get("/snippets?relation=owned") { with(beto) }.andExpect { status { isOk() } }
    }

    // El criterio del #30: quien recibió el snippet ve sus tests, pero no los puede tocar
    @Test
    fun `GET tests de un snippet compartido responde 200 con los tests`() {
        val id = saved("auth0|ana")
        permissions.registerReader(id, "auth0|beto")
        mockMvc
            .post("/snippets/$id/tests") {
                with(jwt().jwt { it.subject("auth0|ana") }.authorities(writeSnippets))
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Saluda","input":["Juan"],"output":["Hola Juan"]}"""
            }.andExpect { status { isCreated() } }

        mockMvc
            .get("/snippets/$id/tests") { with(jwt().jwt { it.subject("auth0|beto") }.authorities(readSnippets)) }
            .andExpect {
                status { isOk() }
                jsonPath("$[0].name") { value("Saluda") }
            }
    }

    @Test
    fun `POST tests de un snippet compartido responde 404`() {
        val id = saved("auth0|ana")
        permissions.registerReader(id, "auth0|beto")

        mockMvc
            .post("/snippets/$id/tests") {
                with(jwt().jwt { it.subject("auth0|beto") }.authorities(writeSnippets))
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Intruso"}"""
            }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `GET tests con Permisos caido para quien no es dueno responde 502`() {
        val id = saved("auth0|ana")
        permissions.failWith = "connection refused"

        mockMvc
            .get("/snippets/$id/tests") { with(jwt().jwt { it.subject("auth0|beto") }.authorities(readSnippets)) }
            .andExpect { status { isBadGateway() } }
    }

    // Un snippet de ana, con su contenido en el asset service
    private fun saved(ownerId: String): UUID {
        val id = UUID.randomUUID()
        asset.put(id, "println(1);")
        repository.save(Snippet(id, ownerId, "Saludo", "Pide un nombre", "printscript", "1.1"))
        return id
    }
}

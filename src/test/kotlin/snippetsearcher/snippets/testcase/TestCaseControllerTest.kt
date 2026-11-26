package snippetsearcher.snippets.testcase

import com.jayway.jsonpath.JsonPath
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
import org.springframework.test.web.servlet.ResultActionsDsl
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
import snippetsearcher.snippets.snippet.Snippet
import snippetsearcher.snippets.snippet.SnippetRepository
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Los tests de un snippet por HTTP, con Postgres real: el orden de las listas lo guarda la base.
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
class TestCaseControllerTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val printScript: FakePrintScriptClient,
    @Autowired private val snippets: SnippetRepository,
    @Autowired private val testCases: TestCaseRepository,
    @Autowired private val asset: FakeAssetClient,
    @Autowired private val permissions: FakePermissionsClient,
) {
    private val ana = "auth0|ana"
    private val writeSnippets = SimpleGrantedAuthority("SCOPE_write:snippets")
    private val readSnippets = SimpleGrantedAuthority("SCOPE_read:snippets")

    // Los inputs y outputs a propósito fuera de orden alfabético: si la base los ordenara, se notaría
    private val saludo =
        """
        {"name":"Saluda a Juan","input":["Juan","Ana","Beto"],"output":["Hola Juan","Hola Ana","Hola Beto"]}
        """.trimIndent()

    @BeforeEach
    fun limpiarFakes() {
        printScript.clear()
        asset.clear()
        permissions.clear()
    }

    @Test
    fun `POST tests guarda el test y responde 201 con las listas en orden`() {
        val snippetId = savedSnippet(ana)

        create(snippetId, saludo).andExpect {
            status { isCreated() }
            jsonPath("$.id") { exists() }
            jsonPath("$.name") { value("Saluda a Juan") }
            jsonPath("$.input[0]") { value("Juan") }
            jsonPath("$.input[2]") { value("Beto") }
            jsonPath("$.output[1]") { value("Hola Ana") }
        }
    }

    // La US 8 permite guardar outputs que no coinciden: guardar no ejecuta nada
    @Test
    fun `crear un test no lo ejecuta`() {
        val snippetId = savedSnippet(ana)

        create(snippetId, saludo).andExpect { status { isCreated() } }

        assertNull(printScript.lastVersion)
    }

    @Test
    fun `GET tests lista los tests del snippet en el orden en que se crearon`() {
        val snippetId = savedSnippet(ana)
        create(snippetId, """{"name":"Primero"}""")
        create(snippetId, saludo)

        mockMvc
            .get("/snippets/$snippetId/tests") { with(token(ana, readSnippets)) }
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(2) }
                jsonPath("$[0].name") { value("Primero") }
                jsonPath("$[0].input") { isEmpty() }
                jsonPath("$[1].name") { value("Saluda a Juan") }
                jsonPath("$[1].input[1]") { value("Ana") }
            }
    }

    @Test
    fun `PUT tests cambia el nombre y las listas`() {
        val snippetId = savedSnippet(ana)
        val testId = idOf(create(snippetId, saludo))

        mockMvc
            .put("/snippets/$snippetId/tests/$testId") {
                with(token(ana, writeSnippets))
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Saluda a Ana","input":["Ana"],"output":["Hola Ana"]}"""
            }.andExpect {
                status { isOk() }
                jsonPath("$.name") { value("Saluda a Ana") }
                jsonPath("$.input.length()") { value(1) }
            }

        assertEquals(listOf("Hola Ana"), testCases.findById(testId).orElseThrow().outputs)
    }

    @Test
    fun `DELETE tests lo saca de la lista`() {
        val snippetId = savedSnippet(ana)
        val testId = idOf(create(snippetId, saludo))

        mockMvc
            .delete("/snippets/$snippetId/tests/$testId") { with(token(ana, writeSnippets)) }
            .andExpect { status { isNoContent() } }

        assertEquals(0, testCases.findBySnippetIdOrderByCreatedAtAscIdAsc(snippetId).size)
    }

    // El aviso de la review de #62: sin ON DELETE CASCADE, la FK haría fallar el borrado del snippet
    @Test
    fun `borrar el snippet borra sus tests`() {
        val snippetId = savedSnippet(ana)
        create(snippetId, saludo)

        mockMvc
            .delete("/snippets/$snippetId") { with(token(ana, writeSnippets)) }
            .andExpect { status { isNoContent() } }

        assertEquals(0, testCases.findBySnippetIdOrderByCreatedAtAscIdAsc(snippetId).size)
    }

    @Test
    fun `los tests de un snippet de otro usuario responden 404 y no se tocan`() {
        val snippetId = savedSnippet(ana)
        val testId = idOf(create(snippetId, saludo))
        val beto = "auth0|beto"

        mockMvc.get("/snippets/$snippetId/tests") { with(token(beto, readSnippets)) }.andExpect {
            status { isNotFound() }
        }
        create(snippetId, saludo, beto).andExpect { status { isNotFound() } }
        mockMvc.delete("/snippets/$snippetId/tests/$testId") { with(token(beto, writeSnippets)) }.andExpect {
            status { isNotFound() }
        }

        assertEquals(1, testCases.findBySnippetIdOrderByCreatedAtAscIdAsc(snippetId).size)
    }

    @Test
    fun `un snippet que no existe responde 404`() {
        mockMvc.get("/snippets/${UUID.randomUUID()}/tests") { with(token(ana, readSnippets)) }.andExpect {
            status { isNotFound() }
        }
    }

    // Con solo el id del test se podría tocar el de otro snippet: tiene que ser del snippet de la URL
    @Test
    fun `un test de otro snippet responde 404 aunque los dos sean del mismo dueno`() {
        val primero = savedSnippet(ana)
        val segundo = savedSnippet(ana)
        val testId = idOf(create(primero, saludo))

        mockMvc
            .delete("/snippets/$segundo/tests/$testId") { with(token(ana, writeSnippets)) }
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `un test sin nombre responde 400`() {
        val snippetId = savedSnippet(ana)

        create(snippetId, """{"name":"   ","input":["Juan"]}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("Falta el nombre del test") }
        }
    }

    @Test
    fun `crear un test con solo read_snippets responde 403`() {
        val snippetId = savedSnippet(ana)

        mockMvc
            .post("/snippets/$snippetId/tests") {
                with(token(ana, readSnippets))
                contentType = MediaType.APPLICATION_JSON
                content = saludo
            }.andExpect { status { isForbidden() } }
    }

    private fun token(
        subject: String,
        scope: SimpleGrantedAuthority,
    ) = jwt().jwt { it.subject(subject) }.authorities(scope)

    private fun create(
        snippetId: UUID,
        body: String,
        subject: String = ana,
    ) = mockMvc.post("/snippets/$snippetId/tests") {
        with(token(subject, writeSnippets))
        contentType = MediaType.APPLICATION_JSON
        content = body
    }

    private fun idOf(result: ResultActionsDsl): UUID =
        UUID.fromString(JsonPath.read(result.andReturn().response.contentAsString, "$.id"))

    // Un snippet de ese usuario, con su contenido en el asset service
    private fun savedSnippet(ownerId: String): UUID {
        val id = UUID.randomUUID()
        asset.put(id, "println(1);")
        snippets.save(Snippet(id, ownerId, "Saludo", "Pide un nombre", "printscript", "1.1", "prs"))
        return id
    }
}

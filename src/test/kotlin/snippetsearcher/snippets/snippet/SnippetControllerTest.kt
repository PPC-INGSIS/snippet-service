package snippetsearcher.snippets.snippet

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
import org.springframework.test.web.servlet.post
import snippetsearcher.snippets.TestcontainersConfiguration
import snippetsearcher.snippets.printscript.FakePrintScriptClient
import snippetsearcher.snippets.printscript.FakePrintScriptConfiguration
import snippetsearcher.snippets.printscript.ValidationError
import snippetsearcher.snippets.printscript.ValidationResult
import java.util.UUID
import kotlin.test.assertEquals

// Crear un snippet es escribir: los tokens de este test traen write:snippets
private val writeSnippets = SimpleGrantedAuthority("SCOPE_write:snippets")

@Import(TestcontainersConfiguration::class, FakePrintScriptConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class SnippetControllerTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val printScript: FakePrintScriptClient,
    @Autowired private val repository: SnippetRepository,
) {
    private val body =
        """
        {"name":"Saludo","description":"Pide un nombre","language":"printscript","version":"1.1","content":"println(1);"}
        """.trimIndent()

    @BeforeEach
    fun codigoValidoPorDefecto() {
        printScript.result = ValidationResult.Checked(emptyList())
    }

    @Test
    fun `POST snippets con codigo valido responde 201 con su id`() {
        mockMvc
            .post("/snippets") {
                with(jwt().authorities(writeSnippets))
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isCreated() }
                jsonPath("$.id") { exists() }
                jsonPath("$.name") { value("Saludo") }
            }
    }

    @Test
    fun `POST snippets guarda el sub del token como dueno`() {
        val response =
            mockMvc
                .post("/snippets") {
                    with(jwt().jwt { it.subject("auth0|ana") }.authorities(writeSnippets))
                    contentType = MediaType.APPLICATION_JSON
                    content = body
                }.andExpect { status { isCreated() } }
                .andReturn()
                .response.contentAsString

        assertEquals("auth0|ana", ownerOf(response))
    }

    @Test
    fun `POST snippets ignora un dueno que venga en el body`() {
        val bodyConDueno =
            """
            {"name":"Saludo","description":"Pide un nombre","language":"printscript","version":"1.1",
            "content":"println(1);","ownerId":"auth0|otro"}
            """.trimIndent()

        val response =
            mockMvc
                .post("/snippets") {
                    with(jwt().jwt { it.subject("auth0|ana") }.authorities(writeSnippets))
                    contentType = MediaType.APPLICATION_JSON
                    content = bodyConDueno
                }.andExpect { status { isCreated() } }
                .andReturn()
                .response.contentAsString

        assertEquals("auth0|ana", ownerOf(response))
    }

    @Test
    fun `POST snippets con un token sin sub responde 401 y no guarda nada`() {
        val before = repository.count()

        mockMvc
            .post("/snippets") {
                with(jwt().jwt { token -> token.claims { it.remove("sub") } }.authorities(writeSnippets))
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isUnauthorized() }
            }

        assertEquals(before, repository.count())
    }

    @Test
    fun `POST snippets con codigo invalido responde 400 con los errores`() {
        printScript.result = ValidationResult.Checked(listOf(ValidationError("Falta el punto y coma", 1, 11)))

        mockMvc
            .post("/snippets") {
                with(jwt().authorities(writeSnippets))
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.errors[0].message") { value("Falta el punto y coma") }
                jsonPath("$.errors[0].line") { value(1) }
                jsonPath("$.errors[0].column") { value(11) }
            }
    }

    @Test
    fun `POST snippets con una version que no existe responde 400 con el mensaje`() {
        printScript.result = ValidationResult.Rejected("La versión '1.3' no existe")

        mockMvc
            .post("/snippets") {
                with(jwt().authorities(writeSnippets))
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.message") { value("La versión '1.3' no existe") }
            }
    }

    // La respuesta no expone el dueño, así que se busca en la base con el id que devolvió
    private fun ownerOf(response: String): String {
        val id = UUID.fromString(JsonPath.read<String>(response, "$.id"))
        return repository.findById(id).orElseThrow().ownerId
    }
}

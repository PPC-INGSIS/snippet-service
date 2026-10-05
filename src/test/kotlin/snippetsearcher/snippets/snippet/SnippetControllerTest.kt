package snippetsearcher.snippets.snippet

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import snippetsearcher.snippets.TestcontainersConfiguration
import snippetsearcher.snippets.printscript.FakePrintScriptClient
import snippetsearcher.snippets.printscript.FakePrintScriptConfiguration
import snippetsearcher.snippets.printscript.ValidationError
import snippetsearcher.snippets.printscript.ValidationResult

@Import(TestcontainersConfiguration::class, FakePrintScriptConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class SnippetControllerTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val printScript: FakePrintScriptClient,
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
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isCreated() }
                jsonPath("$.id") { exists() }
                jsonPath("$.name") { value("Saludo") }
            }
    }

    @Test
    fun `POST snippets con codigo invalido responde 400 con los errores`() {
        printScript.result = ValidationResult.Checked(listOf(ValidationError("Falta el punto y coma", 1, 11)))

        mockMvc
            .post("/snippets") {
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
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.message") { value("La versión '1.3' no existe") }
            }
    }
}

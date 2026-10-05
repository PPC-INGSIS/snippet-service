package snippetsearcher.snippets

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class SnippetControllerTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @Test
    fun `POST snippets crea el snippet y responde 201 con su id`() {
        mockMvc
            .post("/snippets") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    """
                    {"name":"Saludo","description":"Pide un nombre","language":"printscript","version":"1.1"}
                    """.trimIndent()
            }.andExpect {
                status { isCreated() }
                jsonPath("$.id") { exists() }
                jsonPath("$.name") { value("Saludo") }
                jsonPath("$.language") { value("printscript") }
            }
    }
}

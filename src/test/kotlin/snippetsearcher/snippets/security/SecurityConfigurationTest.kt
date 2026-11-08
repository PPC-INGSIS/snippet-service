package snippetsearcher.snippets.security

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import snippetsearcher.snippets.TestcontainersConfiguration
import snippetsearcher.snippets.printscript.FakePrintScriptConfiguration

// Mismos @Import que SnippetControllerTest: Spring reusa ese contexto en vez de levantar otro
@Import(TestcontainersConfiguration::class, FakePrintScriptConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigurationTest(
    @Autowired private val mockMvc: MockMvc,
) {
    @Test
    fun `un pedido sin token responde 401`() {
        mockMvc
            .post("/snippets") {
                contentType = MediaType.APPLICATION_JSON
                content = "{}"
            }.andExpect {
                status { isUnauthorized() }
            }
    }

    @Test
    fun `el healthcheck de readiness responde sin token`() {
        mockMvc
            .get("/actuator/health/readiness")
            .andExpect {
                status { isOk() }
            }
    }
}

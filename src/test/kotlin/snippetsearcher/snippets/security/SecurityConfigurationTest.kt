package snippetsearcher.snippets.security

import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
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

    @Test
    fun `sin los headers de nginx, el 401 apunta a la URL interna`() {
        mockMvc
            .get("/snippets")
            .andExpect {
                status { isUnauthorized() }
                header {
                    string(
                        "WWW-Authenticate",
                        containsString("resource_metadata=\"http://localhost/.well-known/oauth-protected-resource\""),
                    )
                }
            }
    }

    @Test
    fun `con los headers de nginx, el 401 apunta a la URL publica con https y el prefijo api`() {
        mockMvc
            .get("/snippets") {
                header("X-Forwarded-Proto", "https")
                header("X-Forwarded-Prefix", "/api")
            }.andExpect {
                status { isUnauthorized() }
                header {
                    string(
                        "WWW-Authenticate",
                        allOf(
                            containsString("https://localhost"),
                            containsString("/api/.well-known/oauth-protected-resource"),
                        ),
                    )
                }
            }
    }

    @Test
    fun `crear un snippet con solo read_snippets responde 403`() {
        mockMvc
            .post("/snippets") {
                with(jwt().authorities(SimpleGrantedAuthority("SCOPE_read:snippets")))
                contentType = MediaType.APPLICATION_JSON
                content = "{}"
            }.andExpect {
                status { isForbidden() }
            }
    }

    @Test
    fun `listar usuarios con solo write_snippets responde 403`() {
        mockMvc
            .get("/users") { with(jwt().authorities(SimpleGrantedAuthority("SCOPE_write:snippets"))) }
            .andExpect {
                status { isForbidden() }
            }
    }
}

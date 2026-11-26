package snippetsearcher.snippets.language

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import snippetsearcher.snippets.TestcontainersConfiguration
import snippetsearcher.snippets.printscript.FakePrintScriptClient
import snippetsearcher.snippets.printscript.FakePrintScriptConfiguration
import snippetsearcher.snippets.printscript.VersionsResult

// Listar lenguajes es leer: los tokens de este test traen read:snippets
private val readSnippets = SimpleGrantedAuthority("SCOPE_read:snippets")

@Import(TestcontainersConfiguration::class, FakePrintScriptConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class LanguageControllerTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val printScript: FakePrintScriptClient,
) {
    @BeforeEach
    fun limpiarFake() {
        printScript.clear()
    }

    @Test
    fun `GET languages responde cada lenguaje con su extension y sus versiones`() {
        printScript.versionsResult = VersionsResult.Found(listOf("1.0", "1.1"))

        mockMvc
            .get("/languages") { with(jwt().authorities(readSnippets)) }
            .andExpect {
                status { isOk() }
                content {
                    json(
                        """[{"language":"printscript","extension":"prs","versions":["1.0","1.1"]}]""",
                        JsonCompareMode.STRICT,
                    )
                }
            }
    }

    @Test
    fun `GET languages responde 502 si no se pudieron obtener las versiones`() {
        printScript.versionsResult = VersionsResult.Failed("PrintScript no respondió")

        mockMvc
            .get("/languages") { with(jwt().authorities(readSnippets)) }
            .andExpect {
                status { isBadGateway() }
                jsonPath("$.detail") { value("No se pudieron obtener las versiones de los lenguajes") }
            }
    }

    @Test
    fun `GET languages sin token responde 401`() {
        mockMvc.get("/languages").andExpect { status { isUnauthorized() } }
    }
}

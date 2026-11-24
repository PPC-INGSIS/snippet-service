package snippetsearcher.snippets.user

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import snippetsearcher.snippets.TestcontainersConfiguration
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Listar usuarios es leer: los tokens de este test traen read:snippets
private val readSnippets = SimpleGrantedAuthority("SCOPE_read:snippets")

@Import(TestcontainersConfiguration::class, FakeUserDirectoryConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class UserControllerTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val directory: FakeUserDirectory,
) {
    @BeforeEach
    fun directorioVacio() {
        directory.result = UserPage(emptyList(), 0)
        directory.lastSearch = null
    }

    @Test
    fun `GET users responde la pagina con la forma que espera la UI`() {
        directory.result = UserPage(listOf(User("auth0|ana", "ana@mail.com")), 12)

        mockMvc
            .get("/users") {
                with(jwt().authorities(readSnippets))
                param("name", "an")
                param("page", "2")
                param("page_size", "5")
            }.andExpect {
                status { isOk() }
                jsonPath("$.page") { value(2) }
                jsonPath("$.page_size") { value(5) }
                jsonPath("$.count") { value(12) }
                jsonPath("$.users[0].id") { value("auth0|ana") }
                jsonPath("$.users[0].name") { value("ana@mail.com") }
            }
    }

    @Test
    fun `GET users busca el nombre sin espacios en la pagina pedida`() {
        mockMvc
            .get("/users") {
                with(jwt().authorities(readSnippets))
                param("name", "  ana ")
                param("page", "2")
                param("page_size", "5")
            }.andExpect { status { isOk() } }

        assertEquals(FakeUserDirectory.Search("ana", 2, 5), directory.lastSearch)
    }

    @Test
    fun `GET users sin parametros busca la primera pagina de 10`() {
        mockMvc.get("/users") { with(jwt().authorities(readSnippets)) }.andExpect { status { isOk() } }

        assertEquals(FakeUserDirectory.Search("", 0, 10), directory.lastSearch)
    }

    @ParameterizedTest
    @CsvSource("-1, 10", "0, 0", "0, 51")
    fun `GET users con una pagina invalida responde 400 sin buscar`(
        page: Int,
        pageSize: Int,
    ) {
        mockMvc
            .get("/users") {
                with(jwt().authorities(readSnippets))
                param("page", page.toString())
                param("page_size", pageSize.toString())
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.detail") { exists() }
            }

        assertNull(directory.lastSearch)
    }

    @Test
    fun `GET users sin token responde 401`() {
        mockMvc.get("/users").andExpect { status { isUnauthorized() } }
    }
}

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
import snippetsearcher.snippets.printscript.ValidationError
import snippetsearcher.snippets.printscript.ValidationResult
import java.util.UUID
import kotlin.test.assertEquals

// Crear un snippet es escribir: los tokens de este test traen write:snippets
private val writeSnippets = SimpleGrantedAuthority("SCOPE_write:snippets")

// Pedir un snippet es leer
private val readSnippets = SimpleGrantedAuthority("SCOPE_read:snippets")

@Import(
    TestcontainersConfiguration::class,
    FakePrintScriptConfiguration::class,
    FakeAssetConfiguration::class,
    FakePermissionsConfiguration::class,
)
@SpringBootTest
@AutoConfigureMockMvc
class SnippetControllerTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val printScript: FakePrintScriptClient,
    @Autowired private val repository: SnippetRepository,
    @Autowired private val asset: FakeAssetClient,
    @Autowired private val permissions: FakePermissionsClient,
) {
    private val body =
        """
        {"name":"Saludo","description":"Pide un nombre","language":"printscript","version":"1.1","content":"println(1);"}
        """.trimIndent()

    // Reemplazo total de un snippet: cambia todos los datos y el contenido
    private val updateBody =
        """
        {"name":"Despedida","description":"Dice chau","language":"printscript","version":"1.0","content":"println(2);"}
        """.trimIndent()

    @BeforeEach
    fun codigoValidoPorDefecto() {
        printScript.clear()
        asset.clear()
        permissions.clear()
    }

    @Test
    fun `POST snippets con Permisos caido responde 502`() {
        permissions.failWith = "connection refused"

        mockMvc
            .post("/snippets") {
                with(jwt().authorities(writeSnippets))
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isBadGateway() }
                jsonPath("$.message") { exists() }
            }
    }

    // 502 y no 400: el pedido estaba bien, el que falló fue un servicio nuestro
    @Test
    fun `POST snippets con el asset service caido responde 502`() {
        asset.failWith = "connection refused"

        mockMvc
            .post("/snippets") {
                with(jwt().authorities(writeSnippets))
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isBadGateway() }
                jsonPath("$.message") { exists() }
            }
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

    @Test
    fun `GET snippets por id del dueno responde 200 con los datos y el contenido`() {
        val id = saved("auth0|ana")

        mockMvc
            .get("/snippets/$id") {
                with(jwt().jwt { it.subject("auth0|ana") }.authorities(readSnippets))
            }.andExpect {
                status { isOk() }
                jsonPath("$.id") { value(id.toString()) }
                jsonPath("$.name") { value("Saludo") }
                jsonPath("$.description") { value("Pide un nombre") }
                jsonPath("$.language") { value("printscript") }
                jsonPath("$.version") { value("1.1") }
                jsonPath("$.content") { value("println(1);") }
            }
    }

    @Test
    fun `GET snippets por id inexistente responde 404`() {
        mockMvc
            .get("/snippets/${UUID.randomUUID()}") {
                with(jwt().jwt { it.subject("auth0|ana") }.authorities(readSnippets))
            }.andExpect {
                status { isNotFound() }
            }
    }

    @Test
    fun `GET snippets por id de otro usuario responde 404`() {
        val id = saved("auth0|ana")

        mockMvc
            .get("/snippets/$id") {
                with(jwt().jwt { it.subject("auth0|beto") }.authorities(readSnippets))
            }.andExpect {
                status { isNotFound() }
            }
    }

    @Test
    fun `GET snippets por id con el asset service caido responde 502`() {
        val id = saved("auth0|ana")
        asset.failWith = "connection refused"

        mockMvc
            .get("/snippets/$id") {
                with(jwt().jwt { it.subject("auth0|ana") }.authorities(readSnippets))
            }.andExpect {
                status { isBadGateway() }
                jsonPath("$.message") { exists() }
            }
    }

    // 500 y no 502: el asset service contestó bien, lo que quedó mal son nuestros datos
    @Test
    fun `GET snippets por id de una fila sin contenido responde 500`() {
        val id = saved("auth0|ana")
        asset.clear()

        mockMvc
            .get("/snippets/$id") {
                with(jwt().jwt { it.subject("auth0|ana") }.authorities(readSnippets))
            }.andExpect {
                status { isInternalServerError() }
            }
    }

    @Test
    fun `GET snippets con un id que no es un UUID responde 400`() {
        mockMvc
            .get("/snippets/no-es-un-uuid") {
                with(jwt().authorities(readSnippets))
            }.andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `GET snippets por id con un token sin sub responde 401`() {
        mockMvc
            .get("/snippets/${UUID.randomUUID()}") {
                with(jwt().jwt { token -> token.claims { it.remove("sub") } }.authorities(readSnippets))
            }.andExpect {
                status { isUnauthorized() }
            }
    }

    @Test
    fun `PUT snippets por id del dueno responde 200 y cambia la fila`() {
        val id = saved("auth0|ana")

        mockMvc
            .put("/snippets/$id") {
                with(jwt().jwt { it.subject("auth0|ana") }.authorities(writeSnippets))
                contentType = MediaType.APPLICATION_JSON
                content = updateBody
            }.andExpect {
                status { isOk() }
                jsonPath("$.id") { value(id.toString()) }
                jsonPath("$.name") { value("Despedida") }
                jsonPath("$.version") { value("1.0") }
                jsonPath("$.content") { value("println(2);") }
            }

        assertEquals("Despedida", repository.findById(id).orElseThrow().name)
    }

    @Test
    fun `PUT snippets con codigo invalido responde 400 con los errores y no cambia nada`() {
        val id = saved("auth0|ana")
        printScript.result = ValidationResult.Checked(listOf(ValidationError("Falta el punto y coma", 1, 11)))

        mockMvc
            .put("/snippets/$id") {
                with(jwt().jwt { it.subject("auth0|ana") }.authorities(writeSnippets))
                contentType = MediaType.APPLICATION_JSON
                content = updateBody
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.errors[0].message") { value("Falta el punto y coma") }
                jsonPath("$.errors[0].line") { value(1) }
                jsonPath("$.errors[0].column") { value(11) }
            }

        assertEquals("Saludo", repository.findById(id).orElseThrow().name)
    }

    @Test
    fun `PUT snippets con una version que no existe responde 400 con el mensaje`() {
        val id = saved("auth0|ana")
        printScript.result = ValidationResult.Rejected("La versión '1.3' no existe")

        mockMvc
            .put("/snippets/$id") {
                with(jwt().jwt { it.subject("auth0|ana") }.authorities(writeSnippets))
                contentType = MediaType.APPLICATION_JSON
                content = updateBody
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.message") { value("La versión '1.3' no existe") }
            }
    }

    @Test
    fun `PUT snippets sin version responde 400`() {
        val id = saved("auth0|ana")
        val sinVersion =
            """
            {"name":"Despedida","description":"Dice chau","language":"printscript","content":"println(2);"}
            """.trimIndent()

        mockMvc
            .put("/snippets/$id") {
                with(jwt().jwt { it.subject("auth0|ana") }.authorities(writeSnippets))
                contentType = MediaType.APPLICATION_JSON
                content = sinVersion
            }.andExpect {
                status { isBadRequest() }
            }

        assertEquals("Saludo", repository.findById(id).orElseThrow().name)
    }

    @Test
    fun `PUT snippets por id de otro usuario responde 404 y no cambia nada`() {
        val id = saved("auth0|ana")

        mockMvc
            .put("/snippets/$id") {
                with(jwt().jwt { it.subject("auth0|beto") }.authorities(writeSnippets))
                contentType = MediaType.APPLICATION_JSON
                content = updateBody
            }.andExpect {
                status { isNotFound() }
            }

        assertEquals("Saludo", repository.findById(id).orElseThrow().name)
    }

    @Test
    fun `PUT snippets por id inexistente responde 404`() {
        mockMvc
            .put("/snippets/${UUID.randomUUID()}") {
                with(jwt().jwt { it.subject("auth0|ana") }.authorities(writeSnippets))
                contentType = MediaType.APPLICATION_JSON
                content = updateBody
            }.andExpect {
                status { isNotFound() }
            }
    }

    @Test
    fun `PUT snippets con el asset service caido responde 502 y no cambia la fila`() {
        val id = saved("auth0|ana")
        asset.failWith = "connection refused"

        mockMvc
            .put("/snippets/$id") {
                with(jwt().jwt { it.subject("auth0|ana") }.authorities(writeSnippets))
                contentType = MediaType.APPLICATION_JSON
                content = updateBody
            }.andExpect {
                status { isBadGateway() }
                jsonPath("$.message") { exists() }
            }

        assertEquals("Saludo", repository.findById(id).orElseThrow().name)
    }

    @Test
    fun `PUT snippets con un token sin sub responde 401`() {
        mockMvc
            .put("/snippets/${UUID.randomUUID()}") {
                with(jwt().jwt { token -> token.claims { it.remove("sub") } }.authorities(writeSnippets))
                contentType = MediaType.APPLICATION_JSON
                content = updateBody
            }.andExpect {
                status { isUnauthorized() }
            }
    }

    @Test
    fun `DELETE snippets por id del dueno responde 204 y despues el GET responde 404`() {
        val id = saved("auth0|ana")

        mockMvc
            .delete("/snippets/$id") { with(jwt().jwt { it.subject("auth0|ana") }.authorities(writeSnippets)) }
            .andExpect { status { isNoContent() } }

        mockMvc
            .get("/snippets/$id") { with(jwt().jwt { it.subject("auth0|ana") }.authorities(readSnippets)) }
            .andExpect { status { isNotFound() } }
        assertEquals(0, asset.count())
    }

    @Test
    fun `DELETE snippets por id de otro usuario responde 404 y no borra nada`() {
        val id = saved("auth0|ana")

        mockMvc
            .delete("/snippets/$id") { with(jwt().jwt { it.subject("auth0|beto") }.authorities(writeSnippets)) }
            .andExpect { status { isNotFound() } }

        assertEquals(true, repository.existsById(id))
        assertEquals(1, asset.count())
    }

    @Test
    fun `DELETE snippets por id inexistente responde 404`() {
        mockMvc
            .delete("/snippets/${UUID.randomUUID()}") {
                with(jwt().jwt { it.subject("auth0|ana") }.authorities(writeSnippets))
            }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `DELETE snippets con el asset service caido responde 204 igual`() {
        val id = saved("auth0|ana")
        asset.failWith = "connection refused"

        mockMvc
            .delete("/snippets/$id") { with(jwt().jwt { it.subject("auth0|ana") }.authorities(writeSnippets)) }
            .andExpect { status { isNoContent() } }

        assertEquals(false, repository.existsById(id))
    }

    @Test
    fun `DELETE snippets con solo read_snippets responde 403 y no borra nada`() {
        val id = saved("auth0|ana")

        mockMvc
            .delete("/snippets/$id") { with(jwt().jwt { it.subject("auth0|ana") }.authorities(readSnippets)) }
            .andExpect { status { isForbidden() } }

        assertEquals(true, repository.existsById(id))
    }

    @Test
    fun `DELETE snippets con un token sin sub responde 401`() {
        mockMvc
            .delete("/snippets/${UUID.randomUUID()}") {
                with(jwt().jwt { token -> token.claims { it.remove("sub") } }.authorities(writeSnippets))
            }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `GET snippets devuelve la forma que espera la UI`() {
        val ana = "auth0|lista-forma"
        saved(ana)

        mockMvc
            .get("/snippets") {
                with(jwt().jwt { it.subject(ana) }.authorities(readSnippets))
            }.andExpect {
                status { isOk() }
                jsonPath("$.page") { value(0) }
                jsonPath("$.page_size") { value(10) }
                jsonPath("$.count") { value(1) }
                jsonPath("$.snippets[0].name") { value("Saludo") }
                jsonPath("$.snippets[0].language") { value("printscript") }
            }
    }

    // El listado no muestra el contenido, así que tampoco lo manda
    @Test
    fun `GET snippets no trae el contenido de los snippets`() {
        val ana = "auth0|lista-sin-contenido"
        saved(ana)

        mockMvc
            .get("/snippets") {
                with(jwt().jwt { it.subject(ana) }.authorities(readSnippets))
            }.andExpect {
                status { isOk() }
                jsonPath("$.snippets[0].content") { doesNotExist() }
            }
    }

    @Test
    fun `GET snippets no muestra los snippets de otro usuario`() {
        saved("auth0|lista-ana")

        mockMvc
            .get("/snippets") {
                with(jwt().jwt { it.subject("auth0|lista-beto") }.authorities(readSnippets))
            }.andExpect {
                status { isOk() }
                jsonPath("$.count") { value(0) }
                jsonPath("$.snippets") { isEmpty() }
            }
    }

    // page_size viaja en snake_case porque así lo manda la UI
    @Test
    fun `GET snippets corta la pagina en page_size y devuelve el total igual`() {
        val ana = "auth0|lista-pagina"
        saved(ana, "Alfa")
        saved(ana, "Beta")

        mockMvc
            .get("/snippets?page=0&page_size=1") {
                with(jwt().jwt { it.subject(ana) }.authorities(readSnippets))
            }.andExpect {
                status { isOk() }
                jsonPath("$.page_size") { value(1) }
                jsonPath("$.count") { value(2) }
                jsonPath("$.snippets.length()") { value(1) }
                jsonPath("$.snippets[0].name") { value("Alfa") }
            }
    }

    @Test
    fun `GET snippets filtra por snippetName`() {
        val ana = "auth0|lista-filtro"
        saved(ana, "Alfa")
        saved(ana, "Beta")

        mockMvc
            .get("/snippets?snippetName=bet") {
                with(jwt().jwt { it.subject(ana) }.authorities(readSnippets))
            }.andExpect {
                status { isOk() }
                jsonPath("$.count") { value(1) }
                jsonPath("$.snippets[0].name") { value("Beta") }
            }
    }

    @Test
    fun `GET snippets ordenado por un campo que no se puede responde 400`() {
        mockMvc
            .get("/snippets?sortBy=ownerId") {
                with(jwt().jwt { it.subject("auth0|lista-orden") }.authorities(readSnippets))
            }.andExpect {
                status { isBadRequest() }
                jsonPath("$.detail") { exists() }
            }
    }

    @Test
    fun `GET snippets con un sentido de orden invalido responde 400`() {
        mockMvc
            .get("/snippets?sortDirection=arriba") {
                with(jwt().jwt { it.subject("auth0|lista-sentido") }.authorities(readSnippets))
            }.andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `GET snippets con page_size fuera de rango responde 400`() {
        mockMvc
            .get("/snippets?page_size=0") {
                with(jwt().jwt { it.subject("auth0|lista-tamano") }.authorities(readSnippets))
            }.andExpect {
                status { isBadRequest() }
            }

        mockMvc
            .get("/snippets?page_size=${SnippetController.MAX_PAGE_SIZE + 1}") {
                with(jwt().jwt { it.subject("auth0|lista-tamano") }.authorities(readSnippets))
            }.andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `GET snippets con page negativa responde 400`() {
        mockMvc
            .get("/snippets?page=-1") {
                with(jwt().jwt { it.subject("auth0|lista-pagina-negativa") }.authorities(readSnippets))
            }.andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `GET snippets con un token sin sub responde 401`() {
        mockMvc
            .get("/snippets") {
                with(jwt().jwt { token -> token.claims { it.remove("sub") } }.authorities(readSnippets))
            }.andExpect {
                status { isUnauthorized() }
            }
    }

    // Un snippet ya guardado, con su contenido en el asset service, listo para pedirlo
    private fun saved(
        ownerId: String,
        name: String = "Saludo",
    ): UUID {
        val id = UUID.randomUUID()
        asset.put(id, "println(1);")
        repository.save(Snippet(id, ownerId, name, "Pide un nombre", "printscript", "1.1"))
        return id
    }

    // La respuesta no expone el dueño, así que se busca en la base con el id que devolvió
    private fun ownerOf(response: String): String {
        val id = UUID.fromString(JsonPath.read<String>(response, "$.id"))
        return repository.findById(id).orElseThrow().ownerId
    }
}

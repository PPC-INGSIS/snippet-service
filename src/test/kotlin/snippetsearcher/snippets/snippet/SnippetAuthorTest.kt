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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import snippetsearcher.snippets.TestcontainersConfiguration
import snippetsearcher.snippets.asset.FakeAssetClient
import snippetsearcher.snippets.asset.FakeAssetConfiguration
import snippetsearcher.snippets.permissions.FakePermissionsConfiguration
import snippetsearcher.snippets.printscript.FakePrintScriptConfiguration
import snippetsearcher.snippets.user.FakeUserDirectory
import snippetsearcher.snippets.user.FakeUserDirectoryConfiguration
import java.util.UUID
import kotlin.test.assertEquals

private val writeSnippets = SimpleGrantedAuthority("SCOPE_write:snippets")
private val readSnippets = SimpleGrantedAuthority("SCOPE_read:snippets")

/**
 * Los dos campos que la UI pide y no devolvíamos: `author` y `extension`.
 *
 * `author` es el **nombre** del dueño, no su `sub`: lo que guardamos es `auth0|abc123` y el nombre
 * vive en Auth0, así que hay que ir a buscarlo. Lo que se prueba acá es cómo se busca —una consulta
 * por página, no una por fila— y qué pasa cuando Auth0 no contesta.
 */
@Import(
    TestcontainersConfiguration::class,
    FakePrintScriptConfiguration::class,
    FakeAssetConfiguration::class,
    FakePermissionsConfiguration::class,
    FakeUserDirectoryConfiguration::class,
)
@SpringBootTest
@AutoConfigureMockMvc
class SnippetAuthorTest(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val repository: SnippetRepository,
    @Autowired private val asset: FakeAssetClient,
    @Autowired private val directory: FakeUserDirectory,
) {
    @BeforeEach
    fun limpiarLosFakes() {
        asset.clear()
        directory.clear()
    }

    /**
     * La extensión no viene del body: sale del catálogo de lenguajes.
     *
     * El cliente de la UI no la manda (`apiSnippetOperations.createSnippet` postea name,
     * description, language, version y content), y además es un dato derivado del lenguaje:
     * aceptarla permitiría un snippet printscript con extensión exe.
     */
    @Test
    fun `POST snippets devuelve la extension del lenguaje sin que el body la mande`() {
        val ana = dueno("crear")

        mockMvc
            .post("/snippets") {
                with(jwt().jwt { it.subject(ana) }.authorities(writeSnippets))
                contentType = MediaType.APPLICATION_JSON
                content =
                    """
                    {"name":"Saludo","description":"Pide un nombre","language":"printscript",
                    "version":"1.1","content":"println(1);"}
                    """.trimIndent()
            }.andExpect {
                status { isCreated() }
                jsonPath("$.extension") { value("ps") }
            }
    }

    // Un lenguaje que no está en el catálogo no tiene extensión que devolver
    @Test
    fun `un lenguaje que no esta en el catalogo devuelve la extension vacia`() {
        val ana = dueno("sin-lenguaje")
        val id = guardar(ana, language = "cobol")

        mockMvc
            .get("/snippets/$id") {
                with(jwt().jwt { it.subject(ana) }.authorities(readSnippets))
            }.andExpect {
                status { isOk() }
                jsonPath("$.extension") { value("") }
            }
    }

    @Test
    fun `GET snippets por id devuelve la extension y el nombre del dueno`() {
        val ana = dueno("por-id")
        directory.names = mapOf(ana to "Ana Pérez")
        val id = guardar(ana)

        mockMvc
            .get("/snippets/$id") {
                with(jwt().jwt { it.subject(ana) }.authorities(readSnippets))
            }.andExpect {
                status { isOk() }
                jsonPath("$.extension") { value("ps") }
                jsonPath("$.author") { value("Ana Pérez") }
            }
    }

    @Test
    fun `el listado devuelve el nombre del dueno, no su sub`() {
        val ana = dueno("listado")
        directory.names = mapOf(ana to "Ana Pérez")
        guardar(ana)

        mockMvc
            .get("/snippets?relation=owned") {
                with(jwt().jwt { it.subject(ana) }.authorities(readSnippets))
            }.andExpect {
                status { isOk() }
                jsonPath("$.snippets[0].author") { value("Ana Pérez") }
                jsonPath("$.snippets[0].extension") { value("ps") }
            }
    }

    /**
     * Lo que este ticket tenía que evitar: una llamada a Auth0 por fila.
     *
     * Tres snippets de dos dueños distintos tienen que resolverse con **una** consulta, y con los
     * ids sin repetir. Con 100 filas de un mismo dueño sería igual: una consulta con un solo id.
     */
    @Test
    fun `listar varios snippets pide los nombres una sola vez y sin repetir ids`() {
        val ana = dueno("una-sola-vez")
        val beto = dueno("una-sola-vez-beto")
        directory.names = mapOf(ana to "Ana Pérez", beto to "Beto")
        guardar(ana)
        guardar(ana)
        guardar(beto)
        // Los de Beto entran por lo compartido, no hace falta: alcanza con que Ana vea los suyos
        mockMvc
            .get("/snippets?relation=owned") {
                with(jwt().jwt { it.subject(ana) }.authorities(readSnippets))
            }.andExpect { status { isOk() } }

        assertEquals(1, directory.namesOfCalls)
        assertEquals(setOf(ana), directory.lastNamesOf?.toSet())
    }

    /**
     * Si Auth0 no contesta, el listado responde 200 igual y el `author` es el `sub`.
     *
     * Un vacío parecería un bug del listado; el `sub` dice qué pasó y sigue identificando al dueño.
     * Lo que no puede pasar es que la tabla entera se caiga porque falta un nombre.
     */
    @Test
    fun `si el directorio de usuarios falla el listado responde 200 con el sub como author`() {
        val ana = dueno("sin-auth0")
        directory.namesFail = true
        guardar(ana)

        mockMvc
            .get("/snippets?relation=owned") {
                with(jwt().jwt { it.subject(ana) }.authorities(readSnippets))
            }.andExpect {
                status { isOk() }
                jsonPath("$.snippets[0].author") { value(ana) }
            }
    }

    @Test
    fun `un dueno que ya no esta en Auth0 vuelve con su sub`() {
        val ana = dueno("sin-nombre")
        directory.names = emptyMap()
        guardar(ana)

        mockMvc
            .get("/snippets?relation=owned") {
                with(jwt().jwt { it.subject(ana) }.authorities(readSnippets))
            }.andExpect {
                status { isOk() }
                jsonPath("$.snippets[0].author") { value(ana) }
            }
    }

    // Un dueño por test: el contenedor de Postgres se comparte, así que las filas de un test
    // siguen ahí cuando corre el que viene
    private fun dueno(caso: String) = "auth0|autor-$caso"

    private fun guardar(
        ownerId: String,
        language: String = "printscript",
    ): UUID {
        val id = UUID.randomUUID()
        asset.put(id, "println(1);")
        repository.save(Snippet(id, ownerId, "Saludo", "Pide un nombre", language, "1.1"))
        return id
    }
}

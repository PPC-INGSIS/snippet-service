package snippetsearcher.snippets.snippet

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import snippetsearcher.snippets.TestcontainersConfiguration
import snippetsearcher.snippets.asset.FakeAssetClient
import snippetsearcher.snippets.asset.FakeAssetConfiguration
import snippetsearcher.snippets.permissions.FakePermissionsClient
import snippetsearcher.snippets.permissions.FakePermissionsConfiguration
import snippetsearcher.snippets.printscript.FakePrintScriptConfiguration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * El listado contra Postgres de verdad y no contra un mock del repository.
 *
 * Lo que se prueba acá es justamente la consulta: el filtro por dueño, el LIKE, el orden y
 * el total. Un mock del repository devolvería lo que uno le diga y no probaría nada de eso.
 *
 * Cada test usa su propio dueño: el contenedor de Postgres se comparte entre las clases de
 * test, así que las filas de un test siguen ahí cuando corre el que viene.
 */
@Import(
    TestcontainersConfiguration::class,
    FakePrintScriptConfiguration::class,
    FakeAssetConfiguration::class,
    FakePermissionsConfiguration::class,
)
@SpringBootTest
class SnippetListTest(
    @Autowired private val service: SnippetService,
    @Autowired private val repository: SnippetRepository,
    @Autowired private val asset: FakeAssetClient,
    @Autowired private val permissions: FakePermissionsClient,
) {
    // Permisos también: el listado le pregunta qué está compartido, y otra clase pudo dejarlo fallando
    @BeforeEach
    fun limpiarFakes() {
        asset.clear()
        permissions.clear()
    }

    @Test
    fun `solo trae los snippets del dueno, y ordenados por nombre si nadie pidio otro orden`() {
        val ana = dueno("duenos-ana")
        val beto = dueno("duenos-beto")
        guardar(ana, "Saludo")
        guardar(ana, "Factorial")
        guardar(beto, "El de beto")

        val listado = listar(ana)

        assertEquals(2, listado.total)
        assertEquals(listOf("Factorial", "Saludo"), listado.snippets.map { it.name })
    }

    @Test
    fun `filtra por un pedazo del nombre, sin distinguir mayusculas`() {
        val ana = dueno("nombre")
        guardar(ana, "Saludo")
        guardar(ana, "saludo final")
        guardar(ana, "Factorial")

        val listado = listar(ana, query(name = "SALUD"))

        assertEquals(2, listado.total)
        assertEquals(listOf("Saludo", "saludo final"), listado.snippets.map { it.name }.sorted())
    }

    @Test
    fun `filtra por lenguaje`() {
        val ana = dueno("lenguaje")
        guardar(ana, "Saludo", language = "printscript")
        guardar(ana, "Otro", language = "typescript")

        val listado = listar(ana, query(language = "PrintScript"))

        assertEquals(1, listado.total)
        assertEquals("Saludo", listado.snippets.single().name)
    }

    @Test
    fun `combina el filtro de nombre con el de lenguaje`() {
        val ana = dueno("combinado")
        guardar(ana, "Saludo", language = "printscript")
        guardar(ana, "Saludo", language = "typescript")
        guardar(ana, "Factorial", language = "printscript")

        val listado = listar(ana, query(name = "salu", language = "printscript"))

        assertEquals(1, listado.total)
        assertEquals("Saludo", listado.snippets.single().name)
        assertEquals("printscript", listado.snippets.single().language)
    }

    // En LIKE, _ es "cualquier caracter": sin escaparlo, buscar "a_b" tambien traeria "axb"
    @Test
    fun `el guion bajo de la busqueda no es un comodin`() {
        val ana = dueno("comodin-guion")
        guardar(ana, "a_b")
        guardar(ana, "axb")

        val listado = listar(ana, query(name = "a_b"))

        assertEquals(1, listado.total)
        assertEquals("a_b", listado.snippets.single().name)
    }

    // Y % es "cualquier cosa": sin escaparlo, buscar "%" traeria todo
    @Test
    fun `el porcentaje de la busqueda no es un comodin`() {
        val ana = dueno("comodin-porcentaje")
        guardar(ana, "100%")
        guardar(ana, "Saludo")

        val listado = listar(ana, query(name = "%"))

        assertEquals(1, listado.total)
        assertEquals("100%", listado.snippets.single().name)
    }

    @Test
    fun `un filtro en blanco es lo mismo que no filtrar`() {
        val ana = dueno("filtro-blanco")
        guardar(ana, "Saludo")
        guardar(ana, "Factorial")

        val listado = listar(ana, query(name = "   ", language = ""))

        assertEquals(2, listado.total)
    }

    @Test
    fun `el total es el de todos los snippets, no el de la pagina`() {
        val ana = dueno("paginado")
        repeat(5) { guardar(ana, "Snippet $it") }

        val primera = listar(ana, SnippetQuery(page = 0, pageSize = 2))
        val ultima = listar(ana, SnippetQuery(page = 2, pageSize = 2))

        assertEquals(5, primera.total)
        assertEquals(2, primera.snippets.size)
        assertEquals(5, ultima.total)
        assertEquals(1, ultima.snippets.size)
    }

    @Test
    fun `una pagina mas alla del final viene vacia pero con el total`() {
        val ana = dueno("pagina-de-mas")
        guardar(ana, "Saludo")

        val listado = listar(ana, SnippetQuery(page = 10, pageSize = 10))

        assertEquals(1, listado.total)
        assertTrue(listado.snippets.isEmpty())
    }

    @Test
    fun `ordena por nombre descendente`() {
        val ana = dueno("orden-nombre")
        guardar(ana, "Alfa")
        guardar(ana, "Beta")
        guardar(ana, "Gama")

        val listado = listar(ana, query(sortBy = "name", sortDirection = "DESC"))

        assertEquals(listOf("Gama", "Beta", "Alfa"), listado.snippets.map { it.name })
    }

    @Test
    fun `ordena por lenguaje`() {
        val ana = dueno("orden-lenguaje")
        guardar(ana, "Uno", language = "printscript")
        guardar(ana, "Dos", language = "typescript")
        guardar(ana, "Tres", language = "javascript")

        val listado = listar(ana, query(sortBy = "language", sortDirection = "asc"))

        assertEquals(listOf("javascript", "printscript", "typescript"), listado.snippets.map { it.language })
    }

    /**
     * El orden tiene que ser el mismo entre un pedido y el que viene: si no, al pasar de
     * pagina la UI puede ver dos veces la misma fila y nunca ver otra. Con el nombre
     * repetido, el unico desempate posible es el id.
     */
    @Test
    fun `paginar no repite ni saltea snippets con el mismo nombre`() {
        val ana = dueno("orden-estable")
        repeat(3) { guardar(ana, "Igual") }

        val primeraVuelta = (0..2).map { listar(ana, SnippetQuery(page = it, pageSize = 1)).snippets.single().id }
        val segundaVuelta = (0..2).map { listar(ana, SnippetQuery(page = it, pageSize = 1)).snippets.single().id }

        assertEquals(3, primeraVuelta.toSet().size, "paginando se repitio o se salteo alguno")
        assertEquals(primeraVuelta, segundaVuelta, "el orden cambio entre dos pedidos iguales")
    }

    // No es solo un campo invalido: ordenar por owner_id es ordenar por una columna que no mostramos
    @Test
    fun `no se puede ordenar por una columna que no se muestra`() {
        val result = service.list(dueno("orden-prohibido"), SnippetQuery(0, 10, sortBy = "ownerId"))

        val rechazado = assertIs<ListSnippetsResult.SortFieldNotAllowed>(result)
        assertEquals("ownerId", rechazado.field)
    }

    @Test
    fun `un campo de orden inventado no llega a la consulta`() {
        val result = service.list(dueno("orden-inventado"), SnippetQuery(0, 10, sortBy = "lo que sea"))

        assertIs<ListSnippetsResult.SortFieldNotAllowed>(result)
    }

    @Test
    fun `un sentido de orden que no es asc ni desc se rechaza`() {
        val result = service.list(dueno("sentido"), SnippetQuery(0, 10, sortDirection = "arriba"))

        val rechazado = assertIs<ListSnippetsResult.SortDirectionNotAllowed>(result)
        assertEquals("arriba", rechazado.direction)
    }

    @Test
    fun `filtra por cada estado de linteo`() {
        val ana = dueno("compliance-filtro")
        LintStatus.entries.forEach { guardar(ana, it.name, lintStatus = it) }

        LintStatus.entries.forEach { status ->
            val listado = listar(ana, query(compliance = status.value))

            assertEquals(1, listado.total)
            assertEquals(status, listado.snippets.single().lintStatus)
        }
    }

    @Test
    fun `un estado de linteo inventado se rechaza`() {
        val result = service.list(dueno("compliance-inventado"), SnippetQuery(0, 10, compliance = "casi"))

        val rechazado = assertIs<ListSnippetsResult.ComplianceNotAllowed>(result)
        assertEquals("casi", rechazado.compliance)
    }

    // Alfabético por el valor guardado, no por el texto de la API
    @Test
    fun `ordena por estado de linteo en los dos sentidos`() {
        val ana = dueno("compliance-orden")
        listOf(LintStatus.PENDING, LintStatus.COMPLIANT, LintStatus.FAILED, LintStatus.NOT_COMPLIANT)
            .forEach { guardar(ana, it.name, lintStatus = it) }
        val alfabetico = listOf(LintStatus.COMPLIANT, LintStatus.FAILED, LintStatus.NOT_COMPLIANT, LintStatus.PENDING)

        val asc = listar(ana, query(sortBy = "compliance", sortDirection = "asc"))
        val desc = listar(ana, query(sortBy = "compliance", sortDirection = "desc"))

        assertEquals(alfabetico, asc.snippets.map { it.lintStatus })
        assertEquals(alfabetico.reversed(), desc.snippets.map { it.lintStatus })
    }

    // El listado no muestra el contenido, asi que no tiene por que pedirlo: seria una
    // llamada al asset service por cada fila de la tabla
    @Test
    fun `listar no le pide el contenido al asset service`() {
        val ana = dueno("sin-contenido")
        repeat(3) { guardar(ana, "Snippet $it") }

        listar(ana)

        assertEquals(0, asset.gets)
    }

    private fun dueno(caso: String) = "auth0|listado-$caso"

    private fun guardar(
        ownerId: String,
        name: String,
        language: String = "printscript",
        lintStatus: LintStatus = LintStatus.PENDING,
    ) = repository.save(
        Snippet(
            id = UUID.randomUUID(),
            ownerId = ownerId,
            name = name,
            description = "una descripcion",
            language = language,
            version = "1.1",
        ).apply { this.lintStatus = lintStatus },
    )

    private fun listar(
        ownerId: String,
        query: SnippetQuery = query(),
    ) = assertIs<ListSnippetsResult.Listed>(service.list(ownerId, query))

    // Los filtros y el orden, con la primera pagina. Paginar se pide con SnippetQuery directo
    private fun query(
        name: String? = null,
        language: String? = null,
        sortBy: String? = null,
        sortDirection: String? = null,
        compliance: String? = null,
    ) = SnippetQuery(0, 10, name, language, sortBy, sortDirection, compliance = compliance)
}

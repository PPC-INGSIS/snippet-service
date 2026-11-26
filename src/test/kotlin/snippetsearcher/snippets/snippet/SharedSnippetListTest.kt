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

/**
 * El listado por relación (owned, shared, all) contra Postgres de verdad: lo compartido sale de
 * Permisos, pero el filtro, el orden y el total los resuelve la consulta.
 *
 * Mismos @Import que SnippetListTest, para reusar el contexto. Cada test usa sus propios usuarios.
 */
@Import(
    TestcontainersConfiguration::class,
    FakePrintScriptConfiguration::class,
    FakeAssetConfiguration::class,
    FakePermissionsConfiguration::class,
)
@SpringBootTest
class SharedSnippetListTest(
    @Autowired private val service: SnippetService,
    @Autowired private val repository: SnippetRepository,
    @Autowired private val asset: FakeAssetClient,
    @Autowired private val permissions: FakePermissionsClient,
) {
    @BeforeEach
    fun limpiarFakes() {
        asset.clear()
        permissions.clear()
    }

    @Test
    fun `sin relation trae los propios y los compartidos, ordenados juntos`() {
        val (ana, beto) = usuarios("todos")
        guardar(beto, "Propio de beto")
        compartir(guardar(ana, "De ana"), beto)
        guardar(ana, "No compartido")

        val listado = listar(beto)

        assertEquals(2, listado.total)
        assertEquals(listOf("De ana", "Propio de beto"), listado.snippets.map { it.name })
    }

    @Test
    fun `shared trae solo los que le compartieron`() {
        val (ana, beto) = usuarios("compartidos")
        guardar(beto, "Propio de beto")
        compartir(guardar(ana, "De ana"), beto)

        val listado = listar(beto, "shared")

        assertEquals(listOf("De ana"), listado.snippets.map { it.name })
    }

    // Lo propio no depende de Permisos: con owned ni se le pregunta
    @Test
    fun `owned trae solo los propios aunque Permisos este caido`() {
        val (ana, beto) = usuarios("propios")
        guardar(beto, "Propio de beto")
        compartir(guardar(ana, "De ana"), beto)
        permissions.failWith = "connection refused"

        val listado = listar(beto, "owned")

        assertEquals(listOf("Propio de beto"), listado.snippets.map { it.name })
    }

    // Una lista incompleta sin aviso haría creer que le sacaron lo compartido
    @Test
    fun `con Permisos caido no devuelve una lista incompleta`() {
        val (_, beto) = usuarios("caido")
        guardar(beto, "Propio de beto")
        permissions.failWith = "connection refused"

        val result = service.list(beto, query(relation = "all"))

        assertEquals("connection refused", assertIs<ListSnippetsResult.SharedUnavailable>(result).message)
    }

    @Test
    fun `a quien no le compartieron nada, shared le devuelve una lista vacia`() {
        val (_, beto) = usuarios("nada")
        guardar(beto, "Propio de beto")

        assertEquals(0, listar(beto, "shared").total)
    }

    @Test
    fun `los filtros se aplican tambien a lo compartido`() {
        val (ana, beto) = usuarios("filtro")
        compartir(guardar(ana, "Saludo de ana"), beto)
        compartir(guardar(ana, "Factorial"), beto)

        val listado = listar(beto, "shared", name = "salud")

        assertEquals(1, listado.total)
        assertEquals("Saludo de ana", listado.snippets.single().name)
    }

    @Test
    fun `una relation que no existe se rechaza`() {
        val result = service.list(usuarios("invalida").second, query(relation = "amigos"))

        assertEquals("amigos", assertIs<ListSnippetsResult.RelationNotAllowed>(result).relation)
    }

    private fun usuarios(caso: String) = "auth0|compartidos-$caso-ana" to "auth0|compartidos-$caso-beto"

    private fun guardar(
        ownerId: String,
        name: String,
    ) = repository.save(Snippet(UUID.randomUUID(), ownerId, name, "una descripcion", "printscript", "1.1"))

    private fun compartir(
        snippet: Snippet,
        userId: String,
    ) {
        permissions.registerOwner(snippet.id, snippet.ownerId)
        permissions.registerReader(snippet.id, userId)
    }

    private fun listar(
        userId: String,
        relation: String? = null,
        name: String? = null,
    ) = assertIs<ListSnippetsResult.Listed>(service.list(userId, query(relation, name)))

    private fun query(
        relation: String? = null,
        name: String? = null,
    ) = SnippetQuery(0, 10, name = name, relation = relation)
}

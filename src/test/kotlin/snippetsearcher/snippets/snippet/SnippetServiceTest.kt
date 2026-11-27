package snippetsearcher.snippets.snippet

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import snippetsearcher.snippets.TestcontainersConfiguration
import snippetsearcher.snippets.asset.ContentResult
import snippetsearcher.snippets.asset.FakeAssetClient
import snippetsearcher.snippets.asset.FakeAssetConfiguration
import snippetsearcher.snippets.language.ValidationError
import snippetsearcher.snippets.language.ValidationResult
import snippetsearcher.snippets.permissions.FakePermissionsClient
import snippetsearcher.snippets.permissions.FakePermissionsConfiguration
import snippetsearcher.snippets.printscript.FakePrintScriptClient
import snippetsearcher.snippets.printscript.FakePrintScriptConfiguration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

@Import(
    TestcontainersConfiguration::class,
    FakePrintScriptConfiguration::class,
    FakeAssetConfiguration::class,
    FakePermissionsConfiguration::class,
)
@SpringBootTest
class SnippetServiceTest(
    @Autowired private val service: SnippetService,
    @Autowired private val repository: SnippetRepository,
    @Autowired private val printScript: FakePrintScriptClient,
    @Autowired private val asset: FakeAssetClient,
    @Autowired private val permissions: FakePermissionsClient,
) {
    private val owner = "auth0|ana"

    private val saludo =
        NewSnippet(
            name = "Saludo",
            description = "Pide un nombre",
            language = "printscript",
            version = "1.1",
            content = "println(1);",
        )

    @BeforeEach
    fun codigoValidoPorDefecto() {
        printScript.clear()
        asset.clear()
        permissions.clear()
    }

    @Test
    fun `crea un snippet valido y lo deja guardado con su dueno`() {
        val result = service.create(owner, saludo)

        val created = assertIs<CreateSnippetResult.Created>(result)
        val found = repository.findById(created.snippet.id).orElseThrow()
        assertEquals("auth0|ana", found.ownerId)
        assertEquals("Saludo", found.name)
        assertEquals("Pide un nombre", found.description)
        assertEquals("printscript", found.language)
        assertEquals("1.1", found.version)
    }

    @Test
    fun `cada snippet creado recibe un id distinto`() {
        val first = assertIs<CreateSnippetResult.Created>(service.create(owner, saludo))
        val second = assertIs<CreateSnippetResult.Created>(service.create(owner, saludo.copy(name = "Otro")))

        assertNotEquals(first.snippet.id, second.snippet.id)
    }

    @Test
    fun `no guarda un snippet con codigo invalido`() {
        printScript.result = ValidationResult.Checked(listOf(ValidationError("Falta el punto y coma", 1, 11)))
        val before = repository.count()

        val result = service.create(owner, saludo.copy(content = "println(1)"))

        val invalid = assertIs<CreateSnippetResult.Invalid>(result)
        assertEquals("Falta el punto y coma", invalid.errors.single().message)
        assertEquals(before, repository.count())
    }

    @Test
    fun `no guarda un snippet si PrintScript rechaza el pedido`() {
        printScript.result = ValidationResult.Rejected("La versión '1.3' no existe")
        val before = repository.count()

        val result = service.create(owner, saludo.copy(version = "1.3"))

        val rejected = assertIs<CreateSnippetResult.Rejected>(result)
        assertEquals("La versión '1.3' no existe", rejected.message)
        assertEquals(before, repository.count())
    }

    @Test
    fun `no guarda un snippet con un lenguaje que no existe ni valida su codigo`() {
        val before = repository.count()

        val result = service.create(owner, saludo.copy(language = "python"))

        val rejected = assertIs<CreateSnippetResult.Rejected>(result)
        assertEquals("El lenguaje 'python' no existe", rejected.message)
        assertNull(printScript.lastVersion)
        assertEquals(before, repository.count())
        assertEquals(0, asset.count())
        assertEquals(0, permissions.count())
    }

    @Test
    fun `el contenido queda guardado en el asset service y no en la base`() {
        val result = service.create(owner, saludo)

        val created = assertIs<CreateSnippetResult.Created>(result)
        val content = assertIs<ContentResult.Found>(asset.get(created.snippet.id))
        assertEquals("println(1);", content.content)
    }

    // El criterio del ticket: no puede quedar un snippet sin contenido
    @Test
    fun `si el asset service falla no se guarda el snippet`() {
        asset.failWith = "connection refused"
        val before = repository.count()

        val result = service.create(owner, saludo)

        val failed = assertIs<CreateSnippetResult.ContentNotSaved>(result)
        assertEquals("connection refused", failed.message)
        assertEquals(before, repository.count())
    }

    // Si el código no compila no se llama al asset service: no hay contenido que guardar
    @Test
    fun `un snippet invalido no escribe contenido`() {
        printScript.result = ValidationResult.Checked(listOf(ValidationError("Falta el punto y coma", 1, 11)))

        service.create(owner, saludo.copy(content = "println(1)"))

        assertEquals(0, asset.count())
    }

    @Test
    fun `crear un snippet registra al dueno en Permisos`() {
        val result = service.create(owner, saludo)

        val created = assertIs<CreateSnippetResult.Created>(result)
        assertEquals(owner, permissions.ownerOf(created.snippet.id))
    }

    // El criterio del ticket: sin dueno el snippet no se podria compartir ni borrar
    @Test
    fun `si Permisos falla no se guarda el snippet`() {
        permissions.failWith = "connection refused"
        val before = repository.count()

        val result = service.create(owner, saludo)

        val failed = assertIs<CreateSnippetResult.OwnerNotRegistered>(result)
        assertEquals("connection refused", failed.message)
        assertEquals(before, repository.count())
    }

    // El contenido va antes que el dueno, asi que si Permisos falla ya quedo escrito.
    // Es basura que nadie referencia: mas barata que una fila sin dueno.
    @Test
    fun `si Permisos falla el contenido queda huerfano pero no hay snippet`() {
        permissions.failWith = "connection refused"

        service.create(owner, saludo)

        assertEquals(1, asset.count())
        assertEquals(0, permissions.count())
    }

    // Si el asset service falla ni se intenta registrar al dueno
    @Test
    fun `si el asset service falla no se llama a Permisos`() {
        asset.failWith = "connection refused"

        service.create(owner, saludo)

        assertEquals(0, permissions.count())
    }

    @Test
    fun `el dueno ve su snippet con el contenido`() {
        val created = assertIs<CreateSnippetResult.Created>(service.create(owner, saludo))

        val result = service.find(owner, created.snippet.id)

        val found = assertIs<FindSnippetResult.Found>(result)
        assertEquals("Saludo", found.snippet.name)
        assertEquals("println(1);", found.content)
    }

    @Test
    fun `un id que no existe no se encuentra`() {
        val result = service.find(owner, UUID.randomUUID())

        assertIs<FindSnippetResult.NotFound>(result)
    }

    @Test
    fun `el snippet de otro usuario no se encuentra`() {
        val created = assertIs<CreateSnippetResult.Created>(service.create(owner, saludo))

        val result = service.find("auth0|beto", created.snippet.id)

        assertIs<FindSnippetResult.NotFound>(result)
    }

    // Distinto de "no existe": el snippet está, lo que falló es el servicio que guarda su contenido
    @Test
    fun `si el asset service falla el contenido no esta disponible`() {
        val created = assertIs<CreateSnippetResult.Created>(service.create(owner, saludo))
        asset.failWith = "connection refused"

        val result = service.find(owner, created.snippet.id)

        val unavailable = assertIs<FindSnippetResult.ContentUnavailable>(result)
        assertEquals("connection refused", unavailable.message)
    }

    // Distinto de que el asset service falle: contestó bien, pero la fila quedó sin contenido
    @Test
    fun `una fila sin contenido en el asset service queda como contenido faltante`() {
        val created = assertIs<CreateSnippetResult.Created>(service.create(owner, saludo))
        asset.clear()

        val result = service.find(owner, created.snippet.id)

        assertIs<FindSnippetResult.ContentMissing>(result)
    }

    // El asset service solo se consulta si el snippet es del usuario
    @Test
    fun `el snippet de otro usuario no consulta al asset service`() {
        val created = assertIs<CreateSnippetResult.Created>(service.create(owner, saludo))
        asset.failWith = "no se tendría que llamar"

        val result = service.find("auth0|beto", created.snippet.id)

        assertIs<FindSnippetResult.NotFound>(result)
    }

    private val despedida =
        NewSnippet(
            name = "Despedida",
            description = "Dice chau",
            language = "printscript",
            version = "1.0",
            content = "println(2);",
        )

    private fun created(): Snippet = assertIs<CreateSnippetResult.Created>(service.create(owner, saludo)).snippet

    // Que la fila y el contenido sigan siendo los de saludo: no cambió nada
    private fun assertSigueIgual(id: UUID) {
        val row = repository.findById(id).orElseThrow()
        assertEquals("Saludo", row.name)
        assertEquals("Pide un nombre", row.description)
        assertEquals("1.1", row.version)
        assertEquals("println(1);", assertIs<ContentResult.Found>(asset.get(id)).content)
    }

    @Test
    fun `el dueno actualiza todos los datos y el contenido`() {
        val snippet = created()

        val result = service.update(owner, snippet.id, despedida)

        assertIs<UpdateSnippetResult.Updated>(result)
        val row = repository.findById(snippet.id).orElseThrow()
        assertEquals("Despedida", row.name)
        assertEquals("Dice chau", row.description)
        assertEquals("printscript", row.language)
        assertEquals("1.0", row.version)
        assertEquals("println(2);", assertIs<ContentResult.Found>(asset.get(snippet.id)).content)
    }

    @Test
    fun `actualizar no cambia el dueno ni el id`() {
        val snippet = created()

        service.update(owner, snippet.id, despedida)

        assertEquals(owner, repository.findById(snippet.id).orElseThrow().ownerId)
    }

    @Test
    fun `valida el codigo con la version nueva`() {
        val snippet = created()

        service.update(owner, snippet.id, despedida)

        assertEquals("1.0", printScript.lastVersion)
    }

    @Test
    fun `con codigo invalido no cambia nada`() {
        val snippet = created()
        printScript.result = ValidationResult.Checked(listOf(ValidationError("Falta el punto y coma", 1, 11)))

        val result = service.update(owner, snippet.id, despedida)

        val invalid = assertIs<UpdateSnippetResult.Invalid>(result)
        assertEquals("Falta el punto y coma", invalid.errors.single().message)
        assertSigueIgual(snippet.id)
    }

    @Test
    fun `si PrintScript rechaza el pedido no cambia nada`() {
        val snippet = created()
        printScript.result = ValidationResult.Rejected("La versión '1.3' no existe")

        val result = service.update(owner, snippet.id, despedida.copy(version = "1.3"))

        val rejected = assertIs<UpdateSnippetResult.Rejected>(result)
        assertEquals("La versión '1.3' no existe", rejected.message)
        assertSigueIgual(snippet.id)
    }

    @Test
    fun `con un lenguaje que no existe no cambia nada ni valida el codigo`() {
        val snippet = created()
        // created() ya validó el código al crear el snippet: se borra para probar solo el update
        printScript.lastVersion = null

        val result = service.update(owner, snippet.id, despedida.copy(language = "python"))

        val rejected = assertIs<UpdateSnippetResult.Rejected>(result)
        assertEquals("El lenguaje 'python' no existe", rejected.message)
        assertNull(printScript.lastVersion)
        assertEquals("printscript", repository.findById(snippet.id).orElseThrow().language)
        assertSigueIgual(snippet.id)
    }

    @Test
    fun `el snippet de otro usuario no se actualiza ni se valida`() {
        val snippet = created()
        // created() ya validó el código al crear el snippet: se borra para probar solo el update
        printScript.lastVersion = null

        val result = service.update("auth0|beto", snippet.id, despedida)

        assertIs<UpdateSnippetResult.NotFound>(result)
        assertNull(printScript.lastVersion)
        assertSigueIgual(snippet.id)
    }

    @Test
    fun `un id que no existe no se actualiza`() {
        val result = service.update(owner, UUID.randomUUID(), despedida)

        assertIs<UpdateSnippetResult.NotFound>(result)
    }

    // El criterio central del ticket: no pueden quedar datos nuevos con contenido viejo
    @Test
    fun `si el asset service falla se revierte la fila`() {
        val snippet = created()
        asset.failWith = "connection refused"

        val result = service.update(owner, snippet.id, despedida)

        val failed = assertIs<UpdateSnippetResult.ContentNotSaved>(result)
        assertEquals("connection refused", failed.message)
        asset.failWith = null
        assertSigueIgual(snippet.id)
    }

    @Test
    fun `el dueno borra el snippet, su contenido y sus accesos`() {
        val snippet = created()

        val result = service.delete(owner, snippet.id)

        assertIs<DeleteSnippetResult.Deleted>(result)
        assertEquals(false, repository.existsById(snippet.id))
        assertEquals(0, asset.count())
        assertNull(permissions.ownerOf(snippet.id))
    }

    @Test
    fun `borrar el snippet de otro usuario no borra nada`() {
        val snippet = created()

        val result = service.delete("auth0|beto", snippet.id)

        assertIs<DeleteSnippetResult.NotFound>(result)
        assertSigueIgual(snippet.id)
        assertEquals(owner, permissions.ownerOf(snippet.id))
    }

    @Test
    fun `borrar un id que no existe responde NotFound`() {
        assertIs<DeleteSnippetResult.NotFound>(service.delete(owner, UUID.randomUUID()))
    }

    @Test
    fun `borrar un snippet no toca los demas`() {
        val borrado = created()
        val otro = assertIs<CreateSnippetResult.Created>(service.create(owner, despedida)).snippet

        service.delete(owner, borrado.id)

        assertEquals(true, repository.existsById(otro.id))
        assertEquals(1, asset.count())
        assertEquals(owner, permissions.ownerOf(otro.id))
    }

    // La fila ya no está: el snippet desapareció para el usuario aunque el contenido quede huérfano
    @Test
    fun `si falla el asset service igual se borra el snippet`() {
        val snippet = created()
        asset.failWith = "connection refused"

        val result = service.delete(owner, snippet.id)

        assertIs<DeleteSnippetResult.Deleted>(result)
        assertEquals(false, repository.existsById(snippet.id))
        asset.failWith = null
        assertEquals(1, asset.count())
    }

    @Test
    fun `si falla Permisos igual se borra el snippet`() {
        val snippet = created()
        permissions.failWith = "connection refused"

        val result = service.delete(owner, snippet.id)

        assertIs<DeleteSnippetResult.Deleted>(result)
        assertEquals(false, repository.existsById(snippet.id))
    }

    private val invitado = "auth0|beto"

    @Test
    fun `el dueno comparte y Permisos queda con un acceso READ`() {
        val created = assertIs<CreateSnippetResult.Created>(service.create(owner, saludo))

        val result = service.share(owner, created.snippet.id, invitado)

        assertIs<ShareSnippetResult.Shared>(result)
        assertEquals("READ", permissions.rolDe(created.snippet.id, invitado))
    }

    // El dueno sigue siendo dueno: compartir no le cambia el rol a nadie
    @Test
    fun `compartir no toca el rol del dueno`() {
        val created = assertIs<CreateSnippetResult.Created>(service.create(owner, saludo))

        service.share(owner, created.snippet.id, invitado)

        assertEquals("OWNER", permissions.rolDe(created.snippet.id, owner))
    }

    // 404 y no 403: distinguirlos le confirmaria a cualquiera que ese id existe
    @Test
    fun `el que no es dueno no puede compartir`() {
        val created = assertIs<CreateSnippetResult.Created>(service.create(owner, saludo))

        val result = service.share("auth0|intruso", created.snippet.id, invitado)

        assertIs<ShareSnippetResult.NotFound>(result)
        assertEquals(null, permissions.rolDe(created.snippet.id, invitado))
    }

    @Test
    fun `un snippet que no existe no se comparte`() {
        assertIs<ShareSnippetResult.NotFound>(service.share(owner, UUID.randomUUID(), invitado))
    }

    // La clave en Permisos es (snippet, usuario), asi que repetir no agrega una fila
    @Test
    fun `compartir dos veces con el mismo usuario no falla ni duplica`() {
        val created = assertIs<CreateSnippetResult.Created>(service.create(owner, saludo))

        service.share(owner, created.snippet.id, invitado)
        val segunda = service.share(owner, created.snippet.id, invitado)

        assertIs<ShareSnippetResult.Shared>(segunda)
        // El dueno y el invitado, nadie mas
        assertEquals(2, permissions.accesosDe(created.snippet.id))
    }

    @Test
    fun `si Permisos falla no se comparte`() {
        val created = assertIs<CreateSnippetResult.Created>(service.create(owner, saludo))
        permissions.failWith = "connection refused"

        val result = service.share(owner, created.snippet.id, invitado)

        val fallo = assertIs<ShareSnippetResult.PermissionsUnavailable>(result)
        assertEquals("connection refused", fallo.message)
    }

    @Test
    fun `sin userId no se comparte`() {
        val created = assertIs<CreateSnippetResult.Created>(service.create(owner, saludo))

        assertIs<ShareSnippetResult.BlankTargetUser>(service.share(owner, created.snippet.id, "   "))
    }

    @Test
    fun `alguien con quien se compartio ve el snippet y su contenido`() {
        val snippet = created()
        service.share(owner, snippet.id, invitado)

        val result = service.find(invitado, snippet.id)

        val found = assertIs<FindSnippetResult.Found>(result)
        assertEquals("Saludo", found.snippet.name)
        assertEquals("println(1);", found.content)
    }

    @Test
    fun `alguien sin acceso recibe NotFound aunque el snippet exista`() {
        val snippet = created()

        assertIs<FindSnippetResult.NotFound>(service.find("auth0|intruso", snippet.id))
    }

    // Ver lo propio no puede depender de Permisos: al dueño se lo reconoce por la columna
    @Test
    fun `el dueno ve su snippet aunque Permisos este caido`() {
        val snippet = created()
        permissions.failWith = "connection refused"

        assertIs<FindSnippetResult.Found>(service.find(owner, snippet.id))
    }

    @Test
    fun `si Permisos esta caido no se sabe si alguien mas puede leerlo`() {
        val snippet = created()
        permissions.failWith = "connection refused"

        val result = service.find(invitado, snippet.id)

        assertEquals("connection refused", assertIs<FindSnippetResult.AccessUnknown>(result).message)
    }

    // Leer no es modificar: actualizar, compartir y borrar siguen siendo solo del dueño
    @Test
    fun `alguien con quien se compartio no puede actualizar, compartir ni borrar`() {
        val snippet = created()
        service.share(owner, snippet.id, invitado)

        assertIs<UpdateSnippetResult.NotFound>(service.update(invitado, snippet.id, despedida))
        assertIs<ShareSnippetResult.NotFound>(service.share(invitado, snippet.id, "auth0|otro"))
        assertIs<DeleteSnippetResult.NotFound>(service.delete(invitado, snippet.id))
        assertSigueIgual(snippet.id)
    }
}

package snippetsearcher.snippets.snippet

import org.junit.jupiter.api.Test
import snippetsearcher.snippets.user.FakeUserDirectory
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * El caché de los nombres, sin Spring: se construye con el directorio falso y un TTL corto.
 *
 * Es un test unitario y no un `@SpringBootTest` porque lo que se prueba —a quién se le pregunta y
 * cuántas veces— no necesita ni base ni servidor, y con el TTL por property no se podría probar el
 * vencimiento sin esperar minutos.
 */
class SnippetAuthorsTest {
    private val directory = FakeUserDirectory()

    private fun authors(ttl: Duration = Duration.ofMinutes(5)) = SnippetAuthors(directory, ttl, MAX_SIZE)

    @Test
    fun `pedir dos veces lo mismo consulta una sola vez`() {
        directory.names = mapOf(ANA to "Ana Pérez")
        val authors = authors()

        assertEquals(mapOf(ANA to "Ana Pérez"), authors.namesOf(listOf(ANA)))
        assertEquals(mapOf(ANA to "Ana Pérez"), authors.namesOf(listOf(ANA)))

        assertEquals(1, directory.namesOfCalls)
    }

    // Lo que importa no es solo cuántas veces se pregunta, sino por quiénes
    @Test
    fun `solo se le piden al directorio los que no estan en el cache`() {
        directory.names = mapOf(ANA to "Ana Pérez", BETO to "Beto")
        val authors = authors()
        authors.namesOf(listOf(ANA))

        authors.namesOf(listOf(ANA, BETO))

        assertEquals(2, directory.namesOfCalls)
        assertEquals(setOf(BETO), directory.lastNamesOf?.toSet())
    }

    @Test
    fun `pasado el TTL se vuelve a consultar`() {
        directory.names = mapOf(ANA to "Ana Pérez")
        val authors = authors(ttl = TTL_CORTO)
        authors.namesOf(listOf(ANA))

        esperar {
            authors.namesOf(listOf(ANA))
            directory.namesOfCalls > 1
        }

        assertTrue(directory.namesOfCalls > 1, "el nombre no se volvió a consultar después del TTL")
    }

    @Test
    fun `una pagina de varios snippets del mismo dueno es una sola consulta`() {
        directory.names = mapOf(ANA to "Ana Pérez")
        val authors = authors()

        val nombres = authors.namesOf(List(100) { ANA })

        assertEquals(1, directory.namesOfCalls)
        assertEquals(setOf(ANA), directory.lastNamesOf?.toSet())
        assertEquals("Ana Pérez", nombres.getValue(ANA))
    }

    /**
     * Si el directorio no resolvió el nombre, el `sub` **no** se cachea.
     *
     * Cachearlo sería peor que no tener caché: una caída de Auth0 de un segundo dejaría a todos los
     * snippets mostrando `auth0|...` por lo que dure el TTL, sin forma de recuperarse hasta que
     * venza. Así, en cuanto Auth0 vuelve, el nombre aparece en el pedido siguiente.
     */
    @Test
    fun `el sub que se muestra cuando el directorio falla no se cachea`() {
        directory.namesFail = true
        val authors = authors()
        assertEquals(mapOf(ANA to ANA), authors.namesOf(listOf(ANA)))

        directory.namesFail = false
        directory.names = mapOf(ANA to "Ana Pérez")

        assertEquals(mapOf(ANA to "Ana Pérez"), authors.namesOf(listOf(ANA)))
        assertEquals(2, directory.namesOfCalls)
    }

    @Test
    fun `sin ids no le pregunta nada al directorio`() {
        assertEquals(emptyMap(), authors().namesOf(emptyList()))

        assertEquals(0, directory.namesOfCalls)
    }

    private fun esperar(condicion: () -> Boolean) {
        val limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < limite) {
            if (condicion()) return
            Thread.sleep(20)
        }
    }

    private companion object {
        const val ANA = "auth0|ana"
        const val BETO = "auth0|beto"
        const val MAX_SIZE = 100L
        val TTL_CORTO: Duration = Duration.ofMillis(100)
    }
}

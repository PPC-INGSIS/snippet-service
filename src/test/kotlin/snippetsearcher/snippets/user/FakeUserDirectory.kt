package snippetsearcher.snippets.user

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

// Reemplazo de Auth0 para los tests: devuelve lo que le carguemos en result y anota con qué lo llamaron
class FakeUserDirectory : UserDirectory {
    var result = UserPage(emptyList(), 0)
    var lastSearch: Search? = null

    override fun search(
        name: String,
        page: Int,
        pageSize: Int,
    ): UserPage {
        lastSearch = Search(name, page, pageSize)
        return result
    }

    /** Los nombres por id. Un id que no esté acá queda sin nombre, como un usuario que ya no está en Auth0. */
    var names: Map<String, String> = emptyMap()

    /**
     * Si es true, namesOf no devuelve ningún nombre.
     *
     * Es lo que hace el directorio de verdad cuando Auth0 falla: se come el error, lo loguea y
     * devuelve lo que pudo, que puede ser nada. No tira, porque un nombre que falta no puede
     * tirar abajo el listado.
     */
    var namesFail = false

    // Con qué ids se pidieron los nombres, para probar que se piden de una vez y sin repetir
    var lastNamesOf: Collection<String>? = null

    // Cuántas veces se preguntó: una por página, no una por fila
    var namesOfCalls = 0
        private set

    override fun namesOf(userIds: Collection<String>): Map<String, String> {
        lastNamesOf = userIds
        namesOfCalls++
        if (namesFail) return emptyMap()
        return userIds.mapNotNull { id -> names[id]?.let { id to it } }.toMap()
    }

    fun clear() {
        result = UserPage(emptyList(), 0)
        lastSearch = null
        names = emptyMap()
        namesFail = false
        lastNamesOf = null
        namesOfCalls = 0
    }

    data class Search(
        val name: String,
        val page: Int,
        val pageSize: Int,
    )
}

@TestConfiguration(proxyBeanMethods = false)
class FakeUserDirectoryConfiguration {
    @Bean
    @Primary
    fun fakeUserDirectory() = FakeUserDirectory()
}

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

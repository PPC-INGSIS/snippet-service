package snippetsearcher.snippets.language

import org.junit.jupiter.api.Test
import snippetsearcher.snippets.printscript.VersionsResult
import kotlin.test.assertEquals

class LanguageCatalogTest {
    private fun language(
        name: String,
        extension: String,
        versions: VersionsResult,
    ) = object : Language {
        override val name = name
        override val extension = extension

        override fun versions() = versions
    }

    @Test
    fun `lista cada lenguaje con sus versiones`() {
        val catalog =
            LanguageCatalog(
                listOf(
                    language("printscript", "prs", VersionsResult.Found(listOf("1.0", "1.1"))),
                    language("otro", "otr", VersionsResult.Found(listOf("2.0"))),
                ),
            )

        val expected =
            LanguagesResult.Found(
                listOf(
                    AvailableLanguage("printscript", "prs", listOf("1.0", "1.1")),
                    AvailableLanguage("otro", "otr", listOf("2.0")),
                ),
            )
        assertEquals(expected, catalog.all())
    }

    @Test
    fun `falla si algun lenguaje no puede dar sus versiones`() {
        val catalog =
            LanguageCatalog(
                listOf(
                    language("printscript", "prs", VersionsResult.Found(listOf("1.0"))),
                    language("otro", "otr", VersionsResult.Failed("no respondió")),
                ),
            )

        assertEquals(LanguagesResult.Failed, catalog.all())
    }
}

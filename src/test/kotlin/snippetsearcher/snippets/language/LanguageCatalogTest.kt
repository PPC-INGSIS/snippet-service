package snippetsearcher.snippets.language

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class LanguageCatalogTest {
    private fun language(
        name: String,
        extension: String = "ext",
        versions: VersionsResult = VersionsResult.Found(emptyList()),
    ) = object : Language {
        override val name = name
        override val extension = extension

        override fun versions() = versions

        override fun validate(
            version: String,
            content: String,
        ) = ValidationResult.Checked(emptyList())

        override fun rules(kind: RuleKind) = RulesResult.Found(emptyList())

        override fun validateRules(
            kind: RuleKind,
            rules: Map<String, Any?>,
        ) = RulesValidationResult.Valid

        override fun lint(
            version: String,
            content: String,
            rules: Map<String, Any>,
        ) = LintResult.Linted(emptyList())

        override fun format(
            version: String,
            content: String,
            rules: Map<String, Any>,
        ) = FormatResult.Formatted(content)
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

    @Test
    fun `encuentra un lenguaje por su nombre`() {
        val printscript = language("printscript")
        val catalog = LanguageCatalog(listOf(language("otro"), printscript))

        assertSame(printscript, catalog.find("printscript"))
    }

    @Test
    fun `un nombre que no esta en el catalogo no encuentra nada`() {
        val catalog = LanguageCatalog(listOf(language("printscript")))

        assertNull(catalog.find("python"))
    }

    // Misma regla para buscar el lenguaje y su extensión: si no, un snippet mostraría extensión y no se podría editar
    @Test
    fun `con otras mayusculas no se encuentra el lenguaje ni su extension`() {
        val catalog = LanguageCatalog(listOf(language("printscript", "ps")))

        assertNull(catalog.find("PrintScript"))
        assertEquals("", catalog.extensionOf("PrintScript"))
    }
}

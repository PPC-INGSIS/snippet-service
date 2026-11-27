package snippetsearcher.snippets.rules

import org.junit.jupiter.api.Test
import snippetsearcher.snippets.language.Language
import snippetsearcher.snippets.language.LanguageCatalog
import snippetsearcher.snippets.language.RuleDefinition
import snippetsearcher.snippets.language.RuleKind
import snippetsearcher.snippets.language.RulesResult
import snippetsearcher.snippets.language.ValidationResult
import snippetsearcher.snippets.language.VersionsResult
import kotlin.test.assertEquals

class RuleServiceTest {
    private fun service(rules: RulesResult) =
        RuleService(
            LanguageCatalog(
                listOf(
                    object : Language {
                        override val name = "printscript"
                        override val extension = "ps"

                        override fun versions() = VersionsResult.Found(emptyList())

                        override fun validate(
                            version: String,
                            content: String,
                        ) = ValidationResult.Checked(emptyList())

                        override fun rules(kind: RuleKind) = rules
                    },
                ),
            ),
        )

    @Test
    fun `devuelve las reglas deshabilitadas, con su default y en el orden del lenguaje`() {
        val rules =
            RulesResult.Found(
                listOf(
                    RuleDefinition("if-indent", "Espacios de indentación dentro de un if", "1.1", 4),
                    RuleDefinition(
                        "line-break-after-statement",
                        "Salto de línea después de cada sentencia",
                        "1.0",
                        null,
                    ),
                ),
            )

        val expected =
            UserRulesResult.Found(
                listOf(
                    UserRule("if-indent", "Espacios de indentación dentro de un if", "1.1", false, 4),
                    UserRule(
                        "line-break-after-statement",
                        "Salto de línea después de cada sentencia",
                        "1.0",
                        false,
                        null,
                    ),
                ),
            )
        assertEquals(expected, service(rules).rules(RuleKind.FORMAT))
    }

    @Test
    fun `falla si el lenguaje no pudo dar sus reglas`() {
        val result = service(RulesResult.Failed("PrintScript no respondió")).rules(RuleKind.LINT)

        assertEquals(UserRulesResult.Failed, result)
    }
}

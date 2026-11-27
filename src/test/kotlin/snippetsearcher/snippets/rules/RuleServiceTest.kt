package snippetsearcher.snippets.rules

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import snippetsearcher.snippets.TestcontainersConfiguration
import snippetsearcher.snippets.language.RuleDefinition
import snippetsearcher.snippets.language.RuleKind
import snippetsearcher.snippets.language.RulesResult
import snippetsearcher.snippets.printscript.FakePrintScriptClient
import snippetsearcher.snippets.printscript.FakePrintScriptConfiguration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Mismos @Import que RuleControllerTest: Spring reusa ese contexto en vez de levantar otro
@Import(TestcontainersConfiguration::class, FakePrintScriptConfiguration::class)
@SpringBootTest
class RuleServiceTest(
    @Autowired private val service: RuleService,
    @Autowired private val savedRules: SavedRuleRepository,
    @Autowired private val printScript: FakePrintScriptClient,
) {
    private val ana = "auth0|ana-${UUID.randomUUID()}"

    private val ifIndent = RuleDefinition("if-indent", "Espacios de indentación dentro de un if", "1.1", 4)
    private val lineBreak =
        RuleDefinition("line-break-after-statement", "Salto de línea después de cada sentencia", "1.0", null)

    @BeforeEach
    fun limpiarFake() {
        printScript.clear()
        printScript.rulesResult = RulesResult.Found(listOf(ifIndent, lineBreak))
    }

    @Test
    fun `combina lo guardado con el catalogo, en el orden del lenguaje`() {
        service.replace(ana, RuleKind.FORMAT, listOf(UserRuleData("line-break-after-statement", true, null)))

        val expected =
            UserRulesResult.Found(
                listOf(
                    UserRule("if-indent", "Espacios de indentación dentro de un if", "1.1", false, 4),
                    UserRule(
                        "line-break-after-statement",
                        "Salto de línea después de cada sentencia",
                        "1.0",
                        true,
                        null,
                    ),
                ),
            )
        assertEquals(expected, service.rules(ana, RuleKind.FORMAT))
    }

    @Test
    fun `una regla guardada que ya no esta en el catalogo se ignora`() {
        savedRules.replace(
            ana,
            LANGUAGE,
            RuleKind.FORMAT,
            listOf(SavedRule(SavedRuleId(ana, LANGUAGE, RuleKind.FORMAT, "regla-vieja"), true, 1)),
        )

        val found = service.rules(ana, RuleKind.FORMAT) as UserRulesResult.Found

        assertEquals(listOf("if-indent", "line-break-after-statement"), found.rules.map { it.id })
    }

    @Test
    fun `falla si el lenguaje no pudo dar sus reglas`() {
        printScript.rulesResult = RulesResult.Failed("PrintScript no respondió")

        assertEquals(UserRulesResult.Failed, service.rules(ana, RuleKind.LINT))
    }

    @Test
    fun `si el catalogo falla no se valida ni se guarda nada`() {
        printScript.rulesResult = RulesResult.Failed("PrintScript no respondió")

        val result = service.replace(ana, RuleKind.FORMAT, listOf(UserRuleData("if-indent", true, 2)))

        assertEquals(ReplaceRulesResult.Failed, result)
        assertNull(printScript.lastValidatedRules)
        assertTrue(savedRules.find(ana, LANGUAGE, RuleKind.FORMAT).isEmpty())
    }

    @Test
    fun `a validar va el value de cada regla y, si no tiene, si esta activa`() {
        service.replace(
            ana,
            RuleKind.FORMAT,
            listOf(
                UserRuleData("if-indent", false, 2),
                UserRuleData("line-break-after-statement", true, null),
            ),
        )

        assertEquals(mapOf("if-indent" to 2, "line-break-after-statement" to true), printScript.lastValidatedRules)
    }

    companion object {
        private const val LANGUAGE = "printscript"
    }
}

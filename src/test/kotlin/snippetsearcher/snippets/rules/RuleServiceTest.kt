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
    private val beto = "auth0|beto-${UUID.randomUUID()}"

    private val ifIndent = RuleDefinition("if-indent", "Espacios de indentación dentro de un if", "1.1", 4)
    private val lineBreak =
        RuleDefinition("line-break-after-statement", "Salto de línea después de cada sentencia", "1.0", null)

    @BeforeEach
    fun limpiarFake() {
        printScript.clear()
        printScript.rulesResult = RulesResult.Found(listOf(ifIndent, lineBreak))
    }

    private fun save(
        ownerId: String,
        kind: RuleKind,
        vararg rules: UserRuleData,
    ) = savedRules.replace(
        ownerId,
        LANGUAGE,
        kind,
        rules.map { SavedRule(SavedRuleId(ownerId, LANGUAGE, kind, it.id), it.active, it.value) },
    )

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

    @Test
    fun `las reglas activas son solo las que estan habilitadas`() {
        save(
            ana,
            RuleKind.FORMAT,
            UserRuleData("if-indent", true, 2),
            UserRuleData("space-before-colon", false, "never"),
        )

        assertEquals(mapOf("if-indent" to 2), service.activeRules(ana, LANGUAGE, RuleKind.FORMAT))
    }

    @Test
    fun `un flag activo sale como true`() {
        save(ana, RuleKind.FORMAT, UserRuleData("line-break-after-statement", true, null))

        assertEquals(mapOf("line-break-after-statement" to true), service.activeRules(ana, LANGUAGE, RuleKind.FORMAT))
    }

    @Test
    fun `una regla activa con valor sale con su valor, sea texto o numero`() {
        save(
            ana,
            RuleKind.FORMAT,
            UserRuleData("if-indent", true, 2),
            UserRuleData("space-before-colon", true, "never"),
        )

        assertEquals(
            mapOf("if-indent" to 2, "space-before-colon" to "never"),
            service.activeRules(ana, LANGUAGE, RuleKind.FORMAT),
        )
    }

    @Test
    fun `sin reglas guardadas no hay reglas activas`() {
        assertEquals(emptyMap(), service.activeRules(ana, LANGUAGE, RuleKind.LINT))
    }

    @Test
    fun `las reglas activas no incluyen las de otro usuario ni las de otro kind`() {
        save(ana, RuleKind.FORMAT, UserRuleData("if-indent", true, 2))
        save(ana, RuleKind.LINT, UserRuleData("max-length", true, 80))
        save(beto, RuleKind.FORMAT, UserRuleData("if-indent", true, 8))

        assertEquals(mapOf("if-indent" to 2), service.activeRules(ana, LANGUAGE, RuleKind.FORMAT))
    }

    companion object {
        private const val LANGUAGE = "printscript"
    }
}

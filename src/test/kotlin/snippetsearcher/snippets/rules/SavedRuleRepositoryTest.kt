package snippetsearcher.snippets.rules

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import snippetsearcher.snippets.TestcontainersConfiguration
import snippetsearcher.snippets.language.RuleKind
import java.util.UUID
import kotlin.test.assertEquals

@Import(TestcontainersConfiguration::class)
@SpringBootTest
class SavedRuleRepositoryTest(
    @Autowired private val repository: SavedRuleRepository,
) {
    private val ana = "auth0|ana-${UUID.randomUUID()}"
    private val beto = "auth0|beto-${UUID.randomUUID()}"

    private fun rule(
        ownerId: String,
        kind: RuleKind,
        ruleId: String,
        active: Boolean,
        value: Any?,
    ) = SavedRule(SavedRuleId(ownerId, LANGUAGE, kind, ruleId), active, value)

    private fun valuesOf(rules: List<SavedRule>) = rules.associate { it.id.ruleId to (it.active to it.value) }

    @Test
    fun `guarda y lee un numero, un texto y un flag sin valor`() {
        repository.replace(
            ana,
            LANGUAGE,
            RuleKind.FORMAT,
            listOf(
                rule(ana, RuleKind.FORMAT, "if-indent", true, 2),
                rule(ana, RuleKind.FORMAT, "space-before-colon", false, "never"),
                rule(ana, RuleKind.FORMAT, "line-break-after-statement", true, null),
            ),
        )

        val expected =
            mapOf(
                "if-indent" to (true to 2),
                "space-before-colon" to (false to "never"),
                "line-break-after-statement" to (true to null),
            )
        assertEquals(expected, valuesOf(repository.find(ana, LANGUAGE, RuleKind.FORMAT)))
    }

    @Test
    fun `reemplazar dos veces las mismas reglas no choca con la clave primaria`() {
        val rules = listOf(rule(ana, RuleKind.FORMAT, "if-indent", true, 2))

        repository.replace(ana, LANGUAGE, RuleKind.FORMAT, rules)
        repository.replace(ana, LANGUAGE, RuleKind.FORMAT, rules)

        assertEquals(mapOf("if-indent" to (true to 2)), valuesOf(repository.find(ana, LANGUAGE, RuleKind.FORMAT)))
    }

    @Test
    fun `reemplazar borra solo las de ese usuario y ese kind`() {
        repository.replace(ana, LANGUAGE, RuleKind.FORMAT, listOf(rule(ana, RuleKind.FORMAT, "if-indent", true, 2)))
        repository.replace(ana, LANGUAGE, RuleKind.LINT, listOf(rule(ana, RuleKind.LINT, "max-length", true, 80)))
        repository.replace(beto, LANGUAGE, RuleKind.FORMAT, listOf(rule(beto, RuleKind.FORMAT, "if-indent", true, 8)))

        repository.replace(ana, LANGUAGE, RuleKind.FORMAT, emptyList())

        assertEquals(emptyMap(), valuesOf(repository.find(ana, LANGUAGE, RuleKind.FORMAT)))
        assertEquals(mapOf("max-length" to (true to 80)), valuesOf(repository.find(ana, LANGUAGE, RuleKind.LINT)))
        assertEquals(mapOf("if-indent" to (true to 8)), valuesOf(repository.find(beto, LANGUAGE, RuleKind.FORMAT)))
    }

    companion object {
        private const val LANGUAGE = "printscript"
    }
}

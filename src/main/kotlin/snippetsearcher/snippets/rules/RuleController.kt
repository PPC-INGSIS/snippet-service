package snippetsearcher.snippets.rules

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import snippetsearcher.snippets.language.RuleKind

@RestController
@RequestMapping("/rules")
class RuleController(
    private val service: RuleService,
) {
    @GetMapping("/format")
    fun format(): ResponseEntity<out Any> = rules(RuleKind.FORMAT)

    @GetMapping("/lint")
    fun lint(): ResponseEntity<out Any> = rules(RuleKind.LINT)

    private fun rules(kind: RuleKind): ResponseEntity<out Any> =
        when (val result = service.rules(kind)) {
            is UserRulesResult.Found ->
                ResponseEntity.ok(
                    result.rules.map { UserRuleResponse(it.id, it.description, it.since, it.active, it.value) },
                )

            // 502: el pedido estaba bien, falló el servicio que sabe las reglas
            UserRulesResult.Failed -> {
                val problem =
                    ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "No se pudieron obtener las reglas")
                ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(problem)
            }
        }
}

// La forma que espera la UI (getFormatRules y getLintingRules en printscript-ui), más el since de cada regla
data class UserRuleResponse(
    val id: String,
    val name: String,
    val since: String,
    val isActive: Boolean,
    val value: Any?,
)

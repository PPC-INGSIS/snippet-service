package snippetsearcher.snippets.rules

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import snippetsearcher.snippets.language.RuleKind

@RestController
@RequestMapping("/rules")
class RuleController(
    private val service: RuleService,
) {
    @GetMapping("/format")
    fun format(
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<out Any> = rules(jwt, RuleKind.FORMAT)

    @GetMapping("/lint")
    fun lint(
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<out Any> = rules(jwt, RuleKind.LINT)

    @PutMapping("/format")
    fun replaceFormat(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestBody request: List<UserRuleRequest>,
    ): ResponseEntity<out Any> = replace(jwt, RuleKind.FORMAT, request)

    @PutMapping("/lint")
    fun replaceLint(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestBody request: List<UserRuleRequest>,
    ): ResponseEntity<out Any> = replace(jwt, RuleKind.LINT, request)

    private fun rules(
        jwt: Jwt,
        kind: RuleKind,
    ): ResponseEntity<out Any> {
        val ownerId = jwt.subject ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        return when (val result = service.rules(ownerId, kind)) {
            is UserRulesResult.Found -> ResponseEntity.ok(result.rules.map { it.toResponse() })
            // 502: el pedido estaba bien, falló el servicio que sabe las reglas
            UserRulesResult.Failed -> badGateway("No se pudieron obtener las reglas")
        }
    }

    private fun replace(
        jwt: Jwt,
        kind: RuleKind,
        request: List<UserRuleRequest>,
    ): ResponseEntity<out Any> {
        val ownerId = jwt.subject ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        return when (val result = service.replace(ownerId, kind, request.map { it.toData() })) {
            is ReplaceRulesResult.Replaced -> ResponseEntity.ok(result.rules.map { it.toResponse() })

            // rule dice cuál de las reglas está mal, para que la UI pueda señalarla
            is ReplaceRulesResult.Invalid -> {
                val problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, result.message)
                result.rule?.let { problem.setProperty("rule", it) }
                ResponseEntity.badRequest().body(problem)
            }

            ReplaceRulesResult.Failed -> badGateway("No se pudieron guardar las reglas")
        }
    }

    private fun badGateway(detail: String): ResponseEntity<out Any> =
        ResponseEntity
            .status(HttpStatus.BAD_GATEWAY)
            .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, detail))
}

// La forma que espera la UI (getFormatRules, getLintingRules, modifyFormatRule y modifyLintingRule en
// printscript-ui), más el since de cada regla
data class UserRuleResponse(
    val id: String,
    val name: String,
    val since: String,
    val isActive: Boolean,
    val value: Any?,
)

// Lo que manda la UI en modifyFormatRule y modifyLintingRule. Manda la regla entera: name y since se ignoran
data class UserRuleRequest(
    val id: String,
    val isActive: Boolean,
    val value: Any? = null,
)

private fun UserRule.toResponse() = UserRuleResponse(id, description, since, active, value)

private fun UserRuleRequest.toData() = UserRuleData(id, isActive, value)

package snippetsearcher.snippets.testcase

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

@RestController
@RequestMapping("/snippets/{snippetId}/tests")
class TestCaseController(
    private val service: TestCaseService,
    private val runner: TestRunService,
    private val ledger: TestRunLedger,
) {
    @GetMapping
    fun list(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable snippetId: UUID,
    ): ResponseEntity<out Any> {
        val ownerId = jwt.subject ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        return when (val result = service.list(ownerId, snippetId)) {
            is ListTestCasesResult.Listed -> ResponseEntity.ok(conResultados(snippetId, result.testCases))
            ListTestCasesResult.SnippetNotFound -> ResponseEntity.notFound().build()
            // 502: el pedido estaba bien, falló Permisos
            is ListTestCasesResult.AccessUnknown ->
                ResponseEntity
                    .status(HttpStatus.BAD_GATEWAY)
                    .body(
                        ProblemDetail.forStatusAndDetail(
                            HttpStatus.BAD_GATEWAY,
                            "No se pudo verificar el acceso al snippet",
                        ),
                    )
        }
    }

    /**
     * Corre un test contra el snippet y dice si pasa.
     *
     * El test viaja en el body y no se busca por id porque la UI corre el formulario tal como
     * está, que puede no estar guardado: en `TabPanel.tsx` el botón Test manda el test actual y
     * el de Save es otro. Un test guardado se corre mandando sus datos, igual que uno nuevo.
     */
    @PostMapping("/run")
    fun run(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable snippetId: UUID,
        @RequestBody request: RunTestRequest,
    ): ResponseEntity<out Any> {
        val userId = jwt.subject ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        return when (val result = runner.run(userId, snippetId, request.toRun())) {
            is RunTestResult.Ran -> ResponseEntity.ok(result.toResponse())

            RunTestResult.SnippetNotFound -> ResponseEntity.notFound().build()

            // 400: el pedido estaba mal (la versión del snippet no existe, el código es muy grande)
            is RunTestResult.Rejected ->
                ResponseEntity.badRequest().body(
                    ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, result.message),
                )

            // 500 y no 502: el asset service contestó bien, lo inconsistente son nuestros datos.
            // Mismo criterio que GET /snippets/{id}, que es el mismo caso
            RunTestResult.ContentMissing -> ResponseEntity.internalServerError().build()

            // 502 y no 500: el pedido estaba bien, falló un servicio nuestro
            is RunTestResult.Unavailable ->
                ResponseEntity
                    .status(HttpStatus.BAD_GATEWAY)
                    .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, "No se pudo correr el test"))
        }
    }

    @PostMapping
    fun create(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable snippetId: UUID,
        @RequestBody request: TestCaseRequest,
    ): ResponseEntity<out Any> {
        val ownerId = jwt.subject ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        return service.create(ownerId, snippetId, request.toData()).toResponse(HttpStatus.CREATED)
    }

    @PutMapping("/{testCaseId}")
    fun update(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable snippetId: UUID,
        @PathVariable testCaseId: UUID,
        @RequestBody request: TestCaseRequest,
    ): ResponseEntity<out Any> {
        val ownerId = jwt.subject ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        return service.update(ownerId, snippetId, testCaseId, request.toData()).toResponse(HttpStatus.OK)
    }

    /**
     * Los tests con el resultado de la última corrida.
     *
     * Una consulta para toda la lista y no una por test. `pending` es del snippet y no del test: el
     * trabajo en segundo plano es por snippet, así que mientras está encolado o corriendo, **todos**
     * sus resultados pueden quedar viejos de un momento a otro.
     */
    private fun conResultados(
        snippetId: UUID,
        tests: List<TestCase>,
    ): List<TestCaseResponse> {
        val resultados = ledger.resultsOf(tests.map { it.id })
        val pendiente = ledger.pending(snippetId)
        return tests.map { it.toResponse(resultados[it.id], pendiente) }
    }

    @DeleteMapping("/{testCaseId}")
    fun delete(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable snippetId: UUID,
        @PathVariable testCaseId: UUID,
    ): ResponseEntity<Void> {
        val ownerId = jwt.subject ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        return when (service.delete(ownerId, snippetId, testCaseId)) {
            DeleteTestCaseResult.Deleted -> ResponseEntity.noContent().build()
            DeleteTestCaseResult.NotFound -> ResponseEntity.notFound().build()
        }
    }
}

private fun SaveTestCaseResult.toResponse(success: HttpStatus): ResponseEntity<out Any> =
    when (this) {
        is SaveTestCaseResult.Saved -> ResponseEntity.status(success).body(testCase.toResponse())
        SaveTestCaseResult.NotFound -> ResponseEntity.notFound().build()
        is SaveTestCaseResult.Invalid ->
            ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, message))
    }

// Los nombres son los de la UI (TestCase en printscript-ui): input y output, en singular aunque sean listas.
// Sin listas, el test no pide inputs o no espera outputs.
data class TestCaseRequest(
    val name: String,
    val input: List<String> = emptyList(),
    val output: List<String> = emptyList(),
)

/**
 * El test que se quiere correr. `name` se acepta y se ignora: la UI manda el test entero y no
 * vale la pena hacerla cambiar el body para correrlo.
 */
data class RunTestRequest(
    val name: String? = null,
    val input: List<String> = emptyList(),
    val output: List<String> = emptyList(),
)

// `outputs` es lo que imprimió y `expected` lo que el test esperaba: sin los dos, un test que
// falla no se puede comparar. `error` solo viene si el snippet se cortó antes de terminar.
data class RunTestResponse(
    val passed: Boolean,
    val outputs: List<String>,
    val expected: List<String>,
    val error: String? = null,
)

data class TestCaseResponse(
    val id: UUID,
    val name: String,
    val input: List<String>,
    val output: List<String>,
    // null si nunca corrió en segundo plano. Correrlo a mano con /tests/run no guarda nada: ese
    // resultado es de un test que puede ni estar guardado
    val result: TestResultResponse?,
    // true mientras los tests de este snippet están encolados o corriendo: lo que dice `result`
    // puede quedar viejo en cualquier momento. Es el "still pending" de la US
    val pending: Boolean,
)

// La última corrida de un test. `expected` no viaja: es el `output` del test, que ya está al lado
data class TestResultResponse(
    val passed: Boolean,
    val outputs: List<String>,
    val error: String?,
    val ranAt: Instant,
)

private fun TestCaseRequest.toData() = TestCaseData(name, input, output)

private fun RunTestRequest.toRun() = TestRun(input, output)

private fun RunTestResult.Ran.toResponse() = RunTestResponse(passed, outputs, expected, error)

private fun TestCase.toResponse(
    result: TestCaseResult? = null,
    pending: Boolean = false,
) = TestCaseResponse(
    id,
    name,
    inputs,
    outputs,
    result?.let { TestResultResponse(it.passed, it.outputs, it.error, it.ranAt) },
    pending,
)

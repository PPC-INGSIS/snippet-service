package snippetsearcher.snippets.printscript

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClientException
import org.springframework.web.client.body
import snippetsearcher.snippets.http.timedRestClient
import snippetsearcher.snippets.language.RuleDefinition
import snippetsearcher.snippets.language.RuleKind
import snippetsearcher.snippets.language.RulesResult
import snippetsearcher.snippets.language.ValidationError
import snippetsearcher.snippets.language.ValidationResult
import snippetsearcher.snippets.language.VersionsResult
import snippetsearcher.snippets.security.AccessToken
import java.nio.charset.StandardCharsets
import java.time.Duration

@Component
class HttpPrintScriptClient(
    @Value("\${printscript.url}") baseUrl: String,
    @Value("\${printscript.connect-timeout}") connectTimeout: Duration,
    @Value("\${printscript.read-timeout}") readTimeout: Duration,
    accessToken: AccessToken,
) : PrintScriptClient {
    private val log = LoggerFactory.getLogger(javaClass)

    // PrintScript valida el token igual que nosotros, así que hay que reenviar el del usuario
    private val restClient = timedRestClient(baseUrl, connectTimeout, readTimeout, accessToken)

    override fun validate(
        version: String,
        content: String,
    ): ValidationResult =
        try {
            val response =
                restClient
                    .post()
                    .uri("/validate?version={version}", version)
                    .contentType(MediaType("text", "plain", StandardCharsets.UTF_8))
                    .body(content)
                    .retrieve()
                    .body<ValidationResponse>()

            ValidationResult.Checked(response?.errors.orEmpty())
        } catch (exception: HttpClientErrorException) {
            // RestClient convierte los 4xx en excepciones; los rechazos de PrintScript vuelven a ser resultados
            if (exception.statusCode.value() !in REJECTED_STATUSES) {
                log.warn(VALIDATE_FAILED_LOG, exception)
                throw exception
            }
            ValidationResult.Rejected(rejectionDetail(exception) ?: DEFAULT_REJECTION)
        } catch (exception: RestClientException) {
            // Un 5xx, un timeout o PrintScript caído. Se loguea acá y no se deja para Tomcat: cuando Tomcat
            // la loguea, los filtros ya limpiaron el MDC y la línea sale sin trace.id ni user.id
            log.warn(VALIDATE_FAILED_LOG, exception)
            throw exception
        }

    /**
     * A diferencia de validar, un fallo de PrintScript vuelve como resultado y no como excepción:
     * correr un test no puede tirar abajo el pedido, el usuario tiene que ver "no se pudo correr".
     */
    override fun execute(
        version: String,
        content: String,
        inputs: List<String>,
    ): ExecutionResult =
        try {
            val response =
                restClient
                    .post()
                    .uri("/execute")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(ExecutionRequestBody(content, version, inputs))
                    .retrieve()
                    .body<ExecutionResponse>()

            response?.let { ExecutionResult.Ran(it.outputs, it.error?.message) } ?: run {
                log.warn("PrintScript respondió POST /execute sin body")
                ExecutionResult.Failed(EXECUTE_FAILED)
            }
        } catch (exception: HttpClientErrorException) {
            if (exception.statusCode.value() in REJECTED_STATUSES) {
                ExecutionResult.Rejected(rejectionDetail(exception) ?: DEFAULT_REJECTION)
            } else {
                log.warn(EXECUTE_FAILED_LOG, exception)
                ExecutionResult.Failed(EXECUTE_FAILED)
            }
        } catch (exception: RestClientException) {
            // El detalle va al log y no al resultado: el mensaje de la excepción trae la URL y el puerto internos
            log.warn(EXECUTE_FAILED_LOG, exception)
            ExecutionResult.Failed(EXECUTE_FAILED)
        }

    override fun versions(): VersionsResult =
        try {
            val versions =
                restClient
                    .get()
                    .uri("/versions")
                    .retrieve()
                    .body<VersionsResponse>()
                    ?.versions
                    ?.map { it.id }

            if (versions != null) {
                VersionsResult.Found(versions)
            } else {
                log.warn("PrintScript respondió GET /versions sin body")
                VersionsResult.Failed(VERSIONS_FAILED)
            }
        } catch (exception: RestClientException) {
            // El detalle va al log y no al resultado: el mensaje de la excepción trae la URL y el puerto internos
            log.warn("No se pudieron obtener las versiones de PrintScript", exception)
            VersionsResult.Failed(VERSIONS_FAILED)
        }

    // Sin version: PrintScript devuelve las de la última versión
    override fun rules(kind: RuleKind): RulesResult {
        val segment =
            when (kind) {
                RuleKind.FORMAT -> "format"
                RuleKind.LINT -> "lint"
            }
        return try {
            val rules =
                restClient
                    .get()
                    .uri("/rules/{kind}", segment)
                    .retrieve()
                    .body<RulesResponse>()
                    ?.rules
                    ?.map { RuleDefinition(it.id, it.description, it.since, it.schema.default) }

            if (rules != null) {
                RulesResult.Found(rules)
            } else {
                log.warn("PrintScript respondió GET /rules/{} sin body", segment)
                RulesResult.Failed(RULES_FAILED)
            }
        } catch (exception: RestClientException) {
            log.warn("No se pudieron obtener las reglas de PrintScript", exception)
            RulesResult.Failed(RULES_FAILED)
        }
    }

    private fun rejectionDetail(exception: HttpClientErrorException): String? =
        try {
            exception
                .getResponseBodyAs(RejectedResponse::class.java)
                ?.detail
                ?.takeUnless { it.isBlank() }
        } catch (ignored: RestClientException) {
            // El body no es Problem Details (por ejemplo, HTML de un proxy)
            null
        }

    companion object {
        // Se comparan números y no constantes de HttpStatus: el 413 tiene dos (PAYLOAD_TOO_LARGE, deprecada,
        // y CONTENT_TOO_LARGE), así que el número es lo único que no es ambiguo
        private const val BAD_REQUEST = 400
        private const val CONTENT_TOO_LARGE = 413

        // 400: versión inexistente o body vacío. 413: el código supera el tamaño máximo de PrintScript
        private val REJECTED_STATUSES = setOf(BAD_REQUEST, CONTENT_TOO_LARGE)

        private const val DEFAULT_REJECTION = "PrintScript rechazó el pedido"

        private const val VERSIONS_FAILED = "PrintScript no respondió"

        private const val RULES_FAILED = "PrintScript no respondió"

        private const val VALIDATE_FAILED_LOG = "No se pudo validar el snippet en PrintScript"

        private const val EXECUTE_FAILED = "PrintScript no respondió"

        private const val EXECUTE_FAILED_LOG = "No se pudo correr el snippet en PrintScript"
    }
}

// Lo que recibe POST /execute de printscript-service
data class ExecutionRequestBody(
    val code: String,
    val version: String,
    val inputs: List<String>,
)

// Respuesta 200 de POST /execute. De SourceError solo se usa el mensaje: la línea y la columna
// no le dicen nada a quien escribió el test, que no está mirando el código
data class ExecutionResponse(
    val outputs: List<String>,
    val error: ExecutionErrorResponse? = null,
)

data class ExecutionErrorResponse(
    val message: String,
)

/**
 * Respuesta 200 de printscript-service. Los errores se deserializan directo a [ValidationError].
 *
 * PrintScript devuelve `line` y `column` en `null` cuando el código desborda el parser por tener
 * demasiados niveles de anidamiento (printscript-service#42). Con `Int` no nulo, esa respuesta no se
 * podía deserializar y el pedido terminaba en un 500 con un error de Jackson en el log, en vez del
 * 400 con el mensaje que PrintScript sí había mandado.
 */
data class ValidationResponse(
    val errors: List<ValidationError>,
)

// Respuesta de GET /versions de printscript-service. Se ignora latest: no lo usamos
data class VersionsResponse(
    val versions: List<VersionResponse>,
)

data class VersionResponse(
    val id: String,
)

// Respuesta de GET /rules/{kind} de printscript-service. Se ignoran version y el resto del schema: no los usamos
data class RulesResponse(
    val rules: List<RuleResponse>,
)

data class RuleResponse(
    val id: String,
    val description: String,
    val since: String,
    val schema: RuleSchemaResponse,
)

data class RuleSchemaResponse(
    val default: Any? = null,
)

// Respuesta 400 o 413 de printscript-service, en formato Problem Details (RFC 9457)
data class RejectedResponse(
    val detail: String? = null,
)

package snippetsearcher.snippets.printscript

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

// Reemplazo de printscript-service para los tests: responde lo que le carguemos en result y versionsResult
class FakePrintScriptClient : PrintScriptClient {
    var result: ValidationResult = ValidationResult.Checked(emptyList())
    var versionsResult: VersionsResult = VersionsResult.Found(emptyList())
    var executionResult: ExecutionResult = ExecutionResult.Ran(emptyList(), null)

    // Con qué se pidió correr, para probar que se manda el contenido del snippet y los inputs del test
    var lastInputs: List<String>? = null
    var lastContent: String? = null

    // La última versión con la que se pidió validar: para probar que se usa la que manda el usuario
    var lastVersion: String? = null

    override fun validate(
        version: String,
        content: String,
    ): ValidationResult {
        lastVersion = version
        return result
    }

    override fun execute(
        version: String,
        content: String,
        inputs: List<String>,
    ): ExecutionResult {
        lastVersion = version
        lastContent = content
        lastInputs = inputs
        return executionResult
    }

    override fun versions(): VersionsResult = versionsResult

    // Deja el fake como recién creado, para que ningún test dependa de lo que dejó el anterior
    fun clear() {
        result = ValidationResult.Checked(emptyList())
        versionsResult = VersionsResult.Found(emptyList())
        executionResult = ExecutionResult.Ran(emptyList(), null)
        lastVersion = null
        lastContent = null
        lastInputs = null
    }
}

@TestConfiguration(proxyBeanMethods = false)
class FakePrintScriptConfiguration {
    @Bean
    @Primary
    fun fakePrintScriptClient() = FakePrintScriptClient()
}

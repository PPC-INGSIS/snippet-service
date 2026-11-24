package snippetsearcher.snippets.printscript

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

// Reemplazo de printscript-service para los tests: responde lo que le carguemos en result
class FakePrintScriptClient : PrintScriptClient {
    var result: ValidationResult = ValidationResult.Checked(emptyList())

    // La última versión con la que se pidió validar: para probar que se usa la que manda el usuario
    var lastVersion: String? = null

    override fun validate(
        version: String,
        content: String,
    ): ValidationResult {
        lastVersion = version
        return result
    }

    // Deja el fake como recién creado, para que ningún test dependa de lo que dejó el anterior
    fun clear() {
        result = ValidationResult.Checked(emptyList())
        lastVersion = null
    }
}

@TestConfiguration(proxyBeanMethods = false)
class FakePrintScriptConfiguration {
    @Bean
    @Primary
    fun fakePrintScriptClient() = FakePrintScriptClient()
}

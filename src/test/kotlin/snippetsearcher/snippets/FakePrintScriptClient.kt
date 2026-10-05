package snippetsearcher.snippets

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

// Reemplazo de printscript-service para los tests: responde lo que le carguemos en errors
class FakePrintScriptClient : PrintScriptClient {
    var errors: List<ValidationError> = emptyList()

    override fun validate(
        version: String,
        content: String,
    ): List<ValidationError> = errors
}

@TestConfiguration(proxyBeanMethods = false)
class FakePrintScriptConfiguration {
    @Bean
    @Primary
    fun fakePrintScriptClient() = FakePrintScriptClient()
}

package snippetsearcher.snippets.language

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/languages")
class LanguageController(
    private val catalog: LanguageCatalog,
) {
    @GetMapping
    fun all(): ResponseEntity<out Any> =
        when (val result = catalog.all()) {
            is LanguagesResult.Found ->
                ResponseEntity.ok(result.languages.map { LanguageResponse(it.name, it.extension, it.versions) })

            // 502: el pedido estaba bien, falló el servicio que sabe las versiones
            LanguagesResult.Failed -> {
                val problem =
                    ProblemDetail.forStatusAndDetail(
                        HttpStatus.BAD_GATEWAY,
                        "No se pudieron obtener las versiones de los lenguajes",
                    )
                ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(problem)
            }
        }
}

// La forma que espera la UI (getFileTypes en printscript-ui), más las versiones de cada lenguaje
data class LanguageResponse(
    val language: String,
    val extension: String,
    val versions: List<String>,
)

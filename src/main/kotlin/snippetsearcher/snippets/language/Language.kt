package snippetsearcher.snippets.language

import snippetsearcher.snippets.printscript.VersionsResult

// Un lenguaje en el que se pueden escribir snippets. Agregar uno es agregar un @Component que implemente esto
interface Language {
    val name: String
    val extension: String

    fun versions(): VersionsResult
}

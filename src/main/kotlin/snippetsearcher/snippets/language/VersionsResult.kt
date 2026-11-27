package snippetsearcher.snippets.language

sealed interface VersionsResult {
    data class Found(
        val versions: List<String>,
    ) : VersionsResult

    // El servicio del lenguaje falló o no contestó
    data class Failed(
        val message: String,
    ) : VersionsResult
}

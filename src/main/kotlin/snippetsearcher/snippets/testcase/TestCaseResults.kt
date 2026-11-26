package snippetsearcher.snippets.testcase

sealed interface ListTestCasesResult {
    data class Listed(
        val testCases: List<TestCase>,
    ) : ListTestCasesResult

    // El snippet no existe o el usuario no lo puede leer: a propósito no se distingue
    data object SnippetNotFound : ListTestCasesResult

    // El snippet no es suyo y Permisos no contestó: no se sabe si se lo compartieron
    data class AccessUnknown(
        val message: String,
    ) : ListTestCasesResult
}

// Crear y actualizar tienen los mismos casos
sealed interface SaveTestCaseResult {
    data class Saved(
        val testCase: TestCase,
    ) : SaveTestCaseResult

    // El snippet no existe o es de otro usuario, o el test no es de ese snippet
    data object NotFound : SaveTestCaseResult

    data class Invalid(
        val message: String,
    ) : SaveTestCaseResult
}

sealed interface DeleteTestCaseResult {
    data object Deleted : DeleteTestCaseResult

    data object NotFound : DeleteTestCaseResult
}

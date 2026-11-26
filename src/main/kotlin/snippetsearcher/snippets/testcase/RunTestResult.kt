package snippetsearcher.snippets.testcase

sealed interface RunTestResult {
    /**
     * Se corrió. `passed` es si lo que imprimió coincide, en orden, con lo que el test esperaba.
     *
     * Los outputs obtenidos viajan igual cuando falla: sin ellos, el que escribió el test ve
     * "falló" y no tiene con qué comparar.
     */
    data class Ran(
        val passed: Boolean,
        val outputs: List<String>,
        val expected: List<String>,
        val error: String?,
    ) : RunTestResult

    /** El snippet no existe, o existe y el usuario no lo puede leer: a propósito no se distingue. */
    data object SnippetNotFound : RunTestResult

    /**
     * La fila existe y el asset service contestó bien, pero no tiene el contenido del snippet:
     * son nuestros datos los que quedaron inconsistentes. Aparte de `Unavailable` porque
     * reintentar no lo arregla, y por eso termina en un 500 y no en un 502.
     */
    data object ContentMissing : RunTestResult

    /**
     * No se pudo correr por algo nuestro que puede andar en un rato: el asset service o Permisos
     * no contestaron, o PrintScript no contestó. No es culpa de quien pidió y reintentar sirve.
     */
    data class Unavailable(
        val message: String,
    ) : RunTestResult

    /** PrintScript rechazó el pedido sin correr nada: la versión del snippet no existe, el código es muy grande. */
    data class Rejected(
        val message: String,
    ) : RunTestResult
}

/**
 * Un test para correr, sin nombre.
 *
 * No es `TestCaseData`: el nombre es obligatorio para guardar un test, pero para correrlo no hace
 * falta. La UI tiene dos botones separados —Save y Test— y el de Test no exige nombre, así que
 * pedirlo acá haría fallar algo que del otro lado está permitido.
 */
data class TestRun(
    val inputs: List<String>,
    val expectedOutputs: List<String>,
)

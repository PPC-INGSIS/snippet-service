package snippetsearcher.snippets.snippet

/**
 * Si un usuario puede leer un snippet: es el dueño, o se lo compartieron (OWNER o READ en Permisos).
 *
 * Público porque lo usa más de un servicio (ver el snippet, sus tests): la regla de quién puede
 * leer vive en un solo lugar, SnippetAccess.readable.
 */
sealed interface ReadAccess {
    data class Granted(
        val snippet: Snippet,
    ) : ReadAccess

    // No existe, o existe y no tiene acceso: a propósito no se distingue
    data object Denied : ReadAccess

    // El snippet existe, no es suyo, y Permisos no contestó
    data class Unknown(
        val message: String,
    ) : ReadAccess
}

package snippetsearcher.snippets.execution

import org.slf4j.LoggerFactory
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtDecoderInitializationException
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.stereotype.Component

/**
 * Valida el token que llega en el primer mensaje del WebSocket.
 *
 * Es el mismo chequeo que hace Spring Security en los pedidos HTTP —firma, `iss`, `aud`, `exp` con
 * el mismo `JwtDecoder`, y el scope— pero a mano, porque acá el token no viene en un header y el
 * filtro de Spring no lo ve. Ejecutar es leer, así que pide `read:snippets`.
 */
@Component
class ExecutionToken(
    private val decoder: JwtDecoder,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** El `sub` del usuario si el token sirve, o null si no: para el browser los dos casos son el mismo. */
    fun userOf(token: String): String? {
        val jwt =
            try {
                decoder.decode(token)
            } catch (exception: JwtException) {
                // Sin detalle hacia afuera: decirle por qué no sirve el token ayuda a adivinar tokens
                log.info("Se rechazó una conexión con un token inválido: {}", exception.message)
                return null
            } catch (exception: JwtDecoderInitializationException) {
                // El decoder se arma la primera vez que se usa, bajando el JWKS de Auth0. Si eso falla
                // no es un token inválido, pero se falla cerrado: sin poder validar, no se ejecuta nada
                log.warn("No se pudo validar el token: el decoder no se pudo inicializar", exception)
                return null
            }

        val scopes = jwt.getClaimAsString("scope")?.split(" ").orEmpty()
        if (READ_SCOPE !in scopes) {
            log.info("Se rechazó una conexión con un token sin el scope {}", READ_SCOPE)
            return null
        }

        return jwt.subject?.takeUnless { it.isBlank() }
    }

    private companion object {
        const val READ_SCOPE = "read:snippets"
    }
}

package snippetsearcher.snippets.security

import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component

// El token con el que llegó el pedido del usuario: el mismo que ya validó Spring Security.
// SecurityContextHolder guarda el contexto por hilo: fuera de un pedido (por ejemplo, en Redis) está vacío.
@Component
class UserAccessToken : AccessToken {
    override fun value(): String {
        val authentication =
            checkNotNull(SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken) {
                "No hay un usuario autenticado en este pedido: no hay token para reenviar"
            }
        return authentication.token.tokenValue
    }
}

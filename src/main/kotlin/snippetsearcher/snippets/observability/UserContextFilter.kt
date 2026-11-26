package snippetsearcher.snippets.observability

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Pone el `sub` del usuario en el MDC, para poder preguntar "qué hizo este usuario".
 *
 * Va **después** de Spring Security y no primero como `TraceContextFilter`: el `sub` sale
 * del token ya validado, y antes de la cadena de seguridad el contexto está vacío. Sin
 * `@Order`, un filtro registrado como bean corre después de la cadena de seguridad, que
 * va en -100.
 *
 * La consecuencia es que un 401 se loguea con trace id pero sin usuario, y está bien:
 * en un 401 no hay usuario que anotar.
 *
 * Va el `sub` y **nunca el token**: el token es un secreto, el `sub` es un identificador.
 */
@Component
class UserContextFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        try {
            subDelToken()?.let { MDC.put(USER_ID, it) }
            filterChain.doFilter(request, response)
        } finally {
            // Tomcat reutiliza los threads: sin esto, el pedido siguiente loguearía con
            // el usuario del anterior.
            MDC.remove(USER_ID)
        }
    }

    private fun subDelToken(): String? {
        val authentication = SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken
        return authentication?.token?.subject?.takeUnless { it.isBlank() }
    }

    companion object {
        const val USER_ID = "user.id"
    }
}

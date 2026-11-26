package snippetsearcher.snippets.observability

import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class UserContextFilterTest {
    private val filter = UserContextFilter()

    @AfterEach
    fun limpiarContexto() {
        SecurityContextHolder.clearContext()
    }

    private fun autenticarCon(sub: String?) {
        val jwt =
            Jwt
                .withTokenValue("no-importa")
                .header("alg", "none")
                .also { if (sub != null) it.subject(sub) }
                .claim("scope", "write:snippets")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)
    }

    private fun pasarPorElFiltro(cadena: FilterChain) =
        filter.doFilter(MockHttpServletRequest("POST", "/snippets"), MockHttpServletResponse(), cadena)

    @Test
    fun `el sub del token esta en el MDC mientras corre el pedido`() {
        autenticarCon("auth0|ana")
        var durante: String? = null

        pasarPorElFiltro { _, _ -> durante = MDC.get(UserContextFilter.USER_ID) }

        assertEquals("auth0|ana", durante)
    }

    // Tomcat reutiliza los threads: sin limpiar, el pedido siguiente loguearía con el
    // usuario del anterior. Este test falla si se borra el finally del filtro.
    @Test
    fun `limpia el MDC cuando termina`() {
        autenticarCon("auth0|ana")

        pasarPorElFiltro { _, _ -> }

        assertNull(MDC.get(UserContextFilter.USER_ID))
    }

    @Test
    fun `limpia el MDC aunque la cadena falle`() {
        autenticarCon("auth0|ana")

        assertFailsWith<IllegalStateException> {
            pasarPorElFiltro { _, _ -> error("algo exploto adentro") }
        }

        assertNull(MDC.get(UserContextFilter.USER_ID))
    }

    // Un 401 no tiene usuario que anotar, y eso está bien: el trace id sí va igual
    @Test
    fun `sin usuario autenticado no ensucia el MDC`() {
        var durante: String? = "sin tocar"

        pasarPorElFiltro { _, _ -> durante = MDC.get(UserContextFilter.USER_ID) }

        assertNull(durante)
    }
}

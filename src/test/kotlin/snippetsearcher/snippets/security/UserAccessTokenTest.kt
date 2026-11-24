package snippetsearcher.snippets.security

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UserAccessTokenTest {
    // El contexto es por hilo y los tests comparten hilo: si no se limpia, un test le pasa el usuario al siguiente
    @AfterEach
    fun limpiarContexto() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `devuelve el token con el que llego el pedido`() {
        val jwt =
            Jwt
                .withTokenValue("token-del-usuario")
                .header("alg", "none")
                .subject("auth0|ana")
                .build()
        SecurityContextHolder.getContext().authentication = JwtAuthenticationToken(jwt)

        assertEquals("token-del-usuario", UserAccessToken().value())
    }

    @Test
    fun `sin usuario en el pedido falla con un mensaje claro`() {
        val error = assertFailsWith<IllegalStateException> { UserAccessToken().value() }

        assertEquals("No hay un usuario autenticado en este pedido: no hay token para reenviar", error.message)
    }
}

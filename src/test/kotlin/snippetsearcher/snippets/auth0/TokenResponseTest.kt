package snippetsearcher.snippets.auth0

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse

class TokenResponseTest {
    @Test
    fun `el toString de la respuesta no muestra el token`() {
        val response = TokenResponse("token-secreto", 86400)

        assertFalse(response.toString().contains("token-secreto"))
    }
}

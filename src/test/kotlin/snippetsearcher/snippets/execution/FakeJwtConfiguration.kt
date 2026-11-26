package snippetsearcher.snippets.execution

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import java.time.Instant

/**
 * Un `JwtDecoder` de prueba, para los tests que mandan el token **dentro de un mensaje**.
 *
 * En los tests de HTTP el token se fabrica con `jwt()` y nunca se decodifica. Acá no se puede: el
 * token llega como texto en el primer mensaje del WebSocket y lo decodifica el servicio, así que
 * hace falta un decoder que acepte tokens conocidos y rechace el resto. El de verdad bajaría el
 * JWKS de Auth0.
 */
const val TOKEN_DE_ANA = "token-de-ana"
const val TOKEN_DE_BETO = "token-de-beto"
const val TOKEN_SIN_SCOPE = "token-sin-scope"
const val TOKEN_SIN_SUB = "token-sin-sub"

@TestConfiguration(proxyBeanMethods = false)
class FakeJwtConfiguration {
    @Bean
    @Primary
    fun fakeJwtDecoder(): JwtDecoder =
        JwtDecoder { token ->
            when (token) {
                TOKEN_DE_ANA -> jwt("auth0|ana", "read:snippets write:snippets")
                TOKEN_DE_BETO -> jwt("auth0|beto", "read:snippets write:snippets")
                TOKEN_SIN_SCOPE -> jwt("auth0|ana", "write:snippets")
                TOKEN_SIN_SUB -> jwt(null, "read:snippets")
                // Cualquier otro es un token que no valida, igual que uno vencido o falsificado
                else -> throw JwtException("El token no es válido")
            }
        }

    private fun jwt(
        subject: String?,
        scope: String,
    ): Jwt =
        Jwt
            .withTokenValue("fake")
            .header("alg", "none")
            .claim("scope", scope)
            .also { builder -> subject?.let { builder.subject(it) } }
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(600))
            .build()
}

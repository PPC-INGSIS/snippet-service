package snippetsearcher.snippets.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.Customizer.withDefaults
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain

/**
 * Quién puede entrar. Validar el token (firma, iss, aud, exp) lo configura application.properties;
 * acá se decide qué rutas lo exigen.
 */
@Configuration
class SecurityConfiguration {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .authorizeHttpRequests {
                it
                    // El healthcheck de Swarm pega sin token: con 401 marcaría sano al contenedor como caído
                    .requestMatchers("/actuator/health/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated()
            }.oauth2ResourceServer { it.jwt(withDefaults()) }
            // Con Bearer no hay sesión: cada pedido trae su token y cualquier réplica lo valida
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            // CSRF abusa de las cookies que el browser manda solo; el header Authorization no se manda solo
            .csrf { it.disable() }
        return http.build()
    }
}

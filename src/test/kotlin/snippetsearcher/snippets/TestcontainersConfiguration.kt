package snippetsearcher.snippets

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.GenericContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {
    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer {
        // La misma versión que el docker-compose, para testear contra lo mismo que corre
        return PostgreSQLContainer(DockerImageName.parse("postgres:18"))
    }

    @Bean
    @ServiceConnection(name = "redis")
    fun redisContainer(): GenericContainer<*> {
        // La misma versión que el docker-compose, para testear contra lo mismo que corre
        return GenericContainer<Nothing>(DockerImageName.parse("redis:7.4-alpine")).apply { addExposedPort(6379) }
    }
}

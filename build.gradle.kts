plugins {
    // Kotlin, Spring, JPA, Postgres, Flyway, ktlint, detekt, cobertura y hooks
    id("ppc.kotlin-jpa-service") version "1.0.0"
}

group = "snippetsearcher"
version = "0.0.1-SNAPSHOT"

// Va acá y no en settings: el plugin ya declara mavenCentral() en el proyecto,
// y con eso Gradle ignora los repositorios de dependencias declarados en settings
repositories {
    // La librería de Redis Streams de la cátedra. Mismas credenciales que gradle-conventions.
    maven {
        url = uri("https://maven.pkg.github.com/austral-ingsis/class-redis-streams")
        credentials {
            username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("GITHUB_ACTOR")
            password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    implementation("org.austral.ingsis:redis-streams-mvc:0.1.13") {
        // La librería publica sus dependencias con versión +: sin esto suben todo Spring Boot a la última RC
        isTransitive = false
    }
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")

    testImplementation("org.testcontainers:testcontainers")
}

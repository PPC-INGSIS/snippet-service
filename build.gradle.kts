plugins {
    // Kotlin, Spring, JPA, Postgres, Flyway, ktlint, detekt, cobertura y hooks
    id("ppc.kotlin-jpa-service") version "1.1.0"
    // Valida el token de Auth0 en cada pedido
    id("ppc.kotlin-security") version "1.1.0"
}

group = "snippetsearcher"
version = "0.0.1-SNAPSHOT"

// La versión probada en infraestructura#46 con Spring Boot 4.1.1 y Java 21
val newRelicVersion = "9.5.0"

// El agente no es una dependencia de la app: no va al classpath. Se baja en una
// configuración aparte y se copia a la imagen, porque se engancha a la JVM con
// -javaagent antes de que arranque Spring. Mismos nombres que en los otros servicios.
val newRelicAgent = configurations.create("newRelicAgent")

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

    newRelicAgent("com.newrelic.agent.java:newrelic-agent:$newRelicVersion")
    // Esta sí va al classpath, solo para leer el trace id. Sin el agente devuelve vacío,
    // así que la app corre igual en local y en los tests.
    implementation("com.newrelic.agent.java:newrelic-api:$newRelicVersion")
}

val copyNewRelicAgent =
    tasks.register<Copy>("copyNewRelicAgent") {
        description = "Deja el jar del agente en build/newrelic/newrelic.jar para el Dockerfile"
        from(newRelicAgent)
        into(layout.buildDirectory.dir("newrelic"))
        rename { "newrelic.jar" }
    }

// Colgado de bootJar: así el jar aparece siempre que se arme el artefacto
tasks.named("bootJar") {
    dependsOn(copyNewRelicAgent)
}

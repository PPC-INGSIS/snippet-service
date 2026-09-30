import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.1.1"
    kotlin("plugin.jpa") version "2.3.21"
    // Las mismas versiones que el TP1, que ya sabemos que andan juntas
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
    id("io.gitlab.arturbosch.detekt") version "1.23.7"
    jacoco
}

group = "snippetsearcher"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Versiones de Spring solo para nuestras dependencias, no para ktlint ni detekt
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// --- Análisis estático ---

detekt {
    config.setFrom(file("config/detekt/detekt.yml"))
    // El YAML propio es un diff contra el default, no un reemplazo
    buildUponDefaultConfig = true
}

// --- Git hooks ---

// .git/hooks no se versiona: los hooks viven en .githooks/ y se copian al correr check
tasks.register<Copy>("installGitHooks") {
    from(layout.projectDirectory.dir(".githooks"))
    into(layout.projectDirectory.dir(".git/hooks"))
    // Sin permiso de ejecución, Git ignora el hook sin avisar
    filePermissions { unix("0755") }
}

// --- Cobertura ---

// main() nunca corre en los tests: el test arma el contexto de Spring sin pasar por ahí.
val coveredClasses =
    sourceSets.main.get().output.classesDirs.asFileTree.matching {
        exclude("**/*ApplicationKt.class")
    }

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    classDirectories.setFrom(coveredClasses)
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    classDirectories.setFrom(coveredClasses)
    violationRules {
        rule {
            limit {
                counter = "INSTRUCTION"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
    dependsOn("installGitHooks")
}

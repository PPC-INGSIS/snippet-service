# Snippets

Servicio de **Snippet Searcher** encargado de los snippets. Es la puerta de entrada para todo lo que un usuario hace con un snippet: crearlo, editarlo, listarlo, ver sus tests y sus resultados.

No ejecuta ni valida código. Para eso le pide al servicio del lenguaje que corresponda según el campo `lang` del snippet (por ahora, PrintScript). Tampoco guarda el contenido del snippet: el código vive en el bucket, y acá se guarda solo la ruta.

## Qué guarda

- Los datos del snippet: nombre, descripción, lenguaje, versión y dueño.
- La ruta al contenido en el bucket.
- Los tests (inputs y outputs esperados) y sus resultados.
## Stack

| | |
|---|---|
| Lenguaje | Kotlin 2.3 sobre Java 21 |
| Framework | Spring Boot 4.1 (Web MVC, Data JPA, Actuator) |
| Base de datos | PostgreSQL 18 |
| Build | Gradle (Kotlin DSL) |
| Calidad | ktlint, detekt, JaCoCo |
| Tests | JUnit 5 y Testcontainers |

## Requisitos

- Java 21
- Docker (para la base y para los tests)
## Correrlo localmente

```bash
# 1. Levantar Postgres
docker compose up -d
 
# 2. Levantar el servicio
./gradlew bootRun
```

El servicio queda escuchando en `http://localhost:8080`. Se corta con `Ctrl+C`.

Para verificar que está funcionando y que llega a la base:

```bash
curl localhost:8080/actuator/health
# {"status":"UP"}  → todo bien
# {"status":"DOWN"} → el servicio corre, pero la base no responde
```

Para apagar la base: `docker compose down`. Los datos se conservan en el volumen `postgres-data`; para borrarlos también, `docker compose down -v`.

## Configuración

Funciona sin configurar nada: todos los valores tienen un default para desarrollo local.

Para cambiar alguno, copiá el archivo de ejemplo y editá tu copia:

```bash
cp .env.example .env
```

| Variable | Default | Qué es |
|---|---|---|
| `DB_PORT` | `5433` | Puerto de la base en tu máquina |
| `POSTGRES_DB` | `snippets` | Nombre de la base |
| `POSTGRES_USER` | `snippets` | Usuario de la base |
| `POSTGRES_PASSWORD` | `snippets` | Contraseña de la base |

El mismo `.env` lo leen **Docker Compose** (para crear la base) y **Spring** (para conectarse), así que los dos quedan siempre sincronizados.

- `.env` está en el `.gitignore`: es de cada máquina y **nunca** se sube.
- `.env.example` sí se sube: documenta qué variables existen.
  La base solo acepta conexiones desde la propia máquina (`127.0.0.1`), no desde la red. Por eso las credenciales por defecto sirven únicamente para desarrollo local. En otros entornos se pasan por variables de entorno o secretos, nunca por el repo.

## Verificar

```bash
./gradlew check
```

Corre, en orden:

- **ktlint**: formato del código (espacios, saltos de línea, imports). Reglas en `.editorconfig`.
- **detekt**: estructura del código (complejidad, cantidad de returns, nombres). Reglas en `config/detekt/detekt.yml`.
- **Tests**, con un Postgres descartable levantado por Testcontainers (necesita Docker abierto).
- **Cobertura**: falla si queda por debajo del 80%. El reporte queda en `build/reports/jacoco/test/html/index.html`.
  Si ktlint falla, la mayoría de los problemas se corrigen solos:

```bash
./gradlew ktlintFormat
```

## Git hooks

Se instalan solos la primera vez que corrés `./gradlew check`. Viven en `.githooks/` y se copian a `.git/hooks/`.

| Hook | Cuándo | Qué corre |
|---|---|---|
| `pre-commit` | Antes de cada commit | ktlint y detekt |
| `pre-push` | Antes de cada push | `./gradlew check` completo (necesita Docker abierto) |

## CI

Cada pull request a `dev` o `main` corre `./gradlew check` en GitHub Actions, con el workflow reusable compartido por todos los servicios del proyecto (versión `v1`).

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
| Framework | Spring Boot 4.1 (Web MVC + Data JPA) |
| Base de datos | PostgreSQL 17 |
| Build | Gradle (Kotlin DSL) |
 
## Requisitos
 
- Java 21
- Docker, para levantar Postgres
## Correrlo localmente
 
```bash
# 1. Levantar Postgres
docker compose up -d
 
# 2. Levantar el servicio
./gradlew bootRun
```
 
El servicio queda escuchando en `http://localhost:8080`. Se corta con `Ctrl+C`.
 
Para apagar la base: `docker compose down`. Los datos se conservan en el volumen `snippets-db-data`; para borrarlos también, `docker compose down -v`.
 
## Configuración
 
Se define en `src/main/resources/application.properties`.
 
| Propiedad | Valor local | Qué es |
|---|---|---|
| `spring.datasource.url` | `jdbc:postgresql://localhost:5433/snippets` | Dirección de la base |
| `spring.datasource.username` | `snippets` | Usuario de la base |
| `spring.datasource.password` | `snippets` | Contraseña de la base |
| `spring.jpa.open-in-view` | `false` | No mantener la conexión abierta durante todo el pedido HTTP |
 
Postgres se expone en el puerto **5433** de la máquina (no en el 5432 habitual) para no chocar con otras instancias locales. Dentro del contenedor sigue en el 5432.
 
Las credenciales de este archivo son solo para desarrollo local. En otros entornos se pasan por variables de entorno.
 
## Verificar
 
```bash
./gradlew check
```
 
Compila y corre los tests. Es lo mismo que corre el CI en cada pull request.

# Snippets

Servicio de **Snippet Searcher** encargado de los snippets. Es la puerta de entrada para todo lo que un usuario hace con un snippet: crearlo, editarlo, listarlo, ver sus tests y sus resultados.

No ejecuta ni valida código. Para eso le pide al servicio del lenguaje que corresponda según el campo `language` del snippet (por ahora, PrintScript). Tampoco guarda el contenido: el código vive en el asset service, **identificado por el id del snippet**, así que acá no hay ninguna ruta guardada.

## Qué guarda

- Los datos del snippet: nombre, descripción, lenguaje, versión y dueño.
- Los tests de cada snippet: nombre, entradas y salidas esperadas.
- Las reglas de formateo y de linteo que eligió cada usuario: si están activas y su valor. Solo las que tocó: las demás salen con el default de PrintScript.

Lo que **no** guarda: el contenido del snippet (está en el asset service), quién tiene acceso a qué (está en Permisos) y el resultado de correr un test, que se calcula y se devuelve en el momento.
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
# 1. Levantar Postgres y Redis
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

Casi todo tiene un default para desarrollo local. Las credenciales de Auth0 **no**: sin ellas el servicio no arranca, y eso es a propósito (un servicio que levanta sin saber quién emite los tokens aceptaría cualquier cosa).

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
| `REDIS_HOST` | `localhost` | Host de Redis |
| `REDIS_PORT` | `6379` | Puerto de Redis en tu máquina |
| `AUTH0_DOMAIN` | **sin default** | El dominio de Auth0. De acá salen el `iss` y la Management API |
| `AUTH0_AUDIENCE` | **sin default** | La audiencia de la API: para qué API son los tokens que se aceptan |
| `AUTH0_MANAGEMENT_CLIENT_ID` | **sin default** | La app M2M "Snippets (M2M)", que solo tiene `read:users` |
| `AUTH0_MANAGEMENT_CLIENT_SECRET` | **sin default** | Su secret. Nunca va al repo |
| `PRINTSCRIPT_URL` | `http://localhost:8082` | PrintScript: valida, formatea y ejecuta |
| `PRINTSCRIPT_WS_URL` | `ws://localhost:8082` | Lo mismo por WebSocket, para la ejecución interactiva |
| `ASSET_SERVICE_URL` | `http://localhost:8083` | Dónde vive el contenido de los snippets |
| `PERMISSIONS_URL` | `http://localhost:8081` | Permisos: la tabla de accesos |

El mismo `.env` lo leen **Docker Compose** (para crear la base) y **Spring** (para conectarse), así que los dos quedan siempre sincronizados.

- `.env` está en el `.gitignore`: es de cada máquina y **nunca** se sube.
- `.env.example` sí se sube: documenta qué variables existen.
  La base solo acepta conexiones desde la propia máquina (`127.0.0.1`), no desde la red. Por eso las credenciales por defecto sirven únicamente para desarrollo local. En otros entornos se pasan por variables de entorno o secretos, nunca por el repo.

## Endpoints

Todos piden un token de Auth0 en `Authorization: Bearer <token>`, y **el scope sale del método HTTP**: los `GET` piden `read:snippets` y todo lo demás `write:snippets`. Hay dos excepciones, las dos a propósito:

| Ruta | Por qué entra sin token |
|---|---|
| `GET /actuator/health/**` | Lo consulta el `HEALTHCHECK` del contenedor, que no tiene con qué autenticarse |
| `GET /snippets/{id}/execution` | Es el handshake del WebSocket. El browser no puede mandar headers al abrirlo, así que el token viaja en el primer mensaje (ver [La ejecución interactiva](#la-ejecución-interactiva-websocket)) |

Un token válido pero **sin `sub`** da **401** en todos los endpoints que miran quién pide: sin `sub` no hay a quién atribuirle el snippet. Es el caso de un token de máquina mal configurado, no de un usuario.

Todos los cuerpos van y vuelven en JSON. Los errores que no tienen una forma propia salen como `ProblemDetail` ([RFC 9457](https://www.rfc-editor.org/rfc/rfc9457)), que es el default de Spring.

**Las rutas de acá son las del servicio.** Desplegado, nginx expone todo bajo `/api`: la UI pide `https://<dominio>/api/snippets` y nginx le saca el prefijo antes de llegar hasta acá. El prefijo igual viaja en `X-Forwarded-Prefix`, y con `server.forward-headers-strategy=framework` Spring se lo vuelve a poner a la URI que ve cada pedido. A los endpoints HTTP no les cambia nada, porque ninguno arma URLs ni mira la ruta a mano; al WebSocket sí, y es de donde salió el bug de #87.

### El listado

```
GET /snippets
```

| Parámetro | Default | Qué hace |
|---|---|---|
| `page` | `0` | La página. **Arranca en 0**, no en 1 |
| `page_size` | `10` | Cuántos por página, entre 1 y 100. En **snake_case** |
| `snippetName` | — | Filtra por nombre: contiene, sin distinguir mayúsculas |
| `language` | — | Filtra por lenguaje: igualdad exacta, sin distinguir mayúsculas |
| `sortBy` | `name` | `name` o `language` |
| `sortDirection` | `asc` | `asc` o `desc` |
| `relation` | `all` | `owned` (los propios), `shared` (los que me compartieron) o `all` (los dos) |

```json
{
  "page": 0,
  "page_size": 10,
  "count": 37,
  "snippets": [
    {
      "id": "0f9b...",
      "name": "fibonacci",
      "description": "los primeros n",
      "language": "printscript",
      "version": "1.1",
      "extension": "ps",
      "author": "Lucho Calvani",
      "owned": true
    }
  ]
}
```

| Status | Cuándo |
|---|---|
| `200` | Siempre que el pedido sea válido, incluso si no hay resultados (`count: 0`, `snippets: []`) |
| `400` | `sortBy`, `sortDirection` o `relation` con un valor que no está en la lista, o `page` negativo, o `page_size` fuera de 1..100. El `detail` dice qué valores se aceptan |
| `502` | `relation` es `shared` o `all` y **Permisos no contestó**: la lista estaría incompleta y no hay forma de saber cuánto le falta |

Las decisiones que no se ven en la forma:

- **`count` es el total que matchea el filtro**, no el tamaño de la página. Es lo que necesita el paginador de la UI para saber cuántas páginas hay.
- **El orden desempata por `id`.** Dos snippets con el mismo nombre quedarían en el orden que Postgres quiera, y ese orden puede cambiar entre dos pedidos: el mismo snippet aparecería en dos páginas y otro en ninguna.
- **Un `sortBy` que no existe es un 400 y no un orden por defecto.** Si la UI manda un campo mal escrito, que se note en la primera prueba y no en producción con los snippets en otro orden.
- **`sortBy` es una lista blanca** (`name`, `language`) y no la columna que venga: con texto libre, cualquiera podría ordenar por `owner_id` y un nombre inventado reventaría con un 500.
- **`relation=owned` nunca da 502**, porque no necesita preguntarle nada a Permisos. Si Permisos está caído, la UI puede seguir mostrando los propios pidiendo `relation=owned`.
- **El dueño llega como nombre y no como `sub`.** Los nombres se piden a Auth0 de a toda la página junta y se cachean 5 minutos; si Auth0 no contesta, en `author` viaja el `sub` y el listado igual sale 200. Un listado sin nombres es peor que uno con ids, pero mucho mejor que un error.
- **`owned` dice si el que pide es el dueño**, también en el detalle, al crear, al editar y al compartir. La UI lo usa para no ofrecer borrar, editar ni compartir un snippet que le compartieron (todos darían 404). Va calculado y no como el `sub` del dueño: `author` es un nombre para mostrar y dos usuarios pueden llamarse igual, y mandar el `sub` le pasaría a cada uno el identificador de los demás para que la UI repita una regla que ya vive acá.

### Un snippet

```
GET /snippets/{id}
```

Lo mismo que un elemento del listado **más `content`**, que solo viaja acá: en un listado de 100 snippets serían 100 pedidos al asset service.

| Status | Cuándo |
|---|---|
| `200` | Es tuyo o te lo compartieron |
| `404` | No existe, **o existe y no tenés acceso** |
| `500` | El snippet está en la base pero su contenido no está en el asset service |
| `502` | No se pudo verificar el acceso (Permisos caído) o no se pudo leer el contenido (asset service caído) |

- **404 y no 403 cuando no tenés acceso.** Un 403 confirma que ese id existe, y los ids son adivinables de a uno. No distinguir las dos cosas no le saca nada al que sí tiene acceso.
- **500 y no 502 cuando falta el contenido.** El asset service contestó bien: lo que está mal son nuestros datos, una fila que apunta a un contenido que no está. Es un bug nuestro, no una caída de otro.

### Crear

```
POST /snippets
```

```json
{
  "name": "fibonacci",
  "description": "los primeros n",
  "language": "printscript",
  "version": "1.1",
  "content": "let x: number = 1;"
}
```

| Status | Cuerpo | Cuándo |
|---|---|---|
| `201` | El snippet, con `id`, `author` y `extension` | Se validó, se guardó el contenido y se registró el dueño |
| `400` | `{"errors": [{"message", "line", "column"}]}` | El código no compila. `line` y `column` pueden venir en `null`: no todos los errores de PrintScript tienen posición |
| `400` | `{"message": "..."}` | El pedido no se pudo procesar: el lenguaje no existe, la versión no existe, falta un campo, el nombre es muy largo |
| `502` | `{"message": "..."}` | PrintScript, el asset service o Permisos no contestaron |

- **`extension` no se manda, se deriva del lenguaje.** Es del catálogo, no del snippet: `printscript` → `ps`. Si viajara en el pedido, dos snippets del mismo lenguaje podrían tener extensiones distintas. Viene en la respuesta porque la UI la usa para el nombre del archivo al descargar.
- **Un fallo a mitad de camino no deja basura.** Crear son tres pasos (validar, guardar el contenido, registrar al dueño) y el que falla deshace lo que los anteriores hicieron **solo para ese id**. Por eso un 502 tarda un poco más que un 201.

### Reemplazar, borrar y compartir

```
PUT    /snippets/{id}
DELETE /snippets/{id}
POST   /snippets/{id}/share
```

`PUT` recibe los mismos campos que `POST` y es un **reemplazo total**: lo que no venga se pierde. Devuelve el snippet actualizado con `200`, y los mismos `400`, `404` y `502` que crear.

`DELETE` devuelve **`204`** sin cuerpo, o `404` si no existe o no es tuyo. Borra la fila, el contenido y los accesos; si la limpieza de esos dos falla, queda en el log pero la respuesta sigue siendo `204`: para el que pidió, el snippet ya no está.

`POST /snippets/{id}/share` recibe `{"userId": "auth0|..."}` y devuelve el snippet con `200`. `400` si el `userId` viene vacío, `404` si el snippet no existe o no sos el dueño, `502` si Permisos no contestó.

- **Quién puede compartir lo decide Permisos, no Snippets.** Snippets le pide registrar un acceso de lectura y Permisos contesta si el que pide es el dueño del snippet. La regla vive en un solo lugar.
- **Compartir es idempotente**: compartirle dos veces al mismo usuario no es un error.

### Los tests de un snippet

```
GET    /snippets/{snippetId}/tests
POST   /snippets/{snippetId}/tests
PUT    /snippets/{snippetId}/tests/{testCaseId}
DELETE /snippets/{snippetId}/tests/{testCaseId}
POST   /snippets/{snippetId}/tests/run
```

Un test case es un nombre, las entradas que se le van a dar y las salidas que se esperan:

```json
{ "id": "3c1e...", "name": "sin entrada", "input": [], "output": ["1", "1", "2"] }
```

`input` y `output` están **en singular aunque sean listas** porque así los nombra la UI (`TestCase` en `printscript-ui`). Las dos son opcionales al crear: sin ellas, el test no pide entradas o no espera salidas.

| Endpoint | Status |
|---|---|
| `GET` | `200` con la lista (un array, sin paginar: son pocos por snippet), `404`, `502` si no se pudo verificar el acceso |
| `POST` | `201` con el test creado, `400` si el nombre está vacío o es muy largo, `404` |
| `PUT` | `200` con el test, `400`, `404` (el snippet o el test) |
| `DELETE` | `204`, `404` |

Correr un test:

```
POST /snippets/{snippetId}/tests/run
{ "input": [], "output": ["1", "1", "2"] }
```

```json
{ "passed": false, "outputs": ["1", "1", "3"], "expected": ["1", "1", "2"], "error": null }
```

- **El test viaja en el body y no se busca por id.** La UI corre el formulario tal como está, que puede no estar guardado: en `TabPanel.tsx` el botón Test manda el test actual y el de Save es otro. Un test guardado se corre mandando sus datos, igual que uno nuevo. `name` se acepta y se ignora, así la UI manda el test entero sin armar otro body.
- **Vuelven `outputs` y `expected`.** Sin los dos, un test que falla no se puede comparar: la UI tendría que acordarse de lo que mandó.
- **`error` solo viene si el snippet se cortó antes de terminar.** Un test puede fallar sin error (imprimió otra cosa) o fallar con error (se murió en la línea 3).
- Status: `200` con el resultado —**también si el test no pasa**, porque el pedido estuvo bien—, `400` si la versión del snippet no existe, `404`, `500` si falta el contenido, `502` si PrintScript no contestó.

### Formatear un snippet

```
POST /snippets/{id}/format
{ "content": "let a:number=1;" }
```

```json
{ "content": "let a: number = 1;\n" }
```

- **No guarda nada.** Devuelve el código formateado y el snippet queda como estaba: para cambiarlo, la UI hace el `PUT`.
- **El contenido viaja en el body**, como el test al correrlo: se formatea lo que está en el editor, aunque no esté guardado. Para descargar la versión formateada, la UI manda el contenido guardado y baja lo que vuelve.
- **La versión es la del snippet y las reglas son las de formateo activas de su dueño**, no las de quien pide.
- **Quien tiene lectura puede formatear**, no solo el dueño: formatear no cambia nada y es lo que permite descargar la versión formateada de un snippet compartido.
- La respuesta es un objeto y no el texto suelto, para poder sumarle campos sin romper a la UI.
- Status: `200`, `400` con el mensaje de PrintScript si no pudo formatear (el código no se puede analizar, es muy grande o una regla guardada ya no sirve), `404` si no existe o no lo podés leer, `502` si PrintScript o Permisos no contestaron.

### Usuarios y lenguajes

```
GET /users?name=&page=0&page_size=10
GET /languages
```

`GET /users` busca en el directorio de Auth0, para el autocompletado de "compartir con". Mismos nombres que el listado de snippets (`page` desde 0, `page_size`, `count` como total), con `page_size` hasta **50**: acota lo que se le pide a Auth0 en cada tecla. `400` si `page` o `page_size` están fuera de rango.

`GET /languages` devuelve los lenguajes con su extensión y sus versiones:

```json
[{ "language": "printscript", "extension": "ps", "versions": ["1.0", "1.1"] }]
```

Las versiones las sabe PrintScript, así que es el único de los dos que puede dar **`502`**.

### Reglas de formateo y linteo

```
GET /rules/format
GET /rules/lint
PUT /rules/format
PUT /rules/lint
```

Los `GET` devuelven las reglas de formateo o de linteo del usuario, en el orden en que las da PrintScript:

```json
[
  { "id": "if-indent", "name": "Espacios de indentación dentro de un if", "since": "1.1", "isActive": true, "value": 2 },
  { "id": "line-break-after-statement", "name": "Salto de línea después de cada sentencia", "since": "1.0", "isActive": false, "value": null }
]
```

- **Las reglas las sabe PrintScript, no Snippets.** Se piden a su `GET /rules/{kind}` sin versión, así que vienen las de la última: las reglas son del usuario, no de un snippet. `name` es la descripción que da PrintScript.
- **Snippets guarda lo que eligió cada usuario.** Una regla que el usuario nunca guardó sale deshabilitada, con el default de PrintScript como `value`. Las reglas de tipo flag no tienen valor, así que su `value` es `null`. Una regla guardada que PrintScript ya no tiene no sale.
- **`since` dice desde qué versión de PrintScript corre la regla.** Un usuario puede activar una regla de 1.1 aunque tenga snippets 1.0: PrintScript no la aplica en esos snippets y la informa como ignorada. Con `since` la UI puede avisarlo.
- Status: `200`, o `502` si PrintScript no contestó.

Los `PUT` reemplazan las reglas de ese tipo con la lista que mandó la UI. El body:

```json
[
  { "id": "if-indent", "isActive": true, "value": 2 },
  { "id": "line-break-after-statement", "isActive": false, "value": null }
]
```

- **El reemplazo es total.** Una regla guardada que no viene en la lista deja de estar guardada y vuelve a su default. Así funciona la UI, que manda siempre la lista entera. Se aceptan más campos (`name`, `since`), y se ignoran.
- **Deshabilitar no pierde el valor.** `isActive` y `value` se guardan por separado: una regla con `"isActive": false, "value": 2` vuelve a salir con `2`.
- **Valida PrintScript, no Snippets.** Antes de guardar se manda cada regla a su `POST /rules/{kind}/validate`, con el `value` como valor o, si es `null`, con `isActive`, que es lo que espera un flag. Si alguna no existe o su valor no sirve, no se guarda nada.
- Status:
  - `200` con la lista resultante, con la misma forma que el `GET`.
  - `400` si una regla no existe, si su valor no sirve o si un id viene repetido. La propiedad `rule` dice cuál:

    ```json
    { "title": "Bad Request", "status": 400, "detail": "La regla 'if-indent' acepta valores entre 0 y 8, se recibió 12", "instance": "/rules/format", "rule": "if-indent" }
    ```

    El `detail` es el de PrintScript, salvo el de un id repetido: `La regla 'if-indent' está repetida`.
  - `502` si PrintScript no contestó. No se guarda nada.

### La ejecución interactiva (WebSocket)

```
WS /snippets/{id}/execution
```

Desde el browser, con el prefijo de nginx: `wss://<dominio>/api/snippets/{id}/execution`.

Snippets no ejecuta: hace de **relay** entre el browser y PrintScript. Los mensajes de PrintScript pasan tal cual, sin leerlos.

**El id se lee del tramo anterior a `execution`, no de una posición fija.** El handler se registra con un patrón (`/snippets/*/execution`), así que no hay `@PathVariable` y el id sale de la URI de la sesión. Esa URI sí trae el prefijo de nginx, porque `X-Forwarded-Prefix` se lo vuelve a poner: contando desde adelante, el id de `/api/snippets/{id}/execution` queda en otro lugar que el de `/snippets/{id}/execution`, y la ejecución se rechazaba siempre en dev con `La URL no trae un id de snippet válido` (#87). Es la única parte del servicio que mira la ruta a mano, y por eso la única a la que el prefijo le cambia algo.

El protocolo de este lado son dos mensajes de entrada y uno de salida:

| Mensaje | Dirección | Cuándo |
|---|---|---|
| `{"type":"auth","token":"<jwt>"}` | browser → Snippets | **Siempre el primero**, dentro de 5 segundos |
| `{"type":"input","value":"..."}` | browser → Snippets | Cuando el snippet pide una entrada. Solo después del `auth` |
| `{"type":"rejected","detail":"..."}` | Snippets → browser | No se puede ejecutar. Después de este mensaje se cierra |

Todo lo demás que ve el browser lo generó PrintScript.

**Por qué el token va en un mensaje y no en un header.** El `WebSocket` del browser no permite mandar headers al abrir la conexión: solo la URL. Las tres opciones eran el header (imposible desde el browser), la query string (un token en la URL queda en los logs de nginx y en el historial) o el primer mensaje. Por eso el handshake es la única ruta con `permitAll`: la autenticación la hace el handler con el token del `auth`, no Spring Security.

**Por qué hay un timeout de 5 segundos** (`execution.auth-timeout`). Una conexión abierta sin autenticar ocupa un lugar y no se le puede atribuir a nadie. Si no llega el `auth` a tiempo, llega un `rejected` y se cierra.

Los motivos de un `rejected`:

| `detail` | Qué pasó |
|---|---|
| `No se recibió el mensaje 'auth' a tiempo` | Pasaron los 5 segundos |
| `El primer mensaje tiene que ser 'auth'` | Llegó otra cosa, o no era JSON |
| `La URL no trae un id de snippet válido` | El tramo anterior a `execution` no es un UUID |
| `El token no es válido o no tiene el scope` | Vencido, mal firmado, o sin `read:snippets` |
| `No se encontró el snippet` | No existe **o no tenés acceso**, igual que en el `GET` |
| `No se pudo verificar el acceso al snippet` / `No se pudo leer el contenido del snippet` | Permisos o el asset service no contestaron |
| `Ya tenés N ejecuciones abiertas. Cerrá una para empezar otra` | El tope por usuario: 3 por defecto (`snippets.execution.max-per-user`) |
| `Ya autenticado: solo se acepta 'input'` | Un segundo `auth`, o un mensaje que no se pudo leer |
| `No se pudo arrancar la ejecución` | PrintScript no aceptó la conexión |

**El cierre siempre es 1008, incluso cuando el motivo es "probá más tarde".** El código correcto para el tope de ejecuciones es 1013, pero **no sobrevive**: medido, un cierre con 1013 le llega al cliente como **1002** ("error de protocolo"), mientras 1008 y 1011 llegan tal cual. Un 1002 diría algo falso, así que la distinción viaja en el `detail`, que es lo que la UI tiene que mostrar igual.

**Cerrar el browser corta la ejecución.** La conexión con PrintScript se cierra junto con la del browser, y el lugar del usuario se devuelve ahí mismo. Es lo que evita que alguien que cierra tres pestañas se quede sin poder ejecutar hasta que el servicio se reinicie.

### Lo que la API todavía no devuelve

El tipo `Snippet` de `printscript-ui` tiene un campo **`compliance`** (`pending`, `failed`, `not-compliant`, `compliant`) que ningún endpoint devuelve, porque todavía no existe lo que lo calcula: el linteo automático de cada snippet contra las reglas de su dueño. Mientras no esté, la UI lo trata como ausente.

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

## Observabilidad

El servicio manda trazas, métricas y logs a New Relic con el **agente de Java**, según lo decidido en [infraestructura#46](https://github.com/PPC-INGSIS/infraestructura/issues/46). Mismo armado que en `permissions-service` y `printscript-service`: el jar del agente se baja en la configuración `newRelicAgent` de Gradle, `copyNewRelicAgent` lo deja en `build/newrelic/` y el Dockerfile arranca con `-javaagent:/app/newrelic.jar`.

`newrelic/newrelic.yml` lleva **valores literales**: el agente no entiende `${VAR}` en ese archivo. Lo que cambia por entorno lo pisan las variables `NEW_RELIC_*`, que el agente lee solo.

Los logs salen en **JSON (formato ECS)** a la salida estándar, y cada línea lleva:

| Campo | De dónde sale |
|---|---|
| `trace.id`, `span.id` | `TraceContextFilter`, del agente. Va primero de todos los filtros |
| `user.id` | `UserContextFilter`, el `sub` del token. Va **después** de Spring Security, que es cuando existe |
| `service.name` | la configuración |

Nunca se loguea el token: el `sub` es un identificador, el token es un secreto.

Con el trace id, un pedido de `POST /snippets` se sigue por Snippets, PrintScript, el asset service y Permisos, porque el agente lo propaga entre servicios.

## Git hooks

Se instalan solos la primera vez que corrés `./gradlew check`. Viven en `.githooks/` y se copian a `.git/hooks/`.

| Hook | Cuándo | Qué corre |
|---|---|---|
| `pre-commit` | Antes de cada commit | ktlint y detekt |
| `pre-push` | Antes de cada push | `./gradlew check` completo (necesita Docker abierto) |

## CI

Cada pull request a `dev` o `main` corre `./gradlew check` en GitHub Actions, con el workflow reusable compartido por todos los servicios del proyecto (versión `v1`).

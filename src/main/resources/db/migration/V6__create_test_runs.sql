-- Correr los tests de un snippet en segundo plano (US 16).
--
-- Son dos tablas porque son dos cosas distintas: el **trabajo** (hay tests que correr para este
-- snippet, en qué estado está) y el **resultado** de cada test. El trabajo es por snippet porque el
-- mensaje de la cola es por snippet; los resultados son por test porque es lo que se consulta.
--
-- La tabla de trabajos es la que manda, no el stream de Redis: la librería de la cátedra se suscribe
-- con `receiveAutoAck`, así que un mensaje se confirma al entregarse y un consumidor que se cae a
-- mitad de camino no lo recupera. Lo que quedó sin hacer se sabe mirando acá.
CREATE TABLE snippet_test_run (
    snippet_id  UUID        PRIMARY KEY REFERENCES snippets (id) ON DELETE CASCADE,
    -- QUEUED: encolado, nadie lo tomó. RUNNING: un consumidor lo está corriendo.
    -- FINISHED: corrieron todos. FAILED: se agotaron los intentos y no se reintenta más.
    status      VARCHAR(20) NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'FINISHED', 'FAILED')),
    -- Cuántas veces se intentó. Un trabajo que falla por algo de afuera (PrintScript caído) vuelve a
    -- QUEUED; después de N intentos queda FAILED, así un snippet que falla siempre no se reintenta
    -- para siempre ni tapa a los demás.
    attempts    INT         NOT NULL DEFAULT 0,
    -- Cuándo lo tomó un consumidor. Es el lease: un RUNNING más viejo que el límite es un consumidor
    -- que se murió, no uno trabajando. Lo usa el barrido que vuelve a encolar (#36); acá se escribe.
    started_at  TIMESTAMPTZ,
    updated_at  TIMESTAMPTZ NOT NULL
);

-- Para el barrido de #36: los que están a medias, por antigüedad
CREATE INDEX snippet_test_run_a_medias_idx ON snippet_test_run (started_at)
    WHERE status IN ('QUEUED', 'RUNNING');

-- Lo último que se sabe de cada test. Una fila por test y se sobreescribe: no es un historial, es
-- "cómo salió la última vez", que es lo que muestra la UI.
CREATE TABLE test_case_result (
    test_case_id UUID        PRIMARY KEY REFERENCES test_cases (id) ON DELETE CASCADE,
    passed       BOOLEAN     NOT NULL,
    -- Lo que imprimió. Sin esto, un test que falla no se puede comparar con lo que esperaba
    outputs      TEXT[]      NOT NULL,
    -- Solo si el snippet se cortó antes de terminar. Un test puede fallar sin error (imprimió otra
    -- cosa) o fallar con error (se murió en la línea 3)
    error        TEXT,
    ran_at       TIMESTAMPTZ NOT NULL
);

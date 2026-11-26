-- Los tests de un snippet: un nombre y dos listas en orden (los inputs que va a pedir readInput y los
-- outputs que se esperan). text[] y no una tabla aparte con la posición: el array ya guarda el orden,
-- y un test siempre se lee y se guarda entero.
-- ON DELETE CASCADE: borrar un snippet borra sus tests. Sin eso, el DELETE del snippet fallaría por la FK.
CREATE TABLE test_cases (
    id         UUID         PRIMARY KEY,
    snippet_id UUID         NOT NULL REFERENCES snippets (id) ON DELETE CASCADE,
    name       VARCHAR(255) NOT NULL,
    inputs     TEXT[]       NOT NULL,
    outputs    TEXT[]       NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL
);

-- Los tests siempre se piden por snippet
CREATE INDEX test_cases_snippet_id_idx ON test_cases (snippet_id);

-- Dueño del snippet: el sub del token de Auth0 (por ejemplo auth0|abc123). Es texto, no UUID.
-- Los snippets que ya existen quedan con 'sin-dueno' para que la migración no falle;
-- después se saca el default, así ningún snippet nuevo se puede guardar sin dueño.
-- Esas filas quedan huérfanas a propósito: ningún sub real es 'sin-dueno', así que nadie las ve
-- ni las puede borrar desde la app. Son datos de prueba de antes de que existiera el dueño.
ALTER TABLE snippets ADD COLUMN owner_id VARCHAR(255) NOT NULL DEFAULT 'sin-dueno';
ALTER TABLE snippets ALTER COLUMN owner_id DROP DEFAULT;

-- "Mis snippets" filtra por dueño: sin índice, cada listado recorre la tabla entera
CREATE INDEX snippets_owner_id_idx ON snippets (owner_id);

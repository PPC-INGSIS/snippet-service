-- Dueño del snippet: el sub del token de Auth0 (por ejemplo auth0|abc123). Es texto, no UUID.
-- Los snippets que ya existen quedan con 'sin-dueno' para que la migración no falle;
-- después se saca el default, así ningún snippet nuevo se puede guardar sin dueño.
ALTER TABLE snippets ADD COLUMN owner_id VARCHAR(255) NOT NULL DEFAULT 'sin-dueno';
ALTER TABLE snippets ALTER COLUMN owner_id DROP DEFAULT;
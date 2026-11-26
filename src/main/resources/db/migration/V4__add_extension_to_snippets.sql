-- La extensión del archivo del snippet (prs, ts, ...). La UI ya la manda al crear y la espera de
-- vuelta: la usa para nombrar el archivo cuando se descarga el snippet.
-- Las filas que ya existen quedan con 'prs', que es el default de la UI cuando no encuentra el
-- lenguaje; después se saca el default, así ningún snippet nuevo se puede guardar sin extensión.
ALTER TABLE snippets ADD COLUMN extension VARCHAR(20) NOT NULL DEFAULT 'prs';
ALTER TABLE snippets ALTER COLUMN extension DROP DEFAULT;

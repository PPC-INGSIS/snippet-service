-- Las reglas de formateo y de linteo que eligió cada usuario. Una fila por regla guardada: las que el usuario
-- nunca tocó no tienen fila, y al leerlas salen deshabilitadas con el default del lenguaje.
-- active va aparte de value para que deshabilitar una regla no pierda el valor elegido: al volver a
-- habilitarla, sigue el mismo. value es null en los flags, que no tienen valor.
-- language está aunque hoy haya un solo lenguaje: cada lenguaje tiene sus reglas, y con la columna desde ya
-- sumar otro no obliga a migrar.
-- La clave primaria alcanza como índice: todas las consultas son por (owner_id, language, kind).
CREATE TABLE user_rules (
    owner_id VARCHAR(255) NOT NULL,
    language VARCHAR(50)  NOT NULL,
    kind     VARCHAR(10)  NOT NULL CHECK (kind IN ('FORMAT', 'LINT')),
    rule_id  VARCHAR(255) NOT NULL,
    active   BOOLEAN      NOT NULL,
    value    JSONB,
    PRIMARY KEY (owner_id, language, kind, rule_id)
);

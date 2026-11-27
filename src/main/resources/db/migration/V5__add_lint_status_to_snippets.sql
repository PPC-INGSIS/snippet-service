-- Si el snippet cumple las reglas de linteo de su dueño. Los snippets que ya existen quedan en PENDING:
-- nunca se lintearon.
-- Sin índice: el estado siempre se consulta junto con el dueño, que ya tiene el suyo.
ALTER TABLE snippets
    ADD COLUMN lint_status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        CHECK (lint_status IN ('PENDING', 'COMPLIANT', 'NOT_COMPLIANT', 'FAILED'));

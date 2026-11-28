package snippetsearcher.snippets.testcase

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

/**
 * Cómo salió un test la última vez que corrió.
 *
 * Se sobreescribe: no es un historial sino el último resultado, que es lo que la UI muestra al lado
 * de cada test. El id es el del test, así que no hace falta buscar "el último" de nada.
 */
@Entity
@Table(name = "test_case_result")
class TestCaseResult(
    @Id
    @Column(name = "test_case_id")
    val testCaseId: UUID,
    @Column(nullable = false)
    var passed: Boolean,
    // Lo que imprimió el snippet. Sin esto, un test que falla no se puede comparar con lo esperado
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false)
    var outputs: List<String>,
    // Solo si el snippet se cortó antes de terminar
    @Column
    var error: String?,
    @Column(name = "ran_at", nullable = false)
    var ranAt: Instant,
)

// Los resultados de un snippet se piden por los ids de sus tests (findAllById): la tabla no repite
// el snippet, que ya está en test_cases
interface TestCaseResultRepository : JpaRepository<TestCaseResult, UUID>

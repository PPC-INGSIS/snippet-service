package snippetsearcher.snippets.testcase

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

/**
 * El trabajo de correr los tests de un snippet: en qué estado está y cuántas veces se intentó.
 *
 * Es la tabla de tareas de la teórica (enfoques #4 y #5), y es **la fuente de verdad**, no el stream:
 * la librería de la cátedra confirma cada mensaje al entregarlo (`receiveAutoAck`), así que un
 * consumidor que se cae no lo recupera. Lo que quedó sin hacer se sabe mirando esta tabla.
 */
@Entity
@Table(name = "snippet_test_run")
class SnippetTestRun(
    @Id
    @Column(name = "snippet_id")
    val snippetId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: TestRunStatus,
    @Column(nullable = false)
    var attempts: Int,
    // Cuándo lo tomó un consumidor: null mientras está encolado
    @Column(name = "started_at")
    var startedAt: Instant?,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
)

enum class TestRunStatus {
    /** Encolado: hay un mensaje, nadie lo tomó todavía. */
    QUEUED,

    /** Un consumidor lo está corriendo. Con `startedAt` viejo, es uno que se murió. */
    RUNNING,

    /** Corrieron todos los tests del snippet. Cada resultado está en `test_case_result`. */
    FINISHED,

    /** Se agotaron los intentos. No se reintenta más: un snippet roto no puede tapar a los demás. */
    FAILED,
    ;

    /** Si el resultado que se puede leer hoy puede quedar viejo en cualquier momento. */
    val pendiente: Boolean get() = this == QUEUED || this == RUNNING
}

interface SnippetTestRunRepository : JpaRepository<SnippetTestRun, UUID>

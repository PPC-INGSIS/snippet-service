package snippetsearcher.snippets.testcase

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

// Un test de un snippet. Crearlo solo lo guarda: correrlo es otra cosa
@Entity
@Table(name = "test_cases")
class TestCase(
    @Id
    val id: UUID,
    @Column(name = "snippet_id", nullable = false)
    val snippetId: UUID,
    @Column(nullable = false)
    var name: String,
    // Los arrays de Postgres guardan el orden: el primer input es el primero que pide el snippet
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false)
    var inputs: List<String>,
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false)
    var outputs: List<String>,
    // Para listarlos en el orden en que se crearon
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
)

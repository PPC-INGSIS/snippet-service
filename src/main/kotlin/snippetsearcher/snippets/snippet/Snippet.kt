package snippetsearcher.snippets.snippet

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "snippets")
class Snippet(
    @Id
    val id: UUID,
    @Column(nullable = false)
    var name: String,
    @Column(nullable = false)
    var description: String,
    @Column(nullable = false)
    var language: String,
    @Column(nullable = false)
    var version: String,
)

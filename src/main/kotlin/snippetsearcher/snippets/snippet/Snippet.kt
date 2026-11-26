package snippetsearcher.snippets.snippet

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

// El constructor de una entidad es una columna por parámetro, así que el tope de detekt no aplica.
// Si suma más campos, lenguaje, versión y extensión piden un @Embeddable: siempre viajan juntos
@Suppress("LongParameterList")
@Entity
@Table(name = "snippets")
class Snippet(
    @Id
    val id: UUID,
    @Column(name = "owner_id", nullable = false)
    val ownerId: String,
    @Column(nullable = false)
    var name: String,
    @Column(nullable = false)
    var description: String,
    @Column(nullable = false)
    var language: String,
    @Column(nullable = false)
    var version: String,
    // prs, ts... La elige la UI a partir del lenguaje y la necesita para nombrar la descarga
    @Column(nullable = false)
    var extension: String,
)

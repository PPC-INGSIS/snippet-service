package snippetsearcher.snippets.snippet

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface SnippetRepository : JpaRepository<Snippet, UUID> {
    // El dueño es parte de la búsqueda y no un chequeo aparte: así "no es tuyo" y "no existe"
    // son el mismo resultado, y nadie puede distinguirlos por un descuido en el código que sigue
    fun findByIdAndOwnerId(
        id: UUID,
        ownerId: String,
    ): Snippet?
}

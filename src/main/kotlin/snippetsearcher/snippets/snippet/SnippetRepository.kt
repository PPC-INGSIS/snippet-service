package snippetsearcher.snippets.snippet

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import java.util.UUID

// JpaSpecificationExecutor es lo que permite armar la consulta del listado con los filtros
// que vinieron y nada más, y devuelve la página junto con el total en una sola llamada
interface SnippetRepository :
    JpaRepository<Snippet, UUID>,
    JpaSpecificationExecutor<Snippet> {
    // El dueño es parte de la búsqueda y no un chequeo aparte: así "no es tuyo" y "no existe"
    // son el mismo resultado, y nadie puede distinguirlos por un descuido en el código que sigue
    fun findByIdAndOwnerId(
        id: UUID,
        ownerId: String,
    ): Snippet?
}

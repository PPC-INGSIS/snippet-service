package snippetsearcher.snippets.rules

import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import snippetsearcher.snippets.language.RuleKind

@Repository
class SavedRuleRepository(
    private val entityManager: EntityManager,
) {
    fun find(
        ownerId: String,
        language: String,
        kind: RuleKind,
    ): List<SavedRule> =
        entityManager
            .createQuery(
                "SELECT r FROM SavedRule r WHERE $SCOPE",
                SavedRule::class.java,
            ).setParameter("ownerId", ownerId)
            .setParameter("language", language)
            .setParameter("kind", kind)
            .resultList

    /**
     * Las reglas que no vienen en [rules] dejan de estar guardadas.
     *
     * El borrado es un DELETE de JPQL y no un `remove` por entidad: se ejecuta en el momento, antes de los
     * INSERT. Con `remove`, Hibernate manda los INSERT antes que los DELETE al hacer flush, y guardar una
     * regla que ya estaba choca con la clave primaria.
     */
    @Transactional
    fun replace(
        ownerId: String,
        language: String,
        kind: RuleKind,
        rules: List<SavedRule>,
    ) {
        entityManager
            .createQuery(
                "DELETE FROM SavedRule r WHERE $SCOPE",
            ).setParameter("ownerId", ownerId)
            .setParameter("language", language)
            .setParameter("kind", kind)
            .executeUpdate()
        rules.forEach(entityManager::persist)
    }

    companion object {
        // El mismo en las dos consultas: lo que se borra al reemplazar es justo lo que se lee
        private const val SCOPE = "r.id.ownerId = :ownerId AND r.id.language = :language AND r.id.kind = :kind"
    }
}

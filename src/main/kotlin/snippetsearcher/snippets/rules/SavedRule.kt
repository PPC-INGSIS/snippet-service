package snippetsearcher.snippets.rules

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import snippetsearcher.snippets.language.RuleKind
import java.io.Serializable

// Una regla que eligió un usuario. Se reemplazan todas juntas, así que no hace falta poder modificarla
@Entity
@Table(name = "user_rules")
class SavedRule(
    @EmbeddedId
    val id: SavedRuleId,
    @Column(nullable = false)
    val active: Boolean,
    @JdbcTypeCode(SqlTypes.JSON)
    val value: Any?,
)

@Embeddable
data class SavedRuleId(
    @Column(name = "owner_id", nullable = false)
    val ownerId: String,
    @Column(nullable = false)
    val language: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val kind: RuleKind,
    @Column(name = "rule_id", nullable = false)
    val ruleId: String,
) : Serializable {
    companion object {
        private const val serialVersionUID = 1L
    }
}

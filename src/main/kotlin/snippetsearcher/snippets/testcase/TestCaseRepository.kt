package snippetsearcher.snippets.testcase

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TestCaseRepository : JpaRepository<TestCase, UUID> {
    // El id como desempate: dos tests creados en el mismo instante tienen que salir siempre en el mismo orden
    fun findBySnippetIdOrderByCreatedAtAscIdAsc(snippetId: UUID): List<TestCase>

    // El test tiene que ser de ese snippet: con solo el id, se podría tocar el test de otro snippet
    fun findByIdAndSnippetId(
        id: UUID,
        snippetId: UUID,
    ): TestCase?
}

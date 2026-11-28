package snippetsearcher.snippets.testcase

import java.util.UUID

/**
 * Dejar encolado el trabajo de correr los tests de un snippet.
 *
 * Un puerto y no el productor de Redis directo para que `SnippetWriter` no sepa de colas: actualizar
 * un snippet dispara un trabajo, y con qué se transporta no es asunto suyo. Además así sus tests no
 * necesitan un Redis.
 */
fun interface TestRunQueue {
    fun enqueue(snippetId: UUID)
}

package snippetsearcher.snippets.language

import org.springframework.stereotype.Component
import snippetsearcher.snippets.printscript.PrintScriptClient
import snippetsearcher.snippets.printscript.VersionsResult

@Component
class PrintScriptLanguage(
    private val client: PrintScriptClient,
) : Language {
    override val name = "printscript"
    override val extension = "prs"

    override fun versions(): VersionsResult = client.versions()
}

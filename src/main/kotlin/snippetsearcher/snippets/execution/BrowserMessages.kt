package snippetsearcher.snippets.execution

import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper

/**
 * Los mensajes que Snippets habla con el browser.
 *
 * Son solo dos de entrada —`auth` y `input`— y uno de salida, `rejected`. Todo lo demás que ve el
 * browser son los mensajes de PrintScript, que pasan tal cual sin que nadie los lea.
 *
 * Aparte del handler para que el handler no tenga que saber de JSON: un mensaje que no se puede
 * leer y uno que no tiene el campo que hace falta son el mismo caso, así que las dos funciones
 * devuelven null y quien llama cierra la conexión.
 */
@Component
class BrowserMessages(
    private val json: ObjectMapper,
) {
    /** El token de un `{"type":"auth","token":"..."}`, o null si el mensaje no es eso. */
    fun tokenDe(payload: String): String? =
        leer(payload)?.takeIf { it.type == AUTH }?.token?.takeUnless { it.isBlank() }

    /** El valor de un `{"type":"input","value":"..."}`, o null si el mensaje no es eso. */
    fun inputDe(payload: String): String? = leer(payload)?.takeIf { it.type == INPUT }?.value

    fun rejected(detail: String): String = json.writeValueAsString(Rejected(detail))

    private fun leer(payload: String): BrowserMessage? =
        try {
            json.readValue(payload, BrowserMessage::class.java)
        } catch (ignored: JacksonException) {
            // No es JSON, o no tiene la forma esperada: para el browser es el mismo error
            null
        }

    /**
     * Los dos mensajes en una sola clase, con todo opcional.
     *
     * Un mensaje mal formado no tiene que fallar al parsearse sino al chequearse: así "no es JSON",
     * "le falta el type" y "le falta el token" terminan todos en el mismo null.
     */
    private data class BrowserMessage(
        val type: String? = null,
        val token: String? = null,
        val value: String? = null,
    )

    private data class Rejected(
        val detail: String,
        val type: String = "rejected",
    )

    private companion object {
        const val AUTH = "auth"
        const val INPUT = "input"
    }
}

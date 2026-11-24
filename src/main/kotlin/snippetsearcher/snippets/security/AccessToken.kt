package snippetsearcher.snippets.security

// De dónde sale el token que Snippets les manda a los otros servicios.
// Hoy, del pedido del usuario; los trabajos sin usuario van a tener su propia implementación (#50).
fun interface AccessToken {
    fun value(): String
}

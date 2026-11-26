package snippetsearcher.snippets.user

// Los usuarios que se pueden elegir al compartir un snippet. Hoy salen de Auth0, pero el resto del servicio no lo sabe
interface UserDirectory {
    fun search(
        name: String,
        page: Int,
        pageSize: Int,
    ): UserPage

    /**
     * El nombre de cada uno de esos usuarios, por id, en una sola consulta.
     *
     * De a uno sería una llamada por fila del listado. Los ids que el directorio no conoce
     * simplemente no aparecen en el mapa: quien llama decide qué mostrar en su lugar.
     */
    fun namesOf(userIds: Collection<String>): Map<String, String>
}

// Una página de resultados. total es la cantidad de usuarios que coinciden en todas las páginas, no solo en esta
data class UserPage(
    val users: List<User>,
    val total: Int,
)

data class User(
    val id: String,
    val name: String,
)

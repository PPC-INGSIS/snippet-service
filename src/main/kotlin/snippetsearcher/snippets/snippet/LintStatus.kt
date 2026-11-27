package snippetsearcher.snippets.snippet

enum class LintStatus(
    val value: String,
) {
    PENDING("pending"),
    COMPLIANT("compliant"),
    NOT_COMPLIANT("not-compliant"),
    FAILED("failed"),
    ;

    companion object {
        fun from(value: String): LintStatus? = entries.firstOrNull { it.value.equals(value, ignoreCase = true) }

        val values: List<String> get() = entries.map { it.value }
    }
}

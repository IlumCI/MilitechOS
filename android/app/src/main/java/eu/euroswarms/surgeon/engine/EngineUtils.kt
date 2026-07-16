package eu.euroswarms.surgeon.engine

/** Pure helper logic extracted from the engine so it can be unit-tested directly. */
internal object EngineUtils {

    /** Issues with any of these (normalized) labels are never picked. */
    val SKIP_LABELS = setOf(
        "wontfix", "duplicate", "invalid", "question", "discussion",
        "blocked", "on-hold", "needs-discussion", "wip",
    )

    fun normalizeLabel(name: String): String =
        name.lowercase().replace(Regex("[\\s_]+"), "-")

    fun slugify(title: String): String {
        val slug = title.lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .replace(Regex("-+"), "-")
            .trim('-')
        return slug.take(40).trim('-').ifBlank { "change" }
    }

    /**
     * Orders candidate paths by textual relevance to the issue so the locate model sees the
     * most promising files first (and truncation drops the least relevant, not the
     * alphabetically unlucky). Filename hits count more than directory hits.
     */
    fun rankPaths(paths: List<String>, issueTitle: String, issueBody: String, cap: Int): List<String> {
        val tokens = "$issueTitle $issueBody".lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 }
            .distinct()
        if (tokens.isEmpty()) return paths.take(cap)
        return paths
            .sortedByDescending { path ->
                val lower = path.lowercase()
                val fileName = lower.substringAfterLast('/')
                tokens.sumOf { token ->
                    when {
                        fileName.contains(token) -> 3
                        lower.contains(token) -> 1
                        else -> 0
                    } as Int
                }
            }
            .take(cap)
    }
}

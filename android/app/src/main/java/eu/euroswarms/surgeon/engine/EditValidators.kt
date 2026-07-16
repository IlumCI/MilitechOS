package eu.euroswarms.surgeon.engine

import kotlinx.serialization.json.Json

/**
 * Cheap, local structural checks on the final content of every edited file.
 * These run before the critic model call — a malformed file should never cost tokens.
 */
internal object EditValidators {

    private val strictJson = Json { isLenient = false; ignoreUnknownKeys = false }

    /** Returns null when the content passes, or a human-readable error string. */
    fun validate(path: String, content: String): String? {
        if (content.isBlank()) {
            return "file would be left empty"
        }
        if (path.endsWith(".json")) {
            runCatching { strictJson.parseToJsonElement(content) }
                .onFailure { return "invalid JSON: ${it.message?.lineSequence()?.firstOrNull()}" }
        }
        if (path.endsWith(".yml") || path.endsWith(".yaml")) {
            content.lineSequence().forEachIndexed { index, line ->
                if (line.startsWith("\t")) {
                    return "YAML uses tab indentation at line ${index + 1} (tabs are illegal in YAML)"
                }
            }
        }
        return null
    }
}

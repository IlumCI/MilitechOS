package eu.euroswarms.surgeon.engine

import eu.euroswarms.surgeon.data.FileEdit
import eu.euroswarms.surgeon.net.ChatMessage
import eu.euroswarms.surgeon.net.Http
import eu.euroswarms.surgeon.net.OllamaClient
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
private data class LocateResult(val files: List<String> = emptyList(), val reason: String = "")

@Serializable
private data class AgentEdit(
    val path: String = "",
    @SerialName("is_new") val isNew: Boolean = false,
    @SerialName("old_string") val oldString: String? = null,
    @SerialName("new_string") val newString: String = "",
)

@Serializable
private data class EditPlan(
    val abstain: Boolean = false,
    val reason: String = "",
    @SerialName("commit_message") val commitMessage: String = "",
    val edits: List<AgentEdit> = emptyList(),
)

@Serializable
private data class CriticVerdict(val approve: Boolean = false, val reason: String = "")

/** The materialized, validated result of the edit phase, ready to commit. */
sealed interface AgentOutcome {
    data class Ready(
        val edits: List<FileEdit>,
        val finalContents: Map<String, String>,
        val commitMessage: String,
        val notes: String,
    ) : AgentOutcome

    data class Abstained(val reason: String) : AgentOutcome
    data class Rejected(val reason: String) : AgentOutcome
}

/**
 * Two-phase surgical agent backed by an Ollama model:
 *   1. locate — pick the files to touch.
 *   2. plan   — produce exact string replacements, which we validate before anything is committed.
 * Validation is what keeps changes "surgical": every replacement must match its target exactly once,
 * and file/size caps are enforced (tighter when [danger] is set for private repos).
 */
class SurgicalAgent(private val ollama: OllamaClient) {

    suspend fun locate(issueTitle: String, issueBody: String, paths: List<String>): List<String> {
        if (paths.isEmpty()) return emptyList()
        val raw = ollama.chat(
            messages = listOf(
                ChatMessage("system", Prompts.LOCATE_SYSTEM),
                ChatMessage("user", Prompts.locateUser(issueTitle, issueBody, paths)),
            ),
            temperature = 0.0,
        )
        val parsed = parse(raw, LocateResult.serializer()) ?: return emptyList()
        // Keep only paths that really exist in the candidate list.
        val allowed = paths.toHashSet()
        return parsed.files.filter { it in allowed }.take(MAX_FILES)
    }

    suspend fun plan(
        issueTitle: String,
        issueBody: String,
        files: Map<String, String>,
        danger: Boolean,
    ): AgentOutcome {
        val raw = ollama.chat(
            messages = listOf(
                ChatMessage("system", Prompts.editSystem(danger)),
                ChatMessage("user", Prompts.editUser(issueTitle, issueBody, files)),
            ),
            temperature = if (danger) 0.0 else 0.15,
        )
        val plan = parse(raw, EditPlan.serializer())
            ?: return AgentOutcome.Rejected("Model returned unparseable output")

        if (plan.abstain) {
            return AgentOutcome.Abstained(plan.reason.ifBlank { "Agent chose to abstain" })
        }
        if (plan.edits.isEmpty()) {
            return AgentOutcome.Abstained("Agent produced no edits")
        }

        val fileCap = if (danger) MAX_FILES_DANGER else MAX_FILES
        val touched = plan.edits.map { it.path }.toSet()
        if (touched.size > fileCap) {
            return AgentOutcome.Rejected("Edit touches ${touched.size} files (cap $fileCap)")
        }

        // Apply edits to a mutable copy of each file's content, validating each replacement.
        val working = HashMap(files)
        val outEdits = ArrayList<FileEdit>()
        var changedBytes = 0

        for (edit in plan.edits) {
            if (edit.path.isBlank()) return AgentOutcome.Rejected("Edit with empty path")

            if (edit.isNew) {
                if (files.containsKey(edit.path)) {
                    return AgentOutcome.Rejected("Edit marks existing file '${edit.path}' as new")
                }
                working[edit.path] = edit.newString
                changedBytes += edit.newString.length
                outEdits.add(FileEdit(edit.path, null, edit.newString, isNew = true))
                continue
            }

            val current = working[edit.path]
                ?: return AgentOutcome.Rejected("Edit targets unknown file '${edit.path}'")
            val needle = edit.oldString
            if (needle.isNullOrEmpty()) {
                return AgentOutcome.Rejected("Non-new edit for '${edit.path}' is missing old_string")
            }
            val occurrences = current.occurrencesOf(needle)
            if (occurrences == 0) {
                return AgentOutcome.Rejected("old_string not found in '${edit.path}'")
            }
            if (occurrences > 1) {
                return AgentOutcome.Rejected("old_string is ambiguous in '${edit.path}' ($occurrences matches)")
            }
            working[edit.path] = current.replaceFirst(needle, edit.newString)
            changedBytes += Math.abs(edit.newString.length - needle.length)
            outEdits.add(FileEdit(edit.path, needle, edit.newString, isNew = false))
        }

        if (changedBytes > MAX_CHANGED_BYTES) {
            return AgentOutcome.Rejected("Change too large ($changedBytes bytes); not surgical")
        }

        val finalContents = outEdits.associate { it.path to (working[it.path] ?: "") }
        val message = plan.commitMessage.ifBlank { "Address issue: $issueTitle" }
        return AgentOutcome.Ready(outEdits, finalContents, message, plan.reason)
    }

    /**
     * Independent self-review: a second model call that critiques the produced change against
     * the issue and must actively approve it. Returns (approved, reason).
     */
    suspend fun critique(
        issueTitle: String,
        issueBody: String,
        outcome: AgentOutcome.Ready,
        danger: Boolean,
    ): Pair<Boolean, String> {
        val rendered = outcome.edits.joinToString("\n\n") { edit ->
            buildString {
                append("FILE: ${edit.path}")
                if (edit.isNew) append(" (new file)")
                append('\n')
                edit.oldString?.let { append("REMOVED:\n${it.take(CRITIC_SNIPPET_CHARS)}\n") }
                append("ADDED:\n${edit.newString.take(CRITIC_SNIPPET_CHARS)}")
            }
        }
        val raw = ollama.chat(
            messages = listOf(
                ChatMessage("system", Prompts.criticSystem(danger)),
                ChatMessage("user", Prompts.criticUser(issueTitle, issueBody, outcome.commitMessage, rendered)),
            ),
            temperature = 0.0,
        )
        val verdict = parse(raw, CriticVerdict.serializer())
            ?: return false to "Critic output unparseable"
        return verdict.approve to verdict.reason.ifBlank { if (verdict.approve) "approved" else "no reason given" }
    }

    // ---- helpers ----

    private fun <T> parse(raw: String, serializer: kotlinx.serialization.KSerializer<T>): T? {
        val jsonText = extractJsonObject(raw) ?: return null
        return runCatching { Http.json.decodeFromString(serializer, jsonText) }.getOrNull()
    }

    /** Strips markdown fences and isolates the outermost {...} object. */
    private fun extractJsonObject(raw: String): String? {
        val trimmed = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return trimmed.substring(start, end + 1)
    }

    private fun String.occurrencesOf(sub: String): Int {
        if (sub.isEmpty()) return 0
        var count = 0
        var idx = indexOf(sub)
        while (idx >= 0) {
            count++
            idx = indexOf(sub, idx + sub.length)
        }
        return count
    }

    companion object {
        const val MAX_FILES = 3
        const val MAX_FILES_DANGER = 2
        const val MAX_CHANGED_BYTES = 8_000
        const val CRITIC_SNIPPET_CHARS = 2_000
    }
}

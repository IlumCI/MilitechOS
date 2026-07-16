package eu.euroswarms.surgeon.data

import kotlinx.serialization.Serializable

/**
 * A repository the bot is allowed to work on, e.g. owner="kyegomez", name="swarms".
 *
 * [forkOwner]/[forkName] optionally pin the exact fork to use. When unset, the app assumes
 * the fork lives at <your login>/<name> and creates it if missing. When set, the app uses
 * the named fork verbatim (after verifying it really is a fork of this upstream) and never
 * auto-creates anything.
 */
@Serializable
data class RepoTarget(
    val owner: String,
    val name: String,
    val forkOwner: String? = null,
    val forkName: String? = null,
) {
    val fullName: String get() = "$owner/$name"

    /** Human-readable fork override, or null when using the default guess. */
    val forkOverrideLabel: String?
        get() {
            val fo = forkOwner?.takeIf { it.isNotBlank() }
            val fn = forkName?.takeIf { it.isNotBlank() }
            if (fo == null && fn == null) return null
            return "${fo ?: "(you)"}/${fn ?: name}"
        }
}

/**
 * All user configuration. Persisted as a single JSON blob in DataStore.
 * Defaults are pre-populated with the swarms repo set and the requested commit identity.
 */
@Serializable
data class AppConfig(
    val githubToken: String = "",
    // Defaults target Ollama Cloud (the phone has no local Ollama). For a self-hosted
    // server use http://<host>:11434 and leave the API key blank.
    val ollamaBaseUrl: String = "https://ollama.com",
    val ollamaModel: String = "deepseek-v4-flash:cloud",
    val ollamaApiKey: String = "",
    // Commit identity applied to every automated commit.
    val authorName: String = "Ilum",
    val authorEmail: String = "Ilum@linux.org",
    // Daily pacing. Recommended 5..15.
    val dailyMin: Int = 5,
    val dailyMax: Int = 15,
    // When true, the background scheduler drafts PRs automatically.
    val autoRunEnabled: Boolean = false,
    val repos: List<RepoTarget> = DEFAULT_REPOS,
) {
    val isReady: Boolean
        get() = githubToken.isNotBlank() && ollamaBaseUrl.isNotBlank() &&
            ollamaModel.isNotBlank() && repos.isNotEmpty()

    companion object {
        val DEFAULT_REPOS = listOf(
            RepoTarget("kyegomez", "swarms"),
            RepoTarget("The-Swarm-Corporation", "swarms-platform"),
            RepoTarget("The-Swarm-Corporation", "swarms-website"),
            RepoTarget("The-Swarm-Corporation", "swarms-api-docs"),
            RepoTarget("The-Swarm-Corporation", "swarms-api"),
            RepoTarget("The-Swarm-Corporation", "swarms-framework-docs"),
            RepoTarget("The-Swarm-Corporation", "swarms-cloud-platform"),
        )
    }
}

/** A single surgical edit produced by the agent. */
@Serializable
data class FileEdit(
    val path: String,
    // For replacements: the exact existing text to find (must occur exactly once). Null for new files.
    val oldString: String? = null,
    // The replacement text, or the full contents of a new file.
    val newString: String,
    val isNew: Boolean = false,
)

enum class DraftStatus { DRAFT, SUBMITTED, DISCARDED, FAILED, ABSTAINED }

/**
 * The output of one pipeline run: a branch on the fork carrying a surgical commit,
 * awaiting the human review gate (open-PR step).
 */
@Serializable
data class DraftPr(
    val id: String,
    val repoFullName: String,
    val forkFullName: String,
    val baseBranch: String,
    val headBranch: String,
    val issueNumber: Int,
    val issueTitle: String,
    val issueUrl: String,
    val isPrivateRepo: Boolean,
    val commitSha: String = "",
    val commitMessage: String = "",
    val compareUrl: String = "",
    val edits: List<FileEdit> = emptyList(),
    val agentNotes: String = "",
    val status: DraftStatus = DraftStatus.DRAFT,
    val createdAt: Long,
    val errorMessage: String = "",
    // Set when a re-check finds the issue was closed/locked after this draft was created.
    val isStale: Boolean = false,
)

enum class LogLevel { INFO, WARN, ERROR, SUCCESS }

@Serializable
data class LogEntry(
    val id: String,
    val timestamp: Long,
    val level: LogLevel,
    val message: String,
)

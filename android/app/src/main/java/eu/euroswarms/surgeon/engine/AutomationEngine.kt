package eu.euroswarms.surgeon.engine

import eu.euroswarms.surgeon.data.AppConfig
import eu.euroswarms.surgeon.data.DraftPr
import eu.euroswarms.surgeon.data.DraftStatus
import eu.euroswarms.surgeon.data.LogLevel
import eu.euroswarms.surgeon.data.RepoTarget
import eu.euroswarms.surgeon.data.Store
import eu.euroswarms.surgeon.net.ApiException
import eu.euroswarms.surgeon.net.GitHubClient
import eu.euroswarms.surgeon.net.GhIssue
import eu.euroswarms.surgeon.net.OllamaClient
import java.time.Instant
import java.util.UUID
import kotlin.random.Random

sealed interface EngineResult {
    data class Drafted(val draft: DraftPr) : EngineResult
    data class Skipped(val reason: String) : EngineResult
    data object NoWork : EngineResult
    data class Failed(val reason: String) : EngineResult
}

/**
 * Runs the full workflow once: pick an issue → prepare the fork branch → surgical agent →
 * commit as the configured identity → record a draft awaiting the human PR gate.
 */
class AutomationEngine(
    private val config: AppConfig,
    private val store: Store,
) {
    private val github = GitHubClient(config.githubToken)
    private val agent = SurgicalAgent(
        OllamaClient(config.ollamaBaseUrl, config.ollamaModel, config.ollamaApiKey),
    )

    suspend fun runOnce(): EngineResult {
        if (!config.isReady) return EngineResult.Failed("Configuration incomplete")

        val me = runCatching { github.getAuthenticatedUser() }.getOrElse {
            store.log(LogLevel.ERROR, "GitHub auth failed: ${it.message}")
            return EngineResult.Failed("GitHub auth failed: ${it.message}")
        }
        val myLogin = me.login
        if (myLogin.isBlank()) return EngineResult.Failed("Could not resolve GitHub identity")

        // Try repos in random order. A skip (abstain/reject/stale issue) burns that issue
        // but shouldn't end the run — move on to the next candidate, up to a small budget.
        var attempts = 0
        var lastSkip: EngineResult.Skipped? = null
        val repos = config.repos.shuffled(Random.Default)
        for (repo in repos) {
            if (attempts >= MAX_ATTEMPTS_PER_RUN) break
            val picked = pickIssue(repo) ?: continue
            attempts++
            store.log(LogLevel.INFO, "Selected ${repo.fullName}#${picked.number}: ${picked.title}")
            val result = try {
                processIssue(repo, picked, myLogin)
            } catch (e: ApiException) {
                store.log(LogLevel.ERROR, "API error on ${repo.fullName}#${picked.number}: ${e.message} ${trimBody(e.body)}")
                EngineResult.Failed("${e.message}")
            } catch (e: Exception) {
                store.log(LogLevel.ERROR, "Error on ${repo.fullName}#${picked.number}: ${e.message}")
                EngineResult.Failed(e.message ?: "unknown error")
            }
            when (result) {
                is EngineResult.Skipped -> lastSkip = result // try the next candidate
                else -> return result
            }
        }
        lastSkip?.let { return it }
        store.log(LogLevel.WARN, "No unprocessed issues found across configured repos")
        return EngineResult.NoWork
    }

    private suspend fun pickIssue(repo: RepoTarget): GhIssue? {
        val issues = runCatching { github.listOpenIssues(repo.owner, repo.name) }.getOrElse {
            store.log(LogLevel.WARN, "Could not list issues for ${repo.fullName}: ${it.message}")
            return null
        }
        // Workable = open, not seen before, unassigned, not locked, and not carrying a
        // label that disqualifies it (wontfix, question, blocked, ...).
        val pool = issues.filter { issue ->
            issue.state == "open" &&
                !store.isProcessed(repo.fullName, issue.number) &&
                issue.assignees.isEmpty() &&
                !issue.locked &&
                issue.labels.none { normalizeLabel(it.name) in SKIP_LABELS }
        }
        return pool.randomOrNull(Random.Default)
    }

    /**
     * The issue list is a snapshot, and the model call takes a while — re-fetch the single
     * issue and confirm it is STILL open, unassigned, and unlocked before we commit anything.
     */
    private suspend fun issueStillWorkable(repo: RepoTarget, number: Int): Boolean {
        val fresh = runCatching { github.getIssue(repo.owner, repo.name, number) }.getOrNull()
            ?: return false
        return fresh.state == "open" && fresh.assignees.isEmpty() && !fresh.locked
    }

    private suspend fun processIssue(repo: RepoTarget, issue: GhIssue, myLogin: String): EngineResult {
        val meta = github.getRepo(repo.owner, repo.name)
        val danger = meta.isPrivate
        val baseBranch = meta.defaultBranch
        val issueBody = issue.body.orEmpty().take(MAX_ISSUE_BODY_CHARS)

        github.ensureFork(repo.owner, repo.name, myLogin, baseBranch)
        github.syncForkBranch(myLogin, repo.name, baseBranch)

        // Divergence guard: we only ever build on a fork that exactly matches upstream.
        // A diverged fork is how merge conflicts happen — refuse rather than risk it.
        val upstreamSha = github.getBranchHeadSha(repo.owner, repo.name, baseBranch)
        val baseSha = github.getBranchHeadSha(myLogin, repo.name, baseBranch)
        if (baseSha != upstreamSha) {
            store.log(
                LogLevel.ERROR,
                "Fork $myLogin/${repo.name}:$baseBranch has diverged from upstream — " +
                    "sync or recreate the fork manually. Skipping this repo.",
            )
            return EngineResult.Skipped("Fork diverged from upstream")
        }

        // Locate the files to touch, feeding the model the most issue-relevant paths first.
        val paths = rankPaths(github.listSourcePaths(myLogin, repo.name, baseSha), issue.title, issueBody)
        val targetPaths = agent.locate(issue.title, issueBody, paths)
        if (targetPaths.isEmpty()) {
            store.markProcessed(repo.fullName, issue.number)
            store.log(LogLevel.WARN, "Agent found no relevant files for ${repo.fullName}#${issue.number}; skipped")
            return EngineResult.Skipped("No relevant files located")
        }

        // Fetch current contents.
        val files = LinkedHashMap<String, String>()
        for (path in targetPaths) {
            val content = runCatching { github.getFileContent(myLogin, repo.name, path, baseSha) }.getOrDefault("")
            if (content.isNotEmpty()) files[path] = content
        }
        if (files.isEmpty()) {
            store.markProcessed(repo.fullName, issue.number)
            return EngineResult.Skipped("Could not read located files")
        }

        // Plan the surgical change.
        when (val outcome = agent.plan(issue.title, issueBody, files, danger)) {
            is AgentOutcome.Abstained -> {
                store.markProcessed(repo.fullName, issue.number)
                store.log(LogLevel.WARN, "Agent abstained on ${repo.fullName}#${issue.number}: ${outcome.reason}")
                return EngineResult.Skipped("Abstained: ${outcome.reason}")
            }
            is AgentOutcome.Rejected -> {
                store.markProcessed(repo.fullName, issue.number)
                store.log(LogLevel.WARN, "Change rejected by safety checks on ${repo.fullName}#${issue.number}: ${outcome.reason}")
                return EngineResult.Skipped("Rejected: ${outcome.reason}")
            }
            is AgentOutcome.Ready -> {
                // "New" files must be new to the whole repo, not just to the fetched set —
                // otherwise we would silently overwrite an existing file.
                for (edit in outcome.edits.filter { it.isNew }) {
                    val existing = runCatching {
                        github.getFileContent(myLogin, repo.name, edit.path, baseSha)
                    }.getOrDefault("")
                    if (existing.isNotEmpty()) {
                        store.markProcessed(repo.fullName, issue.number)
                        store.log(
                            LogLevel.WARN,
                            "Rejected ${repo.fullName}#${issue.number}: agent marked existing file '${edit.path}' as new",
                        )
                        return EngineResult.Skipped("Rejected: '${edit.path}' already exists")
                    }
                }

                // Final freshness gate: the issue may have been closed, assigned, or locked
                // while the model was working. Never commit against a dead issue.
                if (!issueStillWorkable(repo, issue.number)) {
                    store.markProcessed(repo.fullName, issue.number)
                    store.log(
                        LogLevel.WARN,
                        "Discarded work on ${repo.fullName}#${issue.number}: issue was closed/assigned/locked mid-run",
                    )
                    return EngineResult.Skipped("Issue no longer workable")
                }

                return commitDraft(repo, issue, myLogin, baseBranch, baseSha, danger, outcome)
            }
        }
    }

    private suspend fun commitDraft(
        repo: RepoTarget,
        issue: GhIssue,
        myLogin: String,
        baseBranch: String,
        baseSha: String,
        danger: Boolean,
        outcome: AgentOutcome.Ready,
    ): EngineResult {
        val headBranch = createUniqueBranch(myLogin, repo.name, issue.number, issue.title, baseSha)

        val baseTreeSha = github.getCommitTreeSha(myLogin, repo.name, baseSha)
        val entries = outcome.finalContents.map { (path, content) ->
            path to github.createBlob(myLogin, repo.name, content)
        }
        val treeSha = github.createTree(myLogin, repo.name, baseTreeSha, entries)
        val commitSha = github.createCommit(
            forkOwner = myLogin,
            name = repo.name,
            message = outcome.commitMessage,
            treeSha = treeSha,
            parentSha = baseSha,
            authorName = config.authorName,
            authorEmail = config.authorEmail,
            isoDate = Instant.now().toString(),
        )
        github.updateRef(myLogin, repo.name, headBranch, commitSha)

        val compareUrl = "https://github.com/${repo.owner}/${repo.name}/compare/" +
            "$baseBranch...$myLogin:${repo.name}:$headBranch?expand=1"

        val draft = DraftPr(
            id = UUID.randomUUID().toString(),
            repoFullName = repo.fullName,
            forkFullName = "$myLogin/${repo.name}",
            baseBranch = baseBranch,
            headBranch = headBranch,
            issueNumber = issue.number,
            issueTitle = issue.title,
            issueUrl = issue.htmlUrl,
            isPrivateRepo = danger,
            commitSha = commitSha,
            commitMessage = outcome.commitMessage,
            compareUrl = compareUrl,
            edits = outcome.edits,
            agentNotes = outcome.notes,
            status = DraftStatus.DRAFT,
            createdAt = System.currentTimeMillis(),
        )
        store.upsertDraft(draft)
        store.markProcessed(repo.fullName, issue.number)
        store.log(
            LogLevel.SUCCESS,
            "Drafted ${repo.fullName}#${issue.number} → branch $headBranch (${outcome.edits.size} file(s))" +
                if (danger) " [PRIVATE — review carefully]" else "",
        )
        return EngineResult.Drafted(draft)
    }

    private suspend fun createUniqueBranch(
        forkOwner: String,
        name: String,
        issueNumber: Int,
        title: String,
        baseSha: String,
    ): String {
        val slug = slugify(title)
        val candidate = "surgeon/issue-$issueNumber-$slug"
        return try {
            github.createBranch(forkOwner, name, candidate, baseSha)
            candidate
        } catch (e: ApiException) {
            // 422 == ref already exists; disambiguate with a short suffix.
            // Anything else (auth, permissions, ...) must propagate, not be retried blindly.
            if (e.code != 422) throw e
            val suffixed = "$candidate-${System.currentTimeMillis() % 100000}"
            github.createBranch(forkOwner, name, suffixed, baseSha)
            suffixed
        }
    }

    /**
     * Orders candidate paths by textual relevance to the issue so the locate model sees the
     * most promising files first (and truncation drops the least relevant, not the
     * alphabetically unlucky). Filename hits count more than directory hits.
     */
    private fun rankPaths(paths: List<String>, issueTitle: String, issueBody: String): List<String> {
        val tokens = "$issueTitle $issueBody".lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 }
            .distinct()
        if (tokens.isEmpty()) return paths.take(MAX_CANDIDATE_PATHS)
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
            .take(MAX_CANDIDATE_PATHS)
    }

    private fun normalizeLabel(name: String): String =
        name.lowercase().replace(Regex("[\\s_]+"), "-")

    private fun slugify(title: String): String {
        val slug = title.lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .replace(Regex("-+"), "-")
            .trim('-')
        return slug.take(40).trim('-').ifBlank { "change" }
    }

    private fun trimBody(body: String): String = body.take(300)

    companion object {
        private const val MAX_ISSUE_BODY_CHARS = 6_000
        private const val MAX_CANDIDATE_PATHS = 300
        private const val MAX_ATTEMPTS_PER_RUN = 3

        /** Issues with any of these labels are never picked. */
        private val SKIP_LABELS = setOf(
            "wontfix", "duplicate", "invalid", "question", "discussion",
            "blocked", "on-hold", "needs-discussion", "wip",
        )
    }
}

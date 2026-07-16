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
import eu.euroswarms.surgeon.net.RateLimitException
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
 * validate + self-review → commit as the configured identity → record a draft awaiting the
 * human PR gate. Clients are injectable for testing.
 */
class AutomationEngine(
    private val config: AppConfig,
    private val store: Store,
    private val github: GitHubClient = GitHubClient(config.githubToken),
    private val agent: SurgicalAgent = SurgicalAgent(
        OllamaClient(config.ollamaBaseUrl, config.ollamaModel, config.ollamaApiKey),
    ),
) {

    suspend fun runOnce(): EngineResult {
        if (!config.isReady) return EngineResult.Failed("Configuration incomplete")
        val myLogin = resolveLogin() ?: return EngineResult.Failed("GitHub auth failed")

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
            when (val result = guardedProcess(repo, picked, myLogin)) {
                is EngineResult.Skipped -> lastSkip = result // try the next candidate
                else -> return result
            }
        }
        lastSkip?.let { return it }
        store.log(LogLevel.WARN, "No unprocessed issues found across configured repos")
        return EngineResult.NoWork
    }

    /**
     * Draft a specific issue chosen by the user. Bypasses the retryable-skip memory
     * (an explicit pick means "try it anyway") but never re-drafts a processed issue.
     */
    suspend fun runOnIssue(repo: RepoTarget, issueNumber: Int): EngineResult {
        if (!config.isReady) return EngineResult.Failed("Configuration incomplete")
        val myLogin = resolveLogin() ?: return EngineResult.Failed("GitHub auth failed")

        if (store.isProcessed(repo.fullName, issueNumber)) {
            return EngineResult.Skipped("Issue was already drafted or handled")
        }
        val issue = runCatching { github.getIssue(repo.owner, repo.name, issueNumber) }.getOrElse {
            return EngineResult.Failed("Could not fetch issue: ${it.message}")
        }
        if (issue.isPullRequest) return EngineResult.Skipped("That number is a pull request")
        if (issue.state != "open" || issue.assignees.isNotEmpty() || issue.locked) {
            return EngineResult.Skipped("Issue is not open/unassigned/unlocked")
        }
        store.log(LogLevel.INFO, "Manually selected ${repo.fullName}#$issueNumber: ${issue.title}")
        return guardedProcess(repo, issue, myLogin)
    }

    /** Open, unseen, unassigned, unlocked, label-eligible issues for the browse tab. */
    suspend fun listWorkableIssues(repo: RepoTarget): List<GhIssue> =
        runCatching { github.listOpenIssues(repo.owner, repo.name) }
            .getOrDefault(emptyList())
            .filter { isWorkable(repo, it) }

    /**
     * Delete surgeon-prefixed branches on the configured forks that no live draft references.
     * Returns how many branches were deleted.
     */
    suspend fun cleanupOrphanBranches(): Int {
        val myLogin = resolveLogin() ?: return 0
        val active = store.drafts.value
            .filter { it.status == DraftStatus.DRAFT || it.status == DraftStatus.SUBMITTED }
            .map { it.forkFullName to it.headBranch }
            .toSet()
        var deleted = 0
        for (repo in config.repos) {
            val forkOwner = repo.forkOwner?.takeIf { it.isNotBlank() } ?: myLogin
            val forkName = repo.forkName?.takeIf { it.isNotBlank() } ?: repo.name
            val branches = runCatching { github.listBranches(forkOwner, forkName) }.getOrDefault(emptyList())
            for (branch in branches) {
                if (!branch.startsWith(BRANCH_PREFIX)) continue
                if (("$forkOwner/$forkName" to branch) in active) continue
                if (runCatching { github.deleteRef(forkOwner, forkName, branch) }.isSuccess) deleted++
            }
        }
        store.log(LogLevel.INFO, "Branch cleanup: deleted $deleted orphan branch(es)")
        return deleted
    }

    // ---- internals ----

    private suspend fun resolveLogin(): String? {
        val me = runCatching { github.getAuthenticatedUser() }.getOrElse {
            store.log(LogLevel.ERROR, "GitHub auth failed: ${it.message}")
            return null
        }
        return me.login.takeIf { it.isNotBlank() }
    }

    /** Runs processIssue with uniform error handling (rate limits get special treatment). */
    private suspend fun guardedProcess(repo: RepoTarget, issue: GhIssue, myLogin: String): EngineResult {
        return try {
            processIssue(repo, issue, myLogin)
        } catch (e: RateLimitException) {
            store.log(
                LogLevel.ERROR,
                "GitHub rate limit hit on ${repo.fullName}#${issue.number}; backing off" +
                    (e.retryAfterSeconds?.let { " (~${it}s)" } ?: ""),
            )
            EngineResult.Failed("Rate limited by GitHub — will retry later")
        } catch (e: ApiException) {
            store.log(LogLevel.ERROR, "API error on ${repo.fullName}#${issue.number}: ${e.message} ${e.body.take(300)}")
            EngineResult.Failed("${e.message}")
        } catch (e: Exception) {
            store.log(LogLevel.ERROR, "Error on ${repo.fullName}#${issue.number}: ${e.message}")
            EngineResult.Failed(e.message ?: "unknown error")
        }
    }

    private fun isWorkable(repo: RepoTarget, issue: GhIssue): Boolean =
        issue.state == "open" &&
            !store.isProcessed(repo.fullName, issue.number) &&
            !store.isSkipped(repo.fullName, issue.number) &&
            issue.assignees.isEmpty() &&
            !issue.locked &&
            issue.labels.none { EngineUtils.normalizeLabel(it.name) in EngineUtils.SKIP_LABELS }

    private suspend fun pickIssue(repo: RepoTarget): GhIssue? {
        val issues = runCatching { github.listOpenIssues(repo.owner, repo.name) }.getOrElse {
            store.log(LogLevel.WARN, "Could not list issues for ${repo.fullName}: ${it.message}")
            return null
        }
        return issues.filter { isWorkable(repo, it) }.randomOrNull(Random.Default)
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

    /**
     * Resolve which fork to work in. An explicit mapping on the RepoTarget wins (verified,
     * never auto-created); otherwise fall back to <myLogin>/<name>, creating it if needed.
     */
    private suspend fun resolveFork(repo: RepoTarget, myLogin: String, baseBranch: String): Pair<String, String> {
        val explicitOwner = repo.forkOwner?.takeIf { it.isNotBlank() }
        val explicitName = repo.forkName?.takeIf { it.isNotBlank() }
        if (explicitOwner == null && explicitName == null) {
            github.ensureFork(repo.owner, repo.name, myLogin, baseBranch)
            return myLogin to repo.name
        }
        val forkOwner = explicitOwner ?: myLogin
        val forkName = explicitName ?: repo.name
        github.verifyFork(forkOwner, forkName, repo.owner, repo.name)
        return forkOwner to forkName
    }

    /** Retryable outcome: remember as skipped (user can clear) and report. */
    private suspend fun skipRetryable(repo: RepoTarget, issue: GhIssue, reason: String): EngineResult {
        store.markSkipped(repo.fullName, issue.number)
        store.log(LogLevel.WARN, "Skipped ${repo.fullName}#${issue.number}: $reason")
        return EngineResult.Skipped(reason)
    }

    private suspend fun processIssue(repo: RepoTarget, issue: GhIssue, myLogin: String): EngineResult {
        val meta = github.getRepo(repo.owner, repo.name)
        val danger = meta.isPrivate
        val baseBranch = meta.defaultBranch
        val issueBody = issue.body.orEmpty().take(MAX_ISSUE_BODY_CHARS)

        val (forkOwner, forkName) = resolveFork(repo, myLogin, baseBranch)
        github.syncForkBranch(forkOwner, forkName, baseBranch)

        // Divergence guard: we only ever build on a fork that exactly matches upstream.
        // A diverged fork is how merge conflicts happen — refuse rather than risk it.
        val upstreamSha = github.getBranchHeadSha(repo.owner, repo.name, baseBranch)
        val baseSha = github.getBranchHeadSha(forkOwner, forkName, baseBranch)
        if (baseSha != upstreamSha) {
            store.log(
                LogLevel.ERROR,
                "Fork $forkOwner/$forkName:$baseBranch has diverged from upstream — " +
                    "sync or recreate the fork manually. Skipping this repo.",
            )
            return EngineResult.Skipped("Fork diverged from upstream")
        }

        // Locate the files to touch, feeding the model the most issue-relevant paths first.
        val paths = EngineUtils.rankPaths(
            github.listSourcePaths(forkOwner, forkName, baseSha),
            issue.title, issueBody, MAX_CANDIDATE_PATHS,
        )
        val targetPaths = agent.locate(issue.title, issueBody, paths)
        if (targetPaths.isEmpty()) {
            return skipRetryable(repo, issue, "No relevant files located")
        }

        // Fetch current contents.
        val files = LinkedHashMap<String, String>()
        for (path in targetPaths) {
            val content = runCatching { github.getFileContent(forkOwner, forkName, path, baseSha) }.getOrDefault("")
            if (content.isNotEmpty()) files[path] = content
        }
        if (files.isEmpty()) {
            return skipRetryable(repo, issue, "Could not read located files")
        }

        // Plan the surgical change.
        when (val outcome = agent.plan(issue.title, issueBody, files, danger)) {
            is AgentOutcome.Abstained -> {
                return skipRetryable(repo, issue, "Abstained: ${outcome.reason}")
            }
            is AgentOutcome.Rejected -> {
                return skipRetryable(repo, issue, "Rejected: ${outcome.reason}")
            }
            is AgentOutcome.Ready -> {
                // Structural sanity of every final file (cheap, local, before any token spend).
                for ((path, content) in outcome.finalContents) {
                    EditValidators.validate(path, content)?.let { error ->
                        return skipRetryable(repo, issue, "Validation failed in $path: $error")
                    }
                }

                // Independent self-review: a second model call must actively approve the change.
                val (approved, critique) = agent.critique(issue.title, issueBody, outcome, danger)
                if (!approved) {
                    return skipRetryable(repo, issue, "Critic rejected: $critique")
                }

                // "New" files must be new to the whole repo, not just to the fetched set —
                // otherwise we would silently overwrite an existing file.
                for (edit in outcome.edits.filter { it.isNew }) {
                    val existing = runCatching {
                        github.getFileContent(forkOwner, forkName, edit.path, baseSha)
                    }.getOrDefault("")
                    if (existing.isNotEmpty()) {
                        return skipRetryable(repo, issue, "Agent marked existing file '${edit.path}' as new")
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

                return commitDraft(repo, issue, forkOwner, forkName, baseBranch, baseSha, danger, outcome)
            }
        }
    }

    private suspend fun commitDraft(
        repo: RepoTarget,
        issue: GhIssue,
        forkOwner: String,
        forkName: String,
        baseBranch: String,
        baseSha: String,
        danger: Boolean,
        outcome: AgentOutcome.Ready,
    ): EngineResult {
        val headBranch = createUniqueBranch(forkOwner, forkName, issue.number, issue.title, baseSha)

        val baseTreeSha = github.getCommitTreeSha(forkOwner, forkName, baseSha)
        val entries = outcome.finalContents.map { (path, content) ->
            path to github.createBlob(forkOwner, forkName, content)
        }
        val treeSha = github.createTree(forkOwner, forkName, baseTreeSha, entries)
        val commitSha = github.createCommit(
            forkOwner = forkOwner,
            name = forkName,
            message = outcome.commitMessage,
            treeSha = treeSha,
            parentSha = baseSha,
            authorName = config.authorName,
            authorEmail = config.authorEmail,
            isoDate = Instant.now().toString(),
        )
        github.updateRef(forkOwner, forkName, headBranch, commitSha)

        val compareUrl = "https://github.com/${repo.owner}/${repo.name}/compare/" +
            "$baseBranch...$forkOwner:$forkName:$headBranch?expand=1"

        val draft = DraftPr(
            id = UUID.randomUUID().toString(),
            repoFullName = repo.fullName,
            forkFullName = "$forkOwner/$forkName",
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
        val slug = EngineUtils.slugify(title)
        val candidate = "$BRANCH_PREFIX/issue-$issueNumber-$slug"
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

    companion object {
        private const val MAX_ISSUE_BODY_CHARS = 6_000
        private const val MAX_CANDIDATE_PATHS = 300
        private const val MAX_ATTEMPTS_PER_RUN = 3
        private const val BRANCH_PREFIX = "surgeon"
    }
}

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

        // Try repos in random order until one yields a workable issue.
        val repos = config.repos.shuffled(Random.Default)
        for (repo in repos) {
            val picked = pickIssue(repo) ?: continue
            store.log(LogLevel.INFO, "Selected ${repo.fullName}#${picked.number}: ${picked.title}")
            return try {
                processIssue(repo, picked, myLogin)
            } catch (e: ApiException) {
                store.log(LogLevel.ERROR, "API error on ${repo.fullName}#${picked.number}: ${e.message} ${trimBody(e.body)}")
                EngineResult.Failed("${e.message}")
            } catch (e: Exception) {
                store.log(LogLevel.ERROR, "Error on ${repo.fullName}#${picked.number}: ${e.message}")
                EngineResult.Failed(e.message ?: "unknown error")
            }
        }
        store.log(LogLevel.WARN, "No unprocessed issues found across configured repos")
        return EngineResult.NoWork
    }

    private suspend fun pickIssue(repo: RepoTarget): GhIssue? {
        val issues = runCatching { github.listOpenIssues(repo.owner, repo.name) }.getOrElse {
            store.log(LogLevel.WARN, "Could not list issues for ${repo.fullName}: ${it.message}")
            return null
        }
        val fresh = issues.filter { !store.isProcessed(repo.fullName, it.number) }
        if (fresh.isEmpty()) return null
        // Prefer unassigned issues; fall back to any fresh one.
        val unassigned = fresh.filter { it.assignees.isEmpty() }
        val pool = unassigned.ifEmpty { fresh }
        return pool.random(Random.Default)
    }

    private suspend fun processIssue(repo: RepoTarget, issue: GhIssue, myLogin: String): EngineResult {
        val meta = github.getRepo(repo.owner, repo.name)
        val danger = meta.isPrivate
        val baseBranch = meta.defaultBranch

        github.ensureFork(repo.owner, repo.name, myLogin)
        github.syncForkBranch(myLogin, repo.name, baseBranch)
        val baseSha = github.getBranchHeadSha(myLogin, repo.name, baseBranch)

        // Locate the files to touch.
        val paths = github.listSourcePaths(myLogin, repo.name, baseSha)
        val targetPaths = agent.locate(issue.title, issue.body.orEmpty(), paths)
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
        when (val outcome = agent.plan(issue.title, issue.body.orEmpty(), files, danger)) {
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
            val suffixed = "$candidate-${System.currentTimeMillis() % 100000}"
            github.createBranch(forkOwner, name, suffixed, baseSha)
            suffixed
        }
    }

    private fun slugify(title: String): String {
        val slug = title.lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .replace(Regex("-+"), "-")
            .trim('-')
        return slug.take(40).trim('-').ifBlank { "change" }
    }

    private fun trimBody(body: String): String = body.take(300)
}

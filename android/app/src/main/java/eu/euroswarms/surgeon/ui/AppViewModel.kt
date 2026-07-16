package eu.euroswarms.surgeon.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import eu.euroswarms.surgeon.data.AppConfig
import eu.euroswarms.surgeon.data.ConfigStore
import eu.euroswarms.surgeon.data.DraftPr
import eu.euroswarms.surgeon.data.DraftStatus
import eu.euroswarms.surgeon.data.LogEntry
import eu.euroswarms.surgeon.data.Store
import eu.euroswarms.surgeon.engine.AutomationEngine
import eu.euroswarms.surgeon.engine.EngineResult
import eu.euroswarms.surgeon.net.GitHubClient
import eu.euroswarms.surgeon.net.OllamaClient
import eu.euroswarms.surgeon.work.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A workable issue row for the browse tab. */
data class IssueRow(
    val repo: eu.euroswarms.surgeon.data.RepoTarget,
    val number: Int,
    val title: String,
    val labels: List<String>,
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val configStore = ConfigStore(app)
    private val store = Store.get(app)

    private fun engine() = AutomationEngine(config.value, store)

    val config: StateFlow<AppConfig> =
        configStore.configFlow.stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig())

    val drafts: StateFlow<List<DraftPr>> = store.drafts
    val logs: StateFlow<List<LogEntry>> = store.logs
    val skippedCount: StateFlow<Int> = store.skippedCount

    val isRunning = MutableStateFlow(false)
    val lastRunMessage = MutableStateFlow<String?>(null)

    // Model picker state: tags fetched from the configured Ollama endpoint.
    val availableModels = MutableStateFlow<List<String>>(emptyList())
    val modelsMessage = MutableStateFlow<String?>(null)

    /** Query /api/tags on the endpoint the user is currently typing (not necessarily saved). */
    fun fetchModels(baseUrl: String, apiKey: String) {
        if (baseUrl.isBlank()) {
            modelsMessage.value = "Enter the Ollama base URL first"
            return
        }
        viewModelScope.launch {
            modelsMessage.value = "Loading models…"
            runCatching {
                withContext(Dispatchers.IO) {
                    OllamaClient(baseUrl.trim(), model = "", apiKey = apiKey.trim()).listModels()
                }
            }.onSuccess { models ->
                availableModels.value = models
                modelsMessage.value = if (models.isEmpty()) "Endpoint returned no models" else null
            }.onFailure {
                availableModels.value = emptyList()
                modelsMessage.value = "Could not list models: ${it.message}"
            }
        }
    }

    fun draftsToday(): Int = store.draftsToday()

    fun saveConfig(newConfig: AppConfig) {
        viewModelScope.launch {
            configStore.save(newConfig)
            if (newConfig.autoRunEnabled) Scheduler.enable(getApplication())
            else Scheduler.disable(getApplication())
        }
    }

    fun runNow() {
        if (isRunning.value) return
        viewModelScope.launch {
            isRunning.value = true
            lastRunMessage.value = "Working…"
            val cfg = config.value
            val message = if (!cfg.isReady) {
                "Finish setup first (GitHub token, Ollama, repos)."
            } else {
                when (val r = withContext(Dispatchers.IO) { AutomationEngine(cfg, store).runOnce() }) {
                    is EngineResult.Drafted -> "Drafted ${r.draft.repoFullName}#${r.draft.issueNumber} — review it."
                    is EngineResult.Skipped -> "Skipped: ${r.reason}"
                    is EngineResult.NoWork -> "No unprocessed issues found."
                    is EngineResult.Failed -> "Failed: ${r.reason}"
                }
            }
            lastRunMessage.value = message
            isRunning.value = false
        }
    }

    fun setDraftStatus(id: String, status: DraftStatus) {
        viewModelScope.launch { store.updateDraftStatus(id, status) }
    }

    /** Discard a draft AND delete its branch on the fork (best-effort). */
    fun discardDraft(draft: DraftPr) {
        viewModelScope.launch {
            val cfg = config.value
            if (cfg.githubToken.isNotBlank() && draft.forkFullName.contains('/')) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        val (fo, fn) = draft.forkFullName.split("/", limit = 2).let { it[0] to it[1] }
                        GitHubClient(cfg.githubToken).deleteRef(fo, fn, draft.headBranch)
                    }
                }
            }
            store.updateDraftStatus(draft.id, DraftStatus.DISCARDED)
        }
    }

    // ---- Review-queue freshness ----

    val isRefreshingDrafts = MutableStateFlow(false)

    /** Re-check each pending draft's issue; mark drafts whose issue died as stale. */
    fun refreshDrafts() {
        if (isRefreshingDrafts.value) return
        viewModelScope.launch {
            val cfg = config.value
            if (cfg.githubToken.isBlank()) return@launch
            isRefreshingDrafts.value = true
            val gh = GitHubClient(cfg.githubToken)
            for (draft in drafts.value.filter { it.status == DraftStatus.DRAFT && !it.isStale }) {
                val parts = draft.repoFullName.split("/", limit = 2)
                if (parts.size != 2) continue
                val issue = withContext(Dispatchers.IO) {
                    runCatching { gh.getIssue(parts[0], parts[1], draft.issueNumber) }.getOrNull()
                }
                if (issue != null && (issue.state != "open" || issue.locked)) {
                    store.upsertDraft(draft.copy(isStale = true))
                }
            }
            isRefreshingDrafts.value = false
        }
    }

    // ---- Maintenance ----

    val maintenanceMessage = MutableStateFlow<String?>(null)

    /** Make retryable-skipped issues eligible for selection again. */
    fun retrySkipped() {
        viewModelScope.launch {
            store.clearSkipped()
            maintenanceMessage.value = "Skipped issues are eligible again."
        }
    }

    /** Delete orphaned surgeon-prefixed branches on the forks. */
    fun cleanupBranches() {
        viewModelScope.launch {
            maintenanceMessage.value = "Cleaning up branches…"
            val deleted = withContext(Dispatchers.IO) {
                runCatching { engine().cleanupOrphanBranches() }.getOrDefault(0)
            }
            maintenanceMessage.value = "Deleted $deleted orphan branch(es)."
        }
    }

    // ---- Issue browsing (manual pick) ----

    val browseIssues = MutableStateFlow<List<IssueRow>>(emptyList())
    val browseLoading = MutableStateFlow(false)

    fun loadIssues() {
        if (browseLoading.value) return
        viewModelScope.launch {
            browseLoading.value = true
            val eng = engine()
            val rows = mutableListOf<IssueRow>()
            for (repo in config.value.repos) {
                val issues = withContext(Dispatchers.IO) {
                    runCatching { eng.listWorkableIssues(repo) }.getOrDefault(emptyList())
                }
                issues.forEach { issue ->
                    rows.add(IssueRow(repo, issue.number, issue.title, issue.labels.map { it.name }))
                }
            }
            browseIssues.value = rows
            browseLoading.value = false
        }
    }

    /** Draft one specific, user-picked issue. */
    fun draftIssue(row: IssueRow) {
        if (isRunning.value) return
        viewModelScope.launch {
            isRunning.value = true
            lastRunMessage.value = "Drafting ${row.repo.fullName}#${row.number}…"
            val result = withContext(Dispatchers.IO) {
                engine().runOnIssue(row.repo, row.number)
            }
            lastRunMessage.value = when (result) {
                is EngineResult.Drafted -> "Drafted ${result.draft.repoFullName}#${result.draft.issueNumber} — review it."
                is EngineResult.Skipped -> "Skipped: ${result.reason}"
                is EngineResult.NoWork -> "Nothing to do."
                is EngineResult.Failed -> "Failed: ${result.reason}"
            }
            browseIssues.value = browseIssues.value.filterNot {
                it.repo.fullName == row.repo.fullName && it.number == row.number
            }
            isRunning.value = false
        }
    }
}

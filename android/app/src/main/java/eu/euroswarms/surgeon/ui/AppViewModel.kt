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
import eu.euroswarms.surgeon.net.OllamaClient
import eu.euroswarms.surgeon.work.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val configStore = ConfigStore(app)
    private val store = Store.get(app)

    val config: StateFlow<AppConfig> =
        configStore.configFlow.stateIn(viewModelScope, SharingStarted.Eagerly, AppConfig())

    val drafts: StateFlow<List<DraftPr>> = store.drafts
    val logs: StateFlow<List<LogEntry>> = store.logs

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
}

package eu.euroswarms.surgeon.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Calendar
import java.util.UUID

/**
 * Lightweight JSON-file persistence for drafts, logs, and issue memory. Exposed as StateFlows
 * for Compose. Constructed from a plain directory so the whole class is JVM-unit-testable.
 *
 * Issue memory is split in two:
 *  - processed: permanently handled (drafted, or dead at re-check). Never picked again.
 *  - skipped:   retryable outcomes (agent abstained/rejected, validation/critic failure).
 *               Excluded from selection until the user clears them ("Retry skipped").
 */
class Store(private val dir: File) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
    private val draftsFile = File(dir, "drafts.json")
    private val logsFile = File(dir, "logs.json")
    private val processedFile = File(dir, "processed.json")
    private val skippedFile = File(dir, "skipped.json")
    private val mutex = Mutex()

    private val _drafts = MutableStateFlow(readDrafts())
    val drafts: StateFlow<List<DraftPr>> = _drafts.asStateFlow()

    private val _logs = MutableStateFlow(readLogs())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val processed: MutableSet<String> = readStringSet(processedFile).toMutableSet()
    private val skipped: MutableSet<String> = readStringSet(skippedFile).toMutableSet()

    private val _skippedCount = MutableStateFlow(skipped.size)
    val skippedCount: StateFlow<Int> = _skippedCount.asStateFlow()

    // ---- Drafts ----

    suspend fun upsertDraft(draft: DraftPr) = mutex.withLock {
        val list = _drafts.value.toMutableList()
        val idx = list.indexOfFirst { it.id == draft.id }
        if (idx >= 0) list[idx] = draft else list.add(0, draft)
        _drafts.value = list
        draftsFile.writeText(json.encodeToString(ListSerializer(DraftPr.serializer()), list))
    }

    suspend fun updateDraftStatus(id: String, status: DraftStatus) {
        val draft = _drafts.value.firstOrNull { it.id == id } ?: return
        upsertDraft(draft.copy(status = status))
    }

    /** Number of drafts created since local midnight, in any non-discarded state. */
    fun draftsToday(): Int {
        val midnight = startOfToday()
        return _drafts.value.count {
            it.createdAt >= midnight && it.status != DraftStatus.DISCARDED && it.status != DraftStatus.FAILED
        }
    }

    // ---- Logs ----

    suspend fun log(level: LogLevel, message: String) = mutex.withLock {
        val entry = LogEntry(UUID.randomUUID().toString(), System.currentTimeMillis(), level, message)
        val list = (listOf(entry) + _logs.value).take(MAX_LOGS)
        _logs.value = list
        logsFile.writeText(json.encodeToString(ListSerializer(LogEntry.serializer()), list))
    }

    // ---- Issue memory ----

    fun isProcessed(repoFullName: String, issueNumber: Int): Boolean =
        processed.contains(key(repoFullName, issueNumber))

    suspend fun markProcessed(repoFullName: String, issueNumber: Int) = mutex.withLock {
        processed.add(key(repoFullName, issueNumber))
        processedFile.writeText(json.encodeToString(SetSerializer(String.serializer()), processed))
    }

    fun isSkipped(repoFullName: String, issueNumber: Int): Boolean =
        skipped.contains(key(repoFullName, issueNumber))

    suspend fun markSkipped(repoFullName: String, issueNumber: Int) = mutex.withLock {
        skipped.add(key(repoFullName, issueNumber))
        _skippedCount.value = skipped.size
        skippedFile.writeText(json.encodeToString(SetSerializer(String.serializer()), skipped))
    }

    /** Forget all retryable skips so those issues become eligible again. */
    suspend fun clearSkipped() = mutex.withLock {
        skipped.clear()
        _skippedCount.value = 0
        skippedFile.writeText(json.encodeToString(SetSerializer(String.serializer()), skipped))
    }

    private fun key(repoFullName: String, issueNumber: Int) = "$repoFullName#$issueNumber"

    // ---- IO helpers ----

    private fun readDrafts(): List<DraftPr> = runCatching {
        if (!draftsFile.exists()) emptyList()
        else json.decodeFromString(ListSerializer(DraftPr.serializer()), draftsFile.readText())
    }.getOrDefault(emptyList())

    private fun readLogs(): List<LogEntry> = runCatching {
        if (!logsFile.exists()) emptyList()
        else json.decodeFromString(ListSerializer(LogEntry.serializer()), logsFile.readText())
    }.getOrDefault(emptyList())

    private fun readStringSet(file: File): Set<String> = runCatching {
        if (!file.exists()) emptySet()
        else json.decodeFromString(SetSerializer(String.serializer()), file.readText())
    }.getOrDefault(emptySet())

    private fun startOfToday(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    companion object {
        private const val MAX_LOGS = 300

        @Volatile private var instance: Store? = null
        fun get(context: Context): Store =
            instance ?: synchronized(this) {
                instance ?: Store(context.applicationContext.filesDir).also { instance = it }
            }
    }
}

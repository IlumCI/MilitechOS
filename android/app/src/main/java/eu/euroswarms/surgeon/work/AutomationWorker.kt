package eu.euroswarms.surgeon.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import eu.euroswarms.surgeon.data.ConfigStore
import eu.euroswarms.surgeon.data.LogLevel
import eu.euroswarms.surgeon.data.Store
import eu.euroswarms.surgeon.engine.AutomationEngine
import eu.euroswarms.surgeon.engine.EngineResult
import kotlin.random.Random

/**
 * Periodic worker. On each run it drafts at most one PR, and only if the day's target
 * has not yet been reached — this spreads the 5..15/day quota out over the day rather
 * than firing them all at once.
 */
class AutomationWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val config = ConfigStore(applicationContext).get()
        val store = Store.get(applicationContext)

        if (!config.autoRunEnabled) return Result.success()
        if (!config.isReady) {
            store.log(LogLevel.WARN, "Auto-run skipped: configuration incomplete")
            return Result.success()
        }

        // Per-day target chosen deterministically-enough within [min, max].
        val target = dailyTarget(config.dailyMin, config.dailyMax)
        val done = store.draftsToday()
        if (done >= target) {
            return Result.success()
        }

        return when (val result = AutomationEngine(config, store).runOnce()) {
            is EngineResult.Drafted -> {
                Notifier.notifyDraftReady(
                    applicationContext,
                    "PR draft ready to review",
                    "${result.draft.repoFullName}#${result.draft.issueNumber}: ${result.draft.issueTitle}",
                    result.draft.issueNumber,
                )
                Result.success()
            }
            is EngineResult.Skipped, is EngineResult.NoWork -> Result.success()
            is EngineResult.Failed -> Result.retry()
        }
    }

    private fun dailyTarget(min: Int, max: Int): Int {
        val lo = min.coerceAtLeast(1)
        val hi = max.coerceAtLeast(lo)
        return if (hi == lo) lo else Random(daySeed()).nextInt(lo, hi + 1)
    }

    // Stable seed per calendar day so the target doesn't jump between runs.
    private fun daySeed(): Long = System.currentTimeMillis() / (24L * 60 * 60 * 1000)
}

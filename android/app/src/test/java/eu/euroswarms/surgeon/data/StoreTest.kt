package eu.euroswarms.surgeon.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

class StoreTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("store-test").toFile()
    }

    private fun draft(status: DraftStatus = DraftStatus.DRAFT, createdAt: Long = System.currentTimeMillis()) =
        DraftPr(
            id = UUID.randomUUID().toString(),
            repoFullName = "up/repo",
            forkFullName = "me/repo",
            baseBranch = "main",
            headBranch = "surgeon/issue-1-x",
            issueNumber = 1,
            issueTitle = "t",
            issueUrl = "u",
            isPrivateRepo = false,
            status = status,
            createdAt = createdAt,
        )

    @Test
    fun processedMemoryPersistsAcrossInstances() = runBlocking {
        val store = Store(dir)
        assertFalse(store.isProcessed("up/repo", 7))
        store.markProcessed("up/repo", 7)
        assertTrue(store.isProcessed("up/repo", 7))
        // A fresh Store over the same directory must read it back.
        assertTrue(Store(dir).isProcessed("up/repo", 7))
    }

    @Test
    fun skippedMemoryIsSeparateAndClearable() = runBlocking {
        val store = Store(dir)
        store.markSkipped("up/repo", 9)
        assertTrue(store.isSkipped("up/repo", 9))
        assertFalse(store.isProcessed("up/repo", 9))
        assertEquals(1, store.skippedCount.value)

        // Persists across instances.
        assertTrue(Store(dir).isSkipped("up/repo", 9))

        // Clearing makes the issue eligible again.
        store.clearSkipped()
        assertFalse(store.isSkipped("up/repo", 9))
        assertEquals(0, store.skippedCount.value)
        assertFalse(Store(dir).isSkipped("up/repo", 9))
    }

    @Test
    fun draftUpsertAndStatusUpdate() = runBlocking {
        val store = Store(dir)
        val d = draft()
        store.upsertDraft(d)
        assertEquals(1, store.drafts.value.size)

        store.updateDraftStatus(d.id, DraftStatus.SUBMITTED)
        assertEquals(DraftStatus.SUBMITTED, store.drafts.value.first().status)

        // Persisted for a fresh instance.
        assertEquals(DraftStatus.SUBMITTED, Store(dir).drafts.value.first().status)
    }

    @Test
    fun draftsTodayExcludesDiscardedAndFailed() = runBlocking {
        val store = Store(dir)
        store.upsertDraft(draft(DraftStatus.DRAFT))
        store.upsertDraft(draft(DraftStatus.SUBMITTED))
        store.upsertDraft(draft(DraftStatus.DISCARDED))
        store.upsertDraft(draft(DraftStatus.FAILED))
        assertEquals(2, store.draftsToday())
    }

    @Test
    fun draftsTodayExcludesYesterday() = runBlocking {
        val store = Store(dir)
        store.upsertDraft(draft(createdAt = System.currentTimeMillis() - 48 * 3600 * 1000))
        assertEquals(0, store.draftsToday())
    }
}

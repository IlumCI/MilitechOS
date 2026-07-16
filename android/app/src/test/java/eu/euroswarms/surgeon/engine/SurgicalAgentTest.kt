package eu.euroswarms.surgeon.engine

import eu.euroswarms.surgeon.net.OllamaClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SurgicalAgentTest {

    private lateinit var server: MockWebServer
    private lateinit var agent: SurgicalAgent

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        agent = SurgicalAgent(OllamaClient(server.url("/").toString(), "test-model", ""))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** Wraps model output content into an Ollama /api/chat response. */
    private fun reply(content: String) {
        val body = buildJsonObject {
            put("message", buildJsonObject {
                put("role", "assistant")
                put("content", content)
            })
            put("done", true)
        }.toString()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))
    }

    private fun planContent(
        path: String = "README.md",
        oldString: String? = "world",
        newString: String = "universe",
        isNew: Boolean = false,
        abstain: Boolean = false,
    ): String = buildJsonObject {
        put("abstain", abstain)
        put("reason", if (abstain) "unsure" else "")
        put("commit_message", "Fix docs")
        put("edits", buildJsonArray {
            add(buildJsonObject {
                put("path", path)
                put("is_new", isNew)
                if (oldString != null) put("old_string", oldString)
                put("new_string", newString)
            })
        })
    }.toString()

    private val files = mapOf("README.md" to "# Hello\nworld\n")

    // ---- plan ----

    @Test
    fun appliesValidEdit() = runBlocking {
        reply(planContent())
        val outcome = agent.plan("t", "b", files, danger = false)
        assertTrue(outcome is AgentOutcome.Ready)
        outcome as AgentOutcome.Ready
        assertEquals("# Hello\nuniverse\n", outcome.finalContents["README.md"])
        assertEquals("Fix docs", outcome.commitMessage)
        assertEquals(1, outcome.edits.size)
    }

    @Test
    fun parsesMarkdownFencedJson() = runBlocking {
        reply("```json\n${planContent()}\n```")
        assertTrue(agent.plan("t", "b", files, danger = false) is AgentOutcome.Ready)
    }

    @Test
    fun rejectsMissingOldString() = runBlocking {
        reply(planContent(oldString = "does-not-exist"))
        val outcome = agent.plan("t", "b", files, danger = false)
        assertTrue(outcome is AgentOutcome.Rejected)
        assertTrue((outcome as AgentOutcome.Rejected).reason.contains("not found"))
    }

    @Test
    fun rejectsAmbiguousOldString() = runBlocking {
        reply(planContent(oldString = "aa"))
        val outcome = agent.plan("t", "b", mapOf("README.md" to "aa aa"), danger = false)
        assertTrue(outcome is AgentOutcome.Rejected)
        assertTrue((outcome as AgentOutcome.Rejected).reason.contains("ambiguous"))
    }

    @Test
    fun abstainsWhenModelAbstains() = runBlocking {
        reply(planContent(abstain = true))
        val outcome = agent.plan("t", "b", files, danger = false)
        assertTrue(outcome is AgentOutcome.Abstained)
        assertEquals("unsure", (outcome as AgentOutcome.Abstained).reason)
    }

    @Test
    fun rejectsNewFileThatAlreadyExistsInFetchedSet() = runBlocking {
        reply(planContent(isNew = true, oldString = null))
        val outcome = agent.plan("t", "b", files, danger = false)
        assertTrue(outcome is AgentOutcome.Rejected)
    }

    @Test
    fun rejectsTooManyFiles() = runBlocking {
        val manyFiles = (1..4).associate { "f$it.md" to "content $it" }
        val content = buildJsonObject {
            put("abstain", false)
            put("commit_message", "m")
            put("edits", buildJsonArray {
                (1..4).forEach { i ->
                    add(buildJsonObject {
                        put("path", "f$i.md")
                        put("is_new", false)
                        put("old_string", "content $i")
                        put("new_string", "changed $i")
                    })
                }
            })
        }.toString()
        reply(content)
        val outcome = agent.plan("t", "b", manyFiles, danger = false)
        assertTrue(outcome is AgentOutcome.Rejected)
        assertTrue((outcome as AgentOutcome.Rejected).reason.contains("cap"))
    }

    @Test
    fun dangerModeTightensFileCap() = runBlocking {
        val threeFiles = (1..3).associate { "f$it.md" to "content $it" }
        val content = buildJsonObject {
            put("abstain", false)
            put("commit_message", "m")
            put("edits", buildJsonArray {
                (1..3).forEach { i ->
                    add(buildJsonObject {
                        put("path", "f$i.md")
                        put("is_new", false)
                        put("old_string", "content $i")
                        put("new_string", "changed $i")
                    })
                }
            })
        }.toString()
        reply(content)
        // 3 files is fine normally, but danger mode caps at 2.
        val outcome = agent.plan("t", "b", threeFiles, danger = true)
        assertTrue(outcome is AgentOutcome.Rejected)
    }

    @Test
    fun rejectsOversizeChange() = runBlocking {
        reply(planContent(oldString = "world", newString = "x".repeat(9001)))
        val outcome = agent.plan("t", "b", files, danger = false)
        assertTrue(outcome is AgentOutcome.Rejected)
        assertTrue((outcome as AgentOutcome.Rejected).reason.contains("large"))
    }

    @Test
    fun rejectsUnparseableModelOutput() = runBlocking {
        reply("I think you should probably change the file somehow")
        assertTrue(agent.plan("t", "b", files, danger = false) is AgentOutcome.Rejected)
    }

    // ---- locate ----

    @Test
    fun locateFiltersHallucinatedPaths() = runBlocking {
        reply("""{"files":["a.py","ghost.py"],"reason":"r"}""")
        val result = agent.locate("t", "b", listOf("a.py", "b.py"))
        assertEquals(listOf("a.py"), result)
    }

    @Test
    fun locateEmptyCandidatesShortCircuits() = runBlocking {
        // No server response enqueued — must not even attempt a call.
        assertEquals(emptyList<String>(), agent.locate("t", "b", emptyList()))
    }

    // ---- critique ----

    private fun readyOutcome(): AgentOutcome.Ready = AgentOutcome.Ready(
        edits = listOf(eu.euroswarms.surgeon.data.FileEdit("README.md", "world", "universe", false)),
        finalContents = mapOf("README.md" to "# Hello\nuniverse\n"),
        commitMessage = "Fix docs",
        notes = "",
    )

    @Test
    fun critiqueApproves() = runBlocking {
        reply("""{"approve":true,"reason":"minimal and complete"}""")
        val (approved, reason) = agent.critique("t", "b", readyOutcome(), danger = false)
        assertTrue(approved)
        assertEquals("minimal and complete", reason)
    }

    @Test
    fun critiqueRejects() = runBlocking {
        reply("""{"approve":false,"reason":"touches unrelated code"}""")
        val (approved, reason) = agent.critique("t", "b", readyOutcome(), danger = false)
        assertFalse(approved)
        assertEquals("touches unrelated code", reason)
    }

    @Test
    fun critiqueFailsClosedOnGarbageOutput() = runBlocking {
        reply("looks good to me!")
        val (approved, _) = agent.critique("t", "b", readyOutcome(), danger = false)
        assertFalse(approved)
    }
}

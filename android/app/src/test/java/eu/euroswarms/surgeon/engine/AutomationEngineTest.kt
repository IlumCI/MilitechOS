package eu.euroswarms.surgeon.engine

import eu.euroswarms.surgeon.data.AppConfig
import eu.euroswarms.surgeon.data.RepoTarget
import eu.euroswarms.surgeon.data.Store
import eu.euroswarms.surgeon.net.GitHubClient
import eu.euroswarms.surgeon.net.OllamaClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end pipeline tests: a fake GitHub API and a fake Ollama server drive the REAL engine,
 * REAL clients, and REAL agent through the complete issue → draft workflow.
 */
class AutomationEngineTest {

    private lateinit var gh: MockWebServer
    private lateinit var ollama: MockWebServer
    private lateinit var storeDir: File
    private lateinit var store: Store

    /** (method, path) of every GitHub call, in order. */
    private val recorded = CopyOnWriteArrayList<Pair<String, String>>()

    /** "METHOD path" → last request body. */
    private val bodies = ConcurrentHashMap<String, String>()

    private val ollamaCalls = AtomicInteger(0)

    // Behaviour switches, flipped per test.
    @Volatile private var forkHeadSha = "AAA"
    @Volatile private var recheckState = "open"
    @Volatile private var criticApprove = true
    @Volatile private var issueLabel: String? = null
    @Volatile private var rateLimitRepoMeta = false

    private val readmeB64: String = Base64.getEncoder().encodeToString("# Hello\nworld\n".toByteArray())

    private fun issueJson(state: String): String {
        val labels = issueLabel?.let { """[{"name":"$it"}]""" } ?: "[]"
        return """{"number":7,"title":"Fix the docs","body":"Please change world to universe in README",""" +
            """"html_url":"http://gh/up/repo/issues/7","assignees":[],"labels":$labels,"state":"$state","locked":false}"""
    }

    private fun json(body: String, code: Int = 200): MockResponse =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun ghRoute(method: String, path: String): MockResponse = when {
        method == "GET" && path == "/user" -> json("""{"login":"tester"}""")

        method == "GET" && path == "/repos/up/repo" ->
            if (rateLimitRepoMeta) {
                MockResponse().setResponseCode(403)
                    .setHeader("x-ratelimit-remaining", "0")
                    .setBody("""{"message":"API rate limit exceeded"}""")
            } else {
                json("""{"full_name":"up/repo","private":false,"default_branch":"main","fork":false}""")
            }

        method == "GET" && path.startsWith("/repos/up/repo/issues/7") -> json(issueJson(recheckState))
        method == "GET" && path.startsWith("/repos/up/repo/issues") -> json("[${issueJson("open")}]")

        method == "GET" && path == "/repos/tester/repo" ->
            json("""{"full_name":"tester/repo","private":false,"default_branch":"main","fork":true,"parent":{"full_name":"up/repo"}}""")

        method == "POST" && path == "/repos/tester/repo/merge-upstream" -> json("{}")

        method == "GET" && path == "/repos/up/repo/git/ref/heads/main" -> json("""{"object":{"sha":"AAA"}}""")
        method == "GET" && path == "/repos/tester/repo/git/ref/heads/main" -> json("""{"object":{"sha":"$forkHeadSha"}}""")

        method == "GET" && path.startsWith("/repos/tester/repo/git/trees/AAA") ->
            json("""{"tree":[{"path":"README.md","type":"blob"}],"truncated":false}""")

        method == "GET" && path.startsWith("/repos/tester/repo/contents/README.md") ->
            json("""{"content":"$readmeB64","encoding":"base64"}""")

        method == "POST" && path == "/repos/tester/repo/git/refs" -> json("{}", 201)
        method == "GET" && path == "/repos/tester/repo/git/commits/AAA" -> json("""{"tree":{"sha":"TTT"}}""")
        method == "POST" && path == "/repos/tester/repo/git/blobs" -> json("""{"sha":"BLOB1"}""")
        method == "POST" && path == "/repos/tester/repo/git/trees" -> json("""{"sha":"TREE2"}""")
        method == "POST" && path == "/repos/tester/repo/git/commits" -> json("""{"sha":"COMMIT1"}""")
        method == "PATCH" && path.startsWith("/repos/tester/repo/git/refs/heads/surgeon") -> json("{}")

        method == "GET" && path.startsWith("/repos/tester/repo/branches") ->
            // Includes the branch a happy-path draft would create, so the janitor's
            // "spare live drafts" behaviour is actually exercised.
            json("""[{"name":"main"},{"name":"surgeon/issue-99-orphan"},{"name":"surgeon/issue-7-fix-the-docs"}]""")
        method == "DELETE" && path.startsWith("/repos/tester/repo/git/refs/heads/surgeon") ->
            MockResponse().setResponseCode(204)

        else -> json("""{"message":"unrouted: $method $path"}""", 404)
    }

    private val planContent: String = buildJsonObject {
        put("abstain", false)
        put("reason", "")
        put("commit_message", "Fix docs")
        put("edits", buildJsonArray {
            add(buildJsonObject {
                put("path", "README.md")
                put("is_new", false)
                put("old_string", "world")
                put("new_string", "universe")
            })
        })
    }.toString()

    private fun chatReply(content: String): MockResponse {
        val body = buildJsonObject {
            put("message", buildJsonObject {
                put("role", "assistant")
                put("content", content)
            })
            put("done", true)
        }.toString()
        return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    }

    @Before
    fun setUp() {
        recorded.clear()
        bodies.clear()
        ollamaCalls.set(0)
        forkHeadSha = "AAA"
        recheckState = "open"
        criticApprove = true
        issueLabel = null
        rateLimitRepoMeta = false

        gh = MockWebServer()
        gh.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val method = request.method ?: ""
                val path = request.path ?: ""
                recorded.add(method to path)
                val body = request.body.readUtf8()
                if (body.isNotBlank()) bodies["$method $path"] = body
                return ghRoute(method, path)
            }
        }
        gh.start()

        ollama = MockWebServer()
        ollama.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                ollamaCalls.incrementAndGet()
                val body = request.body.readUtf8()
                val content = when {
                    body.contains("code navigator") -> """{"files":["README.md"],"reason":"r"}"""
                    body.contains("strict reviewer") ->
                        if (criticApprove) """{"approve":true,"reason":"ok"}"""
                        else """{"approve":false,"reason":"not minimal"}"""
                    else -> planContent
                }
                return chatReply(content)
            }
        }
        ollama.start()

        storeDir = Files.createTempDirectory("engine-test").toFile()
        store = Store(storeDir)
    }

    @After
    fun tearDown() {
        gh.shutdown()
        ollama.shutdown()
    }

    private fun engine(): AutomationEngine {
        val cfg = AppConfig(
            githubToken = "t",
            ollamaBaseUrl = ollama.url("/").toString(),
            ollamaModel = "test-model",
            authorName = "Ilum",
            authorEmail = "Ilum@linux.org",
            repos = listOf(RepoTarget("up", "repo")),
        )
        return AutomationEngine(
            cfg, store,
            GitHubClient("t", gh.url("/").toString()),
            SurgicalAgent(OllamaClient(cfg.ollamaBaseUrl, cfg.ollamaModel, "")),
        )
    }

    private fun paths(method: String) = recorded.filter { it.first == method }.map { it.second }

    // ---- the full happy path ----

    @Test
    fun happyPathProducesCorrectDraft() = runBlocking {
        val result = engine().runOnce()

        assertTrue("expected Drafted, got $result", result is EngineResult.Drafted)
        val draft = (result as EngineResult.Drafted).draft

        // Branch derived from issue number + slug, on the right fork.
        assertTrue(draft.headBranch.startsWith("surgeon/issue-7-fix-the-docs"))
        assertEquals("tester/repo", draft.forkFullName)
        assertEquals("up/repo", draft.repoFullName)
        assertEquals("COMMIT1", draft.commitSha)
        assertEquals("Fix docs", draft.commitMessage)
        assertTrue(draft.compareUrl.contains("up/repo/compare/main...tester:repo:surgeon/issue-7"))

        // The blob really carries the surgically-edited content.
        val blobBody = bodies["POST /repos/tester/repo/git/blobs"]!!
        assertTrue(blobBody.contains("universe"))
        assertFalse(blobBody.contains("world"))

        // The commit is authored/committed as the configured identity, on the right parent.
        val commitBody = bodies["POST /repos/tester/repo/git/commits"]!!
        assertTrue(commitBody.contains("\"name\":\"Ilum\""))
        assertTrue(commitBody.contains("\"email\":\"Ilum@linux.org\""))
        assertTrue(commitBody.contains("\"parents\":[\"AAA\"]"))

        // Exactly three model calls: locate, plan, critic.
        assertEquals(3, ollamaCalls.get())

        // Issue is permanently processed; store carries the draft.
        assertTrue(store.isProcessed("up/repo", 7))
        assertEquals(1, store.drafts.value.size)
    }

    // ---- safety gates ----

    @Test
    fun discardsWorkWhenIssueClosesMidRun() = runBlocking {
        recheckState = "closed" // the pre-commit re-fetch sees a dead issue
        val result = engine().runOnce()

        assertTrue(result is EngineResult.Skipped)
        assertEquals("Issue no longer workable", (result as EngineResult.Skipped).reason)
        // Nothing was committed: no branch creation, no ref update.
        assertTrue(paths("POST").none { it.endsWith("/git/refs") })
        assertTrue(paths("PATCH").isEmpty())
        assertTrue(store.isProcessed("up/repo", 7))
        assertEquals(0, store.drafts.value.size)
    }

    @Test
    fun refusesDivergedFork() = runBlocking {
        forkHeadSha = "ZZZ"
        val result = engine().runOnce()

        assertTrue(result is EngineResult.Skipped)
        assertEquals("Fork diverged from upstream", (result as EngineResult.Skipped).reason)
        // Never read the tree, never called the model.
        assertTrue(paths("GET").none { it.contains("/git/trees/") })
        assertEquals(0, ollamaCalls.get())
    }

    @Test
    fun criticRejectionIsRetryableSkip() = runBlocking {
        criticApprove = false
        val result = engine().runOnce()

        assertTrue(result is EngineResult.Skipped)
        assertTrue((result as EngineResult.Skipped).reason.contains("Critic rejected"))
        // Remembered as retryable, NOT permanently processed.
        assertTrue(store.isSkipped("up/repo", 7))
        assertFalse(store.isProcessed("up/repo", 7))
        // Nothing was committed.
        assertTrue(paths("POST").none { it.endsWith("/git/refs") })
    }

    @Test
    fun skipsIssuesWithDisqualifyingLabels() = runBlocking {
        issueLabel = "wontfix"
        val result = engine().runOnce()
        assertTrue("expected NoWork, got $result", result is EngineResult.NoWork)
        assertEquals(0, ollamaCalls.get())
    }

    @Test
    fun rateLimitSurfacesAsFailure() = runBlocking {
        rateLimitRepoMeta = true
        val result = engine().runOnce()
        assertTrue(result is EngineResult.Failed)
        assertTrue((result as EngineResult.Failed).reason.contains("Rate limited"))
    }

    // ---- manual pick ----

    @Test
    fun manualPickDraftsSpecificIssue() = runBlocking {
        val result = engine().runOnIssue(RepoTarget("up", "repo"), 7)
        assertTrue(result is EngineResult.Drafted)
    }

    @Test
    fun manualPickBypassesSkipMemory() = runBlocking {
        store.markSkipped("up/repo", 7) // e.g. a previous abstain
        val result = engine().runOnIssue(RepoTarget("up", "repo"), 7)
        assertTrue("manual pick must retry skipped issues, got $result", result is EngineResult.Drafted)
    }

    @Test
    fun manualPickRefusesAlreadyProcessedIssue() = runBlocking {
        store.markProcessed("up/repo", 7)
        val result = engine().runOnIssue(RepoTarget("up", "repo"), 7)
        assertTrue(result is EngineResult.Skipped)
        assertTrue((result as EngineResult.Skipped).reason.contains("already"))
    }

    @Test
    fun skippedIssueIsNotPickedAutomatically() = runBlocking {
        store.markSkipped("up/repo", 7)
        val result = engine().runOnce()
        assertTrue(result is EngineResult.NoWork)
    }

    // ---- branch janitor ----

    @Test
    fun cleanupDeletesOrphanSurgeonBranchesButNeverMain() = runBlocking {
        // No live drafts: both surgeon/* branches are orphans; "main" must survive.
        val deleted = engine().cleanupOrphanBranches()
        assertEquals(2, deleted)
        val deletes = paths("DELETE")
        assertEquals(2, deletes.size)
        assertTrue(deletes.all { it.contains("surgeon") })
    }

    @Test
    fun cleanupSparesBranchesOfLiveDrafts() = runBlocking {
        // First produce a real draft, whose branch must survive cleanup.
        val drafted = engine().runOnce()
        assertTrue(drafted is EngineResult.Drafted)
        assertEquals("surgeon/issue-7-fix-the-docs", (drafted as EngineResult.Drafted).draft.headBranch)

        recorded.clear()
        val deleted = engine().cleanupOrphanBranches()
        // Only the orphan is deleted; the live draft's branch (present in the listing) is spared.
        assertEquals(1, deleted)
        val deletes = paths("DELETE")
        assertTrue(deletes.all { it.contains("issue-99-orphan") })
        assertTrue(deletes.none { it.contains("issue-7") })
    }
}

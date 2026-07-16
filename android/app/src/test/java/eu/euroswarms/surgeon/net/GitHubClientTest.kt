package eu.euroswarms.surgeon.net

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Base64

class GitHubClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: GitHubClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = GitHubClient("test-token", server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String, code: Int = 200): MockResponse =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    // ---- content decoding ----

    @Test
    fun decodesBase64FileContentWithLineBreaks() = runBlocking {
        val text = "# Hello\nworld\n"
        // GitHub returns base64 with embedded line breaks; inject one explicitly
        // (\\n in the JSON source becomes a real newline inside the parsed string).
        val raw = Base64.getEncoder().encodeToString(text.toByteArray())
        val withBreak = raw.substring(0, 8) + "\\n" + raw.substring(8)
        server.enqueue(json("""{"content":"$withBreak","encoding":"base64"}"""))
        assertEquals(text, client.getFileContent("o", "r", "README.md", "main"))
    }

    // ---- issue listing ----

    @Test
    fun listOpenIssuesFiltersOutPullRequests() = runBlocking {
        server.enqueue(
            json(
                """[
                  {"number":1,"title":"real issue","state":"open"},
                  {"number":2,"title":"a PR","state":"open","pull_request":{"url":"x"}}
                ]""",
            ),
        )
        val issues = client.listOpenIssues("o", "r")
        assertEquals(1, issues.size)
        assertEquals(1, issues.first().number)
    }

    // ---- auth header ----

    @Test
    fun sendsBearerTokenAndApiVersion() = runBlocking {
        server.enqueue(json("""{"login":"me"}"""))
        client.getAuthenticatedUser()
        val request = server.takeRequest()
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
        assertEquals("2022-11-28", request.getHeader("X-GitHub-Api-Version"))
    }

    // ---- rate limiting ----

    @Test
    fun classifies403WithDrainedQuotaAsRateLimit() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setHeader("x-ratelimit-remaining", "0")
                .setBody("""{"message":"API rate limit exceeded"}"""),
        )
        try {
            client.getAuthenticatedUser()
            fail("expected RateLimitException")
        } catch (e: RateLimitException) {
            assertEquals(403, e.code)
        }
    }

    @Test
    fun classifies429AsRateLimitWithRetryAfter() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(429)
                .setHeader("retry-after", "60")
                .setBody("""{"message":"too many requests"}"""),
        )
        try {
            client.getAuthenticatedUser()
            fail("expected RateLimitException")
        } catch (e: RateLimitException) {
            assertEquals(429, e.code)
            assertEquals(60L, e.retryAfterSeconds)
        }
    }

    @Test
    fun ordinary403IsNotRateLimit() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(403)
                .setHeader("x-ratelimit-remaining", "4999")
                .setBody("""{"message":"Resource not accessible by integration"}"""),
        )
        try {
            client.getAuthenticatedUser()
            fail("expected ApiException")
        } catch (e: RateLimitException) {
            fail("must not be classified as rate limit")
        } catch (e: ApiException) {
            assertEquals(403, e.code)
        }
    }

    // ---- fork verification ----

    @Test
    fun verifyForkAcceptsProperFork() = runBlocking {
        server.enqueue(
            json("""{"full_name":"me/r","fork":true,"parent":{"full_name":"up/r"},"default_branch":"main"}"""),
        )
        client.verifyFork("me", "r", "up", "r") // must not throw
    }

    @Test
    fun verifyForkRejectsWrongParent() = runBlocking {
        server.enqueue(
            json("""{"full_name":"me/r","fork":true,"parent":{"full_name":"other/thing"},"default_branch":"main"}"""),
        )
        try {
            client.verifyFork("me", "r", "up", "r")
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertTrue(e.message!!.contains("Refusing"))
        }
    }

    @Test
    fun verifyForkRejectsNonForkRepo() = runBlocking {
        server.enqueue(
            json("""{"full_name":"me/r","fork":false,"default_branch":"main"}"""),
        )
        try {
            client.verifyFork("me", "r", "up", "r")
            fail("expected ApiException")
        } catch (e: ApiException) {
            assertTrue(e.message!!.contains("Refusing"))
        }
    }

    // ---- git data plumbing ----

    @Test
    fun createCommitSendsIdentityAndParent() = runBlocking {
        server.enqueue(json("""{"sha":"C1"}"""))
        val sha = client.createCommit(
            forkOwner = "me", name = "r", message = "msg",
            treeSha = "T1", parentSha = "P1",
            authorName = "Ilum", authorEmail = "Ilum@linux.org",
            isoDate = "2026-01-01T00:00:00Z",
        )
        assertEquals("C1", sha)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"name\":\"Ilum\""))
        assertTrue(body.contains("\"email\":\"Ilum@linux.org\""))
        assertTrue(body.contains("\"parents\":[\"P1\"]"))
    }

    @Test
    fun deleteRefSendsDeleteToRefPath() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(204))
        client.deleteRef("me", "r", "surgeon/issue-1-x")
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertTrue(request.path!!.startsWith("/repos/me/r/git/refs/heads/surgeon"))
    }

    @Test
    fun listBranchesParsesNames() = runBlocking {
        server.enqueue(json("""[{"name":"main"},{"name":"surgeon/issue-2-y"}]"""))
        assertEquals(listOf("main", "surgeon/issue-2-y"), client.listBranches("me", "r"))
    }
}

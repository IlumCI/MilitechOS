package eu.euroswarms.surgeon.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.Base64

// ---------- Response DTOs ----------

@Serializable
data class GhUser(val login: String = "")

@Serializable
data class GhParent(@SerialName("full_name") val fullName: String = "")

@Serializable
data class GhRepo(
    @SerialName("full_name") val fullName: String = "",
    @SerialName("private") val isPrivate: Boolean = false,
    @SerialName("default_branch") val defaultBranch: String = "main",
    val fork: Boolean = false,
    val parent: GhParent? = null,
)

@Serializable
data class GhLabel(val name: String = "")

@Serializable
data class GhIssue(
    val number: Int,
    val title: String = "",
    val body: String? = null,
    @SerialName("html_url") val htmlUrl: String = "",
    @SerialName("pull_request") val pullRequest: JsonElement? = null,
    val assignees: List<GhUser> = emptyList(),
    val labels: List<GhLabel> = emptyList(),
    val state: String = "open",
    val locked: Boolean = false,
) {
    val isPullRequest: Boolean get() = pullRequest != null
}

@Serializable
private data class GhObj(val sha: String = "")

@Serializable
private data class GhRef(@SerialName("object") val obj: GhObj = GhObj())

@Serializable
private data class GhCommitObj(val tree: GhObj = GhObj())

@Serializable
private data class GhTreeEntry(val path: String = "", val type: String = "")

@Serializable
private data class GhTree(val tree: List<GhTreeEntry> = emptyList(), val truncated: Boolean = false)

@Serializable
private data class GhContent(val content: String = "", val encoding: String = "")

@Serializable
private data class GhShaResp(val sha: String = "")

@Serializable
private data class GhBranch(val name: String = "")

/** Client for the subset of the GitHub REST + Git Data API the pipeline needs. */
class GitHubClient(
    private val token: String,
    baseUrl: String = "https://api.github.com",
) {

    private val base = baseUrl.trimEnd('/')
    private val jsonMedia = "application/json".toMediaType()

    // ---------- Public API ----------

    suspend fun getAuthenticatedUser(): GhUser =
        Http.json.decodeFromString(GhUser.serializer(), get("$base/user"))

    suspend fun getRepo(owner: String, name: String): GhRepo =
        Http.json.decodeFromString(GhRepo.serializer(), get("$base/repos/$owner/$name"))

    suspend fun listOpenIssues(owner: String, name: String, perPage: Int = 50): List<GhIssue> {
        val body = get("$base/repos/$owner/$name/issues?state=open&per_page=$perPage&sort=updated&direction=desc")
        return Http.json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(GhIssue.serializer()), body,
        ).filter { !it.isPullRequest }
    }

    /** Fetch a single issue's current state (for re-verification right before committing). */
    suspend fun getIssue(owner: String, name: String, number: Int): GhIssue =
        Http.json.decodeFromString(GhIssue.serializer(), get("$base/repos/$owner/$name/issues/$number"))

    /**
     * Ensure the authenticated user has a fork of owner/name and that its git data is ready.
     * Verifies that any repo already sitting at myLogin/name really is a fork of THIS upstream —
     * otherwise we could commit branches into an unrelated same-named repository.
     */
    suspend fun ensureFork(owner: String, name: String, myLogin: String, baseBranch: String): String {
        val existing = runCatching { getRepo(myLogin, name) }.getOrNull()
        if (existing != null) {
            requireIsForkOf(existing, owner, name, myLogin, name)
            return myLogin
        }
        // Create the fork (async on GitHub's side).
        post("$base/repos/$owner/$name/forks", buildJsonObject { })
        // Poll until the fork's metadata AND git data are queryable (fresh forks
        // report repo metadata before their branches are readable).
        repeat(20) {
            delay(2000)
            val repo = runCatching { getRepo(myLogin, name) }.getOrNull()
            if (repo != null) {
                requireIsForkOf(repo, owner, name, myLogin, name)
                val branchReady = runCatching { getBranchHeadSha(myLogin, name, baseBranch) }.isSuccess
                if (branchReady) return myLogin
            }
        }
        throw ApiException(504, "", "Fork of $owner/$name did not become available in time")
    }

    /**
     * Verify a user-specified fork: it must exist, be a fork, and its parent must be exactly
     * the intended upstream. Used when the fork mapping is configured manually.
     */
    suspend fun verifyFork(forkOwner: String, forkName: String, upstreamOwner: String, upstreamName: String) {
        val repo = runCatching { getRepo(forkOwner, forkName) }.getOrElse {
            throw ApiException(
                404, "",
                "Configured fork $forkOwner/$forkName is not accessible with this token",
            )
        }
        requireIsForkOf(repo, upstreamOwner, upstreamName, forkOwner, forkName)
    }

    private fun requireIsForkOf(
        repo: GhRepo,
        upstreamOwner: String,
        upstreamName: String,
        forkOwner: String,
        forkName: String,
    ) {
        val expected = "$upstreamOwner/$upstreamName"
        val actualParent = repo.parent?.fullName.orEmpty()
        if (!repo.fork || !actualParent.equals(expected, ignoreCase = true)) {
            throw ApiException(
                409, "",
                "Repo $forkOwner/$forkName exists but is not a fork of $expected " +
                    "(fork=${repo.fork}, parent=${actualParent.ifBlank { "none" }}). Refusing to touch it.",
            )
        }
    }

    /** Fast-forward the fork's [branch] to upstream. Best-effort: diverged/conflicting forks are left as-is. */
    suspend fun syncForkBranch(forkOwner: String, name: String, branch: String) {
        runCatching {
            post(
                "$base/repos/$forkOwner/$name/merge-upstream",
                buildJsonObject { put("branch", branch) },
            )
        }
    }

    suspend fun getBranchHeadSha(owner: String, name: String, branch: String): String {
        val body = get("$base/repos/$owner/$name/git/ref/heads/${enc(branch)}")
        return Http.json.decodeFromString(GhRef.serializer(), body).obj.sha
    }

    suspend fun getCommitTreeSha(owner: String, name: String, commitSha: String): String {
        val body = get("$base/repos/$owner/$name/git/commits/$commitSha")
        return Http.json.decodeFromString(GhCommitObj.serializer(), body).tree.sha
    }

    suspend fun createBranch(forkOwner: String, name: String, newBranch: String, sha: String) {
        post(
            "$base/repos/$forkOwner/$name/git/refs",
            buildJsonObject {
                put("ref", "refs/heads/$newBranch")
                put("sha", sha)
            },
        )
    }

    /** Returns source-file paths in the tree at [sha]. Filtered to editable text extensions and capped. */
    suspend fun listSourcePaths(owner: String, name: String, sha: String, cap: Int = 1000): List<String> {
        val body = get("$base/repos/$owner/$name/git/trees/$sha?recursive=1")
        val tree = Http.json.decodeFromString(GhTree.serializer(), body)
        return tree.tree
            .asSequence()
            .filter { it.type == "blob" }
            .map { it.path }
            .filter { path -> EDITABLE_EXT.any { path.endsWith(it) } }
            .filterNot { path -> IGNORED_DIRS.any { path.contains(it) } }
            .take(cap)
            .toList()
    }

    suspend fun getFileContent(owner: String, name: String, path: String, ref: String): String {
        val body = get("$base/repos/$owner/$name/contents/${encPath(path)}?ref=${enc(ref)}")
        val content = Http.json.decodeFromString(GhContent.serializer(), body)
        if (content.encoding != "base64" || content.content.isBlank()) return ""
        val cleaned = content.content.replace("\n", "").replace("\r", "")
        return String(Base64.getDecoder().decode(cleaned), Charsets.UTF_8)
    }

    suspend fun createBlob(forkOwner: String, name: String, content: String): String {
        val body = post(
            "$base/repos/$forkOwner/$name/git/blobs",
            buildJsonObject {
                put("content", content)
                put("encoding", "utf-8")
            },
        )
        return Http.json.decodeFromString(GhShaResp.serializer(), body).sha
    }

    /** entries: list of (path -> blobSha). Creates a tree layered on [baseTreeSha]. */
    suspend fun createTree(
        forkOwner: String,
        name: String,
        baseTreeSha: String,
        entries: List<Pair<String, String>>,
    ): String {
        val treeArray: JsonArray = buildJsonArray {
            entries.forEach { (path, blobSha) ->
                add(
                    buildJsonObject {
                        put("path", path)
                        put("mode", "100644")
                        put("type", "blob")
                        put("sha", blobSha)
                    },
                )
            }
        }
        val body = post(
            "$base/repos/$forkOwner/$name/git/trees",
            buildJsonObject {
                put("base_tree", baseTreeSha)
                put("tree", treeArray)
            },
        )
        return Http.json.decodeFromString(GhShaResp.serializer(), body).sha
    }

    suspend fun createCommit(
        forkOwner: String,
        name: String,
        message: String,
        treeSha: String,
        parentSha: String,
        authorName: String,
        authorEmail: String,
        isoDate: String,
    ): String {
        val identity = buildJsonObject {
            put("name", authorName)
            put("email", authorEmail)
            put("date", isoDate)
        }
        val body = post(
            "$base/repos/$forkOwner/$name/git/commits",
            buildJsonObject {
                put("message", message)
                put("tree", treeSha)
                put("parents", buildJsonArray { add(parentSha) })
                put("author", identity)
                put("committer", identity)
            },
        )
        return Http.json.decodeFromString(GhShaResp.serializer(), body).sha
    }

    suspend fun updateRef(forkOwner: String, name: String, branch: String, sha: String) {
        patch(
            "$base/repos/$forkOwner/$name/git/refs/heads/${enc(branch)}",
            buildJsonObject {
                put("sha", sha)
                put("force", false)
            },
        )
    }

    suspend fun deleteRef(owner: String, name: String, branch: String) {
        delete("$base/repos/$owner/$name/git/refs/heads/${enc(branch)}")
    }

    suspend fun listBranches(owner: String, name: String): List<String> {
        val body = get("$base/repos/$owner/$name/branches?per_page=100")
        return Http.json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(GhBranch.serializer()), body,
        ).map { it.name }
    }

    // ---------- HTTP plumbing ----------

    private fun baseRequest(url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "Surgeon-Android")

    private suspend fun get(url: String): String = exec(baseRequest(url).get().build())

    private suspend fun post(url: String, body: JsonObject): String =
        exec(baseRequest(url).post(body.toString().toRequestBody(jsonMedia)).build())

    private suspend fun patch(url: String, body: JsonObject): String =
        exec(baseRequest(url).patch(body.toString().toRequestBody(jsonMedia)).build())

    private suspend fun delete(url: String): String =
        exec(baseRequest(url).delete().build())

    private suspend fun exec(request: Request): String = withContext(Dispatchers.IO) {
        Http.client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                // GitHub signals rate limiting via 429, or 403 with a drained quota header.
                val remaining = resp.header("x-ratelimit-remaining")
                val isRateLimit = resp.code == 429 ||
                    (resp.code == 403 && (remaining == "0" || body.contains("rate limit", ignoreCase = true)))
                if (isRateLimit) {
                    val retryAfter = resp.header("retry-after")?.toLongOrNull()
                        ?: resp.header("x-ratelimit-reset")?.toLongOrNull()
                            ?.let { reset -> maxOf(0L, reset - System.currentTimeMillis() / 1000) }
                    throw RateLimitException(
                        resp.code, body, retryAfter,
                        "GitHub rate limit hit (${resp.code}); retry after ${retryAfter ?: "unknown"}s",
                    )
                }
                throw ApiException(resp.code, body, "GitHub ${request.method} ${request.url.encodedPath} → ${resp.code}")
            }
            body
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // Encode a repo path segment-by-segment so slashes survive.
    private fun encPath(path: String): String =
        path.split("/").joinToString("/") { enc(it) }

    companion object {
        private val EDITABLE_EXT = listOf(
            ".py", ".md", ".txt", ".rst", ".toml", ".cfg", ".ini", ".yaml", ".yml",
            ".json", ".ts", ".tsx", ".js", ".jsx", ".css", ".scss", ".html", ".mdx",
            ".sh", ".env.example", ".gitignore", "Dockerfile",
        )
        private val IGNORED_DIRS = listOf(
            "node_modules/", "dist/", "build/", ".next/", "vendor/", "__pycache__/",
            "site-packages/", ".venv/", "migrations/",
        )
    }
}

# Smart DefaultBranchResolver Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the hardcoded `defaultTargetBranch ?: "develop"` pattern with a 6-priority cascading resolver that auto-detects the correct target branch using per-branch overrides, PR data, merge-base heuristics, and Bitbucket API.

**Architecture:** Project-level service (`DefaultBranchResolver`) with `suspend fun resolve(repo)` that runs a 6-priority chain: per-branch override → PR target → merge-base against PR branches → Bitbucket default branch → origin/HEAD → settings fallback. Cache invalidated on `BranchChanged` events.

**Tech Stack:** Kotlin, IntelliJ Platform SDK, git4idea, OkHttp (Bitbucket API), kotlinx.serialization (JSON), JUnit 5 + Turbine (tests)

**Spec:** `docs/superpowers/specs/2026-03-25-smart-default-branch-resolver-design.md`

---

### Task 1: Add `branchTargetOverrides` to PluginSettings

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt:87` (State class)

- [ ] **Step 1: Add the field to State class**

In `PluginSettings.kt`, inside the `State` class (after line 87 where `defaultTargetBranch` is), add:

```kotlin
var branchTargetOverrides by string("")
```

This stores a JSON string like `{"repoPath||branchName": "targetBranch"}`.

- [ ] **Step 2: Build to verify**

Run: `./gradlew :core:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt
git commit -m "feat: add branchTargetOverrides field to PluginSettings"
```

---

### Task 2: Add GitMergeBaseUtil

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/util/GitMergeBaseUtil.kt`
- Create: `core/src/test/kotlin/com/workflow/orchestrator/core/util/GitMergeBaseUtilTest.kt`

- [ ] **Step 1: Write failing tests**

Create `core/src/test/kotlin/com/workflow/orchestrator/core/util/GitMergeBaseUtilTest.kt`:

```kotlin
package com.workflow.orchestrator.core.util

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class GitMergeBaseUtilTest {

    @Test
    fun `parseMergeBaseOutput extracts commit hash`() {
        val output = "abc123def456\n"
        val result = GitMergeBaseUtil.parseMergeBaseOutput(output)
        assertEquals("abc123def456", result)
    }

    @Test
    fun `parseMergeBaseOutput returns null for empty output`() {
        assertNull(GitMergeBaseUtil.parseMergeBaseOutput(""))
        assertNull(GitMergeBaseUtil.parseMergeBaseOutput("  \n"))
    }

    @Test
    fun `parseRevListCount extracts count`() {
        assertEquals(5, GitMergeBaseUtil.parseRevListCount("5\n"))
        assertEquals(0, GitMergeBaseUtil.parseRevListCount("0\n"))
    }

    @Test
    fun `parseRevListCount returns max on invalid input`() {
        assertEquals(Int.MAX_VALUE, GitMergeBaseUtil.parseRevListCount(""))
        assertEquals(Int.MAX_VALUE, GitMergeBaseUtil.parseRevListCount("not-a-number"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.util.GitMergeBaseUtilTest"`
Expected: FAIL — class not found

- [ ] **Step 3: Write the implementation**

Create `core/src/main/kotlin/com/workflow/orchestrator/core/util/GitMergeBaseUtil.kt`:

```kotlin
package com.workflow.orchestrator.core.util

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler

object GitMergeBaseUtil {

    private val log = Logger.getInstance(GitMergeBaseUtil::class.java)

    /**
     * Find the merge-base commit between two branches.
     * Returns the commit hash, or null on failure.
     */
    fun findMergeBase(project: Project, root: VirtualFile, branch1: String, branch2: String): String? {
        return try {
            val handler = GitLineHandler(project, root, GitCommand.MERGE_BASE)
            handler.addParameters(branch1, branch2)
            val result = Git.getInstance().runCommand(handler)
            if (result.success()) {
                parseMergeBaseOutput(result.outputAsJoinedString)
            } else {
                log.info("[Git:MergeBase] merge-base failed for '$branch1' and '$branch2': ${result.errorOutputAsJoinedString}")
                null
            }
        } catch (e: Exception) {
            log.info("[Git:MergeBase] Exception running merge-base: ${e.message}")
            null
        }
    }

    /**
     * Count commits between mergeBase and the tip of a branch.
     * Returns Int.MAX_VALUE on failure.
     */
    fun countDivergingCommits(project: Project, root: VirtualFile, from: String, mergeBase: String): Int {
        return try {
            val handler = GitLineHandler(project, root, GitCommand.REV_LIST)
            handler.addParameters("--count", "$mergeBase..$from")
            val result = Git.getInstance().runCommand(handler)
            if (result.success()) {
                parseRevListCount(result.outputAsJoinedString)
            } else {
                Int.MAX_VALUE
            }
        } catch (e: Exception) {
            Int.MAX_VALUE
        }
    }

    /** Visible for testing */
    fun parseMergeBaseOutput(output: String): String? {
        val trimmed = output.trim()
        return trimmed.ifBlank { null }
    }

    /** Visible for testing */
    fun parseRevListCount(output: String): Int {
        return output.trim().toIntOrNull() ?: Int.MAX_VALUE
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.util.GitMergeBaseUtilTest"`
Expected: PASS — all 4 tests

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/util/GitMergeBaseUtil.kt \
       core/src/test/kotlin/com/workflow/orchestrator/core/util/GitMergeBaseUtilTest.kt
git commit -m "feat: add GitMergeBaseUtil for merge-base and rev-list operations"
```

---

### Task 3: Add `getDefaultBranch()` and `getAllPullRequests()` to BitbucketBranchClient

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/bitbucket/BitbucketBranchClient.kt`

- [ ] **Step 1: Add `getDefaultBranch` method**

Add after the existing `getBranches()` method (around line 475):

```kotlin
/**
 * Get the configured default branch for a repository.
 * GET /rest/api/1.0/projects/{proj}/repos/{repo}/default-branch
 */
suspend fun getDefaultBranch(
    projectKey: String,
    repoSlug: String
): ApiResult<BitbucketBranch> = withContext(Dispatchers.IO) {
    try {
        val url = "$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/default-branch"
        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("Authorization", "Bearer ${tokenProvider()}")
            .build()
        val response = httpClient.newCall(request).execute()
        response.use {
            when (it.code) {
                in 200..299 -> {
                    val body = it.body?.string() ?: ""
                    ApiResult.Success(json.decodeFromString<BitbucketBranch>(body))
                }
                else -> {
                    log.info("[Bitbucket:API] default-branch -> ${it.code}")
                    ApiResult.Error(ErrorType.NOT_FOUND, "Could not fetch default branch: ${it.code}")
                }
            }
        }
    } catch (e: Exception) {
        log.warn("[Bitbucket:API] default-branch failed: ${e.message}")
        ApiResult.Error(ErrorType.NETWORK_ERROR, e.message ?: "Network error")
    }
}
```

- [ ] **Step 2: Add `getAllPullRequests` method**

Add after `getDefaultBranch`:

```kotlin
/**
 * Get all open pull requests for a repository (no user/role filter).
 * Used by DefaultBranchResolver to build merge-base candidate list.
 */
suspend fun getAllPullRequests(
    projectKey: String,
    repoSlug: String,
    state: String = "OPEN",
    limit: Int = 100
): ApiResult<List<BitbucketPrResponse>> = withContext(Dispatchers.IO) {
    try {
        val url = "$baseUrl/rest/api/1.0/projects/$projectKey/repos/$repoSlug/pull-requests" +
            "?state=$state&limit=$limit"
        val request = Request.Builder()
            .url(url)
            .get()
            .addHeader("Authorization", "Bearer ${tokenProvider()}")
            .build()
        val response = httpClient.newCall(request).execute()
        response.use {
            when (it.code) {
                in 200..299 -> {
                    val body = it.body?.string() ?: ""
                    val parsed = json.decodeFromString<BitbucketPrListResponse>(body)
                    ApiResult.Success(parsed.values)
                }
                else -> {
                    log.info("[Bitbucket:API] all-pull-requests -> ${it.code}")
                    ApiResult.Error(ErrorType.SERVER_ERROR, "Failed to fetch PRs: ${it.code}")
                }
            }
        }
    } catch (e: Exception) {
        log.warn("[Bitbucket:API] all-pull-requests failed: ${e.message}")
        ApiResult.Error(ErrorType.NETWORK_ERROR, e.message ?: "Network error")
    }
}
```

- [ ] **Step 3: Verify `BitbucketPrListResponse` DTO is accessible**

`BitbucketPrListResponse` already exists as a `private` class at line 128 of `BitbucketBranchClient.kt`. Since `getAllPullRequests` is added inside the same class, it can access this private DTO. No new DTO needed.

- [ ] **Step 4: Build to verify**

Run: `./gradlew :core:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/bitbucket/BitbucketBranchClient.kt
git commit -m "feat: add getDefaultBranch() and getAllPullRequests() to BitbucketBranchClient"
```

---

### Task 4: Rewrite DefaultBranchResolver as Project Service

**Files:**
- Rewrite: `core/src/main/kotlin/com/workflow/orchestrator/core/util/DefaultBranchResolver.kt`
- Create: `core/src/test/kotlin/com/workflow/orchestrator/core/util/DefaultBranchResolverTest.kt`

- [ ] **Step 1: Write failing tests for override logic (Priority 1)**

Create `core/src/test/kotlin/com/workflow/orchestrator/core/util/DefaultBranchResolverTest.kt`:

```kotlin
package com.workflow.orchestrator.core.util

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach

class DefaultBranchResolverTest {

    @Test
    fun `parseOverrides returns map from valid JSON`() {
        val json = """{"repo||feature/ABC":"develop","repo||bugfix/XYZ":"main"}"""
        val result = DefaultBranchResolver.parseOverrides(json)
        assertEquals("develop", result["repo||feature/ABC"])
        assertEquals("main", result["repo||bugfix/XYZ"])
    }

    @Test
    fun `parseOverrides returns empty map for blank string`() {
        assertTrue(DefaultBranchResolver.parseOverrides("").isEmpty())
        assertTrue(DefaultBranchResolver.parseOverrides("  ").isEmpty())
    }

    @Test
    fun `parseOverrides returns empty map for malformed JSON`() {
        assertTrue(DefaultBranchResolver.parseOverrides("{invalid").isEmpty())
        assertTrue(DefaultBranchResolver.parseOverrides("null").isEmpty())
    }

    @Test
    fun `serializeOverrides produces valid JSON`() {
        val map = mapOf("repo||branch" to "develop")
        val json = DefaultBranchResolver.serializeOverrides(map)
        assertTrue(json.contains("repo||branch"))
        assertTrue(json.contains("develop"))
        // Verify round-trip
        val parsed = DefaultBranchResolver.parseOverrides(json)
        assertEquals("develop", parsed["repo||branch"])
    }

    @Test
    fun `buildOverrideKey uses double-pipe separator`() {
        val key = DefaultBranchResolver.buildOverrideKey("/path/to/repo", "feature/ABC")
        assertEquals("/path/to/repo||feature/ABC", key)
    }

    @Test
    fun `buildOverrideKey handles Windows paths with colon`() {
        val key = DefaultBranchResolver.buildOverrideKey("C:\\Users\\dev\\repo", "main")
        assertEquals("C:\\Users\\dev\\repo||main", key)
    }

    @Test
    fun `orderCandidates prioritises branches targeting originHead`() {
        data class PrBranch(val from: String, val to: String)
        val prs = listOf(
            PrBranch("feature/A", "develop"),
            PrBranch("feature/B", "release/1.0"),
            PrBranch("feature/C", "develop"),
            PrBranch("hotfix/D", "main")
        )
        val currentBranch = "feature/X"
        val originHead = "develop"

        val allBranches = prs.flatMap { listOf(it.from, it.to) }
            .filter { it != currentBranch }
            .distinct()

        val (prioritised, others) = allBranches.partition { branch ->
            prs.any { it.from == branch && it.to == originHead }
        }

        // feature/A and feature/C target develop (originHead) → prioritised
        assertTrue(prioritised.contains("feature/A"))
        assertTrue(prioritised.contains("feature/C"))
        // release/1.0, main, feature/B, hotfix/D → others
        assertTrue(others.contains("release/1.0"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.util.DefaultBranchResolverTest"`
Expected: FAIL — methods not found

- [ ] **Step 3: Rewrite DefaultBranchResolver**

Replace the entire file `core/src/main/kotlin/com/workflow/orchestrator/core/util/DefaultBranchResolver.kt`:

```kotlin
package com.workflow.orchestrator.core.util

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.ConnectionSettings
import com.workflow.orchestrator.core.settings.PluginSettings
import git4idea.repo.GitRepository
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves the target branch for the current branch using a 6-priority cascade:
 * 1. Per-branch override (local)
 * 2. Existing PR target for current branch (Bitbucket API)
 * 3. Merge-base against PR branches in repo (Bitbucket API + local git)
 * 4. Bitbucket repo default branch (Bitbucket API)
 * 5. origin/HEAD symbolic ref (local git)
 * 6. Settings defaultTargetBranch fallback (local)
 */
@Service(Service.Level.PROJECT)
class DefaultBranchResolver(private val project: Project) : Disposable {

    private val log = Logger.getInstance(DefaultBranchResolver::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = ConcurrentHashMap<String, String>()

    init {
        // Invalidate cache on branch change
        scope.launch {
            project.getService(EventBus::class.java).events.collect { event ->
                if (event is WorkflowEvent.BranchChanged) {
                    log.info("[BranchResolver] BranchChanged → clearing cache")
                    cache.clear()
                }
            }
        }
    }

    /**
     * Resolve the target branch for the given repository's current branch.
     * Runs 6-priority cascade, short-circuits on first match.
     */
    suspend fun resolve(repo: GitRepository): String {
        val repoPath = repo.root.path
        val currentBranch = repo.currentBranchName ?: return getFallback()
        val cacheKey = buildOverrideKey(repoPath, currentBranch)

        // Check cache
        cache[cacheKey]?.let { return it }

        val result = runPriorityChain(repo, repoPath, currentBranch)
        cache[cacheKey] = result
        return result
    }

    private suspend fun runPriorityChain(repo: GitRepository, repoPath: String, currentBranch: String): String {
        // Priority 1: Per-branch override
        getOverride(repoPath, currentBranch)?.let {
            log.info("[BranchResolver] P1 override: $currentBranch → $it")
            return it
        }

        // Resolve Bitbucket credentials for network priorities
        val settings = PluginSettings.getInstance(project)
        val repoConfig = settings.getRepoForPath(repoPath) ?: settings.getPrimaryRepo()
        val projectKey = repoConfig?.bitbucketProjectKey.orEmpty()
        val repoSlug = repoConfig?.bitbucketRepoSlug.orEmpty()

        if (projectKey.isNotBlank() && repoSlug.isNotBlank()) {
            val client = createBitbucketClient()
            if (client != null) {
                // Priority 2: Existing PR target
                tryPrTarget(client, projectKey, repoSlug, currentBranch)?.let {
                    log.info("[BranchResolver] P2 PR target: $currentBranch → $it")
                    return it
                }

                // Priority 3: Merge-base against PR branches
                tryMergeBase(client, projectKey, repoSlug, repo, currentBranch)?.let {
                    log.info("[BranchResolver] P3 merge-base: $currentBranch → $it")
                    return it
                }

                // Priority 4: Bitbucket default branch
                tryBitbucketDefault(client, projectKey, repoSlug)?.let {
                    log.info("[BranchResolver] P4 Bitbucket default: $it")
                    return it
                }
            }
        }

        // Priority 5: origin/HEAD
        fromOriginHead(repo)?.let {
            log.info("[BranchResolver] P5 origin/HEAD: $it")
            return it
        }

        // Priority 6: Settings fallback
        val fallback = getFallback()
        log.info("[BranchResolver] P6 fallback: $fallback")
        return fallback
    }

    // --- Priority 2: PR target ---

    private suspend fun tryPrTarget(
        client: BitbucketBranchClient,
        projectKey: String,
        repoSlug: String,
        currentBranch: String
    ): String? {
        return try {
            when (val result = client.getPullRequestsForBranch(projectKey, repoSlug, currentBranch)) {
                is ApiResult.Success -> {
                    // Sort by ID descending as proxy for most recent
                    // (BitbucketPrResponse doesn't carry updatedDate; API already filters OPEN only)
                    result.data
                        .sortedByDescending { it.id }
                        .firstOrNull()
                        ?.toRef?.displayId
                        ?.takeIf { it.isNotBlank() }
                }
                is ApiResult.Error -> null
            }
        } catch (e: Exception) {
            log.info("[BranchResolver] P2 failed: ${e.message}")
            null
        }
    }

    // --- Priority 3: Merge-base ---

    private suspend fun tryMergeBase(
        client: BitbucketBranchClient,
        projectKey: String,
        repoSlug: String,
        repo: GitRepository,
        currentBranch: String
    ): String? {
        // Declare outside withTimeout so best result survives timeout
        var bestBranch: String? = null
        var bestDivergence = Int.MAX_VALUE

        return try {
            withTimeoutOrNull(5000) {
                val allPrs = when (val result = client.getAllPullRequests(projectKey, repoSlug)) {
                    is ApiResult.Success -> result.data
                    is ApiResult.Error -> return@withTimeoutOrNull null
                }
                if (allPrs.isEmpty()) return@withTimeoutOrNull null

                val originHead = fromOriginHead(repo)

                // Collect all unique branches from PRs (both source and destination)
                val prBranches = allPrs.flatMap { pr ->
                    listOfNotNull(
                        pr.fromRef?.displayId?.let { from -> Triple(from, pr.toRef?.displayId, "from") },
                        pr.toRef?.displayId?.let { to -> Triple(to, null, "to") }
                    )
                }

                val uniqueBranches = prBranches.map { it.first }
                    .filter { it != currentBranch }
                    .distinct()

                if (uniqueBranches.isEmpty()) return@withTimeoutOrNull null

                // Order: PR source branches targeting originHead first, then by frequency
                val branchFrequency = uniqueBranches.associateWith { branch ->
                    prBranches.count { it.first == branch }
                }

                val sourcesTargetingOriginHead = if (originHead != null) {
                    allPrs.filter { it.toRef?.displayId == originHead }
                        .mapNotNull { it.fromRef?.displayId }
                        .filter { it != currentBranch }
                        .distinct()
                } else emptyList()

                val ordered = (sourcesTargetingOriginHead +
                    (uniqueBranches - sourcesTargetingOriginHead.toSet())
                        .sortedByDescending { branchFrequency[it] ?: 0 })
                    .distinct()
                    .take(20)

                // Find merge-base with fewest diverging commits
                for (candidate in ordered) {
                    ensureActive() // Check for timeout cancellation
                    val mergeBase = GitMergeBaseUtil.findMergeBase(
                        project, repo.root, currentBranch, candidate
                    ) ?: continue
                    val divergence = GitMergeBaseUtil.countDivergingCommits(
                        project, repo.root, currentBranch, mergeBase
                    )
                    if (divergence < bestDivergence) {
                        bestDivergence = divergence
                        bestBranch = candidate
                    }
                    // Perfect match — current branch is directly on this branch
                    if (divergence == 0) break
                }

                bestBranch
            } ?: run {
                // Timeout — return best candidate found so far
                if (bestBranch != null) {
                    log.info("[BranchResolver] P3 timeout, returning best so far: $bestBranch")
                }
                bestBranch
            }
        } catch (e: Exception) {
            log.info("[BranchResolver] P3 failed: ${e.message}")
            bestBranch // Return best so far even on failure
        }
    }

    // --- Priority 4: Bitbucket default branch ---

    private suspend fun tryBitbucketDefault(
        client: BitbucketBranchClient,
        projectKey: String,
        repoSlug: String
    ): String? {
        return try {
            when (val result = client.getDefaultBranch(projectKey, repoSlug)) {
                is ApiResult.Success -> result.data.displayId.takeIf { it.isNotBlank() }
                is ApiResult.Error -> null
            }
        } catch (e: Exception) {
            log.info("[BranchResolver] P4 failed: ${e.message}")
            null
        }
    }

    // --- Priority 5: origin/HEAD ---

    private fun fromOriginHead(repo: GitRepository): String? {
        val origin = repo.remotes.find { it.name == "origin" } ?: return null
        val originHead = repo.branches.remoteBranches.find {
            it.remote == origin && it.nameForRemoteOperations == "HEAD"
        } ?: return null
        return originHead.nameForLocalOperations.removePrefix("origin/")
            .takeIf { it.isNotBlank() && it != "HEAD" }
    }

    // --- Priority 6: Settings fallback ---

    private fun getFallback(): String {
        val settings = PluginSettings.getInstance(project)
        return settings.state.defaultTargetBranch?.takeIf { it.isNotBlank() } ?: "develop"
    }

    // --- Override management ---

    fun setOverride(repoPath: String, branch: String, target: String) {
        val key = buildOverrideKey(repoPath, branch)
        val overrides = loadOverrides().toMutableMap()
        overrides[key] = target
        saveOverrides(overrides)
        cache.clear()
        log.info("[BranchResolver] Override set: $branch → $target")
    }

    fun getOverride(repoPath: String, branch: String): String? {
        val key = buildOverrideKey(repoPath, branch)
        return loadOverrides()[key]
    }

    fun removeOverride(repoPath: String, branch: String) {
        val key = buildOverrideKey(repoPath, branch)
        val overrides = loadOverrides().toMutableMap()
        overrides.remove(key)
        saveOverrides(overrides)
        cache.clear()
    }

    fun clearAllOverrides() {
        PluginSettings.getInstance(project).state.branchTargetOverrides = ""
        cache.clear()
    }

    fun clearCache() {
        cache.clear()
    }

    private fun loadOverrides(): Map<String, String> {
        val raw = PluginSettings.getInstance(project).state.branchTargetOverrides.orEmpty()
        return parseOverrides(raw)
    }

    private fun saveOverrides(map: Map<String, String>) {
        PluginSettings.getInstance(project).state.branchTargetOverrides = serializeOverrides(map)
    }

    private fun createBitbucketClient(): BitbucketBranchClient? {
        val connSettings = ConnectionSettings.getInstance().state
        val url = connSettings.bitbucketUrl.trimEnd('/')
        if (url.isBlank()) return null
        return BitbucketBranchClient(
            baseUrl = url,
            tokenProvider = { CredentialStore().getToken(ServiceType.BITBUCKET) }
        )
    }

    override fun dispose() {
        scope.cancel()
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun getInstance(project: Project): DefaultBranchResolver =
            project.getService(DefaultBranchResolver::class.java)

        fun buildOverrideKey(repoPath: String, branch: String): String = "$repoPath||$branch"

        fun parseOverrides(raw: String): Map<String, String> {
            if (raw.isBlank()) return emptyMap()
            return try {
                val obj = json.parseToJsonElement(raw).jsonObject
                obj.mapValues { it.value.jsonPrimitive.content }
            } catch (e: Exception) {
                emptyMap()
            }
        }

        fun serializeOverrides(map: Map<String, String>): String {
            if (map.isEmpty()) return ""
            val obj = JsonObject(map.mapValues { JsonPrimitive(it.value) })
            return obj.toString()
        }
    }
}
```

- [ ] **Step 4: Register as project service**

Add to `src/main/resources/META-INF/plugin.xml` (project root, NOT core module) inside `<extensions defaultExtensionNs="com.intellij">`:

```xml
<projectService serviceImplementation="com.workflow.orchestrator.core.util.DefaultBranchResolver"/>
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.util.DefaultBranchResolverTest"`
Expected: PASS — all tests

- [ ] **Step 6: Build full core module**

Run: `./gradlew :core:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/util/DefaultBranchResolver.kt \
       core/src/test/kotlin/com/workflow/orchestrator/core/util/DefaultBranchResolverTest.kt \
       src/main/resources/META-INF/plugin.xml
git commit -m "feat: rewrite DefaultBranchResolver as 6-priority cascading service"
```

---

### Task 5: Migrate callers to async resolver

**Files:**
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildDashboardPanel.kt`
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/PrBar.kt`
- Modify: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/CreatePrDialog.kt`
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarDataService.kt`
- Modify: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDetailPanel.kt`
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/SprintDashboardPanel.kt`

Note: These files were already partially migrated to use the old `DefaultBranchResolver.resolve(repo)`. Now we need to change them to use the new project service with `suspend fun`.

- [ ] **Step 1: Update BuildDashboardPanel.kt**

The 3 call sites already use `getCurrentBranch() ?: getGitRepo()?.let { DefaultBranchResolver.resolve(it) } ?: "develop"`. These are inside `scope.launch` blocks already. Change to:

```kotlin
// Replace all 3 occurrences of:
val branch = getCurrentBranch() ?: getGitRepo()?.let { DefaultBranchResolver.resolve(it) } ?: "develop"
// With:
val branch = getCurrentBranch() ?: getGitRepo()?.let { DefaultBranchResolver.getInstance(project).resolve(it) } ?: "develop"
```

Update the import from `com.workflow.orchestrator.core.util.DefaultBranchResolver` (already present).

Remove the `getGitRepo()` helper method if it's only used for this — but keep it if `getCurrentBranch()` still depends on it.

- [ ] **Step 2: Update PrBar.kt**

The 2 call sites already use `getGitRepo()?.let { DefaultBranchResolver.resolve(it) } ?: "develop"`. Change to async:

For line 163 (targetLabel in UI builder — synchronous context), wrap in coroutine:
```kotlin
// Replace the direct label assignment with async resolution
val targetLabel = JBLabel("resolving...")
// Add after the panel is built:
scope.launch {
    val target = getGitRepo()?.let { DefaultBranchResolver.getInstance(project).resolve(it) } ?: "develop"
    invokeLater { targetLabel.text = target }
}
```

For line 432 (inside `onSubmitPr` which is called from EDT), it's already inside a `scope.launch` further down. Move the resolution into the coroutine:
```kotlin
val toBranch = getGitRepo()?.let { DefaultBranchResolver.getInstance(project).resolve(it) } ?: "develop"
```

- [ ] **Step 3: Update CreatePrDialog.kt**

The target field init (line 60) runs during construction (EDT). Change to resolve async after init:
```kotlin
private val targetField = JBTextField().apply {
    text = "resolving..."
}

// In init block, after init() call:
scope.launch {
    val repos = GitRepositoryManager.getInstance(project).repositories
    val repo = repos.firstOrNull()
    val target = repo?.let { DefaultBranchResolver.getInstance(project).resolve(it) } ?: "develop"
    invokeLater { targetField.text = target }
}
```

- [ ] **Step 4: Update SonarDataService.kt**

The `currentBranch` property (line 66-72) is a `get()` accessor. It's called from coroutine contexts. Change to a suspend function:

```kotlin
private suspend fun getCurrentBranch(): String {
    val resolver = RepoContextResolver.getInstance(project)
    val repoConfig = resolver.resolveFromCurrentEditor() ?: resolver.getPrimary()
    val repos = GitRepositoryManager.getInstance(project).repositories
    val targetRepo = repos.find { it.root.path == repoConfig?.localVcsRootPath } ?: repos.firstOrNull()
    return targetRepo?.currentBranchName
        ?: targetRepo?.let { DefaultBranchResolver.getInstance(project).resolve(it) }
        ?: "develop"
}
```

Update all callers of `currentBranch` property to call `getCurrentBranch()` instead.

- [ ] **Step 5: Update PrDetailPanel.kt**

Line 439 is inside a method that has a `scope.launch` below it. Change to:
```kotlin
val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull()
val defaultTarget = repo?.let { DefaultBranchResolver.getInstance(project).resolve(it) } ?: "develop"
```

Wrap in coroutine if the containing method isn't already suspend.

- [ ] **Step 6: Update SprintDashboardPanel.kt**

Line 818 is inside a method that already launches a coroutine. Change to:
```kotlin
val gitRepo = GitRepositoryManager.getInstance(project).repositories.firstOrNull()
val defaultSource = gitRepo?.let { DefaultBranchResolver.getInstance(project).resolve(it) } ?: "develop"
```

- [ ] **Step 7: Build all affected modules**

Run: `./gradlew :bamboo:compileKotlin :sonar:compileKotlin :pullrequest:compileKotlin :jira:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/BuildDashboardPanel.kt \
       bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/PrBar.kt \
       bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/ui/CreatePrDialog.kt \
       sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarDataService.kt \
       pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/ui/PrDetailPanel.kt \
       jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/SprintDashboardPanel.kt
git commit -m "refactor: migrate all callers to async DefaultBranchResolver service"
```

---

### Task 6: Auto-store source branch from Start Work dialog

**Files:**
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/SprintDashboardPanel.kt`

- [ ] **Step 1: Find the StartWorkResult handling code**

In `SprintDashboardPanel.kt`, after `val dialogResult = dialog.result ?: return@withContext` (around line 903), in the new-branch creation flow.

- [ ] **Step 2: Add override storage after successful branch creation**

After the branch is created successfully (inside the success handler of `branchingService.startWork`), add:

```kotlin
if (!dialogResult.useExisting && dialogResult.sourceBranch.isNotBlank()) {
    val resolver = DefaultBranchResolver.getInstance(project)
    val repoPath = targetRepo?.root?.path ?: ""
    if (repoPath.isNotBlank()) {
        resolver.setOverride(repoPath, dialogResult.branchName, dialogResult.sourceBranch)
    }
}
```

Where `targetRepo` is the GitRepository resolved earlier in the method.

- [ ] **Step 3: Build and verify**

Run: `./gradlew :jira:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/SprintDashboardPanel.kt
git commit -m "feat: auto-store source branch override from Start Work dialog"
```

---

### Task 7: Add target branch indicator to CurrentWorkSection

**Files:**
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/CurrentWorkSection.kt`

- [ ] **Step 1: Add new UI fields**

Add after the existing `branchLabel` field:

```kotlin
private val targetArrowLabel = JBLabel("").apply {
    foreground = StatusColors.SECONDARY_TEXT
    font = font.deriveFont(JBUI.scale(10).toFloat())
}
private val editTargetLabel = JBLabel(AllIcons.Actions.Edit).apply {
    cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
    toolTipText = "Change target branch"
}
```

Add import: `import com.workflow.orchestrator.core.util.DefaultBranchResolver`

- [ ] **Step 2: Add target branch resolution in buildActiveState**

In the `ApplicationManager.getApplication().executeOnPooledThread` block (where branch is resolved), add target branch resolution. Change to a coroutine approach:

```kotlin
// Replace the executeOnPooledThread block with:
scope.launch {
    val resolver = RepoContextResolver.getInstance(project)
    val repoConfig = resolver.resolveFromCurrentEditor() ?: resolver.getPrimary()
    val repos = GitRepositoryManager.getInstance(project).repositories
    val targetRepo = repos.find { it.root.path == repoConfig?.localVcsRootPath } ?: repos.firstOrNull()
    val currentBranch = targetRepo?.currentBranchName ?: ""
    val targetBranch = targetRepo?.let {
        DefaultBranchResolver.getInstance(project).resolve(it)
    } ?: ""

    invokeLater {
        if (currentBranch.isNotBlank()) {
            branchLabel.text = currentBranch
            branchLabel.icon = AllIcons.Vcs.Branch
        }
        if (targetBranch.isNotBlank()) {
            targetArrowLabel.text = "→ $targetBranch"
            metaRow.add(targetArrowLabel)
            metaRow.add(editTargetLabel)
        }
        revalidate()
        repaint()
    }
}
```

This requires adding a `scope` field. Add to the class:

```kotlin
private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
```

And implement `Disposable` to cancel it. The class doesn't currently implement `Disposable`, so either:
- Add `Disposable` interface and `dispose()` method
- Or tie the scope to the parent panel's lifecycle

- [ ] **Step 3: Add edit icon click handler**

In `buildActiveState`, wire the edit icon:

```kotlin
editTargetLabel.mouseListeners.forEach { editTargetLabel.removeMouseListener(it) }
editTargetLabel.addMouseListener(object : java.awt.event.MouseAdapter() {
    override fun mouseClicked(e: java.awt.event.MouseEvent?) {
        showBranchPicker(targetRepo)
    }
})
```

Add the branch picker method using JB components with search:

```kotlin
private fun showBranchPicker(repo: git4idea.repo.GitRepository?) {
    if (repo == null) return
    val branches = repo.branches.remoteBranches
        .map { it.nameForRemoteOperations }
        .filter { it != "HEAD" }
        .sorted()

    val list = com.intellij.ui.components.JBList(branches)
    list.selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION

    val popup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
        .createListPopupBuilder(list)
        .setTitle("Select Target Branch")
        .setFilterAlwaysVisible(true)
        .setItemChoosenCallback {
            val selected = list.selectedValue as? String ?: return@setItemChoosenCallback
            val resolver = DefaultBranchResolver.getInstance(project)
            resolver.setOverride(repo.root.path, repo.currentBranchName ?: "", selected)
            targetArrowLabel.text = "→ $selected"
            revalidate()
            repaint()
        }
        .createPopup()

    popup.showUnderneathOf(editTargetLabel)
}
```

- [ ] **Step 4: Build and verify**

Run: `./gradlew :jira:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/CurrentWorkSection.kt
git commit -m "feat: add target branch indicator with edit to CurrentWorkSection"
```

---

### Task 8: Add "Clear overrides" button to Settings

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/settings/GeneralConfigurable.kt`

- [ ] **Step 1: Add button to repository section**

In `GeneralConfigurable.kt`, inside `repositorySection()` (around line 526 where the other buttons are), add:

```kotlin
button("Clear Branch Overrides") {
    DefaultBranchResolver.getInstance(pluginSettings.project).clearAllOverrides()
    // Show confirmation
    repoStatusLabel.text = "Branch target overrides cleared"
    repoStatusLabel.foreground = StatusColors.SUCCESS
}
```

Add import: `import com.workflow.orchestrator.core.util.DefaultBranchResolver`

- [ ] **Step 2: Build and verify**

Run: `./gradlew :core:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/settings/GeneralConfigurable.kt
git commit -m "feat: add 'Clear Branch Overrides' button to settings"
```

---

### Task 9: Run full test suite and final verification

**Files:** None (verification only)

- [ ] **Step 1: Run all core tests**

Run: `./gradlew :core:test`
Expected: All tests pass

- [ ] **Step 2: Run all module tests**

Run: `./gradlew :jira:test :bamboo:test :sonar:test :pullrequest:test`
Expected: All tests pass

- [ ] **Step 3: Build full plugin**

Run: `./gradlew buildPlugin`
Expected: BUILD SUCCESSFUL — ZIP created

- [ ] **Step 4: Verify plugin loads**

Run: `./gradlew verifyPlugin`
Expected: No API compatibility issues

- [ ] **Step 5: Final commit with any fixes**

If any test fixes were needed, commit them:
```bash
git commit -m "fix: resolve test failures from DefaultBranchResolver migration"
```

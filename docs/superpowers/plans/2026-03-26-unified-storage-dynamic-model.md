# Unified Storage & Dynamic Model Selection — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Consolidate all agent files under `~/.workflow-orchestrator/{ProjectName-hash}/` and replace all hardcoded model IDs with dynamic API-based resolution.

**Architecture:** New `ProjectIdentifier` utility computes stable project directories. New `ModelCache` fetches and caches models from Sourcegraph API with 24h TTL. `StorageMigration` handles one-time lazy migration from old paths. All 13 persistence classes update their path construction.

**Tech Stack:** Kotlin, IntelliJ Platform SDK, OkHttp, kotlinx.serialization, kotlinx.coroutines

**Spec:** `docs/superpowers/specs/2026-03-26-unified-storage-dynamic-model-design.md`

---

### Task 1: Create ProjectIdentifier Utility

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/util/ProjectIdentifier.kt`
- Create: `core/src/test/kotlin/com/workflow/orchestrator/core/util/ProjectIdentifierTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// core/src/test/kotlin/com/workflow/orchestrator/core/util/ProjectIdentifierTest.kt
package com.workflow.orchestrator.core.util

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ProjectIdentifierTest {

    @Test
    fun `compute returns dirName-hash format`() {
        val id = ProjectIdentifier.compute("/Users/dev/Projects/MyPlugin")
        assertTrue(id.startsWith("MyPlugin-"), "Should start with dir name: $id")
        assertEquals(6, id.substringAfter("MyPlugin-").length, "Hash should be 6 hex chars")
    }

    @Test
    fun `compute is stable for same path`() {
        val a = ProjectIdentifier.compute("/some/path/Project")
        val b = ProjectIdentifier.compute("/some/path/Project")
        assertEquals(a, b)
    }

    @Test
    fun `compute differs for different paths with same dir name`() {
        val a = ProjectIdentifier.compute("/path1/Project")
        val b = ProjectIdentifier.compute("/path2/Project")
        assertNotEquals(a, b)
    }

    @Test
    fun `rootDir returns home-based path`(@TempDir tempDir: File) {
        val root = ProjectIdentifier.rootDir("/Users/dev/MyPlugin")
        assertTrue(root.absolutePath.contains(".workflow-orchestrator"))
        assertTrue(root.absolutePath.contains("MyPlugin-"))
    }

    @Test
    fun `sessionsDir returns agent sessions subdirectory`() {
        val sessions = ProjectIdentifier.sessionsDir("/Users/dev/MyPlugin")
        assertTrue(sessions.absolutePath.endsWith("agent/sessions"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && ./gradlew :core:test --tests "com.workflow.orchestrator.core.util.ProjectIdentifierTest" --rerun`
Expected: FAIL — class not found

- [ ] **Step 3: Write the implementation**

```kotlin
// core/src/main/kotlin/com/workflow/orchestrator/core/util/ProjectIdentifier.kt
package com.workflow.orchestrator.core.util

import java.io.File
import java.security.MessageDigest

/**
 * Computes stable, human-readable project identifiers for the unified storage root.
 * Format: {directoryName}-{first6OfSHA256(absolutePath)}
 * Example: "MyPlugin-a3f8b2"
 */
object ProjectIdentifier {

    private const val HASH_LENGTH = 6
    private const val ROOT_DIR = ".workflow-orchestrator"

    /**
     * Compute a stable project identifier from an absolute project path.
     * Format: {dirName}-{6-char hex hash of full path}
     */
    fun compute(projectBasePath: String): String {
        val dirName = File(projectBasePath).name
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(projectBasePath.toByteArray(Charsets.UTF_8))
            .take(HASH_LENGTH / 2)
            .joinToString("") { "%02x".format(it) }
        return "$dirName-$hash"
    }

    /**
     * Root directory for this project's agent data.
     * ~/.workflow-orchestrator/{ProjectName-hash}/
     */
    fun rootDir(projectBasePath: String): File {
        return File(System.getProperty("user.home"), "$ROOT_DIR/${compute(projectBasePath)}")
    }

    /**
     * Agent data directory: ~/.workflow-orchestrator/{proj}/agent/
     */
    fun agentDir(projectBasePath: String): File {
        return File(rootDir(projectBasePath), "agent")
    }

    /**
     * Sessions directory: ~/.workflow-orchestrator/{proj}/agent/sessions/
     */
    fun sessionsDir(projectBasePath: String): File {
        return File(agentDir(projectBasePath), "sessions")
    }

    /**
     * Logs directory: ~/.workflow-orchestrator/{proj}/logs/
     */
    fun logsDir(projectBasePath: String): File {
        return File(rootDir(projectBasePath), "logs")
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.util.ProjectIdentifierTest" --rerun`
Expected: PASS (all 5 tests)

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/util/ProjectIdentifier.kt \
       core/src/test/kotlin/com/workflow/orchestrator/core/util/ProjectIdentifierTest.kt
git commit -m "feat(core): add ProjectIdentifier for unified storage paths"
```

---

### Task 2: Create ModelCache with Dynamic Resolution

**Files:**
- Create: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/ModelCache.kt`
- Create: `core/src/test/kotlin/com/workflow/orchestrator/core/ai/ModelCacheTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// core/src/test/kotlin/com/workflow/orchestrator/core/ai/ModelCacheTest.kt
package com.workflow.orchestrator.core.ai

import com.workflow.orchestrator.core.ai.dto.ModelInfo
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ModelCacheTest {

    @BeforeEach
    fun reset() {
        ModelCache.reset()
    }

    @Test
    fun `pickBest prefers Opus thinking over plain Opus`() {
        val models = listOf(
            ModelInfo(id = "anthropic::2024-10-22::claude-sonnet-4-20250514", ownedBy = "anthropic", created = 1000),
            ModelInfo(id = "anthropic::2024-10-22::claude-opus-4-20250514", ownedBy = "anthropic", created = 2000),
            ModelInfo(id = "anthropic::2024-10-22::claude-opus-4-5-thinking-20250514", ownedBy = "anthropic", created = 3000)
        )
        val best = ModelCache.pickBest(models)
        assertNotNull(best)
        assertTrue(best!!.isOpusClass, "Should pick Opus class")
        assertTrue(best.isThinkingModel, "Should pick thinking model")
    }

    @Test
    fun `pickBest falls back to Opus when no thinking model`() {
        val models = listOf(
            ModelInfo(id = "anthropic::2024-10-22::claude-sonnet-4-20250514", ownedBy = "anthropic", created = 1000),
            ModelInfo(id = "anthropic::2024-10-22::claude-opus-4-20250514", ownedBy = "anthropic", created = 2000)
        )
        val best = ModelCache.pickBest(models)
        assertNotNull(best)
        assertTrue(best!!.isOpusClass)
    }

    @Test
    fun `pickBest falls back to Sonnet when no Opus`() {
        val models = listOf(
            ModelInfo(id = "anthropic::2024-10-22::claude-sonnet-4-20250514", ownedBy = "anthropic", created = 1000),
            ModelInfo(id = "anthropic::2024-10-22::claude-haiku-3-20250514", ownedBy = "anthropic", created = 2000)
        )
        val best = ModelCache.pickBest(models)
        assertNotNull(best)
        assertTrue(best!!.modelName.lowercase().contains("sonnet"))
    }

    @Test
    fun `pickBest picks latest among same tier`() {
        val models = listOf(
            ModelInfo(id = "anthropic::2024-10-22::claude-opus-4-5-thinking-20250101", ownedBy = "anthropic", created = 1000),
            ModelInfo(id = "anthropic::2024-10-22::claude-opus-4-5-thinking-20250514", ownedBy = "anthropic", created = 3000)
        )
        val best = ModelCache.pickBest(models)
        assertNotNull(best)
        assertEquals(3000, best!!.created)
    }

    @Test
    fun `pickBest returns null for empty list`() {
        assertNull(ModelCache.pickBest(emptyList()))
    }

    @Test
    fun `pickBest picks anything available as last resort`() {
        val models = listOf(
            ModelInfo(id = "openai::2024-01-01::gpt-4o", ownedBy = "openai", created = 1000)
        )
        val best = ModelCache.pickBest(models)
        assertNotNull(best)
        assertEquals("openai::2024-01-01::gpt-4o", best!!.id)
    }

    @Test
    fun `getCached returns empty when not populated`() {
        assertTrue(ModelCache.getCached().isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.ai.ModelCacheTest" --rerun`
Expected: FAIL — class not found

- [ ] **Step 3: Write the implementation**

```kotlin
// core/src/main/kotlin/com/workflow/orchestrator/core/ai/ModelCache.kt
package com.workflow.orchestrator.core.ai

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.core.ai.dto.ModelInfo
import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Cached model list with TTL and best-pick logic.
 * Thread-safe, deduplicates concurrent fetches via Mutex.
 */
object ModelCache {

    private val LOG = Logger.getInstance(ModelCache::class.java)
    private var models: List<ModelInfo> = emptyList()
    private var lastFetchMs: Long = 0
    private val lock = Mutex()

    /** 24-hour TTL for model list cache. */
    private const val TTL_MS = 24L * 60 * 60 * 1000

    /**
     * Get available models from cache or API.
     * Fetches from API if cache is empty or expired.
     */
    suspend fun getModels(
        client: SourcegraphChatClient,
        force: Boolean = false
    ): List<ModelInfo> {
        lock.withLock {
            val now = System.currentTimeMillis()
            if (!force && models.isNotEmpty() && (now - lastFetchMs) < TTL_MS) {
                return models
            }
            val result = client.listModels()
            if (result is ApiResult.Success) {
                models = result.data.data
                lastFetchMs = now
                LOG.info("ModelCache: fetched ${models.size} models")
            } else {
                LOG.warn("ModelCache: failed to fetch models, using cached (${models.size})")
            }
            return models
        }
    }

    /** Non-suspend access to whatever is cached. May be empty. */
    fun getCached(): List<ModelInfo> = models

    /**
     * Pick the best available model.
     * Priority: Anthropic Opus thinking (latest) > Opus > Sonnet > anything.
     */
    fun pickBest(models: List<ModelInfo>): ModelInfo? {
        if (models.isEmpty()) return null
        val anthropic = models.filter { it.provider == "anthropic" }

        // 1. Latest Opus with thinking
        anthropic.filter { it.isOpusClass && it.isThinkingModel }
            .maxByOrNull { it.created }?.let { return it }

        // 2. Latest Opus (any)
        anthropic.filter { it.isOpusClass }
            .maxByOrNull { it.created }?.let { return it }

        // 3. Latest Sonnet
        anthropic.filter { it.modelName.lowercase().contains("sonnet") }
            .maxByOrNull { it.created }?.let { return it }

        // 4. Any Anthropic model
        anthropic.maxByOrNull { it.created }?.let { return it }

        // 5. Anything
        return models.maxByOrNull { it.created }
    }

    /** Reset cache — for testing only. */
    fun reset() {
        models = emptyList()
        lastFetchMs = 0
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:test --tests "com.workflow.orchestrator.core.ai.ModelCacheTest" --rerun`
Expected: PASS (all 7 tests)

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/ai/ModelCache.kt \
       core/src/test/kotlin/com/workflow/orchestrator/core/ai/ModelCacheTest.kt
git commit -m "feat(core): add ModelCache with dynamic model resolution and best-pick logic"
```

---

### Task 3: Remove Hardcoded Model IDs from Core

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/AiSettings.kt:11` — default `null`
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/LlmBrainFactory.kt:10-20` — suspend + auto-resolve

- [ ] **Step 1: Change AiSettings default to null**

In `core/src/main/kotlin/com/workflow/orchestrator/core/ai/AiSettings.kt`, change line 11:

```kotlin
// Before:
var sourcegraphChatModel by string("anthropic::2024-10-22::claude-sonnet-4-20250514")

// After:
var sourcegraphChatModel by string(null)
```

- [ ] **Step 2: Make LlmBrainFactory.create() suspend with auto-resolve**

Replace the entire `LlmBrainFactory.kt` file:

```kotlin
// core/src/main/kotlin/com/workflow/orchestrator/core/ai/LlmBrainFactory.kt
package com.workflow.orchestrator.core.ai

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.ConnectionSettings

object LlmBrainFactory {

    private val LOG = Logger.getInstance(LlmBrainFactory::class.java)

    /**
     * Create an LlmBrain, resolving the model dynamically if not configured.
     * Suspend because it may need to fetch models from the API on first use.
     */
    suspend fun create(project: Project): LlmBrain {
        val connections = ConnectionSettings.getInstance()
        val credentialStore = CredentialStore()
        val aiSettings = AiSettings.getInstance(project)
        val sgUrl = connections.state.sourcegraphUrl.trimEnd('/')
        val tokenProvider = { credentialStore.getToken(ServiceType.SOURCEGRAPH) }

        val model = aiSettings.state.sourcegraphChatModel
            ?: resolveAndSaveModel(sgUrl, tokenProvider, aiSettings)

        return OpenAiCompatBrain(
            sourcegraphUrl = sgUrl,
            tokenProvider = tokenProvider,
            model = model
        )
    }

    private suspend fun resolveAndSaveModel(
        sgUrl: String,
        tokenProvider: () -> String?,
        aiSettings: AiSettings
    ): String {
        LOG.info("LlmBrainFactory: no model configured, auto-resolving from API")
        val client = SourcegraphChatClient(
            baseUrl = sgUrl,
            tokenProvider = tokenProvider,
            model = "" // not needed for listModels
        )
        val models = ModelCache.getModels(client)
        val best = ModelCache.pickBest(models)
            ?: throw IllegalStateException(
                "No models available from Sourcegraph. Check your connection and token in Settings > Workflow Orchestrator > General."
            )
        LOG.info("LlmBrainFactory: auto-selected model: ${best.id}")
        aiSettings.state.sourcegraphChatModel = best.id
        return best.id
    }

    fun isAvailable(): Boolean {
        val url = ConnectionSettings.getInstance().state.sourcegraphUrl
        return !url.isNullOrBlank() && CredentialStore().hasToken(ServiceType.SOURCEGRAPH)
    }
}
```

- [ ] **Step 3: Update callers of LlmBrainFactory.create() to use suspend**

Search all callers. The commit message feature in `:cody` calls `LlmBrainFactory.create()`. It runs inside `scope.launch(Dispatchers.IO)`, so adding `suspend` is compatible. Verify:

Run: `cd /Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/.worktrees/phase-3-agentic-ai && grep -rn "LlmBrainFactory.create" --include="*.kt"`

Update each caller — they should already be in coroutine contexts. If any caller uses `LlmBrainFactory.create()` outside a suspend context, wrap it in `runBlocking(Dispatchers.IO)` as a bridge (this should be rare).

- [ ] **Step 4: Build to verify compilation**

Run: `./gradlew :core:compileKotlin :cody:compileKotlin :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/ai/AiSettings.kt \
       core/src/main/kotlin/com/workflow/orchestrator/core/ai/LlmBrainFactory.kt
git commit -m "feat(core): remove hardcoded model IDs, auto-resolve via ModelCache"
```

---

### Task 4: Remove Hardcoded Model IDs from Agent Module

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/AgentSettings.kt:20` — default `null`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt:340-349` — use LlmBrainFactory
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/AgentSettingsConfigurable.kt:270-294` — use ModelCache.pickBest

- [ ] **Step 1: Change AgentSettings default to null**

In `agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/AgentSettings.kt`, change line 20:

```kotlin
// Before:
var sourcegraphChatModel by string("anthropic::2024-10-22::claude-sonnet-4-20250514")

// After:
var sourcegraphChatModel by string(null)
```

- [ ] **Step 2: Fix AgentService.kt brain initialization**

In `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`, replace the `brain` lazy val (lines 340-349):

```kotlin
// Before:
val brain: LlmBrain by lazy {
    val settings = AgentSettings.getInstance(project)
    val connections = ConnectionSettings.getInstance()
    val credentialStore = CredentialStore()
    OpenAiCompatBrain(
        sourcegraphUrl = connections.state.sourcegraphUrl.trimEnd('/'),
        tokenProvider = { credentialStore.getToken(ServiceType.SOURCEGRAPH) },
        model = settings.state.sourcegraphChatModel ?: "anthropic/claude-sonnet-4"
    )
}

// After:
// Brain is created on-demand via LlmBrainFactory (handles auto-resolution).
// Cannot be a lazy val because create() is suspend.
// AgentOrchestrator already creates its own brain — this is only for non-orchestrator uses.
suspend fun createBrain(): LlmBrain = LlmBrainFactory.create(project)
```

Check if `brain` property is referenced elsewhere in `AgentService.kt` and update those call sites to use `createBrain()`.

- [ ] **Step 3: Replace findBestModel with ModelCache.pickBest in AgentSettingsConfigurable**

In `agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/AgentSettingsConfigurable.kt`:

1. Replace the `findBestModel` method (lines 270-294) with a delegation:

```kotlin
private fun findBestModel(models: List<ModelInfo>): ModelInfo? {
    return ModelCache.pickBest(models)
}
```

2. In `loadModelsFromServer()`, after fetching models, also populate ModelCache:

```kotlin
// After: cachedModels = result.data.data
// Add:
// Populate ModelCache so LlmBrainFactory can use cached models
ModelCache.populateFromExternal(cachedModels)
```

Add a `populateFromExternal` method to `ModelCache`:

```kotlin
/** Populate cache from externally-fetched models (e.g., settings page). */
fun populateFromExternal(modelList: List<ModelInfo>) {
    models = modelList
    lastFetchMs = System.currentTimeMillis()
}
```

- [ ] **Step 4: Update test files to use test constant**

Create test utility and update test files:

```kotlin
// agent/src/test/kotlin/com/workflow/orchestrator/agent/TestModels.kt
package com.workflow.orchestrator.agent

object TestModels {
    const val MOCK_MODEL = "test::2024-01-01::mock-model"
}
```

Then in each test file, replace hardcoded model strings:
- `agent/src/test/kotlin/.../e2e/SingleAgentFlowE2ETest.kt:75`
- `agent/src/test/kotlin/.../brain/OpenAiCompatBrainTest.kt:28,61`
- `agent/src/test/kotlin/.../api/SourcegraphChatClientStreamTest.kt:35`
- `agent/src/test/kotlin/.../api/SourcegraphChatClientTest.kt:36`
- `agent/src/test/kotlin/.../runtime/ConversationStoreTest.kt:114,129`

Replace `"anthropic::2024-10-22::claude-sonnet-4-20250514"` and `"anthropic/claude-sonnet-4"` with `TestModels.MOCK_MODEL`.

- [ ] **Step 5: Build and run tests**

Run: `./gradlew :agent:compileKotlin :agent:test --rerun`
Expected: BUILD SUCCESSFUL, tests pass

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/AgentSettings.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/settings/AgentSettingsConfigurable.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/TestModels.kt \
       agent/src/test/kotlin/
git commit -m "feat(agent): remove all hardcoded model IDs, use ModelCache for auto-resolution"
```

---

### Task 5: Migrate ConversationStore to Unified Storage

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ConversationStore.kt:75-86`
- Modify: `agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/ConversationStoreTest.kt`

- [ ] **Step 1: Update ConversationStore to use ProjectIdentifier**

In `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ConversationStore.kt`, change the constructor and sessionDir:

```kotlin
// Before (lines 75-86):
class ConversationStore(
    private val sessionId: String,
    private val baseDir: File? = null
) {
    private val sessionDir: File by lazy {
        val parent = baseDir ?: File(PathManager.getSystemPath(), "workflow-agent/sessions")
        File(parent, sessionId)
    }

// After:
class ConversationStore(
    private val sessionId: String,
    /** Override for testing — when null, uses ProjectIdentifier-based path. */
    private val baseDir: File? = null,
    /** Project base path — required when baseDir is null. */
    private val projectBasePath: String? = null
) {
    private val sessionDir: File by lazy {
        val parent = baseDir ?: run {
            require(projectBasePath != null) { "ConversationStore requires projectBasePath when baseDir is not provided" }
            ProjectIdentifier.sessionsDir(projectBasePath)
        }
        File(parent, sessionId).also { it.mkdirs() }
    }
```

Add import: `import com.workflow.orchestrator.core.util.ProjectIdentifier`

- [ ] **Step 2: Update ConversationStore.getSessionsDir()**

```kotlin
// Before (line 147-148):
fun getSessionsDir(baseDir: File? = null): File {
    return baseDir ?: File(PathManager.getSystemPath(), "workflow-agent/sessions")
}

// After:
fun getSessionsDir(baseDir: File? = null, projectBasePath: String? = null): File {
    return baseDir ?: run {
        require(projectBasePath != null) { "getSessionsDir requires projectBasePath when baseDir is not provided" }
        ProjectIdentifier.sessionsDir(projectBasePath)
    }
}
```

- [ ] **Step 3: Update all callers to pass projectBasePath**

Search for `ConversationStore(` constructor calls and `getSessionsDir()` calls. Each caller should pass `projectBasePath = project.basePath`. Key callers are in:
- `AgentController.kt` — where ConversationSession is created
- `ConversationSession.kt` — where ConversationStore is created
- `AgentStartupActivity.kt` — where sessions are loaded on resume

Run: `grep -rn "ConversationStore(" --include="*.kt" agent/src/main/`
Run: `grep -rn "getSessionsDir" --include="*.kt" agent/src/main/`

Update each caller to include `projectBasePath = project.basePath`.

- [ ] **Step 4: Update existing tests**

In `ConversationStoreTest.kt`, all tests use `baseDir` override (TempDir), so they should continue to work. Verify:

Run: `./gradlew :agent:test --tests "*.ConversationStoreTest" --rerun`
Expected: PASS

- [ ] **Step 5: Build full agent module**

Run: `./gradlew :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ConversationStore.kt
# Also add any callers you updated
git commit -m "feat(agent): migrate ConversationStore to ProjectIdentifier-based paths"
```

---

### Task 6: Migrate SessionTrace to Per-Session Directory

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SessionTrace.kt:28-41`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/AgentOrchestrator.kt:243`

- [ ] **Step 1: Update SessionTrace constructor to take sessionDir**

In `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SessionTrace.kt`:

```kotlin
// Before (lines 28-41):
class SessionTrace(
    private val sessionId: String,
    private val basePath: String
) {
    ...
    private val traceFile: File by lazy {
        val dir = File(basePath, ".workflow/agent/traces")
        dir.mkdirs()
        File(dir, "$sessionId.trace.jsonl")
    }

// After:
class SessionTrace(
    private val sessionId: String,
    private val sessionDir: File
) {
    ...
    private val traceFile: File by lazy {
        val dir = File(sessionDir, "traces")
        dir.mkdirs()
        File(dir, "trace.jsonl")
    }
```

- [ ] **Step 2: Update AgentOrchestrator to pass sessionDir**

In `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/AgentOrchestrator.kt`, line 243:

```kotlin
// Before:
val sessionTrace = project.basePath?.let { SessionTrace(traceId, it) }

// After:
val sessionTrace = session?.sessionDirectory?.let { SessionTrace(traceId, it) }
```

Where `session` is the `ConversationSession` that owns the `ConversationStore`. The `sessionDirectory` property is already exposed on `ConversationStore`.

- [ ] **Step 3: Build to verify**

Run: `./gradlew :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SessionTrace.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/AgentOrchestrator.kt
git commit -m "feat(agent): move SessionTrace into per-session directory"
```

---

### Task 7: Migrate API Debug Dumps to Per-Session Directory

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/ai/SourcegraphChatClient.kt:40-47,478-559`

- [ ] **Step 1: Add apiDebugDir setter to SourcegraphChatClient**

In `core/src/main/kotlin/com/workflow/orchestrator/core/ai/SourcegraphChatClient.kt`:

Replace the lazy `apiDebugDir` (lines 482-486) and counter (line 488) with a configurable approach:

```kotlin
// Before (lines 482-488):
private val apiDebugDir by lazy {
    java.io.File(System.getProperty("user.home"), ".workflow-orchestrator/agent/logs/api-debug").also {
        it.mkdirs()
    }
}
@Volatile private var apiCallCounter = 0

// After:
/** Session-scoped directory for API debug dumps. Set by caller when a session is active. */
@Volatile var apiDebugSessionDir: java.io.File? = null

private val apiDebugDir: java.io.File? get() {
    val sessionDir = apiDebugSessionDir
    return if (sessionDir != null) {
        java.io.File(sessionDir, "api-debug").also { it.mkdirs() }
    } else {
        // Unsessioned calls (e.g., commit message generation) — skip dump
        // or write to a fallback if needed
        null
    }
}

@Volatile private var apiCallCounter = 0
```

- [ ] **Step 2: Guard dump methods against null apiDebugDir**

Update `dumpApiRequest`, `dumpApiResponse`, `dumpApiError` to early-return when `apiDebugDir` is null:

```kotlin
private fun dumpApiRequest(messages: List<ChatMessage>, tools: List<ToolDefinition>?, bodyLength: Int) {
    val dir = apiDebugDir ?: return
    try {
        val idx = ++apiCallCounter
        val file = java.io.File(dir, "call-${String.format("%03d", idx)}-request.txt")
        // ... rest unchanged
    } catch (e: Exception) {
        log.debug("[Agent:API] Failed to dump request: ${e.message}")
    }
}

private fun dumpApiResponse(response: ChatCompletionResponse) {
    val dir = apiDebugDir ?: return
    try {
        val idx = apiCallCounter
        val file = java.io.File(dir, "call-${String.format("%03d", idx)}-response.txt")
        // ... rest unchanged
    } catch (e: Exception) {
        log.debug("[Agent:API] Failed to dump response: ${e.message}")
    }
}

private fun dumpApiError(code: Int, body: String) {
    val dir = apiDebugDir ?: return
    try {
        val idx = apiCallCounter
        val file = java.io.File(dir, "call-${String.format("%03d", idx)}-error.txt")
        // ... rest unchanged
    } catch (_: Exception) {}
}
```

- [ ] **Step 3: Set apiDebugSessionDir from AgentOrchestrator**

In `AgentOrchestrator.kt`, after the brain is created, set the session dir:

```kotlin
// After creating the brain/client, before executing:
(brain as? OpenAiCompatBrain)?.client?.apiDebugSessionDir = session?.sessionDirectory
```

Check how `OpenAiCompatBrain` exposes the underlying `SourcegraphChatClient`. If it doesn't expose `client`, add a property:

```kotlin
// In OpenAiCompatBrain.kt, add:
val client: SourcegraphChatClient get() = chatClient
```

Or alternatively, add a method to `LlmBrain` interface:

```kotlin
fun setApiDebugDir(dir: File?)
```

Choose the approach that requires the least interface change. A setter on `SourcegraphChatClient` via the `OpenAiCompatBrain` wrapper is cleanest.

- [ ] **Step 4: Reset counter per session**

Add to `SourcegraphChatClient`:

```kotlin
/** Reset the API call counter — call when starting a new session. */
fun resetApiCallCounter() {
    apiCallCounter = 0
}
```

Call this from `AgentOrchestrator` when setting up a new session.

- [ ] **Step 5: Build to verify**

Run: `./gradlew :core:compileKotlin :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/com/workflow/orchestrator/core/ai/SourcegraphChatClient.kt \
       core/src/main/kotlin/com/workflow/orchestrator/core/ai/OpenAiCompatBrain.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/AgentOrchestrator.kt
git commit -m "feat(core): scope API debug dumps to per-session directories"
```

---

### Task 8: Migrate Project-Level Storage Classes

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/CoreMemory.kt:30-36`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ArchivalMemory.kt:31-37`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/GuardrailStore.kt:17-24`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/AgentMemoryStore.kt:16-23`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/service/MetricsStore.kt:22-38`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/AgentFileLogger.kt:70-73`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/CheckpointStore.kt:39-41`

- [ ] **Step 1: Update CoreMemory.forProject()**

```kotlin
// Before:
fun forProject(projectBasePath: String): CoreMemory {
    return instanceCache.getOrPut(projectBasePath) {
        val dir = File(projectBasePath, ".workflow/agent")
        dir.mkdirs()
        CoreMemory(File(dir, "core-memory.json"))
    }
}

// After:
fun forProject(projectBasePath: String): CoreMemory {
    return instanceCache.getOrPut(projectBasePath) {
        val dir = ProjectIdentifier.agentDir(projectBasePath)
        dir.mkdirs()
        CoreMemory(File(dir, "core-memory.json"))
    }
}
```

Add import: `import com.workflow.orchestrator.core.util.ProjectIdentifier`

- [ ] **Step 2: Update ArchivalMemory.forProject()**

```kotlin
// Before:
fun forProject(projectBasePath: String): ArchivalMemory {
    return instanceCache.getOrPut(projectBasePath) {
        val dir = File(projectBasePath, ".workflow/agent/archival")
        dir.mkdirs()
        ArchivalMemory(File(dir, "store.json"))
    }
}

// After:
fun forProject(projectBasePath: String): ArchivalMemory {
    return instanceCache.getOrPut(projectBasePath) {
        val dir = File(ProjectIdentifier.agentDir(projectBasePath), "archival")
        dir.mkdirs()
        ArchivalMemory(File(dir, "store.json"))
    }
}
```

- [ ] **Step 3: Update GuardrailStore path constants**

```kotlin
// Before:
class GuardrailStore(
    private val projectBasePath: File,
    ...
// Uses: File(projectBasePath, "$GUARDRAILS_DIR/$GUARDRAILS_FILE")

// After:
class GuardrailStore(
    private val projectBasePath: String,
    ...
// Uses: File(ProjectIdentifier.agentDir(projectBasePath), GUARDRAILS_FILE)
```

Update the constructor parameter type from `File` to `String`, and update callers. The path changes from `{projectBasePath}/.workflow/agent/guardrails.md` to `~/.workflow-orchestrator/{proj}/agent/guardrails.md`.

Update all methods that reference the file:
- `load()`: `val file = File(ProjectIdentifier.agentDir(projectBasePath), GUARDRAILS_FILE)`
- `save()`: `val dir = ProjectIdentifier.agentDir(projectBasePath)` then `File(dir, GUARDRAILS_FILE)`

Remove the `GUARDRAILS_DIR` constant (no longer needed — `ProjectIdentifier.agentDir()` handles the base).

- [ ] **Step 4: Update AgentMemoryStore**

```kotlin
// Before:
private const val MEMORY_DIR = ".workflow/agent/memory"
private val memoryDir: File
    get() = File(projectBasePath, MEMORY_DIR)

// After:
private val memoryDir: File
    get() = File(ProjectIdentifier.agentDir(projectBasePath.absolutePath), "memory")
```

If `projectBasePath` is a `File`, use `.absolutePath`. If it's a `String`, use directly.

- [ ] **Step 5: Update MetricsStore**

```kotlin
// Before:
private const val METRICS_DIR = ".workflow/agent/metrics"
private val metricsDir: File
    get() = File(basePath, METRICS_DIR).also { it.mkdirs() }

// After:
private val metricsDir: File
    get() = File(ProjectIdentifier.agentDir(basePath), "metrics").also { it.mkdirs() }
```

- [ ] **Step 6: Update AgentFileLogger**

```kotlin
// Before:
private val logDir: File = File(
    System.getProperty("user.home"),
    ".workflow-orchestrator/agent/logs"
)

// After:
private val logDir: File
```

Change the constructor to accept `projectBasePath: String`:

```kotlin
class AgentFileLogger(private val projectBasePath: String) {
    ...
    private val logDir: File = ProjectIdentifier.logsDir(projectBasePath).also { it.mkdirs() }
```

Update callers to pass `project.basePath!!`.

- [ ] **Step 7: Update CheckpointStore.forProject()**

```kotlin
// Before:
fun forProject(projectBasePath: String): CheckpointStore {
    return CheckpointStore(File(projectBasePath, ".workflow/agent"))
}

// After:
fun forProject(projectBasePath: String): CheckpointStore {
    return CheckpointStore(ProjectIdentifier.agentDir(projectBasePath))
}
```

- [ ] **Step 8: Build and test**

Run: `./gradlew :agent:compileKotlin :agent:test --rerun`
Expected: BUILD SUCCESSFUL, tests pass (tests use baseDir overrides so path changes don't affect them)

- [ ] **Step 9: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/context/CoreMemory.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ArchivalMemory.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/context/GuardrailStore.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/AgentMemoryStore.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/service/MetricsStore.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/AgentFileLogger.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/CheckpointStore.kt
# Also add any callers you updated
git commit -m "feat(agent): migrate all storage classes to ProjectIdentifier-based paths"
```

---

### Task 9: Create StorageMigration Coordinator

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/StorageMigration.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/StorageMigrationTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/StorageMigrationTest.kt
package com.workflow.orchestrator.agent.runtime

import com.workflow.orchestrator.core.util.ProjectIdentifier
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class StorageMigrationTest {

    @Test
    fun `migrates core-memory from old project dir`(@TempDir tempDir: File) {
        // Setup old location
        val projectDir = File(tempDir, "project")
        val oldAgentDir = File(projectDir, ".workflow/agent")
        oldAgentDir.mkdirs()
        File(oldAgentDir, "core-memory.json").writeText("""{"key":"value"}""")

        // Setup new root (simulating ProjectIdentifier)
        val newRoot = File(tempDir, "new-root")

        StorageMigration.migrateProjectFiles(oldAgentDir, newRoot)

        val newFile = File(newRoot, "agent/core-memory.json")
        assertTrue(newFile.exists(), "core-memory.json should be migrated")
        assertEquals("""{"key":"value"}""", newFile.readText())
        assertFalse(File(oldAgentDir, "core-memory.json").exists(), "old file should be moved")
    }

    @Test
    fun `migrates guardrails from old project dir`(@TempDir tempDir: File) {
        val oldAgentDir = File(tempDir, ".workflow/agent")
        oldAgentDir.mkdirs()
        File(oldAgentDir, "guardrails.md").writeText("- Don't do X")

        val newRoot = File(tempDir, "new-root")
        StorageMigration.migrateProjectFiles(oldAgentDir, newRoot)

        assertTrue(File(newRoot, "agent/guardrails.md").exists())
    }

    @Test
    fun `migrates memory directory`(@TempDir tempDir: File) {
        val oldAgentDir = File(tempDir, ".workflow/agent")
        File(oldAgentDir, "memory").mkdirs()
        File(oldAgentDir, "memory/MEMORY.md").writeText("# Memory")

        val newRoot = File(tempDir, "new-root")
        StorageMigration.migrateProjectFiles(oldAgentDir, newRoot)

        assertTrue(File(newRoot, "agent/memory/MEMORY.md").exists())
    }

    @Test
    fun `migrates archival directory`(@TempDir tempDir: File) {
        val oldAgentDir = File(tempDir, ".workflow/agent")
        File(oldAgentDir, "archival").mkdirs()
        File(oldAgentDir, "archival/store.json").writeText("[]")

        val newRoot = File(tempDir, "new-root")
        StorageMigration.migrateProjectFiles(oldAgentDir, newRoot)

        assertTrue(File(newRoot, "agent/archival/store.json").exists())
    }

    @Test
    fun `migrates metrics directory`(@TempDir tempDir: File) {
        val oldAgentDir = File(tempDir, ".workflow/agent")
        File(oldAgentDir, "metrics").mkdirs()
        File(oldAgentDir, "metrics/scorecard-abc.json").writeText("{}")

        val newRoot = File(tempDir, "new-root")
        StorageMigration.migrateProjectFiles(oldAgentDir, newRoot)

        assertTrue(File(newRoot, "agent/metrics/scorecard-abc.json").exists())
    }

    @Test
    fun `skips migration if old dir does not exist`(@TempDir tempDir: File) {
        val oldAgentDir = File(tempDir, ".workflow/agent") // doesn't exist
        val newRoot = File(tempDir, "new-root")

        // Should not throw
        StorageMigration.migrateProjectFiles(oldAgentDir, newRoot)
        assertFalse(File(newRoot, "agent").exists())
    }

    @Test
    fun `marker file prevents re-migration`(@TempDir tempDir: File) {
        val newRoot = File(tempDir, "new-root")
        newRoot.mkdirs()
        File(newRoot, "migration-v1.marker").createNewFile()

        // Even if old data exists, should skip
        val oldAgentDir = File(tempDir, ".workflow/agent")
        oldAgentDir.mkdirs()
        File(oldAgentDir, "core-memory.json").writeText("data")

        StorageMigration.migrateIfNeeded(
            projectBasePath = tempDir.absolutePath,
            newRoot = newRoot,
            oldProjectAgentDir = oldAgentDir,
            oldSystemSessionsDir = File(tempDir, "nonexistent")
        )

        // Old file should still exist (not migrated)
        assertTrue(File(oldAgentDir, "core-memory.json").exists())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*.StorageMigrationTest" --rerun`
Expected: FAIL — class not found

- [ ] **Step 3: Write the implementation**

```kotlin
// agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/StorageMigration.kt
package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.core.util.ProjectIdentifier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * One-time lazy migration from scattered storage locations to unified
 * ~/.workflow-orchestrator/{ProjectName-hash}/ layout.
 */
object StorageMigration {

    private val LOG = Logger.getInstance(StorageMigration::class.java)
    private const val MARKER = "migration-v1.marker"
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Run migration if not already done. Safe to call multiple times.
     */
    fun migrateIfNeeded(
        projectBasePath: String,
        newRoot: File = ProjectIdentifier.rootDir(projectBasePath),
        oldProjectAgentDir: File = File(projectBasePath, ".workflow/agent"),
        oldSystemSessionsDir: File = File(PathManager.getSystemPath(), "workflow-agent/sessions")
    ) {
        val marker = File(newRoot, MARKER)
        if (marker.exists()) return

        LOG.info("StorageMigration: starting migration for ${File(projectBasePath).name}")

        migrateProjectFiles(oldProjectAgentDir, newRoot)
        migrateSessions(oldSystemSessionsDir, File(newRoot, "agent/sessions"), projectBasePath)
        migrateTraces(oldProjectAgentDir, File(newRoot, "agent/sessions"))

        // Write marker
        newRoot.mkdirs()
        marker.createNewFile()
        LOG.info("StorageMigration: migration complete, marker written")

        // Clean up old project directory
        if (oldProjectAgentDir.exists() && oldProjectAgentDir.list()?.isEmpty() == true) {
            oldProjectAgentDir.delete()
            val workflowDir = oldProjectAgentDir.parentFile
            if (workflowDir?.name == ".workflow" && workflowDir.list()?.isEmpty() == true) {
                workflowDir.delete()
            }
        }
    }

    /**
     * Migrate project-level files (memory, guardrails, metrics, etc.)
     * from {projectBasePath}/.workflow/agent/ to {newRoot}/agent/
     */
    fun migrateProjectFiles(oldAgentDir: File, newRoot: File) {
        if (!oldAgentDir.exists()) return

        val newAgentDir = File(newRoot, "agent")
        newAgentDir.mkdirs()

        // Single files
        moveFileIfExists(File(oldAgentDir, "core-memory.json"), File(newAgentDir, "core-memory.json"))
        moveFileIfExists(File(oldAgentDir, "guardrails.md"), File(newAgentDir, "guardrails.md"))

        // Directories
        moveDirIfExists(File(oldAgentDir, "memory"), File(newAgentDir, "memory"))
        moveDirIfExists(File(oldAgentDir, "archival"), File(newAgentDir, "archival"))
        moveDirIfExists(File(oldAgentDir, "metrics"), File(newAgentDir, "metrics"))
    }

    /**
     * Migrate sessions from system path to unified location.
     * Matches sessions to this project via metadata.json projectPath field.
     */
    private fun migrateSessions(oldSessionsDir: File, newSessionsDir: File, projectBasePath: String) {
        if (!oldSessionsDir.exists()) return

        oldSessionsDir.listFiles()?.filter { it.isDirectory }?.forEach { sessionDir ->
            val metadataFile = File(sessionDir, "metadata.json")
            if (metadataFile.exists()) {
                try {
                    val metadata = json.parseToJsonElement(metadataFile.readText()).jsonObject
                    val sessionProjectPath = metadata["projectPath"]?.jsonPrimitive?.content
                    if (sessionProjectPath != null && File(sessionProjectPath).absolutePath == File(projectBasePath).absolutePath) {
                        val target = File(newSessionsDir, sessionDir.name)
                        if (!target.exists()) {
                            newSessionsDir.mkdirs()
                            sessionDir.renameTo(target)
                            LOG.info("StorageMigration: migrated session ${sessionDir.name}")
                        }
                    }
                } catch (e: Exception) {
                    LOG.warn("StorageMigration: failed to read metadata for ${sessionDir.name}: ${e.message}")
                }
            }
        }
    }

    /**
     * Migrate trace files from old project-level traces/ into their session directories.
     */
    private fun migrateTraces(oldAgentDir: File, newSessionsDir: File) {
        val oldTracesDir = File(oldAgentDir, "traces")
        if (!oldTracesDir.exists()) return

        oldTracesDir.listFiles()?.filter { it.name.endsWith(".trace.jsonl") }?.forEach { traceFile ->
            val sessionId = traceFile.name.removeSuffix(".trace.jsonl")
            val targetSessionDir = File(newSessionsDir, sessionId)
            if (targetSessionDir.exists()) {
                val targetTracesDir = File(targetSessionDir, "traces")
                targetTracesDir.mkdirs()
                traceFile.renameTo(File(targetTracesDir, "trace.jsonl"))
                LOG.info("StorageMigration: migrated trace for session $sessionId")
            }
        }

        // Clean up empty traces dir
        if (oldTracesDir.list()?.isEmpty() == true) {
            oldTracesDir.delete()
        }
    }

    private fun moveFileIfExists(source: File, target: File) {
        if (source.exists()) {
            target.parentFile.mkdirs()
            source.renameTo(target)
        }
    }

    private fun moveDirIfExists(source: File, target: File) {
        if (source.exists() && source.isDirectory) {
            target.parentFile.mkdirs()
            source.renameTo(target)
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :agent:test --tests "*.StorageMigrationTest" --rerun`
Expected: PASS (all 7 tests)

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/StorageMigration.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/StorageMigrationTest.kt
git commit -m "feat(agent): add StorageMigration coordinator for one-time lazy migration"
```

---

### Task 10: Wire Migration into Startup

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/listeners/AgentStartupActivity.kt` (or equivalent startup hook)

- [ ] **Step 1: Find the startup activity**

Run: `grep -rn "ProjectActivity\|StartupActivity\|postStartupActivity" --include="*.kt" agent/src/main/`

- [ ] **Step 2: Add migration call at startup**

In the startup activity's `execute()` or `runActivity()` method, add:

```kotlin
// Run storage migration (one-time, idempotent)
StorageMigration.migrateIfNeeded(project.basePath ?: return)
```

This should run early, before any agent storage is accessed.

- [ ] **Step 3: Build and verify**

Run: `./gradlew :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/listeners/
git commit -m "feat(agent): wire StorageMigration into plugin startup"
```

---

### Task 11: Update CLAUDE.md Documentation

**Files:**
- Modify: `CLAUDE.md` (root)
- Modify: `agent/CLAUDE.md`

- [ ] **Step 1: Update root CLAUDE.md**

Add a "Storage" section or update the existing architecture section to document the unified storage layout:

```markdown
## Agent Storage

All agent data lives under `~/.workflow-orchestrator/{ProjectName-hash}/`:

| Data | Path | Retention |
|---|---|---|
| Sessions | `agent/sessions/{sessionId}/` | Per-session |
| API Debug | `agent/sessions/{sessionId}/api-debug/` | Per-session |
| Traces | `agent/sessions/{sessionId}/traces/` | Per-session |
| Metrics | `agent/metrics/` | 30 days / 100 max |
| Memory | `agent/memory/`, `agent/core-memory.json`, `agent/archival/` | Persistent |
| Guardrails | `agent/guardrails.md` | Persistent |
| Logs | `logs/agent-YYYY-MM-DD.jsonl` | 7 days |

Project identifier format: `{dirName}-{first6OfSHA256(absolutePath)}`.
```

- [ ] **Step 2: Update agent/CLAUDE.md**

Update the "Evaluation & Observability" and file path references. Remove all references to `{projectBasePath}/.workflow/agent/` and `PathManager.getSystemPath()/workflow-agent/sessions/`. Replace with `ProjectIdentifier`-based paths.

Update the "LLM API" section to note: "Model is auto-resolved from `/.api/llm/models` on first use. No hardcoded defaults. Priority: Anthropic Opus thinking > Opus > Sonnet."

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md agent/CLAUDE.md
git commit -m "docs: update CLAUDE.md for unified storage and dynamic model selection"
```

---

### Task 12: Full Build and Test Verification

**Files:** None (verification only)

- [ ] **Step 1: Run all agent tests**

Run: `./gradlew :agent:clean :agent:test --rerun --no-build-cache`
Expected: All tests pass

- [ ] **Step 2: Run core tests**

Run: `./gradlew :core:test --rerun`
Expected: All tests pass

- [ ] **Step 3: Run cody tests (commit message feature)**

Run: `./gradlew :cody:test --rerun`
Expected: All tests pass

- [ ] **Step 4: Verify plugin builds**

Run: `./gradlew verifyPlugin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Build installable ZIP**

Run: `./gradlew buildPlugin`
Expected: ZIP created in `build/distributions/`

- [ ] **Step 6: Search for any remaining hardcoded model IDs**

Run: `grep -rn "anthropic::2024" --include="*.kt" core/src/main/ agent/src/main/ cody/src/main/`
Run: `grep -rn "claude-sonnet-4\|claude-opus" --include="*.kt" core/src/main/ agent/src/main/ cody/src/main/`

Expected: Zero matches in production code (only in test files with `TestModels.MOCK_MODEL`)

- [ ] **Step 7: Search for any remaining old storage paths**

Run: `grep -rn '\.workflow/agent' --include="*.kt" agent/src/main/ core/src/main/`
Run: `grep -rn 'workflow-agent/sessions' --include="*.kt" agent/src/main/`
Run: `grep -rn '\.workflow-orchestrator/agent/logs' --include="*.kt" agent/src/main/ core/src/main/`

Expected: Zero matches (all paths should go through `ProjectIdentifier`)

- [ ] **Step 8: Final commit if any fixups needed**

```bash
git add -A
git commit -m "fix: address remaining hardcoded paths and model IDs from verification"
```

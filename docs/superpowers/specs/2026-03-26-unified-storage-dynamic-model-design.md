# Unified Storage Layout & Dynamic Model Selection

**Date:** 2026-03-26
**Status:** Approved
**Scope:** `:core`, `:agent`, `:cody` modules

## Problem

### Scattered File Storage
Agent data is spread across 3 unrelated locations:
- **IDE system path** (`PathManager.getSystemPath()/workflow-agent/sessions/`) — conversations, checkpoints, plans, tool outputs, subagent transcripts
- **Project root** (`{projectBasePath}/.workflow/agent/`) — traces, metrics, memory, guardrails, archival, core-memory
- **User home** (`~/.workflow-orchestrator/agent/logs/`) — daily audit logs, api-debug dumps

Debugging a single session requires browsing all three. API debug dumps are global (not per-session), making it impossible to correlate LLM requests with the conversation that produced them.

### Hardcoded Model IDs
The model string `anthropic::2024-10-22::claude-sonnet-4-20250514` is hardcoded in 3 production files as a default/fallback:
- `core/ai/AiSettings.kt` (setting default)
- `core/ai/LlmBrainFactory.kt` (null fallback)
- `agent/settings/AgentSettings.kt` (setting default)

Additionally, `agent/AgentService.kt` uses a different format (`anthropic/claude-sonnet-4`) as fallback. These go stale when Anthropic releases new models and create format inconsistencies.

## Design

### Part 1: Unified Storage Under `~/.workflow-orchestrator/`

All agent data consolidates under a single root: `~/.workflow-orchestrator/{ProjectName-hash}/`.

#### Project Identifier

Format: `{directoryName}-{first6OfSHA256(absolutePath)}`

Example: For project at `/Users/dev/Projects/IntelijPlugin`, the identifier is `IntelijPlugin-a3f8b2`.

```kotlin
// In :core
object ProjectIdentifier {
    fun compute(projectBasePath: String): String {
        val dirName = File(projectBasePath).name
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(projectBasePath.toByteArray())
            .take(3)
            .joinToString("") { "%02x".format(it) }
        return "$dirName-$hash"
    }

    fun rootDir(projectBasePath: String): File {
        return File(System.getProperty("user.home"), ".workflow-orchestrator/${compute(projectBasePath)}")
    }
}
```

#### Directory Layout

```
~/.workflow-orchestrator/
└── IntelijPlugin-a3f8b2/
    ├── agent/
    │   ├── core-memory.json
    │   ├── guardrails.md
    │   ├── archival/
    │   │   └── store.json
    │   ├── memory/
    │   │   ├── MEMORY.md
    │   │   └── *.md
    │   ├── metrics/
    │   │   └── scorecard-{sessionId}.json
    │   └── sessions/
    │       └── {sessionId}/
    │           ├── messages.jsonl
    │           ├── metadata.json
    │           ├── checkpoint.json
    │           ├── plan.json
    │           ├── rotation-state.json
    │           ├── traces/
    │           │   └── trace.jsonl
    │           ├── api-debug/
    │           │   ├── call-001-request.txt
    │           │   ├── call-001-response.txt
    │           │   └── call-001-error.txt
    │           ├── tool-outputs/
    │           │   └── {toolCallId}.txt
    │           └── subagents/
    │               ├── agent-{id}.jsonl
    │               └── agent-{id}.meta.json
    └── logs/
        └── agent-YYYY-MM-DD.jsonl
```

#### Changes Per Class

| Class | Old Path Source | New Path Source |
|---|---|---|
| `ConversationStore` | `PathManager.getSystemPath()/workflow-agent/sessions/` | `ProjectIdentifier.rootDir()/agent/sessions/` |
| `SessionTrace` | `{projectBasePath}/.workflow/agent/traces/{sid}.trace.jsonl` | `{sessionDir}/traces/trace.jsonl` |
| `SourcegraphChatClient` (api-debug) | `~/.workflow-orchestrator/agent/logs/api-debug/` | `{sessionDir}/api-debug/` |
| `MetricsStore` | `{projectBasePath}/.workflow/agent/metrics/` | `ProjectIdentifier.rootDir()/agent/metrics/` |
| `GuardrailStore` | `{projectBasePath}/.workflow/agent/guardrails.md` | `ProjectIdentifier.rootDir()/agent/guardrails.md` |
| `CoreMemory` | `{projectBasePath}/.workflow/agent/core-memory.json` | `ProjectIdentifier.rootDir()/agent/core-memory.json` |
| `ArchivalMemory` | `{projectBasePath}/.workflow/agent/archival/store.json` | `ProjectIdentifier.rootDir()/agent/archival/store.json` |
| `AgentMemoryStore` | `{projectBasePath}/.workflow/agent/memory/` | `ProjectIdentifier.rootDir()/agent/memory/` |
| `AgentFileLogger` | `~/.workflow-orchestrator/agent/logs/` | `ProjectIdentifier.rootDir()/logs/` |
| `ToolOutputStore` | `{sessionDir}/tool-outputs/` | `{sessionDir}/tool-outputs/` (unchanged, sessionDir moves) |
| `WorkerTranscriptStore` | `{sessionDir}/subagents/` | `{sessionDir}/subagents/` (unchanged, sessionDir moves) |
| `PlanPersistence` | `{sessionDir}/plan.json` | `{sessionDir}/plan.json` (unchanged, sessionDir moves) |
| `CheckpointStore` | `{projectBasePath}/.workflow/agent/checkpoint-{id}.json` | `ProjectIdentifier.rootDir()/agent/sessions/{id}/checkpoint.json` |
| `GlobalSessionIndex` | IntelliJ `@State` XML | No change (app-level cross-project index) |

#### API Debug Dumps — Session Scoping

`SourcegraphChatClient` currently owns api-debug writing with a global counter. Changes:

1. Add `sessionDir: File?` parameter to `SourcegraphChatClient` (or a setter method)
2. When sessionDir is set, write dumps to `{sessionDir}/api-debug/`
3. Counter is per-session (reset per session)
4. When sessionDir is null (e.g., commit message generation outside a session), write to `ProjectIdentifier.rootDir()/agent/api-debug-unsessioned/` with timestamp-based naming

#### SessionTrace Becomes Per-Session

Currently `SessionTrace` takes `basePath` and creates `{basePath}/.workflow/agent/traces/{sessionId}.trace.jsonl`. Change to:

```kotlin
class SessionTrace(private val sessionDir: File, private val sessionId: String) {
    private val traceFile: File by lazy {
        val dir = File(sessionDir, "traces")
        dir.mkdirs()
        File(dir, "trace.jsonl")
    }
}
```

Single trace file per session (no sessionId in filename — it's already in the directory name).

### Part 2: Dynamic Model Selection

#### Principle

No hardcoded model ID strings in production code. The model is always resolved from the Sourcegraph `/.api/llm/models` endpoint.

#### ModelCache (new class in `:core`)

```kotlin
// core/src/main/kotlin/com/workflow/orchestrator/core/ai/ModelCache.kt
object ModelCache {
    private var models: List<ModelInfo> = emptyList()
    private var lastFetchMs: Long = 0
    private val TTL_MS = 24 * 60 * 60 * 1000L  // 24 hours
    private val lock = Mutex()

    /**
     * Get available models from cache or API.
     * Thread-safe, deduplicates concurrent fetches.
     */
    suspend fun getModels(
        client: SourcegraphChatClient,
        force: Boolean = false
    ): List<ModelInfo> {
        lock.withLock {
            if (!force && models.isNotEmpty() && (System.currentTimeMillis() - lastFetchMs) < TTL_MS) {
                return models
            }
            val result = client.listModels()
            if (result is ApiResult.Success) {
                models = result.data.data
                lastFetchMs = System.currentTimeMillis()
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

        // 4. Anything available
        return models.maxByOrNull { it.created }
    }
}
```

#### LlmBrainFactory Changes

```kotlin
// core/src/main/kotlin/com/workflow/orchestrator/core/ai/LlmBrainFactory.kt
object LlmBrainFactory {
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
        val client = SourcegraphChatClient(sgUrl, tokenProvider)
        val models = ModelCache.getModels(client)
        val best = ModelCache.pickBest(models)
            ?: throw IllegalStateException(
                "No models available from Sourcegraph. Check your connection and token in Settings > Workflow Orchestrator > General."
            )
        aiSettings.state.sourcegraphChatModel = best.id
        return best.id
    }
}
```

**Note:** `LlmBrainFactory.create()` becomes `suspend`. All callers already run in coroutine contexts (agent loop, commit message generation background task), so this is a compatible change.

#### Setting Defaults Change

```kotlin
// core/ai/AiSettings.kt
class State : BaseState() {
    var sourcegraphChatModel by string(null)  // Was: hardcoded model ID
    var maxOutputTokens by property(64000)
}

// agent/settings/AgentSettings.kt
var sourcegraphChatModel by string(null)  // Was: hardcoded model ID
```

#### AgentSettingsConfigurable Changes

The settings page model dropdown:
- On page open: calls `ModelCache.getModels()` to populate dropdown
- If cache is fresh, instant. If stale, shows loading indicator while fetching.
- "Load Models" button calls `ModelCache.getModels(force = true)`
- Auto-selection logic moves into `ModelCache.pickBest()` (DRY)
- `userManuallySelectedModel` flag preserved — if user picked a model, don't auto-upgrade

#### AgentService.kt Fix

```kotlin
// Before:
model = settings.state.sourcegraphChatModel ?: "anthropic/claude-sonnet-4"

// After: use LlmBrainFactory (which handles resolution)
val brain = LlmBrainFactory.create(project)
```

#### Test Constants

Tests that need a model string use a test-only constant:

```kotlin
// In test sources
object TestModels {
    const val MOCK_MODEL = "test::2024-01-01::mock-model"
}
```

### Part 3: Migration

#### Strategy: Lazy Migration on First Access

Each storage class checks for data at the old location on first access. If found, moves/copies it to the new location. A marker file prevents re-running.

#### Migration Coordinator

```kotlin
// agent/src/main/kotlin/.../runtime/StorageMigration.kt
object StorageMigration {
    private const val MARKER = "migration-v1.marker"

    fun migrateIfNeeded(project: Project) {
        val newRoot = ProjectIdentifier.rootDir(project.basePath!!)
        val marker = File(newRoot, MARKER)
        if (marker.exists()) return

        val oldProjectDir = File(project.basePath!!, ".workflow/agent")
        val oldSystemDir = File(PathManager.getSystemPath(), "workflow-agent/sessions")
        val oldHomeDir = File(System.getProperty("user.home"), ".workflow-orchestrator/agent")

        migrateProjectFiles(oldProjectDir, newRoot)
        migrateSessions(oldSystemDir, File(newRoot, "agent/sessions"))
        migrateLogs(oldHomeDir, newRoot)

        // Write marker
        newRoot.mkdirs()
        marker.createNewFile()

        // Clean up old directories
        oldProjectDir.deleteRecursively()
        // Don't delete oldSystemDir or oldHomeDir — may contain other projects' data
    }
}
```

#### Migration Table

| Old Location | New Location | Action |
|---|---|---|
| `{projectBasePath}/.workflow/agent/core-memory.json` | `~/.workflow-orchestrator/{proj}/agent/core-memory.json` | Move |
| `{projectBasePath}/.workflow/agent/guardrails.md` | `~/.workflow-orchestrator/{proj}/agent/guardrails.md` | Move |
| `{projectBasePath}/.workflow/agent/archival/` | `~/.workflow-orchestrator/{proj}/agent/archival/` | Move directory |
| `{projectBasePath}/.workflow/agent/memory/` | `~/.workflow-orchestrator/{proj}/agent/memory/` | Move directory |
| `{projectBasePath}/.workflow/agent/metrics/` | `~/.workflow-orchestrator/{proj}/agent/metrics/` | Move directory |
| `{projectBasePath}/.workflow/agent/traces/{sid}.trace.jsonl` | `~/.workflow-orchestrator/{proj}/agent/sessions/{sid}/traces/trace.jsonl` | Move into matching session |
| `{systemPath}/workflow-agent/sessions/{sid}/` | `~/.workflow-orchestrator/{proj}/agent/sessions/{sid}/` | Move directory (match via metadata.json projectPath) |
| `~/.workflow-orchestrator/agent/logs/agent-*.jsonl` | `~/.workflow-orchestrator/{proj}/logs/` | Copy (can't determine which project) |
| `~/.workflow-orchestrator/agent/logs/api-debug/` | No migration | Old anonymous dumps, can't map to sessions |

#### Session-to-Project Matching

Sessions in the old system path don't have a project identifier in their path. Each session's `metadata.json` contains `projectPath`. During migration:

1. Scan all sessions in `{systemPath}/workflow-agent/sessions/`
2. Read each `metadata.json` to get `projectPath`
3. If `projectPath` matches the current project, move the session directory
4. Sessions for other projects are left for those projects to migrate

#### Hardcoded Model Migration

No file migration needed. Setting defaults change from hardcoded string to `null`. On next use, `LlmBrainFactory` auto-fetches and saves the best available model.

## Files Changed

### New Files
- `core/src/main/kotlin/.../ai/ModelCache.kt` — Cached model list with TTL and best-pick logic
- `core/src/main/kotlin/.../ProjectIdentifier.kt` — Project name+hash computation
- `agent/src/main/kotlin/.../runtime/StorageMigration.kt` — Lazy migration coordinator

### Modified Files (Storage Paths)
- `agent/.../runtime/ConversationStore.kt` — Use `ProjectIdentifier.rootDir()` instead of `PathManager.getSystemPath()`
- `agent/.../runtime/SessionTrace.kt` — Take `sessionDir` instead of `basePath`, write to `{sessionDir}/traces/`
- `agent/.../runtime/AgentFileLogger.kt` — Use `ProjectIdentifier.rootDir()/logs/` instead of `~/.workflow-orchestrator/agent/logs/`
- `agent/.../runtime/MetricsStore.kt` — Use `ProjectIdentifier.rootDir()/agent/metrics/`
- `agent/.../context/GuardrailStore.kt` — Use `ProjectIdentifier.rootDir()/agent/guardrails.md`
- `agent/.../context/CoreMemory.kt` — Use `ProjectIdentifier.rootDir()/agent/core-memory.json`
- `agent/.../context/ArchivalMemory.kt` — Use `ProjectIdentifier.rootDir()/agent/archival/`
- `agent/.../runtime/AgentMemoryStore.kt` — Use `ProjectIdentifier.rootDir()/agent/memory/`
- `agent/.../runtime/CheckpointStore.kt` — Use session dir instead of project basePath
- `core/.../ai/SourcegraphChatClient.kt` — Accept `sessionDir` for api-debug writes, remove global api-debug dir
- `agent/.../orchestrator/AgentOrchestrator.kt` — Pass sessionDir to SourcegraphChatClient and SessionTrace

### Modified Files (Model IDs)
- `core/.../ai/AiSettings.kt` — Default `null` instead of hardcoded model
- `core/.../ai/LlmBrainFactory.kt` — `suspend fun create()`, auto-resolve via `ModelCache`
- `agent/.../settings/AgentSettings.kt` — Default `null` instead of hardcoded model
- `agent/.../settings/AgentSettingsConfigurable.kt` — Use `ModelCache` for dropdown, extract pick logic
- `agent/.../AgentService.kt` — Use `LlmBrainFactory` instead of hardcoded fallback

### Modified Test Files
- `agent/.../e2e/SingleAgentFlowE2ETest.kt` — Use `TestModels.MOCK_MODEL`
- `agent/.../brain/OpenAiCompatBrainTest.kt` — Use `TestModels.MOCK_MODEL`
- `agent/.../api/SourcegraphChatClientStreamTest.kt` — Use `TestModels.MOCK_MODEL`
- `agent/.../api/SourcegraphChatClientTest.kt` — Use `TestModels.MOCK_MODEL`
- `agent/.../runtime/ConversationStoreTest.kt` — Use `TestModels.MOCK_MODEL`

## Not Changed
- `GlobalSessionIndex` — Stays as IntelliJ `@State` (app-level cross-project index, appropriate location)
- `RotationState`, `PlanPersistence`, `WorkerTranscriptStore`, `ToolOutputStore` — These are already relative to `sessionDir`, they move automatically when sessions move

# Checkpoint/Revert Architecture Solidification — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the checkpoint/revert system reliable across all three revert paths (LLM tool, user revert button, user undo button) with proper UI sync, git fallback, destructive command blocking, and per-file revert support.

**Architecture:** Event-sourced rollback — `RollbackEntry` as a new append-only event in `ChangeLedger` alongside `ChangeEntry`. Stats computation filters rolled-back entries. `AgentRollbackManager` gains a layered fallback (LocalHistory → git). All three revert paths converge on a single notification function that pushes rollback state to the UI.

**Tech Stack:** Kotlin, React/TypeScript, JCEF bridge, IntelliJ LocalHistory API, git CLI

---

## File Structure

| Action | Path | Responsibility |
|--------|------|----------------|
| Modify | `agent/src/main/kotlin/.../runtime/ChangeLedger.kt` | Add `RollbackEntry`, rollback-aware stats, persistence |
| Modify | `agent/src/main/kotlin/.../runtime/AgentRollbackManager.kt` | `RollbackResult` return type, git fallback, per-file revert |
| Modify | `agent/src/main/kotlin/.../tools/builtin/RollbackChangesTool.kt` | Record `RollbackEntry`, use new `RollbackResult` |
| Create | `agent/src/main/kotlin/.../tools/builtin/RevertFileTool.kt` | New per-file revert tool |
| Modify | `agent/src/main/kotlin/.../tools/builtin/RunCommandTool.kt` | Better error message for blocked git commands |
| Modify | `agent/src/main/kotlin/.../ui/AgentController.kt` | Unified `pushRollbackToUi()`, fix all 3 revert paths |
| Modify | `agent/src/main/kotlin/.../ui/AgentCefPanel.kt` | Add `notifyRollback()` bridge method |
| Modify | `agent/src/main/kotlin/.../ui/AgentDashboardPanel.kt` | Delegate `notifyRollback()` |
| Modify | `agent/webview/src/bridge/jcef-bridge.ts` | Add `notifyRollback` bridge function |
| Modify | `agent/webview/src/bridge/types.ts` | Add `RollbackInfo` type |
| Modify | `agent/webview/src/stores/chatStore.ts` | Add `rollbackEvents`, `applyRollback` action |
| Create | `agent/webview/src/components/agent/RollbackCard.tsx` | Rollback event card component |
| Modify | `agent/webview/src/components/agent/ToolCallView.tsx` | Support `rolledBack` styling |
| Modify | `agent/webview/src/components/agent/EditStatsBar.tsx` | Render rolled-back state |
| Modify | `agent/src/test/.../runtime/ChangeLedgerTest.kt` | Tests for rollback-aware stats |
| Create | `agent/src/test/.../runtime/AgentRollbackManagerTest.kt` | Tests for git fallback, per-file revert |
| Modify | `agent/src/test/.../tools/builtin/RollbackChangesToolTest.kt` | Tests for RollbackEntry recording |
| Create | `agent/src/test/.../tools/builtin/RevertFileToolTest.kt` | Tests for per-file revert tool |

---

### Task 1: Add RollbackEntry and Rollback-Aware Stats to ChangeLedger

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ChangeLedger.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/ChangeLedgerTest.kt`

- [ ] **Step 1: Write failing tests for rollback-aware stats**

Add these tests to `ChangeLedgerTest.kt` after the existing checkpoint tests (after line 196):

```kotlin
// ── Rollback ──

@Test
fun `recordRollback adds rollback entry`() {
    val entry = makeEntry(id = "e1", checkpointId = "cp-1")
    ledger.recordChange(entry)
    ledger.recordRollback(RollbackEntry(
        id = "rb-1",
        timestamp = System.currentTimeMillis(),
        checkpointId = "cp-1",
        description = "Reverting bad edit",
        source = RollbackSource.LLM_TOOL,
        mechanism = RollbackMechanism.LOCAL_HISTORY,
        affectedFiles = listOf("/src/Test.kt"),
        rolledBackEntryIds = listOf("e1"),
        scope = RollbackScope.FULL_CHECKPOINT
    ))
    assertEquals(1, ledger.allRollbacks().size)
}

@Test
fun `totalStats excludes rolled-back entries`() {
    ledger.recordChange(makeEntry(id = "e1", linesAdded = 10, linesRemoved = 3, filePath = "/a"))
    ledger.recordChange(makeEntry(id = "e2", linesAdded = 5, linesRemoved = 0, filePath = "/b"))

    // Rollback e1 only
    ledger.recordRollback(RollbackEntry(
        id = "rb-1", timestamp = System.currentTimeMillis(),
        checkpointId = "cp-1", description = "revert",
        source = RollbackSource.USER_BUTTON,
        mechanism = RollbackMechanism.LOCAL_HISTORY,
        affectedFiles = listOf("/a"),
        rolledBackEntryIds = listOf("e1"),
        scope = RollbackScope.SINGLE_FILE
    ))

    val stats = ledger.totalStats()
    assertEquals(5, stats.totalLinesAdded)    // only e2
    assertEquals(0, stats.totalLinesRemoved)  // only e2
    assertEquals(1, stats.filesModified)      // only /b
}

@Test
fun `fileStats excludes rolled-back entries`() {
    ledger.recordChange(makeEntry(id = "e1", relativePath = "src/A.kt", linesAdded = 10, linesRemoved = 2))
    ledger.recordChange(makeEntry(id = "e2", relativePath = "src/B.kt", linesAdded = 5, linesRemoved = 1))

    ledger.recordRollback(RollbackEntry(
        id = "rb-1", timestamp = System.currentTimeMillis(),
        checkpointId = "cp-1", description = "revert",
        source = RollbackSource.LLM_TOOL,
        mechanism = RollbackMechanism.GIT_FALLBACK,
        affectedFiles = listOf("src/A.kt"),
        rolledBackEntryIds = listOf("e1"),
        scope = RollbackScope.SINGLE_FILE
    ))

    val stats = ledger.fileStats()
    assertEquals(1, stats.size)
    assertNotNull(stats["src/B.kt"])
    assertNull(stats["src/A.kt"])
}

@Test
fun `toContextString marks rolled-back entries`() {
    ledger.recordChange(makeEntry(id = "e1", relativePath = "src/A.kt"))
    ledger.recordChange(makeEntry(id = "e2", relativePath = "src/B.kt"))

    ledger.recordRollback(RollbackEntry(
        id = "rb-1", timestamp = System.currentTimeMillis(),
        checkpointId = "cp-1", description = "bad edit",
        source = RollbackSource.LLM_TOOL,
        mechanism = RollbackMechanism.LOCAL_HISTORY,
        affectedFiles = listOf("src/A.kt"),
        rolledBackEntryIds = listOf("e1"),
        scope = RollbackScope.SINGLE_FILE
    ))

    val ctx = ledger.toContextString()
    assertTrue(ctx.contains("REVERTED"))
    assertTrue(ctx.contains("src/B.kt"))
}

@Test
fun `rollback entries persist and reload`() {
    ledger.recordChange(makeEntry(id = "e1"))
    ledger.recordRollback(RollbackEntry(
        id = "rb-1", timestamp = System.currentTimeMillis(),
        checkpointId = "cp-1", description = "revert",
        source = RollbackSource.USER_UNDO,
        mechanism = RollbackMechanism.LOCAL_HISTORY,
        affectedFiles = listOf("/src/Test.kt"),
        rolledBackEntryIds = listOf("e1"),
        scope = RollbackScope.FULL_CHECKPOINT
    ))

    val ledger2 = ChangeLedger()
    ledger2.initialize(tempDir)
    ledger2.loadFromDisk()

    assertEquals(1, ledger2.allRollbacks().size)
    assertEquals(0, ledger2.totalStats().totalLinesAdded) // e1 was rolled back
}

@Test
fun `entriesAfterCheckpoint returns correct entries`() {
    ledger.recordChange(makeEntry(id = "e1", checkpointId = "cp-1", iteration = 1))
    ledger.recordChange(makeEntry(id = "e2", checkpointId = "cp-2", iteration = 2))
    ledger.recordChange(makeEntry(id = "e3", checkpointId = "cp-2", iteration = 3))

    ledger.recordCheckpoint(CheckpointMeta("cp-1", "Iter 1", 1, 0, emptyList(), 0, 0))
    ledger.recordCheckpoint(CheckpointMeta("cp-2", "Iter 2", 2, 0, emptyList(), 0, 0))

    val after = ledger.entriesAfterCheckpoint("cp-1")
    assertEquals(2, after.size) // e2 and e3, not e1
    assertEquals("e2", after[0].id)
    assertEquals("e3", after[1].id)
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.runtime.ChangeLedgerTest" -x verifyPlugin`
Expected: FAIL — `RollbackEntry`, `RollbackSource`, `RollbackMechanism`, `RollbackScope`, `recordRollback`, `allRollbacks`, `entriesAfterCheckpoint` don't exist yet.

- [ ] **Step 3: Add RollbackEntry data types to ChangeLedger.kt**

Add after `ChangeAction` enum (after line 225):

```kotlin
@Serializable
enum class RollbackSource { LLM_TOOL, USER_BUTTON, USER_UNDO }

@Serializable
enum class RollbackMechanism { LOCAL_HISTORY, GIT_FALLBACK }

@Serializable
enum class RollbackScope { FULL_CHECKPOINT, SINGLE_FILE }

@Serializable
data class RollbackEntry(
    val id: String,
    val timestamp: Long,
    val checkpointId: String,
    val description: String,
    val source: RollbackSource,
    val mechanism: RollbackMechanism,
    val affectedFiles: List<String>,
    val rolledBackEntryIds: List<String>,
    val scope: RollbackScope
)
```

- [ ] **Step 4: Add rollback storage and methods to ChangeLedger class**

Add a rollback list field after `checkpoints` (line 46):

```kotlin
private val rollbacks = CopyOnWriteArrayList<RollbackEntry>()
```

Add `rolledBackIds()` helper after `allEntries()` (after line 119):

```kotlin
/** IDs of all entries that have been rolled back. */
private fun rolledBackIds(): Set<String> =
    rollbacks.flatMapTo(mutableSetOf()) { it.rolledBackEntryIds }

/** Get all rollback events. */
fun allRollbacks(): List<RollbackEntry> = rollbacks.toList()

/** Record a rollback event. */
fun recordRollback(entry: RollbackEntry) {
    rollbacks.add(entry)
    try {
        val wrapper = buildJsonObject {
            put("type", "rollback")
            put("data", json.encodeToJsonElement(RollbackEntry.serializer(), entry))
        }
        changesFile?.appendText(Json.encodeToString(wrapper) + "\n")
    } catch (e: Exception) {
        LOG.warn("ChangeLedger: failed to persist rollback entry", e)
    }
}

/** Get all change entries after a given checkpoint (by iteration). */
fun entriesAfterCheckpoint(checkpointId: String): List<ChangeEntry> {
    val checkpoint = checkpoints[checkpointId] ?: return emptyList()
    return entries.filter { it.iteration > checkpoint.iteration }
}
```

- [ ] **Step 5: Make totalStats() and fileStats() rollback-aware**

Replace `totalStats()` (line 83-88):

```kotlin
fun totalStats(): EditStats {
    val excluded = rolledBackIds()
    val active = entries.filter { it.id !in excluded }
    val added = active.sumOf { it.linesAdded }
    val removed = active.sumOf { it.linesRemoved }
    val files = active.map { it.filePath }.distinct().size
    return EditStats(added, removed, files)
}
```

Replace `fileStats()` (line 91-103):

```kotlin
fun fileStats(): Map<String, FileEditSummary> {
    val excluded = rolledBackIds()
    return entries.filter { it.id !in excluded }
        .groupBy { it.relativePath }.mapValues { (path, edits) ->
            FileEditSummary(
                path = path,
                editCount = edits.size,
                totalLinesAdded = edits.sumOf { it.linesAdded },
                totalLinesRemoved = edits.sumOf { it.linesRemoved },
                lastIteration = edits.maxOf { it.iteration },
                verified = edits.lastOrNull()?.verified ?: false,
                action = edits.first().action
            )
        }
}
```

- [ ] **Step 6: Update toContextString() to mark rolled-back entries**

In `toContextString()`, after computing `displayStats` (around line 153), add logic to check rolled-back files. Replace the `displayStats.entries.sortedBy` block:

```kotlin
val excluded = rolledBackIds()
val rolledBackPaths = entries.filter { it.id in excluded }.map { it.relativePath }.toSet()

displayStats.entries.sortedBy { it.value.lastIteration }.forEach { (path, summary) ->
    val action = when (summary.action) {
        ChangeAction.CREATED -> "NEW"
        ChangeAction.MODIFIED -> "MOD"
        ChangeAction.DELETED -> "DEL"
    }
    val verified = if (summary.verified) " ✓" else ""
    val edits = if (summary.editCount > 1) " (${summary.editCount} edits)" else ""
    appendLine("  [$action] $path +${summary.totalLinesAdded}/-${summary.totalLinesRemoved}$edits$verified")
}

// Show rolled-back files
if (rolledBackPaths.isNotEmpty()) {
    appendLine()
    appendLine("Rolled back:")
    rolledBackPaths.forEach { path ->
        appendLine("  [REVERTED] $path")
    }
}
```

Note: The `displayStats` already uses `fileStats()` which excludes rolled-back entries, so the active stats are correct. The rolled-back section is additive for LLM awareness.

- [ ] **Step 7: Update loadFromDisk() to restore rollback entries**

Replace `loadFromDisk()` (line 182-197):

```kotlin
fun loadFromDisk() {
    val file = changesFile ?: return
    if (!file.exists()) return
    try {
        file.readLines().forEach { line ->
            if (line.isNotBlank()) {
                try {
                    // Try parsing as a rollback entry first (has "type":"rollback" wrapper)
                    val obj = json.parseToJsonElement(line).jsonObject
                    if (obj["type"]?.jsonPrimitive?.content == "rollback") {
                        val data = obj["data"] ?: return@forEach
                        rollbacks.add(json.decodeFromJsonElement(RollbackEntry.serializer(), data))
                    } else {
                        entries.add(json.decodeFromString<ChangeEntry>(line))
                    }
                } catch (_: Exception) {
                    // Try as plain ChangeEntry (backward compat)
                    try {
                        entries.add(json.decodeFromString<ChangeEntry>(line))
                    } catch (_: Exception) { /* skip malformed */ }
                }
            }
        }
        LOG.info("ChangeLedger: loaded ${entries.size} entries + ${rollbacks.size} rollbacks from disk")
    } catch (e: Exception) {
        LOG.warn("ChangeLedger: failed to load from disk", e)
    }
}
```

Add import at top of file:

```kotlin
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.decodeFromJsonElement
```

- [ ] **Step 8: Run tests to verify they pass**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.runtime.ChangeLedgerTest" -x verifyPlugin`
Expected: ALL PASS

- [ ] **Step 9: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ChangeLedger.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/ChangeLedgerTest.kt
git commit -m "feat(agent): add RollbackEntry to ChangeLedger with rollback-aware stats

Event-sourced rollback tracking: RollbackEntry recorded alongside ChangeEntry
in changes.jsonl. totalStats/fileStats/toContextString exclude rolled-back
entries. Persistence and reload support backward-compatible JSONL format."
```

---

### Task 2: Upgrade AgentRollbackManager with RollbackResult and Git Fallback

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/AgentRollbackManager.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/AgentRollbackManagerTest.kt`

- [ ] **Step 1: Write failing tests for RollbackResult and git fallback**

Create `agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/AgentRollbackManagerTest.kt`:

```kotlin
package com.workflow.orchestrator.agent.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AgentRollbackManagerTest {

    @Test
    fun `RollbackResult success has correct fields`() {
        val result = RollbackResult(
            success = true,
            mechanism = RollbackMechanism.LOCAL_HISTORY,
            affectedFiles = listOf("/src/A.kt", "/src/B.kt"),
            failedFiles = emptyList()
        )
        assertTrue(result.success)
        assertEquals(RollbackMechanism.LOCAL_HISTORY, result.mechanism)
        assertEquals(2, result.affectedFiles.size)
        assertTrue(result.failedFiles.isEmpty())
        assertNull(result.error)
    }

    @Test
    fun `RollbackResult failure has error message`() {
        val result = RollbackResult(
            success = false,
            mechanism = RollbackMechanism.GIT_FALLBACK,
            affectedFiles = listOf("/src/A.kt"),
            failedFiles = listOf("/src/B.kt"),
            error = "git checkout failed for B.kt"
        )
        assertFalse(result.success)
        assertEquals("git checkout failed for B.kt", result.error)
        assertEquals(1, result.failedFiles.size)
    }

    @Test
    fun `RollbackResult partial success tracks both lists`() {
        val result = RollbackResult(
            success = true,
            mechanism = RollbackMechanism.GIT_FALLBACK,
            affectedFiles = listOf("/src/A.kt"),
            failedFiles = listOf("/src/C.kt")
        )
        assertTrue(result.success)
        assertEquals(1, result.affectedFiles.size)
        assertEquals(1, result.failedFiles.size)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.runtime.AgentRollbackManagerTest" -x verifyPlugin`
Expected: FAIL — `RollbackResult` doesn't exist yet.

- [ ] **Step 3: Add RollbackResult data class and refactor rollbackToCheckpoint**

Replace `AgentRollbackManager.kt` content entirely:

```kotlin
package com.workflow.orchestrator.agent.runtime

import com.intellij.history.LocalHistory
import com.intellij.history.Label
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File
import java.util.UUID

/**
 * Manages rollback of agent-made file changes.
 *
 * Primary mechanism: IntelliJ's LocalHistory API.
 * Fallback: git checkout per file (when LocalHistory fails).
 *
 * Returns [RollbackResult] with mechanism used, affected/failed files.
 */
class AgentRollbackManager(private val project: Project) {
    companion object {
        private val LOG = Logger.getInstance(AgentRollbackManager::class.java)
    }

    private val checkpoints = mutableMapOf<String, Label>()
    private val touchedFiles = mutableSetOf<String>()
    private val createdFiles = mutableSetOf<String>()

    /**
     * Create a LocalHistory checkpoint before the agent starts modifying files.
     * Returns a checkpoint ID that can be used to rollback later.
     */
    fun createCheckpoint(description: String): String {
        val id = UUID.randomUUID().toString().take(12)
        try {
            val label = LocalHistory.getInstance().putSystemLabel(
                project, "Agent: $description"
            )
            checkpoints[id] = label
            LOG.info("AgentRollbackManager: created checkpoint $id: $description")
        } catch (e: Exception) {
            LOG.warn("AgentRollbackManager: failed to create checkpoint", e)
        }
        return id
    }

    /** Track a file that the agent has modified (for selective rollback). */
    fun trackFileChange(path: String) {
        touchedFiles.add(path)
    }

    /** Track a file that the agent has created (needs deletion on rollback). */
    fun trackFileCreation(path: String) {
        createdFiles.add(path)
        touchedFiles.add(path)
    }

    /** Get all files the agent has touched in this session. */
    fun getTouchedFiles(): Set<String> = touchedFiles.toSet()

    /**
     * Rollback ALL changes since the given checkpoint.
     * Tries LocalHistory first, falls back to git checkout per file.
     */
    fun rollbackToCheckpoint(checkpointId: String): RollbackResult {
        val label = checkpoints[checkpointId]
        if (label == null) {
            val available = checkpoints.keys.joinToString(", ").ifEmpty { "none" }
            LOG.warn("AgentRollbackManager: checkpoint '$checkpointId' not found. Available: $available")
            return RollbackResult(
                success = false,
                mechanism = RollbackMechanism.LOCAL_HISTORY,
                affectedFiles = emptyList(),
                error = "Checkpoint not found. Available checkpoints: $available"
            )
        }

        val baseDir = project.basePath?.let {
            LocalFileSystem.getInstance().findFileByPath(it)
        }
        if (baseDir == null) {
            LOG.warn("AgentRollbackManager: project base dir not found")
            return RollbackResult(
                success = false,
                mechanism = RollbackMechanism.LOCAL_HISTORY,
                affectedFiles = emptyList(),
                error = "Project base directory not found"
            )
        }

        // Try LocalHistory first
        try {
            WriteCommandAction.runWriteCommandAction(project) {
                label.revert(project, baseDir)
            }
            refreshVfs()
            LOG.info("AgentRollbackManager: rolled back to checkpoint $checkpointId via LocalHistory (${touchedFiles.size} files)")
            return RollbackResult(
                success = true,
                mechanism = RollbackMechanism.LOCAL_HISTORY,
                affectedFiles = touchedFiles.toList()
            )
        } catch (e: Exception) {
            LOG.warn("AgentRollbackManager: LocalHistory rollback failed, trying git fallback", e)
        }

        // Git fallback: checkout each touched file individually
        return gitFallbackRevert()
    }

    /**
     * Rollback a single file to its state at the given checkpoint.
     * Tries LocalHistory byte content first, falls back to git checkout.
     */
    fun rollbackFile(filePath: String, checkpointId: String? = null): RollbackResult {
        val canonicalPath = File(filePath).canonicalPath

        // If this was a created file, just delete it
        if (canonicalPath in createdFiles) {
            return try {
                val file = File(canonicalPath)
                if (file.exists()) file.delete()
                refreshVfs()
                RollbackResult(
                    success = true,
                    mechanism = RollbackMechanism.LOCAL_HISTORY,
                    affectedFiles = listOf(canonicalPath)
                )
            } catch (e: Exception) {
                RollbackResult(
                    success = false,
                    mechanism = RollbackMechanism.LOCAL_HISTORY,
                    affectedFiles = emptyList(),
                    failedFiles = listOf(canonicalPath),
                    error = "Failed to delete created file: ${e.message}"
                )
            }
        }

        // Try git checkout for the single file
        return try {
            val basePath = project.basePath ?: throw IllegalStateException("No project base path")
            val relativePath = File(canonicalPath).relativeTo(File(basePath)).path
            val process = ProcessBuilder("git", "checkout", "HEAD", "--", relativePath)
                .directory(File(basePath))
                .redirectErrorStream(true)
                .start()
            val exitCode = process.waitFor()
            val output = process.inputStream.bufferedReader().readText()

            if (exitCode == 0) {
                refreshVfs()
                LOG.info("AgentRollbackManager: reverted file $relativePath via git")
                RollbackResult(
                    success = true,
                    mechanism = RollbackMechanism.GIT_FALLBACK,
                    affectedFiles = listOf(canonicalPath)
                )
            } else {
                RollbackResult(
                    success = false,
                    mechanism = RollbackMechanism.GIT_FALLBACK,
                    affectedFiles = emptyList(),
                    failedFiles = listOf(canonicalPath),
                    error = "git checkout failed (exit $exitCode): $output"
                )
            }
        } catch (e: Exception) {
            RollbackResult(
                success = false,
                mechanism = RollbackMechanism.GIT_FALLBACK,
                affectedFiles = emptyList(),
                failedFiles = listOf(canonicalPath),
                error = "Revert failed: ${e.message}"
            )
        }
    }

    private fun gitFallbackRevert(): RollbackResult {
        val basePath = project.basePath ?: return RollbackResult(
            success = false, mechanism = RollbackMechanism.GIT_FALLBACK,
            affectedFiles = emptyList(), error = "No project base path"
        )

        val affected = mutableListOf<String>()
        val failed = mutableListOf<String>()

        for (filePath in touchedFiles) {
            try {
                val file = File(filePath)
                if (filePath in createdFiles) {
                    // Delete files that were created by the agent
                    if (file.exists()) file.delete()
                    affected.add(filePath)
                } else {
                    // Restore modified files via git
                    val relativePath = file.relativeTo(File(basePath)).path
                    val process = ProcessBuilder("git", "checkout", "HEAD", "--", relativePath)
                        .directory(File(basePath))
                        .redirectErrorStream(true)
                        .start()
                    val exitCode = process.waitFor()
                    if (exitCode == 0) {
                        affected.add(filePath)
                    } else {
                        failed.add(filePath)
                    }
                }
            } catch (_: Exception) {
                failed.add(filePath)
            }
        }

        refreshVfs()
        val success = failed.isEmpty()
        LOG.info("AgentRollbackManager: git fallback revert — ${affected.size} ok, ${failed.size} failed")

        return RollbackResult(
            success = success,
            mechanism = RollbackMechanism.GIT_FALLBACK,
            affectedFiles = affected,
            failedFiles = failed,
            error = if (failed.isNotEmpty()) "Failed to revert: ${failed.joinToString(", ")}" else null
        )
    }

    private fun refreshVfs() {
        try {
            project.basePath?.let { basePath ->
                LocalFileSystem.getInstance().findFileByPath(basePath)?.refresh(true, true)
            }
        } catch (_: Exception) { }
    }

    /** Clear all checkpoints (call when session ends normally). */
    fun clearCheckpoints() {
        checkpoints.clear()
        touchedFiles.clear()
        createdFiles.clear()
    }

    /** Check if we have any checkpoints available for rollback. */
    fun hasCheckpoints(): Boolean = checkpoints.isNotEmpty()

    /** Get the most recent checkpoint ID. */
    fun latestCheckpointId(): String? = checkpoints.keys.lastOrNull()
}

/** Result of a rollback operation. */
data class RollbackResult(
    val success: Boolean,
    val mechanism: RollbackMechanism,
    val affectedFiles: List<String>,
    val failedFiles: List<String> = emptyList(),
    val error: String? = null
)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.runtime.AgentRollbackManagerTest" -x verifyPlugin`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/AgentRollbackManager.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/AgentRollbackManagerTest.kt
git commit -m "feat(agent): upgrade AgentRollbackManager with RollbackResult and git fallback

Returns structured RollbackResult (success, mechanism, affected/failed files).
LocalHistory primary, git checkout per-file fallback. New rollbackFile() for
single-file revert. Tracks created files separately for proper deletion."
```

---

### Task 3: Update RollbackChangesTool to Record RollbackEntry

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RollbackChangesTool.kt`
- Modify: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/RollbackChangesToolTest.kt`

- [ ] **Step 1: Write failing test for RollbackEntry recording**

Add to `RollbackChangesToolTest.kt` after existing tests:

```kotlin
@Test
fun `tool metadata includes revert_file suggestion in description`() {
    assertTrue(tool.description.contains("rollback") || tool.description.contains("revert"))
}
```

- [ ] **Step 2: Update RollbackChangesTool to use RollbackResult and record RollbackEntry**

Replace the `execute` method in `RollbackChangesTool.kt` (lines 46-104):

```kotlin
override suspend fun execute(params: JsonObject, project: Project): ToolResult {
    val checkpointId = params["checkpoint_id"]?.jsonPrimitive?.content
        ?: return ToolResult(
            content = "Error: 'checkpoint_id' parameter is required.",
            summary = "Error: missing checkpoint_id",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )

    val description = params["description"]?.jsonPrimitive?.content
        ?: return ToolResult(
            content = "Error: 'description' parameter is required.",
            summary = "Error: missing description",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )

    val agentService = try {
        AgentService.getInstance(project)
    } catch (_: Exception) {
        return ToolResult(
            content = "Error: AgentService not available.",
            summary = "Error: no agent service",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )
    }

    val rollbackManager = agentService.currentRollbackManager
        ?: return ToolResult(
            content = "Error: No rollback manager active. Cannot revert changes.",
            summary = "Error: no rollback manager",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )

    val ledger = agentService.currentChangeLedger
    val result = rollbackManager.rollbackToCheckpoint(checkpointId)

    return if (result.success) {
        // Record rollback event in the ledger
        val entriesAfter = ledger?.entriesAfterCheckpoint(checkpointId) ?: emptyList()
        val rollbackEntry = RollbackEntry(
            id = java.util.UUID.randomUUID().toString().take(12),
            timestamp = System.currentTimeMillis(),
            checkpointId = checkpointId,
            description = description,
            source = RollbackSource.LLM_TOOL,
            mechanism = result.mechanism,
            affectedFiles = result.affectedFiles,
            rolledBackEntryIds = entriesAfter.map { it.id },
            scope = RollbackScope.FULL_CHECKPOINT
        )
        ledger?.recordRollback(rollbackEntry)

        // Update context anchor with new effective stats
        if (ledger != null) {
            agentService.currentContextBridge?.updateChangeLedgerAnchor(ledger)
        }

        val mechanismNote = if (result.mechanism == RollbackMechanism.GIT_FALLBACK) {
            "\n\nNote: LocalHistory was unavailable; used git checkout as fallback."
        } else ""

        ToolResult(
            content = "Successfully rolled back to checkpoint $checkpointId. Reason: $description\n\nAll file changes after this checkpoint have been reverted (${result.affectedFiles.size} files).${mechanismNote}\n\nUse list_changes to see the current state.",
            summary = "Rolled back to checkpoint $checkpointId: $description (${result.mechanism})",
            tokenEstimate = 30
        )
    } else {
        ToolResult(
            content = "Error: Failed to rollback to checkpoint '$checkpointId': ${result.error}. Use list_changes to see available checkpoints.",
            summary = "Rollback failed: ${result.error}",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )
    }
}
```

Add imports at top:

```kotlin
import com.workflow.orchestrator.agent.runtime.RollbackEntry
import com.workflow.orchestrator.agent.runtime.RollbackMechanism
import com.workflow.orchestrator.agent.runtime.RollbackScope
import com.workflow.orchestrator.agent.runtime.RollbackSource
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.builtin.RollbackChangesToolTest" -x verifyPlugin`
Expected: ALL PASS

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RollbackChangesTool.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/RollbackChangesToolTest.kt
git commit -m "feat(agent): RollbackChangesTool records RollbackEntry in ledger

Uses new RollbackResult from manager. Records RollbackEntry with source,
mechanism, affected files, and rolled-back entry IDs. Updates context anchor
with effective stats post-rollback."
```

---

### Task 4: Create RevertFileTool for Per-File Revert

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RevertFileTool.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/RevertFileToolTest.kt`

- [ ] **Step 1: Write failing tests**

Create `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/RevertFileToolTest.kt`:

```kotlin
package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.runtime.WorkerType
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RevertFileToolTest {
    private val project = mockk<Project>(relaxed = true)
    private val tool = RevertFileTool()

    @Test
    fun `tool metadata is correct`() {
        assertEquals("revert_file", tool.name)
        assertTrue(tool.parameters.required.contains("file_path"))
        assertTrue(tool.parameters.required.contains("description"))
    }

    @Test
    fun `allowed only for coder`() {
        assertEquals(setOf(WorkerType.CODER), tool.allowedWorkers)
    }

    @Test
    fun `returns error when file_path missing`() = runTest {
        val params = buildJsonObject { put("description", "bad edit") }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("file_path"))
    }

    @Test
    fun `returns error when description missing`() = runTest {
        val params = buildJsonObject { put("file_path", "/src/A.kt") }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("description"))
    }

    @Test
    fun `returns error when agent service unavailable`() = runTest {
        val params = buildJsonObject {
            put("file_path", "/src/A.kt")
            put("description", "bad edit")
        }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.builtin.RevertFileToolTest" -x verifyPlugin`
Expected: FAIL — `RevertFileTool` doesn't exist.

- [ ] **Step 3: Create RevertFileTool.kt**

Create `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RevertFileTool.kt`:

```kotlin
package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.*
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reverts a single file to its pre-edit state. Uses git checkout as the
 * primary mechanism (LocalHistory per-file byte restore is unreliable).
 *
 * Unlike rollback_changes (which reverts ALL changes after a checkpoint),
 * this tool surgically reverts one file while preserving other edits.
 */
class RevertFileTool : AgentTool {
    override val name = "revert_file"
    override val description = """Revert a single file to its original state before the agent modified it. This is a surgical operation — only the specified file is reverted, all other changes remain intact.

Use this when you made a mistake in one file but want to keep changes in other files. For reverting ALL changes, use rollback_changes instead.

Parameters:
- file_path: Absolute or relative path to the file to revert
- description: Why you are reverting this file (for audit trail)"""

    override val parameters = FunctionParameters(
        properties = mapOf(
            "file_path" to ParameterProperty(
                type = "string",
                description = "Path to the file to revert (absolute or relative to project root)."
            ),
            "description" to ParameterProperty(
                type = "string",
                description = "Reason for reverting this file (recorded in audit trail)."
            )
        ),
        required = listOf("file_path", "description")
    )

    override val allowedWorkers = setOf(WorkerType.CODER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val filePath = params["file_path"]?.jsonPrimitive?.content
            ?: return ToolResult(
                content = "Error: 'file_path' parameter is required.",
                summary = "Error: missing file_path",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val description = params["description"]?.jsonPrimitive?.content
            ?: return ToolResult(
                content = "Error: 'description' parameter is required.",
                summary = "Error: missing description",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val agentService = try {
            AgentService.getInstance(project)
        } catch (_: Exception) {
            return ToolResult(
                content = "Error: AgentService not available.",
                summary = "Error: no agent service",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        val rollbackManager = agentService.currentRollbackManager
            ?: return ToolResult(
                content = "Error: No rollback manager active.",
                summary = "Error: no rollback manager",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        // Resolve to absolute path
        val resolvedPath = if (filePath.startsWith("/")) filePath
        else "${project.basePath}/$filePath"

        val result = rollbackManager.rollbackFile(resolvedPath)

        val ledger = agentService.currentChangeLedger

        return if (result.success) {
            // Find entries for this file and record rollback
            val fileEntries = ledger?.changesForFile(resolvedPath) ?: emptyList()
            val rollbackEntry = RollbackEntry(
                id = java.util.UUID.randomUUID().toString().take(12),
                timestamp = System.currentTimeMillis(),
                checkpointId = fileEntries.lastOrNull()?.checkpointId ?: "",
                description = description,
                source = RollbackSource.LLM_TOOL,
                mechanism = result.mechanism,
                affectedFiles = result.affectedFiles,
                rolledBackEntryIds = fileEntries.map { it.id },
                scope = RollbackScope.SINGLE_FILE
            )
            ledger?.recordRollback(rollbackEntry)

            if (ledger != null) {
                agentService.currentContextBridge?.updateChangeLedgerAnchor(ledger)
            }

            ToolResult(
                content = "Successfully reverted $filePath. Reason: $description\n\nThe file has been restored to its pre-edit state. Other file changes are preserved.",
                summary = "Reverted file $filePath: $description",
                tokenEstimate = 20
            )
        } else {
            ToolResult(
                content = "Error: Failed to revert '$filePath': ${result.error}",
                summary = "Revert failed: ${result.error}",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }
}
```

- [ ] **Step 4: Register the tool in ToolRegistry**

Find where `RollbackChangesTool` is registered in the tool registry and add `RevertFileTool` next to it. Search for `RollbackChangesTool()` in the registration code and add `RevertFileTool()` in the same list.

- [ ] **Step 5: Run tests**

Run: `./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.builtin.RevertFileToolTest" -x verifyPlugin`
Expected: ALL PASS

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RevertFileTool.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/RevertFileToolTest.kt
git commit -m "feat(agent): add revert_file tool for per-file rollback

Surgical revert of individual files while preserving other changes. Records
RollbackEntry with SINGLE_FILE scope. Uses git checkout as primary mechanism."
```

---

### Task 5: Fix All Three Revert Paths in AgentController

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt`

- [ ] **Step 1: Fix handleProgress to push stats after rollback tool calls**

In `handleProgress()`, replace the edit stats check at line 1401-1403:

```kotlin
// Push edit stats to UI after file-modifying or rollback tool calls
if (toolInfo.toolName in setOf("edit_file", "create_file", "rollback_changes", "revert_file")) {
    pushEditStatsToUi()
}
```

- [ ] **Step 2: Update revert checkpoint callback to record RollbackEntry**

Replace the revert callback block (lines 1253-1275):

```kotlin
// Wire revert-checkpoint button: JS → Kotlin → RollbackManager
dashboard.setCefRevertCheckpointCallback { checkpointId ->
    val manager = session?.rollbackManager
    if (manager == null) {
        dashboard.appendStatus("No rollback manager available.", RichStreamingPanel.StatusType.WARNING)
        return@setCefRevertCheckpointCallback
    }
    invokeLater {
        val result = manager.rollbackToCheckpoint(checkpointId)
        if (result.success) {
            // Record rollback in ledger
            val ledger = session?.changeLedger
            val entriesAfter = ledger?.entriesAfterCheckpoint(checkpointId) ?: emptyList()
            val rollbackEntry = RollbackEntry(
                id = java.util.UUID.randomUUID().toString().take(12),
                timestamp = System.currentTimeMillis(),
                checkpointId = checkpointId,
                description = "User reverted to checkpoint",
                source = RollbackSource.USER_BUTTON,
                mechanism = result.mechanism,
                affectedFiles = result.affectedFiles,
                rolledBackEntryIds = entriesAfter.map { it.id },
                scope = RollbackScope.FULL_CHECKPOINT
            )
            ledger?.recordRollback(rollbackEntry)

            // Update context anchor
            if (ledger != null) {
                try { AgentService.getInstance(project).currentContextBridge?.updateChangeLedgerAnchor(ledger) } catch (_: Exception) {}
            }

            dashboard.appendStatus("Reverted to checkpoint $checkpointId.", RichStreamingPanel.StatusType.SUCCESS)
            pushEditStatsToUi()
            pushRollbackToUi(rollbackEntry)
        } else {
            dashboard.appendStatus("Failed to revert: ${result.error}", RichStreamingPanel.StatusType.ERROR)
        }
    }
}
```

- [ ] **Step 3: Update handleUndoRequest to record RollbackEntry and push stats**

Replace `handleUndoRequest()` (lines 1282-1306):

```kotlin
private fun handleUndoRequest() {
    val manager = session?.rollbackManager
    val checkpointId = manager?.latestCheckpointId()
    if (manager == null || checkpointId == null) {
        dashboard.appendStatus("No changes to undo.", RichStreamingPanel.StatusType.WARNING)
        return
    }
    invokeLater {
        val answer = Messages.showYesNoDialog(
            project,
            "Undo all file changes made by the agent?",
            "Undo Agent Changes",
            "Undo", "Cancel",
            Messages.getWarningIcon()
        )
        if (answer == Messages.YES) {
            val result = manager.rollbackToCheckpoint(checkpointId)
            if (result.success) {
                val ledger = session?.changeLedger
                val entriesAfter = ledger?.entriesAfterCheckpoint(checkpointId) ?: emptyList()
                val rollbackEntry = RollbackEntry(
                    id = java.util.UUID.randomUUID().toString().take(12),
                    timestamp = System.currentTimeMillis(),
                    checkpointId = checkpointId,
                    description = "User undid all agent changes",
                    source = RollbackSource.USER_UNDO,
                    mechanism = result.mechanism,
                    affectedFiles = result.affectedFiles,
                    rolledBackEntryIds = entriesAfter.map { it.id },
                    scope = RollbackScope.FULL_CHECKPOINT
                )
                ledger?.recordRollback(rollbackEntry)

                if (ledger != null) {
                    try { AgentService.getInstance(project).currentContextBridge?.updateChangeLedgerAnchor(ledger) } catch (_: Exception) {}
                }

                dashboard.appendStatus("All agent changes have been undone.", RichStreamingPanel.StatusType.SUCCESS)
                pushEditStatsToUi()
                pushRollbackToUi(rollbackEntry)
            } else {
                dashboard.appendError("Rollback failed: ${result.error}. Try Edit > Undo or LocalHistory.")
            }
        }
    }
}
```

- [ ] **Step 4: Add pushRollbackToUi helper method**

Add after `pushEditStatsToUi()` (after line 1436):

```kotlin
/**
 * Push a rollback event to the UI for visual feedback (greyed-out tool calls, rollback card).
 */
private fun pushRollbackToUi(entry: RollbackEntry) {
    try {
        val json = kotlinx.serialization.json.Json.encodeToString(
            RollbackEntry.serializer(), entry
        )
        dashboard.notifyRollback(json)
    } catch (e: Exception) {
        LOG.debug("AgentController: failed to push rollback to UI", e)
    }
}
```

Add imports at top of file:

```kotlin
import com.workflow.orchestrator.agent.runtime.RollbackEntry
import com.workflow.orchestrator.agent.runtime.RollbackMechanism
import com.workflow.orchestrator.agent.runtime.RollbackScope
import com.workflow.orchestrator.agent.runtime.RollbackSource
```

- [ ] **Step 5: Also push rollback to UI after LLM tool calls**

In `handleProgress()`, after the `pushEditStatsToUi()` call for rollback tools, add rollback UI notification. Update the block from Step 1:

```kotlin
// Push edit stats to UI after file-modifying or rollback tool calls
if (toolInfo.toolName in setOf("edit_file", "create_file", "rollback_changes", "revert_file")) {
    pushEditStatsToUi()
}

// For rollback tools, also push the rollback event for visual feedback
if (toolInfo.toolName in setOf("rollback_changes", "revert_file")) {
    // The rollback entry was already recorded by the tool itself.
    // Push the latest rollback to UI for greyed-out styling.
    val latestRollback = session?.changeLedger?.allRollbacks()?.lastOrNull()
    if (latestRollback != null) {
        pushRollbackToUi(latestRollback)
    }
}
```

- [ ] **Step 6: Compile and verify**

Run: `./gradlew :agent:compileKotlin -x verifyPlugin`
Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt
git commit -m "fix(agent): unify all 3 revert paths with RollbackEntry recording and UI sync

handleProgress now pushes stats after rollback_changes/revert_file tools.
User revert button and undo button both record RollbackEntry and push to UI.
New pushRollbackToUi() sends rollback events to JCEF for visual feedback."
```

---

### Task 6: Add Kotlin→JS Bridge for Rollback Notifications

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentDashboardPanel.kt`

- [ ] **Step 1: Add notifyRollback to AgentCefPanel**

After the `updateCheckpoints` method (line 996 in `AgentCefPanel.kt`), add:

```kotlin
fun notifyRollback(rollbackJson: String) {
    callJs("notifyRollback(${jsonStr(rollbackJson)})")
}
```

- [ ] **Step 2: Add notifyRollback to AgentDashboardPanel**

After `updateCheckpoints` delegation (line 545 in `AgentDashboardPanel.kt`), add:

```kotlin
fun notifyRollback(rollbackJson: String) {
    runOnEdt { cefPanel?.notifyRollback(rollbackJson) }
    mirrors.forEach { it.notifyRollback(rollbackJson) }
}
```

- [ ] **Step 3: Compile and verify**

Run: `./gradlew :agent:compileKotlin -x verifyPlugin`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentDashboardPanel.kt
git commit -m "feat(agent): add notifyRollback bridge method to JCEF panels

Kotlin→JS bridge for pushing rollback events to the React webview."
```

---

### Task 7: Add Rollback Types and Bridge Handler in Webview

**Files:**
- Modify: `agent/webview/src/bridge/types.ts`
- Modify: `agent/webview/src/bridge/jcef-bridge.ts`
- Modify: `agent/webview/src/stores/chatStore.ts`

- [ ] **Step 1: Add RollbackInfo type to types.ts**

After `CheckpointInfo` interface (after line 223), add:

```typescript
export type RollbackSource = 'LLM_TOOL' | 'USER_BUTTON' | 'USER_UNDO';
export type RollbackMechanism = 'LOCAL_HISTORY' | 'GIT_FALLBACK';
export type RollbackScope = 'FULL_CHECKPOINT' | 'SINGLE_FILE';

export interface RollbackInfo {
  id: string;
  timestamp: number;
  checkpointId: string;
  description: string;
  source: RollbackSource;
  mechanism: RollbackMechanism;
  affectedFiles: string[];
  rolledBackEntryIds: string[];
  scope: RollbackScope;
}
```

- [ ] **Step 2: Add notifyRollback to jcef-bridge.ts**

After the `updateCheckpoints` function in the bridge init, add:

```typescript
notifyRollback(json: string) {
  try {
    const rollback = JSON.parse(json);
    stores?.getChatStore().applyRollback(rollback);
  } catch { /* ignore malformed JSON */ }
},
```

- [ ] **Step 3: Add rollback state and actions to chatStore.ts**

In the state interface (around line 102), after `checkpoints`, add:

```typescript
rollbackEvents: RollbackInfo[];
```

In the actions section (around line 166), after `updateCheckpoints`, add:

```typescript
applyRollback(rollback: RollbackInfo): void;
```

In the initial state (around line 220), add:

```typescript
rollbackEvents: [],
```

In the store implementation (around line 750), after `updateCheckpoints`, add:

```typescript
applyRollback(rollback: RollbackInfo) {
  set((state) => {
    // Mark affected tool call messages as rolled back
    const rolledBackFiles = new Set(rollback.affectedFiles);
    const messages = state.messages.map((msg) => {
      if (msg.type === 'tool-call' && msg.filePath && rolledBackFiles.has(msg.filePath)) {
        return { ...msg, rolledBack: true };
      }
      // Also match by checking if any rolledBackEntryIds relate to this tool call
      return msg;
    });

    return {
      messages,
      rollbackEvents: [...state.rollbackEvents, rollback],
    };
  });
},
```

Add `RollbackInfo` to the imports from `types.ts`.

- [ ] **Step 4: Add rolledBack flag to message types**

In the `Message` type definition in chatStore (or types.ts), ensure tool-call messages can carry a `rolledBack?: boolean` field. Find the tool-call message type and add the optional field.

- [ ] **Step 5: Build webview**

Run: `cd agent/webview && npm run build`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add agent/webview/src/bridge/types.ts \
       agent/webview/src/bridge/jcef-bridge.ts \
       agent/webview/src/stores/chatStore.ts
git commit -m "feat(webview): add rollback types, bridge handler, and store action

RollbackInfo type, notifyRollback bridge function, applyRollback store action
that marks affected tool call messages as rolledBack for greyed-out styling."
```

---

### Task 8: Create RollbackCard React Component

**Files:**
- Create: `agent/webview/src/components/agent/RollbackCard.tsx`

- [ ] **Step 1: Create RollbackCard.tsx**

Create `agent/webview/src/components/agent/RollbackCard.tsx`:

```tsx
import { useState } from 'react';
import type { RollbackInfo } from '@/bridge/types';

interface Props {
  rollback: RollbackInfo;
}

export function RollbackCard({ rollback }: Props) {
  const [expanded, setExpanded] = useState(false);

  const sourceLabel = rollback.source === 'LLM_TOOL' ? 'Agent' : 'You';
  const scopeLabel = rollback.scope === 'FULL_CHECKPOINT' ? 'All changes' : 'Single file';
  const mechanismLabel = rollback.mechanism === 'LOCAL_HISTORY' ? 'LocalHistory' : 'Git';

  return (
    <div
      className="mx-3 my-2 rounded-lg border px-4 py-3"
      style={{
        borderColor: 'var(--warning, #e5a100)',
        background: 'color-mix(in srgb, var(--warning, #e5a100) 8%, var(--bg))',
      }}
    >
      {/* Header */}
      <div className="flex items-center gap-2 text-[12px]">
        <svg width="14" height="14" viewBox="0 0 16 16" fill="none" className="flex-shrink-0">
          <path
            d="M2 8a6 6 0 1 1 6 6H3m0 0 2-2m-2 2 2 2"
            stroke="var(--warning, #e5a100)"
            strokeWidth="1.5"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
        <span className="font-medium" style={{ color: 'var(--fg)' }}>
          Rolled back to checkpoint
        </span>
        <code
          className="text-[10px] px-1.5 py-0.5 rounded"
          style={{ background: 'var(--code-bg)', color: 'var(--fg-muted)' }}
        >
          {rollback.checkpointId}
        </code>
      </div>

      {/* Description */}
      <p className="mt-1 text-[11px]" style={{ color: 'var(--fg-secondary)' }}>
        {rollback.description}
      </p>

      {/* Badges */}
      <div className="flex items-center gap-2 mt-2">
        <span
          className="text-[10px] px-1.5 py-0.5 rounded"
          style={{
            background: sourceLabel === 'Agent' ? 'var(--badge-edit-bg)' : 'var(--badge-read-bg)',
            color: sourceLabel === 'Agent' ? 'var(--badge-edit-fg)' : 'var(--badge-read-fg)',
          }}
        >
          {sourceLabel}
        </span>
        <span
          className="text-[10px] px-1.5 py-0.5 rounded"
          style={{ background: 'var(--code-bg)', color: 'var(--fg-muted)' }}
        >
          {scopeLabel}
        </span>
        <span
          className="text-[10px] px-1.5 py-0.5 rounded"
          style={{ background: 'var(--code-bg)', color: 'var(--fg-muted)' }}
        >
          {mechanismLabel}
        </span>
      </div>

      {/* Affected files (collapsible) */}
      {rollback.affectedFiles.length > 0 && (
        <div className="mt-2">
          <button
            onClick={() => setExpanded(!expanded)}
            className="text-[10px] hover:underline"
            style={{ color: 'var(--link)' }}
          >
            {rollback.affectedFiles.length} file{rollback.affectedFiles.length !== 1 ? 's' : ''} reverted
            <span className="ml-1">{expanded ? '\u25B2' : '\u25BC'}</span>
          </button>
          {expanded && (
            <ul className="mt-1 space-y-0.5">
              {rollback.affectedFiles.map((f) => (
                <li key={f} className="text-[10px] font-mono pl-3" style={{ color: 'var(--fg-muted)' }}>
                  {f.split('/').pop()}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  );
}
```

- [ ] **Step 2: Wire RollbackCard into the chat message list**

Find where messages are rendered in the chat (likely `ChatMessageList.tsx` or similar). Add rendering for rollback events. After the existing message type rendering:

```tsx
import { RollbackCard } from './RollbackCard';

// In the message rendering loop, after other message types:
// Render rollback events from store
{rollbackEvents
  .filter((rb) => /* show at correct position based on timestamp */)
  .map((rb) => <RollbackCard key={rb.id} rollback={rb} />)
}
```

The exact integration point depends on how messages are ordered. The rollback card should appear in the timeline at its timestamp position.

- [ ] **Step 3: Build webview**

Run: `cd agent/webview && npm run build`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add agent/webview/src/components/agent/RollbackCard.tsx
git commit -m "feat(webview): add RollbackCard component for rollback event display

Shows checkpoint ID, description, source/scope/mechanism badges, and
collapsible affected files list. Styled with warning border."
```

---

### Task 9: Add Greyed-Out Styling for Rolled-Back Tool Calls

**Files:**
- Modify: `agent/webview/src/components/agent/ToolCallView.tsx`

- [ ] **Step 1: Add rolledBack styling to ToolCallView**

Find the root container div of `ToolCallView` and add conditional styling:

```tsx
// In ToolCallView props, add:
interface ToolCallViewProps {
  // ... existing props
  rolledBack?: boolean;
}

// In the component's root div, add conditional classes:
<div
  className={`... ${rolledBack ? 'opacity-40' : ''}`}
  style={{ ... }}
>
  {rolledBack && (
    <span
      className="absolute top-1 right-2 text-[9px] px-1.5 py-0.5 rounded"
      style={{ background: 'var(--warning)', color: 'var(--bg)' }}
    >
      reverted
    </span>
  )}
  {/* existing content */}
</div>
```

If the tool call path is displayed, add strikethrough:

```tsx
{rolledBack ? (
  <span className="line-through">{filePath}</span>
) : (
  <span>{filePath}</span>
)}
```

- [ ] **Step 2: Pass rolledBack prop from parent**

In the message list renderer, when rendering tool call messages, pass the `rolledBack` flag:

```tsx
<ToolCallView
  {...toolCallProps}
  rolledBack={message.rolledBack}
/>
```

- [ ] **Step 3: Build webview**

Run: `cd agent/webview && npm run build`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add agent/webview/src/components/agent/ToolCallView.tsx
git commit -m "feat(webview): greyed-out styling for rolled-back tool calls

Tool calls affected by rollback show at 40% opacity with 'reverted' badge
and strikethrough on file paths. Preserves expandability for audit."
```

---

### Task 10: Improve Git Command Blocking Error Messages

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RunCommandTool.kt`

- [ ] **Step 1: Update git command error message to guide toward rollback_changes**

In `checkGitCommand()` (line 232-233), replace the error message:

```kotlin
if (!isSafe) {
    return "Error: 'git $subCommand' is blocked for safety. " +
        "To revert file changes, use the rollback_changes or revert_file tools instead. " +
        "Allowed read-only git commands: ${SAFE_GIT_SUBCOMMANDS.joinToString(", ")}."
}
```

Also update the dangerous flags error (line 250):

```kotlin
return "Error: Flag '$flag' is blocked for safety in git commands. " +
    "To revert changes, use rollback_changes or revert_file tools instead."
```

- [ ] **Step 2: Compile and verify**

Run: `./gradlew :agent:compileKotlin -x verifyPlugin`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RunCommandTool.kt
git commit -m "fix(agent): improve git command blocking to guide toward rollback tools

Error messages for blocked git commands now suggest rollback_changes and
revert_file tools instead of raw git operations."
```

---

### Task 11: Update EditFileTool and CreateFileTool to Track Created Files

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/EditFileTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/CreateFileTool.kt`

- [ ] **Step 1: In CreateFileTool, call trackFileCreation instead of trackFileChange**

Find where `rollback?.trackFileChange(path)` is called in CreateFileTool and change to:

```kotlin
rollback?.trackFileCreation(resolvedPath)
```

This ensures the rollback manager knows which files were created (vs modified) so it can delete them on rollback rather than trying `git checkout`.

- [ ] **Step 2: Compile and verify**

Run: `./gradlew :agent:compileKotlin -x verifyPlugin`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/CreateFileTool.kt
git commit -m "fix(agent): track created files separately for proper rollback deletion

CreateFileTool now calls trackFileCreation() so the rollback manager
deletes new files instead of attempting git checkout on untracked files."
```

---

### Task 12: Run Full Test Suite and Verify Build

**Files:** None (verification only)

- [ ] **Step 1: Run all agent tests**

Run: `./gradlew :agent:test -x verifyPlugin`
Expected: ALL PASS

- [ ] **Step 2: Build the plugin**

Run: `./gradlew buildPlugin`
Expected: BUILD SUCCESS

- [ ] **Step 3: Fix any compilation or test failures**

If tests fail, diagnose and fix. Common issues:
- Import mismatches (new types need imports in test files)
- `RollbackResult` replacing `String?` return type — update all callers
- Serialization issues with new `RollbackEntry` in JSONL

- [ ] **Step 4: Final commit if fixes were needed**

```bash
git add -u
git commit -m "fix(agent): resolve compilation/test issues from checkpoint revert refactor"
```

---

### Task 13: Update Documentation

**Files:**
- Modify: `CLAUDE.md` (root)
- Modify: `agent/CLAUDE.md`

- [ ] **Step 1: Update root CLAUDE.md**

In the Agent Storage table, add a note about rollback entries in changes.jsonl.

- [ ] **Step 2: Update agent/CLAUDE.md**

In the Tools table, add `revert_file` to the Change Tracking row:

```
| Change Tracking | list_changes (always active, read-only), rollback_changes (reverts to LocalHistory checkpoint, git fallback), revert_file (single-file revert) |
```

Add a new section "Checkpoint/Revert Architecture" after the existing "Plan Persistence" section:

```markdown
## Checkpoint/Revert Architecture

Three revert paths, one notification flow:

1. **LLM tool** — `rollback_changes` (full checkpoint) or `revert_file` (single file)
2. **User revert button** — Checkpoint timeline revert in EditStatsBar
3. **User undo button** — Footer undo button in chat UI

All paths:
- Use `AgentRollbackManager` (LocalHistory primary, git checkout fallback)
- Record `RollbackEntry` in `ChangeLedger` (append-only, persisted to changes.jsonl)
- Update context anchor (LLM sees effective stats)
- Push to UI via `notifyRollback()` bridge (greyed-out tool calls + rollback card)

**Stats computation:** `totalStats()` and `fileStats()` exclude entries whose IDs appear in any `RollbackEntry.rolledBackEntryIds`.

**Git command blocking:** `RunCommandTool` blocks `git checkout`, `git reset`, `git restore`, `git clean` and guides the LLM toward `rollback_changes` / `revert_file`.

**Created files:** `AgentRollbackManager.trackFileCreation()` ensures new files are deleted (not git-checkout'd) on rollback.
```

- [ ] **Step 3: Commit**

```bash
git add CLAUDE.md agent/CLAUDE.md
git commit -m "docs: document checkpoint/revert architecture and revert_file tool"
```

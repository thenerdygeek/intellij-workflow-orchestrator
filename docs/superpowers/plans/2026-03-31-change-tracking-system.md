# Change Tracking System — Ledger, Checkpoints, Statistics, Bidirectional LLM Access

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an enterprise-grade change tracking system where every file edit is recorded in a persistent, queryable, compression-proof change ledger — visible to both the user (UI timeline + stats) and the LLM (context anchor + tools). Users can revert to any checkpoint. The LLM can reason about "what I changed so far" and self-revert.

**Architecture:** Three-layer design — Change Ledger (core data + persistence), LocalHistory integration (revert mechanism), UI surface (stats bar + checkpoint timeline). The ledger is the single source of truth. It feeds: (1) a compression-proof `<change_ledger>` context anchor for the LLM, (2) the UI via bridge functions, (3) `list_changes`/`rollback_changes` tools for LLM self-service.

**Tech Stack:** Kotlin, IntelliJ Platform SDK (LocalHistory, WriteCommandAction, VirtualFile), JSONL persistence, React + TypeScript (JCEF webview), Zustand

---

## Architecture Diagram

```
┌──────────────────────────────────────────────────────────┐
│                      UI Layer (React)                     │
│                                                           │
│  EditStatsBar          CheckpointTimeline     RevertBtn   │
│  "+45 -12 | 3 files"  ← expandable →         [Revert]    │
│                        Iter 1: edit PaymentService.kt     │
│                        Iter 2: create PaymentTest.kt      │
│                        Iter 3: edit build.gradle          │
└────────────────────────────┬─────────────────────────────┘
                             │ JCEF bridge (updateEditStats,
                             │ updateCheckpoints, revertCheckpoint)
┌────────────────────────────┴─────────────────────────────┐
│                  ChangeLedger (new core)                   │
│                                                           │
│  entries: List<ChangeEntry>  (append-only, in-memory)     │
│  checkpoints: Map<String, CheckpointMeta>                 │
│                                                           │
│  Persistence: sessions/{id}/changes.jsonl                 │
│  Anchor: <change_ledger> in ContextManager (survives all  │
│          compression — ~2-3K tokens at 50 entries)        │
│                                                           │
│  Tools: list_changes (LLM queries changes)                │
│         rollback_changes (LLM reverts to checkpoint)      │
│                                                           │
│  Feeds → AgentRollbackManager (LocalHistory labels)       │
│        → ContextManager.changeLedgerAnchor                │
│        → UI via AgentController bridge calls               │
│        → SessionCheckpoint (persisted to disk)            │
└──────────┬────────────────────────┬──────────────────────┘
           │                        │
┌──────────┴──────────┐  ┌─────────┴────────────────────┐
│   LocalHistory       │  │  FactsStore (existing)        │
│   (IntelliJ API)     │  │  EDIT_MADE facts now richer:  │
│                      │  │  "iter 3: +12/-3 Service.kt"  │
│   Per-edit labels    │  │  instead of "Replaced 45       │
│   Named: "Agent:     │  │  chars with 78 chars"          │
│   Iter 3 - edit      │  │                                │
│   PaymentService.kt" │  │  Still 200-char cap per fact.  │
│                      │  │  Survives as factsAnchor.      │
│   label.revert()     │  │                                │
│   for rollback       │  │  ChangeLedger is the detailed  │
│                      │  │  store; FactsStore is the      │
│                      │  │  compressed summary.            │
└─────────────────────┘  └────────────────────────────────┘
```

## Data Model

```kotlin
// The core record — one per file edit
@Serializable
data class ChangeEntry(
    val id: String,                    // UUID-12
    val sessionId: String,
    val iteration: Int,                // ReAct loop iteration number
    val timestamp: Long,               // epoch ms
    val filePath: String,              // canonical path
    val relativePath: String,          // project-relative for display
    val toolName: String,              // "edit_file", "create_file"
    val action: ChangeAction,          // CREATED, MODIFIED, DELETED

    // Change metrics (cheap to compute at edit time)
    val linesAdded: Int,
    val linesRemoved: Int,
    val linesBefore: Int,              // file size before edit
    val linesAfter: Int,               // file size after edit

    // Previews (capped for token budget — see COMPRESSION comments)
    val oldPreview: String,            // first 200 chars of old_string
    val newPreview: String,            // first 200 chars of new_string
    val editLineRange: String,         // "42-55" (startLine-endLine)

    // Checkpoint linkage
    val checkpointId: String,          // LocalHistory label ID for revert

    // Verification status (updated by SelfCorrectionGate)
    val verified: Boolean = false,
    val verificationError: String? = null
)

enum class ChangeAction { CREATED, MODIFIED, DELETED }

// Checkpoint metadata — groups related changes
@Serializable
data class CheckpointMeta(
    val id: String,                    // same ID as in AgentRollbackManager
    val description: String,           // "Iteration 3: edit PaymentService.kt"
    val iteration: Int,
    val timestamp: Long,
    val filesModified: List<String>,   // relative paths
    val totalLinesAdded: Int,
    val totalLinesRemoved: Int
)
```

## Compression Strategy

```
// COMPRESSION NOTES (referenced throughout implementation):
//
// The change ledger interacts with context compression at three levels:
//
// 1. ANCHOR LEVEL: changeLedgerAnchor is a compression-proof system message
//    in ContextManager. It survives Phase 1 (tiered pruning) and Phase 2
//    (LLM summarization). Format is a compact table:
//
//    <change_ledger>
//    Iter | File                  | Action   | +/-    | Verified
//    3    | PaymentService.kt     | MODIFIED | +12/-3 | ✓
//    3    | PaymentServiceTest.kt | CREATED  | +45/-0 | ✓
//    5    | build.gradle.kts      | MODIFIED | +2/-1  | ✗ (syntax error)
//    ---
//    Totals: 3 files, +59/-4 lines, 2 checkpoints
//    Latest checkpoint: cp-a1b2c3 (Iteration 5)
//    </change_ledger>
//
//    Budget: ~50-100 tokens for 5 files, ~200-400 tokens for 20 files.
//    Cap at 50 entries (oldest dropped) to stay under 5% of context window.
//
// 2. FACTS LEVEL: FactsStore EDIT_MADE facts are enriched with "+X/-Y"
//    summaries from the ledger. These survive as factsAnchor separately.
//    The changeLedgerAnchor and factsAnchor are complementary:
//    - factsAnchor: per-file summaries (what was done)
//    - changeLedgerAnchor: structured table (cumulative impact)
//
// 3. TOOL RESULT LEVEL: Individual edit_file/create_file tool results
//    are subject to normal tiered pruning (FULL → COMPRESSED → METADATA).
//    The ledger anchor ensures the LLM remembers edits even after tool
//    results are pruned. The LLM can call list_changes to get full details.
//
// 4. DISK LEVEL: changes.jsonl is append-only and never pruned.
//    Full change history survives IDE restarts and context rotations.
//    Referenced by list_changes tool for historical queries.
```

---

### Task 1: ChangeLedger Core — Data Model + Persistence

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ChangeLedger.kt`

- [ ] **Step 1.1: Create ChangeLedger.kt with data model and persistence**

```kotlin
package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Append-only ledger of every file change made by the agent in a session.
 * Single source of truth for edit tracking — feeds UI, LLM context anchor,
 * and checkpoint metadata.
 *
 * COMPRESSION: The ledger itself is NOT in the LLM context window.
 * Instead, it renders a compact summary into changeLedgerAnchor
 * (via toContextString()) which IS compression-proof. The full
 * ledger is persisted to changes.jsonl for historical queries
 * via the list_changes tool.
 */
class ChangeLedger(private val sessionDir: File? = null) {

    companion object {
        private val LOG = Logger.getInstance(ChangeLedger::class.java)
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

        /**
         * COMPRESSION: Max entries rendered in the context anchor.
         * Beyond this, oldest entries are dropped from the anchor
         * (but remain in changes.jsonl on disk for list_changes queries).
         * At ~50-100 tokens per entry, 50 entries = ~2.5-5K tokens.
         * This is ~2.5% of a 190K context window — acceptable overhead
         * for complete change awareness.
         */
        const val MAX_ANCHOR_ENTRIES = 50

        /** Max chars for old/new string previews in ChangeEntry.
         *  COMPRESSION: Previews are rendered in the anchor table.
         *  200 chars ≈ 50 tokens per preview. Keeping short preserves
         *  token budget while giving the LLM enough context to
         *  understand what changed without re-reading the file. */
        const val MAX_PREVIEW_CHARS = 200
    }

    private val entries = CopyOnWriteArrayList<ChangeEntry>()
    private val checkpoints = mutableMapOf<String, CheckpointMeta>()
    private var changesFile: File? = null

    fun initialize(sessionDirectory: File?) {
        changesFile = sessionDirectory?.let { File(it, "changes.jsonl") }
    }

    /**
     * Record a file change. Called by EditFileTool/CreateFileTool after
     * a successful write.
     */
    fun recordChange(entry: ChangeEntry) {
        entries.add(entry)
        // Persist immediately (append-only JSONL)
        try {
            changesFile?.appendText(json.encodeToString(entry) + "\n")
        } catch (e: Exception) {
            LOG.warn("ChangeLedger: failed to persist change entry", e)
        }
    }

    /**
     * Record a checkpoint that groups related changes.
     */
    fun recordCheckpoint(meta: CheckpointMeta) {
        checkpoints[meta.id] = meta
    }

    /** Get all changes for a specific file. */
    fun changesForFile(filePath: String): List<ChangeEntry> =
        entries.filter { it.filePath == filePath || it.relativePath == filePath }

    /** Get all changes for a specific iteration. */
    fun changesForIteration(iteration: Int): List<ChangeEntry> =
        entries.filter { it.iteration == iteration }

    /** Get cumulative stats. */
    fun totalStats(): EditStats {
        val added = entries.sumOf { it.linesAdded }
        val removed = entries.sumOf { it.linesRemoved }
        val files = entries.map { it.filePath }.distinct().size
        return EditStats(added, removed, files)
    }

    /** Get per-file stats. */
    fun fileStats(): Map<String, FileEditSummary> {
        return entries.groupBy { it.relativePath }.mapValues { (path, edits) ->
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

    /** Mark a file's latest change as verified (called by SelfCorrectionGate). */
    fun markVerified(filePath: String, passed: Boolean, error: String? = null) {
        val latest = entries.lastOrNull { it.filePath == filePath } ?: return
        val idx = entries.indexOf(latest)
        if (idx >= 0) {
            entries[idx] = latest.copy(verified = passed, verificationError = error)
        }
    }

    /** Get all checkpoints ordered by iteration. */
    fun listCheckpoints(): List<CheckpointMeta> =
        checkpoints.values.sortedBy { it.iteration }

    /** Get all entries (for UI). */
    fun allEntries(): List<ChangeEntry> = entries.toList()

    /**
     * Render a compact context string for the LLM.
     *
     * COMPRESSION: This string becomes the changeLedgerAnchor in
     * ContextManager — a compression-proof system message. It must be:
     * 1. Compact (token-efficient) — table format, no prose
     * 2. Complete (shows all files, stats, checkpoint IDs)
     * 3. Actionable (LLM can use checkpoint IDs in rollback_changes)
     *
     * Token budget: ~50-100 tokens per file entry. At MAX_ANCHOR_ENTRIES=50,
     * worst case ~5K tokens. Typical session (5-10 files): ~500-1K tokens.
     */
    fun toContextString(): String {
        if (entries.isEmpty()) return ""

        val stats = fileStats()
        val totalStats = totalStats()
        val recentCheckpoint = checkpoints.values.maxByOrNull { it.iteration }

        return buildString {
            appendLine("Changes made in this session:")
            appendLine()

            // Per-file summary (deduplicated, shows cumulative stats)
            // COMPRESSION: Only show latest MAX_ANCHOR_ENTRIES files.
            // Oldest are dropped from anchor but remain on disk.
            val displayStats = if (stats.size > MAX_ANCHOR_ENTRIES) {
                appendLine("[Showing ${MAX_ANCHOR_ENTRIES} most recent of ${stats.size} files]")
                stats.entries.sortedByDescending { it.value.lastIteration }.take(MAX_ANCHOR_ENTRIES)
                    .associate { it.key to it.value }
            } else stats

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

            appendLine()
            appendLine("Totals: ${totalStats.filesModified} files, +${totalStats.totalLinesAdded}/-${totalStats.totalLinesRemoved} lines")

            if (recentCheckpoint != null) {
                appendLine("Latest checkpoint: ${recentCheckpoint.id} (Iteration ${recentCheckpoint.iteration})")
                appendLine("Use rollback_changes(checkpoint_id=\"${recentCheckpoint.id}\") to revert.")
            }

            val allCheckpointIds = checkpoints.values.sortedBy { it.iteration }
            if (allCheckpointIds.size > 1) {
                appendLine("All checkpoints: ${allCheckpointIds.joinToString(", ") { "${it.id} (iter ${it.iteration})" }}")
            }
        }.trimEnd()
    }

    /**
     * Load ledger from disk (for session resume).
     */
    fun loadFromDisk() {
        val file = changesFile ?: return
        if (!file.exists()) return
        try {
            file.readLines().forEach { line ->
                if (line.isNotBlank()) {
                    try {
                        entries.add(json.decodeFromString<ChangeEntry>(line))
                    } catch (_: Exception) { /* skip malformed lines */ }
                }
            }
            LOG.info("ChangeLedger: loaded ${entries.size} entries from disk")
        } catch (e: Exception) {
            LOG.warn("ChangeLedger: failed to load from disk", e)
        }
    }
}

@Serializable
data class ChangeEntry(
    val id: String,
    val sessionId: String,
    val iteration: Int,
    val timestamp: Long,
    val filePath: String,
    val relativePath: String,
    val toolName: String,
    val action: ChangeAction,
    val linesAdded: Int,
    val linesRemoved: Int,
    val linesBefore: Int,
    val linesAfter: Int,
    /** COMPRESSION: Capped at MAX_PREVIEW_CHARS to control anchor token cost. */
    val oldPreview: String,
    /** COMPRESSION: Capped at MAX_PREVIEW_CHARS to control anchor token cost. */
    val newPreview: String,
    val editLineRange: String,
    val checkpointId: String,
    val verified: Boolean = false,
    val verificationError: String? = null
)

@Serializable
enum class ChangeAction { CREATED, MODIFIED, DELETED }

@Serializable
data class CheckpointMeta(
    val id: String,
    val description: String,
    val iteration: Int,
    val timestamp: Long,
    val filesModified: List<String>,
    val totalLinesAdded: Int,
    val totalLinesRemoved: Int
)

data class EditStats(
    val totalLinesAdded: Int,
    val totalLinesRemoved: Int,
    val filesModified: Int
)

data class FileEditSummary(
    val path: String,
    val editCount: Int,
    val totalLinesAdded: Int,
    val totalLinesRemoved: Int,
    val lastIteration: Int,
    val verified: Boolean,
    val action: ChangeAction
)
```

- [ ] **Step 1.2: Compile and verify**

```bash
./gradlew :agent:compileKotlin
```

- [ ] **Step 1.3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ChangeLedger.kt
git commit -m "feat(agent): add ChangeLedger — persistent, queryable edit tracking core"
```

---

### Task 2: Wire ChangeLedger into ConversationSession + ContextManager

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ConversationSession.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt`

- [ ] **Step 2.1: Add ChangeLedger to ConversationSession**

In `ConversationSession`, alongside `rollbackManager` and `planManager`:
```kotlin
/** Change ledger tracking every file edit. Persists to changes.jsonl.
 *  COMPRESSION: Renders to changeLedgerAnchor in ContextManager —
 *  compression-proof summary of all changes visible to the LLM. */
val changeLedger: ChangeLedger = ChangeLedger()
```

In the session initialization (where `sessionDir` is set), add:
```kotlin
changeLedger.initialize(store.sessionDirectory)
```

In session resume/load path:
```kotlin
changeLedger.initialize(store.sessionDirectory)
changeLedger.loadFromDisk()
```

- [ ] **Step 2.2: Add changeLedgerAnchor to ContextManager**

Add a new anchor field alongside `factsAnchor`:
```kotlin
/**
 * COMPRESSION: Compression-proof anchor containing the change ledger summary.
 * Survives Phase 1 (tiered pruning) and Phase 2 (LLM summarization).
 * Updated after every edit_file/create_file call via updateChangeLedgerAnchor().
 * Token cost: ~500-1K for typical sessions (5-10 files), max ~5K at 50 files.
 */
private var changeLedgerAnchor: ChatMessage? = null
```

Add update method:
```kotlin
fun updateChangeLedgerAnchor(changeLedger: ChangeLedger) {
    val contextStr = changeLedger.toContextString()
    // COMPRESSION: Only create anchor if there are changes to report.
    // Empty anchor wastes no tokens. Non-empty anchor is compression-proof.
    changeLedgerAnchor = if (contextStr.isNotEmpty()) {
        ChatMessage(role = "system", content = "<change_ledger>\n$contextStr\n</change_ledger>")
    } else null
    totalTokens = TokenEstimator.estimate(getMessages())
}
```

In `getMessages()`, include `changeLedgerAnchor` alongside other anchors. Place it AFTER `factsAnchor` in the message list (recency zone — higher recall for recent changes).

- [ ] **Step 2.3: Compile and test**

```bash
./gradlew :agent:compileKotlin
./gradlew :agent:test --rerun --no-build-cache
```

- [ ] **Step 2.4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ConversationSession.kt \
  agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt
git commit -m "feat(agent): wire ChangeLedger into ConversationSession + ContextManager anchor"
```

---

### Task 3: CreateFileTool + EditFileTool → Record Changes in Ledger

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/CreateFileTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/EditFileTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/DynamicToolSelector.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ApprovalGate.kt`

- [ ] **Step 3.1: Create CreateFileTool.kt**

Same as Task 1 from previous plan, but with ChangeLedger recording. After successful file creation:

```kotlin
// Record in change ledger (accessed via project service)
try {
    val agentService = AgentService.getInstance(project)
    val session = agentService.currentSession
    val ledger = session?.changeLedger
    val rollback = session?.rollbackManager

    // Create LocalHistory checkpoint for this edit
    val checkpointId = rollback?.createCheckpoint("Create $rawPath") ?: ""

    val lineCount = content.lines().size
    ledger?.recordChange(ChangeEntry(
        id = java.util.UUID.randomUUID().toString().take(12),
        sessionId = session?.sessionId ?: "",
        iteration = agentService.currentIteration ?: 0,
        timestamp = System.currentTimeMillis(),
        filePath = resolvedPath,
        relativePath = rawPath,
        toolName = "create_file",
        action = ChangeAction.CREATED,
        linesAdded = lineCount,
        linesRemoved = 0,
        linesBefore = 0,
        linesAfter = lineCount,
        // COMPRESSION: Cap previews to control anchor token budget
        oldPreview = "",
        newPreview = content.take(ChangeLedger.MAX_PREVIEW_CHARS),
        editLineRange = "1-$lineCount",
        checkpointId = checkpointId
    ))
} catch (_: Exception) { /* ledger recording is best-effort */ }
```

- [ ] **Step 3.2: Modify EditFileTool to record in ChangeLedger**

After the successful write block (before building the return ToolResult), add ledger recording:

```kotlin
// Record in change ledger
// COMPRESSION: The ledger entry captures structured change data that
// survives in the changeLedgerAnchor even after the edit_file tool
// result is pruned from context during Phase 1 tiered pruning.
try {
    val agentService = AgentService.getInstance(project)
    val session = agentService.currentSession
    val ledger = session?.changeLedger
    val rollback = session?.rollbackManager

    val checkpointId = rollback?.createCheckpoint("Edit $rawPath") ?: ""

    val oldLines = content.lines().size
    val newLines = newContent.lines().size
    val editStart = content.indexOf(oldString)
    val startLine = if (editStart >= 0) content.substring(0, editStart).count { it == '\n' } + 1 else 0
    val endLine = startLine + newString.lines().size - 1

    ledger?.recordChange(ChangeEntry(
        id = java.util.UUID.randomUUID().toString().take(12),
        sessionId = session?.sessionId ?: "",
        iteration = agentService.currentIteration ?: 0,
        timestamp = System.currentTimeMillis(),
        filePath = resolvedPath,
        relativePath = rawPath,
        toolName = "edit_file",
        action = ChangeAction.MODIFIED,
        linesAdded = (newLines - oldLines).coerceAtLeast(0),
        linesRemoved = (oldLines - newLines).coerceAtLeast(0),
        linesBefore = oldLines,
        linesAfter = newLines,
        // COMPRESSION: Previews capped at 200 chars for anchor token budget
        oldPreview = oldString.take(ChangeLedger.MAX_PREVIEW_CHARS),
        newPreview = newString.take(ChangeLedger.MAX_PREVIEW_CHARS),
        editLineRange = "$startLine-$endLine",
        checkpointId = checkpointId
    ))

    // Update context anchor so LLM sees the change immediately
    session?.let { s ->
        s.contextManager?.updateChangeLedgerAnchor(s.changeLedger)
    }
} catch (_: Exception) { /* ledger recording is best-effort */ }
```

- [ ] **Step 3.3: Add VFS refresh after writes in EditFileTool**

After `writeViaVfs` (line ~239):
```kotlin
// COMPRESSION: Not related to compression, but ensures IDE diagnostics
// see changes immediately. Without this, VFS watcher delay (1-2s on macOS)
// can cause stale diagnostics in the SelfCorrectionGate verify step.
try { vFile?.refresh(false, false) } catch (_: Exception) { }
```

After `writeViaFileIo` (line ~251):
```kotlin
try { LocalFileSystem.getInstance().refreshAndFindFileByPath(file.absolutePath) } catch (_: Exception) { }
```

- [ ] **Step 3.4: Add diff context in EditFileTool response**

After successful edit, include 3 lines of context around the change:
```kotlin
val contextLines = try {
    val newFileContent = readFileContent(vFile, file)
    val allNewLines = newFileContent.lines()
    val contextStart = (startLine - 4).coerceAtLeast(0)  // 3 lines before
    val contextEnd = (endLine + 3).coerceAtMost(allNewLines.size)  // 3 lines after
    allNewLines.subList(contextStart, contextEnd).mapIndexed { idx, line ->
        "${contextStart + idx + 1}\t$line"
    }.joinToString("\n")
} catch (_: Exception) { null }

val contextSection = if (contextLines != null) "\n\nContext after edit:\n$contextLines" else ""
```

Include `contextSection` in the ToolResult content.

- [ ] **Step 3.5: Register CreateFileTool and update selectors**

In AgentService.kt:
```kotlin
register(CreateFileTool())
```

In DynamicToolSelector ALWAYS_INCLUDE:
```kotlin
"create_file"
```

In ApprovalGate MEDIUM_RISK_TOOLS:
```kotlin
"create_file"
```

With context-aware override in classifyRisk:
```kotlin
if (toolName == "create_file") {
    val path = params["path"] as? String ?: return RiskLevel.MEDIUM
    return when {
        path.contains("/test/") || path.endsWith("Test.kt") || path.endsWith("Test.java") -> RiskLevel.LOW
        path.endsWith(".md") || path.endsWith(".txt") -> RiskLevel.LOW
        else -> RiskLevel.MEDIUM
    }
}
```

- [ ] **Step 3.6: Compile and test**

```bash
./gradlew :agent:compileKotlin
./gradlew :agent:test --rerun --no-build-cache
```

- [ ] **Step 3.7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/CreateFileTool.kt \
  agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/EditFileTool.kt \
  agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt \
  agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/DynamicToolSelector.kt \
  agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ApprovalGate.kt
git commit -m "feat(agent): CreateFileTool + EditFileTool records changes in ChangeLedger"
```

---

### Task 4: Encoding Fallback in ReadFileTool

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/ReadFileTool.kt`

- [ ] **Step 4.1: Add encoding fallback chain**

Replace `file.readLines(Charsets.UTF_8)` in both fallback locations with:

```kotlin
/**
 * Read file with encoding fallback chain.
 * Priority: VirtualFile.charset (IDE-detected) > UTF-8 > ISO-8859-1 (Latin-1).
 *
 * COMPRESSION: Not directly related to compression, but encoding errors
 * produce garbled text that wastes context tokens. Correct encoding
 * ensures tool output is meaningful and token-efficient.
 */
private fun readLinesWithFallback(file: java.io.File): List<String> {
    // Try UTF-8 first
    try {
        val text = file.readText(Charsets.UTF_8)
        // Check for Unicode replacement character (garbled encoding)
        if (!text.contains('\uFFFD')) return text.lines()
    } catch (_: Exception) { }

    // Fallback: ISO-8859-1 (Latin-1) — lossless for all byte values
    try {
        return file.readText(Charsets.ISO_8859_1).lines()
    } catch (_: Exception) { }

    // Final fallback: UTF-8 ignoring errors
    return file.readText(Charsets.UTF_8).lines()
}
```

- [ ] **Step 4.2: Compile and test**

```bash
./gradlew :agent:compileKotlin
./gradlew :agent:test --rerun --no-build-cache
```

- [ ] **Step 4.3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/ReadFileTool.kt
git commit -m "fix(agent): add encoding fallback chain in ReadFileTool (UTF-8 → Latin-1)"
```

---

### Task 5: Per-Edit Checkpoints in SingleAgentSession

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/AgentRollbackManager.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/AgentOrchestrator.kt`

- [ ] **Step 5.1: Enhance AgentRollbackManager with checkpoint metadata**

```kotlin
fun createCheckpoint(description: String, iteration: Int = 0, filesModified: List<String> = emptyList()): String {
    val id = UUID.randomUUID().toString().take(12)
    try {
        val label = LocalHistory.getInstance().putSystemLabel(
            project, "Agent: $description"
        )
        checkpoints[id] = label
        LOG.info("AgentRollbackManager: created checkpoint $id (iter $iteration): $description")
    } catch (e: Exception) {
        LOG.warn("AgentRollbackManager: failed to create checkpoint", e)
    }
    return id
}
```

- [ ] **Step 5.2: Wire checkpoint + ledger updates after tool execution in SingleAgentSession**

After each tool execution that produces artifacts, create checkpoint and record in ledger:

```kotlin
// After tool result processing, if files were modified:
if (toolResult.artifacts.isNotEmpty()) {
    val ledger = session?.changeLedger
    val rollback = session?.rollbackManager

    // Create checkpoint grouping this iteration's changes
    val checkpointId = rollback?.createCheckpoint(
        "Iteration $iteration: $toolName",
        iteration,
        toolResult.artifacts
    ) ?: ""

    // Record checkpoint metadata in ledger
    val iterChanges = ledger?.changesForIteration(iteration) ?: emptyList()
    ledger?.recordCheckpoint(CheckpointMeta(
        id = checkpointId,
        description = "Iteration $iteration: $toolName",
        iteration = iteration,
        timestamp = System.currentTimeMillis(),
        filesModified = toolResult.artifacts.map { it.substringAfterLast('/') },
        totalLinesAdded = iterChanges.sumOf { it.linesAdded },
        totalLinesRemoved = iterChanges.sumOf { it.linesRemoved }
    ))

    // Update the context anchor so LLM sees cumulative changes
    // COMPRESSION: This update keeps the changeLedgerAnchor current.
    // The anchor is re-rendered from the full ledger each time,
    // ensuring it always reflects the latest state.
    session?.let { s ->
        contextManager.updateChangeLedgerAnchor(s.changeLedger)
    }

    // Track for rollback manager
    toolResult.artifacts.forEach { rollback?.trackFileChange(it) }
}
```

- [ ] **Step 5.3: Expose currentIteration from SingleAgentSession**

Add to AgentService (so tools can read the current iteration):
```kotlin
var currentIteration: Int? = null
```

Set it in SingleAgentSession's loop:
```kotlin
agentService.currentIteration = iteration
```

- [ ] **Step 5.4: Compile and test**

```bash
./gradlew :agent:compileKotlin
./gradlew :agent:test --rerun --no-build-cache
```

- [ ] **Step 5.5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt \
  agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/AgentRollbackManager.kt \
  agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/AgentOrchestrator.kt \
  agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt
git commit -m "feat(agent): per-edit checkpoints + ChangeLedger integration in ReAct loop"
```

---

### Task 6: LLM Tools — list_changes + rollback_changes

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/ListChangesTool.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RollbackChangesTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/DynamicToolSelector.kt`

- [ ] **Step 6.1: Create ListChangesTool**

```kotlin
class ListChangesTool : AgentTool {
    override val name = "list_changes"
    override val description = "List all file changes made in this session. " +
        "Shows per-file line counts (+added/-removed), iteration numbers, " +
        "verification status, and checkpoint IDs for rollback. " +
        "Use to understand what you've changed so far before completing a task."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "file" to ParameterProperty(type = "string",
                description = "Optional: filter to changes for a specific file path"),
            "iteration" to ParameterProperty(type = "integer",
                description = "Optional: filter to changes from a specific iteration")
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val agentService = AgentService.getInstance(project)
        val ledger = agentService.currentSession?.changeLedger
            ?: return ToolResult("No active session", "No session", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val filePath = params["file"]?.jsonPrimitive?.content
        val iteration = params["iteration"]?.jsonPrimitive?.intOrNull

        val entries = when {
            filePath != null -> ledger.changesForFile(filePath)
            iteration != null -> ledger.changesForIteration(iteration)
            else -> ledger.allEntries()
        }

        if (entries.isEmpty()) {
            return ToolResult("No changes recorded yet.", "No changes", 5)
        }

        // COMPRESSION: This tool result will be subject to normal tiered pruning.
        // But the changeLedgerAnchor always has a summary. This tool provides
        // the FULL detail (per-edit previews) that the anchor omits.
        val content = buildString {
            appendLine("Changes (${entries.size} edits across ${entries.map { it.relativePath }.distinct().size} files):")
            appendLine()
            entries.forEach { e ->
                val verified = if (e.verified) " ✓" else if (e.verificationError != null) " ✗" else ""
                appendLine("[Iter ${e.iteration}] ${e.action} ${e.relativePath} +${e.linesAdded}/-${e.linesRemoved} (lines ${e.editLineRange})$verified")
                if (e.oldPreview.isNotBlank()) appendLine("  - Old: ${e.oldPreview.take(100)}")
                if (e.newPreview.isNotBlank()) appendLine("  + New: ${e.newPreview.take(100)}")
                appendLine("  Checkpoint: ${e.checkpointId}")
            }
            appendLine()
            val stats = ledger.totalStats()
            appendLine("Totals: +${stats.totalLinesAdded}/-${stats.totalLinesRemoved} lines, ${stats.filesModified} files")
        }

        return ToolResult(content, "${entries.size} changes listed", TokenEstimator.estimate(content))
    }
}
```

- [ ] **Step 6.2: Create RollbackChangesTool**

```kotlin
class RollbackChangesTool : AgentTool {
    override val name = "rollback_changes"
    override val description = "Revert all file changes back to a specific checkpoint. " +
        "Use list_changes to see available checkpoint IDs. " +
        "This uses IntelliJ's LocalHistory — reverts ALL files to checkpoint state."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "checkpoint_id" to ParameterProperty(type = "string",
                description = "Checkpoint ID to revert to (from list_changes output)"),
            "description" to ParameterProperty(type = "string",
                description = "Why you're reverting (shown to user in approval dialog)")
        ),
        required = listOf("checkpoint_id", "description")
    )
    // HIGH risk — reverts files
    override val allowedWorkers = setOf(WorkerType.CODER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val checkpointId = params["checkpoint_id"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: checkpoint_id required", "Missing param", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val agentService = AgentService.getInstance(project)
        val rollback = agentService.currentSession?.rollbackManager
            ?: return ToolResult("No rollback manager", "No session", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val success = rollback.rollbackToCheckpoint(checkpointId)
        return if (success) {
            ToolResult(
                "Reverted to checkpoint $checkpointId. All files restored to that state.",
                "Rolled back to $checkpointId",
                10
            )
        } else {
            ToolResult(
                "Rollback failed. Checkpoint $checkpointId may not exist or LocalHistory may have expired.",
                "Rollback failed",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }
}
```

- [ ] **Step 6.3: Register tools and update selectors**

AgentService.kt:
```kotlin
register(ListChangesTool())
register(RollbackChangesTool())
```

DynamicToolSelector ALWAYS_INCLUDE — add `"list_changes"`. Don't add `rollback_changes` (it's triggered by keyword "revert", "rollback", "undo").

ApprovalGate:
- `list_changes` → NONE_RISK_TOOLS (read-only)
- `rollback_changes` → HIGH (always shows dialog)

- [ ] **Step 6.4: Compile and test**

```bash
./gradlew :agent:compileKotlin
./gradlew :agent:test --rerun --no-build-cache
```

- [ ] **Step 6.5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/ListChangesTool.kt \
  agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RollbackChangesTool.kt \
  agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt \
  agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/DynamicToolSelector.kt \
  agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ApprovalGate.kt
git commit -m "feat(agent): add list_changes + rollback_changes tools for LLM self-service"
```

---

### Task 7: Enrich FactsStore with Change Data

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt`

- [ ] **Step 7.1: Enrich EDIT_MADE facts with line stats from ledger**

In `recordFactFromToolResult`, when toolName is `edit_file` or `create_file`:

```kotlin
// Instead of: factsStore.record(Fact(FactType.EDIT_MADE, filePath, summary.take(200), iteration))
// Use enriched summary from change ledger:
val ledger = session?.changeLedger
val latestChange = ledger?.changesForFile(filePath)?.lastOrNull()
val enrichedSummary = if (latestChange != null) {
    // COMPRESSION: This enriched summary survives in factsAnchor.
    // Format: "iter 3: +12/-3 lines. <original summary>"
    // The "+/-" prefix gives the LLM line-level awareness even after
    // the full tool result is pruned from context.
    "iter ${latestChange.iteration}: +${latestChange.linesAdded}/-${latestChange.linesRemoved} lines. ${summary.take(150)}"
} else {
    summary.take(200)
}
factsStore.record(Fact(FactType.EDIT_MADE, filePath, enrichedSummary, iteration))
```

- [ ] **Step 7.2: Compile and test**

```bash
./gradlew :agent:compileKotlin
./gradlew :agent:test --rerun --no-build-cache
```

- [ ] **Step 7.3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt
git commit -m "feat(agent): enrich EDIT_MADE facts with +/-line stats from ChangeLedger"
```

---

### Task 8: UI — Edit Statistics Bar + Checkpoint Timeline

**Files:**
- Create: `agent/webview/src/components/agent/EditStatsBar.tsx`
- Create: `agent/webview/src/components/agent/CheckpointTimeline.tsx`
- Modify: `agent/webview/src/bridge/types.ts`
- Modify: `agent/webview/src/bridge/jcef-bridge.ts`
- Modify: `agent/webview/src/stores/chatStore.ts`
- Modify: `agent/webview/src/components/chat/ChatView.tsx`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt`

- [ ] **Step 8.1: Add types to bridge/types.ts**

```typescript
export interface EditStats {
  totalLinesAdded: number;
  totalLinesRemoved: number;
  filesModified: number;
}

export interface CheckpointInfo {
  id: string;
  description: string;
  timestamp: number;
  iteration: number;
  filesModified: string[];
  totalLinesAdded: number;
  totalLinesRemoved: number;
}
```

- [ ] **Step 8.2: Add bridge functions in jcef-bridge.ts**

```typescript
window.updateEditStats = (added: number, removed: number, files: number) => {
  useChatStore.getState().updateEditStats({ totalLinesAdded: added, totalLinesRemoved: removed, filesModified: files });
};

window.updateCheckpoints = (json: string) => {
  try {
    const checkpoints = JSON.parse(json) as CheckpointInfo[];
    useChatStore.getState().updateCheckpoints(checkpoints);
  } catch {}
};
```

- [ ] **Step 8.3: Add store actions in chatStore.ts**

```typescript
// State:
editStats: EditStats | null;
checkpoints: CheckpointInfo[];

// Actions:
updateEditStats(stats: EditStats): void;
updateCheckpoints(checkpoints: CheckpointInfo[]): void;
```

- [ ] **Step 8.4: Create EditStatsBar.tsx**

Compact bar showing `+45 -12 | 3 files | 4 checkpoints`:

```tsx
import { useState } from 'react';
import type { EditStats, CheckpointInfo } from '@/bridge/types';
import { CheckpointTimeline } from './CheckpointTimeline';

interface EditStatsBarProps {
  stats: EditStats | null;
  checkpoints: CheckpointInfo[];
}

export function EditStatsBar({ stats, checkpoints }: EditStatsBarProps) {
  const [showCheckpoints, setShowCheckpoints] = useState(false);

  if (!stats || (stats.totalLinesAdded === 0 && stats.totalLinesRemoved === 0)) return null;

  return (
    <div>
      <div
        className="flex items-center gap-3 px-4 py-1.5 text-[11px] font-mono border-t"
        style={{ borderColor: 'var(--border)', background: 'var(--toolbar-bg)' }}
      >
        <span style={{ color: 'var(--diff-add-fg, #b5cea8)' }}>+{stats.totalLinesAdded}</span>
        <span style={{ color: 'var(--diff-rem-fg, #f4a5a5)' }}>-{stats.totalLinesRemoved}</span>
        <span className="w-px h-3" style={{ background: 'var(--border)' }} />
        <span style={{ color: 'var(--fg-muted)' }}>{stats.filesModified} file{stats.filesModified !== 1 ? 's' : ''}</span>
        {checkpoints.length > 0 && (
          <>
            <span className="w-px h-3" style={{ background: 'var(--border)' }} />
            <button
              onClick={() => setShowCheckpoints(!showCheckpoints)}
              className="hover:underline"
              style={{ color: 'var(--link)' }}
            >
              {checkpoints.length} checkpoint{checkpoints.length !== 1 ? 's' : ''}
              <span className="ml-1 text-[9px]">{showCheckpoints ? '▲' : '▼'}</span>
            </button>
          </>
        )}
      </div>
      {showCheckpoints && (
        <CheckpointTimeline
          checkpoints={checkpoints}
          onRevert={(id) => (window as any)._revertCheckpoint?.(id)}
        />
      )}
    </div>
  );
}
```

- [ ] **Step 8.5: Create CheckpointTimeline.tsx**

```tsx
import type { CheckpointInfo } from '@/bridge/types';

interface Props {
  checkpoints: CheckpointInfo[];
  onRevert: (id: string) => void;
}

export function CheckpointTimeline({ checkpoints, onRevert }: Props) {
  return (
    <div
      className="border-t px-4 py-2 space-y-1.5 max-h-48 overflow-y-auto"
      style={{ borderColor: 'var(--border)', background: 'var(--bg)' }}
    >
      {checkpoints.map((cp) => (
        <div key={cp.id} className="flex items-center gap-2 text-[11px]">
          <div className="w-1.5 h-1.5 rounded-full flex-shrink-0" style={{ background: 'var(--accent)' }} />
          <span className="flex-1 truncate" style={{ color: 'var(--fg)' }}>{cp.description}</span>
          <span className="flex-shrink-0 font-mono" style={{ color: 'var(--diff-add-fg)' }}>
            +{cp.totalLinesAdded}
          </span>
          <span className="flex-shrink-0 font-mono" style={{ color: 'var(--diff-rem-fg)' }}>
            -{cp.totalLinesRemoved}
          </span>
          <button
            onClick={() => onRevert(cp.id)}
            className="flex-shrink-0 text-[10px] px-2 py-0.5 rounded hover:opacity-80 transition-opacity"
            style={{ background: 'var(--error)', color: 'var(--bg)' }}
          >
            Revert
          </button>
        </div>
      ))}
    </div>
  );
}
```

- [ ] **Step 8.6: Wire into ChatView**

Place EditStatsBar between the message list and the input bar.

- [ ] **Step 8.7: Wire Kotlin bridge**

In AgentCefPanel.kt:
```kotlin
fun updateEditStats(added: Int, removed: Int, files: Int) {
    callJs("updateEditStats($added,$removed,$files)")
}

fun updateCheckpoints(checkpointsJson: String) {
    callJs("updateCheckpoints(${jsonStr(checkpointsJson)})")
}
```

Add revert bridge:
```kotlin
revertCheckpointQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
    addHandler { checkpointId ->
        session?.rollbackManager?.rollbackToCheckpoint(checkpointId)
        // Refresh VFS after revert
        try {
            LocalFileSystem.getInstance().findFileByPath(project.basePath ?: "")?.refresh(true, true)
        } catch (_: Exception) { }
        JBCefJSQuery.Response("ok")
    }
}
```

Inject into JS:
```kotlin
revertCheckpointQuery?.let { q ->
    val js = q.inject("id")
    js("window._revertCheckpoint = function(id) { $js }")
}
```

In AgentController, after each tool execution that modifies files:
```kotlin
// Push edit stats and checkpoints to UI
val ledger = session?.changeLedger
if (ledger != null) {
    val stats = ledger.totalStats()
    dashboard.updateEditStats(stats.totalLinesAdded, stats.totalLinesRemoved, stats.filesModified)

    val checkpointsJson = kotlinx.serialization.json.Json.encodeToString(
        kotlinx.serialization.builtins.ListSerializer(CheckpointMeta.serializer()),
        ledger.listCheckpoints()
    )
    dashboard.updateCheckpoints(checkpointsJson)
}
```

- [ ] **Step 8.8: Build webview**

```bash
cd agent/webview && npm run build
```

- [ ] **Step 8.9: Compile and test**

```bash
./gradlew :agent:compileKotlin
./gradlew :agent:test --rerun --no-build-cache
```

- [ ] **Step 8.10: Commit**

```bash
git add agent/webview/src/ -f agent/src/main/resources/webview/dist/ \
  agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt \
  agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt
git commit -m "feat(agent): edit stats bar + checkpoint timeline UI with revert"
```

---

## Summary

| Task | What | LLM Sees | User Sees | Survives Compression |
|------|------|----------|-----------|---------------------|
| 1 | ChangeLedger core | — | — | Disk (changes.jsonl) |
| 2 | Wire to session + anchor | `<change_ledger>` table | — | Yes (anchor) |
| 3 | CreateFile + EditFile record | Richer tool results | — | Yes (anchor + facts) |
| 4 | Encoding fallback | Correct file content | — | N/A |
| 5 | Per-edit checkpoints | Checkpoint IDs in anchor | — | Yes (anchor) |
| 6 | list_changes + rollback_changes | Full change query + revert | — | Tool result (normal pruning) |
| 7 | Enriched facts | "+12/-3 lines" in facts | — | Yes (factsAnchor) |
| 8 | UI components | — | Stats bar + timeline + revert | N/A |

## Compression Summary

```
Layer 1 (Always visible to LLM):
  - changeLedgerAnchor: compact table of all changes (~500-5K tokens)
  - factsAnchor: enriched "iter N: +X/-Y lines" summaries

Layer 2 (Available on demand):
  - list_changes tool: full detail with previews, checkpoint IDs
  - Tool results (before pruning): complete edit response with context

Layer 3 (Persisted to disk):
  - changes.jsonl: complete history, survives IDE restarts
  - ToolOutputStore: full tool outputs on disk for re-reads

Layer 4 (IDE-level):
  - LocalHistory labels: named checkpoints for revert
  - IntelliJ undo stack: per-file undo via Ctrl+Z
```

## Rollout Order

Tasks 1-2 first (core + wiring), then 3 (tools that record), then 4 (encoding), then 5 (checkpoints), then 6 (LLM tools), then 7 (enriched facts), then 8 (UI). Each task is independently compilable and testable.

# File Tools Enhancement — CreateFileTool, Checkpoints, Edit Statistics, VFS Refresh

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring agent file tools to enterprise parity — add file creation, granular LocalHistory checkpoints with revert UI, per-conversation edit statistics (lines added/removed), VFS refresh after writes, and diff context in edit responses.

**Architecture:** Six independent enhancements layered on existing infrastructure: EditFileTool (3-tier write), AgentRollbackManager (LocalHistory labels), SelfCorrectionGate (edit tracking), EditDiffView (React diff rendering), ToolOutputStore (context management). Each task is independently testable and shippable.

**Tech Stack:** Kotlin, IntelliJ Platform SDK (LocalHistory, WriteCommandAction, VirtualFile, Document API), React + TypeScript (JCEF webview), Zustand state management

---

## File Structure

| File | Action | Responsibility |
|------|--------|---------------|
| `agent/tools/builtin/CreateFileTool.kt` | Create | New file creation with PathValidator, WriteCommandAction, undo support |
| `agent/tools/builtin/EditFileTool.kt` | Modify | Add VFS refresh after writes, diff context in response, LocalHistory label per edit |
| `agent/tools/builtin/ReadFileTool.kt` | Modify | Add encoding fallback chain |
| `agent/AgentService.kt` | Modify | Register CreateFileTool |
| `agent/tools/DynamicToolSelector.kt` | Modify | Add create_file to ALWAYS_INCLUDE |
| `agent/runtime/AgentRollbackManager.kt` | Modify | Add checkpoint metadata (description, files, timestamps), list checkpoints |
| `agent/runtime/SingleAgentSession.kt` | Modify | Create per-iteration checkpoints, track edit stats |
| `agent/orchestrator/AgentOrchestrator.kt` | Modify | Pass edit statistics to UI |
| `agent/webview/src/bridge/types.ts` | Modify | Add EditStats + Checkpoint types to SessionInfo |
| `agent/webview/src/bridge/jcef-bridge.ts` | Modify | Add bridge functions for checkpoint list + revert |
| `agent/webview/src/components/agent/EditStatsBar.tsx` | Create | Floating bar showing lines added/removed per conversation |
| `agent/webview/src/components/agent/CheckpointList.tsx` | Create | Checkpoint timeline with revert buttons |
| `agent/ui/AgentCefPanel.kt` | Modify | Wire checkpoint bridge functions |
| `agent/ui/AgentController.kt` | Modify | Wire checkpoint revert action, pass edit stats to UI |
| `agent/runtime/ApprovalGate.kt` | Modify | Add create_file to MEDIUM_RISK_TOOLS |

---

### Task 1: CreateFileTool — New File Creation

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/CreateFileTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/DynamicToolSelector.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ApprovalGate.kt`

- [ ] **Step 1.1: Create CreateFileTool.kt**

Follow the exact EditFileTool pattern: PathValidator → WriteCommandAction → VFS → java.io.File fallback.

```kotlin
package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

class CreateFileTool : AgentTool {
    override val name = "create_file"
    override val description = "Create a new file with specified content. " +
        "Use this for creating new source files, test files, config files, etc. " +
        "Parent directories are created automatically. " +
        "Fails if file already exists unless overwrite=true."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "path" to ParameterProperty(type = "string", description = "Absolute or project-relative file path for the new file"),
            "content" to ParameterProperty(type = "string", description = "Content to write to the new file"),
            "overwrite" to ParameterProperty(type = "boolean", description = "Allow overwrite if file already exists. Default: false"),
            "description" to ParameterProperty(type = "string", description = "Brief description of what this file is for (shown in approval dialog)")
        ),
        required = listOf("path", "content", "description")
    )
    override val allowedWorkers = setOf(WorkerType.CODER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val rawPath = params["path"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'path' parameter required", "Error: missing path", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val content = params["content"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'content' parameter required", "Error: missing content", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val overwrite = params["overwrite"]?.jsonPrimitive?.booleanOrNull ?: false

        val (path, pathError) = PathValidator.resolveAndValidate(rawPath, project.basePath)
        if (pathError != null) return pathError
        val resolvedPath = path!!

        val file = java.io.File(resolvedPath)

        // Check if file already exists
        if (file.exists() && !overwrite) {
            return ToolResult(
                "Error: File already exists: $rawPath. Use overwrite=true to replace, or use edit_file to modify.",
                "Error: file exists",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        // Create parent directories if needed
        val parentDir = file.parentFile
        if (parentDir != null && !parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                return ToolResult(
                    "Error: Could not create parent directory: ${parentDir.path}",
                    "Error: mkdir failed",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
            }
        }

        // Write via VFS + WriteCommandAction (undo support) → fallback to java.io.File
        val written = writeViaVfs(resolvedPath, project, rawPath, content)
            || writeViaFileIo(file, content)

        if (!written) {
            return ToolResult(
                "Error: Failed to create file: $rawPath",
                "Error: create failed",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        // Track in EditFileTool.lastEditLineRanges for diff-aware diagnostics
        try {
            val lineCount = content.count { it == '\n' } + 1
            EditFileTool.lastEditLineRanges[file.canonicalPath] = 1..lineCount
        } catch (_: Exception) { }

        val lineCount = content.lines().size
        val summary = "Created $rawPath ($lineCount lines, ${content.length} chars)"
        return ToolResult(
            content = summary,
            summary = summary,
            tokenEstimate = TokenEstimator.estimate(summary),
            artifacts = listOf(resolvedPath)
        )
    }

    private fun writeViaVfs(resolvedPath: String, project: Project, rawPath: String, content: String): Boolean {
        return try {
            if (ApplicationManager.getApplication() == null) return false
            val parentPath = java.io.File(resolvedPath).parent ?: return false

            invokeAndWaitIfNeeded {
                WriteCommandAction.runWriteCommandAction(project, "Agent: create $rawPath", null, Runnable {
                    // Ensure parent dir exists in VFS
                    val parentVFile = VfsUtil.createDirectoryIfMissing(parentPath) ?: return@Runnable
                    val fileName = java.io.File(resolvedPath).name

                    // Create or overwrite file
                    val existingChild = parentVFile.findChild(fileName)
                    val vFile = existingChild ?: parentVFile.createChildData(this, fileName)
                    vFile.setBinaryContent(content.toByteArray(vFile.charset))
                })
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun writeViaFileIo(file: java.io.File, content: String): Boolean {
        return try {
            file.writeText(content, Charsets.UTF_8)
            // Refresh VFS so IDE sees the new file
            try {
                LocalFileSystem.getInstance().refreshAndFindFileByPath(file.absolutePath)
            } catch (_: Exception) { }
            true
        } catch (_: Exception) {
            false
        }
    }
}
```

- [ ] **Step 1.2: Register CreateFileTool in AgentService.kt**

In the "Builtin tools" section (~line 165), add:
```kotlin
register(CreateFileTool())
```

- [ ] **Step 1.3: Add to ALWAYS_INCLUDE in DynamicToolSelector.kt**

Add `"create_file"` to the `ALWAYS_INCLUDE` set.

- [ ] **Step 1.4: Add to ApprovalGate risk classification**

In `MEDIUM_RISK_TOOLS`, add `"create_file"`. Context-aware classification in `classifyRisk()`:
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

- [ ] **Step 1.5: Compile and test**

```bash
./gradlew :agent:compileKotlin
./gradlew :agent:test --rerun --no-build-cache
```

- [ ] **Step 1.6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/CreateFileTool.kt \
  agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt \
  agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/DynamicToolSelector.kt \
  agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ApprovalGate.kt
git commit -m "feat(agent): add CreateFileTool for new file creation with undo support"
```

---

### Task 2: VFS Refresh + Diff Context in EditFileTool Response

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/EditFileTool.kt`

- [ ] **Step 2.1: Add VFS refresh after writes**

After `writeViaVfs` succeeds (line ~239), add:
```kotlin
try { vFile?.refresh(false, false) } catch (_: Exception) { }
```

After `writeViaFileIo` succeeds (line ~251), add:
```kotlin
try { LocalFileSystem.getInstance().refreshAndFindFileByPath(file.absolutePath) } catch (_: Exception) { }
```

- [ ] **Step 2.2: Add diff context in edit response**

After a successful edit, read 3 lines before and 3 lines after the changed range and include in the response. Change the return at line ~147:

```kotlin
// Build response with line context around the edit
val contextLines = try {
    val newFileContent = readFileContent(vFile, file)
    val editStart = content.indexOf(oldString)
    val startLine = content.substring(0, editStart).count { it == '\n' }
    val newLines = newString.lines().size
    val allNewLines = newFileContent.lines()
    val contextStart = (startLine - 3).coerceAtLeast(0)
    val contextEnd = (startLine + newLines + 3).coerceAtMost(allNewLines.size)
    allNewLines.subList(contextStart, contextEnd).mapIndexed { idx, line ->
        "${contextStart + idx + 1}\t$line"
    }.joinToString("\n")
} catch (_: Exception) { null }

val contextSection = if (contextLines != null) "\n\nContext after edit:\n$contextLines" else ""
return ToolResult(
    content = "$summary$contextSection",
    summary = summary,
    tokenEstimate = TokenEstimator.estimate(summary + (contextLines ?: "")),
    artifacts = listOf(resolvedPath)
)
```

- [ ] **Step 2.3: Compile and test**

```bash
./gradlew :agent:compileKotlin
./gradlew :agent:test --rerun --no-build-cache
```

- [ ] **Step 2.4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/EditFileTool.kt
git commit -m "fix(agent): add VFS refresh after writes + diff context in edit response"
```

---

### Task 3: Encoding Fallback in ReadFileTool

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/ReadFileTool.kt`

- [ ] **Step 3.1: Add encoding fallback chain**

Replace the fallback `file.readLines(Charsets.UTF_8)` calls (lines 81, 84) with a fallback chain:

```kotlin
private fun readLinesWithFallback(file: java.io.File): List<String> {
    // Try UTF-8 first (most common)
    try {
        val text = file.readText(Charsets.UTF_8)
        // Validate: if text contains replacement characters, encoding was wrong
        if (!text.contains('\uFFFD')) return text.lines()
    } catch (_: Exception) { }

    // Try ISO-8859-1 (Latin-1) — lossless for all byte values
    try {
        return file.readText(Charsets.ISO_8859_1).lines()
    } catch (_: Exception) { }

    // Final fallback: read as UTF-8 ignoring errors
    return file.readText(Charsets.UTF_8).lines()
}
```

Replace `file.readLines(Charsets.UTF_8)` → `readLinesWithFallback(file)` in both locations.

- [ ] **Step 3.2: Compile and test**

```bash
./gradlew :agent:compileKotlin
./gradlew :agent:test --rerun --no-build-cache
```

- [ ] **Step 3.3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/ReadFileTool.kt
git commit -m "fix(agent): add encoding fallback chain in ReadFileTool (UTF-8 → Latin-1)"
```

---

### Task 4: Per-Iteration Checkpoints with Metadata

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/AgentRollbackManager.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt`

- [ ] **Step 4.1: Enhance AgentRollbackManager with checkpoint metadata**

Add a data class for checkpoint info and a method to list all checkpoints:

```kotlin
data class CheckpointInfo(
    val id: String,
    val description: String,
    val timestamp: Long,
    val filesModified: List<String>,
    val iteration: Int
)

// In AgentRollbackManager:
private val checkpointInfos = mutableListOf<CheckpointInfo>()

fun createCheckpoint(description: String, iteration: Int = 0, filesModified: List<String> = emptyList()): String {
    val id = UUID.randomUUID().toString().take(12)
    try {
        val label = LocalHistory.getInstance().putSystemLabel(project, "Agent: $description")
        checkpoints[id] = label
        checkpointInfos.add(CheckpointInfo(id, description, System.currentTimeMillis(), filesModified, iteration))
    } catch (e: Exception) { LOG.warn("Failed to create checkpoint", e) }
    return id
}

fun listCheckpoints(): List<CheckpointInfo> = checkpointInfos.toList()
```

- [ ] **Step 4.2: Wire per-iteration checkpoints in SingleAgentSession**

In the ReAct loop, after each tool execution that produces artifacts (edited files), create a checkpoint:

```kotlin
// After tool execution, if files were modified:
if (toolResult.artifacts.isNotEmpty()) {
    session?.rollbackManager?.createCheckpoint(
        description = "Iteration $iteration: $toolName",
        iteration = iteration,
        filesModified = toolResult.artifacts
    )
    toolResult.artifacts.forEach { session?.rollbackManager?.trackFileChange(it) }
}
```

- [ ] **Step 4.3: Compile and test**

```bash
./gradlew :agent:compileKotlin
./gradlew :agent:test --rerun --no-build-cache
```

- [ ] **Step 4.4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/AgentRollbackManager.kt \
  agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt
git commit -m "feat(agent): per-iteration LocalHistory checkpoints with metadata"
```

---

### Task 5: Edit Statistics Tracking

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/EditFileTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/AgentOrchestrator.kt`

- [ ] **Step 5.1: Track lines added/removed per edit in EditFileTool**

Add to EditFileTool companion object:

```kotlin
data class EditStats(
    val filePath: String,
    val linesAdded: Int,
    val linesRemoved: Int
)

/** Cumulative edit statistics for the current session. Thread-safe. */
val sessionEditStats = java.util.concurrent.ConcurrentHashMap<String, EditStats>()

fun recordEditStats(filePath: String, oldContent: String, newContent: String) {
    val oldLines = oldContent.lines()
    val newLines = newContent.lines()
    val added = (newLines.size - oldLines.size).coerceAtLeast(0)
    val removed = (oldLines.size - newLines.size).coerceAtLeast(0)
    // Simple: count net line change. For exact diff, use LCS — but that's expensive.
    sessionEditStats.merge(filePath, EditStats(filePath, added, removed)) { existing, new ->
        EditStats(filePath, existing.linesAdded + new.linesAdded, existing.linesRemoved + new.linesRemoved)
    }
}

fun getSessionStats(): Pair<Int, Int> {
    val totalAdded = sessionEditStats.values.sumOf { it.linesAdded }
    val totalRemoved = sessionEditStats.values.sumOf { it.linesRemoved }
    return totalAdded to totalRemoved
}
```

In `execute()`, after successful edit, before return:
```kotlin
recordEditStats(resolvedPath, content, newContent)
```

- [ ] **Step 5.2: Pass edit stats to UI via SessionInfo**

In `AgentOrchestrator`, when building the result, include edit stats:
```kotlin
val (linesAdded, linesRemoved) = EditFileTool.getSessionStats()
```

Pass these in the progress/completion callbacks that reach the UI.

- [ ] **Step 5.3: Compile and test**

```bash
./gradlew :agent:compileKotlin
./gradlew :agent:test --rerun --no-build-cache
```

- [ ] **Step 5.4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/EditFileTool.kt \
  agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt \
  agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/AgentOrchestrator.kt
git commit -m "feat(agent): track lines added/removed per edit for session statistics"
```

---

### Task 6: UI — Edit Statistics Bar + Checkpoint Timeline

**Files:**
- Create: `agent/webview/src/components/agent/EditStatsBar.tsx`
- Create: `agent/webview/src/components/agent/CheckpointTimeline.tsx`
- Modify: `agent/webview/src/bridge/types.ts`
- Modify: `agent/webview/src/bridge/jcef-bridge.ts`
- Modify: `agent/webview/src/stores/chatStore.ts`
- Modify: `agent/webview/src/components/chat/ChatView.tsx`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt`

- [ ] **Step 6.1: Add types**

In `bridge/types.ts`:
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
  filesModified: string[];
  iteration: number;
}

// Update SessionInfo:
export interface SessionInfo {
  status: SessionStatus;
  tokensUsed: number;
  durationMs: number;
  iterations: number;
  filesModified: string[];
  editStats?: EditStats;          // ADD
  checkpoints?: CheckpointInfo[]; // ADD
}
```

- [ ] **Step 6.2: Add bridge functions**

In `jcef-bridge.ts`, register:
```typescript
window.updateEditStats = (added: number, removed: number, files: number) => {
  useChatStore.getState().updateEditStats({ totalLinesAdded: added, totalLinesRemoved: removed, filesModified: files });
};
window.updateCheckpoints = (checkpointsJson: string) => {
  const checkpoints = JSON.parse(checkpointsJson) as CheckpointInfo[];
  useChatStore.getState().updateCheckpoints(checkpoints);
};
```

In Kotlin `AgentCefPanel.kt`, add:
```kotlin
fun updateEditStats(added: Int, removed: Int, files: Int) {
    callJs("updateEditStats($added,$removed,$files)")
}
fun updateCheckpoints(checkpointsJson: String) {
    callJs("updateCheckpoints(${jsonStr(checkpointsJson)})")
}
```

- [ ] **Step 6.3: Create EditStatsBar component**

Small floating bar at the bottom of the chat showing:
```
+45 -12 | 3 files modified | 4 checkpoints
```

Green for additions, red for removals. Clicking "checkpoints" opens the checkpoint timeline.

```tsx
// agent/webview/src/components/agent/EditStatsBar.tsx
export function EditStatsBar({ stats, checkpointCount, onShowCheckpoints }: Props) {
  if (!stats || (stats.totalLinesAdded === 0 && stats.totalLinesRemoved === 0)) return null;
  return (
    <div className="flex items-center gap-3 px-3 py-1.5 text-[11px] font-mono border-t" style={{ borderColor: 'var(--border)' }}>
      <span style={{ color: 'var(--diff-add-fg)' }}>+{stats.totalLinesAdded}</span>
      <span style={{ color: 'var(--diff-rem-fg)' }}>-{stats.totalLinesRemoved}</span>
      <span style={{ color: 'var(--fg-muted)' }}>{stats.filesModified} files</span>
      {checkpointCount > 0 && (
        <button onClick={onShowCheckpoints} className="text-[var(--link)] hover:underline">
          {checkpointCount} checkpoints
        </button>
      )}
    </div>
  );
}
```

- [ ] **Step 6.4: Create CheckpointTimeline component**

Expandable timeline showing all checkpoints with revert buttons:

```tsx
// agent/webview/src/components/agent/CheckpointTimeline.tsx
export function CheckpointTimeline({ checkpoints, onRevert }: Props) {
  return (
    <div className="space-y-2 p-3">
      {checkpoints.map((cp, i) => (
        <div key={cp.id} className="flex items-center gap-2 text-[12px]">
          <div className="w-2 h-2 rounded-full" style={{ background: 'var(--accent)' }} />
          <span className="flex-1">{cp.description}</span>
          <span className="text-[var(--fg-muted)]">{formatTime(cp.timestamp)}</span>
          <button onClick={() => onRevert(cp.id)}
            className="text-[10px] px-2 py-0.5 rounded"
            style={{ background: 'var(--error)', color: 'var(--bg)' }}>
            Revert
          </button>
        </div>
      ))}
    </div>
  );
}
```

- [ ] **Step 6.5: Wire into ChatView**

Add EditStatsBar below the message list, above the input bar. Add CheckpointTimeline as a collapsible panel.

- [ ] **Step 6.6: Wire Kotlin → JS for edit stats updates**

In `AgentController.handleProgress()`, after each edit_file tool:
```kotlin
val (added, removed) = EditFileTool.getSessionStats()
dashboard.updateEditStats(added, removed, EditFileTool.sessionEditStats.size)
```

For checkpoint revert, add a bridge function:
```kotlin
revertCheckpointQuery = JBCefJSQuery.create(b).apply {
    addHandler { checkpointId ->
        session?.rollbackManager?.rollbackToCheckpoint(checkpointId)
        JBCefJSQuery.Response("ok")
    }
}
```

Inject into JS:
```kotlin
js("window._revertCheckpoint = function(id) { ${revertCheckpointQuery.inject("id")} }")
```

- [ ] **Step 6.7: Build webview**

```bash
cd agent/webview && npm run build
```

- [ ] **Step 6.8: Compile and test**

```bash
./gradlew :agent:compileKotlin
./gradlew :agent:test --rerun --no-build-cache
```

- [ ] **Step 6.9: Commit**

```bash
git add agent/webview/src/ agent/src/main/resources/webview/dist/ \
  agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt \
  agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt
git commit -m "feat(agent): edit statistics bar + checkpoint timeline UI with revert"
```

---

## Summary: Token Budget Before/After

| Enhancement | Impact |
|-------------|--------|
| CreateFileTool | Unblocks new file creation — agent can now create test files, configs, new classes |
| VFS Refresh | IDE instantly sees agent changes — no 1-2s watcher delay |
| Diff Context | LLM sees 3 lines around edit — can verify without re-reading file |
| Encoding Fallback | Latin-1/CP-1252 files now readable (not garbled) |
| Checkpoints | Per-iteration LocalHistory labels — user can revert any agent action via timeline |
| Edit Statistics | Real-time "+45 -12 | 3 files" bar — user sees agent's impact at a glance |

## Files Summary

| File | Action | Task |
|------|--------|------|
| `tools/builtin/CreateFileTool.kt` | Create | 1 |
| `tools/builtin/EditFileTool.kt` | Modify | 2, 5 |
| `tools/builtin/ReadFileTool.kt` | Modify | 3 |
| `runtime/AgentRollbackManager.kt` | Modify | 4 |
| `runtime/SingleAgentSession.kt` | Modify | 4, 5 |
| `orchestrator/AgentOrchestrator.kt` | Modify | 5 |
| `AgentService.kt` | Modify | 1 |
| `tools/DynamicToolSelector.kt` | Modify | 1 |
| `runtime/ApprovalGate.kt` | Modify | 1 |
| `ui/AgentCefPanel.kt` | Modify | 6 |
| `ui/AgentController.kt` | Modify | 6 |
| `webview/src/bridge/types.ts` | Modify | 6 |
| `webview/src/bridge/jcef-bridge.ts` | Modify | 6 |
| `webview/src/stores/chatStore.ts` | Modify | 6 |
| `webview/src/components/agent/EditStatsBar.tsx` | Create | 6 |
| `webview/src/components/agent/CheckpointTimeline.tsx` | Create | 6 |
| `webview/src/components/chat/ChatView.tsx` | Modify | 6 |

## Rollout Order

1. **Task 1 (CreateFileTool)** — unblocks immediately, no dependencies
2. **Task 2 (VFS Refresh + Diff Context)** — quick win, improves edit quality
3. **Task 3 (Encoding Fallback)** — quick win, fixes edge case
4. **Task 4 (Checkpoints)** — enhances existing AgentRollbackManager
5. **Task 5 (Edit Statistics)** — tracks data for UI
6. **Task 6 (UI)** — depends on Tasks 4+5 data

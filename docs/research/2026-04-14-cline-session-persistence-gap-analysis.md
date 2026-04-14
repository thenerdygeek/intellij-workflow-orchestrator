# Cline Session Persistence Port — Gap Analysis

**Date**: 2026-04-14
**Cline source**: `/tmp/cline-analysis/src/core/task/message-state.ts`, `storage/disk.ts`, `index.ts`
**Our source**: `agent/src/main/kotlin/com/workflow/orchestrator/agent/session/`

---

## Architecture Comparison

| Component | Cline | Ours | Match? |
|-----------|-------|------|--------|
| **Two-file model** | `api_conversation_history.json` + `ui_messages.json` per task | Same two files per session | Faithful |
| **Atomic writes** | `atomicWriteFile()` — temp + rename | `AtomicFileWriter` — temp + `Files.move(ATOMIC_MOVE)` | Faithful |
| **Save cadence** | Per-change, awaited inline | Same — per-change, awaited inline | Faithful |
| **Mutex** | `p-mutex` with `withStateLock` | `kotlinx.coroutines.sync.Mutex` + separate `globalIndexMutex` | Faithful (improved) |
| **Session index** | `taskHistory.json` in global state | `sessions.json` per project | Faithful |
| **Session lock** | SQLite-based distributed lock manager | `java.nio.channels.FileLock` on `.lock` file | Intentional divergence |
| **Checkpoints** | Shadow Git repository (working directory state) | JSONL + meta JSON (conversation state) | Intentional divergence |
| **Remote sync** | `syncWorker().enqueue()` to cloud | None | N/A (IntelliJ plugin) |

## Faithfully Ported (No Gaps)

These components are correctly ported from Cline:

1. **Two-file persistence model** — Same file names, same purpose, same structure
2. **Atomic write mechanism** — Write to temp → atomic rename → delete temp on error
3. **Per-change save cadence** — Every `addToClineMessages`, `updateClineMessage`, `addToApiConversationHistory` triggers immediate save, awaited inline (not fire-and-forget)
4. **Mutex protection** — All state mutations guarded. We improved on Cline by adding a separate `globalIndexMutex` for `sessions.json` to prevent cross-session corruption
5. **Streaming partial lifecycle** — First chunk → add `partial=true`, subsequent → update in-place, stream end → flip to `partial=false`
6. **Abort stream persistence** — Flip last partial to non-partial, inject synthetic `[Response interrupted by ...]` turn, save both files
7. **`conversationHistoryIndex` cross-reference** — Every UI message maps to its corresponding API message by index
8. **Resume trimming** — Strip trailing resume asks + orphaned api_req_started messages before resume
9. **API history rebuild** — Pop trailing user message, prepend to new user content, rebuild context
10. **Session migration** — Old JSONL format → new two-file JSON format (idempotent)
11. **HistoryItem index** — Same fields (id, ts, task, tokensIn/Out, totalCost, isFavorited, modelId), updated after every message save
12. **Subagent persistence** — Sub-agent conversations saved to per-session subdirectory

## Intentional Divergences (Not Bugs)

These differ by design, not oversight:

### 1. Session Lock: FileLock vs SQLite

**Cline**: `SqliteLockManager` with retry logic (500ms initial, 30s timeout). Robust for networked filesystems.
**Ours**: `java.nio.channels.FileLock` on `.lock` file. Advisory, OS-level, non-blocking `tryAcquire()`.

**Why different**: IntelliJ runs locally — file-based locks are simpler and sufficient. SQLite would add a dependency for no benefit on local filesystems.

### 2. Checkpoint System: Git vs Conversation

**Cline**: Shadow Git repo commits entire working directory state. Checkpoint = git snapshot of files.
**Ours**: JSONL saves conversation messages. Checkpoint = conversation state snapshot.

**Why different**: Our `RollbackManager` handles file revert separately via IntelliJ's `LocalHistory` API + git fallback. We have BOTH capabilities (file revert + conversation checkpoint), just split across different systems rather than unified via git. IntelliJ's LocalHistory is actually more granular than git snapshots.

### 3. UI Message Fields: Plugin-Specific

**Cline**: `commandCompleted`, `lastCheckpointHash`, `isCheckpointCheckedOut`, `isOperationOutsideWorkspace` (VS Code-specific)
**Ours**: `planData`, `approvalData`, `questionData`, `subagentData`, `toolCallData`, `artifactId` (IntelliJ plugin-specific)

**Why different**: Different UI features. Both preserve the core fields (`ts`, `type`, `ask`, `say`, `text`, `partial`, `conversationHistoryIndex`).

### 4. Content Block Types: Custom vs Anthropic SDK

**Cline**: Extends `Anthropic.MessageParam` directly with SDK types
**Ours**: Custom `ContentBlock` sealed interface (`Text`, `ToolUse`, `ToolResult`, `Image`)

**Why different**: We use Sourcegraph's API, not Anthropic's SDK directly. Custom DTOs give us provider independence.

---

## Real Gaps Found

### ~~GAP 1~~ — NOT A GAP: TaskResume Hook Already Implemented

**Status**: Already implemented at `AgentService.kt:1241-1253`. The `TASK_RESUME` hook IS dispatched during resume, with cancellation support. This was incorrectly identified as a gap during initial analysis — verified during planning that the wiring exists.

---

### GAP 2 — MEDIUM: Abort Doesn't Finalize api_req_started Cost/CancelReason

**Cline behavior** (`index.ts:2738-2740`):
When aborting a stream, Cline updates the `api_req_started` UI message with `cancelReason` and accumulated `cost`:

```typescript
await finalizeApiReqMsg(cancelReason, streamingFailedMessage)
```

This ensures the UI shows why the request was cancelled and what it cost, even for interrupted requests.

**Our behavior**: `abortStream()` flips the partial flag and injects the synthetic turn, but does NOT update the `api_req_started` UI message with cost/cancelReason. The cost info is lost for aborted requests.

**Impact**: In the history view, aborted sessions show the last API request without cost data. Users can't see how much an interrupted request consumed. Token tracking is incomplete for cancelled sessions.

**Fix**: In `abortStream()`, find the last `API_REQ_STARTED` UI message and update its text/cost JSON with the accumulated metrics before saving.

---

### GAP 3 — MEDIUM: No Extended Thinking Content Block Types

**Cline behavior** (`content.ts:28-73`):
Cline has dedicated content block types for extended thinking:
- `ClineAssistantThinkingBlock` — thinking with optional `summary`
- `ClineAssistantRedactedThinkingBlock` — redacted thinking blocks

These are persisted in `api_conversation_history.json` so thinking content survives resume.

**Our behavior**: We have a `reasoning` field on `UiMessage` for UI display, but our `ContentBlock` sealed interface only has `Text`, `ToolUse`, `ToolResult`, `Image`. No thinking-specific content blocks for API history.

**Impact**: If using models with extended thinking (Opus thinking via Sourcegraph), thinking blocks may be lost in API conversation history. On resume, the model wouldn't see its prior reasoning chain. This degrades resume quality for thinking models.

**Fix**: Add `Thinking(thinking: String, summary: String?)` and `RedactedThinking(data: String)` to the `ContentBlock` sealed interface. Handle in `ApiMessage.toChatMessage()` conversion.

---

### GAP 4 — LOW: Resume Preamble Missing `wasRecent` Check

**Cline behavior** (`responses.ts:239-257`):
Cline checks if the task was interrupted recently (< 30 seconds):

```typescript
const wasRecent = lastClineMessage?.ts && Date.now() - lastClineMessage.ts < 30_000
```

If recent, the preamble is shorter (less "the world may have changed" warning). If old, it includes context about potential project state changes.

**Our behavior**: `ResumeHelper.buildTaskResumptionPreamble()` always includes the full warning regardless of how recent the interruption was.

**Impact**: Minor — slightly more verbose preamble for immediate resumes. The model wastes a few tokens re-assessing a project state that hasn't changed.

**Fix**: Add `wasRecent` check to `buildTaskResumptionPreamble()`.

---

### GAP 5 — LOW: No `didFinishAbortingStream` Flag

**Cline behavior** (`index.ts:2779`):
Sets `this.taskState.didFinishAbortingStream = true` after abort completes. This flag is checked during resume to know if the abort was clean.

**Our behavior**: No equivalent flag. Abort is synchronous (within the coroutine), so there's no race condition concern like in Cline's async abort.

**Impact**: None in practice — our abort is coroutine-based (not callback-based like Cline), so there's no race between "abort in progress" and "resume starting". The flag is unnecessary for our architecture.

**Not a gap** — architectural difference that eliminates the need for the flag.

---

### GAP 6 — LOW: History UI Missing Sort/Filter Options

**Cline behavior** (`getTaskHistory.ts:12-118`):
History supports:
- Sort by: newest, oldest, most expensive, most tokens
- Filter by: favorites only, current workspace only
- Search: substring match on task text

**Our behavior**: `HistoryView.tsx` shows sessions recent-first. Has search and favorites. No sort toggle. No workspace filter (each project has its own session directory, so this is implicit).

**Impact**: Minor UX gap — power users can't sort by cost or tokens to find expensive sessions. Workspace filtering is N/A (already scoped per project).

**Fix consideration**: Add sort dropdown to HistoryView header. Low priority.

---

### GAP 7 — LOW: No Diff View Revert on Abort

**Cline behavior** (`index.ts:2724-2727`):
If a diff view is open when the stream is aborted, Cline reverts the diff:
```typescript
if (this.diffViewProvider.isEditing) {
    await this.diffViewProvider.revertChanges()
}
```

**Our behavior**: We don't have a DiffViewProvider that opens during tool execution. Our diffs are rendered in the JCEF chat UI (diff cards), not as IntelliJ editor diffs.

**Impact**: None — different UI architecture. Our diffs are display-only cards, not editable diff views.

**Not a gap** — architectural difference.

---

## Features We Have That Cline Doesn't

| Feature | Description |
|---------|-------------|
| **Separate `globalIndexMutex`** | Protects `sessions.json` from cross-session corruption (Cline uses single mutex) |
| **Session export** | Export single or bulk sessions (Cline doesn't have export) |
| **Bulk delete** | Select multiple sessions for deletion (Cline has only "delete all") |
| **`saveBoth()`** | Explicit dual-file atomic save for abort consistency |
| **IntelliJ LocalHistory rollback** | File revert via IDE's built-in LocalHistory (more granular than git snapshots) |
| **Per-project session isolation** | Sessions scoped to `~/.workflow-orchestrator/{proj}/` (Cline uses global `~/.cline/data/tasks/`) |
| **JSONL migration** | Automatic migration from legacy JSONL format |

---

## Summary

| Category | Count | Details |
|----------|-------|---------|
| **Faithfully ported** | 12 | Core persistence model, atomic writes, mutex, streaming, resume, abort, index |
| **Intentional divergences** | 4 | Session lock, checkpoints, UI fields, content block types |
| **Real gaps** | 4 | TaskResume hook, abort cost finalization, thinking blocks, wasRecent |
| **Non-gaps (different arch)** | 2 | didFinishAbortingStream flag, diff view revert |
| **We're better** | 7 | globalIndexMutex, export, bulk delete, saveBoth, LocalHistory, project isolation, migration |

## Priority Fix Order

1. **GAP 3** (MEDIUM) — Extended thinking content blocks (affects resume quality for thinking models)
2. **GAP 2** (MEDIUM) — Abort cost/cancelReason finalization (affects token tracking accuracy)
3. **GAP 1** (MEDIUM) — TaskResume hook wiring (affects hook users)
4. **GAP 4** (LOW) — wasRecent check in resume preamble
5. **GAP 6** (LOW) — History sort options

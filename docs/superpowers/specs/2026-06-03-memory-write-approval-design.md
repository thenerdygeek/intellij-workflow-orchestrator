# Memory-Write Approval Gate + Bypass Toggle

**Date:** 2026-06-03
**Module:** `:agent` (+ one webview component)
**Status:** Design approved, pending spec review

## Problem

The agent's file-based memory lives at `~/.workflow-orchestrator/{proj}/agent/memory/`
and is written via the generic `create_file` / `edit_file` / `delete_file` tools. Those
tools are already "session-approvable" (`ApprovalPolicy.SESSION_APPROVABLE`), so a memory
write prompts once and is then silenced for the rest of the session by "Allow for session"
— including silencing it when the user only meant to allow normal project-file edits.

The user wants memory writes treated more strictly: **every** memory create / update / delete
should require approval (per-invocation, like `run_command`), with a single settings toggle
that bypasses the gate entirely when the user trusts the agent's memory edits.

A related, already-shipped piece: the chat tool card already labels memory operations
("Creating / Reading / Updating / Deleting memory", "Curating memory index") via
`agent/webview/src/lib/describeMemoryOp.ts` + `ToolCallView.tsx`. That labeling is kept
as-is — no change.

## Goals

- Force **per-invocation** approval for memory create / update / delete (no "Allow for
  session"), independent of any prior session approval on the same tool.
- Add a `autoApproveMemoryOperations` setting (default `false`) that bypasses the gate for
  memory writes when enabled.
- Surface the memory verb ("Updating memory · {topic}") in the approval card so the prompt
  says what is being approved.

## Non-goals

- No change to the existing memory tool-card labeling (Part A is already done).
- No new memory tool; memory uses the generic write tools.
- No change to approval behavior for non-memory files.
- Reads (`read_file`) never require approval — unchanged.

## Decisions (locked)

| Decision | Choice |
|---|---|
| Verb labeling (Part A) | Keep current `describeMemoryOp` mapping — no change |
| Approval model | Per-invocation always for memory create/edit/delete; no "Allow for session" |
| Bypass | `autoApproveMemoryOperations` toggle ON → skip the gate entirely for memory writes |
| Setting name / default | `autoApproveMemoryOperations`, default `false` (approval required) |
| Approval-card label | Show the memory verb via `describeMemoryOp` (Section 5 included) |
| Delegated sessions | Inherit the toggle via the single `AgentLoop` construction site |

## Architecture

### Memory-write detection (new, pure)

`agent/src/main/kotlin/com/workflow/orchestrator/agent/memory/MemoryWriteClassifier.kt`:

```kotlin
object MemoryWriteClassifier {
    private val MEMORY_WRITE_TOOLS = setOf("create_file", "edit_file", "delete_file")

    /** True iff [toolName] is a memory WRITE: a write tool whose path arg resolves under [memoryDir]. */
    fun isMemoryWrite(toolName: String, argsJson: String, memoryDir: String?): Boolean
}
```

- Returns `false` immediately if `toolName !in MEMORY_WRITE_TOOLS` or `memoryDir == null`.
- Parses `path` (fallback `file_path`) from `argsJson`.
- Normalizes both the path and `memoryDir` to absolute, normalized `java.nio.file.Path`s and
  returns whether the path starts with the memory dir. Non-absolute / unparseable paths →
  `false` (memory writes use the absolute dir the agent is told about, so this is safe).
- `MEMORY.md` (index curation) is a write under the memory dir → counts as a memory write.
- Pure and dependency-free → fully unit-testable.

### Approval override in `AgentLoop`

At the existing approval check (`AgentLoop.kt`, ~line 1873), before policy computation:

```kotlin
val isMemoryWrite = MemoryWriteClassifier.isMemoryWrite(toolName, call.function.arguments, memoryDirPath)

if (isMemoryWrite && autoApproveMemoryOperations) {
    // Toggle ON → bypass the gate entirely; fall through to execution.
} else {
    // Memory writes: FORCE per-invocation (no allow-for-session) and IGNORE any prior
    // session approval, so a generic "Allow for session" on edit_file cannot silence
    // memory writes. Non-memory tools keep their existing policy + session-approval path.
    val policy = if (isMemoryWrite) ApprovalPolicy(requiresApproval = true, allowSessionApproval = false)
                 else ApprovalPolicy.forTool(toolName)
    val sessionApproved = !isMemoryWrite && sessionApprovalStore.isApproved(toolName)
    if (policy.requiresApproval && approvalGate != null && !sessionApproved) {
        // ...existing gate invocation + ApprovalResult handling, unchanged...
        // ALLOWED_FOR_SESSION still only writes sessionApprovalStore when
        // policy.allowSessionApproval is true — which is false for memory writes.
    }
}
```

Reuses the existing `memoryDirPath: String?` constructor param (added for the completion-gate
work) — so Part B adds exactly **one** new constructor flag, `autoApproveMemoryOperations`.
Passing `allowSessionApproval = false` to the gate makes the approval card hide its
"Allow for session" button (same mechanism `run_command` already relies on).

### Settings + wiring

- `AgentSettings.State`: `var autoApproveMemoryOperations by property(false)`.
- `AgentAdvancedConfigurable` → existing **"Memory"** group: a second checkbox
  *"Auto-approve memory writes (skip the approval prompt for create/update/delete in memory)"*,
  `bindSelected(agentSettings.state::autoApproveMemoryOperations)`.
- `AgentService`: pass `autoApproveMemoryOperations = agentSettings.state.autoApproveMemoryOperations`
  at the single `AgentLoop` construction site (delegated sessions funnel through `executeTask`,
  so they inherit it — same parity as `feedbackEnabled` / `proactiveMemoryUpdatesEnabled`).

### Approval-card memory label (Section 5)

In the webview approval card component (`ApprovalView.tsx` / `approval-card.tsx`), when the
approval is for a memory write, call the existing `describeMemoryOp(toolName, path)` and render
its `{ icon, title }` (e.g. "Updating memory · user_prefs") in place of the bare tool name.
Falls back to the current rendering when `describeMemoryOp` returns `null`. The card already
receives `toolName` and the args/diff, so no new bridge data is required.

## Threading

No new threading concerns. `MemoryWriteClassifier.isMemoryWrite` is a pure call inside the
existing approval section of `AgentLoop.run()`. The gate round-trip is unchanged.

## Edge cases

- **Prior session approval on `edit_file`:** ignored for memory writes (forced per-invocation).
- **`autoApproveMemoryOperations` ON:** memory writes skip the gate; non-memory writes keep
  their normal approval behavior.
- **Relative / unparseable path:** treated as non-memory → normal approval path (safe default).
- **Reads:** `read_file` is not in `MEMORY_WRITE_TOOLS` and is not an approval tool → never gated.
- **Denied memory write:** identical to today's `ApprovalResult.DENIED` handling (tool skipped,
  error reported to the model).

## Testing

- `MemoryWriteClassifierTest` (pure): write tool + memory path → true (POSIX & Windows);
  `read_file` → false; non-memory path → false; `MEMORY.md` under memory dir → true;
  `memoryDir == null` → false; missing path arg → false.
- `AgentLoopMemoryApprovalTest` (behavioral, real `AgentLoop` + fake capturing `approvalGate`):
  (a) a memory `edit_file` invokes the gate **even when** `edit_file` is already in
  `sessionApprovalStore`; (b) `autoApproveMemoryOperations = true` → gate **not** invoked;
  (c) the gate receives `allowSessionApproval = false` for memory writes.
- Webview: a focused test that the approval card renders the memory verb label for a memory
  write and the plain tool name otherwise.
- Docs: update `agent/CLAUDE.md` "Tool Approval" section.

## Files touched

| File | Change |
|---|---|
| `agent/.../memory/MemoryWriteClassifier.kt` | NEW — pure detector |
| `agent/.../loop/AgentLoop.kt` | Approval override + new `autoApproveMemoryOperations` ctor param |
| `agent/.../settings/AgentSettings.kt` | Add `autoApproveMemoryOperations` |
| `agent/.../settings/AgentAdvancedConfigurable.kt` | Memory-group checkbox |
| `agent/.../AgentService.kt` | Pass the flag at the `AgentLoop` site |
| `agent/webview/.../ApprovalView.tsx` (or `approval-card.tsx`) | Memory verb label via `describeMemoryOp` |
| `agent/CLAUDE.md` | Document memory-write approval + setting |
| `agent/.../test/.../memory/MemoryWriteClassifierTest.kt` | NEW |
| `agent/.../test/.../loop/AgentLoopMemoryApprovalTest.kt` | NEW |
| `agent/webview/.../__tests__/...approval...test.tsx` | approval-card label test |

## Open questions

None — all design forks resolved.

# Checkpoint/Revert Architecture — Design Spec

**Date:** 2026-04-03
**Status:** Approved
**Research:** Enterprise comparison of 13+ tools (Claude Code, Cursor, Cline, Aider, Codex CLI, OpenHands, Windsurf, LangGraph, Temporal, Devon, Anthropic SDK, Google ADK)

## Problem Statement

The LLM agent can make mistakes while editing files. When it tries to revert:
1. **LLM calls `rollback_changes`** — only updates LLM context, UI still shows old changes
2. **User clicks "Undo"** — no `RollbackEntry` recorded, no UI sync
3. **LLM falls back to `git checkout`/`git reset`** — ChangeLedger and UI completely unaware

## Design Decisions

### 1. Event-Sourced Rollback (vs mutation)
`RollbackEntry` is a new append-only event type in `ChangeLedger`, alongside `ChangeEntry`. Stats computation filters out entries whose IDs appear in rollback events. Aligns with existing EventStore architecture.

### 2. UI Shows Audit Trail (option 2)
Old changes remain visible but greyed out with strikethrough + "reverted" badge. A `RollbackCard` event card appears in the chat timeline. Preserves the audit trail for understanding LLM behavior.

### 3. Internal Git Fallback (option 1)
`AgentRollbackManager.rollbackToCheckpoint()` tries LocalHistory first, falls back to `git checkout HEAD -- <file>` per touched file. Transparent to callers — they receive `RollbackResult` with `mechanism` field.

### 4. Block Destructive Git Commands (option 2)
`RunCommandTool` already blocks unsafe git subcommands. Error messages now guide toward `rollback_changes` / `revert_file` tools. This closes the loophole of LLM improvising with raw git.

### 5. Full Checkpoint + Per-File Revert (option 3)
`rollback_changes` for full checkpoint revert, new `revert_file` tool for single-file surgical revert. Both use the same `RollbackEntry` recording and UI notification flow.

## Architecture

### Data Types

```kotlin
@Serializable
data class RollbackEntry(
    val id: String,
    val timestamp: Long,
    val checkpointId: String,
    val description: String,
    val source: RollbackSource,       // LLM_TOOL, USER_BUTTON, USER_UNDO
    val mechanism: RollbackMechanism, // LOCAL_HISTORY, GIT_FALLBACK
    val affectedFiles: List<String>,
    val rolledBackEntryIds: List<String>,
    val scope: RollbackScope          // FULL_CHECKPOINT, SINGLE_FILE
)

data class RollbackResult(
    val success: Boolean,
    val mechanism: RollbackMechanism,
    val affectedFiles: List<String>,
    val failedFiles: List<String> = emptyList(),
    val error: String? = null
)
```

### Three Revert Paths → One Flow

```
LLM tool (rollback_changes / revert_file)
User revert button (checkpoint timeline)    → record RollbackEntry
User undo button (footer)                     → update context anchor
                                              → pushEditStatsToUi()
                                              → pushRollbackToUi()
```

### UI Notification

```
Kotlin: pushRollbackToUi(entry)
  → AgentCefPanel.notifyRollback(json)
    → callJs("notifyRollback(...)")
      → chatStore.applyRollback(rollback)
        → marks tool call messages as rolledBack
        → appends RollbackCard to timeline
        → editStats updated with effective totals
```

### Rollback Manager Fallback Chain

```
rollbackToCheckpoint(id):
  1. LocalHistory label.revert()   → success? return LOCAL_HISTORY
  2. git checkout HEAD -- <file>   → per touched file → return GIT_FALLBACK
  3. Delete created files          → tracked separately via trackFileCreation()

rollbackFile(path):
  1. If created file → delete
  2. git checkout HEAD -- <file>   → return GIT_FALLBACK
```

## Research-Backed Validation

| Our Decision | Enterprise Precedent |
|---|---|
| Event-sourced rollback | OpenHands: append-only event log, battle-tested |
| LocalHistory + git fallback | Claude Code: file backups + shadow git; Aider: real git commits |
| LLM can trigger revert | **Novel** — no other tool allows this |
| Block destructive git | **Novel** — no other tool does this |
| Per-file revert | Cursor: per-agent undo; Cline: per-hunk accept/reject |
| Audit trail preservation | Universal — all tools preserve history |

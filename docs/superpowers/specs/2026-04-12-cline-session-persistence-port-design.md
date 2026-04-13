# Cline Session Persistence Port — Design Spec

**Date:** 2026-04-12
**Branch:** `feature/session-persistence-cline-port`
**Source reference:** `docs/research/2026-04-12-cline-session-persistence-source-analysis.md`

## Goal

Faithful port of Cline's session save/resume architecture so users can close IDE and resume any conversation as if they never left. Every chat bubble, tool card, plan step, approval gate, and artifact state persists to disk and rehydrates identically on resume.

## Architecture

Two JSON files per session (Cline pattern) + global index:

```
~/.workflow-orchestrator/{proj}/agent/
├── sessions.json                          # List<HistoryItem> global index
└── sessions/
    └── {sessionId}/
        ├── api_conversation_history.json  # List<ApiMessage> (LLM view)
        ├── ui_messages.json               # List<UiMessage> (chat bubble view)
        ├── task_metadata.json             # files_in_context, model_usage
        ├── context_history.json           # ContextManager truncation state
        ├── .lock                          # java.nio FileLock
        └── checkpoints/                   # existing checkpoint system
```

## Data Model

### UiMessage (extends Cline's ClineMessage)

- `ts`, `type` (ASK/SAY), `ask` enum, `say` enum, `text`, `reasoning`, `images`, `files`
- `partial: Boolean` — true during streaming, flipped to false on stream end or abort
- `conversationHistoryIndex: Int?` — cross-link to api_history position at add-time
- `conversationHistoryDeletedRange: Pair<Int,Int>?` — truncation tracking
- `lastCheckpointHash`, `modelInfo`
- Extensions: `artifactId`, `planData`, `approvalData`, `questionData`, `subagentData`

### UiAsk (15 discriminants)

Cline-ported: RESUME_TASK, RESUME_COMPLETED_TASK, TOOL, COMMAND, FOLLOWUP, COMPLETION_RESULT
Our extensions: PLAN_APPROVE, PLAN_MODE_RESPOND, ACT_MODE_RESPOND, ARTIFACT_RENDER, QUESTION_WIZARD, APPROVAL_GATE, SUBAGENT_PERMISSION

### UiSay (17 discriminants)

Cline-ported: API_REQ_STARTED, API_REQ_FINISHED, TEXT, REASONING, TOOL, CHECKPOINT_CREATED, ERROR
Our extensions: PLAN_UPDATE, ARTIFACT_RESULT, SUBAGENT_STARTED, SUBAGENT_PROGRESS, SUBAGENT_COMPLETED, STEERING_RECEIVED, CONTEXT_COMPRESSED, MEMORY_SAVED, ROLLBACK_PERFORMED

### ApiMessage (Cline's ClineStorageMessage)

- `role` (USER/ASSISTANT), `content: List<ContentBlock>`, `ts`, `modelInfo`, `metrics`
- ContentBlock sealed: Text, ToolUse, ToolResult, Image

### HistoryItem (global index entry, verbatim Cline)

- `id`, `ts`, `task`, `tokensIn`, `tokensOut`, `totalCost`, `size`, `cwdOnTaskInitialization`
- `conversationHistoryDeletedRange`, `isFavorited`, `checkpointManagerErrorMessage`, `modelId`

## MessageStateHandler (replaces SessionStore)

- Owns in-memory `uiMessages: MutableList<UiMessage>` and `apiHistory: MutableList<ApiMessage>`
- All mutations under `kotlinx.coroutines.sync.Mutex` (ports Cline's `p-mutex`)
- Every `add/update/delete` atomically writes both JSON files + updates `sessions.json`
- Atomic write: write to `{file}.tmp.{ts}.{random}` → `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)`
- `addToClineMessages()` sets `conversationHistoryIndex = apiHistory.size - 1`

## Save Cadence (per-chunk, like Cline)

- Streaming chunks → `updateClineMessage(lastIdx, {partial:true, text+=chunk})` → disk write
- Stream end → `updateClineMessage(lastIdx, {partial:false})` → disk write
- Assistant turn complete → `addToApiConversationHistory(assistantMsg)` → disk write
- Tool result → `addToApiConversationHistory(toolResultMsg)` → disk write + `addToClineMessages(toolCard)`
- Plan/artifact/approval state change → `updateClineMessage(idx, newData)` → disk write

## Mid-Stream Interruption (abortStream, verbatim Cline)

1. Flip last partial message's `partial = false`
2. Append synthetic assistant turn: `text + "\n\n[Response interrupted by user]"`
3. Finalize `api_req_started` cost display
4. Save both files

Never replay an in-flight tool call. Never re-prompt with pending tool state.

## Resume Flow (resumeFromHistory, verbatim Cline)

1. Load `ui_messages.json` → trim trailing `resume_task`/`resume_completed_task` rows
2. Trim cost-less `api_req_started` (abandoned, never produced cost)
3. Load `api_conversation_history.json`
4. Check last api message role:
   - `ASSISTANT` → clean case, start new user turn
   - `USER` → pop into `modifiedOldUserContent` (interrupted mid-submission)
5. `postStateToWebview(uiMessages)` → webview rehydrates every bubble
6. Show `resume_task` ask (or `resume_completed_task` if last was completion)
7. Wait for user click/input
8. Build `taskResumption` preamble: "[Task was interrupted X minutes ago. cwd: /... ]" + user reply
9. `initiateTaskLoop(newUserContent)`

## History List UX

- `sessions.json` populates the history panel with HistoryItem cards
- Click → `Controller.showTaskWithId(id)` → full resume flow above
- AgentStartupActivity remains as a convenience notification but no longer required

## Per-Session FileLock

- `java.nio.channels.FileLock` on `.lock` file in session dir
- Acquired in `initTask`, released in abort/complete `finally`
- Prevents two IDE instances clobbering the same session

## ContextManager Adaptation

- Reads from `apiHistory` array (not JSONL file)
- Truncation = `overwriteApiConversationHistory(truncatedList)` + set `conversationHistoryDeletedRange`
- LLM summarization produces summary message, replaces truncated range
- Full history preserved in `ui_messages.json` (UI always shows everything)

## Files Changed

| File | Action | Notes |
|---|---|---|
| `session/SessionStore.kt` | Retire | Replaced by MessageStateHandler |
| `session/Session.kt` | Replace | Thin DTO matching HistoryItem |
| `session/MessageStateHandler.kt` | New | Core persistence class |
| `session/UiMessage.kt` | New | ClineMessage equivalent + enums |
| `session/ApiMessage.kt` | New | ClineStorageMessage equivalent |
| `session/HistoryItem.kt` | New | Global index entry |
| `session/AtomicFileWriter.kt` | New | Write-then-rename helper |
| `session/SessionLock.kt` | New | FileLock wrapper |
| `session/ResumeHelper.kt` | New | Trim + preamble builder |
| `loop/AgentLoop.kt` | Modify | Stream chunks → MSHandler |
| `loop/ContextManager.kt` | Modify | Reads from api_history array |
| `AgentService.kt` | Modify | Resume rewritten to Cline flow |
| `ui/AgentController.kt` | Modify | postStateToWebview pushes ui_messages |
| `ui/HistoryPanel.kt` | Modify | Reads sessions.json |
| `listeners/AgentStartupActivity.kt` | Neuter | Keep notification, remove as primary |
| `memory/ConversationRecall.kt` | Modify | Reads api_history files |
| `webview/stores/chatStore.ts` | Modify | Hydrates from ui_messages array |
| Tests (new) | New | MSHandler, ResumeHelper, abort |
| Tests (retire) | Retire | SessionStoreTest |

## Estimated Scope

~1800 LoC added, ~1200 removed. Net +600. 15 files. 2-3 weeks.

---
name: History tab completely broken — waiting for worktree merge
description: 7 breakages identified in History tab (UI wiring, backend resume, layout, refresh). Fix deferred to avoid rebase conflicts with active worktree touching same files.
type: project
originSessionId: d5e95673-4515-4b32-ae6e-73195e6c52be
---
History tab is completely non-functional. Fix deferred (2026-04-11) because another worktree is actively modifying overlapping files (AgentService.kt, AgentController.kt, AgentCefPanel.kt, AgentDashboardPanel.kt).

**Why:** Fixing now would touch 7 Kotlin files in :agent, causing rebase nightmare when the other worktree merges.

**How to apply:** After the active worktree merges to main, pick up this fix. Check if the merge already addressed any of these issues before starting.

## 7 Breakages Found

1. **Resume callback never wired** — `HistoryTabProvider` creates `HistoryPanel` but never sets `onResumeSession`. Comment references non-existent `AgentToolWindowFactory`. Fix: wire via `AgentControllerRegistry`.

2. **Completed sessions rejected** — `AgentService.resumeSession()` returns null for `SessionStatus.COMPLETED`. Most sessions are completed. Need a `continueSession()` path.

3. **Resume missing 11+ callbacks** — `AgentController.resumeSession()` only passes 4 of 15+ callbacks. Missing: approvalGate, steeringQueue, userInputChannel, onPlanResponse, onPlanModeToggled, onCheckpointSaved, onSubagentProgress, onTokenUpdate, onDebugLog, onRetry, onModelSwitch.

4. **No conversation replay into webview** — `dashboard.reset()` clears chat, but history is never replayed. JS bridge has `loadChatSnapshot()` (jcef-bridge.ts:347) but Kotlin never calls it. Need to expose through AgentCefPanel → AgentDashboardPanel → AgentController.

5. **Empty state destroys layout** — `HistoryPanel.refresh()` calls `removeAll()` when empty, permanently destroying the scroll pane.

6. **No auto-refresh** — `refresh()` called once in `init{}`. List never updates.

7. **Startup notification has no action button** — Shows "Open the Agent tab to resume" but no clickable action.

## Files to Touch (7 Kotlin, 0 frontend)

- `HistoryPanel.kt` — rewrite (~150 lines)
- `HistoryTabProvider.kt` — wire callback (~10 lines)
- `AgentController.kt` — pass all callbacks + replay history (~50 lines)
- `AgentService.kt` — add continueSession() (~30 lines)
- `AgentCefPanel.kt` — expose loadChatSnapshot() (~5 lines)
- `AgentDashboardPanel.kt` — delegate loadChatSnapshot() (~5 lines)
- `AgentStartupActivity.kt` — add notification action (~10 lines)

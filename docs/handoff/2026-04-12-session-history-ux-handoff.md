# Context Handoff: Session History UX

**Date:** 2026-04-12
**From:** Session persistence port session
**To:** New session for History tab UX redesign
**Branch:** `feature/session-persistence-cline-port`
**Worktree:** `.worktrees/session-persistence`

---

## What was done

Faithful port of Cline's two-file session persistence architecture. The **entire save/resume backend** is complete and reviewed (verdict: SHIP). 13 commits, 51+ tests passing.

### New files (session package)

| File | Purpose |
|---|---|
| `session/UiMessage.kt` | UI message data class + 15 `UiAsk` + 17 `UiSay` enums + extension data (PlanCardData, ApprovalGateData, SubagentCardData, QuestionWizardData) |
| `session/ApiMessage.kt` | LLM message data class + ContentBlock sealed interface + lossless `toChatMessage()`/`toApiMessage()` bidirectional conversion |
| `session/HistoryItem.kt` | Global session index entry (id, ts, task, tokensIn/Out, cost, modelId, isFavorited) |
| `session/MessageStateHandler.kt` | Core persistence: owns uiMessages + apiHistory arrays, coroutine Mutex, per-change atomic write, global sessions.json index |
| `session/ResumeHelper.kt` | Trim trailing resume messages, pop trailing user message, build taskResumption preamble, formatTimeAgo |
| `session/AtomicFileWriter.kt` | Write-then-rename via `Files.move(ATOMIC_MOVE)` |
| `session/SessionLock.kt` | Per-session `java.nio.channels.FileLock` |
| `session/SessionMigrator.kt` | Converts old JSONL sessions to new format on startup |

### Modified files

| File | What changed |
|---|---|
| `loop/AgentLoop.kt` | Streams persist per-chunk (`partial: true`), tool results to both files, `abortStream()` for interruption, `api_req_started` cost tracking |
| `AgentService.kt` | `executeTask` creates MessageStateHandler, `resumeSession` rewritten to Cline flow (load, trim, pop, preamble, lock, rehydrate webview) |
| `loop/ContextManager.kt` | `onHistoryOverwrite` callback for compaction persistence |
| `ui/AgentController.kt` | `postStateToWebview(uiMessages)` serializes and pushes to JCEF bridge |
| `ui/HistoryPanel.kt` | Reads `sessions.json` via `MessageStateHandler.loadGlobalIndex()`, click-to-resume |
| `webview/src/stores/chatStore.ts` | `hydrateFromUiMessages()` action + `convertUiMessageToStoreMessage()` mapper |
| `webview/src/bridge/jcef-bridge.ts` | `_loadSessionState` bridge function registered |
| `webview/src/bridge/types.ts` | `UiMessage` TypeScript interface + all discriminant types |

### Deleted files

- `session/SessionStore.kt` — replaced by MessageStateHandler
- `session/SessionStoreTest.kt` — replaced by MessageStateHandlerTest

### Disk layout

```
~/.workflow-orchestrator/{proj}/agent/
├── sessions.json                          # List<HistoryItem> global index
└── sessions/
    └── {sessionId}/
        ├── api_conversation_history.json  # List<ApiMessage> (LLM view)
        ├── ui_messages.json               # List<UiMessage> (chat bubble view)
        ├── .lock                          # Per-session FileLock
        └── checkpoints/                   # Named checkpoints
```

---

## What needs to be done: History UX

The persistence layer is complete but the **user-facing history UX** is still the old Swing-based `HistoryPanel` in a separate 7th tool window tab. This needs to be redesigned to match Cline's integrated chat-panel experience.

### Current state (what exists)

- `HistoryPanel.kt` — Swing JPanel, reads `sessions.json`, renders JBList of session cards, click triggers `resumeSession()`
- `HistoryTabProvider.kt` — registers the panel as tab `order: 6` in the Workflow tool window
- The agent chat UI is a JCEF webview (`AgentCefPanel` / `AgentDashboardPanel`) — completely separate from the Swing history panel
- There is NO history view inside the JCEF chat webview

### What Cline does (the target UX)

In Cline, the history list is **inside the same webview panel as the chat**:
1. When no task is active, the webview shows a **task history list** (cards with title, time ago, cost, model)
2. Clicking a card triggers `showTaskWithId(id)` → full resume flow → chat rehydrates
3. The "+" button starts a new task (clears current chat, shows history)
4. There is NO separate tab/panel for history — it's all in one view

### Key decisions for the new session

1. **Where does history live?** Inside the JCEF chat webview (matching Cline) vs keeping the separate Swing tab
2. **Session list component** — React component in `agent/webview/src/` showing HistoryItem cards
3. **State transitions** — How does the webview switch between "history list view" and "active chat view"?
4. **New session button** — "+" in the chat toolbar that saves current session and shows history
5. **Delete/favorite** — Cline supports favoriting and deleting sessions from the list
6. **Search** — Cline has a search bar over history

### APIs already available for the webview

**Kotlin → JS (already wired):**
- `_loadSessionState(uiMessagesJson)` — rehydrates chat from full ui_messages array
- All existing bridge functions in `jcef-bridge.ts`

**Kotlin → JS (needs adding for history):**
- `_loadSessionHistory(historyItemsJson)` — push HistoryItem[] to webview for rendering
- `_clearChat()` — switch webview to history view

**JS → Kotlin (needs adding):**
- `kotlinBridge.showSession(sessionId)` — user clicked a history card → trigger resume
- `kotlinBridge.deleteSession(sessionId)` — user deletes a session
- `kotlinBridge.toggleFavorite(sessionId)` — user favorites/unfavorites
- `kotlinBridge.startNewSession()` — user clicks "+" → save current, show history

**Data available via `MessageStateHandler`:**
- `MessageStateHandler.loadGlobalIndex(baseDir): List<HistoryItem>` — all sessions sorted by time
- `AgentService.resumeSession(sessionId, onUiMessagesLoaded=...)` — full resume with webview rehydration

### Key references

- Spec: `docs/superpowers/specs/2026-04-12-cline-session-persistence-port-design.md`
- Cline research: `docs/research/2026-04-12-cline-session-persistence-source-analysis.md`
- Plan: `docs/superpowers/plans/2026-04-12-cline-session-persistence-port.md`
- Agent CLAUDE.md: `agent/CLAUDE.md` (updated with new architecture)
- Webview architecture: `agent/webview/` (React 19 + Zustand + Tailwind + Vite)
- Bridge protocol: `agent/webview/src/bridge/jcef-bridge.ts` (42 Kotlin→JS + 26 JS→Kotlin functions)
- Chat store: `agent/webview/src/stores/chatStore.ts` (1445 lines, has `hydrateFromUiMessages`)
- Types: `agent/webview/src/bridge/types.ts` (has `UiMessage` interface)

### Review findings relevant to this work

- **I7 from review:** `convertUiMessageToStoreMessage` doesn't render `planData`, `approvalData`, `questionData` as rich cards yet — just text. These need card components.
- **S5 from review:** The 7th History tab deviates from the 6-tab UX constraint. If history moves into the chat webview, `HistoryTabProvider` can be removed.
- **I6 from review:** No `reconstructTaskHistory` fallback if sessions.json is corrupted — consider adding as part of history panel error states.

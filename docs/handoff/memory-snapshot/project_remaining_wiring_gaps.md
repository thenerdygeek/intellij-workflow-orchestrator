---
name: Remaining wiring gaps after pre-test fixes
description: UI callbacks and features not yet wired after the pre-smoke-test fix pass. Come back to these after initial testing.
type: project
---

Gaps NOT fixed in the pre-smoke-test pass (deferred to post-testing):

## Unwired Callbacks
- `onToolToggled` — user enable/disable tools from Tools panel. Callback exists in AgentCefPanel but no handler in AgentController.
- `onCancelSteering` — cancel mid-execution user steering. Not wired.
- `onInteractiveHtmlMessage` — interactive HTML responses from tools. Not wired.
- `onUndoRequested` — undo last tool call. Stub (LOG.info only). Needs undo manager.
- `onViewTraceRequested` — view execution timeline. Stub. Needs trace UI.
- `onToggleRalphLoop` — Ralph Loop was deleted. Intentional no-op.

## Missing UI Features
- **Diff hunk accept/reject** — Dashboard has `onAcceptDiffHunk`/`onRejectDiffHunk` callbacks. Diffs display but user can't accept/reject individual hunks. Needs hunk parser + rollback logic.
- **Real-time token budget** — `onTokenUpdate` callback exists in AgentLoop but never fires to UI during execution. Only final cost shown.
- **Hook execution feedback** — Hooks run silently. User doesn't see which hooks fired or their results.
- **Tool search activation feedback** — When deferred tools are loaded via tool_search, no UI notification.
- **Session execution timeline** — No per-tool timing trace for debugging.

## Missing Observability (lower priority)
- Metrics/telemetry collection (iteration counts, tool frequency, error rates)
- Session state export for offline debugging
- Token estimate vs API-reported divergence tracking

## React Webview Risk
- 57+ JS functions called via callJs() — webview source not in repo (only compiled dist/)
- Any mismatch between Kotlin callJs() and webview JS = silent failure
- Need to verify webview implements all expected functions

# :automation Module

Docker tag staging, automation suite queue management, and deployment triggering.

## Architecture

Tag validation flow removed — Trigger Now does not pre-validate tags against any Docker registry; Bamboo handles missing-tag failures during the automation run.

- `TagBuilderService` — builds `dockerTagsAsJson` payload (service-to-tag mappings)
- `QueueService` — smart queue with position tracking, wait time estimation, auto-trigger. Uses platform-injected `cs: CoroutineScope`; pollers explicitly `cs.launch(Dispatchers.IO)` (HTTP/SQLite I/O, see `:core` "Service & threading conventions").
- `DriftDetectorService` — no-op after registry calls removed; `isRegistryConfigured()` always returns false
- `TagHistoryService` — persists active queue entries for crash recovery (queue restart)
- `AutomationSettingsService` — suite plan keys and configuration
- `QueueRecoveryStartupActivity` — recovers queue state on IDE restart
- `BaselineCacheService` — project-level cache of the last computed `BaselineLoadResult`
  per suite plan key. In-memory `Map<String, CachedSuiteEntry>` plus on-disk JSON at
  `~/.workflow-orchestrator/{slug}-{sha6}/automation/baseline-cache.json` (atomic
  `.tmp` + `ATOMIC_MOVE`, coroutine `Mutex`-guarded). Tab-open reads cache for
  instant render then reconciles with Bamboo in the background; suite-switch in
  the dropdown does NOT trigger a scan (sticky baseline); the new Refresh button
  in `AutomationPanel`'s status row is the cache-bust signal. Cache survives IDE
  restart. No TTL — terminal-build data is immutable modulo stage re-runs, which
  Refresh handles. Spec: `docs/superpowers/specs/2026-05-11-automation-baseline-cache-design.md`.

## UI

- `AutomationPanel` — main panel with tag staging + queue + monitor sub-panels
- `TagStagingPanel` — service table + tag selector + JSON preview
- `QueueStatusPanel` — read-only status indicator. Mirrors the user's `MonitorPanel` selection (PR 8 #4) via `setSelection(entryId)`; falls back to the most-actionable live entry when nothing is selected. The Cancel button was removed in PR 8 — Cancel/Remove now live on the per-row detail panel where the target is unambiguous.
- `MonitorPanel` — list+detail of every queue entry, including terminal ones. Filter chips (All / Queued / Running / Failed / Completed) with `CANCELLED` bucketed under Failed. Sorted latest-first by `enqueuedAt`. Detail header shows **Cancel** for live entries and **Remove** for terminal entries (calls `QueueService.dismiss`). Exposes `onSelectionChanged: (RunEntry?) -> Unit`.
- `AutomationStatusBarWidgetFactory` — queue indicator in status bar
- **UI Overhaul:** Monospace docker tags, outline run status badges, uppercase section headers. RunListCellRenderer uses cached/pre-built components for performance.

## Monitor lifecycle (PR 8)

Terminal entries (`COMPLETED` / `FAILED` / `FAILED_TO_TRIGGER` / `CANCELLED` / `TAG_INVALID`) are no longer auto-pruned from `QueueService._stateFlow`. They persist in the Monitor list until the user clicks **Remove** (which calls `QueueService.dismiss`). `cancel()` now transitions to `CANCELLED` and keeps the row; only `dismiss()` removes. The `getActiveEntries()` and `getQueuePositionForSuite()` accessors filter out terminal rows so callers like the depth check and the status-bar widget retain "active = live" semantics. The polling loop stops when there are no live entries (terminal-only counts as idle). Across IDE restarts, terminal entries are dropped by `TagHistoryService.getActiveQueueEntries()` — i.e. the persistence layer is still session-scoped.

`QueueEntryStatus` gains a separate `FAILED` value distinct from the `COMPLETED` (success) status; `handleRunningOrQueued` picks one or the other based on Bamboo's `Successful`/`Failed` state. `Unknown`/`NotBuilt` is treated as `COMPLETED` to terminate polling.

# :automation Module

Docker tag staging, automation suite queue management, and deployment triggering.

## Architecture

Tag validation flow removed — Trigger Now does not pre-validate tags against any Docker registry; Bamboo handles missing-tag failures during the automation run.

- `TagBuilderService` — builds `dockerTagsAsJson` payload (service-to-tag mappings) and detects the current build's docker tag. **Docker-tag detection is per-job and order-independent (2026-05-27):** the "Unique Docker Tag" marker is emitted by ONE job, but Bamboo's REST returns a build's jobs in an *unstable* order (the probe returned the same plan's jobs in different orders across builds), so detection must not assume the tag is in the first job. `extractDockerTagFromLog` scans the whole concatenated log up to `MAX_SCAN_CHARS` (4 MB) — `DOCKER_TAG_REGEX.find` short-circuits at the first match, so a present tag is found wherever it sits; the cap only bounds the no-match scan (preserving the perf intent of audit finding F-5 without its head-window correctness bug). The cache-cold REST fallback (`detectDockerTag`) scans EACH job's log (`BuildResultData.stages[].jobs[].resultKey`) rather than the chain/plan-level log (which is empty/404 on this Bamboo), and reports the job key that carried the tag. Pinned by `DockerTagRegexBoundTest` + `TagDetectionPerJobTest`.
- `QueueService` — smart queue with position tracking, wait time estimation, auto-trigger. Uses platform-injected `cs: CoroutineScope`; pollers explicitly `cs.launch(Dispatchers.IO)` (HTTP/SQLite I/O, see `:core` "Service & threading conventions").
- `DriftDetectorService` — no-op after registry calls removed; `isRegistryConfigured()` always returns false
- `TagHistoryService` — persists active queue entries for crash recovery (queue restart). SQLite `queue_entries` table at `schema_version=2` (2026-05-25): v2 added the nullable `branch_key` column via an idempotent `migrateToV2IfNeeded` (PRAGMA `table_info` guard + `ALTER TABLE`, version bump only after the column is confirmed present), so the chosen plan branch survives an IDE restart.
- `AutomationSettingsService` — suite plan keys and configuration. `SuiteConfig.selectedBranch` (nullable) persists the per-suite trigger branch; `get/setSuiteSelectedBranch` mirror the default-stages helper pattern. Blank is normalised to null.
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

- `AutomationPanel` — main panel with tag staging + queue + monitor sub-panels. Header has a **branch selector** (`branchCombo`, replaced the old passive PR-branch label 2026-05-25): on suite-select/tab-open it loads `BambooService.getPlanBranches(planKey)` (guarded by the `loadGeneration` stale-token), offers a `"default"` (= master, `branchKey=null`) entry plus each plan branch (`PlanBranchData.key`), and restores the persisted per-suite selection (falls back to default if the branch was deleted in Bamboo). The selected `branchKey` flows into `QueueEntry.branchKey`; `QueueService.doTrigger` triggers `branchKey ?: suitePlanKey`. The list is sorted alphabetically with `"default"` (master) pinned first. The combo is a `ComboBoxWithWidePopup`: the closed control stays capped at `ComboBoxWidth.DEFAULT` (via `bindBoundedWidth`, which also installs a full-name hover tooltip keyed on the branch label — set the renderer BEFORE `bindBoundedWidth` so the tooltip wrapper survives), while the expanded popup floors at `ComboBoxWidth.WIDE` (`setMinLength`) and grows to fit longer names. A `ComboboxSpeedSearch` (keyed on `BranchComboItem.label`) makes the popup type-to-filter. **Disabled** Bamboo branches render greyed (`ColoredListCellRenderer` + `GRAYED_ATTRIBUTES`, "(disabled)" suffix) and stay selectable; selecting one pops a Yes/No dialog offering to enable it via `BambooService.enablePlanBranch` — on success the branch list re-renders enabled and stays selected, on cancel/failure the combo reverts to the committed `selectedBranchItem`. **Branch selection does NOT rescan the baseline** — the docker-tag baseline stays anchored to the master plan regardless of selected branch (`BaselineCacheService` is untouched, still keyed by `planKey`).
- `TagStagingPanel` — service table + tag selector + JSON preview
- `QueueStatusPanel` — read-only status indicator. Mirrors the user's `MonitorPanel` selection (PR 8 #4) via `setSelection(entryId)`; falls back to the most-actionable live entry when nothing is selected. The Cancel button was removed in PR 8 — Cancel/Remove now live on the per-row detail panel where the target is unambiguous.
- `MonitorPanel` — list+detail of every queue entry, including terminal ones. Filter chips (All / Queued / Running / Failed / Completed) with `CANCELLED` bucketed under Failed. Sorted latest-first by `enqueuedAt`. Detail header shows **Cancel** for live entries and **Remove** for terminal entries (calls `QueueService.dismiss`). Exposes `onSelectionChanged: (RunEntry?) -> Unit`.
- `AutomationStatusBarWidgetFactory` — queue indicator in status bar
- **UI Overhaul:** Monospace docker tags, outline run status badges, uppercase section headers. RunListCellRenderer uses cached/pre-built components for performance.

## Monitor lifecycle (PR 8)

Terminal entries (`COMPLETED` / `FAILED` / `FAILED_TO_TRIGGER` / `CANCELLED` / `TAG_INVALID`) are no longer auto-pruned from `QueueService._stateFlow`. They persist in the Monitor list until the user clicks **Remove** (which calls `QueueService.dismiss`). `cancel()` now transitions to `CANCELLED` and keeps the row; only `dismiss()` removes. The `getActiveEntries()` and `getQueuePositionForSuite()` accessors filter out terminal rows so callers like the depth check and the status-bar widget retain "active = live" semantics. The polling loop stops when there are no live entries (terminal-only counts as idle). Across IDE restarts, terminal entries are dropped by `TagHistoryService.getActiveQueueEntries()` — i.e. the persistence layer is still session-scoped.

`QueueEntryStatus` gains a separate `FAILED` value distinct from the `COMPLETED` (success) status; `handleRunningOrQueued` picks one or the other based on Bamboo's `Successful`/`Failed` state. `Unknown`/`NotBuilt` is treated as `COMPLETED` to terminate polling.

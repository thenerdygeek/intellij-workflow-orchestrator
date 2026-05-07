# :automation Module

Docker tag staging, automation suite queue management, and deployment triggering.

## Architecture

Tag validation flow removed — Trigger Now does not pre-validate tags against any Docker registry; Bamboo handles missing-tag failures during the automation run.

- `TagBuilderService` — builds `dockerTagsAsJson` payload (service-to-tag mappings)
- `QueueService` — smart queue with position tracking, wait time estimation, auto-trigger. Uses platform-injected `cs: CoroutineScope`; pollers explicitly `cs.launch(Dispatchers.IO)` (HTTP/SQLite I/O, see `:core` "Service & threading conventions").
- `DriftDetectorService` — no-op after registry calls removed; `isRegistryConfigured()` always returns false
- `ConflictDetectorService` — detects conflicting tag selections
- `TagHistoryService` — persists last 5 tag configurations
- `AutomationSettingsService` — suite plan keys and configuration
- `QueueRecoveryStartupActivity` — recovers queue state on IDE restart

## UI

- `AutomationPanel` — main panel with tag staging + queue + monitor sub-panels
- `TagStagingPanel` — service table + tag selector + JSON preview
- `QueueStatusPanel` — queue position, wait time, cancel/retry actions
- `MonitorPanel` — running suite status with polling
- `AutomationStatusBarWidgetFactory` — queue indicator in status bar
- **UI Overhaul:** Monospace docker tags, outline run status badges, uppercase section headers. RunListCellRenderer uses cached/pre-built components for performance.

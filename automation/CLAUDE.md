# :automation Module

Docker tag staging, automation suite queue management, and deployment validation.

## Nexus Docker Registry (Docker Registry v2 API)

Auth: `Authorization: Basic <base64(token:)>` — Nexus uses BASIC auth, NOT Bearer

Key endpoints:
- `GET /v2/` — test connection
- `GET /v2/{name}/tags/list` — list Docker tags for a repository
- `HEAD /v2/{name}/manifests/{tag}` — check tag exists (validation)

## Architecture

- `DockerRegistryClient` — HTTP client for Docker Registry v2
- `TagBuilderService` — builds `dockerTagsAsJson` payload (service-to-tag mappings)
- `QueueService` — smart queue with position tracking, wait time estimation, auto-trigger
- `DriftDetectorService` — detects tag drift across environments
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
- `TagValidationBeforeRunProvider` — validates tags before triggering builds

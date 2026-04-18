# Build→Automation Tab Event Connection

**Date:** 2026-04-14
**Branch:** fix/automation-handover-tabs

## Problem

The Automation tab detects docker tags by independently fetching builds from Bamboo via `BambooServiceImpl.getLatestBuild()`, which uses `resolveBranchPlanKey()` — a client-side branch name lookup against Bamboo's branches API. This fails when the branch isn't in the list (pagination, disabled plans, etc.), showing "No CI build found for branch 'X'" even though the Build tab displays the build correctly.

The Build tab uses `BambooApiClient.getLatestResult(planKey, branch)` which hits Bamboo's `/result/{plan}/branch/{name}/latest` endpoint — server-side resolution that works reliably.

## Solution

Connect the Automation tab to the Build tab via a new `BuildLogReady` event, following the same pattern the Quality tab uses with `BuildFinished`. The Build tab already monitors builds and can fetch logs — broadcast the log via an event so the Automation tab extracts the docker tag without making its own API calls.

## Design

### 1. New Event: `BuildLogReady`

Added to `WorkflowEvent.kt`:

```kotlin
data class BuildLogReady(
    val planKey: String,
    val buildNumber: Int,
    val resultKey: String,
    val status: BuildEventStatus,
    val logText: String
) : WorkflowEvent()
```

Carries the build status so consumers distinguish failed vs successful builds. `logText` is the raw build log.

### 2. BuildMonitorService Changes

After detecting a terminal build state in `pollOnce()`, fetch the build log and emit `BuildLogReady`:

- Emitted for **both SUCCESS and FAILED** builds (Automation needs to know about failures)
- Emitted on **first poll too** (unlike `BuildFinished` which skips first poll to avoid stale notifications) — this ensures the Automation tab gets the current state when it subscribes
- Only fetched once per build (guard: only when `statusChanged` or first poll detecting terminal)
- Log fetch failure → still emit with empty logText (consumer handles gracefully)

### 3. AutomationPanel Changes

Subscribe to `BuildLogReady` events in a coroutine scope on init:

```
BuildLogReady received →
  if status == FAILED → show "CI build failed (planKey #buildNumber)"
  if status == SUCCESS →
    extract docker tag from logText →
      if found → update tag in staging table + show success banner
      if not found → show "No 'Unique Docker Tag' in build log"
```

The existing `detectCurrentRepoTag()` remains as initial-load fallback (called during `onSuiteSelected`), but also gets a fix: use `BambooApiClient.getLatestResult(planKey, branch)` directly instead of going through `resolveBranchPlanKey`.

### 4. TagBuilderService Changes

Add a pure extraction method:

```kotlin
fun extractDockerTagFromLog(logText: String): String?
```

Reuses the existing `Unique Docker Tag\s*:\s*(.+)` regex + ANSI stripping logic, but accepts log text as input rather than fetching it.

### 5. AutomationModels Changes

Add `buildFailed()` factory to `TagDetectionResult`:

```kotlin
fun buildFailed(planKey: String, buildNumber: Int) = TagDetectionResult(
    detected = false, tag = null, buildKey = "$planKey-$buildNumber",
    reason = "CI build failed ($planKey #$buildNumber)"
)
```

### 6. Fix Initial Load Path

Fix `BambooServiceImpl.getLatestBuild()` to fall back to the direct `/branch/{name}/latest` URL when `resolveBranchPlanKey()` fails, aligning it with the approach that already works in `BuildMonitorService`.

### 7. Error States in Automation Tab

| Scenario | Banner Message | Color |
|----------|---------------|-------|
| Build succeeded + tag found | `✓ Docker tag: {tag} (from {resultKey})` | SUCCESS (green) |
| Build succeeded + no tag in log | `⚠ Build {resultKey} has no 'Unique Docker Tag' in log` | WARNING (amber) |
| Build failed | `✗ CI build failed ({planKey} #{buildNumber})` | ERROR (red) |
| Log fetch failed | `⚠ Could not fetch build log for {resultKey}` | WARNING (amber) |

## Files Changed

1. `core/events/WorkflowEvent.kt` — add `BuildLogReady`
2. `bamboo/service/BuildMonitorService.kt` — fetch log + emit `BuildLogReady` on terminal state
3. `automation/model/AutomationModels.kt` — add `buildFailed()`, `logFetchFailed()` factories
4. `automation/service/TagBuilderService.kt` — add `extractDockerTagFromLog()` pure function
5. `automation/ui/AutomationPanel.kt` — subscribe to `BuildLogReady`, update diagnostic banner
6. `bamboo/service/BambooServiceImpl.kt` — fix `getLatestBuild()` fallback
7. Tests for all changes

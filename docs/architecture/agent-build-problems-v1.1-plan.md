# Agent — `get_build_problems` V1.1: Gradle import + compile event capture

**Status:** Plan, ready to execute
**Branch:** `refactor/cleanup-perf-caching` (or fresh `feat/build-problems-v1.1`)
**Owner:** Subhankar
**Date:** 2026-04-28
**Predecessor:** `agent-build-problems-tool-plan.md` (V1, shipped)

## Background

V1 shipped Maven import problems via reflective `MavenProjectsManager.problems` snapshot read. V1.1 adds the two missing sources:

- **Gradle import errors** — "Could not resolve all dependencies for task X", repository auth failures, sync exceptions. These flow through `ExternalSystemTaskNotificationListener` events at import time but are **not retained** anywhere by default — once the import finishes (success or failure), the events are gone.
- **Compile errors** — `compileKotlin` / `compileJava` failures from the IDE's Build tool window. Flow through `BuildProgressListener` events, also not retained.

V1's `BuildProblemsService.getRecentBuildProblems()` already returns a list typed by `BuildSource` enum; V1.1 lights up the `GRADLE_IMPORT` and `COMPILE` cases without changing the public API.

## Goals

- Capture Gradle import + compile errors at event time and retain them in a ring buffer keyed by source.
- Surface them through the existing `BuildProblemsService` interface — zero callsite changes.
- Honour the V1 `BuildProblem` shape (path, description, type, severity, optional line/coords).

## Non-goals

- Capturing successful build output, warnings during normal compile, or every import event.
- Per-task / per-module granularity beyond what the event carries.
- Gradle daemon-side errors (those need a separate `GradleConnector` channel — out of scope).
- Replacing the IDE's own Build tool window — this is an LLM-readable index, not a UI.

## Architecture

```
:core
  services/BuildEventCaptureService.kt   ← @Service(PROJECT), startup activity
    ├─ subscribes to ExternalSystemTaskNotificationListener (Gradle import)
    ├─ subscribes to ProjectTaskListener / BuildProgressListener (compile)
    └─ retains last N events per source in a ring buffer (Map<BuildSource, ArrayDeque<BuildProblem>>)

  services/impl/BuildProblemsServiceImpl.kt   ← UPDATED
    └─ getRecentBuildProblems() now combines:
       ├─ Maven probe (V1, unchanged)
       ├─ BuildEventCaptureService.snapshot(GRADLE_IMPORT)
       └─ BuildEventCaptureService.snapshot(COMPILE)
```

**Why a separate capture service?** Gradle/compile errors are *event-time* — they arrive once and you have to be listening when they do. A snapshot-style probe (V1 Maven approach) cannot replay the event later. The capture service installs listeners at project startup and stores events for later read.

## Plan — 4 commits

### Commit 1 — Gradle import event capture

**New files:**
- `core/src/main/kotlin/com/workflow/orchestrator/core/services/BuildEventCaptureService.kt`
  ```kotlin
  interface BuildEventCaptureService {
      fun snapshot(source: BuildSource): List<BuildProblem>
      fun clear(source: BuildSource)
      companion object { fun getInstance(project: Project): BuildEventCaptureService = project.service() }
  }
  ```
- `core/src/main/kotlin/com/workflow/orchestrator/core/services/impl/BuildEventCaptureServiceImpl.kt`
  - `@Service(Service.Level.PROJECT)` with constructor-injected `cs: CoroutineScope` (Phase 4 convention).
  - Map `<BuildSource, ArrayDeque<BuildProblem>>` guarded by Mutex; ring-buffer size = 50 per source.
  - Subscribe to `ExternalSystemProgressNotificationManager` → on `onFailure(taskId, e: Exception)` for Gradle taskId, parse the exception and the most recent stderr buffer for typed errors.
  - **Parser:** regex set for known Gradle error shapes:
    - `"Could not resolve dependency: ([\w.\-]+:[\w.\-]+:[\w.\-]+)"` → `DEPENDENCY` + artifactCoords
    - `"Could not find ([\w.\-]+:[\w.\-]+:[\w.\-]+)"` → `DEPENDENCY` + artifactCoords
    - `"plugin .+ not found"` → `STRUCTURE`
    - `"Build file '([^']+)' line: (\d+)"` → `STRUCTURE` + path + line
    - `"401|403|Unauthorized"` → `REPOSITORY`
    - else → `OTHER` with raw description.

- `core/src/main/kotlin/com/workflow/orchestrator/core/services/impl/BuildEventCaptureStartupActivity.kt`
  - `ProjectActivity` subclass that calls `BuildEventCaptureService.getInstance(project)` to force creation; the constructor wires the listeners.

**Plugin XML:**
- Register `BuildEventCaptureService` as `projectService`.
- Register `BuildEventCaptureStartupActivity` as `<postStartupActivity>`.

**Update:**
- `BuildProblemsServiceImpl.getRecentBuildProblems()` — combine Maven probe + `BuildEventCaptureService.snapshot(GRADLE_IMPORT)`.

**Tests:**
- `BuildEventCaptureServiceImplTest.kt` — fake `ExternalSystemTaskId` + synthetic `Exception` flowed through `onFailure`; assert ring-buffer state.
- `GradleErrorParserTest.kt` — regex parser kept as a separate object for testability; ~12 tests covering each error shape.
- Update `BuildProblemsServiceImplTest.kt` — mock the capture service alongside the Maven probe; test combined output.

**Size:** ~250 LOC src + ~150 LOC tests.

### Commit 2 — Compile event capture

**Update:**
- `BuildEventCaptureServiceImpl` constructor: subscribe to `MessageBusConnection` for `ProjectTaskListener.TOPIC` and/or `BuildProgressListener.TOPIC`.
- On `BuildEvent` whose `result is FailureResult`, extract typed errors from `failures: List<Failure>`.
- Each `Failure` has `message: String`, `description: String?` (often the stack trace).
- Map to `BuildProblem(source = COMPILE, type = COMPILE, severity = ERROR)`.
- Best-effort line extraction from `message` via regex `(.+\.(?:java|kt|kts)):(\d+):` → path + line.

**Update:**
- `BuildProblemsServiceImpl.getRecentBuildProblems()` — add the COMPILE source.

**Tests:**
- Synthetic `BuildEvent.FailureResult` flowed through topic publisher; assert ring-buffer state.

**Size:** ~120 LOC src + ~80 LOC tests.

### Commit 3 — Tool description + plan refresh

**Update:**
- `BuildProblemsTool.kt` description — drop the "V1 Maven only" caveat from the use-for line, since Gradle and compile are now wired. Replace with: "V1.1: Maven, Gradle import, and compile errors are all captured live."
- Bump documented behaviour in `agent/CLAUDE.md` if the tool gets a CLAUDE.md mention (currently it doesn't, but Section "Tools" might).
- Mark V1 plan doc (`agent-build-problems-tool-plan.md`) self-delete trigger as "now satisfied; delete on V1.1 merge."

**Tests:**
- Snapshot regen if the new description churns prompt-snapshots.

**Size:** ~10 LOC src + snapshot diffs.

### Commit 4 — End-to-end characterisation test

**New file:**
- `BuildProblemsEndToEndTest.kt` (`BasePlatformTestCase`) — uses an actual broken Maven `pom.xml` written into a temp project and a synthetic Gradle exception fed through the listener. Asserts that the agent tool returns problems from BOTH sources in the same call.

This is the only test that actually requires the IntelliJ test fixture — all unit tests stay pure-Kotlin via fakes.

**Size:** ~150 LOC test.

## Risks

| Risk | Mitigation |
|---|---|
| `ExternalSystemTaskNotificationListener` API drift across platform versions | Reflective fallback like Maven probe in V1; or hard-pin to 2025.1+ since plugin already targets that |
| `BuildProgressListener` events fire before service is created → missed events | `postStartupActivity` runs before user input is possible; if a build runs during indexing/import, accept the small race |
| Gradle stderr is high-volume; the parser misses some shapes | Always include the raw stderr-tail in `description` so the LLM still has the original text; parser is structuring, not gating |
| Ring-buffer fills with old failures during long sessions | 50 per source is plenty for "recent"; older entries dropped via `ArrayDeque.removeFirst()` |
| Memory leak from stale listeners | `cs: CoroutineScope` constructor-injected = platform-managed lifecycle; listener disconnects when service disposes |

## Open questions

1. **Should V1.1 also persist captured events to disk** (`~/.workflow-orchestrator/{proj}/build-events.json`)? Pro: agent can read history across IDE restarts. Con: scope creep, not requested. Default: in-memory only, document the limitation.
2. **Compile-cascade dedup.** A single broken file produces N errors across N dependent files. Should the capture dedup by `(file, line)`? Default: no — surface them all, let the LLM see the chain.
3. **`clear(source)` API surface.** Is it useful from the agent tool, or internal-only for tests? Default: internal-only; the agent tool reads only.
4. **Snapshot test impact.** Adding/changing the tool description ripples through 7 prompt-snapshot files. Regen + commit them with Commit 3, or split out to a separate doc-only commit?

## Sequencing

- **Bundle V1.1 with the next perf release** so users get build-problems coverage and Phase 4 profiling fixes together.
- **Or ship V1.1 first** as a small point release (v0.83.32-alpha) to validate the listener model before bigger changes.

Recommend the second — V1.1 is mostly additive and low-risk, and shipping it lets us see real Gradle-import failure patterns in the wild before we lock the parser regex.

## Self-delete trigger

Delete this doc when:
- All 4 commits land, and
- A real-world Gradle-import failure has been reported via the agent (validating the listener actually fires), and
- The V1 plan doc (`agent-build-problems-tool-plan.md`) is also deleted, since both will then be merged history.

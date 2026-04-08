# Auto-Detect Project Keys — Design Spec

**Date:** 2026-04-08
**Status:** Approved for implementation
**Scope:** `:core`, `:bamboo`, `:sonar`

## Problem

The plugin auto-detects some project identifiers (bitbucket project/repo via `RepoContextResolver`, bamboo plan key via `PlanDetectionService`, sonar project key via inlined Maven logic in `CodeQualityConfigurable`) but the coverage is uneven and the affordances are inconsistent:

- **Docker tag name** is never auto-detected — users must type it.
- **Bamboo project key** is derivable from the same git remote we already parse, but is not derived.
- Detection only runs when users click one of three separate "Auto-Detect" buttons. New users don't know the buttons exist.
- Detection logic is scattered across modules and UI files (e.g. Maven property lookup is inlined in `CodeQualityConfigurable.kt:70-89`), making it hard to test and reuse.

## Goals

1. Auto-detect docker tag name from `bamboo-specs/**/*.java` (the only source that names the variable).
2. Derive bamboo project key from the git remote URL we already parse.
3. Run all detection automatically in the background on project open, branch switch, credential update, and relevant file changes — without requiring users to click anything.
4. Never overwrite values the user has manually set.
5. Keep the existing manual auto-detect buttons working as a forced re-run.

## Non-Goals

- Replacing `DefaultBranchResolver` or simplifying its 6-tier fallback chain (explicitly out of scope).
- Introducing a `Source` enum or any schema migration on `PluginSettings` / `RepoConfig`.
- Auto-detecting any value that requires guessing a variable name (e.g. reading docker tag from Bamboo plan variables — we have no way to know which variable name is correct).
- Adding new tool windows, panels, or settings pages.
- Deleting existing detection code. Only the inlined Maven snippet in `CodeQualityConfigurable` moves to a service.

## Design

### Components

#### New: `BambooSpecsParser` (`:core`)

Single-purpose pure utility. No IDE APIs, no caching.

```kotlin
object BambooSpecsParser {
    /** Returns map of constant name -> string value found across all .java files
     *  under <projectRoot>/bamboo-specs/src/main/java/. Empty map if dir missing. */
    fun parseConstants(projectRoot: Path): Map<String, String>
}
```

Internals: walks `bamboo-specs/src/main/java/**/*.java`, runs the regex
`(?:private|public)?\s+static\s+final\s+String\s+(\w+)\s*=\s*"([^"]+)"`
over each file's text content, accumulates into a map. First occurrence of a constant name wins. No knowledge of *which* constants are interesting — that's the orchestrator's concern.

#### New: `AutoDetectOrchestrator` (`:core`, project-level service)

Coordinates all detectors. Public API:

```kotlin
@Service(Service.Level.PROJECT)
class AutoDetectOrchestrator(private val project: Project) : Disposable {
    suspend fun detectAll(): AutoDetectResult
    suspend fun detectGitDerivable()
    suspend fun detectFromBambooSpecs()
    suspend fun detectSonarKey()
    suspend fun detectBambooPlan()

    override fun dispose() { /* cancel scope */ }
}
```

Each method is independently triggerable and writes its results to `PluginSettings` / `RepoConfig` via the **fill-only-empty** rule (see below). All methods run on `Dispatchers.IO` under a `SupervisorJob` tied to the project's disposal.

`detectAll()` runs the four detectors in a `coroutineScope` so one failure does not block the others.

#### New: `SonarKeyDetector` (`:sonar`, project-level service)

Extracts the existing Maven detection logic from `CodeQualityConfigurable.kt:70-89` into a reusable service:

```kotlin
@Service(Service.Level.PROJECT)
class SonarKeyDetector(private val project: Project) {
    fun detect(): String?  // null if no Maven project or property absent
}
```

Implementation is identical to today: `MavenProjectsManager.getInstance(project).rootProjects.firstOrNull()?.properties?.getProperty("sonar.projectKey")`. Maven's model already resolves `${project.artifactId}` placeholders, so no additional fallback is needed.

#### Modified: `RepoContextResolver` (`:core`)

The existing git-remote regex extracts `(projectKey, repoSlug)`. We add a small helper that maps the same projectKey value into the bamboo project key field on `RepoConfig`. One-line change in the existing `parseRemoteUrl()` consumer path. No regex changes.

#### Modified: `PlanDetectionService` (`:bamboo`)

Add a thin wrapper:

```kotlin
suspend fun autoDetectIfMissing(): String? {
    if (settings.bambooPlanKey.isNotBlank()) return null
    return autoDetect(currentGitRemoteUrl())
}
```

So the orchestrator can call it without re-implementing the "skip if already set" check.

#### Modified: `CodeQualityConfigurable` (`:sonar`)

The existing "Auto-Detect" button now calls `SonarKeyDetector.detect()`. Functionally identical to today; logic just moved out of the UI class.

#### Modified: `RepositoriesConfigurable` (`:core`)

The existing "Auto-Detect" button now calls `AutoDetectOrchestrator.detectAll()` instead of `RepoContextResolver.autoDetectRepos()`. This consolidates the consolidation point — one button fills everything that's derivable from the repo state.

### The fill-only-empty rule

Implemented once, in `AutoDetectOrchestrator`:

```kotlin
private fun fillIfEmpty(current: String?, detected: String?): String? =
    if (current.isNullOrBlank() && !detected.isNullOrBlank()) detected else current
```

Applied to every field write. **No schema migration**, no `Source` enum. The contract:

- A blank/null field is treated as "user has not set this" and is fair game.
- A non-blank field is treated as "user has set this" and is never touched.
- If the user wants to force re-detection, they clear the field manually and the next trigger refills it.

This rule is the single biggest reason this design stays surgical.

### Triggers

| Trigger | Wire-up | Calls |
|---|---|---|
| Project open | New `WorkflowAutoDetectStartupActivity : ProjectActivity` registered in `:core` plugin.xml | `detectAll()` after `DumbService.smartInvokeLater` so indexes are ready |
| Branch change | Subscribe to existing `WorkflowEvent.BranchChanged` on `EventBus` | `detectFromBambooSpecs()` + `detectBambooPlan()` |
| Credentials updated | New listener on `PluginSettings` change events filtered to credential fields (Bamboo URL/PAT) | `detectBambooPlan()` only |
| `bamboo-specs/**/*.java` modified | `BulkFileListener` registered on project open, scoped to paths under `bamboo-specs/` | `detectFromBambooSpecs()`, debounced 500ms |
| `pom.xml` modified / Maven reimport | `MavenProjectsManager.addProjectsTreeListener` | `detectSonarKey()` |
| `.git/config` modified | `BulkFileListener` scoped to `.git/config` | `detectGitDerivable()` |

All trigger handlers route through `AutoDetectOrchestrator` and run on `Dispatchers.IO`. None write to settings directly. The 500ms debounce on file-watcher triggers prevents `git checkout` from firing the bamboo-specs walk multiple times when many files change at once.

### Notification

`AutoDetectOrchestrator` holds an `AtomicBoolean firstSweepNotified` per project. After the first `detectAll()` of a project session that filled at least one previously-empty field, fire one non-modal balloon:

> *Workflow Orchestrator auto-detected project keys. Review in Settings → Tools → Workflow Orchestrator → Repositories.*

Subsequent triggers are silent — they just update fields. No popups, no progress bars, no logs at WARN.

### Error handling

- Each detector returns `Result<T>`. Failures log at INFO and are skipped.
- One detector failing never blocks the others (`coroutineScope` per-task `try/catch`).
- Missing `bamboo-specs/` → `parseConstants()` returns `emptyMap()`.
- Missing `pom.xml` or no Maven project → `SonarKeyDetector.detect()` returns `null`.
- Missing `.git/config` → already handled by `RepoContextResolver` today.
- Bamboo creds absent → `PlanDetectionService.autoDetectIfMissing()` returns `null` early.

## Threading

- All detection runs on `Dispatchers.IO`.
- File walks use `java.nio.file` directly (no IDE VFS) to avoid read-action overhead.
- Settings writes are dispatched via the existing `PluginSettings` API which already handles its own thread-safety.
- The orchestrator owns a `CoroutineScope(SupervisorJob() + Dispatchers.IO)` cancelled on `dispose()`.

## Testing

### Unit tests

**`BambooSpecsParserTest`** — pure unit test against fixture files:
- Typical `PlanProperties.java` with several constants → all extracted
- File with no matching constants → empty map
- File with multiple constants of same name → first wins
- Malformed Java file → no crash, returns whatever it could match
- Missing `bamboo-specs/` directory → empty map
- Multiple `.java` files → constants merged

**`AutoDetectOrchestratorTest`** — uses fake detectors injected via constructor:
- Fill-only-empty rule never overwrites a non-blank field
- One detector throwing does not block the others
- `firstSweepNotified` fires exactly once per session even across multiple `detectAll()` calls
- Branch-change trigger calls only the two branch-sensitive detectors

**`SonarKeyDetectorTest`** — uses an in-memory Maven project fixture (or mocks `MavenProjectsManager`) to verify property lookup.

### Integration test

**`AutoDetectIntegrationTest`** — real fixture project under `core/src/test/testData/auto-detect-project/` containing:
- `bamboo-specs/src/main/java/constants/PlanProperties.java` with `DOCKER_TAG_NAME = "MyServiceDockerTag"`
- `pom.xml` with `<sonar.projectKey>${project.artifactId}</sonar.projectKey>` and `<artifactId>my-service</artifactId>`
- `.git/config` with a Bitbucket SSH remote

Asserts `detectAll()` populates: bitbucket project key, bitbucket repo slug, bamboo project key, docker tag key, sonar project key.

### Manual smoke

In `runIde` sandbox, verify:
- Project open triggers a single notification
- Modifying `.git/config` triggers re-detection of git-derivable fields only
- Switching git branches triggers bamboo-specs + plan detection
- Manually setting a field, then re-running the auto-detect button, leaves the manual value untouched

## Files Changed

**New:**
- `core/src/main/kotlin/com/workflow/orchestrator/core/autodetect/BambooSpecsParser.kt`
- `core/src/main/kotlin/com/workflow/orchestrator/core/autodetect/AutoDetectOrchestrator.kt`
- `core/src/main/kotlin/com/workflow/orchestrator/core/autodetect/AutoDetectResult.kt`
- `core/src/main/kotlin/com/workflow/orchestrator/core/autodetect/WorkflowAutoDetectStartupActivity.kt`
- `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarKeyDetector.kt`
- `core/src/test/kotlin/com/workflow/orchestrator/core/autodetect/BambooSpecsParserTest.kt`
- `core/src/test/kotlin/com/workflow/orchestrator/core/autodetect/AutoDetectOrchestratorTest.kt`
- `core/src/test/kotlin/com/workflow/orchestrator/core/autodetect/AutoDetectIntegrationTest.kt`
- `sonar/src/test/kotlin/com/workflow/orchestrator/sonar/service/SonarKeyDetectorTest.kt`
- `core/src/test/testData/auto-detect-project/` (fixture tree)

**Modified:**
- `core/src/main/resources/META-INF/plugin.xml` — register `AutoDetectOrchestrator` service + `WorkflowAutoDetectStartupActivity`
- `core/src/main/kotlin/.../RepoContextResolver.kt` — derive bamboo project key from same regex match
- `core/src/main/kotlin/.../RepositoriesConfigurable.kt` — wire "Auto-Detect" button to `AutoDetectOrchestrator.detectAll()`
- `bamboo/src/main/kotlin/.../PlanDetectionService.kt` — add `autoDetectIfMissing()` wrapper
- `sonar/src/main/kotlin/.../CodeQualityConfigurable.kt` — replace inlined Maven lookup with `SonarKeyDetector.detect()` call

## Open Risks

1. **Branch-sensitive bamboo-specs:** If a project has different `DOCKER_TAG_NAME` constants on different branches, the fill-only-empty rule means only the *first* branch's value sticks (because subsequent triggers see a non-blank field). Mitigation: documented behavior; users on multi-tag projects should use manual entry. We considered this acceptable because (a) it's rare, and (b) the alternative is overwriting user input, which is worse.
2. **Maven model not ready at startup:** `SonarKeyDetector` may return `null` if called before Maven import completes. Mitigation: the `pom.xml` modified / Maven reimport trigger will catch up once import finishes. The `WorkflowAutoDetectStartupActivity` waits on `DumbService.smartInvokeLater` which only handles indexing readiness, not Maven import — so the first `detectAll()` may miss the sonar key on first project open of a fresh import. This is acceptable because the next trigger will fix it.
3. **Notification spam during rapid file changes:** The `firstSweepNotified` flag is per-project-session, not per-fill. If the user closes and reopens the project, they'll see one balloon again. This matches IDE convention.

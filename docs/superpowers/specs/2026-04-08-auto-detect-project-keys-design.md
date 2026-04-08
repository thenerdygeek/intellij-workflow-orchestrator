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
6. **Multi-repo support:** when the project has multiple repos configured in `PluginSettings.repos`, detect docker tag, sonar key, and bamboo plan key *per repo* and write to each `RepoConfig` — not just to the global `PluginSettings.State` fields.

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

Extracts the existing Maven detection logic from `CodeQualityConfigurable.kt:70-89` into a reusable service, with two methods — one for the legacy single-project case and one for multi-repo:

```kotlin
@Service(Service.Level.PROJECT)
class SonarKeyDetector(private val project: Project) {
    /** Legacy single-project: returns sonar key from the IDE's first Maven root project. */
    fun detect(): String?

    /** Multi-repo: returns sonar key from the Maven project rooted at the given path,
     *  or null if no Maven root matches that directory. */
    fun detectForPath(repoRootPath: String): String?
}
```

Both methods use the same lookup chain: `properties["sonar.projectKey"]` → `${groupId}:${artifactId}`. Maven's model already resolves `${project.artifactId}` placeholders, so no additional fallback is needed. `detectForPath` finds the matching Maven root by comparing absolute normalized directory paths.

#### Modified: `RepoContextResolver` (`:core`)

The existing git-remote regex extracts `(projectKey, repoSlug)`. We add a small helper that maps the same projectKey value into the bamboo project key field on `RepoConfig`. One-line change in the existing `parseRemoteUrl()` consumer path. No regex changes.

#### Modified: `BambooService` (`:core` interface) + `BambooServiceImpl` (`:bamboo`)

Per CLAUDE.md's "Agent-Exposable Service Architecture" rules, plan auto-detection is exposed as a method on the unified core service interface so the orchestrator (and any future agent tool) can call it without depending on `:bamboo`:

```kotlin
// In :core/services/BambooService.kt
suspend fun autoDetectPlan(gitRemoteUrl: String): ToolResult<String>
```

The implementation in `BambooServiceImpl` constructs `PlanDetectionService(client)` inline and wraps the existing `autoDetect(gitRemoteUrl)` result as a `ToolResult<String>`. The orchestrator iterates over `GitRepositoryManager.repositories`, matches each to a `RepoConfig` via `PluginSettings.getRepoForPath`, and calls `BambooService.autoDetectPlan(remoteUrl)` once per repo. The "skip if already set" check lives in the orchestrator, not in the service method, so the service stays a thin pass-through.

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

### Multi-Repo Handling

The plugin already supports multi-repo projects via `PluginSettings.repos: List<RepoConfig>`. Each `RepoConfig` has its own `sonarProjectKey`, `bambooPlanKey`, `dockerTagKey`, and `localVcsRootPath`. The repo edit dialog in `RepositoriesConfigurable` lets users set these per repo. The Sonar runtime (`SonarDataService`, `QualityDashboardPanel`) already reads per-repo values when an event carries a repo context, falling back to global `PluginSettings.State` otherwise.

The auto-detect orchestrator must populate per-repo values when repos are configured:

1. **`detectFromBambooSpecs()`** — for each `RepoConfig` with a non-blank `localVcsRootPath`, parse `<repoRoot>/bamboo-specs/src/main/java/**/*.java` and write `DOCKER_TAG_NAME` → `repo.dockerTagKey` and `PLAN_KEY` → `repo.bambooPlanKey`.
2. **`detectSonarKey()`** — for each `RepoConfig` with a non-blank `localVcsRootPath`, call `SonarKeyDetector.detectForPath(repoRoot)` and write to `repo.sonarProjectKey`.
3. **`detectBambooPlan()`** — iterate over `GitRepositoryManager.repositories`, match each git repo to a `RepoConfig` by `localVcsRootPath` (via `PluginSettings.getRepoForPath`). For each repo with a blank `bambooPlanKey`, call `BambooService.autoDetectPlan(gitRemoteUrl)` and write the resulting key to `repo.bambooPlanKey`.

**Fallback for no-repo projects:** when `PluginSettings.repos` is empty, the orchestrator behaves as before — it uses `project.basePath` as a synthetic single repo and writes to global `PluginSettings.State` fields directly.

**Mirror to global:** after per-repo detection completes, the primary repo's values (`PluginSettings.getPrimaryRepo()`) mirror to global `PluginSettings.State` via the same fill-only-empty rule. This preserves the existing global-fallback path used by code that doesn't have a repo context (e.g. notifications, generic refresh paths in `SonarDataService`).

The fill-only-empty rule applies at every write — both per-repo and to the global mirror.

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
- Bamboo creds absent → `BambooService.autoDetectPlan()` returns a `ToolResult` with `isError=true`; the orchestrator skips it silently.

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
- **Multi-repo:** when `PluginSettings.repos` has 2 entries with different `localVcsRootPath`s, each gets its own detected sonar key / docker tag / plan key
- **Multi-repo mirror:** after multi-repo detection, the primary repo's values appear in global `PluginSettings.State`
- **No-repo fallback:** when `PluginSettings.repos` is empty, detection writes to global `PluginSettings.State` using `project.basePath` as the synthetic repo path

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
- `src/main/resources/META-INF/plugin.xml` — register `AutoDetectOrchestrator` service + `SonarKeyDetector` service + `WorkflowAutoDetectStartupActivity` + `AutoDetectFileListener`
- `core/src/main/kotlin/com/workflow/orchestrator/core/services/BambooService.kt` — add `autoDetectPlan(gitRemoteUrl: String): ToolResult<String>` interface method
- `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BambooServiceImpl.kt` — implement `autoDetectPlan` (delegates to `PlanDetectionService(client).autoDetect`)
- `core/src/main/kotlin/.../RepositoriesConfigurable.kt` — wire "Auto-Detect" button to also call `AutoDetectOrchestrator.detectAll()` after `autoDetectRepos()`
- `sonar/src/main/kotlin/.../CodeQualityConfigurable.kt` — replace inlined Maven lookup with `SonarKeyDetector.detect()` call

## Open Risks

1. **Branch-sensitive bamboo-specs:** If a project has different `DOCKER_TAG_NAME` constants on different branches, the fill-only-empty rule means only the *first* branch's value sticks (because subsequent triggers see a non-blank field). Mitigation: documented behavior; users on multi-tag projects should use manual entry. We considered this acceptable because (a) it's rare, and (b) the alternative is overwriting user input, which is worse.
2. **Maven model not ready at startup:** `SonarKeyDetector` may return `null` if called before Maven import completes. Mitigation: the `pom.xml` modified / Maven reimport trigger will catch up once import finishes. The `WorkflowAutoDetectStartupActivity` waits on `DumbService.smartInvokeLater` which only handles indexing readiness, not Maven import — so the first `detectAll()` may miss the sonar key on first project open of a fresh import. This is acceptable because the next trigger will fix it.
3. **Notification spam during rapid file changes:** The `firstSweepNotified` flag is per-project-session, not per-fill. If the user closes and reopens the project, they'll see one balloon again. This matches IDE convention.
4. **Multi-repo with missing `localVcsRootPath`:** A `RepoConfig` entry with no `localVcsRootPath` is silently skipped by the per-repo detectors. This is the correct behavior — without a path we can't locate the repo's `bamboo-specs/` or `pom.xml` — but means hand-edited entries that lack the path will not auto-fill. Mitigation: documented behavior; users should set `localVcsRootPath` (which `RepoContextResolver.autoDetectRepos()` does automatically).
5. **Multi-repo Maven roots not matching repo paths:** `SonarKeyDetector.detectForPath` requires the exact repo root to match a Maven root project's directory. Repos that are not Maven projects (or whose root pom is in a subdirectory) return null. This matches today's behavior; the user can fall back to manual entry.

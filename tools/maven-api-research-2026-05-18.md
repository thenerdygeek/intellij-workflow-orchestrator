# Maven API Research — 2026-05-18

Authoritative source: JetBrains/intellij-community `master` (2025.1+ HEAD). All FQN references are under `org.jetbrains.idea.maven`.

## A. Per-module actions

The Maven plugin defines **two parallel action families**: "All projects" actions (operate on `manager.projects` — i.e. every imported Maven module) and "Selected" / "ForProject" actions (operate on what's in the `DataContext`).

The "All" in `Maven.DownloadAllSources`/`Maven.DownloadAllDocs`/`Maven.DownloadAllSourcesAndDocs` means **all Maven modules in the workspace**, not "all artifact kinds" — verified in `DownloadAllSourcesAndDocsAction.kt`, whose `perform()` calls `manager.downloadArtifacts(MavenDownloadSourcesRequest.builder().forProjects(manager.projects).forAllArtifacts()…)`. ([source](https://github.com/JetBrains/intellij-community/blob/master/plugins/maven/src/main/java/org/jetbrains/idea/maven/project/actions/DownloadAllSourcesAndDocsAction.kt))

Per-module / per-selection action IDs (from `plugins/maven/src/main/resources/intellij.maven.xml`, [source](https://github.com/JetBrains/intellij-community/blob/master/plugins/maven/src/main/resources/intellij.maven.xml)):

| Mode we expose | "All" action ID we currently use | Per-module / scoped action ID | Handler class |
|---|---|---|---|
| `generate_sources` | `Maven.UpdateAllFolders` (does NOT exist in current xml — see note) | `Maven.UpdateFolders` (selection-driven) **+** `Maven.UpdateFoldersForProject` (one MavenProject) | `UpdateFoldersAction.kt`, `UpdateFoldersForProjectAction.kt` |
| `download_sources` | `Maven.DownloadAllSources` (all modules) | `Maven.DownloadSelectedSources` | `DownloadSelectedSourcesAction.java` |
| `download_javadocs` | `Maven.DownloadAllDocs` | `Maven.DownloadSelectedDocs` | `DownloadSelectedDocsAction.java` |
| `download_sources_and_javadocs` | `Maven.DownloadAllSourcesAndDocs` | `Maven.DownloadSelectedSourcesAndDocs` | `DownloadSelectedSourcesAndDocsAction.kt` |
| `reload` | — | `Maven.Reimport` (per-module) **+** `Maven.SyncIncrementally` (per-module) **+** `Maven.ReimportProject` (project-level) | — |

> **Naming note:** Our code references `"Maven.UpdateAllFolders"` — this action ID does **not appear** in the current `intellij.maven.xml`. The actual IDs are `Maven.UpdateFolders` (selection-driven, on `Maven.NavigatorActionsToolbar`) and `Maven.UpdateFoldersForProject` (one MavenProject root, on `Maven.BaseProjectMenu`). `Maven.UpdateAllFolders` may be a legacy alias that still resolves, or it may already be broken on 2025.1+. **Needs sandbox verification.**

### DataContext required by per-module actions

The base class for all "selected" actions is `MavenProjectsAction.java` ([source](https://github.com/JetBrains/intellij-community/blob/master/plugins/maven/src/main/java/org/jetbrains/idea/maven/project/actions/MavenProjectsAction.java)). It resolves projects via `MavenActionUtil.getMavenProjects(DataContext)` — which inspects `CommonDataKeys.PROJECT` plus `CommonDataKeys.VIRTUAL_FILE_ARRAY`, then converts each `VirtualFile` → `Module` → `MavenProject` through `MavenProjectsManager.findProject(module)` ([MavenActionUtil source](https://github.com/JetBrains/intellij-community/blob/master/plugins/maven/src/main/java/org/jetbrains/idea/maven/utils/actions/MavenActionUtil.java)). The action's `update()` disables itself when the resulting list is empty.

For the artifact-download family, `MavenProjectsAction` is augmented by `MavenDataKeys.MAVEN_DEPENDENCIES: DataKey<Collection<MavenArtifact>>` — `DownloadSelectedSourcesAndDocsAction` reads it via `getDependencies(e)` to populate the artifact filter. The other public DataKeys in `MavenDataKeys.java` ([source](https://github.com/JetBrains/intellij-community/blob/master/plugins/maven/src/main/java/org/jetbrains/idea/maven/utils/MavenDataKeys.java)): `MAVEN_GOALS`, `RUN_CONFIGURATION`, `MAVEN_PROFILES`, `MAVEN_PROJECTS_TREE`, `MAVEN_REPOSITORY`. **There is no `MAVEN_PROJECT` or `MAVEN_PROJECTS_FILES` data key** — selection flows through `VIRTUAL_FILE_ARRAY` containing pom.xml files.

### Concrete invocation pattern (per-module)

```kotlin
// Build a DataContext that exposes the target pom.xml as VIRTUAL_FILE_ARRAY.
val pomVf: VirtualFile = mavenProject.file  // or look up pom.xml of the target module
val dataContext = SimpleDataContext.builder()
    .add(CommonDataKeys.PROJECT, project)
    .add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(pomVf))
    .build()
val action = ActionManager.getInstance().getAction("Maven.UpdateFolders")
val event = AnActionEvent.createEvent(action, dataContext, Presentation(),
    ActionPlaces.UNKNOWN, ActionUiKind.NONE, null)
ActionUtil.invokeAction(action, event, null)
```

For multi-module scoping, pass an array of pom.xml `VirtualFile`s — `MavenActionUtil.getMavenProjects` enumerates the array. For artifact actions, also bind `MavenDataKeys.MAVEN_DEPENDENCIES` if you want to scope to specific artifacts.

## B. Direct per-module APIs

The modern public Kotlin façade is `MavenAsyncProjectsManager` (declared in `MavenProjectsManagerEx.kt`, [source](https://github.com/JetBrains/intellij-community/blob/master/plugins/maven/src/main/java/org/jetbrains/idea/maven/project/MavenProjectsManagerEx.kt)). `MavenProjectsManager implements MavenAsyncProjectsManager`. Class is `@ApiStatus.Experimental` — JetBrains warns it can shift between major releases.

```kotlin
interface MavenAsyncProjectsManager {
  fun scheduleUpdateAllMavenProjects(spec: MavenSyncSpec)
  suspend fun updateAllMavenProjects(spec: MavenSyncSpec)
  fun scheduleUpdateMavenProjects(spec: MavenSyncSpec,
                                  filesToUpdate: List<VirtualFile>,
                                  filesToDelete: List<VirtualFile>)
  suspend fun updateMavenProjects(spec: MavenSyncSpec,
                                  filesToUpdate: List<VirtualFile>,
                                  filesToDelete: List<VirtualFile>)
  suspend fun downloadArtifacts(request: MavenDownloadSourcesRequest): ArtifactDownloadResult
  fun scheduleDownloadArtifacts(request: MavenDownloadSourcesRequest)
  @ApiStatus.Internal suspend fun importMavenProjects(projectsToImport: List<MavenProject>)
  @ApiStatus.Internal suspend fun addManagedFilesWithProfiles(
      files: List<VirtualFile>, profiles: MavenExplicitProfiles,
      modelsProvider: IdeModifiableModelsProvider?, previewModule: Module?,
      syncProject: Boolean): List<Module>
  suspend fun onProjectStartup()
}
```

`MavenDownloadSourcesRequest` builder accepts `forProjects(Collection<MavenProject>)`, `forArtifacts(Collection<MavenArtifact>)` or `forAllArtifacts()`, `downloadSources(Boolean)`, `downloadDocs(Boolean)`.

**Partial reload (subset of modules):** `scheduleUpdateMavenProjects(spec, filesToUpdate, filesToDelete)` / `updateMavenProjects(...)` — pass the pom.xml `VirtualFile`s of the modules to refresh. This is the correct primitive when the LLM asks "reload module X" rather than "reload all".

**Folder regeneration (subset):** Per-module folder regeneration goes through `MavenFolderResolver(project).resolveFoldersAndImport(mavenProjects: List<MavenProject>)` — what `UpdateFoldersAction.perform()` does directly ([source](https://github.com/JetBrains/intellij-community/blob/master/plugins/maven/src/main/java/org/jetbrains/idea/maven/project/actions/UpdateFoldersAction.kt)). Not exposed on `MavenAsyncProjectsManager`; would need its own reflective path or stay on the action.

**Deprecated direct APIs on `MavenProjectsManager` (still present, will be removed):**
- `forceUpdateProjects(Collection<MavenProject>) → AsyncPromise<Void>` — `@Deprecated`
- `scheduleImportAndResolve() → Promise<List<Module>>` — `@Deprecated`
- `scheduleFoldersResolveForAllProjects()` — `@Deprecated`
- `updateAllMavenProjectsSync()` — `@Deprecated(forRemoval = true)`

`scheduleArtifactsDownloading` was **removed in 2023.2** ([JetBrains Support thread](https://intellij-support.jetbrains.com/hc/en-us/community/posts/13744541641106)). The reflective `getMethod("scheduleArtifactsDownloading", …)` would throw `NoSuchMethodException` on 2025.1+.

## C. Awaiting operations

**Our current `awaitMavenImport` polls `MavenProjectsManager.isImportingInProgress()` every 200 ms. This method does NOT appear in the master `MavenProjectsManager.java`.** Confirmed via GitHub code search: `isImportingInProgress` matches only Gradle/Kotlin scripting files, never the Maven plugin. Either it was removed silently or it never existed on this class — `awaitMavenImport` is almost certainly resolving `NoSuchMethodException` and returning `true` immediately on 2025.1+ (the `catch (_: NoSuchMethodException)` branch at `MavenImportHelper.kt:101`). **The polled fallback is dead code.**

The canonical patterns in current JetBrains-blessed code:

1. **Suspend on the suspend variant.** `MavenAsyncProjectsManager.updateAllMavenProjects(spec)` / `updateMavenProjects(...)` / `downloadArtifacts(request)` are `suspend fun` — they return only when the operation completes. This is the JetBrains-recommended way to await in 2025+.
2. **Callback when you can't suspend.** `MavenUtil.runWhenInitialized(project, Runnable)` registers a one-shot callback that fires when the Maven subsystem is up ([source](https://github.com/JetBrains/intellij-community/blob/master/plugins/maven/src/main/java/org/jetbrains/idea/maven/utils/MavenUtil.kt)). For coroutine bridges, wrap it in `suspendCancellableCoroutine` — see [`ImportMavenProjectUtil.kt`](https://github.com/JetBrains/intellij-community/blob/master/plugins/maven/performanceTesting/src/ImportMavenProjectUtil.kt), which combines `ExternalProjectsManagerImpl.runWhenInitialized`, `MavenUtil.runWhenInitialized`, and `JpsProjectLoadingManager.jpsProjectLoaded` — this is the official perf-test reference for "await full Maven import".

`forceUpdateAllProjectsOrFindAllAvailablePomFiles()` is still the canonical entry point — its body in current master:

```java
private void forceUpdateAllProjectsOrFindAllAvailablePomFiles(MavenSyncSpec spec) {
  if (!isMavenizedProject()) {
    addManagedFiles(collectAllAvailablePomFiles());
    return;
  }
  scheduleUpdateAllMavenProjects(spec);
}
```

Not deprecated. ([source](https://github.com/JetBrains/intellij-community/blob/master/plugins/maven/src/main/java/org/jetbrains/idea/maven/project/MavenProjectsManager.java))

## D. forceMavenReimport correctness

Our `reload` mode reflectively calls `MavenProjectsManager.forceUpdateAllProjectsOrFindAllAvailablePomFiles()`. This is identical to clicking "Reload All Maven Projects" in the tool window — both end up at `scheduleUpdateAllMavenProjects(MavenSyncSpec.full(…))`, and both fall back to `addManagedFiles(collectAllAvailablePomFiles())` when the project isn't yet Mavenized. **Verdict: behaviorally equivalent.** ([source](https://github.com/JetBrains/intellij-community/blob/master/plugins/maven/src/main/java/org/jetbrains/idea/maven/project/MavenProjectsManager.java))

The one nuance is that `scheduleUpdateAllMavenProjects` is fire-and-forget — it doesn't block. Our reflective call returns immediately; the import runs in background. That's fine for our "trigger and surface that it's running" semantics, but it's why `awaitMavenImport` exists at all — and that helper is broken (see C).

## E. Fresh-clone (addManagedFilesOrUnignore)

`addManagedFilesOrUnignore(List<VirtualFile>)` is **still present** on `MavenProjectsManager` and **does trigger a resolve**:

```java
public void addManagedFilesOrUnignore(@NotNull List<VirtualFile> files) {
  addManagedFilesOrUnignoreNoUpdate(files);
  scheduleUpdateAllMavenProjects(
    MavenSyncSpec.incremental("...addManagedFilesOrUnignore"));
}
```

So the post-registration resolve is automatic — we do **not** need a separate trigger call. ([source](https://github.com/JetBrains/intellij-community/blob/master/plugins/maven/src/main/java/org/jetbrains/idea/maven/project/MavenProjectsManager.java))

**However**, the canonical user-facing entry point in 2025+ is `MavenOpenProjectProvider.forceLinkToExistingProjectAsync(projectFile, project)`. This is what `AddManagedFilesAction.kt` (the "+" button in the Maven tool window) actually invokes ([source](https://github.com/JetBrains/intellij-community/blob/master/plugins/maven/src/main/java/org/jetbrains/idea/maven/project/actions/AddManagedFilesAction.kt)). The provider extends `AbstractOpenProjectProvider` and adds: trust-dialog verification, external-system auto-import registry checks, activity tracking. `addManagedFilesOrUnignore` skips all of those.

For an LLM-driven tool, our direct `addManagedFilesOrUnignore` is **functionally correct but skips the trust dialog**. In a fresh-clone scenario where the user hasn't trusted the project yet, this could be a subtle violation of IntelliJ's trust model. `MavenOpenProjectProvider.forceLinkToExistingProjectAsync` is a `suspend fun` so it composes cleanly with our existing coroutine call site.

## F. Pom discovery

`MavenUtil.streamPomFiles(project, root)` ([source](https://github.com/JetBrains/intellij-community/blob/master/plugins/maven/src/main/java/org/jetbrains/idea/maven/utils/MavenUtil.kt)) is a **single-level scan** — `root.getChildren()` filtered by `isPomFile`. No depth recursion, no exclude list. That's because the plugin pairs it with `MavenProjectsManager.collectAllAvailablePomFiles()`, which combines `streamPomFiles` output with module-derived pom files already known from prior imports:

```java
@ApiStatus.Internal
public List<VirtualFile> collectAllAvailablePomFiles() {
  List<VirtualFile> result = new ArrayList<>(
    getFileToModuleMapping(new MavenDefaultModelsProvider(myProject)).keySet());
  MavenUtil.streamPomFiles(myProject, myProject.getBaseDir()).forEach(result::add);
  return result;
}
```

**Our `findPomFiles` with depth-2 walk is more aggressive than what the JetBrains plugin itself does for a cold fresh-clone** — the plugin relies on `forceUpdateAllProjectsOrFindAllAvailablePomFiles()` to discover only the root pom.xml and then chase `<modules>` references via the Maven model. JetBrains does NOT walk subdirectories looking for child pom.xml files; multi-module discovery is driven by parsing the parent's `<modules>`.

So our depth-2 walk handles a case the JetBrains plugin doesn't natively handle (sub-projects without a parent aggregator). That's a defensible deviation for an LLM tool — but the EXCLUDED_DIRS list is ours; the JetBrains plugin has no equivalent because it doesn't walk. `MavenUtil.collectFiles` exists but it converts `Collection<MavenProject>` → `List<VirtualFile>` (read accessor), not a discovery helper.

`@ApiStatus.Internal` on `collectAllAvailablePomFiles()` means we can't call it directly from outside the Maven plugin without breaking the API contract.

## Verdict on our current implementation

| # | Question | Verdict | Justification |
|---|---|---|---|
| A | Per-module action invocation | **SUBOPTIMAL** | Always use the "All" variant; LLM can't scope to one module. Per-module action IDs exist (`Maven.UpdateFolders`, `Maven.DownloadSelectedSources/Docs/SourcesAndDocs`), require `VIRTUAL_FILE_ARRAY` of poms + optional `MAVEN_DEPENDENCIES`. Also: `Maven.UpdateAllFolders` (our current ID for `generate_sources`) is NOT in `intellij.maven.xml` master — likely broken on 2025.1+. |
| B | Direct per-module APIs | **SUBOPTIMAL** | We use only `forceUpdateAllProjectsOrFindAllAvailablePomFiles` (all-projects). `MavenAsyncProjectsManager.scheduleUpdateMavenProjects(spec, files, deletedFiles)` is the right primitive for partial reload. `downloadArtifacts(MavenDownloadSourcesRequest)` is the right primitive for per-module artifact downloads — and is the only way to do per-artifact filtering. |
| C | Awaiting operations | **INCORRECT** | `MavenProjectsManager.isImportingInProgress()` is not on master — our reflective poll exits via `NoSuchMethodException` and reports "import complete" instantly. `awaitMavenImport()` is effectively a no-op on 2025.1+. The correct primitive is to call the `suspend fun` variants (`updateAllMavenProjects`, `updateMavenProjects`, `downloadArtifacts`) which return on completion — or `MavenUtil.runWhenInitialized` for callback bridge. |
| D | `forceMavenReimport` reload | **CORRECT** | Equivalent to the "Reload All Maven Projects" tool window button. Both delegate to `scheduleUpdateAllMavenProjects(MavenSyncSpec.full(...))`. |
| E | `addManagedFilesOrUnignore` | **SUBOPTIMAL** | Functionally correct (auto-triggers resolve) but skips IntelliJ's trust-dialog / external-system auto-import wiring. `MavenOpenProjectProvider.forceLinkToExistingProjectAsync(projectFile, project)` is the JetBrains canonical entry — used by `AddManagedFilesAction.kt` itself. |
| F | `findPomFiles` depth-2 walk | **SUBOPTIMAL** | Our walk is more aggressive than JetBrains' (which only scans depth-1 via `streamPomFiles`). Our `EXCLUDED_DIRS` set is hand-rolled; the plugin has no equivalent because it doesn't walk. Acceptable as a deliberate enhancement for LLM-driven fresh-clone, but document why. Consider adding `dist`, `cmake-build-*`, `coverage` to exclusions; depth 3 may catch some multi-tier layouts (e.g. `apps/*/services/*/pom.xml`). |

## Recommended changes for multi-module support

Ranked by importance:

1. **Replace `awaitMavenImport` polling with a `suspendCancellableCoroutine` over `MavenUtil.runWhenInitialized`** — file `MavenImportHelper.kt:88-120`. The current poll is dead code; reflection finds no method. Mirror `ImportMavenProjectUtil.kt` from the JetBrains perf-test module: chain `ExternalProjectsManagerImpl.runWhenInitialized` + `MavenUtil.runWhenInitialized` + `JpsProjectLoadingManager.jpsProjectLoaded` so we wait for the full import cycle. Keep the existing `NoSuchMethodException`/`ClassNotFoundException` fallback so PyCharm Community still works.

2. **Add a `module_path` parameter to the `refresh_external_project` tool** (`RefreshExternalProjectAction.kt:198-251`) and wire it through to a per-module dispatch:
   - `mode=reload` + `module_path=X` → reflect `MavenAsyncProjectsManager.scheduleUpdateMavenProjects(spec, listOf(pomVf), emptyList())`.
   - `mode=download_*` + `module_path=X` → build `MavenDownloadSourcesRequest` with `forProjects(listOf(mavenProject))` and call `scheduleDownloadArtifacts(request)`.
   - `mode=generate_sources` + `module_path=X` → reflect `MavenFolderResolver(project).resolveFoldersAndImport(listOf(mavenProject))`.
   When `module_path` is unset, keep the current "all" path.

3. **Fix the `Maven.UpdateAllFolders` action ID** in `MAVEN_MODE_ACTIONS` (`RefreshExternalProjectAction.kt:36`). This action ID does NOT appear in master `intellij.maven.xml`. The closest equivalents are `Maven.UpdateFoldersForProject` (project-level, no selection) and `Maven.UpdateFolders` (selection-driven). For our current "all modules" semantics, switching to the `MavenAsyncProjectsManager.updateAllMavenProjects(MavenSyncSpec.full("agent-generate-sources"))` direct call (reflective) is cleaner than guessing action IDs. **Test on 2025.1 sandbox** to confirm `Maven.UpdateAllFolders` is actually a dead ID and not a still-registered alias.

4. **Migrate `addManagedFilesOrUnignore` to `MavenOpenProjectProvider.forceLinkToExistingProjectAsync`** (`MavenImportHelper.kt:74-77`). This routes through the trust-dialog / auto-import-aware path that `AddManagedFilesAction.kt` itself uses. Note: `forceLinkToExistingProjectAsync` is `suspend fun` and accepts a single `VirtualFile` (or path string) — for multiple poms we'd loop. The provider is in package `org.jetbrains.idea.maven.wizards` — reflective access stays the same shape. Fallback to `addManagedFilesOrUnignore` if the provider class is missing (older platforms).

5. **Consolidate on `MavenAsyncProjectsManager` reflective façade** rather than chasing methods on `MavenProjectsManager`. The deprecated direct methods (`forceUpdateProjects(Collection)`, `scheduleImportAndResolve`, `updateAllMavenProjectsSync`) WILL be removed; the `MavenAsyncProjectsManager` interface methods are the supported path even though the interface is `@ApiStatus.Experimental`. Build a small `MavenAsyncFacade` (reflective wrapper) so future API drift only touches one file.

6. **Expand `findPomFiles` exclusions and re-evaluate depth** (`MavenImportHelper.kt:138-148`). Add `dist`, `cmake-build-debug`, `cmake-build-release`, `coverage`, `.tox`, `.next`, `.nuxt`. Consider `MAX_DEPTH = 3` to catch `apps/*/services/*/pom.xml` layouts common in micro-service repos — paired with the new exclusions, false-positive cost stays low. Document that we walk deeper than the JetBrains plugin on purpose (fresh-clone without parent aggregator).

7. **Cache the reflective `MavenAsyncProjectsManager` Method handles** in `MavenImportHelper.kt`. Class.forName/getMethod is cheap but called per-tool-invocation; a `Lazy<…>` block-cache halves the latency on tools the LLM hammers.

## Files referenced

- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/project/RefreshExternalProjectAction.kt` — change targets at lines 34-40 (`MAVEN_MODE_ACTIONS`), 79-96 (`forceMavenReimport`), 198-329 (per-module dispatch).
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/maven/MavenImportHelper.kt` — change targets at lines 74-77 (`addManagedFilesOrUnignore` → `MavenOpenProjectProvider`), 88-120 (`awaitMavenImport` rewrite), 125-148 (`findPomFiles` exclusions + depth).
- `/Users/subhankarhalder/Desktop/Programs/scripts/IntelijPlugin/agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/JavaRuntimeExecTool.kt` — `executeCompileModule` 1956-2050 uses `awaitMavenImport`; once we fix the helper the call site needs no change but the timeout semantic will become meaningful for the first time.

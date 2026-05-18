# Maven IDE-Action Implementation Audit

**Date:** 2026-05-18  
**File audited:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/project/RefreshExternalProjectAction.kt`  
**Source verified against:** `JetBrains/intellij-community` master branch  
**Primary source URL:** `https://github.com/JetBrains/intellij-community/blob/master/plugins/maven/src/main/resources/intellij.maven.xml`

---

## 1. Action ID Verification

### `Maven.UpdateAllFolders` (used for `generate_sources`) — ✗ WRONG

**The ID `Maven.UpdateAllFolders` does not exist in the registered action set.**

From `intellij.maven.xml` (lines 755–758):
```xml
<action id="Maven.UpdateFolders" class="org.jetbrains.idea.maven.project.actions.UpdateFoldersAction"
        icon="MavenIcons.UpdateFolders">
</action>

<action id="Maven.UpdateFoldersForProject" class="org.jetbrains.idea.maven.project.actions.UpdateFoldersForProjectAction"
        icon="MavenIcons.UpdateFolders">
</action>
```

Neither of these IDs is `Maven.UpdateAllFolders`. The "all-projects" variant is `Maven.UpdateFolders` (triggers `UpdateFoldersAction`, which calls `MavenFolderResolver.resolveFoldersAndImport()` on all projects). **However**, the implementation comment in `RefreshExternalProjectAction.kt` already says `Maven.UpdateAllFolders` was removed from the MAVEN_MODE_ACTIONS map in favour of a direct `MavenFolderResolver` call:

```kotlin
// Per the audit, Maven.UpdateAllFolders is not in current intellij.maven.xml.
// Resolve all MavenProjects and invoke MavenFolderResolver directly.
```

So the code is already aware of this and has been corrected at the dispatch level — it does NOT invoke this action ID. **The comment is correct; the map described in the audit prompt is obsolete**. The actual dispatch for `generate_sources` uses `invokeFolderResolver` via reflection, which is architecturally sound.

**Recommendation:** No code change needed. The MAVEN_MODE_ACTIONS map referenced in the audit prompt is from an old revision; current code is already fixed.

---

### `Maven.DownloadAllSources` — ✓ CORRECT

From `intellij.maven.xml` (line 763):
```xml
<action id="Maven.DownloadAllSources" class="...DownloadAllSourcesAction" ...>
```
Registered. Current code dispatches via `MavenAsyncFacade.scheduleDownloadArtifacts`, not the action ID — which is better (no ActionEvent fabrication needed). No issue.

### `Maven.DownloadAllDocs` — ✓ CORRECT

From `intellij.maven.xml` (line 767):
```xml
<action id="Maven.DownloadAllDocs" class="...DownloadAllDocsAction" ...>
```
Registered. Same note — dispatched programmatically, not by action ID.

### `Maven.DownloadAllSourcesAndDocs` — ✓ CORRECT

From `intellij.maven.xml` (line 771):
```xml
<action id="Maven.DownloadAllSourcesAndDocs" class="...DownloadAllSourcesAndDocsAction" ...>
```
Registered. Same note.

---

## 2. Programmatic Reload API: `forceUpdateAllProjectsOrFindAllAvailablePomFiles`

**Status: ⚠ PARTIALLY DEPRECATED — used correctly as a fallback only**

From `MavenProjectsManager.java` (lines 659, 662–664):
```java
@Deprecated
// ...
public void forceUpdateAllProjectsOrFindAllAvailablePomFiles() {
    forceUpdateAllProjectsOrFindAllAvailablePomFiles(
      MavenSyncSpec.full("MavenProjectsManager.forceUpdateAllProjectsOrFindAllAvailablePomFiles", true));
}
```

The method is `@Deprecated` (not `forRemoval=true` — that annotation is on a different method `updateAllMavenProjectsSync`). The canonical modern API is `scheduleUpdateAllMavenProjects(MavenSyncSpec)` on `MavenAsyncProjectsManager` (the interface). Current implementation already calls `MavenAsyncFacade.scheduleUpdateAllMavenProjects` as its primary path and only falls back to `legacyForceUpdate` (which calls `forceUpdateAllProjectsOrFindAllAvailablePomFiles` via reflection) when the modern API is unavailable. This is correct layering.

**The `ReimportAction` in JetBrains source itself still calls `forceUpdateAllProjectsOrFindAllAvailablePomFiles()` with a `@Suppress("deprecation")`**, confirming the method is still operational and there is no better synchronous fallback.

**Recommendation:** No change required. Fallback to `forceUpdateAllProjectsOrFindAllAvailablePomFiles` is appropriate.

---

## 3. "Sync" vs "Reload" Question

**Status: ✓ CORRECTLY UNDERSTOOD — two distinct operations**

From `intellij.maven.xml` (lines 924–928), there is a `Maven.SyncAllGroup` containing two distinct actions:
```xml
<group id="Maven.SyncAllGroup">
  <reference ref="Maven.SyncIncrementally"/>  <!-- IncrementalSyncAction: incremental MavenSyncSpec -->
  <reference ref="Maven.Reimport"/>            <!-- ReimportAction: full MavenSyncSpec + forceUpdate -->
</group>
```

- **`Maven.SyncIncrementally`** (`IncrementalSyncAction`) → `scheduleUpdateAllMavenProjects(MavenSyncSpec.incremental(...))` — only re-reads changed POMs
- **`Maven.Reimport`** (`ReimportAction`) → calls `forceUpdateAllProjectsOrFindAllAvailablePomFiles()` → `scheduleUpdateAllMavenProjects(MavenSyncSpec.full(..., forceResolvingSnapshots=true))` — full re-resolve including snapshots

The plugin's `reload` mode calls `MavenAsyncFacade.scheduleUpdateAllMavenProjects` with `incremental=false` (a full `MavenSyncSpec`), matching the "Reload All Maven Projects" / `Maven.Reimport` semantic. This is the correct choice for an agent-triggered forced refresh.

**Recommendation:** No change. `reload` → full sync is the right mapping.

---

## 4. Fire-and-Forget vs Awaitable Completion

**Status: ✓ CORRECTLY HANDLED — awaitable variants exist and are wired**

`MavenAsyncProjectsManager` (from `MavenProjectsManagerEx.kt`, line 106):
```kotlin
suspend fun updateAllMavenProjects(spec: MavenSyncSpec)
suspend fun updateMavenProjects(spec: MavenSyncSpec, filesToUpdate: ..., filesToDelete: ...)
suspend fun downloadArtifacts(request: MavenDownloadSourcesRequest): ArtifactDownloadResult
```

`MavenAsyncFacade` already implements `updateAllMavenProjects` and `updateMavenProjects` as `suspend fun` wrappers using `invokeSuspendObj` (the reflective suspend-invoke helper at the bottom of `MavenAsyncFacade.kt`). These awaitable variants are defined but **not used by `dispatchMavenAllProjects`** — it uses `scheduleUpdateAllMavenProjects` (fire-and-forget). There is a deliberate design choice here: the tool result says "Refresh runs in the background — recheck module detail after a moment." This is acceptable, but the infrastructure to await completion exists.

The tool could be upgraded to await completion via `updateAllMavenProjects` and report actual completion — though this would block the agent tool for the duration of the Maven sync (potentially 10–120 seconds on large projects). A practical middle ground: await with a short timeout (e.g. 30s), report actual completion if done, fall back to the fire-and-forget message if not.

**Recommendation (optional enhancement, not a bug):** Consider using `MavenAsyncFacade.updateAllMavenProjects(project, description)` with a `withTimeoutOrNull(30_000)` guard for the `reload` mode, reporting "Reload completed" or "Reload triggered (running in background)" based on the outcome.

---

## 5. `isMavenizedProject` vs External-System-ID Check

**Status: ✓ NO DISAGREEMENT — but the local check is more defensive**

From `MavenProjectsManager.java` (lines 399–401):
```java
public boolean isMavenizedProject() {
    return isInitialized();
}
```

`isInitialized()` returns `!initLock.isLocked() && isInitialized.get()` — it is `true` only after the project has fully initialized its Maven model. It does NOT check whether there are any linked roots.

The local code routes on `systemId.readableName.equals("Maven", ignoreCase = true) || systemId.id.equals("Maven", ignoreCase = true)` (the ExternalSystem framework's view). These two checks answer different questions:

- **ExternalSystem check (local):** "Does this external-system root claim to be Maven?" — based on the ExternalSystem API's linked project registry.
- **`isMavenizedProject()`:** "Has `MavenProjectsManager` fully initialized its internal model?" — can be false during initial import even if a root is linked.

They can disagree during fresh-clone import: ExternalSystem may report a linked Maven root before `MavenProjectsManager.isInitialized()` returns `true`. The local code handles this correctly — it detects the root via ExternalSystem and proceeds to dispatch; if the dispatch fails (manager not initialized), `MavenAsyncFacade` returns `CallResult.Failed`/`Unavailable` and the tool produces a warning rather than crashing.

However, the `legacyForceUpdate` path calls `forceUpdateAllProjectsOrFindAllAvailablePomFiles`, which internally checks `isMavenizedProject()` and routes to `addManagedFiles(collectAllAvailablePomFiles())` if false — effectively doing the right thing for the fresh-clone case regardless.

**Recommendation:** No change needed. The existing layering handles the initialization-race correctly.

---

## 6. Other Observable Issues

### 6a. `invokeFolderResolver` uses reflection on a `suspend fun` — ✗ BUG

`invokeFolderResolver` in `RefreshExternalProjectAction.kt` (lines 369–380) calls `MavenFolderResolver.resolveFoldersAndImport(List)` via plain `Method.invoke`, **but this is a `suspend fun`**. Kotlin suspend functions require a `Continuation` parameter appended to the JVM signature. Calling via `method.invoke(resolver, mavenProjects)` will fail with `NoSuchMethodException` (no method with just `(List)` signature) or an `IllegalArgumentException` about argument count.

From `MavenFolderResolver.kt` (line 27):
```kotlin
suspend fun resolveFoldersAndImport(projects: List<MavenProject>) { ... }
```

The JVM signature is `resolveFoldersAndImport(List, Continuation): Object`. The reflection call in `invokeFolderResolver` does not pass a `Continuation`, so it will always throw and the warning `"MavenFolderResolver invocation failed"` will always be emitted. The `generate_sources` mode is silently broken for the `module_paths`-scoped path, and produces only a warning (not an error to the agent).

`MavenAsyncFacade.invokeSuspendObj` already implements the correct reflective-suspend pattern. `invokeFolderResolver` should be rewritten to use the same `invokeSuspendObj` pattern, or replaced by calling `MavenAsyncFacade.updateAllMavenProjects` (which triggers folder generation as part of the full import cycle) with appropriate scoping.

**Recommendation: Fix `invokeFolderResolver` to pass a `Continuation` via `invokeSuspendObj`.** The `all-projects` path in `dispatchMavenAllProjects` has the same bug — `cs.launch { MavenFolderResolver(project).resolveFoldersAndImport() }` is the correct call pattern (as seen in `UpdateFoldersAction.kt`), requiring a coroutine scope, not direct `Method.invoke`.

### 6b. Approval gate is correctly ordered — ✓ CORRECT

The approval gate fires in Step 3, before any dispatch in Steps 4–6. Module-path resolution and VirtualFile lookup happen after approval, which is correct — the agent sees a human-readable `argsDescription` summary at gate time, and expensive I/O doesn't happen on denied operations.

### 6c. `isMaven` check in Step 6 is correct — ✓ CORRECT

Checks both `readableName` and `id` for "Maven" (case-insensitive). Both `MavenUtil.SYSTEM_ID.readableName` and `.id` are `"Maven"` in the platform, so either check alone would suffice, but the belt-and-suspenders approach is safe.

### 6d. `ExternalSystemUtil.refreshProject` for Gradle — ✓ CORRECT

Non-Maven systems use `ExternalSystemUtil.refreshProject` with `ProgressExecutionMode.IN_BACKGROUND_ASYNC`. This is the canonical path for Gradle reimport and is unaffected by the Maven-specific concerns above.

### 6e. `MavenDownloadSourcesRequest` builder API — ✓ CORRECT

`MavenAsyncFacade.buildDownloadRequest` correctly uses the `builder()` / `forProjects()` / `forAllArtifacts()` / `downloadSources()` / `downloadDocs()` / `build()` chain. From `MavenProjectsManagerEx.kt` (lines 149–203), the builder API exactly matches. The `forAllArtifacts()` call (setting `artifacts = null`) is the correct way to request all artifacts of the selected projects.

---

## Summary Table

| # | Finding | Verdict |
|---|---------|---------|
| 1 | `Maven.UpdateAllFolders` action ID | ✗ ID never existed; but code already patched to use direct `MavenFolderResolver` call — effective |
| 2 | `Maven.DownloadAllSources/Docs/SourcesAndDocs` | ✓ All three registered and correct |
| 3 | `forceUpdateAllProjectsOrFindAllAvailablePomFiles` as fallback | ⚠ `@Deprecated` but not `forRemoval`; still correct as a fallback |
| 4 | Sync vs Reload semantics | ✓ `reload` maps to full `MavenSyncSpec` matching `Maven.Reimport` |
| 5 | Fire-and-forget; awaitable variants exist | ✓ Infrastructure present in `MavenAsyncFacade`; optional enhancement to await with timeout |
| 6 | `isMavenizedProject` vs ExternalSystem check | ✓ Different questions; layering handles initialization race correctly |
| 7 | `invokeFolderResolver` missing `Continuation` on suspend call | **✗ BUG — `generate_sources` always silently fails for scoped module-path path** |
| 8 | Approval gate ordering | ✓ Correct (before dispatch) |
| 9 | Gradle fallback via `ExternalSystemUtil.refreshProject` | ✓ Correct |
| 10 | `MavenDownloadSourcesRequest` builder API | ✓ Matches current JetBrains source |

---

## Sources

- Action IDs: `https://github.com/JetBrains/intellij-community/blob/master/plugins/maven/src/main/resources/intellij.maven.xml` (lines 740–974)
- `MavenProjectsManager.java`: `plugins/maven/src/main/java/org/jetbrains/idea/maven/project/MavenProjectsManager.java` (lines 399, 627–680)
- `MavenProjectsManagerEx.kt`: `plugins/maven/src/main/java/org/jetbrains/idea/maven/project/MavenProjectsManagerEx.kt` (lines 105–134, 149–214)
- `MavenFolderResolver.kt`: `plugins/maven/src/main/java/org/jetbrains/idea/maven/project/MavenFolderResolver.kt` (lines 22–30)
- `UpdateFoldersAction.kt`: `plugins/maven/src/main/java/org/jetbrains/idea/maven/project/actions/UpdateFoldersAction.kt`
- `ReimportAction.kt`: `plugins/maven/src/main/java/org/jetbrains/idea/maven/project/actions/ReimportAction.kt`
- `IncrementalSyncAction.kt`: `plugins/maven/src/main/java/org/jetbrains/idea/maven/project/actions/IncrementalSyncAction.kt`

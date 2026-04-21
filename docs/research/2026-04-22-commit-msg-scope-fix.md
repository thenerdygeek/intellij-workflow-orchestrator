# Commit-Message Scope Fix — API Research

**Date:** 2026-04-22  
**Branch:** feature/telemetry-and-logging  
**File being fixed:** `core/src/main/kotlin/com/workflow/orchestrator/core/vcs/GenerateCommitMessageAction.kt`

---

## Problem

When `VcsDataKeys.SELECTED_CHANGES` is empty (normal in the commit toolbar-action context),
the code fell through to `VcsDataKeys.CHANGES` which returns the **whole active changelist**.
Checking 2 of 10 files generated a commit message covering all 10 files.

---

## API Investigation (IntelliJ 2025.1.7 — ideaIU-2025.1.7-aarch64, `app.jar`)

All findings are from decompiling `app.jar` with `javap -p` and `-verbose`.

### 1. `VcsDataKeys.COMMIT_WORKFLOW_HANDLER`

```
public static final DataKey<CommitWorkflowHandler> COMMIT_WORKFLOW_HANDLER;
```

- **FQN:** `com.intellij.openapi.vcs.VcsDataKeys.COMMIT_WORKFLOW_HANDLER`
- **Stability:** Public, `ACC_PUBLIC ACC_STATIC ACC_FINAL`. No `@ApiStatus.Internal` or
  `@ApiStatus.Experimental` annotation in the constant-pool (confirmed via `javap -verbose`).
- **`CommitWorkflowHandler` interface** — does NOT have `getUi()` or `getIncludedChanges()`:
  ```
  public interface CommitWorkflowHandler {
    getAmendCommitHandler(): AmendCommitHandler
    getCommitAuthorTracker(): CommitAuthorTracker  // default
    getExecutor(id: String): CommitExecutor
    isExecutorEnabled(executor: CommitExecutor): Boolean
    execute(executor: CommitExecutor): Unit
    getState(): CommitWorkflowHandlerState          // default
  }
  ```
  There is no `getUi()` method on `CommitWorkflowHandler`.

### 2. `VcsDataKeys.COMMIT_WORKFLOW_UI` ← **chosen primary path**

```
public static final DataKey<CommitWorkflowUi> COMMIT_WORKFLOW_UI;
```

- **FQN:** `com.intellij.openapi.vcs.VcsDataKeys.COMMIT_WORKFLOW_UI`
- **Stability:** Public, no `@ApiStatus.Internal` or `@ApiStatus.Experimental`.
- **`CommitWorkflowUi` interface:**
  ```
  public interface CommitWorkflowUi extends UiCompatibleDataProvider, Disposable {
    getIncludedChanges(): List<Change>        // checked items
    getDisplayedChanges(): List<Change>       // all visible items
    getIncludedUnversionedFiles(): List<FilePath>
    getDisplayedUnversionedFiles(): List<FilePath>
    addInclusionListener(listener, disposable): Unit
    ...
  }
  ```
  - `getIncludedChanges()` returns `List<Change>` (non-null — Kotlin collection).
  - **Semantic:** exactly the files the user has **checked** (checked = included for the next commit).
  - Available in 2025.1+ (present in 2025.1.7 `app.jar`).

**Correction to the task spec:** The spec expected `COMMIT_WORKFLOW_HANDLER → handler.ui.includedChanges`.
That path does not exist — `CommitWorkflowHandler` has no `getUi()`. The correct direct path is
`COMMIT_WORKFLOW_UI.getIncludedChanges()`. Both data keys are registered in `VcsDataKeys` and both
are populated in the modern commit tool window; the UI key is simpler and avoids the extra hop.

### 3. `Refreshable.PANEL_KEY` / `CheckinProjectPanel.getSelectedChanges()`

```
// Refreshable.java
public interface Refreshable {
  static final DataKey<Refreshable> PANEL_KEY;  // no @ApiStatus annotation
  ...
}

// CheckinProjectPanel.java
public interface CheckinProjectPanel extends CommitMessageI, Refreshable {
  Collection<Change> getSelectedChanges();   // returns Collection<Change>
  CommitWorkflowHandler getCommitWorkflowHandler();
  ...
}
```

- **`getSelectedChanges()` semantic:** "selected" means *checked for commit* in both the
  classic commit dialog and the new commit tool window. This is the same set as
  `CommitWorkflowUi.getIncludedChanges()` but exposed via the older `CheckinProjectPanel` API.
  Confirmed by usage in `HealthCheckCheckinHandlerFactory.kt` (line 70):
  `panel.selectedChanges.mapNotNull { it.virtualFile }` — the health-check handler uses this
  to scope checks to committed files, proving it returns the checked subset.
- **Stability:** No `@ApiStatus` annotations. Available since at least IntelliJ 2022.x.

---

## Chosen Resolution Order

```
1. VcsDataKeys.COMMIT_WORKFLOW_UI  → ui.includedChanges         tag: "COMMIT_WORKFLOW_UI"
2. Refreshable.PANEL_KEY as CheckinProjectPanel → selectedChanges  tag: "CHECKIN_PROJECT_PANEL"
3. VcsDataKeys.SELECTED_CHANGES                                 tag: "SELECTED_CHANGES"
4. Nothing found → (emptyList(), "NONE")
```

**No fallback to `VcsDataKeys.CHANGES`** — that was the root-cause bug.

### Why COMMIT_WORKFLOW_UI over COMMIT_WORKFLOW_HANDLER

`COMMIT_WORKFLOW_HANDLER` has no path to the included changes set (no `getUi()` method).
`COMMIT_WORKFLOW_UI` is the direct data key for the commit UI state, exposes `getIncludedChanges()`
directly, and is populated in the modern non-modal commit tool window — exactly the context where
the original bug manifests.

### Reflection decision

Both `COMMIT_WORKFLOW_UI` and `CommitWorkflowUi` are stable public APIs with no `@ApiStatus`
restrictions. **No reflection required.** We use direct API access.

---

## Downstream Fallback Backdoors Removed

- `filesSummary` in `generateMessage()`: dropped `ChangeListManager.allChanges` fallback.
  If `scopedChanges` is empty (all checked files are in a different repo root), log a warning
  and return `null` from `generateMessage` — better than generating for wrong files.
- `buildCodeContext()`: dropped `ChangeListManager.allChanges.take(5)` fallback.
  If `selectedPaths` is empty at this point it cannot happen (we guard at `actionPerformed`),
  but for safety return empty string.

---

## References

- `javap -p` / `javap -verbose` on `ideaIU-2025.1.7-aarch64/lib/app.jar`
- IntelliJ Community API dump: `platform/vcs-api/api-dump.txt` (via context7)
- Existing plugin usage: `HealthCheckCheckinHandlerFactory.kt:70` (`panel.selectedChanges`)

# Multi-Module Compliance — Plan

Branch: `refactor/cleanup-perf-caching`
Date: 2026-04-26
Driver: ReadOnlyBanner shows "<none>" for PR branch in projects where the
project root has no `.git` and only submodules carry `.git`. Confirmed root
cause: `WorkflowContextService.init` never seeds `activeBranch`. User-driven
refinements expanded scope to (a) honest banner text, (b) functional Switch
Branch action with dirty-tree guard, (c) honest module field naming.

---

## Phase A — Boot-seed `activeBranch`

### Problem
`WorkflowContextService.init` wires `FileEditorManagerListener` and
`VCS_REPOSITORY_MAPPING_UPDATED` but never calls `recomputeFromEditor()`.
At project open with no editor restored:
- `_state.value = WorkflowContext()` → `activeBranch = null`
- User focuses PR → `WorkflowContext.interactionMode` evaluates ReadOnly
  (because `activeBranch == null`)
- Banner shows literal `<none>`

Opening any file fires `selectionChanged` → `recomputeFromEditor()` runs →
`activeBranch` populates from `materialize(getPrimary())` → `repos.firstOrNull()`
→ submodule git repo's `currentBranchName`. Banner re-evaluates Live/ReadOnly.

### Fix
- `WorkflowContextService.recomputeFromEditor()`: change `private` →
  `internal` so `WorkflowContextProjectActivity` can call it.
- `WorkflowContextProjectActivity.execute(project)`: seed BEFORE installing
  the mirror so the editor slice is hydrated before any mirrored event lands
  (avoids redundant cascade fire on boot):
  ```
  service.recomputeFromEditor()              // seed first
  WorkflowEventMirror(project, service).install()
  ```
- Spec doc: this activity's existing R8 commitment ("hydrated state on first
  subscribe") was unfulfilled for the editor-derived slice. This change
  fulfills it.

### Why activity (not init)
- Service `init` runs the moment any code calls `getInstance(project)`. In tests
  that pass a `mockk<Project>`, the GitRepositoryManager isn't real and the
  recompute path would throw or cache stale data. Activity-driven seed runs
  only in real project lifecycle.
- Spec already documents the activity as the R8 hydration point.

### Tests (TDD)
- `WorkflowContextServiceBootTest`: add scenario — service constructed with
  no editor, after `recomputeFromEditor` invoked manually, `state.activeBranch`
  is non-null when `GitRepositoryManager` mock returns a repo.
- `WorkflowContextEditorIntegrationTest`: add scenario — service hydrates
  without throwing when no editor open at project start. Asserts the activity
  path runs cleanly.

---

## Phase B — Honest banner text

### Problem
`ReadOnlyBanner.updateMessage()` line 83: `s.activeBranch ?: "<none>"`.
Literal `<none>` is jargon and shipped to users.

### Fix
Replace with `"branch unknown"`. After Phase A this string should rarely render
(only in the genuine zero-git-repo case).

### Test
`BannerVisibilityFlickerTest`: existing tests assert visibility transitions.
Add one assertion: when `activeBranch == null` and `focusPr != null`, the
message text contains "branch unknown".

---

## Phase C — `activeModule` → `editorModule` + `projectModules`

### Problem (user-driven)
Field name `activeModule` is misleading — IntelliJ has all project modules
"active" simultaneously. Only the *editor's* module is special.

### Fix
1. Rename `WorkflowContext.activeModule: ModuleRef?` → `editorModule: ModuleRef?`.
2. Add `projectModules: List<ModuleRef>` populated from
   `ModuleManager.getInstance(project).modules` wrapped in `readAction { }`.
   Cache invalidation: subscribe to `ModuleListener.TOPIC` (modulesAdded /
   moduleRemoved / modulesRenamed) → relaunch `recomputeFromEditor()`. NOT
   recomputed every cycle.
3. `EnvironmentDetailsBuilder`:
   - Replace "Active module: X" (when X non-null) with "Editor module: X".
   - Always emit "Project modules: A, B, C" (from `projectModules`).

### Touch sites (explicit)
- `core/model/workflow/WorkflowContext.kt` — rename + new field
- `core/workflow/WorkflowContextService.kt` — populate both in `recomputeFromEditor`,
  wire `ModuleListener` subscription
- `agent/prompt/EnvironmentDetailsBuilder.kt:92` — read site (purity guard) +
  rendering at lines downstream
- `core/test/.../InteractionModePurityTest.kt` — usages
- `core/test/.../WorkflowContextEqualsTest.kt` — usages
- All other test usages of `WorkflowContext(activeModule = …)` → `editorModule = …`
- `core/model/workflow/ModuleRef.kt` already exists; reuse as-is

NOTE: `WorkflowContext` is NOT persisted (only `activeTicket` round-trips via
`PluginSettings`); no migration needed.

### Tests
- `EnvironmentDetailsBuilderWorkflowContextTest`: assert "Editor module:" only
  when set, "Project modules:" always when list non-empty.
- `InteractionModePurityTest`: rename references.

---

## Phase D — Functional Switch Branch link

### Problem
`ReadOnlyBanner.switchBranchLink` is a no-op placeholder ("T15 wires actual
branch-checkout"). User confirmed: should checkout `focusPr.fromBranch` for the
selected module's repo only, with dirty-tree guard.

### Fix
1. Resolve target repo: `service.state.value.activeRepo?.localVcsRootPath` →
   match in `GitRepositoryManager.getInstance(project).repositories`. Fallback
   to `RepoContextResolver.getInstance(project).resolveCurrentEditorRepoOrPrimary()`.
2. Dirty-tree check (comprehensive — ChangeListManager misses untracked):
   - **Tracked changes scoped to repo:** iterate `ChangeListManager.getInstance(project).allChanges`,
     filter where `change.virtualFile?.path` starts with `repo.root.path + "/"`
     or equals `repo.root.path`.
   - **Untracked files in repo:** `repo.untrackedFilesHolder.untrackedFilePaths`
     (already scoped to this repo).
   - Combined non-empty → dirty.
   - Both calls EDT-safe (snapshot accessors).
3. If dirty → `Notification` via group `workflow.autodetect` (existing) with
   `NotificationType.WARNING`, abort.
4. If clean → `GitBrancher.getInstance(project).checkout(focusPr.fromBranch, false, listOf(repo), null)`.
   `GitBrancher.checkout` MUST run off-EDT — wrap that call in
   `service.serviceCs.launch { withContext(Dispatchers.IO) { … } }`.
5. ActionLink callback runs on EDT — read state on EDT, then launch IO for the
   actual git work. Banner UI thread is never blocked.

### Tests
- `SwitchBranchActionTest` (new): mock GitRepositoryManager + ChangeListManager.
  Cover (a) clean tree → checkout invoked once, (b) dirty tree → notification
  emitted + checkout NOT invoked.
- Note: cannot easily integration-test GitBrancher without real git. Mocks
  validate the orchestration; real git tested manually in Phase H.

---

## Phase E — Static analysis sweep

### Files to audit
Per existing grep:
- `RepoContextResolver.resolveFromCurrentEditor` ✓ has fallback
- `BranchingService.kt:120, 225` ✓ has fallback
- `MentionSearchProvider.kt:101` (semantically OK — `@selection`)
- `AgentController.kt:522` (agent context — verify)
- `EnvironmentDetailsBuilder.kt:123, 164` (covered by Phase C)
- `ProjectContextTool.kt:283` (agent — verify)
- `QualityDashboardPanel.kt:144` (UI action — verify)
- `PrDetailPanel.kt:2090, 2112` (UI — verify)

### Action
Read each, confirm fallback path. Fix any genuine bugs.

---

## Phase F — Tests (TDD failing first)

Tests written before each phase's implementation. See per-phase test sections.

---

## Phase G — Full test suite

- `./gradlew test` (all 9 modules)
- `./gradlew verifyPlugin`
- Triage each failure: test-design vs genuine bug. Fix genuine bugs.

---

## Phase H — Manual smoke

- Multi-module project with no `.git` at root, `.git` in submodules
- Close all editors (File → Close All)
- Reopen project → focus PR via PR dashboard
- Expected: banner shows real branch, NOT "branch unknown" / "<none>"
- Click "Switch branch" with clean tree → checks out
- Modify a file, click "Switch branch" → warning notification, no checkout

---

## Phase I — Update spec/architecture docs

Per `CLAUDE.md` "Update module CLAUDE.md + docs/architecture/ in same commit
as architecture changes":

- `docs/architecture/workflow-context-design.md` — update §3.2 to rename
  `activeModule` → `editorModule` and add `projectModules`. Update R8 to
  explicitly mention editor-derived slice is hydrated by `WorkflowContextProjectActivity`.
- `docs/architecture/phase5-workflow-context-plan.md` — note retroactive fix.
- `core/CLAUDE.md` — update WorkflowContextService section if field names appear.
- `agent/CLAUDE.md` — if it documents EnvironmentDetailsBuilder fields.

---

## Out of scope

- Persisting `focusPr` across IDE restart (Phase 5 design says session-only)
- Re-deriving `focusBuild` from `activeBranch` outside PR focus (deferred)
- Full agent context redesign beyond `editorModule`/`projectModules` lines

---

## Risk register

| Risk | Mitigation |
|---|---|
| Renaming `activeModule` breaks tests | Compile-time error catches all; tests in same change |
| `recomputeFromEditor` made `internal` exposes more API surface | Only used by activity; documented |
| `ChangeListManager.getChangesIn` API name uncertain across platform versions | Verify via grep + ToolSearch context7 |
| Notification group `workflow.banner` may not exist | Reuse `workflow.autodetect` or check `plugin.xml` for registered groups |
| Full test run takes time | Run :core first, then full sweep |

# Phase 5 — `WorkflowContextService` Design

**Branch:** `refactor/cleanup-perf-caching` (Phase 5)
**Date:** 2026-04-26
**Status:** Brainstorm complete; awaiting subagent verification → writing-plans → execution.
**Supersedes:** the Phase 5 gate in `project_branch_refactor_cleanup_perf_caching.md` and the deferred `project_active_ticket_visibility.md`.

## 1. Summary

Today the IntelliJ plugin has three uncoordinated state surfaces:

1. `EventBus.events: SharedFlow<WorkflowEvent>` — push-only, no replay (`replay = 0`, buffer 64). Late subscribers see nothing until the next emit.
2. `EventBus.prContextMap: ConcurrentHashMap<String, PrContext>` — repo→last-PR snapshot, mutated as a side-effect inside `EventBus.emit(PrSelected)`.
3. `PluginSettings.activeTicketId` / `activeTicketSummary` — XML-persisted per-project. Read directly by the active-ticket bar and several gear actions.

Plus per-panel local resolution (panels independently call `RepoContextResolver`, parse branch names, fetch PRs). The result is the symptom set the user reported:

- Build tab's PR bar says "No PR found" while job stages render (within-tab incoherence — two readers, two sources, two times).
- Module label shows one module while job list is for another (same).
- Selecting a PR in PR tab does not update Build / Quality (no cross-tab propagation).
- Selecting a sprint ticket as active does not propagate to PR filter / Handover / commit-prefix (same).
- Dialogs go out of sync with the underlying tab state.

Phase 5 introduces a single project-level service, `WorkflowContextService` in `:core`, that holds the workflow state in a `StateFlow<WorkflowContext>`. Every panel, dialog, bar, and the agent's environment-details builder reads from this one cell. Mutators apply project-level invariants (cascades, persistence, listener bumps). The existing `EventBus` keeps emitting events for non-state notifications during a transition window; `prContextMap` is removed.

## 2. Decisions table

| # | Decision | Choice | Rationale |
|---|---|---|---|
| 1 | Mental model | **Anchor + transient focus** | Active ticket is sticky (`Start Work` lasts hours/days); PR/build are browseable without losing the anchor. |
| 2 | Field bucketing | Anchor: `activeTicket`. Editor-derived: `activeRepo`, `activeBranch`, `activeModule`. Focus chain: `focusPr → focusBuild → focusQualityScope`. | One slot per kind of update; eliminates ambiguity about who writes what. |
| 3 | Start Work behavior | **a.ii — anchor + auto-seed focus chain** | If an open PR matches the ticket key, focus the PR (and cascade build / quality) so all tabs are immediately coherent. |
| 4 | Conflict rule | Derived `interactionMode = Live | ReadOnly` based on `activeBranch == focusPr?.fromBranch` | Branch mismatch is a *correctness* issue (line numbers don't match), not a UX preference. |
| 5 | ReadOnly affordance | **B — per-panel banner + per-control disable** | Banner makes the state legible with visible escape hatches; per-control disable prevents misleading interactions. |
| 6 | Persistence | **A — anchor only** | Matches today's behavior (no regression on active ticket survival); avoids stale-PR confusion across restarts. |
| 7 | Agent visibility | **A — read-only injection** | Agent sees the same anchor as the active-ticket bar; no new agent-driven UI mutation surface (yet). |

## 3. Architecture

### 3.1 Data model

```kotlin
// :core/model/workflow/WorkflowContext.kt
data class WorkflowContext(
    val activeTicket: TicketRef? = null,                // anchor
    val activeRepo: RepoRef? = null,                    // editor-derived
    val activeBranch: String? = null,                   // editor-derived
    val activeModule: ModuleRef? = null,                // editor-derived
    val focusPr: PrRef? = null,                         // focus chain
    val focusBuild: BuildRef? = null,                   // derived from focusPr or activeBranch
    val focusQualityScope: QualityScope? = null,        // derived from focusBuild
) {
    val interactionMode: InteractionMode get() = when {
        focusPr == null -> InteractionMode.Live
        activeBranch != null && focusPr.fromBranch == activeBranch -> InteractionMode.Live
        else -> InteractionMode.ReadOnly
    }
}

// INVARIANT: interactionMode (and any future derived property exposed via state.map { ... })
// MUST be a pure function of declared WorkflowContext fields. Reading external state inside
// a derived getter (e.g., git4idea APIs, settings, system clock) breaks the
// state.map { it.interactionMode }.distinctUntilChanged() flow — the underlying state will
// not change when external state changes, so the flow will silently miss transitions.
// Any new contributing factor must be added as a declared field on WorkflowContext.
// Enforced by InteractionModePurityTest in §9.1.

enum class InteractionMode { Live, ReadOnly }

// :core/model/workflow/refs.kt — small immutable value types
data class TicketRef(val key: String, val summary: String)
data class RepoRef(val name: String, val projectKey: String, val repoSlug: String, val localVcsRootPath: String)
data class PrRef(
    val prId: Int,
    val fromBranch: String,
    val toBranch: String,
    val repoName: String,
    val bambooPlanKey: String?,
    val sonarProjectKey: String?,
)
data class BuildRef(val planKey: String, val buildNumber: Int, val branch: String, val selectedJobKey: String?)
data class QualityScope(val sonarProjectKey: String, val branchName: String?, val moduleKey: String?)
data class ModuleRef(val name: String, val rootPath: String)
```

`*Ref` types are deliberately tiny. They mirror existing `WorkflowEvent` payloads so the migration is a structural rename, not a re-modeling.

### 3.2 Service

```kotlin
// :core/workflow/WorkflowContextService.kt
@Service(Service.Level.PROJECT)
class WorkflowContextService(
    private val project: Project,
    private val cs: CoroutineScope,                     // platform-injected (Phase 4 convention)
) {
    private val _state = MutableStateFlow(WorkflowContext())
    val state: StateFlow<WorkflowContext> = _state.asStateFlow()

    // --- Anchor mutators (persist) ---
    suspend fun setActiveTicket(ticket: TicketRef?)     // also: PluginSettings write + autoSeedFocusFromTicket()
    fun clearActiveTicket()                             // sync wrapper that launches setActiveTicket(null) on cs

    // --- Focus mutators (session-only) ---
    suspend fun focusPr(pr: PrRef?)                     // cascades: lookup latest build → focusBuild → focusQualityScope
    suspend fun focusBuild(build: BuildRef?)            // cascades: focusQualityScope; rare, usually derived
    fun clearFocus()                                    // sync wrapper

    // --- Editor-derived (internal; called by the listener wiring below) ---
    internal suspend fun onEditorRepoChanged(repo: RepoRef?, branch: String?, module: ModuleRef?)

    // --- Convenience derivations for narrow subscribers ---
    val activeTicketFlow: StateFlow<TicketRef?> = state.map { it.activeTicket }.stateIn(cs, SharingStarted.Eagerly, null)
    val interactionModeFlow: StateFlow<InteractionMode> = state.map { it.interactionMode }.distinctUntilChanged().stateIn(cs, SharingStarted.Eagerly, InteractionMode.Live)

    init {
        wireEditorListeners()                           // VCS_REPOSITORY_MAPPING_UPDATED + FILE_EDITOR_MANAGER + BranchChangeListener
        loadAnchorFromSettings()                        // hydrate activeTicket from PluginSettings
    }

    companion object {
        fun getInstance(project: Project): WorkflowContextService = project.service()
    }
}
```

Service constructor matches the **Phase 4 service-injected scope convention** (no `CoroutineScope(SupervisorJob() + …)` allocation).

## 4. Sources of truth & write paths

### 4.0 Cascade serialization (mutex-guarded)

Every public mutator (`setActiveTicket`, `focusPr`, `focusBuild`) and every internal mirror invocation acquires a single project-scoped `Mutex` for the duration of the cascade. The cascade body — including any HTTP lookups — runs **inline under the lock**. This produces two guarantees:

- **Cascade atomicity.** A cascade composes its entire next `WorkflowContext` and writes `_state.value =` exactly once. No subscriber observes a half-applied cascade.
- **No interleaving (last-wins).** If two `focusPr()` calls arrive at t=0 and t=200ms (the C1 race), the second blocks on the mutex until the first fully completes (including its HTTP). The final `state.value` reflects the latest call.

The mirror (§5.1 step 4) routes through the same mutex. Combined with the `state.value`-equality guard, this eliminates the C2 feedback path: by the time the mirror processes a legacy event whose state was just updated by a direct mutator call, `state.value` already matches the event's payload → no-op.

```kotlin
private val cascadeMutex = Mutex()

suspend fun focusPr(pr: PrRef?) = cascadeMutex.withLock {
    val newCtx = if (pr == null) {
        _state.value.copy(focusPr = null, focusBuild = null, focusQualityScope = null)
    } else {
        computeFocusForPr(_state.value, pr)  // does HTTP build lookup off-EDT
    }
    _state.value = newCtx
}
```

**Cancel-previous semantics considered and dropped.** Earlier drafts launched the cascade body on `cs` and used a `currentFocusJob: Job?` field to `cancelAndJoin()` the previous launch — letting a newer click immediately abort an older PR's in-flight HTTP. That design was simplified to inline-mutex during plan v2 because (a) the 5s `withTimeoutOrNull` already bounds the worst-case latency a rapid clicker faces, (b) `runTest` virtual-time interaction with launched HTTP cascades is fragile and was producing spurious test failures, and (c) the C1 correctness fix is fully provided by the mutex alone — cancel-previous was a UX optimization, not a correctness requirement. Revisit if real-world rapid-click latency becomes a complaint.


Each field has exactly one writer.

| Field | Writer | Trigger |
|---|---|---|
| `activeTicket` | `setActiveTicket()` | Sprint tab "Start Work"; branch-change ticket-detection accept; gear "Clear Active Ticket" |
| `activeRepo`, `activeBranch`, `activeModule` | `onEditorRepoChanged()` *(internal)* | `FileEditorManagerListener.selectionChanged`; `VCS_REPOSITORY_MAPPING_UPDATED`; `BranchChangeListener` |
| `focusPr` | `focusPr()` | PR tab row click; auto-seeded by `setActiveTicket()` if a PR matches ticket key |
| `focusBuild` | `focusBuild()` *(rare; usually derived)* | Build tab "pin this build" *(deferred — see §10)*; otherwise re-derived from `focusPr.fromBranch` whenever `focusPr` or `activeBranch` changes |
| `focusQualityScope` | derived only | Re-derived from `focusBuild.selectedJob` whenever `focusBuild` changes |

### 4.1 Cascade on `focusPr(pr)`

Runs inside the mutex (§4.0). For non-null `pr`:

1. (No cancel-previous step — mutex serialization provides last-wins semantics; see §4.0.)
2. Lookup latest build for `pr.fromBranch` via `BambooApiClient` (suspend, off-EDT — `withContext(Dispatchers.IO)`). Wrapped in `withTimeoutOrNull(5_000)` per R2.
3. Compose new context with `focusPr = pr`, `focusBuild = b` (or null on timeout), `focusQualityScope = q`.
4. Single `_state.value = newContext` — **one observable transition.**
5. **Mutator does NOT emit any legacy event.** Legacy event re-emission is the call site's responsibility during the migration window — see §5.

For `pr == null` (Clear PR focus from banner, or unmatched anchor change):

1. Cancel any in-flight focus job.
2. Re-derive `focusBuild` from `activeBranch` (`computeFocusFromBranch(activeBranch)`); null if `activeBranch` is null.
3. Re-derive `focusQualityScope` from new `focusBuild`.
4. Single `_state.value = newContext` with `focusPr = null` and the re-derived `focusBuild` / `focusQualityScope`.

`computeFocusFromBranch` only fires HTTP if `activeBranch != previous activeBranch` (cached result otherwise — avoids editor-storm HTTP traffic per NB11).

### 4.2 Cascade on `setActiveTicket(t)` (auto-seed per a.ii)

Runs inside the mutex (§4.0).

1. **Persist `t` to `PluginSettings` BEFORE any suspend point.** This guarantees disk-state never lags in-memory state — a project close mid-cascade leaves persistence consistent with what the next boot will load.
2. Search open PRs for one whose source branch contains `t.key` (regex via existing `TicketKeyExtractor`). When multiple match, the **highest PR id wins** (deterministic across IDE restarts).
3. If a match exists, compute focus chain (internal helper `computeFocusForPr(matchingPr)`).
4. Single `_state.value = newContext` carrying both `activeTicket` and the new focus chain (or just `activeTicket` if no PR matched).
5. Mutator does NOT emit legacy events (same rule as §4.1.5).

### 4.3 Cascade on editor changes

Runs inside the mutex (§4.0).

1. Listener calls `onEditorRepoChanged(repo, branch, module)` from a coroutine on `cs`.
2. Compose new context with updated `activeRepo`, `activeBranch`, `activeModule`.
3. **`focusBuild` is NOT re-derived on editor changes** when `focusPr != null` — `focusBuild` is bound to the focused PR's source branch, not the editor's branch. When `focusPr == null`, `focusBuild` remains the value from the most recent `focusPr(null)` call (which itself was derived from `activeBranch` at that point); editor-only branch changes do NOT trigger an HTTP build re-lookup. This avoids the N+1 traffic NB11 flagged. Panels that need "build for current branch (regardless of focus)" will subscribe to a separate `latestBuildForBranchFlow` (deferred — see §10).
4. `_state.value = newContext` — single emission. `interactionMode` is computed-on-read; if `focusPr != null` and the new `activeBranch` differs, the next `state.value.interactionMode` returns `ReadOnly` automatically.

### 4.4 Single-merged-emission invariant

Every cascade computes the entire next `WorkflowContext` immutably, then performs **one** `_state.value = next`. Subscribers MUST observe a coherent snapshot. This invariant is the structural reason "PR bar shows X, job stages render Y" becomes impossible.

**Boot-time exception (documented carve-out):** §4.5 describes service initialization. Boot performs *two* emissions — a synchronous anchor-load and an asynchronous PR auto-seed. This is the only place in the design where two emissions per logical cascade occur. Subscribers using `state.collect { }` see both transitions naturally; subscribers reading `state.value` once at construction see the synchronous anchor (never `null`).

### 4.5 Service initialization (boot semantics)

`init` does only synchronous work:

1. `wireEditorListeners()` — registers `FileEditorManagerListener`, `VCS_REPOSITORY_MAPPING_UPDATED`, `BranchChangeListener` via `project.messageBus.connect(this)`. `@Service` instances are platform-disposed in 2024.1+; this connect call is the sanctioned pattern (see also `RepoContextResolver`).
2. `loadAnchorFromSettings()` — reads `PluginSettings.activeTicketId` / `activeTicketSummary`, sets `_state.value = WorkflowContext(activeTicket = persistedTicket)` synchronously. Subscribers reading `state.value` immediately see the persisted anchor. No HTTP, no suspend points.
3. `cs.launch { autoSeedFromAnchor() }` — if a persisted anchor exists, asynchronously runs the `setActiveTicket(persistedTicket)` cascade *just for the auto-seed half* (skipping the persistence step since it's already on disk). This may produce a second emission as `focusPr` populates. Boot-time only.

The mirror (§5.1 step 4) is installed via a separate `ProjectActivity` that runs after service construction but before any panel emits, guaranteeing no events are dropped at startup.

## 5. Migration path

### 5.1 Phase 5a (this branch — additive)

1. Add `WorkflowContextService`, `WorkflowContext`, all `*Ref` types, listener wiring. Service is born subscribed but unused.
2. Migrate the **active-ticket bar** (`WorkflowToolWindowFactory.setupActiveTicketBar`) to subscribe to `service.activeTicketFlow` instead of `EventBus.events.filterIsInstance<TicketChanged>()` + `PluginSettings`. Single subscription replaces two.
3. Migrate **`EventBus.prContextMap` reads** to `service.state.value.focusPr` + `service.state.value.activeRepo`. Delete `prContextMap` and the side-effect mutation inside `EventBus.emit`.
4. **Bridge legacy events into the service** via `WorkflowEventMirror`: a startup subscriber installed on `EventBus.events` that forwards `PrSelected → service.focusPr(...)`, `TicketChanged → service.setActiveTicket(...)`, `BranchChanged → service.onEditorRepoChanged(...)`. **One-way only** — mirror reads events, never emits them. The mirror checks `state.value` first and no-ops if the incoming payload already matches (loop prevention). Existing emit sites stay untouched in 5a; the mirror keeps state in sync until each emit site is migrated to call the mutator directly. **Migration rule:** when an emit site is converted to call a mutator, the call site explicitly re-emits the legacy event after the mutator returns (until 5b deletes the event subscribers entirely).
5. For each panel that today resolves "current ticket / PR / branch / build" locally, replace with `service.state.collect`:
   - `BuildDashboardPanel` (PrBar + job stages — same `state.value` snapshot). **Migration constraint:** PrBar and job stages MUST be migrated in the same commit; partially-migrated state would re-introduce the original within-tab incoherence bug from a different source pair (legacy local vs. service).
   - `QualityDashboardPanel`
   - `PrDashboardPanel` (focus mutator on row click)
   - `PrDetailPanel`
   - `SprintDashboardPanel` (Start Work calls `service.setActiveTicket`)
   - `HandoverPanel`
   - `AutomationPanel`
6. Add the `ReadOnlyBanner` to Build / Quality / PrDetail panels (see §7).
7. Wire the agent's `EnvironmentDetailsBuilder` to read `service.state.value` (see §6).

### 5.2 Phase 5b (post-merge cleanup, separate branch)

Once all panels read from the service, the `WorkflowEvent` mirror is the only emitter. At that point the legacy event subtypes (`PrSelected`, `TicketChanged`, `BranchChanged`) can be deleted along with their now-empty subscribers. **Out of scope for Phase 5a** — the dual-write transition keeps the diff bounded and reversible.

### 5.3 Decision: keep dual-emission during 5a (mirror-bridged)

Push-back point #2 from the design conversation: "kill subscribers immediately or dual-write?"
**Choice: keep both signals alive, bridge via mirror.** Reasons:
- The 7 panel migrations in step 5 are independent — bridging via mirror lets them land as separate commits without breaking each other.
- The `EventBus` is also consumed by the agent webview bridge, by mention shortcuts, and by tests. Cataloging and rewiring all of those in one branch inflates scope.
- Single-source-of-truth is a Phase 5b cleanup, not a Phase 5 hard requirement.

Loop-prevention: the mirror is one-way (event → service); mutators do NOT emit events. Migrated call sites that want to keep legacy subscribers working re-emit the event explicitly after calling the mutator. The mirror's `state.value`-equality check is belt-and-braces against re-emission feedback.

## 6. Agent integration

Per decision #7 (read-only injection):

### 6.1 Environment-details block

`EnvironmentDetailsBuilder.build()` (already `suspend` after Phase 4 D8b) gets a new `appendWorkflowContext()` step:

```
<workflow_context>
Active ticket: AFTER8TE-912 — "Fix login redirect"
Active branch: feat/login-fix
Active repo: backend-api
Focused PR: #42 (feat/login-fix → main)
Interaction mode: Live
</workflow_context>
```

Block omitted entirely when `state.value` is empty (all-null) — don't pollute the prompt with placeholder text.

### 6.2 Mention shortcuts

`@ticket`, `@pr`, `@build` resolve via `service.state.value` first; fall back to settings/legacy paths if the field is null.

### 6.3 No new tools

No `set_active_ticket(key)`, `focus_pr(id)`, `clear_focus()` agent tools. Decision (B) is deferred until (A) is proven and a real use case appears.

### 6.4 Service access from `:agent`

`:agent` already depends only on `:core`. `WorkflowContextService` lives in `:core`; agent calls `WorkflowContextService.getInstance(project)` directly. No new module-boundary wiring needed.

## 7. ReadOnly affordance

### 7.1 `ReadOnlyBanner` component

New file `:core/workflow/ui/ReadOnlyBanner.kt` — a slim Swing component:

```kotlin
class ReadOnlyBanner(project: Project) : JPanel(BorderLayout()), Disposable {
    // Amber background (StatusColors.WARNING_BG).
    // Left: text "Viewing PR #42 (bugfix/xyz). You're on feat/abc — interactions disabled."
    // Right: two link actions: "Switch branch" and "Clear PR focus"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
    init { scope.launch { service.interactionModeFlow.collect { isVisible = (it == ReadOnly); revalidate() } } }
    override fun dispose() { scope.cancel() }
}
```

Banner owns its own `scope` (banners are not `@Service`-managed; the platform service-injected scope rule applies to `@Service` classes only). Parent panel registers the banner as a child `Disposable` (`Disposer.register(parent, banner)` — Phase 4 C convention) so dispose cascades from the tool window.

### 7.2 Per-control disable contract

Helper in `:core/workflow/ui/`:

```kotlin
fun bindLiveOnlyEnablement(
    parent: Disposable,
    service: WorkflowContextService,
    vararg controls: JComponent,
)
```

Implementation: creates a child `CoroutineScope(SupervisorJob() + Dispatchers.EDT)`, subscribes to `interactionModeFlow`, toggles `isEnabled` on every control, and updates each control's tooltip to *"Disabled: PR is on a different branch. Switch to bugfix/xyz to enable."* `Disposer.register(parent) { scope.cancel() }` ensures the subscription dies with the panel. Each panel calls this once for its set of live-only controls.

### 7.3 Live-only enumeration (gate on `Live`)

- Gutter markers (coverage, sonar issues)
- Click-to-fix intentions
- Navigate-to-failing-test-line action
- Breakpoint-from-stacktrace
- Inline diff editor open from "view changes"
- Checkout-PR-locally action
- Comment-anchor-to-line

### 7.4 View-anywhere (always safe; no gating)

- PR metadata (title, description, author, dates, status badges)
- Comments list (read)
- Build status badge / build log viewer
- Coverage % numbers (panel-level, not line-anchored)
- File list (read)
- Approve / decline / merge buttons (server-side, not document-anchored)
- Write-comment-on-PR (server-side anchor, not local document line)

## 8. Persistence

Anchor only. Reuses today's storage:

- `PluginSettings.state.activeTicketId: String?` — the ticket key.
- `PluginSettings.state.activeTicketSummary: String?` — the cached summary.

`WorkflowContextService.init` reads both into `_state` on construction. `setActiveTicket()` writes both back atomically before emitting state. No new persisted fields. No `focusPr` / `focusBuild` persistence — by design.

## 9. Testing strategy

### 9.1 Unit tests on `WorkflowContextService` (in `:core`)

- `setActiveTicket` persists to settings + emits new state.
- `setActiveTicket` with matching open PR auto-seeds `focusPr` + cascades focus chain in **one** state emission.
- `setActiveTicket` with no matching PR leaves focus chain untouched.
- `focusPr(pr)` cascades `focusBuild` and `focusQualityScope` in **one** state emission.
- `focusPr(null)` clears the chain in **one** emission.
- `interactionMode == Live` when `focusPr == null`.
- `interactionMode == Live` when `focusPr.fromBranch == activeBranch`.
- `interactionMode == ReadOnly` when branches differ.
- Editor-listener emits update `activeBranch`; `interactionMode` recomputes correctly.
- `loadAnchorFromSettings()` hydrates `activeTicket` on init.

### 9.2 Characterization tests — lock the original symptoms shut

These are the structural tests that prove Phase 5 fixed the reported bugs.

- `BuildDashboardPanelTest`: `service.focusPr(prX)` → assert PrBar's last-rendered `focusPr` and job-stages' last-rendered `focusPr` are referentially the same `WorkflowContext` instance from `state.value` (both subscribers see the same snapshot from the same emission).
- `PrDashboardCrossTabTest`: simulate PR row click → assert `BuildDashboardPanel` and `QualityDashboardPanel` re-render with new `focusPr` within the same `state.collect` tick.
- `SprintStartWorkTest`: simulate Start Work on a ticket whose key matches an open PR's source branch → assert `focusPr` cascade fires.
- `BranchMismatchInteractionModeTest`: editor branch = `feat/abc`, focus PR on `bugfix/xyz` → `interactionMode == ReadOnly`. Then simulate branch checkout to `bugfix/xyz` → `interactionMode == Live`.
- `WorkflowEventMirrorTest`: emit `WorkflowEvent.PrSelected` directly onto `EventBus.events` → mirror updates `service.state.focusPr`. Re-emit the same event → mirror no-ops (loop prevention via `state.value`-equality check).
- `WorkflowEventMirrorRaceTest`: emit two distinct `PrSelected` events in rapid succession (event A then event B) → mutex serializes; both cascades complete; assert exactly two `_state.value =` writes occur (one per event) AND the final `state.value` reflects event B (the later-arriving event wins). Asserts the C1+C2 fix.
- `InteractionModePurityTest`: assert `interactionMode` only reads declared fields. Implementation: construct two `WorkflowContext` instances differing only in non-declared external state (e.g., system time); assert `interactionMode` equal. Also: kotlin-reflect introspection sanity check that `WorkflowContext::interactionMode` getter references no field outside `WorkflowContext`. Enforces the §3.1 invariant.
- `PersistenceRoundTripTest`: `setActiveTicket(t)` → re-instantiate `WorkflowContextService` with same `Project` → assert `state.value.activeTicket == t` after `loadAnchorFromSettings()`. Validates §4.5 boot synchronization.
- `BannerVisibilityFlickerTest`: rapidly call `focusPr(prX)` / `focusPr(null)` 10 times → assert banner-visibility transitions ≤ 2 (validates `distinctUntilChanged` per R4).

### 9.3 Integration test (`:core`)

One smoke test wiring real `FileEditorManager` + `GitRepositoryManager` listeners through the platform test fixture, asserting `activeRepo` / `activeBranch` / `activeModule` update correctly on simulated editor selection and branch checkout.

## 10. Out of scope / deferred

- **Phase 5b** — delete `WorkflowEvent.PrSelected/TicketChanged/BranchChanged` after all subscribers migrated.
- **`focusBuild` "pin" capability** — design includes the mutator but no UI invokes it. Drop from initial implementation; revisit when a panel needs it. *(Push-back point #1 from the design conversation: defer.)*
- **Agent write tools** (`set_active_ticket`, `focus_pr`) — decision (B); revisit after (A) ships.
- **Multi-project workflow context** — `WorkflowContextService` is `Service.Level.PROJECT`. No cross-project coordination.
- **Multi-window same project** — IntelliJ allows the same Project open in multiple frames (Window > New Window for Project). All frames share the single `Service.Level.PROJECT` instance, so all frames see the same context. Intentional.
- **Background "follow active branch" auto-focus** — when the user checks out a branch that is the source of an open PR, do we auto-focus that PR? Behavior change with surprise risk; defer until UX feedback.
- **`latestBuildForBranchFlow` separate flow** — Build tab can show "build for current branch" when no PR is focused. Currently §4.3 doesn't re-derive `focusBuild` on editor changes; if a panel needs that, it'll subscribe to a small dedicated flow that polls the latest build for `activeBranch` (with cache). Out of scope for 5a; revisit if Build tab UX requires it.
- **Persistence of `focusPr` across restart** — decision (A) rejected this. If users complain about losing PR focus on restart, revisit with a TTL (focus expires after N hours).
- **`docs/architecture/threading-model.md` and `index.html` updates** for the new service — same-commit rule applies, but listed here for explicit scope.

## 11. Open risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | Dual-write divergence between `EventBus` and service | `WorkflowEventMirror` helper enforces single call site; characterization test in §9.2 last item asserts the invariant. |
| R2 | `focusPr()` triggers HTTP (Bamboo build lookup) on every PR row click | Already off-EDT (`suspend`). Add 5s `withTimeoutOrNull`; on timeout, set `focusBuild = null` and let the Build tab show "loading…". Don't block focus emission. |
| R3 | Editor-listener storm (rapid editor switches) bumps state O(n) times | `_state.value` is a no-op when value is `equals`-equal — no spurious downstream emissions. Verify `WorkflowContext.equals()` does deep equality on all fields (`data class` gives this for free). |
| R4 | Banner visibility flicker on rapid focus change | `interactionModeFlow` uses `distinctUntilChanged` — only changes between Live ↔ ReadOnly fire. Visibility toggle is one boolean; no flicker. |
| R5 | Agent prompt growth from `<workflow_context>` block | Block is ≤7 short lines; omitted entirely when state is empty. Negligible token cost. |
| R6 | Phase 5 breaks an active-ticket-bar consumer (e.g., gear menu actions) | Active-ticket-bar migration is one of the first commits in 5a — characterization test asserts label and "Open in Jira" gear action still work. |
| R7 | A panel-author forgets to gate a live-only control | Per-control disable contract requires explicit `bindLiveOnlyEnablement(...)` call; live-only enumeration in §7.3 is the canonical list to audit against. Add a doc note in `:core/CLAUDE.md`. |
| R8 | Mirror startup race — early panel emission lost before mirror subscribes | Mirror installed via `ProjectActivity` (or equivalent), not lazy on first `service.getInstance()`. Guarantees subscription before any panel construction. See §4.5 step 3. |
| R9 | Future maintainer adds external-state factor to `interactionMode` (or new derived getter) | §3.1 invariant + `InteractionModePurityTest` (§9.1) catch this. Reviewers of any change to `WorkflowContext` derived getters MUST verify purity. |

## 12. Exit criteria

- (a) No panel resolves repo / branch / ticket / PR / build on its own — all reads go through `WorkflowContextService.state`.
- (b) Characterization test `PrDashboardCrossTabTest` passes: selecting a PR updates Build + Quality tabs in the same refresh tick.
- (c) Characterization test `SprintStartWorkTest` passes: selecting a sprint ticket propagates to PR filter / Handover / commit-prefix instantly.
- (d) The two user-reported within-tab mismatches (PR bar vs. job stages; module vs. jobs) are structurally impossible — single `state.value` snapshot drives both readers in `BuildDashboardPanelTest`.
- (e) `interactionMode` flips correctly across the 4 cases in §9.1; per-panel banner appears with working escape hatches in `runIde` smoke test.
- (f) Agent system prompt includes `<workflow_context>` when state is non-empty; mention shortcuts resolve via service.
- (g) `EventBus.prContextMap` removed; `WorkflowEventMirror` helper used at every event-emit site.
- (h) `./gradlew verifyPlugin buildPlugin` green on IU-251/252/253; module tests pass.
- (i) `docs/architecture/index.html` + `threading-model.md` + module `CLAUDE.md` files updated in the same commit cycle.

## 13. References

- Branch plan memory: `project_branch_refactor_cleanup_perf_caching.md` § Phase 5.
- Today's surfaces: `core/events/EventBus.kt`, `core/events/WorkflowEvent.kt`, `core/settings/RepoContextResolver.kt`, `core/toolwindow/WorkflowToolWindowFactory.kt`.
- Phase 4 service-injected scope convention: `core/CLAUDE.md` § "Service & threading conventions (Phase 4)".
- Phase 4 Disposable cascade: `phase4-prong-c-plan.md`.
- `TicketKeyExtractor`: `core/util/TicketKeyExtractor.kt` (used by auto-seed).

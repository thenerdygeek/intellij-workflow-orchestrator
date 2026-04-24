# Phase 2 Violations Audit — `refactor/cleanup-perf-caching`

Produced 2026-04-24 at start of Phase 2 (architectural optimization). Baseline: branch `refactor/cleanup-perf-caching` @ `fb378bfd` (Phase 1 complete, v0.83.24-beta).

## 1. Gradle dependency baseline

- `:core` depends on: *(no project deps)* — IntelliJ platform + okhttp + coroutines/serialization
- `:jira` depends on: `project(":core")`
- `:bamboo` depends on: `project(":core")`
- `:sonar` depends on: `project(":core")`
- `:pullrequest` depends on: `project(":core")` (no `okhttp` — goes through core)
- `:automation` depends on: `project(":core")` + `sqlite-jdbc`
- `:handover` depends on: `project(":core")` (no `okhttp`)
- `:agent` depends on: `project(":core")` + `postgresql-jdbc` + `sqlite-jdbc`
- `:mock-server` — standalone Ktor test server, included in `settings.gradle.kts`

Ground truth: every feature module and `:agent` declares ONLY `:core`. Rule B is enforced at the gradle level. Any cross-feature or raw-client pollution below is **source-level only**.

## 2. Executive summary

- **Rule A (Agent bypass):** 0 hard violations. Every integration tool in `agent/tools/integration/*` routes through `ServiceLookup` → `core/services/*`. Agent imports zero feature-module FQNs. Dual-`ToolResult` types are intentional and bridged by `ServiceLookup.toAgentToolResult()` — noted, not a violation.
- **Rule B (Feature↔Feature):** 0 violations. Cross-module flow already funnels through `EventBus` and the two EPs in `core`. Only kdoc/comment references exist.
- **Rule C (Intra-module layers):** **7 clear + 2 borderline violations**, concentrated in `:jira` (5) and `:automation` (1) + `:bamboo/run` (1).
- **Worst offender:** `jira/listeners/BranchChangeTicketDetector.kt` — 3 distinct sub-rule hits in one file.
- **Best behaved:** `:pullrequest` (no `api/` folder; uses `:core` `BitbucketService`/`BitbucketApiClient`). Also clean: `:handover`, `:sonar`, `:agent`.
- **Estimated commits:** 7–8 small/medium, ~700 LOC net. Phase 2 is much smaller than Phase 1.

## 3. Violation #1 — Agent tool bypass

No hits. Agent reads `core/services/*` via `ServiceLookup` and converts with `toAgentToolResult()` at `agent/tools/integration/ServiceLookup.kt:62`. `rg '^import com\.workflow\.orchestrator\.(jira|bamboo|sonar|pullrequest|automation|handover)\.' agent/src/main/kotlin` → empty.

**Dual `ToolResult` design (for reference, not a violation):**
- `com.workflow.orchestrator.agent.tools.ToolResult` (non-generic, LLM-shaped: `content`, `summary`, `tokenEstimate`, artifacts) — `agent/tools/AgentTool.kt:190`
- `com.workflow.orchestrator.core.services.ToolResult<T>` (generic, UI+service-shaped) — `core/services/ToolResult.kt:17`

Bridged one-way (`core → agent`) via `ServiceLookup.kt:62`. Keep as-is in Phase 2.

## 4. Violation #2 — Feature-to-feature imports

No hits. Rule B is fully enforced.

## 5. Violation #3 — Layer violations

| # | File:Line | Sub-rule | What it does | Fix | Size |
|---|---|---|---|---|---|
| 3.1 | `jira/listeners/BranchChangeTicketDetector.kt:16` | listener→http | Imports `jira.api.JiraApiClient`; instantiates it (lines 38–43) inside a `BranchChangeListener`. | Inject `JiraService` (core iface) and call `service.getTicket(ticketId)`. Drop `cachedClient`/`cachedJiraUrl`. | M |
| 3.2 | `jira/listeners/BranchChangeTicketDetector.kt:32–33,159` | listener-state | `@Volatile var cachedClient`, `@Volatile var cachedJiraUrl`, and `companion object { val dismissedBranches: MutableSet<String> }`. | Move `dismissedBranches` into a `@Service(PROJECT)` (e.g. `jira/service/DismissedBranchStore`). `JiraService` already client-caches. | M |
| 3.3 | `jira/listeners/BranchChangeTicketDetector.kt:19,116–144` | listener→ui | Imports `jira.ui.TicketDetectionPopup` and calls `TicketDetectionPopup(...).show(frame)` directly. | Listener emits `WorkflowEvent.TicketDetected`; UI subscriber in `jira/ui/` owns the popup. Preserve current emission semantics (see Decision #4 below). | M |
| 3.4 | `jira/settings/JiraWorkflowConfigurable.kt:17,22` | ui→http + `runBlocking` in Swing | `SearchableConfigurable` imports `jira.api.JiraApiClient` and calls it; also uses `runBlocking` (violates root CLAUDE.md "Never `runBlocking` in Swing"). | Add `JiraService.searchBoards(query): ToolResult<List<Board>>`; configurable uses coroutine scope, no `runBlocking`. Split into: H1 (add method + tests), H2 (wire configurable + kill `runBlocking`). | L |
| 3.5 | `jira/vcs/PostCommitTransitionHandlerFactory.kt:15` | vcs-glue→http | Constructs `JiraApiClient` directly. | Route through `TicketTransitionService` (already in `core/services/jira/`). | S |
| 3.6 | `jira/vcs/TimeTrackingCheckinHandlerFactory.kt:16` | vcs-glue→http | Uses `JiraApiClient` directly. | Route through `JiraService.logWork(...)`. | S |
| 3.7 | `jira/search/JiraSearchContributorFactory.kt:16` | search-glue→http | Instantiates `JiraApiClient` directly. | Route through `JiraService.searchIssues(...)` (exists in `core/services/JiraService.kt`). | S |
| 3.8 | `jira/tasks/JiraTaskRepository.kt:10–11` | raw `okhttp3` outside api/ | `jira/tasks/` (IntelliJ Tasks plugin extension) uses okhttp3 + Request directly, bypassing `JiraApiClient`, `HttpClientFactory.clientFor(ServiceType.JIRA)`, shared pool, retry interceptor, and auth handling. | Replace with `HttpClientFactory.clientFor(ServiceType.JIRA)` or delegate to `JiraApiClient` / `JiraService`. | M |
| 3.9 (borderline) | `bamboo/run/BambooBuildRunState.kt:12` | run-config-glue→http | Constructs `BambooApiClient` directly in run-config execution. | Delegate to `BambooService` / `BuildMonitorService`. | S |
| 3.10 (borderline) | `automation/run/TagValidationBeforeRunProvider.kt:16–17` | run-config-glue→http | Imports `okhttp3.Credentials` + `okhttp3.Request`; Nexus tag validation without `HttpClientFactory` or `DockerRegistryClient`. | Extract `automation/service/TagValidationService`; `BeforeRunProvider` delegates. Use `HttpClientFactory.clientFor(ServiceType.NEXUS)`. | S |

Also noted (not Phase 2): `jira/service/AttachmentDownloadService.kt:20–21` uses `okhttp3` directly. Service-layer HTTP is allowed by Rule C, but should prefer `HttpClientFactory.clientFor(ServiceType.JIRA)` for shared pool + retry. Small housekeeping add-on (+1 S commit) — included in Phase 2 per Decision #5 below.

## 6. Controller architectural decisions (on auditor's Open Questions)

Per `feedback_architecture_autonomy.md` the controller decides architecture autonomously. Decisions:

1. **`:mock-server`** — already in `settings.gradle.kts:16`. Auditor misread. Out of Phase 2 scope (Ktor test server, not a plugin module); not audited further.
2. **Rule C extends to extension-point glue folders** (`vcs/`, `search/`, `tasks/`, `run/`, `settings/`). Spirit of Rule C from `CLAUDE.md` → "Service Architecture (IMPORTANT)" is that only `service/` owns HTTP. Glue folders must delegate to `service/`. This interpretation is locked for Phase 2 and should be added to the module-structure doc.
3. **`ToolResult` duplication stays as-is.** Two distinct types bridged one-way is the explicit design. No unification in Phase 2. Flag as possible Phase 3+ cleanup but low priority.
4. **Preserve `TicketDetected` emission semantics.** The current listener fires `TicketDetected` only on the "dismissed" paths. Commit G must preserve that — do not change event-emission topology as part of a layering fix. Either keep the conditional emission inside the service, or introduce a UI-side presenter that subscribes to a new `TicketDetectedDismissed` event (controller will decide after seeing implementer's proposal; default: keep the event name, move the logic to service).
5. **`jira/service/AttachmentDownloadService` okhttp tidy** — include as a small housekeeping sub-commit during the `:jira` layering pass (Commit I). Low risk, high consistency payoff.

## 7. Recommended commit order (Phase 2 plan)

Smallest-safest-first. Each commit must pass `./gradlew :<module>:test` + `verifyPlugin` and stay single-category.

| # | Commit | Files | LOC | Risk | Why this order |
|---|---|---|---|---|---|
| A | `refactor(jira): route search-everywhere contributor through JiraService` | `jira/search/JiraSearchContributorFactory.kt` | ~25 | Low | 1:1 swap with existing core interface. Establishes "use service, not client" pattern. |
| B | `refactor(jira): route VCS checkin handlers through core services` | `jira/vcs/PostCommitTransitionHandlerFactory.kt`, `jira/vcs/TimeTrackingCheckinHandlerFactory.kt` | ~60 | Low-M | Two small siblings; `TicketTransitionService` + `JiraService.logWork` already exist. |
| C | `refactor(bamboo): route run-config state through BambooService` | `bamboo/run/BambooBuildRunState.kt` | ~40 | Low | Run-config glue only; smoke-test via Run menu. |
| D | `refactor(automation): extract TagValidationService from BeforeRunProvider` | new `automation/service/TagValidationService.kt`, `automation/run/TagValidationBeforeRunProvider.kt` | ~80 | Low | Pure refactor + pickup `HttpClientFactory.clientFor(ServiceType.NEXUS)`. |
| E | `refactor(jira): swap raw okhttp for HttpClientFactory in task repository` | `jira/tasks/JiraTaskRepository.kt` | ~120 | M | Needs shared pool; smoke test via task chooser. |
| F | `refactor(jira): route BranchChangeTicketDetector HTTP through JiraService` | `jira/listeners/BranchChangeTicketDetector.kt` | ~60 net-negative | Low | Fixes #3.1 + part of #3.2; delete > insert. |
| G | `refactor(jira): move TicketDetector state and UI launch out of listener` | new `jira/service/DismissedBranchStore.kt`, new/updated `jira/ui/TicketDetectionPresenter.kt`, `jira/listeners/BranchChangeTicketDetector.kt` | ~150 | M | Fixes #3.2+#3.3. Decision #4 above constrains scope. |
| H1 | `feat(core): add JiraService.searchBoards + impl + tests` | `core/services/JiraService.kt`, `jira/service/JiraServiceImpl.kt` (or equivalent), tests | ~120 | M | Prepares #3.4 fix; tests land first. |
| H2 | `refactor(jira): wire board search through JiraService; remove runBlocking` | `jira/settings/JiraWorkflowConfigurable.kt` | ~80 | M-H | Completes #3.4; also kills a Swing `runBlocking`. |
| I | `refactor(jira): route AttachmentDownloadService through HttpClientFactory` | `jira/service/AttachmentDownloadService.kt` | ~20 | Low | Housekeeping consistency per Decision #5. |

Total: 10 commits, ~750 LOC net.

## 8. Exit criteria (from handoff — unchanged)

- Zero feature-module-to-feature-module imports (already zero, verify again before close)
- All agent-tool entry points go through `core/services/*` (already true, verify)
- `./gradlew verifyPlugin buildPlugin` green after every commit and at close
- `docs/architecture/index.html` updated to reflect enforced layering (add section on extension-point glue folders)
- Net-neutral or negative LOC (target: ≤ +200 LOC, likely net-negative)

## 9. Notes for Phase 3 (caching) — do not execute in Phase 2

- After H1/H2, `JiraService.searchBoards` becomes an ETag/Last-Modified candidate (board lists change rarely).
- After I, all Jira HTTP goes through `HttpClientFactory.clientFor(ServiceType.JIRA)` — enables single point of cache interceptor installation.
- Same for Bamboo after C, Nexus after D.

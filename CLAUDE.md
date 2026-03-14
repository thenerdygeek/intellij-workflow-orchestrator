# IntelliJ Workflow Orchestrator Plugin — Implementation Guide

You are building an IntelliJ IDEA plugin that consolidates the Spring Boot development lifecycle (Jira, Bamboo, SonarQube, Bitbucket, Cody Enterprise) into a single IDE interface. This document is the single source of truth for implementation.

## Critical References

- **Design Spec:** `docs/superpowers/specs/2026-03-11-workflow-orchestrator-plugin-design.md`
- **UX Guide:** `ux-design-guide.md`
- **Phase 1A Plan:** `docs/superpowers/plans/2026-03-11-phase-1a-foundation.md`
- **Phase 1B Plan:** `docs/superpowers/plans/2026-03-11-phase-1b-sprint-branching.md`
- **Requirements:** `requirement.md`
- **Features:** `features.md`
- **Workflow:** `workflow.md`

Read ALL of these before writing any code. They contain approved architectural decisions, API details, and UX rules.

---

## Project Identity

| Key | Value |
|---|---|
| Plugin Name | Workflow Orchestrator |
| Plugin ID | `com.workflow.orchestrator.plugin` |
| Language | Kotlin 2.1.10 |
| Build Tool | Gradle with IntelliJ Platform Gradle Plugin v2 (2.12.0) |
| Target IDE | IntelliJ IDEA 2025.1+ |
| Architecture | Modular monolith — single plugin, 6 Gradle submodules |
| Package | `com.workflow.orchestrator` |

---

## Architecture Rules (Non-Negotiable)

### Module Structure

```
:core        — auth, HTTP, settings, AI, events, caching, offline, onboarding, tool window shell
:jira        — sprint dashboard, branching, commit prefix, time tracking, Jira closure
:bamboo      — build dashboard, polling, log parsing, CVE remediation
:sonar       — coverage markers, quality tab, health check, Cody fix actions
:automation  — dockerTagsAsJson builder, queue, drift detector, conflict detector
:handover    — copyright enforcer, Cody pre-review, PR creation
```

### Dependency Rule
**Feature modules depend ONLY on `:core`, NEVER on each other.** Cross-module communication uses the event bus (`SharedFlow` in `:core`). If you find yourself importing from `:jira` inside `:bamboo`, you are doing it wrong — emit an event from `:jira`, subscribe in `:bamboo`.

### Module Layering
Every feature module follows: `api/ → service/ → ui/ → listeners/`
- `api/` — HTTP client + DTOs (kotlinx.serialization)
- `service/` — Business logic (suspend functions, testable with mocks)
- `ui/` — Tool window panels, actions, gutter icons (IntelliJ UI DSL v2, JB components only)
- `listeners/` — IDE event listeners (lightweight, delegate to services)

### Threading Rules
- **NEVER block the EDT.** Every API call is a `suspend fun` on `Dispatchers.IO`.
- **UI updates ONLY on EDT.** Use `withContext(Dispatchers.EDT)` or `SwingUtilities.invokeLater`.
- **Write actions on EDT.** File edits use `WriteCommandAction.runWriteCommandAction()`.
- **Use `runBackgroundableTask` for user-triggered operations** (Test Connection, Health Check).
- **Background polling uses CoroutineScope** tied to Project lifecycle with `SupervisorJob`.
- **NEVER use `runBlocking` on the EDT.** This freezes the IDE.

---

## External Services — API Details

All services are **self-hosted / on-premise**. All use PAT (Personal Access Token) authentication in Phase 1.

### Jira Server (REST API v2)
```
Auth: Authorization: Bearer <PAT>
Base: https://{host}/rest/api/2/

Key endpoints:
  GET  /rest/api/2/myself                                          → Test connection
  GET  /rest/agile/1.0/board/{boardId}/sprint/{sprintId}/issue     → Sprint tickets (filter: assignee=currentUser())
  GET  /rest/api/2/issue/{key}?expand=issuelinks                   → Ticket with links
  POST /rest/api/2/issue/{key}/transitions                         → Transition status
  POST /rest/api/2/issue/{key}/comment                             → Add comment (wiki markup)
  POST /rest/api/2/issue/{key}/worklog                             → Log time
```

### Bamboo Server (REST API)
```
Auth: Authorization: Bearer <PAT>
Base: https://{host}/rest/api/latest/

Key endpoints:
  GET  /rest/api/latest/currentUser                                → Test connection
  GET  /rest/api/latest/result/{planKey}/latest                    → Latest build result
  GET  /rest/api/latest/result/{buildKey}                          → Specific build result + stages
  GET  /rest/api/latest/result/{buildKey}/log                      → Build log
  POST /rest/api/latest/queue/{planKey}                            → Trigger build (with variables)
  GET  /rest/api/latest/result/{planKey}                           → Running/queued builds

Build variables include `dockerTagsAsJson` which contains the JSON payload of service→docker tag mappings.
```

### SonarQube Server (Web API)
```
Auth: Authorization: Bearer <user-token>
  (SonarQube does NOT support OAuth for API. User tokens only.)

Key endpoints:
  GET  /api/authentication/validate                                → Test connection
  GET  /api/measures/component_tree?component={key}&metricKeys=... → Coverage data
  GET  /api/issues/search?componentKeys={key}&resolved=false       → Open issues
  GET  /api/qualitygates/project_status?projectKey={key}           → Quality gate status

Quality thresholds: 100% new code coverage, 95% new branch coverage.
```

### Bitbucket Server (REST API v1)
```
Auth: Authorization: Bearer <HTTP-access-token>
Base: https://{host}/rest/api/1.0/

Key endpoints:
  GET  /rest/api/1.0/users                                        → Test connection
  POST /rest/api/1.0/projects/{proj}/repos/{repo}/pull-requests    → Create PR
```

### Nexus Docker Registry (Docker Registry v2 API)
```
Auth: Authorization: Basic <base64(token:)>
  (NOTE: Nexus uses BASIC auth, not Bearer!)

Key endpoints:
  GET  /v2/                                                        → Test connection
  GET  /v2/{name}/tags/list                                        → List Docker tags
  HEAD /v2/{name}/manifests/{tag}                                  → Check tag exists
```

### Cody Enterprise (Standalone Cody CLI Agent, JSON-RPC over stdio)
```
Auth: Sourcegraph access token passed in ExtensionConfiguration.accessToken
  + client handles secrets/get requests (agent asks for stored tokens)
Protocol: JSON-RPC 2.0 over stdin/stdout with Content-Length framing
Binary: Cody CLI from @sourcegraph/cody npm package (cody api jsonrpc-stdio)
  On Windows: launched via cmd.exe /c cody.cmd api jsonrpc-stdio

Key JSON-RPC methods (Plugin → Agent):
  initialize           → Auth + capabilities setup (params: ClientInfo)
  initialized          → Notification after init (params: null required)
  chat/new             → Create chat session (params: null required)
  chat/submitMessage   → Send message, get response (contextItems for file context)
  editCommands/code    → "Fix with Cody" inline edits
  editTask/accept      → Accept edit result
  editTask/undo        → Reject edit result
  textDocument/didOpen → Notify agent of open files
  textDocument/didChange → Notify agent of file changes
  shutdown             → Graceful shutdown (params: null required)

Key JSON-RPC methods (Agent → Plugin):
  workspace/edit           → Agent sends file edits to apply
  textDocument/edit        → Text edits for specific document
  secrets/get              → Agent requests stored credentials
  secrets/store            → Agent stores credentials
  editTask/getUserInput    → Agent asks for edit instructions
  webview/createWebviewPanel → Chat panel creation
  debug/message            → Debug logging (silently handled)

ClientCapabilities must match cody_agentic_tool reference:
  chat: "streaming"
  completions: "none"
  git: "none"
  progressBars: "none"
  edit: "enabled"
  editWorkspace: "enabled"
  untitledDocuments: "none"
  showDocument: "none"
  codeLenses: "none"
  showWindowMessage: "notification"
  secrets: "client-managed"

IMPORTANT: Zero-arg methods (chatNew, initialized, shutdown, exit) MUST send
  "params": null in the JSON-RPC message. LSP4J achieves this with Void? parameter.
  Without this, the agent returns "chatID is undefined".

NOTE: The Sourcegraph Cody IDE plugin integration was removed in v0.11.0.
  The plugin's internal agent uses webview-based chat incompatible with JSON-RPC.
  Reference implementations: /Desktop/Programs/Cody/cody_agentic_tool (Python)
  and /Desktop/Programs/Cody/codypy (Python).
```

---

## IntelliJ Platform APIs to Use

**ALWAYS use IntelliJ-native APIs instead of building custom solutions:**

| Task | Use This API | NOT This |
|---|---|---|
| Create git branch | `GitBrancher.checkoutNewBranch()` | Shell `git checkout -b` |
| Get git diff | `Git.diff()` | Shell `git diff` |
| Run Maven build | `MavenRunner.run(params, settings)` | Shell `mvn clean install` |
| Detect changed modules | `MavenProjectsManager.projects` + `Git.diff()` | Manual file→module mapping |
| Commit message hook | `VcsCheckinHandlerFactory` | String replacement |
| Pre-push gate | `PrePushHandler` | Custom dialog |
| Coverage gutter markers | `LineMarkerProvider` + `GutterIconRenderer` | Custom painting |
| Sonar inline warnings | `ExternalAnnotator` (3-phase async) | `Annotator` (sync, blocks EDT) |
| Quick fixes (Alt+Enter) | `IntentionAction` | Custom popup |
| File tree badges | `ProjectViewNodeDecorator` | Custom tree renderer |
| Editor banners | `EditorNotificationProvider` | Custom panel above editor |
| Settings pages | `BoundSearchableConfigurable` + Kotlin UI DSL v2 | Raw Swing panels |
| Credentials | `PasswordSafe` | PersistentStateComponent (plain XML!) |
| Notifications | `NotificationGroupManager` | Custom dialogs |
| Background tasks | `CoroutineScope` with `SupervisorJob` | `Thread()` or `SwingWorker` |
| Spring bean discovery | `SpringManager.getCombinedModel()` | Manual annotation scanning |
| Copyright headers | `com.intellij.copyright.updater` extension point | Regex string matching |

### Plugin Dependencies (in plugin.xml)
```xml
<depends>com.intellij.modules.platform</depends>
<depends>com.intellij.modules.java</depends>
<!-- Optional (loaded only if present): -->
<depends optional="true" config-file="plugin-withGit.xml">Git4Idea</depends>
<depends optional="true" config-file="plugin-withMaven.xml">org.jetbrains.idea.maven</depends>
<depends optional="true" config-file="plugin-withSpring.xml">com.intellij.spring</depends>
<depends optional="true" config-file="plugin-withTasks.xml">com.intellij.tasks</depends>
<depends optional="true" config-file="plugin-withCoverage.xml">Coverage</depends>
```

---

## UX Rules (Non-Negotiable)

1. **ONE tool window** named "Workflow", bottom-docked, with 5 tabs: Sprint, Build, Quality, Automation, Handover.
2. **ONE status bar widget** (text-with-popup): `PROJ-123 ✓` — click opens expanded status popup.
3. **ONE context menu submenu** "Workflow Orchestrator" in EditorPopupMenu — max 5 items, hidden when irrelevant.
4. **Gutter markers** for Sonar: red (blocker/critical), yellow (major), grey (minor). 12x12 Classic UI, 14x14 New UI. SVG with light/dark variants.
5. **Notifications**: 4 groups (workflow.build, workflow.quality, workflow.queue, workflow.automation). Max 2 action buttons per notification.
6. **Settings** under Tools > Workflow Orchestrator with sub-pages: Connections, Notifications, Branching & Commits, Automation Suite, Advanced.
7. **Onboarding**: GotItTooltip on first encounter → collapsible setup dialog. NO modal dialog at startup.
8. **Empty states** for every tab: "No [entity] [state]." + action link.
9. **NEVER use standard Swing** — always JBList, JBTable, JBSplitter, JBColor, JBUI.Borders.
10. **ALL icons**: SVG, light + dark variants. Reuse `AllIcons.*` for standard concepts.

---

## Development Methodology

### Approach: Vertical Slices + Layered Testing

**Phase 1A (Foundation)** is horizontal infrastructure — follow the plan step by step.

**Phase 1B onwards**, build vertically:
1. Pick one user-facing feature (e.g., "View sprint tickets")
2. Capture real API response from your service instance → save as fixture
3. Write API client + DTO deserialization test against the fixture
4. Write service layer with TDD (business logic is pure, testable)
5. Wire into IntelliJ Platform (extension points, actions)
6. Build UI, iterate in `runIde`
7. Write regression tests
8. Manual verification in `runIde`
9. Commit

### Testing Strategy
| Code Type | Test Approach | When |
|---|---|---|
| Business logic (validators, parsers, formatters) | TDD — test first | Before implementation |
| API clients (DTOs) | Fixture-driven — capture real response, then test | After capturing fixture |
| HTTP infrastructure (interceptors) | TDD with MockWebServer | Before implementation |
| IntelliJ Platform integration | Smoke test with BasePlatformTestCase | After implementation |
| UI components | Manual `runIde` verification | During implementation |
| End-to-end flows | Integration test (mock HTTP → verify flow) | After feature works |

### Verification Checklist (Run Before Every Commit)
```bash
./gradlew :core:test          # Unit tests pass
./gradlew verifyPlugin        # No API compatibility issues
./gradlew runIde              # Plugin loads, no errors in IDE log
```

---

## Implementation Status

All phases through 2B are implemented. The plugin is in alpha testing.

### Phase 1A — Foundation (DONE)
Plugin boots, 5-tab tool window, 6 service connections with Test Connection, PasswordSafe credentials, GotItTooltip onboarding, verifyPlugin/buildPlugin passing.

### Phase 1B — Daily Workflow (DONE)
Sprint dashboard (scrum + kanban), Start Work (branch creation on Bitbucket + Jira transition), branch reuse for existing branches, Cody-powered branch name generation, commit prefix with ticket ID, status bar widget, time tracking in commit dialog, Search Everywhere for tickets.

### Phase 1C — CI Feedback (DONE)
Build dashboard with stage list + log viewer, background polling, build status bar widget, project tree build status badges, manual stage trigger run configuration.

### Phase 1D — Quality Visibility (DONE)
Quality tab (overview + issues + coverage), gutter markers (severity-coded), ExternalAnnotator for inline Sonar warnings, editor banners on low-coverage files, project tree coverage badges, diff view coverage highlighting, global inspection tool.

### Phase 1E — AI-Powered Fixes (DONE)
Standalone Cody CLI agent (JSON-RPC over stdio), "Fix with Cody" gutter action, Alt+Enter intention action, test generation gutter action, Cody-generated commit messages with context items + line ranges, Spring-aware context enrichment (beans, endpoints, @Transactional).

### Phase 1F — Quality Enforcement (DONE)
Pre-commit health check (Maven compile + test + copyright + Sonar gate + CVE), incremental Maven build, CVE auto-bumper (IntentionAction on pom.xml), CVE inline annotator, copyright header enforcement with year consolidation.

### Phase 2A — Automation Orchestration (DONE)
Tag staging panel (service table + tag selector + JSON preview), Docker Registry tag validation, drift detector, conflict detector, smart queue (position, wait time, auto-trigger), last 5 configs persisted, queue recovery on IDE restart, status bar queue indicator.

### Phase 2B — Handover (DONE)
One-click PR creation (Bitbucket + Cody-generated description), Jira rich-text closure comment (docker tags + test results + links), time tracking with worklog dialog, Cody pre-review (diff analysis), copyright fix panel, QA clipboard (formatted copy for email/Slack).

**Note:** There is no single "Complete Task" button. PR creation, automation, Jira closure, and QA handover are separate actions because the real workflow is: PR → Bamboo builds → docker tags → automation suites → QA handover. Each step depends on the previous one completing.

### Phase 3+ — Deferred
Regression blame, analytics dashboard, timeline view, error tracing UI, epic summarization. See `memory/project_deferred_features.md`.

---

## Common Mistakes to Avoid

1. **Don't call APIs on the EDT.** If IntelliJ freezes, check your threading.
2. **Don't store secrets in XML.** PasswordSafe only. Check `workflowOrchestrator.xml` never contains tokens.
3. **Don't use `runBlocking` in Swing callbacks.** Use `runBackgroundableTask` or coroutine scope.
4. **Don't import between feature modules.** Use the event bus.
5. **Don't use `Annotator` for external data.** Use `ExternalAnnotator` (3-phase async).
6. **Don't register multiple tool windows.** One window, five tabs.
7. **Don't hardcode Bearer auth for Nexus.** Nexus uses Basic auth.
8. **Don't write DTOs from documentation alone.** Capture real API responses as fixtures first.
9. **Don't fight IntelliJ's patterns.** If an extension point exists for what you need, use it.
10. **Don't skip `verifyPlugin`.** It catches API incompatibilities before users do.

# Workflow Orchestrator — IntelliJ Plugin

Plugin ID: `com.workflow.orchestrator.plugin` | Kotlin 2.1.10 | Gradle + IntelliJ Platform Plugin v2 | Target: IntelliJ IDEA 2025.1+

## Build & Verify

```bash
./gradlew :core:test                # Core unit tests
./gradlew :jira:test                # Jira module tests
./gradlew :bamboo:test              # Bamboo module tests
./gradlew :sonar:test               # Sonar module tests
./gradlew :cody:test                # Cody module tests
./gradlew :pullrequest:test         # PR module tests
./gradlew :automation:test          # Automation module tests
./gradlew :handover:test            # Handover module tests
./gradlew verifyPlugin              # API compatibility check
./gradlew buildPlugin               # Build installable ZIP
./gradlew runIde                    # Launch sandbox IDE with plugin
```

## Architecture

10 Gradle submodules (8 feature modules + core + mock-server):

| Module | Purpose |
|---|---|
| `:core` | Auth, HTTP, settings, events, polling, health checks, tool window shell |
| `:jira` | Sprint dashboard, branching, commit prefix, time tracking, ticket detection |
| `:bamboo` | Build dashboard, polling, log parsing, CVE remediation, PR creation |
| `:sonar` | Coverage markers, quality tab, ExternalAnnotator, project key detection |
| `:cody` | Standalone Cody CLI agent (JSON-RPC), AI fixes, commit messages, context enrichment |
| `:pullrequest` | PR list/detail dashboard, merge actions, Bitbucket PR management |
| `:automation` | Docker tag staging, queue management, drift/conflict detection |
| `:handover` | Jira closure, copyright fixes, Cody pre-review, QA clipboard, time logging |
| `:git-integration` | Git branch operations and VCS integration |

**Dependency rule:** Feature modules depend ONLY on `:core`. Cross-module communication uses `EventBus` (`SharedFlow<WorkflowEvent>` in `:core`).

**Module layering:** `api/` (HTTP client + DTOs) -> `service/` (business logic, suspend funs) -> `ui/` (panels, actions, gutter icons) -> `listeners/` (IDE event listeners)

## Unified Service Layer

Service interfaces in `:core`: `JiraService`, `BambooService`, `SonarService`, `BitbucketService`.
Implementations in feature modules (e.g., `JiraServiceImpl`, `BambooServiceImpl`).
All return `ToolResult<T>` with typed `.data` for UI and `.summary` for AI agent context.

## Shared Utilities (in :core)

- **StatusColors** — JBColor constants: SUCCESS, ERROR, WARNING, INFO, LINK, OPEN, MERGED, DECLINED, SECONDARY_TEXT
- **TimeFormatter** — relative/absolute time formatting
- **HttpClientFactory** — shared ConnectionPool, per-service auth scheme (Bearer/Basic/Token)
- **SmartPoller** — activity-aware polling with exponential backoff (1.5x) + jitter, visibility gating
- **EventBus** — SharedFlow for cross-module events (see `WorkflowEvent.kt` for full list)
- **CredentialStore** — PasswordSafe wrapper for all secrets

## Threading

- API calls: `suspend fun` on `Dispatchers.IO`
- UI updates: `invokeLater` or `withContext(Dispatchers.EDT)`
- File writes: `WriteCommandAction.runWriteCommandAction()`
- User operations: `runBackgroundableTask`
- Background polling: `CoroutineScope` + `SupervisorJob` tied to `Disposable`
- Use coroutine scope, not `runBlocking`, in Swing callbacks

## Authentication

| Service | Auth Scheme | Header |
|---|---|---|
| Jira, Bamboo, Bitbucket | Bearer PAT | `Authorization: Bearer <token>` |
| SonarQube | Bearer user token | `Authorization: Bearer <token>` |
| Nexus Docker Registry | Basic auth | `Authorization: Basic <base64>` |
| Cody/Sourcegraph | Access token | Via JSON-RPC `ExtensionConfiguration.accessToken` |

All credentials stored in PasswordSafe. Never in XML.

## UX Constraints

- ONE tool window "Workflow" (bottom-docked), 6 tabs: Sprint, Build, PR, Quality, Automation, Handover
- ONE status bar widget per service area
- JB components only: JBList, JBTable, JBSplitter, JBColor, JBUI.Borders
- SVG icons with light + dark variants; reuse `AllIcons.*` for standard concepts
- Empty states: "No [entity] [state]." + action link
- Settings: 4 pages under Tools > Workflow Orchestrator: General, Workflow, CI/CD, AI & Advanced
- Onboarding: GotItTooltip on first encounter, collapsible setup dialog (no modal at startup)

## Key References

- Design spec: `docs/superpowers/specs/2026-03-11-workflow-orchestrator-plugin-design.md`
- UX guide: `ux-design-guide.md`
- Requirements: `requirement.md`
- Features: `features.md`
- Workflow: `workflow.md`
- API details: see module-level CLAUDE.md files

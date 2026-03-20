# Module Structure

## Module Dependency Diagram

```mermaid
graph TD
    root["<b>:root</b><br/>Plugin Shell<br/><i>plugin.xml, build config</i>"]
    core["<b>:core</b><br/>Shared Infrastructure<br/><i>auth, HTTP, events, settings, UI shell</i>"]
    jira["<b>:jira</b><br/>Sprint & Tickets<br/><i>dashboard, branching, time tracking</i>"]
    bamboo["<b>:bamboo</b><br/>CI Builds<br/><i>dashboard, polling, CVE remediation</i>"]
    sonar["<b>:sonar</b><br/>Code Quality<br/><i>coverage, issues, gutter markers</i>"]
    cody["<b>:cody</b><br/>AI Assistant<br/><i>JSON-RPC agent, fix/generate actions</i>"]
    pr["<b>:pullrequest</b><br/>Pull Requests<br/><i>PR dashboard, creation, review</i>"]
    auto["<b>:automation</b><br/>Test Automation<br/><i>tag staging, queue, drift detection</i>"]
    handover["<b>:handover</b><br/>Task Completion<br/><i>Jira closure, QA clipboard, copyright</i>"]
    git["<b>:git-integration</b><br/>Git Operations<br/><i>branch listeners, diff provider</i>"]
    mock["<b>:mock-server</b><br/>Test Support<br/><i>mock API responses for testing</i>"]

    root --> core
    root --> jira
    root --> bamboo
    root --> sonar
    root --> cody
    root --> pr
    root --> auto
    root --> handover
    root --> git

    jira --> core
    bamboo --> core
    sonar --> core
    cody --> core
    pr --> core
    auto --> core
    auto -.->|"BambooApiClient<br/>(exception)"| bamboo
    handover --> core
    git --> core

    style core fill:#264f78,stroke:#569cd6,color:#d4d4d4
    style jira fill:#2d4a22,stroke:#6a9955,color:#d4d4d4
    style bamboo fill:#2d4a22,stroke:#6a9955,color:#d4d4d4
    style sonar fill:#2d4a22,stroke:#6a9955,color:#d4d4d4
    style cody fill:#2d4a22,stroke:#6a9955,color:#d4d4d4
    style pr fill:#2d4a22,stroke:#6a9955,color:#d4d4d4
    style auto fill:#2d4a22,stroke:#6a9955,color:#d4d4d4
    style handover fill:#2d4a22,stroke:#6a9955,color:#d4d4d4
    style git fill:#2d4a22,stroke:#6a9955,color:#d4d4d4
    style root fill:#4e3a24,stroke:#ce9178,color:#d4d4d4
    style mock fill:#3b3b3b,stroke:#808080,color:#d4d4d4
```

## Module Responsibilities

| Module | Responsibility |
|---|---|
| `:core` | Shared infrastructure: authentication, HTTP client factory, event bus, plugin settings, tool window shell, onboarding, notifications, and service interfaces |
| `:jira` | Sprint dashboard (scrum + kanban), Start Work flow, branch creation, commit prefix injection, time tracking, and Jira ticket operations |
| `:bamboo` | Build dashboard with stage list and log viewer, background build polling, build status bar widget, project tree badges, and CVE remediation |
| `:sonar` | Quality tab (overview, issues, coverage), severity-coded gutter markers, ExternalAnnotator for inline warnings, editor banners, and coverage badges |
| `:cody` | Standalone Cody CLI agent (JSON-RPC over stdio), "Fix with Cody" gutter action, test generation, commit message generation, and PR description generation |
| `:pullrequest` | PR dashboard with list and detail views, PR creation via Bitbucket API, and PR status tracking |
| `:automation` | Docker tag staging panel, tag validation via Nexus Registry API, drift detector, conflict detector, smart queue with auto-trigger, and config persistence |
| `:handover` | Jira rich-text closure comment, Cody pre-review, copyright fix panel, time log dialog, and QA clipboard (formatted copy for email/Slack) |
| `:git-integration` | Git branch change listeners, diff provider, and branch-to-ticket resolution |
| `:mock-server` | Mock API server for integration testing |

## Dependency Rules

### Primary Rule
**Feature modules depend ONLY on `:core`, never on each other.**

All cross-module communication flows through the `EventBus` (a Kotlin `SharedFlow` in `:core`). If module A needs to react to something in module B, module B emits a `WorkflowEvent` and module A subscribes.

### Known Exception
`:automation` depends on `:bamboo` because it needs `BambooApiClient` to trigger automation suite builds and query build results. This is a deliberate trade-off to avoid duplicating the Bamboo HTTP client.

### Module Layering
Every feature module follows a consistent internal structure:

```
module/
  api/        -- HTTP client + DTOs (kotlinx.serialization)
  service/    -- Business logic (suspend functions, testable with mocks)
  ui/         -- Tool window panels, actions, gutter icons (IntelliJ UI DSL v2)
  listeners/  -- IDE event listeners (lightweight, delegate to services)
```

## Extension Points

The plugin defines 5 custom extension points in `:core` for modular tab and feature registration:

| Extension Point | Interface | Purpose |
|---|---|---|
| `com.workflow.orchestrator.tabProvider` | `WorkflowTabProvider` | Register tool window tabs from feature modules (Sprint, Build, PR, Quality, Automation, Handover) |
| `com.workflow.orchestrator.connectionTester` | `ConnectionTester` | Register per-service connection test implementations (Jira, Bamboo, SonarQube, Bitbucket, Nexus, Cody) |
| `com.workflow.orchestrator.healthCheck` | `HealthCheckContributor` | Register health check steps (Maven compile, tests, copyright, Sonar gate, CVE) |
| `com.workflow.orchestrator.statusBarWidget` | `WorkflowWidgetProvider` | Register status bar widgets (ticket, build, queue) |
| `com.workflow.orchestrator.settingsContributor` | `SettingsContributor` | Register settings sub-pages from feature modules |

All extension points are declared in `:core`'s `plugin.xml` and implemented by feature modules in their respective `plugin-*.xml` configuration files.

## Cross-Module Communication

```mermaid
flowchart LR
    subgraph Emitters
        J[":jira"]
        B[":bamboo"]
        S[":sonar"]
        C[":cody"]
        P[":pullrequest"]
        A[":automation"]
        H[":handover"]
        G[":git-integration"]
    end

    EB["EventBus<br/>(SharedFlow)"]

    subgraph Consumers
        J2[":jira"]
        B2[":bamboo"]
        S2[":sonar"]
        P2[":pullrequest"]
        A2[":automation"]
        H2[":handover"]
        UI["Status Bar<br/>Widgets"]
    end

    J -->|TicketChanged| EB
    B -->|BuildFinished<br/>BuildLogReady| EB
    S -->|QualityGateResult<br/>CoverageUpdated| EB
    C -->|CodyEditReady| EB
    P -->|PullRequestCreated<br/>PullRequestUpdated<br/>PrSelected| EB
    A -->|AutomationTriggered<br/>QueuePositionChanged| EB
    H -->|JiraCommentPosted<br/>PreReviewFinished| EB
    G -->|BranchChanged| EB

    EB --> J2
    EB --> B2
    EB --> S2
    EB --> P2
    EB --> A2
    EB --> H2
    EB --> UI

    style EB fill:#c47e1a,stroke:#dcdcaa,color:#1e1e1e
```

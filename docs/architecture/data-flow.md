# Data Flow Diagrams

## 1. Ticket to Build to Quality (Daily Developer Loop)

The core workflow: pick a ticket, write code, push, wait for build, check quality.

```mermaid
sequenceDiagram
    participant Dev as Developer
    participant Sprint as Sprint Tab
    participant Jira as Jira Server
    participant Git as Git (Local)
    participant BB as Bitbucket Server
    participant Build as Build Tab
    participant Bamboo as Bamboo Server
    participant Quality as Quality Tab
    participant Sonar as SonarQube Server

    Dev->>Sprint: Select ticket PROJ-123
    Sprint->>Jira: GET /rest/agile/1.0/.../issue
    Jira-->>Sprint: Ticket details
    Dev->>Sprint: Click "Start Work"
    Sprint->>BB: Create branch feature/PROJ-123-description
    BB-->>Sprint: Branch created
    Sprint->>Jira: POST /transitions (In Progress)
    Sprint->>Git: GitBrancher.checkoutNewBranch()

    Note over Dev: Developer writes code...

    Dev->>Git: Commit with prefix "PROJ-123: ..."
    Dev->>Git: Push to Bitbucket

    Note over Bamboo: CI build triggered by push

    Bamboo-->>Build: Polling detects new build
    Build->>Build: EventBus.emit(BuildFinished)

    Bamboo->>Sonar: SonarQube analysis runs
    Sonar-->>Quality: Quality data available
    Quality->>Sonar: GET /api/measures/component_tree
    Quality->>Quality: EventBus.emit(QualityGateResult)
    Quality->>Quality: EventBus.emit(CoverageUpdated)
```

## 2. Cody AI Augmentation Flow

How the Cody CLI agent integrates with fix actions, commit messages, and PR descriptions.

```mermaid
sequenceDiagram
    participant Dev as Developer
    participant IDE as IntelliJ Editor
    participant Cody as Cody CLI Agent
    participant SG as Sourcegraph Server

    Note over IDE,Cody: Agent startup (JSON-RPC over stdio)
    IDE->>Cody: initialize(ClientInfo)
    Cody->>SG: Authenticate with access token
    SG-->>Cody: Auth confirmed
    IDE->>Cody: initialized(null)

    rect rgb(40, 60, 40)
    Note over Dev,SG: Fix with Cody (gutter action)
    Dev->>IDE: Click "Fix with Cody" on Sonar issue
    IDE->>IDE: CodyEditService builds fix instruction
    IDE->>Cody: chat/submitMessage(fix instruction + file context)
    Cody->>SG: LLM request with file context
    SG-->>Cody: Generated fix (code in response)
    Cody-->>IDE: Chat response with fixed code
    IDE->>IDE: Parse code from response
    IDE->>IDE: WriteCommandAction.runWriteCommandAction(apply fix)
    IDE->>Dev: Show diff preview
    Dev->>IDE: Accept/reject change
    IDE->>IDE: EventBus.emit(CodyEditReady)
    end

    rect rgb(40, 40, 60)
    Note over Dev,SG: Commit message generation
    Dev->>IDE: Open commit dialog
    IDE->>IDE: Collect git diff + active ticket
    IDE->>Cody: chat/submitMessage(diff + context)
    Cody->>SG: LLM request
    SG-->>Cody: Generated commit message
    Cody-->>IDE: Structured message response
    IDE->>Dev: Pre-fill commit message
    end

    rect rgb(60, 40, 40)
    Note over Dev,SG: PR description generation
    Dev->>IDE: Create PR action
    IDE->>IDE: Collect all commits + diff
    IDE->>Cody: chat/submitMessage(commits + diff)
    Cody->>SG: LLM request
    SG-->>Cody: Generated PR description
    IDE->>Dev: Pre-fill PR description field
    end
```

## 3. Health Check to Commit Flow (Pre-Commit Gates)

The sequence of checks that run before a commit is allowed.

```mermaid
sequenceDiagram
    participant Dev as Developer
    participant VCS as VcsCheckinHandler
    participant HC as HealthCheckService
    participant Maven as Maven Runner
    participant Copy as Copyright Enforcer
    participant Sonar as SonarQube
    participant CVE as CVE Checker
    participant EB as EventBus

    Dev->>VCS: Click "Commit"
    VCS->>HC: runHealthChecks()
    HC->>EB: emit(HealthCheckStarted)

    par Parallel checks
        HC->>Maven: Incremental compile (changed modules)
        Maven-->>HC: compile result
    and
        HC->>Maven: Run tests (changed modules)
        Maven-->>HC: test result
    and
        HC->>Copy: Check copyright headers
        Copy-->>HC: copyright result
    and
        HC->>Sonar: GET quality gate status
        Sonar-->>HC: gate result
    and
        HC->>CVE: Scan pom.xml for vulnerabilities
        CVE-->>HC: CVE result
    end

    alt All checks pass
        HC->>EB: emit(HealthCheckFinished(passed=true))
        HC-->>VCS: Allow commit
        VCS-->>Dev: Commit succeeds
    else Any check fails
        HC->>EB: emit(HealthCheckFinished(passed=false))
        HC-->>VCS: Block commit with details
        VCS-->>Dev: Show failure dialog
    end
```

## 4. Automation Queue to PR to Handover Flow

The end-of-task flow: create PR, run automation, close Jira, hand over to QA.

```mermaid
sequenceDiagram
    participant Dev as Developer
    participant PR as PR Tab
    participant BB as Bitbucket
    participant Cody as Cody Agent
    participant Auto as Automation Tab
    participant Bamboo as Bamboo Server
    participant Nexus as Nexus Registry
    participant HO as Handover Tab
    participant Jira as Jira Server

    Dev->>PR: Click "Create PR"
    PR->>Cody: Generate PR description
    Cody-->>PR: Description text
    PR->>BB: POST /pull-requests
    BB-->>PR: PR #42 created
    PR->>PR: EventBus.emit(PullRequestCreated)

    Note over Bamboo: PR build completes, docker images published

    Dev->>Auto: Open Automation tab
    Auto->>Nexus: GET /v2/{name}/tags/list
    Nexus-->>Auto: Available docker tags
    Dev->>Auto: Select tags for services
    Auto->>Auto: Validate tags (HEAD manifest check)
    Dev->>Auto: Click "Queue"
    Auto->>Bamboo: POST /queue/{planKey} with dockerTagsAsJson
    Auto->>Auto: EventBus.emit(AutomationTriggered)

    loop Poll for completion
        Auto->>Bamboo: GET /result/{buildKey}
        Bamboo-->>Auto: Build status
        Auto->>Auto: EventBus.emit(QueuePositionChanged)
    end

    Auto->>Auto: EventBus.emit(AutomationFinished)

    Dev->>HO: Open Handover tab
    HO->>Cody: Pre-review (analyze diff)
    Cody-->>HO: Review findings
    HO->>HO: EventBus.emit(PreReviewFinished)

    Dev->>HO: Click "Close Jira Ticket"
    HO->>Jira: POST /comment (rich text with tags, results, links)
    HO->>Jira: POST /transitions (Done)
    HO->>HO: EventBus.emit(JiraCommentPosted)

    Dev->>HO: Click "Copy to Clipboard"
    HO->>Dev: QA-formatted summary copied
```

## 5. Branch Change to Active Ticket Sync Flow

How switching branches automatically updates the active ticket across the plugin.

```mermaid
flowchart TD
    A["Git branch change<br/>(checkout, pull, merge)"] --> B["BranchChangeListener<br/>(:git-integration)"]
    B --> C["EventBus.emit<br/>(BranchChanged)"]

    C --> D["ActiveTicketService<br/>(:jira)"]
    D --> E{"Branch matches<br/>ticket pattern?<br/>e.g. feature/PROJ-123-*"}

    E -->|Yes| F["Extract ticket ID<br/>PROJ-123"]
    E -->|No| G["Clear active ticket"]

    F --> H["Jira API: GET ticket details"]
    H --> I["Update PluginSettings<br/>activeTicketId + activeTicketSummary"]
    I --> J["EventBus.emit<br/>(TicketChanged)"]

    J --> K["Status Bar Widget<br/>updates display"]
    J --> L["Sprint Tab<br/>highlights active ticket"]
    J --> M["Commit Prefix Service<br/>updates prefix"]
    J --> N["Build Tab<br/>filters to relevant plan"]

    G --> O["Clear PluginSettings"]
    O --> K

    style A fill:#264f78,stroke:#569cd6,color:#d4d4d4
    style C fill:#c47e1a,stroke:#dcdcaa,color:#1e1e1e
    style J fill:#c47e1a,stroke:#dcdcaa,color:#1e1e1e
    style K fill:#2d4a22,stroke:#6a9955,color:#d4d4d4
    style L fill:#2d4a22,stroke:#6a9955,color:#d4d4d4
    style M fill:#2d4a22,stroke:#6a9955,color:#d4d4d4
    style N fill:#2d4a22,stroke:#6a9955,color:#d4d4d4
```

## 6. Cross-Tab Branch Awareness Flow

How PR selection propagates context to Quality and Build tabs.

```mermaid
flowchart LR
    subgraph PR Tab
        PRS["PR Selected<br/>PR #42"]
    end

    PRS -->|"EventBus.emit<br/>(PrSelected)"| EB["EventBus"]

    EB --> QT["Quality Tab"]
    EB --> BT["Build Tab"]

    subgraph Quality Tab Response
        QT --> Q1["Filter Sonar issues<br/>to PR branch"]
        QT --> Q2["Show coverage diff<br/>for PR changes"]
    end

    subgraph Build Tab Response
        BT --> B1["Filter builds<br/>to PR branch"]
        BT --> B2["Highlight PR build<br/>in dashboard"]
    end

    subgraph Build Events
        BF["BuildFinished event"]
    end

    BF -->|"EventBus"| QT2["Quality Tab"]
    QT2 --> QR["Refresh quality data<br/>after build completes"]

    style EB fill:#c47e1a,stroke:#dcdcaa,color:#1e1e1e
    style PRS fill:#264f78,stroke:#569cd6,color:#d4d4d4
    style BF fill:#264f78,stroke:#569cd6,color:#d4d4d4
```

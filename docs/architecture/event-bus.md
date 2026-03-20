# Event Bus Architecture

## Overview

The `EventBus` is a project-level service in `:core` backed by a Kotlin `MutableSharedFlow<WorkflowEvent>` with zero replay and a 64-element buffer. It is the sole mechanism for cross-module communication -- feature modules never import from each other.

```kotlin
@Service(Service.Level.PROJECT)
class EventBus {
    private val _events = MutableSharedFlow<WorkflowEvent>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val events: SharedFlow<WorkflowEvent> = _events.asSharedFlow()

    suspend fun emit(event: WorkflowEvent) { _events.emit(event) }
}
```

## All 21 WorkflowEvent Types

```mermaid
flowchart LR
    subgraph "Build Events"
        BF["BuildFinished<br/><i>planKey, buildNumber, status</i>"]
        BLR["BuildLogReady<br/><i>planKey, buildNumber, log</i>"]
    end

    subgraph "Quality Events"
        QGR["QualityGateResult<br/><i>projectKey, passed</i>"]
        CU["CoverageUpdated<br/><i>projectKey, lineCoverage</i>"]
    end

    subgraph "AI Events"
        CER["CodyEditReady<br/><i>taskId, filePath, accepted</i>"]
    end

    subgraph "Health Check Events"
        HCS["HealthCheckStarted<br/><i>checks</i>"]
        HCF["HealthCheckFinished<br/><i>passed, results, durationMs</i>"]
    end

    subgraph "Automation Events"
        AT["AutomationTriggered<br/><i>suitePlanKey, buildResultKey, ...</i>"]
        AF["AutomationFinished<br/><i>suitePlanKey, passed, durationMs</i>"]
        QPC["QueuePositionChanged<br/><i>suitePlanKey, position, wait</i>"]
    end

    subgraph "PR Events"
        PRC["PullRequestCreated<br/><i>prUrl, prNumber, ticketId</i>"]
        PRU["PullRequestUpdated<br/><i>prId, field</i>"]
        PRM["PullRequestMerged<br/><i>prId</i>"]
        PRD["PullRequestDeclined<br/><i>prId</i>"]
        PRA["PullRequestApproved<br/><i>prId, byUser</i>"]
        PRS["PrSelected<br/><i>prId, fromBranch, toBranch</i>"]
    end

    subgraph "Ticket Events"
        TC["TicketChanged<br/><i>ticketId, ticketSummary</i>"]
    end

    subgraph "Branch Events"
        BC["BranchChanged<br/><i>branchName</i>"]
    end

    subgraph "Handover Events"
        JCP["JiraCommentPosted<br/><i>ticketId, commentId</i>"]
        PRF["PreReviewFinished<br/><i>findingsCount, highSeverity</i>"]
    end
```

## Event Flow: Emitters and Consumers

```mermaid
flowchart LR
    subgraph Emitters ["Emitters (who fires)"]
        direction TB
        E_BAMBOO[":bamboo"]
        E_SONAR[":sonar"]
        E_CODY[":cody"]
        E_CORE[":core<br/>(health check)"]
        E_AUTO[":automation"]
        E_PR[":pullrequest"]
        E_JIRA[":jira"]
        E_GIT[":git-integration"]
        E_HO[":handover"]
    end

    EB(("EventBus<br/>SharedFlow"))

    subgraph Consumers ["Consumers (who listens)"]
        direction TB
        C_SPRINT["Sprint Tab<br/>(:jira)"]
        C_BUILD["Build Tab<br/>(:bamboo)"]
        C_QUALITY["Quality Tab<br/>(:sonar)"]
        C_PR_TAB["PR Tab<br/>(:pullrequest)"]
        C_AUTO_TAB["Automation Tab<br/>(:automation)"]
        C_HANDOVER["Handover Tab<br/>(:handover)"]
        C_STATUS["Status Bar<br/>Widgets"]
        C_COMMIT["Commit Prefix<br/>Service"]
        C_TREE["Project Tree<br/>Badges"]
    end

    E_BAMBOO -->|"BuildFinished<br/>BuildLogReady"| EB
    E_SONAR -->|"QualityGateResult<br/>CoverageUpdated"| EB
    E_CODY -->|"CodyEditReady"| EB
    E_CORE -->|"HealthCheckStarted<br/>HealthCheckFinished"| EB
    E_AUTO -->|"AutomationTriggered<br/>AutomationFinished<br/>QueuePositionChanged"| EB
    E_PR -->|"PullRequestCreated<br/>PullRequestUpdated<br/>PrSelected<br/>PullRequestMerged<br/>PullRequestDeclined<br/>PullRequestApproved"| EB
    E_JIRA -->|"TicketChanged"| EB
    E_GIT -->|"BranchChanged"| EB
    E_HO -->|"JiraCommentPosted<br/>PreReviewFinished"| EB

    EB --> C_SPRINT
    EB --> C_BUILD
    EB --> C_QUALITY
    EB --> C_PR_TAB
    EB --> C_AUTO_TAB
    EB --> C_HANDOVER
    EB --> C_STATUS
    EB --> C_COMMIT
    EB --> C_TREE

    style EB fill:#c47e1a,stroke:#dcdcaa,color:#1e1e1e
```

## Event Subscription Matrix

| Event | Emitted By | Consumed By |
|---|---|---|
| `BuildFinished` | `:bamboo` (BuildMonitorService) | Build Tab, Quality Tab, Status Bar, Project Tree |
| `BuildLogReady` | `:bamboo` (BuildMonitorService) | Build Tab (log viewer) |
| `QualityGateResult` | `:sonar` (SonarService refresh) | Quality Tab, Status Bar, Handover Tab |
| `CoverageUpdated` | `:sonar` (SonarService refresh) | Quality Tab, Editor Gutter |
| `CodyEditReady` | `:cody` (Agent callback) | Editor (apply/reject UI) |
| `HealthCheckStarted` | `:core` (HealthCheckService) | Status Bar (progress indicator) |
| `HealthCheckFinished` | `:core` (HealthCheckService) | VcsCheckinHandler (gate result) |
| `AutomationTriggered` | `:automation` (queue trigger) | Automation Tab, Status Bar |
| `AutomationFinished` | `:automation` (poll result) | Automation Tab, Handover Tab, Status Bar |
| `QueuePositionChanged` | `:automation` (poll update) | Automation Tab, Status Bar |
| `PullRequestCreated` | `:pullrequest` (PR creation) | PR Tab, Handover Tab, Status Bar |
| `PullRequestUpdated` | `:pullrequest` (PR edit) | PR Tab |
| `PullRequestMerged` | `:pullrequest` (PR poll) | PR Tab, Jira (auto-close), Status Bar |
| `PullRequestDeclined` | `:pullrequest` (PR poll) | PR Tab, Status Bar |
| `PullRequestApproved` | `:pullrequest` (PR poll) | PR Tab |
| `PrSelected` | `:pullrequest` (user click) | Quality Tab, Build Tab |
| `TicketChanged` | `:jira` (Start Work / branch sync) | Sprint Tab, Status Bar, Commit Prefix, Build Tab |
| `BranchChanged` | `:git-integration` (BranchChangeListener) | ActiveTicketService, PR Tab, Build Tab |
| `JiraCommentPosted` | `:handover` (closure comment) | Handover Tab (completion state) |
| `PreReviewFinished` | `:handover` (Cody review) | Handover Tab (findings display) |

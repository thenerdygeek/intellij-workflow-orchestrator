# Service Layer Architecture

## The ToolResult Pattern

Every service method returns `ToolResult<T>` -- a dual-purpose result type that serves both UI panels and the AI agent from the same service call.

```mermaid
classDiagram
    class ToolResult~T~ {
        +T data
        +String summary
        +Boolean isError
        +String? hint
        +Int tokenEstimate
        +success(data, summary, hint) ToolResult~T~$
        +error(summary, hint) ToolResult~Unit~$
    }

    class UIPanel {
        <<consumer>>
        +Uses data: T
        +Renders structured content
    }

    class AIAgent {
        <<consumer>>
        +Uses summary: String
        +Token-efficient text
        +Uses hint for next action
    }

    ToolResult~T~ --> UIPanel : data field
    ToolResult~T~ --> AIAgent : summary field
```

## Unified Service Architecture

The same service interface is consumed by both the UI layer and the AI agent. This ensures consistency -- the AI agent and the human developer see the same data.

```mermaid
flowchart TD
    subgraph Consumers
        UI["UI Panels<br/>(Sprint, Build, Quality...)"]
        AI["Cody AI Agent<br/>(JSON-RPC tools)"]
    end

    subgraph Service Interfaces
        JS["JiraService"]
        BS["BambooService"]
        SS["SonarService"]
        BBS["BitbucketService"]
    end

    subgraph Implementations
        JSI["JiraServiceImpl"]
        BSI["BambooServiceImpl"]
        SSI["SonarServiceImpl"]
        BBSI["BitbucketServiceImpl"]
    end

    subgraph API Clients
        JC["JiraApiClient"]
        BC["BambooApiClient"]
        SC["SonarApiClient"]
        BBC["BitbucketApiClient"]
    end

    subgraph HTTP Layer
        HCF["HttpClientFactory"]
        SP["sharedPool<br/>(ConnectionPool)"]
        AI2["AuthInterceptor"]
        RI["RetryInterceptor"]
    end

    subgraph External
        JiraExt["Jira Server"]
        BambooExt["Bamboo Server"]
        SonarExt["SonarQube"]
        BBExt["Bitbucket Server"]
    end

    UI --> JS & BS & SS & BBS
    AI --> JS & BS & SS & BBS

    JS --> JSI
    BS --> BSI
    SS --> SSI
    BBS --> BBSI

    JSI --> JC
    BSI --> BC
    SSI --> SC
    BBSI --> BBC

    JC --> HCF
    BC --> HCF
    SC --> HCF
    BBC --> HCF

    HCF --> SP
    SP --> AI2
    AI2 --> RI

    RI --> JiraExt & BambooExt & SonarExt & BBExt

    style UI fill:#2d4a22,stroke:#6a9955,color:#d4d4d4
    style AI fill:#4e3a24,stroke:#ce9178,color:#d4d4d4
    style HCF fill:#264f78,stroke:#569cd6,color:#d4d4d4
    style SP fill:#264f78,stroke:#569cd6,color:#d4d4d4
```

## Service Interfaces

### JiraService (`:core`, implemented by `:jira`)

```mermaid
classDiagram
    class JiraService {
        <<interface>>
        +getTicket(key: String) ToolResult~JiraTicketData~
        +getTransitions(key: String) ToolResult~List~JiraTransitionData~~
        +transition(key, transitionId, fields?, comment?) ToolResult~Unit~
        +addComment(key, body) ToolResult~Unit~
        +logWork(key, timeSpent, comment?) ToolResult~Unit~
        +getComments(key) ToolResult~List~JiraCommentData~~
        +testConnection() ToolResult~Unit~
    }

    class JiraServiceImpl {
        -apiClient: JiraApiClient
        -project: Project
    }

    class JiraApiClient {
        -httpClient: OkHttpClient
        +getMyself() JiraUser
        +getSprintIssues(boardId, sprintId) List~JiraIssue~
        +getIssue(key) JiraIssue
        +doTransition(key, transitionId, fields?) Unit
        +postComment(key, body) Unit
        +postWorklog(key, timeSpent, comment?) Unit
    }

    JiraService <|.. JiraServiceImpl
    JiraServiceImpl --> JiraApiClient
```

### BambooService (`:core`, implemented by `:bamboo`)

```mermaid
classDiagram
    class BambooService {
        <<interface>>
        +getLatestBuild(planKey) ToolResult~BuildResultData~
        +getBuild(buildKey) ToolResult~BuildResultData~
        +triggerBuild(planKey, variables) ToolResult~BuildTriggerData~
        +testConnection() ToolResult~Unit~
        +getBuildLog(resultKey) ToolResult~String~
        +getTestResults(resultKey) ToolResult~TestResultsData~
        +rerunFailedJobs(planKey, buildNumber) ToolResult~Unit~
        +getPlanVariables(planKey) ToolResult~List~PlanVariableData~~
        +triggerStage(planKey, variables, stage?) ToolResult~Unit~
    }

    class BambooServiceImpl {
        -apiClient: BambooApiClient
        -project: Project
    }

    class BuildMonitorService {
        -pollingIntervalMs: Long
        -scope: CoroutineScope
        +startPolling()
        +stopPolling()
    }

    class PlanDetectionService {
        +detectPlanKey(projectPath) String?
    }

    class CveRemediationService {
        +scanPom(pomFile) List~CveIssue~
        +suggestFix(issue) String?
    }

    BambooService <|.. BambooServiceImpl
    BambooServiceImpl --> BambooApiClient
    BuildMonitorService --> BambooApiClient
```

### SonarService (`:core`, implemented by `:sonar`)

```mermaid
classDiagram
    class SonarService {
        <<interface>>
        +getIssues(projectKey, filePath?) ToolResult~List~SonarIssueData~~
        +getQualityGateStatus(projectKey) ToolResult~QualityGateData~
        +getCoverage(projectKey) ToolResult~CoverageData~
        +searchProjects(query) ToolResult~List~SonarProjectData~~
        +getAnalysisTasks(projectKey) ToolResult~List~SonarAnalysisTaskData~~
        +testConnection() ToolResult~Unit~
    }

    class SonarServiceImpl {
        -apiClient: SonarApiClient
        -project: Project
    }

    SonarService <|.. SonarServiceImpl
    SonarServiceImpl --> SonarApiClient
```

### BitbucketService (`:core`, implemented by `:pullrequest`)

```mermaid
classDiagram
    class BitbucketService {
        <<interface>>
        +createPullRequest(title, description, fromBranch, toBranch) ToolResult~PullRequestData~
        +testConnection() ToolResult~Unit~
    }

    class BitbucketServiceImpl {
        -apiClient: BitbucketApiClient
        -project: Project
    }

    BitbucketService <|.. BitbucketServiceImpl
    BitbucketServiceImpl --> BitbucketApiClient
```

## Additional Module Services

These services are internal to their modules (not defined as interfaces in `:core`):

| Module | Service | Responsibility |
|---|---|---|
| `:jira` | `SprintService` | Fetch sprint tickets, filter by assignee, board type handling |
| `:jira` | `ActiveTicketService` | Resolve branch name to ticket ID, manage active ticket state |
| `:jira` | `BranchingService` | Generate branch names, create branches on Bitbucket + local |
| `:jira` | `CommitPrefixService` | Inject ticket ID prefix into commit messages |
| `:bamboo` | `BuildMonitorService` | Background polling for build status changes |
| `:bamboo` | `PlanDetectionService` | Auto-detect Bamboo plan key from project structure |
| `:bamboo` | `CveRemediationService` | Scan pom.xml for CVEs, suggest version bumps |
| `:pullrequest` | `PrListService` | Fetch and filter user's pull requests |
| `:pullrequest` | `PrDetailService` | Fetch PR details, diff, activities, and changed files |
| `:pullrequest` | `PrActionService` | Merge, approve, decline, and update PRs |
| `:cody` | `CodyAgentManager` | Manage Cody CLI process lifecycle (start, shutdown, reconnect) |
| `:cody` | `CodyContextService` | Build context items (file ranges, Spring beans, endpoints) |
| `:cody` | `CodyChatService` | Chat session management, commit message and PR description generation |
| `:cody` | `CodyEditService` | "Fix with Cody" edits, test generation, code-to-chat fix flow |
| `:automation` | `QueueService` | Smart queue management, position tracking, auto-trigger |
| `:automation` | `TagBuilderService` | Build dockerTagsAsJson payload from tag selections |
| `:automation` | `DriftDetectorService` | Detect tag version drift between services |
| `:automation` | `ConflictDetectorService` | Detect conflicting tag selections |
| `:sonar` | `SonarDataService` | Cache and refresh Sonar data (issues, coverage, quality gate) |
| `:handover` | `HandoverStateService` | Aggregate handover context (ticket, branch, PR, build, quality) |
| `:handover` | `CopyrightFixService` | Detect and fix copyright header violations |
| `:handover` | `PreReviewService` | Run Cody pre-review on diff before PR |
| `:handover` | `JiraClosureService` | Build rich-text Jira closure comment with docker tags and test results |
| `:handover` | `TimeTrackingService` | Worklog dialog and time logging to Jira |
| `:handover` | `QaClipboardService` | Format QA handover summary for clipboard copy |

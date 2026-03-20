# UI Architecture

## Tool Window Structure

One tool window named "Workflow", bottom-docked, with six tabs. Each tab is contributed by a `WorkflowTabProvider` extension point from its respective module.

```mermaid
graph TD
    TW["Workflow Tool Window<br/>(bottom-docked)"]

    TW --> T1["Sprint Tab<br/>(:jira / SprintTabProvider)"]
    TW --> T2["Build Tab<br/>(:bamboo / BuildTabProvider)"]
    TW --> T3["PR Tab<br/>(:pullrequest / PrTabProvider)"]
    TW --> T4["Quality Tab<br/>(:sonar / QualityTabProvider)"]
    TW --> T5["Automation Tab<br/>(:automation / AutomationTabProvider)"]
    TW --> T6["Handover Tab<br/>(:handover / HandoverTabProvider)"]

    subgraph "Title Bar Actions"
        A1["Refresh"]
        A2["Open in Jira"]
        A3["Settings"]
        A4["Next Tab"]
    end

    subgraph "Gear Menu"
        G1["Settings"]
        G2["Refresh All Tabs"]
        G3["Clear Active Ticket"]
        G4["Open Jira Board"]
        G5["Open SonarQube"]
        G6["Open Bamboo"]
    end

    TW --- A1 & A2 & A3 & A4
    TW --- G1

    style TW fill:#264f78,stroke:#569cd6,color:#d4d4d4
    style T1 fill:#2d4a22,stroke:#6a9955,color:#d4d4d4
    style T2 fill:#2d4a22,stroke:#6a9955,color:#d4d4d4
    style T3 fill:#2d4a22,stroke:#6a9955,color:#d4d4d4
    style T4 fill:#2d4a22,stroke:#6a9955,color:#d4d4d4
    style T5 fill:#2d4a22,stroke:#6a9955,color:#d4d4d4
    style T6 fill:#2d4a22,stroke:#6a9955,color:#d4d4d4
```

## Tab Panel Hierarchies

### Sprint Tab

```mermaid
graph TD
    SP["SprintDashboardPanel"]
    SP --> TL["Ticket List<br/>(JBList)"]
    SP --> TD2["TicketDetailPanel<br/>(split right)"]
    TD2 --> TI["Ticket Info<br/>(key, summary, status, assignee)"]
    TD2 --> TA["Actions<br/>(Start Work, Transition, Log Time)"]

    style SP fill:#2d4a22,stroke:#6a9955,color:#d4d4d4
```

### Build Tab

```mermaid
graph TD
    BD["BuildDashboardPanel"]
    BD --> BL["Build List<br/>(JBTable: plan, number, status, time)"]
    BD --> BS["JBSplitter"]
    BS --> SL["StageListPanel<br/>(stage names + status icons)"]
    BS --> SD["StageDetailPanel<br/>(log viewer, test results)"]

    style BD fill:#2d4a22,stroke:#6a9955,color:#d4d4d4
```

### PR Tab

```mermaid
graph TD
    PD["PrDashboardPanel"]
    PD --> PL["PrListPanel<br/>(JBList: title, author, status)"]
    PD --> PDP["PrDetailPanel<br/>(description, reviewers, actions)"]

    style PD fill:#2d4a22,stroke:#6a9955,color:#d4d4d4
```

### Quality Tab

```mermaid
graph TD
    QD["QualityDashboardPanel"]
    QD --> OV["OverviewPanel<br/>(gate status, metrics summary)"]
    QD --> IL["IssueListPanel<br/>(JBTable: severity, rule, file, line)"]
    QD --> CT["CoverageTablePanel<br/>(JBTable: file, line%, branch%)"]

    style QD fill:#2d4a22,stroke:#6a9955,color:#d4d4d4
```

### Automation Tab

```mermaid
graph TD
    AP["AutomationPanel"]
    AP --> TS["TagStagingPanel<br/>(service table + tag selector)"]
    AP --> SC["SuiteConfigPanel<br/>(JSON preview, validate)"]
    AP --> QS["QueueStatusPanel<br/>(position, wait time, history)"]
    AP --> MP["MonitorPanel<br/>(running suite status)"]

    style AP fill:#2d4a22,stroke:#6a9955,color:#d4d4d4
```

### Handover Tab

```mermaid
graph TD
    HP["HandoverPanel"]
    HP --> HC["HandoverContextPanel<br/>(active ticket + branch summary)"]
    HP --> PR2["PrCreationPanel<br/>(title, description, reviewers)"]
    HP --> PRV["PreReviewPanel<br/>(Cody findings)"]
    HP --> JC["JiraCommentPanel<br/>(rich-text preview)"]
    HP --> TL2["TimeLogPanel<br/>(worklog dialog)"]
    HP --> CP["CopyrightPanel<br/>(fix violations)"]
    HP --> CM["CompletionMacroPanel<br/>(one-click close)"]
    HP --> QA["QaClipboardPanel<br/>(formatted copy)"]

    style HP fill:#2d4a22,stroke:#6a9955,color:#d4d4d4
```

## Settings Pages

```mermaid
graph TD
    ROOT["Tools > Workflow Orchestrator<br/>(WorkflowSettingsConfigurable)<br/><i>Connection status overview</i>"]

    ROOT --> GEN["General<br/>(GeneralConfigurable)<br/><i>Board config, module toggles,<br/>branch patterns, plan keys</i>"]
    ROOT --> CONN["Connections<br/>(ConnectionSettings)<br/><i>URLs + PATs for all 6 services</i>"]
    ROOT --> AI["AI & Advanced<br/>(AiAdvancedConfigurable)<br/><i>Cody config, timeouts,<br/>polling intervals</i>"]

    style ROOT fill:#264f78,stroke:#569cd6,color:#d4d4d4
    style GEN fill:#264f78,stroke:#569cd6,color:#d4d4d4
    style CONN fill:#264f78,stroke:#569cd6,color:#d4d4d4
    style AI fill:#264f78,stroke:#569cd6,color:#d4d4d4
```

## Editor Integration Points

```mermaid
flowchart LR
    subgraph Editor ["Editor Area"]
        GUTTER["Gutter Markers"]
        ANNOT["Inline Annotations"]
        BANNER["Editor Banners"]
        INTENT["Quick Fixes (Alt+Enter)"]
    end

    subgraph "Project View"
        TREE_B["Build Status Badges"]
        TREE_C["Coverage Badges"]
    end

    subgraph "Status Bar"
        SB_T["Ticket Widget<br/>PROJ-123"]
        SB_B["Build Widget"]
        SB_Q["Queue Widget"]
    end

    subgraph "Context Menu"
        CM["Workflow Orchestrator<br/>(max 5 items)"]
    end

    GUTTER -->|"Sonar severity icons<br/>12x12 / 14x14 SVG"| SONAR_G["Red: blocker/critical<br/>Yellow: major<br/>Grey: minor"]
    GUTTER -->|"Fix with Cody"| CODY_G["AI fix action"]
    GUTTER -->|"Generate Test"| TEST_G["Test generation"]

    ANNOT -->|"ExternalAnnotator<br/>(3-phase async)"| SONAR_A["Sonar warnings<br/>with severity + rule"]

    BANNER -->|"EditorNotificationProvider"| COV_B["Low coverage warning<br/>with link to Quality tab"]

    INTENT -->|"IntentionAction"| CVE_I["CVE version bump<br/>in pom.xml"]

    style GUTTER fill:#2d4a22,stroke:#6a9955,color:#d4d4d4
    style ANNOT fill:#2d4a22,stroke:#6a9955,color:#d4d4d4
    style BANNER fill:#2d4a22,stroke:#6a9955,color:#d4d4d4
    style INTENT fill:#2d4a22,stroke:#6a9955,color:#d4d4d4
    style SB_T fill:#4e3a24,stroke:#ce9178,color:#d4d4d4
    style SB_B fill:#4e3a24,stroke:#ce9178,color:#d4d4d4
    style SB_Q fill:#4e3a24,stroke:#ce9178,color:#d4d4d4
```

## Empty States

Every tab implements an empty state using `EmptyStatePanel` with a descriptive message and action link:

| Tab | Empty State Message |
|---|---|
| Sprint | "No tickets assigned. Connect to Jira in Settings to get started." |
| Build | "No builds found. Push your changes to trigger a CI build." |
| PR | "No pull requests found. Connect to Bitbucket in Settings." |
| Quality | "No quality data available. Connect to SonarQube in Settings." |
| Automation | "Automation suite not configured. Set up Bamboo in Settings." |
| Handover | "No active task to hand over. Start work on a ticket first." |

## UI Component Rules

- All components use JetBrains variants: `JBList`, `JBTable`, `JBSplitter`, `JBColor`, `JBUI.Borders`
- All icons are SVG with light + dark variants; standard concepts reuse `AllIcons.*`
- Notifications use 4 groups: `workflow.build`, `workflow.quality`, `workflow.queue`, `workflow.automation`
- Maximum 2 action buttons per notification
- Context menu has maximum 5 items, hidden when irrelevant

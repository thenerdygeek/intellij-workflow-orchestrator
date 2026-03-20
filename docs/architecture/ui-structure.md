# UI Architecture

## Tool Window Structure

One tool window named "Workflow", bottom-docked, with seven tabs. Each tab is contributed by a `WorkflowTabProvider` extension point from its respective module. The Agent tab is the first to use JCEF (embedded Chromium) for its UI.

```mermaid
graph TD
    TW["Workflow Tool Window<br/>(bottom-docked)"]

    TW --> T1["Sprint Tab<br/>(:jira / SprintTabProvider)"]
    TW --> T2["Build Tab<br/>(:bamboo / BuildTabProvider)"]
    TW --> T3["PR Tab<br/>(:pullrequest / PrTabProvider)"]
    TW --> T4["Quality Tab<br/>(:sonar / QualityTabProvider)"]
    TW --> T5["Automation Tab<br/>(:automation / AutomationTabProvider)"]
    TW --> T6["Handover Tab<br/>(:handover / HandoverTabProvider)"]
    TW --> T7["Agent Tab<br/>(:agent / AgentTabProvider)<br/><i>JCEF chat panel</i>"]

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
    style T7 fill:#4a2d6b,stroke:#b07ed6,color:#d4d4d4
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

### Agent Tab (JCEF)

The Agent tab uses a JCEF-based (embedded Chromium) chat panel -- the first module to use JCEF in the plugin. All other tabs use standard Swing/JB components.

```mermaid
graph TD
    AT["AgentPanel<br/>(JCEF JBCefBrowser)"]
    AT --> CP["Chat Panel<br/>(streaming responses, tool call badges)"]
    AT --> TB["Toolbar<br/>(Skills dropdown, history panel)"]
    AT --> PC["Plan Card<br/>(step status, per-step comments, approve/revise)"]
    AT --> QW["Question Wizard<br/>(single/multi-select, back/skip/next)"]
    AT --> TP["Tools Panel<br/>(categorized checkboxes, 4-tab detail view)"]
    AT --> SB["Skill Banner<br/>(active skill indicator with dismiss)"]

    CP --> TCB["Tool Call Badges<br/>(READ/EDIT/CMD/PLAN)"]
    CP --> CD["Collapsible Details<br/>(tool output, file changes)"]

    PC --> SS["Step Status Icons<br/>(pending/active/done/failed)"]
    PC --> SC["Per-Step Comments"]
    PC --> PA["Approve / Revise Buttons"]

    QW --> QT["Chat About This<br/>(textarea)"]
    QW --> QS["Summary Page"]

    style AT fill:#4a2d6b,stroke:#b07ed6,color:#d4d4d4
```

**Plan Editor Tab:** Full-screen `FileEditor` with `JBCefBrowser` for reviewing and editing plans. Opened as an editor tab (not part of the tool window) via three-layer persistence: disk (`plan.json`), context anchor (planAnchor in conversation), and editor tab.

## Settings Pages

```mermaid
graph TD
    ROOT["Tools > Workflow Orchestrator<br/>(WorkflowSettingsConfigurable)<br/><i>Connection status overview</i>"]

    ROOT --> GEN["General<br/>(GeneralConfigurable)<br/><i>Board config, module toggles,<br/>URLs + PATs for all 6 services</i>"]
    ROOT --> WF["Workflow<br/>(WorkflowConfigurable)<br/><i>Branch patterns, plan keys,<br/>commit prefix, time tracking</i>"]
    ROOT --> CICD["CI/CD<br/>(CiCdConfigurable)<br/><i>Bamboo plans, automation suite,<br/>polling intervals, Nexus config</i>"]
    ROOT --> AI["AI & Advanced<br/>(AiAdvancedConfigurable)<br/><i>Cody config, timeouts,<br/>health check toggles</i>"]

    style ROOT fill:#264f78,stroke:#569cd6,color:#d4d4d4
    style GEN fill:#264f78,stroke:#569cd6,color:#d4d4d4
    style WF fill:#264f78,stroke:#569cd6,color:#d4d4d4
    style CICD fill:#264f78,stroke:#569cd6,color:#d4d4d4
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
| Agent | "No agent session active. Type a message to start a coding task." |

## UI Component Rules

- All components use JetBrains variants: `JBList`, `JBTable`, `JBSplitter`, `JBColor`, `JBUI.Borders`
- All icons are SVG with light + dark variants; standard concepts reuse `AllIcons.*`
- Notifications use 9 groups: `workflow.build`, `workflow.quality`, `workflow.queue`, `workflow.automation`, `workflow.healthcheck`, `workflow.cody`, `workflow.pr`, `workflow.handover`, `workflow.automation.queue`
- Maximum 2 action buttons per notification
- Context menu has maximum 5 items, hidden when irrelevant

## Shared UI Utilities

The following shared UI components are defined in `:core` and used across all feature modules:

| Utility | Location | Purpose |
|---|---|---|
| `StatusColors` | `:core` ui/ | JBColor constants: SUCCESS, ERROR, WARNING, INFO, LINK, OPEN, MERGED, DECLINED, SECONDARY_TEXT |
| `TimeFormatter` | `:core` ui/ | Relative time ("2h ago") and absolute time formatting for timestamps |
| `EmptyStatePanel` | `:core` ui/ | Standardized empty state with message text and action link for all tabs |

# IntelliJ Workflow Orchestrator Plugin: Comprehensive Feature Specification

> **Design Philosophy**: *A good feature that always works is better than an excellent feature that sometimes works.*

This document is the definitive feature specification for the IntelliJ IDEA Workflow Orchestrator Plugin. It eliminates context-switching between Jira, Bamboo, SonarQube, Bitbucket, and Cody Enterprise by consolidating the entire Spring Boot development lifecycle into a single IDE interface.

Features are organized into 5 core modules, 5 standalone new features, and cross-cutting concerns. Each feature includes a description, the value it delivers, and how it works technically.

---

## Module 1: The Context Engine (Sprint & Issue Management)

*Focus: Onboarding to a new task instantly without leaving the IDE.*

### 1.1 Unified Active Sprint Dashboard

*   **What**: A dedicated IntelliJ tool window listing all Jira tickets currently assigned to the authenticated user in the active sprint.
*   **Value**: No need to keep a Jira tab open. See priority and status at a glance.
*   **How**: Uses the Jira REST API (`/rest/agile/1.0/board/{boardId}/sprint/{sprintId}/issue`) filtered by `assignee=currentUser()`.

### 1.2 Cross-Team Dependencies View

*   **What**: A section within the Sprint Dashboard that surfaces Jira tickets linked via `is blocked by` relationships where the linked ticket is owned by a different team/project.
*   **Value**: Developers immediately see if their work is waiting on another team, without manually traversing Jira link chains.
*   **How**: Jira REST API supports link traversal. The plugin fetches issue links of type `Blocker` and filters for external project keys.

### 1.3 "Cody, Explain This Epic" (AI Context Summarization)

*   **What**: A "Summarize Context" button next to any Jira ticket. The plugin fetches the ticket, all linked blockers/epics, and comments, feeding them to Cody Enterprise.
*   **Value**: Cody generates a concise summary of the business requirement and suggests specific Spring Boot repositories or Maven modules likely needing modification.
*   **Multi-Ticket Mode**: Select 2+ tickets and Cody summarizes inter-dependencies and suggests an implementation order.
*   **Caching**: Summaries are cached locally (per ticket ID + last-modified timestamp) so re-opening a ticket doesn't re-fetch unless the ticket changed.
*   **How**: Jira REST API for ticket data → Cody API for summarization → local file cache in `.idea/` directory.

### 1.4 Smart Branching & IDE Setup

*   **What**: One-click "Start Work" from the Sprint Dashboard.
*   **Value**:
    *   Creates a new Git branch automatically formatted with the ticket ID (e.g., `feature/PROJ-123-ui-fix`).
    *   Transitions the Jira ticket status to "In Progress" via API.
    *   Registers a commit message template in IntelliJ so every commit is automatically prefixed with `PROJ-123: `.
*   **Branch Naming Validator**: Configurable regex pattern (in plugin settings) to enforce team-specific naming conventions beyond the default `feature/PROJ-123-*` pattern. Shows a warning if a stale branch already exists for the same ticket.
*   **How**: IntelliJ Git4Idea API for branch operations + Jira REST API for status transitions.

### 1.5 Commit Message Intelligence

*   **What**: Two-part commit message assistance:
    1.  **Auto-Prefix**: Every commit within the active branch is automatically prefixed with the Jira ticket ID. Supports both standard (`PROJ-123: message`) and **Conventional Commits** format (`feat(PROJ-123): message`) as a configurable option.
    2.  **Cody-Generated Messages**: When the user opens the commit dialog, the plugin grabs the diff of all selected changelist files and sends it to Cody, which generates a descriptive commit message. The user reviews, edits, and confirms before committing.
*   **Auto-Complete**: When the user types `PROJ-` anywhere in a commit message, the plugin suggests matching ticket IDs from the active sprint.
*   **How**: IntelliJ's `CheckinHandler` API provides access to VCS diffs and the commit dialog. The diff is naturally scoped to the selected changelist. Cody API generates the message from the diff context.

---

## Module 2: The Actionable Quality Gate (Code Quality & Pre-Push)

*Focus: Enforcing the strict 100% new code coverage rule before pushing code.*

### 2.1 In-Editor Sonar Coverage Markers

*   **What**: The plugin polls the SonarQube API and uses IntelliJ's gutter icon API to highlight exact lines of code in the editor that lack test coverage.
*   **Severity Color Coding**: Gutter markers are color-coded by severity — red for blocker/critical issues, yellow for major, grey for minor/info.
*   **Project Tree Badges**: Each file in the IntelliJ project tree shows an inline coverage percentage badge, so developers can spot low-coverage files without opening them.
*   **Value**: Developers don't need to hunt through the Sonar web interface to find missed branches or uncovered lines.
*   **How**: SonarQube API (`/api/measures/component_tree`) for coverage data + IntelliJ `GutterIconRenderer` API for markers + `ProjectViewNodeDecorator` for tree badges.

### 2.2 The "Coverage Closer" (Cody Auto-TDD)

*   **What**: Right-click a red Sonar marker in the gutter → select **"Cody: Cover this branch."**
*   **Value**: The plugin passes the specific uncovered lines, the surrounding class context, and injected Spring dependencies to Cody. Cody generates the required JUnit 5 / Mockito test and appends it to the relevant `*Test.java` file.
*   **Style Matching**: Before generating tests, Cody analyzes existing test files in the same module to match the team's testing style — parameterized vs. standard tests, AssertJ vs. Hamcrest assertions, test naming conventions, etc.
*   **How**: SonarQube uncovered line data → PSI (Program Structure Interface) to extract class context and dependencies → Cody API for test generation → file write to matched test file path.

### 2.3 Shift-Left Validation (Pre-Push Health Check)

*   **What**: A "Health Check" button integrated into the IntelliJ push dialog that validates code locally before it reaches origin.
*   **Incremental Build**: Instead of running a full `mvn clean install`, the plugin detects which Maven modules were changed (via `git diff`) and runs only `mvn clean install -pl <changed-modules> -am`. This dramatically reduces build time in large multi-module projects.
*   **Value**: Prevents broken builds in CI by catching issues locally first, without the time cost of rebuilding the entire project.
*   **How**: Git diff to detect changed files → map files to Maven modules → `mvn` CLI invocation with `-pl` flag → parse exit code and output → gate the push operation.

---

## Module 3: The Auto-Remediator (CI Monitoring)

*Focus: Surfacing build failures natively and automating fixes for common issues.*

### 3.1 Parallel Build Dashboard (Phase 1 — Core)

*   **What**: A real-time monitor for the three parallel Bamboo CI actions:
    1.  **Build Artifact**: Success/Failure of the Maven build.
    2.  **OSS Analysis**: Security vulnerability scan results.
    3.  **SonarQube**: Visual pass/fail status based on thresholds (100% new code coverage, 95% branch coverage).
*   **Notifications**: Native IDE toast notifications for key events (e.g., "Build finished, but Sonar caught 2 new bugs").
*   **How**: Bamboo REST API (`/rest/api/latest/result/{planKey}`) polled on a background thread at configurable intervals (default: 30s). Results rendered in a tool window with status icons.

### 3.2 Build Timeline View (Phase 2)

*   **What**: A Gantt-style timeline showing each CI stage as a horizontal bar, visualizing which stage consumed the most time.
*   **Historical Trends**: Shows build duration trends for the last 10 builds, helping identify if builds are getting slower over time.
*   **Value**: Developers can see at a glance whether the build, OSS scan, or Sonar analysis is the bottleneck.
*   **How**: Bamboo API exposes job start/end timestamps. The plugin computes durations and renders a custom Swing/JPanel timeline.

### 3.3 Maven CVE Auto-Bumper (Phase 1 — Core)

*   **What**: When Bamboo OSS Analysis fails, the plugin parses the Bamboo build log natively.
*   **Value**: Extracts the vulnerable library name and the recommended safe version. Uses IntelliJ's `MavenProjectsManager` to locate the dependency in your `pom.xml`, highlights it as a warning annotation, and provides an **Alt+Enter "Quick Fix"** to update the version.
*   **How**: Bamboo log API → regex-based CVE extraction → PSI-based `pom.xml` navigation → `IntentionAction` for the quick fix.

### 3.4 Inline Build Error Tracing (Phase 2)

*   **What**: If the Bamboo Maven build fails, the plugin fetches the build log, parses compilation errors and test failure stack traces, and deep-links each error directly to the offending file and line number in the editor.
*   **Value**: Eliminates the need to manually read Bamboo logs in the browser and find the corresponding code.
*   **How**: Bamboo log API → parse `[ERROR]` lines and JUnit failure stack traces → map file paths and line numbers to local project via IntelliJ's `FileEditorManager`.

---

## Module 4: The Automation Orchestrator (Integration Testing)

*Focus: Managing the single-user bottleneck of the shared automation suite.*

### 4.1 Smart `dockerTagsAsJson` Payload Builder

*   **What**: A "Staging" UI that generates the JSON payload for the Bamboo automation suite.
*   **Tag Intelligence**: Automatically detects the "Feature Branch Docker Tag" for the service currently being developed. Defaults other services to their latest "Release Tags" from the most recent successful system-wide run.
*   **Diff View**: Before triggering, shows a comparison of your staged config vs. the last successful run — e.g., *"You changed 2 tags: service-A `release-1.2` → `feature/PROJ-123`, service-B unchanged."*
*   **Tag Validation**: Before triggering, pings the Docker registry to confirm every tag in the payload actually exists. Surfaces an error if a tag is missing or misspelled.
*   **How**: Bamboo API for historical build variables → Docker Registry v2 API (`GET /v2/{name}/tags/list`) for tag validation → JSON construction in a custom tool window.

### 4.2 The Smart Queue & Auto-Trigger

*   **What**: Real-time visibility into the Bamboo Automation Branch status with intelligent queuing.
*   **Concurrency Monitor**: Shows whether the suite is currently "Running" or "Idle" and who triggered the current run.
*   **Queue Enrollment**: If the suite is busy, the user clicks "Queue Run" and the staged payload is saved.
*   **Auto-Trigger**: The plugin polls the Bamboo API in the background. The moment the suite becomes idle, it fires the staged payload and notifies the user: *"Your turn in the Automation Queue has started."*
*   **Wait Time Estimation**: Displays an estimated wait time based on the historical average duration of recent suite runs.
*   **Queue Position**: Shows your position (e.g., "You are #2 in the queue").
*   **Cancellation**: Users can cancel their queued run at any time.
*   **How**: Bamboo REST API (`/rest/api/latest/result/{planKey}/latest`) for status polling (background thread, configurable interval) → local queue management → Bamboo trigger API when idle.

---

## Module 5: Handover Engine (PRs & Sprint Closure)

*Focus: Automating the administrative work of closing out a task.*

### 5.1 Gatekeeper & Copyright Enforcer

*   **What**: A pre-flight check before creating a Pull Request, scoped to **changed files only** in the current branch.
*   **Value**: Ensures all touched `.java` files have the required company copyright header, automatically injecting them via IntelliJ File Templates if they are missing.
*   **Per-Module Configuration**: Copyright templates are configurable per Maven module or project (e.g., different headers for different sub-projects).
*   **How**: `git diff --name-only develop...HEAD` to get changed files → PSI file inspection for copyright header → IntelliJ `FileTemplate` API for auto-injection.

### 5.2 Automated Jira Closure Comment

*   **What**: A "Complete Task" button that unlocks when the Bamboo Automation Suite passes.
*   **Rich-Text Comment**: Posts a structured comment to the Jira ticket containing:
    *   **Docker Tags**: The exact `dockerTagsAsJson` used (in a collapsible section).
    *   **Test Results Summary**: Total tests, pass rate %, failures count.
    *   **Links**: Direct links to the Bamboo build plan results.
*   **Status Transition**: Automatically moves the Jira ticket from "In Progress" to "In Review".
*   **How**: Bamboo API for results → Jira REST API (`/rest/api/2/issue/{issueId}/comment`) with wiki markup formatting → Jira transition API.

### 5.3 Cody Pre-Review (AI-Powered Diff Analysis)

*   **What**: Before creating a Pull Request, Cody reviews the entire git diff for common Spring Boot issues.
*   **Detection Patterns**:
    *   Missing `@Transactional` on service methods with DB writes.
    *   Unclosed resources (streams, connections).
    *   Missing error handling on external API calls.
    *   N+1 query patterns in JPA repositories.
    *   Missing input validation on controller endpoints.
*   **One-Click PR**: After Cody review, generates a PR description using Cody (combining Jira context + git diff summary) and opens the PR in Bitbucket.
*   **How**: `git diff develop...HEAD` → Cody API with Spring Boot-specific review prompt → results displayed as annotations in a review panel → Bitbucket REST API for PR creation.

---

## New Standalone Features

### 6.1 Configuration Drift Detector

*   **What**: An automatic staleness check for Docker tags before triggering the automation suite.
*   **The Problem**: Developers copy release tags from previous successful runs. Between runs, services may have published newer release versions. Using stale tags can cause false regression failures.
*   **How It Works**:
    1.  Before triggering, the plugin queries the Docker Registry for the **latest available** release tag of each service.
    2.  Compares against the tags in your staged payload.
    3.  Warns if any tag is outdated: *"service-payments: you selected `release-2.3.1` but `release-2.4.0` is available (released 2 days ago)."*
    4.  One-click **"Update All to Latest"** button refreshes all stale tags.
*   **How (Technical)**: Docker Registry v2 API tag listing → tag timestamp comparison → UI warning in the Staging panel.

### 6.2 Regression Blame & Auto-Triage

*   **What**: Intelligent parsing and categorization of automation suite results.
*   **The Problem**: After automation, developers manually scan hundreds of test results to find regressions. There's no distinction between new failures, pre-existing flakes, and fixed tests.
*   **Result Categories**:
    *   🔴 **New Regressions**: Tests that passed in the previous release baseline run but fail in yours.
    *   🟡 **Known Failures**: Tests that were already failing before your changes (flaky or pre-existing bugs).
    *   🟢 **Newly Fixed**: Tests that were previously failing but now pass with your changes.
*   **Blame Analysis**: For each new regression, the plugin cross-references the failing test with your recent commits to suggest which change may have caused it.
*   **Cody Investigation**: One-click "Investigate in Cody" sends the test failure stack trace + your recent changes to Cody for automated root cause analysis.
*   **How (Technical)**: Bamboo test result artifacts (JUnit XML format) → diff current results vs. previous baseline → git-blame for commit correlation → Cody API for root cause suggestion.

### 6.3 Smart Conflict Detector

*   **What**: Warns developers when another team member is running automation against the same microservice they changed.
*   **The Problem**: Two developers unknowingly change the same service and queue automation runs sequentially. The second run may produce unreliable results due to conflicting changes in the shared test environment.
*   **How It Works**:
    1.  When you stage or queue an automation run, the plugin queries the **Bamboo API** for currently running and recently queued builds.
    2.  Bamboo exposes the **triggering user** and the **build variables** (including `dockerTagsAsJson`).
    3.  The plugin parses the running build's tags and compares with your staged tags.
    4.  If overlapping services are detected, it warns: *"⚠️ @dev-X is currently running automation with service-auth tag `feature/PROJ-456`. Your run also changes service-auth. Consider coordinating."*
*   **Note**: This uses the Bamboo API only — no IDE-to-IDE communication required. Both developers' plugins independently poll the same Bamboo instance.
*   **How (Technical)**: Bamboo REST API (`/rest/api/latest/result/{planKey}`) → parse build variables from running/queued builds → tag overlap detection → notification.

### 6.4 Jira Time Tracking Integration

*   **What**: Automatic time tracking from task start to completion, with Jira worklog integration.
*   **The Problem**: Sprint velocity and time tracking require manual Jira log entries that developers often forget or estimate poorly.
*   **How It Works**:
    1.  Timestamps are recorded when the user clicks "Start Work" (branch creation) and "Complete Task" (Jira transition).
    2.  Before transitioning the ticket, a "Log Work" dialog appears pre-filled with the calculated elapsed time.
    3.  The user can adjust the time before submitting.
    4.  The worklog is posted to Jira automatically.
*   **Optional Enhancement**: Integration with WakaTime or DevTrackr for IDE activity-based time granularity (actual coding time vs. wall-clock time).
*   **How (Technical)**: Local timestamp tracking → Jira Worklog REST API (`/rest/api/2/issue/{issueId}/worklog`) → optional WakaTime plugin data aggregation.

### 6.5 Developer Productivity Dashboard

*   **What**: A personal analytics panel showing workflow efficiency metrics.
*   **Privacy-First**: All data is stored **locally only** in the `.idea/` directory. No team-wide sharing or server uploads.
*   **Metrics Displayed**:
    *   Average time from "Start Work" → "In Review" per ticket.
    *   Build success rate (first-attempt pass vs. number of remediation loops).
    *   Average automation queue wait time.
    *   Sonar coverage delta per PR (how much coverage you added).
    *   Number of Cody-generated vs. hand-written tests.
*   **Weekly Digest**: A toast notification summarizing your week's trends.
*   **How (Technical)**: Event listeners on all plugin actions (start work, build result, queue join/start, commit) → local SQLite or JSON store → custom dashboard panel rendered with IntelliJ's UI toolkit.

---

## Cross-Cutting Concerns

### 7.1 Authentication & User Context

*   **Unified Login**: Support for Personal Access Tokens (PAT) or OAuth for Jira, Bamboo, Bitbucket, and SonarQube.
*   **Secure Storage**: Credentials stored via IntelliJ's `PasswordSafe` API (OS keychain integration).
*   **User Filtering**: All views are filtered to the authenticated user's data.
*   **Persistence**: Last 5 `dockerTagsAsJson` configurations stored locally for quick reuse.

### 7.2 Onboarding Wizard

*   A first-run wizard that walks users step-by-step through: connecting Jira → Bamboo → Bitbucket → SonarQube → Cody Enterprise.
*   Each step includes a **"Test Connection"** button to verify credentials before proceeding.

### 7.3 Plugin Settings UI

*   A comprehensive settings page accessible from IntelliJ Preferences, covering:
    *   API endpoint URLs for all services.
    *   PAT/OAuth credential management.
    *   Notification preferences (enable/disable per event type).
    *   Commit message template format (standard vs. conventional commits).
    *   Branch naming regex pattern.
    *   Queue polling interval.
    *   Cody model selection.
    *   Docker Registry URL.

### 7.4 Offline / Degraded Mode

*   Gracefully handles scenarios when external APIs (Jira, Bamboo, SonarQube) are unreachable.
*   Shows cached data with a clear "Offline — data may be stale" banner.
*   Implements retry with exponential backoff.
*   Core IDE functionality (branching, committing, local builds) remains fully operational.

### 7.5 Notifications

*   System-level toast notifications for all key events:
    *   "Build Finished" / "Build Failed"
    *   "Sonar Quality Gate Failed — 2 issues found"
    *   "Your Queue Turn Has Started"
    *   "Automation Suite Passed / Failed"
*   All notifications are configurable (enable/disable per type) in plugin settings.

### 7.6 Performance

*   All API polling runs on dedicated background threads to prevent IDE lag.
*   Polling intervals are configurable with sensible defaults (30s for builds, 60s for queue status).
*   Local caches for Jira tickets, Cody summaries, and build results reduce redundant API calls.

### 7.7 Keyboard Shortcuts & Command Palette

*   Every major plugin action has a configurable keyboard shortcut.
*   A command palette (searchable action list) for all plugin actions, accessible via a single shortcut.

---

## Phase Roadmap

### Phase 1 — Core (Reliable, High-Value)

| # | Feature |
|---|---|
| 1 | Unified Sprint Dashboard + Cross-Team Dependencies |
| 2 | Smart Branching with Naming Validator |
| 3 | Commit Message Intelligence (Auto-Prefix + Cody Generation) |
| 4 | In-Editor Sonar Markers (Severity-Coded) |
| 5 | Coverage Closer (Cody Auto-TDD with Style Matching) |
| 6 | Shift-Left Incremental Build Validation |
| 7 | Parallel Build Dashboard (Core Status + Toasts) |
| 8 | Maven CVE Auto-Bumper (Core Quick Fix) |
| 9 | dockerTagsAsJson Builder (Diff View + Tag Validation) |
| 10 | Smart Queue with Wait Estimation |
| 11 | Configuration Drift Detector |
| 12 | Copyright Enforcer (Changed Files Only) |
| 13 | Jira Closure Comment (Rich-Text) |
| 14 | Cody Pre-Review |
| 15 | Jira Time Tracking |
| 16 | Authentication, Settings UI, Onboarding Wizard |
| 17 | Offline / Degraded Mode |

### Phase 2 — Advanced

| # | Feature |
|---|---|
| 1 | Cody Epic Summarization (Multi-Ticket + Caching) |
| 2 | Build Timeline View (Gantt + Trends) |
| 3 | Inline Build Error Tracing |
| 4 | Regression Blame & Auto-Triage |
| 5 | Smart Conflict Detector |
| 6 | Developer Productivity Dashboard |
| 7 | Keyboard Shortcuts & Command Palette |

### Phase 3 — Aspirational

| # | Feature |
|---|---|
| 1 | CVE Compatibility Matrix |
| 2 | Multi-Repo Workspace Support |
| 3 | Role-Based Views (Developer vs. Lead) |

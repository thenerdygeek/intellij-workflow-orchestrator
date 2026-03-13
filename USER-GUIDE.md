# Workflow Orchestrator — User Guide

A complete guide for developers using the Workflow Orchestrator IntelliJ plugin. This plugin consolidates Jira, Bamboo, SonarQube, Bitbucket, Cody Enterprise, and Nexus into a single IDE interface.

---

## Table of Contents

1. [Installation & First-Time Setup](#1-installation--first-time-setup)
2. [Settings Overview](#2-settings-overview)
3. [Daily Workflow (Recommended Order)](#3-daily-workflow-recommended-order)
4. [Sprint Dashboard (Jira)](#4-sprint-dashboard-jira)
5. [Build Dashboard (Bamboo)](#5-build-dashboard-bamboo)
6. [Quality Tab (SonarQube)](#6-quality-tab-sonarqube)
7. [Automation Tab (Docker/Bamboo)](#7-automation-tab-dockerbamboo)
8. [Handover Tab (PR & Closure)](#8-handover-tab-pr--closure)
9. [Status Bar Widget](#9-status-bar-widget)
10. [Commit Message Prefixing](#10-commit-message-prefixing)
11. [Editor Integrations](#11-editor-integrations)
12. [Health Check (Pre-Push Gate)](#12-health-check-pre-push-gate)
13. [Cody AI Features](#13-cody-ai-features)
14. [Keyboard Shortcuts & Context Menus](#14-keyboard-shortcuts--context-menus)
15. [Troubleshooting](#15-troubleshooting)

---

## 1. Installation & First-Time Setup

### Install the Plugin
1. Download the `.zip` file from the release page
2. In IntelliJ: **Settings > Plugins > Gear icon > Install Plugin from Disk**
3. Select the `.zip` file and restart the IDE

### First Run
When the plugin loads for the first time:
- A **GotItTooltip** appears anchored to the "Workflow" tool window button at the bottom of the IDE
- Click **Start Setup** to open the settings page, or **Later** to skip
- The tooltip only appears once per IDE installation

### Configure Connections (Required)
Go to **Settings > Tools > Workflow Orchestrator > Connections**

Configure each service you use:

| Service | Auth Type | What You Need |
|---------|-----------|---------------|
| **Jira** | Bearer (PAT) | Server URL + Personal Access Token |
| **Bamboo** | Bearer (PAT) | Server URL + Personal Access Token |
| **Bitbucket** | Bearer (HTTP Access Token) | Server URL + HTTP Access Token |
| **SonarQube** | Bearer (User Token) | Server URL + User Token |
| **Cody Enterprise** | Sourcegraph Token | Sourcegraph URL + Access Token |
| **Nexus** | Basic Auth | Registry URL + Username + Password |

For each service:
1. Enter the **Server URL** (e.g., `https://jira.company.com`)
2. Enter your **Access Token** (stored securely in OS keychain via PasswordSafe)
3. Click **Test Connection** to verify
4. Click **Apply** when done

> **Note:** Not all services are required. The plugin works with whatever you configure. At minimum, configure **Jira** to use the Sprint Dashboard.

### Configure Board Settings
Go to **Settings > Tools > Workflow Orchestrator > Workflow Mapping**

- **Board Type**: Select `Scrum`, `Kanban`, or leave blank for auto-detect
- **Board ID**: Enter your board ID if you know it, or click **Discover Boards** to find available boards
- **Workflow Mapping**: Map your Jira status names to workflow transitions (optional)

---

## 2. Settings Overview

Click **Workflow Orchestrator** in the settings tree to see:

### Root Page
- **Connection Status** — quick view of which services are configured
- **Branch pattern** — naming pattern for new branches (default: `feature/{ticketId}-{summary}`)
- **Conventional commits** — toggle for `feat:`, `fix:` prefixes
- **Module toggles** — enable/disable Sprint, Build, Quality, Automation, Handover tabs

### Sub-Pages
| Page | What It Configures |
|------|--------------------|
| **Connections** | Server URLs and tokens for all 6 services |
| **Workflow Mapping** | Jira board type, board ID, status transition mapping |
| **Advanced** | Polling intervals, timeouts, health check rules, automation settings |

---

## 3. Daily Workflow (Recommended Order)

This is the typical developer workflow using the plugin. **Steps are sequential** — each builds on the previous.

```
1. Pick a ticket          → Sprint tab
2. Start work             → Creates branch + transitions Jira
3. Write code             → Commit messages auto-prefixed
4. Monitor builds         → Build tab (auto-polls)
5. Check quality          → Quality tab (coverage, issues)
6. Fix issues             → Cody AI + gutter markers
7. Configure automation   → Automation tab (docker tags)
8. Queue automation run   → Automation tab
9. Complete task          → Handover tab (PR, Jira comment, time log)
```

> **Independent features (no order required):**
> - Viewing build status — check anytime
> - Checking SonarQube quality — check anytime
> - Searching tickets — search anytime
> - Toggling "All Tickets" view — toggle anytime
> - Using Cody AI fixes — use anytime while coding

---

## 4. Sprint Dashboard (Jira)

**Location:** Tool window (bottom of IDE) > **Sprint** tab

### What You See
- **Sprint header** — sprint name, date range, and ticket count
- **Progress bar** — green (done), blue (in progress), gray (to do)
- **Ticket list** (left) — your assigned tickets as visual cards
- **Ticket detail** (right) — full details of selected ticket
- **Search bar** — filter tickets by key, summary, or assignee name

### Ticket Cards
Each card shows:
- **Priority indicator** (colored circle): red = highest, orange = high, blue = medium, green = low
- **Ticket key** (bold, e.g., `PROJ-123`)
- **Summary** (truncated if long)
- **Status pill** (colored: green = done, blue = in progress, gray = to do)
- **Issue type** badge (Task, Bug, Story, etc.)
- **Assignee** name
- **Blocker count** (if any, shown in red)

### Ticket Detail Panel
Click a ticket to see:
- Full summary and description (HTML rendered)
- Priority, issue type, assignee, sprint info, dates
- **Dependencies** — linked tickets (blocks, blocked by, related) with status dots

### "All Tickets" Toggle
Click the **All Tickets** button in the toolbar to switch between:
- **My Tickets** — only tickets assigned to you (default)
- **All Tickets** — all team tickets grouped by assignee with section headers

This is useful for:
- Pulling other people's tasks into your sprint
- Starting work on someone else's ticket
- Getting branch names for docker tag configuration

### Start Work
1. Select a ticket in the list
2. Click **Start Work** in the toolbar (or detail panel)
3. The plugin will:
   - Create a Git branch (e.g., `feature/PROJ-123-fix-login-page`)
   - Transition the Jira ticket to "In Progress"
   - Set the ticket as your active ticket (shown in status bar)
   - Auto-prefix future commit messages with the ticket ID

### Toolbar Actions
| Button | Action |
|--------|--------|
| Refresh | Reload tickets from Jira |
| All Tickets / My Tickets | Toggle team view |
| Start Work | Create branch + transition ticket |

---

## 5. Build Dashboard (Bamboo)

**Location:** Tool window > **Build** tab

### What You See
- **Build header** — plan key, branch name, build number
- **Stage list** (left) — three parallel build stages with progress bars
- **Stage detail** (right) — log output and error details for selected stage

### Build Stages
The three standard stages are:
1. **Artifact Build** — Maven compilation
2. **OSS Analysis** — Security/vulnerability scan
3. **SonarQube** — Code coverage analysis

Each stage shows:
- Status icon: checkmark (passed), cross (failed), spinner (running)
- Progress bar (visual fill)
- Duration (HH:mm:ss)

### How It Works
- **Automatic polling** — build status refreshes every 30 seconds (configurable)
- **Branch detection** — automatically shows builds for your current Git branch
- **Notifications** — toast notifications on build pass/fail
- Click a failed stage to see **parsed error logs** with highlighted compilation errors and test failures

### Manual Actions
- **Refresh** — manually refresh build status
- **Run Manual Stage** — right-click a stage to re-trigger it

> **No specific order required** — you can check build status at any time during development.

---

## 6. Quality Tab (SonarQube)

**Location:** Tool window > **Quality** tab

### Sub-Tabs

#### Overview
- **Quality gate status** — large indicator showing passed/failed
- **Coverage metrics** — new code coverage %, branch coverage %
- **Thresholds** — 100% new code coverage, 95% new branch coverage
- **Metric cards** — bugs, code smells, duplications

#### Issues
- List of all open SonarQube issues
- Filter by severity: Blocker, Critical, Major, Minor
- Each issue shows: type, file:line location, message
- Click to navigate to the issue in the editor

#### Coverage
- Table of all project files with coverage data
- Columns: file name, line coverage %, branch coverage %
- Color-coded rows: green (90-100%), yellow (70-89%), red (<70%)
- Sortable by any column

### How It Works
- Data refreshes automatically via polling (configurable interval)
- Status label at the bottom shows when data was last updated
- **Refresh** button for manual refresh

> **No specific order required** — check quality anytime. Especially useful after pushing code and waiting for SonarQube analysis.

---

## 7. Automation Tab (Docker/Bamboo)

**Location:** Tool window > **Automation** tab

This tab manages the `dockerTagsAsJson` build variable used to trigger Bamboo automation suites with specific Docker image tags.

### Queue Status Panel (Top)
- Shows if the automation suite is **Idle** or **Running**
- If running: who triggered it and estimated time remaining
- Your position in the queue and estimated wait time

### Tag Staging Panel (Main Area)
A table of services and their Docker tags:

| Column | Description |
|--------|-------------|
| Service | Service name (e.g., `service-auth`) |
| Current Tag | The tag you're staging (editable) |
| Latest Release | The latest release tag from the registry |
| Registry Status | Whether the tag exists in Nexus (validated) |
| Status | OK, Drift (newer available), or Missing |

### Visual Indicators
- **Your service** — highlighted in light green
- **Drift warning** — highlighted in orange (newer tag available)
- **Missing tag** — highlighted in red

### Key Features

#### Configuration Drift Detection
If a newer tag is available for a service, the plugin shows a warning and offers **"Update All to Latest"** to bring all tags current.

#### Tag Validation
Before triggering, the plugin pings the Docker Registry (Nexus) to verify each tag exists. Invalid tags are flagged before the build starts.

#### Conflict Detection
If another developer is running automation with overlapping services, you'll see a warning with their name and the conflicting service/tag.

### Workflow
1. Edit the **Current Tag** column for services you want to deploy
2. Review the **diff view** (your config vs. last successful run)
3. Click **Queue Run** to trigger (or enqueue if busy)
4. If queued, **auto-trigger** fires when your turn arrives
5. A notification appears when your run starts and completes

### Last 5 Configs
Previous `dockerTagsAsJson` payloads are saved. Quick-reuse without re-entering tags.

---

## 8. Handover Tab (PR & Closure)

**Location:** Tool window > **Handover** tab

This tab orchestrates the end-of-task workflow: copyright checks, AI review, PR creation, Jira closure, and time logging. The panels are laid out as a toolbar with 7 buttons, each switching to a different panel.

### Recommended Order (Sequential)
These steps should be done **in order** for a complete handover:

#### Step 1: Copyright Enforcer
- Shows all changed Java files and their copyright header status
- Green checkmark = has header, red cross = missing
- Click **Rescan** to re-check after edits
- Click **Fix All** to auto-inject missing headers using the company template

#### Step 2: Cody Pre-Review
- AI-powered analysis of your diff
- Highlights Spring Boot-specific issues:
  - Missing `@Transactional` annotations
  - Unclosed resources
  - Missing error handling
  - N+1 query patterns
  - Missing input validation
- Review suggestions before creating a PR

#### Step 3: PR Creation
- **Title** — auto-generated as `TICKET-ID: summary` (editable)
- **Source branch** — auto-detected from current branch
- **Target branch** — configurable (default: `develop`)
- **Description** — auto-generated from diff analysis (editable)
- Click **Regenerate Description** to re-run Cody analysis
- Click **Create PR** to open in Bitbucket
- Result shows PR URL on success

#### Step 4: Jira Closure Comment
- Preview of formatted rich-text comment including:
  - Docker tags used (collapsible section)
  - Test results summary
  - Links to Bamboo build
- Click **Edit** to modify in rich-text editor
- Click **Post Comment** to submit to Jira

#### Step 5: Time Logging
- Timestamps auto-recorded from "Start Work" to now
- Pre-filled elapsed time (editable)
- Add work description
- Click **Log Work** to submit worklog to Jira

#### Step 6: QA Clipboard
- Formats test results and deployment info for QA handoff
- One-click copy to clipboard
- Includes: service tags, build links, test counts

#### Step 7: Completion Macro (Optional)
- Orchestrates all handover steps in sequence:
  1. Check copyright headers
  2. Run Cody pre-review
  3. Create PR
  4. Post Jira comment
  5. Log time
  6. Transition ticket to "In Review"
- Use this for a one-click complete handover

---

## 9. Status Bar Widget

**Location:** Bottom-right corner of the IDE

### What It Shows
- **Active ticket**: `PROJ-123` with a checkmark
- **No ticket**: `Workflow: Idle`

### Click to Expand
Clicking the widget shows a popup with:
- Active ticket ID and full summary
- Current branch name
- Quick context without opening the tool window

### Auto-Updates
- Updates when you switch branches (auto-detects ticket from branch name)
- Updates when you click "Start Work"
- Persists across IDE restarts

---

## 10. Commit Message Prefixing

**Location:** VCS commit dialog (automatic)

### How It Works
When you have an active ticket, every commit message is automatically prefixed:

- **Standard format**: `PROJ-123: your message here`
- **Conventional commits**: `feat(PROJ-123): your message here`

### Rules
- Duplicate prefixes are prevented — if the message already contains the ticket ID, no prefix is added
- Format is controlled by the **"Use conventional commits"** toggle in settings
- Works with both the commit dialog and command-line commits (via VCS checkin handler)

> **Automatic** — no action required. Just write your commit message and the prefix is added.

---

## 11. Editor Integrations

### Gutter Markers (SonarQube Coverage)
In the left gutter of the editor:
- **Grey dot** — line is covered by tests
- **Red dot** — line has no test coverage
- **Yellow dot** — partially covered (some branches untested)
- **Special icon on REST endpoints** — uncovered `@RequestMapping` endpoints (high priority)

Hover over a marker to see details. Right-click for actions like "Cover with Cody."

### File Tree Badges
In the Project view (left sidebar), Java files show coverage badges:
- **Green** — 90-100% coverage
- **Yellow** — 70-89% coverage
- **Red** — below 70% coverage

### CVE Annotations (pom.xml)
In `pom.xml` files, vulnerable dependencies are highlighted with yellow/red squiggles. Use **Alt+Enter** to see the quick fix: "Bump to fix CVE vulnerability."

### Editor Banners
Low-coverage files show a banner at the top of the editor with a warning and action link.

---

## 12. Health Check (Pre-Push Gate)

**Location:** VCS commit dialog > checkbox, or triggered by `PrePushHandler`

### What It Checks
1. **Maven compilation** — only for changed modules (incremental)
2. **Maven tests** — only if test files changed
3. **Copyright headers** — on changed Java files
4. **SonarQube quality gate** — checks quality gate status

### How to Use
- Enable/disable in **Settings > Advanced > Health Check**
- Toggle individual checks (compile, test, copyright, sonar gate)
- Set a **skip branch pattern** (regex) to bypass on certain branches (e.g., `develop|main`)
- Configure **timeout** per check (default: 60 seconds)

### Behavior
- **Soft mode** (default) — warns on failure but allows push
- **Hard mode** — blocks push on failure
- Runs **incrementally** — detects changed modules via `git diff` and only builds those

---

## 13. Cody AI Features

Requires Cody Enterprise (Sourcegraph) to be configured.

### "Cover with Cody" (Gutter Action)
1. Right-click a **red coverage marker** in the gutter
2. Select "Generate Test for Uncovered Branch"
3. Cody analyzes:
   - Target method and class context
   - Spring bean dependencies
   - Existing test style in the module
4. Generates a JUnit 5 / Mockito test matching your team's conventions
5. Appends to the matching `*Test.java` file

### "Fix with Cody" (Alt+Enter)
1. Highlight code or place cursor on a SonarQube issue
2. Press **Alt+Enter**
3. Select "Ask Cody to fix this code"
4. Cody generates a fix with a **diff preview**
5. Accept or reject the changes

### Commit Message Generation
- In the commit dialog, Cody can analyze your diff and generate a descriptive commit message
- Review and edit before committing

### Pre-Review (Handover Tab)
- Cody analyzes your full diff for Spring Boot-specific issues
- Available in the Handover tab before creating a PR

---

## 14. Keyboard Shortcuts & Context Menus

### Context Menu
Right-click in the editor to see **"Workflow Orchestrator"** submenu:
- Generate Test for Uncovered Branch (on red gutter markers)
- Ask Cody About This Code
- View SonarQube Issues for This File

### Quick Actions (Tool Window Title Bar)
Small icon buttons at the top of the Workflow tool window:
| Icon | Action |
|------|--------|
| Refresh | Reload current tab data |
| Globe | Open current ticket in Jira browser |
| Arrow | Switch to next tab |

### Gear Menu (Tool Window)
Click the wrench/gear icon for:
- Open Connections Settings
- Refresh All Tabs
- Clear Active Ticket
- Open Jira / Bamboo / SonarQube in Browser

---

## 15. Troubleshooting

### Plugin Not Loading
- Check **Help > Show Log in Explorer/Finder** for errors
- Ensure IntelliJ 2025.1+ is installed
- Try **File > Invalidate Caches > Restart**

### "No tickets assigned" After Configuring Jira
- Verify **Test Connection** shows "Connected successfully"
- Click **Apply** and **OK** in settings
- Switch to another tab and back, or click **Refresh** in the Sprint tab
- Check that your Jira PAT user has tickets assigned in the active sprint

### Only Some Tabs Showing
- This can happen if a service connection fails during tab creation
- Open the IDE log (**Help > Show Log**) and search for `[Workflow]`
- Reconfigure the failing service in Connections settings

### Build Tab Shows No Data
- Verify Bamboo connection is configured
- Check that **Bamboo Plan Key** is set in Workflow Mapping settings
- Ensure you're on a branch that has Bamboo builds

### Board Type Issues
- If you see "No active sprint" on a Kanban board, set the board type to **Kanban** in Workflow Mapping
- Click **Discover Boards** to see all available boards with their types and IDs
- For Scrum boards without an active sprint, the plugin falls back to showing board issues

### Tokens Not Persisting
- Tokens are stored in the OS keychain (PasswordSafe)
- On Windows, ensure Windows Credential Manager is accessible
- On macOS, ensure Keychain Access is unlocked
- Check that IntelliJ has keychain access permissions

### Performance Issues
- Reduce polling intervals in **Advanced** settings if the IDE feels slow
- Disable modules you don't use (root settings page > Enabled Modules)
- Check the IDE log for excessive API calls

---

## Feature Summary

| Feature | Tab/Location | When to Use |
|---------|-------------|-------------|
| View sprint tickets | Sprint tab | Start of day |
| Toggle all team tickets | Sprint tab toolbar | When reviewing team work |
| Search/filter tickets | Sprint tab search bar | Anytime |
| Start work on ticket | Sprint tab > Start Work | Beginning of a task |
| Monitor build status | Build tab | After pushing code |
| Check quality gate | Quality tab > Overview | Before PR |
| View coverage details | Quality tab > Coverage | During development |
| Review SonarQube issues | Quality tab > Issues | During development |
| Configure docker tags | Automation tab | Before deployment |
| Queue automation run | Automation tab | After code is ready |
| Check copyright headers | Handover tab > Copyright | Before PR |
| AI code review | Handover tab > Cody | Before PR |
| Create pull request | Handover tab > PR | After code review |
| Post Jira comment | Handover tab > Jira | After PR |
| Log work hours | Handover tab > Time | End of task |
| One-click completion | Handover tab > Macro | End of task |
| Auto-prefix commits | Commit dialog (auto) | Every commit |
| Coverage gutter markers | Editor gutter | During development |
| Cody AI fixes | Editor > Alt+Enter | During development |
| Health check | Pre-push gate | Before pushing |

---

*Generated for Workflow Orchestrator v0.5.x*

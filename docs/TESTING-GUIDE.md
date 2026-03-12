# Workflow Orchestrator — Field Testing Guide

> Step-by-step guide for testing the plugin on a fresh laptop with no development environment.

---

## Prerequisites

| Requirement | Details |
|---|---|
| **IntelliJ IDEA** | 2025.1+ (Community or Ultimate) |
| **Java** | JDK 21+ installed and configured |
| **Plugin ZIP** | `intellij-workflow-orchestrator-*.zip` from GitHub Releases |
| **A Spring Boot project** | Any Maven-based Spring Boot project (for full testing) |
| **Service access** | Jira Server, Bamboo Server, SonarQube, Bitbucket Server, Nexus Docker Registry, Cody Enterprise (test whichever you have) |

---

## Installation

1. Download `intellij-workflow-orchestrator-*.zip` from [GitHub Releases](https://github.com/thenerdygeek/intellij-workflow-orchestrator/releases)
2. Open IntelliJ IDEA
3. Go to **Settings → Plugins → ⚙️ (gear icon) → Install Plugin from Disk...**
4. Select the downloaded ZIP file
5. Click **OK** and **restart** IntelliJ IDEA

### Verify Installation
After restart, check:
- **Help → About → Plugin list** — "Workflow Orchestrator" should appear
- **View → Tool Windows** — "Workflow" should be listed

---

## Where Are the Logs?

### Log File Location

| OS | Path |
|---|---|
| **macOS** | `~/Library/Logs/JetBrains/IntelliJIdea2025.1/idea.log` |
| **Linux** | `~/.local/share/JetBrains/IntelliJIdea2025.1/log/idea.log` |
| **Windows** | `%APPDATA%\JetBrains\IntelliJIdea2025.1\log\idea.log` |

> **Note:** Replace `IntelliJIdea2025.1` with your actual IDE version folder. For Community Edition, it's `IdeaIC2025.1`.

### How to View Logs

**Option 1: From IDE**
- **Help → Show Log in Finder/Explorer** (opens the log directory)
- **Help → Diagnostic Tools → Debug Log Settings...** (to enable DEBUG level)

**Option 2: From Terminal (live tail)**
```bash
# macOS
tail -f ~/Library/Logs/JetBrains/IntelliJIdea2025.1/idea.log | grep -E "\[Core:|Jira:|Bamboo:|Sonar:|Automation:|Handover:|Cody:"

# Linux
tail -f ~/.local/share/JetBrains/IntelliJIdea2025.1/log/idea.log | grep -E "\[Core:|Jira:|Bamboo:|Sonar:|Automation:|Handover:|Cody:"
```

### Enable DEBUG Logging

By default, only `INFO`, `WARN`, and `ERROR` messages appear. To see `DEBUG` messages:

1. **Help → Diagnostic Tools → Debug Log Settings...**
2. Add these lines (one per line):
```
com.workflow.orchestrator
```
3. Click **OK**
4. Restart IntelliJ or click **Help → Diagnostic Tools → Restart Logging**

### Log Prefixes by Module

All plugin log messages use bracketed prefixes for easy filtering:

| Prefix | Module | What It Covers |
|---|---|---|
| `[Core:Auth]` | core | Connection testing, credential operations |
| `[Core:HTTP]` | core | Auth headers, retries, timeouts |
| `[Core:Events]` | core | Event bus emissions |
| `[Core:Connectivity]` | core | Online/offline state changes |
| `[Core:Onboarding]` | core | First-run detection, tooltip display |
| `[Core:Notifications]` | core | Notification delivery |
| `[Jira:API]` | jira | REST API calls and responses |
| `[Jira:Branch]` | jira | Branch creation, ticket detection |
| `[Jira:Workflow]` | jira | Intent resolution steps |
| `[Jira:Mapping]` | jira | Transition mapping lookups/saves |
| `[Jira:Executor]` | jira | Transition execution with fields |
| `[Bamboo:API]` | bamboo | REST API calls and responses |
| `[Bamboo:Monitor]` | bamboo | Polling cycles, build state changes |
| `[Bamboo:Parser]` | bamboo | Build log parsing results |
| `[Bamboo:CVE]` | bamboo | CVE detection and remediation |
| `[Bamboo:Plan]` | bamboo | Build plan detection |
| `[Sonar:API]` | sonar | REST API calls and responses |
| `[Sonar:Data]` | sonar | Data refresh, quality gate status |
| `[Sonar:ProjectKey]` | sonar | Project key detection method |
| `[Sonar:Issues]` | sonar | Issue mapping summary |
| `[Sonar:Coverage]` | sonar | Coverage mapping results |
| `[Automation:Tags]` | automation | Tag building, JSON preview |
| `[Automation:Queue]` | automation | Queue operations, state changes |
| `[Automation:Drift]` | automation | Drift detection results |
| `[Automation:Conflict]` | automation | Conflict detection results |
| `[Automation:History]` | automation | Tag history saves/loads |
| `[Automation:Registry]` | automation | Docker Registry API calls |
| `[Handover:Jira]` | handover | Jira closure, comments, transitions |
| `[Handover:PR]` | handover | PR creation, title rendering |
| `[Handover:Review]` | handover | Cody pre-review, diff validation |
| `[Handover:Time]` | handover | Worklog operations |
| `[Handover:State]` | handover | Handover state transitions |
| `[Handover:Macro]` | handover | Completion macro steps |
| `[Handover:Copyright]` | handover | Copyright header scanning/fixing |
| `[Handover:QA]` | handover | QA clipboard generation |
| `[Cody:Agent]` | cody | Agent lifecycle, JSON-RPC |
| `[Cody:Chat]` | cody | Chat sessions, message submission |

---

## Step-by-Step Testing

### Phase 1: Plugin Boot & Onboarding

**What to test:** Plugin loads, tool window appears, onboarding tooltip shows.

| Step | Action | Expected Result |
|---|---|---|
| 1.1 | Open any project in IntelliJ | IDE loads normally, no errors |
| 1.2 | Look at bottom tool window strip | "Workflow" tab should appear |
| 1.3 | First time only: observe tooltip | GotItTooltip: "Welcome to Workflow Orchestrator!" with "Start Setup" link |
| 1.4 | Click "Workflow" tab | Tool window opens with 5 tabs: Sprint, Build, Quality, Automation, Handover |
| 1.5 | Click each tab | Each tab loads (may show empty state) |

**Logs to check:**
```bash
grep "\[Core:Onboarding\]" idea.log
```
Expected:
```
[Core:Onboarding] First run detected — showing welcome tooltip
[Core:Onboarding] Welcome tooltip displayed, anchored to Workflow tool window
```

**If it fails:**
- No "Workflow" tab → check `idea.log` for plugin loading errors: `grep -i "workflow.orchestrator" idea.log | grep -i error`
- Plugin not in plugin list → ZIP may be corrupt, re-download

---

### Phase 2: Settings & Connections

**What to test:** All settings pages render, connections can be tested.

| Step | Action | Expected Result |
|---|---|---|
| 2.1 | Open **Settings → Tools → Workflow Orchestrator** | Parent settings page with 4 sub-pages |
| 2.2 | Click **Connections** | 6 service panels: Jira, Bamboo, Bitbucket, SonarQube, Cody Enterprise, Nexus |
| 2.3 | Enter Jira URL + PAT, click **Test Connection** | Background task runs → "Connected" or error message |
| 2.4 | Repeat for each available service | Each shows Connected or descriptive error |
| 2.5 | Click **Apply/OK**, close Settings, reopen | URLs persist, tokens stored securely |
| 2.6 | Click **Workflow Mapping** sub-page | Board type dropdown, 5 intent mapping fields, 4 guard checkboxes |
| 2.7 | Click **Advanced** sub-page | 7 groups: Network, Quality Thresholds, Time Tracking, Branching & PRs, Cody AI, Automation |

**Logs to check:**
```bash
grep "\[Core:Auth\]" idea.log
```
Expected:
```
[Core:Auth] Testing connection to Jira at https://your-jira.com
[Core:Auth] Jira connection test: SUCCESS (200 OK)
```
or on failure:
```
[Core:Auth] Jira connection test: FAILED — 401 Unauthorized
```

**Credential security check:**
```bash
# Verify tokens are NOT in plain XML
grep -r "token\|password\|secret\|pat" ~/Library/Application\ Support/JetBrains/IntelliJIdea2025.1/options/workflowOrchestrator.xml
```
Should find NO tokens. They live in OS keychain (PasswordSafe).

---

### Phase 3: Sprint Dashboard (Jira)

**Prerequisite:** Jira connected, you have tickets assigned to you in a sprint.

| Step | Action | Expected Result |
|---|---|---|
| 3.1 | Click **Sprint** tab in Workflow tool window | List of your sprint tickets (or empty state with "No tickets assigned") |
| 3.2 | Click a ticket | Ticket details expand (key, summary, status, links) |
| 3.3 | Look for cross-team dependency links | Blocked-by links shown if present |

**Logs to check:**
```bash
grep "\[Jira:API\]" idea.log
```
Expected:
```
[Jira:API] GET /rest/agile/1.0/board/{boardId}/sprint/{sprintId}/issue → 200
[Jira:API] Fetched 5 sprint tickets for current user
```

> **Note:** Sprint tab may be a stub if Phase 1B UI wasn't fully implemented. Check logs for errors vs. empty response.

---

### Phase 4: Start Work (Branch + Jira Transition)

**Prerequisite:** Jira connected, a ticket in ready/to-do state, a Git repository open.

| Step | Action | Expected Result |
|---|---|---|
| 4.1 | Select a ticket in Sprint tab | Ticket highlighted |
| 4.2 | Click "Start Work" (or right-click → Workflow Orchestrator → Start Work) | Dialog: confirm branch name |
| 4.3 | Confirm branch creation | Git branch created (e.g., `feature/PROJ-123-summary`), Jira ticket transitions to "In Progress" (or your configured start transition) |
| 4.4 | Check status bar (bottom) | Should show active ticket: `PROJ-123` |
| 4.5 | Switch to a different branch manually | Status bar updates (or clears) |

**Logs to check:**
```bash
grep -E "\[Jira:Branch\]|\[Jira:Workflow\]|\[Jira:Mapping\]" idea.log
```
Expected:
```
[Jira:Branch] Creating branch feature/PROJ-123-fix-login for ticket PROJ-123
[Jira:Workflow] Resolving intent START_WORK for project PROJ
[Jira:Workflow] Step 1: Checking explicit mapping → not found
[Jira:Workflow] Step 2: Checking learned mapping → not found
[Jira:Workflow] Step 3: Checking name match → found "Start Progress" (id=21)
[Jira:Branch] Branch created successfully, transitioning Jira ticket
[Jira:Executor] Executing transition id=21 on PROJ-123 with 0 fields
```

**If disambiguation popup appears:**
```
[Jira:Workflow] Multiple matches found for START_WORK: [Start Progress, Begin Work]
[Jira:Workflow] Showing disambiguation popup to user
[Jira:Mapping] Saved learned mapping: START_WORK → "Start Progress" (id=21)
```

---

### Phase 5: Build Dashboard (Bamboo)

**Prerequisite:** Bamboo connected, at least one build plan configured.

| Step | Action | Expected Result |
|---|---|---|
| 5.1 | Click **Build** tab in Workflow tool window | Build dashboard with plan/build status |
| 5.2 | Wait ~30 seconds | Build status should auto-refresh (polling) |
| 5.3 | Check status bar (bottom) | Build status widget: `PLAN-KEY: ✓ #123` or `✗ #124` |
| 5.4 | If a build is running, watch it update | Status changes from ⟳ to ✓ or ✗ |
| 5.5 | Click on a failed build | Build log/details shown |

**Logs to check:**
```bash
grep "\[Bamboo:Monitor\]" idea.log
```
Expected:
```
[Bamboo:Monitor] Starting polling cycle for 3 plan keys
[Bamboo:Monitor] Build PROJ-PLAN-456 status: SUCCESS
[Bamboo:Monitor] Build PROJ-PLAN-456 changed from RUNNING to SUCCESS
[Bamboo:Monitor] Polling cycle complete, next in 30s
```

**If connection fails:**
```bash
grep "\[Bamboo:API\].*error\|ERROR" idea.log
```

---

### Phase 6: Quality Dashboard (SonarQube)

**Prerequisite:** SonarQube connected, project has SonarQube analysis data.

| Step | Action | Expected Result |
|---|---|---|
| 6.1 | Click **Quality** tab in Workflow tool window | Quality gate status (PASSED/FAILED), issue list, coverage overview |
| 6.2 | Open a source file that has Sonar coverage | Gutter markers: green (covered), red (uncovered), orange (partial) |
| 6.3 | Open a file with low branch coverage | Yellow banner: "N uncovered branch(es) in this file" |
| 6.4 | Check Project tool window (left side file tree) | Coverage % badges next to file names (green/yellow/grey) |
| 6.5 | Hover over a Sonar issue squiggly | Tooltip: rule, message, effort, severity |
| 6.6 | Alt+Enter on a Sonar issue | "Workflow: Fix with Cody" option (if Cody connected) |

**Logs to check:**
```bash
grep "\[Sonar:" idea.log
```
Expected:
```
[Sonar:ProjectKey] Detected project key: com.example:my-service
[Sonar:API] GET /api/measures/component_tree for com.example:my-service → 200
[Sonar:Data] Refreshed: 45 files with coverage, 12 issues, quality gate: OK
[Sonar:Coverage] Overall coverage: 78.5% (45 files)
[Sonar:Issues] Mapped 12 issues: 2 BLOCKER, 3 CRITICAL, 5 MAJOR, 2 MINOR
```

---

### Phase 7: Coverage Markers (Gutter + Tree)

**Prerequisite:** SonarQube data loaded (Phase 6 passed).

| Step | Action | Expected Result |
|---|---|---|
| 7.1 | Open a Java/Kotlin source file | Gutter markers appear on covered/uncovered lines |
| 7.2 | Hover over a **red** gutter marker | Tooltip: "Not covered" |
| 7.3 | Hover over a **green** gutter marker | Tooltip: "Covered" |
| 7.4 | Hover over an **orange** gutter marker | Tooltip: "Partially covered (X of Y branches)" |
| 7.5 | Check file tree in Project view | Files show coverage % in colored text |
| 7.6 | Open a file with <50% coverage | Coverage badge is grey/red |
| 7.7 | Check thresholds in **Advanced Settings** | High=80%, Medium=50% by default; change and verify colors update |

**Logs to check:**
```bash
grep "\[Sonar:Coverage\]" idea.log
```

---

### Phase 8: CVE Detection (Bamboo + pom.xml)

**Prerequisite:** Bamboo connected, build has CVE scan results.

| Step | Action | Expected Result |
|---|---|---|
| 8.1 | Open `pom.xml` in editor | CVE-affected dependencies highlighted |
| 8.2 | Hover over highlighted dependency | Tooltip: CVE ID, severity, fix version |
| 8.3 | Alt+Enter on highlighted dependency | "Bump to fix CVE vulnerability" quick-fix |
| 8.4 | Apply the fix | Version updated in pom.xml, notification shown |

**Logs to check:**
```bash
grep "\[Bamboo:CVE\]" idea.log
```

---

### Phase 9: Automation Suite

**Prerequisite:** Bamboo connected, Docker Registry (Nexus) connected.

| Step | Action | Expected Result |
|---|---|---|
| 9.1 | Click **Automation** tab | Staging panel: service table + tag selector |
| 9.2 | Add services and select tags | Tags appear in table, JSON preview updates |
| 9.3 | Check "Validate tags" | Registry pinged for each tag (HEAD request) |
| 9.4 | Click "Diff" button | Shows diff: your config vs last successful run |
| 9.5 | Check drift detector | "Update All to Latest" if services are behind |
| 9.6 | Click "Queue" / "Trigger Build" | Build queued in Bamboo with dockerTagsAsJson variable |
| 9.7 | Check status bar | Automation widget shows queue state |

**Logs to check:**
```bash
grep "\[Automation:" idea.log
```
Expected:
```
[Automation:Tags] Building dockerTagsAsJson with 3 services
[Automation:Registry] HEAD /v2/my-service/manifests/1.2.3 → 200 (tag exists)
[Automation:Queue] Enqueued build with 3 services, position: 1
[Automation:Drift] Checked 5 services: 2 drifts detected
[Automation:Conflict] No overlapping service tags detected
```

---

### Phase 10: Handover Workflow

**Prerequisite:** Jira + Bitbucket connected, active ticket, code changes committed.

| Step | Action | Expected Result |
|---|---|---|
| 10.1 | Click **Handover** tab | 7-step workflow panel |
| 10.2 | **Step 1: Copyright** | Scan changed files, fix headers if needed |
| 10.3 | **Step 2: Pre-Review** | Cody analyzes diff for Spring Boot anti-patterns |
| 10.4 | **Step 3: PR Creation** | One-click PR with template title (`{ticketId}: {summary}`) |
| 10.5 | **Step 4: Jira Comment** | Rich closure comment preview (docker tags, test results) |
| 10.6 | **Step 5: Time Log** | Worklog dialog (hours, date) |
| 10.7 | **Step 6: QA Handover** | QA email template copied to clipboard |
| 10.8 | **Step 7: Complete** | Jira transitions to "In Review" (or configured transition) |

**Logs to check:**
```bash
grep "\[Handover:" idea.log
```
Expected flow:
```
[Handover:Copyright] Scanned 8 changed files, 2 need header updates
[Handover:Copyright] Fixed copyright header in UserService.kt
[Handover:Review] Building enriched review prompt (diff: 234 lines)
[Handover:Review] Diff validation: OK (234 lines, max 10000)
[Handover:PR] Rendering PR title: "PROJ-123: Fix user login validation"
[Handover:PR] Creating PR: feature/PROJ-123 → main, 2 reviewers
[Handover:Jira] Building closure comment for PROJ-123
[Handover:Jira] Posting comment to PROJ-123 (wiki markup, 456 chars)
[Handover:Time] Logging 2.5 hours to PROJ-123
[Handover:State] Transitioning PROJ-123 to "In Review"
[Handover:QA] Generated QA handover clipboard content
[Handover:Macro] Completion macro executed successfully
```

---

### Phase 11: Cody AI Integration

**Prerequisite:** Cody Enterprise (Sourcegraph) connected.

| Step | Action | Expected Result |
|---|---|---|
| 11.1 | Check Cody agent starts | Logs show Cody agent initialization |
| 11.2 | Open a file with Sonar issues | "Workflow: Fix with Cody" in gutter |
| 11.3 | Click gutter action | Cody generates fix, diff preview shown |
| 11.4 | Open file with uncovered method | "Workflow: Cover with Cody" in gutter |
| 11.5 | Go to commit dialog (Ctrl+K) | "Generate with Cody" option for commit message |

**Logs to check:**
```bash
grep "\[Cody:" idea.log
```
Expected:
```
[Cody:Agent] Starting Cody agent process
[Cody:Agent] Agent initialized successfully (v1.x.x)
[Cody:Chat] Creating new chat session
[Cody:Chat] Submitting message to chat (prompt: 234 chars)
```

---

### Phase 12: Health Check Gate (Pre-Commit)

**Prerequisite:** Health check enabled in Settings > Workflow Orchestrator > Health Check.

| Step | Action | Expected Result |
|---|---|---|
| 12.1 | Enable health checks in settings | Toggle on, select checks (Maven compile, test, copyright) |
| 12.2 | Make a code change and commit (Ctrl+K) | Health check runs before commit |
| 12.3 | If checks pass | Commit proceeds normally |
| 12.4 | If checks fail (hard mode) | Commit blocked, error shown |
| 12.5 | If checks fail (soft mode) | Warning shown, commit allowed |

**Logs to check:**
```bash
grep "healthcheck\|HealthCheck" idea.log
```

---

## Troubleshooting Common Issues

### Plugin doesn't load
```bash
grep -i "error.*workflow\|workflow.*error\|PluginException" idea.log
```
Common causes:
- IntelliJ version too old (needs 2025.1+)
- Missing Java 21+
- Conflicting plugin

### "Test Connection" hangs
```bash
grep "\[Core:HTTP\]\|\[Core:Auth\]" idea.log
```
Common causes:
- Firewall blocking
- Self-signed SSL certificate (add to JDK truststore)
- Wrong URL format (include `https://`)

### No coverage markers appear
```bash
grep "\[Sonar:" idea.log
```
Common causes:
- Project key not detected (check `[Sonar:ProjectKey]`)
- No coverage data in SonarQube
- File paths don't match (SonarQube vs local)

### Jira transition fails
```bash
grep "\[Jira:Workflow\]\|\[Jira:Executor\]\|\[Jira:API\]" idea.log
```
Common causes:
- Transition requires fields that aren't configured
- Workflow doesn't have expected transition names
- Permission issue (check Jira project permissions)

### Cody agent won't start
```bash
grep "\[Cody:Agent\]" idea.log
```
Common causes:
- Node.js not installed or not in PATH
- Sourcegraph token invalid
- Network can't reach Sourcegraph instance

### Build polling not working
```bash
grep "\[Bamboo:Monitor\]\|\[Bamboo:API\]" idea.log
```
Common causes:
- Plan key not detected (check `[Bamboo:Plan]`)
- API returning 403 (token lacks read permissions)

---

## Quick Log Commands Reference

```bash
# All plugin logs (live)
tail -f idea.log | grep "workflow.orchestrator"

# All plugin logs with prefix filtering (live)
tail -f idea.log | grep -E "\[(Core|Jira|Bamboo|Sonar|Automation|Handover|Cody):"

# Only errors and warnings
grep -E "WARN|ERROR" idea.log | grep "workflow.orchestrator"

# Specific module only
grep "\[Jira:" idea.log
grep "\[Sonar:" idea.log
grep "\[Bamboo:" idea.log

# Connection issues
grep "\[Core:Auth\]" idea.log

# Workflow/transition debugging
grep -E "\[Jira:Workflow\]|\[Jira:Mapping\]|\[Jira:Executor\]" idea.log

# Full startup sequence
grep -E "\[Core:Onboarding\]|\[Core:Connectivity\]|workflow.orchestrator.*loaded" idea.log
```

---

## Feature Availability Matrix

Not all features require all services. Here's what works with what:

| Feature | Jira | Bamboo | SonarQube | Bitbucket | Cody | Nexus |
|---|---|---|---|---|---|---|
| Sprint Dashboard | ✅ Required | - | - | - | - | - |
| Start Work (branch + transition) | ✅ Required | - | - | - | - | - |
| Build Dashboard | - | ✅ Required | - | - | - | - |
| Quality Dashboard | - | - | ✅ Required | - | - | - |
| Coverage Markers | - | - | ✅ Required | - | - | - |
| CVE Detection | - | ✅ Required | - | - | - | - |
| Automation Suite | - | ✅ Required | - | - | - | ✅ Optional |
| PR Creation | - | - | - | ✅ Required | ⬜ Optional | - |
| Jira Closure | ✅ Required | - | - | - | - | - |
| Pre-Review | - | - | - | - | ✅ Required | - |
| Fix with Cody | - | - | ✅ Required | - | ✅ Required | - |
| Commit Messages | - | - | - | - | ✅ Required | - |
| Health Check | - | ⬜ Optional | ⬜ Optional | - | - | - |
| Settings & Onboarding | - | - | - | - | - | - |

✅ Required = must be connected for feature to work
⬜ Optional = enhances feature but not required
`-` = not needed

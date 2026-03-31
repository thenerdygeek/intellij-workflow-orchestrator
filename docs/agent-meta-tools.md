# Agent Meta-Tools Reference

> **Version:** 0.41.0 | **Phase 1 Tool Consolidation**
>
> 138 individual tools consolidated into 9 meta-tools. Each meta-tool uses an `action` parameter to select the operation.

## Overview

| Meta-Tool | Actions | Domain | Package |
|-----------|---------|--------|---------|
| `jira` | 15 | Jira ticket management | `tools/integration` |
| `bamboo` | 18 | Bamboo CI/CD | `tools/integration` |
| `sonar` | 9 | SonarQube code quality | `tools/integration` |
| `bitbucket` | 26 | Bitbucket PRs and repos | `tools/integration` |
| `debug` | 24 | Interactive debugging | `tools/debug` |
| `git` | 11 | Git version control | `tools/vcs` |
| `spring` | 15 | Spring Framework intelligence | `tools/framework` |
| `build` | 11 | Maven/Gradle build systems | `tools/framework` |
| `runtime` | 9 | Run configs, tests, compilation | `tools/runtime` |

## Token Budget Impact

| Metric | Before | After |
|--------|--------|-------|
| Tool definitions | 183 tools x ~300 tokens | 57 tools (9 meta + 48 individual) |
| Tokens per API call | ~54,900 (25% of 190K budget) | ~17,000 (9% of budget) |
| Savings | -- | ~37,900 tokens freed per call |

---

## 1. `jira` -- Jira Integration

**Description:** Jira integration -- tickets, sprints, boards, transitions, comments, worklogs, dev branches.

**Allowed Workers:** TOOLER, ORCHESTRATOR

### Actions

#### `get_ticket`
**Purpose:** Get full details of a Jira ticket including status, priority, assignee, sprint, and description.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"get_ticket"` |
| `key` | string | Yes | Jira issue key (e.g. `PROJ-123`) |

**Example Input:**
```json
{
  "action": "get_ticket",
  "key": "PAY-456"
}
```

**Example Output:**
```
PAY-456: Fix null pointer in PaymentService
Status: In Progress | Priority: High | Assignee: John Doe
Sprint: Sprint 42 (active, ends 2026-04-05)
Type: Bug | Labels: backend, payment
Description:
  NPE thrown when processing refund with null customer reference.
  Stack trace shows PaymentService.processRefund() line 142.
```

---

#### `get_transitions`
**Purpose:** List available status transitions for a ticket from its current state.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"get_transitions"` |
| `key` | string | Yes | Jira issue key (e.g. `PROJ-123`) |

**Example Input:**
```json
{
  "action": "get_transitions",
  "key": "PAY-456"
}
```

**Example Output:**
```
Available transitions for PAY-456 (current: In Progress):
  ID 21: Code Review -> Code Review
  ID 31: Done -> Done
  ID 41: Blocked -> Blocked
```

---

#### `transition`
**Purpose:** Move a ticket to a new status using a transition ID. Use `get_transitions` first to find valid IDs.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"transition"` |
| `key` | string | Yes | Jira issue key |
| `transition_id` | string | Yes | Transition ID (use `get_transitions` first) |
| `comment` | string | No | Optional comment to add with the transition |
| `description` | string | No | Brief description shown in approval dialog |

**Example Input:**
```json
{
  "action": "transition",
  "key": "PAY-456",
  "transition_id": "21",
  "comment": "Code complete, ready for review"
}
```

**Example Output:**
```
Transitioned PAY-456 from 'In Progress'. Transition applied: Code Review.
```

---

#### `comment`
**Purpose:** Add a comment to a Jira ticket.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"comment"` |
| `key` | string | Yes | Jira issue key |
| `body` | string | Yes | Comment body text |
| `description` | string | No | Brief description shown in approval dialog |

**Example Input:**
```json
{
  "action": "comment",
  "key": "PAY-456",
  "body": "Root cause identified: null check missing in PaymentService.processRefund(). Fix committed on feature/PAY-456."
}
```

**Example Output:**
```
Comment added to PAY-456 by agent-user.
```

---

#### `get_comments`
**Purpose:** Retrieve all comments on a Jira ticket.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"get_comments"` |
| `key` | string | Yes | Jira issue key |

**Example Input:**
```json
{
  "action": "get_comments",
  "key": "PAY-456"
}
```

**Example Output:**
```
Comments on PAY-456 (3 total):

[1] John Doe (2026-03-28 14:30):
  Seeing NPE in production logs for refund flow.

[2] Jane Smith (2026-03-29 09:15):
  Reproduced locally. Happens when customer_ref is null.

[3] Agent (2026-03-30 10:00):
  Root cause identified: null check missing in PaymentService.processRefund().
```

---

#### `log_work`
**Purpose:** Log time spent on a Jira ticket.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"log_work"` |
| `key` | string | Yes | Jira issue key |
| `time_spent` | string | Yes | Time in Jira format: `'2h'`, `'30m'`, `'1h 30m'` |
| `comment` | string | No | Optional worklog comment |

**Example Input:**
```json
{
  "action": "log_work",
  "key": "PAY-456",
  "time_spent": "2h 30m",
  "comment": "Investigated root cause and implemented fix"
}
```

**Example Output:**
```
Logged 2h 30m on PAY-456. Total logged: 5h 30m.
```

---

#### `get_worklogs`
**Purpose:** Retrieve all work log entries for a ticket.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"get_worklogs"` |
| `key` or `issue_key` | string | Yes | Jira issue key |

**Example Input:**
```json
{
  "action": "get_worklogs",
  "issue_key": "PAY-456"
}
```

**Example Output:**
```
Worklogs for PAY-456 (3 entries, total: 5h 30m):

[1] John Doe — 1h (2026-03-28): Initial investigation
[2] John Doe — 2h (2026-03-29): Debugging and root cause analysis
[3] Agent — 2h 30m (2026-03-30): Implemented fix and tests
```

---

#### `get_sprints`
**Purpose:** List sprints for a given Jira board.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"get_sprints"` |
| `board_id` | string | Yes | Board ID (integer as string) |

**Example Input:**
```json
{
  "action": "get_sprints",
  "board_id": "42"
}
```

**Example Output:**
```
Sprints for board 42 (3 found):

Sprint 41: Closed (2026-03-01 to 2026-03-15)
Sprint 42: Active (2026-03-16 to 2026-03-30)
Sprint 43: Future (2026-03-31 to 2026-04-14)
```

---

#### `get_linked_prs`
**Purpose:** Get pull requests linked to a Jira issue via dev status.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"get_linked_prs"` |
| `issue_id` | string | Yes | Jira issue ID or key |

**Example Input:**
```json
{
  "action": "get_linked_prs",
  "issue_id": "PAY-456"
}
```

**Example Output:**
```
Linked PRs for PAY-456 (1 found):

PR #89: Fix null pointer in refund flow
  Repo: payment-service | Branch: feature/PAY-456 -> master
  Status: OPEN | Reviewers: Jane Smith (APPROVED), Bob Lee (UNAPPROVED)
```

---

#### `get_boards`
**Purpose:** List Jira boards, optionally filtered by type or name.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"get_boards"` |
| `type` | string | No | Board type filter: `'scrum'` or `'kanban'` |
| `name_filter` | string | No | Name filter string |

**Example Input:**
```json
{
  "action": "get_boards",
  "type": "scrum"
}
```

**Example Output:**
```
Scrum boards (3 found):

[42] Payment Team Board (scrum)
[55] Platform Team (scrum)
[67] Mobile Squad (scrum)
```

---

#### `get_sprint_issues`
**Purpose:** Get all issues in a specific sprint.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"get_sprint_issues"` |
| `sprint_id` | string | Yes | Sprint ID (integer as string) |

**Example Input:**
```json
{
  "action": "get_sprint_issues",
  "sprint_id": "142"
}
```

**Example Output:**
```
Sprint 42 issues (8 total):

Done (3):
  PAY-450: Add retry logic to payment gateway [Story, 5pts]
  PAY-451: Update Stripe SDK to v12 [Task, 2pts]
  PAY-453: Fix currency rounding [Bug, 3pts]

In Progress (2):
  PAY-456: Fix null pointer in PaymentService [Bug, 3pts]
  PAY-458: Add webhook for refund status [Story, 5pts]

To Do (3):
  PAY-460: Performance test refund endpoint [Task, 2pts]
  PAY-461: Document refund API changes [Task, 1pt]
  PAY-462: Add metrics for refund latency [Story, 3pts]
```

---

#### `get_board_issues`
**Purpose:** Get all issues on a board (across sprints).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"get_board_issues"` |
| `board_id` | string | Yes | Board ID (integer as string) |

**Example Input:**
```json
{
  "action": "get_board_issues",
  "board_id": "42"
}
```

**Example Output:**
```
Board 42 issues (12 total):
  PAY-456: Fix null pointer in PaymentService [In Progress, Bug]
  PAY-458: Add webhook for refund status [In Progress, Story]
  PAY-460: Performance test refund endpoint [To Do, Task]
  ...
```

---

#### `search_issues`
**Purpose:** Full-text search across Jira issues.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"search_issues"` |
| `text` | string | Yes | Search text |
| `max_results` | string | No | Max results (default 20) |

**Example Input:**
```json
{
  "action": "search_issues",
  "text": "null pointer payment",
  "max_results": "5"
}
```

**Example Output:**
```
Search results for "null pointer payment" (5 of 12):

PAY-456: Fix null pointer in PaymentService [In Progress, Bug, High]
PAY-412: NPE in PaymentGateway.charge() [Done, Bug, Critical]
PAY-389: Null safety review for payment module [Done, Task, Medium]
PAY-301: Handle null merchant ID in PaymentRouter [Done, Bug, High]
PAY-278: Add null checks to PaymentDTO [Done, Task, Low]
```

---

#### `get_dev_branches`
**Purpose:** Get development branches linked to a Jira issue via dev status.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"get_dev_branches"` |
| `issue_id` | string | Yes | Jira issue ID or key |

**Example Input:**
```json
{
  "action": "get_dev_branches",
  "issue_id": "PAY-456"
}
```

**Example Output:**
```
Dev branches for PAY-456 (2 found):

[1] feature/PAY-456 (payment-service)
    Last commit: abc1234 — "Add null check to processRefund" (2h ago)
[2] feature/PAY-456-tests (payment-service)
    Last commit: def5678 — "Add refund null pointer test" (1h ago)
```

---

#### `start_work`
**Purpose:** Start working on a ticket: creates a branch and optionally transitions the ticket.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"start_work"` |
| `issue_key` or `key` | string | Yes | Jira issue key |
| `branch_name` | string | Yes | Branch name to create |
| `source_branch` | string | Yes | Source branch to branch from |
| `description` | string | No | Brief description shown in approval dialog |

**Example Input:**
```json
{
  "action": "start_work",
  "issue_key": "PAY-470",
  "branch_name": "feature/PAY-470-add-refund-limits",
  "source_branch": "master"
}
```

**Example Output:**
```
Started work on PAY-470:
  Branch: feature/PAY-470-add-refund-limits (from master)
  Ticket transitioned to: In Progress
```

---

## 2. `bamboo` -- CI/CD Bamboo Integration

**Description:** Bamboo CI/CD integration -- build status, logs, test results, artifacts, plans, branches, variables.

**Allowed Workers:** TOOLER, ORCHESTRATOR

### Actions

#### `build_status`
**Purpose:** Get the latest build status for a plan.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"build_status"` |
| `plan_key` | string | Yes | Bamboo plan key (e.g. `PROJ-PLAN`) |
| `repo_name` | string | No | Repository name for multi-repo projects |

**Example Input:**
```json
{
  "action": "build_status",
  "plan_key": "PAY-BUILD"
}
```

**Example Output:**
```
PAY-BUILD latest build: #247
Status: SUCCESSFUL
Duration: 4m 32s | Completed: 2026-03-30 09:15
Branch: master | Commit: abc1234
Tests: 342 passed, 0 failed
```

---

#### `get_build`
**Purpose:** Get detailed information about a specific build.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"get_build"` |
| `build_key` | string | Yes | Build result key (e.g. `PROJ-PLAN-123`) |

**Example Input:**
```json
{
  "action": "get_build",
  "build_key": "PAY-BUILD-247"
}
```

**Example Output:**
```
Build PAY-BUILD-247:
Status: SUCCESSFUL | Duration: 4m 32s
Plan: Payment Service Build | Branch: master
Trigger: Manual by john.doe | Started: 2026-03-30 09:10

Stages:
  Compile: SUCCESSFUL (45s)
  Unit Tests: SUCCESSFUL (2m 10s)
  Integration Tests: SUCCESSFUL (1m 37s)

Tests: 342 passed, 0 failed, 0 skipped
Artifacts: 2 (payment-service-1.5.0.jar, test-report.html)
```

---

#### `trigger_build`
**Purpose:** Trigger a new build for a plan, optionally with custom variables.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"trigger_build"` |
| `plan_key` | string | Yes | Bamboo plan key |
| `variables` | string | No | JSON object of build variables: `'{"key":"value"}'` |
| `description` | string | No | Brief description shown in approval dialog |

**Example Input:**
```json
{
  "action": "trigger_build",
  "plan_key": "PAY-BUILD",
  "variables": "{\"DEPLOY_ENV\":\"staging\",\"SKIP_E2E\":\"false\"}"
}
```

**Example Output:**
```
Build triggered: PAY-BUILD-248
Status: QUEUED
Variables: DEPLOY_ENV=staging, SKIP_E2E=false
```

---

#### `get_build_log`
**Purpose:** Retrieve the build log for a specific build.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"get_build_log"` |
| `build_key` | string | Yes | Build result key (e.g. `PROJ-PLAN-123`) |

**Example Input:**
```json
{
  "action": "get_build_log",
  "build_key": "PAY-BUILD-245"
}
```

**Example Output:**
```
Build log for PAY-BUILD-245 (FAILED):

[09:10:05] Starting build...
[09:10:12] Resolving dependencies...
[09:12:30] Compiling sources...
[09:13:15] Running tests...
[09:14:42] FAILURE: PaymentServiceTest.testProcessRefund
  java.lang.NullPointerException: customer reference is null
    at com.example.PaymentService.processRefund(PaymentService.java:142)
    at com.example.PaymentServiceTest.testProcessRefund(PaymentServiceTest.java:89)
[09:14:43] Tests: 341 passed, 1 failed
[09:14:44] BUILD FAILED
```

---

#### `get_test_results`
**Purpose:** Get test results for a specific build.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"get_test_results"` |
| `build_key` | string | Yes | Build result key |

**Example Input:**
```json
{
  "action": "get_test_results",
  "build_key": "PAY-BUILD-245"
}
```

**Example Output:**
```
Test results for PAY-BUILD-245:

Total: 342 | Passed: 341 | Failed: 1 | Skipped: 0

FAILED (1):
  PaymentServiceTest.testProcessRefund
    java.lang.NullPointerException: customer reference is null
    Duration: 0.3s | First failure in build 245
```

---

#### `stop_build`
**Purpose:** Stop a running build (graceful stop).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"stop_build"` |
| `result_key` | string | Yes | Build result key (e.g. `PROJ-PLAN-123`) |
| `description` | string | No | Brief description shown in approval dialog |

**Example Input:**
```json
{
  "action": "stop_build",
  "result_key": "PAY-BUILD-248"
}
```

**Example Output:**
```
Build PAY-BUILD-248 stop requested. Current status: STOPPING.
```

---

#### `cancel_build`
**Purpose:** Cancel a queued or running build.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"cancel_build"` |
| `result_key` | string | Yes | Build result key |
| `description` | string | No | Brief description shown in approval dialog |

**Example Input:**
```json
{
  "action": "cancel_build",
  "result_key": "PAY-BUILD-248"
}
```

**Example Output:**
```
Build PAY-BUILD-248 cancelled.
```

---

#### `get_artifacts`
**Purpose:** List artifacts produced by a build.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"get_artifacts"` |
| `result_key` | string | Yes | Build result key |

**Example Input:**
```json
{
  "action": "get_artifacts",
  "result_key": "PAY-BUILD-247"
}
```

**Example Output:**
```
Artifacts for PAY-BUILD-247 (2 found):

[1] payment-service-1.5.0.jar (12.4 MB)
    Location: shared/payment-service-1.5.0.jar
[2] test-report.html (245 KB)
    Location: shared/test-report.html
```

---

#### `recent_builds`
**Purpose:** Get recent builds for a plan.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"recent_builds"` |
| `plan_key` | string | Yes | Bamboo plan key |
| `max_results` | string | No | Max results to return (default 10) |
| `repo_name` | string | No | Repository name for multi-repo projects |

**Example Input:**
```json
{
  "action": "recent_builds",
  "plan_key": "PAY-BUILD",
  "max_results": "5"
}
```

**Example Output:**
```
Recent builds for PAY-BUILD (5 shown):

#247: SUCCESSFUL  (4m 32s) master  2026-03-30 09:15
#246: SUCCESSFUL  (4m 28s) master  2026-03-29 16:00
#245: FAILED      (4m 44s) master  2026-03-29 14:30
#244: SUCCESSFUL  (4m 15s) master  2026-03-28 11:20
#243: SUCCESSFUL  (4m 22s) master  2026-03-27 17:45
```

---

#### `get_plans`
**Purpose:** List all Bamboo plans accessible to the user.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"get_plans"` |

**Example Input:**
```json
{
  "action": "get_plans"
}
```

**Example Output:**
```
Bamboo plans (4 found):

PAY-BUILD: Payment Service Build (enabled)
PAY-DEPLOY: Payment Service Deploy (enabled)
PLAT-BUILD: Platform Core Build (enabled)
PLAT-INTEG: Platform Integration Tests (disabled)
```

---

#### `get_project_plans`
**Purpose:** List plans within a specific Bamboo project.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"get_project_plans"` |
| `project_key` | string | Yes | Bamboo project key (e.g. `PROJ`) |

**Example Input:**
```json
{
  "action": "get_project_plans",
  "project_key": "PAY"
}
```

**Example Output:**
```
Plans in project PAY (2 found):

PAY-BUILD: Payment Service Build (enabled, last: #247 SUCCESSFUL)
PAY-DEPLOY: Payment Service Deploy (enabled, last: #102 SUCCESSFUL)
```

---

#### `search_plans`
**Purpose:** Search Bamboo plans by name or key.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"search_plans"` |
| `query` | string | Yes | Search query |

**Example Input:**
```json
{
  "action": "search_plans",
  "query": "payment"
}
```

**Example Output:**
```
Plans matching "payment" (2 found):

PAY-BUILD: Payment Service Build
PAY-DEPLOY: Payment Service Deploy
```

---

#### `get_plan_branches`
**Purpose:** List branch plans for a Bamboo plan.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"get_plan_branches"` |
| `plan_key` | string | Yes | Bamboo plan key |
| `repo_name` | string | No | Repository name for multi-repo projects |

**Example Input:**
```json
{
  "action": "get_plan_branches",
  "plan_key": "PAY-BUILD"
}
```

**Example Output:**
```
Branch plans for PAY-BUILD (3 found):

PAY-BUILD0: master (default)
PAY-BUILD2: feature/PAY-456 (last: #3 FAILED)
PAY-BUILD5: feature/PAY-458 (last: #1 SUCCESSFUL)
```

---

#### `get_running_builds`
**Purpose:** List currently running builds for a plan.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"get_running_builds"` |
| `plan_key` | string | Yes | Bamboo plan key |
| `repo_name` | string | No | Repository name for multi-repo projects |

**Example Input:**
```json
{
  "action": "get_running_builds",
  "plan_key": "PAY-BUILD"
}
```

**Example Output:**
```
Running builds for PAY-BUILD (1 found):

PAY-BUILD-248: Building (stage: Unit Tests, 2m 15s elapsed)
  Triggered by: john.doe | Branch: feature/PAY-470
```

---

#### `get_build_variables`
**Purpose:** Get variables used in a specific build.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"get_build_variables"` |
| `result_key` | string | Yes | Build result key |

**Example Input:**
```json
{
  "action": "get_build_variables",
  "result_key": "PAY-BUILD-248"
}
```

**Example Output:**
```
Build variables for PAY-BUILD-248:

bamboo.DEPLOY_ENV = staging
bamboo.SKIP_E2E = false
bamboo.planKey = PAY-BUILD
bamboo.buildNumber = 248
```

---

#### `get_plan_variables`
**Purpose:** Get default variables configured on a plan.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"get_plan_variables"` |
| `plan_key` | string | Yes | Bamboo plan key |

**Example Input:**
```json
{
  "action": "get_plan_variables",
  "plan_key": "PAY-BUILD"
}
```

**Example Output:**
```
Plan variables for PAY-BUILD (3 found):

DEPLOY_ENV = production (default)
SKIP_E2E = true (default)
JAVA_VERSION = 21 (default)
```

---

#### `rerun_failed_jobs`
**Purpose:** Re-run only the failed jobs from a specific build.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"rerun_failed_jobs"` |
| `plan_key` | string | Yes | Bamboo plan key |
| `build_number` | string | Yes | Build number (integer as string) |
| `description` | string | No | Brief description shown in approval dialog |

**Example Input:**
```json
{
  "action": "rerun_failed_jobs",
  "plan_key": "PAY-BUILD",
  "build_number": "245"
}
```

**Example Output:**
```
Re-running failed jobs for PAY-BUILD-245.
Failed stages being re-run: Unit Tests
New build key: PAY-BUILD-249
```

---

#### `trigger_stage`
**Purpose:** Trigger a specific stage of a plan, optionally with variables.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"trigger_stage"` |
| `plan_key` | string | Yes | Bamboo plan key |
| `stage` | string | No | Stage name to trigger |
| `variables` | string | No | JSON object of build variables: `'{"key":"value"}'` |
| `description` | string | No | Brief description shown in approval dialog |

**Example Input:**
```json
{
  "action": "trigger_stage",
  "plan_key": "PAY-DEPLOY",
  "stage": "Deploy to Staging",
  "variables": "{\"DEPLOY_ENV\":\"staging\"}"
}
```

**Example Output:**
```
Stage 'Deploy to Staging' triggered for PAY-DEPLOY.
Build key: PAY-DEPLOY-103
Variables: DEPLOY_ENV=staging
```

---

## 3. `sonar` -- SonarQube Code Quality

**Description:** SonarQube code quality integration -- issues, quality gate, coverage, measures, branches, source lines.

**Allowed Workers:** TOOLER, ANALYZER, ORCHESTRATOR

### Actions

#### `issues`
**Purpose:** Get SonarQube issues for a project, optionally filtered by file.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"issues"` |
| `project_key` | string | Yes | SonarQube project key (e.g. `com.example:my-service`) |
| `file` | string | No | Relative file path filter |
| `repo_name` | string | No | Repository name for multi-repo projects |

**Example Input:**
```json
{
  "action": "issues",
  "project_key": "com.example:payment-service",
  "file": "src/main/java/com/example/PaymentService.java"
}
```

**Example Output:**
```
SonarQube issues for com.example:payment-service (filtered: PaymentService.java):

3 issues found:

[BUG] CRITICAL: Possible null pointer dereference (line 142)
  Rule: java:S2259 | Effort: 15min
  PaymentService.java:142 — customerRef may be null here

[CODE_SMELL] MAJOR: Method has 8 parameters (max 7) (line 55)
  Rule: java:S107 | Effort: 30min

[CODE_SMELL] MINOR: Remove unused import (line 3)
  Rule: java:S1128 | Effort: 2min
```

---

#### `quality_gate`
**Purpose:** Get the quality gate status for a project.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"quality_gate"` |
| `project_key` | string | Yes | SonarQube project key |
| `repo_name` | string | No | Repository name for multi-repo projects |

**Example Input:**
```json
{
  "action": "quality_gate",
  "project_key": "com.example:payment-service"
}
```

**Example Output:**
```
Quality Gate: PASSED

Conditions:
  Coverage: 82.3% (threshold: >= 80%) -- PASSED
  Duplicated Lines: 2.1% (threshold: <= 3%) -- PASSED
  Maintainability Rating: A (threshold: >= A) -- PASSED
  Reliability Rating: A (threshold: >= A) -- PASSED
  New Bugs: 0 (threshold: <= 0) -- PASSED
```

---

#### `coverage`
**Purpose:** Get code coverage metrics for a project.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"coverage"` |
| `project_key` | string | Yes | SonarQube project key |
| `repo_name` | string | No | Repository name for multi-repo projects |

**Example Input:**
```json
{
  "action": "coverage",
  "project_key": "com.example:payment-service"
}
```

**Example Output:**
```
Coverage for com.example:payment-service:

Overall: 82.3% (1,245 / 1,513 lines)
Line coverage: 82.3%
Branch coverage: 76.1%
New code coverage: 91.0%
```

---

#### `search_projects`
**Purpose:** Search for SonarQube projects by name or key.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"search_projects"` |
| `query` | string | Yes | Search query |

**Example Input:**
```json
{
  "action": "search_projects",
  "query": "payment"
}
```

**Example Output:**
```
SonarQube projects matching "payment" (2 found):

[1] com.example:payment-service — Payment Service
    Last analysis: 2026-03-30 09:20 | Quality Gate: PASSED
[2] com.example:payment-gateway — Payment Gateway
    Last analysis: 2026-03-29 18:30 | Quality Gate: FAILED
```

---

#### `analysis_tasks`
**Purpose:** Get recent analysis task status for a project.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"analysis_tasks"` |
| `project_key` | string | Yes | SonarQube project key |
| `repo_name` | string | No | Repository name for multi-repo projects |

**Example Input:**
```json
{
  "action": "analysis_tasks",
  "project_key": "com.example:payment-service"
}
```

**Example Output:**
```
Recent analysis tasks for com.example:payment-service:

[1] Task ce-abc123 — SUCCESS (2026-03-30 09:20, 45s)
[2] Task ce-def456 — SUCCESS (2026-03-29 18:00, 38s)
[3] Task ce-ghi789 — FAILED (2026-03-28 14:30, 12s)
    Error: Unable to read report. File not found.
```

---

#### `branches`
**Purpose:** List analyzed branches for a SonarQube project.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"branches"` |
| `project_key` | string | Yes | SonarQube project key |
| `repo_name` | string | No | Repository name for multi-repo projects |

**Example Input:**
```json
{
  "action": "branches",
  "project_key": "com.example:payment-service"
}
```

**Example Output:**
```
Branches for com.example:payment-service (3 found):

master (main branch) — Quality Gate: PASSED
  Last analysis: 2026-03-30 09:20

feature/PAY-456 — Quality Gate: FAILED
  Last analysis: 2026-03-29 14:30

feature/PAY-458 — Quality Gate: PASSED
  Last analysis: 2026-03-28 11:15
```

---

#### `project_measures`
**Purpose:** Get comprehensive project measures (metrics).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"project_measures"` |
| `project_key` | string | Yes | SonarQube project key |
| `branch` | string | No | Branch name (default: main branch) |
| `repo_name` | string | No | Repository name for multi-repo projects |

**Example Input:**
```json
{
  "action": "project_measures",
  "project_key": "com.example:payment-service",
  "branch": "feature/PAY-456"
}
```

**Example Output:**
```
Measures for com.example:payment-service (branch: feature/PAY-456):

Lines of Code: 15,230
Coverage: 79.1%
Duplicated Lines: 2.8%
Code Smells: 42 | Bugs: 3 | Vulnerabilities: 0
Technical Debt: 4d 2h
Complexity: 1,234 | Cognitive Complexity: 856
Reliability Rating: B | Security Rating: A | Maintainability Rating: A
```

---

#### `source_lines`
**Purpose:** Get source code with SonarQube annotations (coverage, issues) for a specific component.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"source_lines"` |
| `component_key` | string | Yes | SonarQube component key (e.g. `com.example:my-service:src/main/java/MyClass.java`) |
| `from` | string | No | Start line number |
| `to` | string | No | End line number |
| `repo_name` | string | No | Repository name for multi-repo projects |

**Example Input:**
```json
{
  "action": "source_lines",
  "component_key": "com.example:payment-service:src/main/java/com/example/PaymentService.java",
  "from": "140",
  "to": "150"
}
```

**Example Output:**
```
Source lines 140-150 of PaymentService.java:

140 |     public RefundResult processRefund(String orderId) {
141 |         Order order = orderRepository.findById(orderId);
142 | BUG|    String customerRef = order.getCustomerReference();  // <-- NPE possible
143 |         if (customerRef.isEmpty()) {  // <-- NPE if customerRef is null
144 |             throw new InvalidRefundException("No customer reference");
145 |         }
146 |         return refundGateway.process(customerRef, order.getAmount());
147 |     }
148 |
149 |     public List<Refund> getRefundHistory(String customerId) {
150 |         return refundRepository.findByCustomerId(customerId);

Coverage: lines 140-147 covered (8/8), line 142 has issue
```

---

#### `issues_paged`
**Purpose:** Get paginated SonarQube issues (for large result sets).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"issues_paged"` |
| `project_key` | string | Yes | SonarQube project key |
| `page` | string | No | Page number (default 1) |
| `page_size` | string | No | Results per page, max 500 (default 100) |
| `repo_name` | string | No | Repository name for multi-repo projects |

**Example Input:**
```json
{
  "action": "issues_paged",
  "project_key": "com.example:payment-service",
  "page": "1",
  "page_size": "10"
}
```

**Example Output:**
```
SonarQube issues for com.example:payment-service (page 1 of 5, 10/42 total):

[1] BUG CRITICAL: Possible null pointer dereference (PaymentService.java:142)
[2] CODE_SMELL MAJOR: Method has 8 parameters (PaymentService.java:55)
[3] CODE_SMELL MINOR: Remove unused import (PaymentService.java:3)
[4] BUG MAJOR: Resource leak — stream not closed (RefundGateway.java:78)
...
```

---

## 4. `bitbucket` -- Bitbucket Pull Request & Repository Management

**Description:** Bitbucket pull request and repository integration -- PRs, reviews, branches, files, build statuses.

**Allowed Workers:** TOOLER, ORCHESTRATOR, CODER, REVIEWER, ANALYZER

### Actions

#### `create_pr`
**Purpose:** Create a new pull request.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"create_pr"` |
| `title` | string | Yes | PR title |
| `pr_description` | string | Yes | PR body/description text |
| `from_branch` | string | Yes | Source branch |
| `to_branch` | string | No | Target branch (default: `master`) |
| `repo_name` | string | No | Repository name for multi-repo projects |
| `description` | string | No | Approval dialog description |

**Example Input:**
```json
{
  "action": "create_pr",
  "title": "PAY-456: Fix null pointer in refund flow",
  "pr_description": "## Summary\n- Add null check for customerRef in processRefund()\n- Add unit test for null customer reference scenario\n\n## Testing\n- All existing tests pass\n- New test: PaymentServiceTest.testProcessRefundNullCustomerRef",
  "from_branch": "feature/PAY-456",
  "to_branch": "master"
}
```

**Example Output:**
```
Pull request created: PR #90
Title: PAY-456: Fix null pointer in refund flow
Branch: feature/PAY-456 -> master
URL: https://bitbucket.example.com/projects/PAY/repos/payment-service/pull-requests/90
```

---

#### `get_pr_detail`
**Purpose:** Get full details of a pull request.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"get_pr_detail"` |
| `pr_id` | string | Yes | Pull request ID (numeric) |
| `repo_name` | string | No | Repository name for multi-repo projects |

**Example Input:**
```json
{
  "action": "get_pr_detail",
  "pr_id": "90"
}
```

**Example Output:**
```
PR #90: PAY-456: Fix null pointer in refund flow
Status: OPEN | Author: john.doe | Created: 2026-03-30 10:15

Branch: feature/PAY-456 -> master
Reviewers:
  jane.smith: APPROVED
  bob.lee: UNAPPROVED

Description:
  Add null check for customerRef in processRefund()...
```

---

#### `get_pr_commits`
**Purpose:** List commits in a pull request.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"get_pr_commits"` |
| `pr_id` | string | Yes | Pull request ID |
| `repo_name` | string | No | Repository name for multi-repo projects |

**Example Input:**
```json
{
  "action": "get_pr_commits",
  "pr_id": "90"
}
```

**Example Output:**
```
Commits in PR #90 (2 total):

abc1234 — Add null check to processRefund (john.doe, 2h ago)
def5678 — Add test for null customer ref (john.doe, 1h ago)
```

---

#### `get_pr_changes`
**Purpose:** List changed files in a pull request.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"get_pr_changes"` |
| `pr_id` | string | Yes | Pull request ID |
| `repo_name` | string | No | Repository name for multi-repo projects |

**Example Input:**
```json
{
  "action": "get_pr_changes",
  "pr_id": "90"
}
```

**Example Output:**
```
Changed files in PR #90 (2 files):

M src/main/java/com/example/PaymentService.java (+3, -1)
A src/test/java/com/example/PaymentServiceNullRefTest.java (+45)
```

---

#### `get_pr_diff`
**Purpose:** Get the full diff of a pull request.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"get_pr_diff"` |
| `pr_id` | string | Yes | Pull request ID |
| `repo_name` | string | No | Repository name for multi-repo projects |

**Example Input:**
```json
{
  "action": "get_pr_diff",
  "pr_id": "90"
}
```

**Example Output:**
```
diff --git a/src/main/java/com/example/PaymentService.java b/src/main/java/com/example/PaymentService.java
--- a/src/main/java/com/example/PaymentService.java
+++ b/src/main/java/com/example/PaymentService.java
@@ -140,6 +140,8 @@
     public RefundResult processRefund(String orderId) {
         Order order = orderRepository.findById(orderId);
         String customerRef = order.getCustomerReference();
+        if (customerRef == null) {
+            throw new InvalidRefundException("Customer reference is null for order " + orderId);
+        }
         if (customerRef.isEmpty()) {
...
```

---

#### `get_pr_activities`
**Purpose:** Get the activity timeline of a pull request (comments, approvals, merges).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"get_pr_activities"` |
| `pr_id` | string | Yes | Pull request ID |
| `repo_name` | string | No | Repository name for multi-repo projects |

**Example Input:**
```json
{
  "action": "get_pr_activities",
  "pr_id": "90"
}
```

**Example Output:**
```
Activities for PR #90 (4 entries):

[2026-03-30 10:15] OPENED by john.doe
[2026-03-30 10:30] COMMENTED by jane.smith: "Looks good, but add a test for empty string too"
[2026-03-30 11:00] RESCOPED — 1 new commit pushed
[2026-03-30 11:15] APPROVED by jane.smith
```

---

#### `add_inline_comment`
**Purpose:** Add an inline comment to a specific line in a PR diff.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"add_inline_comment"` |
| `pr_id` | string | Yes | Pull request ID |
| `file_path` | string | Yes | File path |
| `line` | string | Yes | Line number (integer as string) |
| `line_type` | string | Yes | Line type: `ADDED`, `REMOVED`, `CONTEXT` |
| `text` | string | Yes | Comment text |
| `repo_name` | string | No | Repository name for multi-repo projects |

**Example Input:**
```json
{
  "action": "add_inline_comment",
  "pr_id": "90",
  "file_path": "src/main/java/com/example/PaymentService.java",
  "line": "142",
  "line_type": "ADDED",
  "text": "Consider logging the orderId before throwing to help with debugging"
}
```

**Example Output:**
```
Inline comment added to PR #90 on PaymentService.java:142 (ADDED).
```

---

#### `reply_to_comment`
**Purpose:** Reply to an existing PR comment thread.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"reply_to_comment"` |
| `pr_id` | string | Yes | Pull request ID |
| `parent_comment_id` | string | Yes | Parent comment ID (integer as string) |
| `text` | string | Yes | Reply text |
| `repo_name` | string | No | Repository name for multi-repo projects |

**Example Input:**
```json
{
  "action": "reply_to_comment",
  "pr_id": "90",
  "parent_comment_id": "4521",
  "text": "Good point, added logging in the latest commit."
}
```

**Example Output:**
```
Reply added to comment 4521 on PR #90.
```

---

#### `add_pr_comment`
**Purpose:** Add a general comment to a pull request.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"add_pr_comment"` |
| `pr_id` | string | Yes | Pull request ID |
| `text` | string | Yes | Comment text |
| `repo_name` | string | No | Repository name for multi-repo projects |
| `description` | string | No | Approval dialog description |

**Example Input:**
```json
{
  "action": "add_pr_comment",
  "pr_id": "90",
  "text": "All review comments addressed. Ready for re-review."
}
```

**Example Output:**
```
Comment added to PR #90.
```

---

#### `add_reviewer`
**Purpose:** Add a reviewer to a pull request.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"add_reviewer"` |
| `pr_id` | string | Yes | Pull request ID |
| `username` | string | Yes | Reviewer username |
| `repo_name` | string | No | Repository name for multi-repo projects |

**Example Input:**
```json
{
  "action": "add_reviewer",
  "pr_id": "90",
  "username": "bob.lee"
}
```

**Example Output:**
```
Reviewer bob.lee added to PR #90.
```

---

#### `remove_reviewer`
**Purpose:** Remove a reviewer from a pull request.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"remove_reviewer"` |
| `pr_id` | string | Yes | Pull request ID |
| `username` | string | Yes | Reviewer username |
| `repo_name` | string | No | Repository name for multi-repo projects |

**Example Input:**
```json
{
  "action": "remove_reviewer",
  "pr_id": "90",
  "username": "bob.lee"
}
```

**Example Output:**
```
Reviewer bob.lee removed from PR #90.
```

---

#### `set_reviewer_status`
**Purpose:** Set the review status for a reviewer on a PR.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"set_reviewer_status"` |
| `pr_id` | string | Yes | Pull request ID |
| `username` | string | Yes | Reviewer username |
| `status` | string | Yes | Status: `APPROVED`, `NEEDS_WORK`, `UNAPPROVED` |
| `repo_name` | string | No | Repository name for multi-repo projects |
| `description` | string | No | Approval dialog description |

**Example Input:**
```json
{
  "action": "set_reviewer_status",
  "pr_id": "90",
  "username": "jane.smith",
  "status": "APPROVED"
}
```

**Example Output:**
```
Reviewer jane.smith status set to APPROVED on PR #90.
```

---

#### `approve_pr`
**Purpose:** Approve a pull request (as the authenticated user).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"approve_pr"` |
| `pr_id` | string | Yes | Pull request ID |
| `repo_name` | string | No | Repository name for multi-repo projects |
| `description` | string | No | Approval dialog description |

**Example Input:**
```json
{
  "action": "approve_pr",
  "pr_id": "90"
}
```

**Example Output:**
```
PR #90 approved.
```

---

#### `merge_pr`
**Purpose:** Merge a pull request.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"merge_pr"` |
| `pr_id` | string | Yes | Pull request ID |
| `strategy` | string | No | Merge strategy: `merge-commit`, `squash`, `ff-only` |
| `delete_source_branch` | string | No | Delete source branch after merge: `true`/`false` |
| `commit_message` | string | No | Custom merge commit message |
| `repo_name` | string | No | Repository name for multi-repo projects |
| `description` | string | No | Approval dialog description |

**Example Input:**
```json
{
  "action": "merge_pr",
  "pr_id": "90",
  "strategy": "squash",
  "delete_source_branch": "true",
  "commit_message": "PAY-456: Fix null pointer in refund flow (#90)"
}
```

**Example Output:**
```
PR #90 merged (squash). Source branch feature/PAY-456 deleted.
Merge commit: ghi9012
```

---

#### `decline_pr`
**Purpose:** Decline (close without merging) a pull request.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"decline_pr"` |
| `pr_id` | string | Yes | Pull request ID |
| `repo_name` | string | No | Repository name for multi-repo projects |
| `description` | string | No | Approval dialog description |

**Example Input:**
```json
{
  "action": "decline_pr",
  "pr_id": "85"
}
```

**Example Output:**
```
PR #85 declined.
```

---

#### `check_merge_status`
**Purpose:** Check whether a PR can be merged (conflict detection, required reviews).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"check_merge_status"` |
| `pr_id` | string | Yes | Pull request ID |
| `repo_name` | string | No | Repository name for multi-repo projects |

**Example Input:**
```json
{
  "action": "check_merge_status",
  "pr_id": "90"
}
```

**Example Output:**
```
Merge status for PR #90:
  Can merge: YES
  Conflicts: NONE
  Required approvals: 1/1 met
  Build status: SUCCESSFUL
```

---

#### `update_pr_title`
**Purpose:** Update the title of a pull request.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"update_pr_title"` |
| `pr_id` | string | Yes | Pull request ID |
| `new_title` | string | Yes | New PR title |
| `repo_name` | string | No | Repository name for multi-repo projects |

**Example Input:**
```json
{
  "action": "update_pr_title",
  "pr_id": "90",
  "new_title": "PAY-456: Fix NPE in refund flow + add null safety tests"
}
```

**Example Output:**
```
PR #90 title updated to: "PAY-456: Fix NPE in refund flow + add null safety tests"
```

---

#### `update_pr_description`
**Purpose:** Update the description/body of a pull request.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"update_pr_description"` |
| `pr_id` | string | Yes | Pull request ID |
| `pr_description` | string | Yes | New description text |
| `repo_name` | string | No | Repository name for multi-repo projects |

**Example Input:**
```json
{
  "action": "update_pr_description",
  "pr_id": "90",
  "pr_description": "## Summary\n- Fix NPE in processRefund()\n- Add null safety tests\n\n## Root Cause\nMissing null check on customerRef"
}
```

**Example Output:**
```
PR #90 description updated.
```

---

#### `get_my_prs`
**Purpose:** List pull requests authored by the authenticated user.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"get_my_prs"` |
| `state` | string | No | PR state: `OPEN`, `MERGED`, `DECLINED` (default `OPEN`) |
| `repo_name` | string | No | Repository name for multi-repo projects |

**Example Input:**
```json
{
  "action": "get_my_prs",
  "state": "OPEN"
}
```

**Example Output:**
```
Your open PRs (2 found):

PR #90: PAY-456: Fix null pointer in refund flow
  feature/PAY-456 -> master | 1 approval | Updated 2h ago

PR #88: PAY-458: Add webhook for refund status
  feature/PAY-458 -> master | 0 approvals | Updated 1d ago
```

---

#### `get_reviewing_prs`
**Purpose:** List pull requests where the authenticated user is a reviewer.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"get_reviewing_prs"` |
| `state` | string | No | PR state: `OPEN`, `MERGED`, `DECLINED` (default `OPEN`) |
| `repo_name` | string | No | Repository name for multi-repo projects |

**Example Input:**
```json
{
  "action": "get_reviewing_prs",
  "state": "OPEN"
}
```

**Example Output:**
```
PRs you're reviewing (1 found):

PR #87: PLAT-200: Upgrade Spring Boot to 3.3
  feature/PLAT-200 -> master | Author: jane.smith | Your status: UNAPPROVED
```

---

#### `get_file_content`
**Purpose:** Get file content at a specific git ref from Bitbucket.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"get_file_content"` |
| `file_path` | string | Yes | File path |
| `at_ref` | string | Yes | Git ref (branch/tag/commit) |
| `repo_name` | string | No | Repository name for multi-repo projects |

**Example Input:**
```json
{
  "action": "get_file_content",
  "file_path": "src/main/java/com/example/PaymentService.java",
  "at_ref": "master"
}
```

**Example Output:**
```
File: src/main/java/com/example/PaymentService.java (at master)

package com.example;

import ...

public class PaymentService {
    ...
}
```

---

#### `get_branches`
**Purpose:** List repository branches, optionally filtered by name.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"get_branches"` |
| `filter` | string | No | Name filter |
| `repo_name` | string | No | Repository name for multi-repo projects |

**Example Input:**
```json
{
  "action": "get_branches",
  "filter": "feature/PAY"
}
```

**Example Output:**
```
Branches matching "feature/PAY" (3 found):

feature/PAY-456 (latest: abc1234, 2h ago)
feature/PAY-458 (latest: xyz9876, 1d ago)
feature/PAY-470 (latest: mno3456, 30m ago)
```

---

#### `create_branch`
**Purpose:** Create a new branch in the repository.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"create_branch"` |
| `name` | string | Yes | Branch name |
| `start_point` | string | Yes | Source ref to branch from |
| `repo_name` | string | No | Repository name for multi-repo projects |
| `description` | string | No | Approval dialog description |

**Example Input:**
```json
{
  "action": "create_branch",
  "name": "feature/PAY-470-refund-limits",
  "start_point": "master"
}
```

**Example Output:**
```
Branch feature/PAY-470-refund-limits created from master.
```

---

#### `search_users`
**Purpose:** Search for Bitbucket users (e.g., to add as reviewers).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"search_users"` |
| `filter` | string | Yes | Name filter |
| `repo_name` | string | No | Repository name for multi-repo projects |

**Example Input:**
```json
{
  "action": "search_users",
  "filter": "smith"
}
```

**Example Output:**
```
Users matching "smith" (2 found):

jane.smith — Jane Smith (jane.smith@example.com)
alex.smith — Alex Smith (alex.smith@example.com)
```

---

#### `get_build_statuses`
**Purpose:** Get CI build statuses for a specific commit.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"get_build_statuses"` |
| `commit_id` | string | Yes | Commit hash |
| `repo_name` | string | No | Repository name for multi-repo projects |

**Example Input:**
```json
{
  "action": "get_build_statuses",
  "commit_id": "abc1234def5678"
}
```

**Example Output:**
```
Build statuses for abc1234 (2 found):

PAY-BUILD #247: SUCCESSFUL (2026-03-30 09:15)
PAY-SONAR: SUCCESSFUL (2026-03-30 09:20)
```

---

#### `list_repos`
**Purpose:** List all repositories accessible to the authenticated user.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"list_repos"` |

**Example Input:**
```json
{
  "action": "list_repos"
}
```

**Example Output:**
```
Repositories (4 found):

payment-service (project: PAY) — primary
payment-gateway (project: PAY)
platform-core (project: PLAT)
platform-common (project: PLAT)
```

---

## 5. `debug` -- Interactive Debugger

**Description:** Interactive debugger -- breakpoints, stepping, inspection, memory, hot swap, and remote attach.

**Allowed Workers:** CODER, REVIEWER, ANALYZER

### Actions

#### `add_breakpoint`
**Purpose:** Set a line breakpoint in a source file.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"add_breakpoint"` |
| `file` | string | Yes | File path (relative to project or absolute) |
| `line` | integer | Yes | 1-based line number |
| `condition` | string | No | Conditional expression |
| `log_expression` | string | No | Expression to log when hit without stopping |
| `temporary` | boolean | No | If true, removed after first hit |

**Example Input:**
```json
{
  "action": "add_breakpoint",
  "file": "src/main/java/com/example/PaymentService.java",
  "line": 142,
  "condition": "customerRef == null"
}
```

**Example Output:**
```
Breakpoint set at PaymentService.java:142
Condition: customerRef == null
```

---

#### `method_breakpoint`
**Purpose:** Set a breakpoint on method entry and/or exit.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"method_breakpoint"` |
| `class_name` | string | Yes | Fully qualified class name |
| `method_name` | string | Yes | Method name |
| `watch_entry` | boolean | No | Break on method entry (default: true) |
| `watch_exit` | boolean | No | Break on method exit (default: false) |

**Example Input:**
```json
{
  "action": "method_breakpoint",
  "class_name": "com.example.PaymentService",
  "method_name": "processRefund",
  "watch_entry": true,
  "watch_exit": true
}
```

**Example Output:**
```
Method breakpoint set: com.example.PaymentService.processRefund()
Watch: entry=true, exit=true
```

---

#### `exception_breakpoint`
**Purpose:** Set a breakpoint that triggers when a specific exception is thrown.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"exception_breakpoint"` |
| `exception_class` | string | Yes | Fully qualified exception class name |
| `caught` | boolean | No | Break on caught exceptions (default: true) |
| `uncaught` | boolean | No | Break on uncaught exceptions (default: true) |
| `condition` | string | No | Optional conditional expression |

**Example Input:**
```json
{
  "action": "exception_breakpoint",
  "exception_class": "java.lang.NullPointerException",
  "caught": true,
  "uncaught": true
}
```

**Example Output:**
```
Exception breakpoint set: java.lang.NullPointerException
Caught: true | Uncaught: true
```

---

#### `field_watchpoint`
**Purpose:** Set a watchpoint on a class field (break on read and/or write).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"field_watchpoint"` |
| `class_name` | string | Yes | Fully qualified class name |
| `field_name` | string | Yes | Field name to watch |
| `file` | string | No | File path (helps resolve location) |
| `watch_read` | boolean | No | Break on field read (default: false) |
| `watch_write` | boolean | No | Break on field write (default: true) |

**Example Input:**
```json
{
  "action": "field_watchpoint",
  "class_name": "com.example.PaymentService",
  "field_name": "refundGateway",
  "watch_read": false,
  "watch_write": true
}
```

**Example Output:**
```
Field watchpoint set: com.example.PaymentService.refundGateway
Watch: read=false, write=true
```

---

#### `remove_breakpoint`
**Purpose:** Remove a breakpoint at a specific file and line.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"remove_breakpoint"` |
| `file` | string | Yes | File path |
| `line` | integer | Yes | Line number |

**Example Input:**
```json
{
  "action": "remove_breakpoint",
  "file": "src/main/java/com/example/PaymentService.java",
  "line": 142
}
```

**Example Output:**
```
Breakpoint removed at PaymentService.java:142.
```

---

#### `list_breakpoints`
**Purpose:** List all current breakpoints, optionally filtered by file.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"list_breakpoints"` |
| `file` | string | No | Filter by file path |

**Example Input:**
```json
{
  "action": "list_breakpoints"
}
```

**Example Output:**
```
Breakpoints (3 total):

[1] PaymentService.java:142 (line, condition: customerRef == null)
[2] com.example.PaymentService.processRefund (method, entry+exit)
[3] java.lang.NullPointerException (exception, caught+uncaught)
```

---

#### `start_session`
**Purpose:** Launch a debug session using an existing run configuration.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"start_session"` |
| `config_name` | string | Yes | Name of the run configuration to debug |
| `wait_for_pause` | integer | No | Seconds to wait for first breakpoint hit (default 0) |

**Example Input:**
```json
{
  "action": "start_session",
  "config_name": "PaymentServiceApplication",
  "wait_for_pause": 10
}
```

**Example Output:**
```
Debug session started: PaymentServiceApplication
Session ID: session-1
Status: RUNNING
Listening for breakpoint hits...
```

---

#### `get_state`
**Purpose:** Get the current state of the debug session (paused, running, stopped).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"get_state"` |
| `session_id` | string | No | Debug session ID (uses active session if omitted) |

**Example Input:**
```json
{
  "action": "get_state"
}
```

**Example Output:**
```
Debug session: session-1
Status: PAUSED at PaymentService.java:142
Thread: main
Reason: Breakpoint hit (condition: customerRef == null)
```

---

#### `step_over`
**Purpose:** Step over the current line (execute it and stop at the next line).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"step_over"` |
| `session_id` | string | No | Debug session ID |

**Example Input:**
```json
{
  "action": "step_over"
}
```

**Example Output:**
```
Stepped over. Now at PaymentService.java:143
```

---

#### `step_into`
**Purpose:** Step into the method call on the current line.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"step_into"` |
| `session_id` | string | No | Debug session ID |

**Example Input:**
```json
{
  "action": "step_into"
}
```

**Example Output:**
```
Stepped into Order.getCustomerReference() at Order.java:55
```

---

#### `step_out`
**Purpose:** Step out of the current method and return to the caller.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"step_out"` |
| `session_id` | string | No | Debug session ID |

**Example Input:**
```json
{
  "action": "step_out"
}
```

**Example Output:**
```
Stepped out. Now at PaymentService.java:142 (returned from Order.getCustomerReference())
Return value: null
```

---

#### `resume`
**Purpose:** Resume execution until the next breakpoint or program end.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"resume"` |
| `session_id` | string | No | Debug session ID |

**Example Input:**
```json
{
  "action": "resume"
}
```

**Example Output:**
```
Resumed. Status: RUNNING
```

---

#### `pause`
**Purpose:** Pause all threads in the debug session.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"pause"` |
| `session_id` | string | No | Debug session ID |

**Example Input:**
```json
{
  "action": "pause"
}
```

**Example Output:**
```
Paused. All threads suspended.
Current position: PaymentService.java:146 (thread: main)
```

---

#### `run_to_cursor`
**Purpose:** Resume execution and pause when reaching a specific line.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"run_to_cursor"` |
| `file` | string | Yes | File path |
| `line` | integer | Yes | Target line number |
| `session_id` | string | No | Debug session ID |

**Example Input:**
```json
{
  "action": "run_to_cursor",
  "file": "src/main/java/com/example/PaymentService.java",
  "line": 146
}
```

**Example Output:**
```
Run to cursor: reached PaymentService.java:146
```

---

#### `stop`
**Purpose:** Stop the debug session and terminate the process.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"stop"` |
| `session_id` | string | No | Debug session ID |

**Example Input:**
```json
{
  "action": "stop"
}
```

**Example Output:**
```
Debug session session-1 stopped. Process terminated.
```

---

#### `evaluate`
**Purpose:** Evaluate a Java/Kotlin expression in the current debug context.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"evaluate"` |
| `expression` | string | Yes | Java/Kotlin expression to evaluate |
| `session_id` | string | No | Debug session ID |

**Example Input:**
```json
{
  "action": "evaluate",
  "expression": "order.getCustomerReference()"
}
```

**Example Output:**
```
Expression: order.getCustomerReference()
Result: null (type: String)
```

---

#### `get_stack_frames`
**Purpose:** Get the current call stack.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"get_stack_frames"` |
| `thread_name` | string | No | Thread name (default: current suspended thread) |
| `max_frames` | integer | No | Max frames to return (default 20, max 50) |
| `session_id` | string | No | Debug session ID |

**Example Input:**
```json
{
  "action": "get_stack_frames",
  "max_frames": 5
}
```

**Example Output:**
```
Stack frames (thread: main, 5 shown):

[0] PaymentService.processRefund(PaymentService.java:142)
[1] PaymentController.handleRefund(PaymentController.java:78)
[2] DispatcherServlet.doDispatch(DispatcherServlet.java:1067)
[3] FrameworkServlet.service(FrameworkServlet.java:897)
[4] HttpServlet.service(HttpServlet.java:750)
```

---

#### `get_variables`
**Purpose:** Inspect variables in the current stack frame.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"get_variables"` |
| `variable_name` | string | No | Specific variable to deep-inspect |
| `max_depth` | integer | No | Max depth for expansion (default 2, max 4) |
| `session_id` | string | No | Debug session ID |

**Example Input:**
```json
{
  "action": "get_variables",
  "variable_name": "order",
  "max_depth": 3
}
```

**Example Output:**
```
Variable: order (Order)
  orderId: "ORD-12345" (String)
  customerReference: null (String)
  amount: 99.99 (BigDecimal)
    intVal: 9999 (int)
    scale: 2 (int)
  status: "PENDING_REFUND" (String)
  createdAt: 2026-03-29T14:30:00Z (Instant)
```

---

#### `thread_dump`
**Purpose:** Get a thread dump of all threads in the debugged process.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"thread_dump"` |
| `max_frames` | integer | No | Max stack frames per thread (default 20, max 50) |
| `include_stacks` | boolean | No | Include stack traces per thread (default: true) |
| `include_daemon` | boolean | No | Include daemon threads (default: false) |
| `session_id` | string | No | Debug session ID |

**Example Input:**
```json
{
  "action": "thread_dump",
  "include_daemon": false,
  "max_frames": 5
}
```

**Example Output:**
```
Thread dump (3 non-daemon threads):

[main] SUSPENDED at PaymentService.java:142
  PaymentService.processRefund(PaymentService.java:142)
  PaymentController.handleRefund(PaymentController.java:78)
  ...

[http-nio-8080-exec-2] RUNNING
  SocketInputStream.read(SocketInputStream.java:186)
  ...

[scheduling-1] TIMED_WAITING
  Thread.sleep(Thread.java:450)
  ...
```

---

#### `memory_view`
**Purpose:** Inspect instances of a class in the debugged JVM heap.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"memory_view"` |
| `class_name` | string | Yes | Fully qualified class name |
| `max_instances` | integer | No | Max instances to list details for (0 = count only, default 0) |
| `session_id` | string | No | Debug session ID |

**Example Input:**
```json
{
  "action": "memory_view",
  "class_name": "com.example.Order",
  "max_instances": 3
}
```

**Example Output:**
```
Memory view: com.example.Order
Instances: 42

Top 3 instances:
  [1] Order{orderId="ORD-12345", status="PENDING_REFUND"}
  [2] Order{orderId="ORD-12344", status="COMPLETED"}
  [3] Order{orderId="ORD-12343", status="COMPLETED"}
```

---

#### `hotswap`
**Purpose:** Hot-swap modified classes into the running debug session without restarting.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"hotswap"` |
| `compile_first` | boolean | No | Compile changed files before reloading (default: true) |
| `session_id` | string | No | Debug session ID |

**Example Input:**
```json
{
  "action": "hotswap"
}
```

**Example Output:**
```
Hot swap completed:
  Compiled: 1 file (PaymentService.java)
  Reloaded: 1 class (com.example.PaymentService)
  Status: SUCCESS
```

---

#### `force_return`
**Purpose:** Force the current method to return a specific value immediately.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"force_return"` |
| `return_value` | string | No | Value to return: `"null"`, `"42"`, `"true"`, etc. Omit for void. |
| `return_type` | string | No | Return type: `auto`, `void`, `null`, `int`, `long`, `boolean`, `string`, `double`, `float`, `char`, `byte`, `short` (default: `auto`) |
| `session_id` | string | No | Debug session ID |

**Example Input:**
```json
{
  "action": "force_return",
  "return_value": "null",
  "return_type": "null"
}
```

**Example Output:**
```
Forced return from PaymentService.processRefund() with value: null
Now at PaymentController.java:78
```

---

#### `drop_frame`
**Purpose:** Drop the current stack frame and restart execution from the caller.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"drop_frame"` |
| `frame_index` | integer | No | Frame index to drop to (0 = current, 1 = caller, default 0) |
| `session_id` | string | No | Debug session ID |

**Example Input:**
```json
{
  "action": "drop_frame",
  "frame_index": 0
}
```

**Example Output:**
```
Frame dropped. Re-entered PaymentService.processRefund() at line 140.
```

---

#### `attach_to_process`
**Purpose:** Attach the debugger to a remote JVM process.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"attach_to_process"` |
| `host` | string | No | Host to connect to (default: `localhost`) |
| `port` | integer | Yes | Debug port (e.g. 5005) |
| `name` | string | No | Display name for the debug configuration |

**Example Input:**
```json
{
  "action": "attach_to_process",
  "host": "localhost",
  "port": 5005,
  "name": "Remote Payment Service"
}
```

**Example Output:**
```
Attached to localhost:5005
Debug session: Remote Payment Service (session-2)
Status: RUNNING
```

---

## 6. `git` -- Git Version Control

**Description:** Git version control -- status, blame, diff, log, branches, show file/commit, stash, merge-base, file history, changelist/shelve operations.

**Allowed Workers:** CODER, REVIEWER, ANALYZER, ORCHESTRATOR

### Actions

#### `status`
**Purpose:** Show the working tree status (modified, staged, untracked files).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"status"` |

**Example Input:**
```json
{
  "action": "status"
}
```

**Example Output:**
```
Git status:

Staged:
  M src/main/java/com/example/PaymentService.java

Unstaged:
  M src/test/java/com/example/PaymentServiceTest.java

Untracked:
  src/test/java/com/example/PaymentServiceNullRefTest.java
```

---

#### `blame`
**Purpose:** Show line-by-line authorship for a file or range of lines.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"blame"` |
| `path` | string | Yes | File path (relative or absolute) |
| `start_line` | integer | No | Start line (1-based, default 1) |
| `end_line` | integer | No | End line (1-based, default last line) |

**Example Input:**
```json
{
  "action": "blame",
  "path": "src/main/java/com/example/PaymentService.java",
  "start_line": 140,
  "end_line": 147
}
```

**Example Output:**
```
Blame for PaymentService.java:140-147:

abc1234 (john.doe  2026-03-30) 140: public RefundResult processRefund(String orderId) {
abc1234 (john.doe  2026-03-30) 141:     Order order = orderRepository.findById(orderId);
9f8e7d6 (jane.doe  2026-01-15) 142:     String customerRef = order.getCustomerReference();
abc1234 (john.doe  2026-03-30) 143:     if (customerRef == null) {
abc1234 (john.doe  2026-03-30) 144:         throw new InvalidRefundException("Customer reference is null");
abc1234 (john.doe  2026-03-30) 145:     }
9f8e7d6 (jane.doe  2026-01-15) 146:     return refundGateway.process(customerRef, order.getAmount());
9f8e7d6 (jane.doe  2026-01-15) 147: }
```

---

#### `diff`
**Purpose:** Show changes in the working tree or between refs.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"diff"` |
| `path` | string | No | File or directory path filter |
| `ref` | string | No | Git ref to diff against (branch, tag, commit, `HEAD~N`) |
| `staged` | boolean | No | Show only staged changes (default: false) |

**Example Input:**
```json
{
  "action": "diff",
  "path": "src/main/java/com/example/PaymentService.java",
  "staged": true
}
```

**Example Output:**
```
diff --git a/src/main/java/com/example/PaymentService.java b/src/main/java/com/example/PaymentService.java
index 9f8e7d6..abc1234 100644
--- a/src/main/java/com/example/PaymentService.java
+++ b/src/main/java/com/example/PaymentService.java
@@ -140,6 +140,9 @@
     public RefundResult processRefund(String orderId) {
         Order order = orderRepository.findById(orderId);
         String customerRef = order.getCustomerReference();
+        if (customerRef == null) {
+            throw new InvalidRefundException("Customer reference is null for order " + orderId);
+        }
         if (customerRef.isEmpty()) {
```

---

#### `log`
**Purpose:** Show commit history, optionally filtered by path or ref.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"log"` |
| `path` | string | No | File or directory path filter |
| `ref` | string | No | Branch/tag/commit to start from |
| `max_count` | integer | No | Max commits to show (default 20, max 50) |
| `oneline` | boolean | No | Compact one-line format (default: false) |

**Example Input:**
```json
{
  "action": "log",
  "max_count": 5,
  "oneline": true
}
```

**Example Output:**
```
abc1234 Add null check to processRefund (2h ago)
def5678 Add test for null customer ref (3h ago)
ghi9012 Update Stripe SDK to v12 (1d ago)
jkl3456 Fix currency rounding in refund (2d ago)
mno7890 Add retry logic to payment gateway (3d ago)
```

---

#### `branches`
**Purpose:** List local and optionally remote branches and tags.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"branches"` |
| `show_remote` | boolean | No | Show remote branches (default: true) |
| `show_tags` | boolean | No | Show tags (default: false) |

**Example Input:**
```json
{
  "action": "branches",
  "show_remote": false,
  "show_tags": true
}
```

**Example Output:**
```
Local branches (3):
* feature/PAY-456 (abc1234)
  master (xyz9876)
  feature/PAY-458 (mno3456)

Tags (2):
  v1.4.0 (tagged 2026-03-15)
  v1.5.0-rc1 (tagged 2026-03-28)
```

---

#### `show_file`
**Purpose:** Show file content at a specific git ref.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"show_file"` |
| `path` | string | Yes | File path |
| `ref` | string | Yes | Git ref (branch, tag, commit SHA, `HEAD~N`) |

**Example Input:**
```json
{
  "action": "show_file",
  "path": "src/main/java/com/example/PaymentService.java",
  "ref": "HEAD~1"
}
```

**Example Output:**
```
File: src/main/java/com/example/PaymentService.java (at HEAD~1)

package com.example;
...
    public RefundResult processRefund(String orderId) {
        Order order = orderRepository.findById(orderId);
        String customerRef = order.getCustomerReference();
        if (customerRef.isEmpty()) {
...
```

---

#### `show_commit`
**Purpose:** Show details of a specific commit.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"show_commit"` |
| `commit` | string | Yes | Commit reference (SHA, `HEAD`, `HEAD~N`, branch name) |
| `include_diff` | boolean | No | Include the full diff (default: false) |

**Example Input:**
```json
{
  "action": "show_commit",
  "commit": "abc1234",
  "include_diff": true
}
```

**Example Output:**
```
Commit: abc1234def5678
Author: john.doe <john.doe@example.com>
Date: 2026-03-30 10:00
Message: Add null check to processRefund

Files changed (1):
  M src/main/java/com/example/PaymentService.java

diff --git a/src/main/java/com/example/PaymentService.java ...
@@ -140,6 +140,9 @@
+        if (customerRef == null) {
+            throw new InvalidRefundException("Customer reference is null");
+        }
```

---

#### `stash_list`
**Purpose:** List all git stashes.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"stash_list"` |

**Example Input:**
```json
{
  "action": "stash_list"
}
```

**Example Output:**
```
Stashes (2 found):

stash@{0}: WIP on feature/PAY-456: abc1234 Add null check
stash@{1}: On master: experimental refactor (3d ago)
```

---

#### `merge_base`
**Purpose:** Find the common ancestor of two refs.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"merge_base"` |
| `ref1` | string | Yes | First ref (local branch, tag, or commit SHA) |
| `ref2` | string | Yes | Second ref |

**Example Input:**
```json
{
  "action": "merge_base",
  "ref1": "feature/PAY-456",
  "ref2": "master"
}
```

**Example Output:**
```
Merge base of feature/PAY-456 and master:
  Commit: xyz9876abc1234
  Author: jane.doe
  Date: 2026-03-25 16:00
  Message: Merge PR #85: Upgrade Spring Boot to 3.3
```

---

#### `file_history`
**Purpose:** Show the commit history for a specific file.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"file_history"` |
| `path` | string | Yes | File path |
| `max_count` | integer | No | Max commits to show (default 15, max 30) |

**Example Input:**
```json
{
  "action": "file_history",
  "path": "src/main/java/com/example/PaymentService.java",
  "max_count": 5
}
```

**Example Output:**
```
File history: PaymentService.java (5 commits):

abc1234 Add null check to processRefund (john.doe, 2h ago)
9f8e7d6 Add processRefund method (jane.doe, 2026-01-15)
5a4b3c2 Extract refund gateway (jane.doe, 2026-01-10)
1d2e3f4 Add PaymentService class (bob.lee, 2025-12-01)
0a1b2c3 Initial commit (bob.lee, 2025-11-15)
```

---

#### `shelve`
**Purpose:** Manage IntelliJ changelists and shelves.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"shelve"` |
| `shelve_action` | string | Yes | Sub-operation: `list`, `list_shelves`, `create`, `shelve`, `unshelve` |
| `name` | string | Conditional | Changelist name (for `create`) |
| `comment` | string | No | Description (for `create` or `shelve`) |
| `shelf_index` | integer | Conditional | 0-based index of shelf to unshelve (for `unshelve`) |

**Example Input (list changelists):**
```json
{
  "action": "shelve",
  "shelve_action": "list"
}
```

**Example Output:**
```
Changelists (2 found):

[Default] 3 files modified
  M PaymentService.java
  M PaymentServiceTest.java
  A PaymentServiceNullRefTest.java

[PAY-456 cleanup] 1 file modified
  M README.md
```

**Example Input (shelve changes):**
```json
{
  "action": "shelve",
  "shelve_action": "shelve",
  "comment": "WIP: refund null safety"
}
```

**Example Output:**
```
Changes shelved: "WIP: refund null safety" (3 files)
```

---

## 7. `spring` -- Spring Framework Intelligence

**Description:** Spring Framework and Spring Boot intelligence -- beans, endpoints, config, security, profiles, repositories, JPA entities, scheduled tasks, actuator.

**Allowed Workers:** TOOLER, ANALYZER, ORCHESTRATOR, CODER

### Actions

#### `context`
**Purpose:** Discover Spring beans (classes annotated with `@Component`, `@Service`, `@Repository`, `@Controller`, `@Configuration`).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"context"` |
| `filter` | string | No | Filter results by name/pattern |

**Example Input:**
```json
{
  "action": "context",
  "filter": "Payment"
}
```

**Example Output:**
```
Spring beans matching "Payment" (3 found):

@Service PaymentService (com.example.service.PaymentService)
  Injected: OrderRepository, RefundGateway, PaymentMapper

@Repository PaymentRepository (com.example.repository.PaymentRepository)
  Extends: JpaRepository<Payment, Long>

@Controller PaymentController (com.example.controller.PaymentController)
  Injected: PaymentService
```

---

#### `endpoints`
**Purpose:** List HTTP endpoints from `@RequestMapping`, `@GetMapping`, etc.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"endpoints"` |
| `filter` | string | No | Filter by path or controller name |
| `include_params` | boolean | No | Show handler method parameters with annotations (default: false) |

**Example Input:**
```json
{
  "action": "endpoints",
  "filter": "refund",
  "include_params": true
}
```

**Example Output:**
```
Endpoints matching "refund" (2 found):

POST /api/payments/refund -> PaymentController.handleRefund()
  Params: @RequestBody RefundRequest request, @RequestHeader("X-Idempotency-Key") String idempotencyKey
  Produces: application/json

GET /api/payments/refund/{orderId}/history -> PaymentController.getRefundHistory()
  Params: @PathVariable String orderId
  Produces: application/json
```

---

#### `bean_graph`
**Purpose:** Show the dependency graph for a specific bean.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"bean_graph"` |
| `bean_name` | string | Yes | Bean class name (simple or fully qualified) |

**Example Input:**
```json
{
  "action": "bean_graph",
  "bean_name": "PaymentService"
}
```

**Example Output:**
```
Bean graph for PaymentService:

PaymentService
  <- PaymentController (injected via constructor)
  -> OrderRepository (field injection)
  -> RefundGateway (constructor param)
  -> PaymentMapper (constructor param)
     -> ModelMapper (constructor param)
```

---

#### `config`
**Purpose:** Read Spring configuration properties from `application.properties`/`application.yml`.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"config"` |
| `property` | string | No | Specific property name (e.g. `spring.datasource.url`) |

**Example Input:**
```json
{
  "action": "config",
  "property": "spring.datasource"
}
```

**Example Output:**
```
Properties matching "spring.datasource":

spring.datasource.url = jdbc:postgresql://localhost:5432/payments
spring.datasource.username = pay_user
spring.datasource.driver-class-name = org.postgresql.Driver
spring.datasource.hikari.maximum-pool-size = 10
spring.datasource.hikari.minimum-idle = 5
```

---

#### `version_info`
**Purpose:** Get Spring/Spring Boot version information.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"version_info"` |
| `module` | string | No | Module name to inspect |

**Example Input:**
```json
{
  "action": "version_info"
}
```

**Example Output:**
```
Spring version info:

Spring Boot: 3.3.0
Spring Framework: 6.1.8
Spring Security: 6.3.0
Spring Data JPA: 3.3.0
Java: 21 (Temurin)
```

---

#### `profiles`
**Purpose:** List Spring profiles and their configuration files.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"profiles"` |

**Example Input:**
```json
{
  "action": "profiles"
}
```

**Example Output:**
```
Spring profiles detected (3):

default — application.properties (42 properties)
dev — application-dev.properties (15 properties)
prod — application-prod.yml (28 properties)
```

---

#### `repositories`
**Purpose:** List Spring Data repositories and their entity types.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"repositories"` |

**Example Input:**
```json
{
  "action": "repositories"
}
```

**Example Output:**
```
Spring Data repositories (4 found):

PaymentRepository extends JpaRepository<Payment, Long>
  Custom methods: findByOrderId(String), findByStatus(PaymentStatus)

OrderRepository extends JpaRepository<Order, Long>
  Custom methods: findByCustomerId(String)

RefundRepository extends JpaRepository<Refund, Long>
  Custom methods: findByCustomerId(String), findByOrderId(String)

CustomerRepository extends JpaRepository<Customer, Long>
```

---

#### `security_config`
**Purpose:** Analyze Spring Security configuration.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"security_config"` |

**Example Input:**
```json
{
  "action": "security_config"
}
```

**Example Output:**
```
Spring Security configuration:

SecurityFilterChain in SecurityConfig.java:
  /api/public/** — permitAll
  /api/admin/** — hasRole("ADMIN")
  /api/** — authenticated
  CSRF: disabled
  Session: STATELESS
  Auth: JWT (BearerTokenAuthenticationFilter)
```

---

#### `scheduled_tasks`
**Purpose:** Find `@Scheduled` methods in the project.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"scheduled_tasks"` |

**Example Input:**
```json
{
  "action": "scheduled_tasks"
}
```

**Example Output:**
```
Scheduled tasks (2 found):

PaymentReconciliationJob.reconcile()
  @Scheduled(cron = "0 0 2 * * *") — daily at 2 AM

MetricsCollector.collectMetrics()
  @Scheduled(fixedRate = 60000) — every 60s
```

---

#### `event_listeners`
**Purpose:** Find Spring event listeners (`@EventListener`, `ApplicationListener`).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"event_listeners"` |

**Example Input:**
```json
{
  "action": "event_listeners"
}
```

**Example Output:**
```
Event listeners (3 found):

PaymentEventHandler.onPaymentCompleted(PaymentCompletedEvent)
  @EventListener | async: false

NotificationService.onRefundProcessed(RefundProcessedEvent)
  @EventListener @Async | async: true

AuditLogger.onApplicationEvent(AuditEvent)
  implements ApplicationListener<AuditEvent>
```

---

#### `boot_endpoints`
**Purpose:** List Spring Boot REST endpoints with detailed handler information.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"boot_endpoints"` |
| `class_name` | string | No | Filter by controller class name |

**Example Input:**
```json
{
  "action": "boot_endpoints",
  "class_name": "PaymentController"
}
```

**Example Output:**
```
Boot endpoints for PaymentController (4 endpoints):

POST /api/payments — createPayment
GET  /api/payments/{id} — getPayment
POST /api/payments/refund — handleRefund
GET  /api/payments/refund/{orderId}/history — getRefundHistory
```

---

#### `boot_autoconfig`
**Purpose:** List Spring Boot auto-configuration classes.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"boot_autoconfig"` |
| `filter` | string | No | Filter by name |
| `project_only` | boolean | No | Only scan project scope (default: true) |

**Example Input:**
```json
{
  "action": "boot_autoconfig",
  "filter": "DataSource"
}
```

**Example Output:**
```
Auto-configuration classes matching "DataSource" (2 found):

DataSourceAutoConfiguration (org.springframework.boot.autoconfigure.jdbc)
  Conditions: @ConditionalOnClass(DataSource.class)

DataSourceTransactionManagerAutoConfiguration
  Conditions: @ConditionalOnClass(DataSource.class, PlatformTransactionManager.class)
```

---

#### `boot_config_properties`
**Purpose:** List `@ConfigurationProperties` classes and their bound properties.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"boot_config_properties"` |
| `class_name` | string | No | Filter by class name |
| `prefix` | string | No | Filter by property prefix |

**Example Input:**
```json
{
  "action": "boot_config_properties",
  "prefix": "app.payment"
}
```

**Example Output:**
```
@ConfigurationProperties with prefix "app.payment":

PaymentProperties (prefix: app.payment)
  maxRetries: int = 3
  timeoutMs: long = 5000
  gatewayUrl: String
  enabled: boolean = true
```

---

#### `boot_actuator`
**Purpose:** List Spring Boot Actuator endpoint configurations.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"boot_actuator"` |

**Example Input:**
```json
{
  "action": "boot_actuator"
}
```

**Example Output:**
```
Actuator configuration:

Endpoints exposed: health, info, metrics, prometheus
Base path: /actuator

management.endpoints.web.exposure.include = health,info,metrics,prometheus
management.endpoint.health.show-details = when-authorized
management.metrics.tags.application = payment-service
```

---

#### `jpa_entities`
**Purpose:** List JPA entity classes, their fields, and relationships.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"jpa_entities"` |
| `entity` | string | No | Specific entity class name |

**Example Input:**
```json
{
  "action": "jpa_entities",
  "entity": "Payment"
}
```

**Example Output:**
```
JPA Entity: Payment (@Table: payments)

Fields:
  @Id @GeneratedValue id: Long
  orderId: String (@Column, unique)
  amount: BigDecimal
  currency: String
  status: PaymentStatus (@Enumerated(STRING))
  createdAt: Instant (@CreatedDate)
  updatedAt: Instant (@LastModifiedDate)

Relationships:
  @ManyToOne customer: Customer (FK: customer_id)
  @OneToMany refunds: List<Refund> (mappedBy: payment)
```

---

## 8. `build` -- Build System Intelligence

**Description:** Build system intelligence -- Maven dependencies/properties/plugins/profiles, Gradle tasks/dependencies/properties, project modules, dependency graphs.

**Allowed Workers:** TOOLER, ANALYZER, ORCHESTRATOR, CODER

### Actions

#### `maven_dependencies`
**Purpose:** List Maven dependencies, optionally filtered by module or scope.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"maven_dependencies"` |
| `module` | string | No | Module/artifactId name |
| `scope` | string | No | Filter by scope: `compile`, `test`, `runtime`, `provided` |
| `search` | string | No | Filter by name substring |

**Example Input:**
```json
{
  "action": "maven_dependencies",
  "module": "payment-service",
  "scope": "compile",
  "search": "spring"
}
```

**Example Output:**
```
Maven dependencies for payment-service (scope: compile, filter: "spring"):

org.springframework.boot:spring-boot-starter-web:3.3.0 (compile)
org.springframework.boot:spring-boot-starter-data-jpa:3.3.0 (compile)
org.springframework.boot:spring-boot-starter-security:3.3.0 (compile)
org.springframework:spring-core:6.1.8 (compile, transitive)
```

---

#### `maven_properties`
**Purpose:** List Maven properties defined in pom.xml.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"maven_properties"` |
| `module` | string | No | Module name |
| `search` | string | No | Filter by name/value substring |

**Example Input:**
```json
{
  "action": "maven_properties",
  "search": "version"
}
```

**Example Output:**
```
Maven properties matching "version":

java.version = 21
spring-boot.version = 3.3.0
project.version = 1.5.0-SNAPSHOT
lombok.version = 1.18.32
mapstruct.version = 1.5.5.Final
```

---

#### `maven_plugins`
**Purpose:** List Maven plugins configured in the project.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"maven_plugins"` |
| `module` | string | No | Module name |

**Example Input:**
```json
{
  "action": "maven_plugins"
}
```

**Example Output:**
```
Maven plugins (5 found):

org.springframework.boot:spring-boot-maven-plugin:3.3.0
org.apache.maven.plugins:maven-compiler-plugin:3.13.0
org.apache.maven.plugins:maven-surefire-plugin:3.2.5
org.jacoco:jacoco-maven-plugin:0.8.12
org.sonarsource.scanner.maven:sonar-maven-plugin:3.11.0
```

---

#### `maven_profiles`
**Purpose:** List Maven profiles defined in the project.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"maven_profiles"` |
| `module` | string | No | Module name |

**Example Input:**
```json
{
  "action": "maven_profiles"
}
```

**Example Output:**
```
Maven profiles (3 found):

dev (active by default)
  Properties: spring.profiles.active=dev

integration-tests
  Plugins: maven-failsafe-plugin

production
  Properties: spring.profiles.active=prod
  Plugins: maven-enforcer-plugin
```

---

#### `maven_dependency_tree`
**Purpose:** Show the full dependency tree, optionally filtered to paths containing a specific artifact.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"maven_dependency_tree"` |
| `module` | string | No | Module name |
| `artifact` | string | No | Filter to paths containing this artifact |

**Example Input:**
```json
{
  "action": "maven_dependency_tree",
  "artifact": "jackson"
}
```

**Example Output:**
```
Dependency tree (filtered: jackson):

com.example:payment-service:1.5.0-SNAPSHOT
  +- org.springframework.boot:spring-boot-starter-web:3.3.0
  |  +- org.springframework.boot:spring-boot-starter-json:3.3.0
  |     +- com.fasterxml.jackson.core:jackson-databind:2.17.1
  |     +- com.fasterxml.jackson.core:jackson-core:2.17.1
  |     +- com.fasterxml.jackson.core:jackson-annotations:2.17.1
  |     +- com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.1
```

---

#### `maven_effective_pom`
**Purpose:** Show the effective POM after inheritance and interpolation.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"maven_effective_pom"` |
| `module` | string | No | Module name |
| `plugin` | string | No | Filter by plugin artifactId |

**Example Input:**
```json
{
  "action": "maven_effective_pom",
  "plugin": "maven-compiler-plugin"
}
```

**Example Output:**
```
Effective POM (plugin: maven-compiler-plugin):

<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-compiler-plugin</artifactId>
  <version>3.13.0</version>
  <configuration>
    <release>21</release>
    <annotationProcessorPaths>
      <path>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <version>1.18.32</version>
      </path>
    </annotationProcessorPaths>
  </configuration>
</plugin>
```

---

#### `gradle_dependencies`
**Purpose:** List Gradle dependencies, optionally filtered by configuration.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"gradle_dependencies"` |
| `module` | string | No | Module name (e.g. `:core`, `jira`) |
| `configuration` | string | No | Filter by configuration: `implementation`, `api`, `testImplementation`, etc. |
| `search` | string | No | Filter by name substring |

**Example Input:**
```json
{
  "action": "gradle_dependencies",
  "module": ":core",
  "configuration": "implementation"
}
```

**Example Output:**
```
Gradle dependencies for :core (configuration: implementation):

com.squareup.okhttp3:okhttp:4.12.0
org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1
org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3
com.intellij:openapi (provided by platform)
```

---

#### `gradle_tasks`
**Purpose:** List available Gradle tasks.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"gradle_tasks"` |
| `module` | string | No | Module name |
| `search` | string | No | Filter by task name |

**Example Input:**
```json
{
  "action": "gradle_tasks",
  "search": "test"
}
```

**Example Output:**
```
Gradle tasks matching "test" (6 found):

:core:test — Runs unit tests for core module
:jira:test — Runs unit tests for jira module
:agent:test — Runs unit tests for agent module
:core:testClasses — Assembles test classes
:agent:testClasses — Assembles test classes
:verifyPlugin — Runs plugin verification
```

---

#### `gradle_properties`
**Purpose:** List Gradle project properties.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"gradle_properties"` |
| `module` | string | No | Module name |
| `search` | string | No | Filter by name/value substring |

**Example Input:**
```json
{
  "action": "gradle_properties",
  "search": "version"
}
```

**Example Output:**
```
Gradle properties matching "version":

pluginVersion = 0.41.0
platformVersion = 2025.1
kotlinVersion = 2.1.10
```

---

#### `project_modules`
**Purpose:** List all modules in the project with their paths and types.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"project_modules"` |

**Example Input:**
```json
{
  "action": "project_modules"
}
```

**Example Output:**
```
Project modules (11 found):

:core — /core (Java/Kotlin)
:jira — /jira (Kotlin)
:bamboo — /bamboo (Kotlin)
:sonar — /sonar (Kotlin)
:cody — /cody (Kotlin, deprecated)
:pullrequest — /pullrequest (Kotlin)
:automation — /automation (Kotlin)
:handover — /handover (Kotlin)
:git-integration — /git-integration (Kotlin)
:agent — /agent (Kotlin)
:mock-server — /mock-server (Kotlin, test only)
```

---

#### `module_dependency_graph`
**Purpose:** Show inter-module dependency relationships.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"module_dependency_graph"` |
| `module` | string | No | Focus on a specific module |
| `transitive` | boolean | No | Include transitive dependencies (default: false) |
| `include_libraries` | boolean | No | Include library dependencies (default: false) |
| `detect_cycles` | boolean | No | Run circular dependency detection (default: true) |

**Example Input:**
```json
{
  "action": "module_dependency_graph",
  "module": "agent",
  "transitive": true
}
```

**Example Output:**
```
Module dependency graph for :agent:

:agent
  -> :core (direct)
     -> (no module dependencies)

All feature modules:
  :jira -> :core
  :bamboo -> :core
  :sonar -> :core
  :pullrequest -> :core
  :agent -> :core

Circular dependencies: NONE
```

---

## 9. `runtime` -- Runtime Management

**Description:** Runtime management -- run configurations, processes, test results, compile, run tests.

**Allowed Workers:** CODER, REVIEWER, ANALYZER, ORCHESTRATOR, TOOLER

### Actions

#### `get_run_configurations`
**Purpose:** List IntelliJ run configurations.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"get_run_configurations"` |
| `type_filter` | string | No | Filter by type: `application`, `spring_boot`, `junit`, `gradle`, `remote_debug` |

**Example Input:**
```json
{
  "action": "get_run_configurations",
  "type_filter": "spring_boot"
}
```

**Example Output:**
```
Run configurations (type: spring_boot, 2 found):

PaymentServiceApplication (Spring Boot)
  Main class: com.example.PaymentApplication
  VM options: -Xmx512m
  Active profiles: dev
  Module: payment-service

[Agent] PaymentServiceDebug (Spring Boot)
  Main class: com.example.PaymentApplication
  Active profiles: dev,debug
  Module: payment-service
```

---

#### `create_run_config`
**Purpose:** Create a new IntelliJ run configuration (auto-prefixed with `[Agent]`).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"create_run_config"` |
| `name` | string | Yes | Configuration name (auto-prefixed with `[Agent]`) |
| `type` | string | Yes | Type: `application`, `spring_boot`, `junit`, `gradle`, `remote_debug` |
| `main_class` | string | Conditional | Fully qualified main class (required for `application`/`spring_boot`) |
| `test_class` | string | Conditional | Fully qualified test class (required for `junit`) |
| `test_method` | string | No | Specific test method (junit only) |
| `module` | string | No | Module name (auto-detected if omitted) |
| `env_vars` | object | No | Environment variables as key-value pairs |
| `vm_options` | string | No | JVM options |
| `program_args` | string | No | Program arguments |
| `working_dir` | string | No | Working directory |
| `active_profiles` | string | No | Spring Boot profiles, comma-separated |
| `port` | integer | No | Remote debug port (default 5005) |

**Example Input:**
```json
{
  "action": "create_run_config",
  "name": "PaymentService Debug",
  "type": "spring_boot",
  "main_class": "com.example.PaymentApplication",
  "active_profiles": "dev,debug",
  "vm_options": "-Xmx512m -Xdebug",
  "env_vars": {"PAYMENT_GATEWAY_URL": "http://localhost:8081"}
}
```

**Example Output:**
```
Run configuration created: [Agent] PaymentService Debug
Type: Spring Boot
Main class: com.example.PaymentApplication
Profiles: dev, debug
VM options: -Xmx512m -Xdebug
Env: PAYMENT_GATEWAY_URL=http://localhost:8081
```

---

#### `modify_run_config`
**Purpose:** Modify an existing run configuration.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"modify_run_config"` |
| `name` | string | Yes | Configuration name |
| `env_vars` | object | No | Environment variables |
| `vm_options` | string | No | JVM options |
| `program_args` | string | No | Program arguments |
| `working_dir` | string | No | Working directory |
| `active_profiles` | string | No | Spring Boot profiles |

**Example Input:**
```json
{
  "action": "modify_run_config",
  "name": "[Agent] PaymentService Debug",
  "vm_options": "-Xmx1g -Xdebug"
}
```

**Example Output:**
```
Run configuration updated: [Agent] PaymentService Debug
  vm_options: -Xmx1g -Xdebug
```

---

#### `delete_run_config`
**Purpose:** Delete a run configuration.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"delete_run_config"` |
| `name` | string | Yes | Configuration name |
| `description` | string | No | Brief description shown in approval dialog |

**Example Input:**
```json
{
  "action": "delete_run_config",
  "name": "[Agent] PaymentService Debug"
}
```

**Example Output:**
```
Run configuration deleted: [Agent] PaymentService Debug
```

---

#### `get_running_processes`
**Purpose:** List currently running processes in the IDE.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"get_running_processes"` |

**Example Input:**
```json
{
  "action": "get_running_processes"
}
```

**Example Output:**
```
Running processes (2 found):

[1] PaymentServiceApplication (Spring Boot) — PID 12345, running 15m
[2] PaymentServiceTest (JUnit) — PID 12346, running 30s
```

---

#### `get_run_output`
**Purpose:** Get console output from a running or completed process.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"get_run_output"` |
| `config_name` | string | Yes | Run configuration name |
| `last_n_lines` | integer | No | Lines from the end (default 200, max 1000) |
| `filter` | string | No | Regex pattern to filter output lines |

**Example Input:**
```json
{
  "action": "get_run_output",
  "config_name": "PaymentServiceApplication",
  "last_n_lines": 20,
  "filter": "ERROR|WARN"
}
```

**Example Output:**
```
Output for PaymentServiceApplication (last 20 lines, filter: ERROR|WARN):

2026-03-30 09:15:01 WARN  HikariPool — Pool full, waiting for connection
2026-03-30 09:15:03 ERROR PaymentService — NullPointerException in processRefund
2026-03-30 09:15:03 WARN  ExceptionHandler — Returning 500 for /api/payments/refund
```

---

#### `get_test_results`
**Purpose:** Get test results from the last test run.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"get_test_results"` |
| `config_name` | string | Yes | Run configuration name |
| `status_filter` | string | No | Filter by status: `FAILED`, `ERROR`, `PASSED`, `SKIPPED` |

**Example Input:**
```json
{
  "action": "get_test_results",
  "config_name": "PaymentServiceTest",
  "status_filter": "FAILED"
}
```

**Example Output:**
```
Test results for PaymentServiceTest (filter: FAILED):

Failed (1 of 15 total):

PaymentServiceTest.testProcessRefundNullCustomerRef
  java.lang.NullPointerException
    at com.example.PaymentService.processRefund(PaymentService.java:142)
  Duration: 0.1s
```

---

#### `run_tests`
**Purpose:** Run tests for a specific class or method using IntelliJ's test runner or Maven/Gradle.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"run_tests"` |
| `class_name` | string | Yes | Fully qualified test class name |
| `method` | string | No | Specific test method name |
| `timeout` | integer | No | Seconds before kill (default 300, max 900) |
| `use_native_runner` | boolean | No | Use IntelliJ native runner (true) or Maven/Gradle (false). Default: true |
| `description` | string | No | Brief description shown in approval dialog |

**Example Input:**
```json
{
  "action": "run_tests",
  "class_name": "com.example.PaymentServiceTest",
  "method": "testProcessRefund",
  "timeout": 60
}
```

**Example Output:**
```
Test run: PaymentServiceTest.testProcessRefund

Result: PASSED (0.3s)

1 test executed:
  PASSED: testProcessRefund (0.3s)
```

---

#### `compile_module`
**Purpose:** Compile a specific module or the entire project.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | Yes | `"compile_module"` |
| `module` | string | No | Module name (compiles entire project if omitted) |
| `description` | string | No | Brief description shown in approval dialog |

**Example Input:**
```json
{
  "action": "compile_module",
  "module": "payment-service"
}
```

**Example Output:**
```
Compilation: payment-service
Result: SUCCESS
Duration: 3.2s
Errors: 0 | Warnings: 2

Warnings:
  PaymentService.java:89 — Unchecked cast: Object to RefundResult
  PaymentMapper.java:12 — Deprecated API usage: ModelMapper.map()
```

---

## Common Workflows

### Code Review Workflow

Review a PR by examining changes, checking code quality, and leaving feedback.

```
1. bitbucket { action: "get_pr_detail", pr_id: "90" }
2. bitbucket { action: "get_pr_diff", pr_id: "90" }
3. sonar { action: "issues", project_key: "com.example:payment-service",
           file: "src/main/java/com/example/PaymentService.java" }
4. bitbucket { action: "add_inline_comment", pr_id: "90",
               file_path: "src/main/java/com/example/PaymentService.java",
               line: "142", line_type: "ADDED",
               text: "Consider logging the orderId before throwing" }
5. bitbucket { action: "set_reviewer_status", pr_id: "90",
               username: "agent", status: "APPROVED" }
```

### Debug a Failing Test

Investigate and fix a test failure using the interactive debugger.

```
1. runtime { action: "run_tests",
             class_name: "com.example.PaymentServiceTest",
             method: "testProcessRefund" }
2. debug { action: "add_breakpoint",
           file: "src/main/java/com/example/PaymentService.java",
           line: 142, condition: "customerRef == null" }
3. debug { action: "start_session",
           config_name: "PaymentServiceTest",
           wait_for_pause: 10 }
4. debug { action: "get_variables" }
5. debug { action: "evaluate", expression: "order.getCustomerReference()" }
6. debug { action: "step_over" }
7. debug { action: "get_state" }
8. debug { action: "stop" }
```

### Sprint Status Check

Get a comprehensive view of sprint progress, build health, and code quality.

```
1. jira { action: "get_boards", type: "scrum" }
2. jira { action: "get_sprints", board_id: "42" }
3. jira { action: "get_sprint_issues", sprint_id: "142" }
4. bamboo { action: "build_status", plan_key: "PAY-BUILD" }
5. sonar { action: "quality_gate",
           project_key: "com.example:payment-service" }
```

### Start Work on a Ticket

Pick up a ticket, create a branch, and set up the development environment.

```
1. jira { action: "get_ticket", key: "PAY-470" }
2. jira { action: "start_work", issue_key: "PAY-470",
          branch_name: "feature/PAY-470-refund-limits",
          source_branch: "master" }
3. spring { action: "endpoints", filter: "refund" }
4. spring { action: "jpa_entities", entity: "Refund" }
5. build { action: "module_dependency_graph", module: "payment-service" }
```

### Investigate Build Failure

Diagnose why a CI build failed and verify the fix locally.

```
1. bamboo { action: "build_status", plan_key: "PAY-BUILD" }
2. bamboo { action: "get_build_log", build_key: "PAY-BUILD-245" }
3. bamboo { action: "get_test_results", build_key: "PAY-BUILD-245" }
4. git { action: "show_commit", commit: "HEAD",
         include_diff: true }
5. runtime { action: "run_tests",
             class_name: "com.example.PaymentServiceTest",
             method: "testProcessRefund" }
6. sonar { action: "issues",
           project_key: "com.example:payment-service",
           file: "src/main/java/com/example/PaymentService.java" }
```

### Full PR Lifecycle

Create, review, and merge a pull request end-to-end.

```
1. git { action: "status" }
2. git { action: "diff", staged: true }
3. bitbucket { action: "create_pr",
               title: "PAY-456: Fix NPE in refund flow",
               pr_description: "...",
               from_branch: "feature/PAY-456",
               to_branch: "master" }
4. bitbucket { action: "add_reviewer", pr_id: "90",
               username: "jane.smith" }
5. bitbucket { action: "check_merge_status", pr_id: "90" }
6. bitbucket { action: "merge_pr", pr_id: "90",
               strategy: "squash",
               delete_source_branch: "true" }
7. jira { action: "transition", key: "PAY-456",
          transition_id: "31",
          comment: "Merged via PR #90" }
```

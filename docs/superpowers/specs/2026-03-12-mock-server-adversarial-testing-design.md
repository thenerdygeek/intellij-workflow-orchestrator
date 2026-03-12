# Mock Server — Adversarial Testing Design

## Purpose

A standalone Ktor-based mock server that simulates Jira, Bamboo, and SonarQube with **deliberately divergent data** to expose hardcoded assumptions in the plugin. This is not a happy-path mock — it is an adversarial testing tool that reveals where the plugin fails to dynamically adapt to real-world service variance.

## Goals

1. Enable full end-to-end UI testing of the plugin without access to real services
2. Expose hardcoded status names, state strings, severity levels, and metric keys
3. Test error handling for malformed responses, timeouts, and unexpected shapes
4. Provide stateful simulation so workflows (start work → build → quality check) progress realistically
5. Be completely independent from the plugin codebase — no shared DTOs, no shared constants

## Non-Goals

- Not a unit test framework (existing MockWebServer tests cover that)
- Not a production mock for CI (this is for manual IDE testing)
- Does not mock Bitbucket, Nexus, or Cody (deferred to later expansion)

---

## Architecture

### Module Structure

```
:mock-server
  ├── build.gradle.kts                    — Ktor dependencies, application plugin
  ├── src/main/kotlin/com/workflow/orchestrator/mockserver/
  │   ├── MockServerMain.kt              — CLI entry point, starts all 3 services
  │   ├── config/
  │   │   └── MockConfig.kt              — Ports, chaos mode toggle, response delays
  │   ├── jira/
  │   │   ├── JiraMockRoutes.kt          — REST API v2 route definitions
  │   │   ├── JiraState.kt               — In-memory mutable state (sprints, tickets, transitions)
  │   │   └── JiraDataFactory.kt         — Generates divergent test data on startup
  │   ├── bamboo/
  │   │   ├── BambooMockRoutes.kt        — REST API route definitions
  │   │   ├── BambooState.kt             — Build state machine with timed progression
  │   │   └── BambooDataFactory.kt       — Divergent build plans, stages, lifecycle states
  │   ├── sonar/
  │   │   ├── SonarMockRoutes.kt         — Web API route definitions
  │   │   ├── SonarState.kt              — Projects, issues, coverage data
  │   │   └── SonarDataFactory.kt        — Custom severities, metrics, quality gate conditions
  │   ├── chaos/
  │   │   └── ChaosMiddleware.kt         — Ktor plugin for random failures and delays
  │   └── admin/
  │       └── AdminRoutes.kt             — State inspection, reset, scenario switching, chaos control
  └── src/main/resources/
      └── application.conf               — Ktor HOCON configuration (ports, defaults)
```

### Key Design Decisions

1. **Standalone Gradle module** — Uses `application` plugin, runs via `./gradlew :mock-server:run`. The plugin has zero compile-time or runtime dependency on it.

2. **No shared DTOs** — The mock server defines its own response data classes independently. If a plugin DTO changes, the mock intentionally does NOT auto-update. Divergence is the point.

3. **Stateful, not static** — Each mock service holds mutable in-memory state. Ticket transitions mutate status. Build triggers start a timed progression. This enables testing real polling and multi-step workflows.

4. **Ktor framework** — Lightweight, Kotlin-native, embedded server. No need for Spring Boot or external containers. Each mock service runs as a separate Ktor module on its own port.

5. **Chaos as a Ktor plugin** — Installed as middleware, toggleable at runtime via admin API. Does not require restart.

### Ports

| Service | Port | Base Path |
|---|---|---|
| Jira Mock | 8180 | `/rest/api/2/`, `/rest/agile/1.0/` |
| Bamboo Mock | 8280 | `/rest/api/latest/` |
| SonarQube Mock | 8380 | `/api/` |

All three start from a single process. Ports are configurable via environment variables (`MOCK_JIRA_PORT`, `MOCK_BAMBOO_PORT`, `MOCK_SONAR_PORT`) or CLI args. Startup fails fast with a clear error if any port is already in use.

---

## Divergent Data Design

The mock server deliberately uses values that differ from what the plugin hardcodes. Each divergence targets a specific assumption found in the code audit.

### Jira Mock

#### Workflow States

The plugin assumes "In Progress" status name and `"indeterminate"` category key (`BranchingService.kt:62-63`). The mock uses entirely different names and categories:

| Status Name | Category Key | Category Name | ID |
|---|---|---|---|
| Open | `new` | To Do | 1 |
| WIP | `in_flight` | In Progress | 2 |
| Peer Review | `in_flight` | In Progress | 3 |
| QA Testing | `verification` | Verification | 4 |
| Approved | `done` | Done | 5 |
| Closed | `done` | Done | 6 |
| Blocked | `blocked` | Blocked | 7 |
| Investigating | `indeterminate` | In Progress | 8 |

**Targeted assumptions:**
- `BranchingService.kt:62` — `name.equals("In Progress")` → no match for "WIP", "Investigating", etc.
- `BranchingService.kt:63` — `statusCategory.key == "indeterminate"` → **matches "Investigating" only** (tests that the fallback path works when name differs but category key matches)
- `TicketListCellRenderer.kt:48-53` — Only handles `"new"`, `"indeterminate"`, `"done"` → `"in_flight"`, `"verification"`, `"blocked"` all fall to grey default

Note: The "Investigating" status deliberately uses `"indeterminate"` as its category key to test the plugin's fallback path where the status name doesn't match "In Progress" but the category key does match. This tests both the failure case (WIP with `"in_flight"`) and the fallback success case (Investigating with `"indeterminate"`).

#### Transitions with Requirements

Each transition includes a `fields` block specifying required fields. The plugin currently ignores these.

```json
{
  "id": "21",
  "name": "Move to Peer Review",
  "to": { "name": "Peer Review", "statusCategory": { "key": "in_flight" } },
  "fields": {
    "assignee": { "required": true },
    "comment": { "required": false }
  }
}
```

Available transitions per status:
- **Open** → WIP (requires assignee), Blocked
- **WIP** → Peer Review (requires comment), Blocked, Open
- **Peer Review** → QA Testing, WIP (rejection)
- **QA Testing** → Approved, WIP (rejection, requires comment)
- **Approved** → Closed
- **Blocked** → Open (requires comment explaining resolution)

#### Issue Types

Uses "Story", "Defect" (not "Bug"), "Spike", "Tech Debt" — diverges from standard Jira types.

#### Sprint Data

- Board ID: 42
- Sprint ID: 7
- Sprint name: "Sprint 2026.11"
- 6 tickets assigned to mock user, spread across statuses
- Tickets include cross-project issue links (blocked-by, relates-to)
- One ticket has no summary (empty string) — tests null/empty handling

#### Transition Field Validation

When the plugin POSTs a transition without required fields, the mock returns HTTP 400:

```json
{
  "errorMessages": [],
  "errors": {
    "assignee": "Assignee is required for this transition"
  }
}
```

The plugin's `JiraApiClient.transitionIssue()` sends only `{"transition":{"id":"..."}}` with no field values. This will surface the gap in required-field handling.

#### API Endpoints Implemented

| Method | Path | Plugin Usage |
|---|---|---|
| GET | `/rest/api/2/myself` | Test connection |
| GET | `/rest/agile/1.0/board?type=scrum` | Board discovery (required before sprint loading) |
| GET | `/rest/agile/1.0/board/{boardId}/sprint?state=active` | Active sprint discovery |
| GET | `/rest/agile/1.0/sprint/{sprintId}/issue` | Sprint ticket loading |
| GET | `/rest/api/2/issue/{key}?expand=issuelinks` | Ticket detail with links |
| GET | `/rest/api/2/issue/{key}/transitions` | Available transitions for ticket |
| POST | `/rest/api/2/issue/{key}/transitions` | Execute transition (validates required fields, mutates state) |
| POST | `/rest/api/2/issue/{key}/comment` | Add comment |
| POST | `/rest/api/2/issue/{key}/worklog` | Log time |

Note: The sprint issues path is `/rest/agile/1.0/sprint/{sprintId}/issue` (no board segment). The board discovery and active sprint endpoints are required because `SprintService` discovers these dynamically before loading issues.

### Bamboo Mock

#### Build Lifecycle States

The plugin hardcodes `"InProgress"`, `"Queued"`, `"Pending"` in `BuildState.kt:10-17` and `BambooApiClient.kt:95`. The mock uses different values:

| lifeCycleState | state | Plugin expects? | Maps to |
|---|---|---|---|
| `Running` | `null` | No (`"InProgress"` expected) | UNKNOWN |
| `Queued` | `null` | Yes | PENDING |
| `Finished` | `Successful` | Yes | SUCCESS |
| `Finished` | `Failed` | Yes | FAILED |
| `Finished` | `PartiallySuccessful` | No | UNKNOWN |
| `Cancelled` | `Cancelled` | No | UNKNOWN |

**Targeted assumptions:**
- `BuildState.kt:11` — `"InProgress"` check fails for `"Running"`
- `BambooApiClient.kt:95` — Filter for running builds misses `"Running"` state
- `BuildState.kt` — No handling for `"PartiallySuccessful"` or `"Cancelled"`

#### Dynamic Build Progression

When a build is triggered via POST, it progresses through states on a timer:

```
Queued → (10s) → Running → (30s) → Finished/Successful or Finished/Failed
```

Build outcome is deterministic based on plan key:
- Plan `PROJ-BUILD` → succeeds
- Plan `PROJ-TEST` → fails (with test failure details)
- Plan `PROJ-SONAR` → PartiallySuccessful (new state)

#### Stage Names

Uses non-standard stage names to test whether the UI adapts:
- "Compile & Package" (instead of artifact-focused naming)
- "Security Scan" (not in the standard 3-lane model)
- "Integration Tests" (instead of generic test naming)

Each stage has its own status and can fail independently.

#### Build Log

Returns realistic multi-line build logs with Maven output, test results, and timestamps. Includes ANSI color codes to test log rendering. Accessed via `?expand=logEntries` query parameter on the result endpoint (not a separate `/log` path).

#### API Endpoints Implemented

| Method | Path | Plugin Usage |
|---|---|---|
| GET | `/rest/api/latest/currentUser` | Test connection |
| GET | `/rest/api/latest/plan` | List all plans (used by `PlanDetectionService`) |
| GET | `/rest/api/latest/search/plans?searchTerm={term}` | Search plans by name |
| GET | `/rest/api/latest/plan/{key}/branch` | List plan branches |
| GET | `/rest/api/latest/plan/{key}/variable` | Get plan variables (used by `ManualStageDialog`) |
| GET | `/rest/api/latest/result/{planKey}/latest` | Latest build result |
| GET | `/rest/api/latest/result/{buildKey}` | Specific build with stages (supports `?expand=logEntries,variables`) |
| GET | `/rest/api/latest/result/{planKey}` | Running/queued builds (filtered by lifeCycleState) |
| POST | `/rest/api/latest/queue/{planKey}` | Trigger build (starts progression timer) |
| DELETE | `/rest/api/latest/queue/{resultKey}` | Cancel a queued/running build |

Note: Build logs and variables are accessed via `?expand=logEntries` and `?expand=variables` query parameters on the result endpoint, not via separate paths. The mock parses the `expand` query parameter and includes the requested data in the response.

### SonarQube Mock

#### Custom Severities

Standard severities plus a custom one:
- `BLOCKER`, `CRITICAL`, `MAJOR`, `MINOR`, `INFO` — standard
- `CRITICAL_SECURITY` — custom, not in plugin's `IssueMapper.kt:45-52`

**Targeted assumption:** `IssueMapper.parseSeverity()` falls back to `INFO` for `"CRITICAL_SECURITY"` — a critical security issue rendered as informational.

#### Quality Gate Status

Returns `"WARN"` in addition to `"OK"` and `"ERROR"`:

```json
{
  "projectStatus": {
    "status": "WARN",
    "conditions": [
      { "status": "OK", "metricKey": "coverage", "comparator": "LT", "errorThreshold": "80", "actualValue": "82.3" },
      { "status": "WARN", "metricKey": "security_rating", "comparator": "GT", "warningThreshold": "1", "actualValue": "3" },
      { "status": "ERROR", "metricKey": "new_coverage", "comparator": "LT", "errorThreshold": "80", "actualValue": "45.0" }
    ]
  }
}
```

**Targeted assumption:** `SonarDataService.kt:115-118` maps `"WARN"` to `NONE`, losing the warning signal entirely.

#### Missing and Extra Metrics

The plugin requests: `coverage,line_coverage,branch_coverage,uncovered_lines,uncovered_conditions` (hardcoded in `SonarApiClient.kt:68`).

The mock returns:
- `coverage` — present (82.3%)
- `line_coverage` — present (85.1%)
- `branch_coverage` — present (71.2%)
- `uncovered_lines` — present (42)
- `uncovered_conditions` — **omitted** (tests missing metric handling)
- `security_rating` — extra metric (tests whether plugin ignores or crashes)
- `reliability_rating` — extra metric

#### Custom Issue Type

Returns standard types plus `"SECURITY_AUDIT"`:
- `BUG`, `VULNERABILITY`, `CODE_SMELL`, `SECURITY_HOTSPOT` — standard
- `SECURITY_AUDIT` — custom, falls back to `CODE_SMELL` in `IssueMapper.kt:37-43`

#### Coverage Data

Returns per-file coverage with realistic line-level data. Some files have 100% coverage, some have 0%, most are mixed. Includes files from packages the plugin won't recognize (tests path matching).

#### API Endpoints Implemented

| Method | Path | Plugin Usage |
|---|---|---|
| GET | `/api/authentication/validate` | Test connection (sometimes returns `{"valid": false}` in chaos mode) |
| GET | `/api/projects/search` | Project discovery (used by `ProjectKeyDetectionService`) |
| GET | `/api/measures/component_tree` | Coverage data per file |
| GET | `/api/sources/lines` | Source line coverage data |
| GET | `/api/issues/search` | Open issues with severity, type, location |
| GET | `/api/qualitygates/project_status` | Quality gate status with conditions |

Note: Extra fields in responses will be silently ignored by the plugin due to `ignoreUnknownKeys = true`. The extra metrics (`security_rating`, `reliability_rating`) test this — they will not crash but will be silently dropped.

---

## Chaos Mode

A Ktor interceptor plugin that, when enabled, randomly corrupts a configurable percentage of responses.

### Failure Types

| Type | Weight | Description |
|---|---|---|
| Malformed JSON | 20% | Truncates response body mid-object (tests unhandled `SerializationException`) |
| Slow response | 15% | Adds 10-15s delay before responding (tests timeout handling) |
| Timeout response | 5% | Adds 35s delay, exceeding plugin's 30s `readTimeout` (tests actual timeout) |
| HTTP 429 | 10% | Returns `Too Many Requests` with `Retry-After` header (tests retry interceptor) |
| HTTP 500 | 15% | Returns `{"error": "Internal Server Error"}` |
| HTTP 503 | 15% | Returns `Service Unavailable` as plain text |
| Empty body | 10% | Returns 200 OK with empty body (tests `decodeFromString("")` crash path) |
| Wrong Content-Type | 10% | Returns `text/html` with `<html>Session expired</html>` (tests content type validation) |

### Configuration

- Default failure rate: 20% of requests
- Configurable via admin API: `POST /__admin/chaos?rate=0.3`
- Disabled by default on startup
- Per-service toggle: can enable chaos on Jira only while Bamboo stays stable
- Admin endpoints (`/__admin/*`) are never affected by chaos

---

## Admin API

Each mock service exposes admin endpoints on its own port. Admin operations are **per-service** — resetting on port 8180 resets only Jira state. The exception is scenario loading: cross-service scenarios (like `default` and `happy-path`) reset all services when called on any port.

| Method | Path | Description |
|---|---|---|
| GET | `/__admin/state` | JSON dump of this service's current state |
| POST | `/__admin/reset` | Reset this service to initial factory data |
| POST | `/__admin/chaos?enabled={bool}` | Enable/disable chaos mode |
| POST | `/__admin/chaos?rate={float}` | Set chaos failure rate (0.0 to 1.0) |
| POST | `/__admin/scenario/{name}` | Load a predefined scenario |
| GET | `/__admin/scenarios` | List available scenarios |
| GET | `/__admin/requests` | Last 50 requests received (method, path, timestamp, response code) |

---

## Predefined Scenarios

Loadable via `POST /__admin/scenario/{name}`. Each scenario resets state and loads specific data.

| Scenario | Service | Description | Tests |
|---|---|---|---|
| `default` | All | Standard divergent data as described above | General assumption exposure |
| `happy-path` | All | Standard Jira statuses, expected Bamboo states, OK quality gate | Baseline — plugin should work |
| `all-builds-failing` | Bamboo | Every build is `Finished/Failed` with error logs | Notification spam, UI when everything red |
| `quality-gate-warn` | SonarQube | `WARN` status with mixed conditions | WARN handling gap |
| `empty-sprint` | Jira | No tickets assigned to current user | Empty state UI rendering |
| `large-sprint` | Jira | 50+ tickets across all statuses | Performance, scrolling, rendering |
| `transition-blocked` | Jira | All transitions require multiple fields | Transition requirement handling |
| `metrics-missing` | SonarQube | Returns only `coverage`, omits all other metrics | Null/missing field handling |
| `build-progression` | Bamboo | 3 builds at different stages, progressing in real-time | Polling and state update rendering |
| `paginated-results` | All | Returns paginated responses with `startAt`, `maxResults`, `isLast` | Tests whether plugin handles multi-page results or assumes single page |
| `no-active-sprint` | Jira | All sprints are `closed` or `future`, none `active` | Tests "No active sprint found" error path in `SprintService` |
| `auth-invalid` | SonarQube | `/api/authentication/validate` returns `{"valid": false}` | Tests handling of authenticated but invalid session |

---

## Authentication

All three mocks accept any non-empty token:

- **Jira/Bamboo**: `Authorization: Bearer <any-non-empty-string>` → 200
- **SonarQube**: `Authorization: Bearer <any-non-empty-string>` → 200
- Empty or missing auth header → 401 `{"message": "Authentication required"}`

This tests that the plugin sends auth headers correctly without requiring real credentials.

---

## Usage Workflow

### Starting the Mock Server

```bash
./gradlew :mock-server:run
```

Output:
```
╔══════════════════════════════════════════════════╗
║          Workflow Orchestrator Mock Server        ║
╠══════════════════════════════════════════════════╣
║  Jira      → http://localhost:8180               ║
║  Bamboo    → http://localhost:8280               ║
║  SonarQube → http://localhost:8380               ║
║                                                  ║
║  Chaos mode: OFF                                 ║
║  Scenario:   default (adversarial)               ║
║                                                  ║
║  Admin:  GET /__admin/state on any port          ║
║  Reset:  POST /__admin/reset on any port         ║
╚══════════════════════════════════════════════════╝

Jira:  6 tickets in Sprint 2026.11, 7 workflow states
Bamboo: 3 plans, 2 recent builds
SonarQube: 1 project, 12 issues, quality gate WARN
```

### Configuring the Plugin

In IntelliJ → Settings → Tools → Workflow Orchestrator → Connections:
- Jira URL: `http://localhost:8180`, PAT: `mock-token`
- Bamboo URL: `http://localhost:8280`, PAT: `mock-token`
- SonarQube URL: `http://localhost:8380`, Token: `mock-token`

### Testing Scenarios

1. **Test Connection** — All three should return success
2. **Open Sprint tab** — See 6 tickets with unfamiliar statuses and potentially wrong colors
3. **Click "Start Work"** — Transition lookup fails (no "In Progress" found)
4. **Open Build tab** — Builds with "Running" state may show as UNKNOWN
5. **Trigger a build** — Watch it progress through Queued → Running → Finished over 40s
6. **Open Quality tab** — Quality gate shows WARN mapped to NONE
7. **Check gutter markers** — "CRITICAL_SECURITY" severity rendered as INFO
8. **Enable chaos** — `curl -X POST localhost:8180/__admin/chaos?enabled=true`
9. **Continue using plugin** — ~20% of requests fail, testing error handling
10. **Switch scenario** — `curl -X POST localhost:8180/__admin/scenario/empty-sprint`

---

## Known Plugin Assumptions to Verify

This is the checklist of hardcoded assumptions the mock server is designed to expose. Each should be verified during testing and tracked as issues to fix.

| # | File | Line | Assumption | Mock Divergence | Expected Failure |
|---|---|---|---|---|---|
| 1 | `BranchingService.kt` | 62 | Status name is "In Progress" | Uses "WIP" | "Start Work" transition not found |
| 2 | `BranchingService.kt` | 63 | Category key is "indeterminate" | Uses "in_flight" (but "Investigating" uses "indeterminate" to test fallback success) | Fallback fails for WIP, succeeds for Investigating |
| 3 | `TicketListCellRenderer.kt` | 48-53 | Only 3 category keys exist | 6 category keys | Unknown categories render grey |
| 4 | `BuildState.kt` | 11 | `lifeCycleState == "InProgress"` | Returns "Running" | Running builds show as UNKNOWN |
| 5 | `BambooApiClient.kt` | 95 | Filter list: InProgress, Queued, Pending | "Running" not in list | Running builds excluded from dashboard |
| 6 | `SonarDataService.kt` | 115-118 | Only "OK" and "ERROR" gate status | Returns "WARN" | Warning mapped to NONE |
| 7 | `IssueMapper.kt` | 37-43 | 4 known issue types | Returns "SECURITY_AUDIT" | Mapped to CODE_SMELL |
| 8 | `IssueMapper.kt` | 45-52 | 5 known severities | Returns "CRITICAL_SECURITY" | Mapped to INFO |
| 9 | `SonarApiClient.kt` | 68 | All 5 metrics always present | Omits `uncovered_conditions` | Potential null/crash |
| 10 | `JiraApiClient.kt` | 69 | Deserialization never fails | Chaos: malformed JSON | Unhandled SerializationException |
| 11 | `BambooApiClient.kt` | 132 | Deserialization never fails | Chaos: malformed JSON | Unhandled SerializationException |
| 12 | `SonarApiClient.kt` | 99 | Deserialization never fails | Chaos: malformed JSON | Unhandled SerializationException |
| 13 | `BuildDashboardPanel.kt` | 137 | Default branch is "master" | N/A (Git-local) | N/A |
| 14 | `SonarDataService.kt` | 40 | Default branch is "main" | N/A (Git-local) | N/A |
| 15 | `JiraApiClient.kt` | 56 | Transitions don't require fields | Mock returns required fields block | POST transition returns 400 when fields missing |
| 16 | All API clients | — | 200 response always has a body | Chaos: empty body on 200 | `decodeFromString("")` throws SerializationException |
| 17 | All API clients | — | Single-page results | `paginated-results` scenario | Plugin may only see first page of data |

---

## Build Configuration

### Dependencies (mock-server/build.gradle.kts)

```kotlin
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

application {
    mainClass.set("com.workflow.orchestrator.mockserver.MockServerMainKt")
}

kotlin {
    jvmToolchain(21) // Match root project JVM target
}

dependencies {
    // Ktor server
    implementation("io.ktor:ktor-server-core:3.1.1")
    implementation("io.ktor:ktor-server-netty:3.1.1")
    implementation("io.ktor:ktor-server-content-negotiation:3.1.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.1")
    implementation("io.ktor:ktor-server-cors:3.1.1")
    implementation("io.ktor:ktor-server-call-logging:3.1.1")
    implementation("io.ktor:ktor-server-status-pages:3.1.1")

    // Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Coroutines (for timed build progression)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.6")
}
```

### settings.gradle.kts Addition

```kotlin
include(":mock-server")
```

The mock-server is included in the Gradle project but is NOT added as a dependency of the root plugin — it exists purely as a standalone runnable module.

---

## Future Expansion

When needed, additional mock services can be added:
- **Bitbucket Mock** — PR creation, review workflows
- **Nexus Docker Registry Mock** — Tag listing, manifest HEAD checks (uses Basic auth, not Bearer)
- **Cody Mock** — JSON-RPC over stdio simulation (more complex, may need a different approach)

Each follows the same pattern: `{service}/` package with Routes, State, and DataFactory.

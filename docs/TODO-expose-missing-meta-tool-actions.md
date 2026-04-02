# TODO: Cleanup & Architecture Fixes

Identified during dead code cleanup (2026-04-02). These core service methods exist and work
but are not yet exposed to the AI agent via meta-tool actions.

## 1. JiraTool — `download_attachment` action

**Service method:** `JiraService.downloadAttachment(issueKey, attachmentId): ToolResult<AttachmentContentData>`

**What it does:** Downloads a Jira attachment and returns its content + saves to disk.

**Return type (`AttachmentContentData`):**
- `filename: String` — original filename
- `mimeType: String?` — MIME type
- `sizeBytes: Long` — file size
- `content: String?` — text content (for text-based files)
- `filePath: String` — local path where file was saved
- `attachmentId: String`

**Implementation notes:**
- Add `"download_attachment"` to JiraTool's action enum
- Parameters: `key` (issue key) + `attachment_id`
- The LLM can use this to read attached specs, logs, configs, CSVs from tickets
- For binary attachments (images, ZIPs), `content` will be null — return `filePath` so the
  agent can reference it or read it with `read_file`
- Consider a size guard: skip content extraction for files > 1MB

## 2. JiraTool — `search_tickets` action (raw JQL)

**Service method:** `JiraService.searchTickets(jql, maxResults): ToolResult<List<JiraTicketData>>`

**What it does:** Runs an arbitrary JQL query against Jira. Unlike the existing `search_issues`
action (which auto-builds JQL from free text scoped to current user), this gives the LLM full
JQL power.

**How it differs from existing `search_issues`:**
| | `search_issues` (exists) | `search_tickets` (to add) |
|---|---|---|
| Input | Free text (`"login bug"`) | Raw JQL (`"project = PROJ AND fixVersion = 3.0"`) |
| API call | `searchIssues(text)` → auto-builds JQL with `text ~ "..." AND assignee = currentUser()` | `searchByJql(jql)` → direct passthrough |
| Scope | Current user only | Any query |
| Default max | 20 | 8 |
| Use case | Quick lookup | Complex/cross-project queries |

**Implementation notes:**
- Add `"search_tickets"` to JiraTool's action enum
- Parameters: `jql` (required), `max_results` (optional, default 8)
- The LLM benefits from both: `search_issues` for "find my login bugs" and
  `search_tickets` for "project = PROJ AND status changed after 2026-03-01"

## 3. BambooBuildsTool — `download_artifact` action

**Service method:** `BambooService.downloadArtifact(artifactUrl, targetFile): ToolResult<Boolean>`

**What it does:** Downloads a build artifact from Bamboo to a local file.

**Current signature:** Takes a URL + File, returns success/failure boolean.

**Implementation notes:**
- Add `"download_artifact"` to BambooBuildsTool's action enum
- Parameters: `artifact_url` (from `get_artifacts` action output) + optional `target_path`
- The LLM workflow: `get_artifacts` → pick artifact → `download_artifact` → `read_file` on result
- Default target: temp directory under agent storage
- Consider wrapping: after download, if the file is text-based (< 500KB), include content
  preview in the response so the LLM doesn't need a second `read_file` call
- Return the local file path in the response so the agent can reference it

## 4. Architecture violation: `:automation` imports from `:bamboo` directly

**Found during cleanup (2026-04-02).** Pre-existing, not caused by this cleanup.

**The problem:** The `:automation` module has `implementation(project(":bamboo"))` in its
`build.gradle.kts` and imports directly from `com.workflow.orchestrator.bamboo.api` and
`com.workflow.orchestrator.bamboo.service`:

```
automation/src/main/kotlin/.../ui/MonitorPanel.kt        → BambooApiClient, BambooResultDto, BambooServiceImpl, BambooTestResultConverter
automation/src/main/kotlin/.../ui/AutomationPanel.kt     → BambooServiceImpl
automation/src/main/kotlin/.../settings/CiCdConfigurable.kt → BambooApiClient
automation/src/main/kotlin/.../service/QueueService.kt   → BambooApiClient
automation/src/main/kotlin/.../service/ConflictDetectorService.kt → BambooApiClient
automation/src/main/kotlin/.../service/TagBuilderService.kt → BambooApiClient
```

Tests also import Bamboo DTOs (`BambooQueueResponse`, `BambooResultDto`, `BambooStageCollection`, etc.).

**Why it violates the architecture:** The dependency rule states feature modules depend ONLY on
`:core`. Cross-module communication should use `EventBus` (`SharedFlow<WorkflowEvent>`) or core
service interfaces. `:automation` bypasses this by importing `:bamboo`'s API client and DTOs directly.

**Impact:**
- `:automation` is tightly coupled to `:bamboo`'s internal implementation (API client, DTOs)
- If `:bamboo`'s `BambooApiClient` changes, `:automation` breaks
- The agent cannot access automation's Bamboo-dependent logic because it only sees `:core`

**Fix approach:**
1. Move the Bamboo DTOs that `:automation` needs into `:core/model/bamboo/` (or create shared DTOs)
2. Add any Bamboo service methods `:automation` needs to `core/services/BambooService.kt`
3. Replace `BambooApiClient` usage in automation with `BambooService` calls
4. Remove `implementation(project(":bamboo"))` from automation's `build.gradle.kts`
5. Update tests to use mocked `BambooService` instead of `BambooApiClient`

## 5. Dissolve `:cody` module (deprecated, architecturally misplaced)

Identified during dead code cleanup (2026-04-02). The `:cody` module is deprecated but still has
3 alive, functional Kotlin files. They should be moved to appropriate modules, then `:cody` deleted.

**Current state:** All 3 files are functional and registered in plugin.xml. The module name is
misleading ("cody" when Cody CLI is deprecated), and it has two architecture violations:
`:cody` depends on `:sonar` (for SonarIssueAnnotator/MappedIssue) and `:core`/`:handover` access
`PsiContextEnricher` via unsafe reflection.

**Refactoring plan:**

### 5a. Move `PsiContextEnricher` → `:core`
- Currently: `cody/src/main/kotlin/.../cody/service/PsiContextEnricher.kt`
- Move to: `core/src/main/kotlin/.../core/psi/PsiContextEnricher.kt`
- Eliminates reflection from `core/bitbucket/PrService.kt` (line 146) and
  `handover/service/PreReviewService.kt` (line 29) — both currently use `Class.forName()`
- Move test: `cody/src/test/.../PsiContextEnricherTest.kt` → `core/src/test/`

### 5b. Move `CodyIntentionAction` → `:sonar`
- Currently: `cody/src/main/kotlin/.../cody/editor/CodyIntentionAction.kt`
- Move to: `sonar/src/main/kotlin/.../sonar/editor/SonarFixIntentionAction.kt` (rename)
- It's fundamentally a SonarQube feature (fixes Sonar issues at caret using LLM)
- Imports `MappedIssue` and `SonarIssueAnnotator` from `:sonar` — moving it there fixes the violation
- Move resources: `cody/src/main/resources/intentionDescriptions/CodyIntentionAction/` → sonar
- Move test: `cody/src/test/.../CodyIntentionActionTest.kt` → sonar
- Update plugin.xml `<intentionAction>` registration with new class path

### 5c. Move `GenerateCommitMessageAction` → `:core`
- Currently: `cody/src/main/kotlin/.../cody/vcs/GenerateCommitMessageAction.kt`
- Move to: `core/src/main/kotlin/.../core/vcs/GenerateCommitMessageAction.kt`
- Only depends on `:core` types + Git4Idea (no cross-module deps)
- Update plugin.xml `<action>` registration with new class path
- After PsiContextEnricher moves to :core, the import becomes direct (no reflection needed)

### 5d. Delete `:cody` module
- Remove from `settings.gradle.kts`
- Remove from root `build.gradle.kts`
- Delete `cody/` directory
- Update CLAUDE.md module list

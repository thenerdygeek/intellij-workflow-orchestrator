# PR Creation Dialog Redesign

## Problem

The current PR creation is an inline form in the Build tab's PrBar — cramped, no markdown preview, no reviewer selection, no ticket transition, no target branch choice. PR creation is a significant action that deserves a proper dialog.

## Solution

Replace the inline form with a modal dialog opened by the "Create PR" button. The dialog provides:

1. Source branch (read-only) + target branch (dropdown)
2. Cody-generated title and markdown description with Edit/Preview tabs
3. Reviewer selection
4. Ticket transition checkbox with status dropdown
5. Create PR button that creates the PR and optionally transitions the Jira ticket

## Dialog Layout

```
┌─ Create Pull Request — PROJ-123 ────────────────── ✕ ─┐
│                                                        │
│  Source:  feature/PROJ-123-fix-order-service            │
│  Target:  [ develop                            ▾ ]     │
│  ───────────────────────────────────────────────────    │
│  Title:   [ PROJ-123: Fix null pointer in order... ]   │
│                                                        │
│  Description                          [⟳ Regenerate]   │
│  [Edit] [Preview]                                      │
│  ┌──────────────────────────────────────────────────┐  │
│  │ ## Summary                                       │  │
│  │ Fixed NPE in `OrderService.getOrder()`...        │  │
│  │                                                  │  │
│  │ ## Changes                                       │  │
│  │ - Added null check in `OrderService`...          │  │
│  │ - Updated `OrderController` to return 404...     │  │
│  │ ...                                              │  │
│  └──────────────────────────────────────────────────┘  │
│                                                        │
│  Reviewers: [john.doe ✕] [jane.smith ✕] [+ Add]       │
│  ───────────────────────────────────────────────────    │
│  ☑ Transition PROJ-123 to [ In Review          ▾ ]     │
│                                                        │
│                          [ Cancel ]  [ Create PR ]     │
└────────────────────────────────────────────────────────┘
```

## Components

### Source branch
Read-only label showing current Git branch name.

### Target branch
Searchable text field using `TextFieldWithAutoCompletion`. Pre-populated with `PluginSettings.state.defaultTargetBranch` (e.g., `develop`). Clicking the field shows all remote branches; typing filters the list in real-time. Branches fetched from `BitbucketBranchClient.getBranches()` and cached for the dialog session.

If many branches exist (100+), the filtered list keeps the UI responsive — only matching branches are rendered. The field shows a 🔍 icon to indicate searchability.

### Title
Editable `JBTextField`. Auto-populated:
- **With Cody**: `chatNew()` + `chatSubmitMessage()` with ticket title + commit messages as context → generates a concise PR title
- **Without Cody**: `PrService.buildPrTitle()` using the pattern `{ticketId}: {summary}`

### Description (Edit/Preview tabs)
Two-tab panel using `CardLayout`:

**Edit tab**: `JBTextArea` with raw markdown text. Editable. Monospace font.

**Preview tab**: `JEditorPane` with `HTMLEditorKit` rendering the markdown as HTML. Read-only. Converts markdown to HTML on tab switch using a simple markdown-to-HTML converter (headings, bold, code, lists, links).

**"⟳ Regenerate" button**: Re-runs the Cody prompt chain and replaces Edit tab content.

**What Bitbucket receives**: The raw markdown from the Edit tab. Bitbucket Server renders markdown natively.

### Description generation — Cody prompt chain

All context sent as `contextItems` (higher token budget). Prompt text contains only instructions.

| Step | Data gathered | How |
|---|---|---|
| 1. Jira ticket | Key, title, description, issue type | `JiraApiClient.getIssue(activeTicketId)` |
| 2. Commit messages | All commits on branch since divergence from target | `git log target..source --oneline` via `Git.log()` |
| 3. Changed files | Files with modified line ranges | `ChangeListManager.allChanges` → `ContextFile.fromPath()` with ranges |
| 4. Module + Spring info | Affected Maven modules, `@RestController` endpoints | `PrService.buildEnrichedDescription()` via reflection |
| 5. Diff stats | Files changed, insertions, deletions | `git diff --stat target..source` via `Git.diff()` |

**Prompt structure:**

```
Generate a pull request description in markdown for a Spring Boot project.

Use this structure:
## Summary
2-3 sentences: what changed and why.

## Changes
Bullet list of specific changes.

## Affected Modules
List modules.

## Testing
What tests were added/modified. Use ✅ checkmarks.

## Jira
Link to the ticket.

Rules:
- Be concise and professional
- Focus on business impact, not implementation details
- Highlight breaking changes if any
- Use code formatting for class/method names
```

**Fallback (no Cody):**
```markdown
## Commits
- commit message 1
- commit message 2
- ...

## Jira
[PROJ-123](https://jira.example.com/browse/PROJ-123)
```

### Reviewers
Tag-chip input field with live autocomplete from Bitbucket users API.

**Pre-populated**: Default reviewers from `PluginSettings.state.prDefaultReviewers` shown as tag chips.

**Adding reviewers**: User types directly in the field after the existing chips. After 300ms debounce, queries `GET /rest/api/1.0/users?filter=<text>` and shows matching users in a dropdown with username, display name, and email. Selecting adds a tag chip. Cursor stays in field for adding more.

**Removing reviewers**: Click ✕ on any chip to remove.

**API**: Add `getUsers(filter: String)` method to `BitbucketBranchClient` in `:core`. Results cached for the dialog session to avoid repeated API calls for the same prefix.

Sent as `List<BitbucketReviewer>` in the PR creation API call.

### Ticket transition
- Checkbox: "Transition PROJ-123 to [dropdown]"
- Dropdown populated from `JiraApiClient.getTransitions(issueKey)` — shows available transitions for current status
- Default selection: first transition matching `WorkflowIntent.SUBMIT_FOR_REVIEW` names ("In Review", "Peer Review", etc.)
- Checked by default
- When unchecked, dropdown is disabled

### Create PR button
On click:
1. Validate: title not empty, target branch selected
2. Disable button, show "Creating..."
3. `BitbucketBranchClient.createPullRequest()` on `Dispatchers.IO`
4. If transition checkbox checked: `JiraApiClient.transitionIssue()` on `Dispatchers.IO`
5. On success: emit `PullRequestCreated` event, close dialog, refresh PrBar
6. On error: show error message in dialog, keep dialog open

## Data Flow

```
PrBar "Create PR" clicked
  → Fetch in parallel (background):
    - Remote branches (BitbucketBranchClient.getBranches)
    - Jira ticket details (JiraApiClient.getIssue)
    - Available transitions (JiraApiClient.getTransitions)
    - Commit messages (Git.log)
  → Show dialog on EDT with loading state for description
  → Launch Cody description generation (background)
  → User edits title/description, selects target, reviewers, transition
  → "Create PR" clicked
    → Create PR on Bitbucket (background)
    → Transition Jira ticket if checked (background)
    → Close dialog, refresh PrBar
```

## Architecture

### Cross-module access — interfaces in `:core`

Instead of reflection, define thin interfaces in `:core` that feature modules implement:

```kotlin
// core/workflow/JiraTicketProvider.kt
interface JiraTicketProvider {
    suspend fun getTicketDetails(ticketId: String): TicketDetails?
    suspend fun getAvailableTransitions(ticketId: String): List<TicketTransition>
    suspend fun transitionTicket(ticketId: String, transitionId: String): Boolean
}

data class TicketDetails(val key: String, val summary: String, val description: String?, val type: String?)
data class TicketTransition(val id: String, val name: String, val targetStatus: String)

// core/ai/TextGenerationService.kt
interface TextGenerationService {
    suspend fun generateText(prompt: String, contextItems: List<ContextFile> = emptyList()): String?
}
```

`:jira` implements `JiraTicketProvider`, `:cody` implements `TextGenerationService`. Both registered as extension points in plugin.xml. `:bamboo` accesses them via `EP_NAME.extensionList` with no compile-time dependency on either module.

### Module placement

The dialog lives in `:bamboo` (triggered from PrBar). It uses:
- `:core` — `BitbucketBranchClient`, `PrService`, `CredentialStore`, `PluginSettings`, `EventBus`, `JiraTicketProvider` (interface), `TextGenerationService` (interface)
- `:jira` — implements `JiraTicketProvider` (no import needed from `:bamboo`)
- `:cody` — implements `TextGenerationService` (no import needed from `:bamboo`)

### New files

| File | Description |
|---|---|
| `core/workflow/JiraTicketProvider.kt` | Interface + DTOs for cross-module Jira access |
| `core/ai/TextGenerationService.kt` | Interface for cross-module AI text generation |
| `bamboo/ui/CreatePrDialog.kt` | DialogWrapper subclass with all UI components |
| `bamboo/service/PrDescriptionGenerator.kt` | Prompt chain + fallback logic, uses `TextGenerationService` |
| `bamboo/service/MarkdownToHtml.kt` | Markdown → HTML converter for Preview tab |
| `jira/service/JiraTicketProviderImpl.kt` | Implements `JiraTicketProvider` using `JiraApiClient` |
| `cody/service/CodyTextGenerationService.kt` | Implements `TextGenerationService` using `CodyChatService` |

### Modified files

| File | Change |
|---|---|
| `bamboo/ui/PrBar.kt` | "Create PR" button opens `CreatePrDialog` instead of inline form |
| `core/bitbucket/BitbucketBranchClient.kt` | Add `getUsers(filter)` method for reviewer autocomplete |
| `src/main/resources/META-INF/plugin.xml` | Register extension points + implementations |

### Removed from PrBar
The expandable inline form (CARD_FORM state) is removed. PrBar keeps only 3 states: no PR (with "Create PR" button), single PR info bar, multiple PR dropdown. The button opens the dialog instead of expanding inline.

## Markdown to HTML conversion

Regex-based converter handling the subset Bitbucket renders:
- `## Heading` → `<h2>Heading</h2>`
- `**bold**` → `<b>bold</b>`
- `` `code` `` → `<code>code</code>`
- `- item` → `<li>item</li>` (with `<ul>` wrapping)
- `[text](url)` → `<a href="url">text</a>`
- Fenced code blocks (triple backtick) → `<pre><code>` (extracted first, skipped during markdown processing)
- Newlines → `<br>` or `<p>` tags
- Checkmarks: use `&#10004;` HTML entity instead of emoji (renders on all platforms)

Preview pane (`JEditorPane`):
- Wrap in `JBScrollPane`
- Use `JBColor` for text/background (theme-aware)
- Add `HyperlinkListener` for clickable links → `BrowserUtil.browse()`

## Edge Cases

- **No active ticket**: Title from branch name, description from commits only, transition section hidden
- **Cody not available**: Title from `PrService.buildPrTitle()`, description from commit messages, no regenerate button
- **No Jira configured**: Transition section hidden entirely
- **PR already exists for branch** (409): Show error "PR already exists" with link to existing PR
- **Target branch same as source**: Validation error
- **Empty commit history**: Description shows "No commits on this branch"
- **Dialog opened while Cody generates**: Description area shows spinner, user can edit title/target/reviewers while waiting
- **Unpushed commits**: Before creating PR, check if branch is pushed. If not, show warning "Branch has unpushed commits — push first" and disable Create PR button
- **Large diffs (50+ files)**: Cap context items at 20 files (sorted by modification time, most recent first) to stay within Cody's context window
- **Slow branch fetch**: Show dialog immediately with `defaultTargetBranch` pre-selected in dropdown. Populate full list when fetch completes.

## Threading

- Dialog opens on EDT
- All API calls (branches, ticket, transitions, commits) on `Dispatchers.IO` — prefetched in parallel before dialog shows
- Dialog shows immediately with available data; slow fetches populate fields as they complete
- Cody generation on `Dispatchers.IO` — result applied on EDT via `invokeLater`
- PR creation + Jira transition on `Dispatchers.IO` — result applied on EDT
- Markdown-to-HTML conversion is synchronous (fast, in-memory) on EDT when switching tabs

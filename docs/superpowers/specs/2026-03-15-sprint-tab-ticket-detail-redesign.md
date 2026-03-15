# Sprint Tab & Ticket Detail Redesign

## Overview

Three interconnected changes:
1. Sprint tab left panel: "Current Work" section + multi-section ticket list
2. Ticket detail panel: rich view with all Jira fields, comments, subtasks, attachments, transitions
3. Cross-cutting: ticket key hyperlinking with API validation, transition dialog with mandatory fields

## 1. Sprint Tab Left Panel — Multi-Section Layout

### Sections (top to bottom)

```
┌─────────────────────────┐
│ CURRENTLY WORKING ON    │  ← Active ticket card (green bg)
│ PROJ-123: Fix order NPE │
│ 🔀 feature/PROJ-123     │
│ In Progress • 2h today  │
├─────────────────────────┤
│ RELATED TICKETS (3)     │  ← Tickets referenced in current ticket
│ PROJ-120 Upgrade Spring │     (from links, description, comments)
│ PROJ-130 Deploy order v2│     that aren't in the sprint
│ PROJ-50  Order Epic     │
├─────────────────────────┤
│ SPRINT 42 — 12 tickets  │  ← Current sprint tickets
│ ████████░░ 75%          │
│ 🔍 Filter...            │
│ PROJ-123 ● Fix order NPE│
│ PROJ-124 ○ Add retry    │
│ PROJ-125 ○ Update valid │
├─────────────────────────┤
│ OTHER (1)               │  ← Tickets opened via click that
│ INFRA-45 Update Jenkins │     aren't in sprint or related
└─────────────────────────┘
```

### Current Work section
- Shows the active ticket (from `ActiveTicketService`)
- Displays: ticket key + summary, branch name, status badge, time spent today, commit count
- Green-tinted background to stand out
- Empty state: "No active ticket — Select a ticket and click Start Work"
- Auto-updates on: branch change, commit, ticket transition

### Related Tickets section
- Populated when a ticket is selected or when the active ticket loads
- Sources: issue links (dependencies), subtask parents, ticket keys found in description/comments
- Only shows tickets NOT in the sprint list (avoids duplication)
- Collapsible, hidden when empty

### Sprint Tickets section
- Current sprint list (existing functionality)
- Sprint name, progress bar, ticket count
- Search/filter field
- Each ticket shows: status dot, key, summary

### Other section
- Tickets opened via hyperlink click from description/comments that aren't in sprint or related
- Session-scoped (cleared on IDE restart)
- Collapsible, hidden when empty

## 2. Ticket Detail Panel — Rich View

### Section order

1. **Header**: key (hyperlinked) + summary + status/priority/type tags
2. **Actions**: Transition button (dropdown) + "Open in Jira ↗" link
3. **Details grid** (2-column): Assignee, Reporter, Reviewer, Tester, Sprint, Dates
4. **Labels / Components / Epic**: color-coded chips
5. **Description**: rendered Jira content with hyperlinked ticket keys
6. **Subtasks**: list with status, hyperlinked key, summary, status badge
7. **Dependencies**: blocked-by / blocks with hyperlinked keys
8. **Attachments**: file cards with icon, name, size, date
9. **Comments**: threaded with avatar, author, relative time, rendered content with hyperlinked keys

### New Jira fields required

These fields need to be added to the sprint issue fetch (via `?fields=` parameter or `?expand=`):

| Field | Jira API field | Type |
|---|---|---|
| Subtasks | `subtasks` | Array of issue stubs |
| Labels | `labels` | Array of strings |
| Components | `components` | Array of `{name}` |
| Epic link | `customfield_10014` (typical, configurable) | String (epic key) |
| Reviewer | `customfield_*` (varies per Jira instance) | User object |
| Tester | `customfield_*` (varies per Jira instance) | User object |
| Comments | Separate API: `GET /rest/api/2/issue/{key}/comment` | Paginated |
| Attachments | `attachment` field | Array of attachment objects |

### Custom fields handling

Reviewer, Tester, and Epic Link field IDs vary per Jira instance. Add settings:

```
Settings > Tools > Workflow Orchestrator > Advanced
  Epic Link Field ID: [customfield_10014]
  Reviewer Field ID:  [customfield_10050]
  Tester Field ID:    [customfield_10051]
```

Pre-populate with common defaults. User can find their field IDs in Jira admin.

### Custom field deserialization

`JiraIssueFields` uses `kotlinx.serialization` with fixed property names. Dynamic custom fields (`customfield_10014`, etc.) cannot be mapped to named properties.

**Approach:** Add a `JsonObject` overflow field to capture custom fields:

```kotlin
@Serializable
data class JiraIssueFields(
    // ... standard fields ...
    val labels: List<String> = emptyList(),
    val components: List<JiraComponent> = emptyList(),
    val subtasks: List<JiraSubtask> = emptyList(),
    val attachment: List<JiraAttachment> = emptyList()
) {
    /** Raw custom fields extracted post-deserialization from the JSON object. */
    @kotlinx.serialization.Transient
    var customFields: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap()
}
```

In the service layer, after deserializing, extract custom fields using configured IDs:
```kotlin
val epicKey = issue.fields.customFields[settings.epicFieldId]?.jsonPrimitive?.content
val reviewer = issue.fields.customFields[settings.reviewerFieldId]?.jsonObject  // user object
```

This requires a custom deserializer or a two-pass parse: first into `JsonObject`, then extract standard fields via `decodeFromJsonElement`, and stash remaining fields into `customFields`.

### Hyperlink behavior

| Action | Result |
|---|---|
| **Click** on ticket key | If in sprint list → select in list + load detail. If in Related/Other → add to relevant section + load detail. If not loaded → add to Other section, fetch and show detail. |
| **Ctrl+Click** on ticket key | Always open in Jira web browser (`{jiraUrl}/browse/{key}`) |
| **Click** on "Open in Jira ↗" | Open current ticket in Jira web |
| **Click** on attachment | Download to temp file and open with system default app |
| **Click** on epic chip | Open epic in Jira web (Ctrl+Click same) |

## 3. Ticket Key Detection & Validation

### Detection
Regex: `\b([A-Z][A-Z0-9]+-\d+)\b`

Scanned in:
- Description text
- Comment bodies
- Epic name field

### Validation (API-based)
1. Extract all unique key matches from the text being rendered
2. Filter out keys already in the global cache (`Map<String, TicketKeyInfo?>`)
3. Batch validate uncached keys in groups of 100 (JQL `IN` clause limit):
   `GET /rest/api/2/search?jql=key in (KEY-1,KEY-2,KEY-3)&fields=key,summary,status`
   Note: Jira `key` field uses bare identifiers — no quotes needed in JQL.
4. Store results in cache: valid keys get `TicketKeyInfo(key, summary, status)`, invalid keys get `null`
5. Re-render text with hyperlinks for valid keys only

### Cache
- `TicketKeyCache` in `:jira` service — `ConcurrentHashMap<String, TicketKeyInfo?>`
- Session-scoped (lives as long as the project is open), LRU cap of 500 entries
- Populated incrementally as user browses tickets
- Invalid keys cached as `null` — not re-checked until explicit Refresh
- Cleared on explicit Refresh (both valid and null entries)

### Rendering
- First render: plain text (no hyperlinks). Triggers async validation.
- When validation returns: re-render with hyperlinks for confirmed keys. Smooth — user sees links appear within ~200ms.

## 4. Transition Dialog with Mandatory Fields

### Flow

```
User clicks "Transition" button
  → Fetch transitions with fields: GET /rest/api/2/issue/{key}/transitions?expand=transitions.fields
  → Show dropdown of available transitions
  → User selects a transition
    → If NO required fields → execute transition immediately
    → If HAS required fields → show Transition Dialog
```

### Transition Dialog

```
┌─ Transition PROJ-123 to Done ──────────────────┐
│                                                  │
│  Resolution: *     [ Fixed                  ▾ ]  │
│  Fix Version:      [ v2.1.0                 ▾ ]  │
│  Comment:          [ Optional comment...      ]  │
│                                                  │
│                    [ Cancel ]  [ Transition ]     │
└──────────────────────────────────────────────────┘
```

### Field type mapping

| Jira schema type | UI component |
|---|---|
| `string` with `allowedValues` | `JComboBox` (dropdown) |
| `string` without `allowedValues` | `JBTextField` |
| `user` | `JBTextField` with Bitbucket user autocomplete |
| `date` | `JBTextField` with date format hint (YYYY-MM-DD) |
| `array` with `allowedValues` | Multi-select checkboxes |
| `number` | `JBTextField` with numeric validation |

### Comment field
Always shown (optional) at the bottom of the dialog. Adds a comment to the ticket during transition via the `update.comment` field in the transition payload.

### Placement
`TransitionDialog` lives in `:jira` module. Other modules trigger it through the `JiraTicketProvider` interface in `:core`:

```kotlin
// Added to JiraTicketProvider interface
suspend fun showTransitionDialog(project: Project, ticketId: String, onTransitioned: () -> Unit)
```

The `:jira` implementation shows the dialog, handles field rendering, and executes the transition. No Jira DTOs leak into `:core` — the interface uses primitives and callbacks only.

## 5. Performance Architecture

### Data loading strategy

| Data | Load timing | Cache |
|---|---|---|
| Basic fields (summary, status, assignee, labels, components) | With sprint fetch | Sprint-scoped |
| Description | With sprint fetch (add to `?fields=`) | Sprint-scoped |
| Subtasks (stubs) | With sprint fetch (`subtasks` field returns stubs) | Sprint-scoped |
| Comments | Lazy on ticket selection | Per-issue, session-scoped |
| Attachments | Lazy on ticket selection | Per-issue, session-scoped |
| Transitions | Lazy on Transition button click | Per-issue, short TTL (5 min) |
| Ticket key validation | Lazy on text render | Global, session-scoped |
| Custom fields (reviewer, tester) | With sprint fetch | Sprint-scoped |

### Rendering pipeline

```
User selects ticket in list
  → [EDT] Render header + details grid + labels + description immediately (from cached JiraIssue)
  → [EDT] Show spinner placeholders for: subtasks, comments, attachments
  → [IO, debounced 200ms] Fetch subtasks + comments + attachments in parallel
  → [EDT] When each arrives, swap spinner for content
```

### Debounce
If user scrolls through tickets quickly, only fetch detail for the ticket that stays selected for 200ms+. Implemented via `Job.cancel()` on previous fetch when selection changes.

### Cache structure

```kotlin
class IssueDetailCache {
    private val cache = ConcurrentHashMap<String, IssueDetailData>()

    data class IssueDetailData(
        val subtasks: List<JiraIssue>? = null,
        val comments: List<JiraComment>? = null,
        val attachments: List<JiraAttachment>? = null,
        val fetchedAt: Instant = Instant.now()
    )

    fun get(key: String): IssueDetailData? = cache[key]
    fun put(key: String, data: IssueDetailData) { cache[key] = data }
    fun invalidate(key: String) { cache.remove(key) }
    fun clear() { cache.clear() }
}
```

### Sprint issue fetch optimization

Current fetch: `GET /rest/agile/1.0/sprint/{id}/issue?maxResults=200`

Updated: add `fields` parameter to include labels, components, attachment metadata:
```
?fields=summary,status,issuetype,priority,assignee,reporter,description,
        labels,components,subtasks,created,updated,issuelinks,sprint,
        customfield_10014,customfield_10050,customfield_10051
```

This avoids N+1 API calls for basic field data.

## 6. New DTOs

```kotlin
// Comments (paginated response)
@Serializable
data class JiraCommentSearchResult(
    val startAt: Int = 0,
    val maxResults: Int = 50,
    val total: Int = 0,
    val comments: List<JiraComment> = emptyList()
)

@Serializable
data class JiraComment(
    val id: String,
    val author: JiraUser,
    val body: String,
    val created: String,
    val updated: String? = null
)

// Attachments
@Serializable
data class JiraAttachment(
    val id: String,
    val filename: String,
    val author: JiraUser? = null,
    val mimeType: String? = null,
    val size: Long = 0,
    val created: String? = null,
    val content: String = ""  // download URL
)

// Components
@Serializable
data class JiraComponent(
    val id: String? = null,
    val name: String,
    val description: String? = null
)

// Subtask stubs (from sprint fetch — NOT lazy loaded)
@Serializable
data class JiraSubtask(
    val id: String,
    val key: String,
    val fields: JiraSubtaskFields
)

@Serializable
data class JiraSubtaskFields(
    val summary: String,
    val status: JiraStatus,
    val issuetype: JiraIssueType? = null
)

// For ticket key cache
data class TicketKeyInfo(
    val key: String,
    val summary: String,
    val status: String
)
```

**Subtasks strategy:** Subtasks come as stubs with the sprint fetch (`?fields=subtasks` returns key + summary + status). This is sufficient for the subtask list in the detail panel — NO lazy loading needed for subtasks. Only comments and attachments are lazy-loaded.

## 7. New API Methods

Add to `JiraApiClient`:
- `getComments(issueKey: String): ApiResult<List<JiraComment>>` — `GET /rest/api/2/issue/{key}/comment`
- `validateTicketKeys(keys: List<String>): ApiResult<Map<String, TicketKeyInfo>>` — batch search

Add to `JiraTicketProvider` interface in `:core`:
- `showTransitionDialog(project: Project, ticketId: String, onTransitioned: () -> Unit)` — shows dialog if needed, executes transition

## 8. Files to Modify/Create

### New files
| File | Description |
|---|---|
| `jira/ui/CurrentWorkSection.kt` | Green-tinted active ticket card |
| `jira/ui/RelatedTicketsSection.kt` | Referenced tickets list |
| `jira/ui/OtherTicketsSection.kt` | Ad-hoc opened tickets list |
| `jira/service/TicketKeyCache.kt` | Session-scoped key validation cache |
| `jira/service/IssueDetailCache.kt` | Per-issue detail data cache |
| `jira/ui/TransitionDialog.kt` | Mandatory fields dialog |
| `jira/api/dto/JiraDtos.kt` | Add JiraComment, JiraAttachment DTOs |
| `core/workflow/JiraTicketProvider.kt` | Add showTransitionDialog method |

### Modified files
| File | Change |
|---|---|
| `jira/ui/SprintDashboardPanel.kt` | Multi-section left panel |
| `jira/ui/TicketDetailPanel.kt` | Rich view with all new sections |
| `jira/api/JiraApiClient.kt` | Add getComments, validateTicketKeys, expand fields param |
| `jira/api/dto/JiraDtos.kt` | Add labels, components, attachment to JiraIssueFields |
| `jira/service/SprintService.kt` | Add custom field IDs to fetch query |
| `core/settings/PluginSettings.kt` | Add epicFieldId, reviewerFieldId, testerFieldId settings |

## 9. Edge Cases

- **No active ticket**: Current Work section shows empty state prompt
- **Custom field IDs not configured**: Reviewer/Tester show "Not configured — set in Settings"
- **Ticket key in text is not a real ticket**: Cached as null, rendered as plain text
- **Very long comment list (100+)**: Paginate — show first 20, "Load more" button
- **Attachment too large**: Show size warning, click still downloads
- **Transition with screen (hasScreen=true in API)**: Show warning "This transition has a Jira screen — open in Jira to complete" + link
- **Related section overflow**: Collapsible, max 10 visible, "Show all" expander
- **Rapid ticket selection**: 200ms debounce prevents unnecessary fetches
- **Circular issue links**: Deduplicate Related Tickets by key to prevent A↔B loops
- **403 on comments/attachments**: Show "Comments unavailable" / "Attachments unavailable" gracefully
- **Transition without screen but with post-function requirements**: Execute and handle 400 error with message

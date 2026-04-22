# Ticket Transition Orchestrator — Design

**Date:** 2026-04-22
**Branch target:** feature branch off `main`
**Status:** Spec — ready for implementation planning

## Problem

Today, ticket transitions happen in at least five places with inconsistent behavior:

1. **Start Work** (`BranchingService.kt:150`, `:265`) — hardcoded "In Progress" by name match, no field collection, no workflow validation.
2. **Post-commit hook** (`PostCommitTransitionHandlerFactory.kt:77`) — hardcoded "In Progress", no fields.
3. **Manual dialog** (`TransitionDialog.kt:144`, opened from `TicketDetailPanel.kt:195`) — collects fields if the DTO contains them, but the plugin never calls `/transitions?expand=transitions.fields`, so the fields block is always empty in practice.
4. **PR create dialog** (`CreatePrDialog.kt`) — prefetches transitions, renders them in a dropdown, but clicking does nothing.
5. **Agent tool** (`JiraTool.kt` → `jiraService.transition`) — has no `fields` parameter. Any transition with mandatory fields fails silently.

There is no shared core service. Each caller rolled its own path. None query workflow rules. None collect fields via user/label/version pickers.

## Goals

1. One core service, `TicketTransitionService`, that every caller (UI + agent) delegates to.
2. Always fetch transitions with `?expand=transitions.fields` so required fields are known before we attempt a POST.
3. A shared, reusable `TicketTransitionDialog` with context-aware pickers (user search, label suggest, version/component lookups, priority dropdowns, etc.) driven by Jira field schema — not hand-coded per field.
4. Agent tool accepts `fields` and returns a structured `MissingFields` error that the agent resolves via `ask_followup_question`.
5. Jira's `/transitions` response is the only source of truth for valid next-states — no client-side workflow rules in v1.
6. Match the plugin's Service Architecture rule: `core/services/<X>.kt` interface → `ToolResult<T>` return → feature-module impl → agent tool wrapper.

## Non-goals (v1)

- Jira Cloud `accountId` user payloads (deployment is Data Center today).
- Per-project hint YAML to layer conventions over Jira's workflow.
- Offline or cached workflow visualization.
- Bulk or scheduled transitions.
- Rollback / undo for transitions (Jira doesn't support this cleanly).

## UX decisions

### Dialog gating — always show, with opt-in skip

- **Default:** Every UI-triggered transition opens `TicketTransitionDialog`, including "silent" auto-flows like Start Work and post-commit. Users see exactly what's about to change.
- **Setting — "Auto-transition when no fields are required"** (default off): When on, auto-flows call `tryAutoTransition`. If the target transition has no required fields and only one valid next-state matches the caller's intent, the transition fires without a dialog. Otherwise the dialog opens.
- **Cache** `getAvailableTransitions` for 60s per ticket so "always show dialog" doesn't add a round-trip on every reopen.

### Agent tool — tool reports, loop decides

The agent tool never opens a Swing dialog. On missing required fields it returns `ToolResult.Error(MissingFields(...))` with full schema metadata. The agent uses the existing `ask_followup_question` tool to collect values from the user (or from its own context) and retries the transition. Headless contexts (sub-agents) behave identically — no special branches.

### Workflow rules — Jira is source of truth

`GET /rest/api/2/issue/{key}/transitions?expand=transitions.fields` returns exactly the transitions valid from the ticket's current status. The orchestrator never second-guesses Jira. No client-side workflow graph. If the user's team needs richer rules later, a per-project hint file is a clean extension point (not shipped in v1).

## Architecture

```
┌───────────────────────────────────────────────────────────────────┐
│  IDE UI callers                                                   │
│   • BranchingService (Start Work)                                 │
│   • PostCommitTransitionHandlerFactory                            │
│   • CreatePrDialog (post-create transition)                       │
│   • TicketDetailPanel (Sprint tab "Transition" button)            │
│                             │                                     │
│                             ▼                                     │
│              TicketTransitionDialog  (shared Swing)               │
│              · uses FieldWidgetFactory keyed on FieldSchema       │
│              · calls JiraSearchService for autocomplete           │
└─────────────────────────────│─────────────────────────────────────┘
                              ▼
┌───────────────────────────────────────────────────────────────────┐
│ :core                                                             │
│   TicketTransitionService    (interface, ToolResult<T>)           │
│   JiraSearchService          (interface, ToolResult<T>)           │
│   models: TransitionMeta, TransitionField, FieldSchema,           │
│           FieldValue, TransitionInput, TransitionOutcome,         │
│           MissingFieldsError, TransitionError                     │
└─────────────────────────────│─────────────────────────────────────┘
                              ▼
┌───────────────────────────────────────────────────────────────────┐
│ :jira                                                             │
│   TicketTransitionServiceImpl                                     │
│   JiraSearchServiceImpl                                           │
│   JiraTransitionResponseParser  (schema → FieldSchema)            │
│   TransitionInputSerializer     (FieldValue → Jira JSON)          │
└─────────────────────────────▲─────────────────────────────────────┘
                              │
┌─────────────────────────────┴─────────────────────────────────────┐
│ :agent                                                            │
│   JiraTool.transition → TicketTransitionService (via :core)       │
│   · Accepts optional `fields` param                               │
│   · On MissingFields, returns structured error for the ReAct      │
│     loop to resolve via ask_followup_question                     │
└───────────────────────────────────────────────────────────────────┘
```

- Two core services — `TicketTransitionService` and `JiraSearchService`. Search is separate because the dialog needs search endpoints before a transition is committed to (e.g., typing in the assignee field).
- Dialog lives in `:jira/ui` because it calls `JiraSearchService`; `:core` can't know about Jira specifics per the layering rule.
- Success emits `WorkflowEvent.TicketTransitioned(key, fromStatus, toStatus, transitionId)` on the `EventBus`. Consumers (Sprint tab, PR dashboard, active-ticket indicator, ticket detail panels) refresh off it.

## Data model

New types under `core/src/main/kotlin/com/workflow/orchestrator/core/model/jira/`:

```kotlin
data class TransitionMeta(
    val id: String,
    val name: String,
    val toStatus: StatusRef,
    val hasScreen: Boolean,
    val fields: List<TransitionField>
)

data class StatusRef(val id: String, val name: String, val category: StatusCategory)
enum class StatusCategory { TO_DO, IN_PROGRESS, DONE, UNKNOWN }

data class TransitionField(
    val id: String,
    val name: String,
    val required: Boolean,
    val schema: FieldSchema,
    val allowedValues: List<FieldOption>,
    val autoCompleteUrl: String?,
    val defaultValue: Any?
)

sealed class FieldSchema {
    object Text : FieldSchema()
    object Number : FieldSchema()
    object Date : FieldSchema()
    object DateTime : FieldSchema()
    object Labels : FieldSchema()
    object Priority : FieldSchema()
    data class SingleSelect(val sourceHint: SelectSource) : FieldSchema()
    data class MultiSelect(val sourceHint: SelectSource) : FieldSchema()
    object CascadingSelect : FieldSchema()
    data class User(val multi: Boolean) : FieldSchema()
    data class Group(val multi: Boolean) : FieldSchema()
    data class Version(val multi: Boolean) : FieldSchema()
    data class Component(val multi: Boolean) : FieldSchema()
    data class Unknown(val rawType: String) : FieldSchema()  // fallback → text input
}

enum class SelectSource { AllowedValues, AutoCompleteUrl, ProjectLookup }

data class FieldOption(val id: String, val value: String, val iconUrl: String? = null)

data class TransitionInput(
    val transitionId: String,
    val fieldValues: Map<String, FieldValue>,
    val comment: String?
)

sealed class FieldValue {
    data class Text(val value: String) : FieldValue()
    data class Number(val value: Double) : FieldValue()
    data class Date(val iso: String) : FieldValue()
    data class DateTime(val iso: String) : FieldValue()
    data class Option(val id: String) : FieldValue()
    data class Options(val ids: List<String>) : FieldValue()
    data class Cascade(val parentId: String, val childId: String?) : FieldValue()
    data class UserRef(val name: String) : FieldValue()
    data class UserRefs(val names: List<String>) : FieldValue()
    data class GroupRef(val name: String) : FieldValue()
    data class VersionRef(val id: String) : FieldValue()
    data class VersionRefs(val ids: List<String>) : FieldValue()
    data class ComponentRef(val id: String) : FieldValue()
    data class ComponentRefs(val ids: List<String>) : FieldValue()
    data class LabelList(val labels: List<String>) : FieldValue()
}

data class TransitionOutcome(
    val key: String,
    val fromStatus: StatusRef,
    val toStatus: StatusRef,
    val transitionId: String,
    val appliedFields: Map<String, FieldValue>
)

sealed class TransitionError {
    data class MissingFields(val payload: MissingFieldsError) : TransitionError()
    data class InvalidTransition(val reason: String) : TransitionError()
    data class RequiresInteraction(val meta: TransitionMeta) : TransitionError()
    data class Network(val cause: Throwable) : TransitionError()
    data class Forbidden(val reason: String) : TransitionError()
}

data class MissingFieldsError(
    val kind: String = "missing_required_fields",
    val transitionId: String,
    val transitionName: String,
    val fields: List<TransitionField>,
    val guidance: String
)

data class UserSuggestion(val name: String, val displayName: String, val email: String?, val avatarUrl: String?, val active: Boolean)
data class LabelSuggestion(val label: String)
data class VersionSuggestion(val id: String, val name: String, val released: Boolean, val archived: Boolean)
data class ComponentSuggestion(val id: String, val name: String, val description: String?)
data class GroupSuggestion(val name: String)
```

The existing `JiraTransitionData` (at `core/model/jira/JiraModels.kt:151`) is deleted; its one non-dialog caller (`CreatePrPrefetch`) moves to `TransitionMeta` in the same commit.

## Service contracts

### `TicketTransitionService` (`:core` interface, `:jira` impl)

```kotlin
interface TicketTransitionService {
    suspend fun getAvailableTransitions(ticketKey: String): ToolResult<List<TransitionMeta>>
    suspend fun prepareTransition(ticketKey: String, transitionId: String): ToolResult<TransitionMeta>
    suspend fun executeTransition(ticketKey: String, input: TransitionInput): ToolResult<TransitionOutcome>
    suspend fun tryAutoTransition(ticketKey: String, transitionId: String, comment: String? = null): ToolResult<TransitionOutcome>
}
```

- `getAvailableTransitions` — always calls Jira with `expand=transitions.fields`. Cached per ticket for 60s; invalidated on `WorkflowEvent.TicketTransitioned`.
- `prepareTransition` — convenience wrapper; uses the same cache.
- `executeTransition` — validates all `required` fields are present in `input.fieldValues` before POSTing; returns `MissingFields` error if not. On success, emits `WorkflowEvent.TicketTransitioned`.
- `tryAutoTransition` — for setting B. If the transition has no required fields and no screen, POSTs immediately. Otherwise returns `RequiresInteraction` so the caller opens the dialog.

### `JiraSearchService` (`:core` interface, `:jira` impl)

```kotlin
interface JiraSearchService {
    suspend fun searchAssignableUsers(ticketKey: String, query: String, limit: Int = 20): ToolResult<List<UserSuggestion>>
    suspend fun searchUsers(query: String, limit: Int = 20): ToolResult<List<UserSuggestion>>
    suspend fun suggestLabels(query: String, limit: Int = 20): ToolResult<List<LabelSuggestion>>
    suspend fun searchGroups(query: String, limit: Int = 20): ToolResult<List<GroupSuggestion>>
    suspend fun listVersions(projectKey: String): ToolResult<List<VersionSuggestion>>
    suspend fun listComponents(projectKey: String): ToolResult<List<ComponentSuggestion>>
    suspend fun followAutoCompleteUrl(url: String, query: String): ToolResult<List<FieldOption>>
}
```

- Endpoint mapping:
  - Assignable users → `GET /rest/api/2/user/assignable/search?issueKey=&query=`
  - Users → `GET /rest/api/2/user/search?query=`
  - Labels → `GET /rest/api/1.0/labels/suggest?query=` (fallback to empty list on 404)
  - Groups → `GET /rest/api/2/groups/picker?query=`
  - Versions → `GET /rest/api/2/project/{key}/versions` (cached 5 min)
  - Components → `GET /rest/api/2/project/{key}/components` (cached 5 min)
  - Generic → call the literal `autoCompleteUrl` from the field schema with `?query=` appended.
- Search endpoints are **not cached** — typed interactively, stale is worse than re-fetch.

### `JiraApiClient` changes

- `getTransitions(issueKey, expandFields: Boolean = true)` — default flipped to `true`.
- `transitionIssue(issueKey, transitionId, fields: Map<String, Any>? = null, comment: String? = null)` — existing signature kept; field marshalling moves into `TransitionInputSerializer` so the client takes an already-built JSON object.
- Body parsing uses `JiraTransitionResponseParser` to produce `List<TransitionMeta>` with populated `TransitionField`s.

### Agent tool surface (`JiraTool.transition`)

```
jira(
  action        = "transition",
  key           = "ABC-123",
  transition_id = "31",
  fields        = { "assignee": {"name": "jdoe"}, "labels": ["bug"] }?,
  comment       = "..."?
)
```

- `fields` is a free-form JSON object; the orchestrator normalizes it to `TransitionInput` (strict parse).
- No `fields` + no required fields → silent transition.
- No `fields` + required fields → `ToolResult.Error(MissingFields(schema))`.
- Bad `fields` (unknown user, unknown label) → `ToolResult.Error(InvalidTransition(reason))` with Jira's message verbatim.
- Tool description in `JiraTool.kt` gets a concrete example of the retry pattern so the LLM knows to ask the user via `ask_followup_question` and re-call with filled `fields`.

## UI — `TicketTransitionDialog`

Replaces the existing half-built `TransitionDialog.kt`. Rewrite in place; rename file to `TicketTransitionDialog.kt`.

### Layout

```
Transition ABC-123 · "Fix login redirect bug"
From: In Progress   →   To: [In Review ▼]

[ required field widgets, sorted first, marked with * ]
[ "Show optional fields" link when optional count > 3 ]

Comment
┌──────────────────────────────────────────────┐
│                                              │
└──────────────────────────────────────────────┘

[spinner while loading]             [Cancel] [Transition]
```

- Target-status dropdown is populated from `getAvailableTransitions`. Changing it re-renders the field block.
- `Transition` button disabled while any required field is empty or fails `validate()`.
- `DialogWrapper` subclass; `ModalityState.stateForComponent()` at open (matches the recent modality-bug sweep).
- `doOKAction()` runs on `Dispatchers.IO` via the dialog's disposable-scoped `CoroutineScope(SupervisorJob() + Dispatchers.IO)`; result marshalled back to EDT via `withContext(Dispatchers.EDT)`.
- Never `runBlocking` (CLAUDE.md rule).

### `FieldWidgetFactory`

```kotlin
object FieldWidgetFactory {
    fun build(field: TransitionField, ctx: WidgetContext, onChange: (FieldValue?) -> Unit): FieldWidget = when (val s = field.schema) {
        FieldSchema.Text       -> TextFieldWidget(field, onChange)
        FieldSchema.Number     -> NumberFieldWidget(field, onChange)
        FieldSchema.Date       -> DatePickerWidget(field, onChange)
        FieldSchema.DateTime   -> DateTimePickerWidget(field, onChange)
        FieldSchema.Labels     -> LabelPickerWidget(field, ctx, onChange)
        FieldSchema.Priority   -> SingleSelectWidget(field, field.allowedValues, onChange)
        is FieldSchema.SingleSelect -> when (s.sourceHint) {
            SelectSource.AllowedValues   -> SingleSelectWidget(field, field.allowedValues, onChange)
            SelectSource.AutoCompleteUrl -> AutoCompleteWidget(field, ctx, onChange, multi=false)
            SelectSource.ProjectLookup   -> error("unexpected for SingleSelect")
        }
        is FieldSchema.MultiSelect -> when (s.sourceHint) {
            SelectSource.AllowedValues   -> MultiSelectWidget(field, field.allowedValues, onChange)
            SelectSource.AutoCompleteUrl -> AutoCompleteWidget(field, ctx, onChange, multi=true)
            SelectSource.ProjectLookup   -> error("unexpected for MultiSelect")
        }
        FieldSchema.CascadingSelect -> CascadingSelectWidget(field, onChange)
        is FieldSchema.User      -> UserPickerWidget(field, ctx, multi = s.multi, onChange)
        is FieldSchema.Group     -> GroupPickerWidget(field, ctx, multi = s.multi, onChange)
        is FieldSchema.Version   -> VersionPickerWidget(field, ctx, multi = s.multi, onChange)
        is FieldSchema.Component -> ComponentPickerWidget(field, ctx, multi = s.multi, onChange)
        is FieldSchema.Unknown   -> TextFieldWidget(field, onChange)
    }
}

interface FieldWidget {
    val component: JComponent
    fun currentValue(): FieldValue?
    fun validate(): String?  // null = ok, else human error
    fun setInitial(value: FieldValue?)
}

data class WidgetContext(
    val project: Project,
    val ticketKey: String,
    val projectKey: String,
    val search: JiraSearchService,
    val disposable: Disposable
)
```

### `SearchableChooser<T>` (shared helper)

All searchable pickers (`UserPicker`, `LabelPicker`, `VersionPicker`, `ComponentPicker`, `GroupPicker`, `AutoCompleteWidget`) use one common component:

- Text field + popup `JBList`.
- Debounces typing 250ms, cancels in-flight search via coroutine cancellation.
- Search runs on `Dispatchers.IO` on a disposable-scoped `CoroutineScope(SupervisorJob())`.
- Empty-state: "No matches." Retry link on error.
- Single-select commits on Enter/click. Multi-select shows selected as removable chips above the text field.

## Schema → FieldSchema mapping

`JiraTransitionResponseParser` converts raw Jira field JSON into `FieldSchema`:

```
schema.type="user"                              → User(multi=false)
schema.type="array", items="user"               → User(multi=true)
schema.type="array", items="string", system="labels" → Labels
schema.type="array", items="version"            → Version(multi=true)
schema.type="version"                           → Version(multi=false)
schema.type="array", items="component"          → Component(multi=true)
schema.type="component"                         → Component(multi=false)
schema.type="priority"                          → Priority
schema.type="string" + allowedValues            → SingleSelect(AllowedValues)
schema.type="string" + autoCompleteUrl          → SingleSelect(AutoCompleteUrl)
schema.type="string" + system="labels" (rare)   → Labels
schema.type="string"                            → Text
schema.type="number"                            → Number
schema.type="date"                              → Date
schema.type="datetime"                          → DateTime
schema.custom = cascadingselect                 → CascadingSelect
(unmatched)                                     → Unknown(rawType)
```

## FieldValue → Jira JSON serialization

`TransitionInputSerializer` is a pure function:

```
UserRef("jdoe")           → {"name": "jdoe"}
UserRefs(["a","b"])       → [{"name":"a"}, {"name":"b"}]
LabelList(["bug","p1"])   → ["bug", "p1"]
Option("10001")           → {"id": "10001"}
Options(["1","2"])        → [{"id":"1"}, {"id":"2"}]
Cascade("p1", "c1")       → {"value":"p1","child":{"value":"c1"}}
Cascade("p1", null)       → {"value":"p1"}
VersionRef(id)            → {"id": id}
VersionRefs(ids)          → [{"id": id}, ...]
ComponentRef(id)          → {"id": id}
ComponentRefs(ids)        → [{"id": id}, ...]
GroupRef("dev-team")      → {"name": "dev-team"}
Text / Number             → primitive
Date                      → "yyyy-MM-dd"
DateTime                  → ISO 8601
```

Single point of change if/when Cloud `accountId` support lands: switch `UserRef` serialization behind a `JiraDeployment.isCloud()` flag.

## Data flow per call site

### Start Work — `BranchingService.kt`

1. Create (or check out existing) branch.
2. `orchestrator.getAvailableTransitions(issueKey)` (cached).
3. Pick the transition matching `settings.defaultStartWorkStatusName` (default "In Progress"), falling back to the first transition whose `toStatus.category == IN_PROGRESS`.
4. None found → log info, skip. (Ticket may already be In Progress.)
5. Setting A (default) → open `TicketTransitionDialog` pre-filled with that transition.
   Setting B → `orchestrator.tryAutoTransition(issueKey, transitionId)`:
   - `Success` → done, show toast.
   - `RequiresInteraction` → open dialog.
6. Dialog success → emit `TicketTransitioned`.

### Post-commit hook — `PostCommitTransitionHandlerFactory.kt`

Same shape as Start Work. The existing balloon "Transition ABC-123 to In Progress?" stays; clicking it opens the dialog instead of firing a hardcoded transition.

### Create PR dialog — `CreatePrDialog.kt` / `CreatePrPrefetch.kt`

1. Prefetch calls `orchestrator.getAvailableTransitions(primaryKey)` (reuses existing prefetch plumbing; replaces the `JiraTransitionData` model).
2. Dialog adds a row: "Transition ticket after PR creation: [None ▼] | [In Review ▼] | ...". Default selection = transition matching `settings.defaultPrCreateStatusName` (default "In Review"). Empty setting disables the row entirely.
3. PR-create success → if a transition was selected, run the same gating as Start Work (A or B). Dialog pops **after** PR creation — field collection does not block PR creation.
4. Transition failure → PR remains created; toast "PR created, but transition to In Review failed: <reason>".

### Sprint tab — `TicketDetailPanel.kt`

Opens `TicketTransitionDialog` from `getAvailableTransitions`. Fields now populate (because the API call includes `expand=transitions.fields`). Smallest delta of the five sites.

### Agent tool — `JiraTool.transition`

1. Tool parses `fields` (optional) into `TransitionInput`.
2. Calls `orchestrator.executeTransition(key, input)`.
3. Orchestrator preflights required-field set from cache; returns `MissingFields` if incomplete.
4. Otherwise POSTs to Jira; returns `TransitionOutcome` or `InvalidTransition(reason)`.

## Events

```kotlin
data class TicketTransitioned(
    val key: String,
    val fromStatus: StatusRef,
    val toStatus: StatusRef,
    val transitionId: String
) : WorkflowEvent()
```

Consumers: Sprint tab, PR dashboard (status-badge refresh), active-ticket indicator, ticket-detail panels, transition cache invalidation.

## Error matrix

| Condition | Source | Surface |
|---|---|---|
| Network timeout (get transitions) | `JiraApiClient` | Dialog: retry link; auto-flow: balloon with retry. |
| HTTP 404 (ticket) | API | "Ticket {key} not found." Flow stops. |
| HTTP 403 (no permission) | API | "You don't have permission to transition this ticket." |
| HTTP 400 on POST (bad value) | API | Parse Jira error JSON; mark offending field inline. Agent tool returns `InvalidTransition(reason)` with Jira's message verbatim. |
| Transition ID not in latest list | Orchestrator | Refresh cache once. Still missing → "Transition no longer valid — status may have changed." Dialog re-renders. |
| Required field missing (dialog) | Orchestrator preflight | Inline error on field; Transition button disabled. |
| Required field missing (agent) | Orchestrator preflight | `ToolResult.Error(MissingFields)` with schema. |
| Unknown `schema.type` | Parser | Widget factory returns `TextFieldWidget`; log warn `"Unknown Jira field schema: {rawType}"`. |
| Search endpoint unavailable | `JiraSearchService` | Picker falls back to plain text input + inline note "Could not load users — enter username manually." |

## Testing strategy

### Unit tests (no IDE fixture)

- `JiraTransitionResponseParser` — table-driven coverage over every `schema.type × items × allowedValues × autoCompleteUrl` combination including cascading, multi-user, label, priority, custom select, unknown.
- `TransitionInputSerializer` — one case per `FieldValue` variant → expected JSON.
- `TicketTransitionServiceImpl` — mocked `JiraApiClient`: preflight cache hit/miss, required-field validation, invalidation on `TicketTransitioned`, `tryAutoTransition` branching.
- `JiraSearchServiceImpl` — one test per endpoint mapping.

### Integration (`BasePlatformTestCase` + WireMock)

- Full dialog flow: open → select transition → pick user via search → submit → 200 → event fired.
- Full dialog flow: open → submit with missing required field → dialog stays open with inline error.
- Post-commit hook end-to-end in both settings (A and B).
- Agent tool missing-fields → `MissingFields` error → simulated refill call succeeds.

### E2E

- `CreatePrDialog` with a mandatory-reviewer workflow: PR created → dialog pops with reviewer picker → success.

## Settings additions — `PluginSettings`

```
ticketTransition.autoTransitionSilently : Boolean = false
  "Skip the transition dialog when the target status has no required fields and the next-state is unambiguous."

ticketTransition.defaultStartWorkStatusName : String = "In Progress"
  "Preferred target status name when Start Work creates a branch. Skipped if not found in the workflow."

ticketTransition.defaultPrCreateStatusName : String = "In Review"
  "Preferred target status name when a PR is created. Empty disables auto-transition on PR create."
```

New subsection under *Tools > Workflow Orchestrator > Jira*: **Ticket Transitions** — three fields above.

## Migration

- Delete `JiraTransitionData` and its single non-dialog caller reference (`CreatePrPrefetch`) in the same commit; internal-only type, no deprecation needed.
- Rewrite `TransitionDialog.kt` → `TicketTransitionDialog.kt` in place. Update callers.
- Flip `JiraApiClient.getTransitions` default from `expandFields=false` to `true`; the one caller that doesn't need expand passes `false`.
- Update post-commit notification text to read "Open transition dialog…" instead of "Transition to In Progress".
- `JiraTool.transition` adds optional `fields` param; description updated; no breaking change to existing agent calls.

## File plan (new + changed)

**New files:**
- `core/src/main/kotlin/com/workflow/orchestrator/core/services/jira/TicketTransitionService.kt`
- `core/src/main/kotlin/com/workflow/orchestrator/core/services/jira/JiraSearchService.kt`
- `core/src/main/kotlin/com/workflow/orchestrator/core/model/jira/TransitionModels.kt` (TransitionMeta, TransitionField, FieldSchema, FieldValue, TransitionInput, TransitionOutcome, TransitionError, MissingFieldsError)
- `core/src/main/kotlin/com/workflow/orchestrator/core/model/jira/SearchModels.kt` (User/Label/Version/Component/GroupSuggestion)
- `core/src/main/kotlin/com/workflow/orchestrator/core/events/TicketTransitioned.kt`
- `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/TicketTransitionServiceImpl.kt`
- `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/JiraSearchServiceImpl.kt`
- `jira/src/main/kotlin/com/workflow/orchestrator/jira/api/JiraTransitionResponseParser.kt`
- `jira/src/main/kotlin/com/workflow/orchestrator/jira/api/TransitionInputSerializer.kt`
- `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/TicketTransitionDialog.kt` (replaces `TransitionDialog.kt`)
- `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/widgets/FieldWidgetFactory.kt`
- `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/widgets/SearchableChooser.kt`
- `jira/src/main/kotlin/com/workflow/orchestrator/jira/ui/widgets/*Widget.kt` (Text, Number, Date, DateTime, SingleSelect, MultiSelect, Cascading, User, Group, Version, Component, Label, AutoComplete)
- Test files mirroring each of the above.

**Changed files:**
- `jira/src/main/kotlin/com/workflow/orchestrator/jira/api/JiraApiClient.kt` — default `expandFields=true`; response parsing delegates to `JiraTransitionResponseParser`.
- `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/BranchingService.kt` — Start Work path (lines 84–160 and 166–244) calls orchestrator.
- `core/src/main/kotlin/com/workflow/orchestrator/core/vcs/PostCommitTransitionHandlerFactory.kt` — uses orchestrator.
- `pullrequest/src/.../CreatePrDialog.kt` + `CreatePrPrefetch.kt` — post-create transition row; uses orchestrator.
- `jira/src/.../ui/TicketDetailPanel.kt` — opens `TicketTransitionDialog` instead of the old one.
- `agent/src/.../tools/integration/JiraTool.kt` — `fields` param, error mapping, updated description.
- `core/src/.../settings/PluginSettings.kt` — three new fields.
- Settings UI class (under *Tools > Workflow Orchestrator*) — new "Ticket Transitions" subsection.
- `core/src/.../model/jira/JiraModels.kt` — remove `JiraTransitionData`.
- `core/CLAUDE.md` + `jira/CLAUDE.md` + `docs/architecture/index.html` — document the new service and flow.

## Rollout

- Single feature branch; single PR; no flag. The change is additive for the agent tool (optional `fields`) and strictly an improvement for the UI paths. Existing screenshots / tests that assert the empty `TransitionDialog` behavior need updating — captured in the test files above.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Jira DC installations without the assignable-users endpoint | `searchAssignableUsers` falls back to `searchUsers` on 404. |
| Labels endpoint `/rest/api/1.0/labels/suggest` removed in some DC versions | On 404, return empty list; picker still allows free-text entry. |
| Large `allowedValues` in a custom field (thousands of options) | `SingleSelectWidget` switches to a searchable chooser when `allowedValues.size > 50`. |
| Parser missing an edge-case schema | `Unknown(rawType)` → text input fallback + warn log; user can still type a value manually. |
| Caching serves stale transitions across a rule change | 60s TTL + invalidation on `TicketTransitioned` bounds the window; manual refresh by reopening the dialog. |
| Agent loops asking for the same field | Agent system prompt updated: "If `ask_followup_question` yields an answer, cache it in conversation memory; do not re-ask for the same field on retry." |

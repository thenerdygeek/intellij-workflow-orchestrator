# `jira` — extended notes

## Why one tool with 17 actions instead of 17 sibling tools

The schema budget is the single biggest constraint on agent quality. Each top-
level tool definition costs ~150-300 tokens in the system prompt. Splitting `jira`
into 17 sibling tools (`jira_get_ticket`, `jira_search`, `jira_transition`, …)
would cost ~3,000-5,000 extra tokens *every iteration*, even when the LLM never
touches Jira — that's an entire budget line item for capabilities the user isn't
using right now.

Notably, this tool is also **deferred**: it lives in the active-deferred tier and
is only brought into the schema once the LLM calls `tool_search` (or once the
project's settings declare a Jira URL during conditional registration in
`AgentService.registerConditionalIntegrationTools`). So the cost only lands when
Jira is actually configured AND the LLM has reached for it. The 17-actions-in-one
shape keeps the post-activation cost bounded.

## Conditional registration

`JiraTool` is registered via `safeRegisterDeferred("Integration") { JiraTool() }`
in `AgentService.registerConditionalIntegrationTools()`, gated on
`ConnectionSettings.state.jiraUrl.isNotBlank()`. If the user hasn't set a Jira URL
in Settings → Tools → Workflow Orchestrator → Connections, this tool never
registers — the LLM doesn't see it, can't call it, and won't waste tokens
hallucinating about it.

`AgentService.reregisterConditionalTools()` re-evaluates this when settings
change at runtime, adding or removing the tool without restarting the session.

## Auth model — bearer tokens via PasswordSafe

Every action calls into `JiraService` (in `:jira` module), which builds an
`Authorization: Bearer <token>` header. The token is stored in IntelliJ's
PasswordSafe (encrypted, OS-keychain-backed on macOS/Windows, libsecret on
Linux) — never in a `PersistentStateComponent` XML file, never in source code,
never in a log line.

This is the load-bearing reason the tool exists: without it, the LLM's only
fallback is `run_command curl -H "Authorization: Bearer $JIRA_TOKEN" ...`,
which:

1. Requires the user's shell to have `JIRA_TOKEN` exported (most don't).
2. If not exported, the LLM tends to embed the literal token in the command
   (after asking the user) — which lands in shell history, in process listings
   visible to other users on shared machines, and in the agent's own command
   log.
3. Bypasses `ProcessEnvironment`'s 35-var sensitive-vars stripper because the
   token isn't an environment variable in this case — it's a command argument.

The whole point of integrating Jira as a first-class tool is to keep credentials
in PasswordSafe and out of the LLM's working memory.

## Per-action grouping (observational)

The 17 actions cluster into roughly five workflows:

| Workflow | Actions | Frequency (intuition) |
|---|---|---|
| Read a ticket | `get_ticket` (with 4 boolean fan-outs), `get_comments`, `get_worklogs`, `get_dev_branches`, `get_linked_prs` | Very high |
| Find tickets | `search_issues`, `search_tickets` | High |
| Modify state | `transition`, `comment`, `log_work` | Medium |
| Sprint/board | `get_sprints`, `get_boards`, `get_sprint_issues`, `get_board_issues` | Low (mostly devstatus dashboard work) |
| Branch + attachments | `start_work`, `get_dev_branches`, `download_attachment`, `get_transitions` | Low-medium |

Without per-action usage telemetry we can't be precise, but the ones that look
*observationally* like drop candidates are:

- **`get_worklogs`** — log read-back is rarely the LLM's job; if a user asks
  "how much time was logged?" they usually open Jira themselves. The action
  earns ~5% of `get_ticket`'s usage at most.
- **`get_board_issues`** — when an LLM wants issues, it almost always reaches
  for `search_issues`/`search_tickets` because JQL is more flexible than a
  board's filter. Board-issue listing is mostly a UI affordance.
- **`get_sprints`** — sprint listing is the kind of thing a user does once at
  start of sprint via the IDE's Sprint tab, not the agent. The two
  sprint-related actions (`get_sprints`, `get_sprint_issues`) together account
  for less work than `get_ticket include_dev_status=true`.

Counter-argument for keeping them: the schema cost of an unused action inside
this meta-tool is roughly one line of the description (~10-30 tokens). Removing
them saves hundreds of tokens, not thousands. Worth doing as a sweep but
individually low-impact.

## `transition` and the MissingFields contract

`transition` is the most complex action. It delegates to
`TicketTransitionService.executeTransition(key, TransitionInput(...))`, which
validates the transition's required field set against the supplied
`fieldValues`. If a required field is missing, the service returns
`TransitionError.MissingFields` with a typed payload listing each
`(id, name, required, schema)` entry.

The tool surfaces this payload to the LLM as a structured `payload_type:
missing_required_fields` block. The intended LLM workflow:

1. Call `transition` once with whatever fields you have.
2. If you get `MissingFields`, call `ask_followup_question` for each field
   (using the `(id, name)` from the payload).
3. Retry `transition` with the complete `fields={ <fieldId>: <value>, ...}`.

This is documented in the tool description verbatim — it's important that the
LLM doesn't try to guess the field IDs from looking at a transition's display
name, because Jira custom-field IDs (`customfield_10412`) are project-specific.

`fields` formats follow the Jira REST API conventions:

- `user`/`assignee`/`reviewer`: `{"name": "<username>"}` (Jira DC)
- `labels`: `["label1", "label2"]`
- `priority`/`select`/`option`: `{"id": "<option-id>"}`
- multi-select: `[{"id": "a"}, {"id": "b"}]`
- cascading: `{"value": "parent", "child": {"value": "child"}}`
- version/component: `{"id": "<id>"}` or `[{"id": "<id>"}, ...]`

`parseFieldsJson` in the tool source coerces a raw `JsonObject` into a typed
`Map<String, FieldValue>` using these conventions. Entries that can't be coerced
are silently skipped — a defensive choice that lets a partial fields blob still
work.

## Common error patterns

| HTTP | Meaning | LLM should |
|---|---|---|
| 401 | Token expired or invalid | Stop. Ask the user to re-enter the Jira token in Settings. Don't retry. |
| 403 | Authenticated but no permission for this action | Ask the user; don't retry. Use `get_ticket(include_permissions=true)` to check before attempting privileged actions. |
| 404 | Issue/board/sprint doesn't exist | Often a typo in the key (`PROJ-123` vs `proj-123`). `ToolValidation.validateJiraKey` catches obvious malformations before the request. |
| 410 | Issue was deleted | Surface the message to the user; the LLM should not silently retry on a different issue. |
| 429 | Rate limited | Jira DC rarely rate-limits; Cloud does. The action returns the network error; the LLM should pause before retrying. |

## Image attachments → BrainRouter routing

`download_attachment` lands the bytes at
`{sessionDir}/downloads/jira-{attachmentId}/{filename}` via the
`SessionDownloadDir` coroutine context element (installed by
`AgentLoopAttachmentScope`).

When the MIME type is `image/{png,jpeg,webp,gif}` AND
`PluginSettings.enableToolImageAutoload` is true AND the MIME is in the
configured whitelist, `autoLoadImageIfApplicable` reads the bytes back from
disk, stores them in the per-session `AttachmentStore`, and returns the
`ToolResult` augmented with an `ImageRefData` entry on `imageRefs`.

Downstream, `AgentLoop` writes the image as a `ContentBlock.ImageRef` block
alongside the text `ContentBlock.ToolResult` in the same user `ApiMessage`.
`BrainRouter` detects images via `messages.any { it.hasImageParts() }` and
routes the next call through `/.api/completions/stream` (vision-capable),
even though the image arrived on a tool-result turn rather than a user turn.

For non-image documents (PDF, DOC, DOCX, XLSX, etc.), the result is augmented
with a `read_document` hint paragraph telling the LLM the file path on disk and
suggesting `read_document` to extract text. Plain text/CSV/HTML files return no
hint — the LLM uses `read_file` directly.

## What a typical `get_ticket` looks like in practice

```
{
  "action": "get_ticket",
  "key": "PROJ-1234",
  "include_dev_status": true,
  "include_remote_links": true,
  "include_history": false,
  "include_permissions": false
}
```

This fans out four parallel `async { service.X() }` calls (ticket, devstatus,
remote-links, permissions). The result is a single block of text with the
ticket header followed by additional sections per requested fan-out:

```
[ticket fields]
…

Dev Status for PROJ-1234: 2 PRs (1 open), 3 commits, 2 builds (1 SUCCESSFUL)

Branches (1):
  - feature/PROJ-1234-do-the-thing (https://bb.../branches/PROJ-1234)

Pull Requests (2):
  - [OPEN] PR #4567 (https://bb.../prs/4567)
  - [MERGED] PR #4501 (https://bb.../prs/4501)

…

Remote Links (showing 2 of 2):
• [Confluence] Design doc → https://confluence.../pages/123
• [Figma] Mocks → https://figma.com/file/abc
```

The fan-outs are why this single action is more useful than sequential calls:
it's one tool call producing the whole context block, vs 4 calls + LLM-side
synthesis.

## The two search variants — `search_issues` vs `search_tickets`

Both run JQL. They diverge on default behaviour:

- **`search_issues`** — text-shaped query: defaults to current-user-only,
  default 20 results. Best when the LLM has natural-language intent like
  "find my open tickets mentioning auth".
- **`search_tickets`** — raw JQL pass-through, default 8 results. Best when the
  LLM knows JQL and wants exact control: `project = PROJ AND status = "In
  Review" AND updated >= -7d`.

The split is observational — the LLM tends to pick `search_tickets` when it has
a JQL string and `search_issues` when it has free text. Could be merged into one
action with a `query_kind` discriminator, but the schema savings would be ~30
tokens; the LLM clarity would be slightly worse.

## What's deliberately NOT exposed

- **Create issues** — out of scope; Jira issue creation is a bigger workflow
  involving project picker, issue-type picker, required-field discovery. The
  agent doesn't create tickets.
- **Edit issue summary/description** — same reason.
- **Watchers, voters, attachments upload** — niche; not requested by users so
  far.
- **Bulk operations** — risk-asymmetric; one wrong JQL on a bulk transition
  could move dozens of tickets.

If a user needs these, the LLM falls back to `run_command curl` with the user
explicitly providing the request body — but PasswordSafe credentials are still
unavailable to that path, so it's a friction barrier (which is correct).

## `start_work` is a workflow, not just an API call

`start_work(key, branch_name, source_branch)` is the only Jira action that
*also* talks to the local Git repository. It:

1. Resolves the source branch in the configured repo.
2. Creates the new branch off that source.
3. Optionally transitions the issue to "In Progress" (per `JiraService`
   semantics).
4. Returns a confirmation block.

This bundles three calls (Git checkout, Git create-branch, Jira transition) into
one — high-value composition for the daily workflow of "start work on a ticket".
The action is justified by composition value, not by individual API count.

## Auditing notes

- `get_dev_branches` and `get_linked_prs` overlap with `get_ticket(include_dev_status=true)`.
  The former two return narrower slices of the same `DevStatusBundle` data the
  latter assembles. Could plausibly be replaced by guidance to use
  `include_dev_status` with a filter param. Net savings: 2 actions × ~15 tokens
  = ~30 tokens. Tradeoff: small individual cost, but worth a sweep when
  consolidating.
- `key`, `issue_key`, and `issue_id` are all accepted for the same parameter
  in some actions (`get_worklogs`, `get_linked_prs`, `get_dev_branches`,
  `start_work`). The triple-fallback is defensive — different LLMs / older
  prompts might use different names. Could consolidate but risks breaking
  existing callers; low priority.
- `description` parameter exists for write-action approval dialogs but isn't
  enforced — it's marked "recommended". Should the LLM forget to set it, the
  approval dialog falls back to the action+key. Could be promoted to required
  for write actions but that's a behaviour change.

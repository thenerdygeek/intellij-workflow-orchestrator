# `bitbucket_pr` — extended notes

## Why one tool with 19 actions instead of 19 sibling tools

Same token-budget argument as `jira`: each top-level tool definition costs
~150-300 tokens in the system prompt. Splitting `bitbucket_pr` into 19 sibling
tools would add ~3,000-5,000 extra tokens *every iteration* — even when the LLM
is writing code and never touches Bitbucket.

`bitbucket_pr` is **conditionally deferred**: it only registers when
`ConnectionSettings.bitbucketUrl` is non-blank (checked at startup and on
settings change via `AgentService.reregisterConditionalTools()`). The LLM can
discover it with `tool_search("bitbucket")` in any session where the URL is
configured but the tool hasn't been activated yet.

## Target platform: Bitbucket Data Center, NOT Cloud

This tool is tested against Bitbucket Data Center 9.4.16 and 10.x.
Bitbucket Cloud uses different REST API paths (`/2.0/repositories/...` vs
`/rest/api/1.0/projects/.../repos/...`) and a different auth scheme (OAuth 2.0
app passwords, not server tokens). This tool does **not** work with Bitbucket Cloud.

If Cloud support is needed, a `bitbucket_cloud_pr` tool with separate endpoint
and auth logic would be required.

## Auth model — bearer tokens via PasswordSafe

Every action calls into `BitbucketService` (in `:pullrequest` module), which
builds an `Authorization: Bearer <token>` header. The token is stored in
IntelliJ's PasswordSafe — encrypted, OS-keychain-backed on macOS/Windows,
libsecret on Linux.

The fallback without this tool is `run_command curl -H "Authorization: Bearer
$BB_TOKEN" ...`, which:

1. Requires `BB_TOKEN` in the shell environment (most users haven't exported it).
2. If the user pastes the token inline, it lands in shell history, in the
   process listing, and in the agent's command log.
3. Bypasses `ProcessEnvironment`'s 35-var stripper (it covers env vars, not
   command arguments).

## The version-race problem on mutations

Bitbucket DC uses optimistic locking on mutable resources. Every PR carries a
`version` field (integer, starts at 0, incremented on each update). Mutation
requests (approve, merge, decline, update title/description) must include the
current `version` value; if the PR has been updated since the version was read,
Bitbucket returns **409 Conflict**.

The correct pattern for any mutation:

1. Call `get_pr_detail(pr_id)` to get the current `version`.
2. Immediately follow with the mutation action.
3. If you get 409, re-fetch and retry.

The `BitbucketService` implementation bundles the current version in mutation
requests automatically — but if there is any delay between the read and the
write (e.g. multiple tool calls, user interaction), the version can go stale.
For the `merge_pr` action specifically, the service always does a fresh fetch
before merging to minimise the race window.

## Action grouping by workflow

The 19 actions cluster into five natural workflows:

| Workflow | Actions | Frequency (intuition) |
|---|---|---|
| Inspect a PR | `get_pr_detail`, `get_pr_commits`, `get_pr_activities`, `get_pr_changes`, `get_pr_diff` | Very high |
| Gate checks | `check_merge_status`, `get_pr_participants`, `get_blocker_comment_count`, `get_required_builds` | High (pre-merge) |
| Write lifecycle | `create_pr`, `approve_pr`, `merge_pr`, `decline_pr` | Medium |
| Edit / housekeeping | `update_pr_title`, `update_pr_description` | Low |
| Discovery | `get_my_prs`, `get_reviewing_prs`, `get_prs_for_branch`, `get_linked_jira_issues` | Low-medium |

## Drop candidates

Without action-level usage telemetry, the three most likely drop candidates are:

1. **`get_blocker_comment_count`** — Returns only an integer count. Its use case
   ("are there open blockers?") is fully covered by `check_merge_status`, which
   also lists veto reasons. The count alone doesn't tell the LLM *what* the
   blockers are. If `check_merge_status` is always run before `merge_pr`, this
   action is redundant. Drop saves one description line (~20 tokens) and one
   action dispatch branch.

2. **`get_required_builds`** — Repo-level configuration, not PR-level status.
   Most users know which builds are required for their branches (CI is set up
   once and rarely changes). The LLM almost never needs to consult this before
   opening or merging a PR; it's more of a "help me understand our branch
   policies" affordance. Drop candidate if usage tracking confirms near-zero
   calls.

3. **`update_pr_title`** — PRs generally get their title right at creation time.
   Renaming a PR mid-review is a rare edge case. The action pair
   `update_pr_title + update_pr_description` together form the lowest-value
   housekeeping cluster in this tool. If forced to drop one of the pair,
   `update_pr_title` is the weaker one; `update_pr_description` is more
   frequently needed when a PR's scope evolves mid-review.

Counter-argument: the schema cost of an unused action inside a meta-tool is
roughly one line of the description (~15-30 tokens). Removing three actions saves
~45-90 tokens — meaningful but not urgent. Worth a sweep when consolidating
across tools, but individually low-impact.

## `get_prs_for_branch` fills a real gap

`get_my_prs` returns PRs *authored* by the current user. In a team workflow,
PRs are sometimes opened by CI automation, by tech leads following a branch-push
webhook, or by teammates. `get_prs_for_branch(branch_name=...)` bypasses the
author filter and returns any PR whose source ref matches the branch — the right
action for "did my push result in a PR?" after `git push`.

This distinction is documented in the tool description but the LLM consistently
forgets it when both actions are available. The `commonLLMMistakes` entry
captures this pattern.

## Cross-tool workflow: PR merge → Jira close

The canonical end-of-task workflow when both Bitbucket and Jira are configured:

```
1. bitbucket_pr(action=check_merge_status, pr_id=42)
   → canMerge: true

2. bitbucket_pr(action=merge_pr, pr_id=42, strategy=squash, delete_source_branch=true)
   → Merged. Commit SHA: abc123f

3. bitbucket_pr(action=get_linked_jira_issues, pr_id=42)
   → ["PROJ-1234"]

4. jira(action=get_transitions, key=PROJ-1234)
   → [{id: 41, name: "Resolve Issue", toStatus: "Resolved"}]

5. jira(action=transition, key=PROJ-1234, transition_id=41, comment="Merged in PR #42.")
   → Transitioned PROJ-1234: In Progress → Resolved
```

Step 3 (`get_linked_jira_issues`) bridges the tools: without it, the LLM has to
extract the ticket key from the PR title or branch name heuristically, which
fails for non-standard naming conventions.

## Common error patterns

| HTTP | Meaning | LLM should |
|---|---|---|
| 401 | Token expired or invalid | Stop. Ask the user to re-enter the Bitbucket token in Settings. Do NOT retry. |
| 403 | Authenticated but insufficient permission | Surface to user. Don't retry. |
| 404 | PR / repo / branch not found | Confirm PR ID or branch name with user — usually a typo. |
| 409 | Optimistic-lock version race (mutation) or merge vetoes or author self-approve | For version race: re-fetch and retry. For merge vetoes: run check_merge_status to identify blockers. For self-approve: don't retry — surface to user. |
| 400 | Bad request — often invalid merge strategy or missing required field | Read the error body; fix the parameter (e.g. correct strategy enum value). |

## Bitbucket DC API notes (9.4.16 / 10.x)

- All endpoints use `Authorization: Bearer <token>` (server-access-token or
  personal-access-token). Personal access tokens use the same header.
- Base path: `/rest/api/1.0/projects/{projectKey}/repos/{repoSlug}/pull-requests/`
- The `X-Atlassian-Token: no-check` header is required for **write** actions
  (create, approve, merge, decline, update) to bypass Atlassian's XSRF check.
  `BitbucketService` handles this automatically.
- Pagination: list endpoints (get_my_prs, get_reviewing_prs, get_prs_for_branch,
  get_pr_commits, get_pr_activities, get_pr_changes) are paginated. The service
  fetches the first page (default 25 items). If the user asks for more,
  pagination is not currently exposed as a parameter — the LLM should note the
  truncation.

## What is deliberately NOT exposed

- **PR comments / inline review comments** — handled by `bitbucket_review`
  (add_pr_comment, add_inline_comment, reply_to_comment, resolve_comment, etc.)
  to keep the schema footprints separate.
- **Branch management** — create_branch, get_branches, get_file_content are in
  `bitbucket_repo`.
- **Bulk PR operations** — risk-asymmetric; a single bad filter could affect
  dozens of PRs.
- **PR webhook management** — admin-level; out of scope for an agent.
- **Attachments on PRs** — Bitbucket DC doesn't expose a clean attachment-upload
  API on PRs; this is a Confluence concern.

package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * PR review actions — comments, inline comments, replies, reviewer management.
 *
 * 12 actions: add_pr_comment, add_inline_comment, reply_to_comment,
 * add_reviewer, remove_reviewer, set_reviewer_status,
 * list_comments, get_comment, edit_comment, delete_comment,
 * resolve_comment, reopen_comment
 */
class BitbucketReviewTool : AgentTool {

    override val name = "bitbucket_review"

    override val description = """
PR review actions — comments, inline comments, replies, reviewer management.

Actions and their parameters:
- add_pr_comment(pr_id, text) → Add a general comment to a PR
- add_inline_comment(pr_id, file_path, line, line_type, text) → Add inline comment on a specific line
- reply_to_comment(pr_id, parent_comment_id, text) → Reply to an existing comment thread
- add_reviewer(pr_id, username) → Add a reviewer to a PR
- remove_reviewer(pr_id, username) → Remove a reviewer from a PR
- set_reviewer_status(pr_id, username, status) → Set reviewer status: APPROVED, NEEDS_WORK, UNAPPROVED
- list_comments(project_key, repo_slug, pr_id, only_open?, only_inline?) → List all comments on a PR (filter by open/inline)
- get_comment(project_key, repo_slug, pr_id, comment_id) → Get a single comment by ID
- edit_comment(project_key, repo_slug, pr_id, comment_id, text, expected_version) → Edit comment text (uses optimistic locking; surfaces STALE_VERSION error)
- delete_comment(project_key, repo_slug, pr_id, comment_id, expected_version) → Delete a comment (uses optimistic locking)
- resolve_comment(project_key, repo_slug, pr_id, comment_id) → Mark a comment thread as resolved
- reopen_comment(project_key, repo_slug, pr_id, comment_id) → Reopen a resolved comment thread

Common optional: repo_name for multi-repo projects. description for approval dialog on write actions.
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty("string", "Operation to perform",
                enumValues = listOf(
                    "add_pr_comment", "add_inline_comment", "reply_to_comment",
                    "add_reviewer", "remove_reviewer", "set_reviewer_status",
                    "list_comments", "get_comment", "edit_comment", "delete_comment",
                    "resolve_comment", "reopen_comment"
                )),
            "pr_id"             to ParameterProperty("string", "Pull request ID (numeric) — required for all actions"),
            "text"              to ParameterProperty("string", "Comment/reply text — for add_pr_comment, add_inline_comment, reply_to_comment"),
            "file_path"         to ParameterProperty("string", "File path — for add_inline_comment"),
            "line"              to ParameterProperty("string", "Line number — for add_inline_comment"),
            "line_type"         to ParameterProperty("string", "Line type: ADDED, REMOVED, CONTEXT — for add_inline_comment", enumValues = listOf("ADDED", "REMOVED", "CONTEXT")),
            "parent_comment_id" to ParameterProperty("string", "Parent comment ID (integer) — for reply_to_comment"),
            "username"          to ParameterProperty("string", "Reviewer username — for add_reviewer, remove_reviewer, set_reviewer_status"),
            "status"            to ParameterProperty("string", "Reviewer status: APPROVED, NEEDS_WORK, UNAPPROVED — for set_reviewer_status", enumValues = listOf("APPROVED", "NEEDS_WORK", "UNAPPROVED")),
            "repo_name"         to ParameterProperty("string", "Repository name for multi-repo projects — omit for primary"),
            "description"       to ParameterProperty("string", "Approval dialog description for write actions: add_pr_comment, set_reviewer_status"),
            "project_key"       to ParameterProperty("string", "Bitbucket project key (e.g. PROJ) — for list_comments, get_comment, edit_comment, delete_comment, resolve_comment, reopen_comment"),
            "repo_slug"         to ParameterProperty("string", "Repository slug — for list_comments, get_comment, edit_comment, delete_comment, resolve_comment, reopen_comment"),
            "only_open"         to ParameterProperty("string", "Filter to open comments only: true/false — for list_comments"),
            "only_inline"       to ParameterProperty("string", "Filter to inline comments only: true/false — for list_comments"),
            "comment_id"        to ParameterProperty("string", "Comment ID (integer) — for get_comment, edit_comment, delete_comment, resolve_comment, reopen_comment"),
            "expected_version"  to ParameterProperty("string", "Current comment version for optimistic locking — for edit_comment, delete_comment")
        ),
        required = listOf("action")
    )

    override val allowedWorkers = setOf(
        WorkerType.TOOLER,
        WorkerType.ORCHESTRATOR,
        WorkerType.CODER,
        WorkerType.REVIEWER,
        WorkerType.ANALYZER
    )

    override fun documentation(): ToolDocumentation = toolDoc("bitbucket_review") {
        summary {
            technical(
                "12-action meta-tool for Bitbucket DC PR review operations: general comments, " +
                "inline line comments, thread replies, reviewer management (add/remove/status), " +
                "comment lifecycle (list/get/edit/delete/resolve/reopen). Uses optimistic locking " +
                "(expected_version) for edit and delete to prevent mid-flight overwrites. " +
                "Bearer-auth via PasswordSafe; conditionally registered when BitbucketUrl is configured."
            )
            plain(
                "The agent's Bitbucket code-review assistant. Like clicking through every review button " +
                "in the PR tab — post a top-level comment, leave an inline note on a specific line, " +
                "reply to a thread, approve or request changes, edit your own comment if you spotted a " +
                "typo, resolve threads when the fix is in. Only appears when Bitbucket URL is set in Settings; " +
                "tokens stay in the OS keychain, never in LLM context."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.NETWORK)
        counterfactual(
            "Without `bitbucket_review`, the LLM would fall back to `run_command curl` with the bearer token " +
            "embedded in the command line — landing in shell history, the agent log, and the ProcessEnvironment " +
            "sensitive-vars strip (which strips env vars, not command arguments). Optimistic locking for edit/delete " +
            "would disappear; the LLM would have to construct the version-stamped request body manually from a prior " +
            "get_comment read, getting it wrong reliably. Reviewer management (add/remove/set_reviewer_status) would " +
            "require multiple curl calls with hand-crafted JSON bodies the LLM frequently misspells. The typed error " +
            "surface (STALE_VERSION on optimistic-lock conflict) would degrade to raw HTTP 409 JSON that the LLM " +
            "has to parse and reason about."
        )
        llmMistake(
            "Calls `add_inline_comment` without first confirming whether the line is ADDED, REMOVED, or CONTEXT. " +
            "The `line_type` is mandatory and workflow-specific — an ADDED line on the right diff pane is 'ADDED', " +
            "but context lines visible on both sides are 'CONTEXT'. Wrong value causes a Bitbucket API 400."
        )
        llmMistake(
            "Omits `expected_version` for `edit_comment` or `delete_comment`. Both require it — Bitbucket uses " +
            "optimistic locking to prevent concurrent overwrites. The LLM must call `get_comment` first to fetch " +
            "the current version number, then pass it."
        )
        llmMistake(
            "Passes `pr_id` as a string with a '#' prefix (e.g. '#42') instead of a bare integer string ('42'). " +
            "`BitbucketToolUtils.parsePrId` calls `toIntOrNull()` on the raw string — the '#' causes it to return " +
            "null and the tool returns `invalidPrId()` without hitting the API."
        )
        llmMistake(
            "Calls `set_reviewer_status` with `status='APPROVED'` without supplying `username`. The tool requires " +
            "a username to identify whose review status to change — it's not assumed to be the current user."
        )
        llmMistake(
            "Uses `add_pr_comment` to reply to an existing thread instead of `reply_to_comment`. Top-level comments " +
            "start a new thread; replies must use `reply_to_comment` with `parent_comment_id` so Bitbucket groups them " +
            "correctly in the review UI."
        )
        llmMistake(
            "Treats a 401 from any action as retryable. 401 = the token in PasswordSafe is expired or revoked; " +
            "retrying just gets another 401. The correct action is to surface the error to the user and stop."
        )
        llmMistake(
            "Conflates `resolve_comment` (marks a thread as resolved — the thread is still visible but collapsed) " +
            "with `delete_comment` (permanently removes the comment). Resolved comments remain in the audit trail; " +
            "deleted comments do not."
        )
        llmMistake(
            "Calls `list_comments` / `get_comment` / `edit_comment` / `delete_comment` / `resolve_comment` / " +
            "`reopen_comment` without `project_key` and `repo_slug`, then wonders why it gets a missing-param error. " +
            "The read/lifecycle actions require all three of project_key, repo_slug, and pr_id — unlike the write " +
            "actions (add_pr_comment, add_inline_comment, reply_to_comment, add_reviewer, remove_reviewer, " +
            "set_reviewer_status) which derive the repo from the plugin's active-repo setting."
        )
        downside(
            "Parameter surface is asymmetric: add/reply/reviewer actions take only `pr_id` (active-repo inferred), " +
            "while list/get/edit/delete/resolve/reopen require `project_key` + `repo_slug` + `pr_id`. The LLM sees " +
            "this as inconsistency and frequently passes or omits the wrong set, causing missing-param errors that " +
            "cost an extra round-trip."
        )
        downside(
            "No attachment support (Bitbucket DC REST does not expose comment attachments via the v1 API — confirmed " +
            "in audit reference memo). No reaction support (also absent from DC API). Cloud SHA-anchor inline comments " +
            "differ from DC's line-number-based inline model — this tool is DC-only."
        )
        downside(
            "Optimistic locking on `edit_comment` / `delete_comment` requires a prior `get_comment` call to obtain " +
            "the current version. This is always a two-tool sequence; the LLM can't batch it."
        )
        downside(
            "REVIEWER worker type is allowed, but code-reviewer subagents typically only read — giving them write " +
            "access (add_pr_comment, set_reviewer_status) is broader than their role suggests. Consider restricting " +
            "to read-only actions for REVIEWER workers in a future revision."
        )
        flowchart(
            """
            flowchart TD
                A[Need to interact with PR review] --> B{What kind?}
                B -- post top-level --> C[add_pr_comment pr_id text]
                B -- post inline --> D[add_inline_comment pr_id file_path line line_type text]
                B -- reply to thread --> E[reply_to_comment pr_id parent_comment_id text]
                B -- reviewer management --> F{Which?}
                F -- add --> G[add_reviewer pr_id username]
                F -- remove --> H[remove_reviewer pr_id username]
                F -- approve/LGTM --> I[set_reviewer_status pr_id username status=APPROVED]
                B -- read comments --> J[list_comments project_key repo_slug pr_id]
                J --> K{Need one comment?}
                K -- yes --> L[get_comment project_key repo_slug pr_id comment_id]
                B -- edit comment --> M[get_comment → expected_version]
                M --> N[edit_comment project_key repo_slug pr_id comment_id text expected_version]
                B -- delete comment --> O[get_comment → expected_version]
                O --> P[delete_comment project_key repo_slug pr_id comment_id expected_version]
                B -- resolve thread --> Q[resolve_comment project_key repo_slug pr_id comment_id]
                B -- reopen thread --> R[reopen_comment project_key repo_slug pr_id comment_id]
            """
        )
        related("bitbucket_pr", Relationship.COMPLEMENT,
            "bitbucket_pr handles PR lifecycle (create, approve, merge, decline, get detail, participants, " +
            "build requirements). bitbucket_review handles the review conversation layer (comments, inline " +
            "notes, reviewer roster changes). A full 'review this PR' workflow uses both tools: bitbucket_pr " +
            "to read the PR and check merge readiness, bitbucket_review to post findings."
        )
        related("jira", Relationship.SEE_ALSO,
            "When a PR is linked to a Jira ticket, combine jira.get_ticket(include_dev_status=true) to check " +
            "overall PR status with bitbucket_review to post review comments — the agent can see Jira context " +
            "and surface it as inline comments on the relevant diff lines."
        )
        related("bitbucket_repo", Relationship.COMPLEMENT,
            "bitbucket_repo provides file content, branch info, and commit context needed before writing " +
            "a meaningful inline comment. Use bitbucket_repo.get_file_content to read the target file, " +
            "then bitbucket_review.add_inline_comment to annotate a specific line."
        )
        observation(
            "AUDIT: Split between bitbucket_pr (19 actions) and bitbucket_review (12 actions) was intentional — " +
            "PR lifecycle ops vs review conversation ops. Total is 31 actions. A merge into a single 'bitbucket_pr' " +
            "with 31 actions would exceed a sensible meta-tool size (Jira is 17, bamboo_builds is 11). More critically, " +
            "the registration condition is the same for both (Bitbucket URL configured), so there is no conditional " +
            "split reason. The main argument for merging is LLM discoverability (the LLM currently has to know both " +
            "tool names exist); the main argument against is token cost — merging would send ~30 action descriptions " +
            "to the LLM every call instead of the current ~12. Given the deferred-load tier, keeping the split is " +
            "preferable: the LLM loads only the tool it needs. Recommendation: keep split, but add a cross-reference " +
            "hint in both tools' descriptions so the LLM discovers the companion tool from tool_search results."
        )
        verdict {
            keep(
                "All 12 actions cover distinct Bitbucket review API surface that would otherwise require the LLM " +
                "to construct raw curl invocations with credentials embedded in command arguments. The optimistic-locking " +
                "contract for edit/delete is the highest-value part — without it, concurrent edits silently clobber each " +
                "other. The inline-comment action (add_inline_comment) is particularly important for code-review subagents " +
                "that need to annotate specific diff lines. KEEP all 12 actions.",
                VerdictSeverity.STRONG
            )
        }
        actions {
            // ── WRITE ACTIONS (active-repo inferred, only pr_id required) ──────────────
            action("add_pr_comment") {
                description {
                    technical(
                        "Posts a general (top-level) comment on the PR. Active-repo is inferred from plugin settings; " +
                        "optional `repo_name` overrides for multi-repo projects."
                    )
                    plain(
                        "Writes a new comment in the PR's activity feed — like clicking 'Add comment' at the bottom " +
                        "of the PR page. Creates a new thread, not a reply."
                    )
                }
                whenLLMUses(
                    "When the user asks to leave a review note, summarize findings, or flag a concern on the PR as a whole " +
                    "(not tied to a specific file/line). Also used at the end of a review workflow to post a summary comment."
                )
                params {
                    required("pr_id", "string") {
                        llmSeesIt("Pull request ID (numeric) — required for all actions")
                        humanReadable("The numeric ID of the pull request (shown in the Bitbucket PR URL, e.g. /pull-requests/42).")
                        whenPresent("Parsed to Int via BitbucketToolUtils.parsePrId; tool returns invalidPrId() if non-numeric.")
                        constraint("must be a numeric string — no '#' prefix")
                        example("42")
                        example("1")
                    }
                    required("text", "string") {
                        llmSeesIt("Comment/reply text — for add_pr_comment, add_inline_comment, reply_to_comment")
                        humanReadable("The comment body to post. Plain text; Bitbucket DC renders basic markdown.")
                        whenPresent("Validated non-blank (validateNotBlank) before posting.")
                        constraint("must be non-blank")
                        example("LGTM — reviewed auth and error-handling paths. No blocking issues.")
                        example("This PR is missing test coverage for the error path in UserService.kt:87.")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects — omit for primary")
                        humanReadable("Override the active repository. Only needed when the workspace contains multiple repos and you're targeting a non-primary one.")
                        whenPresent("Service resolves Bitbucket client against this repo name.")
                        whenAbsent("Active/primary repo from plugin settings is used.")
                        example("backend-api")
                    }
                    optional("description", "string") {
                        llmSeesIt("Approval dialog description for write actions: add_pr_comment, set_reviewer_status")
                        humanReadable("One-line label shown in the agent's write-action approval dialog.")
                        whenPresent("Shown to user in the approval gate.")
                        whenAbsent("Approval dialog falls back to action name + pr_id.")
                        example("Post review summary on PR #42")
                    }
                }
                onSuccess("Returns confirmation with the new comment ID and a short preview of the posted text.")
                onFailure("pr_id not numeric", "BitbucketToolUtils.parsePrId returns null → invalidPrId() error result. No network call made.")
                onFailure("blank text", "validateNotBlank rejects before the network call.")
                onFailure("PR not found", "Bitbucket returns 404 → surfaced as error result. LLM should confirm pr_id with user.")
                onFailure("401 Unauthorized", "Token expired/revoked. Stop and ask user to re-enter token in Settings.")
                example("general review sign-off") {
                    param("action", "add_pr_comment")
                    param("pr_id", "42")
                    param("text", "Reviewed all three changed files. Auth logic is correct. No blocking issues — LGTM.")
                    param("description", "Post review sign-off on PR #42")
                    outcome("New top-level comment appears in the PR activity feed.")
                }
                verdict {
                    keep("Most common review write action — any 'leave a comment' request lands here.", VerdictSeverity.STRONG)
                }
            }

            action("add_inline_comment") {
                description {
                    technical(
                        "Posts an inline (line-level) comment on a specific line of a specific file in the diff. " +
                        "`line_type` must be ADDED, REMOVED, or CONTEXT to indicate which diff side the line lives on."
                    )
                    plain(
                        "Clicks on a specific line in the diff and types a comment — like hovering over a line " +
                        "in the Bitbucket diff view and clicking the '+' button. The comment is pinned to that " +
                        "exact line number in that file."
                    )
                }
                whenLLMUses(
                    "When reviewing code and the agent wants to annotate a specific problematic or noteworthy line — " +
                    "'this function at line 87 has an NPE risk', 'this regex on line 42 has a backtracking issue'. " +
                    "More precise than a general comment; preferred by code-review subagents."
                )
                params {
                    required("pr_id", "string") {
                        llmSeesIt("Pull request ID (numeric) — required for all actions")
                        humanReadable("The numeric PR ID.")
                        whenPresent("Validated as numeric int.")
                        constraint("must be numeric, no '#' prefix")
                        example("42")
                    }
                    required("file_path", "string") {
                        llmSeesIt("File path — for add_inline_comment")
                        humanReadable("The path of the file containing the target line, relative to the repository root.")
                        whenPresent("Passed to the Bitbucket inline comment API as the file path anchor.")
                        constraint("must be the repo-relative path, not an absolute filesystem path")
                        example("src/main/kotlin/com/example/UserService.kt")
                        example("README.md")
                    }
                    required("line", "string") {
                        llmSeesIt("Line number — for add_inline_comment")
                        humanReadable("The 1-based line number in the file to annotate.")
                        whenPresent("Parsed to Int via toIntOrNull(); non-numeric causes error before API call.")
                        constraint("must be a numeric string, representing a 1-based line number")
                        example("87")
                        example("42")
                    }
                    required("line_type", "string") {
                        llmSeesIt("Line type: ADDED, REMOVED, CONTEXT — for add_inline_comment")
                        humanReadable(
                            "Which side of the diff the line belongs to. ADDED = new file side (green line), " +
                            "REMOVED = old file side (red line), CONTEXT = unchanged line visible on both sides."
                        )
                        whenPresent("Passed as the diff-context discriminator to the Bitbucket API.")
                        constraint("must be one of: ADDED, REMOVED, CONTEXT (case-sensitive)")
                        enumValue("ADDED", "REMOVED", "CONTEXT")
                        example("ADDED")
                        example("CONTEXT")
                    }
                    required("text", "string") {
                        llmSeesIt("Comment/reply text — for add_pr_comment, add_inline_comment, reply_to_comment")
                        humanReadable("The inline comment text to post.")
                        whenPresent("Validated non-blank.")
                        constraint("must be non-blank")
                        example("This pattern creates a new DB connection on every call — consider injecting the DataSource.")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects — omit for primary")
                        humanReadable("Override the active repo for multi-repo projects.")
                        whenPresent("Service uses this repo instead of the primary.")
                        whenAbsent("Primary repo from plugin settings used.")
                        example("backend-api")
                    }
                }
                precondition("The file_path must exist in the PR's diff. Commenting on a file not in the diff returns a Bitbucket API 400.")
                precondition("line must fall within the diff range for the given file and line_type.")
                onSuccess("Returns confirmation with the new inline comment ID, file path, and line number.")
                onFailure("line not numeric", "toIntOrNull() fails → 400-style error result before API call.")
                onFailure("wrong line_type", "Bitbucket returns 400 if the line_type doesn't match what's actually in the diff. LLM must re-check the diff context.")
                onFailure("file not in diff", "Bitbucket API returns 400. LLM should verify the file path is part of this PR's changed files.")
                example("annotate a suspicious null check") {
                    param("action", "add_inline_comment")
                    param("pr_id", "42")
                    param("file_path", "src/main/kotlin/com/example/OrderService.kt")
                    param("line", "87")
                    param("line_type", "ADDED")
                    param("text", "This null check can never be true here — `order` is guaranteed non-null by the calling contract at line 60. Consider removing or converting to a require().")
                    outcome("Inline comment pinned to line 87 of OrderService.kt in the PR diff.")
                }
                verdict {
                    keep(
                        "Unique capability — inline annotation is unavailable via any other tool path. High-value for code-review subagents producing actionable per-line feedback.",
                        VerdictSeverity.STRONG
                    )
                }
            }

            action("reply_to_comment") {
                description {
                    technical(
                        "Adds a reply to an existing comment thread. `parent_comment_id` identifies the root comment " +
                        "of the thread; Bitbucket groups the reply under that thread."
                    )
                    plain(
                        "Replies to an existing review comment — like clicking 'Reply' under a comment in the PR thread. " +
                        "Keeps the conversation grouped rather than starting a new top-level thread."
                    )
                }
                whenLLMUses(
                    "When the user or a reviewer has left a comment the agent needs to respond to, or when the agent " +
                    "wants to add context to an existing thread it started (e.g. 'updated, fixed in commit abc123')."
                )
                params {
                    required("pr_id", "string") {
                        llmSeesIt("Pull request ID (numeric) — required for all actions")
                        humanReadable("The numeric PR ID.")
                        whenPresent("Validated as numeric.")
                        constraint("numeric string, no '#'")
                        example("42")
                    }
                    required("parent_comment_id", "string") {
                        llmSeesIt("Parent comment ID (integer) — for reply_to_comment")
                        humanReadable("The ID of the existing comment to reply to. Comes from `list_comments` or `get_comment` output.")
                        whenPresent("Parsed to Int via toIntOrNull(); non-numeric → error before API call.")
                        constraint("must be a numeric string — the integer comment ID from a prior list_comments or get_comment call")
                        example("1001")
                        example("789")
                    }
                    required("text", "string") {
                        llmSeesIt("Comment/reply text — for add_pr_comment, add_inline_comment, reply_to_comment")
                        humanReadable("The reply text.")
                        whenPresent("Validated non-blank.")
                        constraint("must be non-blank")
                        example("Fixed in the latest commit — moved the connection pool initialization to the constructor.")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects — omit for primary")
                        humanReadable("Override the active repo for multi-repo projects.")
                        whenPresent("Service resolves against this repo.")
                        whenAbsent("Primary repo used.")
                        example("backend-api")
                    }
                }
                onSuccess("Returns confirmation with the new reply comment ID.")
                onFailure("parent_comment_id not numeric", "toIntOrNull() fails → error before API call.")
                onFailure("parent comment not found", "Bitbucket 404 → surfaced as error. LLM should use list_comments to find valid IDs.")
                example("acknowledge a reviewer's inline comment") {
                    param("action", "reply_to_comment")
                    param("pr_id", "42")
                    param("parent_comment_id", "1001")
                    param("text", "Good catch — addressed in commit b3f2a1d. The connection is now reused from the injected DataSource.")
                    outcome("Reply appears nested under comment 1001 in the PR thread.")
                }
                verdict {
                    keep("Required for structured review conversations — without it the agent always starts new threads, fragmenting the review.", VerdictSeverity.NORMAL)
                }
            }

            // ── REVIEWER MANAGEMENT ────────────────────────────────────────────────────
            action("add_reviewer") {
                description {
                    technical(
                        "Adds a user to the PR's reviewer roster. `username` must be the Bitbucket username " +
                        "(slug), not the display name."
                    )
                    plain(
                        "Adds someone as a reviewer to the PR — like clicking 'Reviewers ▸ Add reviewer' in the " +
                        "Bitbucket sidebar. The user gets a notification and appears in the participants list."
                    )
                }
                whenLLMUses(
                    "When the user asks to add a reviewer ('can you add @alice as a reviewer?') or when the agent " +
                    "determines from Jira/ticket context that a specific person should review this change."
                )
                params {
                    required("pr_id", "string") {
                        llmSeesIt("Pull request ID (numeric) — required for all actions")
                        humanReadable("The numeric PR ID.")
                        whenPresent("Validated as numeric.")
                        constraint("numeric string, no '#'")
                        example("42")
                    }
                    required("username", "string") {
                        llmSeesIt("Reviewer username — for add_reviewer, remove_reviewer, set_reviewer_status")
                        humanReadable("The Bitbucket username (account slug) of the reviewer to add. Use bitbucket_repo.search_users to resolve a name to a username.")
                        whenPresent("Validated non-blank; passed to the Bitbucket add-reviewer API.")
                        constraint("must be the Bitbucket account slug, not the display name or email")
                        example("alice.smith")
                        example("jdoe")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects — omit for primary")
                        humanReadable("Override the active repo.")
                        whenPresent("Uses this repo instead of primary.")
                        whenAbsent("Primary repo used.")
                        example("backend-api")
                    }
                }
                onSuccess("Returns confirmation that the user was added as a reviewer.")
                onFailure("username not found", "Bitbucket 404 or 400 if the user doesn't exist. LLM should use bitbucket_repo.search_users to find the correct username.")
                onFailure("user already a reviewer", "Bitbucket may return 400. Idempotent retry is safe on some DC versions.")
                example("add reviewer by username") {
                    param("action", "add_reviewer")
                    param("pr_id", "42")
                    param("username", "alice.smith")
                    outcome("Alice appears in the PR reviewers list and receives a notification.")
                }
                verdict {
                    keep("Required for reviewer assignment workflows — no alternative path.", VerdictSeverity.NORMAL)
                }
            }

            action("remove_reviewer") {
                description {
                    technical("Removes a user from the PR's reviewer roster.")
                    plain("Removes someone from the reviewers list — like clicking the ✕ next to a reviewer's name in the Bitbucket sidebar.")
                }
                whenLLMUses("When the user asks to remove a reviewer who was added by mistake or is no longer relevant.")
                params {
                    required("pr_id", "string") {
                        llmSeesIt("Pull request ID (numeric) — required for all actions")
                        humanReadable("The numeric PR ID.")
                        whenPresent("Validated as numeric.")
                        constraint("numeric string, no '#'")
                        example("42")
                    }
                    required("username", "string") {
                        llmSeesIt("Reviewer username — for add_reviewer, remove_reviewer, set_reviewer_status")
                        humanReadable("The Bitbucket account slug of the reviewer to remove.")
                        whenPresent("Validated non-blank; passed to remove-reviewer API.")
                        constraint("must be the Bitbucket account slug")
                        example("alice.smith")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects — omit for primary")
                        humanReadable("Override the active repo.")
                        whenPresent("Uses this repo.")
                        whenAbsent("Primary repo used.")
                        example("backend-api")
                    }
                }
                onSuccess("Returns confirmation that the reviewer was removed.")
                onFailure("user not a reviewer", "Bitbucket 400/404. Removing a non-reviewer is harmless on some DC versions but may error on others.")
                example("remove accidental reviewer") {
                    param("action", "remove_reviewer")
                    param("pr_id", "42")
                    param("username", "bob.jones")
                    outcome("Bob is removed from the PR reviewer list.")
                }
                verdict {
                    keep("Completes reviewer lifecycle management alongside add_reviewer.", VerdictSeverity.WEAK)
                }
            }

            action("set_reviewer_status") {
                description {
                    technical(
                        "Sets a reviewer's approval status to APPROVED, NEEDS_WORK, or UNAPPROVED. " +
                        "The `status` field is validated client-side before the API call."
                    )
                    plain(
                        "Sets the thumbs-up / needs-more-work / withdrawn-review status for a specific reviewer — " +
                        "like clicking 'Approve', 'Request changes', or 'Unapprove' in the Bitbucket review UI."
                    )
                }
                whenLLMUses(
                    "When the user asks to approve the PR ('approve PR #42'), mark it as needing work, or withdraw an earlier approval."
                )
                params {
                    required("pr_id", "string") {
                        llmSeesIt("Pull request ID (numeric) — required for all actions")
                        humanReadable("The numeric PR ID.")
                        whenPresent("Validated as numeric.")
                        constraint("numeric string, no '#'")
                        example("42")
                    }
                    required("username", "string") {
                        llmSeesIt("Reviewer username — for add_reviewer, remove_reviewer, set_reviewer_status")
                        humanReadable("The Bitbucket account slug of the reviewer whose status to change. Typically the current user's username.")
                        whenPresent("Validated non-blank; identifies the participant row to update.")
                        constraint("must be the Bitbucket account slug")
                        example("alice.smith")
                    }
                    required("status", "string") {
                        llmSeesIt("Reviewer status: APPROVED, NEEDS_WORK, UNAPPROVED — for set_reviewer_status")
                        humanReadable(
                            "The new approval status: APPROVED (approved), NEEDS_WORK (changes requested), " +
                            "UNAPPROVED (neutral / withdrawn approval)."
                        )
                        whenPresent("Validated against the allowed set {APPROVED, NEEDS_WORK, UNAPPROVED} before API call.")
                        constraint("must be exactly one of: APPROVED, NEEDS_WORK, UNAPPROVED")
                        enumValue("APPROVED", "NEEDS_WORK", "UNAPPROVED")
                        example("APPROVED")
                        example("NEEDS_WORK")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects — omit for primary")
                        humanReadable("Override the active repo.")
                        whenPresent("Uses this repo.")
                        whenAbsent("Primary repo used.")
                        example("backend-api")
                    }
                    optional("description", "string") {
                        llmSeesIt("Approval dialog description for write actions: add_pr_comment, set_reviewer_status")
                        humanReadable("One-line label for the approval dialog.")
                        whenPresent("Shown in the approval gate.")
                        whenAbsent("Falls back to action name + pr_id.")
                        example("Approve PR #42 as alice.smith")
                    }
                }
                precondition("username must already be a participant/reviewer on the PR before status can be set.")
                onSuccess("Returns confirmation with the new status.")
                onFailure("invalid status", "Validated client-side; returns error before API call if not one of the three values.")
                onFailure("user not a participant", "Bitbucket 400. LLM should call add_reviewer first, then set status.")
                example("approve a PR") {
                    param("action", "set_reviewer_status")
                    param("pr_id", "42")
                    param("username", "alice.smith")
                    param("status", "APPROVED")
                    param("description", "Approve PR #42 after review")
                    outcome("Alice's reviewer status changes to APPROVED; PR shows approval count incremented.")
                }
                example("request changes") {
                    param("action", "set_reviewer_status")
                    param("pr_id", "42")
                    param("username", "alice.smith")
                    param("status", "NEEDS_WORK")
                    outcome("PR shows a 'Needs work' status badge from Alice's perspective.")
                }
                verdict {
                    keep("Approval/LGTM is the most common reviewer action. Without this, the agent can't close the review loop.", VerdictSeverity.STRONG)
                }
            }

            // ── COMMENT LIFECYCLE (require project_key + repo_slug + pr_id) ───────────
            action("list_comments") {
                description {
                    technical(
                        "Lists all comments on a PR. Optional `only_open` and `only_inline` booleans filter the " +
                        "result client-side after fetching. Requires `project_key` + `repo_slug` + `pr_id`."
                    )
                    plain(
                        "Returns all comments on the PR — like scrolling through the Bitbucket 'Activity' and " +
                        "'Diff' tabs and collecting every annotation. Can filter to only unresolved or only inline."
                    )
                }
                whenLLMUses(
                    "Before replying to, editing, or resolving a comment (to find the comment's ID and current version). " +
                    "Also used to summarize review feedback or check review completeness."
                )
                params {
                    required("project_key", "string") {
                        llmSeesIt("Bitbucket project key (e.g. PROJ) — for list_comments, get_comment, edit_comment, delete_comment, resolve_comment, reopen_comment")
                        humanReadable("The Bitbucket project key — the short code shown in the repo URL (e.g. /projects/PROJ/...).")
                        whenPresent("Used to construct the Bitbucket API URL for the comments endpoint.")
                        constraint("uppercase short key, e.g. 'PROJ', 'BACKEND'")
                        example("PROJ")
                        example("BACKEND")
                    }
                    required("repo_slug", "string") {
                        llmSeesIt("Repository slug — for list_comments, get_comment, edit_comment, delete_comment, resolve_comment, reopen_comment")
                        humanReadable("The repository slug as it appears in the Bitbucket URL (lowercase, hyphenated).")
                        whenPresent("Used to construct the full Bitbucket API path.")
                        constraint("lowercase slug matching the repository's URL segment")
                        example("backend-api")
                        example("my-service")
                    }
                    required("pr_id", "string") {
                        llmSeesIt("Pull request ID (numeric) — required for all actions")
                        humanReadable("The numeric PR ID.")
                        whenPresent("Validated as numeric.")
                        constraint("numeric string, no '#'")
                        example("42")
                    }
                    optional("only_open", "string") {
                        llmSeesIt("Filter to open comments only: true/false — for list_comments")
                        humanReadable("When 'true', only returns unresolved (open) comments. Default false (all comments returned).")
                        whenPresent("Parsed via toBoolean(); 'true' filters to unresolved only.")
                        whenAbsent("All comments returned (both open and resolved).")
                        example("true")
                    }
                    optional("only_inline", "string") {
                        llmSeesIt("Filter to inline comments only: true/false — for list_comments")
                        humanReadable("When 'true', only returns inline (line-level) comments. Default false.")
                        whenPresent("Parsed via toBoolean(); 'true' filters to inline-only comments.")
                        whenAbsent("All comment types returned.")
                        example("true")
                    }
                }
                rejectsParam("text", "list_comments reads comments; it does not post. `text` is for add_pr_comment / add_inline_comment / reply_to_comment.")
                rejectsParam("username", "list_comments doesn't filter by author. Use client-side filtering on the returned list.")
                onSuccess("Returns a list of comment objects with: id, text, author, created date, resolution status, and anchor (file path + line for inline comments).")
                onFailure("PR not found", "404 → error result. Verify project_key + repo_slug + pr_id.")
                onFailure("403", "Lacks Browse permission on the repo.")
                example("list all open inline comments before resolving") {
                    param("action", "list_comments")
                    param("project_key", "PROJ")
                    param("repo_slug", "backend-api")
                    param("pr_id", "42")
                    param("only_open", "true")
                    param("only_inline", "true")
                    outcome("Returns only unresolved inline comment objects, each with id, text, file path, and line number. LLM uses IDs for resolve_comment calls.")
                }
                verdict {
                    keep("Required precondition for edit/delete/resolve/reopen — without a comment ID+version the write actions can't proceed.", VerdictSeverity.STRONG)
                }
            }

            action("get_comment") {
                description {
                    technical(
                        "Fetches a single comment by ID. Returns the comment's current version number — " +
                        "required as `expected_version` for edit_comment and delete_comment (optimistic locking)."
                    )
                    plain(
                        "Reads one specific comment by its ID. The main reason to call this is to get the " +
                        "current version number before editing or deleting the comment — Bitbucket requires it " +
                        "to prevent two people overwriting each other."
                    )
                }
                whenLLMUses(
                    "Immediately before calling edit_comment or delete_comment, to obtain the current `version` " +
                    "field for optimistic locking. Also useful to verify a specific comment's text before acting on it."
                )
                params {
                    required("project_key", "string") {
                        llmSeesIt("Bitbucket project key (e.g. PROJ) — for list_comments, get_comment, edit_comment, delete_comment, resolve_comment, reopen_comment")
                        humanReadable("The Bitbucket project key.")
                        whenPresent("Used in the API path.")
                        constraint("uppercase project key")
                        example("PROJ")
                    }
                    required("repo_slug", "string") {
                        llmSeesIt("Repository slug — for list_comments, get_comment, edit_comment, delete_comment, resolve_comment, reopen_comment")
                        humanReadable("The repository slug.")
                        whenPresent("Used in the API path.")
                        constraint("lowercase slug")
                        example("backend-api")
                    }
                    required("pr_id", "string") {
                        llmSeesIt("Pull request ID (numeric) — required for all actions")
                        humanReadable("The numeric PR ID.")
                        whenPresent("Validated as numeric.")
                        constraint("numeric string")
                        example("42")
                    }
                    required("comment_id", "string") {
                        llmSeesIt("Comment ID (integer) — for get_comment, edit_comment, delete_comment, resolve_comment, reopen_comment")
                        humanReadable("The integer ID of the comment to fetch. Comes from list_comments output.")
                        whenPresent("Parsed to Long via toLongOrNull(); non-numeric → error before API call.")
                        constraint("must be a numeric string — the integer comment ID from list_comments")
                        example("1001")
                    }
                }
                onSuccess("Returns the full comment object including: id, text, author, created, updated, version (the optimistic-lock token), and anchor (for inline comments).")
                onFailure("comment_id not numeric", "toLongOrNull() fails → error before API call.")
                onFailure("comment not found", "404 from Bitbucket. LLM should re-fetch list_comments to confirm the ID.")
                example("pre-edit version fetch") {
                    param("action", "get_comment")
                    param("project_key", "PROJ")
                    param("repo_slug", "backend-api")
                    param("pr_id", "42")
                    param("comment_id", "1001")
                    outcome("Returns comment object with version=3. LLM uses version=3 as expected_version in the next edit_comment call.")
                }
                verdict {
                    keep("Required gateway for edit_comment and delete_comment — the version field is the only safe way to prevent optimistic-lock conflicts.", VerdictSeverity.STRONG)
                }
            }

            action("edit_comment") {
                description {
                    technical(
                        "Updates the text of an existing comment. Uses optimistic locking: `expected_version` must " +
                        "match the server's current version or Bitbucket returns 409 STALE_VERSION. Obtain version " +
                        "from a prior get_comment call."
                    )
                    plain(
                        "Edits an existing comment — like clicking the pencil icon on your own comment and saving. " +
                        "Requires the comment's current version number (fetched via get_comment) to prevent " +
                        "accidentally overwriting someone else's concurrent edit."
                    )
                }
                whenLLMUses(
                    "When the agent or user realizes a posted comment has a mistake, is outdated, or should be " +
                    "expanded. Always preceded by get_comment to retrieve the current version."
                )
                params {
                    required("project_key", "string") {
                        llmSeesIt("Bitbucket project key (e.g. PROJ) — for list_comments, get_comment, edit_comment, delete_comment, resolve_comment, reopen_comment")
                        humanReadable("The Bitbucket project key.")
                        whenPresent("Used in the API path.")
                        constraint("uppercase project key")
                        example("PROJ")
                    }
                    required("repo_slug", "string") {
                        llmSeesIt("Repository slug — for list_comments, get_comment, edit_comment, delete_comment, resolve_comment, reopen_comment")
                        humanReadable("The repository slug.")
                        whenPresent("Used in the API path.")
                        constraint("lowercase slug")
                        example("backend-api")
                    }
                    required("pr_id", "string") {
                        llmSeesIt("Pull request ID (numeric) — required for all actions")
                        humanReadable("The numeric PR ID.")
                        whenPresent("Validated as numeric.")
                        constraint("numeric string")
                        example("42")
                    }
                    required("comment_id", "string") {
                        llmSeesIt("Comment ID (integer) — for get_comment, edit_comment, delete_comment, resolve_comment, reopen_comment")
                        humanReadable("The integer ID of the comment to edit.")
                        whenPresent("Parsed to Long.")
                        constraint("numeric string")
                        example("1001")
                    }
                    required("text", "string") {
                        llmSeesIt("Comment/reply text — for add_pr_comment, add_inline_comment, reply_to_comment")
                        humanReadable("The new comment text to replace the existing body.")
                        whenPresent("Validated non-blank.")
                        constraint("must be non-blank")
                        example("Updated after fix: the connection pooling issue has been resolved in commit b3f2a1d.")
                    }
                    required("expected_version", "string") {
                        llmSeesIt("Current comment version for optimistic locking — for edit_comment, delete_comment")
                        humanReadable("The comment's current version number from a prior get_comment call. Bitbucket increments this on every edit; passing a stale value causes a 409 STALE_VERSION error.")
                        whenPresent("Parsed to Int via toIntOrNull(); passed as the If-Match / version parameter to the Bitbucket API.")
                        constraint("must be the exact integer version from the most recent get_comment response")
                        example("3")
                        example("1")
                    }
                }
                precondition("Must call get_comment first to obtain the current version number.")
                precondition("User must have edit permission on this comment (typically: own comments only).")
                onSuccess("Returns the updated comment object with the new version number.")
                onFailure("STALE_VERSION / 409", "The comment was edited by someone else between get_comment and edit_comment. LLM must call get_comment again to obtain the new version, then retry.")
                onFailure("expected_version not numeric", "toIntOrNull() fails → error before API call.")
                onFailure("blank text", "validateNotBlank rejects before API call.")
                onFailure("403", "User doesn't own this comment or lacks edit permission.")
                example("fix a typo in a comment") {
                    param("action", "edit_comment")
                    param("project_key", "PROJ")
                    param("repo_slug", "backend-api")
                    param("pr_id", "42")
                    param("comment_id", "1001")
                    param("text", "This null check is unreachable — `order` is guaranteed non-null by the factory at line 60. Remove or replace with a require().")
                    param("expected_version", "3")
                    outcome("Comment text updated; Bitbucket increments version to 4.")
                    notes("If version was stale (e.g. another reviewer edited the comment), Bitbucket returns 409. LLM re-fetches with get_comment and retries.")
                }
                verdict {
                    keep("Optimistic locking makes edit safe. Without this action the LLM would have to do a raw PATCH with manually crafted JSON.", VerdictSeverity.NORMAL)
                }
            }

            action("delete_comment") {
                description {
                    technical(
                        "Permanently deletes a comment. Uses optimistic locking (`expected_version`), same as edit_comment. " +
                        "Deletion is irreversible — the comment disappears from the audit trail."
                    )
                    plain(
                        "Deletes a comment permanently — like clicking the trash icon on your own comment and confirming. " +
                        "Unlike resolving (which hides the thread but keeps it), deleting removes it entirely."
                    )
                }
                whenLLMUses(
                    "When the user explicitly asks to delete a comment. Typically rare — resolve_comment is preferred " +
                    "because it keeps the audit trail visible."
                )
                params {
                    required("project_key", "string") {
                        llmSeesIt("Bitbucket project key (e.g. PROJ) — for list_comments, get_comment, edit_comment, delete_comment, resolve_comment, reopen_comment")
                        humanReadable("The Bitbucket project key.")
                        whenPresent("Used in the API path.")
                        constraint("uppercase project key")
                        example("PROJ")
                    }
                    required("repo_slug", "string") {
                        llmSeesIt("Repository slug — for list_comments, get_comment, edit_comment, delete_comment, resolve_comment, reopen_comment")
                        humanReadable("The repository slug.")
                        whenPresent("Used in the API path.")
                        constraint("lowercase slug")
                        example("backend-api")
                    }
                    required("pr_id", "string") {
                        llmSeesIt("Pull request ID (numeric) — required for all actions")
                        humanReadable("The numeric PR ID.")
                        whenPresent("Validated as numeric.")
                        constraint("numeric string")
                        example("42")
                    }
                    required("comment_id", "string") {
                        llmSeesIt("Comment ID (integer) — for get_comment, edit_comment, delete_comment, resolve_comment, reopen_comment")
                        humanReadable("The integer ID of the comment to delete.")
                        whenPresent("Parsed to Long.")
                        constraint("numeric string")
                        example("1001")
                    }
                    required("expected_version", "string") {
                        llmSeesIt("Current comment version for optimistic locking — for edit_comment, delete_comment")
                        humanReadable("The comment's current version from a prior get_comment call.")
                        whenPresent("Parsed to Int; passed as the optimistic-lock version to Bitbucket.")
                        constraint("must be the integer version from the most recent get_comment response")
                        example("3")
                    }
                }
                precondition("Must call get_comment first to obtain the current version.")
                precondition("Deletion is permanent and not recoverable via the API. Prefer resolve_comment for soft-hiding.")
                onSuccess("Returns confirmation of deletion. The comment no longer appears in list_comments.")
                onFailure("STALE_VERSION / 409", "Version mismatch — call get_comment again, then retry.")
                onFailure("403", "User doesn't own this comment or lacks delete permission.")
                example("delete an accidentally posted comment") {
                    param("action", "delete_comment")
                    param("project_key", "PROJ")
                    param("repo_slug", "backend-api")
                    param("pr_id", "42")
                    param("comment_id", "1001")
                    param("expected_version", "1")
                    outcome("Comment 1001 is permanently removed from the PR.")
                }
                verdict {
                    keep("Needed for cleanup; rare in practice — resolve_comment is usually preferred.", VerdictSeverity.WEAK)
                }
            }

            action("resolve_comment") {
                description {
                    technical(
                        "Marks a comment thread as resolved — the thread is collapsed in the Bitbucket UI but " +
                        "remains in the audit log. Does NOT require expected_version (no optimistic locking)."
                    )
                    plain(
                        "Resolves an open review thread — like clicking the 'Resolve' button on a comment after " +
                        "the fix has been addressed. The thread is hidden but not deleted; reviewers can still see it."
                    )
                }
                whenLLMUses(
                    "After confirming a fix is in place for a specific inline comment or review concern. Part of " +
                    "the 'close out review feedback' workflow — list open comments, for each fixed issue call " +
                    "resolve_comment."
                )
                params {
                    required("project_key", "string") {
                        llmSeesIt("Bitbucket project key (e.g. PROJ) — for list_comments, get_comment, edit_comment, delete_comment, resolve_comment, reopen_comment")
                        humanReadable("The Bitbucket project key.")
                        whenPresent("Used in the API path.")
                        constraint("uppercase project key")
                        example("PROJ")
                    }
                    required("repo_slug", "string") {
                        llmSeesIt("Repository slug — for list_comments, get_comment, edit_comment, delete_comment, resolve_comment, reopen_comment")
                        humanReadable("The repository slug.")
                        whenPresent("Used in the API path.")
                        constraint("lowercase slug")
                        example("backend-api")
                    }
                    required("pr_id", "string") {
                        llmSeesIt("Pull request ID (numeric) — required for all actions")
                        humanReadable("The numeric PR ID.")
                        whenPresent("Validated as numeric.")
                        constraint("numeric string")
                        example("42")
                    }
                    required("comment_id", "string") {
                        llmSeesIt("Comment ID (integer) — for get_comment, edit_comment, delete_comment, resolve_comment, reopen_comment")
                        humanReadable("The integer ID of the comment thread to resolve.")
                        whenPresent("Parsed to Long.")
                        constraint("numeric string")
                        example("1001")
                    }
                }
                rejectsParam("expected_version", "resolve_comment does not use optimistic locking — no version parameter needed.")
                rejectsParam("text", "Resolving a comment doesn't post new text.")
                onSuccess("Returns confirmation that the thread is resolved. Thread appears collapsed in Bitbucket's review tab.")
                onFailure("comment already resolved", "Bitbucket may return 400. Safe to ignore — the thread is already in the desired state.")
                onFailure("403", "Lacks resolve permission (typically requires being the PR author or a project admin on DC).")
                example("resolve a fixed inline comment") {
                    param("action", "resolve_comment")
                    param("project_key", "PROJ")
                    param("repo_slug", "backend-api")
                    param("pr_id", "42")
                    param("comment_id", "1001")
                    outcome("Comment thread 1001 is marked resolved and collapsed in the review UI.")
                    notes("The thread remains visible in the 'All comments' filter — it's a soft hide, not a delete.")
                }
                verdict {
                    keep("High-value close-the-loop action — lets the agent mark review feedback as addressed without deleting the audit trail.", VerdictSeverity.STRONG)
                }
            }

            action("reopen_comment") {
                description {
                    technical("Reopens a previously resolved comment thread. No optimistic locking required.")
                    plain(
                        "Reopens a resolved review thread — like clicking 'Reopen' on a collapsed comment to indicate " +
                        "the issue was not actually fixed or needs further discussion."
                    )
                }
                whenLLMUses(
                    "When a reviewer determines that a 'resolved' comment was reopened prematurely (the fix was incomplete " +
                    "or the comment was resolved by mistake)."
                )
                params {
                    required("project_key", "string") {
                        llmSeesIt("Bitbucket project key (e.g. PROJ) — for list_comments, get_comment, edit_comment, delete_comment, resolve_comment, reopen_comment")
                        humanReadable("The Bitbucket project key.")
                        whenPresent("Used in the API path.")
                        constraint("uppercase project key")
                        example("PROJ")
                    }
                    required("repo_slug", "string") {
                        llmSeesIt("Repository slug — for list_comments, get_comment, edit_comment, delete_comment, resolve_comment, reopen_comment")
                        humanReadable("The repository slug.")
                        whenPresent("Used in the API path.")
                        constraint("lowercase slug")
                        example("backend-api")
                    }
                    required("pr_id", "string") {
                        llmSeesIt("Pull request ID (numeric) — required for all actions")
                        humanReadable("The numeric PR ID.")
                        whenPresent("Validated as numeric.")
                        constraint("numeric string")
                        example("42")
                    }
                    required("comment_id", "string") {
                        llmSeesIt("Comment ID (integer) — for get_comment, edit_comment, delete_comment, resolve_comment, reopen_comment")
                        humanReadable("The integer ID of the resolved comment thread to reopen.")
                        whenPresent("Parsed to Long.")
                        constraint("numeric string")
                        example("1001")
                    }
                }
                rejectsParam("expected_version", "reopen_comment does not use optimistic locking.")
                rejectsParam("text", "Reopening doesn't post new text; use reply_to_comment after reopening if you need to add context.")
                onSuccess("Returns confirmation that the thread is reopened and visible in the active review comments list.")
                onFailure("comment not resolved", "Bitbucket may return 400 if the thread isn't resolved. Safe to ignore.")
                onFailure("403", "Lacks reopen permission on this PR.")
                example("reopen a prematurely resolved thread") {
                    param("action", "reopen_comment")
                    param("project_key", "PROJ")
                    param("repo_slug", "backend-api")
                    param("pr_id", "42")
                    param("comment_id", "1001")
                    outcome("Thread 1001 moves from 'resolved' back to 'open' and reappears in the active comments tab.")
                }
                verdict {
                    keep("Completes the resolve/reopen cycle — needed for review workflows where comments are closed prematurely.", VerdictSeverity.WEAK)
                }
            }
        }
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        coroutineContext.ensureActive()
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'action' parameter required",
                "Error: missing action",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val service = ServiceLookup.bitbucket(project)
            ?: return ServiceLookup.notConfigured("Bitbucket")

        return when (action) {
            "add_pr_comment" -> {
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val text = params["text"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("text")
                ToolValidation.validateNotBlank(text, "text")?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.addPrComment(prId, text, repoName = repoName).toAgentToolResult()
            }

            "add_inline_comment" -> {
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val filePath = params["file_path"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("file_path")
                val lineStr = params["line"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("line")
                val line = lineStr.toIntOrNull() ?: return ToolResult(
                    "Error: 'line' must be an integer, got '$lineStr'",
                    "Error: invalid line",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
                val lineType = params["line_type"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("line_type")
                val text = params["text"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("text")
                ToolValidation.validateNotBlank(text, "text")?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.addInlineComment(prId, filePath, line, lineType, text, repoName = repoName).toAgentToolResult()
            }

            "reply_to_comment" -> {
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val parentIdStr = params["parent_comment_id"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("parent_comment_id")
                val parentId = parentIdStr.toIntOrNull() ?: return ToolResult(
                    "Error: 'parent_comment_id' must be an integer, got '$parentIdStr'",
                    "Error: invalid parent_comment_id",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
                val text = params["text"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("text")
                ToolValidation.validateNotBlank(text, "text")?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.replyToComment(prId, parentId, text, repoName = repoName).toAgentToolResult()
            }

            "add_reviewer" -> {
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val username = params["username"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("username")
                ToolValidation.validateNotBlank(username, "username")?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.addReviewer(prId, username, repoName = repoName).toAgentToolResult()
            }

            "remove_reviewer" -> {
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val username = params["username"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("username")
                ToolValidation.validateNotBlank(username, "username")?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.removeReviewer(prId, username, repoName = repoName).toAgentToolResult()
            }

            "set_reviewer_status" -> {
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val username = params["username"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("username")
                val status = params["status"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("status")
                if (status !in setOf("APPROVED", "NEEDS_WORK", "UNAPPROVED")) return ToolResult(
                    "Error: 'status' must be APPROVED, NEEDS_WORK, or UNAPPROVED",
                    "Error: invalid status",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.setReviewerStatus(prId, username, status, repoName = repoName).toAgentToolResult()
            }

            "list_comments" -> {
                val projectKey = params["project_key"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("project_key")
                val repoSlug = params["repo_slug"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("repo_slug")
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val onlyOpen = params["only_open"]?.jsonPrimitive?.content?.toBoolean() ?: false
                val onlyInline = params["only_inline"]?.jsonPrimitive?.content?.toBoolean() ?: false
                service.listPrComments(projectKey, repoSlug, prId, onlyOpen, onlyInline).toAgentToolResult()
            }

            "get_comment" -> {
                val projectKey = params["project_key"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("project_key")
                val repoSlug = params["repo_slug"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("repo_slug")
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val commentIdStr = params["comment_id"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("comment_id")
                val commentId = commentIdStr.toLongOrNull() ?: return ToolResult(
                    "Error: 'comment_id' must be an integer, got '$commentIdStr'",
                    "Error: invalid comment_id",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
                service.getPrComment(projectKey, repoSlug, prId, commentId).toAgentToolResult()
            }

            "edit_comment" -> {
                val projectKey = params["project_key"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("project_key")
                val repoSlug = params["repo_slug"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("repo_slug")
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val commentIdStr = params["comment_id"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("comment_id")
                val commentId = commentIdStr.toLongOrNull() ?: return ToolResult(
                    "Error: 'comment_id' must be an integer, got '$commentIdStr'",
                    "Error: invalid comment_id",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
                val text = params["text"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("text")
                ToolValidation.validateNotBlank(text, "text")?.let { return it }
                val versionStr = params["expected_version"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("expected_version")
                val expectedVersion = versionStr.toIntOrNull() ?: return ToolResult(
                    "Error: 'expected_version' must be an integer, got '$versionStr'",
                    "Error: invalid expected_version",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
                service.editPrComment(projectKey, repoSlug, prId, commentId, text, expectedVersion).toAgentToolResult()
            }

            "delete_comment" -> {
                val projectKey = params["project_key"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("project_key")
                val repoSlug = params["repo_slug"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("repo_slug")
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val commentIdStr = params["comment_id"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("comment_id")
                val commentId = commentIdStr.toLongOrNull() ?: return ToolResult(
                    "Error: 'comment_id' must be an integer, got '$commentIdStr'",
                    "Error: invalid comment_id",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
                val versionStr = params["expected_version"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("expected_version")
                val expectedVersion = versionStr.toIntOrNull() ?: return ToolResult(
                    "Error: 'expected_version' must be an integer, got '$versionStr'",
                    "Error: invalid expected_version",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
                service.deletePrComment(projectKey, repoSlug, prId, commentId, expectedVersion).toAgentToolResult()
            }

            "resolve_comment" -> {
                val projectKey = params["project_key"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("project_key")
                val repoSlug = params["repo_slug"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("repo_slug")
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val commentIdStr = params["comment_id"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("comment_id")
                val commentId = commentIdStr.toLongOrNull() ?: return ToolResult(
                    "Error: 'comment_id' must be an integer, got '$commentIdStr'",
                    "Error: invalid comment_id",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
                service.resolvePrComment(projectKey, repoSlug, prId, commentId).toAgentToolResult()
            }

            "reopen_comment" -> {
                val projectKey = params["project_key"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("project_key")
                val repoSlug = params["repo_slug"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("repo_slug")
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val commentIdStr = params["comment_id"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("comment_id")
                val commentId = commentIdStr.toLongOrNull() ?: return ToolResult(
                    "Error: 'comment_id' must be an integer, got '$commentIdStr'",
                    "Error: invalid comment_id",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
                service.reopenPrComment(projectKey, repoSlug, prId, commentId).toAgentToolResult()
            }

            else -> ToolResult(
                content = "Unknown action '$action'. See tool description for valid actions.",
                summary = "Unknown action '$action'",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }
}

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
 * Pull request lifecycle — create, inspect, approve, merge, decline, update PRs.
 *
 * 19 actions: create_pr, get_pr_detail, get_pr_commits, get_pr_activities,
 * get_pr_changes, get_pr_diff, check_merge_status, approve_pr, merge_pr,
 * decline_pr, update_pr_title, update_pr_description, get_my_prs, get_reviewing_prs,
 * get_pr_participants, get_blocker_comment_count, get_linked_jira_issues, get_required_builds,
 * get_prs_for_branch.
 *
 * - get_prs_for_branch(branch_name, repo_name?) → Lists PRs that have the given branch as their
 *   source. Use this to answer "is there a PR for the branch I just pushed?" — get_my_prs does
 *   NOT cover this case (PRs may be opened by anyone).
 */
class BitbucketPrTool : AgentTool {

    override val name = "bitbucket_pr"

    override val description = """
Pull request lifecycle — create, inspect, approve, merge, decline, update PRs.

Actions and their parameters:
- create_pr(title, pr_description, from_branch, to_branch?) → Create pull request
- get_pr_detail(pr_id) → Full PR info with reviewers and status
- get_pr_commits(pr_id) → List commits in PR
- get_pr_activities(pr_id) → Activity feed (comments, approvals, merges)
- get_pr_changes(pr_id) → List changed files in PR
- get_pr_diff(pr_id) → Raw diff of PR changes
- check_merge_status(pr_id) → Check if PR can be merged (vetoes, conflicts)
- approve_pr(pr_id) → Approve the pull request
- merge_pr(pr_id, strategy?, delete_source_branch?, commit_message?) → Merge the PR
- decline_pr(pr_id) → Decline the pull request
- update_pr_title(pr_id, new_title) → Change PR title
- update_pr_description(pr_id, pr_description) → Change PR description
- get_my_prs(state?) → List PRs authored by current user
- get_reviewing_prs(state?) → List PRs where current user is reviewer
- get_pr_participants(pr_id) → Reviewer status + lastReviewedCommit (R-SWAP-5)
- get_blocker_comment_count(pr_id) → Cheap counter for blocker-severity comments (R-SWAP-4)
- get_linked_jira_issues(pr_id) → Jira keys for the PR via Bitbucket's Jira-link plugin (R-ADD-11)
- get_required_builds → Per-branch required-builds conditions for the active repo (R-ADD-15)
- get_prs_for_branch(branch_name, repo_name?) → Lists PRs that have the given branch as their source. Use this to answer "is there a PR for the branch I just pushed?" — get_my_prs does NOT cover this case (PRs may be opened by anyone).

Common optional: repo_name for multi-repo projects. description for approval dialog on write actions.
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty("string", "Operation to perform",
                enumValues = listOf(
                    "create_pr", "get_pr_detail", "get_pr_commits", "get_pr_activities",
                    "get_pr_changes", "get_pr_diff", "check_merge_status", "approve_pr",
                    "merge_pr", "decline_pr", "update_pr_title", "update_pr_description",
                    "get_my_prs", "get_reviewing_prs",
                    "get_pr_participants", "get_blocker_comment_count",
                    "get_linked_jira_issues", "get_required_builds",
                    "get_prs_for_branch"
                )),
            "pr_id"                to ParameterProperty("string", "Pull request ID (numeric) — for most PR actions"),
            "title"                to ParameterProperty("string", "PR title — for create_pr"),
            "pr_description"       to ParameterProperty("string", "PR body/description text — for create_pr, update_pr_description"),
            "from_branch"          to ParameterProperty("string", "Source branch — for create_pr"),
            "to_branch"            to ParameterProperty("string", "Target branch (default: master) — for create_pr"),
            "new_title"            to ParameterProperty("string", "New PR title — for update_pr_title"),
            "state"                to ParameterProperty("string", "PR state: OPEN, MERGED, DECLINED (default OPEN) — for get_my_prs, get_reviewing_prs", enumValues = listOf("OPEN", "MERGED", "DECLINED")),
            "strategy"             to ParameterProperty("string", "Merge strategy: no-ff, squash, rebase-no-ff — for merge_pr", enumValues = listOf("no-ff", "squash", "rebase-no-ff")),
            "delete_source_branch" to ParameterProperty("string", "Delete source branch after merge: true/false — for merge_pr"),
            "commit_message"       to ParameterProperty("string", "Custom merge commit message — for merge_pr"),
            "repo_name"            to ParameterProperty("string", "Repository name for multi-repo projects — omit for primary"),
            "branch_name"          to ParameterProperty("string", "Branch name (e.g. 'feature/PROJ-1') to look up PRs for. Required for get_prs_for_branch."),
            "description"          to ParameterProperty("string", "Approval dialog description for write actions: create_pr, approve_pr, merge_pr, decline_pr")
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

    @Suppress("LongMethod")
    override fun documentation(): ToolDocumentation = toolDoc("bitbucket_pr") {
        summary {
            technical(
                "Single-tool dispatcher for 19 Bitbucket Data Center pull-request operations: " +
                "create / read / inspect (detail, commits, activities, changes, diff) / mutate (approve, merge, decline, update title+description) / " +
                "check (merge-status, participants, blocker-comment-count, required-builds) / discover (my PRs, reviewing PRs, linked Jira issues, PRs for branch). " +
                "Bearer-auth via PasswordSafe; conditionally registered when ConnectionSettings.bitbucketUrl is non-blank. " +
                "All mutations carry version-race risk — merge_pr and decline_pr are irreversible without re-opening."
            )
            plain(
                "The agent's Bitbucket remote control — like having a full Bitbucket PR UI accessible from chat. " +
                "One tool covers the whole PR lifecycle: open a PR, watch it get reviewed, check if it can merge, " +
                "then actually merge or decline it. Credentials stay in the OS keychain (PasswordSafe), " +
                "never in the LLM's working memory or shell history."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.NETWORK)
        counterfactual(
            "Without `bitbucket_pr`, the LLM must fall back to `run_command curl -H 'Authorization: Bearer <token>' https://bitbucket/...`. " +
            "That causes three regressions at once: (1) the Bearer token has to be in the shell environment or embedded in the curl command — " +
            "either path leaks it into shell history, process listings, and the agent command log; " +
            "(2) ProcessEnvironment's 35-var sensitive-var stripper doesn't cover command arguments, so token-in-arg leaks past it; " +
            "(3) the version-race contract on mutations disappears — the LLM gets raw Bitbucket JSON and must figure out optimistic-locking semantics itself. " +
            "The whole point of this tool is to keep credentials in PasswordSafe and surface a typed, safe interface for PR mutations."
        )
        llmMistake(
            "Calls merge_pr without first calling check_merge_status. If the PR has outstanding vetoes or conflicts, " +
            "the merge will 409. The correct sequence is always: check_merge_status → (fix blockers) → merge_pr."
        )
        llmMistake(
            "Passes a string PR ID without numeric parsing check — sometimes uses 'PR-42' instead of '42'. " +
            "The tool's BitbucketToolUtils.parsePrId expects a pure numeric string."
        )
        llmMistake(
            "Uses get_my_prs to find the PR for a branch just pushed. get_my_prs returns PRs the current user authored, " +
            "but the PR may have been opened by another team member. Use get_prs_for_branch(branch_name=...) instead."
        )
        llmMistake(
            "Treats 401 as retryable. 401 means the Bitbucket token is invalid or expired. " +
            "Retrying just produces another 401. The correct move is to surface the message to the user and stop."
        )
        llmMistake(
            "Calls merge_pr without supplying strategy when the repo admin has enforced a specific merge strategy. " +
            "Missing strategy causes a 400 from Bitbucket DC. Check get_required_builds for merge conditions first."
        )
        llmMistake(
            "Tries to approve a PR the current user authored. Bitbucket DC rejects author self-approval with 409. " +
            "get_pr_detail shows the current user; the LLM should detect if it is the PR author and skip approve_pr."
        )
        llmMistake(
            "Passes repo_name inconsistently across a multi-step workflow — e.g., create_pr with repo_name='service-a' " +
            "then get_pr_detail without repo_name (defaults to primary). If primary is a different repo, the PR ID won't resolve."
        )
        flowchart(
            """
            flowchart TD
                A[LLM needs PR work] --> B{What kind?}
                B -- create --> C[create_pr title from_branch to_branch pr_description]
                B -- read --> D[get_pr_detail pr_id]
                D --> E{Need more detail?}
                E -- commits --> F[get_pr_commits pr_id]
                E -- diff --> G[get_pr_diff pr_id]
                E -- comments --> H[get_pr_activities pr_id]
                B -- can it merge? --> I[check_merge_status pr_id]
                I --> J{Blockers?}
                J -- yes --> K[get_blocker_comment_count pr_id]
                J -- no --> L[merge_pr pr_id strategy?]
                B -- review --> M[approve_pr pr_id]
                B -- find mine --> N[get_my_prs state?]
                B -- find for branch --> O[get_prs_for_branch branch_name]
                B -- linked tickets --> P[get_linked_jira_issues pr_id]
            """
        )
        actions {
            action("create_pr") {
                description {
                    technical(
                        "Creates a Bitbucket DC pull request. Validates that from_branch != to_branch before the network call. " +
                        "Delegates to BitbucketService.createPullRequest(title, prDescription, fromBranch, toBranch, repoName)."
                    )
                    plain(
                        "Opens a new pull request — like clicking 'Create pull request' in the Bitbucket UI and filling in the form."
                    )
                }
                whenLLMUses(
                    "After `git push` when the user says 'open a PR' or 'create a PR for this branch'. " +
                    "Also used at end of a coding task when the agent has pushed changes and needs to open review."
                )
                params {
                    required("title", "string") {
                        llmSeesIt("PR title — for create_pr")
                        humanReadable("The pull request headline. Should be concise and describe the change.")
                        whenPresent("Validated non-blank by ToolValidation.validateNotBlank before the request.")
                        constraint("must be non-blank")
                        example("PROJ-1234: Fix timezone conversion in OrderService")
                    }
                    required("pr_description", "string") {
                        llmSeesIt("PR body/description text — for create_pr, update_pr_description")
                        humanReadable("The body of the PR — what changed, why, how to test. Markdown is rendered by Bitbucket.")
                        whenPresent("Sent as the PR description body.")
                        constraint("must be provided; can be empty string if truly no description, but non-blank is recommended")
                        example("## Summary\nFixes UTC→local timezone bug in OrderService#toLocalTime.\n\n## Testing\nUnit tests added for DST edge cases.")
                    }
                    required("from_branch", "string") {
                        llmSeesIt("Source branch — for create_pr")
                        humanReadable("The feature branch being merged in — the 'from' side.")
                        whenPresent("Validated non-blank. If equals to_branch, the tool returns an error before any network call.")
                        constraint("must be non-blank; must differ from to_branch")
                        example("feature/PROJ-1234-fix-timezone")
                    }
                    optional("to_branch", "string") {
                        llmSeesIt("Target branch (default: master) — for create_pr")
                        humanReadable("The branch the PR merges into — the 'to' side.")
                        whenPresent("Used as target ref.")
                        whenAbsent("Defaults to 'master'.")
                        example("main")
                        example("develop")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects — omit for primary")
                        humanReadable("Which Bitbucket repository. Omit for single-repo projects; required when the project has multiple repos.")
                        whenPresent("Service resolves the repo slug from this name.")
                        whenAbsent("Primary configured repository is used.")
                        example("payment-service")
                    }
                    optional("description", "string") {
                        llmSeesIt("Approval dialog description for write actions: create_pr, approve_pr, merge_pr, decline_pr")
                        humanReadable("One-line description for the agent approval dialog. Recommended for write actions.")
                        whenPresent("Shown in the approval gate.")
                        whenAbsent("Approval dialog falls back to action + pr_id.")
                        example("Create PR: Fix timezone bug → main")
                    }
                }
                precondition("from_branch must exist in the repository")
                precondition("to_branch must exist (defaults to 'master' if absent)")
                precondition("user must have Create Pull Request permission on the repo")
                onSuccess("Returns the new PR number and a link to view it in Bitbucket.")
                onFailure("from_branch == to_branch", "Returns error before any network call: 'Cannot create PR: source branch X is the same as target branch Y.'")
                onFailure("401 Unauthorized", "Token invalid or expired. Stop and ask the user to re-enter the Bitbucket token in Settings.")
                onFailure("403 Forbidden", "User lacks Create Pull Request permission on this repo.")
                onFailure("409 Conflict", "A PR for this branch already exists. Surface to user; use get_prs_for_branch to find the existing PR.")
                onFailure("404 branch not found", "The from_branch or to_branch doesn't exist in the repo. LLM should confirm branch name with the user.")
                example("create PR after pushing feature branch") {
                    param("action", "create_pr")
                    param("title", "PROJ-1234: Fix timezone conversion")
                    param("pr_description", "Fixes DST edge case in OrderService#toLocalTime. Added 3 unit tests.")
                    param("from_branch", "feature/PROJ-1234-fix-timezone")
                    param("to_branch", "main")
                    param("description", "Create PR for PROJ-1234 timezone fix")
                    outcome("PR #42 created; Bitbucket link returned.")
                }
                example("create PR with default target branch") {
                    param("action", "create_pr")
                    param("title", "Bump dependencies")
                    param("pr_description", "Automated dependency update.")
                    param("from_branch", "chore/dependency-bump")
                    outcome("PR created targeting 'master' (default to_branch).")
                }
                verdict {
                    keep("Core write action — the agent's ability to open a PR is the end-goal of every coding task. Without it the agent must ask the user to open the PR manually.", VerdictSeverity.STRONG)
                }
            }

            action("get_pr_detail") {
                description {
                    technical("Fetches full PR metadata: title, description, author, reviewers, status, source/target refs, version, links.")
                    plain("Reads everything about a PR — title, description, who's reviewing, current state, and whether it can merge.")
                }
                whenLLMUses(
                    "When the user mentions a PR number ('look at PR 42', 'what's the status of my PR'). " +
                    "Also used as a pre-flight before any PR mutation to get the current `version` field needed for optimistic-locking."
                )
                params {
                    required("pr_id", "string") {
                        llmSeesIt("Pull request ID (numeric) — for most PR actions")
                        humanReadable("The numeric PR ID from Bitbucket — the number after '/pull-requests/' in the URL.")
                        whenPresent("Parsed via BitbucketToolUtils.parsePrId to an integer.")
                        constraint("must be a numeric string (e.g. '42', not 'PR-42')")
                        example("42")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects — omit for primary")
                        humanReadable("Which repository. Omit for single-repo projects.")
                        whenPresent("Service resolves to this repo slug.")
                        whenAbsent("Primary configured repository is used.")
                        example("auth-service")
                    }
                }
                precondition("PR must exist in the configured Bitbucket project")
                onSuccess("Returns PR header (id, title, description, state, author, reviewers with approval status, version, links).")
                onFailure("401 Unauthorized", "Token invalid. Stop and ask user to update token in Settings.")
                onFailure("403 Forbidden", "User lacks read access to the repo.")
                onFailure("404 Not Found", "PR ID doesn't exist. Re-confirm with user — often a typo.")
                example("look up PR before merging") {
                    param("action", "get_pr_detail")
                    param("pr_id", "42")
                    outcome("Returns full PR metadata including current version needed for merge.")
                }
                verdict {
                    keep("The foundational read action for all PR workflows. Nearly every multi-step PR workflow starts here.", VerdictSeverity.STRONG)
                }
            }

            action("get_pr_commits") {
                description {
                    technical("Lists commits included in the PR (those not yet merged into the target branch).")
                    plain("Shows which commits are in the PR — like the 'Commits' tab in the Bitbucket PR view.")
                }
                whenLLMUses(
                    "When the user asks 'what commits are in this PR' or the LLM needs to understand the scope of changes before reviewing or merging."
                )
                params {
                    required("pr_id", "string") {
                        llmSeesIt("Pull request ID (numeric) — for most PR actions")
                        humanReadable("The numeric PR ID.")
                        whenPresent("Parsed to integer; commits for that PR are fetched.")
                        constraint("must be a numeric string")
                        example("42")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects — omit for primary")
                        humanReadable("Which repository. Omit for single-repo projects.")
                        whenPresent("Scopes the lookup to this repo.")
                        whenAbsent("Primary configured repository is used.")
                        example("payment-service")
                    }
                }
                onSuccess("Returns a list of commit entries (SHA, author, message, date).")
                onFailure("401/403", "Token invalid or no access. Surface to user.")
                onFailure("404", "PR not found. Confirm PR ID with user.")
                example("review scope before merge") {
                    param("action", "get_pr_commits")
                    param("pr_id", "42")
                    outcome("Returns N commits in the PR; LLM uses to summarize scope of changes.")
                }
                verdict {
                    keep("Useful for scope-check before merge and for code-review context-gathering.", VerdictSeverity.NORMAL)
                    drop("Low-frequency standalone use. Most PR workflows use get_pr_changes or get_pr_diff for content. This adds commit list only — could be folded into get_pr_detail as an optional flag.", VerdictSeverity.WEAK)
                }
            }

            action("get_pr_activities") {
                description {
                    technical("Fetches the activity feed for a PR: comments, approvals, rescoped events, merges.")
                    plain("Reads the PR's history and discussion — all comments, approvals, and state changes chronologically, like the Bitbucket activity tab.")
                }
                whenLLMUses(
                    "When the user asks 'what's been discussed on this PR', 'has anyone approved this', or the LLM needs to understand reviewer feedback before acting."
                )
                params {
                    required("pr_id", "string") {
                        llmSeesIt("Pull request ID (numeric) — for most PR actions")
                        humanReadable("The numeric PR ID.")
                        whenPresent("Activities for this PR are fetched.")
                        constraint("must be a numeric string")
                        example("42")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects — omit for primary")
                        humanReadable("Which repository. Omit for single-repo projects.")
                        whenPresent("Scopes the lookup to this repo.")
                        whenAbsent("Primary configured repository is used.")
                        example("auth-service")
                    }
                }
                onSuccess("Returns a chronological list of activity entries (type, author, timestamp, body/details).")
                onFailure("401/403", "Token invalid or no access.")
                onFailure("404", "PR not found.")
                example("check if reviewers have commented") {
                    param("action", "get_pr_activities")
                    param("pr_id", "42")
                    outcome("Returns all review comments and approvals; LLM can identify which comments need addressing.")
                }
                verdict {
                    keep("Essential for understanding reviewer feedback. Without it the LLM can't tell what changes are requested before attempting a re-push or merge.", VerdictSeverity.NORMAL)
                }
            }

            action("get_pr_changes") {
                description {
                    technical("Lists files changed in the PR (changed paths, change types: add/modify/delete/copy/move).")
                    plain("Shows which files the PR touches — like the 'Files changed' list in the Bitbucket PR view.")
                }
                whenLLMUses(
                    "When the LLM needs to understand the file-level scope of a PR before doing a code review, impact analysis, or deciding whether to merge."
                )
                params {
                    required("pr_id", "string") {
                        llmSeesIt("Pull request ID (numeric) — for most PR actions")
                        humanReadable("The numeric PR ID.")
                        whenPresent("Changed files for this PR are fetched.")
                        constraint("must be a numeric string")
                        example("42")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects — omit for primary")
                        humanReadable("Which repository. Omit for single-repo projects.")
                        whenPresent("Scopes the lookup to this repo.")
                        whenAbsent("Primary configured repository is used.")
                        example("payment-service")
                    }
                }
                onSuccess("Returns a list of changed paths with their change type (add/modify/delete/copy/move).")
                onFailure("401/403", "Token invalid or no access.")
                onFailure("404", "PR not found.")
                example("impact analysis before merge") {
                    param("action", "get_pr_changes")
                    param("pr_id", "42")
                    outcome("Returns paths like ['src/main/OrderService.kt (MODIFY)', 'src/test/OrderServiceTest.kt (ADD)'].")
                }
                verdict {
                    keep("File-change listing is necessary for meaningful code review and impact analysis — a core PR inspection action.", VerdictSeverity.NORMAL)
                }
            }

            action("get_pr_diff") {
                description {
                    technical("Returns the raw unified diff of all changes in the PR.")
                    plain("Shows the actual code changes — line by line, additions and deletions — the same as `git diff` for the PR.")
                }
                whenLLMUses(
                    "When the LLM needs to inspect the actual code for review, to summarize what changed, or to explain a change to the user."
                )
                params {
                    required("pr_id", "string") {
                        llmSeesIt("Pull request ID (numeric) — for most PR actions")
                        humanReadable("The numeric PR ID.")
                        whenPresent("Unified diff for this PR is fetched.")
                        constraint("must be a numeric string")
                        example("42")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects — omit for primary")
                        humanReadable("Which repository. Omit for single-repo projects.")
                        whenPresent("Scopes the lookup to this repo.")
                        whenAbsent("Primary configured repository is used.")
                        example("auth-service")
                    }
                }
                onSuccess("Returns raw unified diff text. Large PRs may be truncated by ToolOutputSpiller (>30K chars) with a spill path for read_file.")
                onFailure("401/403", "Token invalid or no access.")
                onFailure("404", "PR not found.")
                onFailure("diff is very large", "ToolOutputSpiller auto-spills to disk; LLM receives preview + file path for read_file.")
                example("code review") {
                    param("action", "get_pr_diff")
                    param("pr_id", "42")
                    outcome("Returns full diff; LLM uses to give line-by-line review feedback.")
                }
                verdict {
                    keep("Diff is the primary content surface for AI code review — without this the LLM can only see file names, not code.", VerdictSeverity.STRONG)
                }
            }

            action("check_merge_status") {
                description {
                    technical(
                        "Queries Bitbucket's merge-check endpoint for a PR. Returns whether the PR can be merged and, " +
                        "if not, which vetoes (e.g. unapproved, conflicts, CI failure) are blocking it."
                    )
                    plain(
                        "Asks Bitbucket 'can this PR merge right now?' — like looking at the merge button and reading the blockers listed underneath it."
                    )
                }
                whenLLMUses(
                    "Always before attempting merge_pr. Also when the user asks 'is this PR ready to merge?' or 'what's blocking this PR?'."
                )
                params {
                    required("pr_id", "string") {
                        llmSeesIt("Pull request ID (numeric) — for most PR actions")
                        humanReadable("The numeric PR ID.")
                        whenPresent("Bitbucket's merge-check endpoint is queried for this PR.")
                        constraint("must be a numeric string")
                        example("42")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects — omit for primary")
                        humanReadable("Which repository. Omit for single-repo projects.")
                        whenPresent("Scopes the lookup to this repo.")
                        whenAbsent("Primary configured repository is used.")
                        example("payment-service")
                    }
                }
                precondition("PR must be in OPEN state (MERGED/DECLINED PRs are not mergeable)")
                onSuccess("Returns mergeable status (true/false) and a list of veto reasons if not mergeable.")
                onFailure("401/403", "Token invalid or no access.")
                onFailure("404", "PR not found.")
                example("pre-merge check") {
                    param("action", "check_merge_status")
                    param("pr_id", "42")
                    outcome("Returns 'canMerge: false, vetoes: [Not all required tasks are complete, Merge checks: build not green]'.")
                }
                verdict {
                    keep("Required gate before merge_pr. Skipping it leads to 409 from Bitbucket when blockers exist. Saves the user from attempting an impossible merge.", VerdictSeverity.STRONG)
                }
            }

            action("approve_pr") {
                description {
                    technical("Submits an approval for the PR. Delegates to BitbucketService.approvePullRequest. Author cannot approve their own PR on Bitbucket DC.")
                    plain("Marks the PR as approved — like clicking the 'Approve' button in the Bitbucket review UI.")
                }
                whenLLMUses(
                    "After reviewing code and determining it's ready to merge, when the user says 'approve this PR' or the agent has been given reviewer role."
                )
                params {
                    required("pr_id", "string") {
                        llmSeesIt("Pull request ID (numeric) — for most PR actions")
                        humanReadable("The numeric PR ID to approve.")
                        whenPresent("Approval request submitted for this PR.")
                        constraint("must be a numeric string")
                        example("42")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects — omit for primary")
                        humanReadable("Which repository. Omit for single-repo projects.")
                        whenPresent("Scopes the lookup to this repo.")
                        whenAbsent("Primary configured repository is used.")
                        example("auth-service")
                    }
                    optional("description", "string") {
                        llmSeesIt("Approval dialog description for write actions: create_pr, approve_pr, merge_pr, decline_pr")
                        humanReadable("One-line description for the approval gate.")
                        whenPresent("Shown in the approval gate.")
                        whenAbsent("Approval gate falls back to action + pr_id.")
                        example("Approve PR #42 — code review complete")
                    }
                }
                precondition("PR must be OPEN")
                precondition("Current user must NOT be the PR author (Bitbucket DC rejects author self-approval)")
                precondition("User must have at least Read permission on the repo (reviewers are set on the PR)")
                onSuccess("Returns approval confirmation and the updated reviewer status.")
                onFailure("401 Unauthorized", "Token invalid. Stop and surface to user.")
                onFailure("403 Forbidden", "No access or user is not a participant.")
                onFailure("409 Conflict", "Author attempted self-approval. LLM should check PR author in get_pr_detail before calling approve.")
                example("approve after review") {
                    param("action", "approve_pr")
                    param("pr_id", "42")
                    param("description", "Approve PR #42 — reviewed and LGTM")
                    outcome("PR marked APPROVED; reviewer status updated in Bitbucket.")
                }
                verdict {
                    keep("Write action with real workflow value — enables the agent to close the PR review loop as a designated reviewer.", VerdictSeverity.NORMAL)
                }
            }

            action("merge_pr") {
                description {
                    technical(
                        "Merges a PR. Accepts optional strategy (no-ff, squash, rebase-no-ff), delete_source_branch flag, and commit_message. " +
                        "Irreversible — merged PRs cannot be unmerged."
                    )
                    plain(
                        "Clicks the 'Merge' button — closes the PR by merging the source branch into the target. " +
                        "Can squash commits, rebase, or do a standard merge depending on the strategy."
                    )
                }
                whenLLMUses(
                    "After check_merge_status confirms the PR is mergeable and the user says 'merge this PR'. " +
                    "This is the terminal write action in the PR lifecycle."
                )
                params {
                    required("pr_id", "string") {
                        llmSeesIt("Pull request ID (numeric) — for most PR actions")
                        humanReadable("The numeric PR ID to merge.")
                        whenPresent("That PR is merged.")
                        constraint("must be a numeric string")
                        example("42")
                    }
                    optional("strategy", "string") {
                        llmSeesIt("Merge strategy: no-ff, squash, rebase-no-ff — for merge_pr")
                        humanReadable("How to merge. no-ff = merge commit; squash = one commit; rebase-no-ff = rebase then merge commit.")
                        whenPresent("The specified strategy is used.")
                        whenAbsent("Bitbucket DC uses the repository's default merge strategy.")
                        enumValue("no-ff", "squash", "rebase-no-ff")
                        example("squash")
                    }
                    optional("delete_source_branch", "string") {
                        llmSeesIt("Delete source branch after merge: true/false — for merge_pr")
                        humanReadable("Whether to delete the feature branch after a successful merge. 'true' or 'false' string.")
                        whenPresent("Parsed via toBooleanStrictOrNull; defaults to false on parse failure.")
                        whenAbsent("Source branch is NOT deleted (default false).")
                        example("true")
                    }
                    optional("commit_message", "string") {
                        llmSeesIt("Custom merge commit message — for merge_pr")
                        humanReadable("Custom message for the merge commit. When absent, Bitbucket generates one.")
                        whenPresent("Used as the merge commit message.")
                        whenAbsent("Bitbucket generates a default merge commit message.")
                        example("Merge PROJ-1234: Fix timezone conversion (#42)")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects — omit for primary")
                        humanReadable("Which repository. Omit for single-repo projects.")
                        whenPresent("Scopes the merge to this repo.")
                        whenAbsent("Primary configured repository is used.")
                        example("payment-service")
                    }
                    optional("description", "string") {
                        llmSeesIt("Approval dialog description for write actions: create_pr, approve_pr, merge_pr, decline_pr")
                        humanReadable("One-line description for the approval gate. Strongly recommended for an irreversible action.")
                        whenPresent("Shown in the approval gate.")
                        whenAbsent("Approval gate falls back to action + pr_id.")
                        example("Merge PR #42: Fix timezone bug → main")
                    }
                }
                precondition("check_merge_status must return canMerge=true before calling this")
                precondition("PR must be OPEN (not already MERGED or DECLINED)")
                precondition("user must have Merge permission on the repo")
                onSuccess("Returns merge confirmation with the merged commit SHA.")
                onFailure("401 Unauthorized", "Token invalid. Stop and surface to user.")
                onFailure("403 Forbidden", "User lacks Merge permission.")
                onFailure("409 Conflict", "Merge vetoes are in place (unapproved, conflicts, CI failure). Run check_merge_status to identify blockers.")
                onFailure("bad strategy value", "Bitbucket DC returns 400. Use one of: no-ff, squash, rebase-no-ff.")
                example("merge with squash and branch deletion") {
                    param("action", "merge_pr")
                    param("pr_id", "42")
                    param("strategy", "squash")
                    param("delete_source_branch", "true")
                    param("commit_message", "PROJ-1234: Fix timezone conversion (#42)")
                    param("description", "Merge PR #42 with squash — PROJ-1234 complete")
                    outcome("PR merged as a single squash commit; feature branch deleted.")
                }
                example("standard merge without cleanup") {
                    param("action", "merge_pr")
                    param("pr_id", "42")
                    outcome("PR merged with Bitbucket's default strategy; source branch retained.")
                }
                verdict {
                    keep("The terminal write action in the PR lifecycle. Without it the agent cannot complete PR-based workflows — the user must go to Bitbucket UI to finish.", VerdictSeverity.STRONG)
                }
            }

            action("decline_pr") {
                description {
                    technical("Declines (closes without merging) a PR. Irreversible — declined PRs can be reopened only if the source branch still exists.")
                    plain("Closes a PR without merging — the equivalent of clicking 'Decline' in Bitbucket. The code stays in the branch but the PR is closed.")
                }
                whenLLMUses(
                    "When the user says 'decline this PR', 'close without merging', or decides the approach is wrong and needs to abandon the PR."
                )
                params {
                    required("pr_id", "string") {
                        llmSeesIt("Pull request ID (numeric) — for most PR actions")
                        humanReadable("The numeric PR ID to decline.")
                        whenPresent("That PR is declined.")
                        constraint("must be a numeric string")
                        example("42")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects — omit for primary")
                        humanReadable("Which repository. Omit for single-repo projects.")
                        whenPresent("Scopes the decline to this repo.")
                        whenAbsent("Primary configured repository is used.")
                        example("auth-service")
                    }
                    optional("description", "string") {
                        llmSeesIt("Approval dialog description for write actions: create_pr, approve_pr, merge_pr, decline_pr")
                        humanReadable("One-line description for the approval gate. Strongly recommended for an irreversible-feeling action.")
                        whenPresent("Shown in the approval gate.")
                        whenAbsent("Approval gate falls back to action + pr_id.")
                        example("Decline PR #42 — approach superseded by PR #43")
                    }
                }
                precondition("PR must be OPEN")
                precondition("user must have Merge or Admin permission on the repo to decline")
                onSuccess("Returns decline confirmation; PR state is now DECLINED.")
                onFailure("401 Unauthorized", "Token invalid. Stop and surface to user.")
                onFailure("403 Forbidden", "User lacks permission to decline.")
                onFailure("409 Conflict", "PR is already merged or declined.")
                example("abandon a superseded approach") {
                    param("action", "decline_pr")
                    param("pr_id", "42")
                    param("description", "Decline PR #42 — superseded by PR #43 with different approach")
                    outcome("PR #42 moved to DECLINED state. Source branch still exists.")
                }
                example("decline without description") {
                    param("action", "decline_pr")
                    param("pr_id", "42")
                    outcome("PR #42 declined; approval gate falls back to 'decline_pr #42'.")
                    notes("Not recommended — always include description for irreversible write actions so the user knows what they're approving.")
                }
                verdict {
                    keep("Necessary counterpart to merge_pr. A PR management workflow without decline is incomplete.", VerdictSeverity.NORMAL)
                }
            }

            action("update_pr_title") {
                description {
                    technical("Updates the PR title. Validates new_title is non-blank before the request.")
                    plain("Changes the title of an existing PR — like clicking the pencil icon next to the title in Bitbucket.")
                }
                whenLLMUses(
                    "When the user says 'rename this PR', 'update the PR title to X', or the agent realizes the title it set in create_pr was wrong."
                )
                params {
                    required("pr_id", "string") {
                        llmSeesIt("Pull request ID (numeric) — for most PR actions")
                        humanReadable("The numeric PR ID whose title should be updated.")
                        whenPresent("That PR's title is updated.")
                        constraint("must be a numeric string")
                        example("42")
                    }
                    required("new_title", "string") {
                        llmSeesIt("New PR title — for update_pr_title")
                        humanReadable("The replacement title for the PR.")
                        whenPresent("Validated non-blank; sent as updated title.")
                        constraint("must be non-blank")
                        example("PROJ-1234: Fix UTC→local conversion in OrderService")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects — omit for primary")
                        humanReadable("Which repository. Omit for single-repo projects.")
                        whenPresent("Scopes the update to this repo.")
                        whenAbsent("Primary configured repository is used.")
                        example("payment-service")
                    }
                }
                onSuccess("Returns confirmation with the new title.")
                onFailure("401/403", "Token invalid or no permission.")
                onFailure("404", "PR not found.")
                onFailure("blank new_title", "Rejected by ToolValidation.validateNotBlank before the network call.")
                example("correct a title typo") {
                    param("action", "update_pr_title")
                    param("pr_id", "42")
                    param("new_title", "PROJ-1234: Fix UTC→local timezone conversion in OrderService")
                    outcome("PR title updated; Bitbucket activity log shows the change.")
                }
                verdict {
                    keep("Low-cost housekeeping action. Part of the PR editing pair with update_pr_description.", VerdictSeverity.NORMAL)
                    drop("Low-frequency relative to other actions — most titles are set correctly at create time. Could be dropped if token budget is tight; the user can edit the title manually in Bitbucket.", VerdictSeverity.WEAK)
                }
            }

            action("update_pr_description") {
                description {
                    technical("Updates the PR description (body). Sends pr_description as the new body.")
                    plain("Changes the description/body of an existing PR — like editing the main text box in the Bitbucket PR view.")
                }
                whenLLMUses(
                    "When the user says 'update the PR description', 'add testing instructions to the PR', or after refactoring to reflect updated change rationale."
                )
                params {
                    required("pr_id", "string") {
                        llmSeesIt("Pull request ID (numeric) — for most PR actions")
                        humanReadable("The numeric PR ID whose description should be updated.")
                        whenPresent("That PR's description is updated.")
                        constraint("must be a numeric string")
                        example("42")
                    }
                    required("pr_description", "string") {
                        llmSeesIt("PR body/description text — for create_pr, update_pr_description")
                        humanReadable("The new description body. Replaces the existing description entirely.")
                        whenPresent("Sent as the new description body.")
                        constraint("must be provided (can be empty string to clear)")
                        example("## Summary\nRefactored timezone handling.\n\n## Testing\nRun OrderServiceTest.")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects — omit for primary")
                        humanReadable("Which repository. Omit for single-repo projects.")
                        whenPresent("Scopes the update to this repo.")
                        whenAbsent("Primary configured repository is used.")
                        example("auth-service")
                    }
                }
                onSuccess("Returns confirmation with the updated description summary.")
                onFailure("401/403", "Token invalid or no permission.")
                onFailure("404", "PR not found.")
                example("add testing steps after code change") {
                    param("action", "update_pr_description")
                    param("pr_id", "42")
                    param("pr_description", "## Summary\nFix DST edge case.\n\n## Testing\n1. Run OrderServiceTest\n2. Check UTC offset at DST boundary")
                    outcome("PR description updated with testing instructions.")
                }
                verdict {
                    keep("Frequently needed after scope changes — PR descriptions get stale when implementation evolves.", VerdictSeverity.NORMAL)
                    drop("Slightly overlaps with update_pr_title as a housekeeping pair. If one is dropped, this one is more useful (descriptions get stale more than titles).", VerdictSeverity.WEAK)
                }
            }

            action("get_my_prs") {
                description {
                    technical("Lists PRs authored by the currently authenticated user. Filterable by state (OPEN/MERGED/DECLINED; default OPEN).")
                    plain("Lists the PRs you've opened — like the 'My Pull Requests' view in Bitbucket.")
                }
                whenLLMUses(
                    "When the user asks 'what are my open PRs', 'which PRs have I created', or at session start to understand the user's current work context."
                )
                params {
                    optional("state", "string") {
                        llmSeesIt("PR state: OPEN, MERGED, DECLINED (default OPEN) — for get_my_prs, get_reviewing_prs")
                        humanReadable("Which PR states to include.")
                        whenPresent("PRs filtered to this state.")
                        whenAbsent("Defaults to OPEN.")
                        enumValue("OPEN", "MERGED", "DECLINED")
                        example("OPEN")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects — omit for primary")
                        humanReadable("Which repository. Omit for single-repo projects.")
                        whenPresent("Scopes the list to this repo.")
                        whenAbsent("Primary configured repository is used.")
                        example("payment-service")
                    }
                }
                onSuccess("Returns a list of PR summaries (id, title, state, target branch, reviewer count).")
                onFailure("401/403", "Token invalid or no access.")
                example("'what are my open PRs'") {
                    param("action", "get_my_prs")
                    outcome("Returns all OPEN PRs authored by the current user.")
                }
                verdict {
                    keep("Dashboard-level discovery action; useful at session start to orient the LLM to the user's PR state.", VerdictSeverity.NORMAL)
                }
            }

            action("get_reviewing_prs") {
                description {
                    technical("Lists PRs where the current user is a reviewer. Filterable by state (default OPEN).")
                    plain("Lists PRs you've been asked to review — like the 'Reviewing' section in Bitbucket's PR dashboard.")
                }
                whenLLMUses(
                    "When the user asks 'what PRs need my review', 'show me my review queue', or the agent is helping triage review tasks."
                )
                params {
                    optional("state", "string") {
                        llmSeesIt("PR state: OPEN, MERGED, DECLINED (default OPEN) — for get_my_prs, get_reviewing_prs")
                        humanReadable("Which PR states to include.")
                        whenPresent("Filtered to this state.")
                        whenAbsent("Defaults to OPEN.")
                        enumValue("OPEN", "MERGED", "DECLINED")
                        example("OPEN")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects — omit for primary")
                        humanReadable("Which repository. Omit for single-repo projects.")
                        whenPresent("Scopes the list to this repo.")
                        whenAbsent("Primary configured repository is used.")
                        example("auth-service")
                    }
                }
                onSuccess("Returns a list of PR summaries where the current user is listed as a reviewer.")
                onFailure("401/403", "Token invalid or no access.")
                example("review queue triage") {
                    param("action", "get_reviewing_prs")
                    outcome("Returns N PRs in review queue; LLM can prioritize by age or unreviewed activity.")
                }
                verdict {
                    keep("Reviewer-role complement to get_my_prs; enables the agent to manage a user's full PR workload, not just authored PRs.", VerdictSeverity.NORMAL)
                }
            }

            action("get_pr_participants") {
                description {
                    technical(
                        "Returns participant list for a PR including each reviewer's approval status (APPROVED/NEEDS_WORK/UNAPPROVED) " +
                        "and their lastReviewedCommit (R-SWAP-5: replaces a dedicated endpoint)."
                    )
                    plain(
                        "Lists everyone on the PR review team — who's approved, who needs to review, who has requested changes — " +
                        "like the Reviewers panel in the Bitbucket PR sidebar."
                    )
                }
                whenLLMUses(
                    "When the LLM needs to know if a PR has enough approvals before merging, or which reviewers are blocking progress."
                )
                params {
                    required("pr_id", "string") {
                        llmSeesIt("Pull request ID (numeric) — for most PR actions")
                        humanReadable("The numeric PR ID.")
                        whenPresent("Participants for this PR are fetched.")
                        constraint("must be a numeric string")
                        example("42")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects — omit for primary")
                        humanReadable("Which repository. Omit for single-repo projects.")
                        whenPresent("Scopes the lookup to this repo.")
                        whenAbsent("Primary configured repository is used.")
                        example("payment-service")
                    }
                }
                onSuccess("Returns participants with role (REVIEWER/AUTHOR), status (APPROVED/NEEDS_WORK/UNAPPROVED), and lastReviewedCommit.")
                onFailure("401/403", "Token invalid or no access.")
                onFailure("404", "PR not found.")
                example("check approval count before merge") {
                    param("action", "get_pr_participants")
                    param("pr_id", "42")
                    outcome("Returns 3 reviewers: 2 APPROVED, 1 UNAPPROVED. LLM can decide whether required approvals are met.")
                }
                verdict {
                    keep("High-value pre-merge check that avoids merge_pr 409s from unapproved state.", VerdictSeverity.STRONG)
                }
            }

            action("get_blocker_comment_count") {
                description {
                    technical(
                        "Returns a cheap count of unresolved blocker-severity comments on the PR (R-SWAP-4). " +
                        "Lower cost than get_pr_activities for the specific use case of 'are there open blockers?'."
                    )
                    plain(
                        "Asks: 'how many unresolved blocking comments does this PR have?' — like checking the number shown on the Comments tab " +
                        "without loading all comment text."
                    )
                }
                whenLLMUses(
                    "As a quick pre-merge check before running the full check_merge_status. " +
                    "If blocker count is 0, the LLM can proceed to check_merge_status with confidence."
                )
                params {
                    required("pr_id", "string") {
                        llmSeesIt("Pull request ID (numeric) — for most PR actions")
                        humanReadable("The numeric PR ID.")
                        whenPresent("Unresolved blocker comment count for this PR is returned.")
                        constraint("must be a numeric string")
                        example("42")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects — omit for primary")
                        humanReadable("Which repository. Omit for single-repo projects.")
                        whenPresent("Scopes the lookup to this repo.")
                        whenAbsent("Primary configured repository is used.")
                        example("auth-service")
                    }
                }
                onSuccess("Returns an integer count of unresolved blocker comments (0 = no open blockers).")
                onFailure("401/403", "Token invalid or no access.")
                onFailure("404", "PR not found.")
                example("quick blocker check") {
                    param("action", "get_blocker_comment_count")
                    param("pr_id", "42")
                    outcome("Returns count=2; LLM knows not to attempt merge and should surface blockers to user.")
                }
                verdict {
                    keep("Cheap, targeted readout that avoids loading the full activity feed for a yes/no blocker question.", VerdictSeverity.NORMAL)
                    drop("Could be partially replaced by check_merge_status (which also surfaces blockers) or get_pr_activities (which lists them). " +
                         "If check_merge_status is called first anyway, this action's info is redundant. Drop candidate if usage is low.", VerdictSeverity.WEAK)
                }
            }

            action("get_linked_jira_issues") {
                description {
                    technical(
                        "Fetches Jira issue keys linked to the PR via Bitbucket's Jira integration plugin (R-ADD-11). " +
                        "Returns a list of Jira keys extracted from the Bitbucket-Jira Application Link."
                    )
                    plain(
                        "Lists the Jira tickets linked to this PR — like the 'Related Issues' panel in Bitbucket that connects to Jira via Application Links."
                    )
                }
                whenLLMUses(
                    "When the user asks 'which Jira tickets does this PR address?' or the agent needs to close tickets after merging."
                )
                params {
                    required("pr_id", "string") {
                        llmSeesIt("Pull request ID (numeric) — for most PR actions")
                        humanReadable("The numeric PR ID.")
                        whenPresent("Linked Jira issues for this PR are fetched.")
                        constraint("must be a numeric string")
                        example("42")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects — omit for primary")
                        humanReadable("Which repository. Omit for single-repo projects.")
                        whenPresent("Scopes the lookup to this repo.")
                        whenAbsent("Primary configured repository is used.")
                        example("payment-service")
                    }
                }
                precondition("Bitbucket-Jira Application Link must be configured server-side for linked issues to appear")
                onSuccess("Returns a list of Jira issue keys (e.g. ['PROJ-1234', 'PROJ-1235']).")
                onFailure("401/403", "Token invalid or no access.")
                onFailure("404", "PR not found.")
                onFailure("Application Links not configured", "Returns empty list — not an error. The tool cannot populate links if the Jira integration isn't set up server-side.")
                example("find tickets to close after merge") {
                    param("action", "get_linked_jira_issues")
                    param("pr_id", "42")
                    outcome("Returns ['PROJ-1234']; agent can then call jira(action=transition) to resolve the ticket.")
                }
                verdict {
                    keep("Enables the key cross-tool workflow: merge PR → get linked Jira keys → transition tickets to Done. Without this the agent has to extract keys from PR title/branch heuristically.", VerdictSeverity.NORMAL)
                }
            }

            action("get_required_builds") {
                description {
                    technical(
                        "Returns the per-branch required-builds merge conditions configured for the repo (R-ADD-15). " +
                        "These are the CI/CD build conditions Bitbucket enforces before allowing a merge."
                    )
                    plain(
                        "Shows which CI builds must pass before any PR into a given branch can be merged — " +
                        "like the branch permissions settings in Bitbucket that list 'must pass build X' conditions."
                    )
                }
                whenLLMUses(
                    "When the LLM wants to understand what CI checks are blocking a merge, or before creating a PR to know what builds will be required."
                )
                params {
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects — omit for primary")
                        humanReadable("Which repository. Omit for single-repo projects.")
                        whenPresent("Returns required-build conditions for this repo.")
                        whenAbsent("Primary configured repository is used.")
                        example("auth-service")
                    }
                }
                rejectsParam("pr_id", "get_required_builds is repo-scoped, not PR-scoped — it returns branch-level conditions, not the status of a specific PR's builds.")
                onSuccess("Returns required-build conditions grouped by branch pattern (e.g. 'main: must pass CI-Pipeline, Security-Scan').")
                onFailure("401/403", "Token invalid or no access.")
                onFailure("no required builds configured", "Returns empty list — not an error.")
                example("understand CI gate before PR creation") {
                    param("action", "get_required_builds")
                    outcome("Returns: main branch requires [CI-Pipeline, Security-Scan] to pass.")
                }
                verdict {
                    keep("Enables the agent to explain CI merge gates to the user and set expectations about what must pass before merging.", VerdictSeverity.NORMAL)
                    drop("Rarely queried in practice — most users know which builds run. Could be a drop candidate if usage telemetry shows near-zero calls.", VerdictSeverity.WEAK)
                }
            }

            action("get_prs_for_branch") {
                description {
                    technical(
                        "Lists PRs whose source branch matches branch_name. Handles the 'did my push create a PR?' case — " +
                        "get_my_prs returns author-filtered PRs and misses PRs opened by others on the same branch."
                    )
                    plain(
                        "Answers 'is there a PR for this branch?' — like searching Bitbucket for PRs by source branch name. " +
                        "Works even when someone else opened the PR for your branch."
                    )
                }
                whenLLMUses(
                    "Immediately after `git push` when the user asks 'did that create a PR?' or 'is there a PR for this branch?'. " +
                    "Also useful when the agent pushes code and needs to find or track the resulting PR."
                )
                params {
                    required("branch_name", "string") {
                        llmSeesIt("Branch name (e.g. 'feature/PROJ-1') to look up PRs for. Required for get_prs_for_branch.")
                        humanReadable("The source branch to search for in PR records.")
                        whenPresent("Returns all PRs with this as their source (from_branch) ref.")
                        constraint("must be provided; partial branch names may not match — use the full branch name")
                        example("feature/PROJ-1234-fix-timezone")
                        example("fix/auth-regression")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects — omit for primary")
                        humanReadable("Which repository. Omit for single-repo projects.")
                        whenPresent("Scopes the search to this repo.")
                        whenAbsent("Primary configured repository is used.")
                        example("payment-service")
                    }
                }
                onSuccess("Returns a list of PR summaries (id, title, state, target branch) whose source branch matches branch_name.")
                onFailure("401/403", "Token invalid or no access.")
                onFailure("404 / empty list", "No PRs found for this branch. May mean the branch hasn't been pushed yet or no one opened a PR.")
                example("post-push PR lookup") {
                    param("action", "get_prs_for_branch")
                    param("branch_name", "feature/PROJ-1234-fix-timezone")
                    outcome("Returns PR #42 in OPEN state targeting 'main'; agent can link to it or proceed to check_merge_status.")
                }
                verdict {
                    keep("Fills a critical gap: get_my_prs returns author-scoped PRs only; this action answers 'is there a PR for this branch' regardless of who opened it.", VerdictSeverity.STRONG)
                }
            }
        }
        related("jira", Relationship.COMPLEMENT, "After merging a PR, use jira(action=transition) to close the linked Jira ticket. get_linked_jira_issues bridges the two.")
        related("bitbucket_repo", Relationship.COMPLEMENT, "Use bitbucket_repo for branch management (create_branch, get_branches, get_file_content, build statuses) before or after PR operations.")
        related("bitbucket_review", Relationship.COMPLEMENT, "Use bitbucket_review(add_pr_comment, add_inline_comment, reply_to_comment) to leave inline code review feedback on the PR.")
        downside("Version-race risk on all mutations: merge_pr, approve_pr, and decline_pr send the Bitbucket `version` field (from get_pr_detail). If the PR is updated concurrently, the version is stale and the request returns 409. The LLM must re-fetch and retry.")
        downside("Merge and decline are irreversible without manual Bitbucket admin action. Always require user approval gate confirmation before executing.")
        downside("Conditional registration only: this tool is absent from the schema if the user hasn't configured a Bitbucket URL in Settings → Tools → Workflow Orchestrator → Connections. The LLM must call tool_search('bitbucket') to find it in a fresh session.")
        downside("Bitbucket DC only (tested on 9.4.16 / DC 10.x). Bitbucket Cloud uses different API paths and a different auth scheme. This tool does NOT work with Bitbucket Cloud.")
        downside("get_pr_diff for large PRs may be auto-spilled to disk by ToolOutputSpiller (>30K chars). The LLM receives a preview and a file path for read_file to read the full diff.")
        mergeOpportunity("get_pr_commits and get_pr_changes have similar usage patterns and could be folded into get_pr_detail as optional include flags (include_commits, include_changes), matching the jira get_ticket fan-out pattern. Saves 2 action slots.")
        mergeOpportunity("get_blocker_comment_count partially overlaps with check_merge_status (which also returns veto reasons). If check_merge_status is always called before merge anyway, blocker_comment_count is redundant — consider dropping or folding into check_merge_status response.")
        observation("update_pr_title and update_pr_description form a housekeeping pair. Both are low-frequency individually. If dropping actions to save schema budget, these are the first two candidates after get_blocker_comment_count.")
        observation("get_required_builds is repo-scoped, not PR-scoped — it answers 'what are the merge gate rules' not 'did this PR's builds pass'. The name is slightly misleading; a rename to get_merge_conditions would be clearer.")
        narrative("bitbucket_pr")
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
            "create_pr" -> {
                val title = params["title"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("title")
                val prDescription = params["pr_description"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("pr_description")
                val fromBranch = params["from_branch"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("from_branch")
                val toBranch = params["to_branch"]?.jsonPrimitive?.content ?: "master"
                ToolValidation.validateNotBlank(title, "title")?.let { return it }
                ToolValidation.validateNotBlank(fromBranch, "from_branch")?.let { return it }
                if (fromBranch == toBranch) return ToolResult(
                    "Cannot create PR: source branch '$fromBranch' is the same as target branch '$toBranch'.",
                    "Same source and target branch",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.createPullRequest(title, prDescription, fromBranch, toBranch, repoName = repoName).toAgentToolResult()
            }

            "get_pr_detail" -> {
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getPullRequestDetail(prId, repoName = repoName).toAgentToolResult()
            }

            "get_pr_commits" -> {
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getPullRequestCommits(prId, repoName = repoName).toAgentToolResult()
            }

            "get_pr_activities" -> {
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getPullRequestActivities(prId, repoName = repoName).toAgentToolResult()
            }

            "get_pr_changes" -> {
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getPullRequestChanges(prId, repoName = repoName).toAgentToolResult()
            }

            "get_pr_diff" -> {
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getPullRequestDiff(prId, repoName = repoName).toAgentToolResult()
            }

            "check_merge_status" -> {
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.checkMergeStatus(prId, repoName = repoName).toAgentToolResult()
            }

            "approve_pr" -> {
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.approvePullRequest(prId, repoName = repoName).toAgentToolResult()
            }

            "merge_pr" -> {
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val strategy = params["strategy"]?.jsonPrimitive?.content
                val deleteSourceBranch = params["delete_source_branch"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                val commitMessage = params["commit_message"]?.jsonPrimitive?.content
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.mergePullRequest(prId, strategy, deleteSourceBranch, commitMessage, repoName = repoName).toAgentToolResult()
            }

            "decline_pr" -> {
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.declinePullRequest(prId, repoName = repoName).toAgentToolResult()
            }

            "update_pr_title" -> {
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val newTitle = params["new_title"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("new_title")
                ToolValidation.validateNotBlank(newTitle, "new_title")?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.updatePrTitle(prId, newTitle, repoName = repoName).toAgentToolResult()
            }

            "update_pr_description" -> {
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val newDescription = params["pr_description"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("pr_description")
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.updatePrDescription(prId, newDescription, repoName = repoName).toAgentToolResult()
            }

            "get_my_prs" -> {
                val state = params["state"]?.jsonPrimitive?.content ?: "OPEN"
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getMyPullRequests(state, repoName = repoName).toAgentToolResult()
            }

            "get_reviewing_prs" -> {
                val state = params["state"]?.jsonPrimitive?.content ?: "OPEN"
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getReviewingPullRequests(state, repoName = repoName).toAgentToolResult()
            }

            "get_pr_participants" -> {
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getPullRequestParticipants(prId, repoName = repoName).toAgentToolResult()
            }

            "get_blocker_comment_count" -> {
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getBlockerCommentsCount(prId, repoName = repoName).toAgentToolResult()
            }

            "get_linked_jira_issues" -> {
                val prId = BitbucketToolUtils.parsePrId(params) ?: return BitbucketToolUtils.invalidPrId()
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getLinkedJiraIssues(prId, repoName = repoName).toAgentToolResult()
            }

            "get_required_builds" -> {
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getRequiredBuilds(repoName = repoName).toAgentToolResult()
            }

            "get_prs_for_branch" -> {
                val branchName = params["branch_name"]?.jsonPrimitive?.content
                    ?: return ToolValidation.missingParam("branch_name")
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getPullRequestsForBranch(branchName, repoName).toAgentToolResult()
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

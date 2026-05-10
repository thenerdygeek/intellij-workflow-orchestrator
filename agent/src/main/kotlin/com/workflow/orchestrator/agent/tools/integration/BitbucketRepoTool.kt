package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.docs.AuditKind
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
 * Repository-level operations — branches, users, files, build statuses, repo listing.
 *
 * 6 actions: get_branches, create_branch, search_users, get_file_content,
 * get_build_statuses, list_repos
 */
class BitbucketRepoTool : AgentTool {

    override val name = "bitbucket_repo"

    override val description = """
Repository-level operations — branches, users, files, build statuses, repo listing.

Actions and their parameters:
- get_branches(filter?) → List branches, optionally filtered by name
- create_branch(name, start_point) → Create a new branch from a ref
- search_users(filter) → Search for users by name/username
- get_file_content(file_path, at_ref) → Get file content at a specific git ref
- get_build_statuses(commit_id) → Get CI build statuses for a commit
- get_commit_build_stats(commit_id) → Aggregate {successful, failed, inProgress} counter (R-ADD-12)
- get_commit_pull_requests(commit_id) → Reverse lookup: PRs containing this commit (R-ADD-5)
- list_repos() → List all repositories in the project

Common optional: repo_name for multi-repo projects. description for approval dialog on write actions.
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty("string", "Operation to perform",
                enumValues = listOf(
                    "get_branches", "create_branch", "search_users",
                    "get_file_content", "get_build_statuses",
                    "get_commit_build_stats", "get_commit_pull_requests",
                    "list_repos"
                )),
            "filter"      to ParameterProperty("string", "Name filter — for get_branches, search_users"),
            "name"        to ParameterProperty("string", "Branch name — for create_branch"),
            "start_point" to ParameterProperty("string", "Source ref to branch from — for create_branch"),
            "file_path"   to ParameterProperty("string", "File path — for get_file_content"),
            "at_ref"      to ParameterProperty("string", "Git ref (branch/tag/commit) — for get_file_content"),
            "commit_id"   to ParameterProperty("string", "Commit hash — for get_build_statuses"),
            "repo_name"   to ParameterProperty("string", "Repository name for multi-repo projects — omit for primary"),
            "description" to ParameterProperty("string", "Approval dialog description for write actions: create_branch")
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

    override fun documentation(): ToolDocumentation = toolDoc("bitbucket_repo") {
        summary {
            technical(
                "Single-tool dispatcher for 8 Bitbucket DC repository operations: branch listing/creation, " +
                "user search, file content at a ref, per-commit build statuses, commit build stat aggregates, " +
                "reverse PR lookup by commit, and project-wide repo listing. Bearer-auth via PasswordSafe; " +
                "conditionally registered when ConnectionSettings.bitbucketUrl is non-blank."
            )
            plain(
                "The agent's Bitbucket repository remote control — like having the Bitbucket DC web UI as a " +
                "set of named operations: list branches, cut a new branch, find a user by name, read a file at " +
                "any git ref, check which CI builds passed on a commit, and list every repo in the project. " +
                "Credentials stay in the OS keychain; the LLM never sees the token."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.NETWORK)
        counterfactual(
            "Without `bitbucket_repo`, the LLM falls back to `run_command git branch -r` for branches and " +
            "`run_command curl -H 'Authorization: Bearer <token>' https://bitbucket/.../files/...` for file " +
            "content and build statuses. That is a security regression: (1) the Bearer token must be either " +
            "embedded in the command-line argument (appears in shell history, agent logs, and possibly the LLM " +
            "context) or already exported as an env var; (2) ProcessEnvironment strips env vars but NOT " +
            "command-line arguments, so a token-in-arg leaks into the ToolResult. " +
            "`create_branch` via raw curl also requires knowing X-Atlassian-Token:no-check header handling, " +
            "which LLMs frequently omit, causing silent 403 failures."
        )
        llmMistake(
            "Reads a file with `get_file_content` for a file that already exists in the local working tree. " +
            "Use `read_file` for tracked local files — it is faster, avoids a network round-trip, and returns " +
            "line numbers the agent can reference in edit_file. Reserve `get_file_content` for historical refs " +
            "(`at_ref=main~3`), different branches, or files not checked out locally."
        )
        llmMistake(
            "Calls `get_build_statuses` when only a pass/fail aggregate is needed. `get_commit_build_stats` " +
            "returns a compact `{successful, failed, inProgress}` counter that costs far fewer tokens. " +
            "Use `get_build_statuses` only when the LLM needs the full URL, key, or name of individual CI runs."
        )
        llmMistake(
            "Calls `create_branch` without providing `start_point`, guessing a default. `start_point` is " +
            "required — omitting it returns a missing-param error and no branch is created."
        )
        llmMistake(
            "Uses `get_branches` with a broad or empty `filter` and then manually scans the list for the " +
            "branch it wants. For targeted lookup, pass the exact branch prefix as `filter` — the API returns " +
            "only matching branches, which is cheaper in tokens and latency."
        )
        llmMistake(
            "Passes `repo_name` to `get_commit_build_stats` — that action ignores `repo_name` because " +
            "Bitbucket DC's commit build-stats endpoint scopes by commit hash globally, not per-repo."
        )
        llmMistake(
            "Treats a 401 from any action as retryable. A 401 means the token is wrong or expired; " +
            "retrying produces another 401. Surface to the user and stop."
        )
        downside(
            "Conditional registration: the tool is absent from the schema if Bitbucket URL is not configured. " +
            "The LLM will see `Unknown tool: bitbucket_repo` if it tries to call it in an unconfigured project."
        )
        downside(
            "`get_file_content` fetches raw bytes; binary files (images, JARs, compiled classes) are returned " +
            "as garbled text. The LLM should check the file extension before fetching non-text paths."
        )
        downside(
            "`search_users` searches within the configured project/repo context only — it will not find users " +
            "from other Bitbucket projects unless they have an explicit role in the target repo."
        )
        downside(
            "`create_branch` is a write action and goes through the approval gate. In plan mode the action is " +
            "blocked at the execution guard."
        )
        related("bitbucket_pr", Relationship.COMPLEMENT, "PR operations (create, approve, merge, decline, review). Use together for a full PR workflow: bitbucket_repo to get branches and users, bitbucket_pr to create and manage the PR.")
        related("bitbucket_review", Relationship.COMPLEMENT, "PR inline comments and replies. Compose with bitbucket_repo to look up commit context before adding a review comment.")
        related("jira", Relationship.COMPLEMENT, "Jira issues and dev-status. Use jira.get_ticket(include_dev_status=true) for the Jira-side dev panel; use bitbucket_repo.get_commit_pull_requests to reverse-lookup a commit's PR from the Bitbucket side.")
        related("bamboo_builds", Relationship.SEE_ALSO, "bamboo_builds.build_status returns Bamboo-specific build detail. bitbucket_repo.get_build_statuses returns statuses from ALL CI providers attached to a commit (Bamboo, Jenkins, etc.) via the Bitbucket DC build-status API.")
        related("read_file", Relationship.ALTERNATIVE, "Use read_file for files that exist in the local working tree — it is faster, returns line numbers, and avoids a network round-trip. Reserve bitbucket_repo.get_file_content for remote refs, feature branches not checked out locally, or historical commits.")
        mergeOpportunity(
            "get_build_statuses and get_commit_build_stats both operate on `commit_id`. They could be unified " +
            "under a single action with a `detail` boolean flag (compact counter vs full per-build list). " +
            "Current two-action split forces the LLM to choose; a combined action with a default would be safer."
        )
        observation(
            "get_commit_pull_requests (R-ADD-5) is the only reverse-lookup path: given a SHA, find which PRs " +
            "contain it. This is not available via bitbucket_pr and cannot be replicated with git commands alone."
        )
        observation(
            "repo_name is an optional cross-cutting param accepted by 6 of 8 actions. Actions that ignore it " +
            "(get_commit_build_stats) should ideally declare rejectsParam in each ActionDoc to surface the contract."
        )
        actions {
            // ── get_branches ────────────────────────────────────────────────────────────────
            action("get_branches") {
                description {
                    technical(
                        "Lists branches in the configured Bitbucket repo, with optional prefix filter. " +
                        "Delegates to BitbucketService.getBranches(filter, repoName)."
                    )
                    plain(
                        "Asks Bitbucket 'which branches exist?' — like clicking the branch picker in the UI " +
                        "but returning a text list. Pass a filter to narrow results when you already know the prefix."
                    )
                }
                whenLLMUses(
                    "When the user asks 'what branches exist?', 'is there a branch for PROJ-123?', or when " +
                    "the LLM needs to discover the exact branch name before creating a PR or checking out."
                )
                params {
                    optional("filter", "string") {
                        llmSeesIt("Name filter — for get_branches, search_users")
                        humanReadable("Prefix or substring filter applied server-side — e.g. 'feature/' returns only feature branches.")
                        whenPresent("Only branches whose name contains the filter are returned.")
                        whenAbsent("All branches are returned (may be large for busy repos).")
                        example("feature/PROJ-123")
                        example("release/")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects — omit for primary")
                        humanReadable("Which repo to query; omit to use the primary repo from ConnectionSettings.")
                        whenPresent("Overrides the default repo in the service call.")
                        whenAbsent("Primary repo from ConnectionSettings is used.")
                        example("backend-api")
                    }
                }
                onSuccess("Returns a list of branch names with their latest commit SHA and whether they are the default branch.")
                onFailure("Bitbucket URL not configured", "Tool is not registered — schema call will fail with UnknownTool. User must configure Bitbucket in Settings.")
                onFailure("401 Unauthorized", "Token invalid or expired. Surface to user; do not retry.")
                onFailure("repo not found", "404 — wrong `repo_name` or the repo doesn't exist in the configured project. Re-confirm with user.")
                example("list all branches") {
                    param("action", "get_branches")
                    outcome("Returns all branches; suitable for small repos or when the LLM needs a complete picture.")
                }
                example("find branches for a ticket") {
                    param("action", "get_branches")
                    param("filter", "feature/PROJ-456")
                    outcome("Returns only branches whose name contains 'feature/PROJ-456', typically 0-2 results.")
                    notes("Much cheaper in tokens than fetching all branches and scanning client-side.")
                }
                verdict {
                    keep("Required read step before `create_branch` (to verify the branch doesn't already exist) and before `create_pr` (to confirm the source branch name). Defers well to the cheap filter param.", VerdictSeverity.NORMAL)
                }
            }
            // ── create_branch ────────────────────────────────────────────────────────────────
            action("create_branch") {
                description {
                    technical(
                        "Creates a new branch from a given git ref via Bitbucket DC REST. " +
                        "Delegates to BitbucketService.createBranch(name, startPoint, repoName). " +
                        "Write action — subject to the approval gate and blocked in plan mode."
                    )
                    plain(
                        "Cuts a new branch on Bitbucket — like clicking 'Create branch from...' in the UI. " +
                        "Validates name and start_point are non-blank before sending the API request."
                    )
                }
                whenLLMUses(
                    "When the user says 'create a branch for PROJ-123' or as part of a start-work workflow " +
                    "that requires a remote branch on Bitbucket (e.g. when jira.start_work only creates locally). " +
                    "Typically preceded by `get_branches` to confirm the branch doesn't already exist."
                )
                params {
                    required("name", "string") {
                        llmSeesIt("Branch name — for create_branch")
                        humanReadable("The name for the new branch — e.g. 'feature/PROJ-123-login-refactor'.")
                        whenPresent("Validated non-blank via ToolValidation.validateNotBlank before the API call.")
                        constraint("must be non-blank; Bitbucket enforces git ref naming rules (no spaces, no .., etc.)")
                        example("feature/PROJ-123-login-refactor")
                        example("hotfix/critical-npe-fix")
                    }
                    required("start_point", "string") {
                        llmSeesIt("Source ref to branch from — for create_branch")
                        humanReadable("The commit SHA, branch name, or tag to branch from — the 'base' of the new branch.")
                        whenPresent("Validated non-blank; used as the `startPoint` in the Bitbucket REST payload.")
                        constraint("must be a valid git ref: branch name, full or short commit SHA, or tag")
                        example("main")
                        example("develop")
                        example("a1b2c3d4")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects — omit for primary")
                        humanReadable("Target repo; omit for the primary repo.")
                        whenPresent("Branch is created in this repo instead of the primary.")
                        whenAbsent("Branch is created in the primary repo from ConnectionSettings.")
                        example("backend-api")
                    }
                    optional("description", "string") {
                        llmSeesIt("Approval dialog description for write actions: create_branch")
                        humanReadable("One-line description shown in the agent approval gate. Recommended for user clarity.")
                        whenPresent("Shown in the approval dialog before the branch is created.")
                        whenAbsent("Approval dialog falls back to action+name.")
                        example("Create branch feature/PROJ-123 from main")
                    }
                }
                precondition("User must have the Write or Admin permission on the repo; the token must have repository:write scope.")
                precondition("start_point must resolve to a known ref in the target repo.")
                precondition("The branch must not already exist (Bitbucket returns 409 Conflict if it does).")
                onSuccess("Returns confirmation with the new branch name, its starting commit SHA, and a link to the branch page.")
                onFailure("409 Conflict", "Branch already exists. LLM should surface this to the user or use the existing branch.")
                onFailure("400 Bad Request", "Usually malformed branch name (spaces, ..) or unresolvable start_point. Re-confirm both with the user.")
                onFailure("403 Forbidden", "Token lacks write permission. Surface to user; do not retry.")
                example("create feature branch from develop") {
                    param("action", "create_branch")
                    param("name", "feature/PROJ-456-new-checkout")
                    param("start_point", "develop")
                    param("description", "Create feature/PROJ-456 from develop")
                    outcome("Branch created on Bitbucket; LLM can now open a PR from this branch.")
                }
                verdict {
                    keep("Necessary when the remote branch doesn't exist yet and the workflow requires a Bitbucket-side branch. Cannot be replicated with local git commands when the remote is Bitbucket DC behind auth.", VerdictSeverity.NORMAL)
                }
            }
            // ── search_users ─────────────────────────────────────────────────────────────────
            action("search_users") {
                description {
                    technical(
                        "Searches for Bitbucket DC users by display name or username substring. " +
                        "Delegates to BitbucketService.searchUsers(filter, repoName). " +
                        "`filter` is required and validated non-blank."
                    )
                    plain(
                        "Finds a Bitbucket user by name — like typing in the reviewer autocomplete box in the PR " +
                        "UI but returning the full user record (slug, displayName, emailAddress) as text. " +
                        "The slug is what you need when adding PR reviewers via bitbucket_pr."
                    )
                }
                whenLLMUses(
                    "When the user says 'add Jane Smith as a reviewer' and the LLM needs to resolve a " +
                    "human-readable name to a Bitbucket user slug before calling `bitbucket_pr.add_reviewer`. " +
                    "Also useful when mapping a Jira assignee name to their Bitbucket identity."
                )
                params {
                    required("filter", "string") {
                        llmSeesIt("Name filter — for get_branches, search_users")
                        humanReadable("Display name or username substring to search. Case-insensitive server-side.")
                        whenPresent("Validated non-blank; passed as the user-search query to the Bitbucket API.")
                        constraint("must be non-blank")
                        constraint("minimum useful length is 2-3 characters to avoid oversized result sets")
                        example("jane.smith")
                        example("Jane")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects — omit for primary")
                        humanReadable("Which repo's user context to search within; omit for the primary repo.")
                        whenPresent("Search is scoped to users with a role in this repo.")
                        whenAbsent("Uses the primary repo context.")
                        example("backend-api")
                    }
                }
                precondition("filter must be non-blank (validated before network call).")
                onSuccess("Returns a list of user records: slug (the ID used in reviewer/author params), displayName, emailAddress.")
                onFailure("no results", "Empty list — the filter matched nobody. LLM should ask the user to confirm the name spelling or try a shorter prefix.")
                onFailure("401/403", "Token expired or lacks user-read permission. Surface to user.")
                example("resolve a reviewer by name") {
                    param("action", "search_users")
                    param("filter", "Jane Smith")
                    outcome("Returns user record with slug='jsmith'; LLM passes slug to bitbucket_pr.add_reviewer.")
                }
                verdict {
                    keep("Irreplaceable for name-to-slug resolution when adding PR reviewers. bitbucket_pr add_reviewer requires the slug, not a display name.", VerdictSeverity.NORMAL)
                }
            }
            // ── get_file_content ─────────────────────────────────────────────────────────────
            action("get_file_content") {
                description {
                    technical(
                        "Fetches raw file content at a specific git ref via Bitbucket DC REST. " +
                        "Delegates to BitbucketService.getFileContent(filePath, atRef, repoName). " +
                        "Both `file_path` and `at_ref` are required and validated non-blank."
                    )
                    plain(
                        "Reads a file as it existed at any point in git history — like running " +
                        "`git show <ref>:<path>` but over HTTPS instead of a local clone. " +
                        "Useful for comparing the current version of a file with a different branch or commit."
                    )
                }
                whenLLMUses(
                    "When the LLM needs to read a file on a DIFFERENT branch or at a historical commit " +
                    "(e.g. 'what did AuthService.kt look like on main before this PR?'). " +
                    "Also the ONLY path when the file is not checked out locally (e.g. looking at a " +
                    "colleague's unmerged feature branch without fetching it). " +
                    "Do NOT use for files already in the local working tree — use `read_file` instead."
                )
                params {
                    required("file_path", "string") {
                        llmSeesIt("File path — for get_file_content")
                        humanReadable("Repo-root-relative path to the file — same format as `git show <ref>:<path>`.")
                        whenPresent("Validated non-blank; used as the path segment in the Bitbucket raw-content URL.")
                        constraint("must be non-blank; must be a valid path relative to repo root (no leading slash)")
                        example("src/main/kotlin/com/example/AuthService.kt")
                        example("README.md")
                    }
                    required("at_ref", "string") {
                        llmSeesIt("Git ref (branch/tag/commit) — for get_file_content")
                        humanReadable("The branch name, tag, or full/short commit SHA to read the file at.")
                        whenPresent("Validated non-blank; sent as the `at` query parameter in the Bitbucket request.")
                        constraint("must be a valid git ref in the target repo (Bitbucket resolves it server-side)")
                        example("main")
                        example("feature/PROJ-123")
                        example("a1b2c3d")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects — omit for primary")
                        humanReadable("Which repo to read from; omit for the primary repo.")
                        whenPresent("File is read from this repo instead of the primary.")
                        whenAbsent("Primary repo is used.")
                        example("backend-api")
                    }
                }
                precondition("file_path must exist at the given at_ref in the target repo.")
                precondition("atRef must resolve to a known commit, branch, or tag.")
                onSuccess("Returns raw file content as text. No line numbers (unlike read_file). Large files may be truncated by the ToolOutputSpiller (>30K chars → preview + spill path).")
                onFailure("file not found at ref", "404 — path doesn't exist at that ref. Re-confirm path and ref with the user.")
                onFailure("binary file", "Returns garbled bytes. LLM should check the file extension and avoid fetching non-text files.")
                onFailure("ref not found", "400 or 404 — the branch/tag/SHA doesn't exist. Re-confirm the ref.")
                rejectsParam("filter", "filter is for get_branches / search_users — get_file_content uses file_path and at_ref instead.")
                example("read a file on main") {
                    param("action", "get_file_content")
                    param("file_path", "src/main/kotlin/com/example/AuthService.kt")
                    param("at_ref", "main")
                    outcome("Returns the current content of AuthService.kt on main.")
                    notes("If this file exists in the local working tree on the same ref, prefer `read_file` — it returns line numbers.")
                }
                example("compare a file across branches") {
                    param("action", "get_file_content")
                    param("file_path", "build.gradle.kts")
                    param("at_ref", "feature/PROJ-789-upgrade-deps")
                    outcome("Returns build.gradle.kts as it exists on the feature branch — useful for reviewing dependency changes before merging.")
                }
                verdict {
                    keep("Unique capability: read any file at any ref without checking it out locally. Overlaps with `read_file` for local working-tree files but is the only option for remote refs.", VerdictSeverity.NORMAL)
                    drop("Consider gating with a local-file-existence check — if the file is in the local tree at the same ref, `read_file` is strictly better (line numbers, no network). A documentation note may suffice over an action removal.", VerdictSeverity.WEAK)
                }
            }
            // ── get_build_statuses ───────────────────────────────────────────────────────────
            action("get_build_statuses") {
                description {
                    technical(
                        "Fetches all CI build statuses attached to a commit SHA from Bitbucket DC's " +
                        "build-status API (/rest/build-status/1.0/commits/{sha}). " +
                        "Returns per-build key, state (SUCCESSFUL/FAILED/INPROGRESS), name, and URL. " +
                        "Delegates to BitbucketService.getBuildStatuses(commitId, repoName)."
                    )
                    plain(
                        "Asks Bitbucket 'what CI builds ran on this commit and did they pass?' — " +
                        "like clicking the build-status badge next to a commit in the UI and seeing every " +
                        "pipeline listed (Bamboo, Jenkins, GitHub Actions, etc.)."
                    )
                }
                whenLLMUses(
                    "When the user asks 'did the CI pass on commit abc123?' or 'which builds failed on this PR?' " +
                    "and needs the individual build details (URL, key, name) rather than just a pass/fail count. " +
                    "Use `get_commit_build_stats` instead when only the aggregate count is needed."
                )
                params {
                    required("commit_id", "string") {
                        llmSeesIt("Commit hash — for get_build_statuses")
                        humanReadable("The full or abbreviated commit SHA to query build statuses for.")
                        whenPresent("Validated non-blank; used as the path segment in the build-status API URL.")
                        constraint("must be a valid commit SHA (full 40-char or abbreviated ≥7 chars); validated non-blank")
                        example("a1b2c3d4e5f6")
                        example("a1b2c3d")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects — omit for primary")
                        humanReadable("Repo context; the build-status API is commit-global in Bitbucket DC, but this may affect service routing for multi-repo projects.")
                        whenPresent("Passed through to the service; may influence repo-context resolution.")
                        whenAbsent("Primary repo is used.")
                        example("backend-api")
                    }
                }
                precondition("commit_id must be non-blank (validated before API call).")
                onSuccess("Returns a list of build status entries: key (e.g. 'PROJ-JOB-1'), state (SUCCESSFUL / FAILED / INPROGRESS), name, description, URL, and the timestamp. Multiple CI providers may appear in the same list.")
                onFailure("no statuses found", "Returns empty list — either no CI has reported on this commit yet, or the SHA is from before CI was wired. Not an error.")
                onFailure("commit not found", "404 or empty — SHA doesn't exist in the repo. Re-confirm the commit with the user.")
                example("check CI status for a commit") {
                    param("action", "get_build_statuses")
                    param("commit_id", "a1b2c3d4e5f6")
                    outcome("Returns list of build statuses; LLM can report which jobs passed and link to failed build logs.")
                    notes("If the LLM only needs pass/fail totals, use get_commit_build_stats instead — it is cheaper in tokens.")
                }
                verdict {
                    keep("The only cross-provider CI status view for a commit. bamboo_builds.build_status is Bamboo-specific; this covers all providers attached to Bitbucket DC. Required for 'did the build pass?' checks across heterogeneous CI setups.", VerdictSeverity.NORMAL)
                }
            }
            // ── get_commit_build_stats ────────────────────────────────────────────────────────
            action("get_commit_build_stats") {
                description {
                    technical(
                        "Returns an aggregate build counter for a commit: {successful, failed, inProgress}. " +
                        "Introduced as R-ADD-12. Delegates to BitbucketService.getCommitBuildStats(commitId). " +
                        "Does NOT accept repo_name — the endpoint is commit-global in Bitbucket DC."
                    )
                    plain(
                        "Asks 'did the CI pass on this commit?' and gets back a simple scoreboard: " +
                        "3 successful, 0 failed, 0 in progress. No URLs, no build names — just the numbers. " +
                        "Like a quick green/red badge rather than the full CI panel."
                    )
                }
                whenLLMUses(
                    "When the LLM needs only a pass/fail summary for a commit — e.g. 'is this commit safe to " +
                    "merge?' — and does not need individual build URLs or names. Much cheaper in tokens than " +
                    "`get_build_statuses` for the same commit."
                )
                params {
                    required("commit_id", "string") {
                        llmSeesIt("Commit hash — for get_build_statuses")
                        humanReadable("The commit SHA to aggregate build counts for.")
                        whenPresent("Validated non-blank; used as the commit identifier in the aggregate API call.")
                        constraint("must be a valid non-blank commit SHA")
                        example("a1b2c3d4e5f6")
                    }
                }
                rejectsParam("repo_name", "The Bitbucket DC commit build-stats endpoint is commit-global; repo_name is ignored by this action even if provided.")
                precondition("commit_id must be non-blank (validated before API call).")
                onSuccess("Returns a compact JSON block: `{successful: N, failed: M, inProgress: K}`. Zero totals are included.")
                onFailure("commit not found", "Empty or zero counters — CI may not have reported yet or SHA is unknown. Not treated as an error.")
                example("quick pass/fail check") {
                    param("action", "get_commit_build_stats")
                    param("commit_id", "a1b2c3d4e5f6")
                    outcome("Returns `{successful: 3, failed: 0, inProgress: 0}` — LLM concludes CI passed.")
                    notes("Prefer this over get_build_statuses when the user just wants 'did it pass?' — far fewer tokens.")
                }
                verdict {
                    keep("Token-efficient CI pass/fail gate. The compact output makes it the right first call in any 'is this commit ready?' workflow before escalating to the full get_build_statuses if needed.", VerdictSeverity.NORMAL)
                }
            }
            // ── get_commit_pull_requests ──────────────────────────────────────────────────────
            action("get_commit_pull_requests") {
                description {
                    technical(
                        "Reverse-lookup: given a commit SHA, returns all PRs that contain that commit. " +
                        "Introduced as R-ADD-5. Delegates to BitbucketService.getPullRequestsForCommit(commitId, repoName). " +
                        "This is the only way to answer 'which PR introduced commit X?' without browsing git history."
                    )
                    plain(
                        "Starts from a commit and finds its PR — like asking 'which PR shipped this change?' " +
                        "GitHub has this on the commit page; Bitbucket DC exposes it via a REST API that this " +
                        "action wraps."
                    )
                }
                whenLLMUses(
                    "When the user has a commit SHA (e.g. from a bug report, git blame, or a Jira dev-status " +
                    "panel) and wants to know which PR(s) it belongs to — e.g. 'who reviewed this change?' " +
                    "or 'was this commit part of a PR that was approved?'"
                )
                params {
                    required("commit_id", "string") {
                        llmSeesIt("Commit hash — for get_build_statuses")
                        humanReadable("The commit SHA to reverse-lookup. The tool finds PRs that contain this commit.")
                        whenPresent("Validated non-blank; used as the commit SHA in the reverse-lookup API call.")
                        constraint("must be a valid non-blank commit SHA")
                        example("a1b2c3d4e5f6")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects — omit for primary")
                        humanReadable("Repo to search for PRs containing this commit. Omit for the primary repo.")
                        whenPresent("Reverse-lookup is scoped to this repo.")
                        whenAbsent("Primary repo is used.")
                        example("backend-api")
                    }
                }
                precondition("commit_id must be non-blank.")
                precondition("The commit must be reachable from a branch that has or had an open/merged PR in the target repo.")
                onSuccess("Returns a list of PRs that contain the commit: PR ID, title, state (OPEN/MERGED/DECLINED), author, source branch, target branch, and a link.")
                onFailure("no PRs found", "Empty list — commit was pushed directly to the branch without a PR, or the PR was abandoned. Not an error.")
                onFailure("commit not found", "404 or empty — SHA not in the repo. Re-confirm with user.")
                example("trace a commit back to its PR") {
                    param("action", "get_commit_pull_requests")
                    param("commit_id", "a1b2c3d4e5f6")
                    outcome("Returns PR #42 (MERGED): 'Add login rate-limiting' — LLM can then use bitbucket_pr.get_pr_detail for the full review context.")
                }
                verdict {
                    keep("Unique reverse-lookup path not available in any other tool. Cannot be replicated with git commands alone when the remote is Bitbucket DC.", VerdictSeverity.STRONG)
                }
            }
            // ── list_repos ────────────────────────────────────────────────────────────────────
            action("list_repos") {
                description {
                    technical(
                        "Lists all repositories in the Bitbucket DC project configured in ConnectionSettings. " +
                        "Delegates to BitbucketService.listRepos(). No parameters beyond `action`."
                    )
                    plain(
                        "Asks Bitbucket 'what repos exist in this project?' — like opening the project's " +
                        "repository list page. Returns repo slugs, names, clone URLs, and default branches."
                    )
                }
                whenLLMUses(
                    "When the user asks 'what repositories are in this project?' or when the LLM needs to " +
                    "discover valid `repo_name` values to pass to other actions (get_branches, get_file_content, etc.)."
                )
                params {
                }
                onSuccess("Returns a list of repo entries: slug (the `repo_name` value for other actions), name, description, default branch, HTTP clone URL, and SSH clone URL.")
                onFailure("project not found", "404 — the project key in ConnectionSettings is wrong. User must correct it in Settings.")
                onFailure("401/403", "Token expired or lacks project-read permission. Surface to user.")
                example("discover repos") {
                    param("action", "list_repos")
                    outcome("Returns slugs for backend-api, frontend-web, infra-tf. LLM can now use these as repo_name in subsequent calls.")
                }
                verdict {
                    keep("Discovery action for multi-repo projects. Without it the LLM must guess repo_name values and risk 404 errors.", VerdictSeverity.NORMAL)
                }
            }
        }
        flowchart(
            """
            flowchart TD
                A[LLM needs Bitbucket data] --> B{What kind?}
                B -- branch info --> C[get_branches filter?]
                B -- create branch --> D[get_branches verify not exists]
                D --> E[create_branch name start_point]
                B -- user lookup --> F[search_users filter]
                F --> G[Use slug in bitbucket_pr]
                B -- file at ref --> H{Local working tree?}
                H -- yes, same ref --> I[read_file — faster, line numbers]
                H -- no, remote/different ref --> J[get_file_content file_path at_ref]
                B -- CI status --> K{Need per-build detail?}
                K -- yes --> L[get_build_statuses commit_id]
                K -- no, just pass/fail --> M[get_commit_build_stats commit_id]
                B -- find PR for commit --> N[get_commit_pull_requests commit_id]
                N --> O[bitbucket_pr.get_pr_detail id]
                B -- discover repos --> P[list_repos]
            """
        )
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
            "get_branches" -> {
                val filter = params["filter"]?.jsonPrimitive?.content
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getBranches(filter, repoName = repoName).toAgentToolResult()
            }

            "create_branch" -> {
                val name = params["name"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("name")
                val startPoint = params["start_point"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("start_point")
                ToolValidation.validateNotBlank(name, "name")?.let { return it }
                ToolValidation.validateNotBlank(startPoint, "start_point")?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.createBranch(name, startPoint, repoName = repoName).toAgentToolResult()
            }

            "search_users" -> {
                val filter = params["filter"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("filter")
                ToolValidation.validateNotBlank(filter, "filter")?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.searchUsers(filter, repoName = repoName).toAgentToolResult()
            }

            "get_file_content" -> {
                val filePath = params["file_path"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("file_path")
                val atRef = params["at_ref"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("at_ref")
                ToolValidation.validateNotBlank(filePath, "file_path")?.let { return it }
                ToolValidation.validateNotBlank(atRef, "at_ref")?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getFileContent(filePath, atRef, repoName = repoName).toAgentToolResult()
            }

            "get_build_statuses" -> {
                val commitId = params["commit_id"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("commit_id")
                ToolValidation.validateNotBlank(commitId, "commit_id")?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getBuildStatuses(commitId, repoName = repoName).toAgentToolResult()
            }

            "get_commit_build_stats" -> {
                val commitId = params["commit_id"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("commit_id")
                ToolValidation.validateNotBlank(commitId, "commit_id")?.let { return it }
                service.getCommitBuildStats(commitId).toAgentToolResult()
            }

            "get_commit_pull_requests" -> {
                val commitId = params["commit_id"]?.jsonPrimitive?.content ?: return BitbucketToolUtils.missingParam("commit_id")
                ToolValidation.validateNotBlank(commitId, "commit_id")?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getPullRequestsForCommit(commitId, repoName = repoName).toAgentToolResult()
            }

            "list_repos" -> {
                service.listRepos().toAgentToolResult()
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

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
import java.nio.file.Paths

/**
 * Bamboo plan management tool — discover, search, and configure build plans.
 *
 * Split from the consolidated BambooTool. Covers 10 plan-oriented actions:
 * get_projects, get_plans, get_project_plans, search_plans, get_plan_branches,
 * get_build_variables, get_plan_variables, rerun_failed_jobs, trigger_stage,
 * auto_detect_plan.
 *
 * auto_detect_plan routing:
 *  - Legacy (1-arg): only git_remote_url supplied → uses fast plan-list scan.
 *  - Rich (5-tier): any of repo_root / branch_name / preferred_master supplied →
 *    uses the richer multi-tier detection (better for multi-module repos).
 */
class BambooPlansTool : AgentTool {

    override val name = "bamboo_plans"

    override val description = """
REMOTE CI ONLY: Bamboo plan management — discover, search, and configure build plans and variables.

Use for: 'list Bamboo plans', 'find a CI plan for repo X', 'show plan branches', 'rerun failed CI jobs'.
Do NOT use for: local IDE Maven/Gradle reload, project module discovery, or local build configuration — use get_build_problems / project_structure for those.

Actions and their parameters:
- get_projects() → List all Bamboo projects (use this before get_project_plans to discover valid project keys)
- get_plans() → List all build plans
- get_project_plans(project_key) → Plans in a project
- search_plans(query) → Search plans by name
- get_plan_branches(plan_key, repo_name?) → Plan branch list
- get_build_variables(result_key) → Variables for a specific build
- get_plan_variables(plan_key) → Default plan variables
- rerun_failed_jobs(plan_key, build_number) → Rerun failed jobs in build
- trigger_stage(plan_key, stage?, variables?) → Trigger specific stage (variables: JSON {"key":"value"})
- auto_detect_plan(git_remote_url, repo_root?, branch_name?, preferred_master?) →
    Auto-detect the Bamboo plan key from a Git remote URL.
    When repo_root / branch_name / preferred_master are absent: uses legacy 1-arg detection (fast plan-list scan).
    When any of those optional params is supplied: uses the richer 5-tier detection (better for multi-module repos).

description optional: for approval dialog on rerun_failed_jobs/trigger_stage.
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf(
                    "get_projects", "get_plans", "get_project_plans", "search_plans", "get_plan_branches",
                    "get_build_variables", "get_plan_variables", "rerun_failed_jobs", "trigger_stage",
                    "auto_detect_plan"
                )
            ),
            "plan_key" to ParameterProperty(
                type = "string",
                description = "Bamboo plan key e.g. PROJ-PLAN — for get_plan_branches, get_plan_variables, rerun_failed_jobs, trigger_stage"
            ),
            "project_key" to ParameterProperty(
                type = "string",
                description = "Bamboo project key e.g. PROJ — for get_project_plans"
            ),
            "query" to ParameterProperty(
                type = "string",
                description = "Search query — for search_plans"
            ),
            "repo_name" to ParameterProperty(
                type = "string",
                description = "Repository name for multi-repo projects — for get_plan_branches"
            ),
            "result_key" to ParameterProperty(
                type = "string",
                description = "Bamboo build result key e.g. PROJ-PLAN-123 — for get_build_variables"
            ),
            "build_number" to ParameterProperty(
                type = "string",
                description = "Build number integer — for rerun_failed_jobs"
            ),
            "stage" to ParameterProperty(
                type = "string",
                description = "Stage name to trigger (optional) — for trigger_stage"
            ),
            "variables" to ParameterProperty(
                type = "string",
                description = "JSON object of build variables e.g. '{\"key\":\"value\"}' — for trigger_stage"
            ),
            "git_remote_url" to ParameterProperty(
                type = "string",
                description = "Git remote URL (e.g. git@github.com:org/repo.git or https://github.com/org/repo) — for auto_detect_plan"
            ),
            "repo_root" to ParameterProperty(
                type = "string",
                description = "Optional. Local repo root path. When provided alongside git_remote_url (or with branch_name/preferred_master), routes auto_detect_plan to the richer 5-tier detection (better for multi-module repos)."
            ),
            "branch_name" to ParameterProperty(
                type = "string",
                description = "Optional. Branch to detect. Triggers the 5-tier auto_detect_plan overload when present."
            ),
            "preferred_master" to ParameterProperty(
                type = "string",
                description = "Optional. Preferred master/main branch name. Triggers the 5-tier auto_detect_plan overload when present."
            ),
            "description" to ParameterProperty(
                type = "string",
                description = "Brief description shown in approval dialog — for write actions: rerun_failed_jobs, trigger_stage"
            )
        ),
        required = listOf("action")
    )

    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ORCHESTRATOR)

    override fun documentation(): ToolDocumentation = toolDoc("bamboo_plans") {
        summary {
            technical(
                "Single-tool dispatcher for 10 Bamboo plan management operations: project/plan discovery, " +
                "plan search, branch listing, build variable inspection, variable defaults, failed-job " +
                "rerun, stage triggering with per-variable overrides, and Git-remote-to-plan-key " +
                "auto-detection (legacy 1-arg scan + rich 5-tier multi-module resolver). Bearer-auth " +
                "via PasswordSafe; conditionally registered when ConnectionSettings.bambooUrl is non-blank."
            )
            plain(
                "The agent's Bamboo 'what can I build?' remote control. Like a Bamboo sidebar that " +
                "lists all your CI build plans, lets you search them by name, shows which branches " +
                "are configured, and can kick off a specific stage — as opposed to bamboo_builds, " +
                "which shows you what *already happened* (build results, logs, test outcomes). " +
                "Plans are the blueprint; builds are the execution."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.NETWORK)
        counterfactual(
            "Without `bamboo_plans`, the LLM falls back to `run_command curl -H 'Authorization: Bearer <token>' " +
            "https://bamboo/rest/api/latest/plan.json`. That exposes the Bamboo token in shell history and agent " +
            "logs, returns raw Atlassian JSON the LLM must parse, and provides no auto-detection heuristic for " +
            "matching a Git remote URL to a plan key. The `auto_detect_plan` action alone (matching a remote URL " +
            "to a plan key across potentially hundreds of plans) is not practically replaceable with a single curl."
        )
        llmMistake(
            "Calls `get_plans` when `search_plans` would be faster and cheaper. `get_plans` returns the " +
            "full plan list (potentially hundreds of entries); `search_plans` does server-side filtering. " +
            "Use `get_plans` only when you need an exhaustive enumeration; use `search_plans` whenever " +
            "you have any part of the plan name."
        )
        llmMistake(
            "Confuses `plan_key` format (PROJ-PLAN, two segments) with `result_key` / `build_number` " +
            "format (PROJ-PLAN-123, three segments). `get_plan_branches`, `get_plan_variables`, " +
            "`rerun_failed_jobs`, and `trigger_stage` expect a two-segment plan_key. " +
            "`get_build_variables` expects a three-segment result_key. Using the wrong format returns " +
            "a validation error."
        )
        llmMistake(
            "Calls `rerun_failed_jobs` or `trigger_stage` instead of the equivalent actions in " +
            "`bamboo_builds` when what it really wants is to trigger a *fresh* build from scratch. " +
            "`rerun_failed_jobs` retries only the failed jobs of an existing build run; " +
            "`bamboo_builds(trigger_build)` queues a new build from the top. The two are semantically " +
            "different; pick `bamboo_builds` for new builds and `bamboo_plans` only for re-run/stage."
        )
        llmMistake(
            "Calls `auto_detect_plan` with only `git_remote_url` when it also has `repo_root` or " +
            "`branch_name` from context. The richer 5-tier overload is substantially more accurate " +
            "for multi-module repos. LLM should pass all available location signals."
        )
        llmMistake(
            "Skips `get_projects` and passes a made-up `project_key` to `get_project_plans`. " +
            "Project keys in Bamboo are not the same as in Jira — always enumerate via `get_projects` " +
            "first to discover valid keys."
        )
        llmMistake(
            "Treats 401 from any action as retryable. 401 means the stored Bamboo token is invalid; " +
            "retrying just yields another 401. Surface to the user and stop."
        )
        actions {
            action("get_projects") {
                description {
                    technical("Lists all Bamboo projects with their keys. No parameters required.")
                    plain("Shows you the top-level project folders in Bamboo — like viewing the project list in the Bamboo sidebar.")
                }
                whenLLMUses(
                    "When the user asks 'what Bamboo projects exist' or before calling `get_project_plans` " +
                    "to discover valid project keys."
                )
                params { /* no params beyond action */ }
                precondition("Bamboo URL must be configured in Settings (tool not registered otherwise).")
                onSuccess("Returns the list of Bamboo projects: each entry has a project key and name.")
                onFailure("401 Unauthorized", "Bamboo token is expired or invalid. Stop and ask the user to re-enter the token in Settings.")
                onFailure("503 / connection refused", "Bamboo is unreachable. Check VPN / server status.")
                example("discover all projects") {
                    param("action", "get_projects")
                    outcome("Returns project list: key MYPROJ → 'My Project', key INFRA → 'Infrastructure', etc.")
                }
                verdict {
                    keep("Required precondition for `get_project_plans`. Low cost; no alternative.", VerdictSeverity.NORMAL)
                    drop("Low standalone value — most users know their project key. Could be removed if `get_project_plans` validated the key and returned a helpful error message listing valid keys.", VerdictSeverity.WEAK)
                }
            }
            action("get_plans") {
                description {
                    technical("Returns all build plans across all projects. Expensive on large Bamboo instances — can return hundreds of plans.")
                    plain("Lists every CI build plan on the server — like pulling up the 'All Plans' view in Bamboo. Use sparingly on large servers.")
                }
                whenLLMUses(
                    "When the user asks 'list all Bamboo plans' or when `auto_detect_plan` needs a " +
                    "full scan (legacy 1-arg path). Prefer `search_plans` when any part of the name is known."
                )
                params { /* no params beyond action */ }
                precondition("Bamboo URL configured.")
                onSuccess("Returns the full plan list: each entry has key (e.g. PROJ-PLAN), name, enabled flag.")
                onFailure("Large response truncated", "On very large Bamboo instances the response is spilled to disk via ToolOutputSpiller. Use `search_plans` instead.")
                onFailure("401", "Invalid token — stop.")
                example("enumerate all plans") {
                    param("action", "get_plans")
                    outcome("Returns plan list; LLM scans for the matching plan by name or key.")
                    notes("Prefer `search_plans` if any part of the plan name is known — avoids fetching the full list.")
                }
                verdict {
                    keep("Needed by `auto_detect_plan` legacy path and for user requests to enumerate everything.", VerdictSeverity.NORMAL)
                    drop("High cost on large Bamboo instances. If `search_plans` supported wildcard/empty query the LLM could always use search instead.", VerdictSeverity.WEAK)
                }
            }
            action("get_project_plans") {
                description {
                    technical("Returns all build plans within a specific project, identified by project key.")
                    plain("Narrows the plan list to a single Bamboo project — like clicking on a project folder in the Bamboo sidebar to see only its plans.")
                }
                whenLLMUses(
                    "When the user knows which project they care about and wants to enumerate its plans " +
                    "without fetching the full global list."
                )
                params {
                    required("project_key", "string") {
                        llmSeesIt("Bamboo project key e.g. PROJ — for get_project_plans")
                        humanReadable("The short project identifier in Bamboo (e.g. PROJ, INFRA). Discover valid keys via `get_projects`.")
                        whenPresent("Plans for this project are returned.")
                        constraint("must be a valid Bamboo project key — use get_projects to discover")
                        example("PROJ")
                        example("INFRA")
                    }
                }
                rejectsParam("plan_key", "get_project_plans operates at project level, not plan level")
                precondition("Bamboo URL configured. project_key must exist.")
                onSuccess("Returns the plans in the project: key, name, enabled flag.")
                onFailure("project not found", "Returns an error. Call `get_projects` to discover valid keys.")
                onFailure("401", "Invalid token — stop.")
                example("list plans in MYPROJ") {
                    param("action", "get_project_plans")
                    param("project_key", "MYPROJ")
                    outcome("Returns all plans in the MYPROJ Bamboo project.")
                }
                verdict {
                    keep("More efficient than `get_plans` when the project is known — saves returning the entire plan catalog.", VerdictSeverity.NORMAL)
                }
            }
            action("search_plans") {
                description {
                    technical("Server-side plan search by name substring. More efficient than `get_plans` on large Bamboo instances.")
                    plain("Type-ahead search for Bamboo plans — like the search box in Bamboo that filters the plan list as you type.")
                }
                whenLLMUses(
                    "Whenever the user mentions any part of a plan name and the LLM needs the matching " +
                    "plan key. Prefer over `get_plans` whenever a name fragment is available."
                )
                params {
                    required("query", "string") {
                        llmSeesIt("Search query — for search_plans")
                        humanReadable("Any substring of the plan name to search for.")
                        whenPresent("Server-side search is performed; matching plans returned.")
                        constraint("must be non-blank (validateNotBlank)")
                        example("deploy")
                        example("service-a")
                        example("integration test")
                    }
                }
                rejectsParam("plan_key", "search_plans does not filter by a specific plan key — use get_plan_branches or get_plan_variables for that")
                precondition("Bamboo URL configured.")
                onSuccess("Returns plans whose names contain the query string: key and name per plan.")
                onFailure("no matches", "Empty list returned (not an error). LLM should broaden the query or fall back to `get_plans`.")
                onFailure("401", "Invalid token — stop.")
                example("find plans matching 'backend'") {
                    param("action", "search_plans")
                    param("query", "backend")
                    outcome("Returns plan list filtered to plans whose names contain 'backend'.")
                }
                verdict {
                    keep("The canonical 'find me the plan key' action. Saves token cost vs `get_plans` on large Bamboo instances.", VerdictSeverity.STRONG)
                }
            }
            action("get_plan_branches") {
                description {
                    technical("Lists branches configured for a plan. Optionally filters by repository name for multi-repo plans.")
                    plain("Shows which Git branches have their own Bamboo plan branch (branch plan = a parallel build for that branch). Useful before triggering a build on a feature branch.")
                }
                whenLLMUses(
                    "When the user asks 'does branch X have a CI plan?' or before triggering a build " +
                    "on a non-master branch to confirm a branch plan exists."
                )
                params {
                    required("plan_key", "string") {
                        llmSeesIt("Bamboo plan key e.g. PROJ-PLAN — for get_plan_branches, get_plan_variables, rerun_failed_jobs, trigger_stage")
                        humanReadable("The two-segment Bamboo plan key (project + plan short key).")
                        whenPresent("Validated by ToolValidation.validateBambooPlanKey before the request.")
                        constraint("must match two-segment Bamboo plan key: ^[A-Z][A-Z0-9_]+-[A-Z][A-Z0-9_]+$")
                        example("PROJ-PLAN")
                        example("MYAPP-BUILD")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects — for get_plan_branches")
                        humanReadable("When the plan uses multiple linked repositories, filter branches to just this repo.")
                        whenPresent("Branch list filtered to the named repository.")
                        whenAbsent("All branch plans returned across all linked repositories.")
                        example("my-service")
                    }
                }
                precondition("Bamboo URL configured. plan_key must exist.")
                onSuccess("Returns list of configured branch plans: branch name, key, enabled flag, last build result.")
                onFailure("plan not found", "Returns an error. Confirm the plan key via `search_plans` or `get_plans`.")
                onFailure("plan has no branches", "Empty list (not an error). The plan has only a master/trunk build.")
                example("list branch plans for MYAPP-BUILD") {
                    param("action", "get_plan_branches")
                    param("plan_key", "MYAPP-BUILD")
                    outcome("Returns branch plan list; LLM checks whether feature/my-branch appears.")
                }
                example("filter by repo in a multi-repo plan") {
                    param("action", "get_plan_branches")
                    param("plan_key", "MYAPP-BUILD")
                    param("repo_name", "my-service")
                    outcome("Returns only branches for the 'my-service' repository.")
                }
                verdict {
                    keep("Needed before triggering a branch build to confirm the branch plan exists. Without this, the LLM might trigger on a non-existent branch key.", VerdictSeverity.NORMAL)
                }
            }
            action("get_build_variables") {
                description {
                    technical("Returns the variable values that were used in a specific build result (identified by result_key, e.g. PROJ-PLAN-123).")
                    plain("Shows what variable values were injected into a specific past build run — like checking 'what flags were set when build #123 ran'.")
                }
                whenLLMUses(
                    "When the user asks 'what variables did build PROJ-PLAN-123 use?' or when debugging " +
                    "why a specific build behaved differently from others."
                )
                params {
                    required("result_key", "string") {
                        llmSeesIt("Bamboo build result key e.g. PROJ-PLAN-123 — for get_build_variables")
                        humanReadable("The three-segment build result key: project-plan-buildNumber. Note: this is NOT the same format as plan_key.")
                        whenPresent("Validated by ToolValidation.validateBambooBuildKey before the request.")
                        constraint("must match three-segment Bamboo result key: ^[A-Z][A-Z0-9_]+-[A-Z][A-Z0-9_]+-\\d+$")
                        example("PROJ-PLAN-123")
                        example("MYAPP-BUILD-42")
                    }
                }
                rejectsParam("plan_key", "get_build_variables requires result_key (3 segments) not plan_key (2 segments) — use get_plan_variables for plan-level defaults")
                precondition("Bamboo URL configured. The build result must exist (not expired/deleted).")
                onSuccess("Returns the variable key-value pairs that were in effect for this specific build run.")
                onFailure("result not found", "Build may have been purged per Bamboo retention policy. Try `bamboo_builds(get_build)` first to confirm it still exists.")
                onFailure("401", "Invalid token — stop.")
                example("inspect variables for a specific build") {
                    param("action", "get_build_variables")
                    param("result_key", "PROJ-PLAN-42")
                    outcome("Returns: DEPLOY_ENV=staging, VERSION=1.2.3, etc.")
                }
                verdict {
                    keep("Valuable for debugging 'why did that build behave differently'. Without this, the LLM has to parse raw build logs.", VerdictSeverity.NORMAL)
                    drop("Niche — most users care about plan-level defaults (get_plan_variables), not per-build snapshots. Could merge into bamboo_builds since it operates on a build result key.", VerdictSeverity.WEAK)
                }
            }
            action("get_plan_variables") {
                description {
                    technical("Returns the default variable definitions for a plan (name, value, editable flag). These are the values used in builds unless overridden at trigger time.")
                    plain("Shows the plan's 'environment variables' — the default values that every build of this plan inherits unless explicitly overridden when triggering.")
                }
                whenLLMUses(
                    "When the user asks 'what variables does this plan use?' or before calling `trigger_stage` " +
                    "with custom variables — to know which variable names are valid."
                )
                params {
                    required("plan_key", "string") {
                        llmSeesIt("Bamboo plan key e.g. PROJ-PLAN — for get_plan_branches, get_plan_variables, rerun_failed_jobs, trigger_stage")
                        humanReadable("The two-segment plan key. Discover via `search_plans` or `get_plans`.")
                        whenPresent("Validated by ToolValidation.validateBambooPlanKey.")
                        constraint("two-segment Bamboo plan key (e.g. PROJ-PLAN)")
                        example("PROJ-PLAN")
                    }
                }
                rejectsParam("result_key", "get_plan_variables returns plan defaults, not per-build snapshots — use get_build_variables for build-level values")
                precondition("Bamboo URL configured. plan_key must exist.")
                onSuccess("Returns list of variable definitions: name, default value, whether the variable is editable (can be overridden at trigger time).")
                onFailure("plan not found", "Returns an error. Confirm key via `search_plans`.")
                example("see what variables MYAPP-BUILD supports") {
                    param("action", "get_plan_variables")
                    param("plan_key", "MYAPP-BUILD")
                    outcome("Returns: DEPLOY_ENV (editable, default: staging), SKIP_TESTS (editable, default: false).")
                    notes("LLM can then call trigger_stage with variables overriding these defaults.")
                }
                verdict {
                    keep("Essential pre-flight before trigger_stage(variables=...) — validates variable names and shows editability flags.", VerdictSeverity.NORMAL)
                }
            }
            action("rerun_failed_jobs") {
                description {
                    technical("Retries only the failed jobs from an existing build run. Requires plan_key and build_number. build_number is validated as an integer. Write action — goes through approval gate.")
                    plain("Clicks 'Rerun failed jobs' in the Bamboo UI for a specific build — runs only the jobs that failed, not the whole plan from scratch.")
                }
                whenLLMUses(
                    "When the user says 'rerun the failed jobs in build #42 of MYAPP-BUILD' or when " +
                    "a build had flaky tests and the user wants a cheap retry without a full rebuild."
                )
                params {
                    required("plan_key", "string") {
                        llmSeesIt("Bamboo plan key e.g. PROJ-PLAN — for get_plan_branches, get_plan_variables, rerun_failed_jobs, trigger_stage")
                        humanReadable("The two-segment plan key identifying which plan this build belongs to.")
                        whenPresent("Validated as a two-segment Bamboo plan key.")
                        constraint("two-segment Bamboo plan key (e.g. PROJ-PLAN)")
                        example("MYAPP-BUILD")
                    }
                    required("build_number", "string") {
                        llmSeesIt("Build number integer — for rerun_failed_jobs")
                        humanReadable("The build number to retry (the trailing number in the result key, e.g. 42 from PROJ-PLAN-42).")
                        whenPresent("Parsed as Int; invalid non-numeric values return an explicit error before any network call.")
                        constraint("must be a string that parses as a positive integer")
                        example("42")
                        example("123")
                    }
                    optional("description", "string") {
                        llmSeesIt("Brief description shown in approval dialog — for write actions: rerun_failed_jobs, trigger_stage")
                        humanReadable("Short human-readable description for the approval dialog.")
                        whenPresent("Shown in the agent approval gate so the user can confirm the intent.")
                        whenAbsent("Approval dialog shows a generic message.")
                        example("Rerun failed jobs for MYAPP-BUILD #42")
                    }
                }
                rejectsParam("result_key", "rerun_failed_jobs takes plan_key + build_number separately, not a combined result_key")
                rejectsParam("variables", "rerun_failed_jobs retries with the same variables as the original build — use trigger_stage for variable overrides")
                precondition("Bamboo URL configured.")
                precondition("Build must still exist (not purged by retention policy).")
                precondition("Build must have at least one failed job to retry.")
                onSuccess("Returns confirmation that the rerun was queued; Bamboo reruns only the failed stages/jobs.")
                onFailure("build_number not an integer", "Returns error before any network call.")
                onFailure("build not found", "Bamboo returns 404. Build may have been purged.")
                onFailure("no failed jobs", "Bamboo may return an error or no-op. Confirm via `bamboo_builds(build_status)` first.")
                onFailure("401", "Invalid token — stop.")
                example("retry flaky test stage") {
                    param("action", "rerun_failed_jobs")
                    param("plan_key", "MYAPP-BUILD")
                    param("build_number", "42")
                    param("description", "Retry flaky integration tests on build #42")
                    outcome("Bamboo queues a rerun of only the failed jobs. LLM can poll via `bamboo_builds(build_status)` to track progress.")
                }
                verdict {
                    keep("Distinct from `trigger_build` — retries without the cost of running passing stages again. Frequent use case for flaky tests.", VerdictSeverity.NORMAL)
                }
            }
            action("trigger_stage") {
                description {
                    technical("Triggers a specific stage of a plan with optional variable overrides. `stage` param is optional — omitting it triggers the entire plan. Variables are parsed from a JSON string. Write action — goes through approval gate.")
                    plain("Like clicking 'Run' on a specific stage of a Bamboo plan (not just 'run everything'). You can override build variables at trigger time — e.g. point the deploy stage at a different environment.")
                }
                whenLLMUses(
                    "When the user wants to trigger a Bamboo build or a specific stage of one, optionally " +
                    "with custom variable values. For a new build from scratch (vs retrying failures), " +
                    "use `bamboo_builds(trigger_build)` — this action is preferred when you need per-stage " +
                    "control or variable injection."
                )
                params {
                    required("plan_key", "string") {
                        llmSeesIt("Bamboo plan key e.g. PROJ-PLAN — for get_plan_branches, get_plan_variables, rerun_failed_jobs, trigger_stage")
                        humanReadable("The two-segment plan key to trigger.")
                        whenPresent("Validated as a two-segment Bamboo plan key.")
                        constraint("two-segment Bamboo plan key (e.g. PROJ-PLAN)")
                        example("MYAPP-DEPLOY")
                    }
                    optional("stage", "string") {
                        llmSeesIt("Stage name to trigger (optional) — for trigger_stage")
                        humanReadable("The exact stage name as configured in Bamboo. Omit to trigger the entire plan from the beginning.")
                        whenPresent("Only the named stage is triggered.")
                        whenAbsent("The entire plan is triggered from stage 1.")
                        example("Deploy to staging")
                        example("Integration tests")
                    }
                    optional("variables", "string") {
                        llmSeesIt("JSON object of build variables e.g. '{\"key\":\"value\"}' — for trigger_stage")
                        humanReadable("Override plan-level variable defaults for this run. Pass as a JSON object string.")
                        whenPresent("Parsed by BambooToolUtils.parseVariables; invalid JSON returns an error before the network call.")
                        whenAbsent("Plan-level variable defaults are used unchanged.")
                        constraint("must be a valid JSON object string: {\"key\": \"value\", ...}")
                        constraint("only editable variables can be overridden — check get_plan_variables first")
                        example("{\"DEPLOY_ENV\": \"production\"}")
                        example("{\"SKIP_TESTS\": \"true\", \"VERSION\": \"1.2.3\"}")
                    }
                    optional("description", "string") {
                        llmSeesIt("Brief description shown in approval dialog — for write actions: rerun_failed_jobs, trigger_stage")
                        humanReadable("Short description for the approval dialog.")
                        whenPresent("Shown in the agent approval gate.")
                        whenAbsent("Approval dialog shows a generic message.")
                        example("Deploy MYAPP-DEPLOY to production with VERSION=1.2.3")
                    }
                }
                rejectsParam("build_number", "trigger_stage starts a new run; it does not operate on an existing build number")
                precondition("Bamboo URL configured.")
                precondition("Variable names must be editable on the plan (check get_plan_variables first).")
                onSuccess("Returns confirmation that the build/stage was queued. LLM can poll via `bamboo_builds(build_status)` to track.")
                onFailure("variables JSON invalid", "BambooToolUtils.parseVariables returns a parse error before any network call.")
                onFailure("stage name not found", "Bamboo returns an error. Stage names are case-sensitive; verify via `bamboo_builds(get_build)` on a recent result.")
                onFailure("non-editable variable override", "Bamboo may silently ignore or reject the override. Check editability flag via `get_plan_variables`.")
                onFailure("401", "Invalid token — stop.")
                example("deploy to production with version override") {
                    param("action", "trigger_stage")
                    param("plan_key", "MYAPP-DEPLOY")
                    param("stage", "Deploy to production")
                    param("variables", "{\"VERSION\": \"1.2.3\", \"DEPLOY_ENV\": \"production\"}")
                    param("description", "Deploy MYAPP 1.2.3 to production")
                    outcome("Bamboo queues the Deploy to production stage with VERSION=1.2.3.")
                }
                example("trigger full plan with defaults") {
                    param("action", "trigger_stage")
                    param("plan_key", "MYAPP-BUILD")
                    param("description", "Trigger full MYAPP-BUILD plan")
                    outcome("Full plan triggered from stage 1 with plan-default variable values.")
                    notes("For a fresh plan run with no variable overrides, bamboo_builds(trigger_build) is equally valid.")
                }
                verdict {
                    keep("The only action that combines per-stage targeting + variable injection in one call. High value for deploy workflows.", VerdictSeverity.STRONG)
                }
            }
            action("auto_detect_plan") {
                description {
                    technical(
                        "Heuristically matches a Git remote URL to a Bamboo plan key. Two routing modes: " +
                        "(1) Legacy 1-arg: only git_remote_url supplied → fast scan of `get_plans` by URL substring. " +
                        "(2) Rich 5-tier: any of repo_root/branch_name/preferred_master supplied → multi-tier detection " +
                        "that handles multi-module repos with multiple plan candidates."
                    )
                    plain(
                        "Figures out 'which Bamboo plan is building this Git repository?' by matching the " +
                        "repo's remote URL against the plan catalog. Like looking at a Git remote and " +
                        "guessing which CI pipeline it belongs to — invaluable for multi-repo workspaces."
                    )
                }
                whenLLMUses(
                    "When the user asks 'what's the Bamboo plan for this repo?' or when the agent needs a " +
                    "plan key to pass to `trigger_stage` / `get_plan_branches` but no plan key was provided. " +
                    "Call with all available context (repo_root, branch_name, preferred_master) for best results."
                )
                params {
                    required("git_remote_url", "string") {
                        llmSeesIt("Git remote URL (e.g. git@github.com:org/repo.git or https://github.com/org/repo) — for auto_detect_plan")
                        humanReadable("The git remote URL of the repository whose plan you want to detect. Usually `origin`. Can be SSH or HTTPS format.")
                        whenPresent("The primary discriminator: plan repository URLs are scanned for a match.")
                        constraint("must be non-blank (validateNotBlank)")
                        example("git@github.com:myorg/my-service.git")
                        example("https://bitbucket.example.com/scm/myproj/my-service.git")
                    }
                    optional("repo_root", "string") {
                        llmSeesIt("Optional. Local repo root path. When provided alongside git_remote_url (or with branch_name/preferred_master), routes auto_detect_plan to the richer 5-tier detection (better for multi-module repos).")
                        humanReadable("The local directory where the git repo lives. Triggers the richer 5-tier detection algorithm.")
                        whenPresent("Routes to the 5-tier `BambooService.autoDetectPlan(repoRoot, remoteUrl, ...)` overload. More accurate for multi-module repos.")
                        whenAbsent("Uses the legacy 1-arg overload (plan-list scan) when no other 5-tier triggers are present.")
                        example("/Users/dev/projects/my-service")
                    }
                    optional("branch_name", "string") {
                        llmSeesIt("Optional. Branch to detect. Triggers the 5-tier auto_detect_plan overload when present.")
                        humanReadable("The current Git branch. When provided, the 5-tier algorithm may prefer branch plans over the master plan.")
                        whenPresent("Routes to the 5-tier overload. Enables branch-aware plan selection.")
                        whenAbsent("Branch information not used in detection.")
                        example("feature/my-feature")
                        example("main")
                    }
                    optional("preferred_master", "string") {
                        llmSeesIt("Optional. Preferred master/main branch name. Triggers the 5-tier auto_detect_plan overload when present.")
                        humanReadable("Name of the trunk/main branch in your team's convention (e.g. 'main', 'master', 'trunk').")
                        whenPresent("Routes to the 5-tier overload. Helps the algorithm disambiguate the master plan from branch plans.")
                        whenAbsent("Default trunk detection heuristic used.")
                        example("main")
                        example("master")
                        example("trunk")
                    }
                }
                precondition("Bamboo URL configured.")
                precondition("The plan must have its repository URL configured in Bamboo for URL-matching to work.")
                onSuccess("Returns the detected plan key (e.g. MYAPP-BUILD). LLM can immediately use this as plan_key in other actions.")
                onFailure("no match found", "No plan matched the remote URL. The plan may not have a repository link configured in Bamboo, or the URL format differs from what Bamboo stores. Fall back to `search_plans` by repo name.")
                onFailure("multiple matches", "Several plans match the URL. The 5-tier algorithm uses branch_name and preferred_master to break ties; the legacy path returns all matches for the LLM to disambiguate.")
                onFailure("401", "Invalid token — stop.")
                example("basic detection — SSH remote") {
                    param("action", "auto_detect_plan")
                    param("git_remote_url", "git@github.com:myorg/my-service.git")
                    outcome("Returns plan key MYAPP-BUILD if Bamboo has this repository linked to that plan.")
                }
                example("rich detection with branch context") {
                    param("action", "auto_detect_plan")
                    param("git_remote_url", "git@github.com:myorg/my-service.git")
                    param("repo_root", "/Users/dev/my-service")
                    param("branch_name", "feature/payment-refactor")
                    param("preferred_master", "main")
                    outcome("5-tier detection runs: may return a branch plan key (MYAPP-BUILD-PAYMENT) or master plan key (MYAPP-BUILD) depending on what exists.")
                    notes("Always pass repo_root, branch_name, preferred_master when available — the 5-tier algorithm is substantially more accurate for multi-module repos.")
                }
                verdict {
                    keep(
                        "High value: the LLM has no other practical way to map a Git remote to a Bamboo plan key. " +
                        "Without this, it would have to call get_plans, return hundreds of entries, and ask the user to identify theirs.",
                        VerdictSeverity.STRONG
                    )
                }
            }
        }
        verdict {
            keep(
                "bamboo_plans covers the configuration/discovery half of the Bamboo surface area that bamboo_builds " +
                "deliberately omits. The split is correct: plans = 'what can be built and how', builds = 'what happened'. " +
                "auto_detect_plan and trigger_stage(variables=...) are the two highest-value actions with no " +
                "practical curl alternative. The tool is correctly scoped for a TOOLER/ORCHESTRATOR worker.",
                VerdictSeverity.NORMAL
            )
            drop(
                "10 actions is on the high side. get_projects is low-value standalone; get_build_variables " +
                "arguably belongs in bamboo_builds (it operates on a build result key); get_plans is only " +
                "used by the auto_detect_plan legacy path. If usage tracking confirms these three actions are " +
                "rarely called directly, they could be removed or folded without meaningfully reducing capability.",
                VerdictSeverity.WEAK
            )
        }
        downside(
            "get_plans returns the full plan catalog on large Bamboo instances — potentially hundreds of entries. " +
            "The response is spilled to disk but the token cost of the preview is non-trivial. `search_plans` " +
            "is almost always preferable when any plan name fragment is known."
        )
        downside(
            "auto_detect_plan relies on Bamboo having repository URLs configured on plans. Many Bamboo plans " +
            "in practice have this field empty or use a different URL format (SSH vs HTTPS) from what `git remote -v` returns. " +
            "Detection can silently fail even when a plan clearly exists for the repo."
        )
        downside(
            "trigger_stage and rerun_failed_jobs are write actions that go through the approval gate. " +
            "On large CI pipelines, the agent has no way to estimate job duration before triggering. " +
            "The LLM should communicate expected duration to the user before requesting approval."
        )
        downside(
            "The variables param on trigger_stage is a raw JSON string, not a typed object. " +
            "Malformed JSON is caught by BambooToolUtils.parseVariables before the network call, " +
            "but the LLM must still correctly escape the JSON string inside the tool call — a common mistake."
        )
        related("bamboo_builds", Relationship.COMPLEMENT, "bamboo_builds covers build results, logs, test outcomes, and artifact retrieval — the execution side of the same CI system. Always use bamboo_builds to track a build you triggered via bamboo_plans.")
        related("jira", Relationship.SEE_ALSO, "Bamboo builds can be linked to Jira tickets via the Atlassian integration; jira(get_ticket, include_dev_status=true) surfaces build status without requiring a separate bamboo_plans call.")
        mergeOpportunity(
            "MERGE CANDIDATE: bamboo_plans + bamboo_builds into a single `bamboo` tool. " +
            "Counter-argument: the two tools have 21 combined actions — that's a single tool with " +
            "a very long action enum. The split reduces schema token cost per session because the " +
            "LLM typically needs one side or the other (discovery vs results). Keep split unless " +
            "action-level usage data shows frequent alternation between the two within a single task."
        )
        observation(
            "Action count discrepancy: source has 10 actions (get_projects, get_plans, get_project_plans, " +
            "search_plans, get_plan_branches, get_build_variables, get_plan_variables, rerun_failed_jobs, " +
            "trigger_stage, auto_detect_plan). CLAUDE.md agent module table says '8'. The source is authoritative. " +
            "CLAUDE.md should be updated to reflect 10 actions."
        )
        observation(
            "get_build_variables operates on a result_key (3-segment, e.g. PROJ-PLAN-123) — it belongs " +
            "semantically to bamboo_builds (which also operates on build results). Its presence in " +
            "bamboo_plans is a mild coupling violation but not worth a breaking change given the low call frequency."
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

        val service = ServiceLookup.bamboo(project)
            ?: return ServiceLookup.notConfigured("Bamboo")

        return executeWithService(params, service)
    }

    /**
     * Core execution logic — separated for testability so tests can inject a mock [BambooService]
     * without requiring IntelliJ's service infrastructure.
     */
    internal suspend fun executeWithService(
        params: JsonObject,
        service: com.workflow.orchestrator.core.services.BambooService
    ): ToolResult {
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'action' parameter required",
                "Error: missing action",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        return when (action) {
            "get_projects" -> {
                service.getProjects().toAgentToolResult()
            }

            "get_plans" -> {
                service.getPlans().toAgentToolResult()
            }

            "get_project_plans" -> {
                val projectKey = params["project_key"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("project_key")
                ToolValidation.validateNotBlank(projectKey, "project_key")?.let { return it }
                service.getProjectPlans(projectKey).toAgentToolResult()
            }

            "search_plans" -> {
                val query = params["query"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("query")
                ToolValidation.validateNotBlank(query, "query")?.let { return it }
                service.searchPlans(query).toAgentToolResult()
            }

            "get_plan_branches" -> {
                val planKey = params["plan_key"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("plan_key")
                ToolValidation.validateBambooPlanKey(planKey)?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getPlanBranches(planKey, repoName = repoName).toAgentToolResult()
            }

            "get_build_variables" -> {
                val resultKey = params["result_key"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("result_key")
                ToolValidation.validateBambooBuildKey(resultKey)?.let { return it }
                service.getBuildVariables(resultKey).toAgentToolResult()
            }

            "get_plan_variables" -> {
                val planKey = params["plan_key"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("plan_key")
                ToolValidation.validateBambooPlanKey(planKey)?.let { return it }
                service.getPlanVariables(planKey).toAgentToolResult()
            }

            "rerun_failed_jobs" -> {
                val planKey = params["plan_key"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("plan_key")
                val buildNumberStr = params["build_number"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("build_number")
                val buildNumber = buildNumberStr.toIntOrNull()
                    ?: return ToolResult(
                        "Error: 'build_number' must be an integer, got '$buildNumberStr'",
                        "Error: invalid build_number",
                        ToolResult.ERROR_TOKEN_ESTIMATE,
                        isError = true
                    )
                ToolValidation.validateBambooPlanKey(planKey)?.let { return it }
                service.rerunFailedJobs(planKey, buildNumber).toAgentToolResult()
            }

            "trigger_stage" -> {
                val planKey = params["plan_key"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("plan_key")
                val stage = params["stage"]?.jsonPrimitive?.content
                ToolValidation.validateBambooPlanKey(planKey)?.let { return it }
                val variables = when (val parsed = BambooToolUtils.parseVariables(params["variables"]?.jsonPrimitive?.content)) {
                    is BambooToolUtils.VariablesParseResult.Success -> parsed.variables
                    is BambooToolUtils.VariablesParseResult.Failure -> return parsed.error
                }
                service.triggerStage(planKey, variables, stage).toAgentToolResult()
            }

            "auto_detect_plan" -> {
                val gitRemoteUrl = params["git_remote_url"]?.jsonPrimitive?.content
                    ?: return ToolValidation.missingParam("git_remote_url")
                ToolValidation.validateNotBlank(gitRemoteUrl, "git_remote_url")?.let { return it }

                val repoRoot = params["repo_root"]?.jsonPrimitive?.content
                val branchName = params["branch_name"]?.jsonPrimitive?.content
                val preferredMaster = params["preferred_master"]?.jsonPrimitive?.content

                if (repoRoot == null && branchName == null && preferredMaster == null) {
                    // Legacy 1-arg path — no 5-tier hints supplied
                    service.autoDetectPlan(gitRemoteUrl).toAgentToolResult()
                } else {
                    // 5-tier overload — richer detection for multi-module repos
                    service.autoDetectPlan(
                        repoRoot = repoRoot?.let { Paths.get(it) },
                        remoteUrl = gitRemoteUrl,
                        branchName = branchName,
                        preferredMaster = preferredMaster
                    ).toAgentToolResult()
                }
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

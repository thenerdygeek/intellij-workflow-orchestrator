package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
import com.workflow.orchestrator.core.model.bamboo.BuildChangeData
import com.workflow.orchestrator.core.workflow.ChainKeyResolver
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Bamboo build lifecycle tool — trigger, monitor, stop, and inspect builds.
 *
 * Split from the consolidated BambooTool. Covers 11 build-oriented actions:
 * build_status, get_build, trigger_build, get_build_log, get_test_results,
 * stop_build, cancel_build, get_artifacts, download_artifact, recent_builds,
 * get_running_builds.
 */
class BambooBuildsTool : AgentTool {

    override val name = "bamboo_builds"

    override val description = """
REMOTE CI ONLY: Bamboo build lifecycle — trigger, monitor, stop, inspect builds and test results.

Use for: 'show me the latest Bamboo build', 'why did CI fail', 'fetch artifact from build N', 'rerun failed CI jobs'.
Do NOT use for: local IDE Maven/Gradle reload errors, 'why did my IDE build fail', or anything in the IDE's Build tool window — use get_build_problems for those.

Actions and their parameters:
- build_status(plan_key, branch?, repo_name?) → Latest build status for plan
- get_build(build_key, include_commits?) → Detailed build info. Returns BuildResultData with stages[].jobs[].resultKey usable as the build_key parameter for get_build_log/get_test_results. Pass include_commits=true to also fetch the per-build commit list (SHA, message, author) and complete the Bamboo→Bitbucket→Jira triangle.
- trigger_build(plan_key, variables?, stages?) → Trigger new build (variables: JSON {"key":"value"}; stages: optional array of stage names to run, omit to run all)
- get_build_log(build_key) → Build log output. Accepts a build key (e.g. PROJ-PLAN138-4) for the whole-build log, OR a job-level resultKey from get_build's stages[].jobs[].resultKey (e.g. PROJ-PLAN138-UNIT-4) for just that job's log. Prefer per-job logs when triaging a single failing job.
- get_test_results(build_key) → Test results for build
- stop_build(build_key) → Stop running build
- cancel_build(build_key) → Cancel queued build
- get_artifacts(build_key) → List build artifacts
- download_artifact(artifact_url, target_path?) → Download build artifact to local file
- recent_builds(plan_key, branch?, repo_name?, max_results?) → Recent builds (default 10)
- get_running_builds(plan_key, repo_name?) → Currently running builds

build_key: Bamboo build result key (e.g. PROJ-PLAN-123) — used across all actions that operate on a single build.
description optional: for approval dialog on trigger/stop/cancel.
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf(
                    "build_status", "get_build", "trigger_build", "get_build_log", "get_test_results",
                    "stop_build", "cancel_build", "get_artifacts", "download_artifact", "recent_builds",
                    "get_running_builds"
                )
            ),
            "plan_key" to ParameterProperty(
                type = "string",
                description = "Bamboo plan key e.g. PROJ-PLAN — for build_status, trigger_build, recent_builds, get_running_builds"
            ),
            "build_key" to ParameterProperty(
                type = "string",
                description = "Bamboo build result key e.g. PROJ-PLAN-123 — used across all actions that operate on a single build"
            ),
            "branch" to ParameterProperty(
                type = "string",
                description = "Optional branch name — for build_status, recent_builds. Use project_context tool to discover current branch."
            ),
            "repo_name" to ParameterProperty(
                type = "string",
                description = "Repository name for multi-repo projects — for build_status, recent_builds, get_running_builds"
            ),
            "variables" to ParameterProperty(
                type = "string",
                description = "JSON object of build variables e.g. '{\"key\":\"value\"}' — for trigger_build"
            ),
            "stages" to ParameterProperty(
                type = "array",
                description = "Optional list of stage names to run — for trigger_build. Omit to run all stages (Bamboo default). " +
                    "When provided, Bamboo runs from the first stage in the list forward (REST API limitation: only the first stage is passed). " +
                    "Example: [\"Build\", \"Unit Tests\"] triggers from 'Build' stage onward. " +
                    "Rejected with an error if the list is empty.",
                items = ParameterProperty(type = "string", description = "Stage name")
            ),
            "artifact_url" to ParameterProperty(
                type = "string",
                description = "Artifact download URL (from get_artifacts output) — for download_artifact"
            ),
            "target_path" to ParameterProperty(
                type = "string",
                description = "Optional local path to save artifact — for download_artifact (defaults to temp file)"
            ),
            "max_results" to ParameterProperty(
                type = "string",
                description = "Max results to return (default 10) — for recent_builds"
            ),
            "description" to ParameterProperty(
                type = "string",
                description = "Brief description shown in approval dialog — for write actions: trigger_build, stop_build, cancel_build"
            ),
            "include_commits" to ParameterProperty(
                type = "boolean",
                description = "If true, also fetch the per-build commit list (SHA, message, author) via Bamboo's expand=changes.change. Default false to keep token cost low."
            )
        ),
        required = listOf("action")
    )

    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ORCHESTRATOR)

    @Suppress("LongMethod")
    override fun documentation(): ToolDocumentation = toolDoc("bamboo_builds") {
        summary {
            technical(
                "Single-tool dispatcher for 11 Bamboo CI build-lifecycle operations: status checks (build_status, recent_builds, " +
                "get_running_builds), detailed build inspection (get_build with optional commit fan-out), log retrieval (get_build_log " +
                "at plan or job granularity), test-results fetch, write actions (trigger_build with variable injection, stop_build, " +
                "cancel_build), artifact listing and download. Bearer-auth via PasswordSafe; conditionally registered when " +
                "ConnectionSettings.bambooUrl is non-blank. trigger_build requires X-Atlassian-Token: no-check header on Bamboo DC."
            )
            plain(
                "The agent's Bamboo remote control — like having the Bamboo dashboard in chat. One tool covers the whole build " +
                "lifecycle: check whether CI is green, kick off a new build with custom variables, fetch the raw log when it fails, " +
                "pull test results, stop a runaway build, and download the build artifact. Credentials stay in the OS keychain, " +
                "never in the LLM's working memory or shell history."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.NETWORK)
        counterfactual(
            "Without `bamboo_builds`, the LLM falls back to `run_command curl -H 'Authorization: Bearer <token>' " +
            "https://bamboo/rest/api/latest/result/PROJ-PLAN ...`. That regresses three things at once: " +
            "(1) the Bearer token has to be embedded in the command line or exported as an env var — either path " +
            "leaks it into shell history and the agent command log; " +
            "(2) trigger_build on Bamboo DC requires `X-Atlassian-Token: no-check` and a form-encoded body — " +
            "without the tool the LLM almost always omits these, causing a 403 XSRF rejection; " +
            "(3) branch-plan resolution (ChainKeyResolver) disappears — the LLM must know the Bamboo branch-chain " +
            "key up front instead of deriving it from the plan key + branch name."
        )
        llmMistake(
            "Calls trigger_build without realising Bamboo DC requires X-Atlassian-Token: no-check and a " +
            "form-encoded body. This is handled transparently by the tool, but the LLM sometimes falls back " +
            "to run_command curl for 'more control' and hits a 403 XSRF rejection."
        )
        llmMistake(
            "Confuses build_status (latest status for a plan) with get_build (detailed data for a specific build " +
            "result key). build_status is 'is CI green?' — get_build is 'what happened in build PROJ-PLAN-138?'."
        )
        llmMistake(
            "Calls get_build_log with a plan-level key (e.g. PROJ-PLAN-138) when the user wants only the failing " +
            "job's log. Prefer the job-level resultKey from get_build's stages[].jobs[].resultKey (e.g. " +
            "PROJ-PLAN-UNIT-138) to avoid a 29KB log dump when 3KB would do."
        )
        llmMistake(
            "Passes a branch name directly as plan_key (e.g. 'feature/my-branch') instead of using the branch " +
            "parameter. ChainKeyResolver resolves the plan→branch chain key automatically when branch is provided."
        )
        llmMistake(
            "Calls stop_build and cancel_build interchangeably. stop_build kills a running build; cancel_build " +
            "removes a queued-but-not-started build. Using stop on a queued build (or cancel on a running one) " +
            "produces a 400 or a no-op."
        )
        llmMistake(
            "Treats 401 as retryable. 401 means the PasswordSafe token is wrong or expired; retrying just gets " +
            "another 401. The right move is to surface the message to the user and stop."
        )
        llmMistake(
            "Calls get_artifacts before the build has finished. Bamboo only serves artifact listings for " +
            "completed builds; an in-progress build returns an empty artifact list, not an error."
        )
        downside(
            "Branch-plan resolution via ChainKeyResolver requires the branch to have been built at least once in " +
            "Bamboo — if the branch is brand-new, the chain key won't exist and build_status / recent_builds will " +
            "return 'No chain key for …' even though the plan exists."
        )
        downside(
            "get_build_log returns raw Bamboo log output which can be very large (29KB+ for multi-job plans). " +
            "Prefer the per-job resultKey path when triaging a single failing job."
        )
        downside(
            "download_artifact writes to a temp file by default — the path is lost across sessions if the agent " +
            "doesn't record it. The LLM should always log the returned path or set target_path explicitly."
        )
        downside(
            "trigger_build variables accept only string values ('key':'value' JSON). Numeric or boolean Bamboo " +
            "variables must be stringified by the caller."
        )
        related("bamboo_plans", Relationship.COMPLEMENT, "Use bamboo_plans to discover plan keys and job structure before calling bamboo_builds actions.")
        related("jira", Relationship.COMPLEMENT, "Link a build result to a Jira ticket: get the commit SHAs from get_build(include_commits=true) then call jira(get_dev_branches) to complete the Bamboo→Bitbucket→Jira triangle.")
        related("bitbucket_pr", Relationship.COMPLEMENT, "Show CI build status on a PR: get_build_log result can be annotated on the PR via bitbucket_pr(add_pr_comment).")
        flowchart(
            """
            flowchart TD
                A[LLM needs Bamboo CI info] --> B{What kind?}
                B -- is CI green? --> C[build_status plan_key=PROJ-PLAN]
                C --> D{Green?}
                D -- no --> E[get_build build_key=PROJ-PLAN-N]
                E --> F{Logs or test failures?}
                F -- logs --> G[get_build_log build_key or job resultKey]
                F -- tests --> H[get_test_results build_key]
                B -- history --> I[recent_builds plan_key max_results?]
                B -- running? --> J[get_running_builds plan_key]
                B -- trigger --> K[trigger_build plan_key variables?]
                B -- stop --> L{Running or queued?}
                L -- running --> M[stop_build build_key]
                L -- queued --> N[cancel_build build_key]
                B -- artifacts --> O[get_artifacts build_key]
                O --> P[download_artifact artifact_url target_path?]
            """
        )
        verdict {
            keep(
                "Covers the entire Bamboo build lifecycle in one tool. Without it the LLM cannot safely trigger " +
                "builds (X-Atlassian-Token contract) or resolve branch-plan keys. The credential-isolation and " +
                "XSRF-header handling alone justify keeping it over a raw curl fallback.",
                VerdictSeverity.STRONG
            )
        }
        actions {
            action("build_status") {
                description {
                    technical(
                        "Fetches the latest build result for a plan (or branch chain). Calls ChainKeyResolver to map " +
                        "plan_key + optional branch to the Bamboo chain key, then calls service.getLatestBuild(chainKey)."
                    )
                    plain(
                        "Asks 'is CI green for this plan?' — like glancing at the build status badge on the project page. " +
                        "Optionally scoped to a branch if you only care about feature-branch builds."
                    )
                }
                whenLLMUses(
                    "When the user asks 'is the build passing?', 'did CI break?', or 'what's the current build status for PROJ-PLAN?'. " +
                    "This is the cheapest way to get a pass/fail answer — use it before get_build when only the status matters."
                )
                params {
                    required("plan_key", "string") {
                        llmSeesIt("Bamboo plan key e.g. PROJ-PLAN — for build_status, trigger_build, recent_builds, get_running_builds")
                        humanReadable("The Bamboo plan identifier — project prefix, dash, plan shortkey (e.g. MYPROJECT-BACKEND).")
                        whenPresent("Validated by ToolValidation.validateBambooPlanKey before the request.")
                        constraint("must match Bamboo plan key format: uppercase letters/digits/hyphens, at least one dash")
                        example("PROJ-PLAN")
                        example("MYAPP-BACKEND")
                    }
                    optional("branch", "string") {
                        llmSeesIt("Optional branch name — for build_status, recent_builds. Use project_context tool to discover current branch.")
                        humanReadable("Scope the status check to a specific branch. If omitted, returns the default (trunk) plan's latest build.")
                        whenPresent("ChainKeyResolver maps plan_key + branch to the Bamboo branch-chain key. Fails with an error if the branch has never been built in Bamboo.")
                        whenAbsent("The default plan chain is used (trunk builds).")
                        example("feature/PROJ-1234-my-feature")
                        example("main")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects — for build_status, recent_builds, get_running_builds")
                        humanReadable("Disambiguates the repo when the project has multiple repositories.")
                        whenPresent("Passed to the service for repo-scoped lookup.")
                        whenAbsent("Primary configured repository is used.")
                        example("payment-service")
                    }
                }
                precondition("plan_key must exist in the configured Bamboo server.")
                precondition("If branch is supplied, it must have been built at least once in Bamboo (chain key must exist).")
                onSuccess("Returns a summary line with state (Successful / Failed / In Progress), build number, start time, duration, and key.")
                onFailure("401 Unauthorized", "PasswordSafe token is wrong or expired. Stop and ask the user to update the token in Settings.")
                onFailure("404 Not Found", "plan_key does not exist in Bamboo. Re-confirm the key with the user.")
                onFailure("No chain key for branch", "Branch has never been built in this plan. The user must trigger at least one build on that branch first.")
                example("check if trunk CI is green") {
                    param("action", "build_status")
                    param("plan_key", "PROJ-BACKEND")
                    outcome("Returns latest build status — e.g. 'Successful (Build #142, 4m 12s)'.")
                }
                example("check feature branch build") {
                    param("action", "build_status")
                    param("plan_key", "PROJ-BACKEND")
                    param("branch", "feature/PROJ-999-auth-refactor")
                    outcome("Returns latest build status for the branch chain, not the trunk.")
                }
                verdict {
                    keep("The cheapest CI health check — one API call, minimal tokens. High usage frequency.", VerdictSeverity.STRONG)
                }
            }

            action("get_build") {
                description {
                    technical(
                        "Fetches detailed build result data for a specific build key: stages, jobs with resultKeys, " +
                        "test counts, duration, and optionally the commit list (parallel async via getBuildChanges). " +
                        "Returns BuildResultData including stages[].jobs[].resultKey usable for targeted log/test calls."
                    )
                    plain(
                        "Opens a specific build and reads all its details — like clicking into a finished CI run and " +
                        "expanding all stages and jobs. Optionally also shows which commits triggered this build."
                    )
                }
                whenLLMUses(
                    "When the user says 'what failed in build 142?' or 'show me the stages for PROJ-BACKEND-142'. " +
                    "Also the correct first step before get_build_log — it provides the per-job resultKeys that let " +
                    "the LLM fetch only the failing job's log instead of the entire plan log."
                )
                params {
                    required("build_key", "string") {
                        llmSeesIt("Bamboo build result key e.g. PROJ-PLAN-123 — used across all actions that operate on a single build")
                        humanReadable("The specific build run identifier — plan key plus build number (e.g. PROJ-BACKEND-142).")
                        whenPresent("Validated by ToolValidation.validateBambooBuildKey.")
                        constraint("format: PROJ-PLAN-<number>, e.g. PROJ-BACKEND-142")
                        example("PROJ-BACKEND-142")
                        example("MYAPP-UNIT-23")
                    }
                    optional("include_commits", "boolean") {
                        llmSeesIt("If true, also fetch the per-build commit list (SHA, message, author) via Bamboo's expand=changes.change. Default false to keep token cost low.")
                        humanReadable("Also loads the list of commits that went into this build (SHA, message, author). Useful for tracing which commit broke CI.")
                        whenPresent("Parallel async call to service.getBuildChanges(buildKey). Result is appended as 'Commits (showing N of M)' block. Capped at 50 commits displayed.")
                        whenAbsent("Commit list is not fetched — saves one API call and reduces token usage.")
                        example("true")
                    }
                }
                precondition("build_key must reference an existing build result in Bamboo.")
                onSuccess("Returns build header (state, number, key, duration, start time) plus stage/job breakdown with per-job resultKeys and test counts. With include_commits=true, appends a commit list block.")
                onFailure("401 Unauthorized", "Token wrong or expired. Stop and ask the user to update the token in Settings.")
                onFailure("403 Forbidden", "User lacks BUILD_READ permission on this plan.")
                onFailure("404 Not Found", "build_key doesn't exist. Often a typo in the build number — confirm with the user.")
                example("inspect a failed build") {
                    param("action", "get_build")
                    param("build_key", "PROJ-BACKEND-142")
                    outcome("Returns stages and jobs with resultKeys. LLM picks the failing job's resultKey for the next get_build_log call.")
                }
                example("trace which commit broke CI") {
                    param("action", "get_build")
                    param("build_key", "PROJ-BACKEND-142")
                    param("include_commits", "true")
                    outcome("Returns build details plus a commit list (SHA, author, message) — LLM can identify the offending commit.")
                }
                verdict {
                    keep("Critical pre-step for targeted log/test fetching — provides job resultKeys that keep downstream calls small.", VerdictSeverity.STRONG)
                }
            }

            action("trigger_build") {
                description {
                    technical(
                        "POSTs to Bamboo's queue-build endpoint with X-Atlassian-Token: no-check and a form-encoded body. " +
                        "Accepts an optional JSON variables map. Delegates to service.triggerBuild(planKey, variables). " +
                        "Requires BUILD permission (not BUILD_READ) on the plan."
                    )
                    plain(
                        "Presses the 'Run' button on a Bamboo plan — like clicking 'Run plan' in the Bamboo UI. " +
                        "Optionally injects custom build variables (e.g. a Docker tag or feature flag). The XSRF " +
                        "handshake and form-encoding are handled transparently."
                    )
                }
                whenLLMUses(
                    "When the user says 'trigger a build', 'rerun CI', 'kick off PROJ-BACKEND with tag=1.2.3'. " +
                    "Also used at end of a hotfix workflow to verify the fix before deployment."
                )
                params {
                    required("plan_key", "string") {
                        llmSeesIt("Bamboo plan key e.g. PROJ-PLAN — for build_status, trigger_build, recent_builds, get_running_builds")
                        humanReadable("Which Bamboo plan to trigger. Must be the plan key, not a build key.")
                        whenPresent("Validated by ToolValidation.validateBambooPlanKey.")
                        constraint("must be a valid plan key, not a build result key")
                        example("PROJ-BACKEND")
                    }
                    optional("variables", "string") {
                        llmSeesIt("JSON object of build variables e.g. '{\"key\":\"value\"}' — for trigger_build")
                        humanReadable("Custom Bamboo build variables to inject. Passed as JSON — keys and values must both be strings.")
                        whenPresent("Parsed by BambooToolUtils.parseVariables; invalid JSON returns a parse error before the network call.")
                        whenAbsent("Build is triggered with no extra variables — plan's default variables apply.")
                        constraint("must be valid JSON object; values must be strings (not numbers or booleans)")
                        example("{\"docker.tag\": \"1.2.3\", \"run.smoke\": \"true\"}")
                    }
                    optional("stages", "array") {
                        llmSeesIt("Optional list of stage names to run — for trigger_build. Omit to run all stages (Bamboo default). When provided, Bamboo runs from the first stage in the list forward (REST API limitation: only the first stage is passed). Example: [\"Build\", \"Unit Tests\"] triggers from 'Build' stage onward. Rejected with an error if the list is empty.")
                        humanReadable("Restrict the build to specific stages. Bamboo runs from the first stage in the list forward. Empty list is rejected.")
                        whenPresent("Parsed to a Set<String>; first element passed as stage=<name> with executeAllStages=false to the Bamboo REST queue endpoint.")
                        whenAbsent("All stages run (Bamboo default, executeAllStages=true).")
                        constraint("Must be a non-empty array. Each element is a stage name string.")
                        example("[\"Build\"]")
                        example("[\"Build\", \"Unit Tests\"]")
                    }
                    optional("description", "string") {
                        llmSeesIt("Brief description shown in approval dialog — for write actions: trigger_build, stop_build, cancel_build")
                        humanReadable("One-line label shown in the agent approval gate.")
                        whenPresent("Displayed in the approval dialog.")
                        whenAbsent("Approval dialog falls back to action name.")
                        example("Trigger PROJ-BACKEND with docker.tag=1.2.3")
                    }
                }
                precondition("User must have BUILD permission on the plan (not just BUILD_READ).")
                precondition("Plan must be enabled and not already at queue capacity in Bamboo.")
                onSuccess("Returns the queued build key (e.g. PROJ-BACKEND-143) and a link to view the build in Bamboo.")
                onFailure("401 Unauthorized", "PasswordSafe token wrong or expired. Stop and ask user to update the token.")
                onFailure("403 Forbidden", "User has BUILD_READ but not BUILD permission, or the plan is disabled. Surface to user.")
                onFailure("403 from missing X-Atlassian-Token", "This is handled transparently by the service — if it surfaces it indicates a server misconfiguration, not an auth issue.")
                onFailure("variables parse error", "Invalid JSON in variables param — BambooToolUtils.parseVariables returns a ToolResult error before the network call. LLM must fix the JSON.")
                example("trigger with default variables") {
                    param("action", "trigger_build")
                    param("plan_key", "PROJ-BACKEND")
                    param("description", "Trigger backend build to verify hotfix")
                    outcome("Build PROJ-BACKEND-143 queued; Bamboo link returned.")
                }
                example("trigger with custom docker tag") {
                    param("action", "trigger_build")
                    param("plan_key", "PROJ-BACKEND")
                    param("variables", "{\"docker.tag\": \"1.2.3-hotfix\"}")
                    param("description", "Trigger backend build with hotfix tag")
                    outcome("Build queued with docker.tag=1.2.3-hotfix injected as a Bamboo variable.")
                }
                verdict {
                    keep("The only write path into Bamboo CI — trigger_build is the only way the agent can start a build without the user pressing a button. The XSRF and form-encoding handling is what makes it safe.", VerdictSeverity.STRONG)
                }
            }

            action("get_build_log") {
                description {
                    technical(
                        "Fetches Bamboo build log output for a build key. Accepts either a plan-level build key " +
                        "(e.g. PROJ-PLAN-138) for the aggregate log or a job-level resultKey from get_build's " +
                        "stages[].jobs[].resultKey (e.g. PROJ-PLAN-UNIT-138) for a single job's log."
                    )
                    plain(
                        "Downloads the CI log — like clicking 'View log' in the Bamboo UI. Can fetch the whole " +
                        "build's log or zoom in on a single failing job's log to save tokens."
                    )
                }
                whenLLMUses(
                    "When the user says 'show me why CI failed' or 'get the build log for PROJ-BACKEND-142'. " +
                    "Best practice: call get_build first to get job resultKeys, then call get_build_log with the " +
                    "failing job's resultKey instead of the plan-level key."
                )
                params {
                    required("build_key", "string") {
                        llmSeesIt("Bamboo build result key e.g. PROJ-PLAN-123 — used across all actions that operate on a single build")
                        humanReadable("Either the plan-level build key (PROJ-PLAN-138) or a job-level resultKey (PROJ-PLAN-UNIT-138) from get_build.")
                        whenPresent("Validated by ToolValidation.validateBambooBuildKey. Bamboo routes automatically based on key format.")
                        constraint("plan-level: PROJ-PLAN-<number>; job-level: PROJ-PLAN-SHORTKEY-<number>")
                        example("PROJ-BACKEND-142")
                        example("PROJ-BACKEND-UNIT-142")
                    }
                }
                precondition("Build must exist and be accessible to the current user.")
                onSuccess("Returns the log text (potentially large — 29KB+ for multi-job plans). ToolOutputSpiller may page the output if it exceeds 30K chars.")
                onFailure("401 Unauthorized", "Token wrong or expired.")
                onFailure("403 Forbidden", "User lacks BUILD_READ permission on this plan.")
                onFailure("404 Not Found", "build_key doesn't exist or the log hasn't been archived yet.")
                example("get whole-build log") {
                    param("action", "get_build_log")
                    param("build_key", "PROJ-BACKEND-142")
                    outcome("Returns full aggregated log for all jobs in build 142. May be large.")
                }
                example("get failing job log only") {
                    param("action", "get_build_log")
                    param("build_key", "PROJ-BACKEND-UNIT-142")
                    outcome("Returns log for just the UNIT job — typically 3-5x smaller than the aggregate log.")
                    notes("Get the job resultKey from get_build(build_key=PROJ-BACKEND-142).stages[].jobs[].resultKey first.")
                }
                verdict {
                    keep("Irreplaceable for CI triage — the agent cannot diagnose a build failure without log access.", VerdictSeverity.STRONG)
                }
            }

            action("get_test_results") {
                description {
                    technical(
                        "Fetches test results for a build key: pass/fail counts, failing test names, error messages. " +
                        "Delegates to service.getTestResults(buildKey)."
                    )
                    plain(
                        "Reads the test report — like opening the Tests tab in the Bamboo build view. " +
                        "Shows which tests failed and their error messages without having to parse the raw log."
                    )
                }
                whenLLMUses(
                    "When the user asks 'which tests failed?', 'show me the failing unit tests in build 142'. " +
                    "Use this instead of get_build_log when the goal is test-failure triage — results are structured " +
                    "and far smaller than the full log."
                )
                params {
                    required("build_key", "string") {
                        llmSeesIt("Bamboo build result key e.g. PROJ-PLAN-123 — used across all actions that operate on a single build")
                        humanReadable("The build whose test report to fetch.")
                        whenPresent("Validated by ToolValidation.validateBambooBuildKey.")
                        constraint("format: PROJ-PLAN-<number>")
                        example("PROJ-BACKEND-142")
                    }
                }
                precondition("Build must have completed (or at least started running tests) — results are empty for queued builds.")
                onSuccess("Returns test summary (total, passed, failed, skipped) plus a list of failing tests with class names, method names, and error messages.")
                onFailure("401 Unauthorized", "Token wrong or expired.")
                onFailure("404 Not Found", "Build doesn't exist.")
                onFailure("empty results on in-progress build", "Test results may be incomplete while the build is still running. Wait for build completion or poll via get_running_builds.")
                example("triage test failures") {
                    param("action", "get_test_results")
                    param("build_key", "PROJ-BACKEND-142")
                    outcome("Returns failing test list with class names and error messages. LLM can link failures to source files.")
                }
                verdict {
                    keep("Much more efficient than log parsing for test-failure triage — structured output, minimal tokens.", VerdictSeverity.STRONG)
                }
            }

            action("stop_build") {
                description {
                    technical(
                        "Sends a stop signal to a running build. Delegates to service.stopBuild(buildKey). " +
                        "Accepts either build_key or the legacy result_key alias — both resolve to the same param."
                    )
                    plain(
                        "Kills a running CI build — like clicking 'Stop build' in the Bamboo UI. Only works on " +
                        "builds that are currently executing, not on queued builds (use cancel_build for those)."
                    )
                }
                whenLLMUses(
                    "When the user says 'kill the running build', 'stop build 142', or when a build has hung and " +
                    "the user wants to requeue it. Confirm with the user before stopping a build that teammates " +
                    "may be watching."
                )
                params {
                    required("build_key", "string") {
                        llmSeesIt("Bamboo build result key e.g. PROJ-PLAN-123 — used across all actions that operate on a single build")
                        humanReadable("The running build to stop.")
                        whenPresent("Validated by ToolValidation.validateBambooBuildKey. The legacy 'result_key' alias also resolves here.")
                        constraint("build must currently be in Running state; stopping a queued or finished build is a no-op or error")
                        example("PROJ-BACKEND-142")
                    }
                    optional("description", "string") {
                        llmSeesIt("Brief description shown in approval dialog — for write actions: trigger_build, stop_build, cancel_build")
                        humanReadable("Approval dialog label.")
                        whenPresent("Displayed in the approval gate.")
                        whenAbsent("Approval dialog falls back to action name.")
                        example("Stop hung build PROJ-BACKEND-142")
                    }
                }
                precondition("Build must be in Running state. Queued builds should use cancel_build instead.")
                onSuccess("Returns confirmation that the stop signal was sent. The build transitions to Stopped state asynchronously.")
                onFailure("401 Unauthorized", "Token wrong or expired.")
                onFailure("403 Forbidden", "User lacks BUILD permission on the plan.")
                onFailure("build not running", "Bamboo returns an error or no-op. Use get_running_builds to verify state before stopping.")
                example("stop a hung build") {
                    param("action", "stop_build")
                    param("build_key", "PROJ-BACKEND-142")
                    param("description", "Stop hung backend build")
                    outcome("Stop signal sent; build transitions to Stopped.")
                }
                verdict {
                    keep("Necessary for the 'requeue after fixing the issue' workflow. Without it the user must manually stop the build in Bamboo.", VerdictSeverity.NORMAL)
                }
            }

            action("cancel_build") {
                description {
                    technical(
                        "Removes a queued (not yet started) build from the Bamboo queue. Delegates to service.cancelBuild(buildKey). " +
                        "Accepts either build_key or the legacy result_key alias."
                    )
                    plain(
                        "Pulls a pending build out of the queue before it starts — like clicking 'Cancel' on a " +
                        "queued build in Bamboo. Different from stop_build which kills an already-running build."
                    )
                }
                whenLLMUses(
                    "When the user wants to prevent a queued build from running — e.g. 'cancel the pending build, " +
                    "I need to push a fix first'. Use after confirming the build is actually queued via get_running_builds."
                )
                params {
                    required("build_key", "string") {
                        llmSeesIt("Bamboo build result key e.g. PROJ-PLAN-123 — used across all actions that operate on a single build")
                        humanReadable("The queued build to cancel.")
                        whenPresent("Validated by ToolValidation.validateBambooBuildKey.")
                        constraint("build must be in Queued state; use stop_build for Running builds")
                        example("PROJ-BACKEND-143")
                    }
                    optional("description", "string") {
                        llmSeesIt("Brief description shown in approval dialog — for write actions: trigger_build, stop_build, cancel_build")
                        humanReadable("Approval dialog label.")
                        whenPresent("Displayed in the approval gate.")
                        whenAbsent("Approval dialog falls back to action name.")
                        example("Cancel pending build to push hotfix")
                    }
                }
                precondition("Build must be in Queued state. Running builds should use stop_build instead.")
                onSuccess("Returns confirmation that the build was removed from the queue.")
                onFailure("401 Unauthorized", "Token wrong or expired.")
                onFailure("403 Forbidden", "User lacks BUILD permission.")
                onFailure("build not queued", "Bamboo returns an error if the build has already started running.")
                example("cancel a pending build to push a fix") {
                    param("action", "cancel_build")
                    param("build_key", "PROJ-BACKEND-143")
                    param("description", "Cancel queued build — pushing hotfix first")
                    outcome("Build removed from queue; LLM can push the fix and trigger a fresh build.")
                }
                verdict {
                    keep("Pairs with stop_build to give full queue management. Without cancel_build the user must go to the Bamboo UI to pull a queued build.", VerdictSeverity.NORMAL)
                }
            }

            action("get_artifacts") {
                description {
                    technical("Lists all artifacts produced by a completed build. Delegates to service.getArtifacts(buildKey). Returns artifact names and download URLs for use with download_artifact.")
                    plain("Shows what files a build produced — like the Artifacts tab in Bamboo. Returns names and URLs; use download_artifact to actually fetch a file.")
                }
                whenLLMUses(
                    "When the user asks 'what artifacts did build 142 produce?' or 'get the JAR from the latest build'. " +
                    "Always call this before download_artifact to discover the correct artifact URL."
                )
                params {
                    required("build_key", "string") {
                        llmSeesIt("Bamboo build result key e.g. PROJ-PLAN-123 — used across all actions that operate on a single build")
                        humanReadable("The completed build whose artifacts to list.")
                        whenPresent("Validated by ToolValidation.validateBambooBuildKey. The legacy 'result_key' alias also resolves here.")
                        constraint("build must be completed — artifacts are not available for queued or in-progress builds")
                        example("PROJ-BACKEND-142")
                    }
                }
                precondition("Build must be in a completed state (Successful, Failed, or Stopped). In-progress builds return an empty list.")
                onSuccess("Returns a list of artifact entries: name, file type, and download URL.")
                onFailure("401 Unauthorized", "Token wrong or expired.")
                onFailure("404 Not Found", "build_key doesn't exist.")
                onFailure("empty list on in-progress build", "Build hasn't produced artifacts yet. Wait for completion and retry.")
                example("list artifacts from a successful build") {
                    param("action", "get_artifacts")
                    param("build_key", "PROJ-BACKEND-142")
                    outcome("Returns artifact list with names and download URLs. LLM picks the relevant URL and calls download_artifact.")
                }
                verdict {
                    keep("Required precondition for download_artifact — there is no other way to discover artifact URLs.", VerdictSeverity.STRONG)
                }
            }

            action("download_artifact") {
                description {
                    technical(
                        "Downloads a build artifact to a local file via service.downloadArtifact(artifactUrl, targetFile). " +
                        "If target_path is omitted, creates a temp file (bamboo-artifact-*.tmp). " +
                        "Returns the absolute path and byte size of the saved file."
                    )
                    plain(
                        "Saves a CI artifact to disk — like clicking a download link in the Bamboo Artifacts tab. " +
                        "The file path is returned so you can then read or inspect the artifact."
                    )
                }
                whenLLMUses(
                    "After get_artifacts returns a download URL. Typically to fetch JARs, WARs, test reports, " +
                    "or coverage ZIPs produced by a build."
                )
                params {
                    required("artifact_url", "string") {
                        llmSeesIt("Artifact download URL (from get_artifacts output) — for download_artifact")
                        humanReadable("The URL from get_artifacts. Must be a Bamboo artifact download URL — not an arbitrary external URL.")
                        whenPresent("Passed directly to service.downloadArtifact.")
                        constraint("must be a URL from a prior get_artifacts call; arbitrary external URLs may be rejected by the Bamboo auth layer")
                        example("https://bamboo.example.com/artifact/PROJ-BACKEND/shared/build-142/backend.jar")
                    }
                    optional("target_path", "string") {
                        llmSeesIt("Optional local path to save artifact — for download_artifact (defaults to temp file)")
                        humanReadable("Where to save the file on disk. Use an absolute path so you can reliably reference it later.")
                        whenPresent("File is written to this path. Parent directory must exist.")
                        whenAbsent("java.io.File.createTempFile('bamboo-artifact-', '.tmp') is used — path is returned but lost across sessions if not recorded.")
                        constraint("parent directory must exist; relative paths are resolved from the JVM working directory")
                        example("/tmp/backend-142.jar")
                        example("/Users/me/Downloads/backend-142.jar")
                    }
                }
                precondition("artifact_url must come from a prior get_artifacts call for an accessible build.")
                onSuccess("Returns 'Artifact downloaded to: <absolutePath>\nSize: <bytes> bytes'. LLM can then use read_file or run_command on the downloaded path.")
                onFailure("401 Unauthorized", "Token wrong or expired or artifact requires additional permissions.")
                onFailure("404 Not Found", "Artifact URL is stale (build cleaned up) or invalid.")
                onFailure("parent directory missing", "If target_path parent dir doesn't exist, the file write fails. Use /tmp or omit target_path.")
                example("download a JAR artifact") {
                    param("action", "download_artifact")
                    param("artifact_url", "https://bamboo.example.com/artifact/PROJ-BACKEND/shared/build-142/backend.jar")
                    param("target_path", "/tmp/backend-142.jar")
                    outcome("File saved to /tmp/backend-142.jar (2.4 MB). LLM records the path for later inspection.")
                }
                verdict {
                    keep("Completes the artifact-fetch workflow. Without it, the LLM must fall back to curl — with the same credential-leak risk as other Bamboo curl fallbacks.", VerdictSeverity.NORMAL)
                }
            }

            action("recent_builds") {
                description {
                    technical(
                        "Returns the N most recent builds for a plan (or branch chain), defaulting to 10. " +
                        "Accepts optional branch and repo_name for scoped lookups. Uses ChainKeyResolver " +
                        "for branch→chain mapping. Delegates to service.getRecentBuilds(chainKey, maxResults)."
                    )
                    plain(
                        "Shows the build history for a plan — like the Results tab in Bamboo, showing the last N " +
                        "runs with their status, number, and duration. Useful for spotting a pattern of failures."
                    )
                }
                whenLLMUses(
                    "When the user asks 'has CI been failing recently?', 'show me the last 5 builds for PROJ-BACKEND', " +
                    "or 'when did CI last pass on feature/my-branch?'. Also useful before triggering a build to " +
                    "confirm the plan isn't already in a broken state."
                )
                params {
                    required("plan_key", "string") {
                        llmSeesIt("Bamboo plan key e.g. PROJ-PLAN — for build_status, trigger_build, recent_builds, get_running_builds")
                        humanReadable("Which plan's history to fetch.")
                        whenPresent("Validated by ToolValidation.validateBambooPlanKey.")
                        constraint("must be a valid Bamboo plan key")
                        example("PROJ-BACKEND")
                    }
                    optional("branch", "string") {
                        llmSeesIt("Optional branch name — for build_status, recent_builds. Use project_context tool to discover current branch.")
                        humanReadable("Scope history to a specific branch. Omit for trunk/default plan history.")
                        whenPresent("ChainKeyResolver maps to branch chain key. Branch must have been built at least once.")
                        whenAbsent("Trunk plan history is returned.")
                        example("feature/PROJ-999-auth-refactor")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects — for build_status, recent_builds, get_running_builds")
                        humanReadable("Disambiguates repo in multi-repo projects.")
                        whenPresent("Passed to the service for repo-scoped lookup.")
                        whenAbsent("Primary repository is used.")
                        example("payment-service")
                    }
                    optional("max_results", "string") {
                        llmSeesIt("Max results to return (default 10) — for recent_builds")
                        humanReadable("How many recent builds to return. Default is 10; increase for longer trend analysis.")
                        whenPresent("Parsed as integer; if non-numeric, defaults to 10.")
                        whenAbsent("Defaults to 10.")
                        constraint("parsed as integer; non-numeric values fall back to 10")
                        example("5")
                        example("20")
                    }
                }
                precondition("plan_key must exist in Bamboo. If branch is supplied, the branch must have been built at least once.")
                onSuccess("Returns a list of recent builds with build number, state, start time, duration, and build key.")
                onFailure("401 Unauthorized", "Token wrong or expired.")
                onFailure("404 Not Found", "plan_key not found in Bamboo.")
                onFailure("No chain key for branch", "Branch has never been built in this plan.")
                example("check build trend for trunk") {
                    param("action", "recent_builds")
                    param("plan_key", "PROJ-BACKEND")
                    param("max_results", "5")
                    outcome("Returns last 5 builds with status. LLM can spot intermittent failures or a broken CI trend.")
                }
                verdict {
                    keep("Efficient trend analysis without per-build network calls. Used whenever the user wants build history.", VerdictSeverity.NORMAL)
                }
            }

            action("get_running_builds") {
                description {
                    technical(
                        "Returns currently executing builds for a plan. Optional repo_name for multi-repo disambiguation. " +
                        "Delegates to service.getRunningBuilds(planKey, repoName). Useful for confirming state before " +
                        "calling stop_build or cancel_build."
                    )
                    plain(
                        "Shows what's currently running on a Bamboo plan — like the 'currently building' indicator " +
                        "in the Bamboo dashboard. Use this to check whether a build is active before trying to stop it."
                    )
                }
                whenLLMUses(
                    "When the user says 'is a build running right now?' or before calling stop_build / cancel_build " +
                    "to confirm the build is in the expected state. Also useful after trigger_build to confirm the " +
                    "new build started."
                )
                params {
                    required("plan_key", "string") {
                        llmSeesIt("Bamboo plan key e.g. PROJ-PLAN — for build_status, trigger_build, recent_builds, get_running_builds")
                        humanReadable("Which plan to check for active builds.")
                        whenPresent("Validated by ToolValidation.validateBambooPlanKey.")
                        constraint("must be a valid Bamboo plan key")
                        example("PROJ-BACKEND")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects — for build_status, recent_builds, get_running_builds")
                        humanReadable("Disambiguates repo in multi-repo projects.")
                        whenPresent("Passed to the service for repo-scoped lookup.")
                        whenAbsent("Primary repository is used.")
                        example("payment-service")
                    }
                }
                precondition("plan_key must exist in Bamboo.")
                onSuccess("Returns a list of in-progress builds (build key, state, start time, elapsed). Empty list if nothing is running.")
                onFailure("401 Unauthorized", "Token wrong or expired.")
                onFailure("404 Not Found", "plan_key not found.")
                example("confirm a build is running before stopping") {
                    param("action", "get_running_builds")
                    param("plan_key", "PROJ-BACKEND")
                    outcome("Returns in-progress build list. LLM picks the correct build_key and calls stop_build.")
                }
                verdict {
                    keep("Necessary pre-flight for stop_build and cancel_build — prevents 400/no-op errors from operating on builds in the wrong state.", VerdictSeverity.NORMAL)
                }
            }
        }
        observation("stop_build and cancel_build both accept the legacy 'result_key' alias alongside 'build_key'. The alias is undocumented in the LLM description and could be removed to simplify the schema.")
        observation("get_artifacts and download_artifact form a mandatory two-step pattern — consider whether they could be combined into a single action that lists and optionally downloads in one call.")
        mergeOpportunity("build_status and recent_builds overlap for the 'is CI green?' query — build_status is the fast path; recent_builds adds history. The distinction is clear but may be confusing to the LLM; a single action with a history_count=0 default could unify them.")
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

        // Validate required parameters before service lookup so callers get a
        // helpful error even when Bamboo is not yet configured.
        if (action == "recent_builds" && params["plan_key"]?.jsonPrimitive?.content == null) {
            return ToolResult.error(
                "'plan_key' parameter required. Call project_context(action=get_project) to find the " +
                "value under 'Bamboo Plan Key', or configure it in Settings > Workflow Orchestrator > " +
                "Connections > Bamboo."
            )
        }
        if (action == "get_running_builds" && params["plan_key"]?.jsonPrimitive?.content == null) {
            return ToolResult.error(
                "'plan_key' parameter required. Call project_context(action=get_project) to find the " +
                "value under 'Bamboo Plan Key', or configure it in Settings > Workflow Orchestrator > " +
                "Connections > Bamboo."
            )
        }

        val service = ServiceLookup.bamboo(project)
            ?: return ServiceLookup.notConfigured("Bamboo")

        return when (action) {
            "build_status" -> {
                val planKey = params["plan_key"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("plan_key")
                ToolValidation.validateBambooPlanKey(planKey)?.let { return it }
                val branch = params["branch"]?.jsonPrimitive?.content
                val chainKey = if (branch != null) {
                    ChainKeyResolver.getInstance()?.resolveChainKey(project, planKey, branch)
                        ?: return ToolResult(
                            content = "No Bamboo branch chain for '$branch' in plan $planKey. " +
                                "Verify the branch has been built in Bamboo at least once.",
                            summary = "No chain key for $planKey/$branch",
                            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                            isError = true
                        )
                } else {
                    planKey
                }
                executeBuildStatusForTest(chainKey, service)
            }

            "get_build" -> {
                val buildKey = params["build_key"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("build_key")
                ToolValidation.validateBambooBuildKey(buildKey)?.let { return it }
                val includeCommits = params["include_commits"]?.jsonPrimitive?.content?.lowercase() == "true"
                executeGetBuildForTest(buildKey, includeCommits, service)
            }

            "trigger_build" -> {
                val planKey = params["plan_key"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("plan_key")
                ToolValidation.validateBambooPlanKey(planKey)?.let { return it }
                val variables = when (val parsed = BambooToolUtils.parseVariables(params["variables"]?.jsonPrimitive?.content)) {
                    is BambooToolUtils.VariablesParseResult.Success -> parsed.variables
                    is BambooToolUtils.VariablesParseResult.Failure -> return parsed.error
                }
                val stages: Set<String>? = params["stages"]?.let { el ->
                    try {
                        val arr = el.jsonArray
                        if (arr.isEmpty()) return ToolResult(
                            content = "Error: 'stages' list is empty. Provide at least one stage name or omit the parameter to run all stages.",
                            summary = "Empty stages list",
                            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                            isError = true
                        )
                        arr.map { it.jsonPrimitive.content }.toSet()
                    } catch (_: Exception) { null }
                }
                service.triggerBuild(planKey, variables, stages).toAgentToolResult()
            }

            "get_build_log" -> {
                val buildKey = params["build_key"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("build_key")
                ToolValidation.validateBambooBuildKey(buildKey)?.let { return it }
                service.getBuildLog(buildKey).toAgentToolResult()
            }

            "get_test_results" -> {
                val buildKey = params["build_key"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("build_key")
                ToolValidation.validateBambooBuildKey(buildKey)?.let { return it }
                service.getTestResults(buildKey).toAgentToolResult()
            }

            "stop_build" -> {
                val buildKey = params["build_key"]?.jsonPrimitive?.content
                    ?: params["result_key"]?.jsonPrimitive?.content
                    ?: return ToolValidation.missingParam("build_key")
                ToolValidation.validateBambooBuildKey(buildKey)?.let { return it }
                service.stopBuild(buildKey).toAgentToolResult()
            }

            "cancel_build" -> {
                val buildKey = params["build_key"]?.jsonPrimitive?.content
                    ?: params["result_key"]?.jsonPrimitive?.content
                    ?: return ToolValidation.missingParam("build_key")
                ToolValidation.validateBambooBuildKey(buildKey)?.let { return it }
                service.cancelBuild(buildKey).toAgentToolResult()
            }

            "get_artifacts" -> {
                val buildKey = params["build_key"]?.jsonPrimitive?.content
                    ?: params["result_key"]?.jsonPrimitive?.content
                    ?: return ToolValidation.missingParam("build_key")
                ToolValidation.validateBambooBuildKey(buildKey)?.let { return it }
                service.getArtifacts(buildKey).toAgentToolResult()
            }

            "download_artifact" -> {
                val artifactUrl = params["artifact_url"]?.jsonPrimitive?.content
                    ?: return ToolValidation.missingParam("artifact_url")
                val targetPath = params["target_path"]?.jsonPrimitive?.content
                val targetFile = if (targetPath != null) {
                    java.io.File(targetPath)
                } else {
                    java.io.File.createTempFile("bamboo-artifact-", ".tmp")
                }
                val result = service.downloadArtifact(artifactUrl, targetFile)
                if (result.isError) {
                    result.toAgentToolResult()
                } else {
                    ToolResult(
                        content = "Artifact downloaded to: ${targetFile.absolutePath}\nSize: ${targetFile.length()} bytes",
                        summary = "Downloaded artifact to ${targetFile.name}",
                        tokenEstimate = TokenEstimator.estimate("Downloaded artifact to ${targetFile.absolutePath}"),
                        isError = false
                    )
                }
            }

            "recent_builds" -> {
                val planKey = params["plan_key"]?.jsonPrimitive?.content
                    ?: return ToolResult.error(
                        "'plan_key' parameter required. Call project_context(action=get_project) to find the " +
                        "value under 'Bamboo Plan Key', or configure it in Settings > Workflow Orchestrator > " +
                        "Connections > Bamboo."
                    )
                val maxResults = params["max_results"]?.jsonPrimitive?.content?.toIntOrNull() ?: 10
                ToolValidation.validateBambooPlanKey(planKey)?.let { return it }
                val branch = params["branch"]?.jsonPrimitive?.content
                val chainKey = if (branch != null) {
                    ChainKeyResolver.getInstance()?.resolveChainKey(project, planKey, branch)
                        ?: return ToolResult(
                            content = "No Bamboo branch chain for '$branch' in plan $planKey. " +
                                "Verify the branch has been built in Bamboo at least once.",
                            summary = "No chain key for $planKey/$branch",
                            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                            isError = true
                        )
                } else {
                    planKey
                }
                service.getRecentBuilds(chainKey, maxResults).toAgentToolResult()
            }

            "get_running_builds" -> {
                val planKey = params["plan_key"]?.jsonPrimitive?.content
                    ?: return ToolResult.error(
                        "'plan_key' parameter required. Call project_context(action=get_project) to find the " +
                        "value under 'Bamboo Plan Key', or configure it in Settings > Workflow Orchestrator > " +
                        "Connections > Bamboo."
                    )
                ToolValidation.validateBambooPlanKey(planKey)?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getRunningBuilds(planKey, repoName = repoName).toAgentToolResult()
            }

            else -> ToolResult(
                content = "Unknown action '$action'. See tool description for valid actions.",
                summary = "Unknown action '$action'",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    /**
     * Composes `build_status` output. Bamboo's `/result/{key}/latest` endpoint
     * (behind [BambooService.getLatestBuild]) only returns the most recent
     * *finished* build — a build that is currently in progress or queued is
     * invisible to it. That made the agent report a stale FAILED/SUCCESSFUL state
     * while a newer build was actually running. To give an honest "current CI
     * state" we additionally fetch in-progress/queued builds (which use
     * `includeAllStates=true`) and surface them above the latest finished result.
     *
     * Extracted as an `internal` seam so it can be unit-tested with a mock
     * [BambooService] without IntelliJ infrastructure (mirrors
     * [executeGetBuildForTest]).
     */
    internal suspend fun executeBuildStatusForTest(
        chainKey: String,
        service: com.workflow.orchestrator.core.services.BambooService
    ): ToolResult = coroutineScope {
        val latestDeferred = async { service.getLatestBuild(chainKey) }
        val runningDeferred = async { service.getRunningBuilds(chainKey) }
        val latest = latestDeferred.await().toAgentToolResult()
        val running = runningDeferred.await()
        val active = if (running.isError) emptyList() else running.data.orEmpty()
        if (active.isEmpty()) {
            latest
        } else {
            val notice = buildString {
                append("⚠ ${active.size} build(s) currently IN PROGRESS or QUEUED for $chainKey ")
                append("(not returned by the latest-build endpoint — the most recent FINISHED build is shown below):\n")
                active.forEach { b ->
                    val lifeState = b.lifeCycleState.ifBlank { b.state }.ifBlank { "InProgress" }
                    append("  • #${b.buildNumber} [$lifeState] ${b.buildResultKey}\n")
                }
                append("\n— Most recent FINISHED build —\n")
            }
            val combined = notice + latest.content
            ToolResult(
                combined,
                "${active.size} in-progress/queued + latest finished for $chainKey",
                TokenEstimator.estimate(combined),
                isError = latest.isError
            )
        }
    }

    internal suspend fun executeGetBuildForTest(
        buildKey: String,
        includeCommits: Boolean,
        service: com.workflow.orchestrator.core.services.BambooService
    ): ToolResult {
        if (!includeCommits) {
            return service.getBuild(buildKey).toAgentToolResult()
        }
        return coroutineScope {
            val buildDeferred = async { service.getBuild(buildKey) }
            val changesDeferred = async { service.getBuildChanges(buildKey) }
            val buildResult = buildDeferred.await()
            val changes = changesDeferred.await()
            if (buildResult.isError) return@coroutineScope buildResult.toAgentToolResult()
            val buildAgent = buildResult.toAgentToolResult()
            val commitsBlock = formatBuildCommits(changes.data ?: emptyList())
            val combined = buildAgent.content + "\n\n" + commitsBlock
            ToolResult(combined, "${buildAgent.summary} · ${changes.summary}", TokenEstimator.estimate(combined))
        }
    }

    private fun formatBuildCommits(commits: List<BuildChangeData>): String {
        if (commits.isEmpty()) return "Commits: (none)"
        val lines = commits.take(50).map { c ->
            "• ${c.changesetId.take(8)} · ${c.fullName.ifBlank { c.userName }.ifBlank { "—" }} · ${(c.comment.lineSequence().firstOrNull() ?: "").take(200)}"
        }
        val tail = if (commits.size > 50) "\n  …(${commits.size - 50} more commits)" else ""
        return "Commits (showing ${minOf(50, commits.size)} of ${commits.size}):\n" + lines.joinToString("\n") + tail
    }
}

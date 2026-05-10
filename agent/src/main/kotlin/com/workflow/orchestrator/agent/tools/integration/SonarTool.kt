package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.builtin.RunCommandTool
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
import com.workflow.orchestrator.agent.tools.integration.sonar.CeTaskIdParser
import com.workflow.orchestrator.agent.tools.integration.sonar.IssueSeverity
import com.workflow.orchestrator.agent.tools.integration.sonar.SonarRetry
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.sonar.SonarFileComponent
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.settings.RepoContextResolver
import com.workflow.orchestrator.core.util.BuildToolExecutableResolver
import com.workflow.orchestrator.core.util.HtmlEscape
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext

/**
 * Consolidated SonarQube meta-tool (14 actions).
 *
 * Saves token budget per API call by collapsing all SonarQube operations into
 * a single tool definition with an `action` discriminator parameter.
 *
 * Actions: issues, quality_gate, coverage, search_projects, analysis_tasks,
 *          branches(include_files?), project_measures, source_lines, issues_paged,
 *          security_hotspots, duplications, branch_quality_report,
 *          local_analysis, rule(rule_key)
 */
class SonarTool : AgentTool {

    override val name = "sonar"

    // ══════════════════════════════════════════════════════════════════════
    // Tool documentation (DSL) — 18 actions
    // ══════════════════════════════════════════════════════════════════════

    override fun documentation(): ToolDocumentation = toolDoc("sonar") {
        summary {
            technical(
                "Single-tool dispatcher for 18 SonarQube REST operations: issue listing (simple + paginated), " +
                "quality-gate status, overall coverage metrics, project measures, source-line coverage view, " +
                "security hotspot listing + full hotspot detail, issue facets, branch/file enumeration, " +
                "analysis task status, rule detail, quality-gate catalog, current-user identity, code duplications, " +
                "a consolidated branch-quality report, and a local Maven/Gradle scanner with CE-task polling. " +
                "Bearer-auth via PasswordSafe; conditionally registered when ConnectionSettings.sonarUrl is non-blank."
            )
            plain(
                "The agent's SonarQube remote control. Like having the Sonar web UI available as a tool — " +
                "read issues, check if the quality gate would block a merge, see exactly which lines aren't " +
                "covered, run the scanner locally on a handful of files and get back results in seconds. " +
                "Credentials stay in the OS keychain (PasswordSafe) and never appear in the LLM's context."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.NETWORK)
        counterfactual(
            "Without `sonar`, the LLM must fall back to `run_command curl -H 'Authorization: Bearer <token>' " +
            "https://sonar/api/issues/search?...`. That leaks the Sonar token into the command line (visible in " +
            "`ps aux`, shell history, and agent JSONL logs), forces the LLM to build and parse raw JSON manually, " +
            "and loses the local-analysis workflow entirely (Maven/Gradle spawn + CE-task polling + parallel " +
            "per-file result fetching cannot be replicated with a single curl call)."
        )
        llmMistake(
            "Calls `coverage` expecting to see new-code (PR) coverage. `coverage` returns OVERALL project coverage; " +
            "new-code coverage lives in `branch_quality_report`. When the user asks 'is my PR covered?', use " +
            "`branch_quality_report` with the PR branch name."
        )
        llmMistake(
            "Calls `issues` on a large project and concludes 'all issues are listed' after seeing 500 results. " +
            "`issues` is hard-capped at 500 — for complete enumeration use `issues_paged` across multiple pages, " +
            "or use `issue_facets` to get aggregate counts first without fetching every issue body."
        )
        llmMistake(
            "Calls `analysis_tasks` and gets a 403, then retries. `analysis_tasks` requires admin permission. " +
            "A 403 means the token lacks the right — retrying will never help. Fall back to `branches` or " +
            "`quality_gate` which return equivalent freshness data without admin."
        )
        llmMistake(
            "Calls `security_hotspots` and then tries to mark hotspots fixed via the API. Whether a hotspot " +
            "can be marked via the API depends on `canChangeStatus` in the hotspot_detail response. " +
            "When false, the only remediation path is edit code → push → wait for re-analysis."
        )
        llmMistake(
            "Passes a bare filename (e.g. 'OrderService.java') to `source_lines` as the `component_key`. " +
            "`component_key` must be the full SonarQube component key (e.g. 'com.example:my-service:src/main/java/com/example/OrderService.java'). " +
            "Call `branches(include_files=true)` first to discover the exact component keys in Sonar's scope."
        )
        llmMistake(
            "Passes a branch name like 'release/1.0' or 'main' to `local_analysis` hoping to publish to that branch. " +
            "Protected names (main, master, develop, release/*, hotfix/*, trunk) are automatically redirected to " +
            "'local-scratch-<name>' to avoid overwriting the real branch's Sonar dashboard."
        )
        llmMistake(
            "Uses `issue_facets` with facet names like 'fileUuids' or 'components'. The correct 25.x facet name is 'files' " +
            "(not 'fileUuids'). See the tool description for the full list of valid facet names."
        )
        llmMistake(
            "Calls `local_analysis` without waiting for the result before interpreting Sonar data. `local_analysis` " +
            "blocks until the Maven/Gradle scanner finishes AND the CE task is processed server-side — the returned " +
            "results already reflect the latest analysis. No follow-up `analysis_tasks` poll is needed."
        )
        flowchart(
            """
            flowchart TD
                A[LLM needs Sonar data] --> B{What kind of question?}
                B -- PR quality check --> C[branch_quality_report project_key branch]
                C --> D[New-code issues + coverage + hotspots + gate in one call]
                B -- overall project health --> E[quality_gate + coverage + project_measures]
                B -- find specific issues --> F{How many?}
                F -- up to 500 --> G[issues project_key]
                F -- full enumeration --> H[issue_facets → issues_paged page=1..N]
                B -- security review --> I[security_hotspots → hotspot_detail per key]
                B -- line-level coverage --> J[branches include_files=true → source_lines component_key]
                B -- immediate feedback after edit --> K[local_analysis files=...]
                K --> L{Maven/Gradle detected?}
                L -- yes --> M[Spawn scanner → poll CE task → fetch per-file results]
                L -- no --> N[Error: no build tool found]
                B -- unfamiliar rule --> O[rule rule_key]
            """
        )
        actions {
            action("issues") {
                description {
                    technical(
                        "Calls GET /api/issues/search with project_key, optional file-path filter, optional branch, " +
                        "and optional new_code_only. Returns up to 500 issues in one call. Each issue on Sonar 9.6+ " +
                        "carries `impacts[]` (per-software-quality severity) and `cleanCodeAttribute`/`cleanCodeAttributeCategory` " +
                        "alongside legacy `severity`/`type` fields."
                    )
                    plain(
                        "Fetches the issue list for a project — like opening the Issues page in the Sonar UI " +
                        "and applying a file or branch filter. Capped at 500 results; use `issues_paged` for complete enumeration."
                    )
                }
                whenLLMUses(
                    "When the user asks 'what issues does this project have?' or 'show me bugs in OrderService'. " +
                    "Also the first step before prioritizing what to fix. Set new_code_only=true when the user asks " +
                    "specifically about PR-introduced issues."
                )
                params {
                    required("project_key", "string") {
                        llmSeesIt("SonarQube project key e.g. 'com.example:my-service' — for issues, quality_gate, coverage, analysis_tasks, branches, project_measures, issues_paged")
                        humanReadable("The Sonar project identifier — typically 'groupId:artifactId' in Maven projects or a configured key in CI.")
                        whenPresent("Used as the `componentKeys` query parameter in the Sonar API call.")
                        example("com.example:my-service")
                        example("org.acme:payment-gateway")
                    }
                    optional("file", "string") {
                        llmSeesIt("Optional relative file path filter — for issues")
                        humanReadable("Scope the issue list to a single file. Pass the repo-relative path, not the full component key.")
                        whenPresent("Sent as `files` query param to narrow the issue set to that file only.")
                        whenAbsent("All files in the project are included (up to the 500-result cap).")
                        example("src/main/java/com/example/OrderService.java")
                    }
                    optional("branch", "string") {
                        llmSeesIt("Optional branch name — for issues, quality_gate, coverage, project_measures, source_lines, issues_paged, security_hotspots, duplications. Use project_context tool to discover current branch. For local_analysis: omit to auto-derive from current Git HEAD; protected names (main/master/develop/release/*/hotfix/*/trunk) are auto-redirected to 'local-scratch-<name>'. When include_files=true on the 'branches' action, this scopes the file-list query.")
                        humanReadable("Which branch to query. Without this, Sonar returns the main-branch data.")
                        whenPresent("Passed as `branch` query parameter to scope to that branch's analysis.")
                        whenAbsent("Sonar returns main-branch analysis data.")
                        example("feature/my-pr-branch")
                    }
                    optional("new_code_only", "boolean") {
                        llmSeesIt("When true, return only issues introduced in the new code period (since branch point or configured baseline) — for issues, issues_paged, local_analysis")
                        humanReadable("When true, filters to issues introduced since the branch point — analogous to 'Only New Code' in the Sonar UI.")
                        whenPresent("Adds `inNewCodePeriod=true` to the API call; returns only issues in the new-code window.")
                        whenAbsent("All issues for the branch are returned regardless of when they were introduced.")
                        example("true")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects")
                        humanReadable("Used to select the right Sonar service instance when the IntelliJ project contains multiple repositories.")
                        whenPresent("Passed to `ServiceLookup.sonar(repoName)` to pick the correct service configuration.")
                        whenAbsent("Primary (or only) configured Sonar service is used.")
                        example("payments-service")
                    }
                }
                onSuccess("Returns a formatted issue list grouped by severity (BLOCKER → CRITICAL → MAJOR → MINOR → INFO), with per-issue: rule key, message, file, line, status, and on Sonar 9.6+ the impact vector and clean-code category.")
                onFailure("403 Forbidden", "Token lacks Browse permission on the project. Surface to user; don't retry.")
                onFailure("project not found", "Sonar returns 404 or an empty result if the project_key is wrong. Verify via `search_projects` first.")
                onFailure("result capped at 500", "If the project has more than 500 issues, only the first 500 are returned — no error. Use `issues_paged` or `issue_facets` for complete data.")
                example("all bugs in a project") {
                    param("action", "issues")
                    param("project_key", "com.example:my-service")
                    outcome("Returns up to 500 issues sorted by severity.")
                }
                example("issues in one file on a PR branch") {
                    param("action", "issues")
                    param("project_key", "com.example:my-service")
                    param("file", "src/main/java/com/example/OrderService.java")
                    param("branch", "feature/PROJ-123")
                    param("new_code_only", "true")
                    outcome("Returns only issues introduced in OrderService.java since the branch point.")
                }
                verdict {
                    keep("Core read operation; used in nearly every code-quality workflow.", VerdictSeverity.STRONG)
                }
            }

            action("quality_gate") {
                description {
                    technical(
                        "Calls GET /api/qualitygates/project_status for the given project_key and optional branch. " +
                        "Returns both the overall gate status (OK/WARN/ERROR) and per-condition detail. " +
                        "On Sonar 25.x also carries `caycStatus` ∈ compliant/over-compliant/non-compliant."
                    )
                    plain(
                        "Answers 'would this branch block a merge?' — like looking at the quality gate badge on the " +
                        "Sonar project overview. Shows which specific conditions (coverage, duplication, bugs) are passing or failing."
                    )
                }
                whenLLMUses(
                    "When the user asks 'will my PR pass the quality gate?' or 'what's blocking the quality gate?'. " +
                    "Also useful to run before triggering a merge to confirm Sonar won't block it."
                )
                params {
                    required("project_key", "string") {
                        llmSeesIt("SonarQube project key e.g. 'com.example:my-service' — for issues, quality_gate, coverage, analysis_tasks, branches, project_measures, issues_paged")
                        humanReadable("The Sonar project key.")
                        whenPresent("Used to query the project's current quality-gate status.")
                        example("com.example:my-service")
                    }
                    optional("branch", "string") {
                        llmSeesIt("Optional branch name — for issues, quality_gate, coverage, project_measures, source_lines, issues_paged, security_hotspots, duplications. Use project_context tool to discover current branch. For local_analysis: omit to auto-derive from current Git HEAD; protected names (main/master/develop/release/*/hotfix/*/trunk) are auto-redirected to 'local-scratch-<name>'. When include_files=true on the 'branches' action, this scopes the file-list query.")
                        humanReadable("Which branch to check. Omit to check the main branch gate status.")
                        whenPresent("Gate status is returned for this specific branch analysis.")
                        whenAbsent("Main branch gate status is returned.")
                        example("feature/PROJ-123")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects")
                        humanReadable("Selects the right Sonar service for multi-repo projects.")
                        whenPresent("Used for service selection.")
                        whenAbsent("Primary Sonar service used.")
                    }
                }
                onSuccess("Returns the gate status (OK/WARN/ERROR), per-condition breakdown (metric, operator, threshold, actual value, status), and on 25.x the caycStatus for 'Clean as You Code' gate compliance.")
                onFailure("branch not found", "Sonar returns an error if the branch has never been analyzed. Run `local_analysis` or trigger a CI build first.")
                onFailure("project_key wrong", "Empty/error response. Verify via `search_projects`.")
                example("check PR gate before merge") {
                    param("action", "quality_gate")
                    param("project_key", "com.example:my-service")
                    param("branch", "feature/PROJ-123")
                    outcome("Returns ERROR with the failing conditions (e.g. 'New Coverage < 80%') so the LLM can explain what needs fixing.")
                }
                verdict {
                    keep("Critical integration point for the PR-merge workflow. Used in every 'can I merge?' query.", VerdictSeverity.STRONG)
                }
            }

            action("coverage") {
                description {
                    technical(
                        "Calls GET /api/measures/component for line_coverage, branch_coverage, covered_lines, " +
                        "lines_to_cover. Returns OVERALL project coverage metrics — not new-code coverage."
                    )
                    plain(
                        "Shows the headline coverage numbers for the whole project. Like reading the 'Coverage' widget " +
                        "on the Sonar project overview. Does NOT show PR-specific new-code coverage — use " +
                        "`branch_quality_report` for that."
                    )
                }
                whenLLMUses(
                    "When the user asks 'what's the overall test coverage?' or 'is coverage above 80%?'. " +
                    "NOT for 'did my PR add enough coverage?' — that's `branch_quality_report`."
                )
                params {
                    required("project_key", "string") {
                        llmSeesIt("SonarQube project key e.g. 'com.example:my-service' — for issues, quality_gate, coverage, analysis_tasks, branches, project_measures, issues_paged")
                        humanReadable("The Sonar project key.")
                        whenPresent("Used to query overall coverage metrics.")
                        example("com.example:my-service")
                    }
                    optional("branch", "string") {
                        llmSeesIt("Optional branch name — for issues, quality_gate, coverage, project_measures, source_lines, issues_paged, security_hotspots, duplications. Use project_context tool to discover current branch. For local_analysis: omit to auto-derive from current Git HEAD; protected names (main/master/develop/release/*/hotfix/*/trunk) are auto-redirected to 'local-scratch-<name>'. When include_files=true on the 'branches' action, this scopes the file-list query.")
                        humanReadable("Which branch to check coverage for.")
                        whenPresent("Returns coverage for that branch analysis.")
                        whenAbsent("Main branch coverage returned.")
                        example("main")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects")
                        humanReadable("Multi-repo service selection.")
                        whenPresent("Picks the right Sonar service.")
                        whenAbsent("Primary Sonar service used.")
                    }
                }
                onSuccess("Returns line coverage %, branch coverage %, covered lines, and lines to cover for the project.")
                onFailure("no coverage data", "Sonar returns null metrics if coverage was never configured in the CI pipeline. Return includes the raw response; LLM should explain this to the user.")
                example("check overall coverage") {
                    param("action", "coverage")
                    param("project_key", "com.example:my-service")
                    outcome("Returns 'Line Coverage: 72.3%, Branch Coverage: 61.1%, Covered Lines: 1824/2521'.")
                    notes("Use `branch_quality_report` if the user's question is about new-code coverage in a PR.")
                }
                verdict {
                    keep("Useful for project-level coverage snapshots; complementary to branch_quality_report.", VerdictSeverity.NORMAL)
                }
            }

            action("branch_quality_report") {
                description {
                    technical(
                        "Consolidated new-code quality report via parallel fan-out: GET /api/qualitygates/project_status " +
                        "for new-code gate conditions, GET /api/issues/search inNewCodePeriod for bugs/smells/vulns, " +
                        "GET /api/hotspots/search for security hotspots, GET /api/measures/component for new-code " +
                        "coverage metrics, and GET /api/measures/component_tree for per-file drill-down up to max_files. " +
                        "The per-file block includes exact uncovered line numbers, uncovered branch lines, and " +
                        "duplicated line ranges."
                    )
                    plain(
                        "The single most useful action for PR reviews. One call returns everything you'd check manually " +
                        "on the Sonar 'New Code' tab: which gate conditions fail, what new issues were introduced, " +
                        "which lines aren't covered, and which files have duplication. Saves 4-5 separate API calls."
                    )
                }
                whenLLMUses(
                    "Whenever the user asks about PR quality, new code, 'will this pass Sonar?', or 'what's uncovered in my changes?'. " +
                    "This should be the first Sonar call for any PR-review or code-quality improvement task."
                )
                params {
                    required("project_key", "string") {
                        llmSeesIt("SonarQube project key e.g. 'com.example:my-service' — for issues, quality_gate, coverage, analysis_tasks, branches, project_measures, issues_paged")
                        humanReadable("The Sonar project key for the PR's repository.")
                        whenPresent("All fan-out sub-calls use this key.")
                        example("com.example:my-service")
                    }
                    required("branch", "string") {
                        llmSeesIt("Optional branch name — for issues, quality_gate, coverage, project_measures, source_lines, issues_paged, security_hotspots, duplications. Use project_context tool to discover current branch. For local_analysis: omit to auto-derive from current Git HEAD; protected names (main/master/develop/release/*/hotfix/*/trunk) are auto-redirected to 'local-scratch-<name>'. When include_files=true on the 'branches' action, this scopes the file-list query.")
                        humanReadable("The PR's source branch name — required because new-code data is always branch-specific.")
                        whenPresent("All sub-calls are scoped to this branch.")
                        example("feature/PROJ-123-add-order-validation")
                    }
                    optional("max_files", "string") {
                        llmSeesIt("Max files to drill down into for line-level details (default 20) — for branch_quality_report")
                        humanReadable("Cap the per-file drill-down. Default 20. Increase for large PRs; decrease to avoid context overflow.")
                        whenPresent("Limits per-file blocks to this count. Files with most issues are shown first.")
                        whenAbsent("Defaults to 20 files.")
                        constraint("must be a positive integer")
                        example("10")
                        example("50")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects")
                        humanReadable("Multi-repo service selection.")
                        whenPresent("Picks the right Sonar service.")
                        whenAbsent("Primary Sonar service used.")
                    }
                }
                onSuccess(
                    "Returns a consolidated report: quality gate status + per-condition breakdown, new-code issue counts by severity, " +
                    "security hotspot count, new-code line/branch coverage %, uncovered lines, duplication density, " +
                    "and per-file drill-down with exact uncovered line numbers and duplicated ranges."
                )
                onFailure("branch not analyzed yet", "Sonar returns empty/error data if the branch has never been pushed to CI. Run `local_analysis` to seed the branch first.")
                onFailure("partial fan-out failure", "If one sub-call fails (e.g. hotspots returns 403), that section is omitted from the report; other sections still render.")
                example("full PR quality check") {
                    param("action", "branch_quality_report")
                    param("project_key", "com.example:my-service")
                    param("branch", "feature/PROJ-123")
                    outcome("Returns gate status, new-code issues grouped by severity, coverage %, and per-file uncovered lines — the agent can then use `local_analysis` or direct edits to fix the gaps.")
                }
                example("large PR with more files") {
                    param("action", "branch_quality_report")
                    param("project_key", "com.example:my-service")
                    param("branch", "feature/refactor-payment")
                    param("max_files", "50")
                    outcome("Per-file drill-down covers up to 50 files instead of the default 20.")
                }
                verdict {
                    keep("The highest-value action in the tool. Replaces 4-5 separate calls in a PR review workflow.", VerdictSeverity.STRONG)
                }
            }

            action("local_analysis") {
                description {
                    technical(
                        "Spawns a Maven (`mvn sonar:sonar`) or Gradle (`gradle sonar`) process on specific files via " +
                        "-Dsonar.inclusions=<files>. Uses SONAR_TOKEN env-var (token never in argv). Resolves Maven " +
                        "multi-module scope via MavenProjectsManager. Streams scanner output with CE-task-ID extraction " +
                        "(CeTaskIdParser), polls GET /api/ce/task until SUCCESS/FAILED/CANCELED or timeout. " +
                        "After CE finishes, fetches per-file: issues, source_lines, security_hotspots, duplications " +
                        "in parallel. Branch is auto-derived from Git HEAD; protected names redirected to " +
                        "'local-scratch-<name>' to avoid polluting the real branch's Sonar dashboard."
                    )
                    plain(
                        "Runs SonarQube analysis locally on a handful of files and gives you back the results " +
                        "in one tool call. Like a miniature CI pipeline in your IDE: it compiles, uploads, waits for " +
                        "Sonar to process, then shows you issues + coverage + hotspots per file — without needing to push " +
                        "to remote or wait for the full CI run. Ideal for a tight edit → scan → fix loop."
                    )
                }
                whenLLMUses(
                    "After editing files and wanting immediate Sonar feedback before pushing. Also useful when the " +
                    "CI pipeline is slow and the user wants to verify their fix locally. Requires Maven (pom.xml) or " +
                    "Gradle (build.gradle/settings.gradle) in the project root."
                )
                params {
                    required("files", "string") {
                        llmSeesIt("Comma-separated file paths to analyse (relative to project root or absolute) — for local_analysis. E.g. 'src/main/java/com/example/OrderService.java,src/main/java/com/example/PaymentService.java'")
                        humanReadable("The files to scan — passed as -Dsonar.inclusions to the scanner. Relative or absolute paths both accepted.")
                        whenPresent("Scanner is scoped to only these files; builds only the owning Maven module(s).")
                        constraint("comma-separated; paths can be relative to project root or absolute")
                        example("src/main/java/com/example/OrderService.java")
                        example("src/main/java/com/example/OrderService.java,src/main/java/com/example/PaymentService.java")
                    }
                    optional("branch", "string") {
                        llmSeesIt("Optional branch name — for issues, quality_gate, coverage, project_measures, source_lines, issues_paged, security_hotspots, duplications. Use project_context tool to discover current branch. For local_analysis: omit to auto-derive from current Git HEAD; protected names (main/master/develop/release/*/hotfix/*/trunk) are auto-redirected to 'local-scratch-<name>'. When include_files=true on the 'branches' action, this scopes the file-list query.")
                        humanReadable("Branch to publish results under. Omit to auto-derive from current Git HEAD.")
                        whenPresent("Scanner publishes under this branch name (after protected-name redirect if applicable).")
                        whenAbsent("Auto-derived from Git HEAD via focusPr.fromBranch → current editor branch. Omitted entirely if no branch is detectable (Community Edition safe).")
                        example("feature/PROJ-123")
                    }
                    optional("timeout", "integer") {
                        llmSeesIt("Seconds before the scanner process is killed (default 300, max 900) — for local_analysis")
                        humanReadable("How long to wait for the scanner before killing it. Large projects may need 600-900s.")
                        whenPresent("Scanner process is killed after this many seconds and a TIMEOUT error is returned.")
                        whenAbsent("Defaults to 300s. Clamped to [30, 900].")
                        constraint("clamped to [30, 900]")
                        example("600")
                        example("900")
                    }
                    optional("new_code_only", "boolean") {
                        llmSeesIt("When true, return only issues introduced in the new code period (since branch point or configured baseline) — for issues, issues_paged, local_analysis")
                        humanReadable("Filters per-file results to show only issues introduced since the branch point. Reduces output ~100x on large codebases.")
                        whenPresent("Per-file issue fetching passes inNewCodePeriod=true; min_severity filter is applied after.")
                        whenAbsent("All issues for the scanned files are returned.")
                        example("true")
                    }
                    optional("min_severity", "string") {
                        llmSeesIt("Minimum issue severity to surface in output (BLOCKER | CRITICAL | MAJOR | MINOR | INFO). Lower-severity issues are dropped. Default: INFO (no filter) — for local_analysis")
                        humanReadable("Drop issues below this severity from the output. Useful to reduce noise when the codebase has many MINOR/INFO issues.")
                        whenPresent("Issues below this threshold are counted but not printed; the output notes how many were dropped.")
                        whenAbsent("Defaults to INFO — no filtering.")
                        enumValue("BLOCKER", "CRITICAL", "MAJOR", "MINOR", "INFO")
                        example("MAJOR")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects")
                        humanReadable("Multi-repo service selection for the post-scan API calls.")
                        whenPresent("Used for service selection in CE-task polling and per-file result fetches.")
                        whenAbsent("Primary Sonar service used.")
                    }
                }
                precondition("Maven (pom.xml) or Gradle (build.gradle/settings.gradle) must exist in the project root.")
                precondition("SonarQube URL and token must be configured in Settings > Workflow Orchestrator > Connections.")
                precondition("Pre-flight connection check runs before spawning any process — bad URL/token fails fast.")
                onSuccess(
                    "Returns a per-file report with: scanner duration, branch published to (with any redirect note), " +
                    "CE task ID, then for each file: issues grouped by severity with inline rule 'How to fix' snippets, " +
                    "security hotspots with risk/fix guidance, per-line coverage %, uncovered line ranges, and duplication blocks."
                )
                onFailure("no build tool found", "Returns error: 'No Maven (pom.xml) or Gradle (build.gradle) build file found'. Cannot scan without a build tool.")
                onFailure("scanner timeout", "Returns TIMEOUT error after `timeout` seconds. Try increasing `timeout` or scanning fewer files at once.")
                onFailure("scanner exit non-zero", "Returns the scanner's last 30 lines of stdout. Common causes: Sonar project key mismatch, auth failure, compilation error.")
                onFailure("CE task FAILED", "Scanner succeeded but server processing failed (e.g. plugin version mismatch). Check CE task status in Sonar admin.")
                onFailure("CE task polling 403", "Token lacks 'Execute Analysis' or 'Administer System' permission; falls back to a fixed 5s wait instead of polling. Per-file results may be from a prior analysis.")
                example("quick feedback after editing one file") {
                    param("action", "local_analysis")
                    param("files", "src/main/java/com/example/OrderService.java")
                    param("new_code_only", "true")
                    param("min_severity", "MAJOR")
                    outcome("Runs Maven sonar:sonar scoped to OrderService.java; returns only MAJOR+ issues introduced by the edit, with uncovered lines and hotspots.")
                }
                example("multi-file analysis with custom timeout") {
                    param("action", "local_analysis")
                    param("files", "src/main/java/com/example/OrderService.java,src/main/java/com/example/PaymentService.java")
                    param("timeout", "600")
                    outcome("Analyzes both files with a 600s timeout; per-file results include all issues, coverage, and hotspots.")
                }
                verdict {
                    keep(
                        "Unique capability — no other tool can run a local Sonar scan with CE-task polling and per-file result fetch. " +
                        "Essential for tight edit → verify loops on feature branches.",
                        VerdictSeverity.STRONG
                    )
                }
            }

            action("issues_paged") {
                description {
                    technical(
                        "Calls GET /api/issues/search with p=page and ps=page_size, allowing pagination beyond the " +
                        "500-result cap of the `issues` action. Max page_size is 500."
                    )
                    plain(
                        "The paginated sibling of `issues`. Like flipping through pages on the Sonar Issues tab. " +
                        "Use when the project has more than 500 issues and you need the full list."
                    )
                }
                whenLLMUses(
                    "After calling `issue_facets` to discover total issue count, then walking through pages if total > 500. " +
                    "Or when the user asks for a complete issue export."
                )
                params {
                    required("project_key", "string") {
                        llmSeesIt("SonarQube project key e.g. 'com.example:my-service' — for issues, quality_gate, coverage, analysis_tasks, branches, project_measures, issues_paged")
                        humanReadable("The Sonar project key.")
                        whenPresent("Used as the componentKeys parameter.")
                        example("com.example:my-service")
                    }
                    optional("page", "string") {
                        llmSeesIt("Page number (default 1) — for issues_paged")
                        humanReadable("Which page to fetch (1-based).")
                        whenPresent("Fetches this specific page of results.")
                        whenAbsent("Defaults to page 1.")
                        example("2")
                    }
                    optional("page_size", "string") {
                        llmSeesIt("Results per page max 500 (default 100) — for issues_paged")
                        humanReadable("How many results per page. Default 100, max 500.")
                        whenPresent("Overrides the default page size.")
                        whenAbsent("Defaults to 100.")
                        constraint("max 500")
                        example("100")
                        example("500")
                    }
                    optional("branch", "string") {
                        llmSeesIt("Optional branch name — for issues, quality_gate, coverage, project_measures, source_lines, issues_paged, security_hotspots, duplications. Use project_context tool to discover current branch. For local_analysis: omit to auto-derive from current Git HEAD; protected names (main/master/develop/release/*/hotfix/*/trunk) are auto-redirected to 'local-scratch-<name>'. When include_files=true on the 'branches' action, this scopes the file-list query.")
                        humanReadable("Branch to query.")
                        whenPresent("Scopes results to this branch.")
                        whenAbsent("Main branch.")
                        example("feature/PROJ-123")
                    }
                    optional("new_code_only", "boolean") {
                        llmSeesIt("When true, return only issues introduced in the new code period (since branch point or configured baseline) — for issues, issues_paged, local_analysis")
                        humanReadable("Filter to new-code issues only.")
                        whenPresent("Scopes results to new-code period.")
                        whenAbsent("All issues returned.")
                        example("true")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects")
                        humanReadable("Multi-repo service selection.")
                        whenPresent("Picks the right Sonar service.")
                        whenAbsent("Primary Sonar service used.")
                    }
                }
                onSuccess("Returns one page of issues with pagination metadata (page, pageSize, total).")
                onFailure("page beyond total", "Sonar returns an empty result — not an error. LLM should stop paging when results are empty.")
                example("enumerate all issues across pages") {
                    param("action", "issues_paged")
                    param("project_key", "com.example:my-service")
                    param("page", "2")
                    param("page_size", "500")
                    outcome("Returns issues 501-1000 of the project's full issue list.")
                    notes("Call `issue_facets` first with facets='rules,severities' to decide whether full enumeration is worth it.")
                }
                verdict {
                    keep("Necessary complement to `issues` for large projects. Could be merged into `issues` with a page param, but the action-split keeps the common case simple.", VerdictSeverity.NORMAL)
                }
            }

            action("issue_facets") {
                description {
                    technical(
                        "Calls GET /api/issues/search with facets= param. Returns aggregate counts per facet " +
                        "(e.g. per-severity, per-rule, per-file) without fetching issue bodies. " +
                        "Valid 25.x facets include: severities, types, tags, impactSoftwareQualities, " +
                        "impactSeverities, cleanCodeAttributeCategories, assignees, files, rules, statuses, " +
                        "resolutions, author, directories, scopes, languages, codeVariants, issueStatuses, " +
                        "prioritizedRule, createdAt, sonarsourceSecurity, plus security-compliance facets " +
                        "(pciDss-3.2, pciDss-4.0, owaspAsvs-4.0, owaspMobileTop10-2024, stig-ASD_V5R3, " +
                        "casa, sansTop25, cwe)."
                    )
                    plain(
                        "Gets issue statistics without fetching individual issues. Like the facet bar on the left " +
                        "side of Sonar's Issues page — tells you '42 CRITICAL, 310 MAJOR, 8 BLOCKER' before you " +
                        "decide whether to walk the full list. Much cheaper than paginating all issues."
                    )
                }
                whenLLMUses(
                    "Before walking issues to understand the distribution and decide priority order. " +
                    "Also useful when the user asks 'how many bugs are there?' without wanting the full list. " +
                    "Call BEFORE `issues_paged` to determine how many pages to fetch."
                )
                params {
                    required("project_key", "string") {
                        llmSeesIt("SonarQube project key e.g. 'com.example:my-service' — for issues, quality_gate, coverage, analysis_tasks, branches, project_measures, issues_paged")
                        humanReadable("The Sonar project key.")
                        whenPresent("All facet counts are scoped to this project.")
                        example("com.example:my-service")
                    }
                    required("facets", "string") {
                        llmSeesIt("Comma-separated facet names — for issue_facets. Valid 25.x: severities, types, tags, impactSoftwareQualities, impactSeverities, cleanCodeAttributeCategories, assignees, files, rules, statuses, resolutions, author, directories, scopes, languages, codeVariants, issueStatuses, prioritizedRule, createdAt, sonarsourceSecurity, plus pciDss-3.2/4.0, owaspAsvs-4.0, owaspMobileTop10-2024, stig-ASD_V5R3, casa, sansTop25, cwe.")
                        humanReadable("Comma-separated list of facets to compute. No spaces. Use 'files' NOT 'fileUuids'.")
                        whenPresent("Sonar computes aggregate counts for each named facet and returns them.")
                        constraint("comma-separated, no spaces; use 'files' not 'fileUuids'")
                        example("severities,types")
                        example("files,rules,impactSoftwareQualities")
                    }
                    optional("branch", "string") {
                        llmSeesIt("Optional branch name — for issues, quality_gate, coverage, project_measures, source_lines, issues_paged, security_hotspots, duplications. Use project_context tool to discover current branch. For local_analysis: omit to auto-derive from current Git HEAD; protected names (main/master/develop/release/*/hotfix/*/trunk) are auto-redirected to 'local-scratch-<name>'. When include_files=true on the 'branches' action, this scopes the file-list query.")
                        humanReadable("Branch to compute facets for.")
                        whenPresent("Facets reflect issues on this branch.")
                        whenAbsent("Main branch.")
                        example("feature/PROJ-123")
                    }
                    optional("new_code_only", "boolean") {
                        llmSeesIt("When true, return only issues introduced in the new code period (since branch point or configured baseline) — for issues, issues_paged, local_analysis")
                        humanReadable("Scope facets to new-code issues only.")
                        whenPresent("Facets computed only over new-code issues.")
                        whenAbsent("All issues.")
                        example("true")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects")
                        humanReadable("Multi-repo service selection.")
                        whenPresent("Picks the right Sonar service.")
                        whenAbsent("Primary Sonar service used.")
                    }
                }
                onSuccess("Returns a map of facet → list of (value, count) pairs, e.g. {severities: [{val: 'BLOCKER', count: 3}, {val: 'CRITICAL', count: 14}]}.")
                onFailure("invalid facet name", "Sonar returns a 400 with 'Unknown facet'. Use 'files' not 'fileUuids'; check the description for valid names.")
                example("pre-paging decision") {
                    param("action", "issue_facets")
                    param("project_key", "com.example:my-service")
                    param("facets", "severities,types,files")
                    outcome("Returns per-severity and per-type counts plus the top files by issue count — LLM can decide whether to page through all issues or focus on CRITICAL only.")
                }
                verdict {
                    keep("Low-cost reconnaissance before expensive issue enumeration. Saves unnecessary pagination.", VerdictSeverity.NORMAL)
                }
            }

            action("security_hotspots") {
                description {
                    technical(
                        "Calls GET /api/hotspots/search for the project. Returns hotspot list with location " +
                        "(file, line), probability (HIGH/MEDIUM/LOW), and status (TO_REVIEW/REVIEWED). " +
                        "Does NOT include risk description, fix guidance, or canChangeStatus — use `hotspot_detail` for those."
                    )
                    plain(
                        "Lists security hotspots — like the Security Hotspots page in Sonar. Each hotspot " +
                        "is a potential security issue that needs human review. This action gives you the list; " +
                        "call `hotspot_detail` on each key to get the actual fix guidance."
                    )
                }
                whenLLMUses(
                    "When the user asks about security vulnerabilities or 'what security hotspots are there?' — " +
                    "as the first step before calling `hotspot_detail` on the most critical ones."
                )
                params {
                    required("project_key", "string") {
                        llmSeesIt("SonarQube project key e.g. 'com.example:my-service' — for issues, quality_gate, coverage, analysis_tasks, branches, project_measures, issues_paged")
                        humanReadable("The Sonar project key.")
                        whenPresent("Lists hotspots for this project.")
                        example("com.example:my-service")
                    }
                    optional("branch", "string") {
                        llmSeesIt("Optional branch name — for issues, quality_gate, coverage, project_measures, source_lines, issues_paged, security_hotspots, duplications. Use project_context tool to discover current branch. For local_analysis: omit to auto-derive from current Git HEAD; protected names (main/master/develop/release/*/hotfix/*/trunk) are auto-redirected to 'local-scratch-<name>'. When include_files=true on the 'branches' action, this scopes the file-list query.")
                        humanReadable("Branch to query.")
                        whenPresent("Hotspots scoped to this branch.")
                        whenAbsent("Main branch hotspots returned.")
                        example("feature/PROJ-123")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects")
                        humanReadable("Multi-repo service selection.")
                        whenPresent("Picks the right Sonar service.")
                        whenAbsent("Primary Sonar service used.")
                    }
                }
                onSuccess("Returns a list of hotspots with: key, file path, line, probability, status, message, rule key.")
                onFailure("empty list", "No hotspots — safe result, not an error. The project may have no security-sensitive code patterns.")
                example("list hotspots then get details") {
                    param("action", "security_hotspots")
                    param("project_key", "com.example:my-service")
                    outcome("Returns list of hotspot keys and locations. LLM then calls hotspot_detail for the HIGH-probability ones.")
                }
                verdict {
                    keep("Required first step for the security hotspot workflow. Pairs with hotspot_detail.", VerdictSeverity.NORMAL)
                }
            }

            action("hotspot_detail") {
                description {
                    technical(
                        "Calls GET /api/hotspots/show?hotspot=<key>. Returns full hotspot: riskDescription, " +
                        "vulnerabilityDescription, fixRecommendations (HTML, contains 'Compliant Solution' code " +
                        "examples), canChangeStatus (whether the active token can mark it fixed via API), " +
                        "securityCategory, vulnerabilityProbability."
                    )
                    plain(
                        "Fetches the full details for one security hotspot — like clicking on a hotspot in Sonar " +
                        "to open the details panel. The `fixRecommendations` field contains an actual code example " +
                        "of the correct pattern. **Check `canChangeStatus`** — if false, you cannot mark it fixed " +
                        "via the API; the only path is to fix the code and wait for re-analysis."
                    )
                }
                whenLLMUses(
                    "After calling `security_hotspots` to get the key list, for each HIGH-probability hotspot " +
                    "the user wants guidance on fixing."
                )
                precondition("hotspot_key must be obtained from a prior `security_hotspots` call.")
                params {
                    required("hotspot_key", "string") {
                        llmSeesIt("Hotspot UUID — for hotspot_detail. Discover via security_hotspots first.")
                        humanReadable("The hotspot UUID from the security_hotspots response.")
                        whenPresent("Used as the `hotspot` query parameter.")
                        example("AXoXVjlVjajj3N38AAAA")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects")
                        humanReadable("Multi-repo service selection.")
                        whenPresent("Picks the right Sonar service.")
                        whenAbsent("Primary Sonar service used.")
                    }
                }
                onSuccess("Returns riskDescription, vulnerabilityDescription, fixRecommendations (with a Compliant Solution code example), canChangeStatus, securityCategory, vulnerabilityProbability.")
                onFailure("hotspot not found", "404 if the key is wrong or the hotspot was resolved. Re-run security_hotspots to refresh the key list.")
                onFailure("canChangeStatus=false", "Non-error result. Agent CANNOT mark the hotspot fixed via API — must edit code and wait for re-analysis. Never promise autonomous closure in this case.")
                example("get fix guidance for a hotspot") {
                    param("action", "hotspot_detail")
                    param("hotspot_key", "AXoXVjlVjajj3N38AAAA")
                    outcome("Returns the vulnerability description and a 'Compliant Solution' code snippet the agent can show the user or use to guide a code edit.")
                }
                verdict {
                    keep("Essential complement to security_hotspots. The fixRecommendations field is the key value — it contains a ready-made code pattern.", VerdictSeverity.STRONG)
                }
            }

            action("source_lines") {
                description {
                    technical(
                        "Calls GET /api/sources/lines?component=<component_key>&from=<from>&to=<to>&branch=<branch>. " +
                        "Returns per-line coverage data: lineHits (statement coverage), conditions/coveredConditions " +
                        "(branch coverage), coverageStatus (covered/uncovered/partially-covered), isNew " +
                        "(whether the line is in the new-code period)."
                    )
                    plain(
                        "Shows the source code with coverage data overlaid on each line — like the Sonar " +
                        "'Coverage' view per file. Use it to find exactly which lines lack coverage and whether " +
                        "they are in the new-code window."
                    )
                }
                whenLLMUses(
                    "After confirming via `branches(include_files=true)` that the file is in Sonar's scope, " +
                    "to find uncovered lines and write targeted tests. Also useful to verify whether a specific " +
                    "line is covered before writing a test for it."
                )
                precondition("component_key must be the full SonarQube component key — call `branches(include_files=true)` first to discover it.")
                params {
                    required("component_key", "string") {
                        llmSeesIt("SonarQube component key e.g. 'com.example:my-service:src/main/java/MyClass.java' — for source_lines, duplications")
                        humanReadable("The full component key — not just a filename. Multi-module projects use 'projectKey:moduleName:pathWithinModule' format.")
                        whenPresent("Used as the `component` query parameter.")
                        example("com.example:my-service:src/main/java/com/example/OrderService.java")
                    }
                    optional("from", "string") {
                        llmSeesIt("Start line number — for source_lines")
                        humanReadable("First line to return (1-based). Omit to start from line 1.")
                        whenPresent("Only lines from this line number onward are returned.")
                        whenAbsent("Defaults to line 1.")
                        example("50")
                    }
                    optional("to", "string") {
                        llmSeesIt("End line number — for source_lines")
                        humanReadable("Last line to return (inclusive). Omit to read to end of file.")
                        whenPresent("Only lines up to this line number are returned.")
                        whenAbsent("Reads to end of file.")
                        example("150")
                    }
                    optional("branch", "string") {
                        llmSeesIt("Optional branch name — for issues, quality_gate, coverage, project_measures, source_lines, issues_paged, security_hotspots, duplications. Use project_context tool to discover current branch. For local_analysis: omit to auto-derive from current Git HEAD; protected names (main/master/develop/release/*/hotfix/*/trunk) are auto-redirected to 'local-scratch-<name>'. When include_files=true on the 'branches' action, this scopes the file-list query.")
                        humanReadable("Branch to query coverage for.")
                        whenPresent("Coverage data for this branch analysis.")
                        whenAbsent("Main branch coverage data.")
                        example("feature/PROJ-123")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects")
                        humanReadable("Multi-repo service selection.")
                        whenPresent("Picks the right Sonar service.")
                        whenAbsent("Primary Sonar service used.")
                    }
                }
                onSuccess("Returns per-line data: line number, coverageStatus, lineHits, conditions, coveredConditions, isNew. Uncovered lines have coverageStatus='uncovered' and lineHits=0.")
                onFailure("component key wrong", "Sonar returns empty or 404. Call `branches(include_files=true)` to get the exact component key.")
                onFailure("no coverage data for file", "Lines are returned but coverageStatus is null — the file exists but wasn't instrumented by the coverage tool.")
                example("find uncovered lines in a file") {
                    param("action", "source_lines")
                    param("component_key", "com.example:my-service:src/main/java/com/example/OrderService.java")
                    param("branch", "feature/PROJ-123")
                    outcome("Returns per-line coverage. LLM filters for coverageStatus='uncovered' lines to know exactly what tests to write.")
                }
                verdict {
                    keep("High precision — uniquely identifies exactly which lines need test coverage. No other action provides this granularity.", VerdictSeverity.STRONG)
                }
            }

            action("duplications") {
                description {
                    technical(
                        "Calls GET /api/duplications/show?component=<component_key>&branch=<branch>. Returns " +
                        "duplication blocks with per-fragment: file path, startLine, endLine."
                    )
                    plain(
                        "Shows which code blocks in a file are duplicated and where else they appear. " +
                        "Like the 'Duplications' view on a Sonar file detail page."
                    )
                }
                whenLLMUses(
                    "When the user asks 'where is this code duplicated?' or the quality gate fails on duplication. " +
                    "Call after confirming the component key via `branches(include_files=true)`."
                )
                precondition("component_key must be the full SonarQube component key.")
                params {
                    required("component_key", "string") {
                        llmSeesIt("SonarQube component key e.g. 'com.example:my-service:src/main/java/MyClass.java' — for source_lines, duplications")
                        humanReadable("Full SonarQube component key for the file to check.")
                        whenPresent("Used as the `component` query parameter.")
                        example("com.example:my-service:src/main/java/com/example/OrderService.java")
                    }
                    optional("branch", "string") {
                        llmSeesIt("Optional branch name — for issues, quality_gate, coverage, project_measures, source_lines, issues_paged, security_hotspots, duplications. Use project_context tool to discover current branch. For local_analysis: omit to auto-derive from current Git HEAD; protected names (main/master/develop/release/*/hotfix/*/trunk) are auto-redirected to 'local-scratch-<name>'. When include_files=true on the 'branches' action, this scopes the file-list query.")
                        humanReadable("Branch to query.")
                        whenPresent("Returns duplication data for this branch analysis.")
                        whenAbsent("Main branch.")
                        example("feature/PROJ-123")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects")
                        humanReadable("Multi-repo service selection.")
                        whenPresent("Picks the right Sonar service.")
                        whenAbsent("Primary Sonar service used.")
                    }
                }
                onSuccess("Returns duplication groups: each block has a list of fragments with file path, start line, end line. Empty blocks list means no duplications.")
                onFailure("component not found", "Empty result or 404 if component key is wrong.")
                example("find where code is duplicated") {
                    param("action", "duplications")
                    param("component_key", "com.example:my-service:src/main/java/com/example/OrderService.java")
                    outcome("Returns 2 duplication blocks: one in PaymentService.java lines 45-67, one in InvoiceService.java lines 102-124.")
                }
                verdict {
                    keep("Unique duplication data, only available here.", VerdictSeverity.NORMAL)
                }
            }

            action("project_measures") {
                description {
                    technical(
                        "Calls GET /api/measures/component with a broad metric-key list: ratings (reliability_rating, " +
                        "security_rating, sqale_rating), debt (sqale_index, sqale_debt_ratio), coverage " +
                        "(line_coverage, branch_coverage), duplication (duplicated_lines_density)."
                    )
                    plain(
                        "Fetches all headline metrics for a project in one call — like the Sonar project overview " +
                        "summary card. Broader than `coverage` (which only returns coverage metrics)."
                    )
                }
                whenLLMUses(
                    "When the user asks for an overall health summary: 'how is this project doing on Sonar overall?' " +
                    "or before a release to check all ratings at once."
                )
                params {
                    required("project_key", "string") {
                        llmSeesIt("SonarQube project key e.g. 'com.example:my-service' — for issues, quality_gate, coverage, analysis_tasks, branches, project_measures, issues_paged")
                        humanReadable("The Sonar project key.")
                        whenPresent("All measures are fetched for this component.")
                        example("com.example:my-service")
                    }
                    optional("branch", "string") {
                        llmSeesIt("Optional branch name — for issues, quality_gate, coverage, project_measures, source_lines, issues_paged, security_hotspots, duplications. Use project_context tool to discover current branch. For local_analysis: omit to auto-derive from current Git HEAD; protected names (main/master/develop/release/*/hotfix/*/trunk) are auto-redirected to 'local-scratch-<name>'. When include_files=true on the 'branches' action, this scopes the file-list query.")
                        humanReadable("Branch to query measures for.")
                        whenPresent("Returns measures for this branch analysis.")
                        whenAbsent("Main branch.")
                        example("main")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects")
                        humanReadable("Multi-repo service selection.")
                        whenPresent("Picks the right Sonar service.")
                        whenAbsent("Primary Sonar service used.")
                    }
                }
                onSuccess("Returns all configured measures as (metric_key, value, bestValue) triples — e.g. reliability_rating=A, sqale_debt_ratio=2.3%, line_coverage=72.3%.")
                onFailure("no data", "Sonar may return null for metrics that haven't been computed — common for new projects or unconfigured quality profiles.")
                example("pre-release health check") {
                    param("action", "project_measures")
                    param("project_key", "com.example:my-service")
                    outcome("Returns all ratings (A/B/C/D/E), debt ratio, coverage %, and duplication % for an at-a-glance health summary.")
                }
                verdict {
                    keep("Broad health snapshot in one call; complements quality_gate (which only shows gate conditions).", VerdictSeverity.NORMAL)
                }
            }

            action("branches") {
                description {
                    technical(
                        "Calls GET /api/project_branches/list?project=<project_key>. With include_files=true, " +
                        "also fans out a parallel GET /api/components/tree?component=<project_key>&qualifiers=FIL " +
                        "to list all files Sonar has analyzed. The file list is capped at 100 entries in the output."
                    )
                    plain(
                        "Lists the branches Sonar has analyzed for a project. With include_files=true, also " +
                        "shows every file Sonar knows about — useful to confirm a file is in scope before " +
                        "calling `source_lines` or `duplications` with a component key."
                    )
                }
                whenLLMUses(
                    "To discover which branches have been analyzed (to pick the right branch name for other actions). " +
                    "With include_files=true: before calling source_lines or duplications to verify the file exists " +
                    "in Sonar's component tree and get the exact component key."
                )
                params {
                    required("project_key", "string") {
                        llmSeesIt("SonarQube project key e.g. 'com.example:my-service' — for issues, quality_gate, coverage, analysis_tasks, branches, project_measures, issues_paged")
                        humanReadable("The Sonar project key.")
                        whenPresent("Lists branches for this project.")
                        example("com.example:my-service")
                    }
                    optional("branch", "string") {
                        llmSeesIt("Optional branch name — for issues, quality_gate, coverage, project_measures, source_lines, issues_paged, security_hotspots, duplications. Use project_context tool to discover current branch. For local_analysis: omit to auto-derive from current Git HEAD; protected names (main/master/develop/release/*/hotfix/*/trunk) are auto-redirected to 'local-scratch-<name>'. When include_files=true on the 'branches' action, this scopes the file-list query.")
                        humanReadable("When include_files=true, scopes the component-tree query to this branch.")
                        whenPresent("File list is fetched for this branch.")
                        whenAbsent("File list uses the main branch.")
                        example("feature/PROJ-123")
                    }
                    optional("include_files", "boolean") {
                        llmSeesIt("On the branches action, also include the list of files Sonar analyzed for this project. Default false. Useful before drilling into source_lines / duplications to verify the file is in Sonar's scope.")
                        humanReadable("When true, runs a second parallel call to fetch the file component tree. Output is capped at 100 files.")
                        whenPresent("Parallel async call to listFileComponents; result appended with component keys.")
                        whenAbsent("Only branch list is returned; no file enumeration.")
                        example("true")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects")
                        humanReadable("Multi-repo service selection.")
                        whenPresent("Picks the right Sonar service.")
                        whenAbsent("Primary Sonar service used.")
                    }
                }
                onSuccess("Returns branch list (name, isMain, status, lastAnalysisDate). With include_files=true, also appends a file list section with full component keys.")
                onFailure("no branches", "Project exists but has never been analyzed — Sonar returns an empty list.")
                example("discover branches then fetch file components") {
                    param("action", "branches")
                    param("project_key", "com.example:my-service")
                    param("include_files", "true")
                    param("branch", "feature/PROJ-123")
                    outcome("Returns branch list + up to 100 file component keys, confirming whether OrderService.java is in Sonar's scope and showing its exact component key.")
                }
                verdict {
                    keep("Essential discovery step before source_lines/duplications — prevents the common 'empty results because component key is wrong' failure.", VerdictSeverity.NORMAL)
                }
            }

            action("analysis_tasks") {
                description {
                    technical(
                        "Calls GET /api/ce/activity?component=<project_key>. Returns recent CE task history: " +
                        "task ID, status (SUCCESS/FAILED/CANCELED/IN_PROGRESS/PENDING), submitter, started, " +
                        "duration. Requires admin permission — returns 403 for non-admin tokens."
                    )
                    plain(
                        "Shows the analysis task history — like the 'Background Tasks' page in Sonar admin. " +
                        "Tells you when the last analysis ran, whether it succeeded, and how long it took. " +
                        "REQUIRES admin token; if the token isn't admin, use `branches` or `quality_gate` instead."
                    )
                }
                whenLLMUses(
                    "When the user asks 'when did Sonar last analyze this project?' or 'did the last analysis succeed?' " +
                    "and the user's token is known to be an admin token. DO NOT retry on 403 — surface it immediately."
                )
                params {
                    required("project_key", "string") {
                        llmSeesIt("SonarQube project key e.g. 'com.example:my-service' — for issues, quality_gate, coverage, analysis_tasks, branches, project_measures, issues_paged")
                        humanReadable("The Sonar project key.")
                        whenPresent("Lists CE tasks for this project.")
                        example("com.example:my-service")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects")
                        humanReadable("Multi-repo service selection.")
                        whenPresent("Picks the right Sonar service.")
                        whenAbsent("Primary Sonar service used.")
                    }
                }
                onSuccess("Returns a list of CE tasks with: id, status, type, component, submittedAt, startedAt, executionTimeMs.")
                onFailure("403 Forbidden", "Token lacks admin permission. Do NOT retry. Fall back to `branches` (shows lastAnalysisDate) or `quality_gate` (shows current status). Explain to the user that an admin token is required for this action.")
                example("check last analysis status") {
                    param("action", "analysis_tasks")
                    param("project_key", "com.example:my-service")
                    outcome("Returns last 5 CE tasks showing the most recent SUCCESS 3 hours ago, confirming the branch data in other actions is fresh.")
                }
                verdict {
                    keep("Useful for admin workflows, but 403 is common. The 403 guidance is the most important part of this action's documentation.", VerdictSeverity.NORMAL)
                }
            }

            action("search_projects") {
                description {
                    technical(
                        "Calls GET /api/components/search?qualifiers=TRK&q=<query>. Returns matching projects " +
                        "with their project keys."
                    )
                    plain(
                        "Searches for SonarQube projects by name or key. Like the project search bar in the Sonar UI. " +
                        "Use when you don't know the exact project_key."
                    )
                }
                whenLLMUses(
                    "When the user mentions a service by name but you don't know its Sonar project key. " +
                    "Run this first, then use the returned project_key in subsequent actions."
                )
                params {
                    required("query", "string") {
                        llmSeesIt("Search query — for search_projects")
                        humanReadable("Partial project name or key to search for.")
                        whenPresent("Passed as the `q` query parameter to the Sonar components search API.")
                        constraint("must not be blank")
                        example("payment")
                        example("com.example:my-service")
                    }
                }
                onSuccess("Returns a list of matching projects with: key, name, qualifier, lastAnalysisDate.")
                onFailure("no results", "Empty list — the query matched nothing. Try a shorter or differently spelled query.")
                example("discover project key") {
                    param("action", "search_projects")
                    param("query", "payment")
                    outcome("Returns projects matching 'payment' — e.g. 'com.example:payment-service', 'com.example:payment-gateway'.")
                }
                verdict {
                    keep("Necessary discovery tool when the project_key is unknown. Common first step.", VerdictSeverity.NORMAL)
                }
            }

            action("rule") {
                description {
                    technical(
                        "Calls GET /api/rules/show?key=<rule_key>. Returns: name, description (merged from " +
                        "descriptionSections), severity, type, remediation guidance, tags."
                    )
                    plain(
                        "Fetches the full definition of a Sonar rule — like clicking on a rule name in the Sonar UI " +
                        "to read 'why this matters' and 'how to fix it'. Use when an issue references an unfamiliar " +
                        "rule key like 'java:S1234'."
                    )
                }
                whenLLMUses(
                    "After seeing an issue with an unfamiliar rule key, before guessing what the rule means. " +
                    "The description field includes the fix guidance that the LLM can apply directly."
                )
                params {
                    required("rule_key", "string") {
                        llmSeesIt("Sonar rule key (e.g. 'java:S1234'). Required for the rule action.")
                        humanReadable("The Sonar rule key from an issue — format is 'language:ruleId', e.g. 'java:S1234' or 'kotlin:S100'.")
                        whenPresent("Used as the `key` query parameter.")
                        constraint("must not be blank")
                        example("java:S1234")
                        example("kotlin:S100")
                        example("javascript:S3776")
                    }
                    optional("repo_name", "string") {
                        llmSeesIt("Repository name for multi-repo projects")
                        humanReadable("Multi-repo service selection.")
                        whenPresent("Picks the right Sonar service.")
                        whenAbsent("Primary Sonar service used.")
                    }
                }
                onSuccess("Returns rule name, full description text (HTML stripped), severity, type, tags, and remediation function/effort.")
                onFailure("rule not found", "404 if the rule key is wrong (wrong language prefix or rule ID). Double-check the key from the issue body.")
                example("look up an unfamiliar rule before fixing") {
                    param("action", "rule")
                    param("rule_key", "java:S3749")
                    outcome("Returns the rule name ('Spring beans should be accessed through the container'), description, and fix guidance — agent can apply the pattern without guessing.")
                }
                verdict {
                    keep("Prevents the LLM from guessing rule semantics. The fix guidance is often directly actionable.", VerdictSeverity.NORMAL)
                }
            }

            action("current_user") {
                description {
                    technical(
                        "Calls GET /api/users/current. Returns the authenticated user's login, name, email, " +
                        "group memberships, and global permission flags (isAdmin, etc.)."
                    )
                    plain(
                        "Checks who the configured Sonar token belongs to and what global permissions it has. " +
                        "Like logging into Sonar and viewing 'My Account'. Use to decide whether admin-only " +
                        "actions (like `analysis_tasks`) are safe to call."
                    )
                }
                whenLLMUses(
                    "Before calling `analysis_tasks` to verify the token is admin. Also useful when the user " +
                    "asks 'which Sonar account is this plugin using?'."
                )
                params { }
                onSuccess("Returns: login, name, email, groups[], isAdmin flag, sonarLintAdSeen flag, and other global permissions.")
                onFailure("401 Unauthorized", "Token is invalid or expired. Surface to user immediately — do not retry.")
                example("check if token has admin") {
                    param("action", "current_user")
                    outcome("Returns {login: 'ci-agent', isAdmin: true, groups: ['sonar-administrators']} — confirms analysis_tasks is callable.")
                }
                verdict {
                    keep("Low-cost pre-flight check. Avoids the user receiving a confusing 403 from analysis_tasks.", VerdictSeverity.NORMAL)
                }
            }

            action("quality_gates_list") {
                description {
                    technical(
                        "Calls GET /api/qualitygates/list. Returns all configured quality gates with: " +
                        "id, name, isDefault, isBuiltIn, caycStatus, isAiCodeSupported."
                    )
                    plain(
                        "Lists all quality gate configurations — like the Quality Gates admin page in Sonar. " +
                        "Shows which gate is the default and whether it supports AI-code analysis. " +
                        "Note: `isAiCodeSupported=false` on Community Build — Sonar-side AI Code Fix is unavailable."
                    )
                }
                whenLLMUses(
                    "When the user asks 'which quality gate is applied to this project?' or wants to understand " +
                    "the caycStatus (Clean as You Code compliance) of the configured gates."
                )
                params { }
                onSuccess("Returns a list of quality gate objects with: id, name, isDefault, isBuiltIn, caycStatus (compliant/over-compliant/non-compliant), isAiCodeSupported.")
                onFailure("403 Forbidden", "Listing quality gates may require specific permissions on some Sonar versions. If 403, inform the user.")
                example("check if default gate is CAYC compliant") {
                    param("action", "quality_gates_list")
                    outcome("Returns list showing the default gate has caycStatus='compliant' — meaning new code must meet coverage and issue thresholds before it can be merged.")
                }
                verdict {
                    keep("Useful for understanding the gate landscape before PR quality decisions.", VerdictSeverity.NORMAL)
                }
            }
        }
        related("jira", Relationship.COMPLEMENT, "Jira ticket links to a PR that needs Sonar gate approval before merge.")
        related("bitbucket_pr", Relationship.COMPLEMENT, "BitbucketPR check_merge_status shows Sonar as a required build — use sonar quality_gate to understand why it's failing.")
        related("bamboo_builds", Relationship.COMPLEMENT, "Bamboo triggers the Sonar analysis; bamboo_builds shows build status and sonar shows the resulting quality data.")
        related("diagnostics", Relationship.ALTERNATIVE, "For local IDE-detected errors before pushing — diagnostics uses IntelliJ's inspections; sonar uses the server-side rule engine after analysis.")
        downside("local_analysis requires Maven or Gradle in the project root; pure sonar-scanner projects (no Maven/Gradle) are not supported.")
        downside("analysis_tasks requires an admin token; the LLM cannot know in advance whether the user's token is admin.")
        downside("The `issues` action is hard-capped at 500 results — no paging parameter. Large projects silently truncate.")
        downside("Protected branch names (main, master, develop, release/*, hotfix/*, trunk) in local_analysis are silently redirected; the LLM must read the branch-redirect note in the result to know the published branch name.")
        downside("canChangeStatus=false on security hotspots means the agent cannot mark hotspots reviewed via API — the only remediation path requires code changes and CI re-analysis.")
        observation("CLAUDE.md documents 13 actions but source has 18: issue_facets, current_user, quality_gates_list, hotspot_detail, and rule are all present in the when-block and enum list but were undercounted. Update CLAUDE.md.")
        mergeOpportunity("`issues` and `issues_paged` could be unified into one action with an optional `page` param — the current two-action split is a minor schema-budget inefficiency since `issues_paged` is strictly more general.")
        observation("local_analysis is the most complex action (350+ lines): branch resolution, Maven multi-module scoping, ProcessBuilder security, CE-task polling, parallel per-file fan-out, inline rule/hotspot caches, and range-collapse output formatting.")
        verdict {
            keep(
                "The most comprehensive external-service integration in the plugin. `branch_quality_report` and `local_analysis` " +
                "provide capabilities (consolidated new-code report, local scanner pipeline) that no other tool can replicate. " +
                "18 actions spanning the full Sonar surface area justify the single-tool consolidation.",
                VerdictSeverity.STRONG
            )
        }
    }

    override val description = """
SonarQube code quality — issues, coverage, quality gates, analysis, security hotspots.

Actions and their parameters:
- issues(project_key, file?, branch?, new_code_only?) → Code issues (optionally filter by file path; set new_code_only=true to see only issues in new code period). Returns up to 500 issues — for full coverage on large projects use issues_paged. On Sonar 9.6+ each issue carries `impacts[]` (per-software-quality severity in RELIABILITY/SECURITY/MAINTAINABILITY) and `cleanCodeAttribute`/`cleanCodeAttributeCategory` — use these for prioritization beyond legacy `severity`/`type` (e.g. RELIABILITY/HIGH outranks MAINTAINABILITY/LOW even when both are MAJOR).
- quality_gate(project_key, branch?) → Quality gate status (includes both overall and new code conditions; on Sonar 25.x also carries `caycStatus` ∈ compliant/over-compliant/non-compliant for "Clean as You Code" gate compliance)
- coverage(project_key, branch?) → **Overall** code coverage metrics (line %, branch %, covered/total lines). This returns the full project coverage, NOT new code coverage. For new code coverage, use branch_quality_report instead.
- search_projects(query) → Search SonarQube projects
- analysis_tasks(project_key) → Recent analysis task status. **Requires admin permission** — returns 403 for non-admin tokens; do not retry on 403, ask the user to use an admin token or fall back to `branches`/`quality_gate` for the same data without admin.
- branches(project_key, include_files?) → Analyzed branches. When include_files=true, also lists all files Sonar analyzed for this project (parallel fetch) — use this before calling source_lines / duplications to verify the file is in Sonar's scope.
- rule(rule_key) → Rule details (name, description, remediation, tags) for a Sonar rule key such as 'java:S1234'. Use when an issue references an unfamiliar rule to get fix guidance instead of guessing.
- project_measures(project_key, branch?) → All project metrics (ratings, debt, overall coverage, duplication)
- source_lines(component_key, from?, to?, branch?) → Source code with per-line coverage status (from/to are line numbers). Each line includes `isNew` (true when in the new-code period — pair with `new_code_only=true` to target only PR-introduced lines), `lineHits` (statement coverage; 0 = uncovered), `conditions` + `coveredConditions` (per-line branch coverage — when conditions > 0 and coveredConditions < conditions, the line has an uncovered branch the agent can target with a test).
- issues_paged(project_key, page?, page_size?, branch?, new_code_only?) → Paginated issues (default page 1, 100/page, max 500; set new_code_only=true for new code only)
- security_hotspots(project_key, branch?) → List of security hotspots (location + severity only). For full risk + fix guidance per hotspot, follow up with `hotspot_detail`.
- hotspot_detail(hotspot_key) → **Full hotspot detail** — rule.riskDescription, rule.vulnerabilityDescription, rule.fixRecommendations (HTML; the latter contains a literal "Compliant Solution" code example you can show as the "good pattern"). **CRITICAL CAVEAT:** the response carries `canChangeStatus`. If false, the active token CANNOT mark the hotspot fixed/safe via the API — remediation flow is: edit code → push → wait for re-analysis. **Do NOT promise the user you can autonomously close hotspots when canChangeStatus=false**.
- issue_facets(project_key, branch?, new_code_only?, facets) → Faceted issue counts in one round trip. `facets` is comma-separated, no spaces. Valid 25.x values: severities, types, tags, impactSoftwareQualities, impactSeverities, cleanCodeAttributeCategories, assignees, files, rules, statuses, resolutions, author, directories, scopes, languages, codeVariants, issueStatuses, prioritizedRule, createdAt, sonarsourceSecurity, plus security compliance facets (pciDss-3.2, pciDss-4.0, owaspAsvs-4.0, owaspMobileTop10-2024, stig-ASD_V5R3, casa, sansTop25, cwe). Use `files` (NOT `fileUuids`). Use BEFORE walking the issue list to decide priority order.
- current_user → Authenticated user identity + global permissions (login, name, email, groups, isAdmin). Use to decide whether to surface admin-only hints.
- quality_gates_list → Catalog of all configured quality gates with caycStatus + isAiCodeSupported. **Note:** SonarQube AI Code Fix is not available on Community Build (`isAiCodeSupported=false`). Use the agent's own LLM path for autonomous fixes; don't promise Sonar-side AI Code Fix.
- duplications(component_key, branch?) → Code duplications
- branch_quality_report(project_key, branch, max_files?) → **Consolidated new-code quality report** — one call gets: new-code quality gate conditions, new-code issues (bugs/smells/vulnerabilities), security hotspots, new-code coverage (line %, branch %, uncovered lines/conditions, duplication density), plus per-file drill-down with exact uncovered line numbers, uncovered branch line numbers, and duplicated line ranges. Default max_files=20. **Use this for new code / branch quality analysis** instead of calling issues+quality_gate+coverage+hotspots separately.
- local_analysis(files, branch?, timeout?, new_code_only?, min_severity?) → **Run SonarQube analysis locally on specific files** using Maven/Gradle Sonar plugin, then return fresh results (issues, hotspots, coverage, duplications) for those files. Use this after refactoring to get immediate Sonar feedback without waiting for the CI pipeline. Requires Maven (pom.xml) or Gradle (build.gradle/settings.gradle) and SonarQube connection configured. timeout default 300s. **branch is auto-derived** from the current Git HEAD when omitted. Protected names (main/master/develop/release/*/hotfix/*/trunk) are automatically redirected to `local-scratch-<name>` so your local run never overwrites the real branch's Sonar dashboard. Pass branch explicitly only when you want to publish under a specific non-protected name. **For tight self-correction loops on a feature branch**, pass `new_code_only=true` to surface only issues your edits introduced (typically reduces output 100×); pass `min_severity="MAJOR"` to drop noise.

Common optional: repo_name for multi-repo projects.
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf(
                    "issues", "quality_gate", "coverage", "search_projects",
                    "analysis_tasks", "branches", "project_measures", "source_lines", "issues_paged",
                    "security_hotspots", "hotspot_detail", "issue_facets",
                    "current_user", "quality_gates_list",
                    "duplications", "branch_quality_report", "local_analysis", "rule"
                )
            ),
            "project_key" to ParameterProperty(
                type = "string",
                description = "SonarQube project key e.g. 'com.example:my-service' — for issues, quality_gate, coverage, analysis_tasks, branches, project_measures, issues_paged"
            ),
            "component_key" to ParameterProperty(
                type = "string",
                description = "SonarQube component key e.g. 'com.example:my-service:src/main/java/MyClass.java' — for source_lines, duplications"
            ),
            "query" to ParameterProperty(
                type = "string",
                description = "Search query — for search_projects"
            ),
            "file" to ParameterProperty(
                type = "string",
                description = "Optional relative file path filter — for issues"
            ),
            "branch" to ParameterProperty(
                type = "string",
                description = "Optional branch name — for issues, quality_gate, coverage, project_measures, source_lines, issues_paged, security_hotspots, duplications. Use project_context tool to discover current branch. For local_analysis: omit to auto-derive from current Git HEAD; protected names (main/master/develop/release/*/hotfix/*/trunk) are auto-redirected to 'local-scratch-<name>'. When include_files=true on the 'branches' action, this scopes the file-list query."
            ),
            "from" to ParameterProperty(
                type = "string",
                description = "Start line number — for source_lines"
            ),
            "to" to ParameterProperty(
                type = "string",
                description = "End line number — for source_lines"
            ),
            "page" to ParameterProperty(
                type = "string",
                description = "Page number (default 1) — for issues_paged"
            ),
            "page_size" to ParameterProperty(
                type = "string",
                description = "Results per page max 500 (default 100) — for issues_paged"
            ),
            "new_code_only" to ParameterProperty(
                type = "boolean",
                description = "When true, return only issues introduced in the new code period (since branch point or configured baseline) — for issues, issues_paged, local_analysis"
            ),
            "max_files" to ParameterProperty(
                type = "string",
                description = "Max files to drill down into for line-level details (default 20) — for branch_quality_report"
            ),
            "files" to ParameterProperty(
                type = "string",
                description = "Comma-separated file paths to analyse (relative to project root or absolute) — for local_analysis. E.g. 'src/main/java/com/example/OrderService.java,src/main/java/com/example/PaymentService.java'"
            ),
            "hotspot_key" to ParameterProperty(
                type = "string",
                description = "Hotspot UUID — for hotspot_detail. Discover via security_hotspots first."
            ),
            "facets" to ParameterProperty(
                type = "string",
                description = "Comma-separated facet names — for issue_facets. Valid 25.x: severities, types, tags, impactSoftwareQualities, impactSeverities, cleanCodeAttributeCategories, assignees, files, rules, statuses, resolutions, author, directories, scopes, languages, codeVariants, issueStatuses, prioritizedRule, createdAt, sonarsourceSecurity, plus pciDss-3.2/4.0, owaspAsvs-4.0, owaspMobileTop10-2024, stig-ASD_V5R3, casa, sansTop25, cwe."
            ),
            "timeout" to ParameterProperty(
                type = "integer",
                description = "Seconds before the scanner process is killed (default 300, max 900) — for local_analysis"
            ),
            "min_severity" to ParameterProperty(
                type = "string",
                description = "Minimum issue severity to surface in output (BLOCKER | CRITICAL | MAJOR | MINOR | INFO). Lower-severity issues are dropped. Default: INFO (no filter) — for local_analysis"
            ),
            "rule_key" to ParameterProperty(
                type = "string",
                description = "Sonar rule key (e.g. 'java:S1234'). Required for the rule action."
            ),
            "include_files" to ParameterProperty(
                type = "boolean",
                description = "On the branches action, also include the list of files Sonar analyzed for this project. Default false. Useful before drilling into source_lines / duplications to verify the file is in Sonar's scope."
            ),
            "repo_name" to ParameterProperty(
                type = "string",
                description = "Repository name for multi-repo projects"
            )
        ),
        required = listOf("action")
    )

    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ANALYZER, WorkerType.REVIEWER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        coroutineContext.ensureActive()
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'action' parameter required",
                "Error: missing action",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val service = ServiceLookup.sonar(project)
            ?: return ServiceLookup.notConfigured("SonarQube")

        return when (action) {
            "issues" -> {
                val projectKey = params["project_key"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("project_key")
                val file = params["file"]?.jsonPrimitive?.content
                val branch = params["branch"]?.jsonPrimitive?.content
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                val newCodeOnly = try { params["new_code_only"]?.jsonPrimitive?.boolean } catch (_: Exception) { null } ?: false
                service.getIssues(projectKey, file, branch = branch, repoName = repoName, inNewCodePeriod = newCodeOnly).toAgentToolResult()
            }

            "quality_gate" -> {
                val projectKey = params["project_key"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("project_key")
                ToolValidation.validateNotBlank(projectKey, "project_key")?.let { return it }
                val branch = params["branch"]?.jsonPrimitive?.content
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getQualityGateStatus(projectKey, branch = branch, repoName = repoName).toAgentToolResult()
            }

            "coverage" -> {
                val projectKey = params["project_key"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("project_key")
                ToolValidation.validateNotBlank(projectKey, "project_key")?.let { return it }
                val branch = params["branch"]?.jsonPrimitive?.content
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getCoverage(projectKey, branch = branch, repoName = repoName).toAgentToolResult()
            }

            "search_projects" -> {
                val query = params["query"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("query")
                ToolValidation.validateNotBlank(query, "query")?.let { return it }
                service.searchProjects(query).toAgentToolResult()
            }

            "analysis_tasks" -> {
                val projectKey = params["project_key"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("project_key")
                ToolValidation.validateNotBlank(projectKey, "project_key")?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getAnalysisTasks(projectKey, repoName = repoName).toAgentToolResult()
            }

            "branches" -> {
                val projectKey = params["project_key"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("project_key")
                val branch = params["branch"]?.jsonPrimitive?.content
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                val includeFiles = params["include_files"]?.jsonPrimitive?.content?.lowercase() == "true"
                executeGetBranchesForTest(projectKey, branch, repoName, includeFiles, service)
            }

            "project_measures" -> {
                val projectKey = params["project_key"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("project_key")
                val branch = params["branch"]?.jsonPrimitive?.content
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getProjectMeasures(projectKey, branch, repoName = repoName).toAgentToolResult()
            }

            "source_lines" -> {
                val componentKey = params["component_key"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("component_key")
                val from = params["from"]?.jsonPrimitive?.content?.toIntOrNull()
                val to = params["to"]?.jsonPrimitive?.content?.toIntOrNull()
                val branch = params["branch"]?.jsonPrimitive?.content
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getSourceLines(componentKey, from, to, branch = branch, repoName = repoName).toAgentToolResult()
            }

            "issues_paged" -> {
                val projectKey = params["project_key"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("project_key")
                val page = params["page"]?.jsonPrimitive?.content?.toIntOrNull() ?: 1
                val pageSize = params["page_size"]?.jsonPrimitive?.content?.toIntOrNull() ?: 100
                val branch = params["branch"]?.jsonPrimitive?.content
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                val newCodeOnly = try { params["new_code_only"]?.jsonPrimitive?.boolean } catch (_: Exception) { null } ?: false
                service.getIssuesPaged(projectKey, page, pageSize, branch = branch, repoName = repoName, inNewCodePeriod = newCodeOnly).toAgentToolResult()
            }

            "security_hotspots" -> {
                val projectKey = params["project_key"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("project_key")
                val branch = params["branch"]?.jsonPrimitive?.content
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getSecurityHotspots(projectKey, branch = branch, repoName = repoName).toAgentToolResult()
            }

            "hotspot_detail" -> {
                val hotspotKey = params["hotspot_key"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("hotspot_key")
                ToolValidation.validateNotBlank(hotspotKey, "hotspot_key")?.let { return it }
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getHotspotDetail(hotspotKey, repoName = repoName).toAgentToolResult()
            }

            "issue_facets" -> {
                val projectKey = params["project_key"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("project_key")
                val facets = params["facets"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("facets")
                ToolValidation.validateNotBlank(projectKey, "project_key")?.let { return it }
                ToolValidation.validateNotBlank(facets, "facets")?.let { return it }
                val branch = params["branch"]?.jsonPrimitive?.content
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                val newCodeOnly = try { params["new_code_only"]?.jsonPrimitive?.boolean } catch (_: Exception) { null } ?: false
                service.getIssueFacets(projectKey, branch = branch, inNewCodePeriod = newCodeOnly, facets = facets, repoName = repoName).toAgentToolResult()
            }

            "current_user" -> service.getCurrentUser().toAgentToolResult()

            "quality_gates_list" -> service.listQualityGates().toAgentToolResult()

            "duplications" -> {
                val componentKey = params["component_key"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("component_key")
                val branch = params["branch"]?.jsonPrimitive?.content
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getDuplications(componentKey, branch = branch, repoName = repoName).toAgentToolResult()
            }

            "branch_quality_report" -> {
                val projectKey = params["project_key"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("project_key")
                val branch = params["branch"]?.jsonPrimitive?.content ?: return ToolValidation.missingParam("branch")
                ToolValidation.validateNotBlank(projectKey, "project_key")?.let { return it }
                ToolValidation.validateNotBlank(branch, "branch")?.let { return it }
                val maxFiles = params["max_files"]?.jsonPrimitive?.content?.toIntOrNull() ?: 20
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getBranchQualityReport(projectKey, branch, maxFiles, repoName).toAgentToolResult()
            }

            "rule" -> {
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                executeRuleForTest(params["rule_key"]?.jsonPrimitive?.content, repoName, service)
            }

            "local_analysis" -> {
                val filesParam = params["files"]?.jsonPrimitive?.content
                    ?: return ToolValidation.missingParam("files")
                val userBranch = params["branch"]?.jsonPrimitive?.contentOrNull
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                val timeoutSeconds = (params["timeout"]?.jsonPrimitive?.content?.toLongOrNull() ?: 300L)
                    .coerceIn(30L, 900L)
                val newCodeOnly = try { params["new_code_only"]?.jsonPrimitive?.boolean } catch (_: Exception) { null } ?: false
                val minSeverity = params["min_severity"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                if (minSeverity != null && !IssueSeverity.isValid(minSeverity)) {
                    return ToolResult(
                        "Invalid min_severity '$minSeverity'. Valid: BLOCKER, CRITICAL, MAJOR, MINOR, INFO.",
                        "Invalid min_severity", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                    )
                }
                executeLocalAnalysis(params, project, filesParam, userBranch, repoName, timeoutSeconds, newCodeOnly, minSeverity)
            }

            else -> ToolResult(
                content = "Unknown action '$action'. See tool description for valid actions.",
                summary = "Unknown action '$action'",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // rule — fetch rule details (gap #4)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Exposed internal for testing — exercises the `rule` action logic without
     * needing a live IntelliJ Project / ServiceLookup (mirrors JiraTool pattern).
     */
    internal suspend fun executeRuleForTest(
        ruleKey: String?,
        repoName: String?,
        service: com.workflow.orchestrator.core.services.SonarService
    ): ToolResult {
        if (ruleKey.isNullOrBlank()) return ToolValidation.missingParam("rule_key")
        return service.getRule(ruleKey, repoName).toAgentToolResult()
    }

    // ══════════════════════════════════════════════════════════════════════
    // branches — with optional include_files fan-out (gap #7)
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Exposed internal for testing — exercises the `branches` action logic
     * (with/without include_files) without needing a live IntelliJ Project
     * (mirrors BambooBuildsTool / JiraTool pattern).
     */
    internal suspend fun executeGetBranchesForTest(
        projectKey: String,
        branch: String?,
        repoName: String?,
        includeFiles: Boolean,
        service: com.workflow.orchestrator.core.services.SonarService
    ): ToolResult {
        if (!includeFiles) {
            return service.getBranches(projectKey, repoName = repoName).toAgentToolResult()
        }
        return coroutineScope {
            val branchesDeferred = async { service.getBranches(projectKey, repoName = repoName) }
            val filesDeferred = async { service.listFileComponents(projectKey, branch, repoName) }
            val branchesResult = branchesDeferred.await()
            val filesResult = filesDeferred.await()
            if (branchesResult.isError) return@coroutineScope branchesResult.toAgentToolResult()
            val branchesAgent = branchesResult.toAgentToolResult()
            val filesBlock = formatFileComponents(filesResult.data)
            val combined = branchesAgent.content + "\n\n" + filesBlock
            ToolResult(combined, "${branchesAgent.summary} · ${filesResult.summary}", TokenEstimator.estimate(combined))
        }
    }

    /**
     * Format a list of [SonarFileComponent] into a compact text block.
     * Capped at 100 files to avoid context overflow — the LLM should use
     * source_lines / duplications on specific files after confirming they appear here.
     */
    private fun formatFileComponents(files: List<SonarFileComponent>?): String {
        if (files.isNullOrEmpty()) return "Files: (none analyzed)"
        val cap = 100
        val lines = files.take(cap).map { "  • ${it.path}" }
        val tail = if (files.size > cap) "\n  …(${files.size - cap} more)" else ""
        return "Files (showing ${minOf(cap, files.size)} of ${files.size}):\n" + lines.joinToString("\n") + tail
    }

    // ══════════════════════════════════════════════════════════════════════
    // local_analysis — run sonar-scanner locally, poll CE task, fetch results
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun executeLocalAnalysis(
        params: JsonObject,
        project: Project,
        filesParam: String,
        userBranch: String?,
        repoName: String?,
        timeoutSeconds: Long,
        newCodeOnly: Boolean = false,
        minSeverity: String? = null
    ): ToolResult {
        // Resolve the effective branch: user-supplied takes precedence, else auto-derive from
        // current Git HEAD. Protected names (main/master/develop/release/*/hotfix/*/trunk)
        // are redirected to a scratch namespace so a local run never pollutes the real branch's
        // Sonar dashboard. Multi-module: RepoContextResolver picks the current-editor's repo
        // first, falling back to the primary — correct for both mono-repo-multi-module and
        // multi-repo IntelliJ projects.
        val branchResolution = resolveLocalAnalysisBranch(project, userBranch)
        val branch: String? = when (branchResolution) {
            is BranchResolution.Use -> branchResolution.branch
            is BranchResolution.Omit -> null
        }
        // ── 1. Gather config ───────────────────────────────────────────────
        val settings = PluginSettings.getInstance(project)
        val sonarUrl = settings.connections.sonarUrl.trimEnd('/')
        val projectKeyParam = params["project_key"]?.jsonPrimitive?.contentOrNull
        val projectKey: String = when {
            !projectKeyParam.isNullOrBlank() -> projectKeyParam!!
            settings.state.sonarProjectKey?.isNotBlank() == true -> settings.state.sonarProjectKey!!
            else -> return ToolResult(
                "SonarQube project key not configured. Pass project_key parameter or set it in Settings > Workflow Orchestrator > CI/CD.",
                "Missing project_key", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }
        val token = CredentialStore().getToken(ServiceType.SONARQUBE)

        if (sonarUrl.isBlank()) return ToolResult(
            "SonarQube URL not configured. Set it in Settings > Workflow Orchestrator > Connections.",
            "Missing sonarUrl", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
        )
        if (token.isNullOrBlank()) return ToolResult(
            "SonarQube token not configured. Set it in Settings > Workflow Orchestrator > Connections.",
            "Missing sonar token", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
        )

        // ── 1b. Pre-flight connection check ────────────────────────────────
        // Fail fast on bad URL / token in milliseconds instead of after a
        // full Maven/Gradle compile + scanner upload.
        run {
            val sonarService = ServiceLookup.sonar(project) ?: return ServiceLookup.notConfigured("SonarQube")
            val testResult = sonarService.testConnection()
            if (testResult.isError) {
                return ToolResult(
                    "Pre-flight Sonar connection check failed: ${testResult.summary}. " +
                        "Verify URL and token in Settings > Workflow Orchestrator > Connections before retrying.",
                    "Pre-flight failed: ${testResult.summary}",
                    ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                )
            }
        }

        // ── 2. Resolve file paths to relative paths from project root ──────
        val basePath = project.basePath ?: return ToolResult(
            "Cannot determine project base path.", "No basePath", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
        )
        val files = filesParam.split(",").map { it.trim() }.filter { it.isNotBlank() }
        // Repo-root-relative paths are kept for Sonar API lookups (listFileComponents, getIssues,
        // getSourceLines) which always key components by project-root-relative paths.
        val relativePaths = files.map { f ->
            val norm = f.replace("\\", "/")
            if (norm.startsWith(basePath.replace("\\", "/"))) norm.removePrefix(basePath.replace("\\", "/")).trimStart('/') else norm
        }

        // ── 3. Detect build tool ───────────────────────────────────────────
        val baseDir = File(basePath)
        val hasMaven = File(baseDir, "pom.xml").exists()
        val hasGradle = File(baseDir, "build.gradle").exists() ||
            File(baseDir, "build.gradle.kts").exists() ||
            File(baseDir, "settings.gradle").exists() ||
            File(baseDir, "settings.gradle.kts").exists()
        if (!hasMaven && !hasGradle) return ToolResult(
            "No Maven (pom.xml) or Gradle (build.gradle) build file found in project root. Cannot run sonar analysis.",
            "No build tool", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
        )

        // ── 3b. Multi-module scoping ───────────────────────────────────────
        // For Maven aggregator projects where the parent pom is local-only (not in Sonar),
        // running `mvn sonar:sonar` from the parent produces a reactor where child modules
        // SKIP and the parent FAILS (because its projectKey doesn't exist in Sonar). Scope
        // the run to the child module(s) that actually own the selected files.
        val mavenScope: MavenScope? = if (hasMaven) resolveMavenScope(project, basePath, files) else null
        val workingDir = mavenScope?.workingDir ?: baseDir
        // Sonar inclusions must be relative to the scanner's working directory.
        val scannerInclusions = (mavenScope?.relativePathsFromWorkingDir ?: relativePaths).joinToString(",")

        val buildTool = if (hasMaven) "Maven" else "Gradle"
        val projectsFlag = mavenScope?.projectsFlag

        // ── 3c. Validate branch name before any process spawn ─────────────
        val branchValidation = validateBranchName(branch)
        if (branchValidation.isError) return branchValidation

        // ── 4. Execute scanner, stream output, capture CE task ID ──────────
        val startTime = System.currentTimeMillis()
        val outputBuilder = StringBuilder()
        var ceTaskId: String? = null

        // TODO(backlog): Use IntelliJ's MavenRunner/ExternalSystemUtil instead of ProcessBuilder
        //   so that Settings > Build Tools > Maven/Gradle (custom home, JVM args, settings.xml) are respected.
        val pb = buildScannerProcess(buildTool, sonarUrl, token, projectsFlag, scannerInclusions, branch, workingDir)
        pb.redirectErrorStream(true)

        // Streaming pipe: tool-call correlation is set by AgentLoop via STREAMING_TOOLS.
        // When the ID is null (direct invocation in a test, or missing wiring), heartbeats
        // and scanner stdout simply drop — the final ToolResult still carries everything.
        val toolCallId = RunCommandTool.currentToolCallId.get()
        val streamCb = RunCommandTool.streamCallback
        val emit: (String) -> Unit = { line ->
            if (toolCallId != null) streamCb?.invoke(toolCallId, "$line\n")
        }

        emit("▶ Running $buildTool sonar scan on ${files.size} file(s) (timeout ${timeoutSeconds}s, branch: ${branch ?: "(project default)"})")
        if (mavenScope != null && mavenScope.moduleNames.isNotEmpty()) {
            when {
                mavenScope.projectsFlag != null ->
                    emit("  Scope: modules [${mavenScope.moduleNames.joinToString(", ")}] via -pl -am (reactor root=${baseDir.name})")
                workingDir != baseDir ->
                    emit("  Scope: module '${mavenScope.moduleNames.first()}' (running from ${workingDir.path.removePrefix("$basePath/")})")
                else ->
                    emit("  Scope: reactor root (${baseDir.name})")
            }
        }
        emit("  Inclusions: $scannerInclusions")

        val process = pb.start()
        val readerError = AtomicReference<Throwable?>(null)
        val reader = Thread {
            try {
                process.inputStream.bufferedReader().use { br ->
                    var line = br.readLine()
                    while (line != null) {
                        if (outputBuilder.length < MAX_SCANNER_OUTPUT_CHARS) outputBuilder.appendLine(line)
                        // Extract CE task ID from: "INFO: More about the report processing at .../api/ce/task?id=XXXXX"
                        if (ceTaskId == null) {
                            CeTaskIdParser.extract(line)?.let { ceTaskId = it }
                        }
                        emit(line)
                        line = br.readLine()
                    }
                }
            } catch (t: Throwable) {
                readerError.set(t)
            }
        }.apply { isDaemon = true; name = "sonar-scanner-reader"; start() }

        val completed = try {
            runInterruptible(Dispatchers.IO) {
                process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            emit("✗ Scanner cancelled by caller — killing process")
            process.destroyForcibly()
            reader.join(1000)
            throw ce
        }
        if (!completed) {
            emit("✗ Scanner timed out after ${timeoutSeconds}s — killing process")
            process.destroyForcibly()
            reader.join(1000)
            return ToolResult(
                "[TIMEOUT] Sonar analysis timed out after ${timeoutSeconds}s for files: $scannerInclusions",
                "Analysis timeout", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }
        reader.join(3000)
        readerError.get()?.let {
            emit("⚠ Scanner stdout reader threw ${it.javaClass.simpleName}: ${it.message ?: "(no message)"} — output may be incomplete")
        }
        val elapsedS = (System.currentTimeMillis() - startTime) / 1000.0

        if (process.exitValue() != 0) {
            emit("✗ Scanner exited with code ${process.exitValue()} after ${String.format("%.1f", elapsedS)}s")
            val tail = outputBuilder.toString().lines().takeLast(30).joinToString("\n")
            return ToolResult(
                "Sonar analysis FAILED (exit ${process.exitValue()}) after ${String.format("%.1f", elapsedS)}s.\n\n$tail",
                "Analysis failed (exit ${process.exitValue()})", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        emit("✓ Scanner finished in ${String.format("%.1f", elapsedS)}s (exit ${process.exitValue()})")

        // ── 5. Wait for CE task to finish processing on server ─────────────
        val sonarService = ServiceLookup.sonar(project) ?: return ServiceLookup.notConfigured("SonarQube")

        if (ceTaskId != null) {
            emit("⏳ Waiting for SonarQube server to process report (CE task: $ceTaskId, polling every ${CE_POLL_INTERVAL_MS / 1000}s, max ${CE_POLL_TIMEOUT_MS / 1000}s)")
            val pollDeadline = System.currentTimeMillis() + CE_POLL_TIMEOUT_MS
            var taskStatus = "PENDING"
            var permissionDenied = false
            while (taskStatus in setOf("PENDING", "IN_PROGRESS") && System.currentTimeMillis() < pollDeadline) {
                delay(CE_POLL_INTERVAL_MS)
                // Wrap each poll in retry-with-backoff so a brief 5xx / network blip
                // doesn't drop the loop. retryWhile returns false on FORBIDDEN so we
                // fail fast on permission errors instead of waiting through 3 attempts.
                val statusResult = SonarRetry.withBackoff(
                    maxAttempts = 3,
                    initialDelayMs = 2_000,
                    retryWhile = { r -> !isForbidden(r.summary) }
                ) { sonarService.getCeTaskStatus(ceTaskId!!) }
                if (!statusResult.isError) {
                    val newStatus = statusResult.data ?: "UNKNOWN"
                    if (newStatus != taskStatus) emit("  CE task $ceTaskId: $newStatus")
                    taskStatus = newStatus
                } else if (isForbidden(statusResult.summary)) {
                    permissionDenied = true
                    emit("⚠ CE task polling forbidden — token lacks 'Execute Analysis' or 'Administer System' permission. " +
                        "Falling back to fixed wait. Per-file results below may be from a prior analysis if this run hasn't finished server-side.")
                    break
                } else {
                    emit("  CE task poll error after retries: ${statusResult.summary} — continuing with what we have")
                    break
                }
            }
            if (permissionDenied) {
                // Wait the fallback duration so the server has a chance to finish processing
                // before we hit the per-file endpoints. Not a guarantee, but better than
                // racing the report.
                delay(CE_FALLBACK_WAIT_MS)
            } else if (taskStatus == "FAILED" || taskStatus == "CANCELED") return ToolResult(
                "Sonar analysis submitted successfully but server processing $taskStatus (CE task: $ceTaskId).",
                "CE task $taskStatus", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
            if (!permissionDenied) emit("✓ Server processing complete (status: $taskStatus)")
        } else {
            // Scanner didn't emit a CE task URL (older versions or proxy strips it) — wait briefly
            emit("⏳ Scanner did not emit a CE task ID; waiting ${CE_FALLBACK_WAIT_MS / 1000}s for report propagation")
            delay(CE_FALLBACK_WAIT_MS)
        }

        emit("→ Resolving component keys for ${files.size} file(s) and fetching per-file results...")

        // ── 6. Resolve authoritative component keys for each requested file ─
        // Multi-module Maven/Gradle projects key source files as `projectKey:moduleName:pathWithinModule`,
        // which is NOT predictable from the repo-relative path alone. Query component_tree per unique
        // projectKey (child modules can each have their OWN sonar.projectKey) and merge so
        // getSourceLines/getDuplications hit the right components instead of silently returning empty.
        //
        // Per-file projectKey resolution:
        //  - Prefer the owning Maven module's sonar.projectKey (from MavenProject properties,
        //    fallback groupId:artifactId).
        //  - Fall back to the user-supplied `project_key` param (or settings) for any file we
        //    couldn't map to a module.
        val perFileProjectKeyMap: Map<String, String> = mavenScope?.perFileProjectKey ?: emptyMap()
        val resolvedProjectKeyFor: (String) -> String = { relPath ->
            perFileProjectKeyMap[relPath] ?: projectKey
        }
        val uniqueProjectKeys: Set<String> = (relativePaths.map(resolvedProjectKeyFor)).toSet()
        if (uniqueProjectKeys.size > 1) {
            emit("  Per-file Sonar projectKeys: ${uniqueProjectKeys.joinToString(", ")}")
        }

        // Fetch component lists per unique projectKey in parallel, merge into a single list.
        val componentFetches = coroutineScope {
            uniqueProjectKeys.map { pk ->
                async { pk to sonarService.listFileComponents(pk, branch = branch, repoName = repoName) }
            }.map { it.await() }
        }
        val discoveredComponents = componentFetches.flatMap { (_, result) ->
            if (!result.isError) result.data ?: emptyList() else emptyList()
        }
        val failedComponentFetches = componentFetches.filter { (_, result) -> result.isError }
        val componentResolutionWarning = if (failedComponentFetches.isNotEmpty()) {
            "Note: could not resolve component keys for ${failedComponentFetches.size} projectKey(s) " +
                "(${failedComponentFetches.joinToString { (pk, r) -> "$pk: ${r.summary}" }}). " +
                "Falling back to inferred keys — per-file results may be empty."
        } else null

        // ── 7. Fetch per-file results in parallel ─────────────────────────
        // Per-scan rule description cache. Keyed by rule key. A scan typically
        // hits <50 unique rules even when 700+ issues are reported, so each
        // rule fetch happens at most once.
        //
        // Thread-safety: this and [hotspotCache] below are plain mutableMapOf
        // (not ConcurrentHashMap). Safe today because all cache reads happen
        // sequentially — the per-file coroutineScope's four async{}/await()
        // calls complete before fetchRuleHowToFix / fetchHotspotDetail run,
        // and the outer for-loop iterates files sequentially. If the per-file
        // block is ever parallelised across files, switch to ConcurrentHashMap
        // (with sentinel handling for nullable values, since CHM rejects nulls).
        val ruleCache = mutableMapOf<String, String?>()
        suspend fun fetchRuleHowToFix(ruleKey: String): String? {
            return ruleCache.getOrPut(ruleKey) {
                val r = sonarService.getRule(ruleKey, repoName)
                if (r.isError) null else r.data?.description?.takeIf { it.isNotBlank() }
            }
        }

        // Per-scan hotspot detail cache. Keyed by hotspot key. Same fan-out
        // logic as ruleCache: the same hotspot won't be reported twice in
        // one scan, but caching defends against per-file getSecurityHotspots
        // re-emitting the same hotspot under different component keys.
        val hotspotCache = mutableMapOf<String, com.workflow.orchestrator.core.model.sonar.HotspotDetailData?>()
        suspend fun fetchHotspotDetail(hotspotKey: String): com.workflow.orchestrator.core.model.sonar.HotspotDetailData? {
            return hotspotCache.getOrPut(hotspotKey) {
                val r = sonarService.getHotspotDetail(hotspotKey, repoName)
                if (r.isError) null else r.data
            }
        }

        val sb = StringBuilder()
        sb.appendLine("Local Sonar Analysis Complete")
        val branchLabel = branch ?: "(project default)"
        sb.appendLine("Runner: $buildTool | Files: ${files.size} | Duration: ${String.format("%.1f", elapsedS)}s | Branch: $branchLabel")
        val filterParts = buildList {
            if (newCodeOnly) add("new_code_only=true")
            if (!minSeverity.isNullOrBlank()) add("min_severity=$minSeverity")
        }
        if (filterParts.isNotEmpty()) sb.appendLine("Filters: ${filterParts.joinToString(", ")}")
        sb.appendLine(branchResolution.note)
        if (uniqueProjectKeys.size > 1) {
            sb.appendLine("ProjectKeys (per module): ${uniqueProjectKeys.joinToString(", ")}")
        }
        if (componentResolutionWarning != null) sb.appendLine(componentResolutionWarning)
        if (ceTaskId != null) sb.appendLine("CE Task: $ceTaskId")
        sb.appendLine()

        for (relativePath in relativePaths) {
            val fileProjectKey = resolvedProjectKeyFor(relativePath)
            val resolution = resolveComponentKey(relativePath, discoveredComponents, fileProjectKey)
            val componentKey = resolution.key
            sb.appendLine("${"═".repeat(60)}")
            sb.appendLine(relativePath)
            if (uniqueProjectKeys.size > 1) sb.appendLine("  (projectKey: $fileProjectKey)")
            if (resolution.note != null) sb.appendLine("  (${resolution.note})")
            sb.appendLine()

            coroutineScope {
                val issuesD   = async { sonarService.getIssues(fileProjectKey, filePath = relativePath, branch = branch, repoName = repoName, inNewCodePeriod = newCodeOnly) }
                val sourceD   = async { sonarService.getSourceLines(componentKey, branch = branch, repoName = repoName) }
                val hotspotsD = async { sonarService.getSecurityHotspots(fileProjectKey, branch = branch, repoName = repoName) }
                val dupsD     = async { sonarService.getDuplications(componentKey, branch = branch, repoName = repoName) }

                val issuesResult   = issuesD.await()
                val sourceResult   = sourceD.await()
                val hotspotsResult = hotspotsD.await()
                val dupsResult     = dupsD.await()

                // Issues
                val rawIssueList = if (!issuesResult.isError) issuesResult.data ?: emptyList() else emptyList()
                val issueList = rawIssueList.filter { IssueSeverity.meetsMinSeverity(it.severity, minSeverity) }
                val droppedCount = rawIssueList.size - issueList.size
                if (issueList.isEmpty() && droppedCount == 0) {
                    sb.appendLine("Issues: ✓ None")
                } else if (issueList.isEmpty()) {
                    sb.appendLine("Issues: ✓ None at or above $minSeverity ($droppedCount below threshold dropped)")
                } else {
                    val header = "Issues (${issueList.size})" + (if (droppedCount > 0) " — $droppedCount below $minSeverity dropped" else "")
                    sb.appendLine("$header:")
                    val bySeverity = IssueSeverity.DISPLAY_ORDER
                    for (sev in bySeverity) {
                        val group = issueList.filter { it.severity == sev }
                        if (group.isEmpty()) continue
                        for (issue in group) {
                            val loc = issue.line?.let { "line $it" } ?: "file level"
                            sb.appendLine("  [$sev][${issue.type}] $loc — ${issue.message.take(120)}")
                            sb.appendLine("    Rule: ${issue.rule}")
                            // Inline rule "How to fix" body (cached per scan).
                            // Delegates to stripAndTrim so HTML-tag stripping, entity
                            // unescaping, whitespace collapse, and length cap (600 chars)
                            // all live in one helper. The LLM can call
                            // sonar(action="rule", rule_key=...) for the full text.
                            fetchRuleHowToFix(issue.rule)?.let { desc ->
                                sb.appendLine("    How to fix: ${stripAndTrim(desc, 600)}")
                            }
                        }
                    }
                }

                // Security hotspots for this file: prefer exact match on resolved component key
                // (authoritative, works for multi-module where component keys carry module prefix);
                // fall back to path-suffix match on the hotspot's component string.
                val fileHotspots = if (!hotspotsResult.isError) {
                    val all = hotspotsResult.data ?: emptyList()
                    val exact = all.filter { it.component == componentKey }
                    if (exact.isNotEmpty()) exact
                    else all.filter { it.component.endsWith("/$relativePath") || it.component.endsWith(":$relativePath") }
                } else emptyList()
                if (fileHotspots.isNotEmpty()) {
                    sb.appendLine()
                    sb.appendLine("Security Hotspots (${fileHotspots.size}):")
                    for (hs in fileHotspots) {
                        val loc = hs.line?.let { "line $it" } ?: "file level"
                        sb.appendLine("  [${hs.probability}][${hs.status}] $loc — ${hs.message.take(120)}")
                        // Inline detail (cached per scan): vulnerability + fix recommendations.
                        // Each description block can be 1-3 KB raw HTML on the probed Sonar 25.x
                        // sample, so we strip HTML tags and cap each at 400 chars.
                        fetchHotspotDetail(hs.key)?.let { detail ->
                            sb.appendLine("    Category: ${detail.securityCategory} | Probability: ${detail.vulnerabilityProbability}")
                            sb.appendLine("    Risk: ${stripAndTrim(detail.riskDescription, 400)}")
                            sb.appendLine("    Vulnerability: ${stripAndTrim(detail.vulnerabilityDescription, 400)}")
                            sb.appendLine("    Fix: ${stripAndTrim(detail.fixRecommendations, 600)}")
                            if (!detail.canChangeStatus) {
                                sb.appendLine("    (canChangeStatus=false: agent cannot mark hotspot fixed via API; remediation flow is edit code → push → re-analysis.)")
                            }
                        }
                    }
                }

                // Coverage from source lines
                val lineList = if (!sourceResult.isError) sourceResult.data ?: emptyList() else emptyList()
                if (lineList.isNotEmpty()) {
                    sb.appendLine()
                    val coverable = lineList.count { it.coverageStatus != null }
                    val covered   = lineList.count { it.coverageStatus == "covered" }
                    val uncoveredNums = lineList
                        .filter { it.coverageStatus == "uncovered" || it.coverageStatus == "partially-covered" }
                        .map { it.line }
                    if (coverable > 0) {
                        val pct = covered.toDouble() / coverable * 100
                        sb.appendLine("Coverage: ${String.format("%.1f", pct)}% ($covered/$coverable lines covered)")
                        if (uncoveredNums.isNotEmpty()) {
                            val ranges = collapseRanges(uncoveredNums)
                            val rangeStr = ranges.joinToString(", ") { if (it.first == it.second) "${it.first}" else "${it.first}–${it.second}" }
                            sb.appendLine("  Uncovered/partial: $rangeStr")
                        }
                    } else {
                        sb.appendLine("Coverage: N/A (no coverable lines)")
                    }
                } else if (sourceResult.isError) {
                    sb.appendLine()
                    sb.appendLine("Coverage: unavailable (${sourceResult.summary})")
                }

                // Duplications
                val dupData = if (!dupsResult.isError) dupsResult.data else null
                sb.appendLine()
                if (dupData == null || dupData.blocks.isEmpty()) {
                    sb.appendLine("Duplications: none")
                } else {
                    sb.appendLine("Duplications (${dupData.blocks.size} group(s)):")
                    for (block in dupData.blocks.take(5)) {
                        val fragments = block.fragments.joinToString(" ↔ ") {
                            "${it.file.substringAfterLast('/')}:${it.startLine}–${it.endLine}"
                        }
                        sb.appendLine("  $fragments")
                    }
                }
            }
            sb.appendLine()
        }

        val content = sb.toString().trimEnd()
        return ToolResult(content, "Local Sonar analysis complete: ${files.size} file(s)", content.length / 4)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Maven multi-module scoping for local_analysis
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Scope decision for a Maven sonar:sonar run.
     *
     *  - [workingDir]: directory the scanner process is spawned in. When all files belong to
     *    a single child module this is the module's directory (so `mvn sonar:sonar` runs only
     *    that module's POM and never touches the aggregator parent).
     *  - [relativePathsFromWorkingDir]: the input files expressed relative to [workingDir],
     *    used to build `-Dsonar.inclusions=` for the scanner.
     *  - [moduleNames]: artifactId(s) of the owning Maven module(s) — for logging/emit lines.
     *  - [projectsFlag]: value for `-pl` when the run must stay at the reactor root (files span
     *    multiple modules). Null when [workingDir] is already a single module dir.
     *  - [perFileProjectKey]: `repoRelativePath → sonar.projectKey` map. One entry per requested
     *    file, keyed by the repo-root-relative path used for API lookups. Empty when detection
     *    failed or Maven plugin isn't imported. Consumer falls back to the user-supplied
     *    `project_key` param for any file missing from the map.
     */
    internal data class MavenScope(
        val workingDir: File,
        val relativePathsFromWorkingDir: List<String>,
        val moduleNames: List<String>,
        val projectsFlag: String?,
        val perFileProjectKey: Map<String, String> = emptyMap()
    )

    /**
     * Decide where to spawn `mvn sonar:sonar` for multi-module Maven projects.
     *
     * The bug this avoids: when the IntelliJ project is opened at an aggregator pom that is
     * local-only (not in Sonar/Bitbucket), running `mvn sonar:sonar` from the project base
     * produces a reactor where every child module is SKIPPED and the parent FAILS (its
     * projectKey doesn't exist in Sonar). Scoping the scanner to the child module that owns
     * the file(s) side-steps the parent entirely.
     *
     * Strategy:
     *   1. Resolve each input file (absolute or repo-root-relative) to a VirtualFile.
     *   2. Detect owning modules via [MavenModuleDetector] (Maven API → nearest-pom fallback).
     *   3. If all files belong to one module AND we can locate its directory via the Maven
     *      projects manager, run the scanner from that directory — no `-pl` needed.
     *   4. If files span multiple modules, run from the reactor root with `-pl mod1,mod2 -am`
     *      so Maven builds only the named modules plus their dependencies.
     *   5. If detection fails entirely, return null — caller falls back to reactor-root
     *      behavior (preserves pre-fix behavior for single-module projects).
     */
    internal fun resolveMavenScope(
        project: Project,
        basePath: String,
        files: List<String>
    ): MavenScope? {
        val baseDir = File(basePath)
        val baseNorm = basePath.replace("\\", "/")
        val absolutePaths = files.map { f ->
            val norm = f.replace("\\", "/")
            if (norm.startsWith("/") || Regex("^[A-Za-z]:/").containsMatchIn(norm)) norm
            else "$baseNorm/${norm.trimStart('/')}"
        }

        val vfs = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
        val vFiles = absolutePaths.mapNotNull { vfs.findFileByPath(it) }
        if (vFiles.isEmpty()) return null

        val mavenManager: org.jetbrains.idea.maven.project.MavenProjectsManager? = try {
            org.jetbrains.idea.maven.project.MavenProjectsManager.getInstance(project)
                .takeIf { it.isMavenizedProject }
        } catch (_: Exception) { null }

        // Maven projects API is the authoritative source for module → directory mapping.
        // If the plugin isn't imported yet, bail — the caller falls back to reactor-root
        // behavior (pre-fix default) rather than risk a wrong guess.
        if (mavenManager == null) return null
        val mavenProjects: List<org.jetbrains.idea.maven.project.MavenProject> = mavenManager.projects
        if (mavenProjects.isEmpty()) return null

        // Pair each input file with its owning MavenProject so we can later look up
        // that module's own sonar.projectKey (for the post-scan API calls).
        val vFileToOwner: List<Pair<com.intellij.openapi.vfs.VirtualFile, org.jetbrains.idea.maven.project.MavenProject>> = vFiles.mapNotNull { vf ->
            val owner = mavenProjects
                .filter { com.intellij.openapi.vfs.VfsUtilCore.isAncestor(it.directoryFile, vf, false) }
                .maxByOrNull { it.directoryFile.path.length }
            if (owner != null) vf to owner else null
        }
        if (vFileToOwner.isEmpty()) return null
        val perFileOwner: List<org.jetbrains.idea.maven.project.MavenProject> = vFileToOwner.map { it.second }
        val distinctOwners = perFileOwner.distinctBy { it.directoryFile.path }
        val owningDirs: List<File> = distinctOwners.map { File(it.directoryFile.path) }
        val moduleNames: List<String> = distinctOwners.mapNotNull { it.mavenId.artifactId }

        // Build `repoRelativePath → sonar.projectKey` for every input file. Each module's
        // sonar.projectKey comes from its effective Maven properties (which already merge
        // parent POM properties). Sonar-maven-plugin's documented fallback when the property
        // is unset is `groupId:artifactId`, so we mirror that.
        val perFileProjectKey: Map<String, String> = vFileToOwner.mapNotNull { (vf, owner) ->
            val absPath = vf.path
            val repoRel = when {
                absPath.startsWith("$baseNorm/") -> absPath.removePrefix("$baseNorm/")
                absPath == baseNorm -> "."
                else -> return@mapNotNull null
            }
            val key = owner.properties.getProperty("sonar.projectKey")
                ?: owner.mavenId.let { id ->
                    val g = id.groupId
                    val a = id.artifactId
                    if (!g.isNullOrBlank() && !a.isNullOrBlank()) "$g:$a" else null
                }
                ?: return@mapNotNull null
            repoRel to key
        }.toMap()

        return when {
            owningDirs.size == 1 && owningDirs.first().path != baseDir.path -> {
                // Single child module — cd into it, drop the reactor entirely.
                val moduleDir = owningDirs.first()
                val modNorm = moduleDir.path.replace("\\", "/")
                val relFromModule = absolutePaths.map { abs ->
                    if (abs.startsWith("$modNorm/")) abs.removePrefix("$modNorm/")
                    else if (abs == modNorm) "."
                    else abs
                }
                MavenScope(moduleDir, relFromModule, moduleNames, projectsFlag = null, perFileProjectKey = perFileProjectKey)
            }
            owningDirs.size == 1 -> {
                // File is in the reactor root itself (single-module project, or aggregator
                // that IS also a code module). Preserve prior behavior: run from baseDir.
                val relFromBase = absolutePaths.map { abs ->
                    if (abs.startsWith("$baseNorm/")) abs.removePrefix("$baseNorm/") else abs
                }
                MavenScope(baseDir, relFromBase, moduleNames, projectsFlag = null, perFileProjectKey = perFileProjectKey)
            }
            else -> {
                // Files span multiple modules — stay at reactor root, use -pl to narrow.
                // `-am` pulls in inter-module dependencies so compile order is correct.
                val relFromBase = absolutePaths.map { abs ->
                    if (abs.startsWith("$baseNorm/")) abs.removePrefix("$baseNorm/") else abs
                }
                MavenScope(baseDir, relFromBase, moduleNames, projectsFlag = moduleNames.joinToString(","), perFileProjectKey = perFileProjectKey)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Component-key resolution for local_analysis
    // ══════════════════════════════════════════════════════════════════════

    internal data class ResolvedComponent(val key: String, val note: String?)

    /**
     * Map a user-supplied repo-relative path to the authoritative SonarQube component key.
     *
     * Multi-module Maven/Gradle projects key files as `projectKey:moduleName:pathWithinModule`
     * (not `projectKey:repoRelativePath`), so naive concatenation silently returns empty
     * results from per-file endpoints. Strategy (first hit wins):
     *
     *  1. Exact path match on SonarQube's reported `path`.
     *  2. User path ends in `/component.path` — user passed a repo-rooted path but Sonar
     *     stored it under a module-shortened path.
     *  3. Component path ends in `/userPath` — user passed a module-relative path but Sonar
     *     stored it under a repo-prefixed path. Longest match wins to avoid ambiguity.
     *  4. Unique basename match — last-resort fallback used only when the filename is
     *     unique across the whole project, to avoid cross-module collisions.
     *  5. Legacy `projectKey:relativePath` concatenation (preserves single-module behavior
     *     when component_tree lookup failed or returned nothing).
     *
     * Returns the chosen key plus an optional human-readable note for the tool output when
     * we had to fall back to a non-authoritative strategy — so the LLM can see the mismatch.
     */
    internal fun resolveComponentKey(
        relativePath: String,
        components: List<com.workflow.orchestrator.core.model.sonar.SonarFileComponent>,
        projectKey: String
    ): ResolvedComponent {
        if (components.isEmpty()) {
            return ResolvedComponent("$projectKey:$relativePath", note = null)
        }

        // 1. Exact match
        components.firstOrNull { it.path == relativePath }
            ?.let { return ResolvedComponent(it.key, null) }

        // 2. User path ends with component path
        components
            .filter { relativePath.endsWith("/${it.path}") }
            .maxByOrNull { it.path.length }
            ?.let { return ResolvedComponent(it.key, "matched by module-relative path '${it.path}'") }

        // 3. Component path ends with user path
        components
            .filter { it.path.endsWith("/$relativePath") }
            .maxByOrNull { it.path.length }
            ?.let { return ResolvedComponent(it.key, "matched by repo-relative suffix '${it.path}'") }

        // 4. Unique basename
        val basename = relativePath.substringAfterLast('/')
        val byName = components.filter { it.name == basename }
        if (byName.size == 1) {
            val m = byName[0]
            return ResolvedComponent(m.key, "matched by unique basename '$basename' at '${m.path}'")
        }
        if (byName.size > 1) {
            return ResolvedComponent(
                "$projectKey:$relativePath",
                note = "ambiguous: ${byName.size} files named '$basename' across modules — " +
                    "pass a more specific path to disambiguate"
            )
        }

        // 5. Legacy fallback
        return ResolvedComponent(
            "$projectKey:$relativePath",
            note = "no SonarQube component matches '$relativePath' — per-file results may be empty"
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    // Branch resolution for local_analysis
    // ══════════════════════════════════════════════════════════════════════

    internal sealed class BranchResolution {
        abstract val note: String
        // Publish under this specific branch (emits -Dsonar.branch.name=<branch>).
        data class Use(val branch: String, override val note: String) : BranchResolution()
        // No branch specified and none auto-detectable — omit the flag entirely.
        // Preserves SonarQube Community Edition compatibility (which rejects the branch flag).
        data class Omit(override val note: String) : BranchResolution()
    }

    internal fun resolveLocalAnalysisBranch(project: Project, userProvided: String?): BranchResolution {
        val userBranch = userProvided?.trim()?.takeIf { it.isNotBlank() }
        // Branch resolution chain (repo-resolution sweep, item 8):
        //   A. file-arg's repo  — SKIPPED today: `local_analysis` accepts a `files` CSV but
        //      derives the project root via Maven/Gradle multi-module scoping, not a single
        //      path. Wiring per-file repo lookup here is tracked for future work.
        //   B. focusPr.fromBranch — the user's anchored "current task" branch. Authoritative
        //      when set; the agent should report Sonar issues for the PR being worked on,
        //      not whichever submodule the editor file happens to live in.
        //   C. RepoContextResolver.resolveCurrentEditorRepoOrPrimary — fresh editor/primary
        //      lookup as last resort. Editor-derived; only consulted when no PR is focused.
        val focusedPrBranch = runCatching {
            WorkflowContextService.getInstance(project).state.value.focusPr?.fromBranch
        }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
        val editorOrPrimaryBranch = runCatching {
            // editor-fallback-allowed: Sonar branch resolution case C — agent context for
            // "what is the user looking at", consulted only when no PR is focused.
            RepoContextResolver.getInstance(project).resolveCurrentEditorRepoOrPrimary()?.currentBranchName
        }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
        val currentGitBranch = focusedPrBranch ?: editorOrPrimaryBranch

        val candidate = userBranch ?: currentGitBranch

        return when {
            candidate == null -> BranchResolution.Omit(
                "No branch supplied and no Git branch detected — omitting -Dsonar.branch.name (Community Edition safe)."
            )
            isProtectedBranch(candidate) -> {
                val scratch = toScratchBranch(candidate)
                val source = if (userBranch != null) "Requested" else "Current Git"
                BranchResolution.Use(
                    scratch,
                    "$source branch '$candidate' is a protected name (main/master/develop/release/hotfix/trunk). " +
                        "Publishing to '$scratch' instead to avoid overwriting $candidate's Sonar dashboard."
                )
            }
            userBranch == null -> BranchResolution.Use(
                candidate,
                "Auto-derived branch from current Git HEAD: '$candidate'."
            )
            else -> BranchResolution.Use(
                candidate,
                "Using supplied branch: '$candidate'."
            )
        }
    }

    private fun isProtectedBranch(name: String): Boolean {
        val lower = name.lowercase()
        if (lower in PROTECTED_EXACT) return true
        return PROTECTED_PREFIXES.any { lower.startsWith(it) }
    }

    /**
     * Produce a Sonar-safe scratch branch name from an original branch name.
     * Replaces any non-alphanumeric/dash/dot/underscore character with '-', collapses
     * repeats, trims separators, and caps length. Empty result falls back to 'unknown'.
     */
    private fun toScratchBranch(original: String): String {
        val sanitized = original
            .replace(Regex("[^A-Za-z0-9._-]"), "-")
            .replace(Regex("-+"), "-")
            .trim('-', '.')
            .take(40)
            .ifBlank { "unknown" }
        return "local-scratch-$sanitized"
    }

    // ══════════════════════════════════════════════════════════════════════
    // Security helpers — T2 (HIGH): token off argv + branch-name validation
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Validate a Sonar branch name against the allowed-character allowlist.
     *
     * Allowed: `[a-zA-Z0-9._\-/]+`
     * Rejected: any character outside that set (semicolons, backticks, `$`, `&`, `|`, spaces, …).
     *
     * Null and blank are both treated as "omit the branch flag" — non-error so the caller can
     * skip adding `-Dsonar.branch.name` without additional null-checks.
     *
     * Threat model: even though we use the argv `ProcessBuilder` form (which prevents shell
     * metacharacter interpretation in the OS), validation is applied as defense-in-depth so
     * the LLM cannot accidentally pass a branch name that confuses the Sonar scanner CLI parser
     * itself (e.g. a value like `foo -Dsonar.token=stolen`).
     */
    fun validateBranchName(branch: String?): ToolResult {
        if (branch.isNullOrBlank()) {
            return ToolResult("OK", "branch validation: omitted", 1, isError = false)
        }
        return if (BRANCH_NAME_REGEX.matches(branch)) {
            ToolResult("OK", "branch validation: passed", 1, isError = false)
        } else {
            ToolResult(
                "INVALID_BRANCH_NAME: '$branch' contains characters not allowed in a SonarQube branch name. " +
                    "Allowed: alphanumerics, '.', '_', '-', '/'. " +
                    "Found disallowed character(s). Shell metacharacters (';', '`', '\$', '&', '|', space) are never permitted.",
                "Invalid branch name",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    /**
     * Build a [ProcessBuilder] for a local Sonar scanner invocation.
     *
     * Security guarantees (T2):
     *  1. **Token NOT in argv**: `token` is placed in `SONAR_TOKEN` env var (SonarQube scanner
     *     reads it natively as of v4.x). The process argument list never includes
     *     `-Dsonar.token=<value>` — so `ps aux` output can never leak the credential.
     *  2. **No shell wrapper**: the returned ProcessBuilder uses a discrete argv list, never
     *     `["sh", "-c", "..."]` or `["cmd.exe", "/c", "..."]`. Shell metacharacter injection
     *     via `branch`, `sonarUrl`, or `scannerInclusions` is structurally impossible.
     *  3. **Wrapper preference**: if a project-local `mvnw` / `gradlew` is executable,
     *     its absolute path is used (same pattern as JavaRuntimeExecTool, commit 8d297ef4).
     *     This avoids PATH ambiguity and respects the project's pinned build-tool version.
     *
     * @param buildTool    "Maven" or "Gradle" (case-insensitive first letter).
     * @param sonarUrl     SonarQube server URL (trailing slash must already be trimmed by caller).
     * @param token        Sonar authentication token — placed in env, NOT in argv.
     * @param projectsFlag Maven `-pl` value (e.g. `"module-a,module-b"`) or null. When non-null,
     *                     two discrete argv elements `-pl <value>` are inserted. Gradle projects
     *                     do not use this flag.
     * @param scannerInclusions Value for `-Dsonar.inclusions=` (comma-separated paths).
     * @param branch       Branch name (already validated by [validateBranchName]), or null to omit.
     * @param workingDir   Directory the scanner process should run in.
     */
    fun buildScannerProcess(
        buildTool: String,
        sonarUrl: String,
        token: String,
        projectsFlag: String?,
        scannerInclusions: String,
        branch: String?,
        workingDir: java.io.File
    ): ProcessBuilder {
        val isMaven = buildTool.startsWith("M", ignoreCase = true)

        val argv: MutableList<String> = if (isMaven) {
            // Resolve wrapper → absolute path; fall back to PATH executable
            // (`mvn.cmd` on Windows, `mvn` on Unix). See BuildToolExecutableResolver.
            val mvnExec = BuildToolExecutableResolver.resolveMaven(workingDir)
            mutableListOf(mvnExec, "sonar:sonar").apply {
                if (projectsFlag != null) {
                    add("-pl")
                    add(projectsFlag)
                    add("-am")
                }
                add("-Dsonar.host.url=$sonarUrl")
                add("-Dsonar.inclusions=$scannerInclusions")
            }
        } else {
            // Resolve wrapper → absolute path; fall back to PATH executable
            // (`gradle.bat` on Windows, `gradle` on Unix). See BuildToolExecutableResolver.
            val gradleExec = BuildToolExecutableResolver.resolveGradle(workingDir)
            mutableListOf(gradleExec, "sonar", "-Dsonar.host.url=$sonarUrl", "-Dsonar.inclusions=$scannerInclusions")
        }

        // Branch flag: only appended when non-null/non-blank (caller has already validated).
        if (!branch.isNullOrBlank()) {
            argv.add("-Dsonar.branch.name=$branch")
        }

        val pb = ProcessBuilder(argv)
        pb.directory(workingDir)
        // Token via environment variable — never appears in ps aux output.
        pb.environment()["SONAR_TOKEN"] = token
        return pb
    }

    /**
     * Heuristic match for FORBIDDEN-class ToolResult.summary strings. The
     * core ToolResult shape doesn't carry an ErrorType enum, so we
     * substring-match on the audit-confirmed Sonar 403 phrasing — the
     * `SonarApiClient` 403 path emits `"Insufficient SonarQube permissions"`,
     * so `"insufficient"` is the most specific marker. Earlier drafts also
     * matched `"permission"` and `"forbidden"` but those substrings can
     * appear in unrelated network/proxy errors.
     */
    private fun isForbidden(summary: String): Boolean {
        return "insufficient" in summary.lowercase()
    }

    /**
     * Strips HTML tags, unescapes HTML entities, collapses whitespace, and
     * caps length. Used for inline rendering of Sonar's HTML description
     * fields (riskDescription, vulnerabilityDescription, fixRecommendations)
     * which are 1-3 KB raw but must fit in agent context.
     *
     * Also used for plain-text fields (e.g. SonarRuleData.description after
     * the descriptionSections merge) — strip and unescape are no-ops on
     * tag-free content, so the function is safe for both inputs.
     */
    private fun stripAndTrim(html: String, maxLen: Int): String {
        if (html.isBlank()) return "(none)"
        val stripped = html.replace(Regex("<[^>]+>"), " ")
        val unescaped = HtmlEscape.unescapeHtml(stripped)
        val text = unescaped.replace(Regex("\\s+"), " ").trim()
        return if (text.length > maxLen) text.take(maxLen) + "…" else text
    }

    /** Collapse a sorted list of line numbers into [first, last] pairs for compact display. */
    private fun collapseRanges(lines: List<Int>): List<Pair<Int, Int>> {
        if (lines.isEmpty()) return emptyList()
        val sorted = lines.sorted()
        val result = mutableListOf<Pair<Int, Int>>()
        var start = sorted[0]; var end = sorted[0]
        for (i in 1 until sorted.size) {
            if (sorted[i] == end + 1) end = sorted[i]
            else { result.add(start to end); start = sorted[i]; end = sorted[i] }
        }
        result.add(start to end)
        return result
    }

    companion object {
        private const val CE_POLL_INTERVAL_MS = 3_000L
        private const val CE_POLL_TIMEOUT_MS  = 120_000L   // 2 min max wait for CE processing
        private const val CE_FALLBACK_WAIT_MS = 5_000L     // wait when CE task ID not found in output
        private const val MAX_SCANNER_OUTPUT_CHARS = 50_000

        /**
         * Allowlist regex for SonarQube branch names (T2 security fix).
         * Permits alphanumerics, '.', '_', '-', '/'. Rejects all shell metacharacters.
         */
        internal val BRANCH_NAME_REGEX = Regex("^[a-zA-Z0-9._\\-/]+\$")

        // Branch names that must never be overwritten by a local scan.
        // Exact match, case-insensitive.
        private val PROTECTED_EXACT = setOf(
            "main", "master", "develop", "development", "dev", "trunk", "default"
        )
        // Prefix match, case-insensitive. Covers release/*, hotfix/*, etc.
        private val PROTECTED_PREFIXES = listOf(
            "release/", "releases/", "hotfix/", "hotfixes/"
        )
    }
}

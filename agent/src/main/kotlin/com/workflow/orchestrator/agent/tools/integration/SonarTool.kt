package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.builtin.RunCommandTool
import com.workflow.orchestrator.agent.tools.integration.sonar.CeTaskIdParser
import com.workflow.orchestrator.agent.tools.integration.sonar.SonarRetry
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.sonar.SonarFileComponent
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.settings.RepoContextResolver
import com.workflow.orchestrator.core.util.BuildToolExecutableResolver
import com.workflow.orchestrator.core.workflow.WorkflowContextService
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
- local_analysis(files, branch?, timeout?) → **Run SonarQube analysis locally on specific files** using Maven/Gradle Sonar plugin, then return fresh results (issues, hotspots, coverage, duplications) for those files. Use this after refactoring to get immediate Sonar feedback without waiting for the CI pipeline. Requires Maven (pom.xml) or Gradle (build.gradle) and SonarQube connection configured. timeout default 300s. **branch is auto-derived** from the current Git HEAD when omitted. Protected names (main/master/develop/release/*/hotfix/*/trunk) are automatically redirected to `local-scratch-<name>` so your local run never overwrites the real branch's Sonar dashboard. Pass branch explicitly only when you want to publish under a specific non-protected name.

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
                description = "When true, return only issues introduced in the new code period (since branch point or configured baseline) — for issues, issues_paged"
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
                executeLocalAnalysis(params, project, filesParam, userBranch, repoName, timeoutSeconds)
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
        timeoutSeconds: Long
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
            runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
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
                // doesn't drop the loop. shouldRetry returns false on FORBIDDEN so we
                // fail fast on permission errors instead of waiting through 3 attempts.
                val statusResult = SonarRetry.withBackoff(
                    maxAttempts = 3,
                    initialDelayMs = 2_000,
                    shouldRetry = { r -> !isForbidden(r.summary) }
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
        val sb = StringBuilder()
        sb.appendLine("Local Sonar Analysis Complete")
        val branchLabel = branch ?: "(project default)"
        sb.appendLine("Runner: $buildTool | Files: ${files.size} | Duration: ${String.format("%.1f", elapsedS)}s | Branch: $branchLabel")
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
                val issuesD   = async { sonarService.getIssues(fileProjectKey, filePath = relativePath, branch = branch, repoName = repoName) }
                val sourceD   = async { sonarService.getSourceLines(componentKey, branch = branch, repoName = repoName) }
                val hotspotsD = async { sonarService.getSecurityHotspots(fileProjectKey, branch = branch, repoName = repoName) }
                val dupsD     = async { sonarService.getDuplications(componentKey, branch = branch, repoName = repoName) }

                val issuesResult   = issuesD.await()
                val sourceResult   = sourceD.await()
                val hotspotsResult = hotspotsD.await()
                val dupsResult     = dupsD.await()

                // Issues
                val issueList = if (!issuesResult.isError) issuesResult.data ?: emptyList() else emptyList()
                if (issueList.isEmpty()) {
                    sb.appendLine("Issues: ✓ None")
                } else {
                    sb.appendLine("Issues (${issueList.size}):")
                    val bySeverity = listOf("BLOCKER", "CRITICAL", "MAJOR", "MINOR", "INFO")
                    for (sev in bySeverity) {
                        val group = issueList.filter { it.severity == sev }
                        if (group.isEmpty()) continue
                        for (issue in group) {
                            val loc = issue.line?.let { "line $it" } ?: "file level"
                            sb.appendLine("  [$sev][${issue.type}] $loc — ${issue.message.take(120)}")
                            sb.appendLine("    Rule: ${issue.rule}")
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
     * substring-match on three known phrasings used across the SonarService
     * implementations: "forbidden", "permission", "insufficient".
     */
    private fun isForbidden(summary: String): Boolean {
        val lower = summary.lowercase()
        return "forbidden" in lower || "permission" in lower || "insufficient" in lower
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

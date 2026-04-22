package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.builtin.RunCommandTool
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.core.settings.RepoContextResolver
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/**
 * Consolidated SonarQube meta-tool (13 actions).
 *
 * Saves token budget per API call by collapsing all SonarQube operations into
 * a single tool definition with an `action` discriminator parameter.
 *
 * Actions: issues, quality_gate, coverage, search_projects, analysis_tasks,
 *          branches, project_measures, source_lines, issues_paged,
 *          security_hotspots, duplications, branch_quality_report,
 *          local_analysis
 */
class SonarTool : AgentTool {

    override val name = "sonar"

    override val description = """
SonarQube code quality — issues, coverage, quality gates, analysis, security hotspots.

Actions and their parameters:
- issues(project_key, file?, branch?, new_code_only?) → Code issues (optionally filter by file path; set new_code_only=true to see only issues in new code period)
- quality_gate(project_key, branch?) → Quality gate status (includes both overall and new code conditions)
- coverage(project_key, branch?) → **Overall** code coverage metrics (line %, branch %, covered/total lines). This returns the full project coverage, NOT new code coverage. For new code coverage, use branch_quality_report instead.
- search_projects(query) → Search SonarQube projects
- analysis_tasks(project_key) → Recent analysis task status
- branches(project_key) → Analyzed branches
- project_measures(project_key, branch?) → All project metrics (ratings, debt, overall coverage, duplication)
- source_lines(component_key, from?, to?, branch?) → Source code with per-line coverage status (from/to are line numbers)
- issues_paged(project_key, page?, page_size?, branch?, new_code_only?) → Paginated issues (default page 1, 100/page, max 500; set new_code_only=true for new code only)
- security_hotspots(project_key, branch?) → Security hotspots
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
                    "security_hotspots", "duplications", "branch_quality_report", "local_analysis"
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
                description = "Optional branch name — for issues, quality_gate, coverage, project_measures, source_lines, issues_paged, security_hotspots, duplications. Use project_context tool to discover current branch. For local_analysis: omit to auto-derive from current Git HEAD; protected names (main/master/develop/release/*/hotfix/*/trunk) are auto-redirected to 'local-scratch-<name>'."
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
            "timeout" to ParameterProperty(
                type = "integer",
                description = "Seconds before the scanner process is killed (default 300, max 900) — for local_analysis"
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
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                service.getBranches(projectKey, repoName = repoName).toAgentToolResult()
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
        val relativePaths = files.map { f ->
            val norm = f.replace("\\", "/")
            if (norm.startsWith(basePath.replace("\\", "/"))) norm.removePrefix(basePath.replace("\\", "/")).trimStart('/') else norm
        }
        val inclusions = relativePaths.joinToString(",")

        // ── 3. Detect build tool ───────────────────────────────────────────
        val baseDir = File(basePath)
        val hasMaven = File(baseDir, "pom.xml").exists()
        val hasGradle = File(baseDir, "build.gradle").exists() || File(baseDir, "build.gradle.kts").exists()
        if (!hasMaven && !hasGradle) return ToolResult(
            "No Maven (pom.xml) or Gradle (build.gradle) build file found in project root. Cannot run sonar analysis.",
            "No build tool", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
        )

        val buildTool = if (hasMaven) "Maven" else "Gradle"
        val branchFlag = branch?.let { " -Dsonar.branch.name=$it" } ?: ""
        // sonar.token is the current standard (SonarQube 10+); sonar.login still accepted on older versions
        val command = when {
            // TODO(backlog): Use IntelliJ's MavenRunner/ExternalSystemUtil instead of ProcessBuilder
            //   so that Settings > Build Tools > Maven/Gradle (custom home, JVM args, settings.xml) are respected.
            hasMaven -> "mvn sonar:sonar -Dsonar.host.url=$sonarUrl -Dsonar.token=$token -Dsonar.inclusions=$inclusions$branchFlag"
            else -> {
                val gradle = if (File(baseDir, "gradlew").exists()) "./gradlew" else "gradle"
                "$gradle sonar -Dsonar.host.url=$sonarUrl -Dsonar.token=$token -Dsonar.inclusions=$inclusions$branchFlag"
            }
        }

        // ── 4. Execute scanner, stream output, capture CE task ID ──────────
        val startTime = System.currentTimeMillis()
        val outputBuilder = StringBuilder()
        var ceTaskId: String? = null

        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val pb = if (isWindows) ProcessBuilder("cmd.exe", "/c", command)
        else ProcessBuilder("sh", "-c", command)
        pb.directory(baseDir).redirectErrorStream(true)

        // Streaming pipe: tool-call correlation is set by AgentLoop via STREAMING_TOOLS.
        // When the ID is null (direct invocation in a test, or missing wiring), heartbeats
        // and scanner stdout simply drop — the final ToolResult still carries everything.
        val toolCallId = RunCommandTool.currentToolCallId.get()
        val streamCb = RunCommandTool.streamCallback
        val emit: (String) -> Unit = { line ->
            if (toolCallId != null) streamCb?.invoke(toolCallId, "$line\n")
        }

        emit("▶ Running $buildTool sonar scan on ${files.size} file(s) (timeout ${timeoutSeconds}s, branch: ${branch ?: "(project default)"})")
        emit("  Inclusions: $inclusions")

        val process = pb.start()
        val reader = Thread {
            process.inputStream.bufferedReader().use { br ->
                var line = br.readLine()
                while (line != null) {
                    if (outputBuilder.length < MAX_SCANNER_OUTPUT_CHARS) outputBuilder.appendLine(line)
                    // Extract CE task ID from: "INFO: More about the report processing at .../api/ce/task?id=XXXXX"
                    if (ceTaskId == null && line.contains("api/ce/task?id=")) {
                        ceTaskId = line.substringAfter("api/ce/task?id=").substringBefore(" ").trim()
                    }
                    emit(line)
                    line = br.readLine()
                }
            }
        }.apply { isDaemon = true; name = "sonar-scanner-reader"; start() }

        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) {
            emit("✗ Scanner timed out after ${timeoutSeconds}s — killing process")
            process.destroyForcibly()
            reader.join(1000)
            return ToolResult(
                "[TIMEOUT] Sonar analysis timed out after ${timeoutSeconds}s for files: $inclusions",
                "Analysis timeout", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }
        reader.join(3000)
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
            while (taskStatus in setOf("PENDING", "IN_PROGRESS") && System.currentTimeMillis() < pollDeadline) {
                delay(CE_POLL_INTERVAL_MS)
                val statusResult = sonarService.getCeTaskStatus(ceTaskId!!)
                if (!statusResult.isError) {
                    val newStatus = statusResult.data ?: "UNKNOWN"
                    if (newStatus != taskStatus) emit("  CE task $ceTaskId: $newStatus")
                    taskStatus = newStatus
                } else {
                    emit("  CE task poll error: ${statusResult.summary} — continuing with what we have")
                    break
                }
            }
            if (taskStatus == "FAILED" || taskStatus == "CANCELED") return ToolResult(
                "Sonar analysis submitted successfully but server processing $taskStatus (CE task: $ceTaskId).",
                "CE task $taskStatus", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
            emit("✓ Server processing complete (status: $taskStatus)")
        } else {
            // Scanner didn't emit a CE task URL (older versions or proxy strips it) — wait briefly
            emit("⏳ Scanner did not emit a CE task ID; waiting ${CE_FALLBACK_WAIT_MS / 1000}s for report propagation")
            delay(CE_FALLBACK_WAIT_MS)
        }

        emit("→ Resolving component keys for ${files.size} file(s) and fetching per-file results...")

        // ── 6. Resolve authoritative component keys for each requested file ─
        // Multi-module Maven/Gradle projects key source files as `projectKey:moduleName:pathWithinModule`,
        // which is NOT predictable from the repo-relative path alone. Query component_tree once and
        // map by path so getSourceLines/getDuplications hit the right components instead of silently
        // returning empty. Best-effort: if the call fails, fall back to the legacy concatenation so
        // single-module projects keep working.
        val componentsResult = sonarService.listFileComponents(projectKey, branch = branch, repoName = repoName)
        val discoveredComponents = if (!componentsResult.isError) componentsResult.data ?: emptyList() else emptyList()
        val componentResolutionWarning = if (componentsResult.isError)
            "Note: could not resolve real component keys (${componentsResult.summary}). Falling back to inferred keys — per-file results may be empty on multi-module projects."
        else null

        // ── 7. Fetch per-file results in parallel ─────────────────────────
        val sb = StringBuilder()
        sb.appendLine("Local Sonar Analysis Complete")
        val branchLabel = branch ?: "(project default)"
        sb.appendLine("Runner: $buildTool | Files: ${files.size} | Duration: ${String.format("%.1f", elapsedS)}s | Branch: $branchLabel")
        sb.appendLine(branchResolution.note)
        if (componentResolutionWarning != null) sb.appendLine(componentResolutionWarning)
        if (ceTaskId != null) sb.appendLine("CE Task: $ceTaskId")
        sb.appendLine()

        for (relativePath in relativePaths) {
            val resolution = resolveComponentKey(relativePath, discoveredComponents, projectKey)
            val componentKey = resolution.key
            sb.appendLine("${"═".repeat(60)}")
            sb.appendLine(relativePath)
            if (resolution.note != null) sb.appendLine("  (${resolution.note})")
            sb.appendLine()

            coroutineScope {
                val issuesD   = async { sonarService.getIssues(projectKey, filePath = relativePath, branch = branch, repoName = repoName) }
                val sourceD   = async { sonarService.getSourceLines(componentKey, branch = branch, repoName = repoName) }
                val hotspotsD = async { sonarService.getSecurityHotspots(projectKey, branch = branch, repoName = repoName) }
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

    private sealed class BranchResolution {
        abstract val note: String
        // Publish under this specific branch (emits -Dsonar.branch.name=<branch>).
        data class Use(val branch: String, override val note: String) : BranchResolution()
        // No branch specified and none auto-detectable — omit the flag entirely.
        // Preserves SonarQube Community Edition compatibility (which rejects the branch flag).
        data class Omit(override val note: String) : BranchResolution()
    }

    private fun resolveLocalAnalysisBranch(project: Project, userProvided: String?): BranchResolution {
        val userBranch = userProvided?.trim()?.takeIf { it.isNotBlank() }
        val currentGitBranch = runCatching {
            RepoContextResolver.getInstance(project).resolveCurrentEditorRepoOrPrimary()?.currentBranchName
        }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }

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

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
- local_analysis(files, branch?, timeout?) → **Run SonarQube analysis locally on specific files** using Maven/Gradle Sonar plugin, then return fresh results (issues, hotspots, coverage, duplications) for those files. Use this after refactoring to get immediate Sonar feedback without waiting for the CI pipeline. Requires Maven (pom.xml) or Gradle (build.gradle) and SonarQube connection configured. timeout default 300s.

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
                description = "Optional branch name — for issues, quality_gate, coverage, project_measures, source_lines, issues_paged, security_hotspots, duplications. Use project_context tool to discover current branch."
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
                val branch = params["branch"]?.jsonPrimitive?.contentOrNull
                val repoName = params["repo_name"]?.jsonPrimitive?.contentOrNull
                val timeoutSeconds = (params["timeout"]?.jsonPrimitive?.content?.toLongOrNull() ?: 300L)
                    .coerceIn(30L, 900L)
                executeLocalAnalysis(params, project, filesParam, branch, repoName, timeoutSeconds)
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
        branch: String?,
        repoName: String?,
        timeoutSeconds: Long
    ): ToolResult {
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

        val toolCallId = RunCommandTool.currentToolCallId.get()
        val streamCb = RunCommandTool.streamCallback

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
                    if (toolCallId != null) streamCb?.invoke(toolCallId, "$line\n")
                    line = br.readLine()
                }
            }
        }.apply { isDaemon = true; name = "sonar-scanner-reader"; start() }

        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) {
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
            val tail = outputBuilder.toString().lines().takeLast(30).joinToString("\n")
            return ToolResult(
                "Sonar analysis FAILED (exit ${process.exitValue()}) after ${String.format("%.1f", elapsedS)}s.\n\n$tail",
                "Analysis failed (exit ${process.exitValue()})", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        // ── 5. Wait for CE task to finish processing on server ─────────────
        val sonarService = ServiceLookup.sonar(project) ?: return ServiceLookup.notConfigured("SonarQube")

        if (ceTaskId != null) {
            val pollDeadline = System.currentTimeMillis() + CE_POLL_TIMEOUT_MS
            var taskStatus = "PENDING"
            while (taskStatus in setOf("PENDING", "IN_PROGRESS") && System.currentTimeMillis() < pollDeadline) {
                delay(CE_POLL_INTERVAL_MS)
                val statusResult = sonarService.getCeTaskStatus(ceTaskId!!)
                if (!statusResult.isError) taskStatus = statusResult.data ?: "UNKNOWN"
                else break
            }
            if (taskStatus == "FAILED" || taskStatus == "CANCELED") return ToolResult(
                "Sonar analysis submitted successfully but server processing $taskStatus (CE task: $ceTaskId).",
                "CE task $taskStatus", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        } else {
            // Scanner didn't emit a CE task URL (older versions or proxy strips it) — wait briefly
            delay(CE_FALLBACK_WAIT_MS)
        }

        // ── 6. Fetch per-file results in parallel ─────────────────────────
        val sb = StringBuilder()
        sb.appendLine("Local Sonar Analysis Complete")
        sb.appendLine("Runner: $buildTool | Files: ${files.size} | Duration: ${String.format("%.1f", elapsedS)}s")
        if (ceTaskId != null) sb.appendLine("CE Task: $ceTaskId")
        sb.appendLine()

        for (relativePath in relativePaths) {
            val componentKey = "$projectKey:$relativePath"
            sb.appendLine("${"═".repeat(60)}")
            sb.appendLine("$relativePath")
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

                // Security hotspots for this file
                val fileHotspots = if (!hotspotsResult.isError)
                    (hotspotsResult.data ?: emptyList()).filter { it.component.endsWith(relativePath) || it.component.contains(relativePath) }
                else emptyList()
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
    }
}

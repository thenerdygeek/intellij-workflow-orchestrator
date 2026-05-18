package com.workflow.orchestrator.agent.tools.project

import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.workflow.orchestrator.agent.loop.ApprovalResult
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.framework.maven.MavenAsyncFacade
import com.workflow.orchestrator.agent.tools.framework.maven.MavenDetectResult
import com.workflow.orchestrator.agent.tools.framework.maven.awaitMavenImport
import com.workflow.orchestrator.agent.tools.framework.maven.detectAndRegisterMaven
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

private val VALID_REFRESH_MODES: Set<String> = setOf(
    "reload",
    "generate_sources",
    "download_sources",
    "download_javadocs",
    "download_sources_and_javadocs"
)

/**
 * Write action: triggers an external system (Maven/Gradle) reimport.
 *
 * Modes (Maven-only except `reload`):
 *  - `reload` → MavenAsyncProjectsManager.scheduleUpdateAllMavenProjects, or
 *    scheduleUpdateMavenProjects(filesToUpdate) when `module_paths` is set
 *  - `generate_sources` → MavenFolderResolver.resolveFoldersAndImport
 *  - `download_sources` / `download_javadocs` / `download_sources_and_javadocs`
 *    → MavenAsyncProjectsManager.scheduleDownloadArtifacts with
 *    MavenDownloadSourcesRequest scoped to all projects or `module_paths`
 *
 * `module_paths` (optional array of pom.xml paths) restricts the operation to a
 * subset of Maven modules. Absent ⇒ operates on every imported Maven module
 * (legacy "all-projects" semantics). Non-Maven systems (Gradle) ignore the
 * filter and use ExternalSystemUtil.refreshProject as before.
 *
 * On a fresh-clone project where ExternalSystem reports no linked roots, the
 * tool walks the project basePath for pom.xml files and registers them via
 * MavenOpenProjectProvider (trust-dialog-aware) — see MavenImportHelper.
 */
internal suspend fun executeRefreshExternalProject(
    params: JsonObject,
    project: Project,
    tool: AgentTool
): ToolResult {

    // ── Step 1: gather all linked external roots ─────────────────────────────
    val rootsPerSystem: List<Pair<ProjectSystemId, List<String>>> = try {
        ExternalSystemApiUtil.getAllManagers().mapNotNull { manager ->
            val systemId = manager.getSystemId()
            val roots: List<String> = try {
                ExternalSystemApiUtil.getSettings(project, systemId)
                    .linkedProjectsSettings
                    .mapNotNull { it.externalProjectPath }
                    .filter { it.isNotBlank() }
            } catch (_: Exception) {
                emptyList()
            }
            if (roots.isEmpty()) null else systemId to roots
        }
    } catch (_: Exception) {
        return ToolResult(
            content = "No external project roots are linked. Nothing to refresh.",
            summary = "No external roots",
            tokenEstimate = 10,
            isError = false
        )
    }

    // ── Step 2: nothing linked → try filesystem-based Maven auto-detect ───────
    if (rootsPerSystem.isEmpty()) {
        return when (val detection = detectAndRegisterMaven(project)) {
            is MavenDetectResult.NewlyRegistered -> {
                val imported = awaitMavenImport(project, timeoutMs = 30_000)
                val pomList = detection.pomPaths.joinToString("\n  • ", prefix = "  • ")
                val status = if (imported) "completed" else "still in progress (timed out waiting; reimport continues in background)"
                ToolResult(
                    content = "Detected unmanaged Maven project — registered ${detection.pomPaths.size} pom.xml file(s) " +
                        "via MavenOpenProjectProvider. Import $status.\n$pomList",
                    summary = "Registered ${detection.pomPaths.size} pom(s); import $status",
                    tokenEstimate = 40 + (pomList.length / 4)
                )
            }
            is MavenDetectResult.Failed -> ToolResult(
                content = "No external project roots are linked (no Gradle or Maven import). Nothing to refresh.\n" +
                    "Note: Maven auto-detect aborted: ${detection.message}",
                summary = "No external roots (Maven auto-detect aborted)",
                tokenEstimate = 25,
                isError = false
            )
            is MavenDetectResult.AlreadyImported -> ToolResult(
                content = "Maven reports ${detection.projectCount} project(s) imported, but no roots are exposed via " +
                    "ExternalSystem. This is usually transient — IntelliJ may still be wiring the model. " +
                    "Retry shortly, or open Maven tool window to inspect.",
                summary = "Maven imported but no external roots yet",
                tokenEstimate = 30
            )
            MavenDetectResult.NoPomFound,
            MavenDetectResult.NoMavenPlugin -> ToolResult(
                content = "No external project roots are linked (no Gradle or Maven import). Nothing to refresh.",
                summary = "No external roots",
                tokenEstimate = 10,
                isError = false
            )
        }
    }

    // ── Step 3: parse mode + module_paths + approval gate ────────────────────
    val path = params["path"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    val mode = params["mode"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "reload"
    if (mode !in VALID_REFRESH_MODES) {
        return ToolResult(
            content = "Unknown mode '$mode'. Expected one of: ${VALID_REFRESH_MODES.joinToString(", ")}.",
            summary = "Invalid mode '$mode'",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )
    }

    val modulePaths: List<String>? = (params["module_paths"] as? JsonArray)?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.content.takeIf { c -> c.isNotBlank() } }
        ?.takeIf { it.isNotEmpty() }

    val systemNames = rootsPerSystem.joinToString(", ") { (id, _) -> id.readableName }
    val scopeDesc = when {
        modulePaths != null -> "module_paths=${modulePaths.size} pom(s)"
        path != null -> "root=$path"
        else -> "all external roots"
    }
    val argsDescription = "refresh mode=$mode $scopeDesc ($systemNames)"

    val approval = tool.requestApproval(
        toolName = "project_structure.refresh_external_project",
        args = argsDescription,
        riskLevel = "medium",
        allowSessionApproval = true
    )
    if (approval == ApprovalResult.DENIED) {
        return ToolResult(
            content = "Refresh denied by user.",
            summary = "Approval denied",
            tokenEstimate = 20,
            isError = true
        )
    }

    // ── Step 4: module_paths takes precedence over the path-root filter ───────
    if (modulePaths != null) {
        return executeMavenScopedDispatch(project, mode, modulePaths)
    }

    // ── Step 5: determine targets (legacy path-root filter) ───────────────────
    val targets: List<Pair<ProjectSystemId, String>> = if (path != null) {
        val normalized = File(path).let {
            if (it.isAbsolute) it.canonicalPath else File(project.basePath ?: "", path).canonicalPath
        }
        val matched = rootsPerSystem.flatMap { (systemId, roots) ->
            roots.filter { root ->
                val canonicalRoot = File(root).canonicalPath
                normalized == canonicalRoot || normalized.startsWith("$canonicalRoot/")
            }.map { root -> systemId to root }
        }
        if (matched.isEmpty()) {
            return ToolResult(
                content = "Path '$path' does not match any linked external project root. " +
                    "Known roots: ${rootsPerSystem.flatMap { it.second }.joinToString(", ")}",
                summary = "No matching root for '$path'",
                tokenEstimate = 20,
                isError = true
            )
        }
        matched
    } else {
        rootsPerSystem.flatMap { (systemId, roots) -> roots.map { systemId to it } }
    }

    // ── Step 6: dispatch per target ──────────────────────────────────────────
    val triggered = mutableListOf<String>()
    val warnings = mutableListOf<String>()

    for ((systemId, root) in targets) {
        try {
            val isMaven = systemId.readableName.equals("Maven", ignoreCase = true) ||
                systemId.id.equals("Maven", ignoreCase = true)

            if (mode != "reload" && !isMaven) {
                warnings.add(
                    "Skipped $root (${systemId.readableName}): mode=$mode is Maven-only. " +
                        "Use mode=reload for Gradle/other systems."
                )
                continue
            }

            val noteSuffix: String = when {
                isMaven -> dispatchMavenAllProjects(project, mode, warnings)
                else -> {
                    ExternalSystemUtil.refreshProject(
                        root,
                        ImportSpecBuilder(project, systemId)
                            .use(ProgressExecutionMode.IN_BACKGROUND_ASYNC)
                            .build()
                    )
                    ""
                }
            }
            triggered.add("${systemId.readableName}: $root$noteSuffix")
        } catch (e: Exception) {
            warnings.add("Warning: could not trigger refresh for $root (${systemId.readableName}): ${e.message}")
        }
    }

    // ── Step 7: result ───────────────────────────────────────────────────────
    val sb = StringBuilder()
    sb.appendLine("Refresh triggered (mode=$mode) for ${triggered.size} target(s):")
    triggered.forEach { sb.appendLine("  • $it") }
    if (warnings.isNotEmpty()) {
        sb.appendLine()
        warnings.forEach { sb.appendLine(it) }
    }
    sb.append("Refresh runs in the background — recheck module detail after a moment.")

    return ToolResult(
        content = sb.toString(),
        summary = "${triggered.size} refresh(es) triggered (mode=$mode)",
        tokenEstimate = (sb.length / 4) + 1
    )
}

/**
 * Dispatches a Maven operation on *all* imported projects via the MavenAsyncFacade.
 * Returns a one-line note suffix describing what fired.
 */
private fun dispatchMavenAllProjects(project: Project, mode: String, warnings: MutableList<String>): String {
    return when (mode) {
        "reload" -> {
            val r = MavenAsyncFacade.scheduleUpdateAllMavenProjects(project, "agent-refresh-all")
            describeCallResult(r, "scheduleUpdateAllMavenProjects", warnings, fallbackNote = "via legacy forceUpdateAllProjectsOrFindAllAvailablePomFiles") {
                legacyForceUpdate(project)
            }
        }
        "generate_sources" -> {
            // Per the audit, Maven.UpdateAllFolders is not in current intellij.maven.xml.
            // Resolve all MavenProjects and invoke MavenFolderResolver directly.
            val projects = MavenAsyncFacade.getAllProjects(project)
            if (projects.isEmpty()) {
                warnings.add("Warning: generate_sources requested but no Maven projects found")
                ""
            } else {
                val ok = invokeFolderResolver(project, projects, warnings)
                if (ok) " (via MavenFolderResolver, ${projects.size} project(s))" else ""
            }
        }
        "download_sources" -> dispatchDownload(project, sources = true, docs = false, warnings = warnings)
        "download_javadocs" -> dispatchDownload(project, sources = false, docs = true, warnings = warnings)
        "download_sources_and_javadocs" -> dispatchDownload(project, sources = true, docs = true, warnings = warnings)
        else -> ""
    }
}

/**
 * Dispatches a Maven operation scoped to specific module pom.xml paths.
 */
private fun executeMavenScopedDispatch(project: Project, mode: String, modulePaths: List<String>): ToolResult {
    val warnings = mutableListOf<String>()

    val vfs = LocalFileSystem.getInstance()
    val baseDir = project.basePath?.let { File(it) }
    val resolvedVfs: List<Pair<String, VirtualFile>> = modulePaths.mapNotNull { p ->
        val canonical = File(p).let { if (it.isAbsolute) it else File(baseDir, p) }.canonicalFile
        val vf = vfs.findFileByIoFile(canonical)
        if (vf == null || !vf.exists()) {
            warnings.add("Warning: could not resolve pom file: $p")
            null
        } else p to vf
    }

    if (resolvedVfs.isEmpty()) {
        return ToolResult(
            content = "None of the supplied module_paths resolved to existing pom.xml files:\n" +
                modulePaths.joinToString("\n  • ", prefix = "  • ") +
                "\n\nPass canonical paths to per-module pom.xml files (e.g. 'core/pom.xml').",
            summary = "module_paths did not resolve",
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )
    }

    val mavenProjects = resolvedVfs.mapNotNull { (path, vf) ->
        val mp = MavenAsyncFacade.findProjectByPomFile(project, vf)
        if (mp == null) {
            warnings.add("Warning: pom not tracked as Maven module: $path")
            null
        } else mp
    }

    val noteSuffix = when (mode) {
        "reload" -> {
            val r = MavenAsyncFacade.scheduleUpdateMavenProjects(
                project, "agent-refresh-scoped", resolvedVfs.map { it.second }
            )
            describeCallResult(r, "scheduleUpdateMavenProjects", warnings, fallbackNote = null) { false }
        }
        "generate_sources" -> {
            if (mavenProjects.isEmpty()) "" else {
                val ok = invokeFolderResolver(project, mavenProjects, warnings)
                if (ok) " (MavenFolderResolver on ${mavenProjects.size} module(s))" else ""
            }
        }
        "download_sources" -> {
            if (mavenProjects.isEmpty()) "" else dispatchDownloadForProjects(project, mavenProjects, sources = true, docs = false, warnings = warnings)
        }
        "download_javadocs" -> {
            if (mavenProjects.isEmpty()) "" else dispatchDownloadForProjects(project, mavenProjects, sources = false, docs = true, warnings = warnings)
        }
        "download_sources_and_javadocs" -> {
            if (mavenProjects.isEmpty()) "" else dispatchDownloadForProjects(project, mavenProjects, sources = true, docs = true, warnings = warnings)
        }
        else -> ""
    }

    val sb = StringBuilder()
    sb.appendLine("Refresh triggered (mode=$mode) for ${resolvedVfs.size} module(s):")
    resolvedVfs.forEach { (_, vf) -> sb.appendLine("  • Maven: ${vf.path}$noteSuffix") }
    if (warnings.isNotEmpty()) {
        sb.appendLine()
        warnings.forEach { sb.appendLine(it) }
    }
    sb.append("Refresh runs in the background — recheck module detail after a moment.")

    return ToolResult(
        content = sb.toString(),
        summary = "${resolvedVfs.size} scoped refresh(es) (mode=$mode)",
        tokenEstimate = (sb.length / 4) + 1
    )
}

private fun dispatchDownload(project: Project, sources: Boolean, docs: Boolean, warnings: MutableList<String>): String {
    val all = MavenAsyncFacade.getAllProjects(project)
    if (all.isEmpty()) {
        warnings.add("Warning: no Maven projects to download for")
        return ""
    }
    return dispatchDownloadForProjects(project, all, sources, docs, warnings)
}

private fun dispatchDownloadForProjects(
    project: Project, mavenProjects: List<Any>, sources: Boolean, docs: Boolean, warnings: MutableList<String>
): String {
    val request = MavenAsyncFacade.buildDownloadRequest(mavenProjects, sources, docs)
    if (request == null) {
        warnings.add("Warning: could not construct MavenDownloadSourcesRequest (API may have drifted)")
        return ""
    }
    val r = MavenAsyncFacade.scheduleDownloadArtifacts(project, request)
    return describeCallResult(r, "scheduleDownloadArtifacts", warnings, fallbackNote = null) { false }
}

private fun invokeFolderResolver(project: Project, mavenProjects: List<Any>, warnings: MutableList<String>): Boolean {
    return try {
        val resolverCls = Class.forName("org.jetbrains.idea.maven.project.MavenFolderResolver")
        val resolver = resolverCls.getDeclaredConstructor(Project::class.java).newInstance(project)
        val method = resolverCls.getMethod("resolveFoldersAndImport", List::class.java)
        method.invoke(resolver, mavenProjects)
        true
    } catch (e: Throwable) {
        warnings.add("Warning: MavenFolderResolver invocation failed (${e::class.simpleName}: ${e.message})")
        false
    }
}

/**
 * Legacy fallback for the `reload` mode — used when scheduleUpdateAllMavenProjects
 * is unavailable on this IDE/Platform version. Returns true on success.
 */
private fun legacyForceUpdate(project: Project): Boolean {
    return try {
        val cls = Class.forName("org.jetbrains.idea.maven.project.MavenProjectsManager")
        val mgr = cls.getMethod("getInstance", Project::class.java).invoke(null, project)
        cls.getMethod("forceUpdateAllProjectsOrFindAllAvailablePomFiles").invoke(mgr)
        true
    } catch (_: Throwable) { false }
}

private fun describeCallResult(
    result: MavenAsyncFacade.CallResult,
    methodName: String,
    warnings: MutableList<String>,
    fallbackNote: String?,
    fallback: () -> Boolean
): String = when (result) {
    MavenAsyncFacade.CallResult.Triggered -> " (via $methodName)"
    is MavenAsyncFacade.CallResult.Unavailable -> {
        if (fallbackNote != null && fallback()) " ($fallbackNote)"
        else { warnings.add("Warning: $methodName unavailable: ${result.reason}"); "" }
    }
    is MavenAsyncFacade.CallResult.Failed -> {
        if (fallbackNote != null && fallback()) " ($fallbackNote)"
        else { warnings.add("Warning: $methodName failed: ${result.message}"); "" }
    }
}

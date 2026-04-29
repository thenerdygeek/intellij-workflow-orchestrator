package com.workflow.orchestrator.agent.tools.project

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.loop.ApprovalResult
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import java.io.File

/**
 * Mode → Maven action ID map. Single source of truth for both validation and dispatch.
 *
 *  - `null` = handled directly (currently only `reload`, which uses
 *    [forceMavenReimport] / [ExternalSystemUtil.refreshProject], not action invocation).
 *  - non-null = the Maven plugin action ID to invoke (mirrors a tool-window button click).
 *
 * Adding a new mode requires only an entry here — `VALID_REFRESH_MODES` derives from the keys.
 */
private val MAVEN_MODE_ACTIONS: Map<String, String?> = mapOf(
    "reload" to null,
    "generate_sources" to "Maven.UpdateAllFolders",
    "download_sources" to "Maven.DownloadAllSources",
    "download_javadocs" to "Maven.DownloadAllDocs",
    "download_sources_and_javadocs" to "Maven.DownloadAllSourcesAndDocs"
)

private val VALID_REFRESH_MODES: Set<String> = MAVEN_MODE_ACTIONS.keys

/**
 * Invokes a Maven tool window action by ID — the same code path the user clicks
 * trigger. Returns null on success; an error string when the action is unavailable
 * (Maven plugin not loaded) or invocation throws.
 */
private fun invokePlatformAction(project: Project, actionId: String): String? = try {
    val action = ActionManager.getInstance().getAction(actionId)
        ?: return "Action '$actionId' not found — Maven plugin may be disabled in this IDE."
    val dataContext = SimpleDataContext.getProjectContext(project)
    val event = AnActionEvent.createEvent(
        action,
        dataContext,
        Presentation(),
        ActionPlaces.UNKNOWN,
        com.intellij.openapi.actionSystem.ActionUiKind.NONE,
        null
    )
    ActionUtil.invokeAction(action, event, null)
    null
} catch (e: Throwable) {
    "Failed to invoke '$actionId': ${e::class.simpleName}: ${e.message}"
}

/**
 * Maven full-reload via MavenProjectsManager.forceUpdateAllProjectsOrFindAllAvailablePomFiles().
 * ExternalSystemUtil.refreshProject is the canonical Gradle path but for Maven only re-syncs the
 * project model without re-resolving dependencies.
 *
 * Reflective so :agent stays compile-clean against IDEs without the Maven plugin. Distinguishes:
 *  - [ClassNotFoundException] → expected (Maven plugin not loaded); silently return false.
 *  - any other throwable → unexpected (API drift / signature change in a future Platform);
 *    swallow but pass the cause into [warningSink] so the caller can include it in the result.
 *
 * Returns true on success, false on any failure.
 */
private fun forceMavenReimport(project: Project, warningSink: MutableList<String>): Boolean {
    val cls = try {
        Class.forName("org.jetbrains.idea.maven.project.MavenProjectsManager")
    } catch (_: ClassNotFoundException) {
        return false
    }
    return try {
        val mgr = cls.getMethod("getInstance", Project::class.java).invoke(null, project)
        cls.getMethod("forceUpdateAllProjectsOrFindAllAvailablePomFiles").invoke(mgr)
        true
    } catch (e: Throwable) {
        warningSink.add(
            "Warning: MavenProjectsManager reflection failed (${e::class.simpleName}: ${e.message}). " +
                "Falling back to ExternalSystemUtil.refreshProject — Platform API may have drifted."
        )
        false
    }
}

/**
 * Write action: triggers an external system (Maven/Gradle) reimport for linked roots.
 *
 * Modes (Maven-only except `reload`):
 *  - `reload` → MavenProjectsManager.forceUpdateAllProjectsOrFindAllAvailablePomFiles
 *      (Gradle/others fall through to ExternalSystemUtil.refreshProject)
 *  - `generate_sources` → invokes Maven action `Maven.UpdateAllFolders`
 *  - `download_sources` → invokes `Maven.DownloadAllSources`
 *  - `download_javadocs` → invokes `Maven.DownloadAllDocs`
 *  - `download_sources_and_javadocs` → invokes `Maven.DownloadAllSourcesAndDocs`
 *
 * Non-reload modes against Gradle/other roots are skipped with a warning, not failed,
 * so a mixed-build project can still partially complete.
 *
 * 1. Collects all linked external project roots via [ExternalSystemApiUtil].
 * 2. If none are linked, returns a non-error "nothing to refresh" result immediately.
 * 3. Validates `mode`, requests approval — denied ⇒ error result.
 * 4. Filters to the requested `path` root when provided; no match ⇒ error result.
 * 5. Dispatches per target: Maven uses MavenProjectsManager / action invocation;
 *    Gradle uses [ExternalSystemUtil.refreshProject] in `IN_BACKGROUND_ASYNC`.
 *
 * This function is called from [ProjectStructureTool] for the "refresh_external_project" action.
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
        // ExternalSystem APIs unavailable (e.g., headless test environment without the plugin)
        return ToolResult(
            content = "No external project roots are linked. Nothing to refresh.",
            summary = "No external roots",
            tokenEstimate = 10,
            isError = false
        )
    }

    // ── Step 2: nothing linked ────────────────────────────────────────────────
    if (rootsPerSystem.isEmpty()) {
        return ToolResult(
            content = "No external project roots are linked (no Gradle or Maven import). Nothing to refresh.",
            summary = "No external roots",
            tokenEstimate = 10,
            isError = false
        )
    }

    // ── Step 3: parse mode + approval gate ────────────────────────────────────
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
    val systemNames = rootsPerSystem.joinToString(", ") { (id, _) -> id.readableName }
    val argsDescription = "refresh mode=$mode ${if (path != null) "root=$path" else "all external roots"} ($systemNames)"

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

    // ── Step 4: determine targets ─────────────────────────────────────────────
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

    // ── Step 5: trigger refresh for each target ───────────────────────────────
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
                isMaven && mode == "reload" -> {
                    val ok = forceMavenReimport(project, warnings)
                    if (!ok) {
                        ExternalSystemUtil.refreshProject(
                            root,
                            ImportSpecBuilder(project, systemId)
                                .use(ProgressExecutionMode.IN_BACKGROUND_ASYNC)
                                .build()
                        )
                        " (via ExternalSystemUtil fallback)"
                    } else " (via MavenProjectsManager)"
                }
                isMaven -> {
                    val actionId = MAVEN_MODE_ACTIONS[mode]
                    if (actionId == null) {
                        // Defensive: VALID_REFRESH_MODES derives from the same map, so this can
                        // only fire if a future contributor adds a mode key with a null value
                        // and forgets to handle it directly above.
                        warnings.add("Warning: no Maven action mapping for mode='$mode' (internal bug — please file).")
                        continue
                    }
                    val err = invokePlatformAction(project, actionId)
                    if (err != null) {
                        warnings.add("Warning: $err")
                        continue
                    }
                    " (via $actionId)"
                }
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

    // ── Step 6: result ────────────────────────────────────────────────────────
    val sb = StringBuilder()
    sb.appendLine("Refresh triggered (mode=$mode) for ${triggered.size} root(s):")
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

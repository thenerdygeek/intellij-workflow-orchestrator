package com.workflow.orchestrator.agent.tools.project

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.coroutineContext

/**
 * Consolidated project structure meta-tool for IntelliJ IDEA's module/SDK/library model.
 *
 * Saves token budget per API call by collapsing all project structure operations into
 * a single tool definition with an `action` discriminator parameter.
 *
 * Actions: resolve_file, module_detail, topology, list_sdks, list_libraries,
 *          list_facets, refresh_external_project, add_source_root
 *
 * Each action is implemented in its own file under the `project/` subpackage.
 * Read actions (resolve_file, module_detail, topology, list_sdks, list_libraries, list_facets)
 * are pure IntelliJ model queries and may run in parallel.
 * Write actions (refresh_external_project, add_source_root) mutate the project model
 * and must run sequentially with write-action access.
 */
class ProjectStructureTool : AgentTool {

    override val name = "project_structure"

    override val description = """
Project structure intelligence — module layout, source root configuration, SDK setup, and external system sync.

Actions and their parameters:
- resolve_file(path) → Resolve a file path to its owning module, content root, and source root type
- module_detail(module?) → Full detail for a module: dependencies, SDK, source roots, facets, output paths
- topology(scope?, detect_cycles?) → Module dependency topology for project, application, or all modules
- list_sdks(scope?) → List all configured SDKs (project SDK, module-level overrides)
- list_libraries(module?, scope?) → List libraries attached to a module or the project
- list_facets(module?) → List facets (Spring, Android, JPA, etc.) attached to a module or all modules
- refresh_external_project(module?) → Trigger an external system (Maven/Gradle) reimport for a module or the root
- add_source_root(module, path, kind) → Add a source root to a module (kind: source/test_source/resource/test_resource)
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf(
                    "resolve_file",
                    "module_detail",
                    "topology",
                    "list_sdks",
                    "list_libraries",
                    "list_facets",
                    "refresh_external_project",
                    "add_source_root"
                )
            ),
            "path" to ParameterProperty(
                type = "string",
                description = "File or directory path to resolve — for resolve_file and add_source_root"
            ),
            "module" to ParameterProperty(
                type = "string",
                description = "Module name to scope the query/action. If omitted, uses root project or all modules depending on action."
            ),
            "kind" to ParameterProperty(
                type = "string",
                description = "Source root kind — for add_source_root",
                enumValues = listOf("source", "test_source", "resource", "test_resource")
            ),
            "scope" to ParameterProperty(
                type = "string",
                description = "Scope for topology and SDK/library listing",
                enumValues = listOf("project", "application", "all")
            ),
            "detect_cycles" to ParameterProperty(
                type = "boolean",
                description = "Run circular dependency detection in topology output. Default: true."
            )
        ),
        required = listOf("action")
    )

    override val allowedWorkers = setOf(
        WorkerType.TOOLER,
        WorkerType.ANALYZER,
        WorkerType.REVIEWER,
        WorkerType.ORCHESTRATOR,
        WorkerType.CODER
    )

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        coroutineContext.ensureActive()
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult(
                "Error: 'action' parameter required",
                "Error: missing action",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        return when (action) {
            "resolve_file" -> executeResolveFile(params, project)
            "module_detail" -> executeModuleDetail(params, project)
            "topology" -> executeTopology(params, project)
            "list_sdks" -> executeListSdks(params, project)
            "list_libraries" -> executeListLibraries(params, project)
            "list_facets" -> executeListFacets(params, project)
            "refresh_external_project" -> executeRefreshExternalProject(params, project, this)
            "add_source_root" -> executeAddSourceRoot(params, project, this)
            else -> ToolResult(
                content = "Unknown action '$action'. Valid actions include: resolve_file, module_detail, topology, list_sdks, list_libraries, list_facets, refresh_external_project, add_source_root.",
                summary = "Unknown action '$action'",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }
}

// ── Read action stubs ─────────────────────────────────────────────────────────
// Signature: executeXxx(params: JsonObject, project: Project): ToolResult

internal fun executeResolveFile(params: JsonObject, project: Project): ToolResult =
    ToolResult("Action 'resolve_file' is not yet implemented.", "Stub", 10, isError = true)

internal fun executeModuleDetail(params: JsonObject, project: Project): ToolResult =
    ToolResult("Action 'module_detail' is not yet implemented.", "Stub", 10, isError = true)

internal fun executeTopology(params: JsonObject, project: Project): ToolResult =
    ToolResult("Action 'topology' is not yet implemented.", "Stub", 10, isError = true)

internal fun executeListSdks(params: JsonObject, project: Project): ToolResult =
    ToolResult("Action 'list_sdks' is not yet implemented.", "Stub", 10, isError = true)

internal fun executeListLibraries(params: JsonObject, project: Project): ToolResult =
    ToolResult("Action 'list_libraries' is not yet implemented.", "Stub", 10, isError = true)

internal fun executeListFacets(params: JsonObject, project: Project): ToolResult =
    ToolResult("Action 'list_facets' is not yet implemented.", "Stub", 10, isError = true)

// ── Write action stubs ────────────────────────────────────────────────────────
// Signature: suspend executeXxx(params: JsonObject, project: Project, tool: AgentTool): ToolResult

/**
 * Write actions take [tool] so they can call [AgentTool.requestApproval] for the
 * in-action approval gate (added in the shared-helpers task). Read actions do not
 * need approval and omit the parameter — consistent with every other action in this
 * package.
 */
internal suspend fun executeRefreshExternalProject(params: JsonObject, project: Project, tool: AgentTool): ToolResult =
    ToolResult("Action 'refresh_external_project' is not yet implemented.", "Stub", 10, isError = true)

internal suspend fun executeAddSourceRoot(params: JsonObject, project: Project, tool: AgentTool): ToolResult =
    ToolResult("Action 'add_source_root' is not yet implemented.", "Stub", 10, isError = true)

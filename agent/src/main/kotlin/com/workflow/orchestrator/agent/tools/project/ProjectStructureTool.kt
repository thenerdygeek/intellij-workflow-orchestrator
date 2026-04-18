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
 *          list_facets, refresh_external_project, add_source_root,
 *          set_module_dependency, remove_module_dependency, set_module_sdk,
 *          set_language_level, add_content_root, remove_content_root
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
Project structure intelligence and mutation — query/fix module layout, dependencies, SDK, language level, source/content roots, and external system sync. Use instead of editing build files directly.

Actions and their parameters:
- resolve_file(path) → Resolve a file path to its owning module, content root, and source root type
- module_detail(module?) → Full detail for a module: dependencies, SDK, source roots, facets, output paths
- topology(scope?, detect_cycles?) → Module dependency topology for project, application, or all modules
- list_sdks(scope?) → List all configured SDKs (project SDK, module-level overrides)
- list_libraries(module?, scope?) → List libraries attached to a module or the project
- list_facets(module?) → List facets (Spring, Android, JPA, etc.) attached to a module or all modules
- refresh_external_project(module?) → Trigger an external system (Maven/Gradle) reimport for a module or the root
- add_source_root(module, path, kind) → Add a source root to a module (kind: source/test_source/resource/test_resource)
- set_module_dependency(module, dependsOn, scope?, exported?) → Add or update a module-to-module dependency (scope: compile/test/runtime/provided, default compile)
- remove_module_dependency(module, dependsOn) → Remove an inter-module dependency from a non-external-system module
- set_module_sdk(module, sdkName?) → Set the module SDK by name, or empty string to inherit from project
- set_language_level(module, languageLevel?) → Set Java language level (e.g. 8, 11, 17, 21), or empty string to inherit from project
- add_content_root(module, path) → Add a content root directory to a module
- remove_content_root(module, path) → Remove a content root directory from a module
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
                    "add_source_root",
                    "set_module_dependency",
                    "remove_module_dependency",
                    "set_module_sdk",
                    "set_language_level",
                    "add_content_root",
                    "remove_content_root"
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
                description = "For set_module_dependency: dependency scope (compile/test/runtime/provided). For topology/list_* actions: enumeration scope (project/application/all).",
                enumValues = listOf("compile", "test", "runtime", "provided", "project", "application", "all")
            ),
            "detect_cycles" to ParameterProperty(
                type = "boolean",
                description = "Run circular dependency detection in topology output. Default: true."
            ),
            "dependsOn" to ParameterProperty(
                type = "string",
                description = "Target module name — for set_module_dependency and remove_module_dependency"
            ),
            "exported" to ParameterProperty(
                type = "boolean",
                description = "Whether to re-export the dependency — for set_module_dependency. Default false."
            ),
            "sdkName" to ParameterProperty(
                type = "string",
                description = "SDK name for set_module_sdk. Empty string or omitted = inherit from project."
            ),
            "languageLevel" to ParameterProperty(
                type = "string",
                description = "Java language level for set_language_level, e.g. '8', '11', '17', '21'. Empty string = inherit."
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
            "set_module_dependency" -> executeSetModuleDependency(params, project, this)
            "remove_module_dependency" -> executeRemoveModuleDependency(params, project, this)
            "set_module_sdk" -> executeSetModuleSdk(params, project, this)
            "set_language_level" -> executeSetLanguageLevel(params, project, this)
            "add_content_root" -> executeAddContentRoot(params, project, this)
            "remove_content_root" -> executeRemoveContentRoot(params, project, this)
            else -> ToolResult(
                content = "Unknown action '$action'. Valid actions include: resolve_file, module_detail, topology, list_sdks, list_libraries, list_facets, refresh_external_project, add_source_root, set_module_dependency, remove_module_dependency, set_module_sdk, set_language_level, add_content_root, remove_content_root.",
                summary = "Unknown action '$action'",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }
}

// ── Read action implementations ───────────────────────────────────────────────
// NOTE: executeResolveFile is implemented in ResolveFileAction.kt
// NOTE: executeModuleDetail is implemented in ModuleDetailAction.kt

// ── Write action implementations ──────────────────────────────────────────────
// NOTE: executeRefreshExternalProject is implemented in RefreshExternalProjectAction.kt
// NOTE: executeAddSourceRoot is implemented in AddSourceRootAction.kt
// NOTE: executeSetModuleDependency is implemented in SetModuleDependencyAction.kt
// NOTE: executeRemoveModuleDependency is implemented in RemoveModuleDependencyAction.kt
// NOTE: executeSetModuleSdk is implemented in SetModuleSdkAction.kt
// NOTE: executeSetLanguageLevel is implemented in SetLanguageLevelAction.kt
// NOTE: executeAddContentRoot is implemented in AddContentRootAction.kt
// NOTE: executeRemoveContentRoot is implemented in RemoveContentRootAction.kt

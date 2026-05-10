package com.workflow.orchestrator.agent.tools.project

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
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
- refresh_external_project(module?, mode?) → Trigger Maven/Gradle reimport. Maven `mode`: reload (default) | generate_sources | download_sources | download_javadocs | download_sources_and_javadocs — mirrors the Maven tool window buttons. Non-reload modes are Maven-only.
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
            ),
            "mode" to ParameterProperty(
                type = "string",
                description = "For refresh_external_project on Maven roots: which tool-window button to invoke. Default 'reload'. Non-reload modes are Maven-only.",
                enumValues = listOf(
                    "reload",
                    "generate_sources",
                    "download_sources",
                    "download_javadocs",
                    "download_sources_and_javadocs"
                )
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

    override fun documentation(): ToolDocumentation = toolDoc("project_structure") {
        summary {
            technical(
                "14-action meta-tool for IntelliJ's module/SDK/library model. Read actions " +
                "(resolve_file, module_detail, topology, list_sdks, list_libraries, list_facets) " +
                "are pure model queries. Write actions (refresh_external_project, add_source_root, " +
                "set_module_dependency, remove_module_dependency, set_module_sdk, set_language_level, " +
                "add_content_root, remove_content_root) mutate the IntelliJ project model via " +
                "WriteCommandAction and carry their own internal requestApproval gates."
            )
            plain(
                "The agent's control panel for IntelliJ's project model — like opening File > " +
                "Project Structure in the IDE but from code. You can ask 'which module owns this " +
                "file?', map the whole dependency graph, list SDKs, and also directly add source " +
                "roots, wire module dependencies, switch SDK versions, and change language levels " +
                "without hand-editing .iml or build files. Each write asks the user before changing " +
                "anything, and external-system modules (Gradle/Maven) are protected from accidental " +
                "direct edits that would be overwritten on the next sync."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.IDE_MUTATION)
        counterfactual(
            "Without `project_structure`, the LLM edits .iml files directly, writes `<sourceFolder>` XML by hand, or runs `./gradlew idea` and hopes the model gets it right. Direct .iml edits are overwritten by Gradle/Maven on the next sync and are IDE-format-version fragile. The tool uses IntelliJ's own WriteCommandAction + ModuleRootModificationUtil APIs, so changes integrate with the undo stack and are idempotent."
        )
        llmMistake(
            "Tries to add a source root to a Gradle or Maven module instead of modifying build.gradle / pom.xml first and calling refresh_external_project. The add_source_root action explicitly guards against this (external-system check) and returns a descriptive error with the right remediation, but LLMs frequently skip the guard check in their reasoning."
        )
        llmMistake(
            "Calls topology without first reading module_detail for the involved module — topology gives the graph edges but not the per-module detail (SDK, language level, output path). For diagnosing 'why is module X failing to compile', module_detail is the right first call."
        )
        llmMistake(
            "Uses set_module_sdk with a guessed sdkName (e.g. 'JDK 17') instead of calling list_sdks first to get the exact registered SDK name. SDK names are arbitrary strings set by the user in Project Structure; they are not predictable from the JDK version number alone."
        )
        llmMistake(
            "Calls add_source_root before verifying the path is inside an existing content root. The action returns an error with the current content-root list, but LLMs skip this pre-check, leading to a wasted round-trip."
        )
        llmMistake(
            "Calls remove_module_dependency on a Gradle-managed module. Like add_source_root, this is guarded by the external-system check; the right approach is to remove the dependency from build.gradle and call refresh_external_project."
        )
        llmMistake(
            "Forgets that set_language_level only sets the IntelliJ language level override — it does NOT change sourceCompatibility in Gradle or source/target in the Maven compiler plugin. After setting language level, Gradle/Maven sync will revert it unless the build file is also updated."
        )
        llmMistake(
            "Passes `scope` values from the topology/list_* enum (`project`, `application`, `all`) to set_module_dependency, where `scope` means dependency scope (`compile`, `test`, `runtime`, `provided`). The parameter is shared in the schema but has completely different semantics depending on action."
        )
        downside(
            "Write actions call requestApproval internally but `project_structure` is NOT in AgentLoop.WRITE_TOOLS. This means: (1) the tool is not blocked in plan mode — the execution guard in AgentLoop won't catch it; (2) the schema is not filtered from tool definitions in plan mode; (3) write actions depend entirely on their own per-action requestApproval calls for the user gate. If requestApproval is a no-op (e.g. in automated tests or when ALWAYS_APPROVE is set), mutations run without any gate."
        )
        downside(
            "refresh_external_project is a fire-and-forget trigger — it enqueues a Gradle/Maven reimport in IntelliJ's background task queue but does not wait for it to finish. The LLM cannot determine whether the reimport succeeded within the same tool call; it must poll (via a subsequent module_detail or topology call) or rely on the user to verify."
        )
        downside(
            "list_libraries does not return transitive dependencies — only direct library attachments to the module. For a full transitive dependency view, use the `build` tool's `maven_dependencies` or `gradle_tasks` actions instead."
        )
        downside(
            "topology's cycle detection runs IntelliJ's own cycle analyzer in a ReadAction and may be slow on very large multi-module projects (>100 modules). Pass `detect_cycles=false` to get the topology graph without the cycle-detection overhead."
        )
        related("build", Relationship.COMPLEMENT, "Use `build` (maven_dependencies, gradle_tasks, module_dependency_graph) for build-system-level dependency views; use project_structure for IntelliJ model queries and lightweight mutations on non-external-system modules.")
        related("find_definition", Relationship.COMPLEMENT, "After resolve_file identifies the owning module, use find_definition to navigate to a symbol within that module.")
        observation(
            "The `scope` parameter is overloaded: for set_module_dependency it means dependency scope (compile/test/runtime/provided); for topology and list_* actions it means enumeration scope (project/application/all). The schema uses a single combined enum. This is a latent LLM confusion surface — consider splitting into `dependency_scope` and `scope` in a future revision."
        )
        observation(
            "refresh_external_project supports Maven-only modes (generate_sources, download_sources, download_javadocs, download_sources_and_javadocs) but the description only says 'Non-reload modes are Maven-only'. The LLM has no way to know whether the current project uses Gradle vs Maven before picking a mode. A pre-flight detection (or a Gradle-specific guard in the action) would reduce confusion."
        )
        observation(
            "add_content_root and remove_content_root are the lowest-use actions — content roots are almost always controlled by Gradle/Maven. On purely external-system projects these actions are never applicable. If the codebase transitions entirely to Gradle multi-project builds, these two actions could be dropped without user impact."
        )
        verdict {
            keep(
                "resolve_file, module_detail, and topology are genuinely non-substitutable IDE intelligence actions. The write actions fill the gap between 'read what the model says' and 'fix it' for non-external-system modules, which do exist in mixed IDE-managed/Gradle projects. The external-system guard prevents the most dangerous misuse. Keep the tool but add project_structure to WRITE_TOOLS or add an explicit plan-mode schema filter.",
                VerdictSeverity.STRONG
            )
        }
        actions {
            // ── READ ACTIONS ────────────────────────────────────────────────────────────

            action("resolve_file") {
                description {
                    technical("Resolves a file path to its owning module, content root, and source root type (source/test_source/resource/test_resource/excluded/unknown) using IntelliJ's VFS + ProjectFileIndex.")
                    plain("Answers 'which module owns this file and what kind of source is it?' — like right-clicking a file in the IDE and choosing Module > Properties, but without the UI.")
                }
                whenLLMUses("Before any module-scoped operation: 'is this file part of the test source set?', 'which module do I target for running tests?', 'why is this import red-underlined?'")
                params {
                    required("path", "string") {
                        llmSeesIt("File or directory path to resolve — for resolve_file and add_source_root")
                        humanReadable("Absolute or project-relative path to the file you want to look up.")
                        whenPresent("Path is resolved to a VirtualFile; ProjectFileIndex is queried for module, content root, and source root type.")
                        constraint("must be a valid path under the project root or an absolute path; directory paths are accepted")
                        example("src/main/kotlin/com/example/Service.kt")
                        example("/home/user/project/src/test/kotlin/ServiceTest.kt")
                    }
                }
                rejectsParam("module", "resolve_file derives the module from the path itself; specifying module is ignored.")
                rejectsParam("kind", "kind is an output for resolve_file, not an input; it is ignored.")
                onSuccess("Returns: file path, owning module name, content root path, source root type (source / test_source / resource / test_resource / excluded / unknown), and whether the file is in a generated source root.")
                onFailure("file not found", "Returns isError=true with 'File not found: <path>'. LLM should check the path spelling or use glob_files to discover the correct path.")
                onFailure("file outside project", "Returns isError=true. The path is outside every module's content root. Useful to confirm when a file is an external library or system file.")
                example("identify source root type") {
                    param("action", "resolve_file")
                    param("path", "src/test/kotlin/com/example/OrderServiceTest.kt")
                    outcome("Returns module=':order', contentRoot='src', sourceRootType='test_source'.")
                }
                verdict {
                    keep("The single most useful read action — every module-scoped decision starts here.", VerdictSeverity.STRONG)
                }
            }

            action("module_detail") {
                description {
                    technical("Returns full detail for a module from ModuleRootManager: dependencies (with scope and exported flag), SDK, language level, content roots, source roots (by kind), facets, and compiler output paths.")
                    plain("Opens the 'Module' tab of File > Project Structure for one module — shows everything IntelliJ knows about it: dependencies, SDK, language level, source folders, output directories.")
                }
                whenLLMUses("When the user asks 'why can't module A see module B's classes?', 'what SDK is :core using?', 'what's the language level of this module?', or before attempting any write action on a module.")
                params {
                    optional("module", "string") {
                        llmSeesIt("Module name to scope the query/action. If omitted, uses root project or all modules depending on action.")
                        humanReadable("Which module to inspect. If not given, defaults to the project's root module.")
                        whenPresent("Only the named module is returned.")
                        whenAbsent("Falls back to the project-level root module (if one exists) or returns an error asking for an explicit module name.")
                        example(":core")
                        example("order-service")
                    }
                }
                onSuccess("Returns a structured block: module name, dependencies list (each with name, type, scope, exported), SDK name + version, language level, list of content roots, source root paths by kind, facets list, compiler output paths (production and test).")
                onFailure("module not found", "Returns isError=true listing available module names. LLM should pick from the list or call topology first.")
                example("full module inspection") {
                    param("action", "module_detail")
                    param("module", ":core")
                    outcome("Returns all dependencies, SDK=JDK 21, languageLevel=17, two content roots, four source folders, zero facets, output=build/classes/kotlin/main.")
                }
                verdict {
                    keep("Essential companion to topology — topology shows edges, module_detail shows node internals.", VerdictSeverity.STRONG)
                }
            }

            action("topology") {
                description {
                    technical("Builds the module dependency graph for a given scope (project/application/all), optionally running IntelliJ's cycle-detection analyzer. Returns an adjacency list plus any detected cycles.")
                    plain("Draws the map of which modules depend on which — like a dependency graph whiteboard diagram, delivered as text. Also flags circular dependencies if asked.")
                }
                whenLLMUses("When the user asks 'what depends on :core?', 'is there a circular dependency in my project?', 'show me the module structure', or before a large refactoring that touches module boundaries.")
                params {
                    optional("scope", "string") {
                        llmSeesIt("For set_module_dependency: dependency scope (compile/test/runtime/provided). For topology/list_* actions: enumeration scope (project/application/all).")
                        humanReadable("Which modules to include: 'project' = only modules in this IntelliJ project, 'application' = application modules only, 'all' = all modules including SDK and library pseudo-modules.")
                        whenPresent("Filters the module set to the chosen scope.")
                        whenAbsent("Defaults to 'project'.")
                        enumValue("project", "application", "all")
                        example("project")
                    }
                    optional("detect_cycles", "boolean") {
                        llmSeesIt("Run circular dependency detection in topology output. Default: true.")
                        humanReadable("Whether to run IntelliJ's cycle-detection pass after building the graph. Slow on large projects.")
                        whenPresent("Cycle detection runs; any cycles are reported as a separate `Circular Dependencies` section.")
                        whenAbsent("Defaults to true — cycle detection runs. Pass false to skip on large projects for a faster graph-only result.")
                        example("false")
                    }
                }
                onSuccess("Returns a module adjacency list (module → [dependencies]) plus, if detect_cycles=true, a list of detected cycles each showing the cycle path.")
                onFailure("no modules found", "Returns an empty graph with a note. Happens in empty projects or when the VFS hasn't indexed yet.")
                example("full project topology with cycles") {
                    param("action", "topology")
                    param("scope", "project")
                    param("detect_cycles", "true")
                    outcome("Returns the dependency graph plus any circular dependency paths.")
                }
                example("fast graph without cycle detection") {
                    param("action", "topology")
                    param("detect_cycles", "false")
                    outcome("Returns the adjacency list only; skips the cycle-detection overhead.")
                }
                verdict {
                    keep("Irreplaceable for understanding large multi-module projects before surgery.", VerdictSeverity.STRONG)
                }
            }

            action("list_sdks") {
                description {
                    technical("Lists all SDKs registered in IntelliJ's ProjectJdkTable — both the project-level SDK and per-module SDK overrides.")
                    plain("Shows what JDKs and other SDKs are installed and registered in the IDE — like the SDK list in File > Project Structure > Platform Settings > SDKs.")
                }
                whenLLMUses("Before calling set_module_sdk: must know the exact registered SDK name (e.g. 'corretto-21') before it can be set on a module.")
                params {}
                rejectsParam("module", "list_sdks returns all registered SDKs globally; module scoping is irrelevant.")
                onSuccess("Returns a list of SDK entries: name, type (JavaSDK, PythonSDK, etc.), home path, and version string. Also shows which SDK is the current project SDK.")
                onFailure("no SDKs registered", "Returns an empty list. LLM should instruct the user to add an SDK via File > Project Structure > SDKs.")
                example("discover SDK names before setting one") {
                    param("action", "list_sdks")
                    outcome("Returns ['corretto-21 (Java 21)', 'JDK 17 (Java 17)', 'Python 3.11'] — LLM picks the right name for set_module_sdk.")
                }
                verdict {
                    keep("Required precondition for set_module_sdk — SDK names are not predictable without it.", VerdictSeverity.NORMAL)
                }
            }

            action("list_libraries") {
                description {
                    technical("Lists libraries attached to a module (or the project) from ModuleRootManager / LibraryTablesRegistrar. Returns only direct attachments, not transitive dependencies.")
                    plain("Shows which JARs and library entries are wired directly to a module — like the Libraries tab in Module Settings. Does not show transitive dependencies.")
                }
                whenLLMUses("When the user asks 'what version of guava is on the classpath?', 'is this library attached?', or when diagnosing 'ClassNotFoundException' after a build.")
                params {
                    optional("module", "string") {
                        llmSeesIt("Module name to scope the query/action. If omitted, uses root project or all modules depending on action.")
                        humanReadable("Scope to a specific module. If not given, shows project-level libraries.")
                        whenPresent("Only libraries attached to the named module are listed.")
                        whenAbsent("Returns project-level global libraries from the project library table.")
                        example(":core")
                    }
                    optional("scope", "string") {
                        llmSeesIt("For set_module_dependency: dependency scope (compile/test/runtime/provided). For topology/list_* actions: enumeration scope (project/application/all).")
                        humanReadable("Scope filter for which library table to query: 'project' = module-level attachments, 'application' = IDE application-level library table, 'all' = both.")
                        whenPresent("Filters which library table is queried.")
                        whenAbsent("Defaults to 'project'.")
                        enumValue("project", "application", "all")
                        example("project")
                    }
                }
                onSuccess("Returns a list of library entries: name, type, roots (classes, sources, javadoc), and the scope at which it is attached to the module.")
                onFailure("module not found", "Returns isError=true with the available module list.")
                example("check guava version on :core") {
                    param("action", "list_libraries")
                    param("module", ":core")
                    outcome("Returns library entries including 'Gradle: com.google.guava:guava:32.1.3-jre' with its JAR paths.")
                }
                verdict {
                    keep("Useful for classpath diagnostics. Direct libraries are hard to discover otherwise without reading build files.", VerdictSeverity.NORMAL)
                }
            }

            action("list_facets") {
                description {
                    technical("Lists facets registered on a module (or all modules) via FacetManager — Spring, Android, JPA, Web, etc.")
                    plain("Shows which special frameworks IntelliJ has detected or configured for a module — like the Facets tab in Module Settings. Facets drive IDE features like Spring autowire checks and Android resource indexing.")
                }
                whenLLMUses("When the user asks 'does this module have the Spring facet?' or when the LLM needs to understand which frameworks IntelliJ has recognized on a module.")
                params {
                    optional("module", "string") {
                        llmSeesIt("Module name to scope the query/action. If omitted, uses root project or all modules depending on action.")
                        humanReadable("Scope to a single module. If not given, returns facets across all modules.")
                        whenPresent("Only the named module's facets are returned.")
                        whenAbsent("Returns all facets across all project modules.")
                        example(":web")
                    }
                }
                onSuccess("Returns a list of facet entries: module name, facet type (e.g. 'Spring', 'JPA'), facet name, and any configuration properties the facet exposes.")
                onFailure("module not found", "Returns isError=true with available modules.")
                example("list all Spring facets in project") {
                    param("action", "list_facets")
                    outcome("Returns all modules that have a Spring facet configured — useful before calling the spring tool.")
                }
                verdict {
                    keep("Low-cost query. Useful for framework-aware reasoning without requiring the spring tool.", VerdictSeverity.NORMAL)
                }
            }

            // ── WRITE ACTIONS ───────────────────────────────────────────────────────────

            action("refresh_external_project") {
                description {
                    technical("Triggers a Gradle or Maven reimport via ExternalSystemUtil.refreshProjects. For Maven roots, supports additional mode values (generate_sources, download_sources, download_javadocs, download_sources_and_javadocs) that mirror the Maven tool window buttons. Fire-and-forget: enqueues the task and returns immediately without waiting for completion.")
                    plain("Presses the 'Reload Gradle/Maven project' button in the IDE — like clicking the elephant/Maven icon in the sidebar. The actual sync runs in the background; this tool just kicks it off and returns.")
                }
                whenLLMUses("After editing a build file (build.gradle, pom.xml, settings.gradle) or after calling set_module_sdk/set_language_level — to sync IntelliJ's model with the updated build file.")
                params {
                    optional("module", "string") {
                        llmSeesIt("Module name to scope the query/action. If omitted, uses root project or all modules depending on action.")
                        humanReadable("Scope the reload to one module's external project root. If not given, all external project roots are reloaded.")
                        whenPresent("Only the external project root containing the named module is reloaded.")
                        whenAbsent("All external project roots (all Gradle / Maven roots in the project) are reloaded.")
                        example(":api")
                    }
                    optional("mode", "string") {
                        llmSeesIt("For refresh_external_project on Maven roots: which tool-window button to invoke. Default 'reload'. Non-reload modes are Maven-only.")
                        humanReadable("Maven-specific reload variant. 'reload' = standard reimport. Other modes mirror the Maven tool window: generate_sources = run generate-sources phase, download_sources = fetch source JARs, download_javadocs = fetch javadoc JARs.")
                        whenPresent("For Maven roots: the specified Maven lifecycle/download action is triggered. For Gradle roots: only 'reload' is meaningful; other modes are silently degraded.")
                        whenAbsent("Defaults to 'reload' — standard Gradle/Maven reimport.")
                        enumValue("reload", "generate_sources", "download_sources", "download_javadocs", "download_sources_and_javadocs")
                        example("download_sources")
                    }
                }
                precondition("At least one Gradle or Maven root must be configured in the project for this to have any effect.")
                onSuccess("Returns 'Reimport triggered for <N> external project root(s): [<path>, ...]'. The sync runs asynchronously — a subsequent module_detail call can confirm the model was updated.")
                onFailure("no external system roots", "Returns an informational result (not isError=true) saying no external project roots were found. Appropriate for pure IDE-managed projects.")
                example("reload after editing build.gradle") {
                    param("action", "refresh_external_project")
                    outcome("All Gradle/Maven roots enqueued for reimport. Module graph updated asynchronously.")
                }
                example("download Maven sources") {
                    param("action", "refresh_external_project")
                    param("mode", "download_sources")
                    outcome("Maven source JARs are downloaded for all dependencies; IDE source navigation becomes available.")
                    notes("Maven-only mode. On Gradle projects this degrades to a standard reload.")
                }
                verdict {
                    keep("Essential after any build-file mutation. Without it, the IDE model lags behind the build file changes.", VerdictSeverity.STRONG)
                }
            }

            action("add_source_root") {
                description {
                    technical("Adds a typed source root to an existing content entry of an IntelliJ module via ModuleRootModificationUtil.updateModel + WriteCommandAction. Guards: external-system modules are rejected (edit build file instead); path must be inside an existing content root; requires requestApproval before writing.")
                    plain("Marks a directory as a source folder inside a module — like dragging a folder to 'Sources', 'Tests', 'Resources', or 'Test Resources' in File > Project Structure. Only works on IDE-managed modules; Gradle/Maven modules must be changed via build files.")
                }
                whenLLMUses("When the user says 'mark this directory as a test source root' or 'add src/generated as a source folder' for a module not managed by Gradle or Maven.")
                params {
                    required("module", "string") {
                        llmSeesIt("Module name to scope the query/action. If omitted, uses root project or all modules depending on action.")
                        humanReadable("The IDE-managed module that will receive the new source root.")
                        whenPresent("Validated against ModuleManager; external-system modules are rejected before the write.")
                        constraint("module must not be managed by an external system (Gradle/Maven/SBT) — those edits are overwritten on resync")
                        example(":integration-tests")
                    }
                    required("path", "string") {
                        llmSeesIt("File or directory path to resolve — for resolve_file and add_source_root")
                        humanReadable("Directory to add as a source root. Must already exist on disk and be inside one of the module's content roots.")
                        whenPresent("Resolved to an absolute path, looked up as VirtualFile via LocalFileSystem, then checked against the module's content entries.")
                        constraint("directory must exist on disk before calling this action (create it with run_command first if needed)")
                        constraint("must be inside an existing content root of the specified module")
                        example("src/generated/kotlin")
                        example("/home/user/project/custom-src")
                    }
                    required("kind", "string") {
                        llmSeesIt("Source root kind — for add_source_root")
                        humanReadable("What kind of source the directory holds: production sources, test sources, resources, or test resources.")
                        whenPresent("Mapped to the IntelliJ JPS source root type: source→JavaSourceRootType.SOURCE, test_source→JavaSourceRootType.TEST_SOURCE, resource→JavaResourceRootType.RESOURCE, test_resource→JavaResourceRootType.TEST_RESOURCE.")
                        enumValue("source", "test_source", "resource", "test_resource")
                        example("source")
                        example("test_source")
                    }
                }
                precondition("Module must exist and must NOT be managed by Gradle, Maven, or another external build system.")
                precondition("Directory must exist on disk — create it first with run_command if needed.")
                precondition("Directory must be inside an existing content root of the module.")
                precondition("User approval is requested via the internal approval gate before any write.")
                onSuccess("Returns 'Added [<kind>] source folder to '<module>': <relative-path>'.")
                onFailure("external-system module", "Returns isError=true explaining the module is managed by '<systemId>' and instructing to modify the build file + call refresh_external_project instead.")
                onFailure("path not found", "Returns isError=true with 'Path not found: <absolute> (create the directory first)'.")
                onFailure("path not in content root", "Returns isError=true listing the module's current content roots so the LLM knows the valid anchors.")
                onFailure("approval denied", "Returns isError=true 'add_source_root denied by user'.")
                example("add generated sources") {
                    param("action", "add_source_root")
                    param("module", "app")
                    param("path", "build/generated/sources/apt/main")
                    param("kind", "source")
                    outcome("IntelliJ marks build/generated/sources/apt/main as a source root; symbols generated there become visible to the IDE.")
                    notes("Directory must exist. Use run_command mkdir -p first if generated-sources haven't been produced yet.")
                }
                verdict {
                    keep("Uniquely useful for IDE-managed modules and generated source setups. The external-system guard prevents misuse.", VerdictSeverity.NORMAL)
                }
            }

            action("set_module_dependency") {
                description {
                    technical("Adds or updates a module-to-module dependency in IntelliJ's project model via ModuleRootModificationUtil.updateModel + WriteCommandAction. Guards: self-dependency rejected; external-system modules rejected; idempotent when the identical (target, scope) pair already exists; scope-update-in-place when the target exists with a different scope. Requires requestApproval before writing.")
                    plain("Wires one module to another in the IDE's dependency graph — like clicking '+' on the Dependencies tab in Module Settings and picking a module. Useful for IDE-managed modules only; Gradle/Maven modules must use their build files.")
                }
                whenLLMUses("When the user says 'make :feature depend on :core' for a module not controlled by Gradle or Maven, or when diagnosing missing-dependency compilation errors in an IDE-managed project.")
                params {
                    required("module", "string") {
                        llmSeesIt("Module name to scope the query/action. If omitted, uses root project or all modules depending on action.")
                        humanReadable("The module that should gain a dependency.")
                        whenPresent("Validated against ModuleManager; external-system modules are rejected.")
                        constraint("must not be an external-system module")
                        example(":feature-checkout")
                    }
                    required("dependsOn", "string") {
                        llmSeesIt("Target module name — for set_module_dependency and remove_module_dependency")
                        humanReadable("The module that the owner module should depend on.")
                        whenPresent("Validated that it exists in the project.")
                        constraint("must be a different module from `module` (self-dependency is rejected)")
                        example(":core")
                    }
                    optional("scope", "string") {
                        llmSeesIt("For set_module_dependency: dependency scope (compile/test/runtime/provided). For topology/list_* actions: enumeration scope (project/application/all).")
                        humanReadable("Classpath scope for this dependency: compile = always visible, test = test sources only, runtime = not visible at compile time, provided = compile-time only (not bundled).")
                        whenPresent("Sets the DependencyScope on the ModuleOrderEntry.")
                        whenAbsent("Defaults to 'compile'.")
                        enumValue("compile", "test", "runtime", "provided")
                        example("test")
                    }
                    optional("exported", "boolean") {
                        llmSeesIt("Whether to re-export the dependency — for set_module_dependency. Default false.")
                        humanReadable("If true, modules that depend on `module` also see `dependsOn` transitively in IntelliJ's model (mirrors Maven's `<optional>false</optional>` equivalent).")
                        whenPresent("Sets `isExported = true` on the ModuleOrderEntry.")
                        whenAbsent("Defaults to false — dependency is not re-exported.")
                        example("true")
                    }
                }
                precondition("Both modules must exist in the project.")
                precondition("Owner module must not be an external-system module.")
                precondition("User approval is requested before the write.")
                onSuccess("Returns 'Added dependency :feature-checkout → :core (scope=compile, exported=false).' or 'Updated dependency ...' if the target already existed with a different scope.")
                onFailure("module not found", "Returns isError=true with the list of available module names.")
                onFailure("self-dependency", "Returns isError=true 'Module cannot depend on itself'.")
                onFailure("external-system module", "Returns isError=true instructing to edit the build file instead.")
                onFailure("approval denied", "Returns isError=true 'Dependency change denied by user'.")
                example("add test dependency") {
                    param("action", "set_module_dependency")
                    param("module", ":feature")
                    param("dependsOn", ":test-fixtures")
                    param("scope", "test")
                    outcome("IntelliJ model updated: :feature → :test-fixtures (scope=test). Test sources in :feature can now see :test-fixtures.")
                }
                verdict {
                    keep("Fills a gap for IDE-managed multi-module projects. Idempotent + external-system guard make it safe.", VerdictSeverity.NORMAL)
                }
            }

            action("remove_module_dependency") {
                description {
                    technical("Removes an inter-module dependency by filtering ModuleOrderEntries via ModuleRootModificationUtil.updateModel + WriteCommandAction. Guards: external-system modules rejected; no-op with informational message if the dependency doesn't exist. Requires requestApproval before writing.")
                    plain("Removes a module-to-module link in IntelliJ's dependency graph — the inverse of set_module_dependency.")
                }
                whenLLMUses("When the user says 'remove the dependency from :feature on :old-shared' in an IDE-managed project.")
                params {
                    required("module", "string") {
                        llmSeesIt("Module name to scope the query/action. If omitted, uses root project or all modules depending on action.")
                        humanReadable("The module from which to remove the dependency.")
                        whenPresent("Validated; external-system modules rejected.")
                        constraint("must not be an external-system module")
                        example(":feature")
                    }
                    required("dependsOn", "string") {
                        llmSeesIt("Target module name — for set_module_dependency and remove_module_dependency")
                        humanReadable("The module to un-wire from `module`.")
                        whenPresent("ModuleOrderEntries matching this module name are removed.")
                        constraint("must be different from `module`")
                        example(":legacy-utils")
                    }
                }
                precondition("Owner module must not be an external-system module.")
                precondition("User approval requested before write.")
                onSuccess("Returns 'Removed dependency :feature → :legacy-utils.' or an informational no-op message if the dependency did not exist.")
                onFailure("external-system module", "Returns isError=true instructing to edit the build file instead.")
                onFailure("module not found", "Returns isError=true with the available module list.")
                onFailure("approval denied", "Returns isError=true.")
                example("remove stale dependency") {
                    param("action", "remove_module_dependency")
                    param("module", ":feature")
                    param("dependsOn", ":legacy-utils")
                    outcome("ModuleOrderEntry for :legacy-utils removed from :feature's dependency list.")
                }
                verdict {
                    keep("Necessary complement to set_module_dependency. Low risk due to approval gate.", VerdictSeverity.NORMAL)
                }
            }

            action("set_module_sdk") {
                description {
                    technical("Sets the per-module SDK override via ModuleRootModificationUtil.setModuleSdk. Passing an empty sdkName removes the override (module inherits the project SDK). Requires requestApproval before writing.")
                    plain("Sets which JDK a module uses — overriding the project-wide default. Like the 'Module SDK' dropdown in File > Project Structure > Modules > Dependencies. Pass empty string to go back to the project default.")
                }
                whenLLMUses("When the user says 'set :api to use JDK 21' or 'reset :legacy to the project SDK'. Always preceded by list_sdks to get the exact SDK name.")
                params {
                    required("module", "string") {
                        llmSeesIt("Module name to scope the query/action. If omitted, uses root project or all modules depending on action.")
                        humanReadable("The module whose SDK to change.")
                        whenPresent("Validated against ModuleManager.")
                        example(":api")
                    }
                    optional("sdkName", "string") {
                        llmSeesIt("SDK name for set_module_sdk. Empty string or omitted = inherit from project.")
                        humanReadable("Exact registered SDK name from list_sdks output. Pass empty string or omit to remove the module-level override and inherit from the project SDK.")
                        whenPresent("SDK is looked up by exact name in ProjectJdkTable; if not found returns isError=true.")
                        whenAbsent("Module inherits the project-level SDK (override is cleared).")
                        example("corretto-21")
                        example("JDK 17")
                    }
                }
                precondition("SDK name must match an entry from list_sdks exactly — call list_sdks first.")
                precondition("User approval requested before write.")
                onSuccess("Returns 'Set SDK of :module to <sdkName>.' or 'Cleared SDK override for :module (now inherits project SDK).'")
                onFailure("SDK not found", "Returns isError=true with 'SDK <sdkName> not found. Available SDKs: [...]'. LLM should correct the name from the list.")
                onFailure("module not found", "Returns isError=true.")
                onFailure("approval denied", "Returns isError=true.")
                example("upgrade one module to JDK 21") {
                    param("action", "set_module_sdk")
                    param("module", ":api")
                    param("sdkName", "corretto-21")
                    outcome("Module :api now uses corretto-21 instead of the project-default JDK.")
                    notes("Precede with list_sdks to confirm 'corretto-21' is the exact registered name.")
                }
                verdict {
                    keep("Needed for mixed-JDK projects. list_sdks dependency prevents guessing mistakes.", VerdictSeverity.NORMAL)
                }
            }

            action("set_language_level") {
                description {
                    technical("Sets the Java language level on a module via LanguageLevelModuleExtensionImpl. Passing empty string clears the override (module inherits project language level). Requires requestApproval before writing.")
                    plain("Changes the Java language level a module compiles against — like the 'Language level' dropdown in Module Settings. Setting it here only affects IntelliJ's editor; it does NOT update sourceCompatibility in build.gradle or <source>/<target> in pom.xml.")
                }
                whenLLMUses("When the user says 'set :legacy to Java 8 language level' or 'let :api use Java 21 features' for IDE error highlighting purposes.")
                params {
                    required("module", "string") {
                        llmSeesIt("Module name to scope the query/action. If omitted, uses root project or all modules depending on action.")
                        humanReadable("The module whose language level to change.")
                        whenPresent("Validated against ModuleManager.")
                        example(":legacy")
                    }
                    optional("languageLevel", "string") {
                        llmSeesIt("Java language level for set_language_level, e.g. '8', '11', '17', '21'. Empty string = inherit.")
                        humanReadable("Java version number as a string. Pass empty string or omit to inherit the project-level language level.")
                        whenPresent("Mapped to IntelliJ's LanguageLevel enum (JDK_8, JDK_11, JDK_17, JDK_21, etc.).")
                        whenAbsent("Language level override is cleared; module inherits the project's language level.")
                        constraint("must be a supported IntelliJ language level version string — e.g. '8', '11', '17', '21'; preview levels not supported")
                        example("21")
                        example("8")
                    }
                }
                precondition("User approval requested before write.")
                onSuccess("Returns 'Set language level of :module to Java <N>.' or 'Cleared language level override for :module (now inherits project level).'")
                onFailure("unsupported language level", "Returns isError=true with 'Unsupported language level: <N>'. Valid values are the major Java version numbers supported by IntelliJ.")
                onFailure("module not found", "Returns isError=true.")
                onFailure("approval denied", "Returns isError=true.")
                example("allow Java 21 features in one module") {
                    param("action", "set_language_level")
                    param("module", ":api")
                    param("languageLevel", "21")
                    outcome("IntelliJ no longer marks Java 21 syntax as errors in :api. Note: build.gradle still needs sourceCompatibility = JavaVersion.VERSION_21 to compile correctly.")
                }
                verdict {
                    keep("Stops false-positive red-underlines in mixed-compatibility projects. Important caveat: build file must be updated separately.", VerdictSeverity.NORMAL)
                }
            }

            action("add_content_root") {
                description {
                    technical("Adds a content root directory to a module via ModuleRootModificationUtil.updateModel + WriteCommandAction. Content roots are the top-level directories IntelliJ indexes for a module. Guards: external-system modules rejected; duplicate content roots are silently no-op. Requires requestApproval before writing.")
                    plain("Adds a new top-level directory for IntelliJ to index as part of a module — like clicking '+' next to Content Roots in Module Settings. Rarely needed on Gradle/Maven projects since the build system manages roots.")
                }
                whenLLMUses("When a module needs to include a directory that isn't currently indexed by IntelliJ (e.g., a generated assets directory in an IDE-managed module).")
                params {
                    required("module", "string") {
                        llmSeesIt("Module name to scope the query/action. If omitted, uses root project or all modules depending on action.")
                        humanReadable("The IDE-managed module to which to add the content root.")
                        whenPresent("Validated; external-system modules rejected.")
                        constraint("must not be an external-system module")
                        example(":assets")
                    }
                    required("path", "string") {
                        llmSeesIt("File or directory path to resolve — for resolve_file and add_source_root")
                        humanReadable("Directory to add as a content root. Can be absolute or project-relative.")
                        whenPresent("Resolved to absolute path and added as a new content entry.")
                        constraint("directory should exist on disk or be created before adding")
                        example("/home/user/project/shared-assets")
                    }
                }
                precondition("Module must not be an external-system module.")
                precondition("User approval requested before write.")
                onSuccess("Returns 'Added content root to :module: <path>.' If the root already exists, returns an informational no-op message.")
                onFailure("external-system module", "Returns isError=true instructing to modify the build file.")
                onFailure("module not found", "Returns isError=true.")
                onFailure("approval denied", "Returns isError=true.")
                example("add shared assets directory") {
                    param("action", "add_content_root")
                    param("module", ":ui")
                    param("path", "shared-assets")
                    outcome("IntelliJ indexes the shared-assets directory as part of :ui and shows it in the project view.")
                }
                verdict {
                    keep("Low frequency but irreplaceable when needed. Idempotent on duplicate.", VerdictSeverity.WEAK)
                }
            }

            action("remove_content_root") {
                description {
                    technical("Removes a content root from a module's content entry list via ModuleRootModificationUtil.updateModel + WriteCommandAction. Guards: external-system modules rejected; no-op with informational message if root is not present. Requires requestApproval before writing.")
                    plain("Removes a directory from IntelliJ's index for a module — the inverse of add_content_root.")
                }
                whenLLMUses("When a content root is stale or accidentally registered and should no longer be indexed as part of a module.")
                params {
                    required("module", "string") {
                        llmSeesIt("Module name to scope the query/action. If omitted, uses root project or all modules depending on action.")
                        humanReadable("The IDE-managed module from which to remove the content root.")
                        whenPresent("Validated; external-system modules rejected.")
                        constraint("must not be an external-system module")
                        example(":ui")
                    }
                    required("path", "string") {
                        llmSeesIt("File or directory path to resolve — for resolve_file and add_source_root")
                        humanReadable("The content root path to remove. Can be absolute or project-relative.")
                        whenPresent("Resolved to absolute path; matched against existing ContentEntry files.")
                        example("old-assets")
                    }
                }
                precondition("Module must not be an external-system module.")
                precondition("User approval requested before write.")
                onSuccess("Returns 'Removed content root from :module: <path>.' or a no-op informational message if not found.")
                onFailure("external-system module", "Returns isError=true.")
                onFailure("module not found", "Returns isError=true.")
                onFailure("approval denied", "Returns isError=true.")
                example("remove stale content root") {
                    param("action", "remove_content_root")
                    param("module", ":ui")
                    param("path", "old-assets")
                    outcome("IntelliJ stops indexing old-assets as part of :ui.")
                }
                verdict {
                    keep("Necessary inverse of add_content_root. Low risk; approval-gated.", VerdictSeverity.WEAK)
                }
            }
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

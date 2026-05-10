package com.workflow.orchestrator.agent.tools.framework

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
import com.workflow.orchestrator.agent.tools.framework.build.executeGradleDependencies
import com.workflow.orchestrator.agent.tools.framework.build.executeGradleProperties
import com.workflow.orchestrator.agent.tools.framework.build.executeGradleTasks
import com.workflow.orchestrator.agent.tools.framework.build.executeMavenDependencies
import com.workflow.orchestrator.agent.tools.framework.build.executeMavenDependencyTree
import com.workflow.orchestrator.agent.tools.framework.build.executeMavenEffectivePom
import com.workflow.orchestrator.agent.tools.framework.build.executeMavenPlugins
import com.workflow.orchestrator.agent.tools.framework.build.executeMavenProfiles
import com.workflow.orchestrator.agent.tools.framework.build.executeMavenProperties
import com.workflow.orchestrator.agent.tools.framework.build.executeModuleDependencyGraph
import com.workflow.orchestrator.agent.tools.framework.build.executePipDependencies
import com.workflow.orchestrator.agent.tools.framework.build.executePipList
import com.workflow.orchestrator.agent.tools.framework.build.executePipOutdated
import com.workflow.orchestrator.agent.tools.framework.build.executePipShow
import com.workflow.orchestrator.agent.tools.framework.build.executePoetryLockStatus
import com.workflow.orchestrator.agent.tools.framework.build.executePoetryList
import com.workflow.orchestrator.agent.tools.framework.build.executePoetryOutdated
import com.workflow.orchestrator.agent.tools.framework.build.executePoetryScripts
import com.workflow.orchestrator.agent.tools.framework.build.executePoetryShow
import com.workflow.orchestrator.agent.tools.framework.build.executeProjectModules
import com.workflow.orchestrator.agent.tools.framework.build.executePytestDiscover
import com.workflow.orchestrator.agent.tools.framework.build.executePytestFixtures
import com.workflow.orchestrator.agent.tools.framework.build.executePytestRun
import com.workflow.orchestrator.agent.tools.framework.build.executeUvList
import com.workflow.orchestrator.agent.tools.framework.build.executeUvLockStatus
import com.workflow.orchestrator.agent.tools.framework.build.executeUvOutdated
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.coroutineContext

/**
 * Consolidated build system meta-tool for Maven, Gradle, pip, Poetry, uv, and pytest.
 *
 * Saves token budget per API call by collapsing all build system operations into
 * a single tool definition with an `action` discriminator parameter.
 *
 * Actions: maven_dependencies, maven_properties, maven_plugins, maven_profiles,
 *          maven_dependency_tree, maven_effective_pom,
 *          gradle_dependencies, gradle_tasks, gradle_properties,
 *          project_modules, module_dependency_graph,
 *          pip_list, pip_outdated, pip_show, pip_dependencies,
 *          poetry_list, poetry_outdated, poetry_show, poetry_lock_status, poetry_scripts,
 *          uv_list, uv_outdated, uv_lock_status,
 *          pytest_discover, pytest_run, pytest_fixtures
 *
 * Each action is implemented in its own file under the `build/` subpackage.
 */
class BuildTool : AgentTool {

    override val name = "build"

    override val description = """
Build system intelligence — Maven, Gradle, pip, Poetry, uv, and pytest.

Actions and their parameters:
- maven_dependencies(module?, scope?, search?) → Maven dependencies (scope: compile|test|runtime|provided)
- maven_properties(module?, search?) → POM properties
- maven_plugins(module?) → Build plugins
- maven_profiles(module?) → Build profiles
- maven_dependency_tree(module?, artifact?) → Transitive dependency tree (artifact to filter paths)
- maven_effective_pom(module?, plugin?) → Effective POM (plugin to filter by artifactId)
- gradle_dependencies(module?, configuration?, search?) → Gradle deps (configuration: implementation|api|testImplementation|...)
- gradle_tasks(module?, search?) → Gradle tasks
- gradle_properties(module?, search?) → Gradle properties
- project_modules() → List all IntelliJ modules
- module_dependency_graph(module?, transitive?, include_libraries?, detect_cycles?) → Module dependency graph
- pip_list(search?) → Installed pip packages
- pip_outdated() → Outdated pip packages with available updates
- pip_show(package) → Detailed info for a specific pip package
- pip_dependencies(search?) → Declared dependencies from requirements.txt/setup.cfg/setup.py/pyproject.toml
- poetry_list(search?) → Installed Poetry packages
- poetry_outdated() → Outdated Poetry packages
- poetry_show(package) → Detailed info for a specific Poetry package
- poetry_lock_status() → Poetry lock file status and stats
- poetry_scripts() → Scripts defined in pyproject.toml [tool.poetry.scripts]
- uv_list(search?) → Installed uv packages
- uv_outdated() → Outdated uv packages
- uv_lock_status() → uv lock file status and stats
- pytest_discover(path?) → Discover tests via pytest --collect-only
- pytest_run(path?, pattern?, markers?) → Run pytest with optional filters
- pytest_fixtures(path?) → List available pytest fixtures
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf(
                    "maven_dependencies", "maven_properties", "maven_plugins", "maven_profiles",
                    "maven_dependency_tree", "maven_effective_pom",
                    "gradle_dependencies", "gradle_tasks", "gradle_properties",
                    "project_modules", "module_dependency_graph",
                    "pip_list", "pip_outdated", "pip_show", "pip_dependencies",
                    "poetry_list", "poetry_outdated", "poetry_show", "poetry_lock_status", "poetry_scripts",
                    "uv_list", "uv_outdated", "uv_lock_status",
                    "pytest_discover", "pytest_run", "pytest_fixtures"
                )
            ),
            "module" to ParameterProperty(
                type = "string",
                description = "Module name to inspect. For Maven: module/artifactId name. For Gradle: ':core', 'jira', etc. If omitted, uses root/all modules depending on action."
            ),
            "scope" to ParameterProperty(
                type = "string",
                description = "Filter by dependency scope (compile, test, runtime, provided) — for maven_dependencies"
            ),
            "search" to ParameterProperty(
                type = "string",
                description = "Filter by name/value substring — for maven_dependencies, maven_properties, gradle_dependencies, gradle_tasks, gradle_properties"
            ),
            "artifact" to ParameterProperty(
                type = "string",
                description = "Filter dependency tree to paths containing this artifact — for maven_dependency_tree"
            ),
            "plugin" to ParameterProperty(
                type = "string",
                description = "Filter by plugin artifactId — for maven_effective_pom"
            ),
            "configuration" to ParameterProperty(
                type = "string",
                description = "Filter by Gradle configuration (implementation, api, testImplementation, etc.) — for gradle_dependencies"
            ),
            "transitive" to ParameterProperty(
                type = "boolean",
                description = "Include transitive (indirect) dependencies — for module_dependency_graph. Default: false."
            ),
            "include_libraries" to ParameterProperty(
                type = "boolean",
                description = "Include library dependencies in output — for module_dependency_graph. Default: false."
            ),
            "detect_cycles" to ParameterProperty(
                type = "boolean",
                description = "Run circular dependency detection — for module_dependency_graph. Default: true."
            ),
            "package" to ParameterProperty(
                type = "string",
                description = "Package name to inspect — for pip_show, poetry_show"
            ),
            "path" to ParameterProperty(
                type = "string",
                description = "Test path to scope discovery/execution — for pytest_discover, pytest_run, pytest_fixtures"
            ),
            "pattern" to ParameterProperty(
                type = "string",
                description = "Test name pattern filter (pytest -k) — for pytest_run"
            ),
            "markers" to ParameterProperty(
                type = "string",
                description = "Marker expression filter (pytest -m) — for pytest_run"
            )
        ),
        required = listOf("action")
    )

    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ANALYZER, WorkerType.REVIEWER, WorkerType.ORCHESTRATOR, WorkerType.CODER)

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
            "maven_dependencies" -> executeMavenDependencies(params, project)
            "maven_properties" -> executeMavenProperties(params, project)
            "maven_plugins" -> executeMavenPlugins(params, project)
            "maven_profiles" -> executeMavenProfiles(params, project)
            "maven_dependency_tree" -> executeMavenDependencyTree(params, project)
            "maven_effective_pom" -> executeMavenEffectivePom(params, project)
            "gradle_dependencies" -> executeGradleDependencies(params, project)
            "gradle_tasks" -> executeGradleTasks(params, project)
            "gradle_properties" -> executeGradleProperties(params, project)
            "project_modules" -> executeProjectModules(params, project)
            "module_dependency_graph" -> executeModuleDependencyGraph(params, project)
            "pip_list" -> executePipList(params, project)
            "pip_outdated" -> executePipOutdated(params, project)
            "pip_show" -> executePipShow(params, project)
            "pip_dependencies" -> executePipDependencies(params, project)
            "poetry_list" -> executePoetryList(params, project)
            "poetry_outdated" -> executePoetryOutdated(params, project)
            "poetry_show" -> executePoetryShow(params, project)
            "poetry_lock_status" -> executePoetryLockStatus(params, project)
            "poetry_scripts" -> executePoetryScripts(params, project)
            "uv_list" -> executeUvList(params, project)
            "uv_outdated" -> executeUvOutdated(params, project)
            "uv_lock_status" -> executeUvLockStatus(params, project)
            "pytest_discover" -> executePytestDiscover(params, project)
            "pytest_run" -> executePytestRun(params, project)
            "pytest_fixtures" -> executePytestFixtures(params, project)
            else -> ToolResult(
                content = "Unknown action '$action'. See tool description for valid actions.",
                summary = "Unknown action '$action'",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    @Suppress("LongMethod")
    override fun documentation(): ToolDocumentation = toolDoc("build") {
        summary {
            technical(
                "Build-system meta-tool covering Maven, Gradle, pip, Poetry, uv, and pytest — " +
                    "26 actions dispatched by the `action` parameter. All actions are pure reads: " +
                    "parse POM/build files, query IntelliJ module graph, shell-invoke dependency " +
                    "commands, and run pytest collection/execution. Registered only when hasJavaPlugin " +
                    "is true (ToolRegistrationFilter), though Python actions work whenever the " +
                    "corresponding toolchain is on PATH."
            )
            plain(
                "Your project's blueprint reader. Like asking your build tool to explain itself — " +
                    "the agent can see every dependency, task, property, profile, module wiring, " +
                    "and test in the project without running a full build. Covers Java/Kotlin " +
                    "(Maven + Gradle) and Python (pip, Poetry, uv, pytest) in one tool."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.READ_ONLY)

        counterfactual(
            "Without `build`, the LLM falls back to `run_command mvn dependency:tree` or " +
                "`run_command ./gradlew dependencies` — slow (10-60 s), sensitive to the " +
                "project's network/daemon state, and format-divergent (build-tool prose vs " +
                "structured output). The IntelliJ module graph actions (`project_modules`, " +
                "`module_dependency_graph`) have no shell equivalent at all; the LLM would " +
                "have to manually parse `settings.gradle` and every `build.gradle` file. " +
                "pytest actions are similarly irreplaceable: `pytest --collect-only` output " +
                "varies by plugin and version, whereas the action always returns a structured list."
        )

        llmMistake(
            "Calls `maven_dependencies` on a Gradle project or `gradle_dependencies` on a Maven " +
                "project — receives an empty result or a 'no build file found' message. The LLM " +
                "should inspect `project_modules` first to understand the project's build system, " +
                "or use `glob_files` to find `pom.xml` / `build.gradle.kts` before choosing the action."
        )
        llmMistake(
            "Omits `module` and expects root-only results when the project is multi-module — " +
                "Maven actions without `module` scan all modules (verbose), while Gradle actions " +
                "without `module` start from the root `build.gradle(.kts)` and iterate discovered " +
                "subprojects. Always pass `module` when the intent is scoped to one subproject."
        )
        llmMistake(
            "Uses `pip_show <package>` to check whether a package is installed, then also calls " +
                "`pip_list` for the same package — double work. `pip_show` already returns " +
                "version, location, requires, and required-by in one call."
        )
        llmMistake(
            "Calls `pytest_run` without first running `pytest_discover` — ends up with a " +
                "`path` or `pattern` that matches nothing and receives an empty result with no " +
                "diagnostic. Discover first to confirm test IDs."
        )
        llmMistake(
            "Passes `scope`, `search`, `artifact`, or `plugin` to an action that ignores them " +
                "(e.g., `scope` to `gradle_dependencies` which uses `configuration` instead). " +
                "Parameters are action-scoped; consult the description bullet list before calling."
        )
        llmMistake(
            "Expects `module_dependency_graph` to list external library versions — it shows " +
                "IntelliJ module-to-module edges, not artifact coordinates. Use " +
                "`maven_dependencies` / `gradle_dependencies` for library versions."
        )

        downside(
            "Registered only when `hasJavaPlugin` is true (ToolRegistrationFilter). In a " +
                "pure Python project opened in PyCharm Community without the Java plugin, this " +
                "tool is absent — the LLM must fall back to `run_command pip list` etc."
        )
        downside(
            "Python actions (pip_*, poetry_*, uv_*, pytest_*) shell out to the system Python " +
                "toolchain and are sensitive to which interpreter/virtualenv is active. If the " +
                "venv is not activated in the IDE's terminal environment, results may reflect " +
                "the wrong interpreter."
        )
        downside(
            "Maven actions parse `pom.xml` files on disk via the IntelliJ Maven project model " +
                "rather than running `mvn help:effective-pom`. Results may lag behind unsaved " +
                "editor changes until the Maven project is reimported."
        )
        downside(
            "Gradle properties parsing reads `gradle.properties` files directly — does not " +
                "evaluate Groovy/Kotlin DSL property assignments that compute values at build " +
                "time (e.g., `ext.foo = someFunction()`). Those appear as expressions, not values."
        )
        downside(
            "`pytest_run` executes actual tests — while technically a read from the build-state " +
                "perspective, it has the side effect of running user code. The `pytest_run` " +
                "action's `sideEffect` is conservatively classified READ_ONLY at the tool level " +
                "because the tool itself writes no files, but the LLM should be aware test " +
                "fixtures may mutate external state."
        )

        related("java_runtime_exec", Relationship.COMPOSE_WITH,
            "Use `java_runtime_exec` to actually compile a module or run JUnit tests after " +
                "using `build` to understand the module layout and dependencies.")
        related("python_runtime_exec", Relationship.COMPOSE_WITH,
            "Use `python_runtime_exec` to run pytest natively with IDE integration after " +
                "`build(action=pytest_discover)` confirms the test IDs.")
        related("project_structure", Relationship.COMPLEMENT,
            "Use `project_structure` for IDE-level module settings (SDKs, facets, source roots, " +
                "content roots) — orthogonal to the build-file view that `build` provides.")
        related("diagnostics", Relationship.COMPLEMENT,
            "Use `diagnostics` after resolving a dependency question via `build` to verify the " +
                "code compiles cleanly with the intended configuration.")
        related("run_command", Relationship.FALLBACK,
            "Last-resort fallback for build-tool queries that `build` doesn't cover (e.g., " +
                "`./gradlew dependencyUpdates` for version-update reports). Slower and noisier.")

        observation(
            "CLAUDE.md lists `build` as an 11-action tool. The actual source has 26 actions " +
                "(6 Maven + 3 Gradle + 2 IntelliJ module + 4 pip + 5 Poetry + 3 uv + 3 pytest). " +
                "CLAUDE.md is outdated and should be updated."
        )
        mergeOpportunity(
            "`pip_list`, `poetry_list`, and `uv_list` all return installed package lists with " +
                "an optional `search` filter. They could be collapsed into a single " +
                "`python_list(manager=pip|poetry|uv, search?)` action, reducing the action count " +
                "by 2 and making the schema easier to navigate."
        )
        mergeOpportunity(
            "`pip_outdated`, `poetry_outdated`, and `uv_outdated` are structurally identical — " +
                "same concept, different package manager. A `python_outdated(manager?)` action " +
                "would halve the schema cost."
        )
        mergeOpportunity(
            "`uv_lock_status` and `poetry_lock_status` serve the same intent (is the lockfile " +
                "fresh?). A unified `python_lock_status(manager=poetry|uv)` would be cleaner."
        )

        actions {
            // ── Maven ────────────────────────────────────────────────────────────────

            action("maven_dependencies") {
                description {
                    technical("Parse POM and return declared + transitive Maven dependencies via IntelliJ MavenProjectsManager. Supports scope filter and substring search.")
                    plain("List every library the Maven project depends on — like running `mvn dependency:list` but structured and instant.")
                }
                whenLLMUses("When the LLM needs to know which libraries a Maven module depends on — e.g., to check if spring-boot-starter-web is present, or to find the version of jackson-databind.")
                params {
                    optional("module", "string") {
                        llmSeesIt("Module name to inspect. For Maven: module/artifactId name. For Gradle: ':core', 'jira', etc. If omitted, uses root/all modules depending on action.")
                        humanReadable("Which Maven module (by artifactId) to inspect. In a single-module project this is usually omitted.")
                        whenPresent("Only the specified Maven module's POM is parsed.")
                        whenAbsent("All known Maven modules are enumerated and reported.")
                        example("core")
                        example("jira")
                    }
                    optional("scope", "string") {
                        llmSeesIt("Filter by dependency scope (compile, test, runtime, provided) — for maven_dependencies")
                        humanReadable("Limits results to dependencies in a specific Maven scope.")
                        whenPresent("Only dependencies whose scope matches the given value are returned.")
                        whenAbsent("All scopes are returned.")
                        enumValue("compile", "test", "runtime", "provided")
                        example("test")
                    }
                    optional("search", "string") {
                        llmSeesIt("Filter by name/value substring — for maven_dependencies, maven_properties, gradle_dependencies, gradle_tasks, gradle_properties")
                        humanReadable("Case-insensitive substring filter applied to dependency groupId:artifactId strings.")
                        whenPresent("Only dependencies whose coordinate contains the search string are returned.")
                        whenAbsent("All dependencies are returned.")
                        example("spring")
                        example("jackson")
                    }
                }
                rejectsParam("artifact", "Only used by maven_dependency_tree for path-filtering the tree.")
                rejectsParam("plugin", "Only used by maven_effective_pom.")
                rejectsParam("configuration", "Gradle-only. Maven uses 'scope' instead.")
                onSuccess("Returns a structured list of groupId:artifactId:version entries, optionally filtered by scope and search. Each entry includes the declared scope.")
                onFailure("No pom.xml found", "Returns an empty list or a 'no Maven project' message. Switch to gradle_dependencies if this is a Gradle project.")
                onFailure("Module name not found", "Returns results for all modules (fallback) or an empty list. Verify the artifactId with project_modules first.")
                example("All test dependencies") {
                    param("action", "maven_dependencies")
                    param("scope", "test")
                    outcome("Returns all test-scoped dependencies (JUnit, Mockito, etc.) across all modules.")
                }
                example("Find Spring version") {
                    param("action", "maven_dependencies")
                    param("search", "spring-boot")
                    outcome("Returns the spring-boot-starter-* entries with their version strings.")
                }
                verdict {
                    keep("Maven dependency introspection is a daily need when working with multi-module Java/Kotlin projects — saves a full mvn invocation per query.", VerdictSeverity.STRONG)
                }
            }

            action("maven_properties") {
                description {
                    technical("Read POM `<properties>` entries from the IntelliJ Maven project model. Supports substring search on key or value.")
                    plain("Show the key=value properties defined in pom.xml — like `<java.version>21</java.version>` — without opening the file.")
                }
                whenLLMUses("When the LLM needs to know what version variables or build flags are set in the POM, e.g. `java.version`, `project.build.sourceEncoding`, or custom plugin configuration properties.")
                params {
                    optional("module", "string") {
                        llmSeesIt("Module name to inspect. For Maven: module/artifactId name. For Gradle: ':core', 'jira', etc. If omitted, uses root/all modules depending on action.")
                        humanReadable("Scopes the property lookup to a specific Maven module.")
                        whenPresent("Only that module's POM properties are returned.")
                        whenAbsent("Properties from all modules are merged and returned.")
                        example("core")
                    }
                    optional("search", "string") {
                        llmSeesIt("Filter by name/value substring — for maven_dependencies, maven_properties, gradle_dependencies, gradle_tasks, gradle_properties")
                        humanReadable("Filters the property list to entries whose key or value contains the substring.")
                        whenPresent("Only matching properties are returned.")
                        whenAbsent("All properties are returned.")
                        example("version")
                        example("encoding")
                    }
                }
                onSuccess("Returns a list of property key=value pairs as declared in the POM.")
                onFailure("No Maven project", "Empty list. Ensure a pom.xml is present and the Maven project is imported in IntelliJ.")
                example("Find Java version property") {
                    param("action", "maven_properties")
                    param("search", "java.version")
                    outcome("Returns 'java.version=21' or similar.")
                }
                verdict {
                    keep("Avoids parsing XML by hand. Small action, high SNR.")
                }
            }

            action("maven_plugins") {
                description {
                    technical("Return all Maven plugins declared in the `<build><plugins>` section of the POM, with groupId, artifactId, and version.")
                    plain("List every Maven plugin the project uses to build itself — like the compiler plugin, Surefire, Shade, etc.")
                }
                whenLLMUses("When the LLM needs to understand how the project builds, packages, or tests its code — e.g., to check if the Failsafe plugin is configured for integration tests, or to find the Shade plugin version.")
                params {
                    optional("module", "string") {
                        llmSeesIt("Module name to inspect. For Maven: module/artifactId name. For Gradle: ':core', 'jira', etc. If omitted, uses root/all modules depending on action.")
                        humanReadable("Scopes the plugin lookup to a specific Maven module.")
                        whenPresent("Only that module's plugins are returned.")
                        whenAbsent("Plugins from all modules are returned.")
                        example("core")
                    }
                }
                onSuccess("Returns a list of plugin coordinates (groupId:artifactId:version) per module.")
                onFailure("No Maven project", "Empty list.")
                example("List all plugins") {
                    param("action", "maven_plugins")
                    outcome("Returns maven-compiler-plugin, maven-surefire-plugin, etc. with their versions.")
                }
                verdict {
                    keep("Useful when diagnosing build plugin configuration — avoids reading every POM manually.")
                }
            }

            action("maven_profiles") {
                description {
                    technical("Return Maven profiles declared in the POM — id, activation conditions, and whether the profile is currently active.")
                    plain("Show which Maven build profiles exist (e.g., 'dev', 'prod', 'integration-test') and which are active right now.")
                }
                whenLLMUses("When the LLM needs to understand environment-specific build configuration — e.g., checking if a 'docker' profile toggles a dependency, or diagnosing why a build behaves differently in CI.")
                params {
                    optional("module", "string") {
                        llmSeesIt("Module name to inspect. For Maven: module/artifactId name. For Gradle: ':core', 'jira', etc. If omitted, uses root/all modules depending on action.")
                        humanReadable("Scopes the profile lookup to a specific Maven module.")
                        whenPresent("Only that module's profiles are returned.")
                        whenAbsent("Profiles from all modules are returned.")
                        example("core")
                    }
                }
                onSuccess("Returns a list of profile IDs with their activation state and any activation conditions.")
                onFailure("No Maven project or no profiles declared", "Returns empty list.")
                example("List all profiles") {
                    param("action", "maven_profiles")
                    outcome("Returns 'dev (inactive)', 'prod (inactive)', 'integration-test (active by default)' etc.")
                }
                verdict {
                    keep("Niche but irreplaceable — no shell command gives this structured view of active/inactive profile state without running Maven.")
                }
            }

            action("maven_dependency_tree") {
                description {
                    technical("Return the transitive Maven dependency tree for a module, optionally filtered to paths containing a given artifact substring.")
                    plain("Like `mvn dependency:tree` — shows not just direct dependencies but everything they pull in transitively, in a tree format.")
                }
                whenLLMUses("When the LLM needs to understand WHY a transitive dependency is on the classpath — e.g., tracing who pulled in `log4j-core`, or finding all paths to `guava`.")
                params {
                    optional("module", "string") {
                        llmSeesIt("Module name to inspect. For Maven: module/artifactId name. For Gradle: ':core', 'jira', etc. If omitted, uses root/all modules depending on action.")
                        humanReadable("Which Maven module to show the tree for.")
                        whenPresent("Only that module's tree is returned.")
                        whenAbsent("Tree for all modules is returned — can be very large.")
                        example("core")
                    }
                    optional("artifact", "string") {
                        llmSeesIt("Filter dependency tree to paths containing this artifact — for maven_dependency_tree")
                        humanReadable("Narrows the tree to only branches that contain this artifact name — useful for 'why is X on the classpath?'")
                        whenPresent("Only tree paths that pass through the matching artifact are returned.")
                        whenAbsent("The full tree is returned.")
                        example("log4j")
                        example("guava")
                    }
                }
                rejectsParam("scope", "Tree shows all scopes; use maven_dependencies with scope filter for scoped lists.")
                rejectsParam("search", "Use 'artifact' to filter tree paths.")
                onSuccess("Returns an indented tree of groupId:artifactId:version with omitted-duplicate markers.")
                onFailure("No Maven project", "Empty result.")
                example("Find who pulled in log4j") {
                    param("action", "maven_dependency_tree")
                    param("artifact", "log4j")
                    outcome("Returns all tree paths that include log4j-core or log4j-api, showing the parent chain.")
                }
                verdict {
                    keep("Transitive dependency debugging is a common Java task. This is cleaner and faster than spawning mvn.", VerdictSeverity.STRONG)
                }
            }

            action("maven_effective_pom") {
                description {
                    technical("Return the effective (merged) POM for a Maven module — all inherited settings resolved. Optionally filter by plugin artifactId.")
                    plain("Like `mvn help:effective-pom` — shows the POM after all parent inheritance is merged, so you see the final resolved configuration.")
                }
                whenLLMUses("When the LLM needs to understand inherited POM settings that aren't visible in the local pom.xml — e.g., what compiler source/target the parent BOM sets, or what version of the Surefire plugin is inherited.")
                params {
                    optional("module", "string") {
                        llmSeesIt("Module name to inspect. For Maven: module/artifactId name. For Gradle: ':core', 'jira', etc. If omitted, uses root/all modules depending on action.")
                        humanReadable("Which Maven module's effective POM to return.")
                        whenPresent("Only that module's effective POM is returned.")
                        whenAbsent("Returns the root module's effective POM.")
                        example("core")
                    }
                    optional("plugin", "string") {
                        llmSeesIt("Filter by plugin artifactId — for maven_effective_pom")
                        humanReadable("Narrows the effective POM to only the configuration for a specific plugin.")
                        whenPresent("Only the matched plugin's merged configuration block is returned.")
                        whenAbsent("The full effective POM is returned — can be very large for projects with many parents.")
                        example("maven-compiler-plugin")
                        example("maven-surefire-plugin")
                    }
                }
                onSuccess("Returns the resolved POM XML or a filtered plugin configuration block.")
                onFailure("No Maven project", "Empty result.")
                example("Check inherited compiler settings") {
                    param("action", "maven_effective_pom")
                    param("plugin", "maven-compiler-plugin")
                    outcome("Returns the compiler plugin configuration block with source/target version, encoding, and any annotation-processor entries.")
                }
                verdict {
                    keep("Irreplaceable when debugging POM inheritance chains. The alternative is opening a chain of parent POMs by hand.")
                }
            }

            // ── Gradle ───────────────────────────────────────────────────────────────

            action("gradle_dependencies") {
                description {
                    technical("Return Gradle dependencies for a module by parsing `build.gradle(.kts)` and `settings.gradle(.kts)`. Supports configuration filter and substring search.")
                    plain("List the libraries declared in a Gradle build file — like `./gradlew dependencies` but parsed from the file, not executed.")
                }
                whenLLMUses("When the LLM needs to know which libraries a Gradle module depends on — e.g., to verify `implementation(\"org.springframework.boot:spring-boot-starter\")` is present, or to find the Kotlin stdlib version.")
                params {
                    optional("module", "string") {
                        llmSeesIt("Module name to inspect. For Maven: module/artifactId name. For Gradle: ':core', 'jira', etc. If omitted, uses root/all modules depending on action.")
                        humanReadable("Gradle module name, typically the subproject name (e.g., 'core', 'jira', ':agent').")
                        whenPresent("Only that subproject's `build.gradle(.kts)` is parsed.")
                        whenAbsent("Root and all discovered subprojects are enumerated.")
                        example("core")
                        example(":agent")
                    }
                    optional("configuration", "string") {
                        llmSeesIt("Filter by Gradle configuration (implementation, api, testImplementation, etc.) — for gradle_dependencies")
                        humanReadable("Restricts the result to one Gradle dependency configuration (scope equivalent).")
                        whenPresent("Only dependencies in the named configuration are returned.")
                        whenAbsent("All configurations are returned.")
                        enumValue("implementation", "api", "testImplementation", "runtimeOnly", "compileOnly", "annotationProcessor")
                        example("testImplementation")
                    }
                    optional("search", "string") {
                        llmSeesIt("Filter by name/value substring — for maven_dependencies, maven_properties, gradle_dependencies, gradle_tasks, gradle_properties")
                        humanReadable("Case-insensitive substring filter on dependency coordinates.")
                        whenPresent("Only matching dependency lines are returned.")
                        whenAbsent("All dependencies are returned.")
                        example("mockk")
                        example("kotlin")
                    }
                }
                rejectsParam("scope", "Maven-only. Use 'configuration' for Gradle.")
                rejectsParam("artifact", "Use 'search' to filter Gradle dependency results.")
                onSuccess("Returns dependency coordinate strings grouped by configuration block.")
                onFailure("No build.gradle(.kts) found", "Empty result. Verify the module name or try without module to scan all subprojects.")
                example("Find all test dependencies in :agent") {
                    param("action", "gradle_dependencies")
                    param("module", "agent")
                    param("configuration", "testImplementation")
                    outcome("Returns all testImplementation dependencies for the agent subproject.")
                }
                verdict {
                    keep("Essential for Gradle-based projects. Parsing the build file is much faster than running the daemon.", VerdictSeverity.STRONG)
                }
            }

            action("gradle_tasks") {
                description {
                    technical("Return Gradle tasks defined in `build.gradle(.kts)` files for one or all subprojects. Supports substring search on task name or description.")
                    plain("List every Gradle task available — like `./gradlew tasks` but without starting the Gradle daemon.")
                }
                whenLLMUses("When the LLM needs to know what tasks a Gradle project exposes — e.g., to find the correct task name for running integration tests, or to discover if a custom `dockerBuild` task exists.")
                params {
                    optional("module", "string") {
                        llmSeesIt("Module name to inspect. For Maven: module/artifactId name. For Gradle: ':core', 'jira', etc. If omitted, uses root/all modules depending on action.")
                        humanReadable("Scopes task discovery to a specific subproject.")
                        whenPresent("Only tasks from that subproject's build file are returned.")
                        whenAbsent("Tasks from all subprojects are returned.")
                        example("agent")
                    }
                    optional("search", "string") {
                        llmSeesIt("Filter by name/value substring — for maven_dependencies, maven_properties, gradle_dependencies, gradle_tasks, gradle_properties")
                        humanReadable("Filters the task list by name or description substring.")
                        whenPresent("Only matching tasks are returned.")
                        whenAbsent("All tasks are returned.")
                        example("test")
                        example("docker")
                    }
                }
                onSuccess("Returns a list of task names with their group and description.")
                onFailure("No build.gradle(.kts)", "Empty list.")
                example("Find test-related tasks in agent") {
                    param("action", "gradle_tasks")
                    param("module", "agent")
                    param("search", "test")
                    outcome("Returns 'test', 'testClasses', 'generateAllGoldenSnapshots', etc.")
                }
                verdict {
                    keep("Useful for discovering custom task names before calling run_command.")
                }
            }

            action("gradle_properties") {
                description {
                    technical("Return properties from `gradle.properties` files (project root and per-subproject) plus `ext { }` block values parsed from `build.gradle(.kts)`.")
                    plain("Show the key=value properties from `gradle.properties` — like plugin versions, JVM args, and custom project-wide settings.")
                }
                whenLLMUses("When the LLM needs to know Gradle project-wide configuration values — e.g., the `pluginVersion` used in `gradle.properties`, JVM memory flags, or `kotlin.code.style` settings.")
                params {
                    optional("module", "string") {
                        llmSeesIt("Module name to inspect. For Maven: module/artifactId name. For Gradle: ':core', 'jira', etc. If omitted, uses root/all modules depending on action.")
                        humanReadable("Scopes the property lookup to a subproject's `gradle.properties`.")
                        whenPresent("Returns that subproject's local properties merged with root properties.")
                        whenAbsent("Returns root `gradle.properties` plus all subproject overrides.")
                        example("agent")
                    }
                    optional("search", "string") {
                        llmSeesIt("Filter by name/value substring — for maven_dependencies, maven_properties, gradle_dependencies, gradle_tasks, gradle_properties")
                        humanReadable("Filters to properties whose key or value contains the substring.")
                        whenPresent("Only matching properties are returned.")
                        whenAbsent("All properties are returned.")
                        example("version")
                        example("jvm")
                    }
                }
                onSuccess("Returns a list of key=value pairs from Gradle properties files.")
                onFailure("No gradle.properties", "Empty list.")
                example("Find plugin version property") {
                    param("action", "gradle_properties")
                    param("search", "pluginVersion")
                    outcome("Returns 'pluginVersion=1.4.2' or similar.")
                }
                verdict {
                    keep("Low-cost action that avoids opening files manually. Especially useful for `gradle.properties` version variables in IntelliJ plugin projects.")
                }
            }

            // ── IntelliJ Module Graph ────────────────────────────────────────────────

            action("project_modules") {
                description {
                    technical("Return all IntelliJ modules registered in the project via `ModuleManager.getInstance(project).modules`. Includes module name, type, and root path.")
                    plain("List every module IntelliJ knows about — like looking at the Project Structure dialog's Modules tab, but as text the agent can read.")
                }
                whenLLMUses("When the LLM needs to understand the project's module structure before scoping a dependency query, or when it needs to find the right module name to pass to another `build` action.")
                params {
                    // no params — all parameters are silently ignored
                }
                rejectsParam("module", "project_modules lists all modules; there is no filter.")
                rejectsParam("search", "Not supported. Filter the returned list client-side if needed.")
                onSuccess("Returns a list of module names with their type (JAVA_MODULE, etc.) and content-root paths.")
                onFailure("No modules registered", "Returns empty list — unusual for a normal IntelliJ project.")
                example("Discover all modules") {
                    param("action", "project_modules")
                    outcome("Returns 'core (JAVA_MODULE, /path/to/core)', 'agent (JAVA_MODULE, /path/to/agent)', etc.")
                }
                verdict {
                    keep("The authoritative source of module names for all other `build` actions. Essential as a first step in multi-module projects.", VerdictSeverity.STRONG)
                }
            }

            action("module_dependency_graph") {
                description {
                    technical("Return the IntelliJ module dependency graph via `ModuleRootManager.getInstance(module).dependencies`. Supports transitive expansion, library inclusion, and cycle detection.")
                    plain("Show which modules depend on which other modules — like the Project Structure dependency arrows, but as text. Can also detect circular dependencies.")
                }
                whenLLMUses("When the LLM needs to understand inter-module wiring — e.g., to verify that `:agent` only depends on `:core` (per architecture rules), or to detect a circular dependency between modules.")
                params {
                    optional("module", "string") {
                        llmSeesIt("Module name to inspect. For Maven: module/artifactId name. For Gradle: ':core', 'jira', etc. If omitted, uses root/all modules depending on action.")
                        humanReadable("Scopes the graph to one module — shows what that module depends on.")
                        whenPresent("Only that module's dependency edges are returned.")
                        whenAbsent("The full inter-module graph is returned.")
                        example("agent")
                    }
                    optional("transitive", "boolean") {
                        llmSeesIt("Include transitive (indirect) dependencies — for module_dependency_graph. Default: false.")
                        humanReadable("When true, follows dependency edges recursively to show the full transitive closure.")
                        whenPresent("The graph includes indirect module dependencies (A→B→C is reported as A→B, A→C).")
                        whenAbsent("Only direct module dependencies are shown. Default is false.")
                        example("true")
                    }
                    optional("include_libraries", "boolean") {
                        llmSeesIt("Include library dependencies in output — for module_dependency_graph. Default: false.")
                        humanReadable("When true, includes external JAR/library nodes alongside module nodes in the graph.")
                        whenPresent("Library nodes appear in the graph with their artifact coordinates.")
                        whenAbsent("Only module-to-module edges are shown. Default is false.")
                        example("true")
                    }
                    optional("detect_cycles", "boolean") {
                        llmSeesIt("Run circular dependency detection — for module_dependency_graph. Default: true.")
                        humanReadable("When true, runs a DFS cycle check and reports any detected circular dependency paths.")
                        whenPresent("A cycle-detection result is appended: either 'No cycles detected' or the cycle path.")
                        whenAbsent("Cycle detection runs by default. Pass false only if the graph is very large and you don't need it.")
                        example("false")
                    }
                }
                onSuccess("Returns adjacency list of module→[dependencies] edges. If detect_cycles=true (default), appends cycle report.")
                onFailure("Module not found", "Returns empty graph. Use project_modules to confirm the module name first.")
                example("Verify agent only depends on core") {
                    param("action", "module_dependency_graph")
                    param("module", "agent")
                    outcome("Returns 'agent → [core]' confirming the single allowed dependency edge.")
                }
                example("Full graph with cycle detection") {
                    param("action", "module_dependency_graph")
                    param("detect_cycles", "true")
                    outcome("Returns full module adjacency list plus 'No cycles detected' or the cycle path if one exists.")
                }
                verdict {
                    keep("Architecturally critical — the only way to verify inter-module dependency contracts (e.g., `:agent` must only depend on `:core`) without parsing build files manually.", VerdictSeverity.STRONG)
                }
            }

            // ── pip ──────────────────────────────────────────────────────────────────

            action("pip_list") {
                description {
                    technical("Run `pip list --format=columns` and return installed packages with versions. Supports substring search.")
                    plain("List every Python package installed in the current pip environment — like `pip list` in the terminal.")
                }
                whenLLMUses("When the LLM needs to check which Python packages are installed — e.g., to verify `requests` is available before importing it, or to find the installed version of `django`.")
                params {
                    optional("search", "string") {
                        llmSeesIt("Filter by name/value substring — for pip_list")
                        humanReadable("Filters the package list by name substring (case-insensitive).")
                        whenPresent("Only packages whose name contains the search string are returned.")
                        whenAbsent("All installed packages are listed.")
                        example("django")
                        example("boto")
                    }
                }
                onSuccess("Returns a list of package name + version pairs.")
                onFailure("pip not on PATH", "Returns an error message. The LLM should fall back to run_command pip list.")
                example("Check if requests is installed") {
                    param("action", "pip_list")
                    param("search", "requests")
                    outcome("Returns 'requests 2.31.0' if installed, empty if not.")
                }
                verdict {
                    keep("Convenient but thin — wraps a single pip command. Merge candidate with poetry_list and uv_list.")
                    drop("Could be `run_command pip list | grep <term>` at the cost of one extra tool call. Low drop priority unless action count becomes a concern.", VerdictSeverity.WEAK)
                }
            }

            action("pip_outdated") {
                description {
                    technical("Run `pip list --outdated --format=columns` and return packages with current and latest versions.")
                    plain("Show which pip packages have newer versions available — like `pip list --outdated`.")
                }
                whenLLMUses("When the LLM is asked to check for dependency updates or security patches in a pip-managed project.")
                onSuccess("Returns a list of package name, current version, and latest version.")
                onFailure("pip not on PATH or network unavailable", "Returns error. The outdated check requires a live PyPI connection.")
                example("Check outdated packages") {
                    param("action", "pip_outdated")
                    outcome("Returns 'django 4.1.0 → 5.0.6', 'requests 2.28.0 → 2.31.0', etc.")
                }
                verdict {
                    keep("Useful for security/update audits. Merge candidate with poetry_outdated and uv_outdated.")
                }
            }

            action("pip_show") {
                description {
                    technical("Run `pip show <package>` and return detailed metadata: version, summary, home-page, author, location, requires, required-by.")
                    plain("Show everything pip knows about one installed package — dependencies it needs, who needs it, where it's installed.")
                }
                whenLLMUses("When the LLM needs to understand a specific package's dependency surface — e.g., to check what `celery` requires before upgrading it, or to find where `numpy` is installed.")
                params {
                    required("package", "string") {
                        llmSeesIt("Package name to inspect — for pip_show, poetry_show")
                        humanReadable("The exact pip package name to inspect.")
                        whenPresent("pip show is run for this package and its metadata returned.")
                        example("django")
                        example("celery")
                    }
                }
                onSuccess("Returns package metadata including version, home-page, requires, and required-by.")
                onFailure("Package not installed", "pip show returns a 'WARNING: Package(s) not found' message.")
                example("Inspect celery") {
                    param("action", "pip_show")
                    param("package", "celery")
                    outcome("Returns 'celery 5.3.4, Requires: billiard, click, kombu, vine, Required-by: django-celery-beat'.")
                }
                verdict {
                    keep("Richer than pip_list for single-package queries. Worth keeping separate from pip_list.")
                }
            }

            action("pip_dependencies") {
                description {
                    technical("Parse declared dependencies from `requirements.txt`, `setup.cfg`, `setup.py`, and `pyproject.toml` and return them as a structured list.")
                    plain("Read what the project *declares* it needs (not what's installed) — like opening requirements.txt but understanding all four Python dependency file formats.")
                }
                whenLLMUses("When the LLM needs to understand what a Python project explicitly declares as its dependencies — distinct from pip_list which shows what's currently installed.")
                params {
                    optional("search", "string") {
                        llmSeesIt("Filter by name/value substring — for pip_dependencies")
                        humanReadable("Filters the declared dependency list by name substring.")
                        whenPresent("Only declared dependencies matching the substring are returned.")
                        whenAbsent("All declared dependencies are returned.")
                        example("boto")
                    }
                }
                onSuccess("Returns a list of declared dependency specifiers (e.g., 'django>=4.0,<6.0') from all discovered dependency files.")
                onFailure("No dependency files found", "Empty list. The project may use a different format (e.g., Poetry).")
                example("List all declared dependencies") {
                    param("action", "pip_dependencies")
                    outcome("Returns 'django>=4.2', 'celery~=5.3', 'boto3>=1.26' etc. from requirements.txt.")
                }
                verdict {
                    keep("Distinguishes declared from installed — important for packaging and dependency audits.")
                }
            }

            // ── Poetry ───────────────────────────────────────────────────────────────

            action("poetry_list") {
                description {
                    technical("Run `poetry show` and return installed packages with versions. Supports substring search.")
                    plain("List every Python package in the Poetry-managed virtualenv — like `poetry show` in the terminal.")
                }
                whenLLMUses("When the LLM is working in a Poetry project and needs to check installed packages — analogous to pip_list for Poetry environments.")
                params {
                    optional("search", "string") {
                        llmSeesIt("Filter by name/value substring — for poetry_list")
                        humanReadable("Filters the package list by name substring.")
                        whenPresent("Only packages whose name contains the search string are returned.")
                        whenAbsent("All packages are returned.")
                        example("pydantic")
                    }
                }
                onSuccess("Returns a list of package name + version pairs from the Poetry-managed environment.")
                onFailure("poetry not on PATH", "Returns an error. Fall back to pip_list or run_command poetry show.")
                example("Find pydantic version") {
                    param("action", "poetry_list")
                    param("search", "pydantic")
                    outcome("Returns 'pydantic 2.7.1'.")
                }
                verdict {
                    keep("Merge candidate with pip_list and uv_list into a single python_list(manager) action.", VerdictSeverity.WEAK)
                }
            }

            action("poetry_outdated") {
                description {
                    technical("Run `poetry show --outdated` and return packages with current and latest available versions.")
                    plain("Show which Poetry packages have newer versions available — like `poetry show --outdated`.")
                }
                whenLLMUses("When the LLM is asked to audit dependencies for updates in a Poetry project.")
                onSuccess("Returns a list of package name, installed version, and latest available version.")
                onFailure("poetry not on PATH or network unavailable", "Returns error.")
                example("Check for updates") {
                    param("action", "poetry_outdated")
                    outcome("Returns 'pydantic 2.0.0 → 2.7.1', 'fastapi 0.100.0 → 0.111.0', etc.")
                }
                verdict {
                    keep("Merge candidate with pip_outdated and uv_outdated.", VerdictSeverity.WEAK)
                }
            }

            action("poetry_show") {
                description {
                    technical("Run `poetry show <package>` and return detailed metadata for one installed Poetry package.")
                    plain("Show everything Poetry knows about one specific package — version, description, dependencies, dependents.")
                }
                whenLLMUses("When the LLM needs detailed information about a single package in a Poetry project — analogous to pip_show.")
                params {
                    required("package", "string") {
                        llmSeesIt("Package name to inspect — for pip_show, poetry_show")
                        humanReadable("The exact package name in the Poetry environment to inspect.")
                        whenPresent("poetry show is run for this package and its metadata returned.")
                        example("pydantic")
                        example("sqlalchemy")
                    }
                }
                onSuccess("Returns package metadata including version, description, dependencies, and dependents.")
                onFailure("Package not installed", "poetry show returns a not-found error.")
                example("Inspect sqlalchemy") {
                    param("action", "poetry_show")
                    param("package", "sqlalchemy")
                    outcome("Returns SQLAlchemy version, its transitive dependencies, and which project packages depend on it.")
                }
                verdict {
                    keep("Parallel to pip_show for Poetry environments. Worth keeping as a distinct action.")
                }
            }

            action("poetry_lock_status") {
                description {
                    technical("Check whether `poetry.lock` is consistent with `pyproject.toml` — reports lock file age, package count, and whether `poetry lock --check` passes.")
                    plain("Tell the LLM whether the Poetry lockfile is up to date — like running `poetry lock --check` without modifying anything.")
                }
                whenLLMUses("When the LLM suspects the lockfile is stale after a `pyproject.toml` edit, or wants to confirm lockfile health before running tests.")
                onSuccess("Returns lock file path, age, package count, and whether it's consistent with pyproject.toml.")
                onFailure("poetry not on PATH", "Returns error.")
                example("Check lock consistency") {
                    param("action", "poetry_lock_status")
                    outcome("Returns 'poetry.lock: consistent, 247 packages, last updated 2026-05-08' or 'INCONSISTENT: poetry.lock is out of date'.")
                }
                verdict {
                    keep("Merge candidate with uv_lock_status into python_lock_status(manager).")
                }
            }

            action("poetry_scripts") {
                description {
                    technical("Parse `[tool.poetry.scripts]` from `pyproject.toml` and return the script name → entry-point mapping.")
                    plain("List the command-line scripts the package installs — like looking at `[tool.poetry.scripts]` in pyproject.toml, but without opening the file.")
                }
                whenLLMUses("When the LLM needs to know what CLI entry points a Poetry package exposes — e.g., to find the correct command name for a Django management command wrapper or a custom CLI tool.")
                onSuccess("Returns a list of script-name → module:function entry points from pyproject.toml.")
                onFailure("No [tool.poetry.scripts] section", "Empty list.")
                example("Find CLI entry points") {
                    param("action", "poetry_scripts")
                    outcome("Returns 'myapp = myapp.cli:main', 'myapp-worker = myapp.worker:start'.")
                }
                verdict {
                    keep("Niche but has no shell equivalent that's as clean. Low schema cost.")
                }
            }

            // ── uv ───────────────────────────────────────────────────────────────────

            action("uv_list") {
                description {
                    technical("Run `uv pip list` and return installed packages with versions. Supports substring search.")
                    plain("List every Python package in the uv-managed environment — like `uv pip list` in the terminal.")
                }
                whenLLMUses("When the LLM is working in a uv-managed Python project and needs to check installed packages.")
                params {
                    optional("search", "string") {
                        llmSeesIt("Filter by name/value substring — for uv_list")
                        humanReadable("Filters the package list by name substring.")
                        whenPresent("Only packages whose name contains the search string are returned.")
                        whenAbsent("All packages are returned.")
                        example("httpx")
                    }
                }
                onSuccess("Returns a list of package name + version pairs from the uv environment.")
                onFailure("uv not on PATH", "Returns error. Fall back to pip_list.")
                example("Check if httpx is installed") {
                    param("action", "uv_list")
                    param("search", "httpx")
                    outcome("Returns 'httpx 0.27.0' if installed.")
                }
                verdict {
                    keep("Merge candidate with pip_list and poetry_list.", VerdictSeverity.WEAK)
                }
            }

            action("uv_outdated") {
                description {
                    technical("Run `uv pip list --outdated` and return packages with current and latest versions.")
                    plain("Show which uv packages have newer versions available.")
                }
                whenLLMUses("When the LLM is auditing dependencies for updates in a uv-managed project.")
                onSuccess("Returns a list of package name, installed version, and latest available version.")
                onFailure("uv not on PATH or network unavailable", "Returns error.")
                example("Check outdated uv packages") {
                    param("action", "uv_outdated")
                    outcome("Returns 'httpx 0.24.0 → 0.27.0', 'pydantic 2.0 → 2.7.1', etc.")
                }
                verdict {
                    keep("Merge candidate with pip_outdated and poetry_outdated.", VerdictSeverity.WEAK)
                }
            }

            action("uv_lock_status") {
                description {
                    technical("Check whether `uv.lock` is consistent with `pyproject.toml` — reports lock file age, package count, and lock check result.")
                    plain("Tell the LLM whether the uv lockfile is up to date — like running `uv lock --check`.")
                }
                whenLLMUses("When the LLM wants to confirm uv lockfile health before running tests or installing packages.")
                onSuccess("Returns lock file path, age, package count, and whether it's consistent with pyproject.toml.")
                onFailure("uv not on PATH", "Returns error.")
                example("Check uv lock consistency") {
                    param("action", "uv_lock_status")
                    outcome("Returns 'uv.lock: consistent, 183 packages' or 'INCONSISTENT: uv.lock is out of date'.")
                }
                verdict {
                    keep("Merge candidate with poetry_lock_status.", VerdictSeverity.WEAK)
                }
            }

            // ── pytest ───────────────────────────────────────────────────────────────

            action("pytest_discover") {
                description {
                    technical("Run `pytest --collect-only -q` and return discovered test IDs. Supports path scoping.")
                    plain("List all the tests pytest can find — like `pytest --collect-only` but returning a structured list of test IDs the agent can reference in pytest_run.")
                }
                whenLLMUses("When the LLM needs to know which tests exist before running them — to get exact test IDs for pytest_run's `path` or `pattern` parameter.")
                params {
                    optional("path", "string") {
                        llmSeesIt("Test path to scope discovery/execution — for pytest_discover, pytest_run, pytest_fixtures")
                        humanReadable("Limits discovery to a specific directory or file path.")
                        whenPresent("Only tests under this path are collected.")
                        whenAbsent("Discovery runs from the project root.")
                        example("tests/")
                        example("tests/test_api.py")
                    }
                }
                onSuccess("Returns a list of test IDs in pytest's dotted-path format (e.g., 'tests/test_api.py::test_create_user').")
                onFailure("pytest not on PATH", "Returns error.")
                onFailure("No tests found in path", "Returns empty list with a 'no tests collected' message.")
                example("Discover all tests") {
                    param("action", "pytest_discover")
                    outcome("Returns 'tests/test_api.py::test_create_user', 'tests/test_api.py::test_delete_user', etc.")
                }
                example("Discover tests in a module") {
                    param("action", "pytest_discover")
                    param("path", "tests/services/")
                    outcome("Returns only tests under tests/services/.")
                }
                verdict {
                    keep("Essential precursor to pytest_run. Avoids the LLM guessing test IDs.", VerdictSeverity.STRONG)
                }
            }

            action("pytest_run") {
                description {
                    technical("Run pytest with optional path, `-k` pattern filter, and `-m` marker filter. Returns test results summary and failure output.")
                    plain("Actually run the pytest tests — with the same filtering options as the `pytest` command line. Unlike python_runtime_exec, this uses a shell subprocess and works in any IDE.")
                }
                whenLLMUses("When the LLM needs to run a subset of Python tests to verify a fix — after using pytest_discover to confirm test IDs, or when the LLM knows the test path directly.")
                params {
                    optional("path", "string") {
                        llmSeesIt("Test path to scope discovery/execution — for pytest_discover, pytest_run, pytest_fixtures")
                        humanReadable("Scopes test execution to a specific file or directory.")
                        whenPresent("Only tests in this path are run.")
                        whenAbsent("pytest runs from the project root.")
                        example("tests/test_api.py")
                        example("tests/test_api.py::test_create_user")
                    }
                    optional("pattern", "string") {
                        llmSeesIt("Test name pattern filter (pytest -k) — for pytest_run")
                        humanReadable("Keyword expression passed to pytest's `-k` flag — matches test names by substring or boolean expression.")
                        whenPresent("Only tests whose name matches the expression are run.")
                        whenAbsent("No -k filter is applied.")
                        example("test_create")
                        example("test_create or test_delete")
                        example("not slow")
                    }
                    optional("markers", "string") {
                        llmSeesIt("Marker expression filter (pytest -m) — for pytest_run")
                        humanReadable("Marker expression passed to pytest's `-m` flag — selects tests by their pytest marks.")
                        whenPresent("Only tests decorated with matching marks are run.")
                        whenAbsent("No -m filter is applied.")
                        example("integration")
                        example("not slow and not external")
                    }
                }
                onSuccess("Returns test count (passed/failed/error/skipped), duration, and failure output for each failing test.")
                onFailure("pytest not on PATH", "Returns error.")
                onFailure("No tests match filters", "Returns 'no tests ran' with a collected=0 note.")
                example("Run a single test file") {
                    param("action", "pytest_run")
                    param("path", "tests/test_api.py")
                    outcome("Returns '12 passed in 0.45s' or a failure report with traceback.")
                }
                example("Run integration tests only") {
                    param("action", "pytest_run")
                    param("markers", "integration")
                    outcome("Runs only @pytest.mark.integration tests and returns results.")
                }
                verdict {
                    keep("Shell-based pytest runner — works without the Python plugin. Complements python_runtime_exec (native runner with IDE UI). Both should stay.", VerdictSeverity.STRONG)
                }
            }

            action("pytest_fixtures") {
                description {
                    technical("Run `pytest --fixtures -q` and return fixture names, scopes, and docstrings. Supports path scoping.")
                    plain("List every pytest fixture available — like `pytest --fixtures` — so the LLM knows what setup helpers are available before writing a new test.")
                }
                whenLLMUses("When the LLM is writing a new pytest test and needs to know which fixtures are already defined — e.g., to find a `db_session` fixture or check the scope of a `client` fixture.")
                params {
                    optional("path", "string") {
                        llmSeesIt("Test path to scope discovery/execution — for pytest_discover, pytest_run, pytest_fixtures")
                        humanReadable("Scopes fixture discovery to a specific directory (conftest.py files in that subtree are included).")
                        whenPresent("Fixtures defined in conftest.py files under this path are returned.")
                        whenAbsent("All fixtures visible from the project root are returned.")
                        example("tests/")
                    }
                }
                onSuccess("Returns a list of fixture names with their scope (function/class/module/session) and docstring if present.")
                onFailure("pytest not on PATH", "Returns error.")
                example("Find all fixtures") {
                    param("action", "pytest_fixtures")
                    outcome("Returns 'db_session (scope=function): Creates an isolated DB session for each test', 'client (scope=module): FastAPI test client', etc.")
                }
                verdict {
                    keep("Avoids the LLM reading all conftest.py files manually. Useful when test suite has many fixtures.")
                }
            }
        }
    }
}

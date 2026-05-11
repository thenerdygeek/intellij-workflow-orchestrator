package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.execution.RunManager
import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.application.ApplicationConfigurationType
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Run configuration CRUD — create, modify, delete, and list IntelliJ run/debug configurations.
 *
 * Split from the monolithic RuntimeTool to isolate the heavy config creation params
 * (12 params for create_run_config alone) from the simpler execution operations.
 */
class RuntimeConfigTool : AgentTool {

    override val name = "runtime_config"

    override val description = """
IntelliJ run configuration management — create, modify, delete, and list run/debug configurations.

Actions and their parameters:
- get_run_configurations(type_filter?) → List configs (type_filter: application|spring_boot|junit|gradle|remote_debug)
- create_run_config(name, type, main_class?, test_class?, test_method?, module?, env_vars?, vm_options?, program_args?, working_dir?, active_profiles?, port?) → Create config (type: application|spring_boot|junit|gradle|remote_debug; main_class required for application/spring_boot; test_class required for junit)
- modify_run_config(name, env_vars?, replace_env_vars?, vm_options?, program_args?, working_dir?, active_profiles?) → Modify existing config (at least one change required; env_vars are MERGED with existing by default, set replace_env_vars=true to replace all)
- delete_run_config(name) → Delete config (only [Agent]-prefixed configs)

description optional: for approval dialog on create/modify/delete.
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf(
                    "get_run_configurations", "create_run_config", "modify_run_config", "delete_run_config"
                )
            ),
            "type_filter" to ParameterProperty(
                type = "string",
                description = "Filter by configuration type — for get_run_configurations",
                enumValues = listOf("application", "spring_boot", "junit", "gradle", "remote_debug")
            ),
            "name" to ParameterProperty(
                type = "string",
                description = "Configuration name — for create_run_config (auto-prefixed with [Agent]), modify_run_config, delete_run_config"
            ),
            "type" to ParameterProperty(
                type = "string",
                description = "Configuration type — for create_run_config",
                enumValues = listOf("application", "spring_boot", "junit", "gradle", "remote_debug")
            ),
            "main_class" to ParameterProperty(
                type = "string",
                description = "Fully qualified main class (required for application/spring_boot) — for create_run_config"
            ),
            "test_class" to ParameterProperty(
                type = "string",
                description = "Fully qualified test class (required for junit) — for create_run_config"
            ),
            "test_method" to ParameterProperty(
                type = "string",
                description = "Specific test method name (junit only) — for create_run_config"
            ),
            "module" to ParameterProperty(
                type = "string",
                description = "Module name — for create_run_config (auto-detected if omitted)"
            ),
            "env_vars" to ParameterProperty(
                type = "object",
                description = "Environment variables as key-value pairs — for create_run_config, modify_run_config. In modify_run_config, these are MERGED with existing env vars by default (use replace_env_vars=true for full replacement)"
            ),
            "replace_env_vars" to ParameterProperty(
                type = "boolean",
                description = "If true, replace ALL existing env vars instead of merging — for modify_run_config only (default: false)"
            ),
            "vm_options" to ParameterProperty(
                type = "string",
                description = "JVM options — for create_run_config, modify_run_config"
            ),
            "program_args" to ParameterProperty(
                type = "string",
                description = "Program arguments — for create_run_config, modify_run_config"
            ),
            "working_dir" to ParameterProperty(
                type = "string",
                description = "Working directory — for create_run_config, modify_run_config"
            ),
            "active_profiles" to ParameterProperty(
                type = "string",
                description = "Spring Boot active profiles, comma-separated — for create_run_config, modify_run_config"
            ),
            "port" to ParameterProperty(
                type = "integer",
                description = "Remote debug port (default 5005) — for create_run_config"
            ),
            "description" to ParameterProperty(
                type = "string",
                description = "Brief description of what this action does and why (shown to user in approval dialog) — for create_run_config, modify_run_config, delete_run_config"
            )
        ),
        required = listOf("action")
    )

    override val allowedWorkers = setOf(
        WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER, WorkerType.ORCHESTRATOR, WorkerType.TOOLER
    )

    override fun isWriteAction(action: String?): Boolean = action in setOf(
        "create_run_config", "modify_run_config", "delete_run_config"
    )

    override fun documentation(): ToolDocumentation = toolDoc("runtime_config") {
        summary {
            technical("CRUD over IntelliJ's RunManager: list/create/modify/delete RunConfiguration entries across application, spring_boot, junit, gradle, and remote_debug factories. Create/modify use a mix of typed setters (ApplicationConfiguration) and reflection-based setters (Spring Boot, JUnit persistent data fields, Gradle, remote debug PORT/HOST/SERVER_MODE) so the tool can drive config types whose APIs aren't on the compile-time classpath. Per-property failures are collected and reported alongside the success result; delete is gated to [Agent]-prefixed configs only.")
            plain("Lets the LLM manage the IDE's saved Run/Debug configurations the way a user does via Edit Configurations. Creates a new entry, tweaks env vars or VM options on an existing one, or removes a config the agent created earlier. Agent-created configs are prefixed [Agent] so the tool can never delete a user-owned configuration.")
        }
        sideEffect(SideEffectKind.IDE_MUTATION)
        whatLLMSees(description)
        verdict {
            keep(
                "Programmatic run-config CRUD is the only way for the LLM to create and launch a named " +
                    "configuration without asking the user to manually open Edit Configurations. Pairs with " +
                    "runtime_exec.run_config to form the full 'create → run → clean up' cycle. The [Agent]-prefix " +
                    "safety guard and the reflect-based per-property failure reporting make it safe and debuggable.",
                VerdictSeverity.STRONG,
            )
        }
        counterfactual(
            "Without this tool, the LLM has no way to programmatically create or modify run configurations. " +
                "The only alternative is to ask the user to open Edit Configurations and set them up manually, " +
                "which breaks autonomous debugging and test-isolation workflows. runtime_exec.run_config can " +
                "only launch configurations that already exist; it cannot create them.",
        )
        llmMistake(
            "Providing `name` without the '[Agent] ' prefix when calling modify_run_config or delete_run_config. " +
                "On create, the prefix is added automatically; on modify/delete the caller must use the full " +
                "prefixed name (e.g. '[Agent] Run MyApp Dev').",
        )
        llmMistake(
            "Calling create_run_config with type=application or type=spring_boot without supplying main_class. " +
                "The tool returns an immediate error in that case — the LLM must use find_definition or " +
                "search_code to resolve the fully qualified class name first.",
        )
        llmMistake(
            "Creating a new config every iteration of a debug loop instead of reusing or modifying the " +
                "existing [Agent]-prefixed config. Results in a proliferation of stale configs visible in " +
                "the user's Run dropdown. Call get_run_configurations first to check if the config exists.",
        )
        llmMistake(
            "Assuming modify_run_config replaces env vars by default. The default is MERGE — existing keys " +
                "are preserved. Supply replace_env_vars=true only when a full replacement is intended.",
        )
        downside(
            "Mutates persistent IDE state (RunManager XML) — created configs survive IDE restart and remain " +
                "visible to the user in the Run dropdown. The [Agent]-prefix guard prevents delete on user-owned " +
                "configs, but create and modify have no equivalent guard for non-[Agent] configs (modify accepts " +
                "any existing config name).",
        )
        downside(
            "Spring Boot, JUnit, Gradle, and remote_debug configuration types are resolved via reflection " +
                "(`Class.forName`). If the required plugin is not installed, the factory resolution returns " +
                "null and create_run_config fails with a descriptive error — but there is no pre-flight check " +
                "before attempting the create.",
        )
        observation(
            "runtime_config is NOT in AgentLoop.WRITE_TOOLS but overrides AgentTool.isWriteAction() so the " +
                "plan-mode execution guard blocks create_run_config, modify_run_config, and delete_run_config. " +
                "Bug fixed: plan-mode bypass for the 3 mutating actions (Batches 16+25 of the Phase 5 swarm). " +
                "Note: these actions are still not in APPROVAL_TOOLS — the only mutation guard is [Agent]-prefix on delete.",
        )
        related("runtime_exec", Relationship.COMPOSE_WITH, "Use runtime_exec.run_config to launch the configuration after creating it with create_run_config.")
        related("debug_breakpoints", Relationship.COMPLEMENT, "Set breakpoints before launching a debug configuration; debug_breakpoints.attach_to_process attaches to an already-running process instead of creating a config.")
        related("debug_step", Relationship.SEE_ALSO, "Once a debug session is running (started via runtime_exec with mode=debug), use debug_step to control execution.")
        observation(
            "modify_run_config accepts any existing configuration name (not just [Agent]-prefixed ones). " +
                "This asymmetry with delete_run_config is intentional (tweaking existing configs is low risk) " +
                "but could surprise users who expect the same guard on all mutating actions.",
        )
        observation(
            "The tool dispatches via ApplicationManager.invokeAndWait for RunManager mutations on create and " +
                "modify — both call addConfiguration on the EDT. This means the suspend execute() method can " +
                "briefly block a Dispatchers.IO thread on EDT round-trips. Low risk in practice (RunManager " +
                "writes are fast) but worth noting.",
        )
        actions {
            action("get_run_configurations") {
                description {
                    technical("Lists RunManager.allSettings, optionally filtered by type id/displayName substring. Marks the currently selected configuration with [SELECTED] and extracts main class, module, VM options, and redacted env-var keys via reflection.")
                    plain("'What run configurations exist in this project?' — same content as the run-config dropdown next to the green Run arrow.")
                }
                whenLLMUses("Before create/modify/delete to discover existing config names; to find the currently selected configuration; to filter to a specific type (e.g. all Spring Boot configs).")
                params {
                    optional("type_filter", "string") {
                        llmSeesIt("Filter by configuration type — for get_run_configurations")
                        humanReadable("Restrict the list to one configuration kind.")
                        whenPresent("Only configs whose type id or display name contains the filter token are returned.")
                        whenAbsent("All configurations are returned.")
                        enumValue("application", "spring_boot", "junit", "gradle", "remote_debug")
                        example("spring_boot")
                    }
                }
                onSuccess("Returns 'Run Configurations (N total):' followed by per-config blocks (name + [SELECTED] marker, Type, optional Main class, Module, VM options, Env vars with redacted values).")
                onFailure("internal exception", "Returns 'Error listing run configurations: <msg>' with isError=true.")
                example("list all configs") {
                    param("action", "get_run_configurations")
                    outcome("LLM sees every saved run configuration with the selected one marked.")
                }
                example("list only Spring Boot configs") {
                    param("action", "get_run_configurations")
                    param("type_filter", "spring_boot")
                    outcome("Returns only Spring Boot configurations.")
                }
            }
            action("create_run_config") {
                description {
                    technical("Creates a new RunConfiguration via the type's ConfigurationFactory (resolved by reflection for spring_boot, junit, gradle, remote_debug — only application is direct). The created config name is auto-prefixed with '[Agent] '. Per-type setters apply: ApplicationConfiguration uses typed setters; Spring Boot/Gradle/JUnit use reflection (setMainClassName/setVMParameters/setProgramParameters/setActiveProfiles/JUnit persistent data TEST_OBJECT+MAIN_CLASS_NAME+METHOD_NAME); remote_debug sets PORT/HOST/SERVER_MODE fields. Module is resolved via ModuleManager.findModuleByName. Failures per property are collected and surfaced as warnings in the result rather than aborting the create.")
                    plain("Builds a brand-new Run/Debug configuration in the IDE. The LLM picks the type (application, Spring Boot, JUnit, Gradle, remote debug) and supplies the relevant fields; the tool wires them in. The new config shows up in the Run dropdown with an [Agent] prefix so it's clearly distinguishable.")
                }
                whenLLMUses("To set up a launch configuration the project doesn't have yet — e.g. a Spring Boot run with custom profiles, a JUnit single-test config, a remote-debug attach config — before launching it via runtime_exec.run_config.")
                params {
                    required("name", "string") {
                        llmSeesIt("Configuration name — for create_run_config (auto-prefixed with [Agent]), modify_run_config, delete_run_config")
                        humanReadable("The configuration's display name. Auto-prefixed with '[Agent] ' on create.")
                        whenPresent("Used as the new config's name after '[Agent] ' prefix is added.")
                        constraint("must not collide with an existing config of the same prefixed name")
                        example("Run UserService Tests")
                    }
                    required("type", "string") {
                        llmSeesIt("Configuration type — for create_run_config")
                        humanReadable("Which kind of run configuration to create.")
                        whenPresent("Selects the ConfigurationFactory and per-type setter pipeline.")
                        enumValue("application", "spring_boot", "junit", "gradle", "remote_debug")
                        example("spring_boot")
                    }
                    optional("main_class", "string") {
                        llmSeesIt("Fully qualified main class (required for application/spring_boot) — for create_run_config")
                        humanReadable("Fully qualified class name with a main() method (or @SpringBootApplication).")
                        whenPresent("Set on the configuration via setMainClassName.")
                        whenAbsent("Required when type is application or spring_boot — create_run_config returns an error in those cases.")
                        constraint("required when type is application or spring_boot")
                        example("com.example.MyApp")
                    }
                    optional("test_class", "string") {
                        llmSeesIt("Fully qualified test class (required for junit) — for create_run_config")
                        humanReadable("Fully qualified test class name.")
                        whenPresent("Written into the JUnit configuration's persistent data MAIN_CLASS_NAME field with TEST_OBJECT=class (or method if test_method also set).")
                        whenAbsent("Required when type is junit — create_run_config returns an error in that case.")
                        constraint("required when type is junit")
                        example("com.example.UserServiceTest")
                    }
                    optional("test_method", "string") {
                        llmSeesIt("Specific test method name (junit only) — for create_run_config")
                        humanReadable("A single test method to run within test_class.")
                        whenPresent("Writes METHOD_NAME on the JUnit persistent data and flips TEST_OBJECT to method.")
                        whenAbsent("The whole test_class is run.")
                        example("testCreateUser")
                    }
                    optional("module", "string") {
                        llmSeesIt("Module name — for create_run_config (auto-detected if omitted)")
                        humanReadable("Module to bind the configuration to (classpath/working-dir context).")
                        whenPresent("Resolved via ModuleManager.findModuleByName; failure lists available module names.")
                        whenAbsent("Module is left at the IDE's default.")
                        example("myapp.main")
                    }
                    optional("env_vars", "object") {
                        llmSeesIt("Environment variables as key-value pairs — for create_run_config, modify_run_config. In modify_run_config, these are MERGED with existing env vars by default (use replace_env_vars=true for full replacement)")
                        humanReadable("Environment variables to set on the launched process, as a JSON object of string keys to string values.")
                        whenPresent("Applied via setEnvs (typed for ApplicationConfiguration, reflective otherwise).")
                        whenAbsent("No environment variables set on the new config.")
                        example("""{"SPRING_PROFILES_ACTIVE": "dev", "LOG_LEVEL": "DEBUG"}""")
                    }
                    optional("vm_options", "string") {
                        llmSeesIt("JVM options — for create_run_config, modify_run_config")
                        humanReadable("JVM arguments passed to the process (heap, system properties, agent flags).")
                        whenPresent("Set via setVMParameters / setVmParameters / setVmOptions (whichever the config type exposes).")
                        whenAbsent("No VM options set on the new config (IDE defaults apply).")
                        example("-Xmx2g -Dspring.profiles.active=dev")
                    }
                    optional("program_args", "string") {
                        llmSeesIt("Program arguments — for create_run_config, modify_run_config")
                        humanReadable("Arguments passed to main() (or the equivalent entry point).")
                        whenPresent("Set via setProgramParameters; for Gradle, set via setRawCommandLine.")
                        whenAbsent("No program arguments set on the new config.")
                        example("--server.port=9090")
                    }
                    optional("working_dir", "string") {
                        llmSeesIt("Working directory — for create_run_config, modify_run_config")
                        humanReadable("Directory the process is launched from.")
                        whenPresent("Set via setWorkingDirectory.")
                        whenAbsent("Defaults to project.basePath on create.")
                        example("/path/to/project")
                    }
                    optional("active_profiles", "string") {
                        llmSeesIt("Spring Boot active profiles, comma-separated — for create_run_config, modify_run_config")
                        humanReadable("Spring Boot active profiles list, comma-separated.")
                        whenPresent("Set via setActiveProfiles (reflective; Spring Boot configs only).")
                        whenAbsent("No Spring profiles set explicitly.")
                        example("dev,local")
                    }
                    optional("port", "integer") {
                        llmSeesIt("Remote debug port (default 5005) — for create_run_config")
                        humanReadable("Port number for remote_debug configurations.")
                        whenPresent("Written into the PORT field along with HOST=localhost and SERVER_MODE=false.")
                        whenAbsent("Defaults to 5005.")
                        example("5005")
                    }
                    optional("description", "string") {
                        llmSeesIt("Brief description of what this action does and why (shown to user in approval dialog) — for create_run_config, modify_run_config, delete_run_config")
                        humanReadable("One-line rationale shown to the user in the approval dialog.")
                        whenPresent("Surfaced in the approval gate so the user knows why the agent is creating this config.")
                        whenAbsent("Falls back to a generic 'create_run_config <name>' summary in the approval dialog.")
                        example("Run UserServiceTest in isolation to debug NPE")
                    }
                }
                onSuccess("Returns 'Created run configuration '[Agent] <name>'' with Type, Main class / Test class / Module / VM options / Program args / Env var keys / Active profiles / Debug port as applicable. If some properties failed to apply, a WARNING block lists them but the config is still created.")
                onFailure("name collides with existing config", "Returns 'Configuration '[Agent] <name>' already exists. Use modify_run_config to update it.' with isError=true.")
                onFailure("type is application/spring_boot without main_class", "Returns 'Parameter 'main_class' is required for type '<type>'' with isError=true.")
                onFailure("type=junit without test_class", "Returns 'Parameter 'test_class' is required for type 'junit'' with isError=true.")
                onFailure("ConfigurationFactory not resolvable (plugin missing)", "Returns 'Could not resolve configuration factory for type '<type>'. The required plugin may not be installed.' with isError=true.")
                onFailure("type not in enum", "Returns 'Invalid type '<type>'. Must be one of: application, spring_boot, junit, gradle, remote_debug' with isError=true.")
                example("Spring Boot config with profiles") {
                    param("action", "create_run_config")
                    param("name", "Run MyApp Dev")
                    param("type", "spring_boot")
                    param("main_class", "com.example.MyApp")
                    param("active_profiles", "dev,local")
                    outcome("Creates '[Agent] Run MyApp Dev' as a Spring Boot config with the given profiles.")
                }
                example("JUnit single method") {
                    param("action", "create_run_config")
                    param("name", "Debug testCreateUser")
                    param("type", "junit")
                    param("test_class", "com.example.UserServiceTest")
                    param("test_method", "testCreateUser")
                    outcome("Creates '[Agent] Debug testCreateUser' targeted at a single JUnit method.")
                }
                example("remote debug attach") {
                    param("action", "create_run_config")
                    param("name", "Attach to App")
                    param("type", "remote_debug")
                    param("port", "5005")
                    outcome("Creates '[Agent] Attach to App' with PORT=5005, HOST=localhost, SERVER_MODE=false.")
                }
            }
            action("modify_run_config") {
                description {
                    technical("Mutates an existing RunConfiguration's env_vars / vm_options / program_args / working_dir / active_profiles in place. env_vars merge with existing by default (read via getEnvs reflection or typed accessor, then putAll new); replace_env_vars=true replaces entirely. Per-property failures are collected and surfaced. Requires at least one of the modifiable fields. Existing config may be user-owned (not [Agent]-prefixed) — the tool does not gate modify by prefix.")
                    plain("Tweaks an existing run configuration without recreating it. Add an env var, change VM options, switch profiles. Env vars default to merging with what's there (so the LLM doesn't accidentally erase the user's settings); pass replace_env_vars=true to overwrite the whole set.")
                }
                whenLLMUses("To adjust an existing config — turn on a profile, add an env var, raise the heap — without losing the rest of the config's state.")
                params {
                    required("name", "string") {
                        llmSeesIt("Configuration name — for create_run_config (auto-prefixed with [Agent]), modify_run_config, delete_run_config")
                        humanReadable("Exact name of the configuration to modify (no auto-prefix on modify).")
                        whenPresent("Looked up via RunManager.findConfigurationByName.")
                        constraint("must match an existing configuration's exact name")
                        example("[Agent] Run MyApp Dev")
                    }
                    optional("env_vars", "object") {
                        llmSeesIt("Environment variables as key-value pairs — for create_run_config, modify_run_config. In modify_run_config, these are MERGED with existing env vars by default (use replace_env_vars=true for full replacement)")
                        humanReadable("Environment variables to merge (default) or replace.")
                        whenPresent("Merged with existing env_vars unless replace_env_vars=true.")
                        whenAbsent("Existing env vars are left unchanged.")
                        example("""{"LOG_LEVEL": "TRACE"}""")
                    }
                    optional("replace_env_vars", "boolean") {
                        llmSeesIt("If true, replace ALL existing env vars instead of merging — for modify_run_config only (default: false)")
                        humanReadable("Flip merge semantics to full replace.")
                        whenPresent("If true, env_vars completely replaces the existing env set.")
                        whenAbsent("Defaults to false — merge.")
                        example("true")
                    }
                    optional("vm_options", "string") {
                        llmSeesIt("JVM options — for create_run_config, modify_run_config")
                        humanReadable("New JVM options string (replaces the existing one entirely).")
                        whenPresent("Set via setVMParameters / setVmParameters / setVmOptions.")
                        whenAbsent("Existing VM options are left unchanged.")
                        example("-Xmx4g")
                    }
                    optional("program_args", "string") {
                        llmSeesIt("Program arguments — for create_run_config, modify_run_config")
                        humanReadable("New program arguments string (replaces the existing one entirely).")
                        whenPresent("Set via setProgramParameters / setRawCommandLine.")
                        whenAbsent("Existing program arguments are left unchanged.")
                        example("--debug")
                    }
                    optional("working_dir", "string") {
                        llmSeesIt("Working directory — for create_run_config, modify_run_config")
                        humanReadable("New working directory.")
                        whenPresent("Set via setWorkingDirectory.")
                        whenAbsent("Existing working directory is left unchanged.")
                        example("/tmp/run")
                    }
                    optional("active_profiles", "string") {
                        llmSeesIt("Spring Boot active profiles, comma-separated — for create_run_config, modify_run_config")
                        humanReadable("New Spring Boot active profiles list.")
                        whenPresent("Set via setActiveProfiles.")
                        whenAbsent("Existing Spring profiles are left unchanged.")
                        example("dev,debug")
                    }
                    optional("description", "string") {
                        llmSeesIt("Brief description of what this action does and why (shown to user in approval dialog) — for create_run_config, modify_run_config, delete_run_config")
                        humanReadable("One-line rationale shown to the user in the approval dialog.")
                        whenPresent("Surfaced in the approval gate.")
                        whenAbsent("Falls back to a generic 'modify_run_config <name>' summary in the approval dialog.")
                        example("Bump heap to 4g to test OOM threshold")
                    }
                }
                precondition("at least one of env_vars, vm_options, program_args, working_dir, active_profiles must be provided")
                onSuccess("Returns 'Modified configuration '<name>'' with Changes applied summary and the new field values. Warnings listed if any per-property reflection failed.")
                onFailure("no modifications provided", "Returns 'No modifications specified. Provide at least one of: env_vars, vm_options, program_args, working_dir, active_profiles' with isError=true.")
                onFailure("configuration not found", "Returns 'Configuration '<name>' not found. Use get_run_configurations to list available configs.' with isError=true.")
                example("add an env var without losing the others") {
                    param("action", "modify_run_config")
                    param("name", "[Agent] Run MyApp Dev")
                    param("env_vars", """{"LOG_LEVEL": "TRACE"}""")
                    outcome("Merges LOG_LEVEL=TRACE into the config's existing env vars.")
                }
                example("replace all env vars") {
                    param("action", "modify_run_config")
                    param("name", "[Agent] Run MyApp Dev")
                    param("env_vars", """{"SPRING_PROFILES_ACTIVE": "prod"}""")
                    param("replace_env_vars", "true")
                    outcome("Wipes existing env vars and sets only SPRING_PROFILES_ACTIVE=prod.")
                }
            }
            action("delete_run_config") {
                description {
                    technical("Removes a configuration from RunManager via removeConfiguration. Gated to names starting with '[Agent]' — this is a hard safety constraint to protect user-created configurations from accidental deletion.")
                    plain("Deletes a run configuration the agent created. Refuses to delete anything that doesn't have an [Agent] prefix so user-owned configs are safe.")
                }
                whenLLMUses("After finishing with an agent-created config (e.g. a one-shot JUnit single-method config used during a debug session) or to clean up leftover configs from a previous session.")
                params {
                    required("name", "string") {
                        llmSeesIt("Configuration name — for create_run_config (auto-prefixed with [Agent]), modify_run_config, delete_run_config")
                        humanReadable("Exact name of the configuration to delete.")
                        whenPresent("Looked up via RunManager.findConfigurationByName and removed.")
                        constraint("must start with '[Agent]' — user-owned configs cannot be deleted by this tool")
                        example("[Agent] Run MyApp Dev")
                    }
                    optional("description", "string") {
                        llmSeesIt("Brief description of what this action does and why (shown to user in approval dialog) — for create_run_config, modify_run_config, delete_run_config")
                        humanReadable("One-line rationale shown to the user in the approval dialog.")
                        whenPresent("Surfaced in the approval gate.")
                        whenAbsent("Falls back to a generic 'delete_run_config <name>' summary in the approval dialog.")
                        example("Cleaning up one-shot debug config")
                    }
                }
                onSuccess("Returns 'Deleted run configuration '<name>''.")
                onFailure("name lacks [Agent] prefix", "Returns 'Cannot delete '<name>': only agent-created configurations (containing [Agent] in name) can be deleted. This is a safety constraint to protect user-created configurations.' with isError=true.")
                onFailure("configuration not found", "Returns 'Configuration '<name>' not found. Use get_run_configurations to list available configs.' with isError=true.")
                example("clean up an agent config") {
                    param("action", "delete_run_config")
                    param("name", "[Agent] Run MyApp Dev")
                    outcome("Removes the configuration from RunManager.")
                }
            }
        }
    }

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
            "get_run_configurations" -> executeGetRunConfigurations(params, project)
            "create_run_config" -> executeCreateRunConfig(params, project)
            "modify_run_config" -> executeModifyRunConfig(params, project)
            "delete_run_config" -> executeDeleteRunConfig(params, project)
            else -> ToolResult(
                content = "Unknown action '$action'. Valid actions: get_run_configurations, create_run_config, modify_run_config, delete_run_config",
                summary = "Unknown action '$action'",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Action: get_run_configurations
    // ══════════════════════════════════════════════════════════════════════

    private fun executeGetRunConfigurations(params: JsonObject, project: Project): ToolResult {
        return try {
            val typeFilter = params["type_filter"]?.jsonPrimitive?.content
            val runManager = RunManager.getInstance(project)
            val allSettings = runManager.allSettings

            val filtered = if (typeFilter != null) {
                allSettings.filter { matchesTypeFilter(it.configuration, typeFilter) }
            } else {
                allSettings
            }

            if (filtered.isEmpty()) {
                val filterMsg = if (typeFilter != null) " matching type '$typeFilter'" else ""
                return ToolResult("No run configurations found$filterMsg.", "No configurations", 10)
            }

            val selectedName = runManager.selectedConfiguration?.name
            val sb = StringBuilder()
            sb.appendLine("Run Configurations (${filtered.size} total):")
            sb.appendLine()

            for (settings in filtered) {
                val config = settings.configuration
                val isSelected = config.name == selectedName
                val marker = if (isSelected) " [SELECTED]" else ""
                val typeName = config.type.displayName

                sb.appendLine("${config.name}$marker")
                sb.appendLine("  Type: $typeName")
                extractMainClass(config)?.let { sb.appendLine("  Main class: $it") }
                extractModule(config)?.let { sb.appendLine("  Module: $it") }
                extractVmOptions(config)?.let { sb.appendLine("  VM options: $it") }
                extractEnvVars(config)?.let { if (it.isNotEmpty()) sb.appendLine("  Env vars: $it") }
                sb.appendLine()
            }

            val content = sb.toString().trimEnd()
            ToolResult(content, "${filtered.size} configurations listed", content.length / 4)
        } catch (e: Exception) {
            ToolResult("Error listing run configurations: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    private fun matchesTypeFilter(config: RunConfiguration, filter: String): Boolean {
        val typeName = config.type.displayName.lowercase()
        val typeId = config.type.id.lowercase()
        return when (filter.lowercase()) {
            "application" -> typeId.contains("application") || typeName.contains("application")
            "spring_boot" -> typeId.contains("spring") || typeName.contains("spring")
            "junit" -> typeId.contains("junit") || typeName.contains("junit") || typeId.contains("test")
            "gradle" -> typeId.contains("gradle") || typeName.contains("gradle")
            "remote_debug" -> typeId.contains("remote") || typeName.contains("remote")
            else -> true
        }
    }

    private fun extractMainClass(config: RunConfiguration): String? {
        return try {
            if (config is ApplicationConfiguration) {
                config.mainClassName
            } else {
                val method = config.javaClass.methods.find {
                    it.name == "getMainClassName" || it.name == "getMainClass"
                }
                method?.invoke(config) as? String
            }
        } catch (_: Exception) { null }
    }

    private fun extractModule(config: RunConfiguration): String? {
        return try {
            val method = config.javaClass.methods.find {
                it.name == "getModuleName" || it.name == "getConfigurationModule"
            }
            val result = method?.invoke(config)
            result?.toString()?.takeIf { it.isNotBlank() && it != "null" }
        } catch (_: Exception) { null }
    }

    private fun extractVmOptions(config: RunConfiguration): String? {
        return try {
            val method = config.javaClass.methods.find {
                it.name == "getVMParameters" || it.name == "getVmParameters"
            }
            (method?.invoke(config) as? String)?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }

    private fun extractEnvVars(config: RunConfiguration): String? {
        return try {
            val method = config.javaClass.methods.find { it.name == "getEnvs" }
            @Suppress("UNCHECKED_CAST")
            val envs = method?.invoke(config) as? Map<String, String>
            envs?.entries?.joinToString(", ") { "${it.key}=***" }?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Action: create_run_config
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun executeCreateRunConfig(params: JsonObject, project: Project): ToolResult {
        val configName = params["name"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult("Missing required parameter: name", "Error: missing name", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val configType = params["type"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult("Missing required parameter: type", "Error: missing type", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val validTypes = listOf("application", "spring_boot", "junit", "gradle", "remote_debug")
        if (configType !in validTypes) {
            return ToolResult(
                "Invalid type '$configType'. Must be one of: ${validTypes.joinToString(", ")}",
                "Error: invalid type", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        val mainClass = params["main_class"]?.jsonPrimitive?.contentOrNull
        val testClass = params["test_class"]?.jsonPrimitive?.contentOrNull
        val testMethod = params["test_method"]?.jsonPrimitive?.contentOrNull
        val module = params["module"]?.jsonPrimitive?.contentOrNull
        val envVars = params["env_vars"]?.jsonObject?.let { obj ->
            obj.entries.associate { (k, v) -> k to v.jsonPrimitive.content }
        }
        val vmOptions = params["vm_options"]?.jsonPrimitive?.contentOrNull
        val programArgs = params["program_args"]?.jsonPrimitive?.contentOrNull
        val workingDir = params["working_dir"]?.jsonPrimitive?.contentOrNull
        val activeProfiles = params["active_profiles"]?.jsonPrimitive?.contentOrNull
        val port = params["port"]?.jsonPrimitive?.intOrNull

        if (configType in listOf("application", "spring_boot") && mainClass.isNullOrBlank()) {
            return ToolResult("Parameter 'main_class' is required for type '$configType'", "Error: missing main_class", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
        if (configType == "junit" && testClass.isNullOrBlank()) {
            return ToolResult("Parameter 'test_class' is required for type 'junit'", "Error: missing test_class", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        val prefixedName = "$AGENT_PREFIX$configName"

        return try {
            val runManager = RunManager.getInstance(project)
            val existing = runManager.findConfigurationByName(prefixedName)
            if (existing != null) {
                return ToolResult(
                    "Configuration '$prefixedName' already exists. Use modify_run_config to update it.",
                    "Error: config exists", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                )
            }

            val factory = resolveConfigurationFactory(configType)
                ?: return ToolResult(
                    "Could not resolve configuration factory for type '$configType'. The required plugin may not be installed.",
                    "Error: no factory for $configType", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                )

            val settings = runManager.createConfiguration(prefixedName, factory)
            val config = settings.configuration

            val failures = applyCreateConfigSettings(
                config, configType, mainClass, testClass, testMethod, module,
                envVars, vmOptions, programArgs, workingDir ?: project.basePath,
                activeProfiles, port ?: 5005, project
            )

            ApplicationManager.getApplication().invokeAndWait {
                runManager.addConfiguration(settings)
            }

            val content = buildString {
                appendLine("Created run configuration '$prefixedName'")
                appendLine("  Type: $configType")
                mainClass?.let { appendLine("  Main class: $it") }
                testClass?.let { appendLine("  Test class: $it") }
                testMethod?.let { appendLine("  Test method: $it") }
                module?.let { appendLine("  Module: $it") }
                vmOptions?.let { appendLine("  VM options: $it") }
                programArgs?.let { appendLine("  Program args: $it") }
                envVars?.let { if (it.isNotEmpty()) appendLine("  Env vars: ${it.keys.joinToString(", ")}") }
                activeProfiles?.let { appendLine("  Active profiles: $it") }
                if (configType == "remote_debug") appendLine("  Debug port: ${port ?: 5005}")
                if (failures.isNotEmpty()) {
                    appendLine()
                    appendLine("WARNING: Some properties failed to apply:")
                    failures.forEach { appendLine("  - $it") }
                }
            }.trimEnd()

            val summary = if (failures.isNotEmpty()) {
                "Created config '$prefixedName' ($configType) with ${failures.size} property error(s)"
            } else {
                "Created config '$prefixedName' ($configType)"
            }
            ToolResult(content, summary, TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult("Error creating run configuration: ${e.message}", "Error creating config", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    private fun resolveConfigurationFactory(type: String): ConfigurationFactory? {
        return try {
            when (type) {
                "application" -> ApplicationConfigurationType.getInstance().configurationFactories.firstOrNull()
                "spring_boot" -> resolveFactoryViaReflection("com.intellij.spring.boot.run.SpringBootApplicationConfigurationType")
                "junit" -> resolveFactoryViaReflection("com.intellij.execution.junit.JUnitConfigurationType")
                "gradle" -> resolveFactoryViaReflection("org.jetbrains.plugins.gradle.service.execution.GradleExternalTaskConfigurationType")
                "remote_debug" -> resolveFactoryViaReflection("com.intellij.execution.remote.RemoteConfigurationType")
                else -> null
            }
        } catch (_: Exception) { null }
    }

    private fun resolveFactoryViaReflection(className: String): ConfigurationFactory? {
        return try {
            val clazz = Class.forName(className)
            val getInstance = clazz.getMethod("getInstance")
            val instance = getInstance.invoke(null)
            val getFactories = instance.javaClass.getMethod("getConfigurationFactories")
            @Suppress("UNCHECKED_CAST")
            val factories = getFactories.invoke(instance) as Array<ConfigurationFactory>
            factories.firstOrNull()
        } catch (_: ClassNotFoundException) { null }
        catch (_: Exception) { null }
    }

    @Suppress("LongParameterList")
    private suspend fun applyCreateConfigSettings(
        config: RunConfiguration, configType: String, mainClass: String?,
        testClass: String?, testMethod: String?, module: String?,
        envVars: Map<String, String>?, vmOptions: String?, programArgs: String?,
        workingDir: String?, activeProfiles: String?, port: Int, project: Project
    ): List<String> {
        return when (configType) {
            "application" -> applyApplicationConfig(config, mainClass, vmOptions, programArgs, envVars, workingDir, module, project)
            "spring_boot" -> applyReflectionConfig(config, mainClass, vmOptions, programArgs, envVars, workingDir, activeProfiles, module, project)
            "junit" -> applyJUnitConfig(config, testClass, testMethod, vmOptions, envVars, workingDir, module, project)
            "remote_debug" -> applyRemoteConfig(config, port)
            "gradle" -> applyGradleConfig(config, programArgs, vmOptions, envVars, workingDir)
            else -> emptyList()
        }
    }

    private suspend fun applyApplicationConfig(
        config: RunConfiguration, mainClass: String?, vmOptions: String?,
        programArgs: String?, envVars: Map<String, String>?, workingDir: String?,
        module: String?, project: Project
    ): List<String> {
        val failures = mutableListOf<String>()
        if (config is ApplicationConfiguration) {
            mainClass?.let { trySetProperty(failures, "main_class") { config.mainClassName = it } }
            vmOptions?.let { trySetProperty(failures, "vm_options") { config.vmParameters = it } }
            programArgs?.let { trySetProperty(failures, "program_args") { config.programParameters = it } }
            envVars?.let { trySetProperty(failures, "env_vars") { config.envs = it } }
            workingDir?.let { trySetProperty(failures, "working_dir") { config.workingDirectory = it } }
            module?.let { applyModuleByName(config, it, project, failures) }
        }
        return failures
    }

    private suspend fun applyReflectionConfig(
        config: RunConfiguration, mainClass: String?, vmOptions: String?,
        programArgs: String?, envVars: Map<String, String>?, workingDir: String?,
        activeProfiles: String?, module: String?, project: Project
    ): List<String> {
        val failures = mutableListOf<String>()
        mainClass?.let { trySetReflection(failures, "main_class", config, "setMainClassName", it) }
        vmOptions?.let { trySetReflection(failures, "vm_options", config, "setVMParameters", it) }
        programArgs?.let { trySetReflection(failures, "program_args", config, "setProgramParameters", it) }
        envVars?.let { trySetEnvsReflection(failures, "env_vars", config, it) }
        workingDir?.let { trySetReflection(failures, "working_dir", config, "setWorkingDirectory", it) }
        activeProfiles?.let { trySetReflection(failures, "active_profiles", config, "setActiveProfiles", it) }
        module?.let { applyModuleByName(config, it, project, failures) }
        return failures
    }

    private suspend fun applyJUnitConfig(
        config: RunConfiguration, testClass: String?, testMethod: String?,
        vmOptions: String?, envVars: Map<String, String>?, workingDir: String?,
        module: String?, project: Project
    ): List<String> {
        val failures = mutableListOf<String>()
        testClass?.let {
            trySetProperty(failures, "test_class") {
                val getPersistentData = config.javaClass.methods.find { m -> m.name == "getPersistentData" }
                val data = getPersistentData?.invoke(config)
                if (data != null) {
                    val testObjectField = data.javaClass.getField("TEST_OBJECT")
                    val mainClassField = data.javaClass.getField("MAIN_CLASS_NAME")
                    val testType = if (testMethod != null) "method" else "class"
                    testObjectField.set(data, testType)
                    mainClassField.set(data, it)
                    testMethod?.let { method ->
                        val methodField = data.javaClass.getField("METHOD_NAME")
                        methodField.set(data, method)
                    }
                } else {
                    throw IllegalStateException("getPersistentData() returned null — JUnit config type may not be available")
                }
            }
        }
        vmOptions?.let { trySetReflection(failures, "vm_options", config, "setVMParameters", it) }
        envVars?.let { trySetEnvsReflection(failures, "env_vars", config, it) }
        workingDir?.let { trySetReflection(failures, "working_dir", config, "setWorkingDirectory", it) }
        module?.let { applyModuleByName(config, it, project, failures) }
        return failures
    }

    private suspend fun applyRemoteConfig(config: RunConfiguration, port: Int): List<String> {
        val failures = mutableListOf<String>()
        trySetProperty(failures, "port") {
            val portField = config.javaClass.getField("PORT")
            portField.set(config, port.toString())
        }
        trySetProperty(failures, "host") {
            val hostField = config.javaClass.getField("HOST")
            hostField.set(config, "localhost")
        }
        trySetProperty(failures, "server_mode") {
            val serverModeField = config.javaClass.getField("SERVER_MODE")
            serverModeField.set(config, false)
        }
        return failures
    }

    private suspend fun applyGradleConfig(
        config: RunConfiguration, programArgs: String?, vmOptions: String?,
        envVars: Map<String, String>?, workingDir: String?
    ): List<String> {
        val failures = mutableListOf<String>()
        programArgs?.let { trySetReflection(failures, "program_args", config, "setRawCommandLine", it) }
        vmOptions?.let { trySetReflection(failures, "vm_options", config, "setVmOptions", it) }
        workingDir?.let { trySetReflection(failures, "working_dir", config, "setWorkingDirectory", it) }
        return failures
    }

    private fun setViaReflection(config: RunConfiguration, methodName: String, value: String) {
        val method = config.javaClass.methods.find { it.name == methodName && it.parameterCount == 1 }
            ?: throw NoSuchMethodException("Method '$methodName' not found on ${config.javaClass.simpleName}")
        method.invoke(config, value)
    }

    private fun setEnvsViaReflection(config: RunConfiguration, envs: Map<String, String>) {
        val method = config.javaClass.methods.find { it.name == "setEnvs" && it.parameterCount == 1 }
            ?: throw NoSuchMethodException("Method 'setEnvs' not found on ${config.javaClass.simpleName}")
        method.invoke(config, envs)
    }

    private suspend inline fun trySetProperty(failures: MutableList<String>, propertyName: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            failures.add("$propertyName: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private suspend fun trySetReflection(
        failures: MutableList<String>, propertyName: String,
        config: RunConfiguration, methodName: String, value: String
    ) {
        trySetProperty(failures, propertyName) { setViaReflection(config, methodName, value) }
    }

    private suspend fun trySetEnvsReflection(
        failures: MutableList<String>, propertyName: String,
        config: RunConfiguration, envs: Map<String, String>
    ) {
        trySetProperty(failures, propertyName) { setEnvsViaReflection(config, envs) }
    }

    private suspend fun applyModuleByName(
        config: RunConfiguration, moduleName: String, project: Project, failures: MutableList<String>
    ) {
        trySetProperty(failures, "module") {
            val (mod, availableNames) = readAction {
                val mgr = ModuleManager.getInstance(project)
                mgr.findModuleByName(moduleName) to mgr.modules.map { it.name }.sorted()
            }
            mod ?: throw IllegalArgumentException(
                "Module '$moduleName' not found. Available: $availableNames"
            )
            if (config is ApplicationConfiguration) {
                config.setModule(mod)
            } else {
                val method = config.javaClass.methods.find { m ->
                    m.name == "setModule" && m.parameterCount == 1
                } ?: throw NoSuchMethodException("setModule not found on ${config.javaClass.simpleName}")
                method.invoke(config, mod)
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Action: modify_run_config
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun executeModifyRunConfig(params: JsonObject, project: Project): ToolResult {
        val configName = params["name"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult("Missing required parameter: name", "Error: missing name", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val envVars = params["env_vars"]?.jsonObject?.let { obj ->
            obj.entries.associate { (k, v) -> k to v.jsonPrimitive.content }
        }
        val replaceEnvVars = params["replace_env_vars"]?.jsonPrimitive?.booleanOrNull ?: false
        val vmOptions = params["vm_options"]?.jsonPrimitive?.contentOrNull
        val programArgs = params["program_args"]?.jsonPrimitive?.contentOrNull
        val workingDir = params["working_dir"]?.jsonPrimitive?.contentOrNull
        val activeProfiles = params["active_profiles"]?.jsonPrimitive?.contentOrNull

        if (envVars == null && vmOptions == null && programArgs == null && workingDir == null && activeProfiles == null) {
            return ToolResult(
                "No modifications specified. Provide at least one of: env_vars, vm_options, program_args, working_dir, active_profiles",
                "Error: no modifications", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        return try {
            val runManager = RunManager.getInstance(project)
            val settings = runManager.findConfigurationByName(configName)
                ?: return ToolResult(
                    "Configuration '$configName' not found. Use get_run_configurations to list available configs.",
                    "Error: config not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                )

            val config = settings.configuration
            val changes = mutableListOf<String>()
            val failures = mutableListOf<String>()

            envVars?.let {
                modifyApplyEnvVars(config, it, replaceEnvVars, failures)
                val mode = if (replaceEnvVars) "replaced" else "merged"
                changes.add("env_vars (${it.size} vars, $mode)")
            }
            vmOptions?.let {
                modifyApplyVmOptions(config, it, failures)
                changes.add("vm_options")
            }
            programArgs?.let {
                modifyApplyProgramArgs(config, it, failures)
                changes.add("program_args")
            }
            workingDir?.let {
                modifyApplyWorkingDir(config, it, failures)
                changes.add("working_dir")
            }
            activeProfiles?.let {
                modifyApplyActiveProfiles(config, it, failures)
                changes.add("active_profiles")
            }

            ApplicationManager.getApplication().invokeAndWait {
                runManager.addConfiguration(settings)
            }

            val content = buildString {
                appendLine("Modified configuration '$configName'")
                appendLine("  Changes applied: ${changes.joinToString(", ")}")
                vmOptions?.let { appendLine("  VM options: $it") }
                programArgs?.let { appendLine("  Program args: $it") }
                workingDir?.let { appendLine("  Working dir: $it") }
                envVars?.let { if (it.isNotEmpty()) appendLine("  Env vars: ${it.keys.joinToString(", ")}") }
                activeProfiles?.let { appendLine("  Active profiles: $it") }
                if (failures.isNotEmpty()) {
                    appendLine()
                    appendLine("WARNING: Some properties failed to apply:")
                    failures.forEach { appendLine("  - $it") }
                }
            }.trimEnd()

            val summary = if (failures.isNotEmpty()) {
                "Modified config '$configName' with ${failures.size} property error(s)"
            } else {
                "Modified config '$configName': ${changes.joinToString(", ")}"
            }
            ToolResult(content, summary, TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult("Error modifying run configuration: ${e.message}", "Error modifying config", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    private suspend fun modifyApplyEnvVars(
        config: RunConfiguration, envVars: Map<String, String>,
        replaceEnvVars: Boolean, failures: MutableList<String>
    ) {
        if (config is ApplicationConfiguration) {
            trySetProperty(failures, "env_vars") {
                val effectiveEnvs = if (replaceEnvVars) {
                    envVars
                } else {
                    val merged = config.envs.toMutableMap()
                    merged.putAll(envVars)
                    merged
                }
                config.envs = effectiveEnvs
            }
        } else {
            trySetProperty(failures, "env_vars") {
                val effectiveEnvs = if (replaceEnvVars) {
                    envVars
                } else {
                    val existing = getEnvsViaReflection(config)
                    val merged = existing.toMutableMap()
                    merged.putAll(envVars)
                    merged
                }
                setEnvsViaReflection(config, effectiveEnvs)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun getEnvsViaReflection(config: RunConfiguration): Map<String, String> {
        val method = config.javaClass.methods.find { it.name == "getEnvs" && it.parameterCount == 0 }
            ?: return emptyMap()
        return (method.invoke(config) as? Map<String, String>) ?: emptyMap()
    }

    private suspend fun modifyApplyVmOptions(config: RunConfiguration, vmOptions: String, failures: MutableList<String>) {
        if (config is ApplicationConfiguration) {
            trySetProperty(failures, "vm_options") { config.vmParameters = vmOptions }
        } else {
            trySetProperty(failures, "vm_options") {
                val method = config.javaClass.methods.find {
                    (it.name == "setVMParameters" || it.name == "setVmParameters" || it.name == "setVmOptions")
                        && it.parameterCount == 1
                }
                    ?: throw NoSuchMethodException("No VM parameters setter found on ${config.javaClass.simpleName}")
                method.invoke(config, vmOptions)
            }
        }
    }

    private suspend fun modifyApplyProgramArgs(config: RunConfiguration, args: String, failures: MutableList<String>) {
        if (config is ApplicationConfiguration) {
            trySetProperty(failures, "program_args") { config.programParameters = args }
        } else {
            trySetProperty(failures, "program_args") {
                val method = config.javaClass.methods.find {
                    (it.name == "setProgramParameters" || it.name == "setRawCommandLine")
                        && it.parameterCount == 1
                }
                    ?: throw NoSuchMethodException("No program arguments setter found on ${config.javaClass.simpleName}")
                method.invoke(config, args)
            }
        }
    }

    private suspend fun modifyApplyWorkingDir(config: RunConfiguration, dir: String, failures: MutableList<String>) {
        if (config is ApplicationConfiguration) {
            trySetProperty(failures, "working_dir") { config.workingDirectory = dir }
        } else {
            trySetReflection(failures, "working_dir", config, "setWorkingDirectory", dir)
        }
    }

    private suspend fun modifyApplyActiveProfiles(config: RunConfiguration, profiles: String, failures: MutableList<String>) {
        trySetReflection(failures, "active_profiles", config, "setActiveProfiles", profiles)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Action: delete_run_config
    // ══════════════════════════════════════════════════════════════════════

    private fun executeDeleteRunConfig(params: JsonObject, project: Project): ToolResult {
        val configName = params["name"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult("Missing required parameter: name", "Error: missing name", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        if (!configName.startsWith("[Agent]")) {
            return ToolResult(
                "Cannot delete '$configName': only agent-created configurations (containing [Agent] in name) can be deleted. " +
                    "This is a safety constraint to protect user-created configurations.",
                "Error: cannot delete non-agent config", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        return try {
            val runManager = RunManager.getInstance(project)
            val settings = runManager.findConfigurationByName(configName)
                ?: return ToolResult(
                    "Configuration '$configName' not found. Use get_run_configurations to list available configs.",
                    "Error: config not found", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                )

            ApplicationManager.getApplication().invokeAndWait {
                runManager.removeConfiguration(settings)
            }

            ToolResult("Deleted run configuration '$configName'", "Deleted config '$configName'", 10)
        } catch (e: Exception) {
            ToolResult("Error deleting run configuration: ${e.message}", "Error deleting config", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    companion object {
        const val AGENT_PREFIX = "[Agent] "
    }
}

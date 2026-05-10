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
import com.workflow.orchestrator.agent.tools.framework.spring.executeBeanGraph
import com.workflow.orchestrator.agent.tools.framework.spring.executeBootActuator
import com.workflow.orchestrator.agent.tools.framework.spring.executeBootAutoConfig
import com.workflow.orchestrator.agent.tools.framework.spring.executeBootConfigProperties
import com.workflow.orchestrator.agent.tools.framework.spring.executeBootEndpoints
import com.workflow.orchestrator.agent.tools.framework.spring.executeConfig
import com.workflow.orchestrator.agent.tools.framework.spring.executeContext
import com.workflow.orchestrator.agent.tools.framework.spring.executeEndpoints
import com.workflow.orchestrator.agent.tools.framework.spring.executeAnnotatedMethods
import com.workflow.orchestrator.agent.tools.framework.spring.executeEventListeners
import com.workflow.orchestrator.agent.tools.framework.spring.executeJpaEntities
import com.workflow.orchestrator.agent.tools.framework.spring.executeProfiles
import com.workflow.orchestrator.agent.tools.framework.spring.executeRepositories
import com.workflow.orchestrator.agent.tools.framework.spring.executeScheduledTasks
import com.workflow.orchestrator.agent.tools.framework.spring.executeSecurityConfig
import com.workflow.orchestrator.agent.tools.framework.spring.executeVersionInfo
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Consolidated Spring meta-tool replacing 15 individual Spring/Spring Boot/JPA tools.
 *
 * Saves token budget per API call by collapsing all Spring-related operations into
 * a single tool definition with an `action` discriminator parameter.
 *
 * Actions: context, endpoints, bean_graph, config, version_info, profiles, repositories,
 *          security_config, scheduled_tasks, event_listeners, boot_endpoints, boot_autoconfig,
 *          boot_config_properties, boot_actuator, jpa_entities
 *
 * Each action is implemented in its own file under
 * `com.workflow.orchestrator.agent.tools.framework.spring`. This class is a thin
 * dispatcher that exposes the tool surface (name, description, parameter schema)
 * and routes the `action` parameter to the corresponding handler.
 */
class SpringTool(
    private val includeEndpointActions: Boolean = true,
) : AgentTool {

    override val name = "spring"

    override val description: String
        get() = buildString {
            if (includeEndpointActions) {
                appendLine("Spring framework intelligence — beans, endpoints, configuration, JPA, security, actuator.")
            } else {
                appendLine("Spring framework intelligence — beans, configuration, JPA, security, actuator.")
            }
            appendLine()
            appendLine("Actions and their parameters:")
            appendLine("- context(filter?, profile?) → Spring bean context (filter by name/type and/or @Profile value)")
            if (includeEndpointActions) appendLine("- endpoints(filter?, include_params?) → REST endpoint mappings")
            appendLine("- bean_graph(bean_name) → Bean dependency graph (accepts bean name, alias, class, or @Bean method name)")
            appendLine("- config(property) → Configuration property value")
            appendLine("- version_info(module) → Framework version info")
            appendLine("- profiles() → Active Spring profiles")
            appendLine("- repositories(filter?) → Spring Data repositories")
            appendLine("- security_config() → Security configuration")
            appendLine("- scheduled_tasks() → @Scheduled methods")
            appendLine("- event_listeners() → @EventListener methods")
            appendLine("- annotated_methods(annotation, filter?) → Generic scan for any Spring annotation (@Scheduled, @EventListener, @Transactional, @Cacheable, @Async, @PreAuthorize, @Secured, custom FQN)")
            if (includeEndpointActions) appendLine("- boot_endpoints(class_name?) → Boot endpoint mappings")
            appendLine("- boot_autoconfig(filter?, project_only?) → Auto-configuration classes (project_only default true)")
            appendLine("- boot_config_properties(class_name?, prefix?) → @ConfigurationProperties bindings with IDE metadata (descriptions, defaults, deprecations, source types)")
            appendLine("- boot_actuator() → Actuator endpoints")
            append("- jpa_entities(entity?) → JPA entity metadata via Persistence plugin — inheritance, relationship cardinality/fetch/cascade, named queries")
        }

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = buildList {
                    add("context")
                    if (includeEndpointActions) add("endpoints")
                    addAll(listOf(
                        "bean_graph", "config", "version_info", "profiles", "repositories",
                        "security_config", "scheduled_tasks", "event_listeners", "annotated_methods",
                    ))
                    if (includeEndpointActions) add("boot_endpoints")
                    addAll(listOf("boot_autoconfig", "boot_config_properties", "boot_actuator", "jpa_entities"))
                },
            ),
            "filter" to ParameterProperty(
                type = "string",
                description = "Filter results by name/pattern — for context, endpoints, boot_autoconfig"
            ),
            "profile" to ParameterProperty(
                type = "string",
                description = "Filter to beans whose @Profile matches this substring (case-insensitive) — for context"
            ),
            "bean_name" to ParameterProperty(
                type = "string",
                description = "Bean identifier — accepts canonical bean name, alias, simple or fully-qualified class name, or @Bean method name. Resolved via the Spring plugin's bean model. For bean_graph"
            ),
            "property" to ParameterProperty(
                type = "string",
                description = "Specific property name to look up (e.g., 'spring.datasource.url') — for config"
            ),
            "module" to ParameterProperty(
                type = "string",
                description = "Module name to inspect — for version_info"
            ),
            "include_params" to ParameterProperty(
                type = "boolean",
                description = "If true, show handler method parameters with annotations (default: false) — for endpoints"
            ),
            "class_name" to ParameterProperty(
                type = "string",
                description = "Filter by controller or config properties class name — for boot_endpoints, boot_config_properties"
            ),
            "prefix" to ParameterProperty(
                type = "string",
                description = "Filter by configuration properties prefix — for boot_config_properties"
            ),
            "project_only" to ParameterProperty(
                type = "boolean",
                description = "If true (default), only scan project-scope classes; if false, includes library classes — for boot_autoconfig"
            ),
            "entity" to ParameterProperty(
                type = "string",
                description = "Specific entity class name — for jpa_entities"
            ),
            "annotation" to ParameterProperty(
                type = "string",
                description = "Annotation name — short form (@Scheduled, Transactional, etc.) or fully-qualified (e.g. org.springframework.cache.annotation.Cacheable) — for annotated_methods"
            ),
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
            "context" -> executeContext(params, project)
            "endpoints" -> if (includeEndpointActions) executeEndpoints(params, project) else unsupportedAction(action)
            "bean_graph" -> executeBeanGraph(params, project)
            "config" -> executeConfig(params, project)
            "version_info" -> executeVersionInfo(params, project)
            "profiles" -> executeProfiles(params, project)
            "repositories" -> executeRepositories(params, project)
            "security_config" -> executeSecurityConfig(params, project)
            "scheduled_tasks" -> executeScheduledTasks(params, project)
            "event_listeners" -> executeEventListeners(params, project)
            "annotated_methods" -> executeAnnotatedMethods(params, project)
            "boot_endpoints" -> if (includeEndpointActions) executeBootEndpoints(params, project) else unsupportedAction(action)
            "boot_autoconfig" -> executeBootAutoConfig(params, project)
            "boot_config_properties" -> executeBootConfigProperties(params, project)
            "boot_actuator" -> executeBootActuator(params, project)
            "jpa_entities" -> executeJpaEntities(params, project)
            else -> ToolResult(
                content = "Unknown action '$action'. See tool description for valid actions.",
                summary = "Unknown action '$action'",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    private fun unsupportedAction(action: String): ToolResult = ToolResult(
        content = "Action '$action' is served by the `endpoints` meta-tool in this IDE. " +
            "Use `endpoints(action=list)` or `endpoints(action=list, framework=\"Spring\")`.",
        summary = "Use endpoints tool",
        tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
        isError = true,
    )

    override fun documentation(): ToolDocumentation = toolDoc("spring") {
        summary {
            technical(
                "Single-tool dispatcher for 16 Spring/Spring Boot/JPA introspection operations via IntelliJ's Spring plugin model. " +
                    "Covers bean context enumeration, dependency graph traversal, REST endpoint mapping, configuration property lookup, " +
                    "active profile resolution, Spring Data repository listing, security configuration, scheduled tasks, event listeners, " +
                    "arbitrary annotation scanning, Boot auto-configuration, @ConfigurationProperties bindings (with IDE metadata), " +
                    "Actuator endpoint inventory, and JPA entity metadata. Conditionally registered: requires hasSpringPlugin && hasJavaPlugin. " +
                    "Promoted from deferred to core when Spring is detected in the project."
            )
            plain(
                "The agent's Spring remote control — like having the Spring Beans, Endpoints, and JPA tabs of IntelliJ's Spring tooling " +
                    "accessible as a single tool. Covers everything from 'what beans exist' to 'who injects OrderService' to " +
                    "'show me every @Transactional method'. Only shows up when the IntelliJ Spring plugin is installed and the " +
                    "project has Spring on the classpath."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.READ_ONLY)
        counterfactual(
            "Without `spring`, the LLM falls back to `search_code` scanning for `@Component`, `@Service`, `@Bean`, `@RequestMapping` etc. " +
                "That approach misses beans registered programmatically, beans declared in library JARs or parent contexts, " +
                "aliases, auto-configurations, and the full dependency graph. The Spring plugin's static-analysis model resolves all " +
                "of these at IDE-index level — `search_code` on annotations is a fragile text approximation that produces both " +
                "false positives (commented-out code) and false negatives (programmatic registration). For bean graph traversal " +
                "specifically, reconstructing the dependency tree manually from annotation scans is O(n^2) and still wrong."
        )
        llmMistake(
            "Calls `context` without a `filter` on a large project and then tries to read the entire bean list. " +
                "Large Spring apps can have 500+ beans — always pass a `filter` substring when looking for something specific."
        )
        llmMistake(
            "Passes a fully-qualified class name to `bean_graph` but wraps it in backticks or quotes. " +
                "The param must be a plain string — the resolver accepts canonical name, alias, simple class name, or @Bean method name as-is."
        )
        llmMistake(
            "Calls `security_config` or `scheduled_tasks` with non-existent parameters (e.g., `filter`). " +
                "These actions take no parameters — any extra fields are silently ignored but waste schema tokens."
        )
        llmMistake(
            "Uses `endpoints` to find REST mappings in an IDE where `includeEndpointActions=false` (i.e., the IDE has a dedicated " +
                "`endpoints` meta-tool). The tool returns an explicit redirect error pointing to the `endpoints` tool. " +
                "The LLM should switch immediately rather than retrying."
        )
        llmMistake(
            "Calls `boot_config_properties` without either `class_name` or `prefix` on a project with dozens of " +
                "@ConfigurationProperties classes — gets a massive, truncated dump. Should narrow with at least a prefix filter."
        )
        llmMistake(
            "Calls `annotated_methods` with a short-form annotation name that matches multiple annotations " +
                "(e.g., `Cacheable` matches both Spring's and a custom one). Use the fully-qualified name when precision matters."
        )
        llmMistake(
            "Treats the absence of a bean in `context` results as proof the bean doesn't exist. " +
                "Beans in test-scoped or conditional configurations may not appear; use `bean_graph` from a known caller to trace dependencies."
        )
        downside(
            "Requires both the IntelliJ Spring plugin (Ultimate) and the Java plugin — not available in Community, PyCharm, or WebStorm."
        )
        downside(
            "Results reflect the IDE's index state at call time. Stale index (mid-build or right after a dependency change) " +
                "may produce incomplete results. Re-index (`./gradlew --refresh-dependencies`) and retry."
        )
        downside(
            "The `endpoints` and `boot_endpoints` actions are conditionally included based on `includeEndpointActions`. " +
                "When false, calling them returns an error redirect — the LLM must switch to the `endpoints` meta-tool."
        )
        downside(
            "`jpa_entities` requires the IntelliJ Persistence plugin on top of Spring/Java — not always installed even in Ultimate."
        )
        related("build", Relationship.COMPLEMENT, "Use `build` for Maven/Gradle dependency resolution; use `spring` for the runtime bean model built from those dependencies.")
        related("find_definition", Relationship.COMPLEMENT, "Use `find_definition` to navigate to a specific class; use `spring bean_graph` to understand who wires that class in the Spring context.")
        related("diagnostics", Relationship.COMPLEMENT, "Use `diagnostics` for IDE compiler/inspection errors; use `spring` to investigate Spring-specific wiring problems not visible as compile errors.")
        related("search_code", Relationship.FALLBACK, "Use `search_code` as a last resort when the Spring plugin is unavailable or the index is stale.")
        observation("The `annotated_methods` action subsumes both `scheduled_tasks` and `event_listeners` for any annotation the LLM can name. Consider deprecating the two specialized actions in a future consolidation sweep.")
        observation("`boot_endpoints` and `endpoints` overlap in environments where both actions are registered. The split is IDE-context-driven but the LLM may not know which to prefer. The description should be clearer about when to use each.")
        actions {
            action("context") {
                description {
                    technical(
                        "Enumerates Spring beans visible in the IDE's Spring plugin model, optionally narrowed by a name/type filter substring and/or an @Profile value. " +
                            "Returns bean name, type, scope, and declaration location."
                    )
                    plain(
                        "Lists all the Spring beans the IDE knows about — like opening the Spring Beans panel and browsing the tree. " +
                            "You can filter by name or type to avoid drowning in 500+ entries."
                    )
                }
                whenLLMUses("When the user asks 'what beans exist', 'does this service exist as a Spring bean', or 'which beans implement interface X'. Starting point for any Spring wiring question.")
                params {
                    optional("filter", "string") {
                        llmSeesIt("Filter results by name/pattern — for context, endpoints, boot_autoconfig")
                        humanReadable("Substring filter applied to bean name and type — case-insensitive. Use this to narrow a large context.")
                        whenPresent("Only beans whose name or type contains this substring are returned.")
                        whenAbsent("All beans are returned — can be 500+ in a large project.")
                        example("OrderService")
                        example("Repository")
                    }
                    optional("profile", "string") {
                        llmSeesIt("Filter to beans whose @Profile matches this substring (case-insensitive) — for context")
                        humanReadable("Restricts results to beans declared under a matching @Profile. Useful for multi-environment setups (prod, dev, test).")
                        whenPresent("Only beans with an @Profile annotation matching this substring are included.")
                        whenAbsent("Beans from all profiles are included.")
                        example("prod")
                        example("test")
                    }
                }
                precondition("Spring plugin must be installed and the project must have at least one Spring configuration class or XML config recognized by the IDE.")
                precondition("IDE index must be up-to-date; stale index returns an empty or partial list without error.")
                onSuccess("Returns a formatted list of beans with their type, scope (singleton/prototype/etc.), and declaration source file+line.")
                onFailure("Spring plugin missing", "Returns the SPRING_PLUGIN_MISSING_MSG error. LLM should fall back to search_code for annotation scanning.")
                onFailure("no beans found with filter", "Empty result — not an error. LLM should broaden the filter or remove it.")
                example("list all beans containing 'Service'") {
                    param("action", "context")
                    param("filter", "Service")
                    outcome("Returns all beans whose name or type contains 'Service' — e.g. OrderService, PaymentService, UserService.")
                }
                example("beans active in the prod profile") {
                    param("action", "context")
                    param("profile", "prod")
                    outcome("Returns only beans annotated with @Profile('prod') or @Profile({'prod', 'cloud'}).")
                }
                verdict {
                    keep("The starting point for any bean-discovery task. search_code cannot replicate bean-model resolution.", VerdictSeverity.STRONG)
                }
            }
            action("endpoints") {
                description {
                    technical(
                        "Returns Spring MVC / WebFlux REST endpoint mappings from the IDE's endpoint model — HTTP method, path, handler class+method. " +
                            "Optionally includes handler method parameter names and annotations. Conditional: only available when includeEndpointActions=true."
                    )
                    plain(
                        "Lists all the HTTP routes your Spring app exposes — like the Spring Endpoints tab in IntelliJ Ultimate. " +
                            "GET /api/orders → OrderController.list(), POST /api/orders → OrderController.create(), etc."
                    )
                }
                whenLLMUses("When the user asks 'what routes exist', 'what handles POST /api/orders', or 'show me all endpoints'. Only available in the standard IntelliJ Spring configuration (not when a separate endpoints meta-tool is registered).")
                params {
                    optional("filter", "string") {
                        llmSeesIt("Filter results by name/pattern — for context, endpoints, boot_autoconfig")
                        humanReadable("Substring filter applied to the URL path or handler class name.")
                        whenPresent("Only endpoints whose path or handler class name contains this substring are returned.")
                        whenAbsent("All mapped endpoints are returned.")
                        example("/api/orders")
                        example("OrderController")
                    }
                    optional("include_params", "boolean") {
                        llmSeesIt("If true, show handler method parameters with annotations (default: false) — for endpoints")
                        humanReadable("Adds handler method parameter names and annotations (e.g. @RequestBody, @PathVariable) to each endpoint entry.")
                        whenPresent("Each endpoint entry includes its handler method parameter list.")
                        whenAbsent("Parameters are omitted; response is more concise.")
                        example("true")
                    }
                }
                precondition("Requires includeEndpointActions=true — i.e., no separate endpoints meta-tool is registered in this IDE environment.")
                precondition("Spring plugin must be installed with endpoint analysis support.")
                onSuccess("Returns a formatted list of endpoints with HTTP method, path, handler class, and handler method name.")
                onFailure("includeEndpointActions=false", "Returns an error redirecting to the `endpoints` meta-tool. LLM must switch tools.")
                onFailure("Spring plugin missing", "Returns SPRING_PLUGIN_MISSING_MSG.")
                example("list all POST endpoints") {
                    param("action", "endpoints")
                    param("filter", "POST")
                    outcome("Returns all endpoints mapped to HTTP POST.")
                }
                example("full endpoint detail for order routes") {
                    param("action", "endpoints")
                    param("filter", "/api/orders")
                    param("include_params", "true")
                    outcome("Returns matching endpoints with their handler parameter lists including @RequestBody, @PathVariable annotations.")
                }
                verdict {
                    keep("High-value when available — avoids annotation scanning. Conditional on IDE environment.", VerdictSeverity.NORMAL)
                }
            }
            action("bean_graph") {
                description {
                    technical(
                        "Returns the Spring dependency graph for a specific bean — its direct dependencies (injected beans) and direct dependents (beans that inject it). " +
                            "Bean identifier accepts canonical bean name, alias, simple class name, FQN, or @Bean method name; resolved via the Spring plugin's bean model."
                    )
                    plain(
                        "Shows who a bean talks to and who talks to it — like the Spring bean dependency diagram in IntelliJ. " +
                            "Think of it as 'tracing the wires' between your services without reading every constructor."
                    )
                }
                whenLLMUses("When the user asks 'what does OrderService depend on', 'who injects UserRepository', or 'show me the dependency chain for PaymentGateway'. Critical for circular dependency analysis and understanding blast radius of changes.")
                params {
                    required("bean_name", "string") {
                        llmSeesIt("Bean identifier — accepts canonical bean name, alias, simple or fully-qualified class name, or @Bean method name. Resolved via the Spring plugin's bean model. For bean_graph")
                        humanReadable("How to identify the bean — any of: canonical name (orderService), alias, simple class name (OrderService), fully-qualified class name (com.example.OrderService), or @Bean method name.")
                        whenPresent("The Spring plugin's bean model resolves the identifier to a concrete bean, then walks its dependency edges.")
                        constraint("must not be empty")
                        example("orderService")
                        example("OrderService")
                        example("com.example.order.OrderService")
                    }
                }
                precondition("The bean must exist in the IDE's Spring model — either declared via annotation or registered programmatically in a way the Spring plugin can resolve.")
                onSuccess("Returns the bean's type, scope, declaration location, direct dependencies (injected beans), and direct dependents (beans that inject this one).")
                onFailure("bean not found", "Returns an error listing recognized resolution strategies (name, alias, class, method). LLM should try a different identifier form.")
                onFailure("Spring plugin missing", "Returns SPRING_PLUGIN_MISSING_MSG.")
                example("trace OrderService dependencies") {
                    param("action", "bean_graph")
                    param("bean_name", "OrderService")
                    outcome("Returns OrderService's direct dependencies (e.g. OrderRepository, PaymentGateway, EventPublisher) and which beans inject OrderService.")
                }
                example("find all beans depending on UserRepository") {
                    param("action", "bean_graph")
                    param("bean_name", "userRepository")
                    outcome("Returns dependents list — all services that inject UserRepository.")
                    notes("Useful before refactoring UserRepository — shows blast radius.")
                }
                verdict {
                    keep("Irreplaceable for dependency analysis. No annotation-scan alternative can reconstruct the IDE's resolved dependency graph.", VerdictSeverity.STRONG)
                }
            }
            action("config") {
                description {
                    technical(
                        "Looks up a Spring configuration property value by key from the IDE's configuration model — resolves across application.properties, application.yml, profile-specific files, and environment variable overrides visible to the Spring plugin."
                    )
                    plain(
                        "Looks up a specific config key like `spring.datasource.url` — like ctrl-clicking a property reference in IntelliJ and seeing its resolved value. " +
                            "Handles the multi-file priority stack so you see the effective value."
                    )
                }
                whenLLMUses("When the user asks 'what is the database URL configured', 'what port does the app listen on', or 'is this feature flag enabled'. More reliable than reading application.properties manually when profiles are involved.")
                params {
                    required("property", "string") {
                        llmSeesIt("Specific property name to look up (e.g., 'spring.datasource.url') — for config")
                        humanReadable("The full property key in dotted notation — exactly as it would appear in application.properties.")
                        whenPresent("The Spring plugin resolves the effective value across all active property sources.")
                        constraint("must be a non-empty string")
                        example("spring.datasource.url")
                        example("server.port")
                        example("app.feature.payment-v2.enabled")
                    }
                }
                precondition("Property must be defined in a source visible to the Spring plugin's configuration model (application.properties, .yml, @PropertySource, etc.).")
                onSuccess("Returns the effective property value and the source file+line it was resolved from.")
                onFailure("property not found", "Returns an explicit not-found result. LLM should check the key spelling or use search_code to locate the property source.")
                example("look up datasource URL") {
                    param("action", "config")
                    param("property", "spring.datasource.url")
                    outcome("Returns the JDBC URL and the application.properties line it was resolved from.")
                }
                verdict {
                    keep("Correct multi-source property resolution is hard to replicate with search_code alone. Worth keeping for config debugging tasks.", VerdictSeverity.NORMAL)
                }
            }
            action("version_info") {
                description {
                    technical(
                        "Returns Spring framework version information for a specified module — Spring Core, Spring Boot, Spring Security, Spring Data, etc. — from the IDE's library model."
                    )
                    plain(
                        "Tells you what version of Spring (or Spring Boot, Spring Security, etc.) the project is using — like reading the pom.xml dependency but without parsing XML. " +
                            "Useful when you need to know if a feature is available before recommending it."
                    )
                }
                whenLLMUses("When the user asks 'what Spring Boot version are we on', 'does this project use Spring Security 6', or before recommending an API that was added in a specific version.")
                params {
                    optional("module", "string") {
                        llmSeesIt("Module name to inspect — for version_info")
                        humanReadable("Which Spring module to check. Examples: 'Spring Core', 'Spring Boot', 'Spring Security', 'Spring Data JPA'.")
                        whenPresent("Returns version info for the named module if found on the classpath.")
                        whenAbsent("Returns version info for all detected Spring modules.")
                        example("Spring Boot")
                        example("Spring Security")
                        example("Spring Data JPA")
                    }
                }
                onSuccess("Returns module name(s) and their resolved versions as seen by the IDE.")
                onFailure("module not on classpath", "Returns a not-found result for that module. LLM should suggest checking the build file.")
                example("check Spring Boot version") {
                    param("action", "version_info")
                    param("module", "Spring Boot")
                    outcome("Returns e.g. 'Spring Boot 3.2.4'.")
                }
                verdict {
                    keep("Low token cost, high utility for version-gated recommendations. Avoids parsing pom.xml or build.gradle.", VerdictSeverity.NORMAL)
                }
            }
            action("profiles") {
                description {
                    technical(
                        "Returns the active Spring profiles as resolved by the IDE's Spring configuration model — sourced from spring.profiles.active, VM arguments, and environment variables."
                    )
                    plain(
                        "Tells you which Spring profiles are currently active in the run configuration — like checking the 'Active Profiles' field in IntelliJ's run config dialog."
                    )
                }
                whenLLMUses("When the user asks 'which profiles are active', 'is the prod profile on', or before recommending profile-specific beans/configuration.")
                onSuccess("Returns the list of active profile names. Empty list if no profiles are active (default profile only).")
                onFailure("Spring plugin missing", "Returns SPRING_PLUGIN_MISSING_MSG.")
                example("check active profiles") {
                    param("action", "profiles")
                    outcome("Returns e.g. ['dev', 'local-db'] or empty list for default profile.")
                }
                verdict {
                    keep("One-liner call, zero params, resolves profile state that's spread across env vars + run configs + properties.", VerdictSeverity.NORMAL)
                }
            }
            action("repositories") {
                description {
                    technical(
                        "Lists Spring Data repository interfaces visible to the IDE's Spring plugin model — includes repository type (CrudRepository, JpaRepository, MongoRepository, etc.), the entity type, and the ID type."
                    )
                    plain(
                        "Shows all the Spring Data repository interfaces in the project — like browsing the 'Repositories' node in IntelliJ's Spring Beans panel. " +
                            "Tells you what entity each one manages and what kind of repository it is."
                    )
                }
                whenLLMUses("When the user asks 'what repositories exist', 'how do I query Order objects', or 'which repositories manage User entities'.")
                params {
                    optional("filter", "string") {
                        llmSeesIt("Filter results by name/pattern — for context, endpoints, boot_autoconfig")
                        humanReadable("Substring filter on repository interface name or entity type name.")
                        whenPresent("Only repositories matching the filter are returned.")
                        whenAbsent("All Spring Data repositories are returned.")
                        example("Order")
                        example("UserRepository")
                    }
                }
                precondition("Spring Data must be on the classpath and the Spring plugin must recognize the repository interfaces.")
                onSuccess("Returns repository interface name, extends (CrudRepository/JpaRepository/etc.), entity type, and ID type for each match.")
                onFailure("no repositories found", "Empty result. Spring Data may not be on the classpath, or the interfaces extend custom base types not recognized by the plugin.")
                example("list all repositories") {
                    param("action", "repositories")
                    outcome("Returns all Spring Data repositories with entity and ID types.")
                }
                verdict {
                    keep("Good complement to `bean_graph` for data-layer navigation. Low token cost.", VerdictSeverity.NORMAL)
                }
            }
            action("security_config") {
                description {
                    technical(
                        "Returns the Spring Security configuration as resolved by the IDE — security filter chains, HTTP security rules, authentication providers, and user details service wiring."
                    )
                    plain(
                        "Shows how Spring Security is configured — which URLs are protected, what authentication is required, which filter chains are active. " +
                            "Like reading the SecurityConfig class but with the IDE's resolved view."
                    )
                }
                whenLLMUses("When the user asks 'why is this endpoint returning 403', 'what URLs require authentication', or 'how is CSRF configured'. Saves scanning security config classes manually.")
                onSuccess("Returns security filter chain definitions with URL patterns, access rules, authentication mechanisms, and security bean declarations.")
                onFailure("Spring Security not on classpath", "Returns an informational not-configured result.")
                onFailure("Spring plugin missing", "Returns SPRING_PLUGIN_MISSING_MSG.")
                example("investigate 403 on /api/admin") {
                    param("action", "security_config")
                    outcome("Returns security rules — e.g. '/api/admin/**' requires ROLE_ADMIN; LLM can explain why unauthenticated requests get 403.")
                }
                verdict {
                    keep("Security config is spread across multiple classes and annotations — the IDE model resolves it into one view that's hard to reconstruct manually.", VerdictSeverity.NORMAL)
                }
            }
            action("scheduled_tasks") {
                description {
                    technical(
                        "Lists methods annotated with @Scheduled in the project — returns class, method, cron expression or fixed-rate/delay value, and initial delay."
                    )
                    plain(
                        "Shows all the scheduled background jobs — every method that Spring's @Scheduled annotation will run automatically. " +
                            "Like a cron-job listing for your Spring app."
                    )
                }
                whenLLMUses("When the user asks 'what background jobs run in this app', 'what cron expression does the cleanup task use', or wants to audit scheduled tasks.")
                onSuccess("Returns a list of (class, method, schedule expression) tuples — cron expressions, fixed-rate ms, or fixed-delay ms.")
                onFailure("no @Scheduled methods found", "Empty result — not an error. App may use async events or job frameworks instead.")
                example("list all scheduled tasks") {
                    param("action", "scheduled_tasks")
                    outcome("Returns e.g. 'CleanupService.purgeExpiredSessions — cron: 0 0 * * * ?' and 'MetricsService.flushMetrics — fixedRate: 30000ms'.")
                }
                verdict {
                    keep("Specialized but zero-param; `annotated_methods(annotation=Scheduled)` can do the same thing. Merge candidate.", VerdictSeverity.NORMAL)
                    drop("Fully subsumed by `annotated_methods(annotation=@Scheduled)`. Only difference is this is zero-param and returns a slightly pre-formatted view.", VerdictSeverity.WEAK)
                }
            }
            action("event_listeners") {
                description {
                    technical(
                        "Lists methods annotated with @EventListener in the project — returns class, method, and the event type(s) they listen to."
                    )
                    plain(
                        "Shows all the Spring event listeners — methods that react when something publishes an ApplicationEvent. " +
                            "Useful for tracing event-driven wiring that's invisible from call graphs."
                    )
                }
                whenLLMUses("When the user asks 'what happens when OrderPlacedEvent is published', 'what listeners exist for UserCreatedEvent', or investigates event-driven flows.")
                onSuccess("Returns a list of (class, method, event type) tuples for all @EventListener methods in the project.")
                onFailure("no @EventListener methods found", "Empty result — app may use messaging instead of Spring events.")
                example("list all event listeners") {
                    param("action", "event_listeners")
                    outcome("Returns e.g. 'NotificationService.onOrderPlaced — listens to: OrderPlacedEvent'.")
                }
                verdict {
                    keep("Event-driven wiring is invisible to call-graph tools — this is the only way to trace Spring event flows.", VerdictSeverity.NORMAL)
                    drop("Subsumed by `annotated_methods(annotation=@EventListener)`. Same data, slightly different format.", VerdictSeverity.WEAK)
                }
            }
            action("annotated_methods") {
                description {
                    technical(
                        "Generic annotation scanner — finds all methods in the project annotated with the specified Spring annotation, accepting short form (@Scheduled, Transactional) or fully-qualified name " +
                            "(org.springframework.cache.annotation.Cacheable). Optionally narrows by class name filter. " +
                            "Covers @Transactional, @Cacheable, @Async, @PreAuthorize, @Secured, @EventListener, @Scheduled, and any custom annotation."
                    )
                    plain(
                        "Find every method tagged with a specific annotation — like using IntelliJ's 'Find Usages' on an annotation type but returning methods instead of sites. " +
                            "Works for any Spring (or custom) annotation."
                    )
                }
                whenLLMUses("When the user asks 'what methods are @Transactional', 'which methods use @Cacheable', 'where is @Async used', or investigates any annotation-driven Spring behavior. " +
                    "Supersedes `scheduled_tasks` and `event_listeners` for any annotation the LLM can name.")
                params {
                    required("annotation", "string") {
                        llmSeesIt("Annotation name — short form (@Scheduled, Transactional, etc.) or fully-qualified (e.g. org.springframework.cache.annotation.Cacheable) — for annotated_methods")
                        humanReadable("Which annotation to scan for. Short form (Transactional, @Cacheable) or fully-qualified class name.")
                        whenPresent("Scanner resolves the annotation against the project's type system and finds all method usages.")
                        constraint("must not be empty")
                        example("@Transactional")
                        example("Cacheable")
                        example("org.springframework.scheduling.annotation.Async")
                        example("@PreAuthorize")
                    }
                    optional("filter", "string") {
                        llmSeesIt("Filter results by name/pattern — for context, endpoints, boot_autoconfig")
                        humanReadable("Substring filter on the declaring class name — narrows the scan to specific packages or classes.")
                        whenPresent("Only methods in classes whose name contains the filter are returned.")
                        whenAbsent("All project classes are scanned.")
                        example("Service")
                        example("OrderController")
                    }
                }
                precondition("Annotation must exist on the project's classpath for the IDE to resolve it by fully-qualified name.")
                onSuccess("Returns a list of (class, method, annotation details) tuples for all matching methods.")
                onFailure("annotation not found", "Returns an informational not-found result. Try the fully-qualified name.")
                onFailure("no annotated methods found", "Empty result — annotation may exist but have zero usages in the project.")
                example("find all @Transactional methods") {
                    param("action", "annotated_methods")
                    param("annotation", "@Transactional")
                    outcome("Returns every method marked @Transactional across the whole project with class and method names.")
                }
                example("find @PreAuthorize in controllers only") {
                    param("action", "annotated_methods")
                    param("annotation", "@PreAuthorize")
                    param("filter", "Controller")
                    outcome("Returns @PreAuthorize usages limited to controller classes.")
                }
                example("custom cache annotation") {
                    param("action", "annotated_methods")
                    param("annotation", "com.example.cache.CustomCacheable")
                    outcome("Returns all methods using the custom annotation — FQN required because short form is ambiguous.")
                }
                verdict {
                    keep("The most general of the Spring annotation tools — effectively makes `scheduled_tasks` and `event_listeners` redundant. Strongly prefer this for any annotation query.", VerdictSeverity.STRONG)
                }
            }
            action("boot_endpoints") {
                description {
                    technical(
                        "Returns Spring Boot endpoint mappings via the Boot-specific endpoint model — may differ from the generic `endpoints` action by including reactive routes, functional route definitions, and Boot-specific metadata. " +
                            "Optionally filtered by controller class name. Conditional: only available when includeEndpointActions=true."
                    )
                    plain(
                        "Like `endpoints` but tuned for Spring Boot — picks up reactive (WebFlux) routes and functional endpoints that `endpoints` may miss. " +
                            "Filter by class name to see one controller's routes."
                    )
                }
                whenLLMUses("When working with Spring Boot projects that use WebFlux or functional route definitions, or when `endpoints` doesn't show expected Boot routes.")
                params {
                    optional("class_name", "string") {
                        llmSeesIt("Filter by controller or config properties class name — for boot_endpoints, boot_config_properties")
                        humanReadable("Filter results to routes defined in this controller or router function class.")
                        whenPresent("Only endpoints from the named class are returned.")
                        whenAbsent("All Boot endpoint mappings are returned.")
                        example("OrderController")
                        example("ProductRouter")
                    }
                }
                precondition("Requires includeEndpointActions=true — not available in IDE environments with a dedicated endpoints meta-tool.")
                precondition("Spring Boot must be on the classpath.")
                onSuccess("Returns Boot endpoint mappings with HTTP method, path, handler class and method.")
                onFailure("includeEndpointActions=false", "Returns error redirect to the `endpoints` meta-tool.")
                example("all endpoints in OrderController") {
                    param("action", "boot_endpoints")
                    param("class_name", "OrderController")
                    outcome("Returns all routes declared in OrderController.")
                }
                verdict {
                    keep("Needed for WebFlux and functional router coverage that the generic `endpoints` action misses.", VerdictSeverity.NORMAL)
                }
            }
            action("boot_autoconfig") {
                description {
                    technical(
                        "Lists Spring Boot auto-configuration classes visible to the IDE — by default scoped to project classes only (project_only=true). " +
                            "Returns class name, condition annotations (@ConditionalOnClass, @ConditionalOnMissingBean, etc.), and whether the condition was satisfied."
                    )
                    plain(
                        "Shows which Spring Boot auto-configurations are active (and why) — like the Spring Boot Actuator's `/actuator/conditions` endpoint but without running the app. " +
                            "Tells you 'DataSourceAutoConfiguration is active because HikariCP is on the classpath'."
                    )
                }
                whenLLMUses("When the user asks 'why is X auto-configured', 'what auto-configs are active', or debugging 'why is DataSource being created automatically'.")
                params {
                    optional("filter", "string") {
                        llmSeesIt("Filter results by name/pattern — for context, endpoints, boot_autoconfig")
                        humanReadable("Substring filter on auto-configuration class name.")
                        whenPresent("Only auto-config classes matching the filter are returned.")
                        whenAbsent("All auto-config classes in scope are returned.")
                        example("DataSource")
                        example("Security")
                    }
                    optional("project_only", "boolean") {
                        llmSeesIt("If true (default), only scan project-scope classes; if false, includes library classes — for boot_autoconfig")
                        humanReadable("Controls whether to include auto-configurations from library JARs (Spring Boot itself, third-party starters).")
                        whenPresent("When false, includes library auto-configurations — can be a very long list.")
                        whenAbsent("Defaults to true — only project-defined auto-configurations returned.")
                        example("false")
                    }
                }
                precondition("Spring Boot must be on the classpath and the Spring plugin must recognize the auto-configuration model.")
                onSuccess("Returns auto-configuration class names with their condition annotations and evaluation results.")
                onFailure("no auto-configurations found", "Spring Boot may not be on the classpath, or project_only=true with no project-level auto-configurations defined.")
                example("debug DataSource auto-configuration") {
                    param("action", "boot_autoconfig")
                    param("filter", "DataSource")
                    outcome("Returns DataSourceAutoConfiguration with its @ConditionalOnClass conditions and whether they are matched.")
                }
                verdict {
                    keep("Static analysis of auto-config conditions is not replicable without the IDE's Spring model.", VerdictSeverity.NORMAL)
                }
            }
            action("boot_config_properties") {
                description {
                    technical(
                        "Returns @ConfigurationProperties bindings with rich IDE metadata — property key prefix, bound field names, their types, default values, descriptions (from spring-configuration-metadata.json), " +
                            "deprecation info, and source types. Can be filtered by class name or prefix."
                    )
                    plain(
                        "Shows every configuration property a @ConfigurationProperties class binds — like IntelliJ's property completion hints but as a tool result. " +
                            "Tells you the key, type, description, default, and whether it's deprecated for each bound field."
                    )
                }
                whenLLMUses("When the user asks 'what properties does DatabaseConfig bind', 'what's the default for app.cache.ttl', or 'what configuration does the payment module expose'.")
                params {
                    optional("class_name", "string") {
                        llmSeesIt("Filter by controller or config properties class name — for boot_endpoints, boot_config_properties")
                        humanReadable("Filter to a specific @ConfigurationProperties class by name.")
                        whenPresent("Returns bindings only from that class.")
                        whenAbsent("Returns bindings from all @ConfigurationProperties classes in scope.")
                        example("DatabaseProperties")
                        example("CacheProperties")
                    }
                    optional("prefix", "string") {
                        llmSeesIt("Filter by configuration properties prefix — for boot_config_properties")
                        humanReadable("Filter to classes whose @ConfigurationProperties prefix matches this string.")
                        whenPresent("Only classes with matching prefix are returned.")
                        whenAbsent("All prefixes included.")
                        example("app.cache")
                        example("spring.datasource")
                    }
                }
                precondition("@ConfigurationProperties classes must be annotated and recognized by the Spring plugin. spring-configuration-metadata.json enriches the result with descriptions and defaults.")
                onSuccess("Returns each @ConfigurationProperties class with prefix, and per-field: name, type, description, default value, deprecation status.")
                onFailure("no @ConfigurationProperties found", "Either none exist or the filter is too narrow — widen the filter.")
                example("show all bindings for CacheProperties") {
                    param("action", "boot_config_properties")
                    param("class_name", "CacheProperties")
                    outcome("Returns all bound fields with their keys, types, defaults, and descriptions from spring-configuration-metadata.json.")
                }
                example("find all properties under 'app.cache' prefix") {
                    param("action", "boot_config_properties")
                    param("prefix", "app.cache")
                    outcome("Returns all @ConfigurationProperties classes with prefix='app.cache' and their field bindings.")
                }
                verdict {
                    keep("Unique IDE metadata (descriptions, defaults, deprecations) not available from annotation scanning alone.", VerdictSeverity.STRONG)
                }
            }
            action("boot_actuator") {
                description {
                    technical(
                        "Lists Spring Boot Actuator endpoints configured in the project — returns endpoint id (health, metrics, env, beans, etc.), whether it's exposed over HTTP/JMX, and the URL path."
                    )
                    plain(
                        "Shows which Actuator endpoints your Spring Boot app has enabled and what URL they're at — like reading management.endpoints.web.exposure.include from config but resolved by the IDE model."
                    )
                }
                whenLLMUses("When the user asks 'which actuator endpoints are available', 'what is the health endpoint URL', or before using HTTP probes to check app readiness.")
                onSuccess("Returns a list of Actuator endpoint IDs with their exposure status (HTTP/JMX) and URL paths.")
                onFailure("Actuator not on classpath", "Returns an informational not-configured result.")
                onFailure("Spring plugin missing", "Returns SPRING_PLUGIN_MISSING_MSG.")
                example("list all Actuator endpoints") {
                    param("action", "boot_actuator")
                    outcome("Returns e.g. 'health — HTTP: /actuator/health, JMX: Health', 'metrics — HTTP: /actuator/metrics', etc.")
                }
                verdict {
                    keep("Zero-param, useful before HTTP probing or debugging production health checks.", VerdictSeverity.NORMAL)
                }
            }
            action("jpa_entities") {
                description {
                    technical(
                        "Returns JPA entity metadata via IntelliJ's Persistence plugin model — entity class, table mapping, inheritance strategy, relationship fields (OneToMany, ManyToOne, OneToOne, ManyToMany) " +
                            "with their cardinality, fetch type (EAGER/LAZY), cascade types, and named queries."
                    )
                    plain(
                        "Shows the full JPA data model — entities, their table names, how they relate to each other, and the fetch/cascade strategy for each relationship. " +
                            "Like reading @Entity classes but with the IDE resolving inheritance chains and relationship metadata."
                    )
                }
                whenLLMUses("When the user asks 'what entities exist', 'what relationships does Order have', 'which entities use EAGER fetching', or debugging N+1 query issues by inspecting fetch types.")
                params {
                    optional("entity", "string") {
                        llmSeesIt("Specific entity class name — for jpa_entities")
                        humanReadable("Filter to a specific entity class by simple name. Returns that entity's full metadata.")
                        whenPresent("Returns only the named entity's metadata.")
                        whenAbsent("Returns all JPA entities in the project.")
                        example("Order")
                        example("UserAccount")
                    }
                }
                precondition("Requires IntelliJ's Persistence plugin to be installed — separate from the Spring plugin. Present in IntelliJ Ultimate but may need to be enabled.")
                precondition("JPA / Hibernate must be on the classpath and entities must be annotated with @Entity.")
                onSuccess("Returns entity class name, table name, inheritance strategy, and per-relationship: field name, type, cardinality, fetch, cascade, mappedBy/joinColumn. Also lists @NamedQuery declarations.")
                onFailure("Persistence plugin missing", "Returns an error indicating the IntelliJ Persistence plugin is required.")
                onFailure("entity not found", "Returns a not-found result. Try without the `entity` filter to list available entity names.")
                onFailure("no JPA entities found", "Either no @Entity classes exist or they're in library JARs outside the scan scope.")
                example("get Order entity metadata") {
                    param("action", "jpa_entities")
                    param("entity", "Order")
                    outcome("Returns Order's table name, its OneToMany(items, LAZY, CascadeType.ALL), ManyToOne(customer, EAGER), and any @NamedQuery declarations.")
                }
                example("audit all EAGER relationships") {
                    param("action", "jpa_entities")
                    outcome("Returns all entities — LLM can filter the results for EAGER fetch types to find N+1 candidates.")
                }
                verdict {
                    keep("JPA metadata (inheritance strategy, fetch/cascade per relationship, named queries) requires the Persistence plugin model — not replicable via annotation scanning.", VerdictSeverity.STRONG)
                }
            }
        }
        observation("Consider deprecating `scheduled_tasks` and `event_listeners` — both are fully subsumed by `annotated_methods(annotation=@Scheduled)` and `annotated_methods(annotation=@EventListener)`. The only argument for keeping them is zero-param convenience.")
        observation("The `endpoints` vs `boot_endpoints` split exists for IDE-environment reasons (includeEndpointActions flag) but is confusing to the LLM. The description should be clearer about when to use each.")
        mergeOpportunity("`scheduled_tasks` and `event_listeners` → `annotated_methods`. The former two are convenience wrappers with no additional data that the generic scanner doesn't return.")
    }

    companion object {
        internal const val SPRING_PLUGIN_MISSING_MSG =
            "Error: Spring plugin is not available. Install 'Spring' plugin in IntelliJ Ultimate to use this tool."
    }
}

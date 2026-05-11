package com.workflow.orchestrator.agent.tools.framework.endpoints

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
import kotlin.coroutines.coroutineContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Multi-framework endpoints meta-tool backed by IntelliJ's Microservices
 * Framework. Registered only on editions that bundle
 * `com.intellij.modules.microservices` (Ultimate / PyCharm Pro / WebStorm /
 * Rider / GoLand / RubyMine).
 *
 * Supersedes `spring.endpoints` and `spring.boot_endpoints`: covers every
 * framework that registered an `EndpointsProvider` — Spring MVC / WebFlux /
 * Actuator / Feign, JAX-RS / Jakarta RS, Micronaut (HTTP + management +
 * WebSocket), Quarkus, Helidon, Ktor, gRPC/Protobuf, OpenAPI/Swagger,
 * Retrofit, OkHttp, HTTP Client `.http` files, Django/FastAPI/Flask (PyCharm
 * Pro).
 *
 * The three actions share `EndpointsDiscoverer` as their data source.
 */
class EndpointsTool : AgentTool {

    override val name = "endpoints"

    override val description = """
Multi-framework HTTP endpoint intelligence — backed by IntelliJ's Endpoints view.

Discovers HTTP-server and HTTP-client endpoints across every framework the
IDE supports: Spring MVC/WebFlux/Actuator/Feign, JAX-RS, Micronaut, Ktor,
gRPC, OpenAPI, Retrofit, OkHttp, HTTP Client files, and more.

Actions and their parameters:
- list(filter?, framework?, endpoint_type?) → list every discovered endpoint
- find_usages(url) → find all call sites of a URL (handler + client string literals)
- export_openapi(framework?) → render discovered endpoints as an OpenAPI 3 spec
- export_http_scratch(filter?, host?) → Generate a JetBrains HTTP Client .http scratch file from discovered endpoints and open it
- list_async(filter?) → Async messaging endpoints (Kafka/RabbitMQ/JMS destinations)
- service_graph() → Mermaid graph of services by module (node-only skeleton)
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf("list", "find_usages", "export_openapi", "export_http_scratch", "list_async", "service_graph"),
            ),
            "filter" to ParameterProperty(
                type = "string",
                description = "Free-text filter on URL, HTTP method, or handler class — for list, export_http_scratch",
            ),
            "framework" to ParameterProperty(
                type = "string",
                description = "Framework name substring (e.g. 'Spring', 'Micronaut', 'JAX-RS') — for list, export_openapi",
            ),
            "endpoint_type" to ParameterProperty(
                type = "string",
                description = "Endpoint category: HTTP-Server, HTTP-Client, WebSocket-Server, API-Definition — for list",
            ),
            "url" to ParameterProperty(
                type = "string",
                description = "Full URL path to resolve (e.g. '/api/users/{id}') — for find_usages",
            ),
            "method" to ParameterProperty(
                type = "string",
                description = "Optional HTTP method hint (GET/POST/…) to narrow resolution — for find_usages",
            ),
            "host" to ParameterProperty(
                type = "string",
                description = "Base URL prefix for .http blocks, defaults to http://localhost:8080 — for export_http_scratch",
            ),
        ),
        required = listOf("action"),
    )

    override val allowedWorkers = setOf(
        WorkerType.TOOLER,
        WorkerType.ANALYZER,
        WorkerType.REVIEWER,
        WorkerType.ORCHESTRATOR,
        WorkerType.CODER,
    )

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        coroutineContext.ensureActive()
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult(
                content = "Error: 'action' parameter required",
                summary = "Error: missing action",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true,
            )

        return when (action) {
            "list" -> executeListEndpoints(params, project)
            "find_usages" -> executeFindUsages(params, project)
            "export_openapi" -> executeExportOpenApi(params, project)
            "export_http_scratch" -> executeExportHttpScratch(params, project)
            "list_async" -> executeListAsync(params, project)
            "service_graph" -> executeServiceGraph(params, project)
            else -> ToolResult(
                content = "Unknown action '$action'. Valid actions: list, find_usages, export_openapi, export_http_scratch, list_async, service_graph.",
                summary = "Unknown action '$action'",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true,
            )
        }
    }

    override fun documentation(): ToolDocumentation = toolDoc("endpoints") {
        summary {
            technical(
                "Cross-framework HTTP endpoint meta-tool backed by IntelliJ's Microservices Framework SPI " +
                    "(com.intellij.modules.microservices — Ultimate / PyCharm Pro / WebStorm / Rider / GoLand / RubyMine). " +
                    "Iterates every registered EndpointsProvider and EndpointsUrlTargetProvider to discover server endpoints, " +
                    "client call sites, async messaging destinations (Kafka/RabbitMQ/JMS), and API definitions (OpenAPI/Swagger) " +
                    "across all frameworks: Spring MVC/WebFlux/Actuator/Feign, JAX-RS/Jakarta RS, Micronaut, Quarkus, Helidon, " +
                    "Ktor, gRPC/Protobuf, Retrofit, OkHttp, HTTP Client .http files, Django/FastAPI/Flask (PyCharm Pro). " +
                    "Six actions: list (enumerate + filter), find_usages (URL → all call sites via UrlResolverManager), " +
                    "export_openapi (synthesise OpenAPI 3 YAML via Swagger plugin), export_http_scratch (generate + open a .http scratch file), " +
                    "list_async (Kafka/RabbitMQ/JMS destinations via MQResolverManager), service_graph (Mermaid node skeleton by module). " +
                    "Supersedes spring.endpoints and spring.boot_endpoints in IDEs where this tool is registered — " +
                    "SpringTool.includeEndpointActions is false in that configuration and its endpoint actions redirect here."
            )
            plain(
                "A universal 'show me every HTTP route' tool that works regardless of which web framework the project uses. " +
                    "Instead of asking 'what does Spring think?' or 'what does FastAPI think?', the agent asks `endpoints` once " +
                    "and gets an IDE-wide inventory: server routes, client call sites, async queues, and exported OpenAPI specs — " +
                    "all from the same Endpoints view that IntelliJ shows in the IDE UI. Like opening the Endpoints tool window " +
                    "and pressing Ctrl+F, but returning the results as text. Only available in Ultimate-tier IDEs that bundle " +
                    "the Microservices plugin."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.READ_ONLY)
        counterfactual(
            "Without `endpoints`, the LLM must call framework-specific tools in sequence: `spring(action=endpoints)` for Spring, " +
                "`fastapi(action=routes)` for FastAPI, `flask(action=routes)` for Flask — and still has no coverage for JAX-RS, Micronaut, " +
                "gRPC, Retrofit, OpenAPI definitions, HTTP Client files, or inter-service client call sites. " +
                "`find_usages` in particular has no fallback: resolving a URL string to every place it is called in handlers AND client " +
                "string literals requires IntelliJ's UrlResolverManager SPI — there is no manual equivalent. " +
                "`export_openapi` similarly requires the Swagger plugin OAS synthesis path that `search_code` cannot replicate."
        )
        llmMistake(
            "Calls `spring(action=endpoints)` or `spring(action=boot_endpoints)` in an IDE where `endpoints` is registered. " +
                "Those actions return an explicit redirect error pointing here. The LLM should switch to `endpoints(action=list, framework=\"Spring\")` immediately."
        )
        llmMistake(
            "Calls `list` and then tries to compose a route inventory from the raw output for frameworks that `fastapi` or `flask` " +
                "also cover — and assumes the results will be identical. In IntelliJ Ultimate with PyCharm Python support, `list` " +
                "returns Django/FastAPI/Flask routes via the Python microservices provider; `fastapi(action=routes)` returns routes " +
                "via file-scan regex. The two may differ on dynamically-registered routes — prefer `list` in Ultimate environments."
        )
        llmMistake(
            "Passes `url` to `list` — `url` is only accepted by `find_usages`. `list` uses `filter` for free-text matching on URL path, HTTP method, or handler class."
        )
        llmMistake(
            "Calls `export_openapi` expecting a runnable spec from a gRPC or WebSocket project. " +
                "The action filters to HTTP-Server endpoints only and requires the bundled OpenAPI Specifications (Swagger) plugin. " +
                "Projects with only gRPC or WebSocket handlers will produce an empty-export message."
        )
        llmMistake(
            "Assumes `service_graph` shows inter-service call edges. " +
                "The current implementation emits a node-only Mermaid skeleton (services identified by module name); " +
                "edge extraction from handler bodies (RestTemplate, WebClient, FeignClient) is a planned follow-up — the output says '0 edge(s) [node-only skeleton]'."
        )
        llmMistake(
            "Calls `list_async` expecting HTTP endpoints. `list_async` is scoped exclusively to async messaging " +
                "(Kafka @KafkaListener, RabbitMQ @RabbitListener, JMS @JmsListener) and requires the spring-messaging provider. " +
                "For HTTP routes, use `list` instead."
        )
        downside(
            "Requires com.intellij.modules.microservices — not available in IntelliJ Community, standard PyCharm, " +
                "or any non-Ultimate-tier product that omits the Microservices plugin. " +
                "In Community, the tool is never registered; the LLM must fall back to framework-specific tools or `search_code`."
        )
        downside(
            "Results reflect the IDE's index state at call time. A stale or mid-build index may return partial or empty results " +
                "without surfacing an error. Re-index the project (File → Invalidate Caches) if results look suspiciously empty."
        )
        downside(
            "`export_openapi` requires the bundled 'OpenAPI Specifications' (Swagger) plugin. " +
                "If the user has disabled it under Settings → Plugins, the action returns a diagnostic message " +
                "instead of YAML output — not a failure the LLM can fix programmatically."
        )
        downside(
            "`export_http_scratch` mutates IDE state by creating a scratch file and opening it in the editor. " +
                "Although the data side is read-only (no project files changed), the IDE file-system sees a new file. " +
                "This is the only action with a visible side effect; the tool's sideEffect is READ_ONLY for the project's " +
                "source tree, but users may be surprised by the auto-opened tab."
        )
        downside(
            "`service_graph` emits a node-only Mermaid skeleton. Inter-service edges (RestTemplate, WebClient, FeignClient, " +
                "RestClient calls in handler bodies) are not extracted — the graph is useful for a service inventory but " +
                "not for dependency tracing between services."
        )
        downside(
            "`list_async` self-gates when MQResolverManager has no registered framework-specific resolver (e.g., the project " +
                "uses Kafka without spring-messaging). In that case it returns a friendly no-results message — the LLM " +
                "should fall back to `search_code` with `@KafkaListener` / `@RabbitListener` patterns."
        )
        related(
            "spring", Relationship.ALTERNATIVE,
            "Use `spring(action=endpoints)` in Community or any IDE without the Microservices plugin. " +
                "In Ultimate, SpringTool.includeEndpointActions=false and its endpoint actions redirect to `endpoints`. " +
                "For non-endpoint Spring queries (beans, JPA, security), `spring` remains the correct tool."
        )
        related(
            "fastapi", Relationship.ALTERNATIVE,
            "Use `fastapi(action=routes)` when the Microservices plugin is unavailable or when file-scan precision " +
                "(exact decorator-level source location) is needed. `fastapi` uses PythonFileScanner regex — zero IDE plugin dependency; " +
                "`endpoints` uses the IDE's Python microservices provider (requires PyCharm Pro). Results may differ on dynamic routes."
        )
        related(
            "flask", Relationship.ALTERNATIVE,
            "Use `flask(action=routes)` when the Microservices plugin is unavailable. Same trade-off as `fastapi` vs `endpoints`: " +
                "file-scan is always available, IDE provider requires Ultimate/PyCharm Pro. Blueprint prefix resolution is analogous."
        )
        related(
            "find_references", Relationship.COMPLEMENT,
            "Use `find_references` for PSI-level usages of a Java/Kotlin symbol (class, method). " +
                "Use `endpoints(action=find_usages)` to resolve a URL string to every handler AND HTTP-client call site — " +
                "including string literals in WebClient/RestTemplate calls that PSI find-references misses."
        )
        related(
            "search_code", Relationship.FALLBACK,
            "Fall back to `search_code` when the Microservices plugin is unavailable, the index is stale, " +
                "or endpoint results are unexpectedly empty. Regex on @RequestMapping/@GetMapping/@PostMapping annotations covers most Spring endpoints; " +
                "regex on @app.get/@router.post covers FastAPI/Flask."
        )
        mergeOpportunity(
            "`endpoints(action=list, framework=\"Spring\")` overlaps with `spring(action=endpoints)` in Ultimate IDEs where " +
                "includeEndpointActions=true. The split is intentional: SpringTool.includeEndpointActions=false when `endpoints` is registered, " +
                "so the two actions are mutually exclusive at runtime — but the doc surface creates confusion. " +
                "Long-term: either always register `endpoints` and always set includeEndpointActions=false, or document the IDE-context split more clearly."
        )
        observation(
            "`export_http_scratch` is the only action with a user-visible side effect (scratch file + auto-open in editor). " +
                "Marking the tool READ_ONLY is technically correct for project source files but may surprise users who don't want a tab opened. " +
                "Consider a `dry_run` param that returns the .http body without creating the scratch file."
        )
        observation(
            "The `endpoint_type` filter on `list` accepts 'HTTP-Server', 'HTTP-Client', 'WebSocket-Server', 'API-Definition' — " +
                "these values come from EndpointType.queryTag and are framework-defined strings. " +
                "The LLM must know these exact strings; they are not enumerated in the parameter description."
        )
        actions {
            action("list") {
                description {
                    technical(
                        "Calls EndpointsDiscoverer.discover(project) to iterate all registered EndpointsProvider instances " +
                            "and applies three optional filters: free-text substring on URL/method/handler, framework name substring, " +
                            "and endpoint type tag (HTTP-Server / HTTP-Client / WebSocket-Server / API-Definition). " +
                            "Returns up to 100 rows sorted by URL then HTTP method; truncates with a remainder count."
                    )
                    plain(
                        "Lists every HTTP route the IDE knows about — sorted by URL path. " +
                            "Covers all frameworks at once: Spring routes, FastAPI routes, gRPC stubs, Retrofit clients, and more. " +
                            "Narrow down with a filter substring or restrict to a single framework by name."
                    )
                }
                whenLLMUses(
                    "When the user asks 'what HTTP routes exist in this project', 'what handles GET /api/users', " +
                        "'show me all Spring endpoints', or 'list all WebSocket handlers'. " +
                        "Primary first-call for any endpoint discovery task in an Ultimate-tier IDE."
                )
                params {
                    optional("filter", "string") {
                        llmSeesIt("Free-text filter on URL, HTTP method, or handler class — for list, export_http_scratch")
                        humanReadable("Case-insensitive substring applied to the URL path, HTTP method string (GET/POST/...), or fully-qualified handler class name.")
                        whenPresent("Only endpoints matching the substring in any of the three fields are returned.")
                        whenAbsent("All discovered endpoints are returned (up to 100 rows; truncated with count if more).")
                        example("/api/users")
                        example("POST")
                        example("OrderController")
                    }
                    optional("framework", "string") {
                        llmSeesIt("Framework name substring (e.g. 'Spring', 'Micronaut', 'JAX-RS') — for list, export_openapi")
                        humanReadable("Case-insensitive substring matched against the EndpointsProvider presentation title (e.g. 'Spring MVC', 'Micronaut HTTP', 'JAX-RS').")
                        whenPresent("Only endpoints registered by a provider whose title contains this substring are shown.")
                        whenAbsent("Endpoints from all registered providers across all frameworks are returned.")
                        example("Spring")
                        example("Micronaut")
                        example("JAX-RS")
                        example("gRPC")
                    }
                    optional("endpoint_type", "string") {
                        llmSeesIt("Endpoint category: HTTP-Server, HTTP-Client, WebSocket-Server, API-Definition — for list")
                        humanReadable("Restricts to one category of endpoint. Values are EndpointType.queryTag strings defined by each provider.")
                        whenPresent("Only endpoints whose endpointType tag equals this value (case-insensitive) are returned.")
                        whenAbsent("All endpoint types are returned: server routes, client call sites, WebSocket handlers, and API definitions.")
                        enumValue("HTTP-Server", "HTTP-Client", "WebSocket-Server", "API-Definition")
                        example("HTTP-Server")
                        example("HTTP-Client")
                    }
                }
                precondition("IDE index must be up-to-date. Stale index may return an empty or partial list without an error.")
                precondition("At least one EndpointsProvider must be registered — requires the Microservices plugin and a supported framework on the classpath.")
                onSuccess("Returns a formatted list: 'METHOD   /path    [Framework] (endpointType)' with handler class.method() and source file:line. Truncated at 100 with remainder count.")
                onFailure("No endpoints discovered", "Either no EndpointsProvider is registered for the project's framework, or the index is stale. Try re-indexing or use search_code.")
                onFailure("No endpoints matching the given filters", "The filter/framework/endpoint_type combination is too narrow. Broaden or remove filters.")
                example("list all endpoints") {
                    param("action", "list")
                    outcome("Returns all discovered endpoints across all frameworks and types — Spring routes, Retrofit clients, gRPC stubs, etc.")
                }
                example("Spring server routes containing 'orders'") {
                    param("action", "list")
                    param("filter", "orders")
                    param("framework", "Spring")
                    param("endpoint_type", "HTTP-Server")
                    outcome("Returns Spring MVC/WebFlux server routes whose URL path contains 'orders'.")
                }
                verdict {
                    keep(
                        "The canonical cross-framework endpoint inventory — the only single call that covers all supported frameworks simultaneously. " +
                            "The HTTP-Client filtering (client call sites) and WebSocket-Server type are unique capabilities with no per-framework tool equivalent.",
                        VerdictSeverity.STRONG
                    )
                }
            }
            action("find_usages") {
                description {
                    technical(
                        "Parses the `url` param into a UrlResolveRequest (scheme, authority, UrlPath, optional method hint) " +
                            "and calls UrlResolverManager.getInstance(project).resolve(request) to enumerate every UrlTargetInfo " +
                            "registered by all EndpointsProvider implementations. Returns both handler declarations " +
                            "(HTTP-Server targets) and client call sites (HTTP-Client targets, including string literals in " +
                            "WebClient/RestTemplate/FeignClient/OkHttp calls and .http scratch file requests)."
                    )
                    plain(
                        "Answers 'who calls this URL?' — like Find Usages in the IDE but for URLs, not symbols. " +
                            "Discovers every place a URL path is referenced: the handler that serves it, " +
                            "any RestTemplate/WebClient client that calls it, Feign client interfaces that declare it, " +
                            "and .http scratch files that request it. Essential for impact analysis before renaming or removing a route."
                    )
                }
                whenLLMUses(
                    "When the user asks 'who calls /api/orders/{id}', 'where is this endpoint consumed', " +
                        "'is this route used anywhere', or needs to trace inter-service dependencies by URL. " +
                        "Primary tool for URL-level impact analysis."
                )
                params {
                    required("url", "string") {
                        llmSeesIt("Full URL path to resolve (e.g. '/api/users/{id}') — for find_usages")
                        humanReadable("The URL path to look up. Can be a bare path ('/api/orders/{id}'), or include scheme+authority ('http://localhost:8080/api/orders/{id}'). Path variables use {varName} syntax.")
                        whenPresent("URL is parsed into scheme/authority/path components and resolved via UrlResolverManager.")
                        constraint("Must not be blank")
                        example("/api/users/{id}")
                        example("/api/orders")
                        example("http://order-service/api/orders/{id}")
                    }
                    optional("method", "string") {
                        llmSeesIt("Optional HTTP method hint (GET/POST/…) to narrow resolution — for find_usages")
                        humanReadable("When provided, UrlResolverManager includes the HTTP method in the resolve request to narrow matches.")
                        whenPresent("Resolve request includes the method hint — providers that distinguish by method (Spring MVC) return only matching targets.")
                        whenAbsent("All HTTP methods are included in the resolve — may return more results including both GET and POST handlers for the same path.")
                        enumValue("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS", "HEAD")
                        example("GET")
                        example("POST")
                    }
                }
                rejectsParam("filter", "find_usages resolves by exact URL via UrlResolverManager — use `url` for the path, `method` to narrow by verb")
                rejectsParam("framework", "find_usages searches all registered providers; cannot be narrowed by framework")
                rejectsParam("endpoint_type", "find_usages returns all target types; cannot be narrowed by endpoint_type")
                precondition("IDE index must be up-to-date.")
                precondition("The URL must match a path known to at least one EndpointsProvider. Paths not indexed by the Microservices framework return empty results.")
                onSuccess("Returns resolved targets as '[METHOD] source' lines with file:line, sorted by source. Up to 100 shown; truncated with remainder count.")
                onFailure("No usages found", "The URL is not in the microservices index. Either the route doesn't exist, the index is stale, or the URL pattern uses syntax the provider doesn't index (e.g. runtime-composed paths).")
                example("find all callers of /api/orders/{id}") {
                    param("action", "find_usages")
                    param("url", "/api/orders/{id}")
                    param("method", "GET")
                    outcome("Returns the OrderController.getOrder() handler, any WebClient/RestTemplate calls to '/api/orders/{id}', Feign client declarations, and .http file requests — all in one result.")
                    notes("Without the method hint, both GET and PUT/PATCH handlers for the same path template would be included.")
                }
                verdict {
                    keep(
                        "Unique capability — resolving a URL string to all its declaration and call-site targets via UrlResolverManager " +
                            "is not reproducible with search_code or find_references. Critical for inter-service impact analysis.",
                        VerdictSeverity.STRONG
                    )
                }
            }
            action("export_openapi") {
                description {
                    technical(
                        "Filters EndpointsDiscoverer results to HTTP-Server endpoints only (optionally narrowed by framework substring), " +
                            "extracts their UrlTargetInfo objects, passes them to getSpecificationByUrls() to build an OAS DTO, " +
                            "then calls generateOasDraft(projectName, spec) from the bundled Swagger plugin to produce YAML text. " +
                            "Requires the 'OpenAPI Specifications' (Swagger) plugin to be enabled."
                    )
                    plain(
                        "Generates an OpenAPI 3 specification from the IDE's discovered endpoints — like 'export to OpenAPI' " +
                            "in the Endpoints tool window. Returns YAML text the agent can write to a file, show to the user, " +
                            "or use as a basis for client generation. Requires the Swagger plugin (bundled in Ultimate)."
                    )
                }
                whenLLMUses(
                    "When the user asks 'generate an OpenAPI spec for this service', 'export the API contract', " +
                        "'create a swagger.yaml from the existing routes', or needs an OAS document for client generation or documentation."
                )
                params {
                    optional("framework", "string") {
                        llmSeesIt("Framework name substring (e.g. 'Spring', 'Micronaut', 'JAX-RS') — for list, export_openapi")
                        humanReadable("Restricts the exported spec to endpoints from one framework provider. Without this, all HTTP-Server endpoints from all frameworks are included.")
                        whenPresent("Only HTTP-Server endpoints from providers whose title contains this substring are included in the OAS output.")
                        whenAbsent("All HTTP-Server endpoints from all registered providers are exported to the spec.")
                        example("Spring")
                        example("Micronaut")
                    }
                }
                rejectsParam("filter", "export_openapi does not support free-text filtering — use `framework` to narrow by framework, or filter the YAML output after export")
                rejectsParam("endpoint_type", "export_openapi is scoped to HTTP-Server endpoints only — endpoint_type is not configurable")
                precondition("The bundled 'OpenAPI Specifications' (Swagger) plugin must be enabled under Settings → Plugins.")
                precondition("At least one HTTP-Server endpoint must be discoverable in the project.")
                onSuccess("Returns YAML text — a valid OpenAPI 3 draft spec synthesised from the discovered URL targets. Summary shows line count.")
                onFailure("Swagger plugin disabled", "Returns a diagnostic message explaining the plugin must be enabled. The LLM cannot enable it programmatically.")
                onFailure("No HTTP-Server endpoints found", "No HTTP-Server endpoints were discovered (or the framework filter matched none). Use list first to confirm endpoints exist.")
                example("export OpenAPI spec for the full project") {
                    param("action", "export_openapi")
                    outcome("Returns a YAML OpenAPI 3 spec covering all HTTP-Server endpoints from all frameworks. Can be written to openapi.yaml with create_file.")
                }
                example("export OpenAPI spec for Spring only") {
                    param("action", "export_openapi")
                    param("framework", "Spring")
                    outcome("Returns an OAS spec covering only Spring MVC/WebFlux endpoints — excludes gRPC, Micronaut, etc.")
                }
                verdict {
                    keep(
                        "OAS synthesis via the Swagger plugin is not replicable with any other agent tool — " +
                            "it requires UrlTargetInfo objects that only the IDE's microservices SPI produces.",
                        VerdictSeverity.NORMAL
                    )
                }
            }
            action("export_http_scratch") {
                description {
                    technical(
                        "Calls EndpointsDiscoverer.discover(project) optionally filtered by a free-text substring, " +
                            "builds JetBrains HTTP Client .http request blocks (one ### per endpoint, with empty {} body placeholders for POST/PUT/PATCH), " +
                            "creates a scratch file via ScratchRootType.createScratchFile with the HTTP Request language (if the HTTP Client plugin is installed), " +
                            "and opens it in the editor via FileEditorManager.openFile. Returns the scratch file path and the .http body in the ToolResult."
                    )
                    plain(
                        "Turns the discovered endpoint list into a ready-to-run JetBrains HTTP Client file — like 'Generate → HTTP Request' " +
                            "in the editor but across the entire project at once. The file opens in the editor with gutter 'Run' icons " +
                            "if the HTTP Client plugin is installed. Useful for manual API testing, sharing request collections, or bootstrapping documentation."
                    )
                }
                whenLLMUses(
                    "When the user asks 'create HTTP requests for all these endpoints', 'make a .http file I can test with', " +
                        "'generate a request collection for the API', or wants to manually verify endpoints without writing curl commands."
                )
                params {
                    optional("filter", "string") {
                        llmSeesIt("Free-text filter on URL, HTTP method, or handler class — for list, export_http_scratch")
                        humanReadable("Case-insensitive substring filter applied before generating .http blocks — narrows the exported set.")
                        whenPresent("Only endpoints matching the substring are included in the scratch file.")
                        whenAbsent("All discovered endpoints are included.")
                        example("orders")
                        example("/api/v1")
                        example("POST")
                    }
                    optional("host", "string") {
                        llmSeesIt("Base URL prefix for .http blocks, defaults to http://localhost:8080 — for export_http_scratch")
                        humanReadable("Prepended to every relative URL path when building the .http request line. Endpoints whose URL already starts with 'http' are used verbatim.")
                        whenPresent("Relative endpoint paths are prefixed with this value in every request block.")
                        whenAbsent("Defaults to 'http://localhost:8080'. Update if the service runs on a different port or host.")
                        constraint("Must be a valid base URL (scheme + host + optional port), no trailing slash")
                        example("http://localhost:9090")
                        example("https://api.example.com")
                    }
                }
                rejectsParam("framework", "export_http_scratch uses the free-text `filter` param for narrowing — framework-based filtering is not supported")
                rejectsParam("endpoint_type", "export_http_scratch includes all discovered endpoint types in the scratch file")
                precondition("At least one endpoint must match after filtering.")
                onSuccess("Returns the scratch file path and the full .http body. The file is opened in the editor. Summary shows how many endpoints were exported.")
                onFailure("No endpoints matched filter", "The filter substring matched nothing. Try broadening or removing the filter.")
                onFailure("Scratch file creation failed", "ScratchRootType returned null — unusual IDE state. The full .http body is included in the ToolResult so the agent can create the file manually with create_file.")
                example("generate .http file for all endpoints") {
                    param("action", "export_http_scratch")
                    outcome("Creates and opens 'workflow-endpoints.http' in the editor with one block per discovered endpoint. Returns the file path and .http content.")
                }
                example("generate .http file for order routes on port 9090") {
                    param("action", "export_http_scratch")
                    param("filter", "orders")
                    param("host", "http://localhost:9090")
                    outcome("Creates a scratch file with only the order-related endpoints, using port 9090 as the base URL.")
                }
                verdict {
                    keep(
                        "Provides a runnable request collection in one call — no template wiring required. " +
                            "Scratch file creation with editor auto-open is a user-facing affordance unavailable through other tools.",
                        VerdictSeverity.NORMAL
                    )
                }
            }
            action("list_async") {
                description {
                    technical(
                        "Calls AsyncEndpointsDiscoverer.discover(project) which delegates to MQResolverManager to enumerate " +
                            "async messaging destinations: Kafka topics (@KafkaListener), RabbitMQ queues (@RabbitListener), " +
                            "and JMS destinations (@JmsListener). Results include destination name, MQ type, access type " +
                            "(producer/consumer), handler class and method, and source file location. " +
                            "Self-gates when MQResolverManager has no registered framework-specific resolver — returns a friendly no-results message."
                    )
                    plain(
                        "Lists the async messaging endpoints — Kafka topics, RabbitMQ queues, JMS destinations — " +
                            "the same way `list` shows HTTP routes. Shows whether each destination is a producer or consumer, " +
                            "and which handler method processes the messages. Only works when the spring-messaging provider is registered."
                    )
                }
                whenLLMUses(
                    "When the user asks 'what Kafka topics does this service consume', 'what RabbitMQ queues exist', " +
                        "'show me all @KafkaListener handlers', or auditing the async message flow in an event-driven service."
                )
                params {
                    optional("filter", "string") {
                        llmSeesIt("Free-text filter on URL, HTTP method, or handler class — for list, export_http_scratch")
                        humanReadable("Case-insensitive substring filter applied to destination name, MQ type, or handler class name.")
                        whenPresent("Only async endpoints whose destination, MQ type, or handler class contains this substring are returned.")
                        whenAbsent("All discovered async messaging endpoints are returned.")
                        example("orders")
                        example("Kafka")
                        example("inventory")
                    }
                }
                rejectsParam("framework", "list_async is scoped to the MQResolverManager SPI — framework filtering is not supported")
                rejectsParam("endpoint_type", "list_async always returns async messaging endpoints — endpoint_type filtering is not applicable")
                precondition("spring-messaging provider (or equivalent) must be registered in the Microservices framework.")
                precondition("Project must use @KafkaListener, @RabbitListener, or @JmsListener annotations visible to the IDE index.")
                onSuccess("Returns async endpoints sorted by MQ type then destination name: 'Kafka  consumer  orders-topic', 'handler: OrderConsumer.handleOrder (OrderConsumer.kt:42)'.")
                onFailure("No async messaging endpoints found", "MQResolverManager has no resolver for this project's messaging framework, or the annotations are not indexed. Fall back to search_code('@KafkaListener').")
                example("list all Kafka consumers") {
                    param("action", "list_async")
                    param("filter", "Kafka")
                    outcome("Returns all Kafka @KafkaListener destinations with handler class, method, and source location.")
                }
                example("show all async endpoints") {
                    param("action", "list_async")
                    outcome("Returns Kafka, RabbitMQ, and JMS destinations in one view — sorted by MQ type then destination name.")
                }
                verdict {
                    keep(
                        "The only agent tool that surfaces async messaging topology. @KafkaListener / @RabbitListener scanning via search_code " +
                            "returns raw annotation text; list_async returns structured destination names, types, and handler wiring.",
                        VerdictSeverity.NORMAL
                    )
                }
            }
            action("service_graph") {
                description {
                    technical(
                        "Calls EndpointsDiscoverer.discover(project) and groups discovered endpoints by IntelliJ module " +
                            "using ModuleManager to map file paths to module directories. Emits a Mermaid 'graph LR' diagram " +
                            "with one node per identified service (module or package prefix). Edge extraction from handler bodies " +
                            "is a planned follow-up — current output is a node-only skeleton. Mermaid is rendered natively by the chat UI."
                    )
                    plain(
                        "Generates a Mermaid diagram showing which services (IDE modules) expose HTTP endpoints — " +
                            "a quick 'what services exist in this repo' map. Currently shows nodes only (no arrows between services); " +
                            "the 'which service calls which' edges are planned but not yet implemented."
                    )
                }
                whenLLMUses(
                    "When the user asks 'what services exist in this monorepo', 'draw me a service map', " +
                        "'which modules expose HTTP endpoints', or needs a visual overview before diving into a specific service."
                )
                rejectsParam("filter", "service_graph takes no params — the graph covers all discovered endpoints")
                rejectsParam("framework", "service_graph takes no params — groups by module across all frameworks")
                rejectsParam("endpoint_type", "service_graph takes no params — uses all endpoint types for node identification")
                precondition("At least one endpoint must be discoverable; empty discovery returns an error.")
                onSuccess("Returns a Mermaid 'graph LR' code block with service nodes. Summary states node count and '0 edge(s) [node-only skeleton]'. Chat UI renders it as a diagram.")
                onFailure("No endpoints discovered", "Microservices framework found no endpoints — cannot render service graph. Ensure at least one framework provider is registered.")
                example("draw a service map") {
                    param("action", "service_graph")
                    outcome("Returns a Mermaid diagram with nodes for each service module (e.g. order-service, inventory-service) detected from endpoint file paths. No edges in the current implementation.")
                    notes("Edge extraction (RestTemplate/WebClient/FeignClient call parsing) is a planned follow-up — result explicitly says '0 edge(s) [node-only skeleton]'.")
                }
                verdict {
                    keep(
                        "Zero-param quick overview — useful for orientation in a large multi-module monorepo before deeper analysis.",
                        VerdictSeverity.NORMAL
                    )
                    drop(
                        "Node-only skeleton with no edges limits usefulness for dependency tracing. If edge extraction is not implemented soon, this action's value is marginal vs just calling list + reading module names.",
                        VerdictSeverity.WEAK
                    )
                }
            }
        }
    }
}

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
import com.workflow.orchestrator.agent.tools.framework.fastapi.executeBackgroundTasks
import com.workflow.orchestrator.agent.tools.framework.fastapi.executeConfig
import com.workflow.orchestrator.agent.tools.framework.fastapi.executeDatabase
import com.workflow.orchestrator.agent.tools.framework.fastapi.executeDependencies
import com.workflow.orchestrator.agent.tools.framework.fastapi.executeEvents
import com.workflow.orchestrator.agent.tools.framework.fastapi.executeMiddleware
import com.workflow.orchestrator.agent.tools.framework.fastapi.executeModels
import com.workflow.orchestrator.agent.tools.framework.fastapi.executeRoutes
import com.workflow.orchestrator.agent.tools.framework.fastapi.executeSecurity
import com.workflow.orchestrator.agent.tools.framework.fastapi.executeVersionInfo
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.coroutineContext

/**
 * Consolidated FastAPI meta-tool replacing individual FastAPI analysis tools.
 *
 * Saves token budget per API call by collapsing all FastAPI-related operations into
 * a single tool definition with an `action` discriminator parameter.
 *
 * Actions: routes, dependencies, models, middleware, security, background_tasks,
 *          events, config, database, version_info
 *
 * Each action is implemented in its own file under
 * `com.workflow.orchestrator.agent.tools.framework.fastapi`. This class is a thin
 * dispatcher that exposes the tool surface (name, description, parameter schema)
 * and routes the `action` parameter to the corresponding handler.
 *
 * Only registered when FastAPI is detected in the project (fastapi dependency).
 */
class FastApiTool : AgentTool {

    override val name = "fastapi"

    override val description = """
FastAPI framework intelligence — routes, dependencies, Pydantic models, middleware, security.

Actions and their parameters:
- routes(path?) -> API route decorators (@app.get, @router.post, etc.)
- dependencies(class_name?) -> Depends() chains in function signatures
- models(model?) -> Pydantic BaseModel subclasses with fields
- middleware() -> app.add_middleware() registrations
- security() -> OAuth2, API key, HTTP bearer security schemes
- background_tasks() -> Functions using BackgroundTasks parameter
- events() -> Startup/shutdown event handlers and lifespan
- config(class_name?) -> BaseSettings configuration classes
- database(model?) -> SQLAlchemy/Tortoise ORM model classes
- version_info() -> FastAPI, uvicorn, pydantic versions from deps
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf(
                    "routes", "dependencies", "models", "middleware", "security",
                    "background_tasks", "events", "config", "database", "version_info"
                )
            ),
            "path" to ParameterProperty(
                type = "string",
                description = "Filter routes by URL path pattern"
            ),
            "model" to ParameterProperty(
                type = "string",
                description = "Filter by Pydantic model name or database model name"
            ),
            "class_name" to ParameterProperty(
                type = "string",
                description = "Filter by dependency or config class name"
            )
        ),
        required = listOf("action")
    )

    override val allowedWorkers = setOf(
        WorkerType.TOOLER, WorkerType.ANALYZER, WorkerType.REVIEWER,
        WorkerType.ORCHESTRATOR, WorkerType.CODER
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
            "routes" -> executeRoutes(params, project)
            "dependencies" -> executeDependencies(params, project)
            "models" -> executeModels(params, project)
            "middleware" -> executeMiddleware(params, project)
            "security" -> executeSecurity(params, project)
            "background_tasks" -> executeBackgroundTasks(params, project)
            "events" -> executeEvents(params, project)
            "config" -> executeConfig(params, project)
            "database" -> executeDatabase(params, project)
            "version_info" -> executeVersionInfo(params, project)
            else -> ToolResult(
                content = "Unknown action '$action'. See tool description for valid actions.",
                summary = "Unknown action '$action'",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    override fun documentation(): ToolDocumentation = toolDoc("fastapi") {
        summary {
            technical(
                "Single-tool dispatcher for 10 FastAPI framework introspection operations via file-scan (PythonFileScanner). " +
                    "Covers route enumeration with full prefix composition (APIRouter(prefix=...) + app.include_router(..., prefix=...)), " +
                    "Depends() dependency-injection chain tracing, Pydantic BaseModel field listing, app.add_middleware() registrations, " +
                    "OAuth2/APIKey/HTTPBearer security scheme detection, BackgroundTasks handler discovery, " +
                    "startup/shutdown/lifespan event handlers, BaseSettings config class inspection (with sensitive-value redaction), " +
                    "SQLAlchemy/Tortoise ORM model enumeration, and dependency-file version reporting. " +
                    "Zero compile-time Python plugin dependency — pure regex over .py files. " +
                    "Conditionally registered and promoted from deferred to core when fastapi is detected in project deps."
            )
            plain(
                "The agent's FastAPI remote control — like having a code-aware grep that understands FastAPI's patterns. " +
                    "Covers everything from 'what routes exist at what full URL paths' to 'what security scheme guards these endpoints' " +
                    "to 'what version of FastAPI is installed'. No Python interpreter or plugin required — reads source files directly. " +
                    "Only shows up when the project has fastapi in its dependencies."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.READ_ONLY)
        counterfactual(
            "Without `fastapi`, the LLM falls back to `search_code` scanning for `@app.get`, `@router.post`, `Depends(`, `BaseModel`, etc. " +
                "That approach cannot compose router prefixes — a route defined as `@router.get('/items')` on a router with " +
                "`prefix='/api/v1'` included at `app.include_router(router, prefix='/v2')` requires parsing three separate " +
                "files and resolving variable names across them. `search_code` returns the bare decorator path '/items'; " +
                "the full URL '/v2/api/v1/items' is invisible. Similarly, `search_code` cannot extract structured field " +
                "lists from Pydantic models or produce a consolidated security scheme inventory."
        )
        llmMistake(
            "Expects route results to show the bare decorator path (e.g. '/items') without the router prefix. " +
                "The `routes` action composes APIRouter(prefix=...) + app.include_router(..., prefix=...) into the full URL path. " +
                "When the result shows '/api/v1/items', that is the correct full path — not a bug."
        )
        llmMistake(
            "Calls `routes` with `model` or `class_name` params — those are silently ignored. " +
                "Only `path` is accepted by `routes`; use `model` for `models`/`database` and `class_name` for `dependencies`/`config`."
        )
        llmMistake(
            "Calls `security` expecting it to show which routes are protected. " +
                "The `security` action lists security scheme *declarations* (e.g. `oauth2_scheme = OAuth2PasswordBearer(tokenUrl=...)`), " +
                "not which routes depend on them. To trace which routes use a scheme, follow up with `dependencies(class_name=<scheme_var>)`."
        )
        llmMistake(
            "Calls `middleware` or `background_tasks` or `events` with filter params — none of these actions accept filter parameters. " +
                "They return all matches in the project; filter client-side or use `search_code` for finer-grained scoping."
        )
        llmMistake(
            "Calls `database` expecting SQLAlchemy models defined as dataclasses or mapped with `registry.mapped`. " +
                "The scanner detects `class Foo(Base):` and `class Foo(Model):` patterns only — mapped dataclasses and " +
                "SQLAlchemy 2.0 `DeclarativeBase` subclasses named something other than `Base` will be missed."
        )
        llmMistake(
            "Calls `config` without a `class_name` filter on a large project and tries to use the dump to resolve " +
                "a specific env-var value. BaseSettings fields show the *type* and *default*, not the runtime value — " +
                "actual values come from environment variables or `.env` files not visible to file-scan."
        )
        downside(
            "File-scan primary — misses routes, models, and dependencies registered dynamically at runtime " +
                "(e.g. routes added via `app.add_api_route()` in a loop, models built with `create_model()`, " +
                "or routers assembled from factory functions). No PSI or AST — pure regex on .py text."
        )
        downside(
            "Router prefix composition resolves only one level of nesting per file. " +
                "Cross-file prefix chains (router A imported into router B, B imported into app) are not fully resolved — " +
                "only the innermost prefix visible in the file where the decorator appears is composed."
        )
        downside(
            "The `database` action heuristically detects SQLAlchemy declarative models via `class Foo(Base):` and " +
                "Tortoise models via `class Foo(Model):`. Projects that rename the base class (e.g. `class Foo(MyBase):`) " +
                "will produce zero results without any error signal."
        )
        downside(
            "The `config` action redacts sensitive field values (SECRET_KEY, PASSWORD, API_KEY, TOKEN, DATABASE_URL, etc.) " +
                "via PythonFileScanner.redactIfSensitive — intentional but may surprise the LLM when a field appears as '[REDACTED]'."
        )
        downside(
            "The `events` action detects both `@app.on_event('startup')` (deprecated in FastAPI ≥0.93) and the newer " +
                "lifespan context manager pattern. Projects that use only the lifespan pattern with a differently-named " +
                "context manager (not discovered via `@asynccontextmanager`) may be missed."
        )
        related("django", Relationship.SEE_ALSO, "Same PythonFileScanner foundation; use `django` for Django projects. Separate tool because framework conventions diverge significantly.")
        related("flask", Relationship.SEE_ALSO, "Same PythonFileScanner foundation; use `flask` for Flask/Blueprint projects. Blueprint prefix resolution is analogous to FastAPI router prefix composition.")
        related("search_code", Relationship.FALLBACK, "Use `search_code` when dynamic registration, cross-file router chains, or unconventional base-class names defeat the file-scan patterns.")
        related("python_runtime_exec", Relationship.COMPLEMENT, "Use `python_runtime_exec` to actually run FastAPI tests or validate that the routes work; `fastapi` only reads the source.")
        mergeOpportunity(
            "`django`, `fastapi`, and `flask` all share PythonFileScanner as their file-walk foundation and follow identical " +
                "dispatcher structure. A shared `python_framework` meta-tool with a `framework` param could unify them. " +
                "Kept separate because per-framework action sets differ substantially (Django has manage.py/migrations/admin/signals; " +
                "FastAPI has Depends/BaseSettings/lifespan; Flask has Blueprints/CLI commands) — merging would bloat the schema " +
                "with many framework-conditional params."
        )
        observation(
            "The `security` action detects 10 FastAPI security scheme classes but cannot show which routes are actually protected. " +
                "A hypothetical `security_routes` action that cross-references Depends() chains with route decorators would be more useful — " +
                "but requires multi-file analysis beyond the current per-file scan."
        )
        actions {
            action("routes") {
                description {
                    technical(
                        "Scans all .py files for FastAPI route decorators (@app.get, @router.post, etc.) and composes full URL paths " +
                            "by resolving APIRouter(prefix=...) declarations and app.include_router(..., prefix=...) overrides within the same file. " +
                            "Returns HTTP method, full composed path, and handler function name, grouped by source file."
                    )
                    plain(
                        "Lists all the HTTP routes your FastAPI app exposes — like running 'app.openapi()' offline. " +
                            "GET /api/v1/users → get_users(), POST /api/v1/users → create_user(), etc. " +
                            "Prefix composition means you see the real URL, not just the bare decorator string."
                    )
                }
                whenLLMUses("When the user asks 'what routes exist', 'what handles GET /api/users', 'show me all POST endpoints', or needs a route inventory before adding a new endpoint. Primary starting point for any FastAPI navigation task.")
                params {
                    optional("path", "string") {
                        llmSeesIt("Filter routes by URL path pattern")
                        humanReadable("Case-insensitive substring filter on the composed full URL path.")
                        whenPresent("Only routes whose composed path contains this substring are returned.")
                        whenAbsent("All routes in the project are returned.")
                        example("/api/v1")
                        example("/users")
                        example("orders")
                    }
                }
                onSuccess("Returns routes grouped by file: HTTP method, full composed URL path, and handler function name. Count summary in header.")
                onFailure("no Python files found", "Project base path is unavailable or contains no .py files. Check that the project is opened correctly.")
                onFailure("no routes found matching filter", "Empty result — filter may be too narrow or routes use a dynamic registration pattern not visible to the scanner.")
                example("list all routes") {
                    param("action", "routes")
                    outcome("Returns all route decorators found across all .py files, with composed prefixes: e.g. GET /api/v1/users, POST /api/v1/users/{user_id}.")
                }
                example("filter to order-related routes") {
                    param("action", "routes")
                    param("path", "/orders")
                    outcome("Returns only routes whose composed path contains '/orders'.")
                }
                verdict {
                    keep("The primary route discovery tool for FastAPI. Router prefix composition is the key differentiator over raw search_code — LLM gets real URLs, not bare decorator strings.", VerdictSeverity.STRONG)
                }
            }
            action("dependencies") {
                description {
                    technical(
                        "Scans all .py files for Depends() usages in function signatures, tracking the enclosing function name and the " +
                            "dependency class/callable being injected. Optionally filtered by dependency name or function name via class_name param. " +
                            "Returns (function, Depends(target)) pairs grouped by file."
                    )
                    plain(
                        "Shows FastAPI's dependency injection wiring — which handler functions use Depends() and what they depend on. " +
                            "Like tracing the constructor injections in a DI container, but for FastAPI's parameter-level injection."
                    )
                }
                whenLLMUses("When the user asks 'which endpoints require authentication', 'what does the get_current_user dependency do', 'where is DatabaseSession injected', or auditing the DI chain.")
                params {
                    optional("class_name", "string") {
                        llmSeesIt("Filter by dependency or config class name")
                        humanReadable("Substring filter applied to both the dependency callable name and the declaring function name.")
                        whenPresent("Only entries where the dependency name or the enclosing function name contains this substring are returned.")
                        whenAbsent("All Depends() usages across all .py files are returned.")
                        example("get_current_user")
                        example("DatabaseSession")
                        example("auth")
                    }
                }
                onSuccess("Returns Depends() usages as 'function() -> Depends(target)' lines grouped by file.")
                onFailure("no Depends() usages found", "Either the project uses no FastAPI dependency injection or the filter is too narrow.")
                example("find all auth dependencies") {
                    param("action", "dependencies")
                    param("class_name", "auth")
                    outcome("Returns all handler functions that Depend on auth-related callables (get_current_user, oauth2_scheme, etc.).")
                }
                example("full dependency map") {
                    param("action", "dependencies")
                    outcome("Returns every Depends() usage in the project — useful for auditing DI coverage.")
                }
                verdict {
                    keep("Tracing FastAPI's Depends() chains across files is tedious with search_code — this consolidates it into one structured view.", VerdictSeverity.NORMAL)
                }
            }
            action("models") {
                description {
                    technical(
                        "Scans all .py files for Pydantic BaseModel subclasses and extracts their field definitions (name: type). " +
                            "Skips inner Config classes and private/dunder fields. Truncates at 10 fields per model with a remainder count. " +
                            "Optionally filtered by model name via the model param."
                    )
                    plain(
                        "Lists all the Pydantic data models — your request/response schemas. " +
                            "Shows each class with its fields and types, like reading the model file but across the whole project at once."
                    )
                }
                whenLLMUses("When the user asks 'what does the UserResponse model look like', 'what fields does the CreateOrderRequest have', or 'list all Pydantic models'. Starting point for understanding API contract types.")
                params {
                    optional("model", "string") {
                        llmSeesIt("Filter by Pydantic model name or database model name")
                        humanReadable("Case-insensitive substring filter on model class name.")
                        whenPresent("Only BaseModel subclasses whose name contains this substring are returned.")
                        whenAbsent("All Pydantic models in the project are returned.")
                        example("UserResponse")
                        example("Order")
                        example("Request")
                    }
                }
                onSuccess("Returns Pydantic models grouped by file, each with field name and type annotations. Fields truncated at 10 with remainder count.")
                onFailure("no Pydantic models found matching filter", "Model may not extend BaseModel directly — generic classes, TypedDicts, or dataclasses are not detected.")
                example("get fields for UserResponse") {
                    param("action", "models")
                    param("model", "UserResponse")
                    outcome("Returns UserResponse with its fields: id: int, email: str, created_at: datetime, etc.")
                }
                example("list all models") {
                    param("action", "models")
                    outcome("Returns all Pydantic BaseModel subclasses across the project — useful for a full API schema overview.")
                }
                verdict {
                    keep("Structured field extraction from Pydantic models is more reliable than grepping for class body — handles multi-line fields and skips class metadata.", VerdictSeverity.NORMAL)
                }
            }
            action("middleware") {
                description {
                    technical(
                        "Scans all .py files for app.add_middleware() calls, extracting the middleware class name and keyword arguments. " +
                            "Returns all middleware registrations in registration order (by line number), grouped by source file."
                    )
                    plain(
                        "Shows which middleware layers wrap your FastAPI app — CORS, GZip, session, custom auth middleware, etc. " +
                            "Like reading the list of app.add_middleware() calls but consolidated across all files."
                    )
                }
                whenLLMUses("When the user asks 'is CORS configured', 'what middleware is registered', 'why are all responses getting a header added', or auditing request/response pipeline layers.")
                rejectsParam("path", "middleware takes no filter params — returns all add_middleware() calls in the project")
                rejectsParam("model", "middleware takes no filter params — returns all add_middleware() calls in the project")
                rejectsParam("class_name", "middleware takes no filter params — returns all add_middleware() calls in the project")
                onSuccess("Returns middleware registrations as 'MiddlewareClass (kwargs)' lines with file and line order.")
                onFailure("no add_middleware() calls found", "Project may use @app.middleware decorator pattern instead — not detected by this action. Fall back to search_code('@app.middleware').")
                example("check CORS middleware") {
                    param("action", "middleware")
                    outcome("Returns e.g. 'CORSMiddleware (allow_origins=[\"*\"], allow_methods=[\"*\"])' — confirms CORS is configured and shows its parameters.")
                }
                verdict {
                    keep("Zero-param, fast, gives a clear middleware stack inventory that's otherwise scattered across multiple files.", VerdictSeverity.NORMAL)
                }
            }
            action("security") {
                description {
                    technical(
                        "Scans all .py files for instantiations of 10 FastAPI security scheme classes: " +
                            "OAuth2PasswordBearer, OAuth2PasswordRequestForm, OAuth2AuthorizationCodeBearer, " +
                            "APIKeyHeader, APIKeyQuery, APIKeyCookie, HTTPBearer, HTTPBasic, HTTPBasicCredentials, " +
                            "HTTPAuthorizationCredentials, SecurityScopes. Returns variable_name = SchemeClass(args) per match."
                    )
                    plain(
                        "Shows how the app authenticates requests — which security schemes are declared and with what settings. " +
                            "Like a security audit checklist: 'OAuth2PasswordBearer(tokenUrl=/token)', 'APIKeyHeader(name=X-API-Key)', etc."
                    )
                }
                whenLLMUses("When the user asks 'how does this API authenticate', 'is OAuth2 configured', 'what security schemes are used', or auditing authentication before adding a new protected endpoint.")
                rejectsParam("path", "security takes no filter params — returns all security scheme declarations")
                rejectsParam("model", "security takes no filter params — returns all security scheme declarations")
                rejectsParam("class_name", "security takes no filter params — returns all security scheme declarations")
                onSuccess("Returns security scheme declarations as 'variable = SchemeClass(args)' lines grouped by file.")
                onFailure("no FastAPI security schemes found", "Project may use custom auth middleware or a third-party auth library not matching the 10 known FastAPI scheme class names.")
                example("audit security schemes") {
                    param("action", "security")
                    outcome("Returns e.g. 'oauth2_scheme = OAuth2PasswordBearer(tokenUrl=\"/auth/token\")' and 'api_key = APIKeyHeader(name=\"X-API-Key\")'.")
                    notes("To find which routes use these schemes, follow up with dependencies(class_name=<variable_name>).")
                }
                verdict {
                    keep("Security scheme inventory is hard to reconstruct from search_code alone — this consolidates 10 pattern variants into a single structured call.", VerdictSeverity.NORMAL)
                }
            }
            action("background_tasks") {
                description {
                    technical(
                        "Scans all .py files for function definitions that declare a BackgroundTasks parameter in their signature. " +
                            "Detects both sync and async functions. Returns function name and source file."
                    )
                    plain(
                        "Shows which endpoint handlers use FastAPI's BackgroundTasks for deferred work — " +
                            "like sending emails after a response, processing files in the background, etc."
                    )
                }
                whenLLMUses("When the user asks 'which endpoints kick off background jobs', 'where does the email-sending background task get registered', or auditing async side effects of HTTP handlers.")
                rejectsParam("path", "background_tasks takes no filter params — returns all BackgroundTasks-using functions")
                rejectsParam("model", "background_tasks takes no filter params — returns all BackgroundTasks-using functions")
                rejectsParam("class_name", "background_tasks takes no filter params — returns all BackgroundTasks-using functions")
                onSuccess("Returns function names that declare BackgroundTasks parameter, grouped by source file.")
                onFailure("no functions using BackgroundTasks found", "Project may use Celery, ARQ, or another task queue instead of FastAPI's built-in BackgroundTasks.")
                example("find background task handlers") {
                    param("action", "background_tasks")
                    outcome("Returns e.g. 'create_order()' and 'upload_file()' — the handlers that queue deferred work.")
                }
                verdict {
                    keep("Niche but precise — identifies the exact functions that defer work, which is invisible from route listings alone.", VerdictSeverity.NORMAL)
                }
            }
            action("events") {
                description {
                    technical(
                        "Scans all .py files for FastAPI lifecycle event handlers using three patterns: " +
                            "(1) @app.on_event('startup'/'shutdown') decorators (deprecated but common in older codebases), " +
                            "(2) @asynccontextmanager-decorated lifespan functions, " +
                            "(3) FastAPI(lifespan=<func>) constructor references. " +
                            "Returns event type (startup/shutdown/lifespan) and handler function name."
                    )
                    plain(
                        "Shows what happens when the app starts up and shuts down — database connection setup, connection pool teardown, " +
                            "cache warming, etc. Detects both the old @on_event decorator pattern and the newer lifespan context manager."
                    )
                }
                whenLLMUses("When the user asks 'what initialization runs at startup', 'where is the database connection opened', 'does the app have a shutdown hook', or tracing resource lifecycle management.")
                rejectsParam("path", "events takes no filter params — returns all lifecycle event handlers")
                rejectsParam("model", "events takes no filter params — returns all lifecycle event handlers")
                rejectsParam("class_name", "events takes no filter params — returns all lifecycle event handlers")
                onSuccess("Returns event entries as '[eventType] handler()' lines grouped by file. eventType is startup, shutdown, or lifespan.")
                onFailure("no FastAPI event handlers found", "App may have no explicit startup/shutdown logic, or may use a different lifecycle mechanism (ASGI middleware, APM instrumentation).")
                example("find startup handlers") {
                    param("action", "events")
                    outcome("Returns e.g. '[startup] connect_db()' and '[lifespan] app_lifespan()' — all startup/shutdown logic in one view.")
                    notes("Both the deprecated @on_event pattern and the modern lifespan pattern are detected.")
                }
                verdict {
                    keep("Lifecycle event detection covers three distinct syntactic patterns — search_code would require three separate queries to get the same coverage.", VerdictSeverity.NORMAL)
                }
            }
            action("config") {
                description {
                    technical(
                        "Scans all .py files for pydantic-settings BaseSettings subclasses and extracts their field definitions " +
                            "(name: type). Applies PythonFileScanner.redactIfSensitive() to field values containing credentials " +
                            "(SECRET_KEY, PASSWORD, API_KEY, TOKEN, DATABASE_URL, PRIVATE_KEY, AWS_SECRET). " +
                            "Optionally filtered by class name. Truncates at 10 fields per class."
                    )
                    plain(
                        "Shows the typed configuration classes — every setting the app reads from environment variables. " +
                            "Like reading the Settings class but consolidated across all files and with sensitive defaults redacted."
                    )
                }
                whenLLMUses("When the user asks 'what environment variables does this app use', 'what settings does the DatabaseConfig class expose', 'what's the config structure', or before writing a .env file.")
                params {
                    optional("class_name", "string") {
                        llmSeesIt("Filter by dependency or config class name")
                        humanReadable("Substring filter on the BaseSettings subclass name.")
                        whenPresent("Only config classes whose name contains this substring are returned.")
                        whenAbsent("All BaseSettings subclasses in the project are returned.")
                        example("Settings")
                        example("DatabaseConfig")
                        example("AuthSettings")
                    }
                }
                onSuccess("Returns BaseSettings subclasses with their field names and types. Sensitive field values shown as '[REDACTED]'. Truncated at 10 fields with remainder count.")
                onFailure("no BaseSettings classes found", "Project may use plain os.environ, python-dotenv without pydantic-settings, or Dynaconf — none of these are detected.")
                example("show all config classes") {
                    param("action", "config")
                    outcome("Returns all BaseSettings subclasses — e.g. 'Settings: database_url: str, secret_key: [REDACTED], debug: bool = False'.")
                }
                example("filter to database config") {
                    param("action", "config")
                    param("class_name", "Database")
                    outcome("Returns only config classes whose name contains 'Database' — e.g. DatabaseSettings with host/port/name/user fields.")
                }
                verdict {
                    keep("Structured extraction of typed settings with automatic sensitive-value redaction — more useful than grepping for class definitions.", VerdictSeverity.NORMAL)
                }
            }
            action("database") {
                description {
                    technical(
                        "Scans all .py files for ORM model classes using two patterns: " +
                            "(1) SQLAlchemy declarative: `class Foo(Base):` with Column/mapped_column/relationship field detection and __tablename__ parsing; " +
                            "(2) Tortoise ORM: `class Foo(Model):` with `fields.FieldType()` field detection. " +
                            "Skips Meta and Config inner classes. Optionally filtered by model name. Truncates at 10 fields."
                    )
                    plain(
                        "Shows the database schema as defined in Python ORM models — table names, columns, relationships. " +
                            "Like reading SQLAlchemy models but consolidated across all files into a single structured view."
                    )
                }
                whenLLMUses("When the user asks 'what database tables exist', 'what columns does the users table have', 'what ORM models are defined', or before writing a migration or query.")
                params {
                    optional("model", "string") {
                        llmSeesIt("Filter by Pydantic model name or database model name")
                        humanReadable("Substring filter on the ORM model class name.")
                        whenPresent("Only ORM model classes whose name contains this substring are returned.")
                        whenAbsent("All detected SQLAlchemy and Tortoise models are returned.")
                        example("User")
                        example("Order")
                        example("Product")
                    }
                }
                onSuccess("Returns ORM models grouped by file: class name, ORM type (SQLAlchemy/Tortoise), and field definitions. Truncated at 10 fields with remainder count.")
                onFailure("no database models found", "Base class may be named something other than 'Base' or 'Model' (e.g. 'MyBase', 'AbstractModel') — the scanner detects only the literal names 'Base' and 'Model'.")
                example("show all SQLAlchemy models") {
                    param("action", "database")
                    outcome("Returns all classes extending Base or Model with their Column/field definitions — e.g. 'User [SQLAlchemy]: id: Integer, email: String, created_at: DateTime'.")
                }
                example("get User model structure") {
                    param("action", "database")
                    param("model", "User")
                    outcome("Returns User model with its table name and all column/field definitions.")
                }
                verdict {
                    keep("Covers both SQLAlchemy and Tortoise in one call. The heuristic base-class detection (Base/Model) covers the vast majority of real projects.", VerdictSeverity.NORMAL)
                    drop("Fragile to non-standard base class names — projects that rename the declarative base produce silent empty results. Should document this more prominently.", VerdictSeverity.WEAK)
                }
            }
            action("version_info") {
                description {
                    technical(
                        "Reads dependency version constraints for 12 FastAPI-ecosystem packages (fastapi, uvicorn, pydantic, sqlalchemy, " +
                            "tortoise-orm, alembic, httpx, starlette, python-multipart, python-jose, passlib, databases) from " +
                            "requirements*.txt files, pyproject.toml, and setup.cfg. First-match wins across sources. " +
                            "Returns a sorted list with fastapi first."
                    )
                    plain(
                        "Tells you what version of FastAPI (and its ecosystem) the project uses — like reading requirements.txt " +
                            "but with the framework packages highlighted and parsed from multiple dependency file formats."
                    )
                }
                whenLLMUses("When the user asks 'what FastAPI version is installed', 'is pydantic v1 or v2 in use', 'what SQLAlchemy version does this project use', or before recommending a version-gated API.")
                rejectsParam("path", "version_info reads dependency files, not .py files — path filter has no effect")
                rejectsParam("model", "version_info reads dependency files, not .py files — model filter has no effect")
                rejectsParam("class_name", "version_info reads dependency files, not .py files — class_name filter has no effect")
                onSuccess("Returns a version list with fastapi first, then alphabetical. Summary includes FastAPI version string and total package count.")
                onFailure("no FastAPI-related package versions found", "Dependency files may use a non-standard format, or the project uses uv lock files or conda — only requirements*.txt, pyproject.toml, and setup.cfg are scanned.")
                example("check FastAPI and pydantic versions") {
                    param("action", "version_info")
                    outcome("Returns e.g. 'fastapi==0.110.0, pydantic==2.6.4, uvicorn==0.29.0' — enough to determine pydantic v1 vs v2 compatibility.")
                    notes("Pydantic v1 vs v2 is a critical compatibility break — version_info is the fastest way to determine which is in use before recommending model validators or Field() arguments.")
                }
                verdict {
                    keep("Low token cost, high utility for version-gated recommendations (pydantic v1 vs v2 is a critical break). Avoids parsing dependency files manually.", VerdictSeverity.NORMAL)
                }
            }
        }
    }
}

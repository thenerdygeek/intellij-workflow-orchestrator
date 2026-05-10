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
import com.workflow.orchestrator.agent.tools.framework.flask.executeBlueprints
import com.workflow.orchestrator.agent.tools.framework.flask.executeCliCommands
import com.workflow.orchestrator.agent.tools.framework.flask.executeConfig
import com.workflow.orchestrator.agent.tools.framework.flask.executeExtensions
import com.workflow.orchestrator.agent.tools.framework.flask.executeForms
import com.workflow.orchestrator.agent.tools.framework.flask.executeMiddleware
import com.workflow.orchestrator.agent.tools.framework.flask.executeModels
import com.workflow.orchestrator.agent.tools.framework.flask.executeRoutes
import com.workflow.orchestrator.agent.tools.framework.flask.executeTemplates
import com.workflow.orchestrator.agent.tools.framework.flask.executeVersionInfo
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.coroutineContext

/**
 * Consolidated Flask meta-tool replacing individual Flask analysis tools.
 *
 * Saves token budget per API call by collapsing all Flask-related operations into
 * a single tool definition with an `action` discriminator parameter.
 *
 * Actions: routes, blueprints, config, extensions, models, templates, middleware,
 *          cli_commands, forms, version_info
 *
 * Each action is implemented in its own file under
 * `com.workflow.orchestrator.agent.tools.framework.flask`. This class is a thin
 * dispatcher that exposes the tool surface (name, description, parameter schema)
 * and routes the `action` parameter to the corresponding handler.
 *
 * Only registered when Flask is detected in the project (flask dependency in requirements).
 */
class FlaskTool : AgentTool {

    override val name = "flask"

    override val description = """
Flask framework intelligence — routes, blueprints, config, extensions, SQLAlchemy models, templates.

Actions and their parameters:
- routes(blueprint?) → @app.route / @blueprint.route decorators with methods and handlers
- blueprints(blueprint?) → Blueprint() definitions and register_blueprint() calls
- config() → Class-based and key-value config from config.py / settings.py
- extensions(extension?) → Flask extensions (SQLAlchemy, Migrate, etc.) and init_app() calls
- models(model?) → Flask-SQLAlchemy db.Model classes with Column definitions
- templates() → Jinja2 template files in templates/ directories
- middleware() → @app.before_request, @app.after_request, @app.errorhandler hooks
- cli_commands() → @app.cli.command() and @click.command() CLI commands
- forms() → Flask-WTF FlaskForm/Form classes with field definitions
- version_info() → Flask, Werkzeug, Jinja2, SQLAlchemy versions from deps
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf(
                    "routes", "blueprints", "config", "extensions", "models",
                    "templates", "middleware", "cli_commands", "forms", "version_info"
                )
            ),
            "blueprint" to ParameterProperty(
                type = "string",
                description = "Filter by blueprint name — for routes, blueprints"
            ),
            "extension" to ParameterProperty(
                type = "string",
                description = "Filter by extension name — for extensions"
            ),
            "model" to ParameterProperty(
                type = "string",
                description = "Filter by SQLAlchemy model name — for models"
            ),
            "filter" to ParameterProperty(
                type = "string",
                description = "General filter by name/pattern — for any action"
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
            "blueprints" -> executeBlueprints(params, project)
            "config" -> executeConfig(params, project)
            "extensions" -> executeExtensions(params, project)
            "models" -> executeModels(params, project)
            "templates" -> executeTemplates(params, project)
            "middleware" -> executeMiddleware(params, project)
            "cli_commands" -> executeCliCommands(params, project)
            "forms" -> executeForms(params, project)
            "version_info" -> executeVersionInfo(params, project)
            else -> ToolResult(
                content = "Unknown action '$action'. See tool description for valid actions.",
                summary = "Unknown action '$action'",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    override fun documentation(): ToolDocumentation = toolDoc("flask") {
        summary {
            technical(
                "Single-tool dispatcher for 10 Flask framework introspection operations via regex-based " +
                    "PythonFileScanner. Covers route mapping (with blueprint prefix composition), blueprint " +
                    "definitions and registrations, class-based and key-value config, extension detection " +
                    "(init_app pattern), Flask-SQLAlchemy db.Model classes, Jinja2 template inventory, " +
                    "before/after_request and errorhandler hooks, CLI commands (app.cli.command + click), " +
                    "Flask-WTF form classes, and ecosystem version resolution from requirements/pyproject/Pipfile. " +
                    "Conditionally registered: requires flask in project dependencies. Promoted from deferred to " +
                    "core when Flask is detected."
            )
            plain(
                "The agent's Flask remote control — like having a map of every URL, form, model, and config " +
                    "in a Flask app without having to grep through dozens of files. Only shows up when the " +
                    "project has Flask on its dependency list."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.READ_ONLY)
        counterfactual(
            "Without `flask`, the LLM falls back to `search_code` scanning for `@app.route`, `Blueprint`, " +
                "`db.Model`, etc. That approach cannot compose blueprint url_prefix values from Blueprint() " +
                "constructors with register_blueprint() override prefixes into full URL paths — it sees only " +
                "the raw route fragment. It also misses config values hidden in class bodies, cannot distinguish " +
                "constructor-style from init_app-style extension wiring, and must enumerate template directories " +
                "manually. For any question requiring the composed URL of a blueprinted route, `search_code` " +
                "produces wrong or incomplete answers; `flask(action=routes)` is the only path that resolves " +
                "blueprint prefix composition correctly."
        )
        llmMistake(
            "Passes `blueprint` param to `config`, `middleware`, `cli_commands`, `forms`, `templates`, or " +
                "`version_info`. Those actions do not consume `blueprint` — they only read `filter`. " +
                "The `blueprint` param is meaningful only for `routes` and `blueprints`."
        )
        llmMistake(
            "Calls `models` without first checking whether the project uses models.py files. The action " +
                "scans only files literally named `models.py`; models defined in other files (e.g. " +
                "`user_model.py`, `db/schema.py`) are silently omitted. When the scan returns empty, " +
                "follow up with `search_code` for `db.Model`."
        )
        llmMistake(
            "Calls `forms` expecting to find forms in any .py file. The action only scans `forms.py` and " +
                "`form.py` — forms defined elsewhere are invisible. If the scan returns empty on a project " +
                "with Flask-WTF, check where forms are actually defined with `search_code` for `FlaskForm`."
        )
        llmMistake(
            "Calls `extensions` and expects it to find all Flask extensions. Detection is limited to a " +
                "hard-coded allow-list of ~25 known extension class names. Custom extensions or less-common " +
                "packages not in the list are invisible."
        )
        llmMistake(
            "Treats the `blueprint` filter on `routes` as an exact match. It is a case-insensitive " +
                "substring applied to the full route path, decorator object name, and handler function name — " +
                "not to the Blueprint's registered name. To find all routes under a specific blueprint, " +
                "use the blueprint's variable name as the filter value."
        )
        llmMistake(
            "Calls `version_info` expecting runtime-resolved versions. The action reads static dependency " +
                "files (requirements*.txt, pyproject.toml, Pipfile, setup.py) — version specifiers with " +
                "ranges (>=2.0) appear verbatim; the installed runtime version may differ."
        )
        downside(
            "All actions use regex-based file scanning, not Python AST parsing. Dynamically constructed " +
                "routes (e.g., routes registered via `add_url_rule` or in a loop) are invisible."
        )
        downside(
            "`models` and `forms` scope to filename conventions (`models.py`, `forms.py`). Projects that " +
                "use different file naming (e.g., per-module `user_models.py`) produce empty results even " +
                "when models or forms exist."
        )
        downside(
            "Blueprint prefix composition works only when both the `Blueprint()` constructor (with " +
                "`url_prefix`) and the `register_blueprint()` call are in the same file. Cross-file prefix " +
                "override resolution is not implemented — the override prefix in `register_blueprint` in a " +
                "different file than the `Blueprint()` definition is ignored."
        )
        downside(
            "`extensions` detection relies on a static allow-list of ~25 class names. Any extension whose " +
                "class name is not in the list is silently missed. Custom or less-common extensions require " +
                "`search_code` as a fallback."
        )
        downside(
            "Version resolution reads only pinned (`==`) version specifiers. Range specifiers (`>=`, `~=`) " +
                "produce no version in the result — the package appears to have no pinned version."
        )
        related("django", Relationship.SEE_ALSO, "Same structural pattern for Django projects — use instead when the project uses Django.")
        related("fastapi", Relationship.SEE_ALSO, "Same structural pattern for FastAPI projects — use instead when the project uses FastAPI.")
        related("search_code", Relationship.FALLBACK, "Use as fallback when flask returns empty (custom file naming, dynamic registration, or extensions not in the allow-list).")
        related("python_runtime_exec", Relationship.COMPLEMENT, "Use to run pytest or flask shell commands after introspecting the structure; flask tells you what exists, python_runtime_exec lets you exercise it.")
        observation(
            "The `models` and `forms` actions are significantly narrower than their equivalents in `django` " +
                "due to filename-only scoping. Consider broadening the scan to any .py file containing " +
                "`db.Model` or `FlaskForm` subclasses in a future revision."
        )
        observation(
            "Blueprint prefix composition is a unique capability not available via `search_code`. " +
                "This is the strongest keep-argument for the entire tool — the 9 other actions could " +
                "theoretically be replaced by `search_code`, but accurate composed URL resolution cannot."
        )
        actions {
            action("routes") {
                description {
                    technical(
                        "Scans all .py files for @app.route, @blueprint.route, and HTTP-method shorthand " +
                            "decorators (@bp.get, @bp.post, etc.). Composes full URL paths by combining " +
                            "Blueprint(url_prefix=...) constructor values with register_blueprint(url_prefix=...) " +
                            "overrides found in the same file. Groups results by source file."
                    )
                    plain(
                        "Lists every URL the Flask app serves, with HTTP methods and handler function names. " +
                            "Automatically stitches blueprint prefixes onto route fragments so you see `/api/users` " +
                            "rather than just `/users`."
                    )
                }
                whenLLMUses(
                    "When the user asks 'what endpoints does this Flask app expose', 'does /api/orders exist', " +
                        "'what methods does the orders blueprint serve', or 'show me all POST routes'."
                )
                params {
                    optional("blueprint", "string") {
                        llmSeesIt("Filter by blueprint name — for routes, blueprints")
                        humanReadable(
                            "Case-insensitive substring filter applied to the route path, decorator object " +
                                "name (e.g. `api_bp`), and handler function name. Not an exact blueprint name match."
                        )
                        whenPresent("Only routes whose path, decorator, or handler function match the substring are returned.")
                        whenAbsent("All routes across all files are returned.")
                        example("api_bp")
                        example("/users")
                        example("get_order")
                    }
                    optional("filter", "string") {
                        llmSeesIt("General filter by name/pattern — for any action")
                        humanReadable("Fallback filter — same behavior as `blueprint` for this action.")
                        whenPresent("Applied identically to `blueprint` when `blueprint` is absent.")
                        whenAbsent("No additional filtering.")
                    }
                }
                onSuccess(
                    "Returns a grouped listing by file: decorator expression with composed full path, HTTP methods, " +
                        "and handler function name. Summary line: '{N} routes across {M} file(s)'."
                )
                onFailure(
                    "No .py files in project",
                    "Returns 'No Python files found in project.' — project may have no Python sources."
                )
                onFailure(
                    "No routes match filter",
                    "Returns 'No routes found matching {filter}.' — widen the filter or call without filter."
                )
                example("List all routes") {
                    param("action", "routes")
                    outcome("Full route table across all .py files, with blueprint prefixes composed into full URLs.")
                }
                example("Routes in the auth blueprint") {
                    param("action", "routes")
                    param("blueprint", "auth")
                    outcome("Only routes whose decorator object, path, or handler function contains 'auth'.")
                }
                verdict {
                    keep(
                        "Blueprint prefix composition is the unique capability that justifies the entire tool. " +
                            "`search_code` cannot produce correct composed URLs from multi-file blueprint setups.",
                        VerdictSeverity.STRONG
                    )
                }
            }
            action("blueprints") {
                description {
                    technical(
                        "Scans all .py files for Blueprint() constructor calls and register_blueprint() calls. " +
                            "Reports blueprint variable name, registered name, and url_prefix from both the " +
                            "constructor and any registration-time override. Shows definitions and registrations " +
                            "in separate sections."
                    )
                    plain(
                        "Lists every Blueprint the app defines and everywhere those blueprints are registered, " +
                            "including the URL prefix at each registration point."
                    )
                }
                whenLLMUses(
                    "When the user asks 'what blueprints does this app have', 'where is the auth blueprint " +
                        "registered', or 'what prefix does the api blueprint use'."
                )
                params {
                    optional("blueprint", "string") {
                        llmSeesIt("Filter by blueprint name — for routes, blueprints")
                        humanReadable(
                            "Filters blueprint definitions by matching against the registered name (the string " +
                                "in Blueprint('name', ...)) or the Python variable name. Case-insensitive substring."
                        )
                        whenPresent("Only blueprints whose name or variable name contain the substring are shown in definitions.")
                        whenAbsent("All blueprint definitions and all registrations are returned.")
                        example("auth")
                        example("api")
                    }
                    optional("filter", "string") {
                        llmSeesIt("General filter by name/pattern — for any action")
                        humanReadable("Fallback filter applied when `blueprint` is absent.")
                        whenPresent("Equivalent to `blueprint` param for this action.")
                        whenAbsent("No additional filtering.")
                    }
                }
                onSuccess(
                    "Two sections: 'Flask blueprints ({N} definitions)' and 'Blueprint registrations ({M})'. " +
                        "Each definition shows variable = Blueprint('name', url_prefix) and source file. " +
                        "Each registration shows register_blueprint(ref, url_prefix) and source file."
                )
                onFailure(
                    "No blueprints or registrations found",
                    "Returns 'No blueprints found.' — the app may use a single-file layout with no blueprints."
                )
                example("List all blueprints and registration points") {
                    param("action", "blueprints")
                    outcome("All Blueprint() definitions with their prefixes and all register_blueprint() call sites.")
                }
                example("Find where the users blueprint is registered") {
                    param("action", "blueprints")
                    param("blueprint", "users")
                    outcome("Filtered definition and all registration call sites referencing the 'users' blueprint.")
                }
                verdict {
                    keep(
                        "Complements `routes` — where `routes` shows composed URLs, `blueprints` shows the " +
                            "registration topology. Together they answer both 'what is the full URL' and " +
                            "'where is this blueprint wired in'."
                    )
                }
            }
            action("config") {
                description {
                    technical(
                        "Scans config.py, settings.py, default_settings.py, and any .py file inside a " +
                            "config/ directory. Extracts class-based config (SCREAMING_SNAKE_CASE keys inside " +
                            "class *Config* bodies) and top-level key-value assignments. Also scans all .py " +
                            "files for app.from_object()/app.from_pyfile()/app.from_envvar() references. " +
                            "Sensitive values (SECRET_KEY, DATABASE_URL, API_KEY, etc.) are redacted."
                    )
                    plain(
                        "Shows all Flask configuration keys and their values from config.py-style files. " +
                            "Automatically hides sensitive secrets. Also reveals how the app loads its config " +
                            "(from_object, from_pyfile, from_envvar)."
                    )
                }
                whenLLMUses(
                    "When the user asks 'what is the database URL', 'what config keys does this app have', " +
                        "'what environments are configured', or 'how does the app load its config'."
                )
                params {
                    optional("filter", "string") {
                        llmSeesIt("General filter by name/pattern — for any action")
                        humanReadable("Filters config keys by case-insensitive substring match against the key name.")
                        whenPresent("Only config keys whose name contains the substring are shown.")
                        whenAbsent("All config keys from all matched config files are shown.")
                        example("DATABASE")
                        example("MAIL")
                        example("SECRET")
                    }
                }
                rejectsParam("blueprint", "Config has no concept of blueprints — this param is ignored.")
                rejectsParam("extension", "Config has no extension concept — this param is ignored.")
                rejectsParam("model", "Config has no model concept — this param is ignored.")
                onSuccess(
                    "Config loading references section (from_object/from_pyfile calls), followed by per-file " +
                        "class-based or top-level config key listings. Sensitive values shown as '[REDACTED]'."
                )
                onFailure(
                    "No config.py, settings.py, or config/ directory found",
                    "Returns 'No config.py, settings.py, or config/ directory found.' — the project may use " +
                        "environment variables only, or config is in a non-standard location."
                )
                example("Show all config keys") {
                    param("action", "config")
                    outcome("All config classes and keys from config.py/settings.py, with sensitive values redacted.")
                }
                example("Find database-related config keys") {
                    param("action", "config")
                    param("filter", "DATABASE")
                    outcome("Only config keys containing 'DATABASE', e.g. SQLALCHEMY_DATABASE_URI.")
                }
                verdict {
                    keep(
                        "The redaction of sensitive values and automatic from_object/from_envvar reference " +
                            "tracing are genuinely useful — `search_code` would expose raw secret values."
                    )
                }
            }
            action("extensions") {
                description {
                    technical(
                        "Scans all .py files for known Flask extension constructor calls " +
                            "(e.g. `db = SQLAlchemy(app)`) and init_app() calls (`db.init_app(app)`). " +
                            "Detection is limited to a hard-coded allow-list of ~25 known extension class " +
                            "names (SQLAlchemy, Migrate, LoginManager, CORS, Bcrypt, JWT, etc.). " +
                            "Reports variable name, extension type, and init style (constructor vs init_app). " +
                            "Accepts both `extension` and `filter` params."
                    )
                    plain(
                        "Shows all Flask extensions the project uses and how they're wired to the app — " +
                            "either at construction time or via the deferred init_app() factory pattern."
                    )
                }
                whenLLMUses(
                    "When the user asks 'does this app use SQLAlchemy', 'how is Flask-Login configured', " +
                        "'what extensions are installed', or 'is CORS enabled'."
                )
                params {
                    optional("extension", "string") {
                        llmSeesIt("Filter by extension name — for extensions")
                        humanReadable("Case-insensitive substring filter applied to the extension class name and variable name.")
                        whenPresent("Only extensions whose class name or variable name contains the substring are returned.")
                        whenAbsent("All detected extensions and init_app calls are returned.")
                        example("SQLAlchemy")
                        example("Login")
                        example("CORS")
                    }
                    optional("filter", "string") {
                        llmSeesIt("General filter by name/pattern — for any action")
                        humanReadable("Fallback filter applied when `extension` is absent — same behavior.")
                        whenPresent("Equivalent to `extension` param for this action.")
                        whenAbsent("No additional filtering.")
                    }
                }
                rejectsParam("blueprint", "Extensions have no blueprint scope — this param is ignored.")
                rejectsParam("model", "Extensions are not models — this param is ignored.")
                onSuccess(
                    "Two sections: 'Extension instances' (constructor-style) grouped by file, and " +
                        "'init_app() calls' grouped by file. Summary: '{N} extensions, {M} init_app calls'."
                )
                onFailure(
                    "Extension uses a class name not in the allow-list",
                    "The extension is silently omitted. Follow up with `search_code` for the class name."
                )
                example("Show all Flask extensions") {
                    param("action", "extensions")
                    outcome("All known Flask extensions detected in the project, split by constructor vs init_app style.")
                }
                example("Check if SQLAlchemy is wired") {
                    param("action", "extensions")
                    param("extension", "SQLAlchemy")
                    outcome("SQLAlchemy constructor calls and init_app calls, with source file locations.")
                }
                verdict {
                    keep(
                        "Distinguishes constructor-style from init_app-style extension wiring — a Flask-specific " +
                            "pattern that `search_code` can find but cannot categorize structurally."
                    )
                    drop(
                        "Allow-list of 25 extension names is a maintenance burden and silently misses custom or " +
                            "less-common extensions. A regex-based approach scanning for `.init_app(app)` calls " +
                            "without an allow-list would be broader.",
                        VerdictSeverity.WEAK
                    )
                }
            }
            action("models") {
                description {
                    technical(
                        "Scans files literally named `models.py` for classes that extend `db.Model` or `Model`. " +
                            "Extracts column definitions (`db.Column`, `db.relationship`, `db.backref`) with " +
                            "column name, type, and arguments. Caps column display at 10 per model with a " +
                            "count of additional columns. Filters by model name or file path substring."
                    )
                    plain(
                        "Shows Flask-SQLAlchemy database models — the tables, columns, and relationships — " +
                            "from models.py files. Like a schema inspector that reads Python instead of SQL."
                    )
                }
                whenLLMUses(
                    "When the user asks 'what columns does the User model have', 'show me the database schema', " +
                        "'what relationships does Order have', or 'does a Product model exist'."
                )
                params {
                    optional("model", "string") {
                        llmSeesIt("Filter by SQLAlchemy model name — for models")
                        humanReadable("Case-insensitive substring filter applied to model class name and source file path.")
                        whenPresent("Only models whose class name or file path contains the substring are returned.")
                        whenAbsent("All models from all models.py files are returned.")
                        example("User")
                        example("Order")
                        example("auth/models")
                    }
                    optional("filter", "string") {
                        llmSeesIt("General filter by name/pattern — for any action")
                        humanReadable("Fallback filter applied when `model` is absent — same behavior.")
                        whenPresent("Equivalent to `model` param for this action.")
                        whenAbsent("No additional filtering.")
                    }
                }
                rejectsParam("blueprint", "Models are not blueprint-scoped — this param is ignored.")
                rejectsParam("extension", "Models are not extensions — this param is ignored.")
                precondition("Project must have at least one file named models.py. Models in other files are not scanned.")
                onSuccess(
                    "Grouped by file: each model class shown with its db.Model base, followed by up to 10 " +
                        "column/relationship entries with type and arguments. Summary: '{N} SQLAlchemy models across {M} file(s)'."
                )
                onFailure(
                    "No models.py files exist",
                    "Returns 'No models.py files found in project.' — use `search_code` with pattern `db\\.Model` " +
                        "to find models in non-standard files."
                )
                example("Show all SQLAlchemy models") {
                    param("action", "models")
                    outcome("All db.Model subclasses from models.py files with their column definitions.")
                }
                example("Show columns for the User model") {
                    param("action", "models")
                    param("model", "User")
                    outcome("Only the User model with its Column and relationship fields.")
                }
                verdict {
                    keep("Provides structured column + relationship output that `search_code` returns as raw source lines without grouping or truncation.")
                    drop(
                        "Filename-only scoping (models.py) is a significant blind spot. Many Flask projects " +
                            "split models across per-module files. A `search_code` scan for `db.Model` covers more ground.",
                        VerdictSeverity.NORMAL
                    )
                }
            }
            action("templates") {
                description {
                    technical(
                        "Walks all directories (excluding standard exclusions from PythonFileScanner.shouldScanDir) " +
                            "looking for files with extensions .html, .jinja2, or .j2. Groups results by parent " +
                            "directory. Delegates to PythonFileScanner.scanAndFormatTemplates. Accepts `filter` " +
                            "param for path/name substring filtering."
                    )
                    plain(
                        "Lists all Jinja2 template files in the project — like running `find . -name '*.html'` " +
                            "but scoped to Flask-relevant template directories and extensions."
                    )
                }
                whenLLMUses(
                    "When the user asks 'what templates does this app have', 'is there a login.html template', " +
                        "or 'show me all templates in the auth module'."
                )
                params {
                    optional("filter", "string") {
                        llmSeesIt("General filter by name/pattern — for any action")
                        humanReadable("Case-insensitive substring filter applied to the template file path.")
                        whenPresent("Only templates whose path contains the substring are listed.")
                        whenAbsent("All .html, .jinja2, and .j2 files are listed.")
                        example("auth/")
                        example("login")
                        example(".jinja2")
                    }
                }
                rejectsParam("blueprint", "Template listing has no blueprint scope — this param is ignored.")
                rejectsParam("extension", "Not relevant for templates — this param is ignored.")
                rejectsParam("model", "Not relevant for templates — this param is ignored.")
                onSuccess(
                    "Template files grouped by directory with relative paths. Header: 'Flask/Jinja2 templates'."
                )
                onFailure(
                    "No .html/.jinja2/.j2 files found",
                    "Returns an empty or minimal result — the project may use a frontend framework instead of server-side templates."
                )
                example("List all Jinja2 templates") {
                    param("action", "templates")
                    outcome("All .html, .jinja2, and .j2 files in the project, grouped by directory.")
                }
                example("Find auth-related templates") {
                    param("action", "templates")
                    param("filter", "auth")
                    outcome("Only template files with 'auth' in the path.")
                }
                verdict {
                    keep(
                        "Lightweight but genuinely useful for confirming template layout before editing. " +
                            "The file listing itself could be replaced by `glob_files` with pattern `**/*.html`."
                    )
                    drop(
                        "Thin wrapper around a directory walk with extension filter. `glob_files` with " +
                            "`**/*.html,**/*.jinja2,**/*.j2` achieves the same result without a dedicated action slot.",
                        VerdictSeverity.WEAK
                    )
                }
            }
            action("middleware") {
                description {
                    technical(
                        "Scans all .py files for Flask hook decorators: @app.before_request, @app.after_request, " +
                            "@app.before_first_request, @app.teardown_request, @app.teardown_appcontext, and " +
                            "@app.errorhandler(N). Also detects blueprint-scoped variants. Extracts the decorated " +
                            "function name and groups by source file."
                    )
                    plain(
                        "Lists all request lifecycle hooks — code that runs before or after every request, " +
                            "handles teardown, or catches specific HTTP error codes."
                    )
                }
                whenLLMUses(
                    "When the user asks 'what runs before every request', 'how does the app handle 404 errors', " +
                        "'is there a before_request hook for auth', or 'what teardown logic exists'."
                )
                params {
                    optional("filter", "string") {
                        llmSeesIt("General filter by name/pattern — for any action")
                        humanReadable("Case-insensitive substring filter applied to decorator string and function name.")
                        whenPresent("Only hooks whose decorator or function name contains the substring are returned.")
                        whenAbsent("All detected hooks are returned.")
                        example("before_request")
                        example("errorhandler")
                        example("auth")
                    }
                }
                rejectsParam("blueprint", "Use `filter` with the blueprint variable name to narrow to blueprint-scoped hooks.")
                rejectsParam("extension", "Not relevant for middleware — this param is ignored.")
                rejectsParam("model", "Not relevant for middleware — this param is ignored.")
                onSuccess(
                    "Grouped by source file: each hook shown as '@{obj}.{hookType}({code?}) -> {function}()'. " +
                        "Summary: '{N} middleware hooks across {M} file(s)'."
                )
                onFailure(
                    "No hooks found",
                    "Returns 'No before_request/after_request/errorhandler hooks found.' — the app may use " +
                        "middleware at the WSGI level instead (e.g. via Werkzeug middleware stack)."
                )
                example("List all request lifecycle hooks") {
                    param("action", "middleware")
                    outcome("All @before_request, @after_request, @errorhandler, and teardown decorators with their handler functions.")
                }
                example("Find error handlers") {
                    param("action", "middleware")
                    param("filter", "errorhandler")
                    outcome("Only @app.errorhandler and @blueprint.errorhandler hooks.")
                }
                verdict {
                    keep(
                        "Flask's hook system is specific enough that a dedicated scan is clearer than grepping " +
                            "for a list of decorator names."
                    )
                }
            }
            action("cli_commands") {
                description {
                    technical(
                        "Scans all .py files for three CLI registration patterns: @app.cli.command() " +
                            "(Flask's built-in CLI), @click.command() (standalone Click commands), and " +
                            "@click.group() (Click command groups). Extracts the command name (explicit string " +
                            "arg or function name fallback) and the decorated function name."
                    )
                    plain(
                        "Lists all custom CLI commands the Flask app registers — the commands you'd run with " +
                            "`flask {command}` or as standalone Click scripts."
                    )
                }
                whenLLMUses(
                    "When the user asks 'what flask commands are available', 'how do I seed the database from CLI', " +
                        "'is there a migrate command', or 'what Click commands does this project expose'."
                )
                params {
                    optional("filter", "string") {
                        llmSeesIt("General filter by name/pattern — for any action")
                        humanReadable("Case-insensitive substring filter applied to command name, function name, and decorator string.")
                        whenPresent("Only CLI commands matching the substring are returned.")
                        whenAbsent("All detected CLI commands across all three patterns are returned.")
                        example("db")
                        example("seed")
                        example("migrate")
                    }
                }
                rejectsParam("blueprint", "CLI commands are not blueprint-scoped — this param is ignored.")
                rejectsParam("extension", "Not relevant for CLI — this param is ignored.")
                rejectsParam("model", "Not relevant for CLI — this param is ignored.")
                onSuccess(
                    "Grouped by source file: '@{decorator} {name} -> {function}()'. Three categories covered: " +
                        "app.cli.command, click.command, click.group. Summary: '{N} CLI commands'."
                )
                onFailure(
                    "No CLI commands found",
                    "Returns 'No CLI commands found.' — the project may register commands programmatically " +
                        "via app.cli.add_command(), which this action does not detect."
                )
                example("List all Flask CLI commands") {
                    param("action", "cli_commands")
                    outcome("All @app.cli.command and @click.command decorated functions with their command names.")
                }
                example("Find database management commands") {
                    param("action", "cli_commands")
                    param("filter", "db")
                    outcome("Only CLI commands whose name, function, or decorator contains 'db'.")
                }
                verdict {
                    keep(
                        "Flask CLI commands are easy to miss in a large project; a dedicated scan is much " +
                            "faster than searching for `cli.command` in `search_code`."
                    )
                }
            }
            action("forms") {
                description {
                    technical(
                        "Scans files literally named `forms.py` or `form.py` for classes extending FlaskForm " +
                            "or Form. Extracts field definitions matching WTForms field class names " +
                            "(StringField, SelectField, BooleanField, etc.). Caps field display at 10 per form."
                    )
                    plain(
                        "Shows all Flask-WTF form classes and their fields — like reading the form definition " +
                            "files directly but with structured output grouped by class."
                    )
                }
                whenLLMUses(
                    "When the user asks 'what forms does this app have', 'what fields does the RegistrationForm require', " +
                        "'does a LoginForm exist', or 'show me all WTForms in the project'."
                )
                params {
                    optional("filter", "string") {
                        llmSeesIt("General filter by name/pattern — for any action")
                        humanReadable("Case-insensitive substring filter applied to form class name and source file path.")
                        whenPresent("Only forms whose class name or file path contains the substring are returned.")
                        whenAbsent("All forms from all forms.py/form.py files are returned.")
                        example("Login")
                        example("Registration")
                        example("auth/")
                    }
                }
                rejectsParam("blueprint", "Forms are not blueprint-scoped in this action — use `filter` with a path substring instead.")
                rejectsParam("extension", "Not relevant for forms — this param is ignored.")
                rejectsParam("model", "Not relevant for forms — this param is ignored.")
                precondition("Project must have at least one file named forms.py or form.py.")
                onSuccess(
                    "Grouped by file: each form class with its base (FlaskForm/Form) and up to 10 field entries " +
                        "'fieldName: FieldType'. Summary: '{N} forms'."
                )
                onFailure(
                    "No forms.py or form.py files found",
                    "Returns 'No forms.py files found in project.' — forms may be defined inline in views or " +
                        "using a non-standard filename. Use `search_code` for `FlaskForm` as a fallback."
                )
                example("List all Flask-WTF forms") {
                    param("action", "forms")
                    outcome("All FlaskForm/Form subclasses from forms.py files with their field definitions.")
                }
                example("Show the LoginForm fields") {
                    param("action", "forms")
                    param("filter", "Login")
                    outcome("Only the LoginForm class with its StringField, PasswordField, etc. definitions.")
                }
                verdict {
                    keep("Provides structured field output; `search_code` returns raw class source without grouping.")
                    drop(
                        "Filename-only scoping misses forms in any file not named forms.py or form.py. " +
                            "Same limitation as the `models` action — both could be broadened by scanning for " +
                            "the base class name instead of the filename.",
                        VerdictSeverity.NORMAL
                    )
                }
            }
            action("version_info") {
                description {
                    technical(
                        "Reads requirements*.txt, pyproject.toml, Pipfile, and setup.py from the project root. " +
                            "Extracts pinned versions for flask, werkzeug, jinja2, flask-sqlalchemy, flask-migrate, " +
                            "flask-wtf, flask-login, flask-cors, flask-restful, flask-marshmallow, celery, " +
                            "gunicorn, and sqlalchemy. Deduplicates by package name. Separates core " +
                            "(flask/werkzeug/jinja2) from extensions in output."
                    )
                    plain(
                        "Shows what version of Flask and its companion libraries the project is pinned to — " +
                            "like reading requirements.txt but with noise filtered out and core vs extensions split."
                    )
                }
                whenLLMUses(
                    "When the user asks 'what version of Flask is this project using', 'is Flask 3.x being used', " +
                        "'what version of SQLAlchemy is installed', or 'does this app use Celery'."
                )
                params { /* no params — takes no input beyond action */ }
                rejectsParam("blueprint", "Version info has no blueprint concept — this param is ignored.")
                rejectsParam("extension", "Filter not supported for version_info — this param is ignored.")
                rejectsParam("model", "Not relevant for version info — this param is ignored.")
                rejectsParam("filter", "Filter not supported for version_info — this param is ignored.")
                onSuccess(
                    "Two sections: 'Core' (flask, werkzeug, jinja2) and 'Extensions & libraries' (everything else). " +
                        "Each line: '{package} == {version}'. Summary: 'Flask {version}, {N} packages'."
                )
                onFailure(
                    "No dependency files found or no Flask packages pinned",
                    "Returns 'No Flask/Werkzeug/Jinja2 versions found in dependency files.' — project may use " +
                        "uv lock files or conda which are not scanned."
                )
                onFailure(
                    "Version specifier is a range (>=, ~=) not a pin (==)",
                    "Package appears in output with the range specifier, or may be omitted entirely from the " +
                        "regex match — only `==` pinned versions are reliably captured."
                )
                example("Check Flask version") {
                    param("action", "version_info")
                    outcome("Flask, Werkzeug, Jinja2 pinned versions plus any other matched Flask ecosystem packages.")
                    notes("Only reads static dependency files — does not reflect the actually installed runtime version.")
                }
                verdict {
                    keep("Quick way to confirm the Flask version before applying version-specific advice (e.g. Flask 2 vs Flask 3 app factory pattern differences).")
                    drop(
                        "Reads only four file types and only == pins. `search_code` on requirements.txt with a " +
                            "grep pattern achieves the same in one step and handles all specifier forms.",
                        VerdictSeverity.WEAK
                    )
                }
            }
        }
    }
}

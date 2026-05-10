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
import com.workflow.orchestrator.agent.tools.framework.django.executeAdmin
import com.workflow.orchestrator.agent.tools.framework.django.executeCeleryTasks
import com.workflow.orchestrator.agent.tools.framework.django.executeFixtures
import com.workflow.orchestrator.agent.tools.framework.django.executeForms
import com.workflow.orchestrator.agent.tools.framework.django.executeManagementCommands
import com.workflow.orchestrator.agent.tools.framework.django.executeMiddleware
import com.workflow.orchestrator.agent.tools.framework.django.executeModels
import com.workflow.orchestrator.agent.tools.framework.django.executeSerializers
import com.workflow.orchestrator.agent.tools.framework.django.executeSettings
import com.workflow.orchestrator.agent.tools.framework.django.executeSignals
import com.workflow.orchestrator.agent.tools.framework.django.executeTemplates
import com.workflow.orchestrator.agent.tools.framework.django.executeUrls
import com.workflow.orchestrator.agent.tools.framework.django.executeVersionInfo
import com.workflow.orchestrator.agent.tools.framework.django.executeViews
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.coroutineContext

/**
 * Consolidated Django meta-tool replacing 13 individual Django analysis tools.
 *
 * Saves token budget per API call by collapsing all Django-related operations into
 * a single tool definition with an `action` discriminator parameter.
 *
 * Actions: models, views, urls, settings, admin, management_commands, celery_tasks,
 *          middleware, signals, serializers, forms, fixtures, templates, version_info
 *
 * Each action is implemented in its own file under
 * `com.workflow.orchestrator.agent.tools.framework.django`. This class is a thin
 * dispatcher that exposes the tool surface (name, description, parameter schema)
 * and routes the `action` parameter to the corresponding handler.
 *
 * Only registered when Django is detected in the project (manage.py + django dependency).
 */
class DjangoTool : AgentTool {

    override val name = "django"

    override val description = """
Django framework intelligence — models, views, URLs, settings, admin, management commands.

Actions and their parameters:
- models(filter?) → Django model classes with fields
- views(filter?) → Function-based and class-based views, viewsets
- urls(filter?) → URL patterns from urls.py files
- settings(filter?) → Django settings values
- admin(filter?) → Admin site registrations
- management_commands(filter?) → Custom management commands
- celery_tasks(filter?) → Celery task definitions
- middleware() → MIDDLEWARE stack from settings
- signals(filter?) → Signal handler registrations
- serializers(filter?) → DRF serializer classes
- forms(filter?) → Form and ModelForm classes
- fixtures(filter?) → Fixture files in fixtures/ directories
- templates(filter?) → Template files in templates/ directories
- version_info() → Django version and installed apps
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf(
                    "models", "views", "urls", "settings", "admin",
                    "management_commands", "celery_tasks", "middleware",
                    "signals", "serializers", "forms", "fixtures",
                    "templates", "version_info"
                )
            ),
            "filter" to ParameterProperty(
                type = "string",
                description = "Filter results by name/pattern — for models, views, urls, settings, admin, management_commands, celery_tasks, signals, serializers, forms, fixtures, templates"
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
            "models" -> executeModels(params, project)
            "views" -> executeViews(params, project)
            "urls" -> executeUrls(params, project)
            "settings" -> executeSettings(params, project)
            "admin" -> executeAdmin(params, project)
            "management_commands" -> executeManagementCommands(params, project)
            "celery_tasks" -> executeCeleryTasks(params, project)
            "middleware" -> executeMiddleware(params, project)
            "signals" -> executeSignals(params, project)
            "serializers" -> executeSerializers(params, project)
            "forms" -> executeForms(params, project)
            "fixtures" -> executeFixtures(params, project)
            "templates" -> executeTemplates(params, project)
            "version_info" -> executeVersionInfo(params, project)
            else -> ToolResult(
                content = "Unknown action '$action'. See tool description for valid actions.",
                summary = "Unknown action '$action'",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }

    override fun documentation(): ToolDocumentation = toolDoc("django") {
        summary {
            technical(
                "Single-tool dispatcher for 14 Django framework introspection operations via file-scan (regex on .py files) with PSI-optional design. " +
                    "Covers models, views, URLs, settings (with sensitive-value redaction), admin registrations, management commands, Celery tasks, " +
                    "middleware stack, signals, DRF serializers, forms, fixtures, templates, and version/app inventory. " +
                    "Registered only when Django is detected (manage.py + django in deps); promoted from deferred to core on detection."
            )
            plain(
                "The agent's Django remote control — like having a project-aware 'grep for Django patterns' built in. " +
                    "Instead of asking the LLM to run search_code with Django-specific regex patterns that it often gets wrong, " +
                    "one call enumerates models or URL patterns accurately, grouped by app, with sensitive values (SECRET_KEY, passwords) automatically redacted."
            )
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.READ_ONLY)
        counterfactual(
            "Without `django`, the LLM falls back to `search_code` with Django-specific regex patterns — scanning for `class.*Model`, " +
                "`urlpatterns`, `MIDDLEWARE`, `@admin.register`, etc. This approach is fragile: it misses models defined via abstract base classes, " +
                "catches commented-out code as false positives, cannot redact sensitive settings values, and produces no app-grouping context. " +
                "The file-scan approach in this tool uses the same project-walk that Django itself uses (exclude venv, __pycache__, etc.), " +
                "groups results by Django app, and applies the same INSTALLED_APPS / manage.py heuristics that make the output meaningful."
        )
        llmMistake(
            "Calls `settings` without a filter on a large project and then tries to process hundreds of settings lines. " +
                "Always pass `filter` with a key prefix (e.g., 'DATABASE', 'CACHE') when looking for something specific — " +
                "settings files can contain 100+ variables."
        )
        llmMistake(
            "Calls `middleware` or `version_info` and passes a `filter` param — these two actions silently ignore it " +
                "(middleware reads the MIDDLEWARE list verbatim; version_info reads dependency files). " +
                "Passing `filter` wastes nothing but signals the LLM doesn't know which actions accept filters."
        )
        llmMistake(
            "Treats an empty result from `models` or `views` as proof those don't exist. " +
                "The scanner looks for specific file names (models.py, views.py). Projects that split models across " +
                "multiple files (models/order.py, models/user.py) in a models/ package are only partially covered — " +
                "the LLM should follow up with `search_code` if the result seems incomplete."
        )
        llmMistake(
            "Calls `urls` expecting a fully-resolved URL tree. The tool reads `urlpatterns` from urls.py files but " +
                "does NOT follow `include()` chains — nested URLconfs from included modules are listed as plain strings, " +
                "not expanded. Use `search_code` to trace include() chains."
        )
        llmMistake(
            "Calls `celery_tasks` expecting task results or execution history — this returns task *definitions* " +
                "(@shared_task / @app.task decorated functions), not runtime data. For runtime state, use `run_command` with celery inspect."
        )
        downside(
            "File-scan primary, PSI-optional design: the tool scans .py source files with regex, not the Python type system. " +
                "Django dynamic patterns (e.g., models registered via ContentType framework, views generated at runtime via " +
                "type() calls, admin registrations via admin.autodiscover() with programmatic model references) are invisible."
        )
        downside(
            "Models scanner looks for 'models.py' by filename. Projects that organise models into a models/ package " +
                "(models/__init__.py + models/order.py + models/user.py) will only surface files whose name is 'models.py' — " +
                "split-file model packages are missed unless those files are also named models.py."
        )
        downside(
            "Settings redaction is key-name heuristic: values whose key contains SECRET_KEY, PASSWORD, API_KEY, TOKEN, " +
                "DATABASE_URL, PRIVATE_KEY, AWS_SECRET, etc. are replaced with '[REDACTED]'. " +
                "A custom setting with a non-standard sensitive name (e.g., MY_STRIPE_CREDENTIAL) will NOT be redacted."
        )
        downside(
            "URL pattern resolution is shallow: `include()` references are listed as-is without expanding the included URLconf. " +
                "The full resolved URL tree requires following the include chains manually."
        )
        downside(
            "The tool is only registered when Django is detected at project scan time (manage.py + django in deps). " +
                "If requirements files are missing or unreadable, the tool is not promoted and must be loaded via tool_search."
        )
        related("fastapi", Relationship.SEE_ALSO, "Sister framework tool for FastAPI projects — same file-scan pattern, 10 actions for FastAPI-specific constructs.")
        related("flask", Relationship.SEE_ALSO, "Sister framework tool for Flask projects — same file-scan pattern, 10 actions for Flask-specific constructs.")
        related("search_code", Relationship.FALLBACK, "Use as a fallback when django returns incomplete results (e.g., split model packages, dynamic registrations, include() URL chains).")
        related("project_context", Relationship.COMPLEMENT, "Use project_context to understand overall project structure and file layout before drilling into Django-specific constructs.")
        related("python_runtime_exec", Relationship.COMPLEMENT, "Use python_runtime_exec to run pytest against Django views/models; use django to understand the static structure first.")
        observation(
            "The `filter` param is shared across 12 of 14 actions but the LLM description lists it per-action. " +
                "This is correct UX (avoids confusion about which actions take filter) but produces a slightly longer description than necessary."
        )
        observation(
            "`version_info` is the only action that reads both .txt (requirements.txt) and .toml (pyproject.toml) files. " +
                "All other actions are .py-only. This asymmetry is correct but worth noting if the scanner is ever extended to TOML."
        )
        observation(
            "`middleware` is the only action that returns an ordered list (position matters for Django middleware). " +
                "The output preserves insertion order from the MIDDLEWARE list — this is important and should be preserved in any future refactor."
        )
        actions {
            action("models") {
                description {
                    technical(
                        "Scans models.py files across the project for Django model class definitions (subclasses of Model or models.Model) " +
                            "using regex, groups results by Django app (parent directory name), and lists up to 10 fields per model. " +
                            "Filter applies to model name and app name."
                    )
                    plain(
                        "Lists all Django models — like browsing the ORM layer without opening every models.py. " +
                            "Shows each model's fields grouped by which Django app owns it."
                    )
                }
                whenLLMUses("When the user asks 'what models exist', 'what fields does the Order model have', or 'which app owns the User model'. Starting point for any ORM or database schema question.")
                params {
                    optional("filter", "string") {
                        llmSeesIt("Filter results by name/pattern — for models, views, urls, settings, admin, management_commands, celery_tasks, signals, serializers, forms, fixtures, templates")
                        humanReadable("Case-insensitive substring filter applied to both model name and app name. Use to narrow a large project.")
                        whenPresent("Only models whose name or app contains this substring are returned.")
                        whenAbsent("All models across all apps are returned.")
                        example("Order")
                        example("auth")
                        example("User")
                    }
                }
                precondition("Project must contain at least one models.py file. Models defined only in models/ packages (not named models.py) are not found by this action.")
                onSuccess("Returns models grouped by app, each with up to 10 field names and types. If a model has more than 10 fields, a '... (N more fields)' note is appended.")
                onFailure("no models.py files found", "Returns 'No models.py files found in project.' — not an error ToolResult. LLM should check if models live in a models/ package and use search_code.")
                onFailure("filter matches nothing", "Returns 'No models found matching filter.' — empty result, not an error. LLM should broaden or remove the filter.")
                example("list all models in the orders app") {
                    param("action", "models")
                    param("filter", "orders")
                    outcome("Returns all models defined in any orders/models.py — e.g. Order (10 fields), OrderItem (6 fields), OrderStatus (3 fields).")
                }
                example("find the User model fields") {
                    param("action", "models")
                    param("filter", "User")
                    outcome("Returns User model with its fields — username, email, first_name, last_name, is_active, date_joined, etc.")
                }
                verdict {
                    keep("Primary entry point for ORM schema exploration. The app-grouping and field listing are not replicable with a single search_code call.", VerdictSeverity.STRONG)
                }
            }
            action("views") {
                description {
                    technical(
                        "Scans views.py files for function-based views (def functions returning HttpResponse/render/redirect) and class-based views " +
                            "(classes inheriting from Django generic view classes or ViewSet). Groups by app. Filter applies to view name and app."
                    )
                    plain(
                        "Lists all the request handlers — every function or class in views.py that Django can route requests to. " +
                            "Shows both old-style function views and class-based views (ListView, DetailView, ViewSet, etc.)."
                    )
                }
                whenLLMUses("When the user asks 'what views exist', 'what handles the order detail page', 'which app owns the login view', or 'are there any ViewSets'. Precedes URL routing investigation.")
                params {
                    optional("filter", "string") {
                        llmSeesIt("Filter results by name/pattern — for models, views, urls, settings, admin, management_commands, celery_tasks, signals, serializers, forms, fixtures, templates")
                        humanReadable("Case-insensitive substring filter on view name and app name.")
                        whenPresent("Only views matching the filter are returned.")
                        whenAbsent("All views across all apps are returned.")
                        example("OrderView")
                        example("api")
                    }
                }
                precondition("Project must contain views.py files. Apps that split views into a views/ package are not fully covered.")
                onSuccess("Returns views grouped by app with each view's name and type (FBV or CBV with base class).")
                onFailure("no views.py files found", "Empty result — LLM should check for views/ packages and use search_code.")
                onFailure("filter matches nothing", "Empty result — LLM should broaden or remove the filter.")
                example("find all ViewSets in the api app") {
                    param("action", "views")
                    param("filter", "ViewSet")
                    outcome("Returns all ViewSet subclasses across the project — e.g. OrderViewSet, ProductViewSet.")
                }
                verdict {
                    keep("App-grouped view inventory is significantly more readable than raw search_code output for large Django projects.", VerdictSeverity.NORMAL)
                }
            }
            action("urls") {
                description {
                    technical(
                        "Scans urls.py files for urlpatterns lists, extracting path/re_path/url entries with their route string, view reference, and name. " +
                            "Does NOT follow include() chains — nested URLconfs are listed as plain strings. Filter applies to route pattern and view reference."
                    )
                    plain(
                        "Lists all URL patterns — like reading every urls.py at once to see what routes are registered. " +
                            "Shows the path pattern, what view it calls, and the name= alias if any. Does not expand nested include() imports."
                    )
                }
                whenLLMUses("When the user asks 'what URL handles /api/orders/', 'what routes are registered', 'does this URL exist'. Pairs with views action to trace route-to-handler mappings.")
                params {
                    optional("filter", "string") {
                        llmSeesIt("Filter results by name/pattern — for models, views, urls, settings, admin, management_commands, celery_tasks, signals, serializers, forms, fixtures, templates")
                        humanReadable("Case-insensitive substring filter on the URL route pattern or view reference string.")
                        whenPresent("Only URL entries whose route or view reference contains this substring are returned.")
                        whenAbsent("All URL patterns across all urls.py files are returned.")
                        example("/api/orders")
                        example("OrderViewSet")
                        example("login")
                    }
                }
                precondition("Project must contain urls.py files.")
                onSuccess("Returns URL patterns per file, each with route, view reference, and name. include() entries are listed as-is without expansion.")
                onFailure("no urls.py files found", "Empty result — LLM should check project structure.")
                onFailure("filter matches nothing", "Empty result — LLM should broaden or remove the filter.")
                example("find all URL patterns for orders") {
                    param("action", "urls")
                    param("filter", "orders")
                    outcome("Returns entries like 'path(\"api/orders/\", OrderViewSet.as_view({...}), name=\"order-list\")'.")
                    notes("include() chains to other URLconfs are shown verbatim — follow up with search_code if the actual nested routes are needed.")
                }
                verdict {
                    keep("URL inventory across multiple urls.py files is significantly faster than manual search_code; the shallow-include limitation is acceptable for most routing questions.", VerdictSeverity.NORMAL)
                }
            }
            action("settings") {
                description {
                    technical(
                        "Scans settings*.py files for uppercase assignment patterns (SETTING_NAME = value). Applies sensitive-value redaction via " +
                            "PythonFileScanner.redactIfSensitive() for keys containing SECRET_KEY, PASSWORD, API_KEY, TOKEN, DATABASE_URL, PRIVATE_KEY, AWS_SECRET, etc. " +
                            "Filter applies to setting key names."
                    )
                    plain(
                        "Shows Django settings values — like reading settings.py but with passwords and secret keys automatically blurred. " +
                            "Can filter by key name prefix to find just the database settings or cache settings without reading everything."
                    )
                }
                whenLLMUses("When the user asks 'what is the database configured', 'what is ALLOWED_HOSTS set to', 'what cache backend is used', or needs to understand the runtime configuration.")
                params {
                    optional("filter", "string") {
                        llmSeesIt("Filter results by name/pattern — for models, views, urls, settings, admin, management_commands, celery_tasks, signals, serializers, forms, fixtures, templates")
                        humanReadable("Case-insensitive substring filter on setting key names. Use to narrow the output to a specific area (e.g., 'DATABASE', 'CACHE', 'EMAIL').")
                        whenPresent("Only settings whose key contains this substring are returned.")
                        whenAbsent("All uppercase settings from all settings*.py files are returned — can be 100+ entries.")
                        example("DATABASE")
                        example("CACHE")
                        example("EMAIL")
                        example("ALLOWED")
                    }
                }
                precondition("Project must contain at least one settings*.py file.")
                onSuccess("Returns settings per file with key=value pairs; sensitive values replaced with '[REDACTED]'. Values are truncated at 120 characters.")
                onFailure("no settings files found", "Returns 'No settings*.py files found in project.'")
                onFailure("filter matches nothing", "Returns 'No settings found matching filter.'")
                example("find all database settings") {
                    param("action", "settings")
                    param("filter", "DATABASE")
                    outcome("Returns DATABASES dict and related DB settings; DATABASE_URL and passwords are redacted.")
                }
                example("check ALLOWED_HOSTS") {
                    param("action", "settings")
                    param("filter", "ALLOWED_HOSTS")
                    outcome("Returns ALLOWED_HOSTS value — e.g. ['localhost', '127.0.0.1', '.example.com'].")
                }
                verdict {
                    keep("The sensitive-value redaction alone justifies this over raw read_file on settings.py — prevents the LLM from seeing or echoing credentials.", VerdictSeverity.STRONG)
                }
            }
            action("admin") {
                description {
                    technical(
                        "Scans admin.py files for Django admin registrations — @admin.register decorators and admin.site.register() calls. " +
                            "Returns the registered model name and admin class if present. Filter applies to model name and admin class name."
                    )
                    plain(
                        "Lists all models registered in Django's admin site — what you'd see in the left sidebar of /admin/. " +
                            "Shows both decorator-style and call-style registrations."
                    )
                }
                whenLLMUses("When the user asks 'is this model in the admin', 'what admin classes exist', 'how is Order configured in admin'. Useful for debugging admin-related issues or understanding admin customizations.")
                params {
                    optional("filter", "string") {
                        llmSeesIt("Filter results by name/pattern — for models, views, urls, settings, admin, management_commands, celery_tasks, signals, serializers, forms, fixtures, templates")
                        humanReadable("Case-insensitive substring filter on model name or admin class name.")
                        whenPresent("Only admin registrations matching the filter are returned.")
                        whenAbsent("All admin registrations across all admin.py files are returned.")
                        example("Order")
                        example("ModelAdmin")
                    }
                }
                precondition("Project must contain admin.py files.")
                onSuccess("Returns admin registrations grouped by app file with model name and admin class (or 'default ModelAdmin' for bare registrations).")
                onFailure("no admin.py files found", "Empty result — app may not use Django admin.")
                onFailure("filter matches nothing", "Empty result — LLM should broaden or remove the filter.")
                example("check if Order is in admin") {
                    param("action", "admin")
                    param("filter", "Order")
                    outcome("Returns registration info — e.g. 'Order → OrderAdmin (list_display, search_fields, etc.)'.")
                }
                verdict {
                    keep("Admin registration scanning is non-trivial to replicate with search_code due to the mix of decorator and call-style syntax.", VerdictSeverity.NORMAL)
                }
            }
            action("management_commands") {
                description {
                    technical(
                        "Scans management/commands/ directories for custom management command classes (subclasses of BaseCommand). " +
                            "Returns command name (derived from file name) and the app it belongs to. Filter applies to command name and app."
                    )
                    plain(
                        "Lists all custom manage.py commands the project defines — what you'd see with 'python manage.py help'. " +
                            "Shows command name and which app owns it."
                    )
                }
                whenLLMUses("When the user asks 'what custom management commands exist', 'is there a command to seed the database', 'what does manage.py import_data do'. Useful before recommending run_command with manage.py.")
                params {
                    optional("filter", "string") {
                        llmSeesIt("Filter results by name/pattern — for models, views, urls, settings, admin, management_commands, celery_tasks, signals, serializers, forms, fixtures, templates")
                        humanReadable("Case-insensitive substring filter on command name and app name.")
                        whenPresent("Only management commands matching the filter are returned.")
                        whenAbsent("All custom management commands across all apps are returned.")
                        example("import")
                        example("seed")
                        example("export")
                    }
                }
                precondition("Commands must follow Django convention: <app>/management/commands/<name>.py with a Command(BaseCommand) class.")
                onSuccess("Returns management command names with their owning app and source file path.")
                onFailure("no management commands found", "Empty result — project may use no custom commands or follow a non-standard layout.")
                example("find all import commands") {
                    param("action", "management_commands")
                    param("filter", "import")
                    outcome("Returns e.g. 'myapp.import_products', 'myapp.import_users' with their file paths.")
                }
                verdict {
                    keep("Discovery of custom management commands is not easily done with search_code — the directory convention is non-obvious and the naming is file-based.", VerdictSeverity.NORMAL)
                }
            }
            action("celery_tasks") {
                description {
                    technical(
                        "Scans .py files for Celery task definitions decorated with @shared_task or @app.task. " +
                            "Returns task function name and source file. Filter applies to task name and file path. " +
                            "Returns task *definitions* only — not execution history, queues, or runtime state."
                    )
                    plain(
                        "Lists all Celery background task functions in the project — like running 'celery inspect registered' but statically, without a running worker. " +
                            "Shows what tasks are defined, not whether they're running or queued."
                    )
                }
                whenLLMUses("When the user asks 'what Celery tasks exist', 'is there a task for sending emails', 'what background tasks does this app define'. Precedes any run_command with celery commands.")
                params {
                    optional("filter", "string") {
                        llmSeesIt("Filter results by name/pattern — for models, views, urls, settings, admin, management_commands, celery_tasks, signals, serializers, forms, fixtures, templates")
                        humanReadable("Case-insensitive substring filter on task function name and source file path.")
                        whenPresent("Only tasks matching the filter are returned.")
                        whenAbsent("All Celery task definitions across the project are returned.")
                        example("send_email")
                        example("notification")
                        example("payment")
                    }
                }
                onSuccess("Returns task function names with their source file paths. Does not include queue names, routing keys, or rate limits — those require reading the task decorator arguments.")
                onFailure("no celery tasks found", "Empty result — project may not use Celery or tasks may use non-standard decorators.")
                example("find all notification tasks") {
                    param("action", "celery_tasks")
                    param("filter", "notification")
                    outcome("Returns e.g. 'send_push_notification', 'send_email_notification' with source paths.")
                }
                verdict {
                    keep("Static discovery of Celery tasks is more reliable than search_code with decorator patterns, especially when @shared_task and @app.task are mixed.", VerdictSeverity.NORMAL)
                }
            }
            action("middleware") {
                description {
                    technical(
                        "Reads the MIDDLEWARE list from settings*.py files using regex, preserving insertion order. " +
                            "Returns the ordered middleware class strings exactly as configured. " +
                            "Takes no filter parameter — returns the complete list."
                    )
                    plain(
                        "Shows the Django middleware stack in order — like reading the MIDDLEWARE list in settings.py. " +
                            "Middleware order matters in Django (request processing is top-down, response is bottom-up), so the list is always returned in full."
                    )
                }
                whenLLMUses("When the user asks 'what middleware is configured', 'is SecurityMiddleware installed', 'what order is middleware processed in'. Useful for debugging request/response processing issues.")
                onSuccess("Returns the ordered MIDDLEWARE list from each settings file, numbered 1..N to preserve position context.")
                onFailure("no settings files found", "Returns 'No settings*.py files found in project.'")
                onFailure("MIDDLEWARE not defined in settings", "Returns 'No MIDDLEWARE setting found in settings files.'")
                example("show the full middleware stack") {
                    param("action", "middleware")
                    outcome("Returns ordered list: '1. django.middleware.security.SecurityMiddleware', '2. django.contrib.sessions.middleware.SessionMiddleware', etc.")
                }
                verdict {
                    keep("Zero-param, reads the exact ordered list that Django processes at runtime. Order matters — this is not replicable with search_code which returns unsorted matches.", VerdictSeverity.NORMAL)
                }
            }
            action("signals") {
                description {
                    technical(
                        "Scans .py files for Django signal handler registrations — @receiver decorator and explicit signal.connect() calls. " +
                            "Returns handler function name, signal name, and sender if specified. Filter applies to handler name and signal name."
                    )
                    plain(
                        "Lists all Django signal handlers — functions that run automatically when a model is saved, deleted, or a request is processed. " +
                            "Like tracing the event-driven wiring that's invisible from call graphs."
                    )
                }
                whenLLMUses("When the user asks 'what happens when a User is saved', 'are there post_save signals for Order', 'what signal handlers exist'. Essential for tracing Django event-driven side effects.")
                params {
                    optional("filter", "string") {
                        llmSeesIt("Filter results by name/pattern — for models, views, urls, settings, admin, management_commands, celery_tasks, signals, serializers, forms, fixtures, templates")
                        humanReadable("Case-insensitive substring filter on signal name and handler function name.")
                        whenPresent("Only signal registrations matching the filter are returned.")
                        whenAbsent("All signal handlers across the project are returned.")
                        example("post_save")
                        example("Order")
                        example("notification")
                    }
                }
                onSuccess("Returns signal handler entries with handler name, signal type (post_save, pre_delete, etc.), and sender model if declared.")
                onFailure("no signal handlers found", "Empty result — project may not use signals or may use alternative patterns (e.g., overriding save()).")
                example("find all post_save handlers") {
                    param("action", "signals")
                    param("filter", "post_save")
                    outcome("Returns all @receiver(post_save, ...) handlers and signal.connect() calls for post_save — e.g. 'notify_on_order_save → post_save[Order]'.")
                }
                verdict {
                    keep("Signal handler discovery is not easily done with search_code — the @receiver decorator and signal.connect() call forms require different patterns, and sender= context is key.", VerdictSeverity.NORMAL)
                }
            }
            action("serializers") {
                description {
                    technical(
                        "Scans serializers.py files for DRF serializer class definitions — subclasses of Serializer, ModelSerializer, HyperlinkedModelSerializer, etc. " +
                            "Returns class name, base class, and the model it serializes if detectable from Meta.model. Filter applies to class name and app."
                    )
                    plain(
                        "Lists all Django REST Framework serializers — the classes that control what data the API exposes. " +
                            "Shows which model each serializer wraps, if declared in Meta."
                    )
                }
                whenLLMUses("When the user asks 'what serializers exist', 'how is Order serialized', 'which serializer handles user registration'. Precedes any API request/response investigation.")
                params {
                    optional("filter", "string") {
                        llmSeesIt("Filter results by name/pattern — for models, views, urls, settings, admin, management_commands, celery_tasks, signals, serializers, forms, fixtures, templates")
                        humanReadable("Case-insensitive substring filter on serializer class name and app name.")
                        whenPresent("Only serializers matching the filter are returned.")
                        whenAbsent("All serializers across all apps are returned.")
                        example("Order")
                        example("UserSerializer")
                    }
                }
                precondition("Project must use Django REST Framework and contain serializers.py files.")
                onSuccess("Returns serializer class names grouped by app with base class and (if detectable) the model from Meta.model.")
                onFailure("no serializers.py files found", "Empty result — project may not use DRF or may split serializers differently.")
                example("find the Order serializer") {
                    param("action", "serializers")
                    param("filter", "Order")
                    outcome("Returns OrderSerializer (ModelSerializer, model=Order) and any related serializers like OrderItemSerializer.")
                }
                verdict {
                    keep("Useful for DRF projects — faster than opening every serializers.py for a project-wide inventory.", VerdictSeverity.NORMAL)
                }
            }
            action("forms") {
                description {
                    technical(
                        "Scans forms.py files for Django form class definitions — subclasses of Form and ModelForm. " +
                            "Returns class name, base class, and the model it binds if detectable from Meta.model. Filter applies to class name and app."
                    )
                    plain(
                        "Lists all Django forms — the classes that validate user input in views or the admin. " +
                            "Shows both standalone Form classes and model-bound ModelForm classes."
                    )
                }
                whenLLMUses("When the user asks 'what forms exist', 'how is registration handled', 'is there a ModelForm for Order'. Useful for understanding form-based views before examining views.py.")
                params {
                    optional("filter", "string") {
                        llmSeesIt("Filter results by name/pattern — for models, views, urls, settings, admin, management_commands, celery_tasks, signals, serializers, forms, fixtures, templates")
                        humanReadable("Case-insensitive substring filter on form class name and app name.")
                        whenPresent("Only forms matching the filter are returned.")
                        whenAbsent("All form classes across all apps are returned.")
                        example("Registration")
                        example("OrderForm")
                    }
                }
                precondition("Project must contain forms.py files.")
                onSuccess("Returns form class names grouped by app with base class and model name (for ModelForms).")
                onFailure("no forms.py files found", "Empty result — project may use API-only style (DRF serializers) with no traditional forms.")
                example("find all ModelForms") {
                    param("action", "forms")
                    param("filter", "ModelForm")
                    outcome("Returns all ModelForm subclasses with their bound models.")
                }
                verdict {
                    keep("Low token cost, straightforward value for traditional Django form-based projects.", VerdictSeverity.NORMAL)
                }
            }
            action("fixtures") {
                description {
                    technical(
                        "Scans fixtures/ directories across the project for fixture files (.json, .yaml, .xml). " +
                            "Returns file name, format, and relative path. Filter applies to file name and path."
                    )
                    plain(
                        "Lists all Django fixtures — the seed-data files you load with 'manage.py loaddata'. " +
                            "Shows what fixtures are available and where they live, so you can reference them correctly."
                    )
                }
                whenLLMUses("When the user asks 'what fixtures exist', 'how do I seed the database', 'is there a fixture for test users'. Useful before running manage.py loaddata commands.")
                params {
                    optional("filter", "string") {
                        llmSeesIt("Filter results by name/pattern — for models, views, urls, settings, admin, management_commands, celery_tasks, signals, serializers, forms, fixtures, templates")
                        humanReadable("Case-insensitive substring filter on fixture file name and path.")
                        whenPresent("Only fixtures matching the filter are returned.")
                        whenAbsent("All fixture files across all fixtures/ directories are returned.")
                        example("users")
                        example("initial")
                        example(".json")
                    }
                }
                onSuccess("Returns fixture file names with their format and relative path for use in manage.py loaddata.")
                onFailure("no fixture files found", "Empty result — project may use migrations for initial data or a different seeding approach.")
                example("list all available fixtures") {
                    param("action", "fixtures")
                    outcome("Returns e.g. 'initial_data.json (json) → orders/fixtures/', 'test_users.yaml (yaml) → accounts/fixtures/'.")
                }
                verdict {
                    keep("Fixture discovery is trivially fast and saves the LLM from guessing fixture paths before loaddata calls.", VerdictSeverity.NORMAL)
                }
            }
            action("templates") {
                description {
                    technical(
                        "Scans templates/ directories across the project for template files (.html, .txt, .xml). " +
                            "Returns file name, relative path, and containing app. Filter applies to file name and path."
                    )
                    plain(
                        "Lists all Django template files — what you'd find browsing the templates/ directories in each app. " +
                            "Shows which templates exist so you can reference them correctly in views or extends tags."
                    )
                }
                whenLLMUses("When the user asks 'what templates exist', 'where is the order detail template', 'does a confirmation email template exist'. Useful before editing or creating templates.")
                params {
                    optional("filter", "string") {
                        llmSeesIt("Filter results by name/pattern — for models, views, urls, settings, admin, management_commands, celery_tasks, signals, serializers, forms, fixtures, templates")
                        humanReadable("Case-insensitive substring filter on template file name and path.")
                        whenPresent("Only templates matching the filter are returned.")
                        whenAbsent("All template files across all templates/ directories are returned.")
                        example("order")
                        example("email")
                        example(".txt")
                    }
                }
                onSuccess("Returns template file names with relative paths grouped by containing app or template directory.")
                onFailure("no template files found", "Empty result — project may be API-only (no server-rendered templates).")
                example("find all email templates") {
                    param("action", "templates")
                    param("filter", "email")
                    outcome("Returns e.g. 'order_confirmation_email.html → orders/templates/', 'welcome_email.txt → accounts/templates/'.")
                }
                verdict {
                    keep("Template enumeration is a quick orientation step that prevents the LLM from guessing paths when writing view code or extend chains.", VerdictSeverity.NORMAL)
                }
            }
            action("version_info") {
                description {
                    technical(
                        "Reads Django version from requirements*.txt (via REQUIREMENTS_DJANGO_PATTERN) and pyproject.toml (via PYPROJECT_DJANGO_PATTERN). " +
                            "Reads INSTALLED_APPS from settings*.py to extract project app names (excludes 'django.*' and 'rest_framework' builtins). " +
                            "Takes no filter parameter."
                    )
                    plain(
                        "Tells you what Django version the project uses and which project apps are installed — like reading the requirements.txt and settings.py INSTALLED_APPS together. " +
                            "Useful before recommending Django-version-specific APIs."
                    )
                }
                whenLLMUses("When the user asks 'what Django version is this', 'what apps are installed', or before recommending a feature that was added in a specific Django version (e.g., async views, streaming responses).")
                onSuccess("Returns Django version string (from requirements/pyproject) and sorted list of project app names (excluding django.* and rest_framework builtins).")
                onFailure("Django version not found in requirements files", "Returns 'Django version: not found in requirements files' — still returns the app list if settings files exist.")
                onFailure("no project apps found in INSTALLED_APPS", "Returns 'No project apps found in INSTALLED_APPS.' — may indicate settings.py not parsed correctly or non-standard app layout.")
                example("check Django version and installed apps") {
                    param("action", "version_info")
                    outcome("Returns 'Django version: 4.2.7' and 'Project apps (5): accounts, orders, payments, products, shipping'.")
                }
                verdict {
                    keep("Zero-param, combines two separate file reads (requirements + settings) into one result. Essential before making version-gated API recommendations.", VerdictSeverity.NORMAL)
                }
            }
        }
        observation("12 of 14 actions accept a `filter` param; only `middleware` and `version_info` do not. This is correct by design — middleware order must be preserved in full, and version_info reads structured files with no natural filter dimension.")
        observation("The tool comment says 'replacing 13 individual Django analysis tools' but the dispatcher has 14 actions. The 14-action count is correct — the comment is off by one.")
        mergeOpportunity("`serializers` and `forms` are structurally identical scanner actions (scan *.py by file name, return class/base/model). They could be merged into a single `class_inventory` action with a `file` param — but the current per-action naming improves LLM prompt clarity over a generic action.")
    }
}

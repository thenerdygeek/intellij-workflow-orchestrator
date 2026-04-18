# Python Ecosystem Tools — Implementation Plan (Plan C)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Django, FastAPI, and Flask framework meta-tools, Python build/package tools (pip/poetry/uv), pytest integration, Python debug support, and a `python-engineer` bundled agent persona.

**Architecture:** Each framework tool follows the existing `SpringTool` pattern — a thin dispatcher class with individual action files in a subdirectory. All Python PSI access via reflection through `PythonPsiHelper`. Framework tools are conditionally registered (deferred, promoted to core when detected). Build tool actions are added to the existing `BuildTool` meta-tool. Debug tools extend the existing debug infrastructure with Python-specific capabilities.

**Tech Stack:** Kotlin 2.1.10, Python PSI via reflection (`PythonPsiHelper`), IntelliJ Platform SDK, JUnit 5 + MockK

**Depends on:** Plan A (IdeContext, ToolRegistrationFilter), Plan B1 (provider infrastructure), Plan B2 (PythonProvider, PythonPsiHelper)

**Research:** `docs/research/2026-04-13-python-framework-tools-research.md` — implementation approaches for all actions

---

## Scope

Plan C is large. It can be executed in phases — each phase is independently shippable:

| Phase | What | Tools/Actions |
|---|---|---|
| C1 | Django meta-tool | 15 actions |
| C2 | FastAPI meta-tool | 10 actions |
| C3 | Flask meta-tool | 10 actions |
| C4 | Python build tools | pip/poetry/uv actions in `build` meta-tool |
| C5 | Python debug tools | Basic + advanced debug for Python |
| C6 | Python persona + registration wiring | `python-engineer` agent, framework promotion |

Each phase is a separate task below. The implementing session should commit after each phase.

---

## File Map

| Action | File | Purpose |
|---|---|---|
| Create | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/DjangoTool.kt` | Django meta-tool dispatcher (15 actions) |
| Create | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/django/` | 15 action files + DjangoHelpers.kt |
| Create | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/FastApiTool.kt` | FastAPI meta-tool dispatcher (10 actions) |
| Create | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/fastapi/` | 10 action files + FastApiHelpers.kt |
| Create | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/FlaskTool.kt` | Flask meta-tool dispatcher (10 actions) |
| Create | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/flask/` | 10 action files + FlaskHelpers.kt |
| Create | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/python/` | Shared Python framework utilities (requirement parsing, manage.py execution) |
| Create | `agent/src/main/resources/agents/python-engineer.md` | Bundled Python specialist agent persona |
| Modify | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/BuildTool.kt` | Add pip/poetry/uv actions |
| Modify | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/build/` | New Python build action files |
| Modify | `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt` | Register Django/FastAPI/Flask/Python build/debug tools |
| Create | `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/framework/DjangoToolTest.kt` | Django tool tests |
| Create | `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/framework/FastApiToolTest.kt` | FastAPI tool tests |
| Create | `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/framework/FlaskToolTest.kt` | Flask tool tests |

---

## Task 1 (Phase C1): Django Meta-Tool

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/DjangoTool.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/django/` (16 files)
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/framework/DjangoToolTest.kt`

- [ ] **Step 1: Create DjangoTool dispatcher**

Follow the `SpringTool` pattern exactly. Create `DjangoTool.kt`:

```kotlin
package com.workflow.orchestrator.agent.tools.framework

class DjangoTool : AgentTool {
    override val name = "django"
    override val description = """
Django framework intelligence — models, URLs, views, migrations, settings, templates, admin.

Actions and their parameters:
- models(app?, model?) → Django model definitions, fields, relationships
- urls(app?, pattern?) → URL patterns, views, names, middleware
- views(app?, view?) → Views/viewsets, HTTP methods, permissions
- migrations(app?) → Migration status, pending migrations
- settings(key?) → Settings values (DATABASES, INSTALLED_APPS, MIDDLEWARE, etc.)
- management_commands(app?) → Available manage.py commands
- templates(app?) → Template directory structure, tags/filters
- admin(app?, model?) → Registered admin classes, inlines, list_display
- serializers(app?) → DRF serializers, fields, validators (if DRF installed)
- signals(app?) → Registered signal handlers
- celery_tasks(app?) → Celery task definitions (if celery installed)
- middleware() → Middleware stack in order
- forms(app?, form?) → Form classes, fields, validation
- fixtures(app?) → Available fixture files
- version_info() → Django version, installed packages
""".trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(
                type = "string",
                description = "Operation to perform",
                enumValues = listOf(
                    "models", "urls", "views", "migrations", "settings",
                    "management_commands", "templates", "admin", "serializers",
                    "signals", "celery_tasks", "middleware", "forms", "fixtures",
                    "version_info"
                )
            ),
            "app" to ParameterProperty(type = "string", description = "Django app name to filter by"),
            "model" to ParameterProperty(type = "string", description = "Model class name to inspect"),
            "view" to ParameterProperty(type = "string", description = "View class/function name"),
            "form" to ParameterProperty(type = "string", description = "Form class name"),
            "pattern" to ParameterProperty(type = "string", description = "URL pattern to filter"),
            "key" to ParameterProperty(type = "string", description = "Settings key to look up"),
        ),
        required = listOf("action")
    )

    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ANALYZER, WorkerType.REVIEWER, WorkerType.ORCHESTRATOR, WorkerType.CODER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        coroutineContext.ensureActive()
        val action = params["action"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'action' parameter required", "Error: missing action", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        return when (action) {
            "models" -> executeModels(params, project)
            "urls" -> executeUrls(params, project)
            "views" -> executeViews(params, project)
            "migrations" -> executeMigrations(params, project)
            "settings" -> executeSettings(params, project)
            "management_commands" -> executeManagementCommands(params, project)
            "templates" -> executeTemplates(params, project)
            "admin" -> executeAdmin(params, project)
            "serializers" -> executeSerializers(params, project)
            "signals" -> executeSignals(params, project)
            "celery_tasks" -> executeCeleryTasks(params, project)
            "middleware" -> executeMiddleware(params, project)
            "forms" -> executeForms(params, project)
            "fixtures" -> executeFixtures(params, project)
            "version_info" -> executeVersionInfo(params, project)
            else -> ToolResult("Unknown action: $action", "Error: unknown action '$action'", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }
}
```

- [ ] **Step 2: Create DjangoHelpers.kt**

Shared utilities for Django actions. Create `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/django/DjangoHelpers.kt`:

```kotlin
package com.workflow.orchestrator.agent.tools.framework.django

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.workflow.orchestrator.agent.ide.PythonPsiHelper

/**
 * Shared utilities for Django tool actions.
 *
 * Implementation approach (from research):
 * - Primary: Python PSI via PythonPsiHelper (parse model classes, decorators, imports)
 * - Fallback: File scanning (regex-based, for when PSI is unavailable)
 * - Runtime: manage.py commands (for migrations, management_commands)
 */
object DjangoHelpers {

    val psi = PythonPsiHelper()

    /** Find all Django apps by scanning for apps.py files */
    fun findApps(project: Project): List<DjangoApp> { /* ... */ }

    /** Find models.py files across all apps */
    fun findModelFiles(project: Project, appFilter: String?): List<VirtualFile> { /* ... */ }

    /** Check if a PyClass is a Django model (extends models.Model) */
    fun isDjangoModel(element: Any): Boolean { /* check superclasses for "Model" */ }

    /** Check if a PyClass is a DRF serializer (extends Serializer/ModelSerializer) */
    fun isDrfSerializer(element: Any): Boolean { /* check superclasses */ }

    /** Parse a requirements file for a package version */
    fun getPackageVersion(project: Project, packageName: String): String? { /* ... */ }

    /** Run a manage.py command and return output */
    suspend fun runManagePy(project: Project, args: List<String>, timeoutMs: Long = 10_000): String? { /* ... */ }
}

data class DjangoApp(val name: String, val path: String, val label: String?)
```

- [ ] **Step 3: Implement each action file**

Create one file per action in `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/django/`:

| File | Action | Implementation approach |
|---|---|---|
| `DjangoModelsAction.kt` | `models` | PSI: find PyClass subclassing `models.Model`, extract fields (PyTargetExpression with `models.*Field` calls), relationships (ForeignKey, ManyToManyField), Meta class |
| `DjangoUrlsAction.kt` | `urls` | PSI: parse `urlpatterns` list in urls.py, resolve `path()`, `re_path()`, `include()` calls. File-scan fallback for complex includes. |
| `DjangoViewsAction.kt` | `views` | PSI: find functions decorated with `@api_view` / classes extending `View`/`APIView`/`ViewSet`. Extract HTTP method decorators. |
| `DjangoMigrationsAction.kt` | `migrations` | Runtime: `manage.py showmigrations` for status. File-scan: read migration directories for graph. |
| `DjangoSettingsAction.kt` | `settings` | File-scan: read settings.py/settings/*.py, parse key=value assignments. Handle split settings. |
| `DjangoManagementCommandsAction.kt` | `management_commands` | File-scan: walk `management/commands/` directories across all apps |
| `DjangoTemplatesAction.kt` | `templates` | File-scan: read TEMPLATES setting for dirs, walk template directories |
| `DjangoAdminAction.kt` | `admin` | PSI: find classes extending `admin.ModelAdmin`, extract `list_display`, `list_filter`, inlines |
| `DjangoSerializersAction.kt` | `serializers` | PSI: find classes extending `Serializer`/`ModelSerializer`, extract fields. Only if DRF detected in deps. |
| `DjangoSignalsAction.kt` | `signals` | PSI: find `@receiver` decorators, parse signal names + sender args. Check apps.py `ready()`. |
| `DjangoCeleryTasksAction.kt` | `celery_tasks` | PSI: find `@shared_task`/`@app.task` decorators. Only if celery in deps. |
| `DjangoMiddlewareAction.kt` | `middleware` | File-scan: read MIDDLEWARE setting from settings.py, list in order |
| `DjangoFormsAction.kt` | `forms` | PSI: find classes extending `forms.Form`/`forms.ModelForm`, extract fields |
| `DjangoFixturesAction.kt` | `fixtures` | File-scan: walk `fixtures/` directories across apps |
| `DjangoVersionInfoAction.kt` | `version_info` | File-scan: parse requirements.txt/pyproject.toml for django version + related packages |

Each action file follows the same signature pattern as Spring actions:
```kotlin
suspend fun executeModels(params: JsonObject, project: Project): ToolResult { /* ... */ }
```

- [ ] **Step 4: Write tests**

```kotlin
// agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/framework/DjangoToolTest.kt
class DjangoToolTest {
    @Test
    fun `tool name is django`() {
        val tool = DjangoTool()
        assertEquals("django", tool.name)
    }

    @Test
    fun `all 15 actions are in enum list`() {
        val tool = DjangoTool()
        val actions = tool.parameters.properties["action"]?.enumValues ?: emptyList()
        assertEquals(15, actions.size)
        assertTrue("models" in actions)
        assertTrue("urls" in actions)
        assertTrue("views" in actions)
        assertTrue("migrations" in actions)
        assertTrue("settings" in actions)
        // ... verify all 15
    }

    @Test
    fun `unknown action returns error`() = runTest {
        val tool = DjangoTool()
        val params = buildJsonObject { put("action", "nonexistent") }
        val result = tool.execute(params, mockk())
        assertTrue(result.isError)
    }
}
```

- [ ] **Step 5: Run tests**

Run: `./gradlew :agent:test --tests "*DjangoToolTest*" -v`
Expected: ALL PASS

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/DjangoTool.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/django/ \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/framework/DjangoToolTest.kt
git commit -m "feat(agent): add Django meta-tool with 15 actions

PSI-primary, file-fallback, runtime-optional Django framework intelligence.
Actions: models, urls, views, migrations, settings, management_commands,
templates, admin, serializers, signals, celery_tasks, middleware, forms,
fixtures, version_info."
```

---

## Task 2 (Phase C2): FastAPI Meta-Tool

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/FastApiTool.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/fastapi/` (11 files)
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/framework/FastApiToolTest.kt`

- [ ] **Step 1: Create FastApiTool dispatcher**

Same pattern as DjangoTool. Name: `"fastapi"`. 10 actions: `routes`, `dependencies`, `models`, `middleware`, `security`, `background_tasks`, `events`, `config`, `database`, `version_info`.

Parameters: `action` (required), `path` (filter by route path), `model` (Pydantic model name), `class_name` (config/dependency class).

- [ ] **Step 2: Create FastApiHelpers.kt**

Utilities for detecting FastAPI patterns: `@app.get`/`@router.post` decorators, `Depends()` chains, `BaseModel` subclasses, `BaseSettings` subclasses.

- [ ] **Step 3: Implement each action file**

| File | Action | Implementation approach |
|---|---|---|
| `FastApiRoutesAction.kt` | `routes` | PSI: find functions with `@app.get`/`@app.post`/`@router.*` decorators. Extract path, HTTP method, response_model from decorator args. |
| `FastApiDependenciesAction.kt` | `dependencies` | PSI: find `Depends()` calls in function parameters. Build dependency tree by tracing nested Depends. |
| `FastApiModelsAction.kt` | `models` | PSI: find PyClass extending `BaseModel`. Extract fields (PyTargetExpression with type annotations). |
| `FastApiMiddlewareAction.kt` | `middleware` | PSI: find `app.add_middleware()` calls. Extract middleware class and kwargs. |
| `FastApiSecurityAction.kt` | `security` | PSI: find `OAuth2PasswordBearer`, `APIKeyHeader`, etc. instances. |
| `FastApiBackgroundTasksAction.kt` | `background_tasks` | PSI: find functions accepting `BackgroundTasks` parameter. |
| `FastApiEventsAction.kt` | `events` | PSI: find `@app.on_event("startup"/"shutdown")` decorators or lifespan context managers. |
| `FastApiConfigAction.kt` | `config` | PSI: find PyClass extending `BaseSettings`. Extract fields with defaults and env var names. |
| `FastApiDatabaseAction.kt` | `database` | PSI: find SQLAlchemy `Base.metadata` subclasses or Tortoise model definitions. |
| `FastApiVersionInfoAction.kt` | `version_info` | File-scan: parse deps for fastapi/uvicorn/pydantic versions. |

- [ ] **Step 4: Write tests**

Same pattern as DjangoToolTest — verify tool name, action count, unknown action error.

- [ ] **Step 5: Run tests and commit**

Run: `./gradlew :agent:test --tests "*FastApiToolTest*" -v`

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/FastApiTool.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/fastapi/ \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/framework/FastApiToolTest.kt
git commit -m "feat(agent): add FastAPI meta-tool with 10 actions

Routes, dependencies, Pydantic models, middleware, security, background
tasks, events, config (BaseSettings), database (SQLAlchemy/Tortoise),
version info."
```

---

## Task 3 (Phase C3): Flask Meta-Tool

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/FlaskTool.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/flask/` (11 files)
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/framework/FlaskToolTest.kt`

- [ ] **Step 1: Create FlaskTool dispatcher**

Name: `"flask"`. 10 actions: `routes`, `blueprints`, `config`, `extensions`, `models`, `templates`, `middleware`, `cli_commands`, `forms`, `version_info`.

Parameters: `action` (required), `blueprint` (filter by blueprint name), `extension` (extension name), `model` (SQLAlchemy model name).

- [ ] **Step 2: Create FlaskHelpers.kt and action files**

| File | Action | Implementation approach |
|---|---|---|
| `FlaskRoutesAction.kt` | `routes` | PSI: find `@app.route`/`@blueprint.route` decorators. Extract path, methods, endpoint name. |
| `FlaskBlueprintsAction.kt` | `blueprints` | PSI: find `Blueprint()` constructor calls. Trace `app.register_blueprint()` for prefix/url_prefix. |
| `FlaskConfigAction.kt` | `config` | File-scan: read config.py or `app.config.from_object()` target. |
| `FlaskExtensionsAction.kt` | `extensions` | PSI: find `ext = Extension(app)` or `ext.init_app(app)` patterns. Identify known extensions. |
| `FlaskModelsAction.kt` | `models` | PSI: find PyClass extending `db.Model` (Flask-SQLAlchemy). |
| `FlaskTemplatesAction.kt` | `templates` | File-scan: walk `templates/` directory. List Jinja2 macros and filters. |
| `FlaskMiddlewareAction.kt` | `middleware` | PSI: find `@app.before_request`, `@app.after_request`, `@app.errorhandler` decorators. |
| `FlaskCliCommandsAction.kt` | `cli_commands` | PSI: find `@app.cli.command()` or `@click.command()` decorators. |
| `FlaskFormsAction.kt` | `forms` | PSI: find PyClass extending `FlaskForm`/`Form` (Flask-WTF). |
| `FlaskVersionInfoAction.kt` | `version_info` | File-scan: parse deps for flask/werkzeug/jinja2 versions. |

- [ ] **Step 3: Write tests and commit**

Same pattern. Run: `./gradlew :agent:test --tests "*FlaskToolTest*" -v`

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/FlaskTool.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/flask/ \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/framework/FlaskToolTest.kt
git commit -m "feat(agent): add Flask meta-tool with 10 actions

Routes, blueprints, config, extensions, SQLAlchemy models, Jinja2
templates, before/after request handlers, Click CLI commands, WTForms,
version info."
```

---

## Task 4 (Phase C4): Python Build/Package Tools

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/BuildTool.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/build/PipActions.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/build/PoetryActions.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/build/UvActions.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/build/PytestActions.kt`

- [ ] **Step 1: Add Python build actions to BuildTool**

Add new actions to the existing `BuildTool.kt` dispatcher. The actions should be conditional — only appear in the tool description when Python build tools are detected.

New actions:
```
- pip_list() → Installed pip packages
- pip_outdated() → Packages with newer versions available
- pip_show(package) → Package details
- pip_dependencies(package?) → Dependency tree
- poetry_list() → Poetry-managed packages
- poetry_outdated() → Outdated packages
- poetry_show(package) → Package details
- poetry_lock_status() → Lock file status
- poetry_scripts() → Available scripts
- uv_list() → uv-managed packages
- uv_outdated() → Outdated packages
- uv_lock_status() → Lock file status
- pytest_discover(path?) → Discover test files/functions
- pytest_run(path?, pattern?, markers?) → Run pytest
- pytest_fixtures() → Available fixtures
```

Implementation: All actions delegate to `run_command` with the appropriate CLI tool (`pip`, `poetry`, `uv`, `pytest`). Parse output into structured results.

- [ ] **Step 2: Write tests**

Verify new actions are in the enum list, unknown action error handling, parameter validation.

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/BuildTool.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/build/
git commit -m "feat(agent): add pip/poetry/uv/pytest actions to build meta-tool

Python build/package management actions alongside existing Maven/Gradle
actions. Conditional on detected build tools in IdeContext."
```

---

## Task 5 (Phase C5): Python Debug Tools

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt` (debug registration)
- Create or modify debug tool files as needed for Python support

- [ ] **Step 1: Extend debug tools for Python**

The existing debug tools use `XDebugger` API (platform) + Java-specific extensions. For Python:

**Basic debug (Community):**
- Line breakpoints, conditional breakpoints, exception breakpoints → already work via `XDebugger` API (platform)
- Step in/over/out, resume, pause → already work via `XDebugger` API
- Evaluate expression, get variables, stack frames → already work via `XDebugger` API

The `XDebugger` API is language-agnostic. The existing debug tools should largely work for Python debugging sessions without modification, since `PyDebugProcess` extends `XDebugProcess`.

**What needs to change:**
- `debug_breakpoints` `start_session` action: currently creates a Java/Kotlin run configuration. Need to support Python run configurations when in PyCharm.
- `debug_inspect` `hotswap` action: Not applicable for Python — should return "hotswap not supported for Python".
- `debug_inspect` `drop_frame` action: Limited in Python — should note this.

**Advanced debug (Professional only):**
- Django template debugging → New action, only in Professional
- Remote interpreter debugging → New action, only in Professional
- Attach to process → New action, Professional only

- [ ] **Step 2: Register Python debug tools**

In `AgentService.kt`:

```kotlin
// Python debug tools — basic (Community)
if (ToolRegistrationFilter.shouldRegisterPythonDebugTools(ideContext)) {
    // Basic debug uses same tools as Java — XDebugger is platform-level
    // Only register if Java debug tools are NOT already registered (avoid duplicates)
    if (!ToolRegistrationFilter.shouldRegisterJavaDebugTools(ideContext)) {
        registerDebugTools()  // Same tools, Python debug session via XDebugger
    }
}

// Python advanced debug (Professional only)
if (ToolRegistrationFilter.shouldRegisterPythonAdvancedDebugTools(ideContext)) {
    // TODO: Register Django template debug, remote interpreter, attach-to-process
    log.info("Python advanced debug tools available (Professional edition)")
}
```

- [ ] **Step 3: Write tests and commit**

```bash
git commit -m "feat(agent): extend debug tools for Python support

XDebugger-based debug tools work for Python without modification.
Guard hotswap/drop_frame for Python. Register debug tools when
either Java or Python is available."
```

---

## Task 6 (Phase C6): Python Persona + Registration Wiring

**Files:**
- Create: `agent/src/main/resources/agents/python-engineer.md`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`

- [ ] **Step 1: Create python-engineer persona**

```markdown
---
name: python-engineer
description: Python expert — Django, FastAPI, Flask, pytest, async patterns, type hints
tools: [read_file, edit_file, create_file, search_code, glob_files, run_command,
        find_definition, find_references, diagnostics, django, fastapi, flask,
        build, debug_breakpoints, debug_step, debug_inspect, db_query, db_schema]
max-turns: 15
---

You are a senior Python engineer specializing in web frameworks and modern Python patterns.

## Expertise
- Django (ORM, views, DRF, Celery, signals, middleware, management commands)
- FastAPI (async routes, Depends injection, Pydantic, BackgroundTasks)
- Flask (Blueprints, extensions, Jinja2, Flask-SQLAlchemy)
- pytest (fixtures, parametrize, markers, conftest, mocking)
- Python packaging (pip, poetry, uv, pyproject.toml)
- Type hints (typing module, Protocol, TypeVar, Annotated)
- Async Python (asyncio, aiohttp, async generators)

## Approach
1. Always read the existing code before modifying
2. Use the `django`/`fastapi`/`flask` tools to understand the project structure
3. Follow existing project patterns (check imports, decorators, base classes)
4. Write pytest tests for new code
5. Use type hints consistently
6. Prefer explicit over implicit (PEP 20)
```

- [ ] **Step 2: Wire Django/FastAPI/Flask tools into AgentService**

In `AgentService.registerAllTools()`, after the Spring tool registration:

```kotlin
// Python framework tools — conditionally registered and promoted if detected
if (ToolRegistrationFilter.shouldRegisterDjangoTools(ideContext)) {
    if (ToolRegistrationFilter.shouldPromoteFrameworkTool(ideContext, Framework.DJANGO)) {
        safeRegisterCore { DjangoTool() }
        log.info("Django tool promoted to core (framework detected in project)")
    } else {
        safeRegisterDeferred("Framework") { DjangoTool() }
    }
}

if (ToolRegistrationFilter.shouldRegisterFastApiTools(ideContext)) {
    if (ToolRegistrationFilter.shouldPromoteFrameworkTool(ideContext, Framework.FASTAPI)) {
        safeRegisterCore { FastApiTool() }
        log.info("FastAPI tool promoted to core (framework detected in project)")
    } else {
        safeRegisterDeferred("Framework") { FastApiTool() }
    }
}

if (ToolRegistrationFilter.shouldRegisterFlaskTools(ideContext)) {
    if (ToolRegistrationFilter.shouldPromoteFrameworkTool(ideContext, Framework.FLASK)) {
        safeRegisterCore { FlaskTool() }
        log.info("Flask tool promoted to core (framework detected in project)")
    } else {
        safeRegisterDeferred("Framework") { FlaskTool() }
    }
}

// Also promote Spring if detected (existing tool, new promotion logic)
if (ToolRegistrationFilter.shouldRegisterSpringTools(ideContext)) {
    if (ToolRegistrationFilter.shouldPromoteFrameworkTool(ideContext, Framework.SPRING)) {
        safeRegisterCore { SpringTool() }
        log.info("Spring tool promoted to core (framework detected in project)")
    } else {
        safeRegisterDeferred("Build & Run") { SpringTool() }
    }
}

// Python build tools (pip/poetry/uv actions in build meta-tool)
if (ToolRegistrationFilter.shouldRegisterPythonBuildTools(ideContext)) {
    // BuildTool is already registered if Java build tools are present
    // If only Python, register it now
    if (!ToolRegistrationFilter.shouldRegisterJavaBuildTools(ideContext)) {
        safeRegisterDeferred("Build & Run") { BuildTool() }
    }
    // BuildTool internally checks IdeContext for which actions to expose
}
```

- [ ] **Step 3: Run full test suite**

Run: `./gradlew :agent:test -v`
Expected: ALL PASS

- [ ] **Step 4: Run full build**

Run: `./gradlew clean buildPlugin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Update documentation**

Update `agent/CLAUDE.md` with Python ecosystem tools section.

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/resources/agents/python-engineer.md \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt \
        agent/CLAUDE.md
git commit -m "feat(agent): add python-engineer persona, wire framework tool registration

Bundled python-engineer specialist agent. Django/FastAPI/Flask tools
conditionally registered and promoted to core when framework detected.
Spring tool also promoted when detected. Build tool registers for
Python when Java build tools not present."
```

---

## Summary

After Plan C:

| What changed | Result |
|---|---|
| Django tool | 15 actions — models, urls, views, migrations, settings, etc. |
| FastAPI tool | 10 actions — routes, dependencies, Pydantic models, etc. |
| Flask tool | 10 actions — routes, blueprints, config, extensions, etc. |
| Build tool | pip/poetry/uv/pytest actions alongside Maven/Gradle |
| Debug tools | Python debug via XDebugger (basic), Professional-only advanced |
| python-engineer | Bundled specialist persona for Python projects |
| Framework promotion | Detected frameworks promoted from deferred to core |
| Total new actions | ~50 across 3 framework tools + ~15 build actions |

**What comes next:**
- **Plan D:** Modular system prompt + skill variants + deferred tool discovery

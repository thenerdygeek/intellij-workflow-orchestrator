# Python Framework Meta-Tools Research: Django, FastAPI, Flask

**Date:** 2026-04-13
**Purpose:** Implementation approach for Python framework meta-tools in IntelliJ/PyCharm plugin AI agent
**Context:** Follows the existing `SpringTool` (15 actions) and `BuildTool` (11 actions) meta-tool pattern

---

## Table of Contents

1. [Architecture Decision: PSI vs Runtime vs File Scanning](#1-architecture-decision)
2. [Python PSI API in IntelliJ/PyCharm](#2-python-psi-api)
3. [PyCharm Community vs Professional](#3-community-vs-professional)
4. [Django Meta-Tool (~15 actions)](#4-django-meta-tool)
5. [FastAPI Meta-Tool (~10 actions)](#5-fastapi-meta-tool)
6. [Flask Meta-Tool (~10 actions)](#6-flask-meta-tool)
7. [Framework Detection Strategy](#7-framework-detection-strategy)
8. [Registration & Conditional Loading](#8-registration-and-conditional-loading)
9. [Implementation Plan](#9-implementation-plan)

---

## 1. Architecture Decision: PSI vs Runtime vs File Scanning {#1-architecture-decision}

### Three Extraction Strategies

| Strategy | Pros | Cons | Best For |
|----------|------|------|----------|
| **Python PSI** (IntelliJ API) | No interpreter needed, fast, IDE-integrated, works in dumb mode partially | Requires Python plugin, reflection-based access, limited type resolution | Class/function/decorator discovery, static analysis |
| **Runtime** (`manage.py`, `python -c`) | Complete info, resolves dynamic patterns | Requires working interpreter, slow, may fail, side effects | Migration status, URL resolution, `inspectdb` |
| **File scanning** (VFS/IO) | No dependencies, fast, always works | Can't resolve dynamic imports, no type info | Config files, directory structure, fixtures |

### Decision: Hybrid Approach (PSI-Primary, File-Fallback, Runtime-Optional)

Following the existing `SpringTool` pattern:
- **PSI-first** for class/function/decorator discovery (like `SpringEndpointsAction` uses `AnnotatedElementsSearch`)
- **File scanning** for configuration, templates, management commands, fixtures
- **Runtime** only for `manage.py` operations (migrations, inspectdb) — opt-in, requires configured interpreter

### Key Insight from SpringTool

The `SpringContextAction` uses **reflection** to access the Spring plugin's `SpringManager` API without a compile-time dependency:

```kotlin
val springManagerClass = Class.forName("com.intellij.spring.SpringManager")
val getInstance = springManagerClass.getMethod("getInstance", Project::class.java)
```

We need the same pattern for accessing `com.jetbrains.python.psi.*` classes, since the Python plugin is an optional dependency.

---

## 2. Python PSI API in IntelliJ/PyCharm {#2-python-psi-api}

### Core Python PSI Classes

All in package `com.jetbrains.python.psi`:

| Class | Description | Key Methods |
|-------|-------------|-------------|
| `PyFile` | Python file (`.py`) | `getTopLevelClasses()`, `getTopLevelFunctions()`, `getImportBlock()`, `getStatements()` |
| `PyClass` | Python class | `getName()`, `getSuperClassExpressions()`, `getMethods()`, `getClassAttributes()`, `getInstanceAttributes()`, `getDecoratorList()`, `getSuperClasses()` |
| `PyFunction` | Function/method | `getName()`, `getParameterList()`, `getDecoratorList()`, `getAnnotation()`, `getReturnStatementType()` |
| `PyDecorator` | `@decorator` | `getName()`, `getQualifiedName()`, `getArgumentList()`, `hasArgumentList()` |
| `PyDecoratorList` | List of decorators | `getDecorators()` |
| `PyParameter` | Function parameter | `getName()`, `getDefaultValue()`, `getAnnotation()`, `hasDefaultValue()` |
| `PyAssignmentStatement` | `x = value` | `getTargets()`, `getAssignedValue()` |
| `PyCallExpression` | `func(args)` | `getCallee()`, `getArguments()`, `getArgumentList()` |
| `PyStringLiteralExpression` | String literal | `getStringValue()` |
| `PyImportStatement` | `import x` | `getImportElements()`, `getFullyQualifiedObjectNames()` |
| `PyFromImportStatement` | `from x import y` | `getImportSource()`, `getImportElements()` |
| `PyTargetExpression` | Assignment target | `getName()`, `getQualifiedName()` |
| `PyReferenceExpression` | Name reference | `getName()`, `getReference()`, `resolve()` |

### Accessing Python PSI via Reflection

Since the agent module cannot have a compile-time dependency on the Python plugin, all access must be reflective. Pattern from existing `PsiToolUtils.formatKotlinFileStructure()`:

```kotlin
// Check if a PsiFile is a Python file
val pyFileClass = Class.forName("com.jetbrains.python.psi.PyFile")
if (pyFileClass.isInstance(psiFile)) {
    val getTopLevelClasses = pyFileClass.getMethod("getTopLevelClasses")
    val classes = getTopLevelClasses.invoke(psiFile) as List<*>
    // ...
}
```

### Finding Python Classes by Superclass

Unlike Java's `AnnotatedElementsSearch` (which searches annotations), Python uses:

1. **`PyClassInheritorsSearch`** — finds subclasses of a given base class
   - FQN: `com.jetbrains.python.psi.search.PyClassInheritorsSearch`
   - Usage: `PyClassInheritorsSearch.search(baseClass, scope)`

2. **`PsiShortNamesCache`** — find classes by short name (works for Python too)
   - Already used in `PsiToolUtils.findClassAnywhere()`

3. **`StubIndex`** — direct index queries
   - `PyClassAttributesIndex`, `PySuperClassIndex`, `PyFunctionNameIndex`, etc.

### Finding Python Classes by Decorator

Python decorators are not Java annotations — there is no `AnnotatedElementsSearch` equivalent. Instead:

**Approach A — Scan files and check decorators (recommended):**
```kotlin
// Pseudocode using reflection
val pyFileClass = Class.forName("com.jetbrains.python.psi.PyFile")
val pyClassClass = Class.forName("com.jetbrains.python.psi.PyClass")
val pyDecoratorListClass = Class.forName("com.jetbrains.python.psi.PyDecoratorList")

// For each Python file in project scope, get top-level classes
// For each class, check decoratorList for target decorator names
```

**Approach B — Use PyFunctionNameIndex + StubIndex:**
```kotlin
// com.jetbrains.python.psi.stubs.PyDecoratorStubIndex
// Can query by decorator name directly from stubs (fast, no file parsing)
```

**Approach C — File-based regex scan (fallback):**
```kotlin
// Scan .py files for patterns like:
// @app.route(...), @router.get(...), class FooModel(models.Model):
// Fast but imprecise — use when PSI is unavailable
```

### Key Extension Points

From `intellij.python.psi.xml`:
- `Pythonid.pyClassInheritorsSearch` — `QueryExecutor` for subclass search
- `Pythonid.pyClassMembersProvider` — class member enumeration
- `Pythonid.pyModuleMembersProvider` — module-level member enumeration
- `Pythonid.importResolver` — import resolution
- `Pythonid.typeProvider` — type inference

### PyCharm Django Support APIs (Professional Only)

PyCharm Professional has built-in Django support (`com.intellij.django` plugin ID):
- `DjangoFacet` — detects Django projects, knows settings module
- `DjangoTemplateTagsIndex` — template tag discovery
- `DjangoUrlResolverReference` — URL pattern resolution
- `DjangoSettings` — parsed settings.py
- `DjangoAdminReference` — admin site registration

These are **Professional-only** and should be accessed via reflection with graceful fallback.

---

## 3. PyCharm Community vs Professional {#3-community-vs-professional}

| Feature | Community | Professional |
|---------|-----------|--------------|
| Python PSI (`PyFile`, `PyClass`, `PyFunction`, etc.) | Yes | Yes |
| `PyClassInheritorsSearch` | Yes | Yes |
| Stub indexes (`PyDecoratorStubIndex`, etc.) | Yes | Yes |
| Django facet/framework support | No | Yes |
| Flask/FastAPI framework support | No | Yes |
| Endpoints tool window | No | Yes |
| Template language support (Jinja2, DTL) | Limited | Full |
| Database tools (for model inspection) | No | Yes |

**Strategy:** All our tools must work with Community Edition (PSI + file scanning). Professional features are used as optional enhancements via reflection.

### Plugin Dependencies

```xml
<!-- plugin.xml -->
<depends optional="true" config-file="python-support.xml">com.intellij.modules.python</depends>
<!-- For Professional-only Django support -->
<depends optional="true" config-file="django-support.xml">com.intellij.django</depends>
```

---

## 4. Django Meta-Tool (~15 actions) {#4-django-meta-tool}

### Tool Registration

```kotlin
class DjangoTool : AgentTool {
    override val name = "django"
    // Registered as deferred, category "Python Frameworks"
}
```

### Action: `models` — List All Django Models

**What to extract:** Model name, fields (name, type, constraints), relationships (FK, M2M, O2O), Meta options (db_table, ordering, verbose_name), abstract flag, custom managers.

**Implementation approach: PSI (primary) + File scan (fallback)**

**PSI approach:**
1. Find the base class `django.db.models.Model` via `JavaPsiFacade` (works for Python too via qualified name resolution)
2. Use `PyClassInheritorsSearch.search(modelBaseClass, projectScope)` to find all subclasses
3. For each model class:
   - `getClassAttributes()` to get field assignments
   - Parse field type from the assignment value (e.g., `models.CharField(max_length=200)`)
   - Check for `class Meta:` inner class
   - Check for relationship fields (`ForeignKey`, `ManyToManyField`, `OneToOneField`)

**Reflection-based implementation:**
```kotlin
internal suspend fun executeModels(params: JsonObject, project: Project): ToolResult {
    if (PsiToolUtils.isDumb(project)) return PsiToolUtils.dumbModeError()

    return try {
        ReadAction.nonBlocking<String> {
            val scope = GlobalSearchScope.projectScope(project)
            val pyClassSearch = Class.forName("com.jetbrains.python.psi.search.PyClassInheritorsSearch")
            // Find django.db.models.Model base class
            // Search for all inheritors
            // Extract fields, relationships, Meta
            collectDjangoModels(project, scope, filter)
        }.inSmartMode(project).executeSynchronously()
    } catch (e: ClassNotFoundException) {
        PYTHON_PLUGIN_MISSING_MSG
    }
}
```

**File scan fallback:**
- Scan `**/models.py` and `**/models/*.py` files
- Regex: `class (\w+)\(.*models\.Model.*\):`
- Parse field lines: `field_name = models.FieldType(...)`

**Django field types to detect:**
`CharField`, `TextField`, `IntegerField`, `FloatField`, `DecimalField`, `BooleanField`, `DateField`, `DateTimeField`, `TimeField`, `EmailField`, `URLField`, `FileField`, `ImageField`, `JSONField`, `UUIDField`, `SlugField`, `ForeignKey`, `ManyToManyField`, `OneToOneField`, `GenericForeignKey`

**Where models are defined:**
- `{app}/models.py` (single file, most common)
- `{app}/models/` directory with `__init__.py` importing model classes
- `INSTALLED_APPS` in settings determines which apps to scan

### Action: `urls` — Parse URL Patterns

**What to extract:** URL pattern, view function/class, name, namespace, include() targets.

**Implementation approach: PSI + recursive include resolution**

**PSI approach:**
1. Find root `urls.py` (usually `{project_name}/urls.py`, referenced by `ROOT_URLCONF` in settings)
2. Parse `urlpatterns = [...]` list
3. For each element:
   - `path('pattern/', view, name='name')` — extract path, view reference, name
   - `re_path(r'regex/', view)` — extract regex, view reference
   - `include('app.urls')` — recursively parse the included module
4. Resolve view references to actual functions/classes

**Challenges:**
- Dynamic URL patterns (e.g., `path('api/', include(router.urls))` where router is a DRF router)
- URL namespaces via `app_name` variable
- `include()` with namespace parameter
- `re_path()` with complex regex patterns

**File scan fallback:**
- Scan all `urls.py` files in project
- Regex: `path\(['"]([^'"]+)['"],\s*(\w+)` and `re_path\(r?['"]([^'"]+)['"],\s*(\w+)`
- Cannot resolve `include()` targets without PSI

**Static vs Runtime:**
- Static PSI parsing handles ~80% of URL patterns
- For DRF routers (`router.register()` → auto-generated URLs), runtime `manage.py show_urls` (from `django-extensions`) would be needed
- Decision: PSI-first, note that DRF router URLs may be incomplete

### Action: `views` — List Views/ViewSets

**What to extract:** View name, type (function/class-based/ViewSet), HTTP methods handled, URL it's mapped to (if resolvable), permissions, authentication classes.

**Implementation approach: PSI**

1. **Function-based views (FBVs):**
   - Find functions decorated with `@api_view(['GET', 'POST'])` (DRF) or referenced in `urlpatterns`
   - Check for `@login_required`, `@permission_required`, `@csrf_exempt` decorators

2. **Class-based views (CBVs):**
   - Find subclasses of `django.views.View`, `django.views.generic.*`
   - Check which HTTP methods are overridden (`get()`, `post()`, `put()`, `delete()`, `patch()`)

3. **DRF ViewSets:**
   - Find subclasses of `rest_framework.viewsets.ModelViewSet`, `ViewSet`, `GenericViewSet`
   - Check for `permission_classes`, `authentication_classes` class attributes
   - Check for `@action` decorators for custom actions
   - Detect serializer_class, queryset, filterset_fields

**Detection strategy:**
```
# Base classes to search for inheritors:
django.views.View
django.views.generic.ListView
django.views.generic.DetailView
django.views.generic.CreateView
django.views.generic.UpdateView
django.views.generic.DeleteView
django.views.generic.TemplateView
django.views.generic.RedirectView
django.views.generic.FormView
rest_framework.viewsets.ModelViewSet
rest_framework.viewsets.ViewSet
rest_framework.viewsets.GenericViewSet
rest_framework.views.APIView
rest_framework.generics.ListAPIView
rest_framework.generics.RetrieveAPIView
rest_framework.generics.CreateAPIView
rest_framework.generics.UpdateAPIView
rest_framework.generics.DestroyAPIView
rest_framework.generics.ListCreateAPIView
rest_framework.generics.RetrieveUpdateDestroyAPIView
```

### Action: `migrations` — Migration Status

**What to extract:** Migration name, app, applied status, dependencies, operations.

**Implementation approach: Runtime (primary) + File scan (fallback)**

**Runtime approach (preferred, most accurate):**
```bash
python manage.py showmigrations --list 2>/dev/null
```
Output format:
```
app_name
 [X] 0001_initial
 [X] 0002_add_field
 [ ] 0003_pending_migration
```
Parse with regex: `\s*\[([X ])\]\s+(\d+_\w+)`

**File scan fallback (cannot determine applied status):**
- Scan `{app}/migrations/` directories
- List `000N_*.py` files
- Parse migration file for `dependencies` and `operations` lists
- Cannot determine applied/pending without DB access

**manage.py invocation pattern:**
```kotlin
// Reuse RunCommandTool's process execution pattern
val basePath = project.basePath
val managePy = findManagePy(File(basePath))
val command = "${pythonInterpreter(project)} ${managePy.absolutePath} showmigrations --list"
```

**Python interpreter resolution:**
```kotlin
// Via reflection — PyCharm SDK API
val sdkClass = Class.forName("com.jetbrains.python.sdk.PythonSdkUtil")
val getSdk = sdkClass.getMethod("findPythonSdk", Module::class.java)
val sdk = getSdk.invoke(null, module)
val homePath = sdk?.javaClass?.getMethod("getHomePath")?.invoke(sdk) as? String
```

### Action: `settings` — Parse Django Settings

**What to extract:** DATABASES, INSTALLED_APPS, MIDDLEWARE, ROOT_URLCONF, TEMPLATES, AUTH_USER_MODEL, REST_FRAMEWORK config, CELERY config, custom settings.

**Implementation approach: File scanning + PSI**

**Strategy:**
1. Find `settings.py` or split settings (`settings/base.py`, `settings/dev.py`, `settings/prod.py`)
2. Parse Python assignments for known setting names
3. Handle common patterns:
   - Direct assignment: `DATABASE = {...}`
   - `environ.get()` calls: `SECRET_KEY = os.environ.get('SECRET_KEY', 'default')`
   - Deferred import: `from .base import *`

**Where settings live:**
- `{project_name}/settings.py` (single file)
- `{project_name}/settings/__init__.py` (package)
- `{project_name}/settings/base.py` + `dev.py` + `prod.py` + `test.py`
- `config/settings.py` (alternative layout, e.g., cookiecutter-django)

**Key settings to extract:**

| Setting | Type | Notes |
|---------|------|-------|
| `INSTALLED_APPS` | list | Identifies all Django apps |
| `DATABASES` | dict | DB engine, name, host |
| `MIDDLEWARE` | list | Middleware stack order |
| `ROOT_URLCONF` | string | Root URL configuration module |
| `TEMPLATES` | list | Template engine config |
| `AUTH_USER_MODEL` | string | Custom user model |
| `REST_FRAMEWORK` | dict | DRF configuration |
| `CELERY_BROKER_URL` | string | Celery broker |
| `STATIC_URL` | string | Static files URL |
| `MEDIA_URL` | string | Media files URL |
| `DEBUG` | bool | Debug mode |
| `ALLOWED_HOSTS` | list | Allowed hosts |

**PSI enhancement:**
- Resolve `INSTALLED_APPS` to discover which apps exist
- Use PSI to detect `os.environ.get()` calls and flag secrets

### Action: `management_commands` — Custom Commands

**What to extract:** Command name, help text, arguments.

**Implementation approach: File scanning + PSI**

**Where they live:**
```
{app}/management/commands/{command_name}.py
```

**Convention:** Each command is a `Command` class extending `django.core.management.base.BaseCommand`.

**File scan approach:**
1. Find all `management/commands/` directories in the project
2. List `.py` files (excluding `__init__.py`)
3. The filename IS the command name
4. Parse the file for:
   - `help = "..."` class attribute
   - `add_arguments()` method for argument definitions
   - `handle()` method (the actual command logic)

**PSI enhancement:**
- Use `PyClassInheritorsSearch` to find all subclasses of `BaseCommand`
- Extract `help` attribute value
- Parse `add_arguments` method for `parser.add_argument()` calls

### Action: `templates` — Template Structure

**What to extract:** Template directory tree, template names, template tags/filters used.

**Implementation approach: File scanning**

**Strategy:**
1. Parse `TEMPLATES` setting from settings.py to find template directories
2. If `APP_DIRS: True`, scan `{app}/templates/` directories
3. List `.html`, `.txt`, `.xml` template files
4. Optionally scan templates for `{% load %}` tags to identify template tag libraries

**Template directory conventions:**
```
{app}/templates/{app_name}/           # App-specific templates
{project}/templates/                  # Project-wide templates
{project}/templates/{app_name}/       # Override app templates
```

**Template tag discovery:**
```
{app}/templatetags/{taglib_name}.py   # Custom template tags/filters
```

### Action: `admin` — Admin Site Registration

**What to extract:** Registered models, admin class, list_display, list_filter, search_fields, inlines.

**Implementation approach: PSI**

1. Find all `admin.py` files (by convention in each Django app)
2. Look for:
   - `admin.site.register(Model, AdminClass)` calls
   - `@admin.register(Model)` decorators
3. For each admin class, extract:
   - `list_display` — columns shown in list view
   - `list_filter` — sidebar filters
   - `search_fields` — searchable fields
   - `inlines` — inline model admin classes
   - `fieldsets` or `fields` — form layout

**PSI approach:**
- Search for classes inheriting `django.contrib.admin.ModelAdmin`
- Parse class attributes for configuration
- Search for `admin.site.register()` call expressions

### Action: `serializers` — DRF Serializers

**What to extract:** Serializer name, model (if ModelSerializer), fields, validation methods.

**Implementation approach: PSI**

**Detection:** Check if `rest_framework` is in `INSTALLED_APPS` or importable.

1. Find subclasses of:
   - `rest_framework.serializers.Serializer`
   - `rest_framework.serializers.ModelSerializer`
   - `rest_framework.serializers.HyperlinkedModelSerializer`
2. Extract:
   - `class Meta: model = ..., fields = [...]`
   - Custom field definitions
   - `validate_*()` methods
   - `create()` / `update()` overrides

### Action: `signals` — Signal Handlers

**What to extract:** Signal type, receiver function, sender.

**Implementation approach: PSI + File scan**

**Where signals are defined:**
- `{app}/signals.py` — signal definitions
- `{app}/apps.py` — `ready()` method connects signals
- `{app}/models.py` — inline `@receiver` decorators

**What to look for:**
1. `@receiver(signal_type, sender=Model)` decorators
2. `signal.connect(handler, sender=Model)` calls
3. Common signals: `pre_save`, `post_save`, `pre_delete`, `post_delete`, `m2m_changed`, `request_started`, `request_finished`

### Action: `celery_tasks` — Celery Task Definitions

**What to extract:** Task name, queue, rate_limit, retry policy, periodic schedule.

**Implementation approach: PSI**

**Detection:** Check for `celery` in requirements or `CELERY_BROKER_URL` in settings.

1. Find functions decorated with `@shared_task`, `@app.task`, `@celery.task`
2. Extract decorator arguments: `name`, `bind`, `max_retries`, `default_retry_delay`, `queue`, `rate_limit`
3. Check `celery.py` or `celeryconfig.py` for beat schedule

### Action: `middleware` — Middleware Stack

**What to extract:** Middleware class, order, custom middleware classes.

**Implementation approach: Settings parse + PSI**

1. Read `MIDDLEWARE` list from settings.py
2. For each middleware path, check if it's a project class or Django built-in
3. For project middleware, find the class and extract `process_request`, `process_response`, `process_view`, `process_exception`, `__call__` methods

### Action: `forms` — Form Classes

**What to extract:** Form name, fields, widgets, validators, model (if ModelForm).

**Implementation approach: PSI**

1. Find subclasses of `django.forms.Form` and `django.forms.ModelForm`
2. Extract field definitions and Meta class (for ModelForm)

### Action: `fixtures` — Available Fixtures

**What to extract:** Fixture files, format (JSON/YAML/XML), size.

**Implementation approach: File scanning**

Convention: `{app}/fixtures/{fixture_name}.json|yaml|xml`

1. Scan `fixtures/` directories in all apps
2. List files with size
3. Parse JSON fixtures for model names if small enough

### Action: `version_info` — Django Version

**What to extract:** Django version, Python version, installed packages.

**Implementation approach: Runtime + File scan**

1. Check `requirements.txt`, `Pipfile`, `pyproject.toml` for Django version
2. Optionally run `python -c "import django; print(django.VERSION)"`
3. List key dependency versions (DRF, Celery, etc.)

---

## 5. FastAPI Meta-Tool (~10 actions) {#5-fastapi-meta-tool}

### Tool Registration

```kotlin
class FastApiTool : AgentTool {
    override val name = "fastapi"
    // Registered as deferred, category "Python Frameworks"
}
```

### Action: `routes` — All Route Definitions

**What to extract:** HTTP method, path, function name, path params, query params, response model, status code, tags.

**Implementation approach: PSI (primary) + File scan (fallback)**

**PSI approach:**
1. Find functions with decorators matching pattern `@{var}.{method}(path)` where method is `get`, `post`, `put`, `delete`, `patch`, `options`, `head`
2. Common decorator patterns:
   - `@app.get("/path")` — direct on FastAPI instance
   - `@router.post("/path")` — on APIRouter
   - `@app.api_route("/path", methods=["GET", "POST"])` — multi-method
3. For each route:
   - Extract path from first decorator argument
   - Extract `response_model`, `status_code`, `tags`, `summary`, `description` from keyword args
   - Extract function parameters with type annotations (path params, query params, body)
   - Detect `Depends()` in parameters for dependency injection

**Decorator detection via PSI:**
```kotlin
// Pseudocode
for (pyFunc in pyFile.getTopLevelFunctions()) {
    for (decorator in pyFunc.getDecoratorList().getDecorators()) {
        val qualName = decorator.getQualifiedName()
        // Check if it matches pattern: *.get, *.post, *.put, *.delete, *.patch
        val methodMatch = FASTAPI_ROUTE_METHODS.find { qualName?.endsWith(".$it") == true }
        if (methodMatch != null) {
            val path = extractFirstStringArg(decorator)
            // ...
        }
    }
}
```

**File scan fallback:**
```regex
@\w+\.(get|post|put|delete|patch|options|head)\s*\(\s*["']([^"']+)["']
```

**Challenge: Router inclusion**
FastAPI routes can be split across files via `APIRouter`:
```python
# app/routers/items.py
router = APIRouter(prefix="/items", tags=["items"])

# main.py
app.include_router(items.router, prefix="/api/v1")
```
Need to:
1. Find all `APIRouter()` instantiations
2. Track `include_router()` calls to resolve full path (prefix stacking)
3. This requires cross-file resolution — PSI can do this via `resolve()` on references

### Action: `dependencies` — Dependency Injection Tree

**What to extract:** Dependency function, what it depends on (nested Depends), which routes use it.

**Implementation approach: PSI**

**Strategy:**
1. Find all `Depends(func)` expressions in route function parameters
2. For each dependency function, check if it also uses `Depends()` (nested)
3. Build a dependency tree

**PSI approach:**
- Scan function parameters for `Depends(...)` call expressions
- Resolve the argument of `Depends()` to its definition
- Recursively trace `Depends()` in that function's parameters

**Challenges:**
- `Annotated[Type, Depends(func)]` syntax (Python 3.9+)
- Class-based dependencies (`Depends(ClassName)`)
- Generator dependencies (`yield` in dependency function)
- Default values: `param: str = Depends(get_db)`

### Action: `models` — Pydantic Models

**What to extract:** Model name, fields (name, type, default, validators), nested models, Config class.

**Implementation approach: PSI**

1. Find subclasses of `pydantic.BaseModel`
2. For each model:
   - Parse class body for field definitions (type-annotated attributes)
   - Detect `Field()` calls for validation constraints
   - Find `model_config` (Pydantic v2) or `class Config` (v1)
   - Detect validators: `@field_validator`, `@model_validator` (v2) or `@validator` (v1)

**Base classes to search:**
```
pydantic.BaseModel
pydantic.v1.BaseModel (v1 compat in v2)
```

### Action: `middleware` — Registered Middleware

**What to extract:** Middleware class/function, order, type (ASGI/Starlette).

**Implementation approach: PSI + File scan**

**What to look for:**
```python
app.add_middleware(CORSMiddleware, allow_origins=[...])
app.add_middleware(CustomMiddleware)
```

**PSI approach:**
- Find all `add_middleware()` call expressions on the app object
- Extract the middleware class and keyword arguments
- Also check for `@app.middleware("http")` decorated functions

### Action: `security` — OAuth2/Security Schemes

**What to extract:** Security scheme type, token URL, scopes, dependencies.

**Implementation approach: PSI**

**What to look for:**
```python
oauth2_scheme = OAuth2PasswordBearer(tokenUrl="token")
api_key_header = APIKeyHeader(name="X-API-Key")
```

**PSI approach:**
- Find instantiations of `OAuth2PasswordBearer`, `OAuth2AuthorizationCodeBearer`, `APIKeyHeader`, `APIKeyCookie`, `APIKeyQuery`, `HTTPBasic`, `HTTPBearer`
- Extract constructor arguments
- Trace where these are used in `Depends()`

### Action: `background_tasks` — BackgroundTask Usage

**What to extract:** Functions passed to BackgroundTask, where used.

**Implementation approach: PSI**

**What to look for:**
```python
@app.post("/task")
async def create_task(background_tasks: BackgroundTasks):
    background_tasks.add_task(send_email, email, message)
```

- Find `BackgroundTasks` parameters in route functions
- Find `add_task()` calls on those parameters

### Action: `events` — Startup/Shutdown Handlers

**What to extract:** Event type, handler function.

**Implementation approach: PSI**

**Patterns to detect:**
```python
# Modern (lifespan)
@asynccontextmanager
async def lifespan(app: FastAPI):
    # startup
    yield
    # shutdown

app = FastAPI(lifespan=lifespan)

# Legacy
@app.on_event("startup")
async def startup():
    pass

@app.on_event("shutdown")
async def shutdown():
    pass
```

### Action: `config` — BaseSettings Classes

**What to extract:** Settings fields, types, defaults, env variable names, nested settings.

**Implementation approach: PSI**

1. Find subclasses of `pydantic_settings.BaseSettings` (or `pydantic.BaseSettings` for v1)
2. Extract field definitions with types, defaults
3. Check for `model_config` with `env_prefix`, `env_file`, etc.

### Action: `database` — SQLAlchemy Models (if detected)

**What to extract:** Table name, columns, relationships, mapped class.

**Implementation approach: PSI**

**Detection:** Check for `sqlalchemy` imports or `SQLAlchemy` in dependencies.

**Base classes to search:**
```
sqlalchemy.orm.DeclarativeBase  (SQLAlchemy 2.0+)
sqlalchemy.ext.declarative.declarative_base()  (legacy)
SQLModel  (if sqlmodel is installed)
```

### Action: `version_info`

Same pattern as Django — check requirements/pyproject.toml for FastAPI version.

---

## 6. Flask Meta-Tool (~10 actions) {#6-flask-meta-tool}

### Tool Registration

```kotlin
class FlaskTool : AgentTool {
    override val name = "flask"
    // Registered as deferred, category "Python Frameworks"
}
```

### Action: `routes` — Registered Routes

**What to extract:** HTTP method(s), URL rule, endpoint name, view function.

**Implementation approach: PSI (primary) + Runtime (optional)**

**PSI approach:**
1. Find `@app.route("/path", methods=["GET", "POST"])` decorators
2. Find Blueprint route registrations: `@bp.route("/path")`
3. Resolve `app.add_url_rule()` calls (less common, but possible)

**Decorator patterns:**
```python
@app.route("/", methods=["GET", "POST"])    # Flask app
@bp.route("/items/<int:item_id>")           # Blueprint
@app.get("/items")                          # Flask 2.0+ shortcuts
@app.post("/items")
```

**Runtime approach (optional, most complete):**
```bash
flask routes
# or
python -c "from app import create_app; app = create_app(); print(app.url_map)"
```

**Challenges:**
- Factory pattern (`create_app()`) — routes only exist after app creation
- Dynamic route registration
- `app.add_url_rule()` programmatic registration

### Action: `blueprints` — Blueprint Structure

**What to extract:** Blueprint name, URL prefix, template folder, static folder, registered routes.

**Implementation approach: PSI**

**What to look for:**
```python
bp = Blueprint('auth', __name__, url_prefix='/auth')
# or
bp = Blueprint('admin', __name__, template_folder='templates', static_folder='static')
```

1. Find all `Blueprint()` instantiations
2. Extract constructor arguments (name, import_name, url_prefix, template_folder, static_folder)
3. Find `app.register_blueprint(bp)` calls to determine how blueprints are mounted
4. List routes defined on each blueprint

### Action: `config` — App Configuration

**What to extract:** Configuration keys, values, source (config file, env vars).

**Implementation approach: File scan + PSI**

**Configuration sources:**
```python
app.config.from_pyfile('config.py')
app.config.from_object('config.DevelopmentConfig')
app.config.from_envvar('APP_SETTINGS')
app.config.from_mapping(SECRET_KEY='dev', DATABASE='...')
app.config['KEY'] = 'value'
```

**Strategy:**
1. Find `config.py` or `config/` package
2. Find classes inheriting from base config (common pattern: `Config`, `DevelopmentConfig`, `ProductionConfig`)
3. Scan for `app.config[...]` assignments and `app.config.from_*()` calls

### Action: `extensions` — Flask Extensions

**What to extract:** Extension name, initialization pattern, version.

**Implementation approach: PSI + File scan**

**Common extensions to detect:**
| Extension | Init Pattern |
|-----------|-------------|
| Flask-SQLAlchemy | `db = SQLAlchemy(app)` or `db.init_app(app)` |
| Flask-Migrate | `migrate = Migrate(app, db)` |
| Flask-Login | `login_manager = LoginManager(app)` |
| Flask-WTF | `csrf = CSRFProtect(app)` |
| Flask-Mail | `mail = Mail(app)` |
| Flask-Caching | `cache = Cache(app)` |
| Flask-CORS | `CORS(app)` |
| Flask-JWT-Extended | `jwt = JWTManager(app)` |
| Flask-Marshmallow | `ma = Marshmallow(app)` |
| Flask-RESTful | `api = Api(app)` |
| Flask-SocketIO | `socketio = SocketIO(app)` |

**Detection strategy:**
1. Scan imports for `flask_*` packages
2. Find `init_app()` calls
3. Check `requirements.txt` / `pyproject.toml` for Flask-* packages

### Action: `models` — SQLAlchemy Models

**What to extract:** Model name, table name, columns, relationships.

**Implementation approach: PSI**

**Base classes:**
```python
db.Model  # Flask-SQLAlchemy
```

1. Find the `db = SQLAlchemy()` instance
2. Find classes inheriting from `db.Model`
3. Extract `__tablename__`, column definitions, relationship definitions

**Column types to detect:**
`db.Column(db.Integer)`, `db.Column(db.String(80))`, `db.Column(db.Text)`, `db.Column(db.DateTime)`, `db.Column(db.Boolean)`, `db.Column(db.Float)`, `db.relationship('OtherModel')`

### Action: `templates` — Jinja2 Templates

**What to extract:** Template directory, template files, `render_template()` usage.

**Implementation approach: File scan + PSI**

1. Default template directory: `{app_package}/templates/`
2. Blueprint-specific: `{blueprint_package}/templates/`
3. Find `render_template('name.html', ...)` calls to discover which templates are actually used
4. List template files and their structure

### Action: `middleware` — Before/After Request Handlers

**What to extract:** Handler function, type (before_request, after_request, teardown_request, etc.).

**Implementation approach: PSI**

**Decorator patterns:**
```python
@app.before_request
def before_request_func():
    pass

@app.after_request
def after_request_func(response):
    return response

@app.teardown_request
def teardown_request_func(exception):
    pass

@app.errorhandler(404)
def not_found(error):
    pass

@bp.before_request  # Blueprint-specific
def bp_before_request():
    pass
```

1. Find functions decorated with `@app.before_request`, `@app.after_request`, `@app.teardown_request`, `@app.before_first_request`, `@app.errorhandler(code)`
2. Also check blueprint-level handlers

### Action: `cli_commands` — Click-based CLI Commands

**What to extract:** Command name, group, arguments, help text.

**Implementation approach: PSI**

**Patterns:**
```python
@app.cli.command()
@click.argument('name')
def create_user(name):
    """Create a new user."""
    pass

@app.cli.group()
def translate():
    """Translation and localization commands."""
    pass
```

1. Find functions decorated with `@app.cli.command()` or `@app.cli.group()`
2. Check for `@click.argument()`, `@click.option()` decorators
3. Extract docstring as help text

### Action: `forms` — WTForms Classes

**What to extract:** Form name, fields, validators.

**Implementation approach: PSI**

1. Find subclasses of `flask_wtf.FlaskForm` or `wtforms.Form`
2. Extract field definitions: `name = StringField('Label', validators=[DataRequired()])`

### Action: `version_info`

Same pattern as Django/FastAPI — check requirements for Flask version.

---

## 7. Framework Detection Strategy {#7-framework-detection-strategy}

### When to Load Which Tool

The Python framework tools should be **conditionally registered** based on project detection. Add to `ProjectContextTool.detectFrameworks()` or a separate `PythonFrameworkDetector`:

```kotlin
object PythonFrameworkDetector {

    data class DetectedFrameworks(
        val django: Boolean = false,
        val fastapi: Boolean = false,
        val flask: Boolean = false,
        val djangoRestFramework: Boolean = false,
        val celery: Boolean = false,
        val sqlalchemy: Boolean = false,
        val pydantic: Boolean = false,
    )

    fun detect(project: Project): DetectedFrameworks {
        val basePath = project.basePath ?: return DetectedFrameworks()
        val baseDir = File(basePath)

        // Check requirements files
        val reqFiles = listOf(
            "requirements.txt", "requirements/base.txt", "requirements/prod.txt",
            "Pipfile", "pyproject.toml", "setup.py", "setup.cfg"
        )

        val depContent = reqFiles
            .mapNotNull { File(baseDir, it).takeIf { f -> f.exists() }?.readText() }
            .joinToString("\n")
            .lowercase()

        // Check for manage.py (strong Django signal)
        val hasManagePy = File(baseDir, "manage.py").exists()

        // Check for typical entry points
        val hasWsgiPy = baseDir.walkTopDown().maxDepth(3)
            .any { it.name == "wsgi.py" || it.name == "asgi.py" }

        return DetectedFrameworks(
            django = hasManagePy || depContent.contains("django"),
            fastapi = depContent.contains("fastapi"),
            flask = depContent.contains("flask") && !depContent.contains("fastapi"),
            djangoRestFramework = depContent.contains("djangorestframework"),
            celery = depContent.contains("celery"),
            sqlalchemy = depContent.contains("sqlalchemy"),
            pydantic = depContent.contains("pydantic"),
        )
    }
}
```

### Registration in AgentService

```kotlin
// In AgentService.initializeTools()
try {
    val detected = PythonFrameworkDetector.detect(project)
    if (detected.django) {
        safeRegisterDeferred("Python Frameworks") { DjangoTool() }
    }
    if (detected.fastapi) {
        safeRegisterDeferred("Python Frameworks") { FastApiTool() }
    }
    if (detected.flask) {
        safeRegisterDeferred("Python Frameworks") { FlaskTool() }
    }
} catch (e: Exception) {
    log.warn("Python framework detection failed: ${e.message}")
}
```

---

## 8. Registration & Conditional Loading {#8-registration-and-conditional-loading}

### Deferred Loading Pattern

All three Python tools should be **deferred** (not core), matching `SpringTool` and `BuildTool`:

```kotlin
// Registration in AgentService
safeRegisterDeferred("Python Frameworks") { DjangoTool() }
safeRegisterDeferred("Python Frameworks") { FastApiTool() }
safeRegisterDeferred("Python Frameworks") { FlaskTool() }
```

The LLM discovers them via `tool_search("python")` or `tool_search("django")`.

### Python Plugin Availability Check

Every action must gracefully handle the case where the Python plugin is not installed:

```kotlin
companion object {
    internal const val PYTHON_PLUGIN_MISSING_MSG =
        "Error: Python plugin is not available. Install 'Python Community Edition' plugin to use this tool."

    internal fun isPythonPluginAvailable(): Boolean = try {
        Class.forName("com.jetbrains.python.psi.PyFile")
        true
    } catch (_: ClassNotFoundException) {
        false
    }
}
```

### Graceful Degradation

| Python Plugin Status | PSI Available | File Scan | Runtime |
|---------------------|---------------|-----------|---------|
| Not installed | No | Yes | Maybe (external Python) |
| Community installed | Yes | Yes | Yes (if interpreter configured) |
| Professional installed | Yes + Django/Flask support | Yes | Yes |

Actions should attempt PSI first, fall back to file scanning, and note limitations:

```kotlin
internal suspend fun executeModels(params: JsonObject, project: Project): ToolResult {
    // Try PSI first
    if (isPythonPluginAvailable() && !PsiToolUtils.isDumb(project)) {
        return try {
            ReadAction.nonBlocking<String> {
                collectModelsPsi(project, filter)
            }.inSmartMode(project).executeSynchronously()
                .let { ToolResult(it, "Django models (PSI)", TokenEstimator.estimate(it)) }
        } catch (e: Exception) {
            // Fall through to file scan
        }
    }

    // Fallback: file scan
    return withContext(Dispatchers.IO) {
        val content = collectModelsFileScan(project, filter)
        ToolResult(content, "Django models (file scan, limited)", TokenEstimator.estimate(content))
    }
}
```

---

## 9. Implementation Plan {#9-implementation-plan}

### File Structure

```
agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/
  DjangoTool.kt                    # Meta-tool dispatcher (like SpringTool.kt)
  FastApiTool.kt                   # Meta-tool dispatcher
  FlaskTool.kt                     # Meta-tool dispatcher
  PythonFrameworkDetector.kt       # Detection utility
  PythonPsiHelper.kt               # Shared Python PSI reflection utilities
  django/
    DjangoModelsAction.kt          # models action
    DjangoUrlsAction.kt            # urls action
    DjangoViewsAction.kt           # views action
    DjangoMigrationsAction.kt      # migrations action
    DjangoSettingsAction.kt        # settings action
    DjangoManagementCommandsAction.kt
    DjangoTemplatesAction.kt
    DjangoAdminAction.kt
    DjangoSerializersAction.kt     # DRF serializers
    DjangoSignalsAction.kt
    DjangoCeleryTasksAction.kt
    DjangoMiddlewareAction.kt
    DjangoFormsAction.kt
    DjangoFixturesAction.kt
    DjangoVersionInfoAction.kt
    DjangoHelpers.kt               # Shared constants, utility functions
  fastapi/
    FastApiRoutesAction.kt
    FastApiDependenciesAction.kt
    FastApiModelsAction.kt         # Pydantic models
    FastApiMiddlewareAction.kt
    FastApiSecurityAction.kt
    FastApiBackgroundTasksAction.kt
    FastApiEventsAction.kt
    FastApiConfigAction.kt         # BaseSettings
    FastApiDatabaseAction.kt       # SQLAlchemy models
    FastApiVersionInfoAction.kt
    FastApiHelpers.kt
  flask/
    FlaskRoutesAction.kt
    FlaskBlueprintsAction.kt
    FlaskConfigAction.kt
    FlaskExtensionsAction.kt
    FlaskModelsAction.kt           # SQLAlchemy models
    FlaskTemplatesAction.kt
    FlaskMiddlewareAction.kt
    FlaskCliCommandsAction.kt
    FlaskFormsAction.kt
    FlaskVersionInfoAction.kt
    FlaskHelpers.kt
```

### Shared Python PSI Helper

```kotlin
// PythonPsiHelper.kt
object PythonPsiHelper {

    /** Check if Python plugin is available at runtime. */
    fun isPythonPluginAvailable(): Boolean = try {
        Class.forName("com.jetbrains.python.psi.PyFile")
        true
    } catch (_: ClassNotFoundException) { false }

    /** Check if a PsiFile is a Python file. */
    fun isPythonFile(psiFile: PsiFile): Boolean = try {
        val pyFileClass = Class.forName("com.jetbrains.python.psi.PyFile")
        pyFileClass.isInstance(psiFile)
    } catch (_: ClassNotFoundException) { false }

    /** Get top-level classes from a PyFile via reflection. */
    fun getTopLevelClasses(pyFile: Any): List<Any> = try {
        val pyFileClass = Class.forName("com.jetbrains.python.psi.PyFile")
        val method = pyFileClass.getMethod("getTopLevelClasses")
        @Suppress("UNCHECKED_CAST")
        (method.invoke(pyFile) as? List<Any>) ?: emptyList()
    } catch (_: Exception) { emptyList() }

    /** Get top-level functions from a PyFile via reflection. */
    fun getTopLevelFunctions(pyFile: Any): List<Any> = try {
        val pyFileClass = Class.forName("com.jetbrains.python.psi.PyFile")
        val method = pyFileClass.getMethod("getTopLevelFunctions")
        @Suppress("UNCHECKED_CAST")
        (method.invoke(pyFile) as? List<Any>) ?: emptyList()
    } catch (_: Exception) { emptyList() }

    /** Get class name via reflection on PyClass. */
    fun getClassName(pyClass: Any): String? = try {
        val method = pyClass.javaClass.getMethod("getName")
        method.invoke(pyClass) as? String
    } catch (_: Exception) { null }

    /** Get qualified name of a PyClass. */
    fun getQualifiedName(pyClass: Any): String? = try {
        val method = pyClass.javaClass.getMethod("getQualifiedName")
        method.invoke(pyClass) as? String
    } catch (_: Exception) { null }

    /** Get decorators of a PyDecoratable (PyClass or PyFunction). */
    fun getDecorators(element: Any): List<Any> = try {
        val getDecoratorList = element.javaClass.getMethod("getDecoratorList")
        val decoratorList = getDecoratorList.invoke(element) ?: return emptyList()
        val getDecorators = decoratorList.javaClass.getMethod("getDecorators")
        @Suppress("UNCHECKED_CAST")
        (getDecorators.invoke(decoratorList) as? Array<Any>)?.toList() ?: emptyList()
    } catch (_: Exception) { emptyList() }

    /** Get decorator name (short name, e.g., "route" from "@app.route"). */
    fun getDecoratorName(decorator: Any): String? = try {
        val method = decorator.javaClass.getMethod("getName")
        method.invoke(decorator) as? String
    } catch (_: Exception) { null }

    /** Get the qualified name of a decorator. */
    fun getDecoratorQualifiedName(decorator: Any): String? = try {
        val method = decorator.javaClass.getMethod("getQualifiedName")
        val qname = method.invoke(decorator)
        qname?.javaClass?.getMethod("toString")?.invoke(qname) as? String
    } catch (_: Exception) { null }

    /** Get class attributes (assignments in class body). */
    fun getClassAttributes(pyClass: Any): List<Any> = try {
        val method = pyClass.javaClass.getMethod("getClassAttributes")
        @Suppress("UNCHECKED_CAST")
        (method.invoke(pyClass) as? List<Any>) ?: emptyList()
    } catch (_: Exception) { emptyList() }

    /** Get methods of a class. */
    fun getMethods(pyClass: Any): List<Any> = try {
        val method = pyClass.javaClass.getMethod("getMethods")
        @Suppress("UNCHECKED_CAST")
        (method.invoke(pyClass) as? Array<Any>)?.toList() ?: emptyList()
    } catch (_: Exception) { emptyList() }

    /** Get superclass expressions. */
    fun getSuperClassExpressions(pyClass: Any): List<Any> = try {
        val method = pyClass.javaClass.getMethod("getSuperClassExpressions")
        @Suppress("UNCHECKED_CAST")
        (method.invoke(pyClass) as? Array<Any>)?.toList() ?: emptyList()
    } catch (_: Exception) { emptyList() }

    /** Find all Python files in project scope. */
    fun findPythonFiles(project: Project, scope: GlobalSearchScope): List<PsiFile> {
        return try {
            val pyFileClass = Class.forName("com.jetbrains.python.psi.PyFile")
            val fileTypeClass = Class.forName("com.jetbrains.python.PythonFileType")
            val instanceField = fileTypeClass.getField("INSTANCE")
            val fileType = instanceField.get(null) as com.intellij.openapi.fileTypes.FileType
            FileTypeIndex.getFiles(fileType, scope).mapNotNull { vf ->
                PsiManager.getInstance(project).findFile(vf)
            }
        } catch (_: Exception) { emptyList() }
    }

    /**
     * Find subclasses of a given base class by qualified name.
     * Uses PyClassInheritorsSearch if available, falls back to manual scan.
     */
    fun findSubclasses(project: Project, baseFqn: String, scope: GlobalSearchScope): List<Any> {
        return try {
            // First, find the base class
            val pyPsiFacadeClass = Class.forName("com.jetbrains.python.psi.PyPsiFacade")
            val getInstance = pyPsiFacadeClass.getMethod("getInstance", Project::class.java)
            val facade = getInstance.invoke(null, project)

            // createClassByQName
            val createClass = pyPsiFacadeClass.getMethod(
                "createClassByQName", String::class.java, PsiElement::class.java
            )
            // May need to resolve base class differently — PyClassInheritorsSearch

            // Use AnnotatedElementsSearch equivalent for Python:
            val searchClass = Class.forName(
                "com.jetbrains.python.psi.search.PyClassInheritorsSearch"
            )
            val searchMethod = searchClass.getMethod(
                "search", Class.forName("com.jetbrains.python.psi.PyClass"),
                Boolean::class.javaPrimitiveType,
                GlobalSearchScope::class.java
            )
            // ...
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
```

### Priority Order

1. **Phase 1 — Django** (highest demand, most complex, ~15 actions)
   - Start with: `models`, `urls`, `views`, `settings`, `migrations`
   - Then: `admin`, `serializers`, `management_commands`, `templates`
   - Finally: `signals`, `celery_tasks`, `middleware`, `forms`, `fixtures`, `version_info`

2. **Phase 2 — FastAPI** (growing popularity, ~10 actions)
   - Start with: `routes`, `models` (Pydantic), `dependencies`
   - Then: `middleware`, `security`, `config`, `events`
   - Finally: `background_tasks`, `database`, `version_info`

3. **Phase 3 — Flask** (simpler, ~10 actions)
   - Start with: `routes`, `blueprints`, `config`
   - Then: `extensions`, `models`, `templates`, `middleware`
   - Finally: `cli_commands`, `forms`, `version_info`

### Testing Strategy

Following existing patterns (`SpringToolTest.kt`, `BuildToolTest.kt`):

```kotlin
class DjangoToolTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun `models action finds Django model classes via file scan`() {
        // Create a minimal Django project structure
        val modelsFile = tempDir.resolve("myapp/models.py")
        modelsFile.parent.toFile().mkdirs()
        modelsFile.writeText("""
            from django.db import models

            class Article(models.Model):
                title = models.CharField(max_length=200)
                content = models.TextField()
                author = models.ForeignKey('auth.User', on_delete=models.CASCADE)

                class Meta:
                    ordering = ['-created_at']
        """.trimIndent())

        // Mock project with basePath = tempDir
        // Execute tool and verify output contains model info
    }
}
```

### Token Budget Considerations

Each meta-tool's description should be concise (the existing Spring tool description is ~800 chars). Actions list in description helps the LLM select the right action without loading parameter schemas.

Estimated token costs per tool definition:
- `django` (15 actions): ~500 tokens for schema
- `fastapi` (10 actions): ~350 tokens for schema
- `flask` (10 actions): ~350 tokens for schema

As deferred tools, these only consume tokens when loaded via `tool_search`.

---

## Appendix A: Python File Patterns for File-Scan Fallback

### Django File Patterns

| File | Convention | Regex Pattern |
|------|-----------|---------------|
| Models | `**/models.py`, `**/models/*.py` | `class\s+(\w+)\(.*models\.Model.*\):` |
| URLs | `**/urls.py` | `path\(['"]([^'"]+)['"]` |
| Views | `**/views.py`, `**/views/*.py` | `class\s+(\w+)\(.*View.*\):` or `def\s+(\w+)\(request` |
| Admin | `**/admin.py` | `admin\.site\.register\((\w+)` or `@admin\.register\((\w+)\)` |
| Forms | `**/forms.py` | `class\s+(\w+)\(.*Form.*\):` |
| Serializers | `**/serializers.py` | `class\s+(\w+)\(.*Serializer.*\):` |
| Signals | `**/signals.py` | `@receiver\((\w+)` |
| Settings | `**/settings.py`, `**/settings/*.py` | `^([A-Z_]+)\s*=` |
| Management | `**/management/commands/*.py` | filename = command name |
| Templates | `**/templates/**/*.html` | file listing |
| Fixtures | `**/fixtures/*.{json,yaml,xml}` | file listing |
| Migrations | `**/migrations/0*.py` | file listing + parse dependencies |
| Tasks | `**/tasks.py` | `@shared_task` or `@app\.task` |
| Middleware | settings `MIDDLEWARE` list | settings parse |

### FastAPI File Patterns

| File | Convention | Regex Pattern |
|------|-----------|---------------|
| Routes | `**/*.py` | `@\w+\.(get\|post\|put\|delete\|patch)\(['"]` |
| Models | `**/models.py`, `**/schemas.py` | `class\s+(\w+)\(.*BaseModel.*\):` |
| Dependencies | `**/dependencies.py`, `**/deps.py` | `Depends\((\w+)\)` |
| Config | `**/config.py`, `**/settings.py` | `class\s+(\w+)\(.*BaseSettings.*\):` |
| Middleware | `**/main.py` | `app\.add_middleware\(` |
| Routers | `**/routers/*.py` | `router\s*=\s*APIRouter\(` |

### Flask File Patterns

| File | Convention | Regex Pattern |
|------|-----------|---------------|
| Routes | `**/*.py` | `@\w+\.route\(['"]` |
| Blueprints | `**/*.py` | `Blueprint\(['"](\w+)['"]` |
| Config | `config.py`, `**/config/*.py` | `class\s+(\w+Config)\(` |
| Extensions | `**/extensions.py`, `**/__init__.py` | `\w+\.init_app\(app\)` |
| Models | `**/models.py` | `class\s+(\w+)\(.*db\.Model.*\):` |
| Templates | `**/templates/**/*.html` | file listing |
| CLI | `**/*.py` | `@app\.cli\.(command\|group)\(\)` |
| Forms | `**/forms.py` | `class\s+(\w+)\(.*FlaskForm.*\):` |

---

## Appendix B: manage.py Commands for Runtime Actions

| Command | Output | Use Case |
|---------|--------|----------|
| `manage.py showmigrations --list` | Applied/pending status | `migrations` action |
| `manage.py inspectdb` | Models from existing DB | `models` (reverse engineering) |
| `manage.py show_urls` (django-extensions) | All resolved URLs | `urls` action (most complete) |
| `manage.py diffsettings` | Non-default settings | `settings` action |
| `manage.py check` | System check results | `version_info` / health |
| `manage.py dbshell --command "..."` | Direct DB queries | `models` (schema inspection) |

### Interpreter Resolution for Runtime Actions

```kotlin
/**
 * Resolve the Python interpreter for a project.
 * Tries PyCharm SDK API first, falls back to PATH.
 */
fun resolvePythonInterpreter(project: Project): String? {
    // 1. Try PyCharm SDK API via reflection
    try {
        val moduleManager = ModuleManager.getInstance(project)
        for (module in moduleManager.modules) {
            val sdkUtil = Class.forName("com.jetbrains.python.sdk.PythonSdkUtil")
            val findSdk = sdkUtil.getMethod("findPythonSdk", Module::class.java)
            val sdk = findSdk.invoke(null, module) ?: continue
            val homePath = sdk.javaClass.getMethod("getHomePath").invoke(sdk) as? String
            if (homePath != null) return homePath
        }
    } catch (_: ClassNotFoundException) {
        // Python plugin not available
    }

    // 2. Fallback: check common locations
    val candidates = listOf("python3", "python", "python3.12", "python3.11")
    for (cmd in candidates) {
        try {
            val process = ProcessBuilder(cmd, "--version")
                .redirectErrorStream(true).start()
            if (process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0) {
                return cmd
            }
        } catch (_: Exception) { }
    }

    // 3. Check project-local venv
    val basePath = project.basePath ?: return null
    val venvPython = listOf(
        "$basePath/venv/bin/python",
        "$basePath/.venv/bin/python",
        "$basePath/env/bin/python",
        "$basePath/venv/Scripts/python.exe",  // Windows
        "$basePath/.venv/Scripts/python.exe",
    ).firstOrNull { File(it).exists() }
    if (venvPython != null) return venvPython

    return null
}
```

---

## Appendix C: Key Differences from SpringTool

| Aspect | SpringTool | Python Framework Tools |
|--------|-----------|----------------------|
| Language PSI | Java PSI (compile-time) | Python PSI (reflection-only) |
| Annotation search | `AnnotatedElementsSearch` | No equivalent; use `PyClassInheritorsSearch` + decorator scan |
| Plugin dependency | Spring plugin (optional) | Python plugin (optional) |
| Framework plugin | IntelliJ Spring plugin | PyCharm Django/Flask plugin (Professional only) |
| Config format | `.properties`, `.yml` | `.py`, `.env`, `.toml` |
| Package discovery | Maven/Gradle deps | `requirements.txt`, `pyproject.toml`, `Pipfile` |
| Runtime analysis | Not used (PSI sufficient for Java) | `manage.py` commands useful for Django |
| Type resolution | Full (Java type system in PSI) | Limited (Python is dynamic, type hints optional) |
| Class discovery | `AnnotatedElementsSearch` on annotations | `PyClassInheritorsSearch` on base classes |
| Build tool integration | Maven/Gradle models | pip/poetry/pipenv (no IDE model) |

### Critical Implementation Note

Python's dynamic nature means PSI can only provide **static analysis** — it cannot resolve:
- Dynamic class creation (`type('ClassName', (Base,), {...})`)
- Conditional imports (`if settings.DEBUG: import debug_toolbar`)
- Plugin-based registration (DRF router auto-discovery)
- Runtime middleware ordering (may differ from settings)

For these cases, the tool should note the limitation in the output and suggest the agent use `run_command` to invoke `manage.py` commands for definitive answers.

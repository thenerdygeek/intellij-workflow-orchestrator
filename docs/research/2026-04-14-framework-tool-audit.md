# Framework Tool Deep Audit — Spring, Django, FastAPI, Flask

**Date:** 2026-04-14
**Scope:** Failure scenarios, edge cases, and gaps across 4 framework meta-tools (49 actions total)
**Files audited:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/` — all dispatchers + all 49 action files

---

## 1. SpringTool (15 actions)

### 1.1 context (SpringContextAction)

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Spring plugin not installed | Catches `NoClassDefFoundError` + `ClassNotFoundException`, returns `SPRING_PLUGIN_MISSING_MSG` | Inner `collectBeans()` also catches same exceptions redundantly — double catch is harmless but the outer catch returns a non-error ToolResult (no `isError=true`) | MEDIUM |
| Application context not fully loaded | Uses `SpringManager.getAllModels()` — returns static model, no runtime context needed | None — works without running app | OK |
| Multiple Spring contexts (multi-module) | Iterates `getAllModels(project)` which returns models from all modules | Beans from all modules are merged into one flat list — no module grouping. Hard to distinguish which beans belong to which module. | LOW |
| Spring Boot vs plain Spring | `SpringManager` works for both | No issue | OK |
| Lazy-initialized beans | `getAllCommonBeans()` returns all declared beans regardless of lazy flag | Lazy beans shown but not annotated as lazy — misleading for production understanding | LOW |
| Profile-specific beans (`@Profile`) | `detectStereotype()` only checks stereotype annotations, not `@Profile` | Beans with `@Profile` are shown without indicating which profile activates them — agent may suggest beans that don't exist in the active profile | MEDIUM |
| Result cap at 50 beans | Hard `take(50)` limit | LLM told "50 more beans not shown" but has no way to paginate — no `offset` parameter exists | LOW |
| Outer catch returns success for error | When inner `collectBeans()` returns `SPRING_PLUGIN_MISSING_MSG`, the outer function wraps it in a non-error `ToolResult` | LLM sees error message text but `isError=false` — may not realize it failed | MEDIUM |
| Kotlin-based Spring config | PSI `PsiClass` works for both Java and Kotlin classes | No issue | OK |
| Dumb mode | Checked via `PsiToolUtils.isDumb(project)` | No issue | OK |

### 1.2 endpoints (SpringEndpointsAction)

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Spring plugin not installed | No `NoClassDefFoundError` catch — only uses `JavaPsiFacade` + `AnnotatedElementsSearch` which are IntelliJ core | Works even without Spring plugin (pure PSI annotation search) | OK |
| Reactive endpoints (`@RouterFunction`) | Only scans `@RequestMapping` family annotations | Functional endpoints (RouterFunction-based) completely missed — increasingly common in Spring WebFlux | HIGH |
| `@RequestMapping` with multiple paths | `extractMappingPath()` gets first value only (`findAttributeValue("value")`) | Array paths like `@RequestMapping(value=["/a", "/b"])` only capture first path — second endpoint invisible | HIGH |
| `@RequestMapping` with multiple methods | `extractRequestMethod()` extracts text but doesn't split | `method={GET, POST}` returns "GET, POST" as single string instead of generating two endpoint entries | MEDIUM |
| Mixed Java/Kotlin project | PSI works for both | No issue | OK |
| OpenAPI/Swagger annotations | Not scanned | No OpenAPI metadata shown (tags, descriptions) — LLM missing context for API understanding | LOW |
| Method parameters not shown by default | `include_params` defaults to false | Acceptable default, but the LLM may not know to pass `include_params=true` | LOW |

### 1.3 bean_graph (SpringBeanGraphAction)

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Bean not found | Returns "No class '$beanName' found in project." | No issue | OK |
| Kotlin constructor injection (no `@Autowired`) | Checks `constructors.firstOrNull()` — Kotlin primary constructor is found | Works because Kotlin compiler generates a Java constructor | OK |
| `@Inject` (JSR-330) | Only checks `@Autowired` for field/setter injection | `@Inject` dependencies completely missed — some projects use JSR-330 exclusively | MEDIUM |
| `@Value` dependencies | Not scanned | `@Value("${property}")` injections invisible — agent misses config dependencies | LOW |
| Consumer search scans all bean classes | Iterates all `@Service/@Repository/@Controller/@Component/@Configuration` annotated classes | O(n*m) — in large projects with hundreds of beans, this could be slow. No timeout or cap on consumer search. | MEDIUM |
| `@Lazy` injection | Not detected | Lazy-injected dependencies shown same as eager — misleading | LOW |
| Interface-typed injection | `isAssignableFrom()` checks both directions of inheritance | Works correctly | OK |

### 1.4 config (SpringConfigAction)

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| YAML parsing | Custom line-by-line parser (not SnakeYAML/Jackson) | **CRITICAL**: Multi-line values, flow mappings (`{a: b}`), anchors/aliases (`*ref`), block scalars (`|`, `>`), sequences (`- item`), and inline comments after values are all incorrectly parsed or silently ignored | CRITICAL |
| Properties in YAML vs .properties | Both parsed | Both handled | OK |
| Profile-specific config overrides | Scans `application-dev.yml`, `application-test.yml`, `application-prod.yml` | Found, but no precedence indication — LLM can't tell which profile's value is active | MEDIUM |
| Environment variables overriding properties (`${ENV_VAR}`) | Values shown as-is: `${DB_HOST}` | Not resolved to actual values — acceptable since env vars are runtime, but could add a note | LOW |
| Encrypted properties (jasypt: `ENC(...)`) | Shown as-is | No redaction, no warning — acceptable since they're encrypted | OK |
| Multi-module project | Searches one level of subdirectories under basePath | Works for standard Maven/Gradle multi-module but misses deeply nested modules (3+ levels) | LOW |
| `bootstrap.yml` / `bootstrap.properties` | Included in search list | OK | OK |
| Spring Cloud Config Server | Not supported | Remote configuration sources invisible — only local files scanned | LOW |
| Hardcoded search dirs | Only `src/main/resources`, `src/main/resources/config`, `src/test/resources` | Misses custom resource directories and Gradle resource sets | LOW |

### 1.5 version_info (SpringVersionInfoAction)

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Maven-only | Uses `MavenUtils` exclusively | **No Gradle support** — Gradle projects get "Maven not configured" error. This is a major gap since many Spring Boot projects use Gradle. | CRITICAL |
| BOM-managed versions (no explicit version in dependency) | `findVersion()` requires `version.isNotBlank()` | BOM-managed deps (most Spring Boot starters) have empty version — only the parent POM version is detected | HIGH |
| Spring Boot parent POM | `getParentVersion()` checks parent group ID | Works for standard parent POM setup | OK |
| Project has Spring but no Spring Boot | `Spring Boot` version skipped, `Spring Framework` version shown via `spring-core` | Works correctly | OK |
| Reflection fails (Maven API changed) | `MavenUtils` uses `ReflectionUtils.tryReflective` with null return | Returns "Maven not configured" — misleading error message when Maven IS configured but API changed | LOW |

### 1.6 profiles (SpringProfilesAction)

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| @Profile annotations | Scans classes and methods with `@Profile` | Works well | OK |
| Profile-specific config files | Regex `application-(.+)\\.(properties|ya?ml)` | Works well | OK |
| Active profile detection (application.properties) | Parses `spring.profiles.active=` | Works | OK |
| Active profile detection (application.yml) | Naive line scan: `if trimmed.startsWith("active:") && text.contains("profiles:")` | **BUG**: Can false-positive match any YAML key named "active" if "profiles" appears anywhere in the file. Should properly parse the YAML hierarchy `spring.profiles.active`. | HIGH |
| `spring.profiles.include` | Not scanned | Included profiles invisible | LOW |
| `SPRING_PROFILES_ACTIVE` env var | Not detected | Runtime profile activation invisible — acceptable | LOW |
| Profile expressions (`@Profile("!prod & dev")`) | Extracted as string | Shown correctly, but negation/composition not explained | OK |

### 1.7 security_config (SpringSecurityConfigAction)

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Multiple `SecurityFilterChain` beans | All methods with `SecurityFilterChain` return type found | But patterns are merged across all chains — can't tell which chain has which settings | MEDIUM |
| Method-level security (`@PreAuthorize`) | Scanned via `AnnotatedElementsSearch` | Works well | OK |
| `@Secured` and `@RolesAllowed` | Both scanned, including `jakarta.*` and `javax.*` | Works well | OK |
| OAuth2/JWT configuration | `oauth2Login` and `oauth2ResourceServer` in `securityPatterns` | Only detected as "present" — no details about providers, token URLs, scopes | LOW |
| Custom security filters (`addFilterBefore`) | Not detected | Custom filter chains invisible — agent misses security middleware | MEDIUM |
| `@WithMockUser` in tests | Not filtered out — but `projectScope` excludes test sources | OK (assuming standard project layout) | OK |
| Pattern-based detection (body text contains) | String search in method body text | Could match commented-out code or variable names matching patterns — false positives possible | LOW |

### 1.8 jpa_entities (SpringJpaEntitiesAction)

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| `jakarta.persistence` vs `javax.persistence` | Both handled throughout | Works well | OK |
| Complex mappings (`@Embedded`, `@EmbeddedId`) | Not detected | `@Embedded` and `@EmbeddedId` fields shown as regular columns without embedded type info | MEDIUM |
| Inheritance (`@Inheritance`, `@MappedSuperclass`) | `allFields` includes inherited fields | Fields shown but inheritance strategy not shown | MEDIUM |
| Entities in libraries | `projectScope` — library entities excluded | Acceptable | OK |
| Hibernate-specific annotations (`@Formula`, `@Type`) | Not detected | Hibernate-only annotations invisible | LOW |
| `@Table(schema = "...")` | Only `name` attribute extracted | Schema information lost | LOW |
| `@Column(nullable, unique, length)` | Only `name` attribute extracted | Constraint metadata invisible — agent can't understand column constraints | MEDIUM |
| Composite keys (`@IdClass`, `@EmbeddedId`) | `@Id` detected but `@IdClass`/`@EmbeddedId` not shown | Composite key structure invisible | MEDIUM |
| `@Enumerated`, `@Lob`, `@Temporal` | Not detected | Type mapping annotations invisible | LOW |

### 1.9 repositories (SpringRepositoriesAction)

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Custom repository implementations | Only scans interfaces extending known base interfaces | Custom implementations (`Impl` classes) not shown | LOW |
| `@RepositoryRestResource` | Not detected | REST-exposed repositories not flagged | LOW |
| Spring Data MongoDB, R2DBC | `MongoRepository` and `ReactiveCrudRepository` included | Works | OK |
| Derived query methods | Shown as custom methods | Method names shown but derived query semantics not parsed | OK |
| `@Query` with native SQL | Query text shown | Works | OK |
| Default methods filtered | 19 method names in exclusion set | Comprehensive list | OK |

### 1.10 scheduled_tasks, event_listeners, boot_endpoints, boot_autoconfig, boot_config_properties, boot_actuator

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| **boot_endpoints**: context-path from YAML | Custom YAML parser checks `server.servlet.context-path` | Same naive YAML parser as config — fragile for non-trivial YAML | MEDIUM |
| **boot_endpoints**: multi-module project | Only checks `src/main/resources/application.properties` and `.yml` in basePath | Misses module-specific context paths | LOW |
| **boot_autoconfig**: library auto-configs | `project_only=true` by default | Correct default — library configs shown with `project_only=false` | OK |
| **boot_config_properties**: nested config classes | Only scans direct fields | `@NestedConfigurationProperties` inner classes not traversed | MEDIUM |
| **boot_actuator**: Gradle dependency detection | Falls back to file text search for `spring-boot-starter-actuator` | Works for both Maven and Gradle via text search | OK |
| **boot_actuator**: actuator security | Not analyzed | No indication of whether actuator endpoints are secured | LOW |
| **scheduled_tasks**: `fixedRate` with negative default (-1) | `parsed >= 0` filter | Correctly filters default `-1` sentinel | OK |
| **event_listeners**: `@TransactionalEventListener` | Scanned with phase detection | Works well | OK |

---

## 2. DjangoTool (14 actions)

### 2.1 models (DjangoModelsAction)

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Abstract models (`class Meta: abstract = True`) | Not distinguished from concrete models | Abstract models shown as regular models — agent may try to query them | MEDIUM |
| Proxy models | `Model` in parent class regex matches proxy models | Shown but not distinguished from concrete models | LOW |
| Custom model managers | Not scanned | Custom managers invisible | LOW |
| Model with no fields (pure inheritance) | `FIELD_PATTERN` requires `models.XXXField` — inherited fields not shown | Model appears with zero fields — misleading | MEDIUM |
| Multi-database models (`db_router`) | Not scanned | No database routing info | LOW |
| Model field with `choices` | Not parsed from field args | Choice options invisible | LOW |
| Regex-based parsing | `MODEL_CLASS_PATTERN` requires `Model` or `models.Model` in parent class | Misses models inheriting from custom base classes (e.g., `TimeStampedModel`, `UUIDModel`) | HIGH |
| `findClassEnd` heuristic | Finds next top-level `class` definition | **BUG**: If a model has an inner `class Meta:`, and Meta happens to start at column 0 (malformed indent), it truncates the model body. More importantly, models with methods spanning many lines may capture code from the next model if no `class` keyword appears. | MEDIUM |
| Only scans `models.py` | `walkTopDown().filter { it.name == "models.py" }` | Misses models in `models/` package directories (split models across `models/user.py`, `models/order.py` etc.) — very common Django pattern | HIGH |
| ForeignKey / relationship fields | `FIELD_PATTERN` matches `models.ForeignKey` but only shows field type, not the related model | Related model target invisible | MEDIUM |
| Large projects (500+ files) | `walkTopDown()` scans entire project tree | No timeout, no file count limit — could be slow in monorepos. `walkTopDown()` follows symlinks by default. | MEDIUM |

### 2.2 urls (DjangoUrlsAction)

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Nested includes | `INCLUDE_PATTERN` matched first — include() detected | Include target shown as string, but URL prefix not composed with included patterns — LLM can't see the full resolved URL path | HIGH |
| Dynamic URL patterns (programmatically generated) | Only regex-based line scanning | Programmatic patterns (`urlpatterns += [...]`, loop-generated) invisible | MEDIUM |
| Namespace-prefixed URLs | Not parsed | `app_name` and `namespace` invisible — agent can't use `reverse()` correctly | MEDIUM |
| `re_path` with regex | `re_path` in pattern | Path shown but regex not distinguished from simple path | LOW |
| DRF router auto-generated URLs | Not detected | `router.register(...)` patterns invisible — large chunk of API URLs missing in DRF projects | HIGH |
| Multi-line URL pattern | Regex scans per-line | Multi-line `path(...)` calls broken across lines are missed | MEDIUM |
| URL patterns in `api_urls.py` or `api/urls.py` | Only scans `urls.py` | Non-standard URL file names missed | LOW |

### 2.3 views (DjangoViewsAction)

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Function-based views | `FBV_PATTERN` matches `def xxx(request` | Works for standard FBVs | OK |
| Class-based views | `CBV_PATTERN` matches class names containing `View`, `APIView`, `ViewSet`, `GenericView`, `Mixin` | Works for standard CBVs | OK |
| DRF ViewSets with `@action` decorators | Not scanned | Custom viewset actions invisible — agent misses significant portion of DRF API | HIGH |
| Permissions classes | Not scanned | No permission info on views | MEDIUM |
| Throttle classes | Not scanned | No throttle info | LOW |
| Only scans `views.py` and `viewsets.py` | Named file filter | Misses views in `views/` package directory (split across files) or `api_views.py` | MEDIUM |
| Decorated FBVs (`@api_view`, `@permission_required`) | `FBV_PATTERN` allows `@\w+` decorators before `def` | Only detects function name, not decorator details | LOW |
| Generic views inheriting from custom bases | `CBV_PATTERN` requires View/APIView/ViewSet/GenericView/Mixin in parent | Custom base views (e.g., `class MyView(BaseCustomView)`) missed | MEDIUM |

### 2.4 settings (DjangoSettingsAction)

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Split settings (`base.py`, `dev.py`, `prod.py`) | `startsWith("settings") && extension == "py"` | **BUG**: Files named `base.py`, `dev.py`, `prod.py` in a `settings/` package are NOT detected because they don't start with "settings" | HIGH |
| Settings from environment variables | `os.environ.get(...)` shown as raw value | Shown as-is but not flagged as env-dependent | LOW |
| `django-environ` / `python-decouple` | `env(...)` calls shown as raw value | Not distinguished from static values | LOW |
| SECRET_KEY and sensitive settings | No redaction | **SECRET_KEY, DATABASE password, API keys all shown in full** — potential security issue if tool output is logged or shown in UI | HIGH |
| Multi-line settings | `SETTING_PATTERN` only matches single-line `KEY = value` | Multi-line values (lists, dicts) truncated at first line. `INSTALLED_APPS`, `MIDDLEWARE`, `DATABASES` often span many lines. | HIGH |
| `from base import *` pattern | Not followed | Settings inheritance across files not resolved | MEDIUM |
| Settings in `__init__.py` | Not scanned (doesn't match `startsWith("settings")`) | Common pattern where `settings/__init__.py` imports from split files — completely invisible | MEDIUM |

### 2.5 admin (DjangoAdminAction)

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| `@admin.register()` decorator | `ADMIN_DECORATOR_PATTERN` handles it | Works | OK |
| `admin.site.register()` | `ADMIN_REGISTER_PATTERN` handles it | Works | OK |
| Inline admins (`TabularInline`, `StackedInline`) | Not scanned | Inline model admins invisible | LOW |
| Custom admin methods (`list_display`, `list_filter`) | Not scanned | Admin configuration details invisible | LOW |
| Multiple admin sites | Not detected | Only `admin.site` patterns — custom admin sites missed | LOW |

### 2.6 Other Django actions (middleware, celery_tasks, signals, serializers, forms, fixtures, templates, management_commands, version_info)

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| **middleware**: MIDDLEWARE from settings | Regex scan for `MIDDLEWARE = [...]` | Same multi-line list parsing problem as settings — list truncated at first line | HIGH |
| **celery_tasks**: `@shared_task` and `@app.task` | Regex-based | Works for decorated tasks only — class-based tasks missed | MEDIUM |
| **signals**: `@receiver(signal)` | Regex-based | Works for decorator pattern; `signal.connect()` pattern may be missed | MEDIUM |
| **serializers**: DRF serializers | Regex on `Serializer` in parent class | Works for standard DRF serializers | OK |
| **serializers**: Nested serializer fields | Not scanned | Nesting depth invisible | LOW |
| **forms**: `FlaskForm` reference in description | DjangoTool description says "forms" | Code correctly scans for `forms.Form` and `ModelForm` — no issue | OK |
| **fixtures**: File listing only | Searches `fixtures/` directories | Lists files but doesn't parse content — acceptable | OK |
| **templates**: File listing only | Searches `templates/` directories | Lists files but doesn't parse template inheritance — acceptable | OK |
| **management_commands**: `management/commands/` scan | Searches for Python files in that path | Works for standard layout | OK |
| **version_info**: INSTALLED_APPS parsing | `INSTALLED_APPS\s*=\s*\[([^\]]*)\]` | Same multi-line regex issue — multi-line INSTALLED_APPS lists only capture first line. Also, APP_ENTRY_PATTERN filters `django.*` and `rest_framework` but misses other third-party apps people would want to see. | HIGH |
| **version_info**: Django version from Pipfile/poetry.lock | Only checks requirements*.txt and pyproject.toml | Misses Pipfile, poetry.lock, setup.py, setup.cfg | MEDIUM |

---

## 3. FastApiTool (10 actions)

### 3.1 routes (FastApiRoutesAction)

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Sub-applications (`app.mount`) | Not detected | Mounted sub-apps completely invisible | HIGH |
| APIRouter prefix | `ROUTE_DECORATOR_PATTERN` captures the object name (e.g., `router`) but not its prefix | **Router prefix not resolved** — all routes shown without their prefix. Agent sees `/users` instead of `/api/v1/users`. | CRITICAL |
| Versioned APIs (`/v1/`, `/v2/`) | Not specially handled | If prefixes were resolved, they'd show — but they're not | HIGH |
| WebSocket endpoints (`@app.websocket`) | Not in pattern (only get/post/put/delete/patch/options/head) | WebSocket routes invisible | MEDIUM |
| Tags for route grouping | Not scanned | Tags invisible | LOW |
| `include_router()` calls | Not scanned | Router composition invisible — agent can't see how routers are assembled | HIGH |
| Multiline decorators | Single-line regex | Decorators spanning multiple lines missed | MEDIUM |
| `@app.api_route()` | Not in pattern | Generic route decorator missed | LOW |
| Scans all `.py` files | `walkTopDown().filter { it.extension == "py" }` | Could match test files, conftest.py, migrations — false positives | LOW |

### 3.2 dependencies (FastApiDependenciesAction)

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Deeply nested Depends chains | Only finds direct `Depends(func)` — no recursion | Nested dependency chains not traced — agent sees individual `Depends()` but not the full chain | MEDIUM |
| Class-based dependencies (callable classes with `__call__`) | `Depends(\w+)` captures class name | Class-based deps detected if referenced in `Depends()` — OK | OK |
| `Depends` with `yield` (generator dependencies) | Not distinguished | Yield-based deps shown same as regular — no cleanup lifecycle info | LOW |
| Function tracking for dependencies | Tracks current function by line scanning | Works for simple cases but breaks with nested functions or closures | LOW |
| Dependencies without explicit `Depends()` | Type-hint-only injection (`param: SomeClass`) in newer FastAPI | Not detected — only explicit `Depends()` calls scanned | MEDIUM |

### 3.3 models (FastApiModelsAction)

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Pydantic v1 vs v2 | Regex matches `BaseModel` in parent class | Works for both v1 and v2 (both use `BaseModel`) | OK |
| Generic models (`Generic[T]`) | `BaseModel` must be in parent class | `class PaginatedResponse(BaseModel, Generic[T])` matches; `class PaginatedResponse(GenericModel)` (Pydantic v1) doesn't | MEDIUM |
| Nested models | Not recursively resolved | Nesting depth not shown — just field type names | LOW |
| `BaseSettings` subclasses | Different parent class — not matched by `BaseModel` pattern | Pydantic settings classes missed — use `config` action instead, but this isn't indicated | LOW |
| `model_config` field | Filtered via `fieldName != "model_config"` | Correctly excluded | OK |
| Private fields (`_field`) | Filtered via `!fieldName.startsWith("_")` | Correctly excluded | OK |
| `Field()` with validation | Field default shown in type annotation but `Field(...)` args not parsed | Validation constraints invisible | LOW |

### 3.4 security (FastApiSecurityAction)

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| OAuth2/JWT configuration | `OAuth2PasswordBearer`, `OAuth2AuthorizationCodeBearer` detected | Scheme detected but token URL and scope details extracted only as raw constructor args | LOW |
| Custom security schemes | Only `SECURITY_SCHEMES` list matched | Custom `SecurityBase` subclasses invisible | LOW |
| `Depends(get_current_user)` security pattern | Not detected here (would show in `dependencies` action) | Security dependency injection pattern not connected to security schemes | MEDIUM |

### 3.5 Other FastAPI actions (middleware, background_tasks, events, config, database, version_info)

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| **middleware**: `app.add_middleware()` | Regex-based | Works for standard pattern | OK |
| **background_tasks**: `BackgroundTasks` parameter | Regex-based | Works for standard injection pattern | OK |
| **events**: lifespan vs startup/shutdown | Regex for both patterns | Works | OK |
| **config**: `BaseSettings` classes | Scanned separately | Works | OK |
| **database**: SQLAlchemy/Tortoise models | Regex for `declarative_base()` and `Model` | Works for common patterns | OK |
| **version_info**: Only `==` and comparison operators | `(?:==|>=|<=|~=|!=|>|<)` | Misses `~=` (compatible release) — wait, it IS included. OK | OK |
| **version_info**: No `poetry.lock` or `Pipfile.lock` | Only checks requirements*.txt, pyproject.toml, setup.cfg | Misses locked versions from lock files | MEDIUM |

---

## 4. FlaskTool (10 actions)

### 4.1 routes (FlaskRoutesAction)

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Lazy-loaded routes (using `create_app` pattern) | Regex scans all `.py` files — routes inside `create_app()` are still matched | Works since regex doesn't care about scope | OK |
| Multiple Flask apps | All route decorators matched regardless of app variable name | Routes shown but no indication which app they belong to | LOW |
| Static file routes | Not shown | Static routes invisible — acceptable since they're implicit | OK |
| Error handler routes (`@app.errorhandler(404)`) | Not matched by `ROUTE_DECORATOR_PATTERN` | Error handlers invisible | LOW |
| Blueprint `url_prefix` not composed with route path | Regex captures `@blueprint.route('/path')` but prefix not resolved | **Agent sees `/path` instead of `/api/path`** — same prefix resolution problem as FastAPI | HIGH |
| `add_url_rule()` pattern | Not detected | Programmatic route registration invisible | MEDIUM |
| `methods` parameter on `.route()` | `ROUTE_DECORATOR_PATTERN` optionally captures `methods=[...]` | Works | OK |

### 4.2 blueprints (FlaskBlueprintsAction)

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Nested blueprints (Flask 2.0+) | `REGISTER_BLUEPRINT_PATTERN` matches `register_blueprint()` calls | Nesting shown — parent.register_blueprint(child) detected | OK |
| Blueprint with `url_prefix` | Captured in both constructor and registration | Works | OK |
| Blueprint `template_folder` and `static_folder` | Not captured | Template/static customization invisible | LOW |
| `EXTENSION_CONSTRUCTOR_PATTERN` false positives | `(\w+)\s*=\s*(\w+)\s*\(\s*(\w+)?\s*\)` — matches any `x = Y()` | Pattern is used only in extensions, not blueprints — OK here | OK |

### 4.3 config (FlaskConfigAction)

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Class-based config | `CONFIG_CLASS_PATTERN` matches classes with "Config" in name | Works for standard pattern | OK |
| `from_object()` references | `FROM_OBJECT_PATTERN` detects `app.from_object(...)` | Works well — shows config loading chain | OK |
| `from_envvar()` | Included in `FROM_OBJECT_PATTERN` regex | Works | OK |
| `.env` file config | Not scanned | Dotenv config invisible | LOW |
| Sensitive values (SECRET_KEY, database URIs) | No redaction | **SECRET_KEY shown in full** — same issue as Django | HIGH |
| Config inheritance (`class ProductionConfig(Config)`) | Class inheritance visible but parent config values not merged | Agent sees child class without parent values | MEDIUM |

### 4.4 extensions (FlaskExtensionsAction)

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Extension not initialized (`ext = SQLAlchemy()` but no `init_app`) | Constructor and `init_app` tracked separately | **No cross-reference** — agent can't tell if an extension instance was actually initialized | HIGH |
| Multiple extensions of same type | All instances captured | Shown — but may be confusing | OK |
| Unknown extensions | `KNOWN_EXTENSIONS` whitelist with 22 entries | Custom or uncommon extensions missed — no fallback heuristic | MEDIUM |
| `EXTENSION_CONSTRUCTOR_PATTERN` false positives | `(\w+)\s*=\s*(\w+)\s*\(\s*(\w+)?\s*\)` | Extremely broad regex — `result = SQLAlchemy(db)` works, but `x = anything()` would also match if `anything` happened to be in `KNOWN_EXTENSIONS`. Pattern can't distinguish `SQLAlchemy` from Flask-SQLAlchemy import context. | LOW |
| `init_app()` not always Flask | `INIT_APP_PATTERN` matches any `x.init_app(y)` | Could false-positive on non-Flask `init_app` methods (e.g., Celery, logging) | LOW |

### 4.5 Other Flask actions (models, templates, middleware, cli_commands, forms, version_info)

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| **models**: `db.Model` classes | Regex-based with `db.Column` field detection | Works for standard Flask-SQLAlchemy | OK |
| **models**: SQLAlchemy Declarative Base without Flask-SQLAlchemy | Only matches `db.Model` pattern | Misses raw SQLAlchemy `Base` pattern in Flask apps | LOW |
| **templates**: Jinja2 template listing | Lists files in `templates/` directories | Works | OK |
| **templates**: Template inheritance | Not parsed | Base template chain invisible | LOW |
| **middleware**: `@app.before_request` etc. | Regex-based | Works for decorator pattern | OK |
| **middleware**: WSGI middleware wrapping (`app.wsgi_app = ProxyFix(app.wsgi_app)`) | Not detected | WSGI-level middleware invisible | LOW |
| **cli_commands**: `@app.cli.command()` and `@click.command()` | Regex-based | Works | OK |
| **forms**: FlaskForm/Form classes | Regex-based with field detection | Works for standard pattern | OK |
| **version_info**: Pipfile support | `PIPFILE_PATTERN` included | Better than FastAPI (which misses Pipfile) | OK |
| **version_info**: `poetry.lock` not checked | Only checks pyproject.toml deps, not lock file | Pinned versions from lock file invisible | LOW |

---

## 5. Shared Python Framework Scenarios

| Scenario | Current Handling | Gap/Issue | Severity |
|---|---|---|---|
| Python interpreter not configured | Python tools are file-scan only — no interpreter needed | **No error possible** — tools work without Python configured. However, this means no runtime introspection. | OK (by design) |
| Virtual environment not activated | Not needed — file scan only | No issue | OK |
| Dependencies not installed | Not needed — file scan only. Version info falls back to "not found" | No issue | OK |
| PSI access via reflection fails | **Python tools don't use PSI at all** — pure file I/O + regex | No fallback needed since there's no PSI usage. This is a design choice: zero-dependency on PythonCore plugin. But it means no type resolution, no cross-file reference tracking, no symbol resolution. | MEDIUM (trade-off) |
| File scan fallback accuracy — regex reliability | All Python tools rely exclusively on regex patterns | **Regex patterns are the primary (and only) approach, not a fallback**. This means: (1) multi-line constructs consistently missed, (2) indentation-sensitive Python semantics poorly handled by line-oriented regex, (3) code inside if/try/with blocks may be at deeper indent levels and missed by field patterns requiring exactly 4 spaces | HIGH |
| Large project (500+ files) | `walkTopDown()` scans entire project tree including venv, .git, node_modules | **No exclusion of virtual environments, build directories, or vendor directories** — `venv/`, `.venv/`, `env/`, `node_modules/`, `__pycache__/`, `.tox/`, `.eggs/` all scanned. On large projects this wastes time and may produce false positives from vendored code. | HIGH |
| Symlink loops | `walkTopDown()` follows symlinks by default | Potential infinite loop on symlinked directory structures | MEDIUM |

---

## 6. Cross-Tool Architectural Issues

| Issue | Affected Tools | Severity |
|---|---|---|
| **No directory exclusion** — Python tools scan entire project tree including venv, node_modules, __pycache__, .git | Django, FastAPI, Flask (all file-scan actions) | HIGH |
| **No file count cap** — unbounded `walkTopDown()` could scan thousands of files in monorepos | Django, FastAPI, Flask | MEDIUM |
| **Naive YAML parser** — custom line-by-line parser doesn't handle multi-line values, flow styles, anchors, block scalars | Spring (config, boot_actuator, boot_endpoints, profiles) | CRITICAL |
| **No sensitive value redaction** — SECRET_KEY, database passwords, API tokens shown in plain text | Django (settings), Flask (config), Spring (config) | HIGH |
| **Missing Gradle support** — version_info only works with Maven | Spring (version_info) | CRITICAL |
| **Router/blueprint prefix not resolved** — routes shown without their prefix | FastAPI (routes), Flask (routes) | CRITICAL |
| **Multi-line Python construct parsing** — regex patterns are strictly line-oriented | Django (settings, middleware, version_info), FastAPI (all), Flask (all) | HIGH |
| **No `models/` package directory support** — only `models.py` scanned | Django (models) | HIGH |
| **No `views/` package directory support** — only `views.py`/`viewsets.py` scanned | Django (views) | MEDIUM |
| **`findClassEnd` heuristic fragile** — next top-level `class` used as boundary | Django (models), FastAPI (models), Flask (config) | MEDIUM |
| **No cancellation checks in file scanning loops** — `coroutineContext.ensureActive()` only at dispatcher level | All Python tools | MEDIUM |
| **Inconsistent isError flag** — Spring context returns error text without `isError=true` in outer wrapper | Spring (context) | MEDIUM |
| **No DRF router detection** — DRF's `router.register()` generates majority of API URLs in DRF projects | Django (urls) | HIGH |

---

## 7. Priority Remediation Recommendations

### CRITICAL (fix immediately)

1. **Router/Blueprint prefix resolution** (FastAPI routes, Flask routes) — Parse `APIRouter(prefix="/api")` and `Blueprint(..., url_prefix="/api")` definitions, then compose with route paths. Without this, all route paths are misleading.

2. **YAML parser replacement** (Spring config, actuator, boot_endpoints, profiles) — Replace the custom line-by-line parser with a proper YAML library (SnakeYAML is already on the classpath via Spring). Multi-line values, block scalars, flow mappings, and anchors all silently produce wrong results.

3. **Gradle support for version_info** (Spring) — Add `build.gradle`/`build.gradle.kts` parsing for dependency versions. At minimum, parse `implementation 'group:artifact:version'` lines and `ext`/`extra` properties. Or use IntelliJ's Gradle plugin API via reflection.

### HIGH (fix soon)

4. **Directory exclusion list** — Add a shared `EXCLUDED_DIRS` set (`venv`, `.venv`, `env`, `__pycache__`, `.tox`, `node_modules`, `.git`, `build`, `dist`, `.eggs`, `site-packages`) and filter it in `walkTopDown()`.

5. **`models/` package directory support** (Django) — Also scan for `models/__init__.py` and all `.py` files in `models/` directories.

6. **Sensitive value redaction** — Redact values for keys matching `SECRET`, `PASSWORD`, `KEY`, `TOKEN`, `CREDENTIAL`, `AUTH` patterns. Show `***REDACTED***` in output.

7. **DRF router registration detection** (Django URLs) — Parse `router.register(r'prefix', ViewSet)` patterns and show generated URL patterns.

8. **Multi-line Python constructs** — For critical settings like `INSTALLED_APPS`, `MIDDLEWARE`, `DATABASES`, use a multi-line regex with `DOT_MATCHES_ALL` or read until matching bracket.

9. **Active profile detection in YAML** (Spring profiles) — Replace naive line scan with proper YAML key path checking, or reuse the same YAML parser (once fixed) to extract `spring.profiles.active`.

10. **`@RequestMapping` with multiple paths/methods** (Spring endpoints) — Handle array values for `value`, `path`, and `method` attributes, generating multiple endpoint entries.

### MEDIUM (improve when touching these files)

11. Add `@Profile` annotation info to beans in context action
12. Add `@Inject` (JSR-330) support to bean_graph
13. Add `@Embedded`/`@EmbeddedId`/`@Column` constraint details to JPA entities
14. Add `@action` decorator detection for DRF ViewSets
15. Add SecurityFilterChain separation in security_config
16. Add `include_router()` detection for FastAPI
17. Cross-reference Flask extension instances with `init_app()` calls
18. Add `coroutineContext.ensureActive()` checks inside file scanning loops
19. Fix `isError` flag consistency in Spring context action outer wrapper
20. Detect `GenericModel` (Pydantic v1) in FastAPI models

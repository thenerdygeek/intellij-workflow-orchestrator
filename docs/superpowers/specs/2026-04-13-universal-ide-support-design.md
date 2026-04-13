# Universal JetBrains IDE Support — Design Spec

**Date:** 2026-04-13
**Status:** Draft
**Depends on:** Tooling Architecture Enhancements (completed), Rebase with main (completed)

## Problem Statement

The agent's code intelligence, framework, build, and debug tools are hardwired to Java/Kotlin and Spring. The plugin declares `com.intellij.modules.java` as a required dependency, preventing installation on PyCharm, WebStorm, GoLand, or any non-IntelliJ IDE. Even within IntelliJ, the LLM underutilizes specialized deferred tools (e.g., `spring(action="endpoints")`) because it prefers always-visible core tools like `glob_files` and `search_code`.

This design addresses three problems:
1. **IDE lock-in** — the plugin cannot install outside IntelliJ IDEA
2. **Language lock-in** — code intelligence only works for Java/Kotlin
3. **Tool discovery gap** — the LLM doesn't know about or prefer specialized tools over generic fallbacks

## Target IDEs

| Code | IDE | Edition | Deep Support |
|---|---|---|---|
| IC | IntelliJ IDEA Community | Free | Java/Kotlin |
| IU | IntelliJ IDEA Ultimate | Paid | Java/Kotlin + Python (if plugin) + Spring (if plugin) |
| PC | PyCharm Community | Free | Python |
| PY | PyCharm Professional | Paid | Python + Django debug + remote interpreter |
| Other | WebStorm, GoLand, RustRover, CLion, Rider, etc. | Any | Base tools only (file, git, terminal, memory, integrations, database) |

## Architecture Overview

### Hybrid Provider Pattern

Platform-level abstractions as the foundation, with deep language-specific implementations behind those interfaces.

```
┌─────────────────────────────────────────────────┐
│                   AgentTool                      │
│          (e.g., FindDefinitionTool)              │
├─────────────────────────────────────────────────┤
│         LanguageIntelligenceProvider             │
│              (interface in :core)                │
├──────────────────┬──────────────────────────────┤
│ JavaKotlinProvider│     PythonProvider           │
│  (Java/Kotlin PSI)│  (Python PSI — PyClass,     │
│                   │   PyFunction, etc.)          │
└──────────────────┴──────────────────────────────┘
```

One tool class per capability. The tool detects the file's language and delegates to the appropriate provider. No duplicate tool entries.

### 3-Layer Tool Registration Filter

Tools that can't work in the current environment are **never registered** — the LLM never learns they exist.

```
Layer 1: IDE Product
  → ApplicationInfo.getInstance().build.productCode
  → Determines which language providers load

Layer 2: IDE Edition (Community vs Ultimate/Professional)
  → Determines which premium features register
  → PythonCore (Community) vs com.intellij.python (Professional)

Layer 3: Project Detection
  → Scans for manage.py, requirements.txt, pyproject.toml, pom.xml, etc.
  → Determines which framework/build tools register
```

### Tool Availability Matrix

| Tool | IC | IU | PC | PY | Other |
|---|---|---|---|---|---|
| File/Terminal/Planning/Memory | Yes | Yes | Yes | Yes | Yes |
| Git/VCS | Yes | Yes | Yes | Yes | Yes |
| Integrations (Jira, Bamboo, Sonar, Bitbucket) | If configured | If configured | If configured | If configured | If configured |
| Database (PostgreSQL, SQLite) | Yes | Yes | Yes | Yes | Yes |
| Code intelligence (Java/Kotlin) | Yes | Yes | No | No | No |
| Code intelligence (Python) | No | If python plugin | Yes | Yes | No |
| `spring` meta-tool | No | If spring plugin | No | No | No |
| `django` meta-tool | No | If python plugin + detected | If detected | If detected | No |
| `fastapi` meta-tool | No | If python plugin + detected | If detected | If detected | No |
| `flask` meta-tool | No | If python plugin + detected | If detected | If detected | No |
| Build (Maven/Gradle) | Yes | Yes | No | No | No |
| Build (pip/poetry/uv) | No | If python plugin | Yes | Yes | No |
| Debug (Java/Kotlin) | Yes | Yes | No | No | No |
| Debug (Python — basic) | No | If python plugin | Yes | Yes | No |
| Debug (Python — advanced) | No | No | No | Yes | No |
| Coverage | No | Yes | No | Yes | No |
| `tool_search` | Yes | Yes | Yes | Yes | Yes |

**IC** = IntelliJ Community, **IU** = IntelliJ Ultimate, **PC** = PyCharm Community, **PY** = PyCharm Professional

### IDE Context Detection

```kotlin
data class IdeContext(
    val productCode: String,           // "IU", "IC", "PY", "PC", etc.
    val productName: String,           // "IntelliJ IDEA Ultimate"
    val languages: Set<Language>,      // JAVA, KOTLIN, PYTHON
    val edition: Edition,              // COMMUNITY, PROFESSIONAL, ULTIMATE
    val availablePlugins: Set<String>, // Plugin IDs present
    val detectedFrameworks: Set<Framework>,  // SPRING, DJANGO, FASTAPI, FLASK
    val detectedBuildTools: Set<BuildTool>,  // MAVEN, GRADLE, PIP, POETRY, UV
)

enum class Edition { COMMUNITY, PROFESSIONAL, ULTIMATE, OTHER }
enum class Language { JAVA, KOTLIN, PYTHON }
enum class Framework { SPRING, DJANGO, FASTAPI, FLASK }
enum class BuildTool { MAVEN, GRADLE, PIP, POETRY, UV }
```

Detection logic:
- **IDE product/edition**: `ApplicationInfo.getInstance().build.productCode`
- **Python plugin**: `PluginManager.isPluginInstalled(PluginId.getId("com.intellij.python"))` (Professional) or `PythonCore` (Community)
- **Spring plugin**: `PluginManager.isPluginInstalled(PluginId.getId("com.intellij.spring"))`
- **Django**: presence of `manage.py` + `django` in requirements/pyproject.toml
- **FastAPI**: `fastapi` in requirements/pyproject.toml
- **Flask**: `flask` in requirements/pyproject.toml
- **Maven**: `pom.xml` in project root
- **Gradle**: `build.gradle` or `build.gradle.kts` in project root
- **pip**: `requirements.txt` in project root
- **poetry**: `pyproject.toml` with `[tool.poetry]` section
- **uv**: `pyproject.toml` with `[tool.uv]` section or `uv.lock`

---

## Language Intelligence Providers

### Interface (in `:core` or `:agent`)

```kotlin
interface LanguageIntelligenceProvider {
    val supportedLanguages: Set<Language>

    fun findDefinition(element: PsiElement): List<DefinitionResult>
    fun findReferences(element: PsiElement, scope: SearchScope): List<ReferenceResult>
    fun getFileStructure(file: PsiFile): FileStructureResult
    fun getTypeHierarchy(element: PsiElement): TypeHierarchyResult?
    fun getCallHierarchy(element: PsiElement): CallHierarchyResult?
    fun inferType(element: PsiElement): TypeInferenceResult?
    fun analyzeDataflow(element: PsiElement): DataflowResult?
    fun getDecoratorsOrAnnotations(element: PsiElement): List<AnnotationResult>
    fun findTests(element: PsiElement): List<TestResult>
    fun structuralSearch(pattern: String, scope: SearchScope): List<MatchResult>
    fun getReadWriteAccess(element: PsiElement): ReadWriteAccessResult?
}
```

### JavaKotlinProvider

Wraps the existing Java/Kotlin PSI code already in the tool implementations. Refactor current inline PSI logic into this provider.

Uses: `PsiJavaFile`, `PsiClass`, `PsiMethod`, `JavaPsiFacade`, `KtFile`, `KtClass`, `KtFunction`, etc.

### PythonProvider

New implementation using PyCharm's Python PSI APIs:

| Method | Python PSI API |
|---|---|
| findDefinition | `PyResolveUtil`, `PyClass`, `PyFunction` navigation |
| findReferences | `ReferencesSearch` (platform API, works for Python) |
| getFileStructure | `PyFile.topLevelClasses`, `topLevelFunctions`, `importBlock` |
| getTypeHierarchy | `PyClass.getSuperClasses()`, `PyInheritanceUtil` |
| getCallHierarchy | `PyCallHierarchyProvider` |
| inferType | `PyTypingTypeProvider`, `TypeEvalContext` |
| analyzeDataflow | `PyDefUseUtil`, `ScopeUtil`, reaching definitions |
| getDecoratorsOrAnnotations | `PyDecoratorList`, `PyDecorator` |
| findTests | Scan for `test_*.py`, `pytest` markers, `unittest.TestCase` subclasses |
| structuralSearch | Platform `StructuralSearchProfile` (works for Python) |
| getReadWriteAccess | `ReadWriteAccessDetector` (platform API) |

### Provider Resolution

```kotlin
class LanguageProviderRegistry(private val project: Project) {
    private val providers = mutableMapOf<Language, LanguageIntelligenceProvider>()

    fun providerFor(file: PsiFile): LanguageIntelligenceProvider? {
        val language = file.language  // Platform API — returns Language instance
        return when {
            language.isKindOf(JavaLanguage.INSTANCE) -> providers[Language.JAVA]
            language.id == "kotlin" -> providers[Language.KOTLIN]
            language.id == "Python" -> providers[Language.PYTHON]
            else -> null
        }
    }

    fun providerFor(language: Language): LanguageIntelligenceProvider? = providers[language]
}
```

Tools delegate to the provider:
```kotlin
class FindDefinitionTool(private val registry: LanguageProviderRegistry) : AgentTool {
    override val name = "find_definition"

    override suspend fun execute(params: JsonObject): ToolResult<*> {
        val file = resolveFile(params)
        val provider = registry.providerFor(file)
            ?: return ToolResult.error("Code intelligence not available for ${file.language.displayName}")
        // ... delegate to provider
    }
}
```

---

## Python Framework Meta-Tools

Framework tools are registered as **deferred** tools, only when the framework is **detected** in the project.

### Django Meta-Tool (~15 actions)

| Action | What it does | Detection |
|---|---|---|
| `models` | List all models, fields, relationships, Meta options | Parse models.py files |
| `urls` | Parse urlpatterns — all routes, views, names | Parse urls.py, resolve includes |
| `views` | List views/viewsets, HTTP methods, permissions | Parse views.py, viewsets |
| `migrations` | Migration status, pending, migration graph | Read migrations dirs, run manage.py showmigrations |
| `settings` | Parse settings.py — databases, apps, middleware | Read settings module |
| `management_commands` | List manage.py commands (built-in + custom) | Scan management/commands dirs |
| `templates` | Template directory structure, tags/filters used | Walk template dirs |
| `admin` | Registered admin classes, inlines, list displays | Parse admin.py files |
| `serializers` | DRF serializers, fields, validators | Parse serializers.py (if DRF) |
| `signals` | Registered signal handlers and connections | Parse signals.py, apps.py ready() |
| `celery_tasks` | Celery task definitions and schedules | Parse tasks.py (if celery) |
| `middleware` | Middleware stack in order | Read MIDDLEWARE setting |
| `forms` | Form classes, fields, validation | Parse forms.py |
| `fixtures` | Available fixture files | Scan fixtures dirs |
| `version_info` | Django version, packages, Python version | Introspect installed packages |

**Detection:** `manage.py` exists at project root AND `django` found in requirements.txt / pyproject.toml.

### FastAPI Meta-Tool (~10 actions)

| Action | What it does |
|---|---|
| `routes` | All route definitions, methods, path params, response models |
| `dependencies` | Dependency injection tree (Depends chains) |
| `models` | Pydantic models, fields, validators |
| `middleware` | Registered middleware stack |
| `security` | OAuth2 schemes, API key deps, security scopes |
| `background_tasks` | Background task definitions |
| `events` | Startup/shutdown event handlers |
| `config` | Settings classes (BaseSettings), env vars |
| `database` | SQLAlchemy/Tortoise models if detected |
| `version_info` | FastAPI version, Uvicorn config, Python version |

**Detection:** `fastapi` found in requirements.txt / pyproject.toml.

### Flask Meta-Tool (~10 actions)

| Action | What it does |
|---|---|
| `routes` | All registered routes, blueprints, methods |
| `blueprints` | Blueprint structure, prefixes, registrations |
| `config` | App configuration, environment variables |
| `extensions` | Registered Flask extensions (SQLAlchemy, Login, etc.) |
| `models` | SQLAlchemy models if Flask-SQLAlchemy detected |
| `templates` | Jinja2 template structure, macros, filters |
| `middleware` | Before/after request handlers, error handlers |
| `cli_commands` | Flask CLI commands (click-based) |
| `forms` | WTForms classes if Flask-WTF detected |
| `version_info` | Flask version, extensions versions |

**Detection:** `flask` found in requirements.txt / pyproject.toml.

---

## Python Build/Package Tools

Expand the existing `build` meta-tool to support Python package managers. The tool detects which build system is present and exposes relevant actions.

| Build Tool | Detection | Actions |
|---|---|---|
| **pip** | `requirements.txt` exists | `list_packages`, `outdated`, `show_package`, `dependencies` |
| **poetry** | `pyproject.toml` with `[tool.poetry]` | `list_packages`, `outdated`, `show_package`, `dependencies`, `lock_status`, `scripts` |
| **uv** | `uv.lock` or `[tool.uv]` in pyproject.toml | `list_packages`, `outdated`, `show_package`, `dependencies`, `lock_status` |

These actions are added to the existing `build` meta-tool alongside Maven/Gradle actions. The tool exposes only the actions relevant to the detected build system(s).

---

## Python Test Runner

Integrate pytest into the existing test tooling:

| Capability | Implementation |
|---|---|
| Discover tests | Scan for `test_*.py`, `*_test.py`, pytest markers |
| Run tests | Execute `pytest` with specified path/pattern |
| Run single test | `pytest path::TestClass::test_method` |
| Coverage | `pytest --cov` integration |
| Fixtures | List available fixtures via `pytest --fixtures` |

---

## Python Debug Support

### Basic (PyCharm Community + IntelliJ with PythonCore)

All standard debug actions adapted for Python's debugger:

| Tool | Actions |
|---|---|
| `debug_breakpoints` | line, conditional, exception (catch/uncaught), log breakpoint, enable/disable, remove |
| `debug_step` | step into, step over, step out, run to cursor, resume, pause |
| `debug_inspect` | evaluate expression, get variables, get stack frames, get threads, set value, watches |

Uses PyCharm's `PyDebugProcess` and the platform `XDebugger` API.

### Advanced (PyCharm Professional only)

| Capability | What it does | API |
|---|---|---|
| Django/Flask template debug | Set breakpoints in .html templates | Professional Python plugin |
| Remote interpreter debug | Debug in Docker/SSH/WSL | `PyRemoteDebugProcess` |
| Attach to process | Attach debugger to running Python PID | `PyAttachToProcessAction` |

**Not applicable to Python (skip):**
- Hotswap (JVM-only)
- Field watchpoints (JVM-only)
- Drop frame (limited in Python)

---

## Database Tools

### Universal Availability

Database tools are registered in **all IDE variants** (IC, IU, PC, PY, Other). No DataGrip dependency — pure JDBC.

### Bundled Drivers

| Database | Driver | License | Status |
|---|---|---|---|
| PostgreSQL | postgresql-42.7.3 | BSD-2 | Bundled |
| SQLite | sqlite-jdbc-3.45.3.0 | Apache 2.0 | Bundled |
| MySQL | mysql-connector-j | GPL | **Removed from bundle** — user-supplied via Generic JDBC |
| SQL Server | mssql-jdbc | MIT | Not bundled — user-supplied via Generic JDBC |

Users needing MySQL or SQL Server use the Generic JDBC mode with their own driver on the classpath and a raw JDBC URL. The settings dialog already supports this flow.

---

## Modular System Prompt Architecture

### Problem

The current system prompt is a monolithic 11-section template that describes all tools regardless of IDE environment. This wastes context tokens and can confuse the LLM with descriptions of tools that don't exist.

### Design

The system prompt becomes an assembly of composable sections:

```
┌─────────────────────────────────────────────┐
│  1. Base (universal)                        │  Always included
│     Role, reasoning rules, completion rules │
├─────────────────────────────────────────────┤
│  2. IDE Context (dynamic)                   │  "You are in PyCharm Professional"
│     Product, edition, languages, frameworks │
├─────────────────────────────────────────────┤
│  3. Language Section (conditional)          │  Java/Kotlin guidelines
│     One per active language                 │  OR Python guidelines (not both)
├─────────────────────────────────────────────┤
│  4. Tool Sections (per registered tool)     │  Each tool contributes its own
│     Only registered tools included          │  usage instructions
├─────────────────────────────────────────────┤
│  5. Framework Section (conditional)         │  Spring OR Django OR FastAPI OR Flask
│     Only detected frameworks               │  patterns and conventions
├─────────────────────────────────────────────┤
│  6. Build/Debug Section (conditional)       │  Maven/Gradle OR pip/poetry/uv
│     Matches registered build/debug tools    │  instructions
├─────────────────────────────────────────────┤
│  7. Memory & Skills (universal)             │  Always included
├─────────────────────────────────────────────┤
│  8. User Instructions (universal)           │  Core memory, project context
└─────────────────────────────────────────────┘
```

### Tool Prompt Contribution

Each tool can declare a prompt section that gets included when the tool is registered:

```kotlin
interface AgentTool {
    val name: String
    val description: String
    val promptSection: String?        // null = no prompt contribution
    val promptPlacement: Placement    // TOOL_USAGE, FRAMEWORK, BUILD, DEBUG
    // ... existing methods
}
```

### Language Section Selection

Simple IDE-based selection (no mixed-language merging):

```
IntelliJ (IC/IU) → Java/Kotlin language section
PyCharm (PC/PY)  → Python language section
Other IDEs       → No language section (base tools only)
```

### IDE Context Injection

The system prompt includes an environment context line:

```
You are running in PyCharm Professional 2025.1.
Available languages: Python.
Detected frameworks: Django 5.1.
Build tools: poetry.
Test runner: pytest.
```

### Testing Strategy

Prompt refactoring is high-risk. The testing approach:

1. **Golden snapshot**: Before any changes, capture the exact current system prompt as a reference file.

2. **Dual-write comparison**: Build `ModularSystemPrompt` alongside old `SystemPrompt`. Compare outputs:
   ```kotlin
   @Test
   fun `modular prompt matches monolithic for IntelliJ Ultimate with all plugins`() {
       val old = SystemPrompt.build(project)
       val new = ModularSystemPrompt.assemble(context)
       assertSimilar(old, new, threshold = 0.95)
   }
   ```

3. **Section-level tests**: Each section verifies inclusion/exclusion:
   ```kotlin
   @Test
   fun `Spring section excluded when Spring plugin absent`() {
       val prompt = ModularSystemPrompt.assemble(intellijCommunityContext())
       assertNotContains(prompt, "Spring")
   }
   ```

4. **Structural invariant tests**: Section ordering, no unresolved placeholders, size bounds.

5. **Combinatorial coverage**: Parameterized tests across the IDE product matrix (IC, IU, PC, PY, Other).

---

## Skill Variant System

### Problem

Bundled skills hardcode Java/Spring/Gradle references. A PyCharm user activating the `tdd` skill gets instructions about `@SpringBootTest` and `./gradlew`.

### Design: Language-Variant Skill Files

Each skill can have language-specific variant files:

```
skills/
  tdd/
    SKILL.md              ← base (universal parts)
    SKILL.java.md         ← Java/Kotlin-specific sections
    SKILL.python.md       ← Python-specific sections
  interactive-debugging/
    SKILL.md
    SKILL.java.md
    SKILL.python.md
  brainstorm/
    SKILL.md              ← universal, no variants needed
```

### Loading Logic

`InstructionLoader` assembles the skill by merging base + variant:

```
InstructionLoader.getSkillContent("tdd")
  ↓
Load SKILL.md (base — universal parts)
  ↓
Check IDE product code:
  IntelliJ (IC/IU) → load SKILL.java.md, merge
  PyCharm (PC/PY)  → load SKILL.python.md, merge
  Other            → base only
  ↓
Return assembled content
```

No mixed-language merging. Simple product-code-based selection.

### Skills Requiring Variants

| Skill | Needs variants? | Reason |
|---|---|---|
| interactive-debugging | Yes | Spring/JPA vs Django/FastAPI debugging, JVM vs Python debugger |
| systematic-debugging | Yes | Spring tools vs Python framework tools in investigation |
| tdd | Yes | JUnit/MockK/Gradle vs pytest/poetry/uv |
| subagent-driven | Yes | Gradle verification vs pytest verification |
| writing-plans | Yes | Gradle build commands vs pip/poetry commands |
| brainstorm | No | Universal |
| git-workflow | No | Universal |
| create-skill | No | Universal |
| frontend-design | No | Universal |
| using-skills | No | Universal (meta-skill) |

---

## Bundled Agent Personas

### New Persona

Add `python-engineer` alongside existing `spring-boot-engineer`:

```yaml
# agents/python-engineer.md
---
name: python-engineer
description: Python expert — Django, FastAPI, Flask, pytest, async patterns
tools: [read_file, edit_file, create_file, search_code, glob_files, run_command,
        find_definition, find_references, diagnostics, django, fastapi, flask,
        build, debug_breakpoints, debug_step, debug_inspect]
---

You are a senior Python engineer specializing in web frameworks...
```

### Persona Availability

Personas should respect the same IDE filter:

- `spring-boot-engineer`: only available in IntelliJ (IC/IU) with Spring plugin
- `python-engineer`: only available in PyCharm (PC/PY) or IntelliJ with Python plugin
- All other personas (code-reviewer, architect-reviewer, etc.): universal — they don't reference language-specific tools

---

## Plugin Descriptor Changes

### Current

```xml
<depends>com.intellij.modules.platform</depends>  <!-- All IDEs -->
<depends>com.intellij.modules.java</depends>      <!-- REQUIRED — blocks non-IntelliJ -->
```

### Proposed

```xml
<!-- Required: just the platform — works in ANY JetBrains IDE -->
<depends>com.intellij.modules.platform</depends>

<!-- Optional: deep features unlock when available -->
<depends optional="true" config-file="plugin-withJava.xml">com.intellij.modules.java</depends>
<depends optional="true" config-file="plugin-withPython.xml">com.intellij.python</depends>
<depends optional="true" config-file="plugin-withPythonCore.xml">PythonCore</depends>
<depends optional="true" config-file="plugin-withSpring.xml">com.intellij.spring</depends>
<depends optional="true" config-file="plugin-withGit.xml">Git4Idea</depends>
<depends optional="true" config-file="plugin-withMaven.xml">org.jetbrains.idea.maven</depends>
<depends optional="true" config-file="plugin-withCoverage.xml">Coverage</depends>
```

This allows the plugin to install on any JetBrains IDE. Language-specific extensions, services, and tool registrations go into the conditional config files.

---

## Mixed-Language Projects

When the IDE supports multiple languages (e.g., IntelliJ Ultimate with Python plugin), tool dispatch is **per-file auto-detection**:

- Operating on a `.py` file → Python provider
- Operating on a `.java` / `.kt` file → Java/Kotlin provider
- Both providers loaded simultaneously
- Framework tools for both Spring and Django can be available if both are detected

However, **system prompt and skills use simple IDE-based selection** (not per-file):
- IntelliJ → Java/Kotlin language section + skill variants
- PyCharm → Python language section + skill variants

This is a pragmatic trade-off: tools need per-file accuracy, but the prompt and skills work better with a single coherent language context.

---

## Non-Functional Requirements

### Graceful Degradation

Every capability must handle the absence of its backing plugin gracefully:
- Tool registration is wrapped in try-catch — failure logs a warning, doesn't crash the agent
- Provider resolution returns null for unsupported languages — the tool returns a clear error message
- Framework detection failure → framework tools simply not registered
- The agent remains fully functional with just the base tools in any JetBrains IDE

### Token Efficiency

- Prompt sections only included for registered tools — smaller prompt in simpler environments
- Deferred tools not sent initially — discovered via `tool_search`
- Framework tools conditional on project detection — no Django tool in a Spring project

### Backward Compatibility

- Existing IntelliJ users see zero changes — same tools, same prompt, same behavior
- The Java/Kotlin provider wraps existing code, doesn't rewrite it
- All existing tests continue to pass unchanged

---

## Deferred Tool Discovery — Making the LLM Prefer Specialized Tools

### Problem

The LLM prefers always-visible core tools (`glob_files`, `search_code`) over more powerful deferred tools (`spring(action="endpoints")`, `django(action="models")`, `call_hierarchy`). Example:

```
User: "What endpoints does the API expose?"

What happens:   Agent uses glob_files("**/*Controller.java") + search_code("@PostMapping")
                → Finds annotations, but misses: runtime URLs, middleware, security config

What should happen: Agent uses tool_search("endpoints") → discovers spring(action="endpoints")
                    → Returns actual URL paths, HTTP methods, request/response types, middleware chain
```

This is not a bug in the LLM — it's a structural problem with three root causes.

### Root Cause Analysis

**1. Positional Bias (BiasBusters, 2025)**

LLMs concentrate heavily on tools that appear earlier in the prompt. Core tools are listed first in the CAPABILITIES section. The deferred tool catalog sits in section 6b — the "Lost in the Middle" zone (Stanford) where attention is lowest.

**2. No Task-to-Tool Routing**

The LLM must independently decide to use `tool_search` before knowing what it will find. There's no signal that says "for this type of question, a specialized tool exists." The cognitive load is: "I could use glob_files (which I know works) or gamble on tool_search (which might find something better)."

**3. Generic Tools Always Work**

`glob_files` + `search_code` can answer almost any question — just with lower quality. The LLM never "fails" using generic tools, so it never learns to reach for specialized ones. There's no feedback loop.

### Solution: Multi-Pronged Approach

No single fix addresses all three root causes. The solution combines four techniques:

#### Technique 1: Contextual Tool Hints in the System Prompt

Instead of a passive catalog, inject **task-oriented hints** that map common questions to the right tool:

```
## When to Use Specialized Tools (via tool_search)

Before using glob_files or search_code for these tasks, use tool_search first:

| If you need to...                        | Search for...      | Instead of...                    |
|------------------------------------------|--------------------|---------------------------------|
| Find API endpoints or routes             | "endpoints"        | Grepping for @PostMapping        |
| Understand class/type relationships      | "type_hierarchy"   | Manually reading extends/impl    |
| Trace who calls a function               | "call_hierarchy"   | Grepping for function name       |
| Check test coverage                      | "coverage"         | Reading coverage reports manually |
| Inspect database schema                  | "db_schema"        | Reading migration files          |
| Understand Spring beans/config           | "spring"           | Grepping for @Bean/@Component    |
| Analyze Django models/URLs               | "django"           | Reading models.py manually       |
| Check code quality issues                | "run_inspections"  | Running linter via run_command   |
| Rename across codebase                   | "refactor_rename"  | Find-and-replace via edit_file   |
```

This table exploits the "Instructions after data = 30% better recall" finding (Anthropic) by placing actionable guidance after the tool catalog.

#### Technique 2: Tool Category Hints in IdeContext

The IDE context line (injected at prompt top, primacy zone) includes a one-liner about available specialized categories:

```
You are running in IntelliJ IDEA Ultimate 2025.1.
Available languages: Java, Kotlin.
Detected frameworks: Spring Boot 3.4.
Build tools: Gradle.
Specialized tools available via tool_search: spring, build, debug, database, coverage, code-quality.
```

This puts tool awareness in the highest-attention zone without listing full schemas.

#### Technique 3: Proactive Tool Suggestions from tool_search

When `tool_search` is called, it should return not just the requested tool but also **related tools** the LLM might not know to ask for:

```
Current: tool_search("spring") → returns spring tool schema

Proposed: tool_search("spring") → returns spring tool schema
          + "Related tools you may also need: build, coverage, db_schema"
```

This creates a discovery chain — one search leads to awareness of adjacent capabilities.

#### Technique 4: Framework-Aware Tool Promotion

When a framework is detected (Spring, Django, etc.), **promote** the framework meta-tool from deferred to core. This is the most aggressive fix: if the project is a Django project, the `django` tool should be in the always-visible core set, not hidden behind `tool_search`.

The promotion logic:

```kotlin
fun registerToolsForContext(registry: ToolRegistry, context: IdeContext) {
    // Always core
    registerCoreTools(registry)

    // Promote framework tools to core if framework is detected
    if (Framework.SPRING in context.detectedFrameworks) {
        registry.registerCore(SpringTool())  // promoted from deferred
    }
    if (Framework.DJANGO in context.detectedFrameworks) {
        registry.registerCore(DjangoTool())  // promoted from deferred
    }
    if (Framework.FASTAPI in context.detectedFrameworks) {
        registry.registerCore(FastApiTool())  // promoted from deferred
    }
    if (Framework.FLASK in context.detectedFrameworks) {
        registry.registerCore(FlaskTool())  // promoted from deferred
    }

    // Build tool promoted to core (always relevant)
    registry.registerCore(BuildTool())  // promoted from deferred

    // Everything else stays deferred
    registerDeferredTools(registry)
}
```

This adds at most 1-2 framework tools to the core set (a project is rarely Spring + Django + Flask simultaneously). The token cost is ~200-400 tokens per promoted tool — well worth the improvement in tool selection.

### Expected Behavior After Fix

```
User: "What endpoints does the API expose?"

In a Spring project (IntelliJ):
  → spring is core (promoted) → agent uses spring(action="endpoints") directly
  → Returns: GET /api/users, POST /api/users, PUT /api/users/{id}, ...
  → With: request/response types, middleware, security annotations

In a Django project (PyCharm):
  → django is core (promoted) → agent uses django(action="urls") directly
  → Returns: /api/users/ (UserViewSet), /admin/ (admin.site), ...
  → With: view functions, URL names, middleware stack

In a project with no framework detected:
  → No framework tool promoted → agent uses glob_files + search_code (correct fallback)
```

### Tradeoffs

| Technique | Token cost | Effectiveness | Risk |
|---|---|---|---|
| Task-to-tool hints table | ~300 tokens | Medium — helps if LLM reads it | May be ignored in mid-prompt |
| IdeContext category hints | ~30 tokens | Medium — primacy zone placement | Very brief, may not trigger action |
| Related tool suggestions | 0 (runtime) | Medium — creates discovery chains | Only helps after first search |
| Framework tool promotion | ~200-400 tokens | High — tool is always visible | Slightly larger core tool set |

All four should be implemented together. The combination addresses all three root causes:
- Positional bias → promotion + IdeContext hints (primacy zone)
- No task-to-tool routing → hints table + related suggestions
- Generic tools always work → promoted tools are the obvious first choice

---

## Out of Scope

- **Other IDE deep support**: Go, JavaScript, Rust, C++ language providers (future work — just add a new provider)
- **Cross-IDE agent communication**: Planned separately (see `project_cross_ide_communication.md`)

# IDE Tool Robustness — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix 11 CRITICAL and 24 highest-impact HIGH issues across all IDE-level tools, identified by a 4-domain audit (debug, runtime/coverage/build, code quality/PSI, framework tools). These are bugs that cause the LLM to receive incorrect, incomplete, or misleading information — leading to doom loops, wasted context, and wrong actions.

**Architecture:** Surgical fixes grouped by shared root cause. No new features — only correctness, safety, and output quality improvements to existing tools.

**Tech Stack:** Kotlin 2.1.10, IntelliJ Platform SDK, JUnit 5 + MockK

**Audit documents (read these for full context):**
- `docs/research/2026-04-14-debug-tool-audit.md`
- `docs/research/2026-04-14-runtime-coverage-build-tool-audit.md`
- `docs/research/2026-04-14-code-quality-psi-tool-audit.md`
- `docs/research/2026-04-14-framework-tool-audit.md`

---

## Phase 1: CRITICAL Fixes (11 issues — fix first, highest impact)

### Task 1: Fix call_hierarchy infinite recursion

**Problem:** `collectCallers()` in both `JavaKotlinProvider` and `PythonProvider` has NO `visited` set. Recursive call chains (A calls B, B calls A) cause stack overflow.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ide/JavaKotlinProvider.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ide/PythonProvider.kt`

**Fix:**

- [ ] **Step 1: Add `visited` set to `findCallers()` in JavaKotlinProvider**

In the `findCallers` method, add a `visited: MutableSet<PsiElement>` parameter (or local set) that tracks already-visited elements. Before recursing, check `if (element in visited) return`. Use element identity (not equality) via `IdentityHashMap` or `SmartPsiElementPointer`.

```kotlin
override fun findCallers(element: PsiElement, depth: Int, scope: SearchScope): List<CallerInfo> {
    val visited = Collections.newSetFromMap(IdentityHashMap<PsiElement, Boolean>())
    return collectCallersRecursive(element, depth, scope, 0, visited)
}

private fun collectCallersRecursive(
    element: PsiElement, maxDepth: Int, scope: SearchScope,
    currentDepth: Int, visited: MutableSet<PsiElement>
): List<CallerInfo> {
    if (currentDepth >= maxDepth || !visited.add(element)) return emptyList()
    // ... existing ReferencesSearch logic, passing visited to recursive calls
}
```

- [ ] **Step 2: Same fix in PythonProvider**

Apply identical `visited` set pattern to `PythonProvider.findCallers()`.

- [ ] **Step 3: Write test for recursive call chain**

```kotlin
@Test
fun `findCallers handles recursive call chains without stack overflow`() {
    // Verify method returns within timeout when A->B->A cycle exists
    // Use mockk to simulate mutual references
}
```

- [ ] **Step 4: Run tests and commit**

```bash
git commit -m "fix(agent): add cycle detection to call_hierarchy findCallers

Prevents infinite recursion (stack overflow) when methods have recursive
call chains (A calls B, B calls A). Uses IdentityHashMap-based visited
set in both JavaKotlinProvider and PythonProvider."
```

---

### Task 2: Fix diagnostics isError=true for found problems

**Problem:** `SemanticDiagnosticsTool` returns `isError=true` when diagnostic problems are found (line 105). Finding problems is a SUCCESSFUL result — the tool worked correctly. `isError=true` poisons the ReAct loop, causing the agent to treat every file with issues as a tool failure.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/SemanticDiagnosticsTool.kt`

**Fix:**

- [ ] **Step 1: Change isError to false when problems are found**

Find the line where `ToolResult` is constructed with problems and change `isError = true` to `isError = false`. The tool should only return `isError = true` for actual tool failures (file not found, dumb mode, provider error), NOT for "I successfully found problems."

- [ ] **Step 2: Verify no other tools have this pattern**

Grep for `isError = true` across all PSI/IDE tools. Verify that `isError` is only set for actual tool failures, not for successful-but-non-empty results.

- [ ] **Step 3: Run tests and commit**

```bash
git commit -m "fix(agent): diagnostics returns isError=false when problems found

Finding diagnostic problems is a successful result, not a tool error.
isError=true was causing the agent to treat files with issues as tool
failures, poisoning the ReAct loop."
```

---

### Task 3: Fix diagnostics cross-session contamination

**Problem:** `lastEditLineRanges` is a `static ConcurrentHashMap` on `EditFileTool`, shared across ALL agent sessions in the same JVM. Session A's edit range leaks into Session B's diagnostics.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/EditFileTool.kt` (or wherever `lastEditLineRanges` is defined)

**Fix:**

- [ ] **Step 1: Scope lastEditLineRanges per session**

Options (choose the simplest):
1. Key by `sessionId + filePath` instead of just `filePath`
2. Move to session-scoped storage (e.g., on `ConversationSession` or `AgentController`)
3. Use a `WeakReference`-based map that clears when the session is disposed

The simplest fix: prefix the map key with session ID.

```kotlin
// BEFORE
lastEditLineRanges[canonicalPath] = lineRange

// AFTER  
lastEditLineRanges["$sessionId:$canonicalPath"] = lineRange
```

And in `SemanticDiagnosticsTool`, use the same session-scoped key when looking up.

- [ ] **Step 2: Run tests and commit**

```bash
git commit -m "fix(agent): scope lastEditLineRanges per session

Prevents cross-session contamination where session A's edit range
leaks into session B's diagnostics calls."
```

---

### Task 4: Fix optimize_imports missing dumb mode check

**Problem:** `OptimizeImportsTool` has NO dumb mode check. During indexing, it can silently remove imports that haven't been indexed yet (treating used imports as unused).

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/OptimizeImportsTool.kt`

**Fix:**

- [ ] **Step 1: Add dumb mode check at start of execute()**

```kotlin
override suspend fun execute(params: JsonObject, project: Project): ToolResult {
    if (DumbService.isDumb(project)) {
        return ToolResult(
            "IDE is currently indexing. optimize_imports cannot run safely during indexing " +
            "because it may incorrectly identify used imports as unused. Try again in a moment.",
            "Error: IDE indexing",
            ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )
    }
    // ... existing logic
}
```

- [ ] **Step 2: Also add dumb mode check to FormatCodeTool**

Same pattern — `FormatCodeTool` is also missing the check (HIGH severity from audit).

- [ ] **Step 3: Run tests and commit**

```bash
git commit -m "fix(agent): add dumb mode checks to optimize_imports and format_code

Prevents optimize_imports from removing used imports during indexing,
and format_code from operating on incomplete PSI trees."
```

---

### Task 5: Fix debug session resolution inconsistency

**Problem:** `DebugStepTool` uses `controller.getSession()` (agent-only registry) while `DebugInspectTool` uses `IdeStateProbe.debugState()` (platform-aware). User-started debug sessions are invisible to step/resume but visible to evaluate/variables.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugStepTool.kt` (or `DebugStepUtils.kt`)

**Fix:**

- [ ] **Step 1: Unify session resolution**

Port `DebugStepTool` to use the same `IdeStateProbe.debugState()` + `requireSession()` / `requireSuspendedSession()` pattern that `DebugInspectTool` already uses. This makes both tools see user-started sessions.

- [ ] **Step 2: Verify DebugBreakpointsTool session resolution**

Check if `DebugBreakpointsTool` has the same inconsistency for its `start_session` action feedback.

- [ ] **Step 3: Run tests and commit**

```bash
git commit -m "fix(agent): unify debug session resolution across all debug tools

Port DebugStepTool to use IdeStateProbe.debugState() like DebugInspectTool.
Both tools now see user-started debug sessions, preventing contradictory
information that causes doom loops."
```

---

### Task 6: Fix list_breakpoints only showing XLineBreakpoint

**Problem:** `list_breakpoints` only iterates `XLineBreakpoint` instances. Exception breakpoints and field watchpoints set by the agent are completely invisible.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugBreakpointsTool.kt`

**Fix:**

- [ ] **Step 1: Include all breakpoint types in list_breakpoints**

Use `XDebuggerManager.getInstance(project).breakpointManager.allBreakpoints` instead of filtering to `XLineBreakpoint` only. Format each type appropriately:
- Line breakpoints: `file:line [condition] [enabled/disabled]`
- Exception breakpoints: `Exception: ClassName [caught/uncaught] [enabled/disabled]`
- Field watchpoints: `Field: ClassName.fieldName [access/modification] [enabled/disabled]`
- Method breakpoints: `Method: ClassName.methodName [entry/exit] [enabled/disabled]`

- [ ] **Step 2: Run tests and commit**

```bash
git commit -m "fix(agent): list_breakpoints shows all breakpoint types

Previously only showed XLineBreakpoint. Now includes exception breakpoints,
field watchpoints, and method breakpoints with type-specific formatting."
```

---

### Task 7: Fix FastAPI/Flask router prefix resolution

**Problem:** FastAPI routes show `/users` instead of `/api/v1/users` because router prefixes aren't resolved. Flask has the same issue with blueprint `url_prefix`.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/fastapi/FastApiRoutesAction.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/flask/FlaskRoutesAction.kt`

**Fix:**

- [ ] **Step 1: Parse APIRouter prefix in FastAPI**

When scanning for route decorators, also scan for `APIRouter(prefix="...")` definitions. Build a map of `variable_name → prefix`. When a route is found on `@router.get("/users")`, look up the router variable to get the prefix and compose the full path: `prefix + route_path`.

Also scan for `app.include_router(router, prefix="/api/v1")` calls to get prefix overrides.

- [ ] **Step 2: Parse Blueprint url_prefix in Flask**

When scanning for routes, also scan for `Blueprint("name", ..., url_prefix="/api")` definitions. Build a map of `variable_name → url_prefix`. Compose with route paths.

Also scan for `app.register_blueprint(bp, url_prefix="/override")` for prefix overrides.

- [ ] **Step 3: Run tests and commit**

```bash
git commit -m "fix(agent): resolve router/blueprint prefixes in FastAPI and Flask routes

FastAPI: parse APIRouter(prefix=...) and app.include_router(..., prefix=...)
to compose full URL paths. Flask: parse Blueprint(url_prefix=...) and
app.register_blueprint(..., url_prefix=...) for the same."
```

---

### Task 8: Fix Spring naive YAML parser

**Problem:** Spring config/actuator/profiles actions use a custom line-by-line YAML parser that silently fails on multi-line values, flow mappings, anchors/aliases, block scalars.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/spring/SpringHelpers.kt` (or wherever the YAML parser lives)

**Fix:**

- [ ] **Step 1: Replace naive YAML parser with SnakeYAML**

SnakeYAML is already bundled with IntelliJ Platform (used by the YAML plugin). Use `org.yaml.snakeyaml.Yaml` to parse YAML files properly:

```kotlin
import org.yaml.snakeyaml.Yaml

fun parseYaml(content: String): Map<String, Any?> {
    val yaml = Yaml()
    return yaml.load<Map<String, Any?>>(content) ?: emptyMap()
}
```

If SnakeYAML isn't available, use IntelliJ's built-in YAML PSI (`YAMLFile`, `YAMLKeyValue`) to parse YAML files properly.

- [ ] **Step 2: Update all Spring actions that use the YAML parser**

Actions affected: `config`, `boot_actuator`, `boot_endpoints`, `profiles`. Replace `parseYamlProperty()` calls with the new parser.

- [ ] **Step 3: Run tests and commit**

```bash
git commit -m "fix(agent): replace naive YAML parser with SnakeYAML in Spring tools

The custom line-by-line parser silently failed on multi-line values,
flow mappings, anchors/aliases, and block scalars. SnakeYAML handles
all YAML features correctly."
```

---

### Task 9: Fix Spring version_info Gradle support

**Problem:** Spring `version_info` action only works with Maven. Gradle projects get "Maven not configured" error.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/spring/SpringVersionInfoAction.kt`

**Fix:**

- [ ] **Step 1: Add Gradle version detection**

When Maven is not configured, check for `build.gradle` or `build.gradle.kts`. Parse the Spring Boot plugin version from `plugins { id 'org.springframework.boot' version '3.4.0' }` and dependency versions from `implementation("org.springframework.boot:spring-boot-starter-web")` blocks.

- [ ] **Step 2: Run tests and commit**

```bash
git commit -m "fix(agent): add Gradle support to Spring version_info action

Previously only worked with Maven. Now parses Spring Boot version from
Gradle build files when Maven is not configured."
```

---

### Task 10: Fix 6 PSI tools hardcoding Java provider fallback

**Problem:** `type_hierarchy`, `call_hierarchy`, `get_method_body`, `get_annotations`, `find_implementations`, `structural_search` use `registry.forLanguageId("JAVA") ?: registry.forLanguageId("kotlin")` instead of resolving from the target file. Python symbols are completely unreachable.

**Files:**
- Modify: 6 tool files in `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/`

**Fix:**

- [ ] **Step 1: Change all 6 tools to resolve provider from file**

Each of these tools accepts a `symbol` or `class_name` parameter. The fix:

1. If a `file` parameter is provided, resolve provider from the file's language
2. If only a symbol name is given (no file), try each registered provider in order until one finds it
3. Never hardcode `"JAVA"` fallback

```kotlin
// BEFORE (hardcoded Java fallback)
val provider = registry.forLanguageId("JAVA") ?: registry.forLanguageId("kotlin")
    ?: return ToolResult.error("No code intelligence provider available")

// AFTER (file-based or multi-provider resolution)
val provider = if (filePath != null) {
    val psiFile = resolvePsiFile(project, filePath)
    psiFile?.let { registry.forFile(it) }
} else {
    // Try all providers until one finds the symbol
    registry.allProviders().firstNotNullOfOrNull { provider ->
        provider.findSymbol(project, symbolName)?.let { provider }
    }
} ?: return ToolResult.error("No code intelligence provider available for this file type")
```

- [ ] **Step 2: Add `allProviders()` method to LanguageProviderRegistry**

```kotlin
fun allProviders(): List<LanguageIntelligenceProvider> =
    providers.values.distinct()
```

- [ ] **Step 3: Run tests for all 6 tools and commit**

```bash
git commit -m "fix(agent): resolve PSI provider from file language, not hardcoded Java

type_hierarchy, call_hierarchy, get_method_body, get_annotations,
find_implementations, and structural_search now resolve the provider
from the target file's language. Python symbols are now reachable."
```

---

## Phase 2: HIGH Fixes (24 issues — grouped by root cause)

### Task 11: Fix head-biased truncation across all tools

**Problem:** All runtime/coverage/build tools truncate with `content.take(N)`, keeping the beginning and losing the end. Build and test output has the critical info (errors, summary, failure stack traces) at the END.

**Files:**
- Modify: `RuntimeExecTool.kt`, `CoverageTool.kt`, `BuildTool.kt` (and any shared truncation utility)

**Fix:**

- [ ] **Step 1: Create a shared middle-truncation utility**

`RunCommandTool` already has middle-truncation (first 60% + last 40%). Extract this into a shared utility and use it in all runtime/build/coverage tools:

```kotlin
object OutputTruncation {
    fun middleTruncate(content: String, maxChars: Int): String {
        if (content.length <= maxChars) return content
        val headChars = (maxChars * 0.6).toInt()
        val tailChars = maxChars - headChars - 50 // 50 for truncation marker
        val head = content.take(headChars)
        val tail = content.takeLast(tailChars)
        val droppedLines = content.count { it == '\n' } - head.count { it == '\n' } - tail.count { it == '\n' }
        return "$head\n\n--- ($droppedLines lines truncated) ---\n\n$tail"
    }
}
```

- [ ] **Step 2: Replace `content.take(N)` calls with `OutputTruncation.middleTruncate()`**

Find all instances of head-biased truncation in runtime, coverage, and build tools. Replace with middle truncation.

- [ ] **Step 3: Increase run_tests output cap from 4KB to at least 12KB**

The `RUN_TESTS_MAX_OUTPUT_CHARS = 4000` is too small for compilation errors. Increase to match `MAX_RESULT_CHARS` (12000) or use the per-tool `ToolOutputConfig` system.

- [ ] **Step 4: Run tests and commit**

```bash
git commit -m "fix(agent): replace head-biased truncation with middle-truncation

Build/test output has errors and stack traces at the END. Head-biased
truncation was losing the most critical information. Now uses first-60%
+ last-40% pattern from RunCommandTool. Also increases run_tests output
cap from 4KB to 12KB."
```

---

### Task 12: Fix format_code and optimize_imports missing no-op feedback

**Problem:** Both tools always say "Formatted" / "Optimized imports" even when nothing changed. LLM wastes context on no-ops.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/FormatCodeTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/OptimizeImportsTool.kt`

**Fix:**

- [ ] **Step 1: Compare document text before and after formatting**

```kotlin
val textBefore = document.text
// ... reformat ...
val textAfter = document.text

if (textBefore == textAfter) {
    return ToolResult("File already formatted — no changes needed.", "Already formatted", ...)
}

val changedLines = countChangedLines(textBefore, textAfter)
return ToolResult("Formatted ${file.name} ($changedLines lines changed).", ...)
```

- [ ] **Step 2: Same pattern for optimize_imports**

Compare import section before and after. Report "Imports already optimal" or "Removed N unused imports: ..."

- [ ] **Step 3: Run tests and commit**

```bash
git commit -m "fix(agent): format_code and optimize_imports report actual changes

Both tools now compare before/after text and report 'no changes needed'
when nothing changed, or the number of lines/imports changed."
```

---

### Task 13: Fix run_tests requiring class_name despite optional description

**Problem:** Tool description shows `class_name?` (optional) but code requires it. No "run all tests" capability.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/RuntimeExecTool.kt`

**Fix:**

- [ ] **Step 1: Support running all tests when class_name is omitted**

When `class_name` is not provided, find the project's default test configuration or create one that runs all tests in the project/module.

Alternatively, if "run all tests" is intentionally not supported (too expensive), update the description to make `class_name` clearly required and explain why.

- [ ] **Step 2: Run tests and commit**

```bash
git commit -m "fix(agent): support run_tests without class_name (runs all tests)

Or: fix(agent): make class_name clearly required in run_tests description"
```

---

### Task 14: Fix evaluate expression with no timeout

**Problem:** `debug_inspect` `evaluate` action has no timeout on expression evaluation. Hanging expressions block indefinitely.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugInspectTool.kt`

**Fix:**

- [ ] **Step 1: Wrap evaluation with withTimeoutOrNull**

```kotlin
val result = withTimeoutOrNull(10_000L) { // 10 second timeout
    controller.evaluate(expression, frameIndex)
}
if (result == null) {
    return ToolResult("Expression evaluation timed out after 10 seconds. The expression may contain an infinite loop or be waiting for a lock.", ...)
}
```

- [ ] **Step 2: Run tests and commit**

```bash
git commit -m "fix(agent): add 10s timeout to debug evaluate expression

Prevents indefinite hangs when evaluating expressions that contain
infinite loops or wait for locks."
```

---

### Task 15: Fix RunInspectionsTool using isEnabledByDefault instead of profile

**Problem:** `RunInspectionsTool` and `ListQuickFixesTool` use `isEnabledByDefault` filter instead of `profile.isToolEnabled()`. Skips user-enabled inspections that aren't default-enabled.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/RunInspectionsTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/ListQuickFixesTool.kt`

**Fix:**

- [ ] **Step 1: Replace isEnabledByDefault with profile.isToolEnabled()**

```kotlin
// BEFORE
if (!toolWrapper.isEnabledByDefault) continue

// AFTER
if (!profile.isToolEnabled(toolWrapper.shortName, psiFile)) continue
```

- [ ] **Step 2: Run tests and commit**

```bash
git commit -m "fix(agent): use profile.isToolEnabled() for inspection filtering

Previously used isEnabledByDefault which skipped user-enabled inspections.
Now respects the project's active inspection profile."
```

---

### Task 16: Fix RefactorRenameTool not finding Python symbols globally

**Problem:** `findElement()` uses `JavaPsiFacade` which never finds Python classes/functions. Python symbols only found via file-scoped search.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/RefactorRenameTool.kt`

**Fix:**

- [ ] **Step 1: Use LanguageProviderRegistry for symbol resolution**

Accept `LanguageProviderRegistry` in the constructor. When looking up a symbol:

```kotlin
// Try file-scoped search first (if file parameter provided)
// Then try provider registry (works for both Java and Python)
val element = if (filePath != null) {
    findSymbolInFile(project, filePath, symbolName)
} else {
    providerRegistry.allProviders().firstNotNullOfOrNull { it.findSymbol(project, symbolName) }
        ?: findSymbolViaJavaPsiFacade(project, symbolName) // fallback for Java
}
```

- [ ] **Step 2: Run tests and commit**

```bash
git commit -m "fix(agent): RefactorRenameTool finds Python symbols via provider registry

Previously used JavaPsiFacade exclusively. Now delegates to
LanguageProviderRegistry for global symbol lookup, supporting
both Java/Kotlin and Python."
```

---

### Task 17: Fix silent reflection failures in RuntimeConfigTool

**Problem:** `create_run_config` and `modify_run_config` catch and swallow ALL exceptions in reflection-based config setters. Tool reports success even when main_class, VM options, or env vars silently failed to apply.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/RuntimeConfigTool.kt`

**Fix:**

- [ ] **Step 1: Collect and report failed property assignments**

Instead of `catch (_: Exception) {}`, collect failures:

```kotlin
val failures = mutableListOf<String>()
try {
    // set main_class
} catch (e: Exception) {
    failures.add("main_class: ${e.message}")
}
// ... repeat for each property

if (failures.isNotEmpty()) {
    return ToolResult(
        "Created run config '${name}' but some properties failed to apply:\n" +
        failures.joinToString("\n") { "  - $it" },
        "Warning: config created with errors",
        ...
    )
}
```

- [ ] **Step 2: Run tests and commit**

```bash
git commit -m "fix(agent): report failed property assignments in RuntimeConfigTool

Previously swallowed all reflection errors, reporting success when
main_class or env_vars silently failed. Now collects and reports
which properties failed to apply."
```

---

### Task 18: Fix modify_run_config env vars replacement

**Problem:** Setting `env_vars = {"NEW": "val"}` silently replaces ALL existing env vars. The description doesn't warn about this destructive behavior.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/RuntimeConfigTool.kt`

**Fix:**

- [ ] **Step 1: Merge env vars instead of replacing**

```kotlin
// BEFORE
config.envs = newEnvVars

// AFTER
val merged = config.envs.toMutableMap()
merged.putAll(newEnvVars)
config.envs = merged
```

Add a `replace_env_vars` boolean parameter (default false) for cases where full replacement is intended.

- [ ] **Step 2: Run tests and commit**

```bash
git commit -m "fix(agent): merge env vars in modify_run_config instead of replacing

Previously replacing ALL existing env vars when setting new ones.
Now merges by default. Added replace_env_vars parameter for full
replacement when intended."
```

---

### Task 19: Fix Gradle multi-include parsing

**Problem:** The `settings.gradle.kts` parser regex only matches single `include(":")` calls. The common `include(":a", ":b", ":c")` pattern is not parsed.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/build/` (Gradle action files)

**Fix:**

- [ ] **Step 1: Update regex to handle comma-separated includes**

```kotlin
// Match: include(":a"), include(":a", ":b", ":c"), include ":a", ":b"
val includePattern = Regex("""include\s*\(?([^)]+)\)?""")
// Then split the captured group by comma and extract quoted module names
```

- [ ] **Step 2: Run tests and commit**

```bash
git commit -m "fix(agent): parse comma-separated Gradle include statements

Previously only matched single include(':module') calls. Now handles
include(':a', ':b', ':c') pattern common in multi-module projects."
```

---

### Task 20: Fix pip JSON parsing using regex

**Problem:** `parsePipJsonList` and `parsePipJsonOutdated` use regex to parse `pip list --format=json`. Field reordering breaks the parser. `kotlinx.serialization.json` is already a dependency.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/build/PipActions.kt`

**Fix:**

- [ ] **Step 1: Replace regex with kotlinx.serialization.json parser**

```kotlin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

fun parsePipJsonList(output: String): List<PipPackage> {
    val json = Json { ignoreUnknownKeys = true }
    val array = json.parseToJsonElement(output).jsonArray
    return array.map { item ->
        val obj = item.jsonObject
        PipPackage(
            name = obj["name"]?.jsonPrimitive?.content ?: "",
            version = obj["version"]?.jsonPrimitive?.content ?: "",
        )
    }
}
```

- [ ] **Step 2: Run tests and commit**

```bash
git commit -m "fix(agent): use JSON parser for pip output instead of regex

Regex-based parsing broke on field reordering. kotlinx.serialization.json
is already a dependency — use it properly."
```

---

### Task 21: Fix Python framework tools not using PSI

**Problem:** Django, FastAPI, Flask tools use exclusively file I/O + regex. PythonPsiHelper is available but not used. Regex patterns are fragile for Python's indentation-sensitive syntax.

**Files:**
- Modify: Multiple action files in `django/`, `fastapi/`, `flask/` directories

**Fix:**

- [ ] **Step 1: Add PSI-based parsing as primary path with regex fallback**

For each action that currently uses regex-only parsing, add a PSI path:

```kotlin
suspend fun executeModels(params: JsonObject, project: Project): ToolResult {
    val helper = PythonPsiHelper()
    if (helper.isAvailable) {
        return executeModelsViaPsi(params, project, helper)
    }
    // Fallback to regex-based file scanning
    return executeModelsViaRegex(params, project)
}
```

Priority actions for PSI upgrade (highest regex fragility):
1. Django `models` — class inheritance detection, field type extraction
2. Django `views` — decorator detection, class inheritance
3. FastAPI `routes` — decorator argument parsing
4. FastAPI `models` — Pydantic BaseModel subclass detection

- [ ] **Step 2: Add directory exclusion patterns**

Exclude `venv/`, `node_modules/`, `__pycache__/`, `.git/`, `.tox/`, `.mypy_cache/` from file scanning.

```kotlin
private val EXCLUDED_DIRS = setOf("venv", ".venv", "node_modules", "__pycache__",
    ".git", ".tox", ".mypy_cache", ".pytest_cache", "dist", "build", ".eggs")

fun shouldScanDir(dir: VirtualFile): Boolean =
    dir.name !in EXCLUDED_DIRS && !dir.name.startsWith(".")
```

- [ ] **Step 3: Add sensitive value redaction**

In Django `settings` and Flask `config` actions, redact values for keys matching:

```kotlin
private val SENSITIVE_KEYS = setOf("SECRET_KEY", "PASSWORD", "API_KEY", "TOKEN",
    "DATABASE_URL", "PRIVATE_KEY", "AWS_SECRET", "REDIS_URL")

fun redactIfSensitive(key: String, value: String): String =
    if (SENSITIVE_KEYS.any { key.uppercase().contains(it) }) "***REDACTED***" else value
```

- [ ] **Step 4: Run tests and commit**

```bash
git commit -m "fix(agent): Python framework tools use PSI when available, redact secrets

Django/FastAPI/Flask tools now use PythonPsiHelper as primary parsing
path with regex fallback. Added directory exclusions (venv, node_modules,
__pycache__) and sensitive value redaction (SECRET_KEY, passwords, tokens)."
```

---

## Phase 3: Verification

### Task 22: Full test suite and documentation

- [ ] **Step 1: Run full agent test suite**

Run: `./gradlew :agent:test -v`
Expected: ALL PASS

- [ ] **Step 2: Run full project build**

Run: `./gradlew clean buildPlugin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Run plugin verification**

Run: `./gradlew verifyPlugin`
Expected: PASS

- [ ] **Step 4: Update agent CLAUDE.md**

Document the key fixes: middle-truncation pattern, session-scoped edit ranges, dumb mode checks, provider resolution from file language.

- [ ] **Step 5: Commit**

```bash
git commit -m "docs(agent): document IDE tool robustness improvements"
```

---

## Summary

| Phase | Tasks | Issues Fixed | Severity |
|---|---|---|---|
| Phase 1 | Tasks 1-10 | 11 | All CRITICAL |
| Phase 2 | Tasks 11-21 | 24 | All HIGH (highest impact) |
| Phase 3 | Task 22 | — | Verification |

**Remaining after this plan:** 72+ MEDIUM and 66+ LOW issues. These are improvements, not bugs — they can be addressed incrementally in future work.

**Key architectural improvements:**
- Middle-truncation utility (shared across all tools)
- Session-scoped state (no cross-session contamination)
- File-based provider resolution (no hardcoded language fallbacks)
- PSI-primary with regex fallback (Python framework tools)
- Sensitive value redaction (Django/Flask settings)
- Dumb mode checks on all PSI-dependent tools

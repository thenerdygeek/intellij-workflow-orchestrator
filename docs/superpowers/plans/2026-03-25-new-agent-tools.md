# New Agent Tools Implementation Plan (17 Tools)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add 17 new agent tools — 8 IDE intelligence + 9 runtime/debug — to give the AI agent capabilities that can't be replicated by `edit_file` alone.

**Architecture:** Each tool follows the existing `AgentTool` interface pattern (`execute(params, project) -> ToolResult`). IDE tools use `ReadAction.nonBlocking` + `inSmartMode`. Debug tools follow `AgentDebugController` patterns (EDT+WriteAction for breakpoints, `DebuggerCommandImpl` for JDI calls). All tools go in `agent/src/main/kotlin/.../tools/` subdirectories.

**Tech Stack:** IntelliJ Platform SDK (PSI, XDebugger, JDI), Kotlin coroutines, kotlinx.serialization

**Research docs:**
- `docs/research/ide-intelligence-tools-api-research.md` (45KB)
- `docs/research/2026-03-25-debug-runtime-tools-api-research.md` (40KB)

**Scope:** 3 batches — implement and test each batch independently before moving to the next.

---

## Batch 1: IDE Intelligence Tools (8 tools)

### Task 1: Type Inference Tool (`type_inference`)

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/TypeInferenceTool.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/psi/TypeInferenceToolTest.kt`

**API:** `PsiExpression.getType()` (Java), K1 `BindingContext.getType()` via reflection (Kotlin)
**Threading:** `ReadAction.nonBlocking { }.inSmartMode(project).executeSynchronously()`
**Silent fail:** Returns null for uncompiled code, dumb mode, non-typed elements

- [ ] **Step 1: Write failing test**

```kotlin
@Test
fun `returns error when file not found`() {
    val tool = TypeInferenceTool()
    val result = runBlocking { tool.execute(buildJsonObject {
        put("file", JsonPrimitive("/nonexistent.java"))
        put("offset", JsonPrimitive(0))
    }, mockProject) }
    assertTrue(result.isError)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :agent:test --tests "*.TypeInferenceToolTest" -v`
Expected: FAIL — class not found

- [ ] **Step 3: Implement TypeInferenceTool**

```kotlin
class TypeInferenceTool : AgentTool {
    override val name = "type_inference"
    override val description = "Get the resolved type of an expression or variable at a given position. Returns the fully-qualified type, nullability info, and generic type parameters. Java and Kotlin supported."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "file" to ParameterProperty(type = "string", description = "File path (relative or absolute)"),
            "offset" to ParameterProperty(type = "integer", description = "Character offset in the file (0-based)"),
            "line" to ParameterProperty(type = "integer", description = "1-based line number (alternative to offset)"),
            "column" to ParameterProperty(type = "integer", description = "1-based column (used with line)")
        ),
        required = listOf("file")
    )

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        // 1. Resolve file path to VirtualFile
        // 2. Convert line:column to offset if needed
        // 3. ReadAction.nonBlocking in smart mode:
        //    a. Find PsiFile, findElementAt(offset)
        //    b. Walk up to nearest PsiExpression/PsiVariable/PsiMethod
        //    c. Java: expr.type?.presentableText + canonicalText
        //    d. Kotlin: reflection to K1 analyze() -> BindingContext.getType()
        // 4. Return type info with presentable name, FQN, nullability
    }
}
```

Key implementation details:
- Use `PsiTreeUtil.getParentOfType(element, PsiExpression::class.java, PsiVariable::class.java, PsiMethod::class.java)` to find typed element
- For Kotlin, use reflection (same pattern as `PsiToolUtils.kt`): `Class.forName("org.jetbrains.kotlin.psi.KtExpression")` → `.analyze()` → `BindingContext.getType()`
- If in dumb mode, return error: "Index not ready — try again in a few seconds"
- Handle both `offset` param and `line`+`column` params

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :agent:test --tests "*.TypeInferenceToolTest" -v`

- [ ] **Step 5: Register in AgentService and commit**

Add to `AgentService.kt` tool registration, add to `ToolCategoryRegistry` under "psi" category.

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/TypeInferenceTool.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/psi/TypeInferenceToolTest.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt
git commit -m "feat(agent): add type_inference tool — resolve expression types via PSI"
```

---

### Task 2: Structural Search Tool (`structural_search`)

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/StructuralSearchTool.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/psi/StructuralSearchToolTest.kt`

**API:** `Matcher(project, MatchOptions).testFindMatches()`, `MatchOptions`, `MalformedPatternException`
**Threading:** `ReadAction` on background thread
**Silent fail:** Empty results for non-matching patterns; `MalformedPatternException` for invalid syntax

- [ ] **Step 1: Write failing test**

```kotlin
@Test
fun `returns error on malformed pattern`() {
    val tool = StructuralSearchTool()
    val result = runBlocking { tool.execute(buildJsonObject {
        put("pattern", JsonPrimitive("$$$invalid$$$"))
        put("file_type", JsonPrimitive("java"))
    }, mockProject) }
    assertTrue(result.isError)
    assertTrue(result.content.contains("malformed", ignoreCase = true))
}
```

- [ ] **Step 2: Run test to verify it fails**

- [ ] **Step 3: Implement StructuralSearchTool**

```kotlin
class StructuralSearchTool : AgentTool {
    override val name = "structural_search"
    override val description = "Search for code patterns using structural search syntax. More powerful than regex — matches code structure, not text. Use \$var\$ for template variables. Example: 'System.out.println(\$arg\$)' finds all println calls."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "pattern" to ParameterProperty(type = "string", description = "Structural search pattern. Use \$var\$ for template variables."),
            "file_type" to ParameterProperty(type = "string", description = "File type: 'java' or 'kotlin' (default: java)"),
            "scope" to ParameterProperty(type = "string", description = "Search scope: 'project' (default) or a module name"),
            "max_results" to ParameterProperty(type = "integer", description = "Maximum results to return (default: 20)")
        ),
        required = listOf("pattern")
    )

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        // 1. Parse params
        // 2. Resolve LanguageFileType (JavaFileType.INSTANCE or KotlinFileType.INSTANCE)
        // 3. Build MatchOptions with pattern, fileType, scope
        // 4. Validate pattern via Matcher.validate() — catch MalformedPatternException
        // 5. Run Matcher.testFindMatches() inside ReadAction on background thread
        // 6. Format results: file:line — matched text (truncated)
        // 7. Cap at max_results
    }
}
```

Key gotchas:
- `JavaFileType.INSTANCE` requires import `com.intellij.ide.highlighter.JavaFileType`
- `KotlinFileType.INSTANCE` via reflection: `Class.forName("org.jetbrains.kotlin.idea.KotlinFileType").getField("INSTANCE").get(null)`
- Pattern validation MUST happen before matching — `MalformedPatternException` message is user-friendly
- **Do NOT use `testFindMatches()`** — it searches within a provided string, not the project. Use `Matcher(project, options)` with configured `MatchOptions.scope` and call `findMatches()` with a `MatchResultSink` callback to collect results
- Large projects: add timeout and result cap
- Alternative synchronous approach: iterate results via `Matcher.matchByDownUp()` on individual PSI elements

- [ ] **Step 4: Run test**
- [ ] **Step 5: Register and commit**

```bash
git commit -m "feat(agent): add structural_search tool — semantic pattern matching via SSR"
```

---

### Task 3: DataFlow Analysis Tool (`dataflow_analysis`)

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/DataFlowAnalysisTool.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/psi/DataFlowAnalysisToolTest.kt`

**API:** `CommonDataflow.getDfType()`, `DfaNullability.fromDfType()`, `CommonDataflow.getExpressionRange()`, `CommonDataflow.computeValue()`
**Threading:** `ReadAction.nonBlocking` in smart mode
**Silent fail:** Returns `DfType.TOP` (unknown) for unanalyzable expressions. **Java only.**

- [ ] **Step 1: Write failing test**
- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Implement DataFlowAnalysisTool**

```kotlin
class DataFlowAnalysisTool : AgentTool {
    override val name = "dataflow_analysis"
    override val description = "Analyze nullability, value ranges, and constant values at a specific expression in Java code. Returns whether a variable can be null, its possible value range, and constant value if determinable. Java only — does not work on Kotlin files."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "file" to ParameterProperty(type = "string", description = "Java file path"),
            "offset" to ParameterProperty(type = "integer", description = "Character offset (0-based)"),
            "line" to ParameterProperty(type = "integer", description = "1-based line number (alternative to offset)"),
            "column" to ParameterProperty(type = "integer", description = "1-based column (used with line)")
        ),
        required = listOf("file")
    )

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        // 1. Resolve file, must be .java — return error for .kt files
        // 2. Find PsiExpression at offset
        // 3. CommonDataflow.getDfType(expr) → DfType
        // 4. DfaNullability.fromDfType(dfType) → nullability
        // 5. CommonDataflow.getExpressionRange(expr) → LongRangeSet
        // 6. CommonDataflow.computeValue(expr) → constant value
        // 7. Format: "Expression: x, Type: String, Nullability: NOT_NULL, Range: 0..100, Value: 42"
    }
}
```

Key gotchas:
- **Java only** — check file extension, return clear error for Kotlin
- `CommonDataflow.getDfType()` returns `DfType.TOP` when unknown — don't report as "null"
- `DfaNullability.FLUSHED` means "was tracked but lost due to method call" — treat as UNKNOWN
- Expression must be inside a method body — field initializers may not work

- [ ] **Step 4: Run test**
- [ ] **Step 5: Register and commit**

```bash
git commit -m "feat(agent): add dataflow_analysis tool — nullability and value ranges via DFA"
```

---

### Task 4: Read/Write Access Tool (`read_write_access`)

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/ReadWriteAccessTool.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/psi/ReadWriteAccessToolTest.kt`

**API:** `ReferencesSearch.search()` + `PsiUtil.isAccessedForWriting()` / `isAccessedForReading()`
**Threading:** `ReadAction.nonBlocking` in smart mode (search can be slow)
**Silent fail:** Empty results if element is not a `PsiNamedElement`. **Java classification only** (Kotlin needs manual parent analysis).

- [ ] **Step 1: Write failing test**
- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Implement ReadWriteAccessTool**

```kotlin
class ReadWriteAccessTool : AgentTool {
    override val name = "read_write_access"
    override val description = "Find all read and write accesses to a variable, field, or parameter. Shows which code reads the value vs which code modifies it. Useful for understanding data flow and finding unintended mutations."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "file" to ParameterProperty(type = "string", description = "File containing the variable/field declaration"),
            "offset" to ParameterProperty(type = "integer", description = "Character offset of the variable name (0-based)"),
            "line" to ParameterProperty(type = "integer", description = "1-based line number (alternative to offset)"),
            "column" to ParameterProperty(type = "integer", description = "1-based column"),
            "scope" to ParameterProperty(type = "string", description = "Search scope: 'project' (default) or 'file'")
        ),
        required = listOf("file")
    )

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        // 1. Find PsiVariable/PsiField at offset
        // 2. ReferencesSearch.search(element, scope).findAll()
        // 3. For each reference:
        //    a. Get parent PsiExpression
        //    b. PsiUtil.isAccessedForWriting(expr) → write
        //    c. PsiUtil.isAccessedForReading(expr) → read
        //    d. Record file:line + context snippet
        // 4. For Kotlin references: check parent node type manually
        //    (KtBinaryExpression with LEFT operand = write, else read)
        // 5. Format: "Reads (5): file.java:10, ... | Writes (2): file.java:20, ..."
    }
}
```

Key gotchas:
- `PsiUtil.isAccessedForWriting/Reading` is in `com.intellij.psi.util.PsiUtil` (java-psi-api) — Java only
- For Kotlin: check if reference parent is `KtBinaryExpression` with `KtTokens.EQ` and ref is LHS
- `ReferencesSearch.search().findAll()` can be slow on large projects — use `ReadAction.nonBlocking` with timeout
- A `+=` assignment is BOTH a read and a write — `isAccessedForReading` returns true, `isAccessedForWriting` returns true

- [ ] **Step 4: Run test**
- [ ] **Step 5: Register and commit**

```bash
git commit -m "feat(agent): add read_write_access tool — classify references as reads vs writes"
```

---

### Task 5: Test Finder Tool (`test_finder`)

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/TestFinderTool.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/psi/TestFinderToolTest.kt`

**API:** `TestFinder.EP_NAME.extensionList` → `findTestsForClass()` / `findClassesForTest()`
**Threading:** `ReadAction.nonBlocking` in smart mode
**Silent fail:** Empty results if naming conventions don't match

- [ ] **Step 1: Write failing test**
- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Implement TestFinderTool**

```kotlin
class TestFinderTool : AgentTool {
    override val name = "test_finder"
    override val description = "Find the test class for a source class, or the source class for a test class. Uses IntelliJ's test framework integration (JUnit4, JUnit5, TestNG). Convention-based: looks for FooTest, FooTests, TestFoo patterns."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "file" to ParameterProperty(type = "string", description = "Source or test file path"),
            "class_name" to ParameterProperty(type = "string", description = "Optional: specific class name within the file")
        ),
        required = listOf("file")
    )

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        // 1. Find PsiFile, then PsiClass (by name or first class in file)
        // 2. Iterate TestFinder.EP_NAME.extensionList
        // 3. For each finder: findSourceElement() to determine if test or source
        // 4. If test → findClassesForTest(), if source → findTestsForClass()
        // 5. Format: list of found classes with file paths
    }
}
```

- [ ] **Step 4: Run test**
- [ ] **Step 5: Register and commit**

```bash
git commit -m "feat(agent): add test_finder tool — find tests for source and vice versa"
```

---

### Task 6: Module Dependency Graph Tool (`module_dependency_graph`)

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/ModuleDependencyGraphTool.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/framework/ModuleDependencyGraphToolTest.kt`

**API:** `ModuleManager`, `ModuleRootManager.getDependencies()`, `OrderEnumerator.recursively()`
**Threading:** `ReadAction` (works in dumb mode)
**Silent fail:** Empty deps for no-dep modules. Null module entries for unloaded deps.

- [ ] **Step 1: Write failing test**
- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Implement ModuleDependencyGraphTool**

```kotlin
class ModuleDependencyGraphTool : AgentTool {
    override val name = "module_dependency_graph"
    override val description = "Get the project's module dependency graph. Shows direct and transitive dependencies, detects circular dependencies, and lists library dependencies per module."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "module" to ParameterProperty(type = "string", description = "Specific module name (optional — shows all modules if omitted)"),
            "transitive" to ParameterProperty(type = "boolean", description = "Include transitive dependencies (default: false)"),
            "include_libraries" to ParameterProperty(type = "boolean", description = "Include library dependencies (default: false)"),
            "detect_cycles" to ParameterProperty(type = "boolean", description = "Run circular dependency detection (default: true)")
        ),
        required = emptyList()
    )

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        // 1. Get all modules via ModuleManager
        // 2. Build adjacency list: module → [dependencies]
        // 3. If transitive: use OrderEnumerator.recursively()
        // 4. If include_libraries: include LibraryOrderEntry items
        // 5. If detect_cycles: DFS cycle detection
        // 6. Format: tree view of dependencies + cycle warnings
    }
}
```

- [ ] **Step 4: Run test**
- [ ] **Step 5: Register and commit**

```bash
git commit -m "feat(agent): add module_dependency_graph tool — deps, transitive closure, cycle detection"
```

---

### Task 7: Changelist/Shelve Tool (`changelist_shelve`)

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/vcs/ChangelistShelveTool.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/vcs/ChangelistShelveToolTest.kt`

**API:** `ChangeListManager` (read), `ChangeListManagerImpl` (write), `ShelveChangesManager`
**Threading:** Mutations async. Shelve on background thread. Unshelve `@CalledInAny`.
**Silent fail:** Changelist mutations are async — not visible immediately. Unshelve conflicts may be silent.

- [ ] **Step 1: Write failing test**
- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Implement ChangelistShelveTool**

```kotlin
class ChangelistShelveTool : AgentTool {
    override val name = "changelist_shelve"
    override val description = "Manage VCS changelists and shelve/unshelve changes. Actions: list (changelists), list_shelves, create (changelist), shelve (current changes), unshelve (shelved changes). Shelving saves your changes and reverts the working tree."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "action" to ParameterProperty(type = "string", description = "Action: list, list_shelves, create, shelve, unshelve"),
            "name" to ParameterProperty(type = "string", description = "Changelist name (for create action)"),
            "comment" to ParameterProperty(type = "string", description = "Description (for create/shelve)"),
            "shelf_index" to ParameterProperty(type = "integer", description = "Index of shelf to unshelve (from list_shelves)")
        ),
        required = listOf("action")
    )
}
```

- [ ] **Step 4: Run test**
- [ ] **Step 5: Register and commit**

```bash
git commit -m "feat(agent): add changelist_shelve tool — create changelists, shelve/unshelve changes"
```

---

### Task 8: Problem View Tool (`problem_view`)

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/ProblemViewTool.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/ide/ProblemViewToolTest.kt`

**API:** `WolfTheProblemSolver.isProblemFile()`, `DocumentMarkupModel.forDocument()` → `HighlightInfo`
**Threading:** `ReadAction` for both `WolfTheProblemSolver` and `DocumentMarkupModel.forDocument()`. Document must be committed.
**Silent fail:** `HighlightInfo` only populated for editor-opened files. Won't have data for unopened files.

- [ ] **Step 1: Write failing test**
- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Implement ProblemViewTool**

```kotlin
class ProblemViewTool : AgentTool {
    override val name = "problem_view"
    override val description = "Get current problems (errors, warnings) from the IDE's analysis. Shows compilation errors, inspection warnings, and unresolved references. Note: only shows problems for files that have been opened in the editor."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "file" to ParameterProperty(type = "string", description = "Specific file to check (optional — lists all problem files if omitted)"),
            "severity" to ParameterProperty(type = "string", description = "Filter by severity: 'error', 'warning', 'all' (default: all)")
        ),
        required = emptyList()
    )
}
```

Key gotcha: Clearly document in the tool description that problems are only available for opened files. If the user asks about problems in a file they haven't opened, suggest using `diagnostics` tool instead (which forces PSI analysis).

- [ ] **Step 4: Run test**
- [ ] **Step 5: Register and commit**

```bash
git commit -m "feat(agent): add problem_view tool — read IDE problems (errors/warnings)"
```

---

### Task 9: Batch registration + integration test for Batch 1

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ToolCategoryRegistry.kt`
- Modify: `agent/CLAUDE.md` — update tool count and table

- [ ] **Step 1: Register all 8 tools in AgentService**

Add to the tool registration block:
```kotlin
// IDE Intelligence (Batch 1)
register(TypeInferenceTool())
register(StructuralSearchTool())
register(DataFlowAnalysisTool())
register(ReadWriteAccessTool())
register(TestFinderTool())
register(ModuleDependencyGraphTool())
register(ChangelistShelveTool())
register(ProblemViewTool())
```

- [ ] **Step 2: Add to ToolCategoryRegistry**

```kotlin
"psi" to listOf("type_inference", "structural_search", "dataflow_analysis", "read_write_access", "test_finder", ...existing...)
"vcs" to listOf("changelist_shelve", ...existing...)
"ide" to listOf("problem_view", ...existing...)
"framework" to listOf("module_dependency_graph", ...existing...)
```

- [ ] **Step 3: Update CLAUDE.md tool count and table**

- [ ] **Step 4: Run all agent tests**

Run: `./gradlew :agent:test`

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(agent): register 8 IDE intelligence tools (105 total)"
```

---

## Batch 2: Debug Tools — Breakpoint Types (3 tools)

### Task 10: Exception Breakpoint Tool (`exception_breakpoint`)

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/ExceptionBreakpointTool.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/debug/ExceptionBreakpointToolTest.kt`

**API:** `JavaExceptionBreakpointType`, `JavaExceptionBreakpointProperties`, `XBreakpointManager.addBreakpoint()`
**Threading:** EDT + WriteAction (same as AddBreakpointTool)

- [ ] **Step 1: Write failing test**
- [ ] **Step 2: Run test**
- [ ] **Step 3: Implement ExceptionBreakpointTool**

```kotlin
class ExceptionBreakpointTool(private val controller: AgentDebugController) : AgentTool {
    override val name = "exception_breakpoint"
    override val description = "Set a breakpoint that triggers when a specific exception is thrown. Much faster than finding the throw site — breaks wherever the exception occurs. Specify caught/uncaught filters."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "exception_class" to ParameterProperty(type = "string", description = "Fully qualified exception class (e.g., 'java.lang.NullPointerException')"),
            "caught" to ParameterProperty(type = "boolean", description = "Break on caught exceptions (default: true)"),
            "uncaught" to ParameterProperty(type = "boolean", description = "Break on uncaught exceptions (default: true)"),
            "condition" to ParameterProperty(type = "string", description = "Optional conditional expression")
        ),
        required = listOf("exception_class")
    )
}
```

Key: Validate exception class FQN format. Warn in output that breakpoint is set but won't trigger if class name is wrong (no way to validate at set time).

- [ ] **Step 4: Run test**
- [ ] **Step 5: Register and commit**

```bash
git commit -m "feat(agent): add exception_breakpoint tool — break on exception types"
```

---

### Task 11: Field Watchpoint Tool (`field_watchpoint`)

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/FieldWatchpointTool.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/debug/FieldWatchpointToolTest.kt`

**API:** `JavaFieldBreakpointType`, `JavaFieldBreakpointProperties`, PSI resolution for field line
**Threading:** EDT + WriteAction for breakpoint. ReadAction for PSI field lookup.

- [ ] **Step 1: Write failing test**
- [ ] **Step 2: Run test**
- [ ] **Step 3: Implement FieldWatchpointTool**

```kotlin
class FieldWatchpointTool(private val controller: AgentDebugController) : AgentTool {
    override val name = "field_watchpoint"
    override val description = "Set a watchpoint that triggers when a field is read or written. Use this to find who is modifying a field. Moderate performance impact."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "file" to ParameterProperty(type = "string", description = "File containing the field"),
            "class_name" to ParameterProperty(type = "string", description = "Fully qualified class name"),
            "field_name" to ParameterProperty(type = "string", description = "Field name to watch"),
            "watch_read" to ParameterProperty(type = "boolean", description = "Break on field read (default: false)"),
            "watch_write" to ParameterProperty(type = "boolean", description = "Break on field write (default: true)")
        ),
        required = listOf("class_name", "field_name")
    )
}
```

Key: Use PSI to find the field declaration line, then `addLineBreakpoint()` with `JavaFieldBreakpointType`. If file not provided, search by class FQN via `JavaPsiFacade.getInstance(project).findClass()`.

- [ ] **Step 4: Run test**
- [ ] **Step 5: Register and commit**

```bash
git commit -m "feat(agent): add field_watchpoint tool — break on field read/write"
```

---

### Task 12: Method Breakpoint Tool (`method_breakpoint`)

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/MethodBreakpointTool.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/debug/MethodBreakpointToolTest.kt`

**API:** `JavaMethodBreakpointType`, `JavaMethodBreakpointProperties`
**Threading:** EDT + WriteAction + ReadAction for PSI method lookup

- [ ] **Step 1: Write failing test**
- [ ] **Step 2: Run test**
- [ ] **Step 3: Implement MethodBreakpointTool**

```kotlin
class MethodBreakpointTool(private val controller: AgentDebugController) : AgentTool {
    override val name = "method_breakpoint"
    override val description = "Set a breakpoint that triggers on method entry and/or exit. Works on interface methods (triggers on all implementations). WARNING: Method breakpoints are significantly slower than line breakpoints — use sparingly."
    // ... parameters: class_name, method_name, watch_entry, watch_exit
}
```

Key: **Must include performance warning in description** so LLM knows to prefer line breakpoints. Use PSI to find method declaration line.

- [ ] **Step 4: Run test**
- [ ] **Step 5: Register and commit**

```bash
git commit -m "feat(agent): add method_breakpoint tool — entry/exit breakpoints with perf warning"
```

---

### Task 13: Register Batch 2 + commit

- [ ] **Step 1: Register 3 tools in AgentService**
- [ ] **Step 2: Run all agent tests**
- [ ] **Step 3: Commit**

```bash
git commit -m "feat(agent): register 3 debug breakpoint tools (108 total)"
```

---

## Batch 3: Debug Tools — Runtime Inspection (6 tools + 1 helper)

### Task 14: AgentDebugController — Add Manager Thread Helper

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/AgentDebugController.kt`

Before implementing JDI-level tools, we need a coroutine wrapper for the debugger manager thread.

- [ ] **Step 1: Add `executeOnManagerThread` helper** (code in Batch 2 Task 10 above was moved here)
- [ ] **Step 2: Test with existing GetDebugStateTool**
- [ ] **Step 3: Commit**

```bash
git commit -m "feat(agent): add executeOnManagerThread helper to AgentDebugController"
```

---

### Task 15: Thread Dump Tool (`thread_dump`)

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/ThreadDumpTool.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/debug/ThreadDumpToolTest.kt`

**API:** `VirtualMachineProxyImpl.allThreads()`, `ThreadReferenceProxyImpl.status()/.frames()`
**Threading:** **Debugger manager thread** via `executeOnManagerThread()`
**Silent fail:** Empty if VM disconnected. `frames()` throws if thread not suspended.

- [ ] **Step 1–5: Implement and test**

```kotlin
class ThreadDumpTool(private val controller: AgentDebugController) : AgentTool {
    override val name = "thread_dump"
    override val description = "Get a thread dump showing all threads, their states (RUNNING, BLOCKED, WAITING), and stack traces. Essential for diagnosing deadlocks and concurrency issues. Requires an active debug session."
    // params: session_id, include_stacks (default true), max_frames (default 20)
}
```

Key: Use `executeOnManagerThread` for all JDI calls. Map `ThreadReference.status()` constants to readable strings. Skip daemon threads optionally. Detect potential deadlocks by finding BLOCKED threads waiting on monitors held by other BLOCKED threads.

```bash
git commit -m "feat(agent): add thread_dump tool — all threads with states and stacks"
```

---

### Task 16: Force Return Tool (`force_return`)

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/ForceReturnTool.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/debug/ForceReturnToolTest.kt`

**API:** `ThreadReferenceProxyImpl.forceEarlyReturn(Value)`, `VirtualMachineProxyImpl.mirrorOf()`
**Threading:** Debugger manager thread
**Silent fail:** Capability check required. Fails on native methods.

- [ ] **Step 1–5: Implement and test**

```kotlin
class ForceReturnTool(private val controller: AgentDebugController) : AgentTool {
    override val name = "force_return"
    override val description = "Force the current method to return immediately with a specified value. Skips remaining method execution. Use to test alternate code paths without code changes. Cannot be used on native methods."
    // params: session_id, return_value (string representation), return_type (null/int/boolean/string/void)
}
```

Key: Check `vmProxy.canForceEarlyReturn()` first. Use `vmProxy.mirrorOf()` to create JDI values. Handle void methods with `VoidValue`. **Needs approval gate** — changes execution flow.

```bash
git commit -m "feat(agent): add force_return tool — force method to return early with value"
```

---

### Task 17: Drop Frame Tool (`drop_frame`)

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DropFrameTool.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/debug/DropFrameToolTest.kt`

**API:** `ThreadReferenceProxyImpl.popFrames(StackFrameProxyImpl)`, `VirtualMachineProxyImpl.canPopFrames()`
**Threading:** Debugger manager thread

- [ ] **Step 1–5: Implement and test**

```kotlin
class DropFrameTool(private val controller: AgentDebugController) : AgentTool {
    override val name = "drop_frame"
    override val description = "Rewind execution to the beginning of the current or a parent method frame. Variable state is NOT reset. Side effects (file writes, network calls) are NOT undone. Cannot drop frames holding locks or native frames."
    // params: session_id, frame_index (default 0 = current frame)
}
```

Key: Check `canPopFrames()`. Clearly warn in output that side effects are not undone. **Needs approval gate.**

```bash
git commit -m "feat(agent): add drop_frame tool — rewind execution to method start"
```

---

### Task 18: HotSwap Tool (`hotswap`)

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/HotSwapTool.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/debug/HotSwapToolTest.kt`

**API:** `HotSwapUI.getInstance(project).reloadChangedClasses()`, `HotSwapStatusListener`
**Threading:** Any thread (schedules internally). Callback on EDT.

- [ ] **Step 1–5: Implement and test**

```kotlin
class HotSwapTool(private val controller: AgentDebugController) : AgentTool {
    override val name = "hotswap"
    override val description = "Apply code changes to a running debug session without restarting. Compiles changed files and reloads classes. Only method body changes are supported — adding/removing methods, fields, or changing signatures will fail."
    // params: session_id, compile_first (default true)
}
```

Key: Wrap `HotSwapStatusListener` in `suspendCancellableCoroutine`. Report success/failure/nothingToReload clearly. **Needs approval gate.**

```bash
git commit -m "feat(agent): add hotswap tool — reload changed classes in running debug session"
```

---

### Task 19: Memory View Tool (`memory_view`)

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/MemoryViewTool.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/debug/MemoryViewToolTest.kt`

**API:** `VirtualMachine.instanceCounts()`, `ReferenceType.instances()`
**Threading:** Debugger manager thread

- [ ] **Step 1–5: Implement and test**

```kotlin
class MemoryViewTool(private val controller: AgentDebugController) : AgentTool {
    override val name = "memory_view"
    override val description = "Count live instances of a class in the JVM heap. Use to detect memory leaks (e.g., Connection objects not being closed). Requires debug session to be paused."
    // params: session_id, class_name (FQN), max_instances (default 100, 0 = count only)
}
```

Key: Check `vm.canGetInstanceInfo()`. `instances(0)` returns ALL instances — dangerous OOM. Always cap with `max_instances`. Use `executeOnManagerThread()`.

```bash
git commit -m "feat(agent): add memory_view tool — count live instances for leak detection"
```

---

### Task 20: Attach to Process Tool (`attach_to_process`)

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/AttachToProcessTool.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/debug/AttachToProcessToolTest.kt`

**API:** `RemoteConfiguration`, `ExecutionEnvironmentBuilder`, `ProgramRunnerUtil`
**Threading:** Configuration creation any thread. `executeConfiguration()` on EDT.

- [ ] **Step 1–5: Implement and test**

```kotlin
class AttachToProcessTool(private val controller: AgentDebugController) : AgentTool {
    override val name = "attach_to_process"
    override val description = "Attach the debugger to an already running JVM. The target JVM must be started with JDWP agent: -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
    // params: host (default localhost), port (required), transport (default socket)
}
```

Key: Same session-start pattern as `StartDebugSessionTool` — listen via `XDebuggerManagerListener`. Create run config with `[Agent]` prefix. **Needs approval gate** — attaching debugger may slow target.

```bash
git commit -m "feat(agent): add attach_to_process tool — attach debugger to running JVM"
```

---

### Task 21: Register Batch 3 + final integration

- [ ] **Step 1: Register all 6 tools in AgentService**
- [ ] **Step 2: Update ToolCategoryRegistry — add "debug" category entries**
- [ ] **Step 3: Update CLAUDE.md — tool count and table**
- [ ] **Step 4: Run full test suite**

Run: `./gradlew :agent:test`

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(agent): register 6 runtime debug tools (114 total)"
```

---

## Summary

| Batch | Tools | Category | Total after |
|-------|-------|----------|-------------|
| 1 | type_inference, structural_search, dataflow_analysis, read_write_access, test_finder, module_dependency_graph, changelist_shelve, problem_view | IDE Intelligence | 105 |
| 2 | exception_breakpoint, field_watchpoint, method_breakpoint | Debug Breakpoints | 108 |
| 3 | thread_dump, force_return, drop_frame, hotswap, memory_view, attach_to_process + manager thread helper | Debug Runtime | 114 |

**Note:** Terminal tool was dropped — `run_command` already provides this via `GeneralCommandLine` + `CapturingProcessHandler`. Stream debugger dropped — no programmatic API. Console output already exists as `GetRunOutputTool`. Coverage dropped — better done via `run_tests` with separate config.

**Dropped count:** 4 tools (terminal, stream debugger, console output, coverage) → 17 net new tools → **114 total**.

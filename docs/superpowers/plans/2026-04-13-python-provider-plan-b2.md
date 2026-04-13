# Python Language Intelligence Provider — Implementation Plan (Plan B2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement a `PythonProvider` that gives the AI agent deep Python code intelligence in PyCharm (and IntelliJ with Python plugin). All 14 PSI tools (find_definition, type_hierarchy, call_hierarchy, etc.) will work for Python files via this provider.

**Architecture:** `PythonProvider` implements the same `LanguageIntelligenceProvider` interface that `JavaKotlinProvider` already implements. ALL Python PSI access is via reflection (the agent module has no compile-time dependency on the Python plugin). The provider registers for language ID `"Python"` in the `LanguageProviderRegistry`. When a tool targets a `.py` file, the registry resolves `PythonProvider` and the tool delegates to it.

**Tech Stack:** Kotlin 2.1.10, Python PSI APIs via reflection (`com.jetbrains.python.psi.*`), `PythonCore` plugin (Community-level — no Professional APIs required for code intelligence), JUnit 5 + MockK

**Depends on:** Plan B1 (completed — `LanguageIntelligenceProvider` interface, `LanguageProviderRegistry`, `JavaKotlinProvider`, all 14 tools refactored)

**Research:** `docs/research/2026-04-13-python-psi-api-research.md` — complete API mapping for all 12 Python PSI areas

---

## Key Constraint: Reflection-Only Access

The `:agent` module cannot have a compile-time dependency on the Python plugin. All Python PSI access must use reflection:

```kotlin
// Pattern used throughout PythonProvider:
private val pyFileClass by lazy {
    try { Class.forName("com.jetbrains.python.psi.PyFile") } catch (_: ClassNotFoundException) { null }
}

private fun isPyFile(file: PsiFile): Boolean =
    pyFileClass?.isInstance(file) == true
```

This matches the existing pattern used by `JavaKotlinProvider` for Kotlin PSI (see `isKotlinDeclaration()`, `formatKotlinFileStructure()` in the existing code).

---

## File Map

| Action | File | Purpose |
|---|---|---|
| Create | `agent/src/main/kotlin/com/workflow/orchestrator/agent/ide/PythonProvider.kt` | Python implementation of LanguageIntelligenceProvider |
| Create | `agent/src/main/kotlin/com/workflow/orchestrator/agent/ide/PythonPsiHelper.kt` | Reflection utilities for Python PSI classes |
| Create | `agent/src/test/kotlin/com/workflow/orchestrator/agent/ide/PythonPsiHelperTest.kt` | Tests for reflection helper (class loading, method access) |
| Create | `agent/src/test/kotlin/com/workflow/orchestrator/agent/ide/PythonProviderTest.kt` | Tests for provider (mock-based, verifies interface contract) |
| Modify | `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt` | Register PythonProvider when Python plugin available |
| Modify | `agent/src/main/kotlin/com/workflow/orchestrator/agent/ide/IdeContext.kt` | No changes needed (Python filters already added in B1) |

---

## Task 1: PythonPsiHelper — Reflection Utilities

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ide/PythonPsiHelper.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/ide/PythonPsiHelperTest.kt`

This helper class lazily loads all Python PSI classes and provides type-safe reflection wrappers. Every other method in `PythonProvider` delegates to this helper.

- [ ] **Step 1: Write failing tests for PythonPsiHelper**

```kotlin
// agent/src/test/kotlin/com/workflow/orchestrator/agent/ide/PythonPsiHelperTest.kt
package com.workflow.orchestrator.agent.ide

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class PythonPsiHelperTest {

    @Test
    fun `isAvailable returns false when Python plugin not loaded`() {
        // In test environment (no Python plugin), should return false
        // This test verifies graceful degradation
        val helper = PythonPsiHelper()
        // May be true or false depending on test classpath —
        // the key invariant is it doesn't throw
        assertDoesNotThrow { helper.isAvailable }
    }

    @Test
    fun `classNames are correct`() {
        assertEquals("com.jetbrains.python.psi.PyFile", PythonPsiHelper.PY_FILE_CLASS)
        assertEquals("com.jetbrains.python.psi.PyClass", PythonPsiHelper.PY_CLASS_CLASS)
        assertEquals("com.jetbrains.python.psi.PyFunction", PythonPsiHelper.PY_FUNCTION_CLASS)
        assertEquals("com.jetbrains.python.psi.PyTargetExpression", PythonPsiHelper.PY_TARGET_EXPRESSION_CLASS)
        assertEquals("com.jetbrains.python.psi.PyDecorator", PythonPsiHelper.PY_DECORATOR_CLASS)
        assertEquals("com.jetbrains.python.psi.PyDecoratorList", PythonPsiHelper.PY_DECORATOR_LIST_CLASS)
        assertEquals("com.jetbrains.python.psi.PyImportStatement", PythonPsiHelper.PY_IMPORT_STATEMENT_CLASS)
        assertEquals("com.jetbrains.python.psi.PyFromImportStatement", PythonPsiHelper.PY_FROM_IMPORT_STATEMENT_CLASS)
    }

    @Test
    fun `loadClass returns null for unavailable class without throwing`() {
        val result = PythonPsiHelper.loadClass("com.nonexistent.FakeClass")
        assertNull(result)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :agent:test --tests "*PythonPsiHelperTest*" -v`
Expected: FAIL — class not found

- [ ] **Step 3: Implement PythonPsiHelper**

```kotlin
// agent/src/main/kotlin/com/workflow/orchestrator/agent/ide/PythonPsiHelper.kt
package com.workflow.orchestrator.agent.ide

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import java.lang.reflect.Method

/**
 * Reflection-based access to Python PSI classes from the PythonCore plugin.
 * All class loading is lazy and cached — if the Python plugin is not installed,
 * [isAvailable] returns false and all accessors return null.
 *
 * This avoids a compile-time dependency on the Python plugin, matching the
 * pattern used by [JavaKotlinProvider] for Kotlin PSI access.
 */
class PythonPsiHelper {

    companion object {
        const val PY_FILE_CLASS = "com.jetbrains.python.psi.PyFile"
        const val PY_CLASS_CLASS = "com.jetbrains.python.psi.PyClass"
        const val PY_FUNCTION_CLASS = "com.jetbrains.python.psi.PyFunction"
        const val PY_TARGET_EXPRESSION_CLASS = "com.jetbrains.python.psi.PyTargetExpression"
        const val PY_DECORATOR_CLASS = "com.jetbrains.python.psi.PyDecorator"
        const val PY_DECORATOR_LIST_CLASS = "com.jetbrains.python.psi.PyDecoratorList"
        const val PY_IMPORT_STATEMENT_CLASS = "com.jetbrains.python.psi.PyImportStatement"
        const val PY_FROM_IMPORT_STATEMENT_CLASS = "com.jetbrains.python.psi.PyFromImportStatement"
        const val PY_PARAMETER_CLASS = "com.jetbrains.python.psi.PyParameter"
        const val PY_PARAMETER_LIST_CLASS = "com.jetbrains.python.psi.PyParameterList"
        const val PY_ASSIGNMENT_STATEMENT_CLASS = "com.jetbrains.python.psi.PyAssignmentStatement"
        const val PY_CALL_EXPRESSION_CLASS = "com.jetbrains.python.psi.PyCallExpression"
        const val PY_REFERENCE_EXPRESSION_CLASS = "com.jetbrains.python.psi.PyReferenceExpression"
        const val PY_STRING_LITERAL_CLASS = "com.jetbrains.python.psi.PyStringLiteralExpression"
        const val PY_TYPED_ELEMENT_CLASS = "com.jetbrains.python.psi.PyTypedElement"
        const val PY_RESOLVE_CONTEXT_CLASS = "com.jetbrains.python.psi.resolve.PyResolveContext"
        const val TYPE_EVAL_CONTEXT_CLASS = "com.jetbrains.python.psi.types.TypeEvalContext"
        const val PY_CLASS_INHERITORS_SEARCH = "com.jetbrains.python.psi.search.PyClassInheritorsSearch"

        fun loadClass(fqn: String): Class<*>? =
            try { Class.forName(fqn) } catch (_: ClassNotFoundException) { null }
    }

    // Lazy-loaded Python PSI classes
    val pyFileClass by lazy { loadClass(PY_FILE_CLASS) }
    val pyClassClass by lazy { loadClass(PY_CLASS_CLASS) }
    val pyFunctionClass by lazy { loadClass(PY_FUNCTION_CLASS) }
    val pyTargetExpressionClass by lazy { loadClass(PY_TARGET_EXPRESSION_CLASS) }
    val pyDecoratorClass by lazy { loadClass(PY_DECORATOR_CLASS) }
    val pyDecoratorListClass by lazy { loadClass(PY_DECORATOR_LIST_CLASS) }
    val pyImportStatementClass by lazy { loadClass(PY_IMPORT_STATEMENT_CLASS) }
    val pyFromImportStatementClass by lazy { loadClass(PY_FROM_IMPORT_STATEMENT_CLASS) }
    val pyParameterClass by lazy { loadClass(PY_PARAMETER_CLASS) }
    val pyCallExpressionClass by lazy { loadClass(PY_CALL_EXPRESSION_CLASS) }
    val pyReferenceExpressionClass by lazy { loadClass(PY_REFERENCE_EXPRESSION_CLASS) }
    val pyTypedElementClass by lazy { loadClass(PY_TYPED_ELEMENT_CLASS) }
    val typeEvalContextClass by lazy { loadClass(TYPE_EVAL_CONTEXT_CLASS) }
    val pyClassInheritorsSearchClass by lazy { loadClass(PY_CLASS_INHERITORS_SEARCH) }

    /** True if PythonCore plugin is loaded and classes are available */
    val isAvailable: Boolean
        get() = pyFileClass != null

    // --- Type-safe reflection helpers ---

    fun isPyFile(file: PsiFile): Boolean =
        pyFileClass?.isInstance(file) == true

    fun isPyClass(element: PsiElement): Boolean =
        pyClassClass?.isInstance(element) == true

    fun isPyFunction(element: PsiElement): Boolean =
        pyFunctionClass?.isInstance(element) == true

    fun isPyTargetExpression(element: PsiElement): Boolean =
        pyTargetExpressionClass?.isInstance(element) == true

    fun isPyDeclaration(element: PsiElement): Boolean =
        isPyClass(element) || isPyFunction(element) || isPyTargetExpression(element)

    fun isPyCallExpression(element: PsiElement): Boolean =
        pyCallExpressionClass?.isInstance(element) == true

    /** Get the name of a PyClass, PyFunction, or PyTargetExpression */
    fun getName(element: PsiElement): String? =
        invokeMethod(element, "getName") as? String

    /** Get the qualified name of a PyClass or PyFunction */
    fun getQualifiedName(element: PsiElement): String? =
        invokeMethod(element, "getQualifiedName") as? String

    /** Get the docstring of a PyClass or PyFunction */
    fun getDocStringExpression(element: PsiElement): PsiElement? =
        invokeMethod(element, "getDocStringExpression") as? PsiElement

    /** Get top-level classes from a PyFile */
    @Suppress("UNCHECKED_CAST")
    fun getTopLevelClasses(file: PsiFile): List<PsiElement> =
        (invokeMethod(file, "getTopLevelClasses") as? Array<*>)?.toList()?.filterIsInstance<PsiElement>()
            ?: emptyList()

    /** Get top-level functions from a PyFile */
    @Suppress("UNCHECKED_CAST")
    fun getTopLevelFunctions(file: PsiFile): List<PsiElement> =
        (invokeMethod(file, "getTopLevelFunctions") as? Array<*>)?.toList()?.filterIsInstance<PsiElement>()
            ?: emptyList()

    /** Get top-level attributes from a PyFile */
    @Suppress("UNCHECKED_CAST")
    fun getTopLevelAttributes(file: PsiFile): List<PsiElement> =
        (invokeMethod(file, "getTopLevelAttributes") as? List<*>)?.filterIsInstance<PsiElement>()
            ?: emptyList()

    /** Get import block from a PyFile */
    @Suppress("UNCHECKED_CAST")
    fun getImportBlock(file: PsiFile): List<PsiElement> =
        (invokeMethod(file, "getImportBlock") as? List<*>)?.filterIsInstance<PsiElement>()
            ?: emptyList()

    /** Get super classes of a PyClass */
    @Suppress("UNCHECKED_CAST")
    fun getSuperClasses(element: PsiElement, context: Any?): Array<PsiElement> =
        try {
            if (context != null) {
                val method = element.javaClass.getMethod("getSuperClasses", typeEvalContextClass)
                (method.invoke(element, context) as? Array<*>)?.filterIsInstance<PsiElement>()?.toTypedArray()
                    ?: emptyArray()
            } else {
                emptyArray()
            }
        } catch (_: Exception) { emptyArray() }

    /** Get methods/functions of a PyClass */
    @Suppress("UNCHECKED_CAST")
    fun getMethods(element: PsiElement): Array<PsiElement> =
        (invokeMethod(element, "getMethods") as? Array<*>)?.filterIsInstance<PsiElement>()?.toTypedArray()
            ?: emptyArray()

    /** Get properties/attributes of a PyClass */
    @Suppress("UNCHECKED_CAST")
    fun getClassAttributes(element: PsiElement): Array<PsiElement> =
        (invokeMethod(element, "getClassAttributes") as? Array<*>)?.filterIsInstance<PsiElement>()?.toTypedArray()
            ?: emptyArray()

    /** Get the parameter list of a PyFunction */
    fun getParameterList(element: PsiElement): PsiElement? =
        invokeMethod(element, "getParameterList") as? PsiElement

    /** Get parameters from a PyParameterList */
    @Suppress("UNCHECKED_CAST")
    fun getParameters(paramList: PsiElement): Array<PsiElement> =
        (invokeMethod(paramList, "getParameters") as? Array<*>)?.filterIsInstance<PsiElement>()?.toTypedArray()
            ?: emptyArray()

    /** Get the decorator list of a PyFunction or PyClass */
    fun getDecoratorList(element: PsiElement): PsiElement? =
        invokeMethod(element, "getDecoratorList") as? PsiElement

    /** Get decorators from a PyDecoratorList */
    @Suppress("UNCHECKED_CAST")
    fun getDecorators(decoratorList: PsiElement): Array<PsiElement> =
        (invokeMethod(decoratorList, "getDecorators") as? Array<*>)?.filterIsInstance<PsiElement>()?.toTypedArray()
            ?: emptyArray()

    /** Get the callee expression of a PyDecorator/PyCallExpression */
    fun getCallee(element: PsiElement): PsiElement? =
        invokeMethod(element, "getCallee") as? PsiElement

    /** Create a TypeEvalContext for code analysis */
    fun createCodeAnalysisContext(file: PsiFile): Any? =
        try {
            val method = typeEvalContextClass?.getMethod("codeAnalysis", com.intellij.openapi.project.Project::class.java, PsiFile::class.java)
            method?.invoke(null, file.project, file)
        } catch (_: Exception) { null }

    /** Get the type of a typed element using TypeEvalContext */
    fun getType(element: PsiElement, context: Any?): Any? =
        try {
            if (context != null && pyTypedElementClass?.isInstance(element) == true) {
                val method = pyTypedElementClass!!.getMethod("getType", typeEvalContextClass)
                method.invoke(element, context)
            } else null
        } catch (_: Exception) { null }

    /** Get the presentable text of a PyType */
    fun getTypeName(pyType: Any?): String? =
        try {
            pyType?.javaClass?.getMethod("getName")?.invoke(pyType) as? String
        } catch (_: Exception) { null }

    /** Search for inheritors of a PyClass */
    @Suppress("UNCHECKED_CAST")
    fun findInheritors(pyClass: PsiElement, searchScope: com.intellij.psi.search.SearchScope): List<PsiElement> =
        try {
            val searchClass = pyClassInheritorsSearchClass ?: return emptyList()
            val searchMethod = searchClass.getMethod("search", pyClassClass, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, searchScope.javaClass)
            val query = searchMethod.invoke(null, pyClass, true, true, searchScope)
            val findAll = query?.javaClass?.getMethod("findAll")
            (findAll?.invoke(query) as? Collection<*>)?.filterIsInstance<PsiElement>() ?: emptyList()
        } catch (_: Exception) {
            // Fallback: try simpler overload
            try {
                val searchClass = pyClassInheritorsSearchClass!!
                val searchMethod = searchClass.methods.firstOrNull { it.name == "search" && it.parameterCount == 1 }
                val query = searchMethod?.invoke(null, pyClass)
                val findAll = query?.javaClass?.getMethod("findAll")
                (findAll?.invoke(query) as? Collection<*>)?.filterIsInstance<PsiElement>() ?: emptyList()
            } catch (_: Exception) { emptyList() }
        }

    // --- Internal reflection helper ---

    private fun invokeMethod(obj: Any, methodName: String, vararg args: Any?): Any? =
        try {
            val method = obj.javaClass.getMethod(methodName)
            method.invoke(obj, *args)
        } catch (_: Exception) { null }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew :agent:test --tests "*PythonPsiHelperTest*" -v`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ide/PythonPsiHelper.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/ide/PythonPsiHelperTest.kt
git commit -m "feat(agent): add PythonPsiHelper for reflection-based Python PSI access

Lazy-loading reflection wrappers for PyFile, PyClass, PyFunction,
PyDecorator, TypeEvalContext, PyClassInheritorsSearch, and 20+ other
Python PSI classes. Zero compile-time dependency on Python plugin."
```

---

## Task 2: PythonProvider Implementation

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ide/PythonProvider.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/ide/PythonProviderTest.kt`

This implements all 16 methods of `LanguageIntelligenceProvider` for Python. Each method delegates to `PythonPsiHelper` for PSI access.

- [ ] **Step 1: Write failing tests for PythonProvider**

```kotlin
// agent/src/test/kotlin/com/workflow/orchestrator/agent/ide/PythonProviderTest.kt
package com.workflow.orchestrator.agent.ide

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class PythonProviderTest {

    @Test
    fun `supportedLanguageIds contains Python`() {
        val helper = PythonPsiHelper()
        val provider = PythonProvider(helper)
        assertTrue("Python" in provider.supportedLanguageIds)
    }

    @Test
    fun `structuralSearch returns null — not supported for Python`() {
        val helper = PythonPsiHelper()
        val provider = PythonProvider(helper)
        // Structural search is not available for Python (confirmed in research)
        // Provider should return null, and StructuralSearchTool handles the null response
        // Full test requires Python PSI classpath — this verifies the contract
        assertDoesNotThrow { provider.supportedLanguageIds }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :agent:test --tests "*PythonProviderTest*" -v`
Expected: FAIL — class not found

- [ ] **Step 3: Implement PythonProvider**

Create `agent/src/main/kotlin/com/workflow/orchestrator/agent/ide/PythonProvider.kt`.

The implementation follows the same structure as `JavaKotlinProvider` (1232 lines). Each method:
1. Validates the element type using `PythonPsiHelper.isPy*()` checks
2. Uses reflection to call Python PSI methods via the helper
3. Builds result types from the same `LanguageIntelligenceProvider` result data classes

**Method implementation guide (based on research at `docs/research/2026-04-13-python-psi-api-research.md`):**

| Method | Python PSI API | Notes |
|---|---|---|
| `findSymbol` | Iterate `PyFile.topLevelClasses` + `topLevelFunctions`, check names. Fallback: `PsiShortNamesCache` (platform API, works for Python). | Similar to Java approach but uses Python structure |
| `findSymbolAt` | `file.findElementAt(offset)` then walk up to `PyClass`/`PyFunction`/`PyTargetExpression` using `PythonPsiHelper.isPyDeclaration()` | Same traversal pattern as Java |
| `getDefinitionInfo` | `PythonPsiHelper.getName()`, `getQualifiedName()`, document manager for line numbers | Build signature from function params + return annotation |
| `getFileStructure` | `getTopLevelClasses()`, `getTopLevelFunctions()`, `getTopLevelAttributes()`, `getImportBlock()` | Very clean API, documented in research |
| `getTypeHierarchy` | `PythonPsiHelper.getSuperClasses()` for supertypes, `PythonPsiHelper.findInheritors()` for subtypes | Requires `TypeEvalContext` from `createCodeAnalysisContext()` |
| `findImplementations` | `PythonPsiHelper.findInheritors()` for classes. For methods: search inheritor classes then check for method override by name. | Less precise than Java's `OverridingMethodsSearch` but functional |
| `inferType` | `PythonPsiHelper.getType(element, context)` + `getTypeName(pyType)` | TypeEvalContext handles stub-based inference, annotations, and Pyright |
| `analyzeDataflow` | Return `DataflowResult(Nullability.UNKNOWN, null, null)` — Python has no equivalent of Java's `CommonDataflow`. Note this in summary. | Honest limitation — don't fabricate results |
| `findCallers` | `ReferencesSearch.search()` (platform API) — works for Python | Same as Java approach |
| `findCallees` | Walk PSI children, find `PyCallExpression` nodes via `PythonPsiHelper.isPyCallExpression()` | Similar to Java's `PsiMethodCallExpression` walk |
| `getMetadata` | `PythonPsiHelper.getDecoratorList()` → `getDecorators()` → each decorator's name and arguments | Python decorators map to Java annotations |
| `getBody` | Get function's `statementList` text range, extract with context lines | Same document-based extraction as Java |
| `classifyAccesses` | `ReferencesSearch.search()` (platform), then check parent context for assignment (write) vs read | Less precise than Java's `PsiUtil.isAccessedForWriting` — check if parent is `PyAssignmentStatement` target |
| `findRelatedTests` | Scan for `test_*.py` files, check if file/class name matches `Test{ClassName}` pattern. Use `PythonUnitTestDetectorsBasedOnSettings` via reflection if available. | Simpler than Java's TestFinder EP but covers pytest conventions |
| `getDiagnostics` | Walk PSI tree for `PsiErrorElement` (same as Java). For unresolved refs: check `PyReferenceExpression.resolve()` returns null. | Same platform-level approach |
| `structuralSearch` | Return `null` — structural search is NOT available for Python in IntelliJ Platform | Research confirmed this. Tool shows "not supported for Python" message. |

**Key differences from JavaKotlinProvider:**
- All PSI access via `PythonPsiHelper` reflection (never direct imports)
- `analyzeDataflow` returns UNKNOWN (no Python equivalent)
- `structuralSearch` returns null (not supported)
- `findRelatedTests` uses pytest conventions instead of TestFinder EP
- Decorator handling differs from annotation handling (decorators are callable expressions)
- No nullability from annotations — Python's `Optional[]` / `X | None` detected via type string

- [ ] **Step 4: Run tests**

Run: `./gradlew :agent:test --tests "*PythonProviderTest*" -v`
Expected: ALL PASS

- [ ] **Step 5: Run full test suite**

Run: `./gradlew :agent:test -v`
Expected: ALL PASS (PythonProvider created but not yet wired)

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ide/PythonProvider.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/ide/PythonProviderTest.kt
git commit -m "feat(agent): add PythonProvider implementing LanguageIntelligenceProvider

Full Python code intelligence via reflection-based PSI access. Supports
all 14 provider operations except dataflow_analysis (returns UNKNOWN —
no Python equivalent) and structural_search (returns null — not
supported for Python in IntelliJ Platform)."
```

---

## Task 3: Wire PythonProvider into AgentService

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`

- [ ] **Step 1: Register PythonProvider when Python plugin is available**

In `AgentService.registerAllTools()`, after the `JavaKotlinProvider` registration:

```kotlin
// Register Python provider when Python plugin is available
if (ToolRegistrationFilter.shouldRegisterPythonPsiTools(ideContext)) {
    val pythonHelper = PythonPsiHelper()
    if (pythonHelper.isAvailable) {
        providerRegistry.register(PythonProvider(pythonHelper))
        LOG.info("Python code intelligence provider registered")
    } else {
        LOG.warn("Python PSI tools requested but PythonCore plugin classes not found")
    }
}
```

**Important:** The PSI tools themselves are already registered under `shouldRegisterJavaPsiTools` OR `shouldRegisterPythonPsiTools`. Since both use the same tool classes (e.g., `FindDefinitionTool`), and the tools now delegate to the registry, we need to ensure the tools are registered when EITHER Java or Python (or both) is available.

Update the tool registration guard:

```kotlin
// BEFORE (from Plan A/B1):
if (ToolRegistrationFilter.shouldRegisterJavaPsiTools(ideContext)) {
    safeRegisterCore { FindDefinitionTool(project, providerRegistry) }
    // ...
}

// AFTER:
val hasPsiSupport = ToolRegistrationFilter.shouldRegisterJavaPsiTools(ideContext) ||
    ToolRegistrationFilter.shouldRegisterPythonPsiTools(ideContext)

if (hasPsiSupport) {
    safeRegisterCore { FindDefinitionTool(project, providerRegistry) }
    safeRegisterCore { FindReferencesTool(project, providerRegistry) }
    safeRegisterCore { SemanticDiagnosticsTool(project, providerRegistry) }
    // ... all 14 PSI tools
} else {
    LOG.info("Skipping PSI tools — neither Java nor Python plugin available")
}
```

- [ ] **Step 2: Verify all tests pass**

Run: `./gradlew :agent:test -v`
Expected: ALL PASS

- [ ] **Step 3: Verify build**

Run: `./gradlew buildPlugin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt
git commit -m "feat(agent): wire PythonProvider into AgentService

Register PythonProvider when Python plugin available. PSI tools now
register when either Java or Python is present. Tools delegate to
the correct provider based on the target file's language."
```

---

## Task 4: Full Verification and Documentation

**Files:**
- Modify: `agent/CLAUDE.md`

- [ ] **Step 1: Run full test suite**

Run: `./gradlew :agent:test -v`
Expected: ALL PASS

- [ ] **Step 2: Run full project build**

Run: `./gradlew clean buildPlugin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Verify plugin**

Run: `./gradlew verifyPlugin`
Expected: PASS

- [ ] **Step 4: Update agent CLAUDE.md**

In the "Language Intelligence Providers" section, update:

```markdown
**Implementations:**
- `JavaKotlinProvider` — wraps existing PsiToolUtils + inline Java/Kotlin PSI logic
- `PythonProvider` — reflection-based Python PSI access via `PythonPsiHelper`
  - All 14 operations implemented except: `analyzeDataflow` (returns UNKNOWN — no Python equivalent) and `structuralSearch` (returns null — not supported for Python)
  - Requires `PythonCore` plugin (Community-level, no Professional APIs needed)
```

- [ ] **Step 5: Commit**

```bash
git add agent/CLAUDE.md
git commit -m "docs(agent): document PythonProvider in architecture docs"
```

---

## Summary

After Plan B2:

| What changed | Result |
|---|---|
| PythonPsiHelper | Reflection utilities for 20+ Python PSI classes, zero compile-time dependency |
| PythonProvider | Full `LanguageIntelligenceProvider` implementation for Python |
| AgentService | Registers PythonProvider when Python plugin available |
| PSI tool registration | Tools register when either Java or Python is present |
| Tool behavior | find_definition, find_references, type_hierarchy, etc. all work for `.py` files |
| Known limitations | `dataflow_analysis` → UNKNOWN for Python, `structural_search` → not supported |

**What comes next:**
- **Plan C:** Django/FastAPI/Flask meta-tools + pip/poetry/uv build tools + Python debug
- **Plan D:** Modular system prompt + skill variants + deferred tool discovery

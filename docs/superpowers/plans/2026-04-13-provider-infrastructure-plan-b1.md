# Language Intelligence Provider Infrastructure — Implementation Plan (Plan B1)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce a `LanguageIntelligenceProvider` interface and `LanguageProviderRegistry` so PSI tools delegate language-specific logic to pluggable providers. Refactor the 14 existing Java/Kotlin PSI tools to use a `JavaKotlinProvider` behind this interface. No new Python code — just restructuring for extensibility.

**Architecture:** Each PSI tool currently hardcodes Java/Kotlin PSI access inline. This plan extracts language-specific logic into a `JavaKotlinProvider` that implements `LanguageIntelligenceProvider`. Tools resolve the correct provider via `LanguageProviderRegistry` based on file language. Code quality tools (format_code, optimize_imports, refactor_rename, run_inspections, problem_view, list_quickfixes) are NOT touched — they use platform APIs that are already language-agnostic.

**Tech Stack:** Kotlin 2.1.10, IntelliJ Platform PSI API, JUnit 5 + MockK

**Depends on:** Plan A (completed — `IdeContext`, `ToolRegistrationFilter`, conditional plugin descriptor)

**Design Spec:** `docs/superpowers/specs/2026-04-13-universal-ide-support-design.md`

**Research:** `docs/research/2026-04-13-python-psi-api-research.md` (informs interface design)

---

## Scope

**IN scope (14 PSI tools to refactor):**

| Tool | Name | Registration | File |
|---|---|---|---|
| FindDefinitionTool | `find_definition` | Core | `tools/psi/FindDefinitionTool.kt` |
| FindReferencesTool | `find_references` | Core | `tools/psi/FindReferencesTool.kt` |
| SemanticDiagnosticsTool | `diagnostics` | Core | `tools/ide/SemanticDiagnosticsTool.kt` |
| FindImplementationsTool | `find_implementations` | Deferred | `tools/psi/FindImplementationsTool.kt` |
| FileStructureTool | `file_structure` | Deferred | `tools/psi/FileStructureTool.kt` |
| TypeHierarchyTool | `type_hierarchy` | Deferred | `tools/psi/TypeHierarchyTool.kt` |
| CallHierarchyTool | `call_hierarchy` | Deferred | `tools/psi/CallHierarchyTool.kt` |
| TypeInferenceTool | `type_inference` | Deferred | `tools/psi/TypeInferenceTool.kt` |
| DataFlowAnalysisTool | `dataflow_analysis` | Deferred | `tools/psi/DataFlowAnalysisTool.kt` |
| GetMethodBodyTool | `get_method_body` | Deferred | `tools/psi/GetMethodBodyTool.kt` |
| GetAnnotationsTool | `get_annotations` | Deferred | `tools/psi/GetAnnotationsTool.kt` |
| TestFinderTool | `test_finder` | Deferred | `tools/psi/TestFinderTool.kt` |
| StructuralSearchTool | `structural_search` | Deferred | `tools/psi/StructuralSearchTool.kt` |
| ReadWriteAccessTool | `read_write_access` | Deferred | `tools/psi/ReadWriteAccessTool.kt` |

**NOT in scope (already language-agnostic):**

| Tool | Why excluded |
|---|---|
| `format_code` | Uses `CodeStyleManager.reformat()` — platform API, works for any language |
| `optimize_imports` | Uses `LanguageImportStatements.INSTANCE.forFile()` — platform API |
| `refactor_rename` | Uses `RenameProcessor` — platform API, handles all languages |
| `run_inspections` | Uses `InspectionProjectProfileManager` — platform API |
| `problem_view` | Uses `DocumentMarkupModel` / `HighlightInfo` — platform API |
| `list_quickfixes` | Uses `InspectionProjectProfileManager` — platform API |

---

## File Map

| Action | File | Purpose |
|---|---|---|
| Create | `agent/src/main/kotlin/com/workflow/orchestrator/agent/ide/LanguageIntelligenceProvider.kt` | Provider interface + result types |
| Create | `agent/src/main/kotlin/com/workflow/orchestrator/agent/ide/LanguageProviderRegistry.kt` | Provider resolution by file language |
| Create | `agent/src/main/kotlin/com/workflow/orchestrator/agent/ide/JavaKotlinProvider.kt` | Java/Kotlin implementation wrapping existing PsiToolUtils logic |
| Create | `agent/src/test/kotlin/com/workflow/orchestrator/agent/ide/LanguageProviderRegistryTest.kt` | Registry resolution tests |
| Modify | `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/PsiToolUtils.kt` | Extract shared utilities, keep as base helper |
| Modify | 14 tool files in `tools/psi/` and `tools/ide/` | Delegate to provider via registry |
| Modify | `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt` | Initialize registry, pass to tools |
| Modify | `agent/src/main/kotlin/com/workflow/orchestrator/agent/ide/IdeContext.kt` | Add Python filter methods to ToolRegistrationFilter |

---

## Task 1: LanguageIntelligenceProvider Interface and Result Types

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ide/LanguageIntelligenceProvider.kt`

The interface exposes primitive operations that tools compose. It does NOT have one method per tool — instead, tools call the operations they need.

- [ ] **Step 1: Create the provider interface and result types**

```kotlin
// agent/src/main/kotlin/com/workflow/orchestrator/agent/ide/LanguageIntelligenceProvider.kt
package com.workflow.orchestrator.agent.ide

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.SearchScope

/**
 * Language-specific code intelligence operations. Each implementation handles
 * one language family (Java/Kotlin, Python, etc.). Tools delegate to the
 * appropriate provider via [LanguageProviderRegistry].
 */
interface LanguageIntelligenceProvider {

    /** Languages this provider handles */
    val supportedLanguageIds: Set<String>

    // --- Symbol Resolution ---

    /** Find a named symbol (class, function, variable) by name in project scope */
    fun findSymbol(project: Project, name: String): PsiElement?

    /** Find a symbol in a specific file at the given offset */
    fun findSymbolAt(file: PsiFile, offset: Int): PsiElement?

    /** Get the definition location of a resolved element */
    fun getDefinitionInfo(element: PsiElement): DefinitionInfo?

    // --- File Structure ---

    /** Get the structure of a file (classes, functions, imports) */
    fun getFileStructure(file: PsiFile, detail: DetailLevel): FileStructureResult

    // --- Type System ---

    /** Get the type hierarchy for a class/type element */
    fun getTypeHierarchy(element: PsiElement): TypeHierarchyResult?

    /** Find implementations/subclasses of a class or overrides of a method */
    fun findImplementations(element: PsiElement, scope: SearchScope): List<ImplementationInfo>

    /** Infer the type of an expression, variable, or parameter */
    fun inferType(element: PsiElement): TypeInferenceResult?

    /** Analyze dataflow for an expression (nullability, range, constant value) */
    fun analyzeDataflow(element: PsiElement): DataflowResult?

    // --- Call Graph ---

    /** Find callers of a function/method */
    fun findCallers(element: PsiElement, depth: Int, scope: SearchScope): List<CallerInfo>

    /** Find callees within a function/method body */
    fun findCallees(element: PsiElement): List<CalleeInfo>

    // --- Metadata ---

    /** Get annotations (Java) or decorators (Python) on an element */
    fun getMetadata(element: PsiElement, includeInherited: Boolean): List<MetadataInfo>

    /** Get the source text of a function/method body */
    fun getBody(element: PsiElement, contextLines: Int): BodyResult?

    // --- Access Analysis ---

    /** Classify usages of a variable as read, write, or read-write */
    fun classifyAccesses(element: PsiElement, scope: SearchScope): AccessClassification

    // --- Test Discovery ---

    /** Find tests associated with a source element, or source for a test */
    fun findRelatedTests(element: PsiElement): TestRelationResult

    // --- Diagnostics ---

    /** Find syntax errors and unresolved references in a file */
    fun getDiagnostics(file: PsiFile, lineRange: IntRange?): List<DiagnosticInfo>

    // --- Structural Search ---

    /** Search for structural patterns (e.g., $Type$.$method$($args$)) */
    fun structuralSearch(project: Project, pattern: String, scope: SearchScope): List<StructuralMatchInfo>?
}

// --- Result Types ---

enum class DetailLevel { MINIMAL, SIGNATURES, FULL }

data class DefinitionInfo(
    val filePath: String,
    val line: Int,
    val signature: String,
    val documentation: String? = null,
    val skeleton: String? = null,
)

data class FileStructureResult(
    val packageOrModule: String?,
    val imports: List<String>,
    val declarations: List<DeclarationInfo>,
    val formatted: String, // Pre-formatted output for tool result
)

data class DeclarationInfo(
    val name: String,
    val kind: String, // "class", "function", "property", "variable", etc.
    val signature: String,
    val line: Int,
    val children: List<DeclarationInfo> = emptyList(),
)

data class TypeHierarchyResult(
    val element: String, // class name
    val supertypes: List<HierarchyEntry>,
    val subtypes: List<HierarchyEntry>,
)

data class HierarchyEntry(
    val name: String,
    val qualifiedName: String,
    val filePath: String?,
    val line: Int?,
)

data class ImplementationInfo(
    val name: String,
    val signature: String,
    val filePath: String,
    val line: Int,
)

data class TypeInferenceResult(
    val typeName: String,
    val qualifiedName: String?,
    val nullability: Nullability,
)

enum class Nullability { NULLABLE, NOT_NULL, UNKNOWN }

data class DataflowResult(
    val nullability: Nullability,
    val valueRange: String?,
    val constantValue: String?,
)

data class CallerInfo(
    val name: String,
    val filePath: String,
    val line: Int,
    val depth: Int,
)

data class CalleeInfo(
    val name: String,
    val filePath: String?,
    val line: Int?,
)

data class MetadataInfo(
    val name: String,
    val qualifiedName: String,
    val parameters: Map<String, String>,
    val isInherited: Boolean = false,
)

data class BodyResult(
    val source: String,
    val startLine: Int,
    val endLine: Int,
)

data class AccessClassification(
    val reads: List<AccessInfo>,
    val writes: List<AccessInfo>,
    val readWrites: List<AccessInfo>,
)

data class AccessInfo(
    val filePath: String,
    val line: Int,
    val context: String,
)

data class TestRelationResult(
    val isTestElement: Boolean,
    val relatedElements: List<TestRelatedInfo>,
)

data class TestRelatedInfo(
    val name: String,
    val filePath: String,
    val line: Int,
    val kind: String, // "test_class", "source_class", etc.
)

data class DiagnosticInfo(
    val message: String,
    val severity: String, // "ERROR", "WARNING"
    val line: Int,
    val filePath: String,
    val isPreExisting: Boolean = false,
)

data class StructuralMatchInfo(
    val matchedText: String,
    val filePath: String,
    val line: Int,
)
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ide/LanguageIntelligenceProvider.kt
git commit -m "feat(agent): add LanguageIntelligenceProvider interface and result types

Define provider interface with 15 operations (symbol resolution, file
structure, type system, call graph, metadata, access analysis, test
discovery, diagnostics, structural search) and 15 result data classes."
```

---

## Task 2: LanguageProviderRegistry

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ide/LanguageProviderRegistry.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/ide/LanguageProviderRegistryTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
// agent/src/test/kotlin/com/workflow/orchestrator/agent/ide/LanguageProviderRegistryTest.kt
package com.workflow.orchestrator.agent.ide

import io.mockk.every
import io.mockk.mockk
import com.intellij.psi.PsiFile
import com.intellij.lang.Language as IjLanguage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class LanguageProviderRegistryTest {

    @Test
    fun `register and resolve provider by language id`() {
        val registry = LanguageProviderRegistry()
        val provider = mockk<LanguageIntelligenceProvider>()
        every { provider.supportedLanguageIds } returns setOf("JAVA", "kotlin")

        registry.register(provider)

        assertEquals(provider, registry.forLanguageId("JAVA"))
        assertEquals(provider, registry.forLanguageId("kotlin"))
        assertNull(registry.forLanguageId("Python"))
    }

    @Test
    fun `resolve provider for PsiFile`() {
        val registry = LanguageProviderRegistry()
        val provider = mockk<LanguageIntelligenceProvider>()
        every { provider.supportedLanguageIds } returns setOf("JAVA")

        registry.register(provider)

        val language = mockk<IjLanguage>()
        every { language.id } returns "JAVA"

        val file = mockk<PsiFile>()
        every { file.language } returns language

        assertEquals(provider, registry.forFile(file))
    }

    @Test
    fun `returns null for unsupported language`() {
        val registry = LanguageProviderRegistry()
        assertNull(registry.forLanguageId("Ruby"))
    }

    @Test
    fun `hasProvider returns correct state`() {
        val registry = LanguageProviderRegistry()
        assertFalse(registry.hasProvider("JAVA"))

        val provider = mockk<LanguageIntelligenceProvider>()
        every { provider.supportedLanguageIds } returns setOf("JAVA")
        registry.register(provider)

        assertTrue(registry.hasProvider("JAVA"))
    }

    @Test
    fun `later registration overrides earlier for same language`() {
        val registry = LanguageProviderRegistry()
        val provider1 = mockk<LanguageIntelligenceProvider>()
        val provider2 = mockk<LanguageIntelligenceProvider>()
        every { provider1.supportedLanguageIds } returns setOf("JAVA")
        every { provider2.supportedLanguageIds } returns setOf("JAVA")

        registry.register(provider1)
        registry.register(provider2)

        assertEquals(provider2, registry.forLanguageId("JAVA"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :agent:test --tests "*LanguageProviderRegistryTest*" -v`
Expected: FAIL — class not found

- [ ] **Step 3: Implement LanguageProviderRegistry**

```kotlin
// agent/src/main/kotlin/com/workflow/orchestrator/agent/ide/LanguageProviderRegistry.kt
package com.workflow.orchestrator.agent.ide

import com.intellij.psi.PsiFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves the appropriate [LanguageIntelligenceProvider] for a given file or language.
 * Thread-safe — providers can be registered from any thread.
 */
class LanguageProviderRegistry {

    private val providers = ConcurrentHashMap<String, LanguageIntelligenceProvider>()

    fun register(provider: LanguageIntelligenceProvider) {
        for (langId in provider.supportedLanguageIds) {
            providers[langId] = provider
        }
    }

    fun forFile(file: PsiFile): LanguageIntelligenceProvider? =
        providers[file.language.id]

    fun forLanguageId(languageId: String): LanguageIntelligenceProvider? =
        providers[languageId]

    fun hasProvider(languageId: String): Boolean =
        languageId in providers
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :agent:test --tests "*LanguageProviderRegistryTest*" -v`
Expected: ALL PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ide/LanguageProviderRegistry.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/ide/LanguageProviderRegistryTest.kt
git commit -m "feat(agent): add LanguageProviderRegistry for file-based provider resolution

Thread-safe registry that maps language IDs to LanguageIntelligenceProvider
implementations. Supports resolution by PsiFile or language ID string."
```

---

## Task 3: JavaKotlinProvider Implementation

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ide/JavaKotlinProvider.kt`

This wraps the existing `PsiToolUtils` logic and the inline Java/Kotlin PSI code from the 14 tools. It does NOT rewrite any logic — it calls the same functions, just behind the provider interface.

- [ ] **Step 1: Create JavaKotlinProvider**

Create `agent/src/main/kotlin/com/workflow/orchestrator/agent/ide/JavaKotlinProvider.kt`.

This class implements `LanguageIntelligenceProvider` by delegating to the existing `PsiToolUtils` methods and reimplementing the inline PSI logic from each tool. The key pattern:

```kotlin
package com.workflow.orchestrator.agent.ide

import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.search.SearchScope
// ... other imports from existing tools

class JavaKotlinProvider(private val project: Project) : LanguageIntelligenceProvider {

    override val supportedLanguageIds = setOf("JAVA", "kotlin")

    // Each method extracts the core PSI logic from the corresponding tool.
    // The tool keeps the parameter parsing, error handling, and output formatting.
    // The provider handles only the language-specific PSI operations.
    
    // ... implementations
}
```

**Implementation approach for each method:**

1. **`findSymbol`** — Extract from `FindDefinitionTool`: call `PsiToolUtils.findClass()` / `PsiToolUtils.findClassAnywhere()`, then search methods/fields within found class.

2. **`findSymbolAt`** — Use `file.findElementAt(offset)?.parent` to get the enclosing declaration.

3. **`getDefinitionInfo`** — Extract from `FindDefinitionTool`: format location using `PsiToolUtils.relativePath()`, build signature using `PsiToolUtils.formatMethodSignature()` or `PsiToolUtils.formatClassSkeleton()`.

4. **`getFileStructure`** — Extract from `FileStructureTool`: use `PsiToolUtils.formatClassSkeleton()` for Java and `PsiToolUtils.formatKotlinFileStructure()` for Kotlin (already uses reflection).

5. **`getTypeHierarchy`** — Extract from `TypeHierarchyTool`: `PsiClass.supers` for supertypes, `ClassInheritorsSearch.search()` for subtypes.

6. **`findImplementations`** — Extract from `FindImplementationsTool`: `OverridingMethodsSearch.search()` for methods, `ClassInheritorsSearch.search()` for classes.

7. **`inferType`** — Extract from `TypeInferenceTool`: `PsiLocalVariable.type`, `PsiParameter.type`, `PsiMethod.returnType`, with Kotlin reflection fallback.

8. **`analyzeDataflow`** — Extract from `DataFlowAnalysisTool`: reflection-based `CommonDataflow.getDfType()`.

9. **`findCallers`** — Extract from `CallHierarchyTool`: `ReferencesSearch.search()` with recursive depth.

10. **`findCallees`** — Extract from `CallHierarchyTool`: `PsiTreeUtil.findChildrenOfType(PsiMethodCallExpression)` + Kotlin `KtCallExpression` via reflection.

11. **`getMetadata`** — Extract from `GetAnnotationsTool`: `PsiModifierListOwner.modifierList.annotations` with optional superclass walk.

12. **`getBody`** — Extract from `GetMethodBodyTool`: `PsiMethod.body.text` with context lines.

13. **`classifyAccesses`** — Extract from `ReadWriteAccessTool`: `PsiUtil.isAccessedForWriting/Reading()` + Kotlin reflection.

14. **`findRelatedTests`** — Extract from `TestFinderTool`: `TestFinder.EP_NAME` extensions.

15. **`getDiagnostics`** — Extract from `SemanticDiagnosticsTool`: `PsiErrorElement` walking + unresolved reference detection.

16. **`structuralSearch`** — Extract from `StructuralSearchTool`: `MatchOptions` + `Matcher` + `CollectingMatchResultSink`.

**Critical rule:** Do NOT rewrite logic. Copy-move the existing inline code into the provider method, keeping the same behavior. The tool class becomes a thin wrapper that:
1. Parses parameters from JSON
2. Resolves the file and provider via registry
3. Calls the provider method
4. Formats the result into a ToolResult string

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :agent:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Run existing tests to verify no regressions**

Run: `./gradlew :agent:test -v`
Expected: ALL PASS (provider is created but not yet wired — existing tools unchanged)

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ide/JavaKotlinProvider.kt
git commit -m "feat(agent): add JavaKotlinProvider implementing LanguageIntelligenceProvider

Wraps existing PsiToolUtils and inline Java/Kotlin PSI logic from the 14
PSI tools behind the LanguageIntelligenceProvider interface. Same behavior,
new structure. Not yet wired — tools still use inline logic."
```

---

## Task 4: Wire Registry into AgentService and Refactor Core PSI Tools

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/FindDefinitionTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/FindReferencesTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/SemanticDiagnosticsTool.kt`

- [ ] **Step 1: Initialize LanguageProviderRegistry in AgentService**

In `AgentService.kt`, in the `registerAllTools()` method, after the `IdeContext` detection:

```kotlin
// Initialize provider registry
val providerRegistry = LanguageProviderRegistry()

if (ToolRegistrationFilter.shouldRegisterJavaPsiTools(ideContext)) {
    providerRegistry.register(JavaKotlinProvider(project))
}

// Store for tool access
this.providerRegistry = providerRegistry
```

Add field:
```kotlin
lateinit var providerRegistry: LanguageProviderRegistry
    private set
```

- [ ] **Step 2: Refactor FindDefinitionTool to use provider**

In `FindDefinitionTool.kt`, change the constructor to accept the registry:

```kotlin
class FindDefinitionTool(
    private val project: Project,
    private val registry: LanguageProviderRegistry,
) : AgentTool {
```

In the `execute` method, resolve the provider from the file:
```kotlin
override suspend fun execute(params: JsonObject): ToolResult<*> {
    val file = resolveFile(params) ?: return ToolResult.error("File not found")
    val psiFile = PsiManager.getInstance(project).findFile(file)
        ?: return ToolResult.error("Cannot parse file")
    
    val provider = registry.forFile(psiFile)
        ?: return ToolResult.error("Code intelligence not available for ${psiFile.language.displayName}")
    
    // Delegate to provider
    val symbolName = params["symbol"]?.jsonPrimitive?.content ?: ...
    val element = provider.findSymbol(project, symbolName)
        ?: return ToolResult.error("Symbol '$symbolName' not found")
    val info = provider.getDefinitionInfo(element)
        ?: return ToolResult.error("Cannot resolve definition")
    
    // Format result (keep existing formatting logic)
    return ToolResult.success(formatDefinitionResult(info))
}
```

Update registration in `AgentService.kt`:
```kotlin
safeRegisterCore { FindDefinitionTool(project, providerRegistry) }
```

- [ ] **Step 3: Refactor FindReferencesTool to use provider**

Same pattern — accept `LanguageProviderRegistry` in constructor, resolve provider from file, delegate symbol resolution to provider. Reference search itself uses platform `ReferencesSearch` API which is already language-agnostic.

Update registration:
```kotlin
safeRegisterCore { FindReferencesTool(project, providerRegistry) }
```

- [ ] **Step 4: Refactor SemanticDiagnosticsTool to use provider**

Accept registry in constructor, delegate `getDiagnostics()` to provider.

Update registration:
```kotlin
safeRegisterCore { SemanticDiagnosticsTool(project, providerRegistry) }
```

- [ ] **Step 5: Run all tests**

Run: `./gradlew :agent:test -v`
Expected: ALL PASS (same behavior, just delegated through provider)

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/FindDefinitionTool.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/FindReferencesTool.kt \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ide/SemanticDiagnosticsTool.kt
git commit -m "refactor(agent): wire LanguageProviderRegistry into core PSI tools

Initialize registry in AgentService, register JavaKotlinProvider. Refactor
FindDefinitionTool, FindReferencesTool, and SemanticDiagnosticsTool to
delegate language-specific logic to the provider via registry."
```

---

## Task 5: Refactor Deferred PSI Tools

**Files:**
- Modify: 11 tool files in `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/`

All follow the same pattern as Task 4:
1. Accept `LanguageProviderRegistry` in constructor
2. Resolve provider from the file being analyzed
3. Delegate the language-specific PSI operation to the provider
4. Keep parameter parsing and output formatting in the tool

- [ ] **Step 1: Refactor FileStructureTool**

Accept registry, delegate `getFileStructure()` to provider. The tool keeps detail level parsing and result formatting.

- [ ] **Step 2: Refactor TypeHierarchyTool**

Accept registry, delegate `getTypeHierarchy()` to provider. The tool keeps max depth/count limits and output formatting.

- [ ] **Step 3: Refactor CallHierarchyTool**

Accept registry, delegate `findCallers()` and `findCallees()` to provider. The tool keeps depth parameter and caller/callee section formatting.

- [ ] **Step 4: Refactor FindImplementationsTool**

Accept registry, delegate `findImplementations()` to provider. The tool keeps max results and abstract/interface filtering parameters.

- [ ] **Step 5: Refactor TypeInferenceTool**

Accept registry, delegate `inferType()` to provider. The tool keeps offset resolution and result formatting.

- [ ] **Step 6: Refactor DataFlowAnalysisTool**

Accept registry, delegate `analyzeDataflow()` to provider. The tool keeps expression resolution and null/range/constant formatting.

- [ ] **Step 7: Refactor GetMethodBodyTool**

Accept registry, delegate `getBody()` to provider. The tool keeps overload handling and context lines parameter.

- [ ] **Step 8: Refactor GetAnnotationsTool**

Accept registry, delegate `getMetadata()` to provider. The tool keeps include_inherited parameter and annotation formatting.

- [ ] **Step 9: Refactor TestFinderTool**

Accept registry, delegate `findRelatedTests()` to provider. The tool keeps direction detection and result formatting.

- [ ] **Step 10: Refactor StructuralSearchTool**

Accept registry, delegate `structuralSearch()` to provider. Return `null` from provider if structural search is not supported for the language (Python will return null — flagged in research).

- [ ] **Step 11: Refactor ReadWriteAccessTool**

Accept registry, delegate `classifyAccesses()` to provider. The tool keeps max results and read/write/readwrite section formatting.

- [ ] **Step 12: Update all registrations in AgentService**

Update every `safeRegisterDeferred` call for deferred PSI tools to pass `providerRegistry`:

```kotlin
safeRegisterDeferred("Code Intelligence") { FindImplementationsTool(project, providerRegistry) }
safeRegisterDeferred("Code Intelligence") { FileStructureTool(project, providerRegistry) }
safeRegisterDeferred("Code Intelligence") { TypeHierarchyTool(project, providerRegistry) }
safeRegisterDeferred("Code Intelligence") { CallHierarchyTool(project, providerRegistry) }
safeRegisterDeferred("Code Intelligence") { TypeInferenceTool(project, providerRegistry) }
safeRegisterDeferred("Code Intelligence") { DataFlowAnalysisTool(project, providerRegistry) }
safeRegisterDeferred("Code Intelligence") { GetMethodBodyTool(project, providerRegistry) }
safeRegisterDeferred("Code Intelligence") { GetAnnotationsTool(project, providerRegistry) }
safeRegisterDeferred("Code Intelligence") { TestFinderTool(project, providerRegistry) }
safeRegisterDeferred("Code Intelligence") { StructuralSearchTool(project, providerRegistry) }
safeRegisterDeferred("Code Intelligence") { ReadWriteAccessTool(project, providerRegistry) }
```

- [ ] **Step 13: Run full test suite**

Run: `./gradlew :agent:test -v`
Expected: ALL PASS

- [ ] **Step 14: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/psi/ \
        agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt
git commit -m "refactor(agent): wire LanguageProviderRegistry into all deferred PSI tools

All 11 deferred PSI tools now accept LanguageProviderRegistry and delegate
language-specific logic to the provider. Same behavior, extensible structure.
Tools: file_structure, type_hierarchy, call_hierarchy, find_implementations,
type_inference, dataflow_analysis, get_method_body, get_annotations,
test_finder, structural_search, read_write_access."
```

---

## Task 6: Add Python Stubs to ToolRegistrationFilter and Verify

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ide/IdeContext.kt`
- Modify: `agent/src/test/kotlin/com/workflow/orchestrator/agent/ide/ContextAwareRegistrationTest.kt`

- [ ] **Step 1: Add Python filter methods to ToolRegistrationFilter**

In `IdeContext.kt`, add to `ToolRegistrationFilter`:

```kotlin
/** Python PSI tools — requires Python plugin (Pro or Core) */
fun shouldRegisterPythonPsiTools(context: IdeContext): Boolean =
    context.supportsPython

/** Python build tools (pip/poetry/uv actions in build meta-tool) */
fun shouldRegisterPythonBuildTools(context: IdeContext): Boolean =
    context.supportsPython

/** Python debug tools — basic (Community) */
fun shouldRegisterPythonDebugTools(context: IdeContext): Boolean =
    context.supportsPython

/** Python debug tools — advanced (Professional only: Django debug, remote interpreter) */
fun shouldRegisterPythonAdvancedDebugTools(context: IdeContext): Boolean =
    context.supportsPythonAdvanced

/** Django meta-tool */
fun shouldRegisterDjangoTools(context: IdeContext): Boolean =
    context.supportsPython && Framework.DJANGO in context.detectedFrameworks

/** FastAPI meta-tool */
fun shouldRegisterFastApiTools(context: IdeContext): Boolean =
    context.supportsPython && Framework.FASTAPI in context.detectedFrameworks

/** Flask meta-tool */
fun shouldRegisterFlaskTools(context: IdeContext): Boolean =
    context.supportsPython && Framework.FLASK in context.detectedFrameworks
```

- [ ] **Step 2: Add tests for Python filter methods**

In `ContextAwareRegistrationTest.kt`, add:

```kotlin
@Test
fun `shouldRegisterPythonPsiTools returns true for PyCharm`() {
    val context = makeContext(
        product = IdeProduct.PYCHARM_PROFESSIONAL,
        hasPythonPlugin = true,
    )
    assertTrue(ToolRegistrationFilter.shouldRegisterPythonPsiTools(context))
}

@Test
fun `shouldRegisterPythonPsiTools returns false for IntelliJ without Python`() {
    val context = makeContext(
        product = IdeProduct.INTELLIJ_COMMUNITY,
        hasJavaPlugin = true,
    )
    assertFalse(ToolRegistrationFilter.shouldRegisterPythonPsiTools(context))
}

@Test
fun `shouldRegisterDjangoTools returns true when Django detected in PyCharm`() {
    val context = makeContext(
        product = IdeProduct.PYCHARM_COMMUNITY,
        hasPythonCorePlugin = true,
        detectedFrameworks = setOf(Framework.DJANGO),
    )
    assertTrue(ToolRegistrationFilter.shouldRegisterDjangoTools(context))
}

@Test
fun `shouldRegisterDjangoTools returns false when Django not detected`() {
    val context = makeContext(
        product = IdeProduct.PYCHARM_PROFESSIONAL,
        hasPythonPlugin = true,
    )
    assertFalse(ToolRegistrationFilter.shouldRegisterDjangoTools(context))
}

@Test
fun `shouldRegisterPythonAdvancedDebugTools requires Professional Python plugin`() {
    val communityContext = makeContext(
        product = IdeProduct.PYCHARM_COMMUNITY,
        hasPythonCorePlugin = true,
    )
    assertFalse(ToolRegistrationFilter.shouldRegisterPythonAdvancedDebugTools(communityContext))

    val proContext = makeContext(
        product = IdeProduct.PYCHARM_PROFESSIONAL,
        hasPythonPlugin = true,
    )
    assertTrue(ToolRegistrationFilter.shouldRegisterPythonAdvancedDebugTools(proContext))
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :agent:test --tests "*ContextAwareRegistrationTest*" -v`
Expected: ALL PASS

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ide/IdeContext.kt \
        agent/src/test/kotlin/com/workflow/orchestrator/agent/ide/ContextAwareRegistrationTest.kt
git commit -m "feat(agent): add Python tool registration filters to ToolRegistrationFilter

Add filter methods for Python PSI, build, debug (basic + advanced),
Django, FastAPI, and Flask tools. Ready for Plan B2/C implementation."
```

---

## Task 7: Full Verification and Documentation

**Files:**
- Modify: `agent/CLAUDE.md`

- [ ] **Step 1: Run full test suite**

Run: `./gradlew :agent:test -v`
Expected: ALL PASS

- [ ] **Step 2: Run full project build**

Run: `./gradlew clean buildPlugin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Verify plugin compatibility**

Run: `./gradlew verifyPlugin`
Expected: PASS

- [ ] **Step 4: Update agent CLAUDE.md**

Add a "Language Intelligence Providers" section after "IDE Context Detection":

```markdown
## Language Intelligence Providers

PSI tools delegate language-specific logic to pluggable providers via `LanguageProviderRegistry`.

**Interface:** `LanguageIntelligenceProvider` (15 operations: symbol resolution, file structure, type hierarchy, implementations, type inference, dataflow, callers/callees, metadata, body, access classification, test discovery, diagnostics, structural search)

**Implementations:**
- `JavaKotlinProvider` — wraps existing PsiToolUtils + inline Java/Kotlin PSI logic
- (Python provider planned — Plan B2)

**Registry:** `LanguageProviderRegistry` resolves provider by `PsiFile.language.id`. Thread-safe (ConcurrentHashMap). Initialized in `AgentService.registerAllTools()`.

**Tool pattern:** Each PSI tool accepts the registry in its constructor, resolves the provider from the target file, delegates the PSI operation, and formats the result. If no provider exists for the language, returns "Code intelligence not available for {language}."

Key files: `ide/LanguageIntelligenceProvider.kt`, `ide/LanguageProviderRegistry.kt`, `ide/JavaKotlinProvider.kt`
```

- [ ] **Step 5: Commit**

```bash
git add agent/CLAUDE.md
git commit -m "docs(agent): add Language Intelligence Provider architecture docs"
```

---

## Summary

After Plan B1:

| What changed | Result |
|---|---|
| Provider interface | 15-operation `LanguageIntelligenceProvider` with typed results |
| Provider registry | `LanguageProviderRegistry` resolves by file language |
| JavaKotlinProvider | Wraps existing PSI logic — same behavior, new structure |
| 14 PSI tools | All delegate to provider via registry |
| 6 code quality tools | Untouched — already language-agnostic |
| Python filters | `ToolRegistrationFilter` ready for Python tool registration |
| Backward compatibility | Zero behavior change for existing IntelliJ users |

**What comes next:**
- **Plan B2:** Implement `PythonProvider` behind the same interface (using `PythonCore` PSI APIs via reflection)
- **Plan C:** Django/FastAPI/Flask meta-tools + pip/poetry/uv build tools + Python debug
- **Plan D:** Modular system prompt + skill variants + deferred tool discovery

# IDE Intelligence Tools — IntelliJ Platform SDK API Research

Research date: 2026-03-25
Target platform: IntelliJ IDEA 2025.1+ (Ultimate)
Existing bundled plugins in `:agent`: `Git4Idea`, `com.intellij.java`, `com.intellij.spring`, `org.jetbrains.kotlin`

---

## 1. Type Inference (`type_inference`)

### Purpose
Get the resolved/inferred type of an expression or declaration at a given offset in a file.

### Exact Classes & Methods

**Java (via `com.intellij.java` bundled plugin — already available):**
- `com.intellij.psi.PsiExpression.getType()` → `PsiType?`
- `com.intellij.psi.PsiVariable.getType()` → `PsiType`
- `com.intellij.psi.PsiMethod.getReturnType()` → `PsiType?`
- `com.intellij.psi.PsiType.getCanonicalText()` → `String`
- `com.intellij.psi.PsiType.getPresentableText()` → `String` (human-friendly)
- `com.intellij.psi.PsiTypesUtil.getClassType(PsiClass)` → `PsiClassType`
- `com.intellij.psi.util.TypeConversionUtil.isAssignable(PsiType, PsiType)` → `boolean`

**Kotlin (via `org.jetbrains.kotlin` bundled plugin — already available):**

K1 API (deprecated but works in 2025.1):
- `org.jetbrains.kotlin.psi.KtExpression` — no `.getType()` method
- `KtExpression.analyze()` → `BindingContext`
- `bindingContext.get(BindingContext.EXPRESSION_TYPE_INFO, expression)` → `KotlinTypeInfo?`
- `bindingContext.getType(expression)` → `KotlinType?`
- `KtDeclaration.resolveToDescriptorIfAny()` → `DeclarationDescriptor?`

K2 API (new, required for 2025.3+):
- `org.jetbrains.kotlin.analysis.api.analyze(element) { ... }` — opens `KaSession`
- Within `KaSession`: `expression.expressionType` → `KaType?`
- `KaType.render(position)` → `String`
- K2 is default in 2025.1, mandatory in 2025.3+

### Threading Requirements
- **ReadAction** required. All PSI access must be inside `ReadAction.compute {}` or `ReadAction.nonBlocking {}`.
- Must NOT be in dumb mode — use `DumbService.isDumb(project)` check or `.inSmartMode(project)`.

### Input Requirements
- File path (absolute) + offset (character position in file)
- Or: `PsiElement` at the offset (obtained via `PsiManager.getInstance(project).findFile(vf)` then `psiFile.findElementAt(offset)`)
- Use `PsiTreeUtil.getParentOfType(element, PsiExpression::class.java)` to walk up to nearest expression

### Output Format
- `PsiType.getPresentableText()` for human-readable: `List<String>`
- `PsiType.getCanonicalText()` for fully-qualified: `java.util.List<java.lang.String>`
- For Kotlin: `KotlinType.toString()` or `KaType.render()`

### Silent Failure Modes
- Returns `null` when: code doesn't compile, element is not a typed expression, index not ready (dumb mode)
- `PsiExpression.getType()` returns `null` for unresolvable expressions
- Kotlin `analyze()` returns empty `BindingContext` if Kotlin plugin inactive
- K2 `expressionType` returns `null` for error expressions

### Platform Compatibility
- Java types: works on all OS, requires `com.intellij.java` plugin (bundled in Ultimate)
- Kotlin types: requires `org.jetbrains.kotlin` plugin (bundled)
- **WARNING**: K1 BindingContext API is being removed. For 2025.1 target, use K1 with reflection fallback to K2.

### Example Usage Pattern
```kotlin
// Java type inference
ReadAction.nonBlocking<String?> {
    val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return@nonBlocking null
    val element = psiFile.findElementAt(offset) ?: return@nonBlocking null
    val expr = PsiTreeUtil.getParentOfType(element, PsiExpression::class.java)
    expr?.type?.presentableText
}.inSmartMode(project).executeSynchronously()

// Kotlin type inference (K1, via reflection to avoid hard dep)
val ktExprClass = Class.forName("org.jetbrains.kotlin.psi.KtExpression")
if (ktExprClass.isInstance(element)) {
    val analyze = ktExprClass.getMethod("analyze")
    val bc = analyze.invoke(element) // BindingContext
    val getType = bc.javaClass.getMethod("getType", ktExprClass)
    val type = getType.invoke(bc, element) // KotlinType?
    type?.toString()
}
```

### Existing Codebase Usage
- `PsiToolUtils.kt` uses reflection for Kotlin type references (`getTypeReference()`)
- `JpaEntitiesTool.kt` uses `field.type.presentableText` for Java field types
- `SpringBeanGraphTool.kt` uses `field.type.canonicalText` and `param.type.canonicalText`

---

## 2. Structural Search (`structural_search`)

### Purpose
Run structural search patterns programmatically to find code patterns (e.g., "all try-catch blocks that catch Exception", "all methods returning null").

### Exact Classes & Methods
- `com.intellij.structuralsearch.Matcher` — main entry point
  - `Matcher(project: Project, matchOptions: MatchOptions)` — constructor
  - `findMatches(sink: MatchResultSink)` — async results via callback
  - `testFindMatches(source: String, fileContext: Boolean, sourceFileType: LanguageFileType, physicalSourceFile: Boolean)` → `List<MatchResult>` — synchronous, for testing/programmatic use
  - `matchByDownUp(element: PsiElement)` → `List<MatchResult>` — match against a single element
  - `static buildMatcher(project: Project, fileType: LanguageFileType, constraint: String)` → `Matcher`
  - `static validate(project: Project, options: MatchOptions)` — throws on invalid pattern

- `com.intellij.structuralsearch.MatchOptions` — pattern configuration
  - `setSearchPattern(text: String)`
  - `setFileType(fileType: LanguageFileType)` — e.g., `JavaFileType.INSTANCE`, `KotlinFileType.INSTANCE`
  - `setScope(scope: SearchScope)` — e.g., `GlobalSearchScope.projectScope(project)`
  - `setRecursiveSearch(recursive: Boolean)`
  - `setCaseSensitiveMatch(caseSensitive: Boolean)`
  - `addVariableConstraint(constraint: MatchVariableConstraint)`

- `com.intellij.structuralsearch.MatchResult` — a single match
  - `getMatch()` → `PsiElement` — the matched PSI element
  - `getMatchImage()` → `String` — text of the match
  - `getChildren()` → `List<MatchResult>` — sub-matches for variables

- `com.intellij.structuralsearch.plugin.util.SmartPsiPointer` — for storing match locations safely

- Exceptions: `MalformedPatternException`, `UnsupportedPatternException`

### Threading Requirements
- **ReadAction** required for PSI access during matching.
- `findMatches()` runs on the calling thread — must NOT be EDT for large scopes.
- `testFindMatches()` is synchronous — run on background thread.

### Input Requirements
- Search pattern string (SSR syntax)
- `LanguageFileType` (Java, Kotlin, XML, etc.)
- `SearchScope` (project, module, directory, file)
- Optional: variable constraints (type, count, text regex)

### Pattern Syntax
- `$var$` — template variable matching any element
- `$var${0,∞}$` — zero or more occurrences
- `$ReturnType$ $MethodName$($Parameters$)` — method pattern
- `try { $TryStatement$ } catch($ExceptionType$ $ExceptionName$) { $CatchStatement$ }` — structural
- Constraints: type filter, text regex, count range, reference target

### Output Format
- `List<MatchResult>` where each result has:
  - `match: PsiElement` (the matched code)
  - `matchImage: String` (the text)
  - Child `MatchResult`s for each template variable

### Silent Failure Modes
- `MalformedPatternException` if pattern syntax is invalid
- Returns empty list if no matches (not an error)
- Pattern may silently not match if `fileType` doesn't correspond to actual file language
- Kotlin patterns require `org.jetbrains.kotlin` plugin; without it, returns empty

### Platform Compatibility
- Works on all OS
- Java patterns: requires `com.intellij.java` (bundled)
- Kotlin patterns: requires `org.jetbrains.kotlin` (bundled)
- **No additional plugin dependency needed** — `structuralsearch` is a platform module

### Example Usage Pattern
```kotlin
val options = MatchOptions().apply {
    setSearchPattern("System.out.println(\$arg\$)")
    setFileType(JavaFileType.INSTANCE)
    scope = GlobalSearchScope.projectScope(project)
    setRecursiveSearch(true)
}

try {
    Matcher.validate(project, options)
    val matcher = Matcher(project, options)
    val results = ReadAction.compute<List<MatchResult>, Exception> {
        matcher.testFindMatches("", true, JavaFileType.INSTANCE, false)
    }
    results.forEach { r ->
        val file = r.match.containingFile.virtualFile.path
        val line = r.match.containingFile.viewProvider.document
            ?.getLineNumber(r.match.textOffset)?.plus(1)
        println("$file:$line — ${r.matchImage}")
    }
} catch (e: MalformedPatternException) {
    // Invalid pattern
}
```

### Existing Codebase Usage
- **None found** — this would be a new capability.

---

## 3. DataFlow Analysis (`dataflow_analysis`)

### Purpose
Get nullability info, value ranges, and detect dead code branches using IntelliJ's DFA engine.

### Exact Classes & Methods
- `com.intellij.codeInspection.dataFlow.CommonDataflow` — main public API
  - `static getDataflowResult(context: PsiElement)` → `DataflowResult?` — cached results for containing code block
  - `static getDfType(expression: PsiExpression)` → `DfType` — get dataflow type
  - `static getDfType(expression: PsiExpression, ignoreAssertions: Boolean)` → `DfType`
  - `static getExpressionRange(expression: PsiExpression?)` → `LongRangeSet?` — numeric value range
  - `static computeValue(expression: PsiExpression?)` → `Object?` — constant value if determinable

- `com.intellij.codeInspection.dataFlow.types.DfType` — abstract type info
  - `DfTypes.NULL` — known null
  - `DfTypes.NOT_NULL_OBJECT` — known non-null
  - `DfTypes.intValue(n)` — exact int value
  - `DfType.meet(other: DfType)` → `DfType` — intersection
  - `DfType.isSuperType(other: DfType)` → `boolean`

- `com.intellij.codeInspection.dataFlow.Nullability` — enum
  - `NULLABLE`, `NOT_NULL`, `UNKNOWN`, `FLUSHED`

- `com.intellij.codeInspection.dataFlow.CommonDataflow.DataflowResult`
  - `getDfType(expression: PsiExpression)` → `DfType`
  - `getExpressionValues(expression: PsiExpression)` → `Set<Object>`

- `com.intellij.codeInspection.dataFlow.DfaNullability` — extraction helper
  - `fromDfType(dfType: DfType)` → `DfaNullability`

### Threading Requirements
- **ReadAction** required (accesses PSI).
- Results are cached via `CachedValuesManager` — cheap after first call.
- Must be in smart mode (needs resolved types).

### Input Requirements
- `PsiExpression` (Java only — this is a Java-specific API)
- The expression must be inside a method body (not a field initializer in some cases)

### Output Format
- `DfType` — can check with `DfTypes.NULL.isSuperType(dfType)` for nullability
- `LongRangeSet` — numeric range (e.g., `0..100`)
- `Set<Object>` — possible constant values
- `Nullability` enum — `NULLABLE`, `NOT_NULL`, `UNKNOWN`

### Silent Failure Modes
- Returns `null` DataflowResult if code block is too complex or doesn't compile
- Returns `DfType.TOP` (unknown) for unanalyzable expressions
- **Java-only**: does NOT work for Kotlin code (Kotlin has its own analysis, not exposed the same way)
- Method bodies only — not applicable to top-level expressions
- Ignores some complex patterns (lambdas, streams) in some cases

### Platform Compatibility
- **Java only** — requires `com.intellij.java` (bundled)
- Part of `java-analysis-impl` module — available in Ultimate
- Works on all OS
- **No equivalent public API for Kotlin** dataflow analysis

### Example Usage Pattern
```kotlin
ReadAction.nonBlocking<String?> {
    val psiFile = PsiManager.getInstance(project).findFile(vf) as? PsiJavaFile ?: return@nonBlocking null
    val element = psiFile.findElementAt(offset) ?: return@nonBlocking null
    val expr = PsiTreeUtil.getParentOfType(element, PsiExpression::class.java)
        ?: return@nonBlocking null

    val dfType = CommonDataflow.getDfType(expr)
    val nullability = DfaNullability.fromDfType(dfType)
    val range = CommonDataflow.getExpressionRange(expr)
    val value = CommonDataflow.computeValue(expr)

    buildString {
        appendLine("Expression: ${expr.text}")
        appendLine("Type: ${expr.type?.presentableText}")
        appendLine("Nullability: $nullability")
        if (range != null) appendLine("Range: $range")
        if (value != null) appendLine("Constant value: $value")
    }
}.inSmartMode(project).executeSynchronously()
```

### Existing Codebase Usage
- **None found** — new capability.

---

## 4. Read vs Write Access (`read_write_access`)

### Purpose
Classify all references to a variable/field as read accesses or write accesses.

### Exact Classes & Methods

**Primary approach: `ReferencesSearch` + `PsiUtil` classification**

- `com.intellij.psi.search.searches.ReferencesSearch`
  - `static search(element: PsiElement)` → `Query<PsiReference>`
  - `static search(element: PsiElement, scope: SearchScope)` → `Query<PsiReference>`
  - `static search(element: PsiElement, scope: SearchScope, ignoreAccessScope: Boolean)` → `Query<PsiReference>`

- `com.intellij.psi.util.PsiUtil` (Java-specific, in `java-psi-api`)
  - `static isAccessedForWriting(expr: PsiExpression)` → `boolean` — checks if expression is on LHS of assignment or in ++/-- operation
  - `static isAccessedForReading(expr: PsiExpression)` → `boolean` — returns false ONLY when expression is the sole target of a simple `=` assignment

**Note:** `ReadWriteAccessSearch` does NOT exist as a standalone class. The correct approach is `ReferencesSearch` + manual classification.

### Threading Requirements
- **ReadAction** required.
- `ReferencesSearch.search()` can be slow on large projects — use `ReadAction.nonBlocking` with progress.
- Smart mode required (needs resolved references).

### Input Requirements
- `PsiElement` — the variable, field, or parameter to search for
- Use `PsiTreeUtil.getParentOfType(element, PsiVariable::class.java)` to get the actual variable
- Optional: `SearchScope` to limit search range

### Output Format
- `Query<PsiReference>` — iterate or `.findAll()` to get `Collection<PsiReference>`
- For each reference: `ref.element` gives the `PsiElement`, then check:
  - `PsiUtil.isAccessedForWriting(expr)` where `expr` is the reference element cast to `PsiExpression`
  - `PsiUtil.isAccessedForReading(expr)` — true in most cases except sole LHS of `=`

### Silent Failure Modes
- `ReferencesSearch.search().findAll()` returns empty collection if element has no references (not an error)
- Returns empty if element is not a resolvable `PsiNamedElement`
- `PsiUtil.isAccessedForWriting/Reading` only works on `PsiExpression` — Kotlin references need different handling
- For Kotlin: use `org.jetbrains.kotlin.idea.references.KtReference` and check parent node type

### Platform Compatibility
- `ReferencesSearch` — platform API, works on all languages
- `PsiUtil.isAccessedForWriting/Reading` — **Java only** (in `java-psi-api`)
- For Kotlin: manual parent-node checking needed
- Works on all OS

### Example Usage Pattern
```kotlin
ReadAction.nonBlocking<Map<String, List<String>>> {
    val variable = PsiTreeUtil.getParentOfType(elementAtOffset, PsiVariable::class.java)
        ?: return@nonBlocking emptyMap()

    val refs = ReferencesSearch.search(variable, GlobalSearchScope.projectScope(project)).findAll()
    val reads = mutableListOf<String>()
    val writes = mutableListOf<String>()

    for (ref in refs) {
        val refElement = ref.element
        val expr = PsiTreeUtil.getParentOfType(refElement, PsiExpression::class.java)
        val doc = refElement.containingFile?.viewProvider?.document
        val line = doc?.getLineNumber(refElement.textOffset)?.plus(1) ?: 0
        val location = "${refElement.containingFile.virtualFile.name}:$line"

        if (expr != null && PsiUtil.isAccessedForWriting(expr)) {
            writes.add(location)
        }
        if (expr != null && PsiUtil.isAccessedForReading(expr)) {
            reads.add(location)
        }
    }
    mapOf("reads" to reads, "writes" to writes)
}.inSmartMode(project).executeSynchronously()
```

### Existing Codebase Usage
- **None found** — new capability.

---

## 5. Test Finder (`test_finder`)

### Purpose
Find the test class for a given source class, or the source class for a given test class.

### Exact Classes & Methods

- `com.intellij.testIntegration.TestFinder` — extension point interface (EP: `testFinder`)
  - `findSourceElement(from: PsiElement)` → `PsiElement?` — get the containing class/file
  - `findTestsForClass(element: PsiElement)` → `Collection<PsiElement>` — find test classes for source class
  - `findClassesForTest(element: PsiElement)` → `Collection<PsiElement>` — find source classes for test class
  - `isTest(element: PsiElement)` → `boolean` — determine if element is a test

- `com.intellij.testIntegration.TestFinderHelper` — utility class
  - Not found as separate file — functionality appears inlined into implementations

- Implementations registered via EP:
  - `com.intellij.java.impl.testIntegration.JavaTestFinder` — for JUnit/TestNG
  - Kotlin test finder provided by Kotlin plugin

- To iterate all registered finders:
  ```kotlin
  TestFinder.EP_NAME.extensionList
  ```

### Threading Requirements
- **ReadAction** required (accesses PSI).
- `findTestsForClass` may trigger index searches — can be slow.
- Smart mode required.

### Input Requirements
- `PsiElement` — typically a `PsiClass` or the `PsiFile` containing the class
- The element can be of any language — each `TestFinder` implementation checks if it can handle it
- For best results, pass the `PsiClass` itself (not a random element inside it)

### Output Format
- `Collection<PsiElement>` — typically `PsiClass` instances representing test/source classes
- Empty collection if no association found
- Multiple results possible (e.g., multiple test classes for one source)

### How it Works with Test Frameworks
- **JUnit 4/5**: Looks for classes named `FooTest`, `FooTests`, `TestFoo` for source class `Foo`
- **TestNG**: Same naming convention + `@Test` annotation detection
- **Convention-based**: Primarily uses naming patterns, not annotations
- The `isTest(element)` method checks for `@Test` annotations and test superclasses

### Silent Failure Modes
- Returns empty collection if no test found (not an error)
- Returns empty if element is not a class
- Returns empty if test class is in a different module not in the search scope
- Naming convention mismatch = no results (e.g., `FooSpec` won't match default Java finder)
- Kotlin test finders depend on `org.jetbrains.kotlin` plugin being active

### Platform Compatibility
- `TestFinder` interface — platform API (in `lang-api`)
- Java implementation requires `com.intellij.java` (bundled)
- Works on all OS
- No additional plugin dependencies needed for Java/Kotlin

### Example Usage Pattern
```kotlin
ReadAction.nonBlocking<List<String>> {
    val psiFile = PsiManager.getInstance(project).findFile(vf) ?: return@nonBlocking emptyList()
    val psiClass = PsiTreeUtil.findChildOfType(psiFile, PsiClass::class.java)
        ?: return@nonBlocking emptyList()

    val finders = TestFinder.EP_NAME.extensionList
    val results = mutableListOf<String>()

    for (finder in finders) {
        val sourceElement = finder.findSourceElement(psiClass) ?: continue
        val isTest = finder.isTest(sourceElement)

        val found = if (isTest) {
            finder.findClassesForTest(sourceElement)
        } else {
            finder.findTestsForClass(sourceElement)
        }

        found.forEach { el ->
            val name = (el as? PsiClass)?.qualifiedName ?: el.text.take(60)
            val path = el.containingFile?.virtualFile?.path ?: "unknown"
            results.add("$name ($path)")
        }
    }
    results
}.inSmartMode(project).executeSynchronously()
```

### Existing Codebase Usage
- **None found** — new capability.

---

## 6. Module Dependency Graph (`module_dependency_graph`)

### Purpose
Get the dependency graph of IntelliJ project modules, detect circular dependencies, compute transitive closure.

### Exact Classes & Methods

- `com.intellij.openapi.module.ModuleManager` (already used in codebase)
  - `static getInstance(project: Project)` → `ModuleManager`
  - `getModules()` → `Array<Module>`
  - `findModuleByName(name: String)` → `Module?`
  - `getModuleDependentModules(module: Module)` → `List<Module>` — modules that depend ON this module
  - `getSortedModules()` → `Array<Module>` — topologically sorted

- `com.intellij.openapi.roots.ModuleRootManager` (already used in codebase)
  - `static getInstance(module: Module)` → `ModuleRootManager`
  - `getDependencies()` → `Array<Module>` — direct module dependencies
  - `getDependencyModuleNames()` → `Array<String>`
  - `getOrderEntries()` → `Array<OrderEntry>` — all dependency entries (modules, libs, SDK)

- `com.intellij.openapi.roots.OrderEntry` — base interface
  - `ModuleOrderEntry` — dependency on another module
    - `getModule()` → `Module?`
    - `isExported()` → `boolean`
    - `getScope()` → `DependencyScope`
  - `LibraryOrderEntry` — dependency on a library
    - `getLibrary()` → `Library?`
    - `getLibraryName()` → `String?`
  - `ModuleSourceOrderEntry` — module's own sources
  - `JdkOrderEntry` — SDK dependency

- `com.intellij.openapi.roots.OrderEnumerator` — fluent API for dependency traversal
  - `ModuleRootManager.getInstance(module).orderEntries()` → `OrderEnumerator`
  - `.recursively()` — include transitive dependencies
  - `.exportedOnly()` — only exported dependencies
  - `.withoutSdk()` — exclude SDK
  - `.productionOnly()` — exclude test dependencies
  - `.forEachModule(processor: Processor<Module>)` — iterate dependent modules
  - `.forEachLibrary(processor: Processor<Library>)` — iterate dependent libraries

- `com.intellij.openapi.roots.DependencyScope` — enum
  - `COMPILE`, `TEST`, `RUNTIME`, `PROVIDED`

### Threading Requirements
- **ReadAction** required for `ModuleRootManager` access.
- `ModuleManager.getInstance()` is safe from any thread.
- `ModuleRootManager.getOrderEntries()` needs read lock.

### Input Requirements
- `Project` instance
- Optional: specific module name to start from

### Output Format
- `Array<Module>` — module objects
- `Array<OrderEntry>` — typed dependency entries
- Build your own adjacency list for graph operations

### Circular Dependency Detection
IntelliJ does NOT expose a direct circular dependency detection API. You must:
1. Build adjacency list from `ModuleRootManager.getDependencies()` for each module
2. Run DFS/Tarjan's algorithm to find cycles
3. `ModuleManager.getSortedModules()` returns topological order — if it differs from dependency traversal, cycles exist

### Silent Failure Modes
- `ModuleRootManager.getDependencies()` returns empty array for modules with no deps (not an error)
- `ModuleOrderEntry.getModule()` returns `null` if the dependency module is missing/not loaded
- During indexing, module structure is still available (not affected by dumb mode)

### Platform Compatibility
- Platform API — works on all OS, all IDE types
- No additional plugins required
- Already used in `ProjectModulesTool.kt` and `CompileModuleTool.kt`

### Example Usage Pattern
```kotlin
ReadAction.compute<Map<String, List<String>>, Exception> {
    val modules = ModuleManager.getInstance(project).modules
    val graph = mutableMapOf<String, List<String>>()

    for (module in modules) {
        val deps = ModuleRootManager.getInstance(module).getDependencies()
        graph[module.name] = deps.map { it.name }
    }

    // Detect cycles via DFS
    val visited = mutableSetOf<String>()
    val inStack = mutableSetOf<String>()
    val cycles = mutableListOf<List<String>>()

    fun dfs(node: String, path: MutableList<String>) {
        if (node in inStack) {
            cycles.add(path.dropWhile { it != node } + node)
            return
        }
        if (node in visited) return
        visited.add(node)
        inStack.add(node)
        path.add(node)
        graph[node]?.forEach { dfs(it, path) }
        path.removeAt(path.lastIndex)
        inStack.remove(node)
    }

    graph.keys.forEach { dfs(it, mutableListOf()) }
    graph // return adjacency list + cycles found
}
```

### Existing Codebase Usage
- `ProjectModulesTool.kt` — uses `ModuleManager.getInstance(project).modules` and `ModuleRootManager.getInstance(module)` for source roots
- `CompileModuleTool.kt` — uses `ModuleManager.getInstance(project).modules.find { it.name == moduleName }`
- `RepoMapGenerator.kt` — uses `ModuleManager.getInstance(project).modules`

---

## 7. Changelists/Shelve (`changelist_shelve`)

### Purpose
Create/manage changelists, move files between them, shelve changes for later, unshelve them back.

### Exact Classes & Methods

**Changelist Management:**
- `com.intellij.openapi.vcs.changes.ChangeListManager`
  - `static getInstance(project: Project)` → `ChangeListManager`
  - `getChangeLists()` → `List<LocalChangeList>`
  - `getDefaultChangeList()` → `LocalChangeList`
  - `getAllChanges()` → `Collection<Change>`
  - `getChange(file: VirtualFile)` → `Change?`
  - `getChangesIn(dir: VirtualFile)` → `Collection<Change>`
  - `getAffectedFiles()` → `List<VirtualFile>`
  - `getUnversionedFilesPaths()` → `List<FilePath>`
  - `getModifiedWithoutEditing()` → `List<VirtualFile>`
  - `getStatus(file: VirtualFile)` → `FileStatus`
  - `isFileAffected(file: VirtualFile)` → `boolean`

- `com.intellij.openapi.vcs.changes.ChangeListManagerImpl` (implementation, direct access needed for mutations)
  - `addChangeList(name: String, comment: String?)` → `LocalChangeList`
  - `addChangeList(name: String, comment: String?, data: ChangeListData?)` → `LocalChangeList`
  - `removeChangeList(name: String)`
  - `removeChangeList(list: LocalChangeList)`
  - `moveChangesTo(list: LocalChangeList, vararg changes: Change)`
  - `setDefaultChangeList(list: LocalChangeList)`
  - `editChangeListData(name: String, newData: ChangeListData?)` → `boolean`

- `com.intellij.openapi.vcs.changes.LocalChangeList`
  - `getName()` → `String`
  - `getId()` → `String`
  - `getComment()` → `String?`
  - `getChanges()` → `Collection<Change>`
  - `isDefault()` → `boolean`

**Shelving:**
- `com.intellij.openapi.vcs.changes.shelf.ShelveChangesManager`
  - `static getInstance(project: Project)` → `ShelveChangesManager`
  - `shelveChanges(changes: Collection<Change>, commitMessage: String, rollback: Boolean)` → `ShelvedChangeList` — throws `IOException, VcsException`
  - `shelveChanges(changes: Collection<Change>, commitMessage: String, rollback: Boolean, markToBeDeleted: Boolean)` → `ShelvedChangeList`
  - `unshelveChangeList(changeList: ShelvedChangeList, changes: List<ShelvedChange>?, binaryFiles: List<ShelvedBinaryFile>?, targetChangeList: LocalChangeList?, showSuccessNotification: Boolean)` — `@CalledInAny`
  - `getShelvedChangeLists()` → `List<ShelvedChangeList>` (`@NotNull @Unmodifiable`)
  - `getRecycledShelvedChangeLists()` → `List<ShelvedChangeList>`

- `com.intellij.openapi.vcs.changes.shelf.ShelvedChangeList`
  - `getDescription()` → `String`
  - `getChanges()` → `List<ShelvedChange>`
  - `getBinaryFiles()` → `List<ShelvedBinaryFile>`
  - `getDate()` → `Date`

### Threading Requirements
- **ChangeListManager read operations**: ReadAction or any thread (thread-safe reads)
- **ChangeListManager mutations** (`addChangeList`, `moveChangesTo`): should be called from background thread, applies asynchronously
- **ShelveChangesManager.shelveChanges()**: background thread (does I/O, patch generation)
- **ShelveChangesManager.unshelveChangeList()**: `@CalledInAny` — can be called from any thread
- **deleteShelves**: `@RequiresEdt` — must be on EDT

### Input Requirements
- For changelist creation: name (String), optional comment
- For shelving: `Collection<Change>` from `ChangeListManager.getAllChanges()`
- For unshelving: `ShelvedChangeList` from `getShelvedChangeLists()`, optional target `LocalChangeList`

### Output Format
- `LocalChangeList` objects with name, id, changes
- `ShelvedChangeList` objects with description, date, changes

### Conflict Handling on Unshelve
- `unshelveChangeList()` can return `ApplyPatchStatus` indicating success/failure
- Conflicts show a merge dialog when `showSuccessNotification=true`
- Use `reverse=false` for unshelving, `reverse=true` for reverting a shelf

### Silent Failure Modes
- `getDefaultChangeList()` never returns null (always exists)
- `getAllChanges()` returns empty collection if working tree is clean
- `shelveChanges()` throws `VcsException` if files are locked or patches can't be created
- `unshelveChangeList()` may silently skip files that conflict if `showSuccessNotification=false`
- Changelist changes are reflected asynchronously — may not be immediately visible after `moveChangesTo()`

### Platform Compatibility
- Platform VCS API — works on all OS
- Requires VCS integration active in project (Git, SVN, etc.)
- No additional plugins required (part of platform)

### Example Usage Pattern
```kotlin
// Create changelist and move files
val clm = ChangeListManager.getInstance(project) as ChangeListManagerImpl
val newList = clm.addChangeList("Agent: refactoring changes", "Isolated changes for review")
val targetChanges = clm.allChanges.filter { it.virtualFile?.name?.endsWith(".kt") == true }
clm.moveChangesTo(newList, *targetChanges.toTypedArray())

// Shelve changes
val shelveManager = ShelveChangesManager.getInstance(project)
val changes = clm.allChanges.toList()
val shelf = shelveManager.shelveChanges(changes, "WIP: feature X", true) // rollback=true reverts working tree

// Unshelve later
val shelves = shelveManager.shelvedChangeLists
val targetShelf = shelves.first()
shelveManager.unshelveChangeList(targetShelf, null, null, clm.defaultChangeList, true)
```

### Existing Codebase Usage
- `GitStatusTool.kt` — uses `ChangeListManager.getInstance(project)` for `allChanges` and `modifiedWithoutEditing`

---

## 8. Problem View (`problem_view`)

### Purpose
Read current problems (errors, warnings) from files — from compilation, inspections, and external tools.

### Exact Classes & Methods

**Layer 1: WolfTheProblemSolver (file-level problem flag)**
- `com.intellij.problems.WolfTheProblemSolver`
  - `static getInstance(project: Project)` → `WolfTheProblemSolver`
  - `isProblemFile(file: VirtualFile)` → `boolean` — is file flagged as problematic?
  - `hasProblemFilesBeneath(module: Module)` → `boolean`
  - `hasSyntaxErrors(file: VirtualFile)` → `boolean`
  - `reportProblemsFromExternalSource(file: VirtualFile, source: Object)` — add external problem markers
  - `clearProblemsFromExternalSource(file: VirtualFile, source: Object)`

**Layer 2: DocumentMarkupModel + HighlightInfo (detailed error/warning extraction)**
- `com.intellij.openapi.editor.impl.DocumentMarkupModel`
  - `static forDocument(document: Document, project: Project, create: Boolean)` → `MarkupModel`

- `com.intellij.openapi.editor.markup.MarkupModel`
  - `getAllHighlighters()` → `Array<RangeHighlighter>`

- `com.intellij.openapi.editor.markup.RangeHighlighter`
  - `isValid()` → `boolean`
  - `getErrorStripeTooltip()` → `Object?` — cast to `HighlightInfo`
  - `getStartOffset()` → `int`
  - `getEndOffset()` → `int`

- `com.intellij.codeInsight.daemon.impl.HighlightInfo`
  - `getSeverity()` → `HighlightSeverity`
  - `getDescription()` → `String?` — error/warning message
  - `type` → `HighlightInfoType`
  - `startOffset`, `endOffset` — character range
  - `getToolTip()` → `String?`

- `com.intellij.lang.annotation.HighlightSeverity` — severity levels
  - `ERROR`, `WARNING`, `WEAK_WARNING`, `INFORMATION`, `GENERIC_SERVER_ERROR_OR_WARNING`

**Layer 3: PSI-based error detection (what SemanticDiagnosticsTool already does)**
- `com.intellij.psi.PsiErrorElement` — syntax errors in PSI tree
- `PsiTreeUtil.collectElementsOfType(psiFile, PsiErrorElement::class.java)`
- `PsiReference.resolve()` returning `null` → unresolved reference

**Layer 4: Problems View internals (2024.1+)**
- `com.intellij.analysis.problemsView.toolWindow.ProblemsView` — internal API
- `com.intellij.analysis.problemsView.toolWindow.HighlightingWatcher` — internal, monitors highlighting changes
- These are `@ApiStatus.Internal` — NOT recommended for plugin use

### Threading Requirements
- `WolfTheProblemSolver`: any thread (thread-safe)
- `DocumentMarkupModel.forDocument()`: **ReadAction** required, document must be committed
- `HighlightInfo` extraction: **ReadAction** required
- The file must have been opened in an editor for highlights to be populated (or `DaemonCodeAnalyzer` must have run)

### Input Requirements
- `VirtualFile` for WolfTheProblemSolver
- `Document` (from `FileDocumentManager.getInstance().getDocument(vf)`) for DocumentMarkupModel
- File must have been analyzed by the daemon analyzer for HighlightInfo to be populated

### Output Format
- `boolean` — from `isProblemFile()`
- `Array<RangeHighlighter>` → filter/cast to `HighlightInfo` → get severity, description, offset

### Difference Between Problem Sources
| Source | What it catches | When populated |
|--------|----------------|----------------|
| `PsiErrorElement` | Syntax errors only | Immediately on file parse |
| `WolfTheProblemSolver` | Files with errors (boolean) | After daemon analysis |
| `DocumentMarkupModel` + `HighlightInfo` | All errors + warnings + inspections | After file opened in editor + daemon pass |
| Compilation | Build errors | After explicit compile action |

### Silent Failure Modes
- `DocumentMarkupModel.forDocument()` returns empty model if file hasn't been opened in editor
- `HighlightInfo` may not be populated if daemon hasn't finished analyzing the file
- `getErrorStripeTooltip()` may return non-`HighlightInfo` objects (check with `instanceof`)
- `WolfTheProblemSolver.isProblemFile()` may return false even for files with warnings (only tracks errors)
- **Critical**: HighlightInfo is only available for files that have been opened/analyzed. For unopened files, you need to trigger analysis first via `DaemonCodeAnalyzer.getInstance(project).restart(psiFile)`

### Platform Compatibility
- All APIs work on all OS
- `WolfTheProblemSolver` — platform API, no extra plugins
- `DocumentMarkupModel` + `HighlightInfo` — platform API (in `analysis-impl`)
- Problems View internals — `@ApiStatus.Internal`, may change without notice

### Example Usage Pattern
```kotlin
ReadAction.nonBlocking<List<Map<String, Any>>> {
    val document = FileDocumentManager.getInstance().getDocument(vf) ?: return@nonBlocking emptyList()
    val markup = DocumentMarkupModel.forDocument(document, project, false)
        ?: return@nonBlocking emptyList()

    val problems = mutableListOf<Map<String, Any>>()
    for (highlighter in markup.allHighlighters) {
        if (!highlighter.isValid) continue
        val info = highlighter.errorStripeTooltip as? HighlightInfo ?: continue
        if (info.severity.myVal < HighlightSeverity.WARNING.myVal) continue  // skip info/hints

        val line = document.getLineNumber(info.startOffset) + 1
        problems.add(mapOf(
            "line" to line,
            "severity" to info.severity.name,
            "message" to (info.description ?: ""),
            "range" to "${info.startOffset}-${info.endOffset}"
        ))
    }
    problems
}.inSmartMode(project).executeSynchronously()
```

### Existing Codebase Usage
- `SemanticDiagnosticsTool.kt` — uses `WolfTheProblemSolver.isProblemFile()`, `PsiErrorElement` collection, and unresolved reference scanning. Does NOT use `DocumentMarkupModel`/`HighlightInfo` approach.

---

## 9. Terminal (`terminal`)

### Purpose
Create terminal tabs, run commands, capture output. Also: run commands silently without UI.

### Two Approaches

**Approach A: IntelliJ Terminal Plugin (visible terminal tab)**
Requires plugin dependency: `org.jetbrains.plugins.terminal` (bundled but NOT in agent's current deps)

**Approach B: GeneralCommandLine + CapturingProcessHandler (silent, no UI)**
Platform API, no additional dependencies needed.

### Approach A: Terminal Plugin API

**Classes (Legacy API — works in 2025.1):**
- `org.jetbrains.plugins.terminal.ShellTerminalWidget`
  - `executeCommand(shellCommand: String)` — throws `IOException` if another command is typed
  - `getTypedShellCommand()` → `String`
  - `hasRunningCommands()` → `boolean` (`@RequiresBackgroundThread`)
  - `getCurrentDirectory()` → `String?`

- `org.jetbrains.plugins.terminal.TerminalToolWindowManager`
  - (Deprecated — use `TerminalToolWindowFactory` pattern instead)

**Classes (New API — 2025.2+, Experimental):**
- `com.intellij.terminal.TerminalView` — main terminal instance
  - Access via `TerminalView.DATA_KEY` from `DataContext`
  - `sendText(text: String)` — send text to terminal
  - `createSendTextBuilder()` → `TerminalSendTextBuilder`
  - `outputModels` — read-only terminal output access
  - `shellIntegrationDeferred` — `Deferred<TerminalShellIntegration>`

- `com.intellij.terminal.TerminalToolWindowTabsManager`
  - `getTabs()` — get open terminal tabs
  - `createTabBuilder()` — create new terminal tab

- `com.intellij.terminal.TerminalShellIntegration`
  - `addCommandExecutionListener()` — listen for command completion
  - Requires `await()` for initialization
  - Only available for Bash, Zsh, PowerShell

- `com.intellij.terminal.TerminalOutputModel`
  - `textLength`, `startOffset`, `endOffset`

**Status:** `@ApiStatus.Experimental` — the new Terminal API is under active development as of 2025.3.

### Approach B: GeneralCommandLine (RECOMMENDED for agent use)

**Classes:**
- `com.intellij.execution.configurations.GeneralCommandLine`
  - `GeneralCommandLine(vararg command: String)` — constructor
  - `setWorkDirectory(dir: String)` or `setWorkDirectory(dir: File)`
  - `withEnvironment(key: String, value: String)` → `GeneralCommandLine`
  - `withCharset(charset: Charset)` → `GeneralCommandLine`
  - `createProcess()` → `Process`

- `com.intellij.execution.process.CapturingProcessHandler`
  - `CapturingProcessHandler(commandLine: GeneralCommandLine)`
  - `runProcess(timeoutMs: Int)` → `ProcessOutput` — synchronous, blocks
  - `runProcessWithProgressIndicator(indicator: ProgressIndicator)` → `ProcessOutput`

- `com.intellij.execution.process.ProcessOutput`
  - `getStdout()` → `String`
  - `getStderr()` → `String`
  - `getExitCode()` → `int`
  - `isTimeout()` → `boolean`
  - `isCancelled()` → `boolean`
  - `getStdoutLines()` → `List<String>`

- `com.intellij.execution.util.ExecUtil`
  - `static execAndGetOutput(commandLine: GeneralCommandLine)` → `ProcessOutput` — convenience wrapper

- `com.intellij.execution.process.OSProcessHandler` — for long-running processes with streaming output
  - `OSProcessHandler(commandLine: GeneralCommandLine)`
  - `startNotify()` — begin capturing
  - `addProcessListener(listener: ProcessListener)`

- `com.intellij.execution.process.ScriptRunnerUtil`
  - `static getProcessOutput(commandLine: GeneralCommandLine)` → `String` — simplest wrapper

### Threading Requirements
- **Approach A**: `executeCommand()` can be called from any thread; terminal UI updates on EDT
- **Approach B**: `CapturingProcessHandler.runProcess()` blocks — call on background thread (`Dispatchers.IO`)
- `ExecUtil.execAndGetOutput()` blocks — background thread only

### Input Requirements
- Command string (or command + args as separate strings)
- Working directory (defaults to project base path)
- Optional: environment variables, charset, timeout

### Output Format
- **Approach A**: No direct output capture API (must use `TerminalOutputModel` in new API, which is experimental)
- **Approach B**: `ProcessOutput` with `stdout`, `stderr`, `exitCode`

### Silent Failure Modes
- **Approach A**: `executeCommand()` throws `IOException` if another command is already in the prompt
- **Approach B**: `runProcess()` sets `isTimeout()=true` if timeout exceeded (default: no timeout)
- `exitCode` of -1 or non-zero indicates command failure
- `stderr` may be empty even on failure for some commands
- Command not found: non-zero exit code + error in stderr
- On Windows: shell commands need `cmd /c` prefix

### Platform Compatibility
- **Approach A**: Requires `org.jetbrains.plugins.terminal` plugin (bundled in all IDEs)
- **Approach B**: Platform API — works everywhere, no extra deps
- `GeneralCommandLine` handles OS-specific quoting/escaping automatically
- Path separator differences (/ vs \) handled by `GeneralCommandLine`

### Example Usage Pattern
```kotlin
// Approach B: Silent command execution (RECOMMENDED)
suspend fun runCommand(command: String, workDir: String, timeoutMs: Int = 30_000): ProcessOutput {
    return withContext(Dispatchers.IO) {
        val cmdLine = GeneralCommandLine("/bin/sh", "-c", command)
            .withWorkDirectory(workDir)
            .withCharset(Charsets.UTF_8)
        val handler = CapturingProcessHandler(cmdLine)
        handler.runProcess(timeoutMs)
    }
}

// Usage
val output = runCommand("gradle test --info", project.basePath!!, 120_000)
if (output.exitCode == 0) {
    println("stdout: ${output.stdout}")
} else {
    println("stderr: ${output.stderr}")
}

// Approach A: Visible terminal tab (needs terminal plugin dep)
// ShellTerminalWidget approach
val terminalView = TerminalView.getInstance(project)
val widget = terminalView.createLocalShellWidget(project.basePath!!, "Agent Terminal")
widget.executeCommand("npm test")
```

### Existing Codebase Usage
- The agent already has a `run_command` tool — check its implementation for the existing pattern.

<br>

---

## Summary: Build Dependency Requirements

| Tool | Additional Plugin Deps Needed | New Build Config? |
|------|-------------------------------|-------------------|
| 1. Type Inference | None (uses existing `com.intellij.java`, `org.jetbrains.kotlin`) | No |
| 2. Structural Search | None (platform module) | No |
| 3. DataFlow Analysis | None (uses existing `com.intellij.java`) | No |
| 4. Read/Write Access | None (uses existing `com.intellij.java` for PsiUtil) | No |
| 5. Test Finder | None (platform `lang-api`) | No |
| 6. Module Deps | None (already used in codebase) | No |
| 7. Changelists/Shelve | None (platform VCS API) | No |
| 8. Problem View | None (platform API) | No |
| 9. Terminal | `org.jetbrains.plugins.terminal` for Approach A; None for Approach B | Optional |

## Summary: Threading Requirements

| Tool | ReadAction | Smart Mode | EDT | Background |
|------|-----------|------------|-----|------------|
| 1. Type Inference | Yes | Yes | No | Yes |
| 2. Structural Search | Yes | Yes | No | Yes (can be slow) |
| 3. DataFlow Analysis | Yes | Yes | No | Yes |
| 4. Read/Write Access | Yes | Yes | No | Yes (can be slow) |
| 5. Test Finder | Yes | Yes | No | Yes |
| 6. Module Deps | Yes | No (works in dumb mode) | No | Optional |
| 7. Changelists/Shelve | Reads: any thread; Writes: background | No | deleteShelves: Yes | Yes |
| 8. Problem View | Yes (for HighlightInfo) | Yes (for full results) | No | Yes |
| 9. Terminal | No | No | No | Yes (for blocking) |

## Summary: Risk Assessment

| Tool | Risk Level | Key Risk |
|------|-----------|----------|
| 1. Type Inference | MEDIUM | K1→K2 migration coming; use reflection for Kotlin types |
| 2. Structural Search | LOW | Stable API, well-documented pattern syntax |
| 3. DataFlow Analysis | MEDIUM | Java-only; complex expressions may return TOP/unknown |
| 4. Read/Write Access | LOW | Straightforward API; Kotlin needs manual parent-check |
| 5. Test Finder | LOW | Convention-based; may miss non-standard naming |
| 6. Module Deps | LOW | Already used in codebase; cycle detection is custom code |
| 7. Changelists/Shelve | MEDIUM | Async mutations; conflict handling on unshelve |
| 8. Problem View | HIGH | HighlightInfo only for editor-opened files; daemon must complete |
| 9. Terminal | MEDIUM | Legacy API deprecated; new API is @Experimental |

## Recommendations

1. **Terminal**: Use **Approach B** (`GeneralCommandLine` + `CapturingProcessHandler`). It requires no extra plugin deps, provides clean stdout/stderr capture, and is the stable API. Add terminal plugin dep only if visible terminal tab creation is needed.

2. **Type Inference**: Use reflection for Kotlin types (as `PsiToolUtils.kt` already does). For Java, direct `PsiExpression.getType()` is stable and clean.

3. **Problem View**: Combine the existing `SemanticDiagnosticsTool` approach (PSI errors + unresolved refs) with `DocumentMarkupModel` + `HighlightInfo` for files that have been editor-opened. Fallback gracefully to PSI-only analysis for unopened files.

4. **DataFlow Analysis**: Implement for Java only. Document that Kotlin support is not available via this API.

5. **Structural Search**: Safe to use. Wrap pattern validation in try-catch for `MalformedPatternException`.

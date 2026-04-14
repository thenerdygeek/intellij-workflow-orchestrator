# Python PSI API Research for PythonProvider Implementation

**Date:** 2026-04-13
**Purpose:** Deep API reference for implementing a `PythonProvider` that mirrors the existing Java/Kotlin PSI tools
**Status:** Complete

---

## Table of Contents

1. [Plugin Dependencies & Setup](#1-plugin-dependencies--setup)
2. [Find Definition / Navigation](#2-find-definition--navigation)
3. [Find References](#3-find-references)
4. [Type Hierarchy](#4-type-hierarchy)
5. [Call Hierarchy](#5-call-hierarchy)
6. [Type Inference](#6-type-inference)
7. [Dataflow Analysis](#7-dataflow-analysis)
8. [Decorators (Python's Annotations)](#8-decorators-pythons-annotations)
9. [File Structure](#9-file-structure)
10. [Structural Search](#10-structural-search)
11. [Test Finder](#11-test-finder)
12. [Python Debug APIs](#12-python-debug-apis)
13. [Build / Package Management APIs](#13-build--package-management-apis)
14. [PSI Element Type Reference](#14-psi-element-type-reference)
15. [Community vs Professional Feature Matrix](#15-community-vs-professional-feature-matrix)
16. [Implementation Mapping](#16-implementation-mapping-existing-tools--python-equivalents)

---

## 1. Plugin Dependencies & Setup

### Plugin IDs

| Plugin | ID | Edition | Notes |
|---|---|---|---|
| Python Community Edition | `PythonCore` | PyCharm Community, IntelliJ Community (with plugin) | Free, open-source |
| Python Professional | `Pythonid` | PyCharm Professional, IntelliJ Ultimate | Paid, includes Django debug, remote interpreter |

**Important (2024.2+):** When using `Pythonid` functionality, a dependency on BOTH `PythonCore` AND `Pythonid` is required.

### Gradle Configuration

```gradle
repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // For PyCharm-targeted plugin:
        pycharm("<versionNumber>")
        bundledPlugin("PythonCore")
        // If using Professional features:
        // bundledPlugin("Pythonid")
    }
    // Or for IntelliJ IDEA + Python plugin:
    // intellijIdeaUltimate("<versionNumber>")
    // bundledPlugin("PythonCore")
}
```

### plugin.xml Dependency

```xml
<depends>com.intellij.modules.python</depends>
```

### Maven Artifacts

The Python PSI API and implementation are published as separate Maven artifacts:

- `com.jetbrains.intellij.python:python-psi-api` — Stable API interfaces
- `com.jetbrains.intellij.python:python-psi-impl` — Implementation classes (less stable)

### PSI Architecture Split

The Python PSI layer is split between:

- **API** (`intellij.python.psi` / `python-psi-api`) — Stable interfaces: `PyClass`, `PyFunction`, `PyFile`, etc.
- **Implementation** (`intellij.python.psi.impl` / `python-psi-impl`) — Concrete implementations, utilities, type providers

This separation allows plugins to depend on stable APIs while implementation details evolve.

---

## 2. Find Definition / Navigation

### Core Resolution Mechanism

Python symbol resolution uses `PsiReference.resolve()` and `PsiPolyVariantReference.multiResolve()`, same as the platform API. The key difference is the resolution context.

#### PyReferenceExpression

**FQN:** `com.jetbrains.python.psi.PyReferenceExpression`
**Package:** `python-psi-api`
**Plugin:** `PythonCore`

Key methods:

```java
// Get the reference for resolution
@NotNull PsiPolyVariantReference getReference();

// Get qualifier (for dotted access like obj.method)
@Nullable PyExpression getQualifier();

// Follow assignment chain to find ultimate definition
@NotNull QualifiedResolveResult followAssignmentsChain(
    @NotNull PyResolveContext resolveContext);

// Multi-resolve version (handles multiple possible targets)
@NotNull List<QualifiedRatedResolveResult> multiFollowAssignmentsChain(
    @NotNull PyResolveContext resolveContext);

@NotNull List<QualifiedRatedResolveResult> multiFollowAssignmentsChain(
    @NotNull PyResolveContext resolveContext,
    @NotNull Predicate<? super PyTargetExpression> follow);
```

#### PyResolveContext

**FQN:** `com.jetbrains.python.psi.resolve.PyResolveContext`
**Package:** `python-psi-impl`

Resolution context that controls how references are resolved. Used together with `TypeEvalContext` to identify function elements, classes, and other resolved targets.

#### PyTargetExpression (Variables / Assignments)

**FQN:** `com.jetbrains.python.psi.PyTargetExpression`
**Package:** `python-psi-api`
**Plugin:** `PythonCore`

Represents the left-hand side of assignments (variable definitions).

```java
// Find the assigned value (right-hand side)
@Nullable PyExpression findAssignedValue();

// Multi-resolve assigned value
List<PsiElement> multiResolveAssignedValue(@NotNull PyResolveContext resolveContext);

// Get annotation (type hint)
@Nullable PyAnnotation getAnnotation();

// Get qualifier for attribute access
@Nullable PyExpression getQualifier();

// Get containing class (for class attributes)
@Nullable PyClass getContainingClass();

// Get the reference
@NotNull PsiReference getReference();
```

#### PyClass — Definition Navigation

**FQN:** `com.jetbrains.python.psi.PyClass`
**Package:** `python-psi-api`
**Plugin:** `PythonCore`

```java
// Find methods by name
PyFunction findMethodByName(@Nullable String name, boolean inherited,
                            TypeEvalContext context);
List<PyFunction> multiFindMethodByName(@NotNull String name, boolean inherited,
                                       @Nullable TypeEvalContext context);

// Find __init__ or __new__
PyFunction findInitOrNew(boolean inherited, @Nullable TypeEvalContext context);
List<PyFunction> multiFindInitOrNew(boolean inherited, @Nullable TypeEvalContext context);

// Find nested classes
PyClass findNestedClass(String name, boolean inherited);
```

#### PyFunction — Definition Navigation

**FQN:** `com.jetbrains.python.psi.PyFunction`
**Package:** `python-psi-api`
**Plugin:** `PythonCore`

```java
// Get containing class (for methods)
@Nullable PyClass getContainingClass();

// Get parameter list
@NotNull PyParameterList getParameterList();

// Get the function body
@NotNull PyStatementList getStatementList();

// Get docstring
@Nullable PyStringLiteralExpression getDocStringExpression();
```

#### How to Navigate Usage → Definition (Implementation Pattern)

```kotlin
fun findDefinition(project: Project, element: PsiElement): PsiElement? {
    // 1. Get reference at the element
    val ref = element.reference ?: return null
    
    // 2. Resolve to definition
    val resolved = ref.resolve()
    
    // 3. For PyReferenceExpression, can follow assignment chain
    if (element is PyReferenceExpression) {
        val context = PyResolveContext.defaultContext(
            TypeEvalContext.codeAnalysis(project, element.containingFile)
        )
        val result = element.followAssignmentsChain(context)
        return result.element  // Ultimate definition
    }
    
    return resolved
}
```

#### PyReferenceResolveProvider (Extension Point)

**FQN:** `com.jetbrains.python.psi.resolve.PyReferenceResolveProvider`
**Extension point:** For adding custom reference resolution logic
**Sample:** `com.jetbrains.python.psi.resolve.PythonBuiltinReferenceResolveProvider`

---

## 3. Find References

### Platform API — Works for Python

`ReferencesSearch` from the IntelliJ Platform works for Python elements. This is the **primary API** for finding usages.

**FQN:** `com.intellij.psi.search.searches.ReferencesSearch`
**Package:** Platform API (`platform-indexing-api`)
**Plugin:** Platform (works with any language)

```java
// Basic usage — finds all references to a Python element
PsiElement pyElement = ...; // PyClass, PyFunction, PyTargetExpression, etc.
Query<PsiReference> query = ReferencesSearch.search(pyElement);

for (PsiReference ref : query) {
    PsiElement usage = ref.getElement();
    // Process usage
}

// Scoped search
SearchScope scope = GlobalSearchScope.projectScope(project);
Query<PsiReference> scopedQuery = ReferencesSearch.search(pyElement, scope, false);

// Get all at once
Collection<PsiReference> allRefs = query.findAll();
```

### Python-Specific Considerations

- `ReferencesSearch` handles Python's dynamic features (method overrides, dynamic attribute access) through the Python plugin's reference contributor extensions
- For class method overrides, the search will find subclass override call sites
- The `useScope` parameter on `PsiElement.getUseScope()` correctly handles Python's module-level scoping

### No Python-Specific Reference Search API Needed

Unlike Java's `OverridingMethodsSearch` (which has no Python equivalent), the standard `ReferencesSearch` is sufficient for Python. Python doesn't have formal override annotations, so reference search is the primary mechanism.

---

## 4. Type Hierarchy

### PyClass — Hierarchy Methods

**FQN:** `com.jetbrains.python.psi.PyClass`
**Package:** `python-psi-api`
**Plugin:** `PythonCore`

```java
// Get direct superclasses as PyClass instances
PyClass[] getSuperClasses(@Nullable TypeEvalContext context);

// Get superclass expressions (what's written in the class header)
PyExpression[] getSuperClassExpressions();

// Get superclass types (including generics resolution)
List<PyClassLikeType> getSuperClassTypes(@NotNull TypeEvalContext context);

// Get ALL ancestor classes (full MRO traversal)
List<PyClass> getAncestorClasses(@Nullable TypeEvalContext context);

// Get methods (own and inherited)
PyFunction[] getMethods();
PyFunction[] getMethodsInherited(@Nullable TypeEvalContext context);

// Check subclass relationship
boolean isSubclass(PyClass parent, @Nullable TypeEvalContext context);
boolean isSubclass(@NotNull String superClassQName, @Nullable TypeEvalContext context);

// Get nested classes
PyClass[] getNestedClasses();
```

### PyTypeHierachyProvider (Note: Typo in Actual Filename)

**FQN:** `com.jetbrains.python.hierarchy.PyTypeHierachyProvider`
**Source:** `python/src/com/jetbrains/python/hierarchy/PyTypeHierachyProvider.java`
**Plugin:** `PythonCore`

This is the standard `HierarchyProvider` implementation for Python. Note the typo in the class name ("Hierachy" instead of "Hierarchy") — this is in the actual JetBrains source.

**Related files in `python/src/com/jetbrains/python/hierarchy/`:**
- `PyTypeHierachyProvider.java` — Provider entry point
- `PyTypeHierarchyBrowser.java` — UI for browsing type hierarchy
- `PyHierarchyNodeDescriptor.java` — Node representation in hierarchy tree
- `PyHierarchyUtils.java` — Utility functions

### Building a Type Hierarchy Tree (Implementation Pattern)

```kotlin
fun buildTypeHierarchy(pyClass: PyClass, context: TypeEvalContext): TypeHierarchyNode {
    // 1. Supertypes (walk up)
    val supers = pyClass.getAncestorClasses(context)
    
    // 2. Subtypes (walk down) — use DefinitionsScopedSearch or ClassInheritorsSearch
    val inheritors = com.intellij.psi.search.searches.ClassInheritorsSearch
        .search(pyClass, GlobalSearchScope.projectScope(project), true)
    
    // 3. Build tree
    return TypeHierarchyNode(
        cls = pyClass,
        supers = supers.map { buildAncestorNode(it) },
        subs = inheritors.map { buildTypeHierarchy(it as PyClass, context) }
    )
}
```

### PyDisjointBaseUtil — MRO Analysis

**FQN:** `com.jetbrains.python.psi.PyDisjointBaseUtil`
**Package:** `python-psi-impl`

Handles Python's C3 linearization (MRO) for multiple inheritance analysis.

### PyABCUtil — Abstract Base Class Analysis

**FQN:** `com.jetbrains.python.psi.types.PyABCUtil`
**Package:** `python-psi-impl`

Utilities for analyzing abstract base class protocols (abc.ABC, abc.abstractmethod).

---

## 5. Call Hierarchy

### PyCallHierarchyProvider — EXISTS

**FQN:** `com.jetbrains.python.hierarchy.call.PyCallHierarchyProvider`
**Source:** `python/src/com/jetbrains/python/hierarchy/call/PyCallHierarchyProvider.java`
**Plugin:** `PythonCore`

Python has a **complete call hierarchy implementation** in the IntelliJ platform. The call hierarchy directory contains 6 files:

| Class | Purpose |
|---|---|
| `PyCallHierarchyProvider.java` | Provider entry point (registered as `CallHierarchyProvider` for Python) |
| `PyCallHierarchyBrowser.java` | UI browser component |
| `PyCallHierarchyTreeStructureBase.java` | Base tree structure |
| `PyCallerFunctionTreeStructure.java` | "Callers of" tree (who calls this function) |
| `PyCalleeFunctionTreeStructure.java` | "Callees of" tree (what does this function call) |
| `PyStaticCallHierarchyUtil.java` | Static analysis utilities |

### PyStaticCallHierarchyUtil

**FQN:** `com.jetbrains.python.hierarchy.call.PyStaticCallHierarchyUtil`
**Key method:** `getCallees(PyFunction)` — returns the set of functions called by a given function

### Building Call Hierarchy Programmatically

```kotlin
fun getCallers(function: PyFunction, project: Project): List<PyFunction> {
    // Use ReferencesSearch to find all call sites
    val refs = ReferencesSearch.search(function, GlobalSearchScope.projectScope(project))
    return refs.mapNotNull { ref ->
        PsiTreeUtil.getParentOfType(ref.element, PyFunction::class.java)
    }.distinct()
}

fun getCallees(function: PyFunction): List<PyCallExpression> {
    // Walk the function body for call expressions
    return PsiTreeUtil.findChildrenOfType(function, PyCallExpression::class.java).toList()
}
```

### PyCallExpression — Call Site Analysis

**FQN:** `com.jetbrains.python.psi.PyCallExpression`
**Package:** `python-psi-api`
**Plugin:** `PythonCore`

```java
// Get what's being called
@Nullable PyExpression getCallee();

// Get arguments
@Nullable PyArgumentList getArgumentList();
PyExpression @NotNull [] getArguments();

// Get keyword argument
@Nullable PyExpression getKeywordArgument(@NotNull String keyword);

// Resolve callee to callable(s)
@NotNull List<PyCallable> multiResolveCalleeFunction(
    @NotNull PyResolveContext resolveContext);

@NotNull List<PyCallableType> multiResolveCallee(
    @NotNull PyResolveContext resolveContext);

// Map arguments to parameters
@NotNull List<PyArgumentsMapping> multiMapArguments(
    @NotNull PyResolveContext resolveContext);
```

---

## 6. Type Inference

### TypeEvalContext — Central Type Inference Context

**FQN:** `com.jetbrains.python.psi.types.TypeEvalContext`
**Package:** `python-psi-api`
**Plugin:** `PythonCore`

`TypeEvalContext` manages caching and evaluation scope for type inference. **All type inference operations require a `TypeEvalContext`.**

#### Factory Methods

```java
// For code completion — fast, may sacrifice accuracy
static TypeEvalContext codeCompletion(@NotNull Project project,
                                      @Nullable PsiFile origin);

// For code analysis/inspections — balanced
static TypeEvalContext codeAnalysis(@NotNull Project project,
                                    @Nullable PsiFile origin);

// For deep code insight — maximum accuracy, slower
static TypeEvalContext deepCodeInsight(@NotNull Project project);

// For user-initiated actions (e.g., quick doc, parameter info)
static TypeEvalContext userInitiated(@NotNull Project project,
                                     @Nullable PsiFile origin);
```

#### Key Instance Methods

```java
// Get return type of a callable
@Nullable PyType getReturnType(@NotNull PyCallable callable);

// Get type of a typed element
@Nullable PyType getType(@NotNull PyTypedElement element);

// Check if inference is allowed for an element
boolean allowReturnTypes(@NotNull PsiElement element);
boolean allowCallContext(@NotNull PsiElement element);
```

### PyTypeProvider — Extension Point for Type Inference

**FQN:** `com.jetbrains.python.psi.impl.PyTypeProvider`
**Package:** `python-psi-api`
**Extension point:** `Pythonid.typeProvider`
**Plugin:** `PythonCore`

This is the core extension point for adding custom type inference logic. Multiple providers are chained; the first non-null result wins.

```java
interface PyTypeProvider {
    // Extension point name
    ExtensionPointName<PyTypeProvider> EP_NAME =
        ExtensionPointName.create("Pythonid.typeProvider");

    // Infer type of a reference expression
    @Nullable PyType getReferenceExpressionType(
        @NotNull PyReferenceExpression referenceExpression,
        @NotNull TypeEvalContext context);

    // Infer type of a reference target (variable, parameter)
    @Nullable Ref<PyType> getReferenceType(
        @NotNull PsiElement referenceTarget,
        @NotNull TypeEvalContext context,
        @Nullable PsiElement anchor);

    // Infer parameter type
    @Nullable Ref<PyType> getParameterType(
        @NotNull PyNamedParameter param,
        @NotNull PyFunction func,
        @NotNull TypeEvalContext context);

    // Infer return type of a callable
    @Nullable Ref<PyType> getReturnType(
        @NotNull PyCallable callable,
        @NotNull TypeEvalContext context);

    // Infer call result type
    @Nullable Ref<PyType> getCallType(
        @NotNull PyFunction function,
        @NotNull PyCallSiteExpression callSite,
        @NotNull TypeEvalContext context);

    // Infer context manager variable type (for `with` statements)
    @Nullable PyType getContextManagerVariableType(
        PyClass contextManager,
        PyExpression withExpression,
        TypeEvalContext context);

    // Infer callable type (the function object itself)
    @Nullable PyType getCallableType(
        @NotNull PyCallable callable,
        @NotNull TypeEvalContext context);

    // Infer generic type parameters
    @Nullable PyType getGenericType(
        @NotNull PyClass cls,
        @NotNull TypeEvalContext context);

    // Get generic type substitutions
    @NotNull Map<PyType, PyType> getGenericSubstitutions(
        @NotNull PyClass cls,
        @NotNull TypeEvalContext context);

    // Prepare callee type for call expression analysis
    @Nullable Ref<@Nullable PyCallableType> prepareCalleeTypeForCall(
        @Nullable PyType type,
        @NotNull PyCallExpression call,
        @NotNull TypeEvalContext context);

    // (Experimental) Get member types
    @ApiStatus.Experimental
    @Nullable List<@NotNull PyTypeMember> getMemberTypes(
        @NotNull PyType type,
        @NotNull String name,
        @Nullable PyExpression location,
        @NotNull AccessDirection direction,
        @NotNull PyResolveContext context);
}
```

### Built-in Type Providers

| Provider | FQN | Purpose |
|---|---|---|
| `PyStdlibTypeProvider` | `c.j.p.codeInsight.stdlib.PyStdlibTypeProvider` | Built-in library type inference (list, dict, etc.) |
| `PyDataclassTypeProvider` | `c.j.p.codeInsight.stdlib.PyDataclassTypeProvider` | `@dataclass` field type generation |
| `PyNamedTupleTypeProvider` | `c.j.p.codeInsight.stdlib.PyNamedTupleTypeProvider` | `NamedTuple` structure analysis |
| `PyTypedDictTypeProvider` | `c.j.p.codeInsight.typing.PyTypedDictTypeProvider` | `TypedDict` structure analysis |
| `PyiTypeProvider` | `c.j.p.pyi.PyiTypeProvider` | Type stub (`.pyi`) file support |
| `PyDecoratedFunctionTypeProvider` | `c.j.p.codeInsight.decorator.PyDecoratedFunctionTypeProvider` | Decorator effect analysis |
| `PyJavaTypeProvider` | `c.j.p.community.plugin.java.psi.impl.PyJavaTypeProvider` | Java/Python interop |

### PyType — Type System Hierarchy

**Base:** `com.jetbrains.python.psi.types.PyType` (interface)

| Type Class | FQN | Represents |
|---|---|---|
| `PyClassType` | `c.j.p.psi.types.PyClassType` | Concrete class instances |
| `PyUnionType` | `c.j.p.psi.types.PyUnionType` | Union types (`str | int`, `Optional[X]`) |
| `PyLiteralType` | `c.j.p.psi.types.PyLiteralType` | Literal value types (`Literal["foo"]`) |
| `PyTupleType` | `c.j.p.psi.types.PyTupleType` | Tuple types with per-element tracking |
| `PyTypedDictType` | `c.j.p.psi.types.PyTypedDictType` | TypedDict structures |
| `PyCallableType` | `c.j.p.psi.types.PyCallableType` | Callable signatures |
| `PyIntersectionType` | `c.j.p.psi.types.PyIntersectionType` | Intersection types |
| `PyAnyType` | `c.j.p.psi.types.PyAnyType` | `Any` / unknown type |
| `PySelfType` | `c.j.p.psi.types.PySelfType` | `Self` type for generic classes |
| `PyDynamicallyEvaluatedType` | `c.j.p.psi.types.PyDynamicallyEvaluatedType` | Runtime-determined types |

### Getting Types (Implementation Pattern)

```kotlin
fun getTypeAtPosition(project: Project, psiFile: PsiFile, offset: Int): String? {
    val context = TypeEvalContext.codeAnalysis(project, psiFile)
    val element = psiFile.findElementAt(offset) ?: return null
    
    // For expressions
    val expr = PsiTreeUtil.getParentOfType(element, PyExpression::class.java)
    if (expr != null) {
        val type = context.getType(expr)
        return type?.name  // e.g., "str", "List[int]", "Optional[MyClass]"
    }
    
    // For variables
    val target = PsiTreeUtil.getParentOfType(element, PyTargetExpression::class.java)
    if (target != null) {
        val type = context.getType(target)
        return type?.name
    }
    
    // For function return types
    val function = PsiTreeUtil.getParentOfType(element, PyFunction::class.java)
    if (function != null) {
        val returnType = context.getReturnType(function)
        return returnType?.name
    }
    
    return null
}
```

### Mypy/Pyright Stub Integration

Type stubs (`.pyi` files) are handled by `PyiTypeProvider`. The Python plugin:

1. Ships bundled stubs for the standard library (typeshed)
2. Reads `.pyi` stubs from installed packages
3. Uses `PyiTypeProvider` to overlay stub types onto runtime implementations
4. Stub types take precedence over inferred types

No explicit mypy/pyright tool integration exists in the PSI layer — those are external tools. The Python plugin has its own built-in type checker (`PyTypeCheckerInspection`).

### Type Checking and Validation

```java
// Type compatibility checking
PyTypeChecker  // com.jetbrains.python.psi.types.PyTypeChecker

// Constraint satisfaction for generics
PyTypeInferenceCspFactory  // Generates CSP for generic type parameter solving
PyTypeInferenceCspSolver   // Solves generic type parameter resolution

// Expected type computation
PyExpectedTypeJudgement    // Context-aware expected type analysis
```

---

## 7. Dataflow Analysis

### PyDefUseUtil — Definition-Use Chain Analysis

**FQN:** `com.jetbrains.python.refactoring.PyDefUseUtil`
**Package:** `python-psi-impl`
**Plugin:** `PythonCore`

This class provides control-flow-based definition-use analysis for Python variables.

```java
// Get latest definitions reaching a given anchor point
public static @NotNull LatestDefsResult getLatestDefs(
    @NotNull ScopeOwner block,
    @NotNull String varName,
    @NotNull PsiElement anchor,
    boolean acceptTypeAssertions,
    boolean acceptImplicitImports,
    @NotNull TypeEvalContext context);

// Overload with explicit control flow
public static @NotNull LatestDefsResult getLatestDefs(
    @NotNull PyControlFlow controlFlow,
    @NotNull ScopeOwner scopeOwner,
    @NotNull String varName,
    @NotNull PsiElement anchor,
    boolean acceptTypeAssertions,
    boolean acceptImplicitImports,
    @NotNull TypeEvalContext context);

// Get post-references (uses after a definition)
public static PsiElement @NotNull [] getPostRefs(
    @NotNull ScopeOwner block,
    @NotNull PyTargetExpression var,
    PyExpression anchor);

// Check if one element is defined before another in the control flow
public static boolean isDefinedBefore(
    final @NotNull PsiElement searched,
    final @NotNull PsiElement target);
```

#### LatestDefsResult (Record)

```java
record LatestDefsResult(
    List<Instruction> defs,   // List of definition instructions
    boolean foundPrefixCall   // Whether a prefix call was found
)
```

#### Constants

```java
static final int MAX_CONTROL_FLOW_SIZE = 200;  // Safety limit for analysis
```

### ScopeOwner — Scope Boundaries

**FQN:** `com.jetbrains.python.codeInsight.controlflow.ScopeOwner`
**Package:** `python-psi-api`

Python scope owners are: `PyFile`, `PyFunction`, `PyClass`. These define variable scope boundaries.

```kotlin
// Get the scope owner for any element
val scopeOwner = ScopeUtil.getScopeOwner(element)
// or
val declarationScope = ScopeUtil.getDeclarationScope(element)
```

### Control Flow Graph

**FQN:** `com.jetbrains.python.codeInsight.controlflow.PyControlFlowBuilder`
**Package:** `python-psi-impl`

```java
// Build a control flow graph
PyControlFlow buildControlFlow(@NotNull ScopeOwner owner);
```

#### ControlFlowCache — Getting Control Flow

**FQN:** `com.jetbrains.python.codeInsight.controlflow.ControlFlowCache`
**Package:** `python-psi-impl`

```java
// Get cached control flow for a scope owner
static PyControlFlow getControlFlow(@NotNull ScopeOwner element);

// Get with custom builder
static PyControlFlow getControlFlow(
    @NotNull ScopeOwner element,
    @NotNull PyControlFlowBuilder builder);

// Get scope information
static Scope getScope(@NotNull ScopeOwner element);
```

The cache uses `SoftReference` — it may be garbage-collected under memory pressure and rebuilt on demand.

### PyTypeAssertionEvaluator — Type Narrowing

**FQN:** `com.jetbrains.python.codeInsight.controlflow.PyTypeAssertionEvaluator`
**Package:** `python-psi-impl`

Handles type narrowing from `isinstance()`, `assert`, type guards, and other control flow-dependent type refinement.

### Implementation Pattern for Dataflow

```kotlin
fun getReachingDefinitions(
    project: Project,
    element: PsiElement,
    varName: String
): List<PsiElement> {
    val context = TypeEvalContext.codeAnalysis(project, element.containingFile)
    val scopeOwner = ScopeUtil.getScopeOwner(element) ?: return emptyList()
    
    val result = PyDefUseUtil.getLatestDefs(
        scopeOwner,
        varName,
        element,
        /* acceptTypeAssertions = */ true,
        /* acceptImplicitImports = */ true,
        context
    )
    
    return result.defs.mapNotNull { it.element }
}
```

---

## 8. Decorators (Python's Annotations)

### PyDecoratorList

**FQN:** `com.jetbrains.python.psi.PyDecoratorList`
**Package:** `python-psi-api`
**Plugin:** `PythonCore`

```java
// Get all decorators in declaration order (outermost first)
PyDecorator @NotNull [] getDecorators();

// Find a decorator by name
default @Nullable PyDecorator findDecorator(String name);
```

### PyDecorator

**FQN:** `com.jetbrains.python.psi.PyDecorator`
**Package:** `python-psi-api`
**Plugin:** `PythonCore`

Represents a single decorator (e.g., `@staticmethod`, `@app.route("/path")`).

```java
// Get the decorated function/class
default @Nullable PyFunction getTarget();

// Get the decorator expression (the name part)
default @Nullable PyExpression getExpression();

// Check if it's a built-in decorator
boolean isBuiltin();

// Check if it has parenthesized arguments: @foo(...) vs @foo
boolean hasArgumentList();

// Get arguments (inherits from PyCallExpression)
default @Nullable PyArgumentList getArgumentList();

// Get the callee expression
default @Nullable PyExpression getCallee();
```

**Note:** `PyDecorator` extends `PyCallExpression` — all call expression methods are available, including `getArguments()`, `getKeywordArgument(name)`.

### Getting Decorator Info (Implementation Pattern)

```kotlin
fun getDecorators(function: PyFunction): List<DecoratorInfo> {
    val decoratorList = function.decoratorList ?: return emptyList()
    return decoratorList.decorators.map { decorator ->
        DecoratorInfo(
            name = decorator.qualifiedName?.toString() ?: decorator.name ?: "unknown",
            hasArgs = decorator.hasArgumentList(),
            arguments = decorator.arguments?.map { it.text } ?: emptyList(),
            isBuiltin = decorator.isBuiltin
        )
    }
}

// For classes
fun getClassDecorators(pyClass: PyClass): List<DecoratorInfo> {
    val decoratorList = pyClass.decoratorList ?: return emptyList()
    // Same pattern as above
}
```

### Qualifier Access for Complex Decorators

For decorators like `@app.route("/path")` or `@pytest.mark.parametrize(...)`:

```kotlin
val decorator: PyDecorator = ...
val callee = decorator.callee
if (callee is PyReferenceExpression) {
    val qualifier = callee.qualifier  // "app" for @app.route
    val name = callee.referencedName  // "route" for @app.route
}
```

---

## 9. File Structure

### PyFile

**FQN:** `com.jetbrains.python.psi.PyFile`
**Package:** `python-psi-api`
**Plugin:** `PythonCore`

```java
// Top-level declarations
List<PyClass> getTopLevelClasses();
List<PyFunction> getTopLevelFunctions();
List<PyTargetExpression> getTopLevelAttributes();
List<PyTypeAliasStatement> getTypeAliasStatements();

// Find by name
PyFunction findTopLevelFunction(@NotNull String name);
PyClass findTopLevelClass(@NotNull String name);
PyTargetExpression findTopLevelAttribute(@NotNull String name);
PyTypeAliasStatement findTypeAliasStatement(@NotNull String name);

// Imports
List<PyImportElement> getImportTargets();
List<PyFromImportStatement> getFromImports();
List<PyImportStatementBase> getImportBlock();

// __all__ definition
List<String> getDunderAll();

// Exports / name resolution
PsiElement findExportedName(String name);
Iterable<PyElement> iterateNames();
List<RatedResolveResult> multiResolveName(@NotNull String name);
List<RatedResolveResult> multiResolveName(@NotNull String name, boolean exported);
PsiElement getElementNamed(String name);

// All statements
List<PyStatement> getStatements();

// Documentation
PyStringLiteralExpression getDocStringExpression();
String getDeprecationMessage();
```

### Full File Structure Enumeration Pattern

```kotlin
fun getFileStructure(pyFile: PyFile): FileStructure {
    return FileStructure(
        imports = pyFile.importBlock.map { formatImport(it) },
        classes = pyFile.topLevelClasses.map { cls ->
            ClassInfo(
                name = cls.name ?: "anonymous",
                superClasses = cls.superClassExpressions.map { it.text },
                methods = cls.methods.map { fn ->
                    MethodInfo(
                        name = fn.name ?: "anonymous",
                        params = fn.parameterList.parameters.map { it.name ?: "_" },
                        decorators = fn.decoratorList?.decorators?.map { it.name ?: "" } ?: emptyList(),
                        returnAnnotation = fn.annotation?.value?.text
                    )
                },
                classAttributes = cls.classAttributes.map { it.name ?: "" },
                instanceAttributes = cls.instanceAttributes.map { it.name ?: "" },
                nestedClasses = cls.nestedClasses.map { it.name ?: "" },
                decorators = cls.decoratorList?.decorators?.map { it.name ?: "" } ?: emptyList()
            )
        },
        functions = pyFile.topLevelFunctions.map { fn ->
            FunctionInfo(
                name = fn.name ?: "anonymous",
                params = fn.parameterList.parameters.map { it.name ?: "_" },
                returnAnnotation = fn.annotation?.value?.text
            )
        },
        globalVariables = pyFile.topLevelAttributes.map { it.name ?: "" },
        typeAliases = pyFile.typeAliasStatements.map { it.name ?: "" },
        dunderAll = pyFile.dunderAll
    )
}
```

### PyClass — Full Structure

```java
// Methods
PyFunction[] getMethods();

// Properties (computed attributes)
Map<String, Property> getProperties();

// Class-level attributes (cls.x = ...)
List<PyTargetExpression> getClassAttributes();

// Instance attributes (self.x = ... in __init__)
List<PyTargetExpression> getInstanceAttributes();

// __slots__
List<String> getSlots(@Nullable TypeEvalContext context);
List<String> getOwnSlots();

// Nested classes
PyClass[] getNestedClasses();
```

---

## 10. Structural Search

### Status: NOT AVAILABLE for Python

**Structural search and replace is NOT currently supported for Python** in IntelliJ/PyCharm. It works only for Java, Kotlin, Scala, and Groovy.

There is a long-standing feature request (see JetBrains support post) but `PythonStructuralSearchProfile` does not exist as a functional implementation.

### Alternatives for Python

For the `StructuralSearchTool` Python implementation, use these alternatives:

1. **PSI Tree Walking** — `PsiTreeUtil.findChildrenOfType()` with custom predicates
2. **PSI Pattern Matching** — Manual tree traversal matching specific PSI structures
3. **Code Templates** — Build structural patterns using PyClass/PyFunction/PyCallExpression matchers

```kotlin
// Example: Find all classes that inherit from a specific base
fun findClassesExtending(project: Project, baseName: String): List<PyClass> {
    val scope = GlobalSearchScope.projectScope(project)
    val context = TypeEvalContext.codeAnalysis(project, null)
    
    // Use ClassInheritorsSearch (platform API, works for Python)
    val baseClass = PyPsiFacade.getInstance(project)
        .findClass(baseName) ?: return emptyList()
    
    return ClassInheritorsSearch.search(baseClass, scope, true)
        .filterIsInstance<PyClass>()
        .toList()
}

// Example: Find all functions with a specific decorator
fun findDecoratedFunctions(pyFile: PyFile, decoratorName: String): List<PyFunction> {
    return PsiTreeUtil.findChildrenOfType(pyFile, PyFunction::class.java)
        .filter { fn -> fn.decoratorList?.findDecorator(decoratorName) != null }
}
```

### Workaround for Agent Tool

Since `StructuralSearchTool` currently uses `StructuralSearchProfile`, the Python implementation should fall back to PSI-based pattern matching and clearly indicate in the tool response that structural search is not natively available for Python.

---

## 11. Test Finder

### Test Detection API

**Key classes in `com.jetbrains.python.testing`:**

| Class | FQN | Purpose |
|---|---|---|
| `PyAbstractTestConfiguration` | `c.j.p.testing.PyAbstractTestConfiguration` | Base test run configuration |
| `PyAbstractTestFactory` | `c.j.p.testing.PyAbstractTestFactory` | Factory for creating test configs |
| `PythonTestConfigurationType` | `c.j.p.testing.PythonTestConfigurationType` | Configuration type registry |
| `PythonUnitTestDetectorsBasedOnSettings` | `c.j.p.testing.PythonUnitTestDetectorsBasedOnSettings` | Settings-aware test detection |
| `PyTestsLocator` | `c.j.p.testing.PyTestsLocator` | SMTestLocator for test navigation |

### Core Test Detection Function

```kotlin
// The primary test detection function
fun isTestElement(
    element: PsiElement,
    testCaseClassRequired: ThreeState,
    typeEvalContext: TypeEvalContext
): Boolean
```

This delegates to `PythonUnitTestDetectorsBasedOnSettings` which provides:
- `isTestFile()` — Identifies test modules
- `isTestFunction()` — Detects functions matching test patterns
- `isTestClass()` — Validates class-based tests

### Test Discovery Patterns

**pytest:**
- Files: `test_*.py` or `*_test.py`
- Functions: `test_*` prefix
- Classes: `Test*` prefix (no `__init__`)
- Markers: `@pytest.mark.parametrize`, `@pytest.mark.skip`, etc.

**unittest:**
- Classes: subclasses of `unittest.TestCase`
- Methods: `test*` prefix within `TestCase` subclasses

**nose:**
- Functions: `test_*` prefix
- Classes: `Test*` prefix

### Test Target Types

```kotlin
enum class PyRunTargetVariant {
    PATH,    // File system paths (test_file.py)
    PYTHON,  // Qualified names (module.TestClass.test_method)
    CUSTOM   // Framework-specific (pytest node IDs)
}
```

### Factory/Configuration Resolution

```kotlin
// Get test framework factory by ID
fun getFactoryById(id: String): PyAbstractTestFactory<*>?
fun getFactoryByIdOrDefault(id: String): PyAbstractTestFactory<*>

// Factory instances from configuration type
PythonTestConfigurationType.getInstance().typedFactories
```

### Implementation Pattern for Test Finder

```kotlin
fun findTests(pyFile: PyFile, context: TypeEvalContext): List<TestInfo> {
    val results = mutableListOf<TestInfo>()
    
    // Find test functions at module level
    pyFile.topLevelFunctions.forEach { fn ->
        if (fn.name?.startsWith("test_") == true || fn.name?.startsWith("test") == true) {
            results.add(TestInfo(
                name = fn.name!!,
                type = "function",
                framework = "pytest",
                markers = getTestMarkers(fn)
            ))
        }
    }
    
    // Find test classes
    pyFile.topLevelClasses.forEach { cls ->
        val isTestCase = cls.isSubclass("unittest.TestCase", context)
        val isPytestClass = cls.name?.startsWith("Test") == true
        
        if (isTestCase || isPytestClass) {
            val testMethods = cls.methods.filter { m ->
                m.name?.startsWith("test_") == true || m.name?.startsWith("test") == true
            }
            results.add(TestInfo(
                name = cls.name!!,
                type = "class",
                framework = if (isTestCase) "unittest" else "pytest",
                methods = testMethods.map { it.name!! },
                markers = getTestMarkers(cls)
            ))
        }
    }
    
    return results
}

fun getTestMarkers(element: PyDecoratable): List<String> {
    val decorators = element.decoratorList?.decorators ?: return emptyList()
    return decorators
        .filter { it.qualifiedName?.toString()?.startsWith("pytest.mark") == true }
        .map { it.qualifiedName?.toString() ?: it.name ?: "" }
}
```

### PyCharm pytest Plugin (Helper Script)

**Path:** `python/helpers/pycharm/teamcity/pytest_plugin.py`

This is a pytest plugin that PyCharm injects into test runs for test failure reporting. It's not a PSI API but relevant for understanding how PyCharm discovers and runs tests.

---

## 12. Python Debug APIs

### PyDebugProcess

**FQN:** `com.jetbrains.python.debugger.PyDebugProcess`
**Source:** `python/src/com/jetbrains/python/debugger/PyDebugProcess.java`
**Extends:** `XDebugProcess`
**Implements:** `IPyDebugProcess`, `ProcessListener`, `PyDebugProcessWithConsole`, `PyStepIntoSupport`
**Plugin:** `PythonCore` (basic), `Pythonid` (advanced — Django templates, remote debug)

#### Session Management

```java
XDebugSession getSession();          // Inherited from XDebugProcess
void sessionInitialized();           // Initializes debugger, waits for connection
```

#### Stepping

```java
void startStepOver(@Nullable XSuspendContext context);
void startStepInto(@Nullable XSuspendContext context);
void startStepOut(@Nullable XSuspendContext context);
void startStepIntoMyCode(@Nullable XSuspendContext context);  // User code only
void startSmartStepInto(@NotNull PySmartStepIntoVariant variant);  // Intelligent step-into
```

#### Execution Control

```java
void resume(@Nullable XSuspendContext context);
void stop();
void startPausing();
void runToPosition(@NotNull XSourcePosition position, @Nullable XSuspendContext context);
```

#### Evaluation & Inspection

```java
// Evaluate expressions
PyDebugValue evaluate(String expression, boolean execute, boolean doTrunc);

// Console execution
void consoleExec(String command, @NotNull PyDebugCallback<String> callback);

// Frame and variable access
@Nullable XValueChildrenList loadFrame(@Nullable XStackFrame contextFrame);
@Nullable XValueChildrenList loadVariable(PyDebugValue var);
void changeVariable(PyDebugValue var, String value);

// Array/data viewer
void getArrayItems(PyDebugValue var, int rowOffset, int colOffset,
                   int rows, int cols, String format);
void executeDataViewerCommand(DataViewerCommandBuilder builder);

// Object reference analysis
void loadReferrers(PyReferringObjectsValue var, PyDebugCallback callback);
```

#### Breakpoint Management

```java
XBreakpointHandler<?>[] getBreakpointHandlers();
void addBreakpoint(PySourcePosition position, XLineBreakpoint<?> breakpoint);
void removeBreakpoint(PySourcePosition position);
```

#### Thread Management

```java
Collection<PyThreadInfo> getThreads();
boolean isConnected();
```

#### Source Position Resolution

```java
@Nullable XSourcePosition getSourcePositionForName(String name, String parentType);
@Nullable XSourcePosition getSourcePositionForType(String typeName);
```

#### Remote/Attach Debugging

```java
void handleDebugPort(int localPort);       // Remote debugger port mapping
PyPositionConverter getPositionConverter();  // Source mapping for remote
```

### PyDebugRunner

**FQN:** `com.jetbrains.python.debugger.PyDebugRunner`
**Source:** `python/src/com/jetbrains/python/debugger/PyDebugRunner.java`

Launches Python debug sessions. Creates `PyDebugProcess` via `XDebugProcessStarter`.

### Professional-Only Debug Features (Pythonid)

| Feature | Class/API | Notes |
|---|---|---|
| Django template debugging | Django-specific debug process subclass | Steps through `{% %}` tags |
| Remote interpreter | SSH-based interpreter + remote debug | `PyRemoteDebugProcess` |
| Docker debugging | Container-based debug sessions | Via Docker plugin integration |
| PyAttachToProcessAction | Attach debugger to running Python process | Local only, not remote |

### XDebugger Platform Integration

PyDebugProcess extends `XDebugProcess` from the platform XDebugger API. All standard XDebugger features work:

- Breakpoint types (line, exception, conditional)
- Watch expressions
- Variable modification
- Frame navigation
- Thread switching

The same `XDebugger` integration points used by the existing Java debug tools apply to Python via `PyDebugProcess`.

---

## 13. Build / Package Management APIs

### PyPackageManager (DEPRECATED)

**FQN:** `com.jetbrains.python.packaging.PyPackageManager`
**Status:** `@Deprecated(forRemoval = true)` — Use `PyTargetEnvironmentPackageManager` instead
**Plugin:** `PythonCore`

Key methods (still functional but deprecated):

```java
// Install packages
void install(@NotNull List<PyRequirement> requirements, @NotNull List<String> extraArgs);

// Refresh and get installed packages
List<PyPackage> refreshAndGetPackages(boolean alwaysRefresh);

// Create virtualenv
String createVirtualEnv(@NotNull String destinationDir, boolean useGlobalSite);
```

### PyPackageManagerUI (Not Deprecated)

**FQN:** `com.jetbrains.python.packaging.PyPackageManagerUI`

The UI wrapper for package management operations. Safe to use even though it internally calls deprecated APIs.

```java
void install(@NotNull List<PyRequirement> requirements, @NotNull List<String> extraArgs);
```

**Important threading note:** Use `executeOnPooledThread`, NOT `runWriteAction`, when calling install.

### PyPackageUtil

**FQN:** `com.jetbrains.python.packaging.PyPackageUtil`
**Plugin:** `PythonCore`

```java
// Setup.py detection
static boolean hasSetupPy(@NotNull Module module);
static @Nullable VirtualFile findSetupPyFile(@NotNull Module module);
static @Nullable PyFile findSetupPy(@NotNull Module module);
static @Nullable PyCallExpression findSetupCall(@NotNull Module module);

// Requirements.txt
static boolean hasRequirementsTxt(@NotNull Module module);
static @Nullable VirtualFile findRequirementsTxt(@NotNull Module module);
static @Nullable List<PyRequirement> getRequirementsFromTxt(@NotNull Module module);

// Setup.py requirements
static @Nullable List<PyRequirement> findSetupPyRequires(@NotNull Module module);
static @Nullable Map<String, List<PyRequirement>> findSetupPyExtrasRequire(
    @NotNull Module module);

// Package names
static @NotNull List<String> getPackageNames(@NotNull Module module);

// Package management availability
static boolean packageManagementEnabled(
    @Nullable Sdk sdk, boolean newUi, boolean calledFromInspection);

// Add requirement to project
static void addRequirementToTxtOrSetupPy(
    @NotNull Module module, @NotNull String requirementName,
    @NotNull LanguageLevel languageLevel);

// Watch for interpreter path changes
static void runOnChangeUnderInterpreterPaths(
    @NotNull Sdk sdk, @NotNull Disposable parentDisposable, @NotNull Runnable runnable);

// Requirements formatting
static @NotNull String requirementsToString(
    @NotNull List<? extends PyRequirement> requirements);
```

### PyPIPackageUtil

**FQN:** `com.jetbrains.python.packaging.PyPIPackageUtil`
**Source:** `python/src/com/jetbrains/python/packaging/PyPIPackageUtil.java`

Utilities for querying the Python Package Index (PyPI).

### PythonSdkType — Interpreter Detection

**FQN:** `com.jetbrains.python.sdk.PythonSdkType`
**Source:** `python/src/com/jetbrains/python/sdk/PythonSdkType.java`
**Plugin:** `PythonCore`

Represents a Python SDK (interpreter). Handles detection of:
- System Python installations
- virtualenv environments
- conda environments
- Poetry environments (detected from `pyproject.toml`)
- uv environments (detected as of 2025+)
- Hatch environments

### PySdkExt — SDK Utilities (Kotlin Extensions)

**FQN:** `com.jetbrains.python.sdk.PySdkExt`
**Source:** `python/src/com/jetbrains/python/sdk/PySdkExt.kt`

Kotlin extension functions for Python SDK detection:

```kotlin
// Detect virtual environments
fun detectVirtualEnvs(...)

// Check environment type
PythonSdkUtil.isConda(sdk)
PythonSdkUtil.isVirtualEnv(sdk)
```

### Environment Detection Pattern

```kotlin
fun detectPythonEnvironment(project: Project): PythonEnvInfo? {
    val sdk = PythonSdkUtil.findPythonSdk(project.modules.firstOrNull()) ?: return null
    
    return PythonEnvInfo(
        pythonPath = sdk.homePath,
        version = sdk.versionString,
        isConda = PythonSdkUtil.isConda(sdk),
        isVirtualEnv = PythonSdkUtil.isVirtualEnv(sdk),
        sdkType = sdk.sdkType.name
    )
}
```

---

## 14. PSI Element Type Reference

### Complete List of Python PSI Element Types

All in package `com.jetbrains.python.psi`, from `python-psi-api`:

#### Core Elements
| Interface | Represents |
|---|---|
| `PyElement` | Base interface for all Python PSI elements |
| `PyExpression` | Base for all expressions |
| `PyStatement` | Base for all statements |
| `PyFile` | Python source file |

#### Declarations
| Interface | Represents |
|---|---|
| `PyClass` | Class definition (`class Foo:`) |
| `PyFunction` | Function/method definition (`def foo():`) |
| `PyCallable` | Anything callable (functions, classes, lambdas) |
| `PyParameter` | Function parameter |
| `PyDecorator` | Single decorator (`@staticmethod`) |
| `PyDecoratorList` | List of decorators on a function/class |

#### Expressions
| Interface | Represents |
|---|---|
| `PyCallExpression` | Function/method call (`foo(x)`) |
| `PyReferenceExpression` | Name reference (`foo`, `obj.attr`) |
| `PyBinaryExpression` | Binary operation (`a + b`) |
| `PyLambdaExpression` | Lambda expression (`lambda x: x`) |
| `PyTargetExpression` | Assignment target (`x = ...`) |
| `PySubscriptionExpression` | Subscript (`arr[0]`) |
| `PyConditionalExpression` | Ternary (`x if cond else y`) |

#### Literals
| Interface | Represents |
|---|---|
| `PyLiteralExpression` | Base for all literals |
| `PyStringLiteralExpression` | String literal |
| `PyNumericLiteralExpression` | Number literal |
| `PyBoolLiteralExpression` | `True`/`False` |
| `PyNoneLiteralExpression` | `None` |
| `PyEllipsisLiteralExpression` | `...` |

#### Collections
| Interface | Represents |
|---|---|
| `PyListLiteralExpression` | List literal `[1, 2, 3]` |
| `PyTupleExpression` | Tuple `(1, 2, 3)` |
| `PySetLiteralExpression` | Set literal `{1, 2, 3}` |
| `PyDictLiteralExpression` | Dict literal `{"a": 1}` |

#### Comprehensions
| Interface | Represents |
|---|---|
| `PyListCompExpression` | `[x for x in ...]` |
| `PySetCompExpression` | `{x for x in ...}` |
| `PyDictCompExpression` | `{k: v for k, v in ...}` |
| `PyGeneratorExpression` | `(x for x in ...)` |

#### Control Flow Statements
| Interface | Represents |
|---|---|
| `PyIfStatement` | `if`/`elif`/`else` |
| `PyForStatement` | `for` loop |
| `PyWhileStatement` | `while` loop |
| `PyWithStatement` | `with` context manager |
| `PyTryExceptStatement` | `try`/`except`/`finally` |
| `PyBreakStatement` | `break` |
| `PyContinueStatement` | `continue` |
| `PyPassStatement` | `pass` |
| `PyReturnStatement` | `return` |
| `PyRaiseStatement` | `raise` |
| `PyAssertStatement` | `assert` |
| `PyMatchStatement` | `match` (3.10+) |

#### Pattern Matching (3.10+)
| Interface | Represents |
|---|---|
| `PyPattern` | Base pattern type |
| `PyCaseClause` | `case` clause |
| `PySequencePattern` | Sequence unpacking pattern |
| `PyMappingPattern` | Dictionary pattern |
| `PyClassPattern` | Class pattern |

#### Import Statements
| Interface | Represents |
|---|---|
| `PyImportStatement` | `import foo` |
| `PyFromImportStatement` | `from foo import bar` |
| `PyImportElement` | Individual imported name |

#### Assignment
| Interface | Represents |
|---|---|
| `PyAssignmentStatement` | `x = value` |
| `PyAugAssignmentStatement` | `x += value` |

---

## 15. Community vs Professional Feature Matrix

### APIs Available in PythonCore (Community — FREE)

- All PSI element types (PyClass, PyFunction, PyFile, etc.)
- Type inference (TypeEvalContext, PyTypeProvider)
- All type providers (stdlib, dataclass, typed dict, named tuple, stubs)
- Reference resolution (PyReferenceExpression, ReferencesSearch)
- Type hierarchy (PyTypeHierachyProvider)
- Call hierarchy (PyCallHierarchyProvider)
- Control flow analysis (PyControlFlowBuilder, PyDefUseUtil)
- Decorator analysis (PyDecoratorList, PyDecorator)
- File structure (PyFile.getTopLevelClasses/Functions/etc.)
- Test discovery (pytest, unittest, nose)
- Package management (PyPackageManager, PyPackageUtil)
- Basic debugging (PyDebugProcess)
- Type checking inspections (PyTypeCheckerInspection)

### APIs Requiring Pythonid (Professional — PAID)

- Django template debugging
- Remote interpreter support (SSH, Docker, WSL)
- Advanced profiling
- Database tools integration
- Scientific mode / Jupyter integration
- `PyAttachToProcessAction` (some features)

### Summary for Implementation

**All 12 tool categories in this research can be implemented using `PythonCore` (Community) APIs alone.** Professional features add debugging capabilities but are not required for PSI-based code intelligence.

---

## 16. Implementation Mapping: Existing Tools -> Python Equivalents

### Direct Mapping Table

| Existing Tool | Java/Kotlin API | Python Equivalent | Difficulty |
|---|---|---|---|
| `FindDefinitionTool` | `PsiClass`, `PsiMethod`, `PsiField` | `PyClass`, `PyFunction`, `PyTargetExpression` | Low |
| `FindReferencesTool` | `ReferencesSearch` | `ReferencesSearch` (same API) | Low |
| `TypeHierarchyTool` | `ClassInheritorsSearch`, `PsiClass.getSupers()` | `PyClass.getSuperClasses()`, `ClassInheritorsSearch` | Low |
| `CallHierarchyTool` | Java call hierarchy | `PyCallHierarchyProvider`, `PyStaticCallHierarchyUtil` | Medium |
| `TypeInferenceTool` | `PsiType`, `PsiExpression.getType()` | `TypeEvalContext.getType()`, `PyType` | Medium |
| `DataFlowAnalysisTool` | Java dataflow (platform) | `PyDefUseUtil`, `ControlFlowCache` | Medium |
| `GetAnnotationsTool` | `PsiAnnotation`, `PsiModifierList` | `PyDecoratorList`, `PyDecorator` | Low |
| `FileStructureTool` | `PsiClass`, `PsiMethod` in file | `PyFile.getTopLevelClasses/Functions()` | Low |
| `StructuralSearchTool` | `StructuralSearchProfile` | PSI tree walking (no native support) | High |
| `TestFinderTool` | JUnit detection | `PythonUnitTestDetectorsBasedOnSettings` | Medium |
| `GetMethodBodyTool` | `PsiMethod.getBody()` | `PyFunction.getStatementList()` | Low |
| `ReadWriteAccessTool` | Java read/write analysis | `PyDefUseUtil` + control flow | Medium |
| `FindImplementationsTool` | `DefinitionsScopedSearch` | `ClassInheritorsSearch` for Python | Low |

### Key Differences from Java/Kotlin

1. **No formal annotations** — Python uses decorators instead. The `GetAnnotationsTool` becomes `GetDecoratorsTool` for Python.

2. **No static types by default** — Type inference relies on `TypeEvalContext` + type hints. Results may be `null` for untyped code.

3. **No structural search** — Must implement pattern matching manually via PSI tree walking.

4. **Multiple inheritance** — `getSuperClasses()` returns an array (not single parent). MRO matters.

5. **Dynamic nature** — `PyDynamicallyEvaluatedType` represents runtime-determined types that can't be statically resolved.

6. **Module-level scope** — Python files are modules. `PyFile` directly contains functions, classes, and variables (no package/class wrapper required).

7. **No separate field concept** — Class attributes (`cls.x`) and instance attributes (`self.x`) are both `PyTargetExpression`.

### Provider Interface Design

```kotlin
interface LanguageIntelligenceProvider {
    fun findDefinition(project: Project, symbol: String, classHint: String?): DefinitionResult?
    fun findReferences(project: Project, element: PsiElement, scope: SearchScope): List<ReferenceResult>
    fun getTypeHierarchy(project: Project, className: String): TypeHierarchyResult?
    fun getCallHierarchy(project: Project, functionName: String): CallHierarchyResult?
    fun inferType(project: Project, file: PsiFile, offset: Int): TypeResult?
    fun analyzeDataflow(project: Project, file: PsiFile, offset: Int): DataflowResult?
    fun getDecorators(project: Project, element: PsiElement): List<DecoratorResult>  // annotations equivalent
    fun getFileStructure(project: Project, file: PsiFile): FileStructureResult
    fun findTests(project: Project, file: PsiFile): List<TestResult>
    fun isAvailable(project: Project): Boolean
}

class PythonProvider : LanguageIntelligenceProvider {
    override fun isAvailable(project: Project): Boolean {
        // Check if PythonCore plugin is loaded
        return PluginManager.isPluginInstalled(PluginId.getId("PythonCore"))
    }
    // ... implementations using Python PSI APIs
}
```

---

## Sources

- [Python Language Support - DeepWiki (IntelliJ Community)](https://deepwiki.com/JetBrains/intellij-community/9-python-language-support)
- [PyCharm Plugin Development - IntelliJ Platform Plugin SDK](https://plugins.jetbrains.com/docs/intellij/pycharm.html)
- [Plugin Development: Leveraging IDE Python Type Inference - Medium](https://medium.com/alan/plugin-development-leveraging-ide-python-type-inference-with-type-hints-7426b3d5ee49)
- [PSI References - IntelliJ Platform Plugin SDK](https://plugins.jetbrains.com/docs/intellij/psi-references.html)
- [PSI Cookbook - IntelliJ Platform Plugin SDK](https://plugins.jetbrains.com/docs/intellij/psi-cookbook.html)
- [PyClass source - GitHub JetBrains/intellij-community](https://github.com/JetBrains/intellij-community/blob/master/python/python-psi-api/src/com/jetbrains/python/psi/PyClass.java)
- [PyFile source - GitHub JetBrains/intellij-community](https://github.com/JetBrains/intellij-community/blob/master/python/python-psi-api/src/com/jetbrains/python/psi/PyFile.java)
- [PyFunction source - GitHub JetBrains/intellij-community](https://github.com/JetBrains/intellij-community/blob/master/python/python-psi-api/src/com/jetbrains/python/psi/PyFunction.java)
- [PyTypeProvider source - GitHub JetBrains/intellij-community](https://github.com/JetBrains/intellij-community/blob/master/python/python-psi-api/src/com/jetbrains/python/psi/impl/PyTypeProvider.java)
- [PyDefUseUtil source - GitHub JetBrains/intellij-community](https://github.com/JetBrains/intellij-community/blob/master/python/python-psi-impl/src/com/jetbrains/python/refactoring/PyDefUseUtil.java)
- [PyDebugProcess source - GitHub JetBrains/intellij-community](https://github.com/JetBrains/intellij-community/blob/master/python/src/com/jetbrains/python/debugger/PyDebugProcess.java)
- [PyPackageUtil source - GitHub JetBrains/intellij-community](https://github.com/JetBrains/intellij-community/blob/master/python/src/com/jetbrains/python/packaging/PyPackageUtil.java)
- [Python hierarchy directory - GitHub JetBrains/intellij-community](https://github.com/JetBrains/intellij-community/tree/master/python/src/com/jetbrains/python/hierarchy)
- [Call hierarchy for Python - JetBrains Support](https://intellij-support.jetbrains.com/hc/en-us/community/posts/4411174832018-Call-hierarchy-for-Python)
- [Structural search not available for Python - JetBrains Support](https://intellij-support.jetbrains.com/hc/en-us/community/posts/115000013530-Can-t-find-option-to-structurally-search-within-Python-files)
- [PyPackageManager deprecation - JetBrains Support](https://intellij-support.jetbrains.com/hc/en-us/community/posts/24121542538898-PyPackageManager-deprecation)
- [PyTestsShared.kt source - GitHub JetBrains/intellij-community](https://github.com/JetBrains/intellij-community/blob/master/python/src/com/jetbrains/python/testing/PyTestsShared.kt)
- [PythonSdkType source - GitHub JetBrains/intellij-community](https://github.com/JetBrains/intellij-community/blob/master/python/src/com/jetbrains/python/sdk/PythonSdkType.java)
- [Plugin Compatibility - IntelliJ Platform Plugin SDK](https://plugins.jetbrains.com/docs/intellij/plugin-compatibility.html)

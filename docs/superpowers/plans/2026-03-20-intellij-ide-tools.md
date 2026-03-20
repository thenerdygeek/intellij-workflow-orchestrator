# IntelliJ IDE-Level Tools — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add 14 new tools that leverage IntelliJ's IDE-level intelligence — semantic diagnostics, code formatting, import optimization, inspections, refactoring, compilation, test execution, VCS, and Spring/JPA integration. These tools give the agent capabilities no CLI-based tool can match.

**Architecture:** All tools follow the existing pattern: implement `AgentTool`, use `ReadAction`/`WriteCommandAction` for PSI, return `ToolResult`. Organized into 3 new packages: `tools/ide/` (IDE operations), `tools/vcs/` (VCS operations), `tools/framework/` (Spring/JPA). Registered in `AgentService.toolRegistry`.

**Tech Stack:** Kotlin, IntelliJ Platform APIs (PSI, CodeStyle, Inspections, Refactoring, CompilerManager, RunManager, Git4Idea, Spring Plugin)

**Research:** Full API signatures at `docs/superpowers/research/2026-03-20-intellij-api-signatures.md`

---

## File Structure

### New Files (14 tools + 3 tests)

```
agent/src/main/kotlin/.../agent/tools/
├── ide/                              ← NEW PACKAGE
│   ├── SemanticDiagnosticsTool.kt    ← WolfTheProblemSolver + enhanced PSI
│   ├── FormatCodeTool.kt             ← CodeStyleManager
│   ├── OptimizeImportsTool.kt        ← ImportOptimizer / OptimizeImportsProcessor
│   ├── RunInspectionsTool.kt         ← InspectionManager + LocalInspectionTool
│   ├── RefactorRenameTool.kt         ← RenameProcessor (headless)
│   ├── CompileModuleTool.kt          ← CompilerManager
│   ├── RunTestsTool.kt              ← RunManager + ExecutionManager
│   ├── ListQuickFixesTool.kt        ← IntentionManager
├── vcs/                              ← NEW PACKAGE
│   ├── GitStatusTool.kt             ← GitRepositoryManager + ChangeListManager
│   ├── GitBlameTool.kt              ← VcsAnnotationProvider
│   ├── FindImplementationsTool.kt   ← OverridingMethodsSearch
├── framework/                        ← NEW PACKAGE
│   ├── SpringConfigTool.kt          ← SpringConfigurationModel
│   ├── JpaEntitiesTool.kt           ← AnnotatedElementsSearch for @Entity
│   ├── ProjectModulesTool.kt        ← MavenProjectsManager
```

### Modified Files (1)
```
agent/src/main/kotlin/.../agent/AgentService.kt  ← Register all 14 tools
```

---

## Task 1: 3 Trivial Wins — SemanticDiagnostics, FormatCode, OptimizeImports

The highest-ROI tools that fix the agent's most common failure mode (edits that don't compile because of missing imports/formatting).

**Files:**
- Create: `agent/src/main/kotlin/.../agent/tools/ide/SemanticDiagnosticsTool.kt`
- Create: `agent/src/main/kotlin/.../agent/tools/ide/FormatCodeTool.kt`
- Create: `agent/src/main/kotlin/.../agent/tools/ide/OptimizeImportsTool.kt`
- Modify: `agent/src/main/kotlin/.../agent/AgentService.kt`

### SemanticDiagnosticsTool

Goes beyond the existing `DiagnosticsTool` (which only finds `PsiErrorElement` syntax errors). This tool combines:
1. `WolfTheProblemSolver.isProblemFile()` — IDE's problem tracker
2. Enhanced PSI analysis — unresolved references via `PsiReference.resolve() == null`

```kotlin
package com.workflow.orchestrator.agent.tools.ide

class SemanticDiagnosticsTool : AgentTool {
    override val name = "semantic_diagnostics"
    override val description = "Get semantic errors for a file: unresolved references, type mismatches, missing imports. More thorough than basic syntax checking — catches compilation errors."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "path" to ParameterProperty(type = "string", description = "File path (e.g., 'src/main/kotlin/UserService.kt')")
        ),
        required = listOf("path")
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val rawPath = params["path"]?.jsonPrimitive?.content ?: return paramError("path")
        val (path, pathError) = PathValidator.resolveAndValidate(rawPath, project.basePath)
        if (pathError != null) return pathError

        if (DumbService.isDumb(project)) return dumbError()

        return ReadAction.compute<ToolResult, Exception> {
            val vf = LocalFileSystem.getInstance().findFileByPath(path!!) ?: return@compute fileNotFound(path)
            val psiFile = PsiManager.getInstance(project).findFile(vf) ?: return@compute fileNotFound(path)

            val problems = mutableListOf<String>()

            // 1. Check WolfTheProblemSolver
            val wolf = WolfTheProblemSolver.getInstance(project)
            if (wolf.isProblemFile(vf)) {
                problems.add("File is flagged as problematic by the IDE.")
            }

            // 2. Collect PsiErrorElement (syntax errors)
            val syntaxErrors = PsiTreeUtil.collectElementsOfType(psiFile, PsiErrorElement::class.java)
            syntaxErrors.forEach { error ->
                val doc = psiFile.viewProvider.document
                val line = doc?.getLineNumber(error.textOffset)?.plus(1) ?: 0
                problems.add("Syntax error at line $line: ${error.errorDescription}")
            }

            // 3. Check for unresolved references (semantic errors)
            psiFile.accept(object : com.intellij.psi.PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: com.intellij.psi.PsiElement) {
                    super.visitElement(element)
                    for (ref in element.references) {
                        if (ref.resolve() == null && ref.canonicalText.isNotBlank()) {
                            val doc = psiFile.viewProvider.document
                            val line = doc?.getLineNumber(element.textOffset)?.plus(1) ?: 0
                            val text = ref.canonicalText.take(50)
                            problems.add("Unresolved reference at line $line: '$text'")
                        }
                    }
                }
            })

            if (problems.isEmpty()) {
                ToolResult("No errors found in ${vf.name}.", "No errors", 5)
            } else {
                val content = "${problems.size} issue(s) in ${vf.name}:\n${problems.joinToString("\n") { "  - $it" }}"
                ToolResult(content, "${problems.size} issues in ${vf.name}", TokenEstimator.estimate(content), isError = true)
            }
        }
    }
}
```

### FormatCodeTool

```kotlin
class FormatCodeTool : AgentTool {
    override val name = "format_code"
    override val description = "Reformat a file according to the project's code style settings (.editorconfig, IDE settings). Use after editing files to ensure consistent formatting."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "path" to ParameterProperty(type = "string", description = "File path to format")
        ),
        required = listOf("path")
    )
    override val allowedWorkers = setOf(WorkerType.CODER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val rawPath = params["path"]?.jsonPrimitive?.content ?: return paramError("path")
        val (path, pathError) = PathValidator.resolveAndValidate(rawPath, project.basePath)
        if (pathError != null) return pathError

        return try {
            ReadAction.compute<ToolResult, Exception> {
                val vf = LocalFileSystem.getInstance().findFileByPath(path!!) ?: return@compute fileNotFound(path)
                val psiFile = PsiManager.getInstance(project).findFile(vf) ?: return@compute fileNotFound(path)

                // WriteCommandAction for formatting
                WriteCommandAction.runWriteCommandAction(project, "Format Code", null, {
                    CodeStyleManager.getInstance(project).reformat(psiFile)
                }, psiFile)

                ToolResult("Formatted ${vf.name} according to project code style.", "Formatted ${vf.name}", 5)
            }
        } catch (e: Exception) {
            ToolResult("Error formatting: ${e.message}", "Format error", 5, isError = true)
        }
    }
}
```

**Note:** `WriteCommandAction` inside `ReadAction` is incorrect — WriteAction must NOT be inside ReadAction. Fix: use `ApplicationManager.getApplication().invokeAndWait` + `WriteCommandAction` outside the ReadAction.

Correct pattern:
```kotlin
override suspend fun execute(params: JsonObject, project: Project): ToolResult {
    // ... path validation ...

    val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path!!) ?: return fileNotFound(path)

    return try {
        // Must run on EDT for WriteCommandAction
        var result: ToolResult? = null
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(project, "Agent: Format Code", null, {
                val psiFile = PsiManager.getInstance(project).findFile(vf)
                if (psiFile != null) {
                    CodeStyleManager.getInstance(project).reformat(psiFile)
                    result = ToolResult("Formatted ${vf.name}", "Formatted ${vf.name}", 5, artifacts = listOf(path))
                } else {
                    result = ToolResult("Cannot parse file: $path", "Parse error", 5, isError = true)
                }
            })
        }
        result!!
    } catch (e: Exception) {
        ToolResult("Error formatting: ${e.message}", "Format error", 5, isError = true)
    }
}
```

### OptimizeImportsTool

```kotlin
class OptimizeImportsTool : AgentTool {
    override val name = "optimize_imports"
    override val description = "Add missing imports and remove unused imports in a file. Use after editing files to fix unresolved references caused by missing imports."
    // ... parameters: path (required)
    override val allowedWorkers = setOf(WorkerType.CODER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        // ... path validation ...

        return try {
            var result: ToolResult? = null
            ApplicationManager.getApplication().invokeAndWait {
                WriteCommandAction.runWriteCommandAction(project, "Agent: Optimize Imports", null, {
                    val psiFile = PsiManager.getInstance(project).findFile(vf)
                    if (psiFile != null) {
                        val optimizers = LanguageImportStatements.INSTANCE.forFile(psiFile)
                        for (optimizer in optimizers) {
                            if (optimizer.supports(psiFile)) {
                                optimizer.processFile(psiFile).run()
                            }
                        }
                        result = ToolResult("Optimized imports in ${vf.name}", "Imports optimized", 5, artifacts = listOf(path))
                    }
                })
            }
            result ?: ToolResult("Could not optimize imports", "Error", 5, isError = true)
        } catch (e: Exception) {
            ToolResult("Error: ${e.message}", "Import error", 5, isError = true)
        }
    }
}
```

- [ ] **Step 1:** Create `agent/src/main/kotlin/.../agent/tools/ide/` package
- [ ] **Step 2:** Create `SemanticDiagnosticsTool.kt`
- [ ] **Step 3:** Create `FormatCodeTool.kt` with correct WriteCommandAction threading
- [ ] **Step 4:** Create `OptimizeImportsTool.kt`
- [ ] **Step 5:** Register all 3 in `AgentService.kt` toolRegistry
- [ ] **Step 6:** Verify: `./gradlew :agent:compileKotlin && ./gradlew :agent:test --rerun --no-build-cache`
- [ ] **Step 7:** Commit: `feat(agent): 3 IDE tools — semantic diagnostics, format code, optimize imports`

---

## Task 2: RunInspections + RefactorRename + ListQuickFixes

The intelligence tools — inspections, refactoring, and quick fixes.

**Files:**
- Create: `agent/src/main/kotlin/.../agent/tools/ide/RunInspectionsTool.kt`
- Create: `agent/src/main/kotlin/.../agent/tools/ide/RefactorRenameTool.kt`
- Create: `agent/src/main/kotlin/.../agent/tools/ide/ListQuickFixesTool.kt`
- Modify: `AgentService.kt`

### RunInspectionsTool

Runs IntelliJ inspections on a file and returns problems found. Uses `LocalInspectionTool.buildVisitor()` + `ProblemsHolder` pattern.

```kotlin
class RunInspectionsTool : AgentTool {
    override val name = "run_inspections"
    override val description = "Run IntelliJ code inspections on a file: unused code, null safety, performance issues, Spring misconfig. Returns problems with severity and suggested fixes."
    // params: path (required), severity_filter (optional: "ERROR", "WARNING", "INFO")
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER)
}
```

### RefactorRenameTool

Uses `RenameProcessor` for safe, project-wide rename.

```kotlin
class RefactorRenameTool : AgentTool {
    override val name = "refactor_rename"
    override val description = "Safely rename a symbol (class, method, field, variable) across the entire project. Updates all references, imports, and usages. Much safer than text replacement via edit_file."
    // params: symbol (required), new_name (required), file (optional for disambiguation)
    override val allowedWorkers = setOf(WorkerType.CODER)
}
```

Uses headless `RenameProcessor(project, element, newName, searchInComments=false, searchTextOccurrences=false).run()` inside `WriteCommandAction`.

### ListQuickFixesTool

Lists available Alt+Enter quick fixes at a location.

```kotlin
class ListQuickFixesTool : AgentTool {
    override val name = "list_quickfixes"
    override val description = "List available quick fixes (Alt+Enter actions) for errors at a specific line in a file. Shows what IntelliJ can auto-fix."
    // params: path (required), line (required)
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.ANALYZER)
}
```

- [ ] **Step 1:** Create `RunInspectionsTool.kt`
- [ ] **Step 2:** Create `RefactorRenameTool.kt` with `RenameProcessor` headless mode
- [ ] **Step 3:** Create `ListQuickFixesTool.kt`
- [ ] **Step 4:** Register in AgentService
- [ ] **Step 5:** Verify compilation and tests
- [ ] **Step 6:** Commit: `feat(agent): 3 IDE intelligence tools — inspections, rename refactoring, quick fixes`

---

## Task 3: CompileModule + RunTests

Build and test execution using IDE-native APIs (faster than shell `mvn`).

**Files:**
- Create: `agent/src/main/kotlin/.../agent/tools/ide/CompileModuleTool.kt`
- Create: `agent/src/main/kotlin/.../agent/tools/ide/RunTestsTool.kt`
- Modify: `AgentService.kt`

### CompileModuleTool

Uses `CompilerManager.make()` for incremental compilation (2-5s vs mvn's 30-60s).

```kotlin
class CompileModuleTool : AgentTool {
    override val name = "compile_module"
    override val description = "Compile a module using IntelliJ's incremental compiler. Much faster than 'mvn compile' (2-5 seconds vs 30-60). Returns structured error output."
    // params: module (optional — compile whole project if not specified)
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER)
}
```

Uses `suspendCancellableCoroutine` to bridge the async callback.

### RunTestsTool

Creates and executes a JUnit run configuration, returns structured results.

```kotlin
class RunTestsTool : AgentTool {
    override val name = "run_tests"
    override val description = "Run a specific test class or test method using IntelliJ's test runner. Returns pass/fail with failure messages. Faster than 'mvn test'."
    // params: class_name (required), method_name (optional), module (optional)
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER)
}
```

- [ ] **Step 1:** Create `CompileModuleTool.kt` with async callback bridge
- [ ] **Step 2:** Create `RunTestsTool.kt` with JUnit config creation
- [ ] **Step 3:** Register in AgentService
- [ ] **Step 4:** Verify
- [ ] **Step 5:** Commit: `feat(agent): 2 IDE build tools — compile module, run tests`

---

## Task 4: VCS Tools — GitStatus, GitBlame, FindImplementations

**Files:**
- Create: `agent/src/main/kotlin/.../agent/tools/vcs/GitStatusTool.kt`
- Create: `agent/src/main/kotlin/.../agent/tools/vcs/GitBlameTool.kt`
- Create: `agent/src/main/kotlin/.../agent/tools/vcs/FindImplementationsTool.kt`
- Modify: `AgentService.kt`

### GitStatusTool

```kotlin
class GitStatusTool : AgentTool {
    override val name = "git_status"
    override val description = "Get git status: current branch, changed files (staged and unstaged), untracked files. Structured output, faster than 'git status'."
    // params: none required
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER)
}
```

Uses `GitRepositoryManager.getInstance(project).repositories` + `ChangeListManager.getInstance(project).allChanges`.

### GitBlameTool

```kotlin
class GitBlameTool : AgentTool {
    override val name = "git_blame"
    override val description = "Get git blame for a file: who changed each line, when, and in which commit. Useful for understanding code ownership and recent changes."
    // params: path (required), start_line (optional), end_line (optional)
    override val allowedWorkers = setOf(WorkerType.ANALYZER)
}
```

### FindImplementationsTool

```kotlin
class FindImplementationsTool : AgentTool {
    override val name = "find_implementations"
    override val description = "Find concrete implementations of an interface method or abstract method. Shows which classes implement a specific method."
    // params: method (required), class_name (optional)
    override val allowedWorkers = setOf(WorkerType.ANALYZER)
}
```

Uses `OverridingMethodsSearch.search(psiMethod)`.

- [ ] **Step 1:** Create `agent/src/main/kotlin/.../agent/tools/vcs/` package
- [ ] **Step 2:** Create `GitStatusTool.kt`
- [ ] **Step 3:** Create `GitBlameTool.kt`
- [ ] **Step 4:** Create `FindImplementationsTool.kt`
- [ ] **Step 5:** Register in AgentService
- [ ] **Step 6:** Verify
- [ ] **Step 7:** Commit: `feat(agent): 3 VCS/navigation tools — git status, git blame, find implementations`

---

## Task 5: Framework Tools — SpringConfig, JpaEntities, ProjectModules

**Files:**
- Create: `agent/src/main/kotlin/.../agent/tools/framework/SpringConfigTool.kt`
- Create: `agent/src/main/kotlin/.../agent/tools/framework/JpaEntitiesTool.kt`
- Create: `agent/src/main/kotlin/.../agent/tools/framework/ProjectModulesTool.kt`
- Modify: `AgentService.kt`

### SpringConfigTool

```kotlin
class SpringConfigTool : AgentTool {
    override val name = "spring_config"
    override val description = "Read Spring configuration properties (application.properties/yml). Shows resolved values for a specific property or lists all configured properties."
    // params: property (optional — if provided, look up specific; otherwise list all)
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.ORCHESTRATOR)
}
```

### JpaEntitiesTool

```kotlin
class JpaEntitiesTool : AgentTool {
    override val name = "jpa_entities"
    override val description = "List JPA entities in the project: entity class, table name, fields with column mappings, relationships (@OneToMany, @ManyToOne, etc.)."
    // params: entity (optional — specific entity, or list all)
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.ORCHESTRATOR)
}
```

Uses `AnnotatedElementsSearch.searchPsiClasses(entityAnnotation, scope)`.

### ProjectModulesTool

```kotlin
class ProjectModulesTool : AgentTool {
    override val name = "project_modules"
    override val description = "List project modules: module name, path, dependencies, source directories. For Maven projects, shows groupId:artifactId:version."
    // params: none
    override val allowedWorkers = setOf(WorkerType.ANALYZER, WorkerType.ORCHESTRATOR)
}
```

Uses `MavenProjectsManager.getInstance(project).getProjects()` or `ModuleManager.getInstance(project).modules`.

- [ ] **Step 1:** Create `agent/src/main/kotlin/.../agent/tools/framework/` package
- [ ] **Step 2:** Create `SpringConfigTool.kt` (reflection for optional Spring plugin)
- [ ] **Step 3:** Create `JpaEntitiesTool.kt` (PSI-based @Entity search)
- [ ] **Step 4:** Create `ProjectModulesTool.kt` (MavenProjectsManager or ModuleManager)
- [ ] **Step 5:** Register in AgentService
- [ ] **Step 6:** Verify
- [ ] **Step 7:** Commit: `feat(agent): 3 framework tools — Spring config, JPA entities, project modules`

---

## Task 6: Update DynamicToolSelector + Final Registration

Add keyword triggers for all new tools so DynamicToolSelector includes them when relevant.

**Files:**
- Modify: `agent/src/main/kotlin/.../agent/tools/DynamicToolSelector.kt`
- Modify: `agent/src/main/kotlin/.../agent/AgentService.kt` (final verification)

### New keyword triggers:

```kotlin
// IDE tools — triggered by code quality keywords
"compile" to setOf("compile_module", "semantic_diagnostics"),
"format" to setOf("format_code"),
"import" to setOf("optimize_imports"),
"inspection" to setOf("run_inspections"),
"inspect" to setOf("run_inspections"),
"rename" to setOf("refactor_rename"),
"refactor" to setOf("refactor_rename"),
"quick fix" to setOf("list_quickfixes"),
"test" to setOf("run_tests"),

// VCS tools — triggered by git keywords
"git" to setOf("git_status", "git_blame"),
"blame" to setOf("git_blame"),
"branch" to setOf("git_status"),
"commit" to setOf("git_status"),
"diff" to setOf("git_status"),
"implement" to setOf("find_implementations"),

// Framework tools
"config" to setOf("spring_config"),
"properties" to setOf("spring_config"),
"entity" to setOf("jpa_entities"),
"table" to setOf("jpa_entities"),
"module" to setOf("project_modules"),
"dependency" to setOf("project_modules"),
```

Also add the 3 trivial wins to `ALWAYS_INCLUDE`:
```kotlin
private val ALWAYS_INCLUDE = setOf(
    // ... existing ...
    "format_code", "optimize_imports", "semantic_diagnostics"  // Run after every edit
)
```

- [ ] **Step 1:** Add all new tool keyword triggers to DynamicToolSelector
- [ ] **Step 2:** Add format_code, optimize_imports, semantic_diagnostics to ALWAYS_INCLUDE
- [ ] **Step 3:** Verify all 14 new tools are registered and compile
- [ ] **Step 4:** Run: `./gradlew :agent:test --rerun --no-build-cache`
- [ ] **Step 5:** Commit: `feat(agent): register 14 IDE tools in DynamicToolSelector with keyword triggers`

---

## Verification

```bash
./gradlew :agent:compileKotlin          # All compiles
./gradlew :agent:test --rerun --no-build-cache   # All tests pass
./gradlew compileKotlin                  # All modules compile
```

Final tool count: 30 existing + 14 new = **44 tools**

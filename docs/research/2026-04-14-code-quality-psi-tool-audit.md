# Code Quality & PSI Tool Audit

**Date:** 2026-04-14
**Scope:** 6 code quality tools + 14 PSI code intelligence tools + 1 diagnostics tool (21 total)
**Branch:** main (tooling-architecture worktree)

---

## Executive Summary

**55 issues found** across 20 tools. Breakdown by severity:

| Severity | Count | Description |
|----------|-------|-------------|
| CRITICAL | 6 | Infinite loop, data loss, silent failures that mislead the LLM |
| HIGH | 18 | Missing safety checks, incorrect results, poor error guidance |
| MEDIUM | 20 | Missing "no-op" feedback, truncation gaps, Python limitations |
| LOW | 11 | Cosmetic, missing convenience features, minor UX |

**Top 5 systemic issues:**
1. **No cycle detection in call hierarchy** -- recursive calls cause infinite recursion (both Java and Python providers)
2. **FormatCodeTool + OptimizeImportsTool lack dumb mode checks** -- will crash during indexing
3. **Symbol-based tools hardcode Java/Kotlin provider fallback** -- Python symbols unreachable in 6 tools
4. **StructuralSearchTool ignores its own `file_type` parameter** -- declared but never read
5. **FormatCodeTool gives no diff** -- LLM cannot tell what changed

---

## Part 1: Code Quality Tools (6 tools)

### 1. FormatCodeTool (`format_code`)

| # | Scenario | Current Handling | Gap/Issue | Severity |
|---|----------|-----------------|-----------|----------|
| 1 | File has syntax errors | `CodeStyleManager.reformat()` still runs -- platform API handles partial PSI trees | No gap -- platform handles it | -- |
| 2 | File is binary or not a source file | `PsiManager.findFile()` returns null for binary files -> "Cannot parse file" error | Error message doesn't suggest using `read_file` to check file type first | LOW |
| 3 | Unsaved changes in editor | Uses `LocalFileSystem.refreshAndFindFileByPath()` + PSI reformat on the VFS file; changes to Document (unsaved editor content) may not be reflected if the Document and VFS are out of sync | Should use `FileDocumentManager.getInstance().getDocument(vf)` to commit pending editor changes before formatting, or document this limitation | MEDIUM |
| 4 | Formatting changes nothing | Always says "Formatted {name} according to project code style" even if no changes were made | **No "already formatted" feedback** -- LLM wastes context on no-op | HIGH |
| 5 | Python files | `CodeStyleManager` is platform API -- delegates to language-specific formatter. Works for Python if Python plugin is installed | No gap (platform handles delegation) | -- |
| 6 | File is read-only | `WriteCommandAction` will throw -- caught by generic `catch (e: Exception)` | Error message is generic "Error formatting: {message}" -- doesn't tell LLM the file is read-only or suggest checking permissions | MEDIUM |
| 7 | VCS pending merge conflict markers | `reformat()` will format around conflict markers (they're treated as text) | LLM gets no warning that formatting a file with merge conflicts may produce nonsensical results | MEDIUM |
| 8 | EditorConfig interaction | `CodeStyleManager` already respects `.editorconfig` if the EditorConfig plugin is enabled | No gap -- description already mentions ".editorconfig, IDE settings" | -- |
| 9 | No dumb mode check | **Missing entirely** -- `CodeStyleManager.reformat()` doesn't need indexes for basic formatting, but PSI tree construction via `PsiManager.findFile()` requires read access that may conflict during indexing | Could crash during dumb mode if PSI file is not valid | HIGH |
| 10 | No diff output | Returns "Formatted {name}" with no indication of what changed | **LLM cannot verify formatting did what it expected** -- should show line count of changes or a summary | HIGH |

**Python readiness:** Works for Python files (CodeStyleManager delegates to PythonFormattingService). No Python-specific issues.

---

### 2. OptimizeImportsTool (`optimize_imports`)

| # | Scenario | Current Handling | Gap/Issue | Severity |
|---|----------|-----------------|-----------|----------|
| 1 | No unused imports | `optimizer.processFile(psiFile).run()` still runs, returns "Optimized imports" regardless | **No "imports already optimal" feedback** -- same issue as FormatCodeTool | HIGH |
| 2 | Side-effect imports (Python `import os`, Java static initializers) | `LanguageImportStatements` delegates to language-specific optimizer which handles this | Python plugin's optimizer should handle `__all__`, but platform doesn't guarantee side-effect awareness | MEDIUM |
| 3 | Wildcard imports (Java `import java.util.*`) | Platform optimizer handles wildcard-to-specific conversion based on project code style settings | No gap | -- |
| 4 | Python files | `LanguageImportStatements.INSTANCE.forFile()` returns Python optimizer if Python plugin installed | If Python plugin not installed, falls back to "No import optimizer available" -- good message | -- |
| 5 | File has syntax errors | PSI tree may be partial; optimizer runs best-effort on valid portions | No warning that results may be incomplete on broken files | LOW |
| 6 | No dumb mode check | **Missing entirely** -- `LanguageImportStatements` may need index access for import resolution | Can crash or produce incorrect results (removing imports that are actually used but not yet indexed) | CRITICAL |
| 7 | No diff output | Same as FormatCodeTool -- "Optimized imports" with no indication of what was added/removed | LLM cannot verify which imports were changed | HIGH |
| 8 | Concurrent session issue | Two agent sessions could optimize the same file simultaneously | `WriteCommandAction` provides serialization, but the Runnable passed is not idempotent -- could produce unexpected results if two optimizations race | LOW |

**Python readiness:** Works if Python plugin is installed. Returns clear "No import optimizer available" if not. No Python-specific edge cases beyond side-effect imports.

---

### 3. RefactorRenameTool (`refactor_rename`)

| # | Scenario | Current Handling | Gap/Issue | Severity |
|---|----------|-----------------|-----------|----------|
| 1 | Symbol has usages in other modules/files | `RenameProcessor.findUsages()` + `performRefactoring()` handles cross-file updates | No gap -- this is the whole point of the tool | -- |
| 2 | New name conflicts with existing symbol | `RenameProcessor` may throw or produce a broken state | No pre-check for name conflicts; error caught generically. LLM gets "Error during rename: {message}" with no guidance to choose a different name | HIGH |
| 3 | Renaming would break Spring bean names, API endpoints, string references | `RenameProcessor(project, element, newName, false, false)` -- second `false` = `searchInStrings=false`, third `false` = `searchInComments=false` | **String/comment references are silently skipped** -- Spring `@Qualifier("beanName")`, Thymeleaf templates, etc. will break with no warning | HIGH |
| 4 | Symbol is in a library (unmodifiable) | `findElement()` returns elements from project scope only via `GlobalSearchScope.projectScope()` | Library symbols not found -- returns "Cannot find symbol" which is misleading (should say "symbol is in a library and cannot be renamed") | MEDIUM |
| 5 | Kotlin <-> Java cross-boundary rename | `RenameProcessor` handles this natively in IntelliJ | No gap | -- |
| 6 | Python symbols | `findElement()` uses `JavaPsiFacade` and `PsiShortNamesCache` which are Java-specific; `findSymbolInFile()` uses generic `PsiRecursiveElementWalkingVisitor` with `PsiNamedElement` check | **Python classes/functions found via file-scoped search but not via global search** -- `JavaPsiFacade.findClass()` never finds Python classes | HIGH |
| 7 | Rename involves updating string literals | `searchInStrings=false` hardcoded | See #3 -- should be a parameter or at least mentioned in the tool description | MEDIUM |
| 8 | Approval gate denial | Not handled -- the tool doesn't check approval result | Approval gate is handled at the AgentLoop level, not in the tool. Tool assumes it runs only after approval. | -- |
| 9 | Performance: widely-used symbol | `findUsages()` + `performRefactoring()` are bounded by IntelliJ's own performance | No timeout protection within the tool itself (relies on per-tool timeout from ToolRegistry) | LOW |
| 10 | Two RenameProcessor instances created | Phase 1 creates one for `findUsages()`, Phase 2 creates a new one for `performRefactoring()` | The element may have changed between the two phases (e.g., if another thread modified the file). The second `RenameProcessor` operates on the original `element` which may be stale | MEDIUM |
| 11 | Dumb mode check present | Yes, calls `DumbService.isDumb(project)` | Good | -- |
| 12 | `findElement` favors first method match | `psiClass.findMethodsByName(memberName, false).firstOrNull()` | If a class has overloaded methods, always returns the first one -- no way to disambiguate by parameter types | MEDIUM |

**Python readiness:** Partially works. File-scoped search (`findSymbolInFile`) finds Python symbols via `PsiNamedElement`. Global search via `JavaPsiFacade` will NOT find Python classes/functions. RenameProcessor works on any PsiElement once found.

---

### 4. RunInspectionsTool (`run_inspections`)

| # | Scenario | Current Handling | Gap/Issue | Severity |
|---|----------|-----------------|-----------|----------|
| 1 | No inspections enabled for profile | Loop runs zero iterations, returns "No inspection problems found" | Good -- clear message | -- |
| 2 | File has severe syntax errors (broken PSI) | Caught per-inspection by `catch (_: Exception)` -- silently skips failing inspections | No indication to LLM that some inspections were skipped due to parse errors; could give false "no problems" on a broken file | MEDIUM |
| 3 | Python inspections | `LocalInspectionToolWrapper` delegates to language-specific inspections; Python inspections from PyCharm run if Python plugin is installed | Works, but only `LocalInspectionToolWrapper` is checked -- **global inspections (GlobalInspectionTool) are excluded** | MEDIUM |
| 4 | Project vs IDE default inspections | `InspectionProjectProfileManager.getInstance(project).currentProfile` uses the project's active profile | Good -- uses project profile | -- |
| 5 | Large file with many issues | `MAX_PROBLEMS = 30` with truncation message | Good truncation, but **no guidance to LLM** on how to see more (e.g., "run again with a different severity filter") | LOW |
| 6 | Severity mapping | Maps `ProblemHighlightType` to `ERROR`, `WARNING`, `INFO` | `WEAK_WARNING` maps to `INFO` which may confuse the LLM -- weak warnings are different from informational hints | LOW |
| 7 | Quick fix availability | Shows fix family names: `[fixes: ...]` | Good -- lists fix names but doesn't indicate if they're applicable or one-click | -- |
| 8 | `problem.lineNumber` accuracy | Uses `problem.lineNumber + 1` with comment "ProblemDescriptor uses 0-based" | `ProblemDescriptor.lineNumber` is computed from the element's text offset -- it could be -1 if the element's text range is invalid. No guard for negative line numbers | LOW |
| 9 | `isEnabledByDefault` filter | Skips inspections where `isEnabledByDefault == false` | This means user-enabled but not-default inspections are **excluded**. Should check `profile.isToolEnabled(toolWrapper.id, psiFile)` instead | HIGH |
| 10 | Performance on large files | Runs ALL enabled inspections serially on the entire file | No timeout per inspection -- a single slow inspection could block the entire tool | MEDIUM |

**Python readiness:** Works for Python inspections if Python plugin is installed. Python plugin registers `LocalInspectionTool` implementations that are picked up by the profile loop. Global Python inspections are excluded (same as Java).

---

### 5. ProblemViewTool (`problem_view`)

| # | Scenario | Current Handling | Gap/Issue | Severity |
|---|----------|-----------------|-----------|----------|
| 1 | No files open in editor | Returns "No files are open in the editor." | Good clear message | -- |
| 2 | Python files | `HighlightInfo` is language-agnostic -- works for any language with a daemon highlighter | Works | -- |
| 3 | External tool problems (ESLint, Pylint) | Only reads `DocumentMarkupModel` which contains IDE highlighter results; external annotators that integrate with IntelliJ (like ESLint via plugin) do show up | External tools that don't integrate as IntelliJ annotators are NOT shown -- no mention of this limitation | LOW |
| 4 | Different problem sources | All problems shown with `ERROR` or `WARNING` label only; no source attribution | LLM cannot distinguish compiler errors from inspection warnings from external tool findings | MEDIUM |
| 5 | Stale data | `DocumentMarkupModel` reflects the current state of the highlighting daemon | Highlighting daemon may not have finished re-analyzing after a recent edit -- **stale results with no warning** | HIGH |
| 6 | File not open in editor | Uses WolfTheProblemSolver to check if file is flagged, but `collectHighlightProblems` gets highlights from the DocumentMarkupModel which requires the file to have been opened | Returns "File flagged as problematic (no detailed info available)" -- good fallback message | -- |
| 7 | No dumb mode check | **Missing** -- `DocumentMarkupModel` may have stale/partial highlights during indexing | Could show incomplete problem set during indexing with no warning | MEDIUM |
| 8 | Info-level problems excluded | Line 169: `else -> continue // skip INFO and below` | Cannot see info-level diagnostics even with severity filter | LOW |
| 9 | Per-file cap | `problems.take(30)` | No truncation message shown -- silently clips at 30 per file | MEDIUM |
| 10 | Severity filter "all" shows errors and warnings only | Despite "all" filter, INFO is still excluded by the hardcoded `continue` on line 169 | Filter description says "all" but actually means "all errors and warnings" | MEDIUM |

**Python readiness:** Works for Python files. `HighlightInfo` is populated by Python daemon highlighter.

---

### 6. ListQuickFixesTool (`list_quickfixes`)

| # | Scenario | Current Handling | Gap/Issue | Severity |
|---|----------|-----------------|-----------|----------|
| 1 | No problem at specified line | Returns "No quick fixes available at line N" | Good | -- |
| 2 | Multiple problems overlap at same line | All problems at the target line are collected, fixes deduplicated by `distinctBy { it.fixName }` | Good handling | -- |
| 3 | Python quick fixes | Same architecture as RunInspectionsTool -- delegates to language-specific inspections | Works if Python plugin installed | -- |
| 4 | Fix description clarity | Shows `fix.familyName` and `problem.descriptionTemplate` | `familyName` is often generic (e.g., "Add import") -- doesn't show the specific import that would be added | MEDIUM |
| 5 | Can the LLM apply a quick fix? | **Read-only discovery only** -- no companion "apply_quickfix" tool exists | LLM discovers fixes but has no way to apply them programmatically. Must use `edit_file` manually. This is a significant gap for automation | HIGH |
| 6 | `isEnabledByDefault` filter | Same issue as RunInspectionsTool -- skips user-enabled, non-default inspections | See RunInspectionsTool #9 | HIGH |
| 7 | Performance | Runs ALL enabled inspections on the ENTIRE file just to find fixes on ONE line | Extremely wasteful -- should scope the visitor to elements near the target line | HIGH |
| 8 | `MAX_FIXES = 20` | Shows up to 20 unique fixes per line | Reasonable cap, but no guidance on what to do if capped | LOW |

**Python readiness:** Works for Python quick fixes. Same caveats as RunInspectionsTool.

---

## Part 2: PSI Code Intelligence Tools (14 tools)

### Cross-Cutting Issues (Affect Multiple Tools)

| # | Issue | Affected Tools | Severity |
|---|-------|---------------|----------|
| A | **Hardcoded Java/Kotlin provider fallback for symbol-based lookups** -- `registry.forLanguageId("JAVA") ?: registry.forLanguageId("kotlin")` means Python symbols are unreachable when no file context is provided | type_hierarchy, call_hierarchy, get_method_body, get_annotations, find_implementations, structural_search | HIGH |
| B | **No `visited` set in `collectCallers()`** -- recursive calls (A calls B, B calls A) cause infinite recursion until stack overflow or timeout | call_hierarchy (both Java and Python providers) | CRITICAL |
| C | **`inSmartMode(project)` used correctly** on all PSI tools via `ReadAction.nonBlocking<T>{...}.inSmartMode(project).executeSynchronously()` | All 14 PSI tools | -- (good) |
| D | **All PSI tools check `PsiToolUtils.isDumb(project)`** before the ReadAction block | All 14 PSI tools | -- (good) |
| E | **Binary/non-source files** -- tools that take a `path` parameter will get null from `PsiManager.findFile()` for .class/.pyc files | diagnostics, file_structure, type_inference, dataflow_analysis, read_write_access, test_finder | MEDIUM |
| F | **File not in project** -- PathValidator restricts to project scope; decompiled library source is accessible via VFS but not via PathValidator | All file-based PSI tools | LOW |
| G | **Concurrent modification** -- ReadAction blocks are non-blocking and check `psiFile.isValid` but don't guard against mid-read PSI tree mutations (write actions preempt non-blocking reads -- correct behavior, but may cause retries) | All PSI tools | -- (handled by platform) |

---

### 7. FindDefinitionTool (`find_definition`)

| # | Scenario | Current Handling | Gap/Issue | Severity |
|---|----------|-----------------|-----------|----------|
| 1 | Symbol is overloaded (multiple definitions) | For methods: shows disambiguation note with count of other methods | Good -- tells LLM there are N other methods | -- |
| 2 | Symbol is in a library with no source | `findSymbol()` searches `projectScope` only -- library classes not found | Returns "No definition found" -- should suggest searching with library scope or using `read_file` on decompiled source | MEDIUM |
| 3 | Symbol is a local variable | Not reachable via `findSymbol()` (searches class/method level only) | Returns "No definition found" with no guidance. Should suggest using `type_inference` with file+line instead | MEDIUM |
| 4 | Python symbols | `resolveProvider()` falls back to Java/Kotlin provider when no element found yet | **Works for Python** because file-based provider resolution is tried first when element is found; but initial symbol search goes through Java provider which won't find Python-only symbols | MEDIUM |
| 5 | Disambiguation for classes | Only methods get a disambiguation count; classes with same simple name across packages don't get disambiguation hints | LLM doesn't know there are multiple classes named "Config" in different packages | LOW |
| 6 | Output format for "else" branch | Falls through to generic format showing `info.signature` | For Kotlin functions found via PsiNamedElement, signature may be the full text rather than a clean signature | LOW |

**Python readiness:** Partially works. If the Python symbol is found by the Java/Kotlin fallback provider (via PsiShortNamesCache), the element's file language is checked and the Python provider is used for `getDefinitionInfo()`. But `PsiShortNamesCache` for Python depends on the Python plugin registering its classes there.

---

### 8. FindReferencesTool (`find_references`)

| # | Scenario | Current Handling | Gap/Issue | Severity |
|---|----------|-----------------|-----------|----------|
| 1 | 5000+ usages | `references.take(50)` with truncation message | Good truncation. Header shows total count. | -- |
| 2 | References in comments/strings | `ReferencesSearch.search()` by default only finds code references, not string/comment references | No mention of this limitation -- LLM may think there are no references in config files when they're in strings | LOW |
| 3 | Test vs production references | Not distinguished -- all references shown in flat list | LLM cannot filter to "only test usages" or "only production usages" | LOW |
| 4 | File-scoped fallback for methods | `(psiFile as? PsiJavaFile)?.classes` -- hardcoded cast to PsiJavaFile | **File-scoped method search fails silently for Kotlin and Python files** -- only works for Java files | HIGH |
| 5 | Context lines | `context_lines` parameter supported (0-3) | Good feature for seeing surrounding code | -- |
| 6 | Python symbols | `resolveSearchTarget` falls back to `PsiShortNamesCache` for methods/fields but searches Java facades first | Works for Python if the symbol is registered in PsiShortNamesCache. File-scoped search (when `file` param provided) only works for Java files due to `PsiJavaFile` cast | HIGH |

**Python readiness:** Global search works through `PsiShortNamesCache` fallback. File-scoped search (line 142: `PsiJavaFile` cast) does NOT work for Python files -- returns null silently and falls through to global search.

---

### 9. SemanticDiagnosticsTool (`diagnostics`)

| # | Scenario | Current Handling | Gap/Issue | Severity |
|---|----------|-----------------|-----------|----------|
| 1 | File with 200+ errors | `relevantProblems.take(20)` with truncation | Cap is only 20 (lower than RunInspectionsTool's 30) -- should be consistent | LOW |
| 2 | Pre-existing errors vs new errors | Edit-range filtering via `EditFileTool.lastEditLineRanges` | **Good** -- innovative feature that scopes to recent edits | -- |
| 3 | Warnings included? | Depends on provider's `getDiagnostics()` -- no severity filter parameter | No way for LLM to filter by severity (unlike RunInspectionsTool and ProblemViewTool which have severity params) | MEDIUM |
| 4 | `lastEditLineRanges` is a static ConcurrentHashMap | Shared across ALL agent sessions in the same JVM | **Cross-session contamination** -- Session A's edit range leaks into Session B's diagnostics if they edit the same file | CRITICAL |
| 5 | Edit range consumed on read | `lastEditLineRanges.remove(canonicalPath)` -- the range is consumed | If diagnostics is called twice on the same file, second call has no edit range and shows ALL problems | MEDIUM |
| 6 | ToolResult `isError = true` for any problems found | Line 105: `ToolResult(..., isError = true)` when problems exist | **Finding problems is not an error condition** -- this causes the agent to treat every file with issues as a tool failure. This is semantically wrong. Should be `isError = false` with problems reported in content | CRITICAL |
| 7 | Python files | Provider-based -- PythonProvider's `getDiagnostics()` should handle | Works | -- |
| 8 | No provider available | Returns "Code intelligence not available for {language}" with `isError = false` | Message is informational but doesn't suggest alternatives (e.g., "use `run_command` with a compiler instead") | LOW |

**Python readiness:** Works through PythonProvider. Edit-range filtering works regardless of language.

---

### 10. FileStructureTool (`file_structure`)

| # | Scenario | Current Handling | Gap/Issue | Severity |
|---|----------|-----------------|-----------|----------|
| 1 | Very large file (5000+ lines) | No explicit size limit -- provider returns full structure | For "full" detail level, output could be enormous. No warning or auto-downgrade to "signatures" for large files | MEDIUM |
| 2 | Nested classes/functions | Java: PsiToolUtils handles nested classes recursively. Kotlin: formatKotlinClass recurses into nested classes | Good recursive handling | -- |
| 3 | Kotlin extension functions | formatKotlinFileStructure handles top-level functions | Extension functions shown as regular `fun` -- no indication of the receiver type | LOW |
| 4 | No provider (unsupported language) | Falls back to first 100 lines of raw text | Good fallback, but **tells LLM nothing about file structure** -- just raw text. Should at least say "No structural analysis available for {language}, showing raw text preview" | MEDIUM |
| 5 | Python files | PythonProvider's `getFileStructure()` should handle | Works -- returns classes, functions, attributes | -- |
| 6 | Binary files | `PsiManager.findFile()` returns null -> "Error: Cannot read file" | Error message doesn't explain why or suggest alternatives | LOW |

**Python readiness:** Works through PythonProvider. Extension functions are not a concept in Python, so no gap.

---

### 11. TypeHierarchyTool (`type_hierarchy`)

| # | Scenario | Current Handling | Gap/Issue | Severity |
|---|----------|-----------------|-----------|----------|
| 1 | Interface with 100+ implementors | `subtypes.take(30)` with "... (N more)" message | Good truncation with count | -- |
| 2 | Diamond inheritance | `collectSupertypes` uses a `visited` set to prevent cycles | Good -- diamond inheritance handled | -- |
| 3 | Generic types | Shows `presentableText` which includes type parameters | Good | -- |
| 4 | **Python classes** | Provider resolved via `registry.forLanguageId("JAVA") ?: registry.forLanguageId("kotlin")` | **Python classes unreachable** -- always falls back to Java/Kotlin provider, which will not find Python classes via `findSymbol()` | HIGH |
| 5 | Subtypes search performance | `ClassInheritorsSearch.search(psiClass, scope, true)` may be slow for very broad interfaces (e.g., `Serializable`) | No timeout protection; relies on per-tool timeout | LOW |
| 6 | Non-class elements | `getTypeHierarchy()` returns null for non-class elements | Returns "No class 'X' found in project" -- could be confusing if the user passes a method name | LOW |

**Python readiness:** Does NOT work for Python. Hardcoded Java/Kotlin provider fallback means Python classes are never found.

---

### 12. CallHierarchyTool (`call_hierarchy`)

| # | Scenario | Current Handling | Gap/Issue | Severity |
|---|----------|-----------------|-----------|----------|
| 1 | Recursive calls (A->B->A) | **No cycle detection** -- `collectCallers` has no `visited` set | **Infinite recursion** until stack overflow or per-tool timeout kills it | CRITICAL |
| 2 | Lambda/closure calls | Java: `PsiMethodCallExpression` only tracks direct method calls; lambdas that call the method are found as callers (via ReferencesSearch), but lambda bodies as callees are not tracked. Kotlin: reflection-based KtCallExpression lookup catches some but not all | **Lambda callees are partially missing** -- `PsiMethodCallExpression` doesn't cover lambda invocations, SAM conversions, or method references (::method) | HIGH |
| 3 | Depth limit | Max 3, configurable via parameter | Good | -- |
| 4 | Python methods | Provider resolved via `registry.forLanguageId("JAVA") ?: registry.forLanguageId("kotlin")` | **Python methods unreachable** -- same issue as TypeHierarchyTool | HIGH |
| 5 | Callers truncation | Depth 1: 30 per method. Depth 2+: 10 per method | No total count of all callers -- truncation is per-level, not global. Could still produce very large output with depth=3 | LOW |
| 6 | `(top-level)` caller label | When call is not inside a method (e.g., static initializer, field initializer) | Good labeling | -- |

**Python readiness:** Does NOT work for Python. Same hardcoded provider fallback issue. PythonProvider's `collectPyCallers` has the same cycle detection bug.

---

### 13. TypeInferenceTool (`type_inference`)

| # | Scenario | Current Handling | Gap/Issue | Severity |
|---|----------|-----------------|-----------|----------|
| 1 | Position has no typed element | Returns "No typed element found at this position" | Good | -- |
| 2 | Multiple position formats | Supports `offset`, `line`, and `line+column` | Good flexibility | -- |
| 3 | Kotlin types | Resolves via reflection-based class name matching (KtProperty, KtParameter, KtNamedFunction) + provider `inferType()` | Good | -- |
| 4 | Python files | Provider resolved via file-based `registry.forFile(psiFile)` | **Works for Python** -- file-based resolution correctly selects PythonProvider | -- |
| 5 | Generic types | Shows `presentableText` which includes `<T>` | Good | -- |
| 6 | Expression type (not variable) | Falls through to "expression" classification | Type inference on arbitrary expressions depends on provider implementation | -- |
| 7 | `isError` detection via string prefix | `content.startsWith("Error:") || content.startsWith("Code intelligence not available")` | **Fragile** -- if provider returns a result that happens to start with "Error:", it's misclassified as an error | LOW |

**Python readiness:** Works through PythonProvider (file-based resolution).

---

### 14. DataFlowAnalysisTool (`dataflow_analysis`)

| # | Scenario | Current Handling | Gap/Issue | Severity |
|---|----------|-----------------|-----------|----------|
| 1 | Kotlin files | Explicit check: `filePath.endsWith(".kt") || filePath.endsWith(".kts")` with clear error message | Good -- clear explanation | -- |
| 2 | Python files | No explicit check for `.py` files | Falls through to provider which returns null from `analyzeDataflow()`, then returns "No expression found..." -- **misleading**: suggests positional problem when the real issue is language unsupport | MEDIUM |
| 3 | No DFA result | Returns "No expression found at this position. DataFlow analysis requires an expression inside a method body." | Good guidance | -- |
| 4 | Java files | Full DFA via provider (CommonDataflow) | Works | -- |
| 5 | File extension check is fragile | Only checks `.kt` and `.kts` -- doesn't check `.py`, `.js`, `.ts`, etc. | Python/JS files slip through to provider which returns null with a misleading message | MEDIUM |

**Python readiness:** Does NOT work -- PythonProvider.analyzeDataflow() returns null. The tool doesn't proactively reject Python files, leading to a misleading error message about "no expression found."

---

### 15. GetMethodBodyTool (`get_method_body`)

| # | Scenario | Current Handling | Gap/Issue | Severity |
|---|----------|-----------------|-----------|----------|
| 1 | Method is in a library (decompiled source) | Provider's `getBody()` works on any PsiMethod regardless of source | Should work on decompiled .class files if navigated via class index | -- |
| 2 | Overloaded methods | Shows up to 3 overloads with `(overload #N)` labels | Good -- shows multiple overloads | -- |
| 3 | Method is inherited | Searches with `false` (own class) first, then `true` (inherited) with note | Good -- clear "inherited from X" note | -- |
| 4 | Method not found | Lists available methods (up to 20) for guidance | **Excellent** -- helps the LLM discover correct method names | -- |
| 5 | Python functions | Provider resolved via `registry.forLanguageId("JAVA") ?: registry.forLanguageId("kotlin")` | **Python unreachable** -- always uses Java/Kotlin provider, which casts result to `PsiClass` | HIGH |
| 6 | Context lines | Supported (0-5) via `context_lines` parameter | Good | -- |
| 7 | Very long method body | No truncation on method body text | A 500-line method body is returned in full -- could overwhelm context window | MEDIUM |

**Python readiness:** Does NOT work for Python. `findSymbol()` goes through Java/Kotlin provider. Even if found, result is cast to `PsiClass` which Python elements are not.

---

### 16. GetAnnotationsTool (`get_annotations`)

| # | Scenario | Current Handling | Gap/Issue | Severity |
|---|----------|-----------------|-----------|----------|
| 1 | No annotations | Returns "(none)" | Good | -- |
| 2 | Inherited annotations | Controlled via `include_inherited` parameter | Good | -- |
| 3 | Fields don't have inherited annotations | Comment: "fields don't have inherited annotations" -- `includeInherited` passed as `false` | Correct | -- |
| 4 | Python decorators | Provider resolved via `registry.forLanguageId("JAVA") ?: registry.forLanguageId("kotlin")` | **Python unreachable** -- same hardcoded fallback issue | HIGH |
| 5 | Annotation parameter values | Shows `key=value` pairs | Good | -- |
| 6 | Overloaded methods | Shows annotations for all overloads with index labels | Good | -- |

**Python readiness:** Does NOT work for Python. Python decorators could be represented as MetadataInfo, but the tool never reaches the PythonProvider.

---

### 17. FindImplementationsTool (`find_implementations`)

| # | Scenario | Current Handling | Gap/Issue | Severity |
|---|----------|-----------------|-----------|----------|
| 1 | Non-abstract method | Returns "No implementations found... may not be abstract/interface" | Good guidance | -- |
| 2 | Many implementations | `implementations.take(40)` with truncation | Good | -- |
| 3 | Python classes/methods | Provider resolved via `registry.forLanguageId("JAVA") ?: registry.forLanguageId("kotlin")` | **Python unreachable** -- same hardcoded fallback | HIGH |
| 4 | Class implementations (not just methods) | JavaKotlinProvider handles both PsiMethod (OverridingMethodsSearch) and PsiClass (ClassInheritorsSearch) | Good | -- |
| 5 | Kotlin implementations | Found via ClassInheritorsSearch which works cross-language | Good | -- |

**Python readiness:** Does NOT work for Python.

---

### 18. StructuralSearchTool (`structural_search`)

| # | Scenario | Current Handling | Gap/Issue | Severity |
|---|----------|-----------------|-----------|----------|
| 1 | **`file_type` parameter declared but never used** | Parameter is in the schema but the `execute()` method never reads it | **LLM can pass `file_type="kotlin"` and it is silently ignored** -- search always uses whatever the provider defaults to | HIGH |
| 2 | Invalid SSR pattern | Caught by generic exception handler | Error message is generic -- doesn't explain SSR syntax or provide examples | MEDIUM |
| 3 | Module scope resolution | Falls back to project scope silently when module not found | Good -- silent fallback is reasonable | -- |
| 4 | Python | Provider resolved via `registry.forLanguageId("JAVA") ?: registry.forLanguageId("kotlin")`; PythonProvider returns null for structuralSearch | **Returns "structural search failed -- provider returned null"** -- confusing. Should explicitly say "Structural search is not available for Python" | MEDIUM |
| 5 | `max_results` cap | Default 20, shown from full result set | Good | -- |
| 6 | Matched text truncation | `match.matchedText.take(100)` | Good -- prevents huge output per match | -- |

**Python readiness:** Does NOT work for Python (PythonProvider.structuralSearch returns null). Tool gives confusing error message rather than a clear "not supported for Python" message.

---

### 19. ReadWriteAccessTool (`read_write_access`)

| # | Scenario | Current Handling | Gap/Issue | Severity |
|---|----------|-----------------|-----------|----------|
| 1 | File-based provider resolution | Uses `registry.forFile(psiFile)` | **Correct** -- resolves Python provider for .py files | -- |
| 2 | No references found | Shows "No references found" within the analysis header | Good | -- |
| 3 | Large number of accesses | `reads.take(50)`, `writes.take(50)`, `readWrites.take(50)` | Good truncation | -- |
| 4 | Kotlin classification | Via class name check: "KtProperty", "KtParameter" | Good | -- |
| 5 | Python files | File-based resolution selects PythonProvider correctly | **Works for Python** | -- |
| 6 | Scope parameter | `project` (global) or `file` (local) | Good flexibility | -- |

**Python readiness:** Works through PythonProvider (file-based resolution). Access classification for Python variables/attributes depends on PythonProvider.classifyAccesses() implementation.

---

### 20. TestFinderTool (`test_finder`)

| # | Scenario | Current Handling | Gap/Issue | Severity |
|---|----------|-----------------|-----------|----------|
| 1 | File-based provider resolution | Uses `registry.forFile(psiFile)` | **Correct** -- resolves Python provider for .py files | -- |
| 2 | No test found | Returns "No test classes found for this source class." or "No source classes found for this test." | Good directional message | -- |
| 3 | Multiple test classes | Shows all related test classes with indices | Good | -- |
| 4 | Python test files (pytest) | PythonProvider's `findRelatedTests()` should handle | Depends on PythonProvider implementation | -- |
| 5 | `findClassByName` uses `PsiClass` | Casts to `PsiClass` which Python classes might not be | Could fail to find Python classes in file -- falls through to "no class found in file" error | MEDIUM |
| 6 | `findFirstClass` uses `PsiClass` | Same issue as #5 | Same issue | MEDIUM |

**Python readiness:** Partially works. `findClassByName` and `findFirstClass` use `PsiClass` instanceof checks which Python classes satisfy (they implement `PsiClass` interface via the Python plugin). However, if the Python plugin represents classes differently, these checks could fail.

---

## Systemic Fix Recommendations

### CRITICAL Priority (fix immediately)

1. **Call hierarchy cycle detection** -- Add `visited: MutableSet<PsiElement>` to `collectCallers()` in both `JavaKotlinProvider` and `PythonProvider`. Check `if (!visited.add(method)) return` at the top of the recursion.

2. **Diagnostics `isError=true` for found problems** -- Change line 105 of `SemanticDiagnosticsTool.kt` from `isError = true` to `isError = false`. Finding problems is a successful diagnostic result, not a tool error.

3. **`lastEditLineRanges` cross-session contamination** -- Move from static `ConcurrentHashMap` on `EditFileTool` to per-session state (e.g., on `ConversationSession` or passed via `WorkerContext`).

4. **OptimizeImportsTool dumb mode crash** -- Add `DumbService.isDumb(project)` check before execution, same as RunInspectionsTool.

### HIGH Priority (fix in next sprint)

5. **Hardcoded Java/Kotlin provider fallback** -- For symbol-based tools (type_hierarchy, call_hierarchy, get_method_body, get_annotations, find_implementations, structural_search), change from `registry.forLanguageId("JAVA") ?: registry.forLanguageId("kotlin")` to a multi-provider search: try all registered providers' `findSymbol()` until one succeeds.

6. **FormatCodeTool dumb mode check** -- Add `DumbService.isDumb(project)` check. While formatting doesn't need indexes, `PsiManager.findFile()` can fail during initial indexing.

7. **FormatCodeTool + OptimizeImportsTool: no-op detection** -- Capture file content hash before operation, compare after. If unchanged, return "File is already properly formatted / imports are already optimal."

8. **FormatCodeTool + OptimizeImportsTool: diff output** -- Show what changed (at minimum: "N lines changed" or list of affected lines).

9. **StructuralSearchTool: wire `file_type` parameter** -- Read the parameter and pass it to the provider's `structuralSearch()` call (or set the `MatchOptions.fileType`).

10. **RefactorRenameTool: Python support** -- When `JavaPsiFacade` fails to find the symbol, try all registered providers' `findSymbol()` before falling back to file-scoped search.

11. **FindReferencesTool: Python file-scoped search** -- Replace `(psiFile as? PsiJavaFile)?.classes` with provider-based class discovery that works across languages.

12. **RunInspectionsTool + ListQuickFixesTool: `isEnabledByDefault` filter** -- Replace with `profile.isToolEnabled(toolWrapper.id, psiFile)` to respect user's actual inspection settings.

13. **ListQuickFixesTool: performance** -- Scope the inspection visitor to only elements near the target line instead of walking the entire file.

14. **RefactorRenameTool: name conflict pre-check** -- Before executing the rename, check if `newName` conflicts with existing symbols in the same scope. Return a clear error if it does.

### MEDIUM Priority (fix when touching these files)

15. **DataFlowAnalysisTool: Python rejection** -- Add explicit `.py` file extension check alongside `.kt`/`.kts`, with a clear message about language support.

16. **ProblemViewTool: dumb mode check** -- Add check or at least a warning that results may be stale during indexing.

17. **ProblemViewTool: INFO exclusion transparency** -- Either include INFO-level problems when `severity=all`, or document the exclusion in the parameter description.

18. **ProblemViewTool: per-file truncation message** -- Add truncation message when hitting the 30-problem cap per file.

19. **FileStructureTool: large file auto-downgrade** -- When file exceeds N lines (e.g., 2000) and detail is "full", auto-downgrade to "signatures" and note this in the output.

20. **StructuralSearchTool: Python error message** -- When provider returns null, check if the file is Python and return "Structural search is not available for Python" instead of the generic error.

21. **FormatCodeTool: read-only file message** -- Catch the specific exception for read-only files and return a clear message.

22. **SemanticDiagnosticsTool: edit range consumed on read** -- Consider keeping the range and only clearing it on the next edit, so multiple diagnostics calls on the same file work correctly.

23. **GetMethodBodyTool: long method truncation** -- Add a max-lines cap (e.g., 200 lines) with a truncation notice, or offer a `max_lines` parameter.

24. **RefactorRenameTool: stale element between phases** -- Use a single RenameProcessor instance for both find-usages and perform-refactoring phases.

---

## Python Readiness Summary

| Tool | Python Works? | Reason |
|------|--------------|--------|
| format_code | YES | Platform API delegates to language-specific formatter |
| optimize_imports | YES | LanguageImportStatements delegates to Python optimizer |
| refactor_rename | PARTIAL | File-scoped search works; global search via JavaPsiFacade fails |
| run_inspections | YES | LocalInspectionToolWrapper delegates to Python inspections |
| problem_view | YES | HighlightInfo is language-agnostic |
| list_quickfixes | YES | Same architecture as run_inspections |
| diagnostics | YES | File-based provider resolution selects PythonProvider |
| find_definition | PARTIAL | Works when element is found (file-based provider), but initial global search goes through Java provider |
| find_references | PARTIAL | Global search works via PsiShortNamesCache; file-scoped search hardcoded to PsiJavaFile |
| file_structure | YES | File-based provider resolution selects PythonProvider |
| type_hierarchy | NO | Hardcoded Java/Kotlin provider fallback |
| call_hierarchy | NO | Hardcoded Java/Kotlin provider fallback |
| type_inference | YES | File-based provider resolution selects PythonProvider |
| dataflow_analysis | NO | PythonProvider returns null; no explicit Python rejection |
| get_method_body | NO | Hardcoded Java/Kotlin provider; casts to PsiClass |
| get_annotations | NO | Hardcoded Java/Kotlin provider fallback |
| find_implementations | NO | Hardcoded Java/Kotlin provider fallback |
| structural_search | NO | Hardcoded Java/Kotlin provider; PythonProvider returns null |
| read_write_access | YES | File-based provider resolution selects PythonProvider |
| test_finder | PARTIAL | File-based resolution works; PsiClass cast may fail for some Python class representations |

**Summary:** 8 of 20 tools fully work for Python, 4 partially work, 8 do not work at all. The primary blocker is 6 tools using hardcoded `registry.forLanguageId("JAVA")` fallback instead of file-based or multi-provider resolution.

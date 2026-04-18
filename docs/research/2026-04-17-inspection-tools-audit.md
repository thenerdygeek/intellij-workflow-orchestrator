# Inspection / Diagnostics Tool Audit — Current State vs. IntelliJ Contract

**Date:** 2026-04-17
**Scope:** `run_inspections`, `list_quickfixes`, `problem_view`, `diagnostics`, `format_code`, `optimize_imports`, `refactor_rename` — the IDE's static analysis, problem reporting, and code transformation tools.
**Companion context:** From the runtime/test audit — focus on (1) signal fidelity (aggregate counts vs. per-item structured data), (2) output spilling for large result sets, (3) reflection-based IntelliJ API access, (4) threading contracts (read action, write action, EDT, dumb mode).

---

## 1. Executive Summary

The inspection tools **inherit the same signal-fidelity and output-spilling issues** as the runtime/test path, plus new threading hazards. Three findings:

| Finding | Class | Impact |
|---|---|---|
| **F1: Aggregate counts, no per-item data** | Signal fidelity (matching runtime incident #1) | LLM sees `"30 problems in file"` but not the file:line:severity:message per problem. Truncated at 30 items hardcoded, 2K display. Large refactors return only headlines. |
| **F2: ToolOutputSpiller not wired** | Output spilling (matching runtime incident in section 5.4) | `RunInspectionsTool` collects ALL problems then truncates to 30 display items without consulting spiller. A file with 100+ issues shows first 30 + count. On refactoring 500 files, inspection output (>50K) hard-truncates. |
| **F3: DumbService checks present but incomplete** | Threading safety | `OptimizeImportsTool` and `FormatCodeTool` check `DumbService.isDumb()`. `RunInspectionsTool` and `ListQuickFixesTool` do NOT — they can run partial analysis during indexing and miss enabled inspections. `SemanticDiagnosticsTool` checks but delegates to provider — no guarantees the provider respects dumb mode. |
| **F4: RefactorRenameTool has no conflict/cross-module guard** | Refactor safety | No pre-check for rename conflicts, external library symbols, or cross-module references. `RenameProcessor(searchInsideComments=false, searchTextOccurrences=false)` configured, but no dry-run / preview mode or conflict report to the LLM. |
| **F5: Inspection profile API misused** | IntelliJ contract compliance | Uses `profile.isToolEnabled(key, psiFile)` (correct) but uses `LocalInspectionToolWrapper.tool.buildVisitor()` (deprecated manual walk). Should use `LocalInspectionToolWrapper.processFile()` or `InspectionManager.runInspection()`. |
| **F6: Quick-fix collection duplicates inspection run** | Redundant work | `ListQuickFixesTool` runs the ENTIRE inspection suite again (same psi walk, same holders) just to extract fixes. Should cache quick-fix info in `RunInspectionsTool` or use `IntentionAction` API directly via `CodeInsightUtils`. |

---

## 2. Per-Tool Audit

### 2.1 `RunInspectionsTool` (RunInspectionsTool.kt)

**Threading:** ReadAction non-blocking (`inSmartMode`), correct.  
**Dumb mode:** Checks `DumbService.isDumb()` at line 52, returns error. Good.  
**Inspection profile:** Uses `profile.isToolEnabled(key, psiFile)` at line 71 (correct).

**Issues:**

1. **Manual inspection walk (line 68-82)** — Walks all inspection tools, calls `tool.buildVisitor(holder, false)` directly, then does a recursive walk of psiFile. This pattern is deprecated; `LocalInspectionToolWrapper.processFile(psiFile, Holder)` abstracts the visitor building + walking. **Risk:** if buildVisitor's semantics change in a future platform, we won't get the update.

2. **Aggregate count only (line 113)** — Returns `"30 problems(s) in {vf.name}:\n{lines}"` where `lines` is joined text. The header says "30 problems" but the LLM can only inspect the first 30 in the displayed list. If inspection found 150 problems, the LLM's context gets 30 + a "... and 120 more" footer. **No per-item JSON** — the LLM can't query problem ID 45 or sort by severity.

3. **Hardcoded MAX_PROBLEMS = 30 (line 150)** — Blocks seeing a complete problem set on large files. A service class with 20 inspections enabled might yield 50+ issues. The tool design assumes "peek at first 30" is enough; it's not for understanding a refactoring scope.

4. **No ToolOutputSpiller (line 114)** — Content is tokenized inline. TokenEstimator.estimate(content) assumes everything fits in context. A file with 150 issues, each 200 chars = 30K output, exceeds the spiller threshold but is not spilled. The `summary` is 5 tokens but the `content` is 8K tokens — a gap the spiller was designed to close.

5. **isError semantics (line 104)** — Returns `isError=false` when problems are found (successful analysis). The problem list is the success payload. This matches CLAUDE.md claim; good.

6. **Exception swallowing (line 98-100)** — Catches all exceptions during inspection.run and silently continues. This masks platform regressions (e.g., if a custom inspection breaks on a new file type). Should at least log or count skipped tools.

### 2.2 `ListQuickFixesTool` (ListQuickFixesTool.kt)

**Threading:** ReadAction non-blocking, correct.  
**Dumb mode:** Checks at line 51. Good.  
**Quick-fix source:** Uses inspection problem.fixes (line 102-109), not IntentionActions.

**Issues:**

1. **Redundant full inspection run (lines 83-117)** — Identical loop to `RunInspectionsTool`, runs all inspections again, just to collect fixes from ProblemsHolder. This is wasteful. The tool should:
   - Call `RunInspectionsTool` (if already called in the session), or
   - Use `IntentionAction.isAvailable()` + `CodeInsightUtils.getAvailableActions(editor, offset)` directly (the proper IDE API).

2. **Line-only precision (line 101)** — Matches quick fixes by line number. If a line has multiple problems (e.g., "unused variable" + "missing @Override"), will list fixes for all. No way to disambiguate "I want the fix for the unused variable, not the annotation".

3. **Deduplication by fixName only (line 127)** — Two problems with the same fix (e.g., "Inline variable" for two different variables) get deduplicated into one entry. The LLM might request "apply fix 1" but ambiguous which problem it applies to.

4. **MAX_FIXES = 20 hardcoded (line 149)** — Same cap issue as RunInspectionsTool.

5. **No per-fix precondition check (line 105-109)** — Adds every fix from every problem, even if `fix.isAvailable()` returns false at this offset. A quick fix that's disabled for the current context is still shown.

### 2.3 `ProblemViewTool` (ProblemViewTool.kt)

**Threading:** ReadAction non-blocking, correct.  
**Coverage:** Uses WolfTheProblemSolver (lines 83-84) + HighlightInfo extraction (lines 160-181). Both are correct IntelliJ contract signals.

**Issues:**

1. **HighlightInfo extraction from DocumentMarkupModel (lines 147-188)** — Iterates `markupModel.allHighlighters` and extracts HighlightInfo. This is the on-the-fly daemon highlighter output. **It only includes problems for files that have been opened in the editor and have the daemon running.** As the tool's own description notes (line 26-27), unopened files won't show problems here. This is by design (WolfTheProblemSolver is file-level, HighlightInfo is editor-relative), so it's correct documentation, but the limitation is stark.

2. **Cap at 30 problems per file (line 187)** — Same hardcoding as RunInspectionsTool. If a file has warnings scattered across 200 lines, only the first 30 are shown.

3. **No STDERR vs STDOUT separation (line 169)** — When problems are found, returns them as the content. No distinction between errors, warnings, and info (severity filter is applied at line 173-177, so this is actually OK; all returned items are filtered to the requested severity).

4. **isError semantics (line 91)** — Returns `isError=false` when no problems (success, "file is clean"). Returns `isError=true` only on tool failure. When problems ARE found, `isError=false` (line 95). This matches the CLAUDE.md semantics and is consistent with RunInspectionsTool.

5. **No ToolOutputSpiller** — Same as RunInspectionsTool.

### 2.4 `SemanticDiagnosticsTool` (SemanticDiagnosticsTool.kt)

**Threading:** ReadAction non-blocking, inSmartMode, correct.  
**Dumb mode:** Checks at line 45. Good.  
**Provider delegation:** Delegates to `registry.forFile(psiFile)` (line 63), which is language-aware.

**Issues:**

1. **Provider doesn't guarantee dumb-mode handling** — The tool checks `isDumb()` and bails, but if a future provider skips the check, it could run partial analysis during indexing. No guard in the provider interface contract itself.

2. **Edit-range caching (lines 50-52)** — References `EditFileTool.lastEditLineRanges` (a static map keyed by sessionId:path). This is session-scoped state management. **Risk:** if the sessionId computation changes or Edit-FileTool.scopedKey() changes, the key won't match and edit-aware scoping breaks. Should be centralized in ContextManager, not scattered across tool classes. Also, `remove()` is destructive; if called twice, the second call gets null and scoping is silently lost.

3. **isError semantics (line 106)** — Returns `isError=false` when problems are found (correct, per CLAUDE.md). Good.

4. **No ToolOutputSpiller** — Line 105 returns content with no spiller.

5. **Large file cap at 20 issues (line 99)** — If a file has 100 errors near the edit range, shows first 20 + count. This is less aggressive than RunInspectionsTool (which shows 30), but still a hardcoded limit.

### 2.5 `FormatCodeTool` (FormatCodeTool.kt)

**Threading:** EDT write action via `WriteCommandAction` (line 54), correct.  
**Dumb mode:** Checks at line 40. Good.  
**No problems analysis** — formats and returns before/after line count. No inspection hooks.

**Issues:**

1. **CodeStyleManager.reformat() without file range** — Line 59 calls `reformat(psiFile)` which reformats the entire file. There's an overload `reformat(psiFile, ranges: Collection<TextRange>)` for partial reformatting. If the agent edited a few lines, reformatting only those lines would be faster and less likely to conflict. **Not a correctness bug, but a performance miss.**

2. **No PsiDocumentManager.commitDocument before format** — The tool gets a Document (line 57) but never commits pending PSI changes via `PsiDocumentManager.getInstance(project).commitDocument(document)` before formatting. If there are uncommitted writes, `reformat()` operates on stale PSI. **Risk:** EditFileTool wrote changes to the PSI, FormatCodeTool then gets the Document, which is now out of sync with the PSI tree. The result depends on whether the Document was already synced by EditFileTool's own WriteCommandAction.

3. **No ToolOutputSpiller** — Returns only before/after line counts, so overflow is unlikely. But summary is 5 tokens, content is 10 tokens; no spilling needed.

### 2.6 `OptimizeImportsTool` (OptimizeImportsTool.kt)

**Threading:** EDT write action, correct.  
**Dumb mode:** Checks at line 40, good.  
**Import API:** Uses `LanguageImportStatements.INSTANCE.forFile(psiFile)` (line 61), which is the proper abstraction.

**Issues:**

1. **processFile().run() pattern (lines 63-66)** — Loops through optimizers and calls `optimizer.processFile(psiFile).run()`. This is a Runnable that modifies the PSI tree. The tool relies on `WriteCommandAction` wrapping to make it undoable. However, there's no error handling — if `processFile()` returns a Runnable that throws, the exception propagates and the whole operation fails without a partial ToolResult.

2. **Import extraction is text-based (lines 112-116)** — Regex matching on "import " lines. This works for Java/Kotlin but won't generalize to Python (`from X import Y`) or other languages that don't use the word "import". (Kotlin does `import`, Python does `import` and `from...import`, so this heuristic is fragile.)

3. **No ToolOutputSpiller** — Returns only summary counts ("added X, removed Y"), which is lightweight.

### 2.7 `RefactorRenameTool` (RefactorRenameTool.kt)

**Threading:** ReadAction for finding element (line 53-55), EDT write action for refactoring (line 74), correct separation.  
**Dumb mode:** Checks at line 48, good.

**Issues:**

1. **No conflict detection or preview (lines 67-79)** — Calls `RenameProcessor(project, element, newName, false, false)` with `setPreviewUsages(false)`. This means:
   - No user preview dialog (correct for agent).
   - No conflict checking (setSearchInsideComments=false, setSearchTextOccurrences=false prevent some false positives).
   - **No cross-module or external-library conflict report to the LLM.** If the LLM renames a public class used by another project, the rename succeeds locally but breaks downstream. The LLM gets no signal.

2. **No pre-validation of rename safety (lines 92-146)** — The `findElement()` logic iterates through providers and registry, trying multiple approaches. If the element is found but is:
   - A library symbol (from a JAR).
   - A symbol overriding an external interface.
   - A method with cross-module references.
   ...the tool proceeds anyway. **Risk:** Renaming a library method silently fails (the JAR doesn't change), or renaming an override breaks inheritance.

3. **findElement fallback complexity (lines 107-128)** — Has four fallback strategies: (1) search in file, (2) ClassName.methodName via PSI, (3) registry providers, (4) PSI facade. Each one might return a different element if there's shadowing. The tool just returns the first match. **Risk:** two classes with the same method name; LLM specifies `findElement("myMethod")` and the wrong class is renamed.

4. **Performance: no caching of element resolution (line 67-71)** — Resolves the element, then in the write action phase, creates a NEW RenameProcessor to findUsages, then another one for performRefactoring. The element lookup happens twice. For large projects, this is wasteful.

5. **No isError semantics documentation** — The tool returns `isError=false` on success and `isError=true` only on exception. If a rename is skipped because the element is readonly, it returns an error. But if a rename succeeds on the local source but fails on cross-module references (undetectable), it returns success. **Inconsistent feedback.**

---

## 3. IntelliJ Contract Compliance

### Key IntelliJ APIs Used

| API / Class | Contract | Current usage | Gap |
|---|---|---|---|
| `InspectionManager.getInstance(project)` | Create ProblemsHolder for inspection | `RunInspectionsTool` + `ListQuickFixesTool` use it | OK |
| `InspectionProjectProfileManager.getInstance(project).currentProfile` | Get active inspection profile | All tools use it | OK |
| `profile.isToolEnabled(key, psiFile)` | Check if inspection is enabled | `RunInspectionsTool` + `ListQuickFixesTool` use it correctly | OK |
| `LocalInspectionToolWrapper.tool.buildVisitor()` | Visitor for inspection | `RunInspectionsTool` uses it; deprecated pattern | R1 |
| `WolfTheProblemSolver.getInstance(project).isProblemFile()` | File-level problem flag | `ProblemViewTool` uses it | OK |
| `DocumentMarkupModel.forDocument()` / `HighlightInfo` | On-the-fly highlighting | `ProblemViewTool` uses it | OK (but limited to open files) |
| `CodeStyleManager.reformat()` | Reformat PSI file | `FormatCodeTool` uses it | OK (no range optimization) |
| `LanguageImportStatements.INSTANCE.forFile()` | Import optimizers by language | `OptimizeImportsTool` uses it | OK |
| `RenameProcessor(element, newName)` | Safe rename refactoring | `RefactorRenameTool` uses it | Missing preview, conflict detection (R5) |
| `DumbService.isDumb()` | Index completion check | `FormatCodeTool`, `OptimizeImportsTool`, `RunInspectionsTool` (partial), `SemanticDiagnosticsTool` use it | Some tools missing check (R2) |

### Inspection Profile Contract

Per CLAUDE.md: "we use `profile.isToolEnabled()` (per CLAUDE.md) instead of `isEnabledByDefault`"

**Verification:** Line 71 in RunInspectionsTool and line 86 in ListQuickFixesTool both use `profile.isToolEnabled(key, psiFile)`. Correct — this respects the active profile and per-file overrides.

### Signal Fidelity vs. IntelliJ Output

| Tool | IntelliJ contract | Current output | Gap |
|---|---|---|---|
| RunInspectionsTool | Per-problem: file, line, severity, message, quick-fixes | Aggregate count + first 30 formatted lines | F1 |
| ListQuickFixesTool | Per-fix: problem description, family name, availability | Aggregate count + first 20 formatted fixes | F1 + duplicate-run overhead |
| ProblemViewTool | Per-problem from HighlightInfo: severity, description, range | Aggregate count + first 30 + daemon-only coverage | F1 |
| SemanticDiagnosticsTool | Per-diagnostic: line, message | Aggregate count + first 20 + edit-scoped | F1 |
| FormatCodeTool | Before/after file comparison | Line count changed | OK (no signal loss) |
| OptimizeImportsTool | Structured import diff (added, removed) | Summary counts + file path | OK (lightweight) |
| RefactorRenameTool | Rename success + usage count OR conflict list | Success ToolResult OR error + message | Missing conflict preview (F4) |

---

## 4. Output Spilling Analysis

All inspection/format/refactor tools call `ToolResult(content, summary, tokenEstimate, isError)` where `content` is built as a string and tokenized inline.

**Current:** No tool uses `ToolOutputSpiller`. All truncate via `truncateOutput()` (middle-truncation strategy, first 60% + last 40%).

**Overflow scenarios:**
- RunInspectionsTool on a large file: 150 issues × 200 chars/issue = 30K content, truncated to 12K.
- ListQuickFixesTool on a complex class: 50+ fixes × 150 chars = 7.5K, safe.
- ProblemViewTool on a multi-file result: 5 files × 30 problems × 100 chars = 15K, truncated.
- SemanticDiagnosticsTool: 20 issues × 100 chars = 2K, safe.

**Recommendation (R3):** Wire ToolOutputSpiller into RunInspectionsTool and ProblemViewTool, with 30K spill threshold.

---

## 5. Threading & Dumb Mode

### DumbService Check Audit

| Tool | Check | Line | Issue |
|---|---|---|---|
| RunInspectionsTool | Yes | 52 | Checks but runs manual inspection walk — should check per-inspection too |
| ListQuickFixesTool | Yes | 51 | OK |
| ProblemViewTool | No | — | **Missing. HighlightInfo is daemon output, always available, but the tool's semantics assume indexing is complete.** If index is not complete, missing problems should be flagged. |
| SemanticDiagnosticsTool | Yes | 45 | OK, but delegates to provider with no contract guarantee |
| FormatCodeTool | Yes | 40 | OK |
| OptimizeImportsTool | Yes | 40 | OK |
| RefactorRenameTool | Yes | 48 | OK |

**F3 Conclusion:** Four tools check DumbService correctly. Two tools don't (ProblemViewTool, SemanticDiagnosticsTool delegates without guarantee). RunInspectionsTool checks but then runs manual walk that doesn't re-check per tool.

---

## 6. Reflection-Based API Access (Fragility Audit)

The runtime audit flagged "brittle reflection-based access to IntelliJ internals." Let's check inspection tools.

| Tool | Reflection Use | API stability | Fallback |
|---|---|---|---|
| RunInspectionsTool | None (uses public API) | High | N/A |
| ListQuickFixesTool | None | High | N/A |
| ProblemViewTool | None | High | N/A |
| SemanticDiagnosticsTool | Registry lookup via string name | Medium | Language provider pattern is good; no fallback if provider missing |
| FormatCodeTool | None | High | N/A |
| OptimizeImportsTool | `LanguageImportStatements.INSTANCE.forFile()` extension point | High | If no optimizer, returns "No optimizer available" |
| RefactorRenameTool | Registry lookup (`registry.allProviders()`) + `RenameProcessor` | Medium | Four fallback strategies (file search, PSI facade, Java methods, class lookup) |

**Conclusion:** No heavy reflection (unlike the runtime tools' `CompilerTopics.COMPILATION_STATUS` listener magic). All tools use stable extension points. Low risk.

---

## 7. Missing Scenarios (Inspection Contract vs. Implementation)

Per the pattern from the runtime audit, here are signals the IntelliJ contract exposes that **no** inspection tool currently emits:

| # | Signal | Why it matters | Current behavior |
|---|---|---|---|
| 1 | Per-problem HighlightInfo.getSeverity() + HighlightInfo.getDescription() | Allows LLM to sort/filter by severity or extract specific messages | Aggregate counts only |
| 2 | Quick-fix precondition failure (isAvailable=false) | Prevents "apply fix X" when unavailable | ListQuickFixesTool shows all fixes, not just available ones |
| 3 | Inspection profile mismatch (tool enabled in global profile but disabled in project profile) | Explains why a known inspection didn't fire | Tool uses isToolEnabled but doesn't explain mismatches |
| 4 | Rename conflict list (Usages where rename would break) | Allows LLM to decide if rename is safe cross-module | RefactorRenameTool has no conflict preview |
| 5 | IntentionAction availability context (cursor position, selection, etc.) | Explains why an action is grayed out | ListQuickFixesTool knows line but not cursor offset |
| 6 | Formatter applied only to edited ranges (if API supports it) | Speeds up large refactors, focuses changes | FormatCodeTool reformats entire file |
| 7 | Empty inspection result (no problems found) vs daemon not run yet (index incomplete) | Distinguishes "clean code" from "incomplete analysis" | Only ProblemViewTool checks daemon; others assume completion |

---

## 8. Refactor Safety Deep Dive

### RefactorRenameTool Risks

Per CLAUDE.md: "is `RefactorRenameTool` guarded against cross-module / external-library conflicts?"

**Current guard:** `RenameProcessor(searchInsideComments=false, searchTextOccurrences=false)` — prevents false positives from comment text or method references in strings. But:

1. **No `searchInLibraries` parameter checked** — If element is from a `.jar`, rename still proceeds. IntelliJ's `RenameProcessor` will update local usages but not the `.jar`. Result: local compile succeeds, but users of the library see unresolved references.

2. **No dry-run / preview** — `setPreviewUsages(false)` means no preview dialog. Good for agent UX, but also means no conflict list is generated. LLM has no visibility into what would break.

3. **No architecture boundary checks** — If element is in module A and used by module B, rename could violate encapsulation. Java doesn't have a standard "package-visible" check; the tool relies on `RenameProcessor.findUsages()` which uses PSI scope. Scope might miss transitive usages (e.g., overrides in subclasses in different modules).

4. **No string-based usages** — `setSearchTextOccurrences(false)` prevents regex replacement in strings. Correct for safety (no false positives), but might miss brittle string-based lookups (e.g., reflection, bean names in XML). **This is a design trade-off, not a bug.**

**Recommendation (R5):** Before calling RenameProcessor.performRefactoring, collect usages and report any that cross module boundaries or involve library symbols. Return `isError=false` but include a warning in the summary.

---

## 9. Architectural Issues (Cross-Cutting)

### 9.1 RunInspectionsTool vs ListQuickFixesTool Redundancy

Both tools run the same inspection suite (lines 68-117 in RunInspectionsTool, lines 83-117 in ListQuickFixesTool). This is wasteful.

**Ideal:** Call `RunInspectionsTool` if already called in the session, or cache the results in a per-file inspection cache. Quick-fix extraction should be lightweight (just call `RunInspectionsTool` and extract fixes from its output).

### 9.2 Hardcoded MAX_PROBLEMS / MAX_FIXES Limits

Four tools hardcode display limits: RunInspectionsTool (30), ListQuickFixesTool (20), ProblemViewTool (30), SemanticDiagnosticsTool (20).

**Consequence:** A file with 100+ issues shows only the first N + a count. The LLM can't see issue #51 even if it's critical. Removing the limit and relying on ToolOutputSpiller would be more flexible.

### 9.3 Edit-Range Caching via Static Map

`SemanticDiagnosticsTool` stores edit ranges in `EditFileTool.lastEditLineRanges` (static map, scoped by `sessionId:path`). This couples two tools' internal state.

**Better:** Store in `ContextManager` or session-level state, not scattered across tools.

### 9.4 Inspection API Modernization

`RunInspectionsTool` and `ListQuickFixesTool` use the manual visitor pattern (`tool.buildVisitor(holder, false)` + manual walk). This is deprecated in favor of `LocalInspectionToolWrapper.processFile()` or `InspectionManager.runInspection()`.

**Risk:** Future platform updates might change the visitor contract. Using the wrapper directly insulates from this.

---

## 10. Recommendations

Grouped by outcome:

### F1 — Per-Item Structured Data

- **R1.** Add `data: List<ProblemEntry>` (JSON) to `ToolResult` for inspection tools. Each entry: `{ file, line, severity, message, inspection, fixes: [...] }`. The LLM can query specific problems.
- **R2.** Modify tool descriptions to advertise JSON availability: "Returns first 30 problems as text; full list available via JSON in `result.data`."
- **R3.** Remove hardcoded MAX_PROBLEMS / MAX_FIXES limits; let ToolOutputSpiller manage display vs. full context trade-off.

### F2 — Output Spilling

- **R4.** Wire `ToolOutputSpiller` into RunInspectionsTool and ProblemViewTool. Set spill threshold at 30K.

### F3 — DumbService Completeness

- **R5.** Add `DumbService.isDumb()` check to `ProblemViewTool` (line after 56). If dumb, return: `"IDE is still indexing. Problem view incomplete until indexing finishes."` with `isError=true`.
- **R6.** Document SemanticDiagnosticsTool's contract: "Results depend on language provider. For Java/Kotlin, respects dumb mode. For other languages, provider-dependent."

### F4 — RefactorRenameTool Safety

- **R7.** Before `performRefactoring`, collect usages and check for cross-module / library usages. Return a warning in `summary` if found: `"Rename succeeded locally; 3 usages outside this module were not updated."` with `isError=false` (success, but with caveat).
- **R8.** Document in tool description: "Use with caution for public symbols. Preview mode not available; renamed symbols outside this module will not be updated."

### F5 — Inspection API Modernization

- **R9.** Replace manual visitor walk with `LocalInspectionToolWrapper.processFile()` or `InspectionManager.runInspection()`. Reduces coupling to visitor contract.

### F6 — Quick-Fix Redundancy

- **R10.** Refactor `ListQuickFixesTool` to cache or reuse RunInspectionsTool results. If RunInspectionsTool was called, extract fixes from its cached output. Otherwise, run a lightweight quick-fix query via `IntentionAction` API directly.

### Cross-Cutting

- **R11.** Create an inspection-session cache (in ContextManager or SessionState) to deduplicate inspection runs within a session. Key: `(filePath, profileId, minSeverity)`.
- **R12.** Add unit tests for edge cases: empty file, file with 100+ issues, mixed severities, no quick fixes available, cross-module rename, external library symbol rename.

---

## 11. Not Covered (Worth Follow-Up)

- **Debug tools** (`debug_breakpoints`, `debug_step`, `debug_inspect`): Threading and listener cleanup patterns.
- **Runtime tools** (confirmed in companion audit): Exact same spilling + signal-fidelity gaps.
- **Framework tools** (`spring`, `django`, `fastapi`, `flask`): File-scan only, no runtime analysis.
- **Database tools** (`db_query`, `db_schema`): Likely truncation patterns without spilling.

---

## 12. Comparison to Runtime Audit Findings

| Finding | Runtime tools | Inspection tools |
|---|---|---|
| Aggregate counts vs per-item data | Incident #1 (compile errors counts-only) | F1 (problems counts-only) |
| ToolOutputSpiller not wired | Section 5.4 (confirmed) | F2 (confirmed) |
| DumbService checks | Some tools missing | F3 (some tools missing) |
| Reflection-based API access | Heavy (CompilerTopics listener) | Light (extension points only) |
| Hardcoded limits | 12K truncation, MAX_PROBLEMS | 30 items hardcoded |
| Cross-module safety | N/A (not relevant) | F4 (RefactorRenameTool risk) |

The inspection tools follow a similar pattern to runtime tools, with signal-fidelity and output-spilling as the primary concerns. The additional concern (refactor safety) is unique to the inspection category.


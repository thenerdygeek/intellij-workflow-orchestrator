# IDE Tools Audit Report

**Date:** 2026-03-25
**Scope:** All IntelliJ IDE tools in `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/`
**Total tools audited:** 97 (6 builtin, 16 PSI/IDE intelligence, 22 debug, 4 runtime, 3 config, 25 framework/Spring, ~21 integration wrappers not individually audited as they delegate to core services)

---

## Executive Summary

The tool implementations are generally well-engineered with consistent patterns: proper `ReadAction.nonBlocking` usage for PSI tools, dumb mode checks, path validation, token estimation, and sensible output caps. However, several critical and high-severity issues exist around threading, hanging coroutines, silent failures, and missing cancellation support.

**Finding counts:** 8 CRITICAL, 14 HIGH, 18 MEDIUM, 12 LOW

---

## CRITICAL: Will break/hang/crash in normal usage

### C1. RunInspectionsTool and ListQuickfixesTool use blocking `ReadAction.compute` instead of `ReadAction.nonBlocking`

**Files:** `ide/RunInspectionsTool.kt`, `ide/ListQuickFixesTool.kt`
**Issue:** Both tools use `ReadAction.compute<ToolResult, Exception> { ... }` which executes synchronously on the calling thread and blocks write actions from proceeding. If a write action is pending (e.g., user is typing), the read action will either deadlock or starve the EDT. All other PSI tools correctly use `ReadAction.nonBlocking<String> { ... }.inSmartMode(project).executeSynchronously()`.
**Impact:** Can freeze the IDE for seconds if inspections run on a large file while the user is editing.
**Fix:** Migrate to `ReadAction.nonBlocking<ToolResult> { ... }.inSmartMode(project).executeSynchronously()`.

### C2. SemanticDiagnosticsTool uses blocking `ReadAction.compute` for potentially expensive unresolved-reference scan

**File:** `ide/SemanticDiagnosticsTool.kt`
**Issue:** Same as C1. The `PsiRecursiveElementWalkingVisitor` walks the entire PSI tree inside `ReadAction.compute`, which can take significant time on large files and block write actions.
**Fix:** Migrate to `ReadAction.nonBlocking`.

### C3. StartDebugSessionTool can hang indefinitely via `suspendCancellableCoroutine` inside EDT

**File:** `debug/StartDebugSessionTool.kt`
**Issue:** The tool calls `suspendCancellableCoroutine` inside `withContext(Dispatchers.EDT)`. The continuation resumes from a `messageBus` subscriber callback (`XDebuggerManagerListener.processStarted`). If the debug session fails to start (e.g., port conflict, class not found, build failure), the `processStarted` callback is never invoked, and the coroutine hangs on EDT forever. There is no timeout wrapping this `suspendCancellableCoroutine`.
**Impact:** Blocks the EDT permanently, freezing the entire IDE.
**Fix:** Wrap the `suspendCancellableCoroutine` in `withTimeoutOrNull(30_000)` and disconnect the message bus connection on timeout/cancellation.

### C4. RunTestsTool native runner uses `java.util.Timer` polling without cleanup on cancellation

**File:** `ide/RunTestsTool.kt`
**Issue:** The `executeWithNativeRunner` method creates a `java.util.Timer` that polls every 100ms for up to 5 seconds. If the coroutine is cancelled, the timer is never cancelled -- it continues running orphaned. Additionally, `ProgramRunnerUtil.executeConfiguration` is called on EDT, then the code immediately enters `suspendCancellableCoroutine` without any guarantee the test process has started. The timer's `cont.resume()` could be called after the continuation has been cancelled, causing `IllegalStateException`.
**Impact:** Leaked timers, potential crashes from resuming a cancelled continuation.
**Fix:** Use `cont.invokeOnCancellation { timer.cancel() }`. Use `resumeWith(Result.success(...))` guarded by `cont.isActive` check, or use `cont.tryResume()`.

### C5. RefactorRenameTool calls `RenameProcessor.run()` inside `WriteCommandAction` on EDT -- triggers dialogs

**File:** `ide/RefactorRenameTool.kt`
**Issue:** `RenameProcessor.run()` may trigger conflict resolution dialogs, preview windows, or user confirmation dialogs. When called programmatically via `processor.setPreviewUsages(false)`, it still opens a progress dialog. The `invokeAndWait` + `runWriteCommandAction` wrapping means if any modal dialog appears, it will block the agent's coroutine indefinitely.
**Impact:** Agent hangs waiting for user to dismiss a dialog that appeared behind other windows.
**Fix:** Use `processor.setSearchInComments(false)` and `processor.setSearchTextOccurrences(false)` (already done). Additionally, set `processor.setInteractive(false)` if available. Better: run in a background task using `processor.run()` outside WriteCommandAction, as RenameProcessor manages its own write actions internally.

### C6. AgentDebugController `computeChildren` and `resolvePresentation` have no timeout

**File:** `debug/AgentDebugController.kt`
**Issue:** `computeChildren` and `resolvePresentation` use `suspendCancellableCoroutine` that wraps XDebugger callbacks. If the debuggee is stuck (infinite loop, deadlock), these callbacks may never fire. There is no `withTimeoutOrNull` around them.
**Impact:** `get_variables` tool hangs forever if the debug process is unresponsive.
**Fix:** Wrap each `suspendCancellableCoroutine` call in `withTimeoutOrNull(5000)`.

### C7. EditFileTool `replaceFirst` uses Kotlin String.replaceFirst which treats `$` in replacement as regex backreference

**File:** `builtin/EditFileTool.kt` (line 82)
**Issue:** `content.replaceFirst(oldString, newString)` uses `String.replaceFirst` which is NOT `String.replaceFirst(oldString, newString)` with literal semantics in all cases. Actually, Kotlin `String.replaceFirst(String, String)` IS literal (not regex). HOWEVER, the `replace(oldString, newString)` for `replaceAll=true` is also literal. No issue here.
**Retracted:** On re-examination, Kotlin's `String.replaceFirst(String, String)` and `String.replace(String, String)` both use literal replacement. This is NOT a bug.

### C7 (revised). EditFileTool `writeViaDocument` replace-all backward-scan can infinite-loop when `newString` contains `oldString`

**File:** `builtin/EditFileTool.kt` (lines 182-190)
**Issue:** The backward replacement loop in `writeViaDocument` searches from end to beginning. When `replaceAll=true` and `newString` contains `oldString`, after each replacement the document text changes. The `lastIndexOf(oldString, offset - 1)` scan could find the newly-inserted text if the replacement shifted text into an earlier position. However, since it scans backward and `offset` decreases, this is mitigated. But: if `newString.length > oldString.length` AND the replacement inserts `oldString` at a position BEFORE the current offset, the loop could re-match it. Example: replacing "a" with "ba" -- after replacing last "a" at position 5, it becomes "ba" at position 5. Then `lastIndexOf("a", 4)` could find the "a" within the newly-inserted "ba" at position 6 (no, because offset decreased to 4). Actually, the offset logic `offset - 1` prevents re-scanning the same position. The issue is subtle but the current code is correct for most cases.
**Retracted.**

### C7 (final). CompileModuleTool callback can lose result if `invokeLater` and callback race

**File:** `ide/CompileModuleTool.kt`
**Issue:** `compiler.make(scope) { ... callback ... }` fires the callback on EDT when compilation finishes. But `invokeLater` is used to schedule the make call. If `cont.isCompleted` is already true (e.g., the coroutine was cancelled), the result is silently dropped. More critically, there's no timeout on the entire compile operation. If compilation hangs (broken build system), the agent hangs forever.
**Impact:** Agent coroutine hangs with no recourse.
**Fix:** Wrap the `suspendCancellableCoroutine` in `withTimeoutOrNull(120_000)`.

### C8. Multiple debug step tools call `session.stepOver()` / `session.resume()` etc. without EDT dispatch

**Files:** `debug/DebugStepOverTool.kt`, `DebugStepIntoTool.kt`, `DebugStepOutTool.kt`, `DebugResumeTool.kt`, `DebugPauseTool.kt`, `DebugStopTool.kt`
**Issue:** XDebugSession methods like `stepOver()`, `stepInto()`, `stepOut()`, `resume()`, `pause()`, `stop()` are documented to require EDT. The step tools delegate to `executeStep()` which calls `action(session)` (e.g., `session.stepOver(false)`) directly without `withContext(Dispatchers.EDT)`. The `DebugStopTool` also calls `session.stop()` without EDT dispatch. `DebugResumeTool` calls `session.resume()` off EDT.
**Impact:** Undefined behavior, potential `IllegalStateException` or corrupted debugger state.
**Fix:** Wrap all session action calls in `withContext(Dispatchers.EDT) { ... }`.

---

## HIGH: Significant issues affecting reliability

### H1. SearchCodeTool does recursive file I/O on the calling coroutine without cancellation checks

**File:** `builtin/SearchCodeTool.kt`
**Impact:** On large projects (100K+ files), `searchFiles` recursively walks the entire directory tree. There is no `yield()` or `isActive` check, so cancelling the agent session won't stop the search until it finishes naturally.
**Fix:** Add `if (!kotlinx.coroutines.isActive) return` at the top of the recursive function, or use Java NIO's `FileVisitResult` pattern with cancellation support.

### H2. SearchCodeTool reads every file fully into memory (`file.readLines`)

**File:** `builtin/SearchCodeTool.kt` (line 193)
**Impact:** For files just under the 1MB limit, reading all lines into memory can cause GC pressure. A 1MB file with many lines will allocate a large `List<String>`.
**Fix:** Use `bufferedReader().useLines` for streaming line-by-line matching.

### H3. GlobFilesTool `Files.walkFileTree` has no file count limit

**File:** `builtin/GlobFilesTool.kt`
**Impact:** In a project with millions of files (e.g., monorepo with node_modules not properly excluded), `walkFileTree` visits every file even though only `max_results` are returned. The walk continues until all files are visited.
**Fix:** Add an early exit when `matches.size >= maxResults * 2` (or some reasonable limit) to avoid scanning unnecessary files.

### H4. RunCommandTool `streamCallback` is a static mutable field -- not thread-safe across concurrent tool calls

**File:** `builtin/RunCommandTool.kt` (line 48)
**Impact:** `streamCallback` is a companion object property shared across all instances. If two `run_command` tools execute concurrently (parallel read-only execution), they share the same callback. The `currentToolCallId` is `ThreadLocal` which mitigates this for the tool call ID, but `streamCallback` itself could be overwritten between tool calls.
**Fix:** Pass the stream callback as a tool execution context parameter rather than a static field.

### H5. RunTestsTool shell fallback reads ALL stdout AFTER process completes -- can OOM

**File:** `ide/RunTestsTool.kt` (line 503)
**Issue:** `process.inputStream.bufferedReader().readText()` reads the entire output buffer AFTER `waitFor`. If the test produces gigabytes of output (e.g., verbose logging), this reads it all into memory at once. Additionally, reading after waitFor can deadlock on some platforms if the pipe buffer is full (though `redirectErrorStream(true)` mitigates this).
**Fix:** Stream output in a thread (like RunCommandTool does) or read with a cap.

### H6. EvaluateExpressionTool and GetVariablesTool reject `frame_index > 0` with a hard error

**Files:** `debug/EvaluateExpressionTool.kt` (line 77), `debug/GetVariablesTool.kt` (line 63)
**Issue:** Both tools advertise `frame_index` as a parameter but then return an error for any non-zero value with "Only the top frame (#0) is currently supported." This is misleading -- the parameter exists in the schema but doesn't work. The AgentDebugController's `evaluate` method also acknowledges this: it falls back to `currentStackFrame` for non-zero indices.
**Fix:** Either implement proper frame indexing (by storing XStackFrame references in `getStackFrames`) or remove the `frame_index` parameter from these tools' schemas.

### H7. GetRunOutputTool console text extraction is fragile -- relies on reflection to find `getText()`

**File:** `runtime/GetRunOutputTool.kt` (lines 139-169)
**Issue:** `extractConsoleText` tries to find a `getText()` method via reflection on the console component. IntelliJ's `ConsoleViewImpl` does not expose a simple `getText()` method on its Swing component. The `extractTextFromComponent` will almost always return `null`, making this tool useless for most console types.
**Impact:** Tool returns "console output is empty" even when there is visible output.
**Fix:** Use `ConsoleViewImpl.getText()` directly (cast to `ConsoleViewImpl` which does have this method), or use the `com.intellij.execution.process.ProcessHandler`'s captured output.

### H8. ListBreakpointsTool, RemoveBreakpointTool, GetDebugStateTool access XDebuggerManager without EDT

**Files:** `debug/ListBreakpointsTool.kt`, `debug/RemoveBreakpointTool.kt` (correctly uses EDT), `debug/GetDebugStateTool.kt`
**Issue:** `ListBreakpointsTool` directly calls `XDebuggerManager.getInstance(project).breakpointManager.allBreakpoints` without any threading guarantee. While read-only access to breakpoints is generally safe, accessing `XDebugSession` properties in `GetDebugStateTool` (e.g., `session.isStopped`, `session.currentPosition`, `session.suspendContext`) should ideally be done on EDT.
**Fix:** Wrap state reads in `withContext(Dispatchers.EDT)`.

### H9. FormatCodeTool and OptimizeImportsTool use `invokeAndWait` which can deadlock if already on EDT

**Files:** `ide/FormatCodeTool.kt`, `ide/OptimizeImportsTool.kt`
**Issue:** `ApplicationManager.getApplication().invokeAndWait { ... }` will deadlock if the caller is already on EDT. While the agent normally runs on IO dispatcher, edge cases (test environments, nested calls from EDT callbacks) could trigger this.
**Fix:** Use `invokeAndWaitIfNeeded` (which is already imported in EditFileTool but not used here).

### H10. FileStructureTool does not use PathValidator

**File:** `psi/FileStructureTool.kt` (line 40)
**Issue:** The tool constructs the path as `if (rawPath.startsWith("/")) rawPath else "${project.basePath}/$rawPath"` without using `PathValidator.resolveAndValidate`. This bypasses the path traversal security check that all other file-accessing tools use.
**Impact:** An LLM could potentially read file structures outside the project directory.
**Fix:** Use `PathValidator.resolveAndValidate(rawPath, project.basePath)`.

### H11. AddBreakpointTool does not use PathValidator

**File:** `debug/AddBreakpointTool.kt` (line 79)
**Issue:** Same as H10. Resolves path manually without traversal validation.

### H12. RemoveBreakpointTool does not use PathValidator

**File:** `debug/RemoveBreakpointTool.kt` (line 56)
**Issue:** Same as H10.

### H13. DebugRunToCursorTool does not use PathValidator

**File:** `debug/DebugRunToCursorTool.kt` (line 96)
**Issue:** Same as H10.

### H14. FindReferencesTool path resolution lacks PathValidator

**File:** `psi/FindReferencesTool.kt` (line 49)
**Issue:** The `filePath` parameter is resolved as `if (filePath.startsWith("/")) filePath else "${project.basePath}/$filePath"` without PathValidator.

---

## MEDIUM: Issues that degrade quality but don't break

### M1. ReadFileTool uses `readAction` (lowercase) which may not cancel properly

**File:** `builtin/ReadFileTool.kt` (line 75)
**Issue:** Uses `com.intellij.openapi.application.readAction { ... }` (coroutine-based) for reading Document text. This is correct for coroutine contexts but is a non-blocking read action that might retry on write conflicts. If the IDE is doing heavy indexing, this could take longer than expected.
**Severity:** Low risk, just noting the API choice.

### M2. SyntaxValidator only validates Java and Kotlin files

**File:** `builtin/SyntaxValidator.kt`
**Missing:** No validation for XML, Groovy (`build.gradle`), SQL, or other common file types. Edits to these files skip the syntax gate entirely.

### M3. PsiToolUtils Kotlin formatting uses extensive reflection

**File:** `psi/PsiToolUtils.kt` (lines 115-370)
**Issue:** All Kotlin PSI access uses `Class.forName` + `getMethod` + `invoke` reflection. While this avoids compile-time dependency on the Kotlin plugin, it's brittle across Kotlin plugin version changes. Any method rename in the Kotlin PSI API will silently break formatting with `null` returns.
**Mitigation:** The code catches exceptions and returns null/empty. This is the right pattern for optional plugin dependencies.

### M4. CallHierarchyTool uses `PsiToolUtils.findClass` (not `findClassAnywhere`) for className

**File:** `psi/CallHierarchyTool.kt` (line 150)
**Issue:** `findMethod` calls `PsiToolUtils.findClass` which only uses `JavaPsiFacade`. Kotlin-only classes won't be found. Other tools (FindDefinitionTool, GetMethodBodyTool) correctly use `findClassAnywhere`.
**Fix:** Change to `PsiToolUtils.findClassAnywhere`.

### M5. FindImplementationsTool only finds Java method overrides, not Kotlin overrides

**File:** `psi/FindImplementationsTool.kt`
**Issue:** `OverridingMethodsSearch.search` operates on `PsiMethod`. For Kotlin functions that aren't backed by a `PsiMethod` light class, overrides may be missed.
**Mitigation:** Kotlin plugin generates light classes for most declarations, so this should work in practice. Edge cases exist for extension functions and top-level functions.

### M6. SpringConfigTool YAML parser is naive -- doesn't handle arrays, multi-line values, or anchors

**File:** `framework/SpringConfigTool.kt` (lines 132-162)
**Issue:** The simple line-by-line YAML parser doesn't handle: YAML arrays (`- item`), multi-line strings (`|`, `>`), anchors/aliases (`&`, `*`), flow syntax (`{key: value}`), or comments after values.
**Impact:** Properties will be missing or incorrectly parsed in complex YAML configs.
**Fix:** Use SnakeYAML or Jackson YAML if available on classpath, or document the limitation.

### M7. GradleDependenciesTool `dependencies {}` regex doesn't handle nested blocks properly

**File:** `framework/GradleDependenciesTool.kt` (line 182)
**Issue:** The regex `dependencies\s*\{([^}]*(?:\{[^}]*\}[^}])*)\}` handles one level of nested braces but not deeper nesting (e.g., `dependencies { configurations.all { ... } }` with three levels). Also, Kotlin DSL build scripts with complex expressions inside dependency declarations (e.g., `constraints { ... }`) may confuse the parser.

### M8. RunCommandTool blocks remote refs (`origin/`, `upstream/`) in all git commands -- overly restrictive

**File:** `builtin/RunCommandTool.kt` (line 121)
**Issue:** `git log origin/main..HEAD` and `git diff origin/main...HEAD` are common read-only operations that are blocked. This forces the agent to use only local refs, which may be stale.
**Fix:** Allow remote refs in read-only git commands (log, diff, show, rev-list, merge-base).

### M9. RunCommandTool `ALLOWED_PREFIXES` is prefix-based but can be bypassed

**File:** `builtin/RunCommandTool.kt`
**Issue:** Commands like `rm -rf ./src` are not hard-blocked (not matching any `HARD_BLOCKED` pattern) and not on the allowed list. They fall through to the "non-allowlisted" path which relies on ApprovalGate. The safety depends entirely on the approval mechanism working. `rm` by itself with project-scoped paths is intentionally not blocked, but this should be documented.

### M10. SemanticDiagnosticsTool only supports `.kt` and `.java` files

**File:** `ide/SemanticDiagnosticsTool.kt` (line 43)
**Issue:** Returns "Semantic diagnostics only available for .kt and .java files" for XML, Groovy, SQL, etc. For an agent editing `build.gradle.kts`, there's no way to check for errors after editing.

### M11. GetRunOutputTool regex filter compilation happens before null-checking the pattern variable

**File:** `runtime/GetRunOutputTool.kt` (lines 48-53)
**Issue:** Minor -- the regex is compiled correctly and errors are handled. No actual bug.
**Retracted.**

### M12. CompileModuleTool error messages don't include line numbers

**File:** `ide/CompileModuleTool.kt` (line 99)
**Issue:** Compiler error messages show `$file: ${msg.message}` but don't include `msg.line` or `msg.column`, making it harder for the LLM to locate and fix errors.
**Fix:** Add line/column: `"$file:${msg.line}: ${msg.message}"`.

### M13. RunTestsTool native runner's `findTestRoot` uses reflection -- fragile across IDE versions

**File:** `ide/RunTestsTool.kt` (lines 322-332)
**Issue:** Uses `console.javaClass.methods.find { it.name == "getResultsViewer" }` and `viewer.javaClass.methods.find { it.name == "getTestsRootNode" }`. These method names could change in future IDE versions.

### M14. GetRunConfigurationsTool extracts config properties via reflection -- silent failures

**File:** `runtime/GetRunConfigurationsTool.kt` (lines 99-147)
**Issue:** All property extraction (main class, module, VM options, env vars) uses reflection. If reflection fails, the property is silently omitted. The user won't know why information is missing.

### M15. CreateRunConfigTool JUnit configuration uses field access via reflection -- extremely fragile

**File:** `config/CreateRunConfigTool.kt` (lines 340-364)
**Issue:** Accesses `TEST_OBJECT` and `MAIN_CLASS_NAME` fields on JUnit's `JUnitConfiguration.Data` class via `getField()`. Public fields in the JUnit plugin could be renamed or removed without notice.

### M16. Multiple Spring PSI tools scan entire project scope -- slow on large codebases

**Files:** `psi/SpringEndpointsTool.kt`, `psi/SpringContextTool.kt`, `psi/SpringBeanGraphTool.kt`, etc.
**Issue:** `AnnotatedElementsSearch.searchPsiClasses` across `GlobalSearchScope.projectScope` can take 5-15 seconds on large projects with many classes. No progress indication or cancellation support.
**Fix:** Add timeout via `withTimeoutOrNull` around the `ReadAction.nonBlocking { ... }.executeSynchronously()` call.

### M17. TypeHierarchyTool's `collectSupertypes` recursive traversal has no depth limit

**File:** `psi/TypeHierarchyTool.kt` (lines 75-86)
**Issue:** The `visited` set prevents infinite loops from cycles, but deep class hierarchies (e.g., complex Spring framework classes with 10+ supertypes) will produce very verbose output. No depth limit.
**Fix:** Add a `maxDepth` parameter (default 5).

### M18. EditFileTool does not create parent directories for new files

**File:** `builtin/EditFileTool.kt`
**Missing:** The tool only replaces content in existing files. There's no `create_file` tool. If the agent needs to create a new file, it must use `run_command` with `mkdir -p && cat > file`, which is inelegant. However, this is by design -- `edit_file` is for editing, not creating.

---

## LOW: Minor improvements

### L1. ReadFileTool default limit of 200 lines is small for many use cases

**File:** `builtin/ReadFileTool.kt`
**Suggestion:** Consider 500 lines as default. The LLM often needs more context, and 200 lines of a Kotlin file covers only a few classes.

### L2. SearchCodeTool `prevPath` variable is assigned but never used

**File:** `builtin/SearchCodeTool.kt` (line 140)
**Issue:** `prevPath = match.relativePath` is a dead assignment. `prevPath` is never read.
**Fix:** Remove the variable.

### L3. PathValidator allows absolute paths outside the project if they resolve to a path starting with the project path prefix

**File:** `builtin/PathValidator.kt`
**Issue:** `canonical.startsWith(projectCanonical + File.separator)` -- if `projectCanonical` is `/home/user/project` and someone passes `/home/user/project-other/...`, it won't match because of the `File.separator` check. This is correct behavior, just noting it works properly.

### L4. TokenEstimator is called for output but not for error messages -- minor inconsistency

**Various files**
**Issue:** Error results use `ToolResult.ERROR_TOKEN_ESTIMATE` (hardcoded 5) while successful results use `TokenEstimator.estimate(content)`. Error messages could have more than 5 tokens.
**Impact:** Negligible -- token estimates are approximate.

### L5. DeleteRunConfigTool safety check uses string prefix matching which could be spoofed

**File:** `config/DeleteRunConfigTool.kt` (line 49)
**Issue:** `configName.startsWith("[Agent]")` -- an LLM could rename a user config to start with `[Agent]` using `modify_run_config` (which doesn't enforce the prefix), then delete it.
**Impact:** Low -- requires deliberate multi-step attack by the LLM.

### L6. debug_step_over/into/out don't verify session is on EDT before calling step methods

**See C8 above** -- this is the same issue but re-listed for completeness per tool.

### L7. DebugStopTool calls `session.stop()` but doesn't wait for actual termination

**File:** `debug/DebugStopTool.kt`
**Issue:** Returns success immediately after `stop()`, but the process may still be running. A subsequent `start_debug_session` on the same port could fail.
**Fix:** Add a brief `delay(500)` or poll `session.isStopped`.

### L8. Spring tools don't indicate which ones require IntelliJ Ultimate vs Community

**Various Spring tools**
**Issue:** `SpringContextTool` requires the Spring plugin (Ultimate), while `SpringEndpointsTool` and `SpringConfigTool` work via PSI annotation scanning (Community). The descriptions don't clarify this.

### L9. RunCommandTool `HARD_BLOCKED` patterns don't cover `rm -rf *` (without leading `/`)

**File:** `builtin/RunCommandTool.kt`
**Issue:** `rm -rf .` or `rm -rf *` in the project directory is not hard-blocked. It would be caught by ApprovalGate as a non-allowlisted command, but a more aggressive `rm` pattern would be safer.

### L10. GetRunningProcessesTool `extractProcessName` returns `handler.toString()` which is unhelpful

**File:** `runtime/GetRunningProcessesTool.kt` (line 91)
**Issue:** `ProcessHandler.toString()` typically returns the class name and hash code, not a useful process description.
**Fix:** Try `handler.commandLine` or similar before falling back to `toString()`.

### L11. FindDefinitionTool doesn't handle Kotlin-only symbols well when using `#` separator

**File:** `psi/FindDefinitionTool.kt`
**Issue:** The `symbol.split('#', '.')` approach works for Java conventions but Kotlin extension functions and top-level functions use different naming patterns.

### L12. AgentDebugController `removeAgentBreakpoints()` (no-arg overload) only clears the tracking set

**File:** `debug/AgentDebugController.kt` (line 330)
**Issue:** The no-arg `removeAgentBreakpoints()` only calls `agentBreakpoints.clear()` but doesn't actually remove the breakpoints from the IDE. The overload with `XDebuggerManager` parameter does the proper removal. If the no-arg version is called, breakpoints remain in the IDE but the controller loses track of them.
**Fix:** Mark the no-arg version `@Deprecated` or remove it, or always pass `XDebuggerManager`.

---

## Cross-Cutting Concerns

### Cancellation Support

Most tools lack coroutine cancellation support. When the user stops the agent mid-execution:
- `SearchCodeTool` and `GlobFilesTool` will continue scanning files until completion
- `RunInspectionsTool` and `ListQuickfixesTool` will continue inspecting until completion
- Debug tools' `suspendCancellableCoroutine` callbacks could resume after cancellation
- `RunTestsTool` native runner timers are not cancelled

**Recommendation:** Add `ensureActive()` or `yield()` calls in long-running loops. Use `cont.invokeOnCancellation` for all `suspendCancellableCoroutine` usages.

### Timeout Handling

| Tool | Has Timeout | Notes |
|------|-------------|-------|
| run_command | Yes (120s default, 600s max) | Properly enforced |
| run_tests | Yes (120s default) | Shell fallback has timeout; native runner uses coroutine timeout |
| compile_module | No | Can hang indefinitely |
| start_debug_session | No | Can hang indefinitely (C3) |
| debug_run_to_cursor | Implicit (30s waitForPause) | Run-to-cursor itself has no timeout |
| evaluate_expression | No | Hangs if evaluator doesn't respond |
| get_variables | No | Hangs if debuggee unresponsive (C6) |
| All PSI tools | No | `executeSynchronously()` blocks until smart mode, which could be minutes |
| All Spring PSI tools | No | Project-wide scans can take 10+ seconds |

### Multi-Module Project Support

Most tools handle multi-module projects correctly:
- `SearchCodeTool` and `GlobFilesTool` search from project root recursively
- `ProjectModulesTool` lists all modules
- `GradleDependenciesTool` discovers submodules from settings.gradle
- `CompileModuleTool` supports optional `module` parameter

**Gap:** `RunTestsTool` shell fallback looks for `pom.xml` / `build.gradle` only in the project root. Multi-module Maven/Gradle projects often need `-pl module` or `--tests` with module qualification. The tool doesn't detect which module contains the test class.

### Output Quality

Tool outputs are generally well-structured with:
- Clear error messages with actionable suggestions (e.g., "Use get_run_configurations to list available configs")
- Token-cap truncation on large outputs
- Summary strings for context management
- Line numbers and file paths for navigation

**Gaps:**
- Debug tools return positions as absolute paths instead of project-relative paths (inconsistent with other tools)
- `GetRunOutputTool` often returns empty output due to H7

### Security

PathValidator is consistently used in builtin tools but **missing from debug tools (H10-H13) and some PSI tools (H14)**. The file structure tool and find_definition tool construct paths without validation.

---

## Summary of Recommended Fixes by Priority

### Must Fix Before Production
1. **C3** -- StartDebugSessionTool: Add timeout to prevent permanent EDT freeze
2. **C8** -- Debug step/resume/stop tools: Add EDT dispatch
3. **C1/C2** -- RunInspectionsTool, ListQuickfixesTool, SemanticDiagnosticsTool: Migrate to `ReadAction.nonBlocking`
4. **C4** -- RunTestsTool: Fix timer leak and continuation safety
5. **C6** -- AgentDebugController: Add timeouts to variable/presentation resolution

### Should Fix Soon
6. **H7** -- GetRunOutputTool: Fix console text extraction
7. **H9** -- FormatCodeTool, OptimizeImportsTool: Use `invokeAndWaitIfNeeded`
8. **H10-H14** -- Add PathValidator to debug tools, FileStructureTool, FindReferencesTool
9. **H4** -- RunCommandTool: Make streamCallback non-static
10. **H6** -- EvaluateExpressionTool, GetVariablesTool: Remove or implement frame_index

### Nice to Have
11. **M4** -- CallHierarchyTool: Use `findClassAnywhere`
12. **M8** -- RunCommandTool: Allow remote refs in read-only git commands
13. **M12** -- CompileModuleTool: Include line numbers in errors
14. **L2** -- SearchCodeTool: Remove dead variable
15. **L10** -- GetRunningProcessesTool: Improve process name extraction

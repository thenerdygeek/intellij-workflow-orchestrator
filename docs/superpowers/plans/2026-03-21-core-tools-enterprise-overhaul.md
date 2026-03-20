# Core Tools Enterprise-Grade Overhaul — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Overhaul the 4 core agent tools (search_code, read_file, edit_file, run_command) to match enterprise-grade standards from Claude Code, Codex, and Cursor. Add a new glob_files tool. Fix critical gaps: search output modes, .gitignore respect, file type filters, read-before-edit enforcement, command output truncation, and result pagination.

**Architecture:** Enhance existing tools in-place (no new tool classes except GlobFilesTool). Each tool gains parameters from the Claude Code research. Search gets 3 output modes (files/content/count) + context lines + file type filter + .gitignore respect. Read gains line truncation + binary detection. Edit gains read-before-edit enforcement via WorkingSet. Run gains configurable timeout + output limit increase + background flag.

**Tech Stack:** Kotlin 2.1.10, IntelliJ Platform, Java ProcessBuilder, regex, kotlinx.serialization

**Research:** `docs/superpowers/research/2026-03-21-core-tool-implementations.md`

---

## File Structure

### New Files
| File | Responsibility |
|------|---------------|
| `agent/.../tools/builtin/GlobFilesTool.kt` | File discovery by glob pattern (e.g., `**/*.kt`) |
| `agent/src/test/.../tools/builtin/GlobFilesToolTest.kt` | Glob tool tests |

### Modified Files
| File | Change |
|------|--------|
| `agent/.../tools/builtin/SearchCodeTool.kt` | Add output_mode, context lines, file_type filter, case_insensitive, .gitignore/.workflow exclusion |
| `agent/.../tools/builtin/ReadFileTool.kt` | Add line truncation (2000 chars/line), binary detection, file size warning |
| `agent/.../tools/builtin/EditFileTool.kt` | Add read-before-edit enforcement via WorkingSet, replace_all param |
| `agent/.../tools/builtin/RunCommandTool.kt` | Increase output limit, add description param, configurable timeout |
| `agent/.../AgentService.kt` | Register GlobFilesTool |
| `agent/.../tools/ToolCategoryRegistry.kt` | Add glob_files to core category |
| `agent/.../tools/DynamicToolSelector.kt` | Add glob_files to ALWAYS_INCLUDE, update tool count |
| `agent/src/test/.../tools/builtin/SearchCodeToolTest.kt` | Update tests for new parameters |
| `agent/src/test/.../tools/builtin/ReadFileToolTest.kt` | Update tests for new features |
| `agent/src/test/.../tools/builtin/EditFileToolTest.kt` | Add read-before-edit tests |

---

## Task 1: SearchCodeTool — Enterprise Overhaul

The biggest change. Add 3 output modes (like Claude Code's Grep), context lines, file type filter, case-insensitive flag, and .gitignore respect.

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SearchCodeTool.kt`
- Modify: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/SearchCodeToolTest.kt`

- [ ] **Step 1: Update SearchCodeTool parameters**

New parameter set (matching Claude Code's Grep):

```kotlin
override val parameters = FunctionParameters(
    properties = mapOf(
        "pattern" to ParameterProperty(type = "string", description = "Search string or regex pattern"),
        "path" to ParameterProperty(type = "string", description = "File or directory to search. Defaults to project root."),
        "output_mode" to ParameterProperty(type = "string", description = "Output mode: 'files' (file paths only, default), 'content' (matching lines with context), 'count' (match counts per file)"),
        "file_type" to ParameterProperty(type = "string", description = "File extension filter (e.g., 'kt', 'java', 'xml'). Only search files with this extension."),
        "case_insensitive" to ParameterProperty(type = "boolean", description = "Case-insensitive search. Default: false."),
        "context_lines" to ParameterProperty(type = "integer", description = "Number of lines before and after each match to include (only with output_mode='content'). Default: 0."),
        "max_results" to ParameterProperty(type = "integer", description = "Maximum matches to return. Default: 50.")
    ),
    required = listOf("pattern")
)
```

Rename `query` → `pattern` (keep `query` as fallback for backward compat).

- [ ] **Step 2: Add .gitignore and .workflow exclusion**

Update `SKIP_DIRS` to include `.workflow`:
```kotlin
private val SKIP_DIRS = setOf(
    ".git", ".idea", "node_modules", "target", "build", ".gradle", ".worktrees", ".workflow"
)
```

Add `.gitignore` parsing:
```kotlin
private fun loadGitignorePatterns(basePath: String): Set<Regex> {
    val gitignore = File(basePath, ".gitignore")
    if (!gitignore.exists()) return emptySet()
    return gitignore.readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .mapNotNull { pattern ->
            try {
                val regexPattern = pattern.trim()
                    .replace(".", "\\.")
                    .replace("**/", "(.*/)?")
                    .replace("*", "[^/]*")
                    .replace("?", "[^/]")
                Regex(regexPattern)
            } catch (_: Exception) { null }
        }.toSet()
}
```

Check each file against gitignore patterns before reading.

- [ ] **Step 3: Implement 3 output modes**

```kotlin
val outputMode = params["output_mode"]?.jsonPrimitive?.content ?: "files"

when (outputMode) {
    "files" -> {
        // Return only file paths that contain matches (deduplicated)
        val matchingFiles = matches.map { it.substringBefore(":") }.distinct()
        content = matchingFiles.joinToString("\n")
        summary = "${matchingFiles.size} files match '$pattern'"
    }
    "content" -> {
        // Return matching lines with optional context
        // Format: path:lineNum: content (with context lines if specified)
        content = matches.joinToString("\n")
        summary = "Found ${matches.size} matches for '$pattern'"
    }
    "count" -> {
        // Return match count per file
        val counts = matches.groupBy { it.substringBefore(":") }
            .map { (file, lines) -> "$file: ${lines.size} matches" }
        content = counts.joinToString("\n")
        summary = "${matches.size} matches across ${counts.size} files for '$pattern'"
    }
}
```

- [ ] **Step 4: Implement context lines**

For `output_mode = "content"` with `context_lines > 0`:

```kotlin
// When a match is found, include N lines before and after
if (regex.containsMatchIn(line)) {
    val start = maxOf(0, lineIdx - contextLines)
    val end = minOf(lines.size - 1, lineIdx + contextLines)
    for (ctxIdx in start..end) {
        val prefix = if (ctxIdx == lineIdx) ">" else " "
        contextMatches.add("$relativePath:${ctxIdx + 1}:$prefix ${lines[ctxIdx]}")
    }
    contextMatches.add("---")  // separator between match groups
}
```

- [ ] **Step 5: Implement file_type filter and case_insensitive**

```kotlin
val fileType = params["file_type"]?.jsonPrimitive?.content
val caseInsensitive = params["case_insensitive"]?.jsonPrimitive?.boolean ?: false

// File type filter
if (fileType != null && file.extension.lowercase() != fileType.lowercase()) continue

// Case insensitive regex
val regexOpts = if (caseInsensitive) setOf(RegexOption.IGNORE_CASE) else emptySet()
val regex = try {
    Regex(pattern, regexOpts)
} catch (_: Exception) {
    Regex(Regex.escape(pattern), regexOpts)
}
```

- [ ] **Step 6: Update tests**

Add tests for:
- `output_mode = "files"` returns only file paths
- `output_mode = "count"` returns counts per file
- `context_lines = 2` shows surrounding lines
- `file_type = "kt"` filters non-Kotlin files
- `case_insensitive = true` matches regardless of case
- `.workflow` directory excluded from results
- `.gitignore` patterns respected

- [ ] **Step 7: Verify and commit**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.builtin.SearchCodeToolTest" --rerun --no-build-cache
git commit -m "feat(agent): search_code enterprise overhaul — 3 output modes, context lines, file type filter, .gitignore respect"
```

---

## Task 2: GlobFilesTool — File Discovery by Pattern

New tool for finding files by name/pattern (like Claude Code's Glob). Separate from search_code which finds content.

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/GlobFilesTool.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/GlobFilesToolTest.kt`

- [ ] **Step 1: Write tests**

```kotlin
class GlobFilesToolTest {
    @Test fun `finds kotlin files with glob pattern`()
    @Test fun `returns files sorted by modification time`()
    @Test fun `respects max_results limit`()
    @Test fun `returns error when pattern is missing`()
    @Test fun `skips excluded directories`()
}
```

- [ ] **Step 2: Implement GlobFilesTool**

```kotlin
class GlobFilesTool : AgentTool {
    override val name = "glob_files"
    override val description = "Find files by name pattern. Use for file discovery — 'what files exist matching X?'. Returns file paths sorted by modification time (newest first). For searching file CONTENTS, use search_code instead."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "pattern" to ParameterProperty(type = "string", description = "Glob pattern (e.g., '**/*.kt', 'src/**/*.java', '*.xml')"),
            "path" to ParameterProperty(type = "string", description = "Directory to search. Defaults to project root."),
            "max_results" to ParameterProperty(type = "integer", description = "Maximum files to return. Default: 50.")
        ),
        required = listOf("pattern")
    )
    allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER)
}
```

Implementation:
- Uses `java.nio.file.PathMatcher` with `"glob:$pattern"`
- Walks file tree with `Files.walk()`
- Skips SKIP_DIRS (same list as SearchCodeTool)
- Sorts results by `Files.getLastModifiedTime()` (newest first)
- Returns relative paths, one per line
- Summary: "Found N files matching 'pattern'"

- [ ] **Step 3: Verify and commit**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.builtin.GlobFilesToolTest" --rerun --no-build-cache
git commit -m "feat(agent): glob_files tool — file discovery by pattern, sorted by modification time"
```

---

## Task 3: ReadFileTool — Line Truncation + Binary Detection

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/ReadFileTool.kt`
- Modify: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/ReadFileToolTest.kt`

- [ ] **Step 1: Add line truncation and binary detection**

```kotlin
companion object {
    private const val DEFAULT_LIMIT = 200
    private const val MAX_LINE_CHARS = 2000  // Truncate long lines (Claude Code pattern)
    private const val MAX_FILE_SIZE = 10_000_000  // 10MB — reject binary/huge files
    private val BINARY_EXTENSIONS = setOf("jar", "class", "png", "jpg", "jpeg", "gif", "ico", "svg",
        "zip", "tar", "gz", "war", "ear", "so", "dll", "exe", "pdf", "woff", "woff2", "ttf", "eot")
}
```

In execute:
```kotlin
// Binary detection
if (file.extension.lowercase() in BINARY_EXTENSIONS) {
    return ToolResult("Error: '${file.name}' is a binary file and cannot be read as text.",
        "Binary file", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
}

// Size check
if (file.length() > MAX_FILE_SIZE) {
    return ToolResult("Error: '${file.name}' is ${file.length() / 1_000_000}MB — too large. Use search_code to find specific content.",
        "File too large", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
}

// Line truncation
val truncatedLine = if (line.length > MAX_LINE_CHARS) line.take(MAX_LINE_CHARS) + " [truncated]" else line
```

- [ ] **Step 2: Record read in WorkingSet**

After successful read, record in the session's WorkingSet (for read-before-edit enforcement in Task 4):

The tool currently doesn't have access to the session's WorkingSet. We need to pass it through. Option: read tracking via AgentService (set of read file paths per session). Simpler: the SingleAgentSession already tracks read files via AgentController progress callbacks. So the WorkingSet is already updated externally — no change needed in ReadFileTool itself.

- [ ] **Step 3: Update tests and commit**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.builtin.ReadFileToolTest" --rerun --no-build-cache
git commit -m "feat(agent): read_file — binary detection, line truncation (2000 chars), file size limit (10MB)"
```

---

## Task 4: EditFileTool — Read-Before-Edit Enforcement + replace_all

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/EditFileTool.kt`
- Modify: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/EditFileToolTest.kt`

- [ ] **Step 1: Add replace_all parameter**

```kotlin
"replace_all" to ParameterProperty(type = "boolean", description = "Replace all occurrences of old_string. Default: false (requires unique match).")
```

When `replace_all = true`:
```kotlin
if (replaceAll) {
    newContent = content.replace(oldString, newString)
    val count = countOccurrences(content, oldString)
    // Success: replaced N occurrences
} else {
    // Existing uniqueness check
}
```

- [ ] **Step 2: Add read-before-edit warning**

Check if the file was recently read via AgentService's WorkingSet tracking. If not, add a warning (not a hard block — the LLM might have context from search results):

```kotlin
// Soft warning if file wasn't explicitly read (Claude Code enforces this strictly,
// but we use a softer approach since search results provide some context)
val agentService = try { AgentService.getInstance(project) } catch (_: Exception) { null }
val workingSet = agentService?.activeController?.session?.workingSet
val wasRead = workingSet?.getFiles()?.any { it.path.endsWith(File(resolvedPath).name) } ?: true
val readWarning = if (!wasRead) {
    "\nWarning: This file was not explicitly read before editing. Consider reading it first to verify context."
} else ""
```

- [ ] **Step 3: Update tests and commit**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.builtin.EditFileToolTest" --rerun --no-build-cache
git commit -m "feat(agent): edit_file — replace_all parameter, read-before-edit warning"
```

---

## Task 5: RunCommandTool — Increased Output + Configurable Timeout + Description

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/RunCommandTool.kt`

- [ ] **Step 1: Increase output limit and add parameters**

```kotlin
companion object {
    private const val DEFAULT_TIMEOUT_SECONDS = 120L  // was 60
    private const val MAX_TIMEOUT_SECONDS = 600L      // 10 minutes max
    private const val MAX_OUTPUT_CHARS = 30_000        // was 4000 — match Claude Code
}

// New parameters:
"description" to ParameterProperty(type = "string", description = "Brief description of what this command does (for logging/UI)")
"timeout" to ParameterProperty(type = "integer", description = "Timeout in seconds. Default: 120, max: 600.")
```

- [ ] **Step 2: Use configurable timeout**

```kotlin
val timeoutSeconds = (params["timeout"]?.jsonPrimitive?.int?.toLong() ?: DEFAULT_TIMEOUT_SECONDS)
    .coerceIn(1, MAX_TIMEOUT_SECONDS)
val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
```

- [ ] **Step 3: Truncate with clear indicator**

When output exceeds 30K chars:
```kotlin
if (output.length > MAX_OUTPUT_CHARS) {
    val truncated = output.take(MAX_OUTPUT_CHARS)
    content = "$truncated\n\n[Output truncated at ${MAX_OUTPUT_CHARS} characters. ${output.length - MAX_OUTPUT_CHARS} characters omitted.]"
}
```

- [ ] **Step 4: Commit**

```bash
git commit -m "feat(agent): run_command — 30K output limit, configurable timeout (120s default, 600s max), description param"
```

---

## Task 6: Register GlobFilesTool + Update Categories

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ToolCategoryRegistry.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/DynamicToolSelector.kt`

- [ ] **Step 1: Register in AgentService**

```kotlin
register(GlobFilesTool())
```

- [ ] **Step 2: Add to core category in ToolCategoryRegistry**

Add `"glob_files"` to the core tools list. Update comment to 53 tools.

- [ ] **Step 3: Add to DynamicToolSelector**

Add `"glob_files"` to `ALWAYS_INCLUDE`. Add keyword trigger:
```kotlin
"find file" to setOf("glob_files"),
"list files" to setOf("glob_files"),
"what files" to setOf("glob_files"),
```

- [ ] **Step 4: Verify and commit**

```bash
./gradlew :agent:clean :agent:test --rerun --no-build-cache
git commit -m "feat(agent): register glob_files, update to 53 tools"
```

---

## Task 7: Final Verification + Documentation

- [ ] **Step 1: Run full test suite**

```bash
./gradlew :agent:clean :agent:test --rerun --no-build-cache
```

- [ ] **Step 2: Plugin verification**

```bash
./gradlew verifyPlugin
```

- [ ] **Step 3: Update agent/CLAUDE.md**

Update tool count to 53. Update core tools description with new features. Add glob_files to tools table.

- [ ] **Step 4: Commit**

```bash
git commit -m "docs(agent): update CLAUDE.md for 53 tools with enterprise-grade core tool features"
```

---

## Implementation Order

```
Task 1: SearchCodeTool overhaul (biggest change)     ← independent
Task 2: GlobFilesTool (new tool)                      ← independent
Task 3: ReadFileTool enhancements                     ← independent
Task 4: EditFileTool (replace_all + read warning)     ← independent
Task 5: RunCommandTool (output + timeout)             ← independent
Task 6: Register + wire glob_files                    ← depends on Task 2
Task 7: Final verification + docs                     ← depends on all
```

All tasks 1-5 are independent and can be done in any order.

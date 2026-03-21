# IntelliJ-Native APIs vs Basic File Operations for AI Agent Tools

**Date:** 2026-03-21
**Status:** Research complete
**Purpose:** Evaluate whether our agent tools should use IntelliJ Platform indexed/native APIs instead of basic filesystem operations

---

## Executive Summary

Our current agent tools (SearchCodeTool, ReadFileTool, EditFileTool, RunCommandTool) use basic `java.io.File` operations — filesystem walks, `File.readLines()`, `File.writeText()`, `ProcessBuilder`. IntelliJ Platform provides indexed, cached, undo-aware alternatives that are significantly faster for large projects and integrate properly with the IDE's data model.

**Key finding:** The most impactful upgrade is EditFileTool. Using `File.writeText()` bypasses IntelliJ's undo system, Document model, and VFS — meaning the IDE may not see changes until a manual refresh, and users cannot Ctrl+Z agent edits. SearchCodeTool also has a major upgrade path via index-based search.

---

## 1. Code Search

### Current Implementation (SearchCodeTool)
```
java.io.File walk → readLines() → Regex match per line
```
- Walks the entire filesystem tree recursively
- Reads every file into memory to regex-match
- Hardcoded SKIP_DIRS: .git, .idea, node_modules, target, build, .gradle, .worktrees
- Hardcoded BINARY_EXTENSIONS filter
- Skips files > 1MB
- O(n) where n = all files in project

### IntelliJ-Native Alternatives

#### Option A: PsiSearchHelper + CacheManager (Index-Based Word Search)
```kotlin
val helper = PsiSearchHelper.getInstance(project)
val scope = GlobalSearchScope.projectScope(project)

// Find files containing a word (uses the word index — O(1) lookup)
helper.processFilesWithWord(scope, "searchTerm", UsageSearchContext.IN_CODE.toShort(), true) { psiFile ->
    // Process matching file
    true // continue
}

// Or via CacheManager directly for more control over search context:
val cacheManager = CacheManager.getInstance(project)
val files = cacheManager.getFilesWithWord("searchTerm", UsageSearchContext.ANY.toShort(), scope, true)
```

**Pros:**
- Uses IntelliJ's pre-built word index — no filesystem walk needed
- O(1) index lookup instead of O(n) file scan
- Automatically respects project scope (content roots, excludes)
- Already indexed when IDE opens — instant results

**Cons:**
- Word-based only — cannot do arbitrary regex across the index
- Requires `ReadAction` (or `readAction {}` coroutine variant)
- Unavailable in dumb mode (during indexing)
- Only finds words that are in the index; custom patterns need post-filtering

#### Option B: FindManager + FindModel (IntelliJ's "Find in Path")
```kotlin
val findManager = FindManager.getInstance(project)
val model = FindModel().apply {
    stringToFind = "searchPattern"
    isRegularExpressions = true  // supports regex!
    isCaseSensitive = true
    isWholeWordsOnly = false
    // Scope configuration
    directoryName = project.basePath
    isWithSubdirectories = true
    // File filter
    fileFilter = "*.kt,*.java"
}

// Find all occurrences
FindInProjectUtil.findUsages(model, project, { usage ->
    val file = usage.file?.path
    val line = usage.line
    // Process match
    true
}, ProcessInfo.DEFAULT)
```

**Pros:**
- Full regex support
- Uses IntelliJ's optimized search (combines index pre-filter + regex post-filter)
- Respects project scope and file type filters
- Handles encoding automatically
- Same engine as Ctrl+Shift+F

**Cons:**
- `FindInProjectUtil` is in `lang-impl` (implementation detail, not stable API)
- UI-oriented API — may show progress bars or dialogs if not careful
- Threading: must be called carefully (some parts expect EDT)

#### Option C: FileBasedIndex Direct Access
```kotlin
// Search the word index directly
FileBasedIndex.getInstance().processValues(
    IdIndex.NAME,
    IdIndexEntry("searchWord", true),
    null, // all files
    { file, value -> /* process */ true },
    GlobalSearchScope.projectScope(project)
)
```

**Pros:** Maximum control over index access
**Cons:** Very low-level, complex API, word-based only

### Recommendation for SearchCodeTool

**Hybrid approach:**
1. Use `PsiSearchHelper.processFilesWithWord()` for word-based searches (most agent queries are word-based: class names, method names, variable names)
2. For regex patterns, use `FindManager` with `FindModel` (regex=true)
3. Fall back to current filesystem walk if in dumb mode (`DumbService.isDumb(project)`)

**Threading:** Wrap in `readAction {}` (Kotlin coroutine variant) or `ReadAction.nonBlocking { }.inSmartMode(project).executeSynchronously()`

---

## 2. File Discovery (Glob/List Files)

### Current Implementation
No dedicated glob tool exists. SearchCodeTool walks `java.io.File` tree. The `run_command` tool with `find` or `ls` is the fallback.

### IntelliJ-Native Alternatives

#### FilenameIndex (Find Files by Name or Extension)
```kotlin
// By exact name — O(1) index lookup
val files = FilenameIndex.getVirtualFilesByName("build.gradle.kts", GlobalSearchScope.projectScope(project))

// By extension — O(1) index lookup
val kotlinFiles = FilenameIndex.getAllFilesByExt(project, "kt")

// With scope filtering
val kotlinInModule = FilenameIndex.getAllFilesByExt(project, "kt", GlobalSearchScope.moduleScope(module))
```

**Performance:** Index-based. No filesystem walk. Returns `Collection<VirtualFile>`.

#### FileTypeIndex (Find Files by FileType)
```kotlin
val javaFiles = FileBasedIndex.getInstance().getContainingFiles(
    FileTypeIndex.NAME,
    JavaFileType.INSTANCE,
    GlobalSearchScope.projectScope(project)
)
```

#### ProjectFileIndex (Iterate Project Content)
```kotlin
val projectFileIndex = ProjectFileIndex.getInstance(project)

// Iterate all content files
projectFileIndex.iterateContent { virtualFile ->
    // virtualFile is in project content (not excluded, not library)
    true // continue
}

// Check individual files
projectFileIndex.isInContent(virtualFile)    // true if in content root
projectFileIndex.isExcluded(virtualFile)     // true if excluded
projectFileIndex.isInSource(virtualFile)     // true if in source root
projectFileIndex.isInTestSource(virtualFile) // true if in test source
```

#### VfsUtilCore (VFS-Based Walk)
```kotlin
VfsUtilCore.iterateChildrenRecursively(
    projectBaseDir,
    { file -> !projectFileIndex.isExcluded(file) }, // filter
    { file ->
        // Process each file
        true // continue
    }
)
```

### Scope and Exclusion Behavior

**Critical finding:** `ProjectFileIndex.isExcluded()` checks IntelliJ's project structure exclusions (directories marked "Excluded" in Project Structure settings), **NOT** `.gitignore`. These are separate concepts:

| Mechanism | What it filters | Source |
|-----------|----------------|--------|
| `ProjectFileIndex.isExcluded()` | Directories excluded in Project Structure (build, out, .gradle) | Module settings |
| `ProjectFileIndex.isInContent()` | Only content roots (src, resources, test) | Module content roots |
| `.gitignore` | Git-ignored files | Git |
| `FileTypeRegistry.isFileIgnored()` | Files ignored by IDE (names starting with `.`) | IDE settings |

For agent tools, `ProjectFileIndex.isInContent()` is the best filter — it automatically excludes build output, .gradle, .idea, generated code, and library JARs. It covers most of what our hardcoded SKIP_DIRS does, plus it's project-aware.

### Recommendation for File Discovery Tool

```kotlin
// Primary: index-based lookup for known patterns
FilenameIndex.getVirtualFilesByName(name, scope)         // exact name
FilenameIndex.getAllFilesByExt(project, ext)              // by extension

// Fallback: VFS walk with ProjectFileIndex filter
VfsUtilCore.iterateChildrenRecursively(baseDir, { !projectFileIndex.isExcluded(it) }, processor)
```

**Dumb mode:** `FilenameIndex` requires smart mode. Fall back to VFS walk in dumb mode.
**Threading:** Requires `ReadAction`.

---

## 3. File Read

### Current Implementation (ReadFileTool)
```kotlin
java.io.File(path).bufferedReader(Charsets.UTF_8).useLines { it.toList() }
```
- Reads directly from disk
- Hardcoded UTF-8 encoding
- Does NOT see unsaved editor changes

### IntelliJ-Native Alternatives

#### VirtualFile (VFS Layer)
```kotlin
val vFile = LocalFileSystem.getInstance().findFileByPath(path)
val bytes = vFile?.contentsToByteArray()  // raw bytes
val charset = vFile?.charset              // detected encoding
val content = String(bytes, charset)
```

**Pros:**
- VFS caches file metadata (name, length, timestamp)
- Proper encoding detection (via `VirtualFile.charset`)
- Works with non-local filesystems (archives, remote)

**Cons:**
- VFS snapshot may be stale if external tool modified file
- Still reads from disk (no unsaved changes)

#### Document (In-Memory Editor State)
```kotlin
val vFile = LocalFileSystem.getInstance().findFileByPath(path)
val document = FileDocumentManager.getInstance().getDocument(vFile!!)
// document.text contains the CURRENT editor content (including unsaved changes!)
val text = document?.text
val lineCount = document?.lineCount
```

**Pros:**
- Sees unsaved editor changes (what the user is currently looking at)
- Proper line separator handling
- Text is already decoded with correct encoding

**Cons:**
- Forces document load into memory if not already cached
- `getDocument()` should be called in a read action
- Document may differ from disk content

#### PsiFile (Structural Understanding)
```kotlin
val psiFile = PsiManager.getInstance(project).findFile(vFile!!)
// psiFile.text — full text
// psiFile.children — PSI tree nodes
// (psiFile as? PsiJavaFile)?.classes — Java classes
```

**Pros:** Structural understanding of the file
**Cons:** Much heavier than needed for plain text reading

### Key Decision: Disk vs Document

| Scenario | Best Source | Why |
|----------|-----------|-----|
| Agent reads a file it hasn't touched | `Document` (via `FileDocumentManager`) | Sees unsaved user edits |
| Agent reads a file it just wrote with `File.writeText()` | `java.io.File` | VFS/Document may not have refreshed yet |
| Agent reads a file after `Document.replaceString()` | `Document` | Already in-memory |

### Recommendation for ReadFileTool

**Use Document when available, VFS as fallback:**
```kotlin
val vFile = LocalFileSystem.getInstance().findFileByPath(path)
val document = FileDocumentManager.getInstance().getCachedDocument(vFile!!)
val text = document?.text  // unsaved changes visible
    ?: String(vFile.contentsToByteArray(), vFile.charset)  // fallback to VFS
```

Use `getCachedDocument()` (not `getDocument()`) to avoid forcing document load for files not currently open. This is important because `getDocument()` loads the file into heap memory and creates a Document object that won't be GC'd easily.

**Threading:** `ReadAction` required for Document and PsiFile access. VirtualFile content access also needs ReadAction.

---

## 4. File Edit

### Current Implementation (EditFileTool)
```kotlin
val content = java.io.File(path).readText(Charsets.UTF_8)
val newContent = content.replace(oldString, newString)
java.io.File(path).writeText(newContent, Charsets.UTF_8)
```

### Problems with Current Approach

1. **No undo support:** `File.writeText()` bypasses IntelliJ's command system. Users cannot Ctrl+Z agent edits.
2. **No VFS notification:** IntelliJ doesn't know the file changed until VFS refresh (either async via file watcher, or manual). Editor may show stale content.
3. **No Document sync:** If the file is open in the editor, the Document still has old content until VFS refresh triggers a reload.
4. **Race condition:** If user has unsaved changes, `File.readText()` reads the disk version (missing user's changes), then `File.writeText()` overwrites the user's unsaved work.
5. **Encoding loss:** Hardcoded UTF-8 may corrupt files with other encodings.
6. **No auto-format:** Changed code isn't reformatted to match project style.

### IntelliJ-Native Approach

#### Document + WriteCommandAction (Undo-Aware)
```kotlin
val vFile = LocalFileSystem.getInstance().findFileByPath(path)!!
val document = FileDocumentManager.getInstance().getDocument(vFile)!!

ApplicationManager.getApplication().invokeAndWait {
    WriteCommandAction.runWriteCommandAction(project, "Agent: Edit $path", null, {
        val text = document.text
        val startOffset = text.indexOf(oldString)
        if (startOffset >= 0) {
            document.replaceString(startOffset, startOffset + oldString.length, newString)
        }
    })
}
```

**Pros:**
- Full undo support (Ctrl+Z works)
- Automatic VFS notification (IDE sees the change immediately)
- Document stays in sync with editor
- Reads current editor state (includes unsaved changes)
- Registered as a named command (shows "Agent: Edit foo.kt" in Edit > Undo)
- Can be combined with `CodeStyleManager.reformat()` for auto-formatting
- Proper encoding handling

**Cons:**
- Must run on EDT (via `invokeAndWait` or `withContext(Dispatchers.EDT)`)
- `WriteCommandAction` takes a write lock — blocks all read actions briefly
- More code than `File.writeText()`

#### With Auto-Format
```kotlin
WriteCommandAction.runWriteCommandAction(project, "Agent: Edit $path", null, {
    document.replaceString(startOffset, startOffset + oldString.length, newString)
    // Auto-format the changed region
    val psiFile = PsiManager.getInstance(project).findFile(vFile)
    if (psiFile != null) {
        CodeStyleManager.getInstance(project).reformatText(
            psiFile,
            startOffset,
            startOffset + newString.length
        )
    }
})
```

#### File Creation via VFS + PSI
```kotlin
WriteCommandAction.runWriteCommandAction(project, "Agent: Create file", null, {
    val parentDir = VfsUtil.createDirectoryIfMissing(parentPath)
    val newFile = parentDir?.createChildData(this, fileName)
    val document = FileDocumentManager.getInstance().getDocument(newFile!!)
    document?.setText(content)
})
```

### Recommendation for EditFileTool

**This is the highest-priority upgrade.** Switch from `File.writeText()` to `Document.replaceString()` inside `WriteCommandAction`:

1. Read from `Document` (sees unsaved changes)
2. Edit via `Document.replaceString()` inside `WriteCommandAction`
3. Undo support comes for free
4. VFS stays in sync automatically
5. Optional: `CodeStyleManager.reformatText()` for auto-formatting

**Threading:** Must execute on EDT via `invokeAndWait` or `withContext(Dispatchers.EDT)`. The write command action handles the write lock internally.

**Rollback integration:** Our existing `AgentRollbackManager` uses `LocalHistory` — this already works with `WriteCommandAction` edits. With `File.writeText()`, LocalHistory might miss the change.

---

## 5. Command Execution

### Current Implementation (RunCommandTool)
```kotlin
val processBuilder = ProcessBuilder("sh", "-c", command)
processBuilder.directory(workDir)
processBuilder.redirectErrorStream(true)
val process = processBuilder.start()
process.waitFor(60, TimeUnit.SECONDS)
val output = process.inputStream.bufferedReader().readText()
```

### IntelliJ-Native Alternatives

#### GeneralCommandLine + CapturingProcessHandler
```kotlin
val commandLine = GeneralCommandLine("sh", "-c", command)
    .withWorkDirectory(workDir)
    .withCharset(Charsets.UTF_8)
    .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
    // ^ Critical on macOS: GUI apps don't inherit shell env by default.
    // This makes PATH, JAVA_HOME, etc. visible to the child process.

val handler = CapturingProcessHandler(commandLine)
val result = handler.runProcess(60_000) // timeout in ms

val stdout = result.stdout
val stderr = result.stderr
val exitCode = result.exitCode
val isTimeout = result.isTimeout
```

**Pros:**
- `ParentEnvironmentType.CONSOLE` — solves macOS env inheritance (our current `ProcessBuilder` doesn't do this; `sh -c` partially works but not reliably for all env vars)
- Proper charset handling (auto-detects from process)
- Built-in timeout support
- Separate stdout/stderr capture
- `CapturingProcessHandler.runProcessWithProgressIndicator()` — shows progress in IDE
- Platform-aware command line escaping

**Cons:**
- Slightly more code
- Still silent execution (no IDE console output)

#### OSProcessHandler (For Streaming Output)
```kotlin
val handler = OSProcessHandler(commandLine)
handler.addProcessListener(object : ProcessAdapter() {
    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
        // Stream output line by line (for long-running commands)
    }
    override fun processTerminated(event: ProcessEvent) {
        // Command finished
    }
})
handler.startNotify()
```

**Pros:** Streaming output, progress indicators, cancellation support
**Cons:** More complex, async callback model

#### RunManager (For Build/Test Tasks)
```kotlin
// For Gradle specifically
val settings = RunManager.getInstance(project)
    .getConfigurationSettings(GradleExternalTaskConfigurationType.getInstance())
// Execute via ProgramRunnerUtil.executeConfiguration(settings, executor)
```

**Pros:** Full IDE integration (Run panel, output console, test results)
**Cons:** Heavy, only for run configurations

### Recommendation for RunCommandTool

**Switch to GeneralCommandLine + CapturingProcessHandler:**
1. Fixes macOS environment inheritance issue (`ParentEnvironmentType.CONSOLE`)
2. Proper charset detection
3. Clean stdout/stderr separation
4. Built-in timeout
5. Drop-in replacement — same sync execution model

**Threading:** Can run from any background thread. No EDT required.

---

## 6. Cross-Cutting Concerns

### Threading Requirements Summary

| API | Thread Requirement | Coroutine Equivalent |
|-----|-------------------|---------------------|
| `PsiSearchHelper` | `ReadAction` | `readAction { }` |
| `FilenameIndex` | `ReadAction` + Smart mode | `smartReadAction(project) { }` |
| `FileBasedIndex` | `ReadAction` + Smart mode | `smartReadAction(project) { }` |
| `ProjectFileIndex` | `ReadAction` | `readAction { }` |
| `Document.text` (read) | `ReadAction` | `readAction { }` |
| `Document.replaceString()` | EDT + `WriteCommandAction` | `withContext(Dispatchers.EDT) { WriteCommandAction... }` |
| `VirtualFile.contentsToByteArray()` | `ReadAction` | `readAction { }` |
| `GeneralCommandLine` execution | Any thread | `withContext(Dispatchers.IO) { }` |
| `PsiManager.findFile()` | `ReadAction` | `readAction { }` |

### Coroutine Integration (2024.1+)

IntelliJ 2024.1+ provides Kotlin coroutine-native read/write actions:

```kotlin
// Read (write-action-allowing — restarts if write arrives)
val result = readAction {
    PsiSearchHelper.getInstance(project).processFilesWithWord(...)
}

// Read in smart mode (waits for indexing)
val files = smartReadAction(project) {
    FilenameIndex.getAllFilesByExt(project, "kt")
}

// Write (on EDT, with undo)
writeAction {
    document.replaceString(start, end, newText)
}
```

These are the recommended APIs for our agent tools since we target IntelliJ 2025.1+.

### Dumb Mode Strategy

| Tool | In Smart Mode | In Dumb Mode (Fallback) |
|------|--------------|------------------------|
| SearchCodeTool | `PsiSearchHelper` index lookup | Fall back to filesystem walk (current impl) |
| File Discovery | `FilenameIndex`, `FileTypeIndex` | VFS walk with `VfsUtilCore.iterateChildrenRecursively()` |
| ReadFileTool | `Document` / `VirtualFile` | Works fine (no index needed) |
| EditFileTool | `Document` + `WriteCommandAction` | Works fine (no index needed) |
| RunCommandTool | `GeneralCommandLine` | Works fine (no index needed) |

**Pattern:** Check `DumbService.isDumb(project)` at tool entry, degrade gracefully. Our existing `PsiToolUtils.isDumb()` already does this for PSI tools.

### VFS Performance vs java.io.File

| Operation | java.io.File | VFS |
|-----------|-------------|-----|
| File existence check | Disk I/O (slow on NFS) | Cached in-memory |
| Directory listing | Disk I/O | Cached snapshot |
| File metadata (size, timestamp) | Disk I/O per call | Cached |
| File content | Always reads from disk | Cached if previously accessed |
| Encoding detection | Manual / hardcoded | Automatic via `VirtualFile.charset` |
| Change detection | Manual polling | File watcher + VFS events |

VFS is significantly faster for repeated access patterns (agent tools reading the same files multiple times during a session). First access is similar speed.

### What ProjectFileIndex.isExcluded() Covers vs Doesn't

**Covers (excluded from project structure):**
- Build output directories (build/, out/, target/)
- .gradle/ directory
- Generated source directories
- Directories manually marked "Excluded"

**Does NOT cover:**
- `.gitignore` entries (these are Git-level, not IDE-level)
- `node_modules/` (unless manually excluded in project structure)
- `.worktrees/` (custom to our project)

**Recommendation:** Use `ProjectFileIndex.isInContent()` as the primary filter, then add our custom skip list for agent-specific exclusions (node_modules, .worktrees).

---

## 7. Priority Ranking for Implementation

| Priority | Tool | Current | Target | Impact |
|----------|------|---------|--------|--------|
| **P0** | EditFileTool | `File.writeText()` | `Document` + `WriteCommandAction` | Undo support, no stale editors, no race conditions |
| **P1** | SearchCodeTool | Filesystem walk + regex | `PsiSearchHelper` + `FindManager` | 10-100x faster for large projects |
| **P1** | ReadFileTool | `File.readLines()` | `Document` (unsaved changes) + VFS fallback | Sees unsaved user edits, proper encoding |
| **P2** | RunCommandTool | `ProcessBuilder` | `GeneralCommandLine` + `CapturingProcessHandler` | macOS env fix, proper charset |
| **P2** | File Discovery | No dedicated tool | `FilenameIndex` + `ProjectFileIndex` | Index-based, respects project scope |

---

## 8. Migration Strategy

### Phase 1: EditFileTool (Highest Impact)
- Switch from `File.readText/writeText` to `Document.replaceString` + `WriteCommandAction`
- Add VFS refresh after edit: not needed with Document approach (automatic)
- Add optional `CodeStyleManager.reformatText()` for auto-formatting
- Test undo: verify Ctrl+Z works after agent edit

### Phase 2: ReadFileTool + SearchCodeTool
- ReadFileTool: Try `getCachedDocument()` first, fall back to VFS, then `File`
- SearchCodeTool: Use `PsiSearchHelper` for word queries, `FindManager` for regex
- Add dumb mode fallback (keep current filesystem implementation)

### Phase 3: RunCommandTool + File Discovery
- RunCommandTool: Swap `ProcessBuilder` for `GeneralCommandLine`
- Create `ListFilesTool` using `FilenameIndex` + `ProjectFileIndex`

---

## Sources

- [File-Based Indexes | IntelliJ Platform Plugin SDK](https://plugins.jetbrains.com/docs/intellij/file-based-indexes.html)
- [Virtual File System | IntelliJ Platform Plugin SDK](https://plugins.jetbrains.com/docs/intellij/virtual-file-system.html)
- [Virtual Files | IntelliJ Platform Plugin SDK](https://plugins.jetbrains.com/docs/intellij/virtual-file.html)
- [Threading Model | IntelliJ Platform Plugin SDK](https://plugins.jetbrains.com/docs/intellij/threading-model.html)
- [Coroutine Read Actions | IntelliJ Platform Plugin SDK](https://plugins.jetbrains.com/docs/intellij/coroutine-read-actions.html)
- [Documents | IntelliJ Platform Plugin SDK](https://plugins.jetbrains.com/docs/intellij/documents.html)
- [Execution | IntelliJ Platform Plugin SDK](https://plugins.jetbrains.com/docs/intellij/execution.html)
- [Indexing and PSI Stubs | IntelliJ Platform Plugin SDK](https://plugins.jetbrains.com/docs/intellij/indexing-and-psi-stubs.html)
- [Run Configurations | IntelliJ Platform Plugin SDK](https://plugins.jetbrains.com/docs/intellij/run-configurations.html)
- [PsiSearchHelper API](https://github.com/JetBrains/intellij-community/blob/master/platform/indexing-api/src/com/intellij/psi/search/PsiSearchHelper.java)
- [FilenameIndex API](https://github.com/JetBrains/intellij-community/blob/idea/243.22562.145/platform/indexing-api/src/com/intellij/psi/search/FilenameIndex.java)
- [FindModel API](https://github.com/JetBrains/intellij-community/blob/master/platform/indexing-api/src/com/intellij/find/FindModel.java)
- [GeneralCommandLine API](https://dploeger.github.io/intellij-api-doc/com/intellij/execution/configurations/GeneralCommandLine.html)
- [ProjectFileIndex API](https://dploeger.github.io/intellij-api-doc/com/intellij/openapi/roots/ProjectFileIndex.html)
- [FileDocumentManager API](https://dploeger.github.io/intellij-api-doc/com/intellij/openapi/fileEditor/FileDocumentManager.html)
- [CapturingProcessHandler API](https://dploeger.github.io/intellij-api-doc/com/intellij/execution/process/CapturingProcessHandler.html)

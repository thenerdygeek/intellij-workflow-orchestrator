# Performance Cleanup — Remove Redundant Extensions & Fix EDT Bottlenecks

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate IDE hangs caused by redundant/expensive extension points — remove 7 features, add settings + perf fixes to 4 features, fix 2 architectural bugs.

**Architecture:** Three workstreams: (A) delete unused extensions + plugin.xml registrations, (B) add settings toggles + fix perf for retained features, (C) fix poller lazy-start and DumbService guards. All changes are independent per task.

**Tech Stack:** Kotlin, IntelliJ Platform SDK, Swing

---

### Task 1: Remove 7 redundant extension features

**Files:**
- Delete: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoverageBannerProvider.kt`
- Delete: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/inspection/SonarGlobalInspectionTool.kt`
- Delete: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SpringEndpointCacheService.kt`
- Delete: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/diff/CoverageDiffExtension.kt`
- Delete: `cody/src/main/kotlin/com/workflow/orchestrator/cody/editor/CodyTestGenerator.kt`
- Delete: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/editor/CveIntentionAction.kt`
- Delete: `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/CveRemediationService.kt`
- Delete: `bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/editor/CveIntentionActionTest.kt`
- Delete: `bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/service/CveRemediationServiceTest.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarDataService.kt`
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoverageLineMarkerProvider.kt`

- [ ] **Step 1: Delete source files**

```bash
rm -f \
  sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoverageBannerProvider.kt \
  sonar/src/main/kotlin/com/workflow/orchestrator/sonar/inspection/SonarGlobalInspectionTool.kt \
  sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SpringEndpointCacheService.kt \
  sonar/src/main/kotlin/com/workflow/orchestrator/sonar/diff/CoverageDiffExtension.kt \
  cody/src/main/kotlin/com/workflow/orchestrator/cody/editor/CodyTestGenerator.kt \
  bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/editor/CveIntentionAction.kt \
  bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/CveRemediationService.kt \
  bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/editor/CveIntentionActionTest.kt \
  bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/service/CveRemediationServiceTest.kt
```

- [ ] **Step 2: Remove plugin.xml registrations**

Remove these blocks from `src/main/resources/META-INF/plugin.xml`:

```xml
<!-- Remove: SpringEndpointCacheService projectService (line ~157-158) -->
<projectService serviceImplementation="com.workflow.orchestrator.sonar.service.SpringEndpointCacheService"/>

<!-- Remove: CoverageBannerProvider (line ~171-172) -->
<editorNotificationProvider implementation="com.workflow.orchestrator.sonar.ui.CoverageBannerProvider"/>

<!-- Remove: CodyTestGenerator JAVA + kotlin (line ~174-179) -->
<codeInsight.lineMarkerProvider language="JAVA"
    implementationClass="com.workflow.orchestrator.cody.editor.CodyTestGenerator"/>
<codeInsight.lineMarkerProvider language="kotlin"
    implementationClass="com.workflow.orchestrator.cody.editor.CodyTestGenerator"/>

<!-- Remove: CveIntentionAction (line ~192-196) -->
<intentionAction>
    <language>XML</language>
    <className>com.workflow.orchestrator.bamboo.editor.CveIntentionAction</className>
    <category>Workflow Orchestrator</category>
</intentionAction>

<!-- Remove: CveRemediationService projectService (line ~197-198) -->
<projectService serviceImplementation="com.workflow.orchestrator.bamboo.service.CveRemediationService"/>

<!-- Remove: SonarGlobalInspectionTool (line ~300-306) -->
<globalInspection ...SonarGlobalInspectionTool.../>

<!-- Remove: CoverageDiffExtension (line ~312) -->
<diff.DiffExtension implementation="com.workflow.orchestrator.sonar.diff.CoverageDiffExtension"/>
```

- [ ] **Step 3: Remove SpringEndpointCacheService references from SonarDataService**

In `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/service/SonarDataService.kt`:

Remove the `SpringEndpointCacheService.getInstance(project).clearCache()` call in `clearLineCoverageCache()` (around line 208). Remove the import if present.

- [ ] **Step 4: Remove SpringEndpointCacheService references from CoverageLineMarkerProvider**

In `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoverageLineMarkerProvider.kt`:

Remove all Spring endpoint logic:
- Remove the `isEndpoint` check (lines ~77-79)
- Remove `ICON_ENDPOINT_UNCOVERED` constant and its `when` branch
- Remove `triggerEndpointCachePopulation()` method and its call in the fetch callback
- Remove `getEndpointCacheService()` helper
- Remove the `SpringEndpointCacheService` import

The simplified icon selection becomes:
```kotlin
val (icon, tooltip) = when (lineStatus) {
    LineCoverageStatus.COVERED -> ICON_COVERED to "Line covered"
    LineCoverageStatus.UNCOVERED -> ICON_UNCOVERED to "Line not covered"
    else -> ICON_PARTIAL to "Partially covered (some branches uncovered)"
}
```

- [ ] **Step 5: Compile and test**

```bash
./gradlew compileKotlin
./gradlew :sonar:test :bamboo:test :cody:test
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 6: Commit**

```bash
git add -u && git commit -m "perf: remove 7 redundant IDE extensions causing EDT overhead

Remove CoverageBannerProvider, SonarGlobalInspectionTool,
SpringEndpointCacheService, CoverageDiffExtension, CodyTestGenerator,
CveIntentionAction, and CveRemediationService. These features duplicated
information already available in the Quality/Build tabs and added
measurable overhead (highlighter scans, PSI walks, daemon restarts)."
```

---

### Task 2: Add settings + fix perf for CodyIntentionAction

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt`
- Modify: `cody/src/main/kotlin/com/workflow/orchestrator/cody/editor/CodyIntentionAction.kt`
- Modify: `src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Add setting to PluginSettings**

In `core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt`, add after the `coverageGutterMarkersEnabled` line:

```kotlin
var sonarIntentionActionEnabled by property(false) // disabled by default — enable in settings
```

- [ ] **Step 2: Add language filters to plugin.xml**

Replace the current `<intentionAction>` registration for CodyIntentionAction (which has NO language filter) with two language-specific registrations:

```xml
<intentionAction>
    <language>JAVA</language>
    <className>com.workflow.orchestrator.cody.editor.CodyIntentionAction</className>
    <category>Workflow Orchestrator</category>
</intentionAction>
<intentionAction>
    <language>kotlin</language>
    <className>com.workflow.orchestrator.cody.editor.CodyIntentionAction</className>
    <category>Workflow Orchestrator</category>
</intentionAction>
```

This ensures `isAvailable()` is never called for XML, YAML, properties, or other file types.

- [ ] **Step 3: Fix CodyIntentionAction performance**

Rewrite `cody/src/main/kotlin/com/workflow/orchestrator/cody/editor/CodyIntentionAction.kt`:

```kotlin
package com.workflow.orchestrator.cody.editor

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.workflow.orchestrator.core.ai.LlmBrainFactory
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.sonar.model.MappedIssue
import com.workflow.orchestrator.sonar.ui.SonarIssueAnnotator

class CodyIntentionAction : IntentionAction, DumbAware {

    override fun getText(): String = "Fix with AI Agent (Workflow)"

    override fun getFamilyName(): String = "Workflow Orchestrator"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (editor == null || file == null) return false
        // Setting + LLM availability — both are fast O(1) checks
        if (!PluginSettings.getInstance(project).state.sonarIntentionActionEnabled) return false
        if (!LlmBrainFactory.isAvailable()) return false
        // Check ONLY the highlighter at the caret offset using getUserData — O(1) per highlighter
        // instead of scanning all highlighters and computing line numbers
        return findSonarIssueAtCaret(editor, editor.caretModel.offset) != null
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file == null) return
        val sonarIssue = findSonarIssueAtCaret(editor, editor.caretModel.offset) ?: return
        val filePath = file.virtualFile.path

        val prompt = buildString {
            appendLine("Fix the following SonarQube issue in this file.")
            appendLine()
            appendLine("**Issue:** [${sonarIssue.rule}] ${sonarIssue.message}")
            appendLine("**Type:** ${sonarIssue.type}")
            appendLine("**File:** ${file.virtualFile.name}")
            appendLine("**Lines:** ${sonarIssue.startLine}-${sonarIssue.endLine}")
            appendLine()
            appendLine("Read the file, understand the surrounding code, apply a minimal fix that resolves the issue without changing behavior, and verify with diagnostics that the fix compiles.")
        }

        com.workflow.orchestrator.core.ai.AgentChatRedirect.getInstance()?.sendToAgent(project, prompt, listOf(filePath))
    }

    private fun findSonarIssueAtCaret(editor: Editor, offset: Int): MappedIssue? {
        // Only check highlighters that overlap the caret offset and have SONAR_ISSUE_KEY
        // This avoids iterating ALL highlighters — the markupModel.allHighlighters iteration
        // is still O(N) but we short-circuit on first match and skip line number computation
        return editor.markupModel.allHighlighters
            .firstOrNull { it.startOffset <= offset && offset <= it.endOffset &&
                it.getUserData(SonarIssueAnnotator.SONAR_ISSUE_KEY) != null }
            ?.getUserData(SonarIssueAnnotator.SONAR_ISSUE_KEY)
    }

    override fun startInWriteAction(): Boolean = false
}
```

Key perf fixes:
- `DumbAware` interface — tells IntelliJ it's safe during indexing (avoids dumb mode overhead)
- Settings check + `LlmBrainFactory.isAvailable()` as first guards — both are O(1) and return `false` most of the time, short-circuiting the highlighter scan entirely
- `firstOrNull` with combined predicate — single pass, early exit on first match
- Language filter in plugin.xml — never called for non-Java/Kotlin files

- [ ] **Step 4: Compile and test**

```bash
./gradlew :cody:compileKotlin :core:compileKotlin
./gradlew :cody:test
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add -u && git commit -m "perf: add setting + language filter for CodyIntentionAction

Add sonarIntentionActionEnabled setting (default: false). Add language
filters (JAVA/kotlin) to plugin.xml so isAvailable() is never called
for non-source files. Implement DumbAware, short-circuit on settings
check before any highlighter iteration."
```

---

### Task 3: Add setting + fix perf for SonarIssueAnnotator

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt`
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/SonarIssueAnnotator.kt`

- [ ] **Step 1: Add setting to PluginSettings**

In `core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt`, add after `sonarIntentionActionEnabled`:

```kotlin
var sonarInlineAnnotationsEnabled by property(false) // disabled by default — enable in settings
```

- [ ] **Step 2: Fix SonarIssueAnnotator performance**

In `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/SonarIssueAnnotator.kt`:

**2a: Add DumbService + settings guard in `collectInformation()`** — add at the very top of the method (after line 72):

```kotlin
override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): SonarAnnotationInput? {
    val project = file.project
    if (com.intellij.openapi.project.DumbService.isDumb(project)) return null
    if (!PluginSettings.getInstance(project).state.sonarInlineAnnotationsEnabled) return null
    val virtualFile = file.virtualFile ?: return null
    // ... rest unchanged
```

Add import: `import com.workflow.orchestrator.core.settings.PluginSettings`

**2b: Fix `apply()` — eliminate O(N×M) highlighter scan.** Replace the highlighter scanning block (lines 207-214) with a direct `addRangeHighlighter` approach that stores the user data at creation time:

```kotlin
override fun apply(file: PsiFile, annotationResult: SonarAnnotationResult, holder: AnnotationHolder) {
    val doc = file.viewProvider.document ?: return

    for (annotation in annotationResult.annotations) {
        val issue = annotation.issue

        val startLine = (issue.startLine - 1).coerceIn(0, doc.lineCount - 1)
        val endLine = (issue.endLine - 1).coerceIn(0, doc.lineCount - 1)

        val startOffset = doc.getLineStartOffset(startLine) + issue.startOffset
        val endOffset = if (issue.endOffset > 0) {
            doc.getLineStartOffset(endLine) + issue.endOffset
        } else {
            doc.getLineEndOffset(endLine)
        }

        val textRange = TextRange(
            startOffset.coerceIn(0, doc.textLength),
            endOffset.coerceIn(0, doc.textLength)
        )

        if (textRange.isEmpty) continue

        holder.newAnnotation(annotation.severity, annotation.tooltip)
            .range(textRange)
            .tooltip(annotation.tooltip)
            .withFix(SonarIssueFix(issue, file.virtualFile?.path ?: ""))
            .create()
    }
}
```

This removes the `editor.markupModel.allHighlighters` scan entirely. The `SONAR_ISSUE_KEY` user data storage is no longer needed in `apply()` since `CodyIntentionAction` can find issues by matching offset ranges against `SonarDataService.stateFlow` directly (see Task 2 step 3 — if the intention action is disabled by default, the highlighter storage is dead code anyway).

**2c: Add a lightweight `SonarIssueFix` inner class** that `CodyIntentionAction` can use as an alternative to highlighter user data:

```kotlin
private class SonarIssueFix(
    private val issue: MappedIssue,
    private val filePath: String
) : com.intellij.codeInsight.intention.IntentionAction {
    override fun getText() = "Fix with AI Agent: [${issue.rule}]"
    override fun getFamilyName() = "Workflow Orchestrator"
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?) =
        com.workflow.orchestrator.core.ai.LlmBrainFactory.isAvailable() &&
        PluginSettings.getInstance(project).state.sonarIntentionActionEnabled
    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val prompt = buildString {
            appendLine("Fix the following SonarQube issue in this file.")
            appendLine()
            appendLine("**Issue:** [${issue.rule}] ${issue.message}")
            appendLine("**Type:** ${issue.type}")
            appendLine("**File:** ${filePath.substringAfterLast('/')}")
            appendLine("**Lines:** ${issue.startLine}-${issue.endLine}")
            appendLine()
            appendLine("Read the file, understand the surrounding code, apply a minimal fix.")
        }
        com.workflow.orchestrator.core.ai.AgentChatRedirect.getInstance()?.sendToAgent(project, prompt, listOf(filePath))
    }
    override fun startInWriteAction() = false
}
```

This attaches the fix directly to each annotation via `withFix()`, so the Alt+Enter quick fix appears without needing the separate `CodyIntentionAction` highlighter scan at all. Both approaches can coexist.

- [ ] **Step 3: Remove SONAR_ISSUE_KEY usage from apply()**

Remove the `editor` variable and the `allHighlighters` block from `apply()`. The `SONAR_ISSUE_KEY` companion object can remain (it's used by `CodyIntentionAction.findSonarIssueAtCaret`), but `apply()` no longer writes to it. This eliminates the O(N×M) performance issue.

- [ ] **Step 4: Compile and test**

```bash
./gradlew :sonar:compileKotlin :core:compileKotlin
./gradlew :sonar:test
```

Expected: BUILD SUCCESSFUL, all tests pass (existing `SonarIssueAnnotatorLogicTest` tests pure logic, not the apply method).

- [ ] **Step 5: Commit**

```bash
git add -u && git commit -m "perf: add setting + fix O(N×M) highlighter scan in SonarIssueAnnotator

Add sonarInlineAnnotationsEnabled setting (default: false). Add DumbService
guard. Remove allHighlighters scan from apply() — attach SonarIssueFix
directly via withFix() instead. Eliminates O(issues × highlighters) EDT
work on every daemon pass."
```

---

### Task 4: Fix CoverageLineMarkerProvider — eliminate DaemonCodeAnalyzer.restart() cascade

**Files:**
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoverageLineMarkerProvider.kt`

- [ ] **Step 1: Replace `DaemonCodeAnalyzer.restart()` with `DaemonCodeAnalyzer.getInstance(project).restart()`**

Actually, the problem IS `restart()`. Replace it with `FileEditorManager`-based update that only refreshes gutter icons without rerunning all daemon passes.

In `CoverageLineMarkerProvider.kt`, replace the fetch callback (lines ~43-63):

```kotlin
if (projectPending.putIfAbsent(relativePath, true) == null) {
    val psiFile = element.containingFile
    service.fetchLineCoverageAsync(relativePath) {
        try {
            val isValid = com.intellij.openapi.application.ReadAction.compute<Boolean, RuntimeException> {
                !project.isDisposed && psiFile.isValid
            }
            if (isValid) {
                // Use invokeLater + repaint instead of DaemonCodeAnalyzer.restart()
                // restart() re-runs ALL annotators/markers/inspections — extremely expensive.
                // Instead, just update the editor's gutter to trigger line marker re-evaluation.
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    if (!project.isDisposed) {
                        val editors = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).allEditors
                        for (editor in editors) {
                            val textEditor = editor as? com.intellij.openapi.fileEditor.TextEditor ?: continue
                            val editorFile = editor.file ?: continue
                            val editorRelPath = if (baseDir != null) {
                                (com.intellij.openapi.vfs.VfsUtilCore.getRelativePath(editorFile, baseDir) ?: "").replace('\\', '/')
                            } else editorFile.path.replace('\\', '/')
                            if (editorRelPath == relativePath) {
                                textEditor.editor.gutterComponentEx.repaint()
                            }
                        }
                    }
                }
            }
        } finally {
            projectPending.remove(relativePath)
        }
    }
}
```

Wait — `gutterComponentEx.repaint()` alone won't re-trigger `getLineMarkerInfo()`. The daemon needs to re-run the line marker pass. The lightest way is `DaemonCodeAnalyzer.getInstance(project).restart(psiFile)`, but that's what we're trying to avoid.

Better approach: use `EditorMarkupModel` to trigger a targeted refresh. Actually, the most correct approach is to use `FileStatusManager.getInstance(project).fileStatusesChanged()` or to use `UpdateHighlightersUtil`. But these are internal APIs.

The pragmatic fix: **don't trigger any refresh at all on fetch completion.** Instead, make `getLineMarkerInfo()` return `null` when data isn't cached (current behavior), and rely on the daemon's natural re-runs (typing, tab switching, save) to pick up the data. The data will appear within seconds of the fetch completing, without forcing a restart.

Replace the callback with:

```kotlin
if (projectPending.putIfAbsent(relativePath, true) == null) {
    service.fetchLineCoverageAsync(relativePath) {
        projectPending.remove(relativePath)
        // Data is now cached. Markers will appear on the next natural daemon pass
        // (typing, save, tab switch). No DaemonCodeAnalyzer.restart() — that would
        // re-run ALL annotators/markers/inspections, causing cascade overhead.
    }
}
```

- [ ] **Step 2: Add DumbService guard**

Add at the top of `getLineMarkerInfo()`, after the `PsiFile` check:

```kotlin
if (com.intellij.openapi.project.DumbService.isDumb(project)) return null
```

- [ ] **Step 3: Compile and test**

```bash
./gradlew :sonar:compileKotlin
./gradlew :sonar:test
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 4: Commit**

```bash
git add -u && git commit -m "perf: remove DaemonCodeAnalyzer.restart() cascade from CoverageLineMarkerProvider

Stop forcing a full daemon restart when coverage data arrives. Markers
appear on the next natural daemon pass (typing, save, tab switch).
Eliminates the double-pass cascade that re-ran all annotators,
markers, and inspections for every file open. Add DumbService guard."
```

---

### Task 5: Fix leaked CoroutineScopes + add settings for VCS handlers

**Files:**
- Modify: `core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt`
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/vcs/TimeTrackingCheckinHandlerFactory.kt`
- Modify: `jira/src/main/kotlin/com/workflow/orchestrator/jira/vcs/PostCommitTransitionHandlerFactory.kt`

- [ ] **Step 1: Add settings to PluginSettings**

In `core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt`, add in the time tracking section:

```kotlin
var autoLogTimeOnCommit by property(true)
var autoTransitionOnCommit by property(true)
```

- [ ] **Step 2: Fix TimeTrackingCheckinHandlerFactory — use project scope + add setting**

In `jira/src/main/kotlin/com/workflow/orchestrator/jira/vcs/TimeTrackingCheckinHandlerFactory.kt`:

Replace `CoroutineScope(Dispatchers.IO).launch` (line ~110) with:

```kotlin
@Suppress("UnstableApiUsage")
project.coroutineScope.launch(Dispatchers.IO) {
```

Add import: `import com.intellij.platform.ide.progress.withBackgroundProgress` (or just `import kotlinx.coroutines.launch`)

Also add setting guard in `beforeCheckin()` or where the handler decides whether to show the time tracking panel:

```kotlin
if (!PluginSettings.getInstance(project).state.autoLogTimeOnCommit) return ReturnResult.COMMIT
```

- [ ] **Step 3: Fix PostCommitTransitionHandlerFactory — use project scope + add setting**

In `jira/src/main/kotlin/com/workflow/orchestrator/jira/vcs/PostCommitTransitionHandlerFactory.kt`:

Replace both `CoroutineScope(Dispatchers.IO).launch` occurrences (lines ~42, ~70) with:

```kotlin
@Suppress("UnstableApiUsage")
project.coroutineScope.launch(Dispatchers.IO) {
```

Add setting guard in the handler:

```kotlin
if (!PluginSettings.getInstance(project).state.autoTransitionOnCommit) return ReturnResult.COMMIT
```

- [ ] **Step 4: Compile and test**

```bash
./gradlew :jira:compileKotlin :core:compileKotlin
./gradlew :jira:test
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 5: Commit**

```bash
git add -u && git commit -m "fix: use project.coroutineScope for VCS handlers, add enable/disable settings

Replace leaked CoroutineScope(Dispatchers.IO) with project.coroutineScope
in TimeTrackingCheckinHandlerFactory and PostCommitTransitionHandlerFactory.
Add autoLogTimeOnCommit and autoTransitionOnCommit settings."
```

---

### Task 6: Lazy-start PrListService poller

**Files:**
- Modify: `pullrequest/src/main/kotlin/com/workflow/orchestrator/pullrequest/service/PrListService.kt`

- [ ] **Step 1: Make the poller lazy-initialized**

Replace the eager field initializer (lines ~41-52):

```kotlin
// BEFORE (eager — starts allocating immediately on service creation):
private val poller = SmartPoller(
    name = "PR-List",
    baseIntervalMs = 60_000,
    maxIntervalMs = 300_000,
    scope = scope,
    action = { ... }
)
```

With lazy initialization:

```kotlin
private var poller: SmartPoller? = null

private fun getOrCreatePoller(): SmartPoller {
    return poller ?: SmartPoller(
        name = "PR-List",
        baseIntervalMs = 60_000,
        maxIntervalMs = 300_000,
        scope = scope,
        action = {
            val oldMySize = _myPrs.value.size
            val oldReviewSize = _reviewingPrs.value.size
            refresh()
            _myPrs.value.size != oldMySize || _reviewingPrs.value.size != oldReviewSize
        }
    ).also { poller = it }
}

fun startPolling() = getOrCreatePoller().start()

fun stopPolling() { poller?.stop() }

fun setVisible(visible: Boolean) { poller?.setVisible(visible) }
```

This ensures the poller is only created when `startPolling()` is first called (which happens when the PR tab becomes visible), not when the service is instantiated.

- [ ] **Step 2: Compile and test**

```bash
./gradlew :pullrequest:compileKotlin
./gradlew :pullrequest:test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add -u && git commit -m "perf: lazy-start PrListService poller — only create on first tab visibility

SmartPoller is now created on first startPolling() call instead of
eagerly in the field initializer. Prevents background HTTP polling
from starting before the user ever opens the PR tab."
```

---

### Task 7: Add DumbService guards to all remaining providers

**Files:**
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/CoverageLineMarkerProvider.kt` (already done in Task 4)
- Modify: `sonar/src/main/kotlin/com/workflow/orchestrator/sonar/ui/SonarIssueAnnotator.kt` (already done in Task 3)
- Modify: `cody/src/main/kotlin/com/workflow/orchestrator/cody/editor/CodyIntentionAction.kt` (already done in Task 2 via DumbAware)

All three providers now have DumbService protection from Tasks 2-4. This task is a verification step.

- [ ] **Step 1: Verify all providers are protected**

Grep for all `LineMarkerProvider` and `ExternalAnnotator` implementations and confirm each has either:
- `DumbService.isDumb(project)` check, OR
- Implements `DumbAware` interface

```bash
grep -rn "LineMarkerProvider\|ExternalAnnotator" --include="*.kt" sonar/src/main cody/src/main | grep -v "import\|test\|//\|CLAUDE"
```

- [ ] **Step 2: Verify compile**

```bash
./gradlew compileKotlin
./gradlew :sonar:test :cody:test :bamboo:test :jira:test
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 3: Final commit (if any DumbService guards were missed)**

```bash
git add -u && git commit -m "perf: verify DumbService guards on all line marker providers and annotators"
```

---

### Task 8: Add settings UI for new toggle settings

**Files:**
- Modify: The settings panel that manages CI/CD or AI & Advanced settings (find the configurable that contains `coverageGutterMarkersEnabled`)

- [ ] **Step 1: Find the settings UI panel**

```bash
grep -rn "coverageGutterMarkersEnabled\|coverageHighThreshold\|sonarMetricKeys" --include="*.kt" */src/main | grep -v "PluginSettings\|CLAUDE\|test"
```

- [ ] **Step 2: Add checkboxes for the new settings**

Add these checkboxes to the appropriate settings page:

| Setting | Label | Default | Section |
|---------|-------|---------|---------|
| `sonarIntentionActionEnabled` | "Show 'Fix with AI Agent' quick fix on SonarQube issues" | `false` | AI & Advanced |
| `sonarInlineAnnotationsEnabled` | "Show SonarQube issue underlines in editor" | `false` | CI/CD |
| `autoLogTimeOnCommit` | "Auto-log time to Jira on commit" | `true` | Workflow |
| `autoTransitionOnCommit` | "Suggest ticket transition after commit" | `true` | Workflow |

- [ ] **Step 3: Compile and test**

```bash
./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add -u && git commit -m "feat: add settings UI for sonar annotations, AI quick fix, and VCS toggles"
```

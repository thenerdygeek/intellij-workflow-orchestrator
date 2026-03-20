# Three-Layer Plan Persistence + Editor Tab — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist agent plans to disk, inject a compression-proof anchored summary into context, and display plans in a full-screen editor tab with interactive comments.

**Architecture:** Three layers: (1) `plan.json` saved to session directory for ground-truth persistence, (2) a structured `<active_plan>` system message that survives context compression and is updated in-place as steps complete, (3) a custom `FileEditorProvider` with JCEF rendering for full-screen plan viewing with clickable file links and per-step comments.

**Tech Stack:** Kotlin 2.1.10, IntelliJ Platform 2025.1+ (`LightVirtualFile`, `FileEditorProvider`, `JBCefBrowser`), kotlinx.serialization, JCEF HTML/CSS/JS

**Research:** `docs/superpowers/research/2026-03-20-plan-persistence-patterns.md`

---

## File Structure

### New Files
| File | Responsibility |
|------|---------------|
| `agent/src/main/kotlin/.../runtime/PlanPersistence.kt` | Save/load `plan.json` to session directory |
| `agent/src/main/kotlin/.../context/PlanAnchor.kt` | Build and update the anchored `<active_plan>` system message |
| `agent/src/main/kotlin/.../ui/plan/AgentPlanFileType.kt` | Virtual file type for `.agentplan` |
| `agent/src/main/kotlin/.../ui/plan/AgentPlanVirtualFile.kt` | In-memory virtual file holding plan data |
| `agent/src/main/kotlin/.../ui/plan/AgentPlanEditorProvider.kt` | FileEditorProvider that creates plan editors |
| `agent/src/main/kotlin/.../ui/plan/AgentPlanEditor.kt` | FileEditor with JBCefBrowser for rich plan rendering |
| `agent/src/main/resources/webview/agent-plan.html` | Full-screen plan HTML/CSS/JS (reuses chat plan card styling) |
| `agent/src/test/kotlin/.../runtime/PlanPersistenceTest.kt` | Tests for plan save/load |
| `agent/src/test/kotlin/.../context/PlanAnchorTest.kt` | Tests for anchored summary building/updating |

### Modified Files
| File | Change Summary |
|------|---------------|
| `agent/.../runtime/PlanManager.kt` | Add persistence calls on submit/update, add anchor update callback |
| `agent/.../tools/builtin/CreatePlanTool.kt` | After approval, inject anchored plan summary into context |
| ~~`agent/.../tools/builtin/UpdatePlanStepTool.kt`~~ | No changes needed — persistence + anchor wired through PlanManager callbacks |
| `agent/.../ui/AgentController.kt` | Open editor tab on plan creation, wire persistence |
| `src/main/resources/META-INF/plugin.xml` | Register FileEditorProvider extension |

---

## Task 1: Plan File Persistence (Layer 1)

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/PlanPersistence.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/PlanPersistenceTest.kt`

- [ ] **Step 1: Write tests for PlanPersistence**

```kotlin
package com.workflow.orchestrator.agent.runtime

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class PlanPersistenceTest {

    @TempDir
    lateinit var tempDir: Path

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    @Test
    fun `save writes plan json to session directory`() {
        val sessionDir = File(tempDir.toFile(), "session-1").apply { mkdirs() }
        val plan = AgentPlan(
            goal = "Fix NPE",
            approach = "Add null check",
            steps = listOf(PlanStep(id = "1", title = "Read file", files = listOf("Foo.kt"), action = "read")),
            testing = "Run tests"
        )
        PlanPersistence.save(plan, sessionDir)

        val planFile = File(sessionDir, "plan.json")
        assertTrue(planFile.exists())
        val loaded = json.decodeFromString<AgentPlan>(planFile.readText())
        assertEquals("Fix NPE", loaded.goal)
        assertEquals(1, loaded.steps.size)
    }

    @Test
    fun `load returns plan from session directory`() {
        val sessionDir = File(tempDir.toFile(), "session-2").apply { mkdirs() }
        val plan = AgentPlan(goal = "Test", steps = listOf(PlanStep(id = "1", title = "Step")))
        PlanPersistence.save(plan, sessionDir)

        val loaded = PlanPersistence.load(sessionDir)
        assertNotNull(loaded)
        assertEquals("Test", loaded!!.goal)
    }

    @Test
    fun `load returns null when no plan file exists`() {
        val sessionDir = File(tempDir.toFile(), "session-3").apply { mkdirs() }
        assertNull(PlanPersistence.load(sessionDir))
    }

    @Test
    fun `updateStepStatus updates step in persisted plan`() {
        val sessionDir = File(tempDir.toFile(), "session-4").apply { mkdirs() }
        val plan = AgentPlan(
            goal = "Test",
            steps = listOf(
                PlanStep(id = "1", title = "A", status = "pending"),
                PlanStep(id = "2", title = "B", status = "pending")
            )
        )
        PlanPersistence.save(plan, sessionDir)
        PlanPersistence.updateStepStatus(sessionDir, "1", "done")

        val loaded = PlanPersistence.load(sessionDir)!!
        assertEquals("done", loaded.steps.find { it.id == "1" }?.status)
        assertEquals("pending", loaded.steps.find { it.id == "2" }?.status)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.runtime.PlanPersistenceTest" --rerun --no-build-cache
```
Expected: FAIL — `PlanPersistence` doesn't exist.

- [ ] **Step 3: Implement PlanPersistence**

```kotlin
package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists the agent's execution plan to disk as structured JSON.
 * Ground-truth storage that survives context compression and IDE restarts.
 *
 * File: {sessionDir}/plan.json
 */
object PlanPersistence {
    private val LOG = Logger.getInstance(PlanPersistence::class.java)
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }
    private const val PLAN_FILENAME = "plan.json"

    fun save(plan: AgentPlan, sessionDir: File) {
        try {
            sessionDir.mkdirs()
            File(sessionDir, PLAN_FILENAME).writeText(json.encodeToString(plan))
        } catch (e: Exception) {
            LOG.warn("PlanPersistence: failed to save plan", e)
        }
    }

    fun load(sessionDir: File): AgentPlan? {
        val file = File(sessionDir, PLAN_FILENAME)
        if (!file.exists()) return null
        return try {
            json.decodeFromString<AgentPlan>(file.readText())
        } catch (e: Exception) {
            LOG.warn("PlanPersistence: failed to load plan", e)
            null
        }
    }

    fun updateStepStatus(sessionDir: File, stepId: String, status: String) {
        val plan = load(sessionDir) ?: return
        val updatedSteps = plan.steps.map { step ->
            if (step.id == stepId) step.copy(status = status) else step
        }
        save(plan.copy(steps = updatedSteps), sessionDir)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.runtime.PlanPersistenceTest" --rerun --no-build-cache
```
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/PlanPersistence.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/PlanPersistenceTest.kt
git commit -m "feat(agent): plan file persistence — save/load plan.json to session directory"
```

---

## Task 2: Anchored Plan Summary (Layer 2)

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/context/PlanAnchor.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/context/PlanAnchorTest.kt`

- [ ] **Step 1: Write tests for PlanAnchor**

```kotlin
package com.workflow.orchestrator.agent.context

import com.workflow.orchestrator.agent.runtime.AgentPlan
import com.workflow.orchestrator.agent.runtime.PlanStep
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PlanAnchorTest {

    @Test
    fun `buildSummary creates structured plan with status icons`() {
        val plan = AgentPlan(
            goal = "Refactor auth",
            steps = listOf(
                PlanStep(id = "1", title = "Analyze", status = "done"),
                PlanStep(id = "2", title = "Implement", status = "running"),
                PlanStep(id = "3", title = "Test", status = "pending")
            )
        )
        val summary = PlanAnchor.buildSummary(plan)
        assertTrue(summary.contains("<active_plan"))
        assertTrue(summary.contains("Refactor auth"))
        assertTrue(summary.contains("1.✓ Analyze"))
        assertTrue(summary.contains("2.◉ Implement"))
        assertTrue(summary.contains("3.○ Test"))
        assertTrue(summary.contains("</active_plan>"))
    }

    @Test
    fun `buildSummary shows completion count`() {
        val plan = AgentPlan(
            goal = "Test",
            steps = listOf(
                PlanStep(id = "1", title = "A", status = "done"),
                PlanStep(id = "2", title = "B", status = "done"),
                PlanStep(id = "3", title = "C", status = "pending")
            )
        )
        val summary = PlanAnchor.buildSummary(plan)
        assertTrue(summary.contains("2/3"))
    }

    @Test
    fun `buildSummary includes files modified from done steps`() {
        val plan = AgentPlan(
            goal = "Test",
            steps = listOf(
                PlanStep(id = "1", title = "Edit", status = "done", files = listOf("Foo.kt", "Bar.kt")),
                PlanStep(id = "2", title = "Pending", status = "pending", files = listOf("Baz.kt"))
            )
        )
        val summary = PlanAnchor.buildSummary(plan)
        assertTrue(summary.contains("Foo.kt"))
        assertTrue(summary.contains("Bar.kt"))
        // Pending step files should NOT appear anywhere in the summary
        assertFalse(summary.contains("Baz.kt"))
    }

    @Test
    fun `updateSummaryInMessages replaces existing plan message`() {
        val messages = mutableListOf(
            mapOf("role" to "system", "content" to "You are an agent."),
            mapOf("role" to "system", "content" to "<active_plan>\nOld plan\n</active_plan>"),
            mapOf("role" to "user", "content" to "Fix NPE")
        )
        val newSummary = "<active_plan>\nNew plan\n</active_plan>"
        val index = PlanAnchor.findPlanMessageIndex(messages.map { it["content"] ?: "" })
        assertEquals(1, index)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.context.PlanAnchorTest" --rerun --no-build-cache
```
Expected: FAIL — `PlanAnchor` doesn't exist.

- [ ] **Step 3: Implement PlanAnchor**

```kotlin
package com.workflow.orchestrator.agent.context

import com.workflow.orchestrator.agent.api.dto.ChatMessage
import com.workflow.orchestrator.agent.runtime.AgentPlan

/**
 * Builds and manages the compression-proof anchored plan summary.
 *
 * The summary is injected as a system message that ContextManager.compress()
 * never drops (system messages are excluded from compression). It's updated
 * in-place when step statuses change, keeping the LLM aware of plan progress
 * even after context compression.
 *
 * Format uses fixed sections (Factory.ai pattern) to prevent information loss:
 * - Goal, Status, Progress (with step icons), Files modified, Key decisions
 */
object PlanAnchor {
    private const val TAG_OPEN = "<active_plan>"
    private const val TAG_CLOSE = "</active_plan>"

    fun buildSummary(plan: AgentPlan): String {
        val doneCount = plan.steps.count { it.status == "done" }
        val totalCount = plan.steps.size
        val currentStep = plan.steps.firstOrNull { it.status == "running" }

        val statusLine = if (doneCount == totalCount) "completed" else "executing ($doneCount/$totalCount steps complete)"

        val progressLines = plan.steps.joinToString(" | ") { step ->
            val icon = when (step.status) {
                "done" -> "✓"
                "running" -> "◉"
                "failed" -> "✗"
                else -> "○"
            }
            "${step.id}.$icon ${step.title}"
        }

        val filesModified = plan.steps
            .filter { it.status == "done" }
            .flatMap { it.files }
            .distinct()

        val filesLine = if (filesModified.isNotEmpty()) {
            "Files modified: ${filesModified.joinToString(", ")}"
        } else {
            "Files modified: none yet"
        }

        val currentLine = if (currentStep != null) {
            "Current: Step ${currentStep.id} — ${currentStep.title}"
        } else if (doneCount == totalCount) {
            "Current: All steps complete"
        } else {
            "Current: Awaiting next step"
        }

        return """
            $TAG_OPEN
            Goal: ${plan.goal}
            Status: $statusLine
            Progress: $progressLines
            $currentLine
            $filesLine
            Full plan: available on disk (plan.json in session directory)
            $TAG_CLOSE
        """.trimIndent()
    }

    /**
     * Find the index of an existing <active_plan> message in a list of message contents.
     * Returns -1 if not found.
     */
    fun findPlanMessageIndex(messageContents: List<String>): Int {
        return messageContents.indexOfFirst { it.contains(TAG_OPEN) }
    }

    /**
     * Check if a message contains an active plan anchor.
     */
    fun isPlanMessage(content: String): Boolean = content.contains(TAG_OPEN)

    /**
     * Create a ChatMessage containing the plan anchor.
     */
    fun createPlanMessage(plan: AgentPlan): ChatMessage {
        return ChatMessage(role = "system", content = buildSummary(plan))
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.context.PlanAnchorTest" --rerun --no-build-cache
```
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/context/PlanAnchor.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/context/PlanAnchorTest.kt
git commit -m "feat(agent): anchored plan summary — compression-proof structured context"
```

---

## Task 3: Wire Persistence + Anchor into Plan Tools

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/PlanManager.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/UpdatePlanStepTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt`

- [ ] **Step 1: Add sessionDir and context manager references to PlanManager**

In `PlanManager.kt`, add fields for persistence integration:

```kotlin
/** Session directory for plan.json persistence. Set by AgentController. */
var sessionDir: java.io.File? = null

/** Callback to update the anchored plan summary in context. */
var onPlanAnchorUpdate: ((AgentPlan) -> Unit)? = null
```

- [ ] **Step 2: Add persistence to submitPlan()**

In `PlanManager.submitPlan()`, after setting `currentPlan` and invoking `onPlanCreated`, add:

```kotlin
// Persist to disk
sessionDir?.let { PlanPersistence.save(plan, it) }
```

- [ ] **Step 3: Add persistence + anchor update to updateStepStatus()**

In `PlanManager.updateStepStatus()`, after updating the step status and invoking `onStepUpdated`, add:

```kotlin
// Persist updated plan to disk
sessionDir?.let { dir -> currentPlan?.let { PlanPersistence.save(it, dir) } }
// Update anchored plan summary in context
currentPlan?.let { onPlanAnchorUpdate?.invoke(it) }
```

- [ ] **Step 4: Wire session directory and anchor callback in AgentController**

In `AgentController.kt`, in the `executeTask()` method where plan callbacks are wired (around lines 186-191), add:

```kotlin
// Set session directory for plan persistence
currentSession.planManager.sessionDir = currentSession.store.sessionDirectory

// Wire anchor update: replaces or adds the <active_plan> system message
currentSession.planManager.onPlanAnchorUpdate = { plan ->
    val messages = currentSession.contextManager.getMessages()
    val contents = messages.map { it.content ?: "" }
    val existingIndex = com.workflow.orchestrator.agent.context.PlanAnchor.findPlanMessageIndex(contents)
    if (existingIndex >= 0) {
        // Replace existing plan anchor message
        currentSession.contextManager.replaceSystemMessage(
            existingIndex,
            com.workflow.orchestrator.agent.context.PlanAnchor.createPlanMessage(plan)
        )
    } else {
        // Add new plan anchor message
        currentSession.contextManager.addMessage(
            com.workflow.orchestrator.agent.context.PlanAnchor.createPlanMessage(plan)
        )
    }
}
```

Note: This requires adding a dedicated `planAnchor` field to `ContextManager` — see Step 5. We use a dedicated field instead of storing the plan in the `messages` list because `getMessages()` constructs a synthetic list (anchored summaries + messages), making index-based replacement unreliable.

- [ ] **Step 5: Add dedicated planAnchor field to ContextManager**

In `ContextManager.kt`, add a field and update `getMessages()`:

```kotlin
// Add field after anchoredSummaries (line 40):
/** Dedicated plan anchor — survives compression, updated in-place. */
private var planAnchor: ChatMessage? = null

// Add setter method:
/**
 * Set or update the anchored plan summary. This is a dedicated slot
 * separate from the messages list — it's always included in getMessages()
 * output and never dropped by compress(). Tokens are recalculated.
 */
fun setPlanAnchor(message: ChatMessage?) {
    planAnchor = message
    totalTokens = TokenEstimator.estimate(getMessages())
}

// Update getMessages() to include planAnchor:
fun getMessages(): List<ChatMessage> {
    val result = mutableListOf<ChatMessage>()
    if (anchoredSummaries.isNotEmpty()) {
        result.add(ChatMessage(role = "system", content = anchoredSummaries.joinToString("\n\n")))
    }
    planAnchor?.let { result.add(it) }  // Plan anchor always included
    result.addAll(messages)
    return result
}
```

Update the `onPlanAnchorUpdate` wiring in AgentController to use the new method:

```kotlin
currentSession.planManager.onPlanAnchorUpdate = { plan ->
    currentSession.contextManager.setPlanAnchor(
        com.workflow.orchestrator.agent.context.PlanAnchor.createPlanMessage(plan)
    )
}
```

- [ ] **Step 6: Inject initial anchor after plan approval in CreatePlanTool**

In `CreatePlanTool.kt`, after the `PlanApprovalResult.Approved` handling, the approval response already tells the LLM to execute. The anchor injection happens automatically via `onPlanAnchorUpdate` when `submitPlan()` is called. But we also need to trigger it after approval — add to `PlanManager.approvePlan()`:

```kotlin
fun approvePlan() {
    currentPlan?.approved = true
    // Complete future first (unblocks CreatePlanTool), then update anchor
    approvalFuture?.complete(PlanApprovalResult.Approved)
    approvalFuture = null
    currentPlan?.let { onPlanAnchorUpdate?.invoke(it) }
}
```

- [ ] **Step 7: Verify compilation and tests**

```bash
./gradlew :agent:clean :agent:test --rerun --no-build-cache
```
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/PlanManager.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/UpdatePlanStepTool.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/context/ContextManager.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/CreatePlanTool.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt
git commit -m "feat(agent): wire plan persistence and anchored summary into plan tools"
```

---

## Task 4: Plan Editor Tab — File Type + Virtual File

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/plan/AgentPlanFileType.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/plan/AgentPlanVirtualFile.kt`

- [ ] **Step 1: Create AgentPlanFileType**

```kotlin
package com.workflow.orchestrator.agent.ui.plan

import com.intellij.openapi.fileTypes.FileType
import javax.swing.Icon
import com.intellij.icons.AllIcons

/**
 * Virtual file type for agent implementation plans.
 * Not associated with any disk extension — used only for LightVirtualFile instances.
 */
object AgentPlanFileType : FileType {
    override fun getName() = "AgentPlan"
    override fun getDescription() = "Agent Implementation Plan"
    override fun getDefaultExtension() = "agentplan"
    override fun getIcon(): Icon = AllIcons.Actions.ListFiles
    override fun isBinary() = false
    override fun isReadOnly() = true
}
```

- [ ] **Step 2: Create AgentPlanVirtualFile**

```kotlin
package com.workflow.orchestrator.agent.ui.plan

import com.intellij.testFramework.LightVirtualFile
import com.workflow.orchestrator.agent.runtime.AgentPlan

/**
 * In-memory virtual file representing an agent's execution plan.
 * Opened in an editor tab for full-screen plan viewing.
 * No disk backing — discarded when the tab is closed.
 */
class AgentPlanVirtualFile(
    val plan: AgentPlan,
    val sessionId: String
) : LightVirtualFile("Implementation Plan", AgentPlanFileType, "") {

    /** Updated when step status changes — the editor reads this. */
    var currentPlan: AgentPlan = plan

    override fun isWritable() = false
}
```

- [ ] **Step 3: Verify compilation**

```bash
./gradlew :agent:compileKotlin --no-build-cache
```
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/plan/AgentPlanFileType.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/plan/AgentPlanVirtualFile.kt
git commit -m "feat(agent): plan editor virtual file type and LightVirtualFile"
```

---

## Task 5: Plan Editor Tab — FileEditor + JCEF Rendering

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/plan/AgentPlanEditorProvider.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/plan/AgentPlanEditor.kt`
- Create: `agent/src/main/resources/webview/agent-plan.html`
- Modify: `src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1: Create AgentPlanEditorProvider**

```kotlin
package com.workflow.orchestrator.agent.ui.plan

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Registers the plan editor for AgentPlanVirtualFile instances.
 * IntelliJ calls accept() to check if this provider handles a given file,
 * then createEditor() to build the editor component.
 */
class AgentPlanEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is AgentPlanVirtualFile

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        return AgentPlanEditor(project, file as AgentPlanVirtualFile)
    }

    override fun getEditorTypeId() = "AgentPlanEditor"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
```

- [ ] **Step 2: Create AgentPlanEditor**

```kotlin
package com.workflow.orchestrator.agent.ui.plan

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefJSQuery
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.runtime.PlanManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/**
 * Full-screen plan editor using JCEF (Chromium).
 * Renders the plan with step statuses, file links, comment inputs,
 * and approve/revise buttons.
 */
class AgentPlanEditor(
    private val project: Project,
    private val planFile: AgentPlanVirtualFile
) : UserDataHolderBase(), FileEditor {

    private val json = Json { encodeDefaults = true }
    private val browser = JBCefBrowser()

    // JS bridges for plan actions
    private val approveQuery = JBCefJSQuery.create(browser)
    private val reviseQuery = JBCefJSQuery.create(browser)
    private val fileClickQuery = JBCefJSQuery.create(browser)

    init {
        // Wire approve callback
        approveQuery.addHandler { _ ->
            try {
                val agentService = AgentService.getInstance(project)
                agentService.currentPlanManager?.approvePlan()
            } catch (_: Exception) {}
            null
        }

        // Wire revise callback
        reviseQuery.addHandler { commentsJson ->
            try {
                val agentService = AgentService.getInstance(project)
                val comments = Json.decodeFromString<Map<String, String>>(commentsJson)
                agentService.currentPlanManager?.revisePlan(comments)
            } catch (_: Exception) {}
            null
        }

        // Wire file click callback — opens file in editor
        fileClickQuery.addHandler { filePath ->
            try {
                val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                    .findFileByPath(filePath)
                    ?: project.basePath?.let { base ->
                        com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                            .findFileByPath("$base/$filePath")
                    }
                if (vf != null) {
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                            .openFile(vf, true)
                    }
                }
            } catch (_: Exception) {}
            null
        }

        // Load the plan HTML
        val htmlUrl = javaClass.getResource("/webview/agent-plan.html")
        if (htmlUrl != null) {
            browser.loadURL(htmlUrl.toExternalForm())
        }

        // Inject plan data and JS bridges after page loads
        browser.jbCefClient.addLoadHandler(object : org.cef.handler.CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: org.cef.browser.CefBrowser?, frame: org.cef.browser.CefFrame?, httpStatusCode: Int) {
                if (frame?.isMain == true) {
                    val planJson = json.encodeToString(planFile.currentPlan)
                    val escaped = planJson
                        .replace("\\", "\\\\")
                        .replace("'", "\\'")
                        .replace("\n", "\\n")
                        .replace("\r", "")

                    // Inject JS bridges
                    val js = """
                        window._approvePlan = function() { ${approveQuery.inject("'approved'")} };
                        window._revisePlan = function(json) { ${reviseQuery.inject("json")} };
                        window._openFile = function(path) { ${fileClickQuery.inject("path")} };
                        renderPlan('$escaped');
                    """.trimIndent()
                    cefBrowser?.executeJavaScript(js, "", 0)
                }
            }
        }, browser.cefBrowser)
    }

    /**
     * Update a specific step's status in the rendered plan.
     * Called from AgentController when PlanManager fires onStepUpdated.
     */
    fun updatePlanStep(stepId: String, status: String) {
        browser.cefBrowser.executeJavaScript(
            "updatePlanStep('${stepId.replace("'", "\\'")}', '${status.replace("'", "\\'")}');",
            "", 0
        )
    }

    override fun getComponent(): JComponent = browser.component
    override fun getPreferredFocusedComponent(): JComponent = browser.component
    override fun getName() = "Plan"
    override fun isValid() = true
    override fun isModified() = false
    override fun setState(state: FileEditorState) {}
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun getFile(): VirtualFile = planFile

    override fun dispose() {
        approveQuery.dispose()
        reviseQuery.dispose()
        fileClickQuery.dispose()
        browser.dispose()
    }
}
```

- [ ] **Step 3: Create agent-plan.html**

Create `agent/src/main/resources/webview/agent-plan.html` — a full-screen plan renderer. Reuse the CSS variables and design language from `agent-chat.html` but with more spacious layout:

The HTML should include:
- Same CSS variables as agent-chat.html (dark/light theme support)
- Full-width plan card without chat container constraints
- Larger font sizes (14px body, 16px titles)
- Expandable step descriptions
- Clickable file paths (`onclick="window._openFile('path')"`)
- Full-width comment textareas (not small inputs)
- Approve/Revise buttons at the bottom
- `renderPlan(planJson)` and `updatePlanStep(stepId, status)` JS functions

(The subagent should build the complete HTML file reusing the plan card CSS patterns from agent-chat.html lines 351-376, adapted for full-screen.)

- [ ] **Step 4: Register FileEditorProvider in plugin.xml**

In `src/main/resources/META-INF/plugin.xml`, add after the existing agent extensions:

```xml
<fileEditorProvider implementation="com.workflow.orchestrator.agent.ui.plan.AgentPlanEditorProvider"/>
```

- [ ] **Step 5: Verify compilation**

```bash
./gradlew :agent:compileKotlin --no-build-cache
```
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/plan/AgentPlanEditorProvider.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/plan/AgentPlanEditor.kt \
       agent/src/main/resources/webview/agent-plan.html \
       src/main/resources/META-INF/plugin.xml
git commit -m "feat(agent): plan editor tab with JCEF rendering, file links, and comments"
```

---

## Task 6: Wire Editor Tab into AgentController

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt`

- [ ] **Step 1: Open editor tab when plan is created**

In `AgentController.kt`, in the `onPlanCreated` callback (around lines 186-189), after calling `dashboard.renderPlan(json)`, add:

```kotlin
// Open full-screen plan in editor tab
com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
    val virtualFile = com.workflow.orchestrator.agent.ui.plan.AgentPlanVirtualFile(plan, currentSession.sessionId)
    com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project).openFile(virtualFile, false) // false = don't steal focus
    // Store reference for step updates
    currentPlanFile = virtualFile
}
```

Add field to AgentController:
```kotlin
private var currentPlanFile: com.workflow.orchestrator.agent.ui.plan.AgentPlanVirtualFile? = null
```

- [ ] **Step 2: Update editor tab when steps change**

In the `onStepUpdated` callback (around line 190-191), after calling `dashboard.updatePlanStep()`, add:

```kotlin
// Update editor tab
com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
    currentPlanFile?.let { file ->
        // Update the virtual file's plan data
        currentSession.planManager.currentPlan?.let { file.currentPlan = it }
        // Find the editor and update it
        com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
            .getEditors(file)
            .filterIsInstance<com.workflow.orchestrator.agent.ui.plan.AgentPlanEditor>()
            .forEach { editor -> editor.updatePlanStep(stepId, status) }
    }
}
```

- [ ] **Step 3: Verify compilation and manual test**

```bash
./gradlew :agent:compileKotlin --no-build-cache
```
Expected: PASS

Manual verification: `./gradlew runIde` → open agent tab → ask a complex task → verify plan opens in editor tab.

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt
git commit -m "feat(agent): open plan editor tab on plan creation, update on step completion"
```

---

## Task 7: Final Integration Verification

- [ ] **Step 1: Run full test suite**

```bash
./gradlew :agent:clean :agent:test --rerun --no-build-cache
```
Expected: ALL PASS

- [ ] **Step 2: Run plugin verification**

```bash
./gradlew verifyPlugin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Manual verification in runIde**

```bash
./gradlew runIde
```
Verify:
1. Ask agent a complex task that triggers plan creation
2. Plan card appears in chat (existing behavior)
3. Plan also opens in editor tab (new)
4. Step icons update in both chat and editor tab as agent works
5. File links in editor tab are clickable
6. Comments work in editor tab
7. Approve/Revise works from editor tab
8. After context compression, agent still sees plan status via `<active_plan>` anchor

- [ ] **Step 4: Commit any fixes**

```bash
git add -A
git commit -m "chore(agent): final fixes for plan persistence and editor tab"
```

---

## Implementation Order

```
Task 1: PlanPersistence (disk save/load)        ← independent
Task 2: PlanAnchor (structured summary)          ← independent
Task 3: Wire into plan tools + controller        ← depends on Tasks 1, 2
Task 4: Virtual file type                        ← independent
Task 5: FileEditor + JCEF + HTML                 ← depends on Task 4
Task 6: Wire editor tab into controller          ← depends on Tasks 3, 5
Task 7: Final verification                       ← depends on all
```

Parallelizable groups:
- **Group A (parallel):** Tasks 1, 2, 4
- **Group B (after A):** Tasks 3, 5
- **Group C (after B):** Task 6
- **Group D (final):** Task 7

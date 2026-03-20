# Interactive Question/Option Chooser — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an `ask_questions` tool that renders an interactive question wizard inline in the agent chat with single/multi-select options, skip/back/cancel navigation, "Chat about this" notes, and a summary page with edit-any-question.

**Architecture:** `QuestionManager` (state machine + data classes) mirrors the `PlanManager` pattern. `AskQuestionsTool` uses `suspendCancellableCoroutine` to suspend while the user answers. JCEF renders the wizard in `agent-chat.html` with JS functions and bridges back to Kotlin.

**Tech Stack:** Kotlin 2.1.10, IntelliJ Platform 2025.1+, JCEF (JBCefBrowser, JBCefJSQuery), kotlinx.serialization

**Spec:** `docs/superpowers/specs/2026-03-20-interactive-question-chooser.md`

---

## File Structure

### New Files
| File | Responsibility |
|------|---------------|
| `agent/src/main/kotlin/.../runtime/QuestionManager.kt` | Data classes (QuestionSet, Question, QuestionOption, QuestionAnswer, QuestionResult) + state machine + future management |
| `agent/src/main/kotlin/.../tools/builtin/AskQuestionsTool.kt` | Tool implementation: validate params, submit to QuestionManager, suspend for result |
| `agent/src/test/kotlin/.../runtime/QuestionManagerTest.kt` | State machine, answer tracking, skip, cancel, clear tests |
| `agent/src/test/kotlin/.../tools/builtin/AskQuestionsToolTest.kt` | Parameter validation tests |

### Modified Files
| File | Change |
|------|--------|
| `agent/.../resources/webview/agent-chat.html` | Add question wizard JS functions + CSS |
| `agent/.../ui/AgentCefPanel.kt` | Add 6 JBCefJSQuery bridges + rendering methods |
| `agent/.../ui/AgentDashboardPanel.kt` | Add delegation methods for questions |
| `agent/.../ui/AgentController.kt` | Wire QuestionManager callbacks |
| `agent/.../AgentService.kt` | Add `currentQuestionManager` field, register tool |
| `agent/.../runtime/ConversationSession.kt` | Add `questionManager` field |
| `agent/.../tools/ToolCategoryRegistry.kt` | Add `ask_questions` to planning category |

---

## Task 1: QuestionManager — Data Classes + State Machine

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/QuestionManager.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/QuestionManagerTest.kt`

- [ ] **Step 1: Write tests**

```kotlin
package com.workflow.orchestrator.agent.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class QuestionManagerTest {

    private fun sampleQuestions() = QuestionSet(
        title = "Auth Setup",
        questions = listOf(
            Question(id = "q1", question = "Which auth?", type = "single", options = listOf(
                QuestionOption(id = "oauth2", label = "OAuth2", description = "Standard"),
                QuestionOption(id = "jwt", label = "JWT", description = "Lightweight")
            )),
            Question(id = "q2", question = "Integrations?", type = "multiple", options = listOf(
                QuestionOption(id = "ldap", label = "LDAP"),
                QuestionOption(id = "saml", label = "SAML")
            )),
            Question(id = "q3", question = "Token policy?", type = "single", options = listOf(
                QuestionOption(id = "short", label = "Short-lived"),
                QuestionOption(id = "long", label = "Long-lived")
            ))
        )
    )

    @Test
    fun `submitQuestions returns future and fires callback`() {
        val mgr = QuestionManager()
        var callbackFired = false
        mgr.onQuestionsCreated = { callbackFired = true }
        val future = mgr.submitQuestions(sampleQuestions())
        assertTrue(callbackFired)
        assertFalse(future.isDone)
    }

    @Test
    fun `answerQuestion stores answer`() {
        val mgr = QuestionManager()
        mgr.submitQuestions(sampleQuestions())
        mgr.answerQuestion("q1", listOf("oauth2"))
        val result = mgr.buildResult()
        assertEquals(listOf("oauth2"), result.answers["q1"]?.selectedOptions)
    }

    @Test
    fun `skipQuestion adds to skipped`() {
        val mgr = QuestionManager()
        mgr.submitQuestions(sampleQuestions())
        mgr.skipQuestion("q1")
        val result = mgr.buildResult()
        assertTrue(result.skipped.contains("q1"))
        assertFalse(result.answers.containsKey("q1"))
    }

    @Test
    fun `setChatMessage stores note with option label`() {
        val mgr = QuestionManager()
        mgr.submitQuestions(sampleQuestions())
        mgr.answerQuestion("q1", listOf("oauth2"))
        mgr.setChatMessage("q1", "OAuth2", "Need legacy support")
        val result = mgr.buildResult()
        assertEquals("Re: OAuth2 — Need legacy support", result.answers["q1"]?.chatMessage)
    }

    @Test
    fun `submitAnswers completes future`() {
        val mgr = QuestionManager()
        val future = mgr.submitQuestions(sampleQuestions())
        mgr.answerQuestion("q1", listOf("oauth2"))
        mgr.skipQuestion("q2")
        mgr.skipQuestion("q3")
        mgr.submitAnswers()
        assertTrue(future.isDone)
        val result = future.get()
        assertEquals(1, result.answers.size)
        assertEquals(2, result.skipped.size)
        assertFalse(result.cancelled)
    }

    @Test
    fun `cancelQuestions completes future with cancelled`() {
        val mgr = QuestionManager()
        val future = mgr.submitQuestions(sampleQuestions())
        mgr.cancelQuestions()
        assertTrue(future.isDone)
        assertTrue(future.get().cancelled)
    }

    @Test
    fun `editQuestion fires onShowQuestion callback`() {
        val mgr = QuestionManager()
        mgr.submitQuestions(sampleQuestions())
        var shownIndex = -1
        mgr.onShowQuestion = { shownIndex = it }
        mgr.editQuestion("q2")
        assertEquals(1, shownIndex)  // q2 is at index 1
    }

    @Test
    fun `clear resets all state`() {
        val mgr = QuestionManager()
        mgr.submitQuestions(sampleQuestions())
        mgr.answerQuestion("q1", listOf("oauth2"))
        mgr.clear()
        assertNull(mgr.currentQuestions)
        val result = mgr.buildResult()
        assertTrue(result.answers.isEmpty())
    }

    @Test
    fun `buildResult marks unanswered questions as skipped`() {
        val mgr = QuestionManager()
        mgr.submitQuestions(sampleQuestions())
        mgr.answerQuestion("q1", listOf("oauth2"))
        // q2 and q3 not answered and not explicitly skipped
        val result = mgr.buildResult()
        assertTrue(result.skipped.contains("q2"))
        assertTrue(result.skipped.contains("q3"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.runtime.QuestionManagerTest" --rerun --no-build-cache
```
Expected: FAIL — `QuestionManager` doesn't exist.

- [ ] **Step 3: Implement QuestionManager**

```kotlin
package com.workflow.orchestrator.agent.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.CompletableFuture

@Serializable
data class QuestionSet(
    val title: String? = null,
    val questions: List<Question>
)

@Serializable
data class Question(
    val id: String,
    val question: String,
    val type: String,  // "single" or "multiple"
    val options: List<QuestionOption>
)

@Serializable
data class QuestionOption(
    val id: String,
    val label: String,
    val description: String = ""
)

@Serializable
data class QuestionAnswer(
    val questionId: String,
    val selectedOptions: List<String>,
    val chatMessage: String? = null
)

@Serializable
data class QuestionResult(
    val answers: Map<String, QuestionAnswer> = emptyMap(),
    val skipped: List<String> = emptyList(),
    val cancelled: Boolean = false
)

class QuestionManager {
    companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }

    var currentQuestions: QuestionSet? = null
        private set
    private var submissionFuture: CompletableFuture<QuestionResult>? = null
    private val answers = mutableMapOf<String, QuestionAnswer>()
    private val skipped = mutableSetOf<String>()

    // UI callbacks
    var onQuestionsCreated: ((QuestionSet) -> Unit)? = null
    var onShowQuestion: ((Int) -> Unit)? = null
    var onShowSummary: ((QuestionResult) -> Unit)? = null
    var onSubmitted: (() -> Unit)? = null

    fun submitQuestions(questionSet: QuestionSet): CompletableFuture<QuestionResult> {
        clear()
        currentQuestions = questionSet
        submissionFuture = CompletableFuture()
        onQuestionsCreated?.invoke(questionSet)
        return submissionFuture!!
    }

    fun answerQuestion(questionId: String, selectedOptions: List<String>) {
        skipped.remove(questionId)
        val existing = answers[questionId]
        answers[questionId] = QuestionAnswer(
            questionId = questionId,
            selectedOptions = selectedOptions,
            chatMessage = existing?.chatMessage
        )
    }

    fun skipQuestion(questionId: String) {
        answers.remove(questionId)
        skipped.add(questionId)
    }

    fun setChatMessage(questionId: String, optionLabel: String, message: String) {
        val existing = answers[questionId]
        if (existing != null) {
            answers[questionId] = existing.copy(chatMessage = "Re: $optionLabel — $message")
        } else {
            answers[questionId] = QuestionAnswer(
                questionId = questionId,
                selectedOptions = emptyList(),
                chatMessage = "Re: $optionLabel — $message"
            )
        }
        // Transition to summary
        onShowSummary?.invoke(buildResult())
    }

    fun submitAnswers() {
        val result = buildResult()
        submissionFuture?.complete(result)
        submissionFuture = null
        onSubmitted?.invoke()
    }

    fun cancelQuestions() {
        val result = QuestionResult(cancelled = true)
        submissionFuture?.complete(result)
        submissionFuture = null
        onSubmitted?.invoke()
    }

    fun editQuestion(questionId: String) {
        val questions = currentQuestions?.questions ?: return
        val index = questions.indexOfFirst { it.id == questionId }
        if (index >= 0) {
            skipped.remove(questionId)
            onShowQuestion?.invoke(index)
        }
    }

    fun buildResult(): QuestionResult {
        val allIds = currentQuestions?.questions?.map { it.id } ?: emptyList()
        val actualSkipped = allIds.filter { it !in answers && it in skipped } +
            allIds.filter { it !in answers && it !in skipped }
        return QuestionResult(
            answers = answers.toMap(),
            skipped = actualSkipped.distinct()
        )
    }

    fun clear() {
        currentQuestions = null
        submissionFuture = null
        answers.clear()
        skipped.clear()
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.runtime.QuestionManagerTest" --rerun --no-build-cache
```
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/QuestionManager.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/QuestionManagerTest.kt
git commit -m "feat(agent): QuestionManager state machine with data classes and tests"
```

---

## Task 2: AskQuestionsTool — Validation + Suspension

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/AskQuestionsTool.kt`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/AskQuestionsToolTest.kt`

- [ ] **Step 1: Write tests**

```kotlin
package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AskQuestionsToolTest {

    private val project = mockk<Project>(relaxed = true)
    private val tool = AskQuestionsTool()

    @Test
    fun `tool metadata is correct`() {
        assertEquals("ask_questions", tool.name)
        assertTrue(tool.parameters.required.contains("questions"))
        assertTrue(tool.description.contains("structured questions"))
    }

    @Test
    fun `returns error when questions param is missing`() = runTest {
        val params = buildJsonObject { put("title", "Test") }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("'questions' parameter required"))
    }

    @Test
    fun `returns error for empty questions array`() = runTest {
        val params = buildJsonObject { put("questions", "[]") }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("At least one question required"))
    }

    @Test
    fun `returns error for question with no options`() = runTest {
        val params = buildJsonObject {
            put("questions", """[{"id":"q1","question":"Test?","type":"single","options":[]}]""")
        }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("must have at least one option"))
    }

    @Test
    fun `returns error for duplicate question IDs`() = runTest {
        val params = buildJsonObject {
            put("questions", """[
                {"id":"q1","question":"A?","type":"single","options":[{"id":"a","label":"A"}]},
                {"id":"q1","question":"B?","type":"single","options":[{"id":"b","label":"B"}]}
            ]""")
        }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("Duplicate question ID"))
    }

    @Test
    fun `returns error for more than 20 questions`() = runTest {
        val questions = (1..21).joinToString(",") { """{"id":"q$it","question":"Q$it?","type":"single","options":[{"id":"a","label":"A"}]}""" }
        val params = buildJsonObject { put("questions", "[$questions]") }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("Maximum 20 questions"))
    }

    @Test
    fun `returns error for invalid question type`() = runTest {
        val params = buildJsonObject {
            put("questions", """[{"id":"q1","question":"Test?","type":"invalid","options":[{"id":"a","label":"A"}]}]""")
        }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("type must be"))
    }

    @Test
    fun `returns error for duplicate option IDs within question`() = runTest {
        val params = buildJsonObject {
            put("questions", """[{"id":"q1","question":"Test?","type":"single","options":[
                {"id":"a","label":"A"},{"id":"a","label":"B"}
            ]}]""")
        }
        val result = tool.execute(params, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("Duplicate option ID"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.builtin.AskQuestionsToolTest" --rerun --no-build-cache
```
Expected: FAIL

- [ ] **Step 3: Implement AskQuestionsTool**

```kotlin
package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.*
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AskQuestionsTool : AgentTool {
    override val name = "ask_questions"
    override val description = "Ask the user structured questions with options. Use for requirement gathering, decision points, or when you need the user to choose between approaches. Each question can be single-select or multi-select. Returns structured answers."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "title" to ParameterProperty(type = "string", description = "Optional title for the question set"),
            "questions" to ParameterProperty(type = "string", description = "JSON array of questions. Each: {id, question, type: 'single'|'multiple', options: [{id, label, description}]}")
        ),
        required = listOf("questions")
    )
    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.CODER, WorkerType.ANALYZER)

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val questionsJson = params["questions"]?.jsonPrimitive?.content
            ?: return errorResult("'questions' parameter required")
        val title = params["title"]?.jsonPrimitive?.content

        // Parse and validate
        val questions = try {
            json.decodeFromString<List<Question>>(questionsJson)
        } catch (e: Exception) {
            return errorResult("Invalid questions JSON: ${e.message}")
        }

        if (questions.isEmpty()) return errorResult("At least one question required")
        if (questions.size > 20) return errorResult("Maximum 20 questions allowed")

        // Validate IDs unique
        val questionIds = questions.map { it.id }
        val dupQid = questionIds.groupBy { it }.filter { it.value.size > 1 }.keys.firstOrNull()
        if (dupQid != null) return errorResult("Duplicate question ID: $dupQid")

        for (q in questions) {
            if (q.options.isEmpty()) return errorResult("Question '${q.id}' must have at least one option")
            if (q.options.size > 10) return errorResult("Question '${q.id}': maximum 10 options per question")
            if (q.type !in setOf("single", "multiple")) return errorResult("Question '${q.id}': type must be 'single' or 'multiple'")
            val dupOid = q.options.groupBy { it.id }.filter { it.value.size > 1 }.keys.firstOrNull()
            if (dupOid != null) return errorResult("Duplicate option ID: $dupOid in question '${q.id}'")
        }

        val questionSet = QuestionSet(title = title, questions = questions)

        // Get QuestionManager
        val questionManager = try {
            AgentService.getInstance(project).currentQuestionManager
        } catch (_: Exception) { null }
            ?: return errorResult("No active session for question management")

        if (questionManager.currentQuestions != null) {
            return errorResult("A question set is already active. Wait for the current one to complete.")
        }

        // Submit and suspend
        val result = try {
            withTimeoutOrNull(600_000L) {
                suspendCancellableCoroutine<QuestionResult> { cont ->
                    val future = questionManager.submitQuestions(questionSet)
                    cont.invokeOnCancellation { future.cancel(true) }
                    future.whenComplete { value, error ->
                        if (error != null) {
                            if (!cont.isCompleted) cont.resumeWithException(error)
                        } else {
                            if (!cont.isCompleted) cont.resume(value)
                        }
                    }
                }
            } ?: return errorResult("Questions timed out after 10 minutes. Please try again.")
        } catch (e: CancellationException) {
            return ToolResult("Questions were cancelled.", "Cancelled", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        if (result.cancelled) {
            return ToolResult(
                content = "User cancelled the question wizard without submitting.",
                summary = "Questions cancelled",
                tokenEstimate = 10
            )
        }

        // Format result
        val answeredCount = result.answers.size
        val totalCount = questions.size
        val sb = StringBuilder("User answered $answeredCount of $totalCount questions:\n\n")

        for (q in questions) {
            val answer = result.answers[q.id]
            sb.append("Q (${q.id}): ${q.question}\n")
            if (answer != null) {
                sb.append("  Type: ${q.type}\n")
                val selectedLabels = answer.selectedOptions.map { optId ->
                    val opt = q.options.find { it.id == optId }
                    "$optId — \"${opt?.label ?: optId}\""
                }
                sb.append("  Selected: ${selectedLabels.joinToString(", ")}\n")
                if (answer.chatMessage != null) {
                    sb.append("  Note: \"${answer.chatMessage}\"\n")
                }
            } else {
                sb.append("  Skipped\n")
            }
            sb.append("\n")
        }

        return ToolResult(
            content = sb.toString().trim(),
            summary = "Answered $answeredCount/$totalCount questions",
            tokenEstimate = sb.length / 4
        )
    }

    private fun errorResult(msg: String) = ToolResult(
        "Error: $msg", "Question error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
    )
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew :agent:test --tests "com.workflow.orchestrator.agent.tools.builtin.AskQuestionsToolTest" --rerun --no-build-cache
```
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/AskQuestionsTool.kt \
       agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/builtin/AskQuestionsToolTest.kt
git commit -m "feat(agent): ask_questions tool with validation and suspension"
```

---

## Task 3: Register Tool + Add to System

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt:37,95-97`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ConversationSession.kt:56`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ToolCategoryRegistry.kt:92-99`

- [ ] **Step 1: Add currentQuestionManager to AgentService**

After `var currentPlanManager: PlanManager? = null` (line 37), add:
```kotlin
var currentQuestionManager: QuestionManager? = null
```

In the tool registration section (around line 95-97), after `register(UpdatePlanStepTool())`, add:
```kotlin
register(AskQuestionsTool())
```

Add import: `import com.workflow.orchestrator.agent.tools.builtin.AskQuestionsTool`

- [ ] **Step 2: Add questionManager to ConversationSession**

After `val planManager: PlanManager = PlanManager()` (line 56), add:
```kotlin
val questionManager: QuestionManager = QuestionManager()
```

Add import: `import com.workflow.orchestrator.agent.runtime.QuestionManager`

- [ ] **Step 3: Add ask_questions to ToolCategoryRegistry planning category**

In the planning category (lines 92-99), update tools list:
```kotlin
tools = listOf("create_plan", "update_plan_step", "ask_questions")
```

- [ ] **Step 4: Verify compilation**

```bash
./gradlew :agent:compileKotlin --no-build-cache
```
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/ConversationSession.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ToolCategoryRegistry.kt
git commit -m "feat(agent): register ask_questions tool and wire QuestionManager into session"
```

---

## Task 4: JCEF Question Wizard — HTML/CSS/JS

**Files:**
- Modify: `agent/src/main/resources/webview/agent-chat.html`

- [ ] **Step 1: Add question wizard CSS**

Add after the existing `.plan-btn.revise:hover` styles (around line 376), new CSS for the question wizard. Use the same design language as plan cards:

- `.question-wizard` — card container (same style as `.plan-card`)
- `.question-header` — progress indicator + cancel button
- `.question-option` — option row with radio/checkbox, label, description, "Chat about this"
- `.question-option.selected` — highlighted border
- `.question-option-radio` / `.question-option-checkbox` — selection indicators
- `.question-nav` — Back/Skip/Next buttons
- `.question-summary` — summary card with editable answers
- `.question-summary-item` — individual answer row with Edit button
- `.question-chat-banner` — top banner when chat-about is active
- `.question-disabled-input` — styled disabled chat input

- [ ] **Step 2: Add question wizard JS functions**

Add after the existing `revisePlanAction()` function (around line 833):

**`showQuestions(questionsJson)`** — parses JSON, renders the first question card with all options. Disables chat input. Stores questions data in a global `_questionData` variable.

**`showQuestion(index)`** — renders a specific question by index. Shows Back (if index > 0), Skip, Next buttons. Updates progress dots.

**`showQuestionSummary(summaryJson)`** — renders the summary page with answered/skipped questions, Edit buttons, Cancel/Retry/Submit buttons.

**`enableChatAbout(optionLabel)`** — shows chat-about banner at top, enables chat input with placeholder "Add your note about [optionLabel]...". Sets a `_chatAboutMode` flag.

**`enableChatInput()`** — removes question wizard from DOM, re-enables normal chat input.

**`updateQuestionNav(index)`** — helper to update Back/Skip/Next button states.

Each option card has:
- Click handler to select/deselect (radio for single, checkbox for multiple)
- "Chat about this" button that calls `enableChatAbout()` and auto-selects the option

- [ ] **Step 3: Add JS→Kotlin callback wiring**

The JS functions call `window._questionAnswered(id, json)`, `window._questionSkipped(id)`, `window._chatAboutOption(qid, label, msg)`, `window._questionsSubmitted()`, `window._questionsCancelled()`, `window._editQuestion(id)`. These are injected by `AgentCefPanel`.

Handle the chat-about submit: when `_chatAboutMode` is true and user presses Enter in the chat input, intercept the submit, extract the message, call `window._chatAboutOption(questionId, optionLabel, message)`, and prevent normal send.

- [ ] **Step 4: Verify HTML loads without errors**

```bash
./gradlew :agent:compileKotlin --no-build-cache
```
Expected: PASS (HTML is a resource, no compilation needed, but verify no syntax issues)

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/resources/webview/agent-chat.html
git commit -m "feat(agent): question wizard HTML/CSS/JS in agent chat"
```

---

## Task 5: JCEF Bridges — AgentCefPanel + AgentDashboardPanel

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt:96-165`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentDashboardPanel.kt:219-239`

- [ ] **Step 1: Add JBCefJSQuery bridges to AgentCefPanel**

After the existing plan queries (around line 110), create 6 new queries:

```kotlin
// Question wizard bridges
private val questionAnsweredQuery = JBCefJSQuery.create(browser)
private val questionSkippedQuery = JBCefJSQuery.create(browser)
private val chatAboutQuery = JBCefJSQuery.create(browser)
private val questionsSubmittedQuery = JBCefJSQuery.create(browser)
private val questionsCancelledQuery = JBCefJSQuery.create(browser)
private val editQuestionQuery = JBCefJSQuery.create(browser)
```

Add callback properties:
```kotlin
var onQuestionAnswered: ((String, String) -> Unit)? = null
var onQuestionSkipped: ((String) -> Unit)? = null
var onChatAboutOption: ((String, String, String) -> Unit)? = null
var onQuestionsSubmitted: (() -> Unit)? = null
var onQuestionsCancelled: (() -> Unit)? = null
var onEditQuestion: ((String) -> Unit)? = null
```

Wire handlers (same pattern as planApproveQuery):
```kotlin
questionAnsweredQuery.addHandler { data ->
    // data = "questionId:optionsJson"
    val sep = data.indexOf(':')
    if (sep > 0) onQuestionAnswered?.invoke(data.substring(0, sep), data.substring(sep + 1))
    null
}
// ... similar for other 5 queries
```

- [ ] **Step 2: Inject JS bridges in loadHandler**

In the loadHandler (around lines 134-157), add alongside existing plan bridge injection:

```kotlin
js("window._questionAnswered = function(qid, opts) { ${questionAnsweredQuery.inject("qid + ':' + opts")} }")
js("window._questionSkipped = function(qid) { ${questionSkippedQuery.inject("qid")} }")
js("window._chatAboutOption = function(qid, label, msg) { ${chatAboutQuery.inject("qid + ':' + label + ':' + msg")} }")
js("window._questionsSubmitted = function() { ${questionsSubmittedQuery.inject("'submit'")} }")
js("window._questionsCancelled = function() { ${questionsCancelledQuery.inject("'cancel'")} }")
js("window._editQuestion = function(qid) { ${editQuestionQuery.inject("qid")} }")
```

- [ ] **Step 3: Add rendering methods**

```kotlin
fun showQuestions(questionsJson: String) { callJs("showQuestions(${jsonStr(questionsJson)})") }
fun showQuestion(index: Int) { callJs("showQuestion($index)") }
fun showQuestionSummary(summaryJson: String) { callJs("showQuestionSummary(${jsonStr(summaryJson)})") }
// enableChatInput() already exists or add: fun enableChatInput() { callJs("enableChatInput()") }
```

- [ ] **Step 4: Add delegation methods to AgentDashboardPanel**

After existing plan delegation methods (around line 239), add:

```kotlin
fun showQuestions(questionsJson: String) { cefPanel?.showQuestions(questionsJson) }
fun showQuestion(index: Int) { cefPanel?.showQuestion(index) }
fun showQuestionSummary(summaryJson: String) { cefPanel?.showQuestionSummary(summaryJson) }
fun enableChatInput() { cefPanel?.enableChatInput() }

fun setCefQuestionCallbacks(
    onAnswered: (String, String) -> Unit,
    onSkipped: (String) -> Unit,
    onChatAbout: (String, String, String) -> Unit,
    onSubmitted: () -> Unit,
    onCancelled: () -> Unit,
    onEdit: (String) -> Unit
) {
    cefPanel?.onQuestionAnswered = onAnswered
    cefPanel?.onQuestionSkipped = onSkipped
    cefPanel?.onChatAboutOption = onChatAbout
    cefPanel?.onQuestionsSubmitted = onSubmitted
    cefPanel?.onQuestionsCancelled = onCancelled
    cefPanel?.onEditQuestion = onEdit
}
```

- [ ] **Step 5: Dispose new queries**

In `AgentCefPanel.dispose()`, add disposal of all 6 new queries.

- [ ] **Step 6: Verify compilation**

```bash
./gradlew :agent:compileKotlin --no-build-cache
```
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt \
       agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentDashboardPanel.kt
git commit -m "feat(agent): JCEF bridges for question wizard (6 callbacks + rendering methods)"
```

---

## Task 6: AgentController — Wire Everything Together

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt:183-219`

- [ ] **Step 1: Wire QuestionManager callbacks**

In `executeTask()`, after the existing plan callback wiring (around line 219), add:

```kotlin
// Wire QuestionManager
try {
    val agentSvc = AgentService.getInstance(project)
    agentSvc.currentQuestionManager = currentSession.questionManager
} catch (_: Exception) {}

currentSession.questionManager.onQuestionsCreated = { questionSet ->
    val json = QuestionManager.json.encodeToString(
        com.workflow.orchestrator.agent.runtime.QuestionSet.serializer(), questionSet
    )
    dashboard.showQuestions(json)
}
currentSession.questionManager.onShowQuestion = { index ->
    dashboard.showQuestion(index)
}
currentSession.questionManager.onShowSummary = { result ->
    val json = QuestionManager.json.encodeToString(
        com.workflow.orchestrator.agent.runtime.QuestionResult.serializer(), result
    )
    dashboard.showQuestionSummary(json)
}
currentSession.questionManager.onSubmitted = {
    dashboard.enableChatInput()
}
```

- [ ] **Step 2: Wire JCEF→QuestionManager callbacks**

After the plan callbacks wiring (setCefPlanCallbacks), add:

```kotlin
dashboard.setCefQuestionCallbacks(
    onAnswered = { questionId, optionsJson ->
        val options = kotlinx.serialization.json.Json.decodeFromString<List<String>>(optionsJson)
        currentSession.questionManager.answerQuestion(questionId, options)
    },
    onSkipped = { questionId ->
        currentSession.questionManager.skipQuestion(questionId)
    },
    onChatAbout = { questionId, optionLabel, message ->
        currentSession.questionManager.setChatMessage(questionId, optionLabel, message)
    },
    onSubmitted = {
        currentSession.questionManager.submitAnswers()
    },
    onCancelled = {
        currentSession.questionManager.cancelQuestions()
    },
    onEdit = { questionId ->
        currentSession.questionManager.editQuestion(questionId)
    }
)
```

- [ ] **Step 3: Clean up on new session**

In `newChat()`, alongside `currentPlanFile = null`, add:
```kotlin
session?.questionManager?.clear()
```

- [ ] **Step 4: Verify compilation and tests**

```bash
./gradlew :agent:clean :agent:test --rerun --no-build-cache
```
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt
git commit -m "feat(agent): wire QuestionManager callbacks into AgentController"
```

---

## Task 7: Final Verification

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

- [ ] **Step 3: Verify no stale references**

```bash
grep -r "retryQuestion\b" agent/src/ --include="*.kt"
```
Expected: No matches (renamed to editQuestion)

- [ ] **Step 4: Commit any fixes**

```bash
git add -A && git commit -m "chore(agent): final cleanup for question chooser"
```

---

## Implementation Order

```
Task 1: QuestionManager (data classes + state machine)     ← independent
Task 2: AskQuestionsTool (validation + suspension)          ← depends on Task 1
Task 3: Register tool + wire into system                    ← depends on Task 2
Task 4: JCEF question wizard HTML/CSS/JS                    ← independent (UI only)
Task 5: JCEF bridges (AgentCefPanel + Dashboard)            ← depends on Task 4
Task 6: AgentController wiring                              ← depends on Tasks 3, 5
Task 7: Final verification                                  ← depends on all
```

Parallelizable groups:
- **Group A (parallel):** Tasks 1, 4
- **Group B (after Task 1):** Tasks 2, 3
- **Group C (after Task 4):** Task 5
- **Group D (after B+C):** Task 6
- **Group E (final):** Task 7

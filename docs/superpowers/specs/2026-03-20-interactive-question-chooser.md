# Interactive Question/Option Chooser — Design Spec

## Problem

The agent currently communicates with users only via free-text chat. When the LLM needs structured input (choosing between approaches, selecting integrations, confirming requirements), it relies on the user typing answers in natural language. This is slow, ambiguous, and doesn't scale to multiple related questions.

## Solution

A new `ask_questions` tool that renders an interactive question wizard inline in the chat. The LLM provides structured questions with options (single or multi-select). The user answers visually, can add notes via "Chat about this", reviews a summary, and submits. The tool returns structured results to the LLM.

## Use Cases

1. **Requirement gathering** — LLM asks 3-10 questions before starting work (e.g., "Which auth approach? Which integrations? What token policy?")
2. **Decision points** — LLM pauses mid-task with 1-2 questions (e.g., "I found 3 patterns. Which should I follow?")

---

## Tool Interface

### `ask_questions` Tool

```
name: "ask_questions"
description: "Ask the user structured questions with options. Use for requirement gathering,
  decision points, or any time you need the user to choose between approaches. Each question
  can be single-select or multi-select. The user can add notes to options via 'Chat about this'.
  Returns structured answers."
parameters:
  title: String (optional) — overall title for the question set
  questions: String (required) — JSON array of questions, each with:
    id: String — unique question ID (must be unique across all questions)
    question: String — the question text
    type: "single" | "multiple" — selection mode
    options: Array of { id: String (unique within question), label: String, description: String }
```

### Validation Rules

- At least 1 question required
- Each question must have at least 1 option
- Max 20 questions, max 10 options per question
- Question IDs must be unique across all questions
- Option IDs must be unique within each question
- `type` must be "single" or "multiple"

### Tool Result Format

```
User answered 2 of 3 questions:

Q1 (auth-approach): Which authentication approach?
  Type: single
  Selected: oauth2 — "OAuth2 with Spring Security"
  Note: "Re: OAuth2 with Spring Security — Need legacy token support during migration"

Q2 (integrations): Which enterprise integrations?
  Type: multiple
  Selected: ldap — "LDAP / Active Directory", saml — "SAML 2.0"

Q3 (token-policy): Token expiration policy?
  Skipped
```

If cancelled:
```
User cancelled the question wizard without submitting.
```

### Tool Behavior

- `allowedWorkers`: `setOf(WorkerType.ORCHESTRATOR, WorkerType.CODER, WorkerType.ANALYZER)` — available to the main agent and most workers
- Suspension: Uses `suspendCancellableCoroutine` + `CompletableFuture` (same pattern as `CreatePlanTool`)
- Timeout: 10 minutes (same as plan approval)
- On timeout: returns error "Questions timed out"
- On cancel: returns "User cancelled" (not an error — LLM should handle gracefully)
- On submit: returns structured result with all answers

---

## Data Model

```kotlin
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
    val selectedOptions: List<String>,  // option IDs
    val chatMessage: String? = null     // per-question note from "Chat about this"
    // Format: "Re: [option label] — [user's message]"
    // The triggering option's label is prepended for context
)

@Serializable
data class QuestionResult(
    val answers: Map<String, QuestionAnswer>,  // questionId → answer
    val skipped: List<String>,                  // questionIds that were skipped
    val cancelled: Boolean = false              // true if user clicked Cancel
)
```

**Chat message is per-question, not per-option.** When the user clicks "Chat about this" on a specific option, the option's label is prepended to the user's message for context (e.g., "Re: OAuth2 — I want legacy token support"). This avoids a complex per-option note data model while preserving which option triggered the note.

---

## State Machine

```
                                    ┌─────────────┐
                                    │  CANCELLED   │
                                    └──────▲───────┘
                                           │ (Cancel button)
ANSWERING(N) ──→ ANSWERING(N+1) ──→ SUMMARY ──→ SUBMITTED
    ↑    │           ↑                  │
    │    │           │                  │ (Edit click)
    │    └─ Back ────┘                  │
    │                                   │
    ├── CHATTING ──→ SUMMARY            │
    │                                   │
    └───────────────────────────────────┘
```

### States

| State | Description | Chat Input |
|-------|-------------|-----------|
| ANSWERING(N) | Showing question N, user selects option(s) and clicks Next/Skip | Disabled |
| CHATTING | User clicked "Chat about this", types one message about the selected option | Enabled (with option context placeholder) |
| SUMMARY | Review page showing all answers, Edit/Answer buttons, Submit/Retry/Cancel | Disabled |
| SUBMITTED | Future completed, results returned to LLM | Enabled (normal chat) |
| CANCELLED | User dismissed the wizard, future completed with cancelled=true | Enabled (normal chat) |

### Transitions

| From | To | Trigger |
|------|-----|---------|
| ANSWERING(N) | ANSWERING(N+1) | User clicks Next (answer stored) |
| ANSWERING(N) | ANSWERING(N+1) | User clicks Skip (question added to skipped list) |
| ANSWERING(N) | ANSWERING(N-1) | User clicks Back (N > 0) |
| ANSWERING(last) | SUMMARY | User clicks Next/Skip on final question |
| ANSWERING(any) | CHATTING | User clicks "Chat about this" on an option (option auto-selected as answer) |
| CHATTING | SUMMARY | User presses Enter (note stored with option label prefix, jump to summary) |
| SUMMARY | ANSWERING(N) | User clicks Edit/Answer on question N |
| SUMMARY | SUBMITTED | User clicks Submit |
| SUMMARY | CANCELLED | User clicks Cancel |
| ANSWERING(any) | CANCELLED | User clicks Cancel (available in header) |

---

## QuestionManager

Same pattern as `PlanManager`:

```kotlin
class QuestionManager {
    var currentQuestions: QuestionSet? = null
    private var submissionFuture: CompletableFuture<QuestionResult>? = null
    private val answers = mutableMapOf<String, QuestionAnswer>()
    private val skipped = mutableSetOf<String>()

    // UI callbacks (set by AgentController)
    var onQuestionsCreated: ((QuestionSet) -> Unit)? = null
    var onShowQuestion: ((Int) -> Unit)? = null           // navigate to question index
    var onShowSummary: ((QuestionResult) -> Unit)? = null  // show summary page
    var onSubmitted: (() -> Unit)? = null                  // re-enable chat

    fun submitQuestions(questionSet: QuestionSet): CompletableFuture<QuestionResult>
    fun answerQuestion(questionId: String, selectedOptions: List<String>)
    fun skipQuestion(questionId: String)
    fun setChatMessage(questionId: String, optionLabel: String, message: String)
    // Stores: "Re: $optionLabel — $message"
    fun submitAnswers()    // completes future with QuestionResult, fires onSubmitted
    fun cancelQuestions()  // completes future with cancelled=true, fires onSubmitted
    fun editQuestion(questionId: String)  // fires onShowQuestion for that question's index
    fun buildResult(): QuestionResult
    fun clear()  // resets all state for reuse
}
```

**Lifecycle:** `QuestionManager` is created per `ConversationSession`. It's set on `AgentService.currentQuestionManager` when `executeTask()` runs (same as `currentPlanManager`). When the session ends or a new session starts, the old manager is replaced.

---

## UI Rendering (JCEF)

All rendering happens inline in `agent-chat.html`. No separate HTML file.

### New JS Functions

| Function | Purpose |
|----------|---------|
| `showQuestions(questionsJson)` | Render question wizard card, disable chat input, show first question |
| `showQuestion(index)` | Navigate to specific question (for Back/Edit) |
| `showQuestionSummary(summaryJson)` | Render summary/submit page |
| `enableChatAbout(optionLabel)` | Enable chat input with "Add your note about [optionLabel]..." placeholder |
| `enableChatInput()` | Re-enable normal chat input after submit/cancel |

### New JS → Kotlin Callbacks (via JBCefJSQuery)

| Callback | Data | Trigger |
|----------|------|---------|
| `questionAnswered(questionId, selectedOptionsJson)` | question ID + JSON array of selected option IDs | User clicks Next |
| `questionSkipped(questionId)` | question ID | User clicks Skip |
| `chatAboutOption(questionId, optionLabel, message)` | question ID + triggering option's label + user's note | User presses Enter in chat-about mode |
| `questionsSubmitted()` | — | User clicks Submit |
| `questionsCancelled()` | — | User clicks Cancel |
| `editQuestion(questionId)` | question ID | User clicks Edit on a question in summary |

### Chat Input Behavior

| State | Chat Input |
|-------|-----------|
| Questions active | Disabled: "Answer the question above to continue..." |
| Chat-about active | Enabled: "Add your note about [option label]..." |
| Summary page | Disabled: "Review your answers above..." |
| After submit/cancel | Enabled: normal placeholder |

### Visual Design

Follows the existing chat card pattern (same as plan cards):
- Dark card background (`var(--tool-bg)`) with border radius
- Question header with progress indicator (Q1 of 3, dots ● ○ ○) and Cancel button (×)
- Single select: radio button styling (circle with dot)
- Multi select: checkbox styling (square with checkmark)
- Selected options: highlighted border (`var(--link)` for single, `var(--success)` for multi)
- "Chat about this" button: subtle, right-aligned on each option
- Navigation: Back (when N > 0), Skip, Next buttons
- Summary: each question editable, selected options shown as pills/tags
- Skipped questions: dimmed with "click to answer" prompt
- Action buttons: Cancel (left), Retry (center), Submit (right)

---

## JCEF Bridge (AgentCefPanel)

New methods matching the plan pattern:

```kotlin
// Rendering
fun showQuestions(questionsJson: String)        // calls JS showQuestions()
fun showQuestion(index: Int)                    // calls JS showQuestion()
fun showQuestionSummary(summaryJson: String)    // calls JS showQuestionSummary()
fun enableChatInput()                           // calls JS enableChatInput()

// Callbacks (set by AgentController via AgentDashboardPanel)
var onQuestionAnswered: ((String, String) -> Unit)?       // questionId, selectedOptionsJson
var onQuestionSkipped: ((String) -> Unit)?                 // questionId
var onChatAboutOption: ((String, String, String) -> Unit)? // questionId, optionLabel, message
var onQuestionsSubmitted: (() -> Unit)?
var onQuestionsCancelled: (() -> Unit)?
var onEditQuestion: ((String) -> Unit)?                    // questionId
```

---

## AgentController Wiring

```kotlin
// In executeTask(), alongside plan callback wiring:

// Set QuestionManager on AgentService
agentSvc.currentQuestionManager = currentSession.questionManager

// Wire question UI callbacks
currentSession.questionManager.onQuestionsCreated = { questionSet ->
    val json = questionManagerJson.encodeToString(questionSet)
    dashboard.showQuestions(json)
}
currentSession.questionManager.onShowQuestion = { index ->
    dashboard.showQuestion(index)
}
currentSession.questionManager.onShowSummary = { result ->
    val json = questionManagerJson.encodeToString(result)
    dashboard.showQuestionSummary(json)
}
currentSession.questionManager.onSubmitted = {
    dashboard.enableChatInput()
}

// Wire JCEF → QuestionManager callbacks
dashboard.setCefQuestionCallbacks(
    onAnswered = { questionId, optionsJson ->
        val options = Json.decodeFromString<List<String>>(optionsJson)
        currentSession.questionManager.answerQuestion(questionId, options)
    },
    onSkipped = { questionId ->
        currentSession.questionManager.skipQuestion(questionId)
    },
    onChatAbout = { questionId, optionLabel, message ->
        currentSession.questionManager.setChatMessage(questionId, optionLabel, message)
        // Note: does NOT call submitAnswers(). Transitions to SUMMARY, not SUBMITTED.
        // The summary page is shown via onShowSummary callback from QuestionManager.
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

---

## Files

### New Files

| File | Responsibility |
|------|---------------|
| `tools/builtin/AskQuestionsTool.kt` | Tool implementation with suspension, validation |
| `runtime/QuestionManager.kt` | State machine, data classes, future management, clear() |
| `test/.../AskQuestionsToolTest.kt` | Tool parameter validation tests (0 questions, 0 options, duplicates, limits) |
| `test/.../QuestionManagerTest.kt` | State machine + answer tracking + skip + cancel tests |

### Modified Files

| File | Change |
|------|--------|
| `resources/webview/agent-chat.html` | Add question wizard JS functions + CSS (showQuestions, showQuestion, showQuestionSummary, enableChatAbout, enableChatInput) |
| `ui/AgentCefPanel.kt` | Add JCEF bridge methods + 6 JS query callbacks |
| `ui/AgentDashboardPanel.kt` | Add delegation methods for questions (showQuestions, showQuestion, showQuestionSummary, setCefQuestionCallbacks) |
| `ui/AgentController.kt` | Wire QuestionManager callbacks |
| `AgentService.kt` | Add `currentQuestionManager: QuestionManager?` field |
| `runtime/ConversationSession.kt` | Add `val questionManager: QuestionManager = QuestionManager()` field |
| `tools/ToolCategoryRegistry.kt` | Add `ask_questions` to planning category |

---

## Edge Cases

| Edge Case | Behavior |
|-----------|----------|
| LLM sends 0 questions | Tool returns error: "At least one question required" |
| Question has 0 options | Tool returns error: "Each question must have at least one option" |
| Duplicate question IDs | Tool returns error: "Duplicate question ID: [id]" |
| Duplicate option IDs within question | Tool returns error: "Duplicate option ID: [id] in question [qid]" |
| More than 20 questions | Tool returns error: "Maximum 20 questions allowed" |
| More than 10 options per question | Tool returns error: "Maximum 10 options per question" |
| User submits with all questions skipped | Allowed — tool returns all as skipped, LLM decides what to do |
| User clicks Cancel | Tool returns with `cancelled=true`, chat re-enabled |
| 10-minute timeout | Tool returns error, chat re-enabled |
| User closes IDE during questions | Session persists, but future is lost. On resume, no active wizard |
| LLM calls ask_questions while one is active | Returns error: "A question set is already active. Wait for the current one to complete." |
| Single question (decision point) | Works fine — shows Q1 of 1, Next goes directly to summary |
| User clicks Next without selecting (single-select) | Question added to skipped list (same as clicking Skip) |
| User clicks Next without selecting (multi-select) | Question added to skipped list (same as clicking Skip) |
| User clicks "Chat about this" without selecting the option first | The option is auto-selected as the answer, then chat input activates |

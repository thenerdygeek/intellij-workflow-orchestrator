package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.core.ai.TokenEstimator
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import com.workflow.orchestrator.agent.tools.docs.Relationship
import com.workflow.orchestrator.agent.tools.docs.SideEffectKind
import com.workflow.orchestrator.agent.tools.docs.ToolDocumentation
import com.workflow.orchestrator.agent.tools.docs.VerdictSeverity
import com.workflow.orchestrator.agent.tools.docs.toolDoc
import com.workflow.orchestrator.agent.util.JsEscape
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class Question(
    val id: String,
    val question: String,
    val type: String,
    val options: List<QuestionOption>
)

@Serializable
data class QuestionOption(
    val id: String,
    val label: String,
    val description: String = ""
)

/**
 * Unified question tool — handles both simple single questions and multi-question wizards.
 *
 * Faithful port of Cline's ask_followup_question with extension for structured multi-question flows.
 *
 * **Simple mode** (like Cline): pass `question` string + optional `options` array.
 * The question is shown in the chat, the loop blocks waiting for the user's typed answer.
 *
 * **Wizard mode** (our extension): pass `questions` JSON array with structured options.
 * A multi-step wizard UI is shown in the IDE. Each question has a type (single/multiple)
 * and a list of options.
 *
 * The LLM should use simple mode for most questions. Wizard mode is for structured
 * decision trees (e.g. "Pick a database, then pick a schema, then pick a migration strategy").
 */
class AskQuestionsTool : AgentTool {
    override val name = "ask_followup_question"
    override val description = "Ask the user a question to gather additional information needed to complete the task. " +
        "Use this when you encounter ambiguities, need clarification, or require more details to proceed effectively. " +
        "Use judiciously — prefer using available tools to find information over asking the user.\n\n" +
        "Two modes:\n" +
        "- **Simple** (most common): pass 'question' with optional 'options' array. User sees the question in chat and types an answer.\n" +
        "- **Wizard** (structured): pass 'questions' JSON array for multi-step structured questions with options."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "question" to ParameterProperty(
                type = "string",
                description = "A clear, specific question to ask the user. This is the simple mode — the question appears in the chat and the user types their answer."
            ),
            "options" to ParameterProperty(
                type = "string",
                description = "Optional JSON array of 2-5 string options for the user to choose from, e.g. [\"Option 1\", \"Option 2\"]. " +
                    "Saves the user from typing. Only used with the 'question' parameter (simple mode)."
            ),
            "questions" to ParameterProperty(
                type = "string",
                description = "JSON array of structured questions for the wizard mode. Only use this for complex multi-step decisions. " +
                    "Format: [{\"id\":\"q1\",\"question\":\"...\",\"type\":\"single|multiple\",\"options\":[{\"id\":\"o1\",\"label\":\"...\"}]}]"
            ),
            "title" to ParameterProperty(
                type = "string",
                description = "Optional title for the question wizard (only used with 'questions' parameter)."
            )
        ),
        required = emptyList() // Either 'question' or 'questions' required, validated in execute
    )
    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.CODER, WorkerType.ANALYZER)

    override fun documentation(): ToolDocumentation = toolDoc("ask_followup_question") {
        summary {
            technical("Two-mode user-input primitive: simple mode shows a chat question with optional clickable choice chips; wizard mode renders a multi-step JCEF dashboard wizard with single/multi-select options, back/skip/next nav, and a summary page. Both modes complete a CompletableDeferred that suspends the agent loop until the user answers, dismisses, or the 5-minute timeout fires; a 10s UI-render watchdog auto-resolves with [UI_RENDER_FAILED] when the JCEF bridge never confirms the render.")
            plain("Like a chatbot pausing to ask 'before I proceed, can you clarify X?' — the agent puts a question in the chat (or a multi-step wizard in the side panel), then waits at the door until you answer, skip, or close it. After 5 minutes of silence the agent gives up and reports a timeout instead of hanging forever.")
        }
        whatLLMSees(description)
        sideEffect(SideEffectKind.AGENT_CONTROL)
        counterfactual(
            "Without ask_followup_question, the LLM either (a) guesses with whatever context it has and proceeds — frequently doing the wrong thing and burning tool calls down a dead-end branch, or (b) emits a question as plain assistant text and finishes its turn, hoping the user notices and replies. Plain-text questions have no UI affordance: no choice chips, no inline-comment surface, no loop suspension — the user has to read the chat, realize a question was asked, and type back manually. Net cost: ~2-3 wasted iterations per ambiguous task and a degraded UX where the user discovers questions retroactively rather than being prompted."
        )
        llmMistake("Asks a trivial question whose answer is already in the conversation context, project files, or current editor — wastes a 5-minute-suspendable round-trip when read_file / search_code would have answered it. The 'use judiciously' line in the description targets this but the LLM still over-asks, especially after compaction trims the relevant context.")
        llmMistake("Uses wizard mode (`questions` array) for a single yes/no decision — the heavyweight multi-step UI for one click is overkill; should use simple mode with `options=[\"Yes\", \"No\"]`. Wizard mode is for genuine multi-step decision trees (pick database → pick schema → pick migration strategy).")
        llmMistake("Stuffs multiple unrelated questions into a single `question` string ('What database, what auth scheme, and what deployment target?') — the user has to type a multi-part answer in plain text instead of getting clickable options for each. Either ask one question at a time (simple mode) or use wizard mode with structured questions.")
        llmMistake("Forgets that simple mode's `options` is a JSON-encoded string array, not a JSON array literal — passes `[Option A, Option B]` (unquoted) and the parse fails, falling back to plain-text question. Must be `[\"Option A\", \"Option B\"]`.")
        llmMistake("Calls ask_followup_question after the user has already volunteered the information (e.g. they wrote 'use Postgres' in the prompt) — re-asking burns the user's patience and the suspension grace period. Trust user-stated facts; don't re-ask.")
        llmMistake("Provides neither `question` nor `questions` — both are validated as required-one-of in execute(); returns an error ToolResult and the iteration is wasted.")
        llmMistake("Phrases the question open-endedly ('What should I do next?') instead of presenting concrete options — the user has to type a free-form answer when chips would have closed the loop in one click. When you can enumerate the choices, do.")
        params {
            optional("question", "string") {
                llmSeesIt("A clear, specific question to ask the user. This is the simple mode — the question appears in the chat and the user types their answer.")
                humanReadable("The simple-mode question — one sentence, shown inline in the chat. Pair with `options` to give the user clickable choices instead of a typing prompt.")
                whenPresent("Routes to executeSimple(): question is shown via showSimpleQuestionCallback (chat-based) or wrapped as a single-question wizard fallback. The agent loop suspends on a CompletableDeferred until the user answers, skips, dismisses, or the 5-min timeout fires. Returned content wraps both the question and the answer in `<question>` / `<answer>` tags so context survives compaction.")
                whenAbsent("Required-one-of with `questions`. If neither is provided, execute() returns an error ToolResult; if `questions` is provided instead, wizard mode runs.")
                constraint("mutually exclusive with `questions` in practice — if both are sent, simple mode wins (the early check on `simpleQuestion != null` runs first)")
                example("Should I use Postgres or MySQL for this service?")
                example("The migration is destructive — confirm you want to proceed?")
            }
            optional("options", "string") {
                llmSeesIt("Optional JSON array of 2-5 string options for the user to choose from, e.g. [\"Option 1\", \"Option 2\"]. Saves the user from typing. Only used with the 'question' parameter (simple mode).")
                humanReadable("Clickable choice chips for the simple-mode question — turns 'type your answer' into 'click one of these'. Best for closed-set decisions (Yes/No, A/B/C) where free-text would be slower.")
                whenPresent("Parsed via kotlinx.serialization as List<String>. On parse failure, options are dropped and a parseWarning is prepended to the eventual answer content (the question still renders, just without chips). Re-serialized via JsEscape.toJsonString before being passed to the JCEF bridge.")
                whenAbsent("User sees the question with a typing prompt only — no clickable chips. Fine for open-ended questions.")
                constraint("must be a JSON-encoded string array (not a JSON array literal); malformed JSON is silently dropped with a warning prepended to the answer")
                constraint("description suggests 2-5 options, but no enforcement — the LLM can pass 1 or 50; UI ergonomics suffer outside the 2-5 band")
                example("[\"Yes\", \"No\"]")
                example("[\"Postgres\", \"MySQL\", \"SQLite\"]")
            }
            optional("questions", "string") {
                llmSeesIt("JSON array of structured questions for the wizard mode. Only use this for complex multi-step decisions. Format: [{\"id\":\"q1\",\"question\":\"...\",\"type\":\"single|multiple\",\"options\":[{\"id\":\"o1\",\"label\":\"...\"}]}]")
                humanReadable("Wizard-mode payload — a JSON array of structured questions, each with its own options list. Renders as a multi-step wizard in the dashboard with back/skip/next navigation. Only worth it for genuine decision trees; for a single question use simple mode.")
                whenPresent("Routes to executeWizard(): JSON is validated (max 20 questions, max 10 options each, unique IDs, type ∈ {single, multiple}). On validation failure returns an error ToolResult with a format example. On success, fires showQuestionsCallback to render the wizard; the agent loop suspends on a CompletableDeferred until the user submits, cancels, or the 5-min timeout fires.")
                whenAbsent("Required-one-of with `question`. If neither is provided, execute() returns an error ToolResult.")
                constraint("max 20 questions per wizard call; each question max 10 options")
                constraint("question IDs must be unique within the array; option IDs must be unique within their question")
                constraint("each question's `type` must be `single` or `multiple` — `multiple` lets the user select more than one option")
                example("[{\"id\":\"db\",\"question\":\"Pick a database\",\"type\":\"single\",\"options\":[{\"id\":\"pg\",\"label\":\"Postgres\"},{\"id\":\"my\",\"label\":\"MySQL\"}]}]")
            }
            optional("title", "string") {
                llmSeesIt("Optional title for the question wizard (only used with 'questions' parameter).")
                humanReadable("Header text for the wizard panel — only renders in wizard mode. Like the title bar on a setup-wizard dialog.")
                whenPresent("Wrapped into the wizardJson payload as `\"title\":...` and rendered as the wizard header.")
                whenAbsent("Wizard renders without a title bar — questions appear with default styling.")
                example("Pick your stack")
            }
        }
        verdict {
            keep(
                "Load-bearing for ambiguous tasks. Without it, the LLM has no primitive that both poses a question to the user AND yields control of the loop until they answer — plain-text questions don't suspend the loop and have no UI affordance (no choice chips, no wizard). The simple/wizard split is justified: simple mode covers ~95% of cases; wizard mode is the only way to render structured multi-step decision trees with proper navigation.",
                VerdictSeverity.STRONG,
            )
            drop(
                "Wizard mode alone could plausibly drop. It has heavier UX cost (separate JCEF callback path, separate watchdog, separate validation), is rarely the right choice (the LLM almost always over-uses it for single questions when simple mode + options would suffice), and the simple-mode fallback already wraps a single question into a one-step wizard internally — proving the simple path can subsume the structured path in practice. If wizard mode were dropped, simple mode would handle every real use case at the cost of slightly less polished multi-step flows.",
                VerdictSeverity.WEAK,
            )
        }
        mergeOpportunity("Simple and wizard modes share the same scaffolding (CompletableDeferred + uiRenderConfirmed + 10s watchdog Timer + 5-min withTimeoutOrNull + cancelled/[UI_RENDER_FAILED]/[SKIPPED] sentinel handling). The two execute*() methods differ only in (a) which callback fires (showSimpleQuestionCallback vs showQuestionsCallback) and (b) the result-content shape. A unified executeBlocking(payload, callback, summaryShape) helper would cut ~60 lines of duplication and pin the watchdog/timeout invariants in one place.")
        mergeOpportunity("Simple mode already falls back to wizard mode (wraps the question as a single-question wizard) when showSimpleQuestionCallback is null. This means wizard mode's UI surface is the universal renderer; simple mode is a UI-affinity hint, not a separate code path at the dashboard layer. Could collapse to a single mode with an `is_wizard` boolean flag and let the UI choose the render strategy.")
        observation("Both modes use a shared `pendingQuestions: CompletableDeferred<String>` companion-object field as the suspension primitive. Concurrent callers would clobber each other (last-writer-wins) — fine in practice because the agent loop is single-threaded, but worth flagging if the loop ever parallelizes user-input tools.")
        observation("The 10s UI-render watchdog uses `java.util.Timer` (daemon thread) rather than a coroutine, with the comment explaining structured-concurrency reasons (a child coroutine outliving withTimeoutOrNull would leak). Pattern is correct but uncommon in the codebase; worth keeping the rationale in the source.")
        related("plan_mode_respond", Relationship.SEE_ALSO, "Contrast: plan_mode_respond proposes a multi-step plan and waits for user feedback or approval; ask_followup_question gathers a single piece of clarifying info needed to proceed. Both suspend the loop on a CompletableDeferred but differ in payload shape (plan vs question) and rendered surface (plan card vs chat question / wizard).")
        related("attempt_completion", Relationship.SEE_ALSO, "Contrast: attempt_completion ends the session with a finished result; ask_followup_question pauses mid-task to gather more input. Calling attempt_completion when you should be asking a question loses the user's chance to course-correct before the wrong work lands.")
        related("ask_user_input", Relationship.ALTERNATIVE, "Deferred-tier sibling — exists in the same problem space (gather input from user). Worth comparing when auditing input-gathering tool surface area.")
        downside("5-minute suspension grace; if the user goes idle the loop sits idle for 5 minutes before timing out. No interim progress signal to the user; the chat just shows the unanswered question.")
        downside("10s UI-render watchdog can fire spuriously if the JCEF bridge takes longer than 10s to render (rare but possible on slow machines / cold start) — the LLM gets [UI_RENDER_FAILED] and is told to ask the question as plain text, even though the user might still be looking at a wizard that was about to render.")
        downside("Simple-mode `options` parsing failures are silent at the LLM-facing level — the question still renders, but without chips, and the LLM has no way to know the parse failed until the answer comes back with the parseWarning prepended. The warning surfaces in content, not in the tool response shape.")
        downside("Wizard mode validation rejects on first error with a JSON format example — useful for the LLM to retry, but a malformed wizard means a wasted iteration. Simple mode is more forgiving (silently drops bad options).")
        downside("`pendingQuestions` is a single-slot @Volatile field. If the LLM emits two ask_followup_question calls back-to-back (e.g. recovering from a malformed first call), the second overwrites the first deferred and the first user-input round-trip is lost. Defensible because the agent loop serializes tool calls; brittle if that invariant changes.")
        downside("Cancellation produces a different result shape than skip: cancelled returns isError=false but a 'User dismissed' content; skip returns a `<skipped>` block instructing the LLM to use best judgement. The LLM has to handle three response shapes (answered, skipped, cancelled) plus two error shapes (timeout, UI render failed) — five terminal states for one tool.")
        downside("`uiRenderConfirmed` is set by an out-of-band JCEF bridge round-trip; tests can't easily exercise the watchdog branch without a live JCEF instance. The watchdog branch is therefore lightly tested in integration.")
        flowchart("""
            flowchart TD
                A[LLM calls ask_followup_question] --> B{question or questions?}
                B -- "neither" --> X1[Return error: missing param]
                B -- "question (simple)" --> C[executeSimple]
                B -- "questions (wizard)" --> D[executeWizard]
                C --> C1{Parse options JSON}
                C1 --> C2{showSimpleQuestionCallback?}
                C2 -- "yes" --> C3[Fire chat callback]
                C2 -- "no, fallback" --> C4{showQuestionsCallback?}
                C4 -- "no" --> X2[Return error: UI not available]
                C4 -- "yes" --> C5[Wrap as single-Q wizard]
                C3 --> S
                C5 --> S
                D --> D1[validateQuestions]
                D1 -- "fail" --> X3[Return error: invalid JSON / shape]
                D1 -- "ok" --> D2[Fire wizard callback with title+questions]
                D2 --> S
                S[Set pendingQuestions deferred<br/>Start 10s render watchdog<br/>Suspend on withTimeoutOrNull 5min] --> R{User action / timeout}
                R -- "answers" --> R1[Return content with question+answer tags]
                R -- "skips" --> R2[Return skipped block — best judgement]
                R -- "dismisses" --> R3[Return user-dismissed isError=false]
                R -- "5min elapses" --> R4[Return timeout error]
                R -- "10s no UI render" --> R5[Watchdog fires UI_RENDER_FAILED — agent told to ask as plain text]
        """)
    }

    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        private const val QUESTION_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes

        /**
         * Grace period after the callback fires before we assume the UI failed to render.
         * If the deferred isn't resolved by the user AND the UI render confirmation hasn't
         * arrived within this window, we log a warning. The tool still waits for the full
         * [QUESTION_TIMEOUT_MS], but this shorter check enables early diagnostics.
         *
         * Set to 10s — enough for EDT scheduling + JCEF round-trip, short enough to detect
         * a stuck UI before the user gives up.
         */
        private const val UI_RENDER_GRACE_MS = 10_000L

        /** Callback to show the question wizard in the dashboard UI (wizard mode). */
        var showQuestionsCallback: ((String) -> Unit)? = null

        /** Callback to show a simple question in the chat UI (simple mode).
         *  Receives: questionText, optionsJson (nullable). */
        var showSimpleQuestionCallback: ((String, String?) -> Unit)? = null

        /** Deferred result that blocks tool execution until the user answers. */
        @Volatile
        var pendingQuestions: CompletableDeferred<String>? = null

        /**
         * Set to true by the UI layer (JCEF bridge round-trip) when the question
         * has been successfully rendered. Checked by [executeSimple]/[executeWizard]
         * after [UI_RENDER_GRACE_MS] to detect silent UI failures.
         */
        @Volatile
        var uiRenderConfirmed: Boolean = false

        /** Resolve the pending question(s) with the user's answer. */
        fun resolveQuestions(answersJson: String) {
            pendingQuestions?.complete(answersJson)
        }

        /** Cancel the pending question(s) (user dismissed). */
        fun cancelQuestions() {
            pendingQuestions?.complete("""{"cancelled":true}""")
        }
    }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val simpleQuestion = params["question"]?.jsonPrimitive?.content
        val questionsJson = params["questions"]?.let { element ->
            // Accept both string-encoded JSON and raw JSON arrays
            try { element.jsonPrimitive.content } catch (_: Exception) { element.toString() }
        }

        // Must provide either 'question' (simple) or 'questions' (wizard)
        if (simpleQuestion == null && questionsJson == null) {
            return ToolResult(
                "Error: Either 'question' (simple mode) or 'questions' (wizard mode) parameter is required.",
                "Error: missing question", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        // ── Simple mode: single question, user types answer ──
        if (simpleQuestion != null) {
            // Extract options — accept both string-encoded JSON and raw JSON arrays
            val optionsStr = params["options"]?.let { element ->
                try { element.jsonPrimitive.content } catch (_: Exception) { element.toString() }
            }
            return executeSimple(simpleQuestion, optionsStr)
        }

        // ── Wizard mode: structured multi-question wizard ──
        return executeWizard(questionsJson!!, params["title"]?.jsonPrimitive?.content)
    }

    /**
     * Simple mode — faithful port of Cline's ask_followup_question.
     * Shows question in chat, blocks for user's typed answer, returns it wrapped in <answer> tags.
     */
    private suspend fun executeSimple(question: String, optionsJson: String?): ToolResult {
        val deferred = CompletableDeferred<String>()
        pendingQuestions = deferred
        uiRenderConfirmed = false

        val (options, parseWarning) = parseSimpleOptions(optionsJson)

        // Show via simple question callback (chat-based) or wizard fallback
        val simpleCallback = showSimpleQuestionCallback
        val wizardCallback = showQuestionsCallback
        if (simpleCallback != null) {
            val reserializedOptions = if (options.isNotEmpty()) {
                "[${options.joinToString(",") { JsEscape.toJsonString(it) }}]"
            } else null
            simpleCallback(question, reserializedOptions)
        } else if (wizardCallback != null) {
            // Fallback: wrap as a single-question wizard
            val wrappedJson = buildString {
                append("""{"questions":[{"id":"q1","question":""")
                append(JsEscape.toJsonString(question))
                append(""","type":"single","options":[""")
                options.forEachIndexed { i, opt ->
                    if (i > 0) append(",")
                    append("""{"id":"o${i+1}","label":""")
                    append(JsEscape.toJsonString(opt))
                    append("}")
                }
                append("]}]}")
            }
            wizardCallback(wrappedJson)
        } else {
            pendingQuestions = null
            return ToolResult(
                "Error: Question UI not available. Ask questions as plain text instead.",
                "ask_followup_question: UI not available",
                ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        // UI render watchdog: if the JCEF bridge hasn't confirmed the render within
        // UI_RENDER_GRACE_MS, assume the UI is stuck and auto-resolve the deferred
        // so the agent loop doesn't block for 5 minutes. Uses a daemon timer thread
        // to avoid structured concurrency issues (we can't launch a coroutine that
        // outlives the withTimeoutOrNull scope without completing it).
        val watchdogTimer = java.util.Timer("ask-question-watchdog", true)
        watchdogTimer.schedule(object : java.util.TimerTask() {
            override fun run() {
                if (!deferred.isCompleted && !uiRenderConfirmed) {
                    deferred.complete("[UI_RENDER_FAILED]")
                }
            }
        }, UI_RENDER_GRACE_MS)

        val answer = withTimeoutOrNull(QUESTION_TIMEOUT_MS) { deferred.await() }
        watchdogTimer.cancel()
        pendingQuestions = null

        if (answer == null) {
            return ToolResult(
                "User did not respond within 5 minutes.",
                "ask_followup_question: timeout",
                ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        if (answer.contains("\"cancelled\":true")) {
            return ToolResult(
                "User dismissed the question.",
                "ask_followup_question: cancelled",
                ToolResult.ERROR_TOKEN_ESTIMATE, isError = false
            )
        }

        if (answer == "[SKIPPED]") {
            val content = buildString {
                appendLine("<question>")
                appendLine(question)
                appendLine("</question>")
                appendLine("<skipped>")
                appendLine("The user chose to skip this question. Proceed using your best judgement, or ask a different question if you still need clarification.")
                appendLine("</skipped>")
            }
            return ToolResult(
                content = content,
                summary = "ask_followup_question: user skipped",
                tokenEstimate = TokenEstimator.estimate(content)
            )
        }

        if (answer == "[UI_RENDER_FAILED]") {
            return ToolResult(
                "Error: The question UI failed to render (JCEF bridge timeout). " +
                    "The question was: \"$question\". " +
                    "Ask the question as plain text in your response instead of using this tool.",
                "ask_followup_question: UI render failed",
                ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        // Match Cline's response format: <answer>text</answer>
        // Also include the question text wrapped in <question> tags so the LLM
        // can reason about the answer even after context compaction
        val content = buildString {
            if (parseWarning != null) appendLine(parseWarning).appendLine()
            appendLine("<question>")
            appendLine(question)
            appendLine("</question>")
            appendLine("<answer>")
            appendLine(answer)
            appendLine("</answer>")
        }
        return ToolResult(
            content = content,
            summary = "User answered: ${answer.take(200)}",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    /**
     * Wizard mode — structured multi-question flow with options UI.
     */
    private suspend fun executeWizard(questionsJson: String, title: String?): ToolResult {
        // Validate
        val validationError = validateQuestions(questionsJson)
        if (validationError != null) return validationError

        val callback = showQuestionsCallback
        if (callback == null) {
            return ToolResult(
                "Error: Question wizard UI not available.",
                "ask_followup_question: wizard UI not available",
                ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        val wizardJson = if (title != null) {
            """{"title":${JsEscape.toJsonString(title)},"questions":$questionsJson}"""
        } else {
            """{"questions":$questionsJson}"""
        }

        val deferred = CompletableDeferred<String>()
        pendingQuestions = deferred
        uiRenderConfirmed = false
        callback(wizardJson)

        // UI render watchdog (same pattern as executeSimple)
        val watchdogTimer = java.util.Timer("ask-question-wizard-watchdog", true)
        watchdogTimer.schedule(object : java.util.TimerTask() {
            override fun run() {
                if (!deferred.isCompleted && !uiRenderConfirmed) {
                    deferred.complete("[UI_RENDER_FAILED]")
                }
            }
        }, UI_RENDER_GRACE_MS)

        val answersJson = withTimeoutOrNull(QUESTION_TIMEOUT_MS) { deferred.await() }
        watchdogTimer.cancel()
        pendingQuestions = null

        if (answersJson == null) {
            return ToolResult(
                "User did not respond within 5 minutes.",
                "ask_followup_question: wizard timeout",
                ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        if (answersJson.contains("\"cancelled\":true")) {
            return ToolResult(
                "User cancelled the question wizard.",
                "ask_followup_question: wizard cancelled",
                ToolResult.ERROR_TOKEN_ESTIMATE, isError = false
            )
        }

        if (answersJson == "[UI_RENDER_FAILED]") {
            return ToolResult(
                "Error: The question wizard UI failed to render (JCEF bridge timeout). " +
                    "Ask the questions as plain text in your response instead of using this tool.",
                "ask_followup_question: wizard UI render failed",
                ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        val content = "User answered the questions:\n$answersJson"
        return ToolResult(
            content = content,
            summary = "ask_followup_question: user responded",
            tokenEstimate = TokenEstimator.estimate(content)
        )
    }

    // ── Validation helpers ──

    fun validateQuestions(questionsJson: String): ToolResult? {
        val questions = try {
            json.decodeFromString<List<Question>>(questionsJson)
        } catch (e: Exception) {
            return ToolResult(
                "Error: invalid questions JSON: ${e.message}. Expected format: [{\"id\":\"q1\",\"question\":\"...\",\"type\":\"single\",\"options\":[{\"id\":\"o1\",\"label\":\"...\"}]}]",
                "Error: invalid JSON", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        if (questions.isEmpty()) {
            return ToolResult("Error: questions array must be non-empty", "Error: empty questions", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
        if (questions.size > 20) {
            return ToolResult("Error: max 20 questions (got ${questions.size})", "Error: too many questions", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        val seenQuestionIds = mutableSetOf<String>()
        for (q in questions) {
            if (!seenQuestionIds.add(q.id)) return ToolResult("Error: Duplicate question ID '${q.id}'", "Error: duplicate ID", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
            if (q.type != "single" && q.type != "multiple") return ToolResult("Error: question '${q.id}' has invalid type '${q.type}'", "Error: invalid type", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
            if (q.options.isEmpty()) return ToolResult("Error: question '${q.id}' must have options", "Error: no options", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
            if (q.options.size > 10) return ToolResult("Error: question '${q.id}' has too many options (${q.options.size})", "Error: too many options", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
            val seenOpts = mutableSetOf<String>()
            for (opt in q.options) {
                if (!seenOpts.add(opt.id)) return ToolResult("Error: Duplicate option ID '${opt.id}' in '${q.id}'", "Error: duplicate option", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
            }
        }
        return null
    }

    /**
     * Parse simple options from JSON string. Returns null on parse failure
     * so the caller can report the error to the LLM instead of silently
     * falling back to no-options mode.
     */
    private fun parseSimpleOptions(optionsJson: String?): Pair<List<String>, String?> {
        if (optionsJson.isNullOrBlank()) return emptyList<String>() to null
        return try {
            json.decodeFromString<List<String>>(optionsJson) to null
        } catch (e: Exception) {
            emptyList<String>() to "Warning: could not parse 'options' as JSON string array: ${e.message}. " +
                "Expected format: [\"Option A\", \"Option B\", \"Option C\"]. " +
                "Options were ignored — showing question as plain text."
        }
    }

}

package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.diagnostic.Logger
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
    val type: String,
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
        private val LOG = Logger.getInstance(QuestionManager::class.java)
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }

    var currentQuestions: QuestionSet? = null
        private set

    private var submissionFuture: CompletableFuture<QuestionResult>? = null
    private val answers = mutableMapOf<String, QuestionAnswer>()
    private val skipped = mutableSetOf<String>()

    /** True when questions have been submitted and are awaiting user answers. */
    val isAwaitingAnswers: Boolean
        get() = submissionFuture != null && submissionFuture?.isDone == false

    // UI callbacks
    var onQuestionsCreated: ((QuestionSet) -> Unit)? = null
    var onShowQuestion: ((Int) -> Unit)? = null
    var onShowSummary: ((QuestionResult) -> Unit)? = null
    var onSubmitted: (() -> Unit)? = null

    fun submitQuestions(questionSet: QuestionSet): CompletableFuture<QuestionResult> {
        clear()
        currentQuestions = questionSet
        submissionFuture = CompletableFuture()
        LOG.info("QuestionManager: questions submitted with ${questionSet.questions.size} questions")
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
        answers[questionId] = QuestionAnswer(
            questionId = questionId,
            selectedOptions = existing?.selectedOptions ?: emptyList(),
            chatMessage = "Re: $optionLabel — $message"
        )
        onShowSummary?.invoke(buildResult())
    }

    fun submitAnswers() {
        val result = buildResult()
        submissionFuture?.complete(result)
        submissionFuture = null
        onSubmitted?.invoke()
    }

    fun cancelQuestions() {
        submissionFuture?.complete(QuestionResult(cancelled = true))
        submissionFuture = null
        onSubmitted?.invoke()
    }

    fun editQuestion(questionId: String) {
        val index = currentQuestions?.questions?.indexOfFirst { it.id == questionId } ?: return
        if (index >= 0) {
            onShowQuestion?.invoke(index)
        }
    }

    fun buildResult(): QuestionResult {
        val allQuestionIds = currentQuestions?.questions?.map { it.id } ?: emptyList()
        val unanswered = allQuestionIds.filter { it !in answers }
        val allSkipped = (skipped + unanswered).distinct()
        return QuestionResult(
            answers = answers.toMap(),
            skipped = allSkipped
        )
    }

    fun clear() {
        currentQuestions = null
        submissionFuture = null
        answers.clear()
        skipped.clear()
    }
}

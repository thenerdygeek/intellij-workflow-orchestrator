package com.workflow.orchestrator.agent.walkthrough

/**
 * Pure state holder for one guided code tour. NO platform imports — headless-testable.
 * Thread-confinement: all callers mutate from the EDT (tool calls hop via Dispatchers.EDT,
 * popup clicks are already on EDT), so there is no internal locking.
 */
class WalkthroughStateMachine {
    enum class Status { IDLE, GENERATING, COMPLETE, ENDED }

    sealed class NextOutcome {
        data class Advanced(val step: WalkthroughStep, val index: Int) : NextOutcome()
        data object AwaitingMore : NextOutcome()
        data object TourFinished : NextOutcome()
    }

    var status: Status = Status.IDLE; private set
    var title: String? = null; private set
    var cursor: Int = 0; private set
    var pendingNext: Boolean = false; private set
    var generationPaused: Boolean = false; private set
    var pendingQuestion: String? = null; private set
    var endedByUser: Boolean = false; private set

    private val steps = mutableListOf<WalkthroughStep>()

    val isActive: Boolean get() = status == Status.GENERATING || status == Status.COMPLETE

    fun stepCount(): Int = steps.size
    fun currentStep(): WalkthroughStep? = steps.getOrNull(cursor)
    fun isOnLastStep(): Boolean = cursor == steps.size - 1

    /** @return false when a tour is already active (start is illegal then). */
    fun start(title: String?, initial: List<WalkthroughStep>): Boolean {
        if (isActive) return false
        require(initial.isNotEmpty()) { "start requires at least one step" }
        this.title = title
        steps.clear(); steps.addAll(initial)
        cursor = 0
        pendingNext = false; generationPaused = false; pendingQuestion = null; endedByUser = false
        status = Status.GENERATING
        return true
    }

    /** @return the index auto-advanced to when a parked pendingNext was consumed, else null. */
    fun append(newSteps: List<WalkthroughStep>): Int? {
        check(isActive) { "no active tour" }
        if (newSteps.isEmpty()) return null
        steps.addAll(newSteps)
        if (pendingNext) {
            pendingNext = false
            cursor++
            return cursor
        }
        return null
    }

    fun finish() {
        check(isActive) { "no active tour" }
        status = Status.COMPLETE
    }

    /**
     * Run-teardown signal (idempotent). GENERATING -> COMPLETE; clears paused/pendingNext.
     * @return true when a pending question was force-resolved (caller shows fallback note).
     */
    fun markGenerationEnded(): Boolean {
        if (status != Status.GENERATING) return false
        status = Status.COMPLETE
        generationPaused = false
        pendingNext = false
        val hadQuestion = pendingQuestion != null
        pendingQuestion = null
        return hadQuestion
    }

    fun next(): NextOutcome {
        check(isActive) { "no active tour" }
        if (cursor < steps.size - 1) {
            cursor++
            return NextOutcome.Advanced(steps[cursor], cursor)
        }
        return if (status == Status.COMPLETE) NextOutcome.TourFinished
        else { pendingNext = true; NextOutcome.AwaitingMore }
    }

    fun back(): WalkthroughStep? {
        check(isActive) { "no active tour" }
        pendingNext = false
        if (cursor == 0) return null
        cursor--
        return steps[cursor]
    }

    fun end(byUser: Boolean) {
        if (!isActive) return
        status = Status.ENDED
        endedByUser = byUser
        pendingNext = false; generationPaused = false; pendingQuestion = null
    }

    fun setGenerationPaused(paused: Boolean) {
        if (status == Status.GENERATING) generationPaused = paused
    }

    /** @return false when a question is already pending or no tour is active. */
    fun askQuestion(question: String): Boolean {
        if (!isActive || pendingQuestion != null) return false
        pendingQuestion = question
        return true
    }

    /** @return false when there was no pending question (the tool-error case). */
    fun answerDelivered(): Boolean {
        if (pendingQuestion == null) return false
        pendingQuestion = null
        return true
    }

    fun counterText(): String {
        val plus = if (status == Status.GENERATING) "+" else ""
        return "Step ${cursor + 1} of ${steps.size}$plus"
    }

    fun toolStatusLine(): String {
        val tourName = title?.let { "\"$it\"" } ?: "(untitled)"
        val complete = if (status == Status.GENERATING) "no" else "yes"
        return "Tour $tourName: ${steps.size} steps queued (queue complete: $complete), user is on step ${cursor + 1}."
    }
}

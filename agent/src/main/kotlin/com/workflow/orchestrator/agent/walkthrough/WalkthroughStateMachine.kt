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

    var status: Status = Status.IDLE
        private set
    var title: String? = null
        private set
    var cursor: Int = 0
        private set
    var pendingNext: Boolean = false
        private set
    var generationPaused: Boolean = false
        private set
    var endedByUser: Boolean = false
        private set

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
        steps.clear()
        steps.addAll(initial)
        cursor = 0
        pendingNext = false
        generationPaused = false
        endedByUser = false
        status = Status.GENERATING
        return true
    }

    /** 1-based read access for update targeting. */
    fun stepAt(index: Int): WalkthroughStep? = steps.getOrNull(index - 1)

    data class UpdateOutcome(val ok: Boolean, val isCurrent: Boolean, val message: String = "")

    /** Replace or append a step's body by 1-based index. */
    fun updateStep(index: Int, bodyMarkdown: String, append: Boolean): UpdateOutcome {
        if (!isActive) return UpdateOutcome(false, false, "no active walkthrough")
        val existing = steps.getOrNull(index - 1)
            ?: return UpdateOutcome(false, false, "step $index does not exist (tour has ${steps.size} step(s))")
        val newBody = if (append) "${existing.bodyMarkdown}\n\n$bodyMarkdown" else bodyMarkdown
        steps[index - 1] = existing.copy(bodyMarkdown = newBody)
        return UpdateOutcome(true, isCurrent = (index - 1 == cursor))
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

    /** Run-teardown signal (idempotent). GENERATING -> COMPLETE; clears paused/pendingNext. */
    fun markGenerationEnded() {
        if (status != Status.GENERATING) return
        status = Status.COMPLETE
        generationPaused = false
        pendingNext = false
    }

    fun next(): NextOutcome {
        check(isActive) { "no active tour" }
        if (cursor < steps.size - 1) {
            cursor++
            return NextOutcome.Advanced(steps[cursor], cursor)
        }
        return if (status == Status.COMPLETE) {
            NextOutcome.TourFinished
        } else {
            pendingNext = true
            NextOutcome.AwaitingMore
        }
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
        pendingNext = false
        generationPaused = false
    }

    fun setGenerationPaused(paused: Boolean) {
        if (status == Status.GENERATING) generationPaused = paused
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

package com.workflow.orchestrator.agent.walkthrough

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class WalkthroughControllerWiringContractTest {

    private val controller: String =
        Files.readString(Path.of("src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt"))

    @Test
    fun `onComplete calls markGenerationEnded BEFORE the result-kind dispatch`() {
        val onComplete = controller.substringAfter("private fun onComplete(result: LoopResult)")
        val beforeWhen = onComplete.substringBefore("when (result)")
        assertTrue(
            beforeWhen.contains("markGenerationEnded"),
            "markGenerationEnded must run before `when (result)` — the SessionHandoff branch " +
                "early-returns before the cleanup footer and would leak a permanent spinner",
        )
    }

    @Test
    fun `newChat and showSession end the active tour`() {
        val newChat = controller.substringAfter("fun newChat()").substringBefore("resetForNewChat()")
        assertTrue(newChat.contains("endTour"), "newChat must end the active walkthrough")
        val showSession = controller.substringAfter("fun showSession(sessionId: String)")
            .substringBefore("viewedSessionId = sessionId")
        assertTrue(showSession.contains("endTour"), "showSession must end the active walkthrough")
    }

    @Test
    fun `onLoopAwaitingUserInput pauses generation and the channel branch resumes it`() {
        val awaiting = controller.substringAfter("private fun onLoopAwaitingUserInput(reason: String)")
            .substringBefore("private fun")
        assertTrue(awaiting.contains("setGenerationPaused(true)"))
        // Anchor on the branch's unique LOG line — NOT "loopWaitingForInput = false", whose first
        // occurrence is the field declaration at the top of the class (would pin nothing).
        val channelBranch = controller.substringAfter("feeding user message into existing loop via channel")
            .substringBefore("channel.send(task)")
        assertTrue(
            channelBranch.contains("setGenerationPaused(false)"),
            "the parked-loop release path must clear the walkthrough paused state",
        )
    }

    @Test
    fun `ask_followup_question pending path pauses and its answer path resumes (spec section 4)`() {
        // Pause must be set in at least TWO places: onLoopAwaitingUserInput AND the
        // ask_followup_question show/unlock path (the wizard suspends on its own deferred
        // and never reaches onLoopAwaitingUserInput).
        val pauseCount = Regex("""setGenerationPaused\(true\)""").findAll(controller).count()
        assertTrue(
            pauseCount >= 2,
            "expected setGenerationPaused(true) in onLoopAwaitingUserInput AND the " +
                "ask_followup_question unlock path (found $pauseCount)",
        )
        val answerBranch = controller.substringAfter("resolving pending question with user answer")
            .substringBefore("pending.complete(task)")
        assertTrue(
            answerBranch.contains("setGenerationPaused(false)"),
            "answering the wizard question must clear the walkthrough paused state",
        )
    }

    @Test
    fun `wizard-mode question both sets and clears the walkthrough paused state (spec section 4)`() {
        // The wizard presentation (showQuestionsCallback) suspends on its own deferred and
        // never reaches onLoopAwaitingUserInput, so it must pause the tour itself.
        val wizardShow = controller.substringAfter("showQuestionsCallback = { questionsJson ->")
            .substringBefore("AskUserInputTool.showInputCallback")
        assertTrue(
            wizardShow.contains("setGenerationPaused(true)"),
            "the wizard-mode question callback must pause walkthrough generation",
        )
        // Wizard submit/cancel route through resolveQuestions()/cancelQuestions(), NOT executeTask,
        // so the parked-channel / pending-question clears never fire — both must clear here.
        val onSubmitted = controller.substringAfter("onSubmitted = {")
            .substringBefore("onCancelled = {")
        assertTrue(
            onSubmitted.contains("setGenerationPaused(false)"),
            "submitting wizard answers must clear the walkthrough paused state",
        )
        val onCancelled = controller.substringAfter("onCancelled = {")
            .substringBefore("setCefProcessInputCallbacks")
        assertTrue(
            onCancelled.contains("setGenerationPaused(false)"),
            "cancelling the wizard must clear the walkthrough paused state",
        )
    }

    @Test
    fun `controller exposes the wizard-pending query for Ask gating`() {
        assertTrue(
            controller.contains("fun isChatAwaitingUserReply()"),
            "Ask gating needs AgentController.isChatAwaitingUserReply()",
        )
    }
}

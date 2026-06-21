package com.workflow.orchestrator.agent.tools.background

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackgroundEligibilityTest {
    @Test
    fun `work tools are backgroundable`() {
        assertTrue(BackgroundEligibility.isBackgroundable("run_command"))
        assertTrue(BackgroundEligibility.isBackgroundable("read_file"))
        assertTrue(BackgroundEligibility.isBackgroundable("search_code"))
        assertTrue(BackgroundEligibility.isBackgroundable("agent"))
    }

    @Test
    fun `control-flow and interactive tools are not backgroundable`() {
        for (t in listOf(
            "attempt_completion", "task_report", "plan_mode_respond", "enable_plan_mode",
            "ask_followup_question", "ask_questions", "ask_user_input", "new_task",
            "use_skill", "tool_search", "think",
        )) {
            assertFalse(BackgroundEligibility.isBackgroundable(t), "$t must not be backgroundable")
        }
    }
}

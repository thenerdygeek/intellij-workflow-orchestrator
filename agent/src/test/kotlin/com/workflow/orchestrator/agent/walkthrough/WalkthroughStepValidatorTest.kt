package com.workflow.orchestrator.agent.walkthrough

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure coverage of the validator's bounds + error-message contract (no platform fixture — the
 * old BasePlatformTestCase variant collided on the headless "Indexing timeout"). The real
 * platform probe in `defaultStepValidator` just maps a file to a [StepFileProbe]; the decision
 * logic + the exact messages the LLM reads live in `validateStepsWith`, tested here.
 */
class WalkthroughStepValidatorTest {

    private fun step(file: String, start: Int, end: Int) =
        WalkthroughStep(file, start, end, null, "body")

    @Test
    fun `keeps valid steps and rejects missing, non-text and out-of-bounds individually`() {
        val steps = listOf(
            step("Real.kt", 1, 3), // valid (file has 5 lines)
            step("Gone.kt", 1, 1), // not found
            step("Binary.bin", 1, 1), // not text
            step("Real.kt", 9, 10), // start_line beyond EOF
            step("Real.kt", 2, 9), // end_line beyond EOF
        )
        val probe: (WalkthroughStep) -> StepFileProbe = { s ->
            when (s.file) {
                "Real.kt" -> StepFileProbe.Text(lineCount = 5)
                "Binary.bin" -> StepFileProbe.NotText
                else -> StepFileProbe.NotFound
            }
        }

        val result = validateStepsWith(steps, probe)

        assertEquals(1, result.valid.size)
        assertEquals("Real.kt", result.valid.single().file)
        assertEquals(4, result.errors.size)
        assertTrue(result.errors[0].contains("step 2: file not found: Gone.kt"))
        assertTrue(result.errors[1].contains("step 3: not a text file: Binary.bin"))
        assertTrue(result.errors[2].contains("step 4: start_line 9 exceeds file length 5"))
        assertTrue(result.errors[3].contains("step 5: end_line 9 exceeds file length 5"))
    }

    @Test
    fun `a step exactly at the last line is valid`() {
        val result = validateStepsWith(listOf(step("A.kt", 5, 5))) { StepFileProbe.Text(lineCount = 5) }
        assertEquals(1, result.valid.size)
        assertTrue(result.errors.isEmpty())
    }
}

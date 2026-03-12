package com.workflow.orchestrator.cody.service

import com.workflow.orchestrator.cody.protocol.ContextFile
import com.workflow.orchestrator.cody.protocol.Range
import com.workflow.orchestrator.cody.protocol.Position
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CodyContextServiceTest {

    private val service = CodyContextServiceLogic()

    @Test
    fun `buildFixInstruction includes issue message and type`() {
        val instruction = service.buildFixInstruction(
            issueType = "BUG",
            issueMessage = "Possible NullPointerException",
            ruleKey = "java:S2259"
        )
        assertTrue(instruction.contains("BUG"))
        assertTrue(instruction.contains("NullPointerException"))
        assertTrue(instruction.contains("S2259"))
    }

    @Test
    fun `buildTestInstruction includes line range`() {
        val instruction = service.buildTestInstruction(
            range = Range(start = Position(20, 0), end = Position(30, 0)),
            existingTestFile = null
        )
        assertTrue(instruction.contains("20"))
        assertTrue(instruction.contains("30"))
    }

    @Test
    fun `buildTestInstruction references existing test file when provided`() {
        val instruction = service.buildTestInstruction(
            range = Range(start = Position(10, 0), end = Position(20, 0)),
            existingTestFile = "src/test/kotlin/FooTest.kt"
        )
        assertTrue(instruction.contains("FooTest.kt"))
        assertTrue(instruction.contains("existing"))
    }

    @Test
    fun `resolveTestFileFallback maps main to test path`() {
        val result = service.resolveTestFileFallback("src/main/kotlin/com/app/UserService.kt")
        assertEquals("src/test/kotlin/com/app/UserServiceTest.kt", result)
    }

    @Test
    fun `resolveTestFileFallback maps java main to test path`() {
        val result = service.resolveTestFileFallback("src/main/java/com/app/UserService.java")
        assertEquals("src/test/java/com/app/UserServiceTest.java", result)
    }

    @Test
    fun `resolveTestFileFallback returns null for non-main files`() {
        val result = service.resolveTestFileFallback("src/test/kotlin/FooTest.kt")
        assertNull(result)
    }
}

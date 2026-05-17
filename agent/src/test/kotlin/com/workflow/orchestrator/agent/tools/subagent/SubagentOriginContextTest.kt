package com.workflow.orchestrator.agent.tools.subagent

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.coroutines.coroutineContext

class SubagentOriginContextTest {

    @Test
    fun `origin context is null outside withSubagentOrigin`() = runTest {
        assertNull(coroutineContext[SubagentOriginContext.Key])
    }

    @Test
    fun `origin context is visible inside withSubagentOrigin`() = runTest {
        withSubagentOrigin("a-42", "code-reviewer") {
            val ctx = coroutineContext[SubagentOriginContext.Key]
            assertEquals("a-42", ctx?.agentId)
            assertEquals("code-reviewer", ctx?.label)
        }
    }

    @Test
    fun `nested withSubagentOrigin shadows outer`() = runTest {
        withSubagentOrigin("outer-1", "outer-label") {
            withSubagentOrigin("inner-2", "inner-label") {
                val ctx = coroutineContext[SubagentOriginContext.Key]
                assertEquals("inner-2", ctx?.agentId)
                assertEquals("inner-label", ctx?.label)
            }
        }
    }
}

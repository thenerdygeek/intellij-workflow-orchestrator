package com.workflow.orchestrator.agent.tools.subagent

import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
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

    @Test
    fun `merging pre-withContext context with origin element preserves both`() = runTest {
        // Goal: a context that has BOTH the pre-withContext Job AND the SubagentOriginContext.
        val preContext = coroutineContext
        val originElement = SubagentOriginContext("a-test", "test-label")
        val merged = preContext + originElement

        // 1. The origin element is readable from the merged context.
        assertEquals("a-test", merged[SubagentOriginContext.Key]?.agentId)
        assertEquals("test-label", merged[SubagentOriginContext.Key]?.label)

        // 2. The merged context's Job is the pre-context's Job (NOT a withContext child).
        // Job identity must be the same as preContext's so scope.launch children
        // are fire-and-forget peers of preContext's Job, not children of an inner wrapper.
        assertSame(
            preContext[Job],
            merged[Job],
        )
    }

    @Test
    fun `SubagentRunner source merges originContext into callerContext for scope`() {
        val src = java.io.File(
            "src/main/kotlin/com/workflow/orchestrator/agent/tools/subagent/SubagentRunner.kt"
        ).readText()
        // The run() wrapper must build the callerContext as `preContext + SubagentOriginContext(...)`
        // so the origin element is visible to scope.launch children inside runInternal.
        // Acceptable patterns:
        //  - explicit construction: `SubagentOriginContext(agentId, label)`
        //  - context read inside withSubagentOrigin then merge
        val hasMerge = """SubagentOriginContext\(\s*agentId\s*,\s*label\s*\)""".toRegex().containsMatchIn(src)
                || """coroutineContext\[SubagentOriginContext\.Key\]""".toRegex().containsMatchIn(src)
        assert(hasMerge) {
            "SubagentRunner.run must merge SubagentOriginContext into the context passed to runInternal."
        }
    }
}

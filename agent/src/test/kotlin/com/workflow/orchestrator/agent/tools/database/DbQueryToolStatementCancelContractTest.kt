package com.workflow.orchestrator.agent.tools.database

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class DbQueryToolStatementCancelContractTest {
    private val src = File(
        "src/main/kotlin/com/workflow/orchestrator/agent/tools/database/DbQueryTool.kt"
    ).readText()

    @Test
    fun `registers a Statement cancel hook on coroutine cancellation`() {
        assertTrue(src.contains(".cancel()"), "the JDBC Statement must be cancelled on stop")
        assertTrue(
            src.contains("invokeOnCompletion") || src.contains("ensureActive") || src.contains("CancellationException"),
            "a cancellation hook must drive Statement.cancel()",
        )
    }
}

// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.agent.tools.background

import com.workflow.orchestrator.core.ai.AssistantMessageParser
import com.workflow.orchestrator.core.ai.ToolUseContent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RunInBackgroundParsingTest {
    private val xml = """
        <read_file>
        <path>src/Main.kt</path>
        <run_in_background>true</run_in_background>
        </read_file>
    """.trimIndent()

    @Test
    fun `reserved tag parses for a tool that does not declare it`() {
        // paramNames is the AUGMENTED set (registry params + reserved tag); read_file does NOT declare run_in_background.
        val paramNames = setOf("path", BackgroundEligibility.RUN_IN_BACKGROUND_PARAM)
        val parsed = AssistantMessageParser.parse(xml, toolNames = setOf("read_file"), paramNames = paramNames)
        val toolUse = parsed.filterIsInstance<ToolUseContent>().single()
        assertEquals("read_file", toolUse.name)
        assertEquals("src/Main.kt", toolUse.params["path"])
        assertEquals("true", toolUse.params[BackgroundEligibility.RUN_IN_BACKGROUND_PARAM])
    }

    @Test
    fun `reserved tag is dropped when NOT in paramNames -- proving the augmentation is what keeps it`() {
        // Without the reserved name in paramNames (the pre-augmentation state), the parser drops the unknown tag.
        val parsed = AssistantMessageParser.parse(xml, toolNames = setOf("read_file"), paramNames = setOf("path"))
        val toolUse = parsed.filterIsInstance<ToolUseContent>().single()
        assertEquals("src/Main.kt", toolUse.params["path"])
        assertNull(toolUse.params[BackgroundEligibility.RUN_IN_BACKGROUND_PARAM])
    }
}

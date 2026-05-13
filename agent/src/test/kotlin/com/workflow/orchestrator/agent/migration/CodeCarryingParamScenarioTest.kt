package com.workflow.orchestrator.agent.migration

import com.workflow.orchestrator.core.ai.AssistantMessageParser
import com.workflow.orchestrator.core.ai.ToolUseContent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CodeCarryingParamScenarioTest {

    private val knownTools = setOf("create_file", "edit_file")
    private val knownParams = setOf("path", "old_string", "new_string", "content", "diff")

    @Test
    fun `K1 — content with embedded closing-tag-like substring uses lastIndexOf to find true close`() {
        // The MODEL writes a content payload that itself contains a literal `</content>`
        // substring (e.g., user is writing HTML). The parser MUST use the last occurrence
        // of `</content>` to delimit the tool param, not the first.
        val raw = """<create_file>
<path>page.html</path>
<content><div>before</content>middle</content>after</content>
</create_file>"""
        val tu = AssistantMessageParser.parse(raw, knownTools, knownParams)
            .filterIsInstance<ToolUseContent>().single()
        // The captured content should include the embedded </content> substrings up to
        // the LAST </content> close
        val content = tu.params["content"]!!
        assertTrue(content.contains("<div>before</content>middle"))
        assertTrue(content.endsWith("after"))
    }

    @Test
    fun `K2 — diff with XML-like tokens parses correctly`() {
        val raw = """<edit_file>
<path>a.kt</path>
<diff>- <tag>old</tag>
+ <tag>new</tag></diff>
</edit_file>"""
        val tu = AssistantMessageParser.parse(raw, knownTools, knownParams)
            .filterIsInstance<ToolUseContent>().single()
        assertTrue(tu.params["diff"]!!.contains("<tag>old</tag>"))
        assertTrue(tu.params["diff"]!!.contains("<tag>new</tag>"))
    }

    @Test
    fun `K3 — old_string and new_string preserve embedded XML verbatim`() {
        val raw = """<edit_file>
<path>a.kt</path>
<old_string><foo></foo></old_string>
<new_string><bar></bar></new_string>
</edit_file>"""
        val tu = AssistantMessageParser.parse(raw, knownTools, knownParams)
            .filterIsInstance<ToolUseContent>().single()
        assertEquals("<foo></foo>", tu.params["old_string"])
        assertEquals("<bar></bar>", tu.params["new_string"])
    }
}

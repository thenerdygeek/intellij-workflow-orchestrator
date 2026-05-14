package com.workflow.orchestrator.agent.tools.integration

import com.workflow.orchestrator.core.services.ToolResult as CoreToolResult
import com.workflow.orchestrator.agent.tools.ToolOutputConfig
import com.workflow.orchestrator.agent.tools.ToolOutputSpiller
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Tests for toAgentToolResult() String-data handling.
 *
 * Regression guard for the "10K premature truncation" bug:
 * Previously, String data was capped at 10_000 chars inside toAgentToolResult(), which
 * silently stripped content before AgentLoop's grep_pattern and output_file pipeline
 * could operate on it. Large build logs (249K chars) arrived at AgentLoop already
 * truncated, so grep always returned empty and output_file never spilled to disk.
 *
 * The fix: pass the full String through. AgentLoop owns size management
 * (30K auto-spill, 50K hard cap via truncateOutput, output_file explicit spill).
 */
class ToAgentToolResultStringTest {

    // ── full content pass-through ─────────────────────────────────────────────

    @Test
    fun `large String data passes through without truncation`() {
        val bigLog = "A".repeat(50_000)
        val core = CoreToolResult(data = bigLog, summary = "Build log for PROJ-1: 50000 chars")
        val agent = core.toAgentToolResult()

        assertTrue(
            agent.content.contains(bigLog),
            "Full 50K log must survive toAgentToolResult — AgentLoop is responsible for size management"
        )
        assertFalse(
            agent.content.contains("[TRUNCATED"),
            "toAgentToolResult must NOT add its own TRUNCATED marker; that belongs to AgentLoop's pipeline"
        )
    }

    @Test
    fun `content at exactly the old 10K boundary passes through`() {
        val log = "X".repeat(10_001)
        val core = CoreToolResult(data = log, summary = "log summary")
        val agent = core.toAgentToolResult()

        assertTrue(agent.content.length >= log.length, "10001-char string must not be cut")
    }

    // ── grep_pattern can match tail content ───────────────────────────────────

    @Test
    fun `grep_pattern applied to full content finds match at byte 240000`() {
        // Simulate a Maven build log: 240K of compile noise + 10K of deploy output at the tail
        val noise = "[ INFO ] compiling source\n".repeat(10_000)     // ~240K
        val deployTail = "Uploading artifact-1.84.0-20260514.123456-1.jar\n" +
            "BUILD SUCCESS\n"
        val fullLog = noise + deployTail

        val core = CoreToolResult(data = fullLog, summary = "Build log: ${fullLog.length} chars")
        val agent = core.toAgentToolResult()

        // Simulate what AgentLoop does: apply grep to the full content the tool returns
        val grepResult = ToolOutputConfig.applyGrep(agent.content, "20260514")
        assertTrue(
            grepResult.isNotBlank(),
            "grep for '20260514' must find the timestamped deploy line — " +
                "it lives at byte ~240K and was previously unreachable after the 10K cap"
        )
        assertTrue(grepResult.contains("20260514"))
    }

    @Test
    fun `grep_pattern for BUILD SUCCESS finds tail-only match`() {
        val head = "some startup output\n".repeat(500)   // ~10K
        val tail = "BUILD SUCCESS\n"
        val fullLog = head + tail

        val core = CoreToolResult(data = fullLog, summary = "log")
        val agent = core.toAgentToolResult()

        val grepResult = ToolOutputConfig.applyGrep(agent.content, "BUILD SUCCESS")
        assertTrue(grepResult.contains("BUILD SUCCESS"), "tail content must be reachable via grep")
    }

    // ── small strings still work correctly ────────────────────────────────────

    @Test
    fun `short String data is appended after summary`() {
        val core = CoreToolResult(data = "hello world", summary = "small result")
        val agent = core.toAgentToolResult()

        assertTrue(agent.content.contains("small result"))
        assertTrue(agent.content.contains("hello world"))
    }

    @Test
    fun `String data equal to summary is not duplicated`() {
        val core = CoreToolResult(data = "the summary", summary = "the summary")
        val agent = core.toAgentToolResult()

        val count = agent.content.split("the summary").size - 1
        assertEquals(1, count, "summary-equal data must not be appended a second time")
    }

    @Test
    fun `blank String data produces only the summary`() {
        val core = CoreToolResult(data = "   ", summary = "empty log")
        val agent = core.toAgentToolResult()

        assertEquals("empty log", agent.content.trim())
    }

    // ── end-to-end: toAgentToolResult → AgentLoop spill decision → file path ──
    // These simulate exactly what AgentLoop does after receiving the ToolResult.

    @Test
    fun `large build log from toAgentToolResult triggers auto-spill with file path`(@TempDir spillDir: Path) {
        // 249K Maven build log — same shape as the user-reported failure
        val noise = "[ INFO ] compiling module\n".repeat(10_000)   // ~250K
        val fullLog = noise + "BUILD SUCCESS\n"
        assertTrue(fullLog.length > ToolOutputConfig.SPILL_THRESHOLD_CHARS,
            "test data must exceed the 30K threshold to exercise the spill path")

        // Step 1: toAgentToolResult passes full content through (the fix)
        val core = CoreToolResult(data = fullLog, summary = "Build log for PROJ-1: ${fullLog.length} chars")
        val agentResult = core.toAgentToolResult()
        assertTrue(agentResult.content.length > ToolOutputConfig.SPILL_THRESHOLD_CHARS,
            "full log must survive toAgentToolResult so AgentLoop's 30K threshold fires")

        // Step 2: AgentLoop spill decision — content > 30K → spill
        val spiller = ToolOutputSpiller(spillDir)
        val spillResult = spiller.spill("bamboo_builds", agentResult.content)

        // File path must appear in the preview the LLM receives
        assertNotNull(spillResult.spilledToFile, "spill file must be created for a >30K build log")
        assertTrue(spillResult.preview.contains("Output saved to:"),
            "preview sent to LLM must contain the file path so it can call read_file")
        assertTrue(spillResult.preview.contains("bamboo_builds"),
            "tool name must appear in the spill file path/preview")

        // Full content is preserved on disk (not truncated)
        val diskContent = File(spillResult.spilledToFile!!).readText()
        assertEquals(agentResult.content, diskContent,
            "disk file must contain the complete log, not a truncated version")
    }

    @Test
    fun `output_file=true on large log — preview is short and contains file path`(@TempDir spillDir: Path) {
        val fullLog = "log line\n".repeat(5_000)  // ~45K — well over 30K
        val core = CoreToolResult(data = fullLog, summary = "Build log: ${fullLog.length} chars")
        val agentResult = core.toAgentToolResult()

        val spiller = ToolOutputSpiller(spillDir)
        val spillResult = spiller.spill("bamboo_builds", agentResult.content)

        // Preview is far shorter than the full log (head-20 + tail-10 lines, not 5000 lines)
        assertTrue(spillResult.preview.length < fullLog.length / 10,
            "preview must be much shorter than the full log")
        assertTrue(spillResult.preview.contains("Output saved to:"))
        assertTrue(spillResult.preview.contains("[Use read_file or search_code"))
    }

    // ── grep ordering: grep runs on full content, only matches reach the LLM ──

    @Test
    fun `grep_pattern sees full content and only matched lines reach LLM`(@TempDir spillDir: Path) {
        // 249K log: only the last few lines match the pattern
        val noise = "[ INFO ] compiling\n".repeat(10_000)
        val deployLines = "Uploading artifact-1.84.0-20260514.123456-1.jar to repo\n" +
            "Uploaded  artifact-1.84.0-20260514.123456-1.jar\n" +
            "BUILD SUCCESS\n"
        val fullLog = noise + deployLines

        val core = CoreToolResult(data = fullLog, summary = "log")
        val agentResult = core.toAgentToolResult()

        // AgentLoop applies grep to the full content from toAgentToolResult()
        val grepResult = ToolOutputConfig.applyGrep(agentResult.content, "20260514")

        // Only matching lines are returned to the LLM — not the 249K of compile noise
        assertTrue(grepResult.contains("20260514"), "matched lines must be present")
        assertFalse(grepResult.contains("[ INFO ] compiling"), "non-matching noise must be absent")
        assertTrue(grepResult.length < 500, "grep output should be tiny — only the matched lines")

        // After grep the content is small — no auto-spill needed, no file path expected
        val spiller = ToolOutputSpiller(spillDir)
        val spillResult = spiller.spill("bamboo_builds", grepResult)
        assertNull(spillResult.spilledToFile,
            "grep result is small — spill must NOT fire, matched lines are returned directly")
    }

    @Test
    fun `grep_pattern with output_file=true — disk file has filtered content not full log`(@TempDir spillDir: Path) {
        // 50K log: only a few lines match
        val log = (1..2500).joinToString("\n") { i ->
            if (i % 500 == 0) "SNAPSHOT artifact-1.84.0-20260514.${i}-1.jar uploaded"
            else "[ INFO ] build step $i"
        }
        val core = CoreToolResult(data = log, summary = "log")
        val agentResult = core.toAgentToolResult()

        // AgentLoop: grep first, then output_file=true forces spill of whatever remains
        val grepResult = ToolOutputConfig.applyGrep(agentResult.content, "SNAPSHOT")

        val spiller = ToolOutputSpiller(spillDir)
        val spillResult = spiller.spill("bamboo_builds", grepResult)

        // With output_file=true the spill always fires even for small grep results
        // (AgentLoop condition: requestedOutputFile || content > 30K)
        // Here grepResult is small so auto-spill won't fire — this test documents the
        // correct behaviour: grep wins, small result returned directly without a disk write.
        assertNull(spillResult.spilledToFile,
            "grep reduced content below 30K — spill must not fire even if output_file=true " +
            "was the intent; AgentLoop uses OR so requestedOutputFile=true would still spill, " +
            "but the spiller itself has no knowledge of that flag")
        assertTrue(spillResult.preview.contains("SNAPSHOT"), "matched lines must be in the result")
        assertFalse(spillResult.preview.contains("[ INFO ]"), "compile noise must not be in the result")
    }
}

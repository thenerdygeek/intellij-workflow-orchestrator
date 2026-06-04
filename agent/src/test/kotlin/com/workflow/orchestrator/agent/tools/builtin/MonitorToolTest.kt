package com.workflow.orchestrator.agent.tools.builtin

import com.workflow.orchestrator.agent.monitor.MonitorEvent
import com.workflow.orchestrator.agent.monitor.MonitorHandle
import com.workflow.orchestrator.agent.monitor.MonitorSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MonitorToolTest {

    private fun tool() = MonitorTool(sessionIdProvider = { "s1" }, cs = CoroutineScope(SupervisorJob()))

    private fun handleWith(id: String, label: String, vararg lines: String): MonitorHandle {
        val src = object : MonitorSource {
            override val monitorId = id
            override val description = label
            override fun start(emit: (MonitorEvent) -> Unit) {}
            override fun stop() {}
        }
        return MonitorHandle(src, sessionId = "s1", startedAt = 0L).also { h -> lines.forEach { h.appendLine(it) } }
    }

    @Test
    fun `renderStatus shows id label state and buffered event lines`() {
        val s = MonitorTool.renderStatus(handleWith("shell-abc", "watch build", "line1 ERROR", "line2 done"))
        assertTrue(s.contains("shell-abc"), "should show id: $s")
        assertTrue(s.contains("watch build"), "should show label: $s")
        assertTrue(s.contains("RUNNING"), "should show state: $s")
        assertTrue(s.contains("line1 ERROR"), "should show buffered events: $s")
        assertTrue(s.contains("line2 done"), "should show buffered events: $s")
    }

    @Test
    fun `renderStatus notes when no events have matched yet`() {
        val s = MonitorTool.renderStatus(handleWith("shell-xyz", "watch"))
        assertTrue(s.contains("shell-xyz"), "should show id: $s")
        assertTrue(s.contains("no matching events", ignoreCase = true), "should note empty buffer: $s")
    }

    @Test
    fun `status is an allowed action value in the schema`() {
        val enum = tool().parameters.properties["action"]?.enumValues
        assertTrue(enum != null && "status" in enum, "action enum should include 'status', was: $enum")
    }

    @Test
    fun `filter description documents case sensitivity`() {
        val desc = tool().parameters.properties["filter"]?.description ?: ""
        assertTrue(desc.contains("(?i)"), "filter desc should document case-insensitive (?i) prefix: $desc")
    }

    @Test
    fun `start requires command and filter for shell source`() {
        val err = MonitorTool.validateStart(source = "shell", command = null, filter = "ERROR")
        assertTrue(err!!.contains("command"))
    }

    @Test
    fun `start rejects an unknown source`() {
        val err = MonitorTool.validateStart(source = "kafka", command = "x", filter = "y")
        assertTrue(err!!.contains("not supported"))
    }

    @Test
    fun `source enum includes bamboo`() {
        val enum = tool().parameters.properties["source"]?.enumValues
        assertTrue(enum != null && "bamboo" in enum, "source enum should include 'bamboo', was: $enum")
    }

    // ---- validateBambooStart -------------------------------------------------

    @Test
    fun `validateBambooStart missing planKey returns error`() {
        val err = MonitorTool.validateBambooStart(planKey = null, level = "build", stageName = null, jobName = null)
        assertTrue(err != null && err.contains("plan_key"), "expected plan_key error, got: $err")
    }

    @Test
    fun `validateBambooStart bad level returns error`() {
        val err = MonitorTool.validateBambooStart(planKey = "PROJ-PLAN", level = "sprint", stageName = null, jobName = null)
        assertTrue(err != null && (err.contains("level") || err.contains("build") || err.contains("stage") || err.contains("job")),
            "expected level error, got: $err")
    }

    @Test
    fun `validateBambooStart level stage without stage_name returns error`() {
        val err = MonitorTool.validateBambooStart(planKey = "PROJ-PLAN", level = "stage", stageName = null, jobName = null)
        assertTrue(err != null && err.contains("stage_name"), "expected stage_name error, got: $err")
    }

    @Test
    fun `validateBambooStart level job without stage_name and job_name returns error`() {
        val err = MonitorTool.validateBambooStart(planKey = "PROJ-PLAN", level = "job", stageName = null, jobName = null)
        assertTrue(err != null, "expected error for job without stage_name and job_name")
    }

    @Test
    fun `validateBambooStart valid build returns null`() {
        val err = MonitorTool.validateBambooStart(planKey = "PROJ-PLAN", level = "build", stageName = null, jobName = null)
        assertEquals(null, err, "valid build params should pass, got: $err")
    }

    @Test
    fun `validateBambooStart null level defaults to build and passes`() {
        val err = MonitorTool.validateBambooStart(planKey = "PROJ-PLAN", level = null, stageName = null, jobName = null)
        assertEquals(null, err, "null level should default to build (valid), got: $err")
    }

    @Test
    fun `valid shell start passes validation`() {
        assertEquals(null, MonitorTool.validateStart(source = "shell", command = "tail -f log", filter = "ERROR"))
    }

    @Test
    fun `invalid regex filter is reported`() {
        val err = MonitorTool.validateStart(source = "shell", command = "tail -f log", filter = "(")
        assertTrue(err!!.contains("regex"))
    }

    @Test
    fun `renderStatus shows EXITED state and exit code for a finished monitor`() {
        val h = handleWith("shell-z", "watch build", "compiling…", "BUILD FAILED")
        h.markExited(1)
        val s = MonitorTool.renderStatus(h)
        assertTrue(s.contains("EXITED"), "status should show EXITED state: $s")
        assertTrue(s.contains("code=1") || s.contains("(1)"), "status should surface the exit code: $s")
        assertTrue(s.contains("BUILD FAILED"), "status should still show buffered matched lines: $s")
    }

    @Test
    fun `renderStatus shows code=0 for a clean exit`() {
        val h = handleWith("shell-ok", "watch build", "BUILD SUCCESSFUL")
        h.markExited(0)
        val s = MonitorTool.renderStatus(h)
        assertTrue(s.contains("code=0"), "status should surface a clean exit code: $s")
    }

    @Test
    fun `renderStatus handles unknown (null) exit code`() {
        val h = handleWith("shell-unknown", "watch build", "something")
        h.markExited(null)
        val s = MonitorTool.renderStatus(h)
        assertTrue(s.contains("EXITED"), "status should render EXITED without crashing on a null code: $s")
    }
}

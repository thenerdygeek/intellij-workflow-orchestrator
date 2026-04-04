package com.workflow.orchestrator.agent.hooks

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class HookEventTest {

    @Test
    fun `hookName returns PascalCase for all types`() {
        assertEquals("TaskStart", HookType.TASK_START.hookName)
        assertEquals("UserPromptSubmit", HookType.USER_PROMPT_SUBMIT.hookName)
        assertEquals("TaskResume", HookType.TASK_RESUME.hookName)
        assertEquals("PreCompact", HookType.PRE_COMPACT.hookName)
        assertEquals("TaskCancel", HookType.TASK_CANCEL.hookName)
        assertEquals("TaskComplete", HookType.TASK_COMPLETE.hookName)
        assertEquals("PreToolUse", HookType.PRE_TOOL_USE.hookName)
        assertEquals("PostToolUse", HookType.POST_TOOL_USE.hookName)
    }

    @Test
    fun `isCancellable returns true for cancellable hooks`() {
        assertTrue(HookType.isCancellable(HookType.TASK_START))
        assertTrue(HookType.isCancellable(HookType.USER_PROMPT_SUBMIT))
        assertTrue(HookType.isCancellable(HookType.TASK_RESUME))
        assertTrue(HookType.isCancellable(HookType.PRE_COMPACT))
        assertTrue(HookType.isCancellable(HookType.PRE_TOOL_USE))
    }

    @Test
    fun `isCancellable returns false for observation-only hooks`() {
        assertFalse(HookType.isCancellable(HookType.TASK_CANCEL))
        assertFalse(HookType.isCancellable(HookType.TASK_COMPLETE))
        assertFalse(HookType.isCancellable(HookType.POST_TOOL_USE))
    }

    @Test
    fun `fromString parses UPPER_SNAKE_CASE`() {
        assertEquals(HookType.TASK_START, HookType.fromString("TASK_START"))
        assertEquals(HookType.PRE_TOOL_USE, HookType.fromString("PRE_TOOL_USE"))
        assertEquals(HookType.POST_TOOL_USE, HookType.fromString("POST_TOOL_USE"))
    }

    @Test
    fun `fromString parses PascalCase`() {
        assertEquals(HookType.TASK_START, HookType.fromString("TaskStart"))
        assertEquals(HookType.PRE_TOOL_USE, HookType.fromString("PreToolUse"))
        assertEquals(HookType.POST_TOOL_USE, HookType.fromString("PostToolUse"))
        assertEquals(HookType.USER_PROMPT_SUBMIT, HookType.fromString("UserPromptSubmit"))
    }

    @Test
    fun `fromString returns null for unknown names`() {
        assertNull(HookType.fromString("UnknownHook"))
        assertNull(HookType.fromString(""))
        assertNull(HookType.fromString("pre_tool_use")) // lowercase not supported
    }

    @Test
    fun `HookEvent defaults cancellable from type`() {
        val cancellableEvent = HookEvent(type = HookType.PRE_TOOL_USE, data = emptyMap())
        assertTrue(cancellableEvent.cancellable)

        val observationEvent = HookEvent(type = HookType.POST_TOOL_USE, data = emptyMap())
        assertFalse(observationEvent.cancellable)
    }

    @Test
    fun `HookEvent cancellable can be overridden`() {
        val event = HookEvent(type = HookType.PRE_TOOL_USE, data = emptyMap(), cancellable = false)
        assertFalse(event.cancellable)
    }

    @Test
    fun `TASK_COMPLETE hook is observation-only and not cancellable`() {
        val event = HookEvent(
            type = HookType.TASK_COMPLETE,
            data = mapOf(
                "sessionId" to "sess-123",
                "summary" to "Task completed successfully",
                "iterations" to 5,
                "tokensUsed" to 12000
            )
        )
        assertFalse(event.cancellable, "TASK_COMPLETE must be non-cancellable (observation-only)")
        assertEquals("TaskComplete", event.type.hookName)
    }

    @Test
    fun `fromString parses TASK_COMPLETE in both formats`() {
        assertEquals(HookType.TASK_COMPLETE, HookType.fromString("TASK_COMPLETE"))
        assertEquals(HookType.TASK_COMPLETE, HookType.fromString("TaskComplete"))
    }

    @Test
    fun `all 8 hook types are present`() {
        assertEquals(8, HookType.entries.size, "Should have 8 hook types matching Cline's full set")
    }
}

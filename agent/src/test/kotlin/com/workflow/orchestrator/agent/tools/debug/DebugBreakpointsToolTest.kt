package com.workflow.orchestrator.agent.tools.debug

import com.intellij.debugger.ui.breakpoints.JavaExceptionBreakpointType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointManager
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.java.debugger.breakpoints.properties.JavaExceptionBreakpointProperties
import org.jetbrains.java.debugger.breakpoints.properties.JavaFieldBreakpointProperties
import org.jetbrains.java.debugger.breakpoints.properties.JavaMethodBreakpointProperties
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DebugBreakpointsToolTest {
    private val project = mockk<Project>(relaxed = true)
    private val controller = mockk<AgentDebugController>(relaxed = true)
    private val tool = DebugBreakpointsTool(controller)

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    // ── Schema / wiring ─────────────────────────────────────────────────────

    @Test
    fun `tool name is debug_breakpoints`() {
        assertEquals("debug_breakpoints", tool.name)
    }

    @Test
    fun `action enum contains all 7 actions`() {
        val actions = tool.parameters.properties["action"]?.enumValues
        assertNotNull(actions)
        assertEquals(7, actions!!.size)
        assertTrue("add_breakpoint" in actions)
        assertTrue("method_breakpoint" in actions)
        assertTrue("exception_breakpoint" in actions)
        assertTrue("field_watchpoint" in actions)
        assertTrue("remove_breakpoint" in actions)
        assertTrue("list_breakpoints" in actions)
        assertTrue("attach_to_process" in actions)
    }

    @Test
    fun `only action is required`() {
        assertEquals(listOf("action"), tool.parameters.required)
    }

    @Test
    fun `allowedWorkers includes CODER, REVIEWER, ANALYZER`() {
        assertEquals(
            setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER),
            tool.allowedWorkers
        )
    }

    @Test
    fun `toToolDefinition produces valid schema`() {
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("debug_breakpoints", def.function.name)
        assertEquals("object", def.function.parameters.type)
        assertTrue(def.function.description.isNotBlank())
    }

    @Test
    fun `missing action returns error`() = runTest {
        val result = tool.execute(buildJsonObject { }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("action"))
    }

    @Test
    fun `unknown action returns error`() = runTest {
        val result = tool.execute(buildJsonObject { put("action", "nonexistent") }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("Unknown action"))
    }

    // ── Task 4.6 — Validation-path coverage per breakpoint type ─────────────
    // These tests exercise the early-return validation guards that run BEFORE
    // the Dispatchers.EDT + WriteAction.compute block. The happy-path add/list
    // flows require a live XBreakpointManager and IntelliJ write-action
    // machinery, which cannot be provided in a MockK unit test. Full
    // add-breakpoint happy-path coverage is deferred to the manual
    // verification checklist at docs/plans/2026-04-17-phase5-manual-verification.md.

    @Test
    fun `add_breakpoint rejects missing file`() = runTest {
        val result = tool.execute(
            buildJsonObject {
                put("action", "add_breakpoint")
                put("line", 10)
            },
            project
        )
        assertTrue(result.isError, "Missing 'file' must produce an error")
        assertTrue(result.content.contains("file"), "Error message must name the missing parameter")
    }

    @Test
    fun `add_breakpoint rejects missing line`() = runTest {
        val result = tool.execute(
            buildJsonObject {
                put("action", "add_breakpoint")
                put("file", "src/Main.java")
            },
            project
        )
        assertTrue(result.isError)
        assertTrue(result.content.contains("line"))
    }

    @Test
    fun `add_breakpoint rejects line less than 1`() = runTest {
        val result = tool.execute(
            buildJsonObject {
                put("action", "add_breakpoint")
                put("file", "src/Main.java")
                put("line", 0)
            },
            project
        )
        assertTrue(result.isError)
        assertTrue(result.content.contains("Line number must be >= 1"))
    }

    @Test
    fun `method_breakpoint rejects when both watch_entry and watch_exit are false`() = runTest {
        val result = tool.execute(
            buildJsonObject {
                put("action", "method_breakpoint")
                put("class_name", "com.foo.Bar")
                put("method_name", "doWork")
                put("watch_entry", false)
                put("watch_exit", false)
            },
            project
        )
        assertTrue(result.isError, "Both watches disabled must produce an error")
        assertTrue(
            result.content.contains("watch_entry") && result.content.contains("watch_exit"),
            "Error must name both watch parameters; got: ${result.content}"
        )
    }

    @Test
    fun `method_breakpoint rejects missing class_name`() = runTest {
        val result = tool.execute(
            buildJsonObject {
                put("action", "method_breakpoint")
                put("method_name", "doWork")
            },
            project
        )
        assertTrue(result.isError)
        assertTrue(result.content.contains("class_name"))
    }

    @Test
    fun `method_breakpoint rejects missing method_name`() = runTest {
        val result = tool.execute(
            buildJsonObject {
                put("action", "method_breakpoint")
                put("class_name", "com.foo.Bar")
            },
            project
        )
        assertTrue(result.isError)
        assertTrue(result.content.contains("method_name"))
    }

    @Test
    fun `exception_breakpoint rejects missing exception_class`() = runTest {
        val result = tool.execute(
            buildJsonObject { put("action", "exception_breakpoint") },
            project
        )
        assertTrue(result.isError)
        assertTrue(result.content.contains("exception_class"))
    }

    @Test
    fun `exception_breakpoint rejects blank exception_class`() = runTest {
        val result = tool.execute(
            buildJsonObject {
                put("action", "exception_breakpoint")
                put("exception_class", "   ")
            },
            project
        )
        assertTrue(result.isError)
        assertTrue(
            result.content.contains("cannot be blank") || result.content.contains("blank"),
            "Error must explain blank rejection; got: ${result.content}"
        )
    }

    @Test
    fun `field_watchpoint rejects when both watch_read and watch_write are false`() = runTest {
        val result = tool.execute(
            buildJsonObject {
                put("action", "field_watchpoint")
                put("class_name", "com.foo.Bar")
                put("field_name", "count")
                put("watch_read", false)
                put("watch_write", false)
            },
            project
        )
        assertTrue(result.isError, "Both watches disabled must produce an error")
        assertTrue(
            result.content.contains("watch_read") && result.content.contains("watch_write"),
            "Error must name both watch parameters; got: ${result.content}"
        )
    }

    @Test
    fun `field_watchpoint rejects missing class_name`() = runTest {
        val result = tool.execute(
            buildJsonObject {
                put("action", "field_watchpoint")
                put("field_name", "count")
            },
            project
        )
        assertTrue(result.isError)
        assertTrue(result.content.contains("class_name"))
    }

    @Test
    fun `field_watchpoint rejects missing field_name`() = runTest {
        val result = tool.execute(
            buildJsonObject {
                put("action", "field_watchpoint")
                put("class_name", "com.foo.Bar")
            },
            project
        )
        assertTrue(result.isError)
        assertTrue(result.content.contains("field_name"))
    }

    // ── Task 4.6.5 — list_breakpoints covers all four breakpoint types ──────
    //
    // Contract under test: list_breakpoints surfaces plain line breakpoints,
    // method breakpoints (XLineBreakpoint<JavaMethodBreakpointProperties>),
    // field watchpoints (XLineBreakpoint<JavaFieldBreakpointProperties>),
    // and Java exception breakpoints (XBreakpoint<JavaExceptionBreakpointProperties>
    // — NOT an XLineBreakpoint), and distinguishes them in the serialized
    // output.
    //
    // The current implementation distinguishes types via string prefixes
    // (no explicit `type` field):
    //   - Plain line:         "Foo.java:42 [...]"
    //   - Method breakpoint:  "Method: com.foo.Bar.doWork [...]"
    //   - Field watchpoint:   "Field: com.foo.Bar.count [...]"
    //   - Exception:          "Exception: java.lang.NullPointerException [...]"
    //
    // Static-audit finding: this prefix-based tagging is the canonical type
    // signal. Adding an explicit `type=X` field would be churn; test asserts
    // against the actual output shape.

    @Test
    fun `list_breakpoints surfaces all four breakpoint types with distinct prefixes`() = runTest {
        // Build mocks for each of the four breakpoint types.

        // 1. Plain line breakpoint (no Java-specific properties).
        val plainProps = mockk<com.intellij.xdebugger.breakpoints.XBreakpointProperties<*>>(relaxed = true)
        val plainLineBp = mockk<XLineBreakpoint<*>>(relaxed = true).apply {
            every { fileUrl } returns "file:///proj/src/Plain.java"
            every { line } returns 41 // zero-based, displays as 42
            every { isEnabled } returns true
            every { isTemporary } returns false
            every { suspendPolicy } returns SuspendPolicy.ALL
            every { conditionExpression } returns null
            every { logExpressionObject } returns null
            every { properties } returns plainProps
        }

        // 2. Method breakpoint — XLineBreakpoint whose props are JavaMethodBreakpointProperties.
        val methodProps = JavaMethodBreakpointProperties().apply {
            myClassPattern = "com.foo.Bar"
            myMethodName = "doWork"
            WATCH_ENTRY = true
            WATCH_EXIT = false
        }
        val methodBp = mockk<XLineBreakpoint<*>>(relaxed = true).apply {
            every { fileUrl } returns "file:///proj/src/Bar.java"
            every { line } returns 9
            every { isEnabled } returns true
            every { isTemporary } returns false
            every { suspendPolicy } returns SuspendPolicy.ALL
            every { conditionExpression } returns null
            every { logExpressionObject } returns null
            every { properties } returns methodProps
        }

        // 3. Field watchpoint — XLineBreakpoint whose props are JavaFieldBreakpointProperties.
        val fieldProps = JavaFieldBreakpointProperties("count", "com.foo.Bar").apply {
            WATCH_ACCESS = true
            WATCH_MODIFICATION = true
        }
        val fieldBp = mockk<XLineBreakpoint<*>>(relaxed = true).apply {
            every { fileUrl } returns "file:///proj/src/Bar.java"
            every { line } returns 14
            every { isEnabled } returns true
            every { isTemporary } returns false
            every { suspendPolicy } returns SuspendPolicy.ALL
            every { conditionExpression } returns null
            every { logExpressionObject } returns null
            every { properties } returns fieldProps
        }

        // 4. Exception breakpoint — bare XBreakpoint (NOT XLineBreakpoint) with
        //    Java exception properties. Type must be an instance of
        //    JavaExceptionBreakpointType for the isJavaExceptionBreakpoint() check.
        val exceptionProps = JavaExceptionBreakpointProperties("java.lang.NullPointerException").apply {
            NOTIFY_CAUGHT = true
            NOTIFY_UNCAUGHT = false
        }
        val exceptionType = mockk<JavaExceptionBreakpointType>(relaxed = true)
        val exceptionBp = mockk<XBreakpoint<*>>(relaxed = true).apply {
            @Suppress("UNCHECKED_CAST")
            every { type } returns exceptionType as XBreakpointType<XBreakpoint<Nothing>, *>
            every { isEnabled } returns true
            every { conditionExpression } returns null
            every { properties } returns exceptionProps
        }

        // Wire XDebuggerManager.getInstance(project).breakpointManager.allBreakpoints.
        val bpManager = mockk<XBreakpointManager>(relaxed = true)
        every { bpManager.allBreakpoints } returns arrayOf(plainLineBp, methodBp, fieldBp, exceptionBp)

        val xDebuggerManager = mockk<XDebuggerManager>(relaxed = true)
        every { xDebuggerManager.breakpointManager } returns bpManager

        mockkStatic(XDebuggerManager::class)
        every { XDebuggerManager.getInstance(project) } returns xDebuggerManager

        val result = tool.execute(
            buildJsonObject { put("action", "list_breakpoints") },
            project
        )

        assertFalse(
            result.isError,
            "list_breakpoints with four registered breakpoints must succeed; got: ${result.content}"
        )

        val content = result.content

        // Every type must appear with its distinguishing prefix.
        assertTrue(
            content.contains("Plain.java:42"),
            "Plain line breakpoint must appear as '<file>:<line>'; got:\n$content"
        )
        assertTrue(
            content.contains("Method: com.foo.Bar.doWork"),
            "Method breakpoint must use 'Method:' prefix; got:\n$content"
        )
        assertTrue(
            content.contains("Field: com.foo.Bar.count"),
            "Field watchpoint must use 'Field:' prefix; got:\n$content"
        )
        assertTrue(
            content.contains("Exception: java.lang.NullPointerException"),
            "Exception breakpoint must use 'Exception:' prefix; got:\n$content"
        )

        // Header count must reflect all four entries.
        assertTrue(
            content.contains("Breakpoints (4)"),
            "Header must report exactly four breakpoints; got:\n$content"
        )

        // Per-type traits must surface so the LLM can distinguish them beyond the prefix.
        assertTrue(
            content.contains("entry"),
            "Method breakpoint with WATCH_ENTRY=true must include 'entry' trait; got:\n$content"
        )
        assertTrue(
            content.contains("access") && content.contains("modification"),
            "Field watchpoint with both reads and writes enabled must include 'access' and 'modification' traits; got:\n$content"
        )
        assertTrue(
            content.contains("caught"),
            "Exception breakpoint with NOTIFY_CAUGHT=true must include 'caught' trait; got:\n$content"
        )
    }

    @Test
    fun `list_breakpoints reports no breakpoints when manager is empty`() = runTest {
        val bpManager = mockk<XBreakpointManager>(relaxed = true)
        every { bpManager.allBreakpoints } returns emptyArray()

        val xDebuggerManager = mockk<XDebuggerManager>(relaxed = true)
        every { xDebuggerManager.breakpointManager } returns bpManager

        mockkStatic(XDebuggerManager::class)
        every { XDebuggerManager.getInstance(project) } returns xDebuggerManager

        val result = tool.execute(
            buildJsonObject { put("action", "list_breakpoints") },
            project
        )

        assertFalse(result.isError, "Empty list is a valid non-error response; got: ${result.content}")
        assertTrue(
            result.content.contains("No breakpoints"),
            "Expected 'No breakpoints' in content; got: ${result.content}"
        )
    }

    // ── Task 11 — C7: canPutBreakpointAt pre-check ──────────────────────────

    @Test
    fun `add_breakpoint on non-breakpointable line returns 'line not breakpointable' error`() = runTest {
        // Arrange: project.basePath returns a real-looking root so PathValidator passes.
        every { project.basePath } returns "/tmp/testproject"

        // Stub LocalFileSystem so findFileByPath returns a mock VirtualFile.
        val vFile = mockk<VirtualFile>(relaxed = true)
        every { vFile.name } returns "Foo.kt"

        val localFileSystem = mockk<LocalFileSystem>(relaxed = true)
        every { localFileSystem.findFileByPath(any<String>()) } returns vFile

        mockkStatic(LocalFileSystem::class)
        every { LocalFileSystem.getInstance() } returns localFileSystem

        // Stub XDebuggerUtil so canPutBreakpointAt returns false for the target line.
        val util = mockk<XDebuggerUtil>(relaxed = true)
        every { util.canPutBreakpointAt(any(), any(), any()) } returns false

        mockkStatic(XDebuggerUtil::class)
        every { XDebuggerUtil.getInstance() } returns util

        // Act: attempt add_breakpoint on a comment/blank line (line 10, 1-based).
        val result = tool.execute(
            buildJsonObject {
                put("action", "add_breakpoint")
                put("file", "/tmp/testproject/src/Foo.kt")
                put("line", 10)
            },
            project,
        )

        // Assert: must be an error containing "not breakpointable".
        assertTrue(result.isError, "Expected isError=true but got false; content: ${result.content}")
        assertTrue(
            result.content.contains("not breakpointable", ignoreCase = true),
            "Expected 'not breakpointable' in message but got: ${result.content}",
        )
    }
}

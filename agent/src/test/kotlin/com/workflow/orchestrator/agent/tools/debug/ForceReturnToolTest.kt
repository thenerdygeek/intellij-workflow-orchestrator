package com.workflow.orchestrator.agent.tools.debug

import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import com.workflow.orchestrator.agent.runtime.WorkerType
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ForceReturnToolTest {
    private val controller = mockk<AgentDebugController>(relaxed = true)
    private val project = mockk<Project>(relaxed = true)
    private val tool = ForceReturnTool(controller)

    @Test
    fun `metadata is correct`() {
        assertEquals("force_return", tool.name)
        assertTrue(tool.description.contains("Force the current method to return"))
        assertTrue(tool.description.contains("native methods"))
        assertTrue(tool.description.contains("paused"))
    }

    @Test
    fun `description is required`() {
        assertEquals(listOf("description"), tool.parameters.required)
    }

    @Test
    fun `has all expected parameters`() {
        val props = tool.parameters.properties
        assertEquals(4, props.size)
        assertTrue(props.containsKey("session_id"))
        assertTrue(props.containsKey("return_value"))
        assertTrue(props.containsKey("return_type"))
        assertTrue(props.containsKey("description"))
    }

    @Test
    fun `return_type has enum values`() {
        val returnTypeProp = tool.parameters.properties["return_type"]!!
        assertNotNull(returnTypeProp.enumValues)
        assertTrue(returnTypeProp.enumValues!!.contains("auto"))
        assertTrue(returnTypeProp.enumValues!!.contains("void"))
        assertTrue(returnTypeProp.enumValues!!.contains("int"))
        assertTrue(returnTypeProp.enumValues!!.contains("boolean"))
        assertTrue(returnTypeProp.enumValues!!.contains("string"))
        assertTrue(returnTypeProp.enumValues!!.contains("null"))
    }

    @Test
    fun `allowed workers is CODER only`() {
        assertEquals(setOf(WorkerType.CODER), tool.allowedWorkers)
    }

    @Test
    fun `returns error when no session found`() = runTest {
        every { controller.getSession(null) } returns null

        val result = tool.execute(buildJsonObject {
            put("description", "force return")
        }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("No debug session found"))
    }

    @Test
    fun `returns error when specified session not found`() = runTest {
        every { controller.getSession("bad-id") } returns null

        val result = tool.execute(buildJsonObject {
            put("session_id", "bad-id")
            put("description", "force return")
        }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("No debug session found"))
        assertTrue(result.content.contains("bad-id"))
    }

    @Test
    fun `returns error when session not suspended`() = runTest {
        val session = mockk<XDebugSession>(relaxed = true)
        every { controller.getSession(null) } returns session
        every { session.isSuspended } returns false

        val result = tool.execute(buildJsonObject {
            put("description", "force return")
        }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("not suspended"))
    }

    @Nested
    inner class WithSuspendedSession {
        private val session = mockk<XDebugSession>(relaxed = true)

        @BeforeEach
        fun setUp() {
            every { controller.getSession(any()) } returns session
            every { controller.getSession(null) } returns session
            every { session.isSuspended } returns true
        }

        @Test
        fun `returns success for void return`() = runTest {
            coEvery { controller.executeOnManagerThread(session, any<(com.intellij.debugger.engine.DebugProcessImpl, com.intellij.debugger.jdi.VirtualMachineProxyImpl) -> Unit>()) } returns Unit

            val result = tool.execute(buildJsonObject {
                put("return_type", "void")
                put("description", "force void return")
            }, project)
            assertFalse(result.isError)
            assertTrue(result.content.contains("Forced early return"))
            assertTrue(result.content.contains("void"))
        }

        @Test
        fun `returns success with int value`() = runTest {
            coEvery { controller.executeOnManagerThread(session, any<(com.intellij.debugger.engine.DebugProcessImpl, com.intellij.debugger.jdi.VirtualMachineProxyImpl) -> Unit>()) } returns Unit

            val result = tool.execute(buildJsonObject {
                put("return_value", "42")
                put("return_type", "int")
                put("description", "force return 42")
            }, project)
            assertFalse(result.isError)
            assertTrue(result.content.contains("Forced early return"))
            assertTrue(result.content.contains("42"))
        }

        @Test
        fun `returns success with boolean value`() = runTest {
            coEvery { controller.executeOnManagerThread(session, any<(com.intellij.debugger.engine.DebugProcessImpl, com.intellij.debugger.jdi.VirtualMachineProxyImpl) -> Unit>()) } returns Unit

            val result = tool.execute(buildJsonObject {
                put("return_value", "true")
                put("return_type", "boolean")
                put("description", "force return true")
            }, project)
            assertFalse(result.isError)
            assertTrue(result.content.contains("Forced early return"))
            assertTrue(result.content.contains("true"))
        }

        @Test
        fun `returns success with string value`() = runTest {
            coEvery { controller.executeOnManagerThread(session, any<(com.intellij.debugger.engine.DebugProcessImpl, com.intellij.debugger.jdi.VirtualMachineProxyImpl) -> Unit>()) } returns Unit

            val result = tool.execute(buildJsonObject {
                put("return_value", "hello")
                put("return_type", "string")
                put("description", "force return hello")
            }, project)
            assertFalse(result.isError)
            assertTrue(result.content.contains("Forced early return"))
            assertTrue(result.content.contains("hello"))
        }

        @Test
        fun `returns error on IllegalStateException from controller`() = runTest {
            coEvery {
                controller.executeOnManagerThread(session, any<(com.intellij.debugger.engine.DebugProcessImpl, com.intellij.debugger.jdi.VirtualMachineProxyImpl) -> Unit>())
            } throws IllegalStateException("JVM does not support force early return")

            val result = tool.execute(buildJsonObject {
                put("return_type", "void")
                put("description", "force void return")
            }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("JVM does not support"))
        }

        @Test
        fun `returns error on IncompatibleThreadStateException`() = runTest {
            coEvery {
                controller.executeOnManagerThread(session, any<(com.intellij.debugger.engine.DebugProcessImpl, com.intellij.debugger.jdi.VirtualMachineProxyImpl) -> Unit>())
            } throws com.sun.jdi.IncompatibleThreadStateException("thread not suspended")

            val result = tool.execute(buildJsonObject {
                put("return_type", "void")
                put("description", "force void return")
            }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("not in a compatible state"))
        }

        @Test
        fun `returns error on NativeMethodException`() = runTest {
            coEvery {
                controller.executeOnManagerThread(session, any<(com.intellij.debugger.engine.DebugProcessImpl, com.intellij.debugger.jdi.VirtualMachineProxyImpl) -> Unit>())
            } throws com.sun.jdi.NativeMethodException("native method")

            val result = tool.execute(buildJsonObject {
                put("return_type", "void")
                put("description", "force void return")
            }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("native method"))
        }

        @Test
        fun `returns error on InvalidTypeException`() = runTest {
            coEvery {
                controller.executeOnManagerThread(session, any<(com.intellij.debugger.engine.DebugProcessImpl, com.intellij.debugger.jdi.VirtualMachineProxyImpl) -> Unit>())
            } throws com.sun.jdi.InvalidTypeException("type mismatch")

            val result = tool.execute(buildJsonObject {
                put("return_value", "42")
                put("return_type", "int")
                put("description", "force return 42")
            }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("Type mismatch"))
        }

        @Test
        fun `uses specified session_id`() = runTest {
            coEvery { controller.executeOnManagerThread(session, any<(com.intellij.debugger.engine.DebugProcessImpl, com.intellij.debugger.jdi.VirtualMachineProxyImpl) -> Unit>()) } returns Unit

            tool.execute(buildJsonObject {
                put("session_id", "debug-5")
                put("return_type", "void")
                put("description", "force void return")
            }, project)
            verify { controller.getSession("debug-5") }
        }

        @Test
        fun `defaults return_type to auto when not specified`() = runTest {
            coEvery { controller.executeOnManagerThread(session, any<(com.intellij.debugger.engine.DebugProcessImpl, com.intellij.debugger.jdi.VirtualMachineProxyImpl) -> Unit>()) } returns Unit

            val result = tool.execute(buildJsonObject {
                put("description", "force void return")
            }, project)
            // No return_value and auto type → inferred as void
            assertFalse(result.isError)
            assertTrue(result.content.contains("Forced early return"))
        }

        @Test
        fun `returns error on NumberFormatException`() = runTest {
            coEvery {
                controller.executeOnManagerThread(session, any<(com.intellij.debugger.engine.DebugProcessImpl, com.intellij.debugger.jdi.VirtualMachineProxyImpl) -> Unit>())
            } throws NumberFormatException("not a number")

            val result = tool.execute(buildJsonObject {
                put("return_value", "not_a_number")
                put("return_type", "int")
                put("description", "force return")
            }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("Invalid number format"))
        }

        @Test
        fun `returns error on generic exception`() = runTest {
            coEvery {
                controller.executeOnManagerThread(session, any<(com.intellij.debugger.engine.DebugProcessImpl, com.intellij.debugger.jdi.VirtualMachineProxyImpl) -> Unit>())
            } throws RuntimeException("unexpected error")

            val result = tool.execute(buildJsonObject {
                put("return_type", "void")
                put("description", "force void return")
            }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("Error forcing return"))
        }

        @Test
        fun `returns error on IllegalArgumentException`() = runTest {
            coEvery {
                controller.executeOnManagerThread(session, any<(com.intellij.debugger.engine.DebugProcessImpl, com.intellij.debugger.jdi.VirtualMachineProxyImpl) -> Unit>())
            } throws IllegalArgumentException("return_value required for int type")

            val result = tool.execute(buildJsonObject {
                put("return_type", "int")
                put("description", "force return")
            }, project)
            assertTrue(result.isError)
            assertTrue(result.content.contains("Invalid parameter"))
        }
    }

    @Nested
    inner class InferReturnType {
        private val session = mockk<XDebugSession>(relaxed = true)

        @BeforeEach
        fun setUp() {
            every { controller.getSession(any()) } returns session
            every { controller.getSession(null) } returns session
            every { session.isSuspended } returns true
            coEvery { controller.executeOnManagerThread(session, any<(com.intellij.debugger.engine.DebugProcessImpl, com.intellij.debugger.jdi.VirtualMachineProxyImpl) -> Unit>()) } returns Unit
        }

        @Test
        fun `auto type with null value infers void`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("description", "test")
            }, project)
            assertFalse(result.isError)
            assertTrue(result.content.contains("Return value: (void/none)"))
        }

        @Test
        fun `auto type with null string infers null`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("return_value", "null")
                put("description", "test")
            }, project)
            assertFalse(result.isError)
            assertTrue(result.content.contains("Return value: null"))
        }

        @Test
        fun `auto type with true infers boolean`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("return_value", "true")
                put("description", "test")
            }, project)
            assertFalse(result.isError)
            assertTrue(result.content.contains("Return value: true"))
        }

        @Test
        fun `auto type with number infers int`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("return_value", "42")
                put("description", "test")
            }, project)
            assertFalse(result.isError)
            assertTrue(result.content.contains("Return value: 42"))
        }

        @Test
        fun `auto type with decimal infers double`() = runTest {
            val result = tool.execute(buildJsonObject {
                put("return_value", "3.14")
                put("description", "test")
            }, project)
            assertFalse(result.isError)
            assertTrue(result.content.contains("Return value: 3.14"))
        }
    }

    @Test
    fun `toToolDefinition produces valid schema`() {
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("force_return", def.function.name)
        assertEquals("object", def.function.parameters.type)
        assertEquals(listOf("description"), def.function.parameters.required)
    }
}

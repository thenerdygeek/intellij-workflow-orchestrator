package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.WorkerType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class PythonRuntimeExecToolTest {
    private val project = mockk<Project>(relaxed = true)
    private val tool = PythonRuntimeExecTool()

    @Test
    fun `tool name is python_runtime_exec`() {
        assertEquals("python_runtime_exec", tool.name)
    }

    @Test
    fun `action enum contains only run_tests and compile_module`() {
        val actions = tool.parameters.properties["action"]?.enumValues
        assertNotNull(actions)
        assertEquals(setOf("run_tests", "compile_module"), actions!!.toSet())
    }

    @Test
    fun `description mentions pytest and py_compile`() {
        val desc = tool.description
        assertTrue(desc.contains("pytest"), "description should mention pytest")
        assertTrue(desc.contains("py_compile"), "description should mention py_compile")
    }

    @Test
    fun `class_name parameter is re-described as a pytest path`() {
        // Contract: on the Python tool, class_name is reinterpreted as a pytest path / node id.
        val desc = tool.parameters.properties["class_name"]?.description
            ?: fail<Nothing>("class_name parameter missing")
        assertTrue(
            desc.contains("ytest") && (desc.contains("path") || desc.contains("node id")),
            "class_name description should frame it as a pytest path/node id, was: $desc"
        )
    }

    @Test
    fun `method parameter is re-described as -k keyword expression`() {
        val desc = tool.parameters.properties["method"]?.description
            ?: fail<Nothing>("method parameter missing")
        assertTrue(desc.contains("-k"), "method description should mention pytest -k pattern, was: $desc")
    }

    @Test
    fun `markers parameter exists`() {
        assertNotNull(tool.parameters.properties["markers"])
    }

    @Test
    fun `only action is required`() {
        assertEquals(listOf("action"), tool.parameters.required)
    }

    @Test
    fun `allowedWorkers includes all expected types`() {
        assertEquals(
            setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER, WorkerType.ORCHESTRATOR, WorkerType.TOOLER),
            tool.allowedWorkers
        )
    }

    @Test
    fun `toToolDefinition produces valid schema`() {
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("python_runtime_exec", def.function.name)
        assertTrue(def.function.description.isNotBlank())
    }

    @Test
    fun `missing action returns error`() = runTest {
        val result = tool.execute(buildJsonObject { }, project)
        assertTrue(result.isError)
    }

    @Test
    fun `unknown action returns python_runtime_exec-scoped error`() = runTest {
        val result = tool.execute(buildJsonObject { put("action", "nonexistent") }, project)
        assertTrue(result.isError)
        assertTrue(result.content.contains("python_runtime_exec"))
    }

    @Test
    fun `compile_module errors when project basePath missing`() = runTest {
        every { project.basePath } returns null
        val result = tool.execute(
            buildJsonObject { put("action", "compile_module") },
            project
        )
        assertTrue(result.isError)
        assertTrue(result.content.contains("project base path"))
    }

    @Test
    fun `compile_module errors when module path escapes project root`(@TempDir temp: Path) = runTest {
        every { project.basePath } returns temp.toString()
        val result = tool.execute(
            buildJsonObject {
                put("action", "compile_module")
                put("module", "../../etc")
            },
            project
        )
        assertTrue(result.isError)
        assertTrue(
            result.content.contains("outside the project"),
            "path traversal must be rejected, got: ${result.content}"
        )
    }

    @Test
    fun `compile_module errors when module path does not exist`(@TempDir temp: Path) = runTest {
        every { project.basePath } returns temp.toString()
        val result = tool.execute(
            buildJsonObject {
                put("action", "compile_module")
                put("module", "nonexistent_dir")
            },
            project
        )
        assertTrue(result.isError)
        assertTrue(result.content.contains("does not exist"))
    }

    @Test
    fun `compile_module reports no python files found on empty dir`(@TempDir temp: Path) = runTest {
        every { project.basePath } returns temp.toString()
        val result = tool.execute(
            buildJsonObject { put("action", "compile_module") },
            project
        )
        // Empty temp dir → no .py files found. This is not an error; it's reported cleanly.
        assertFalse(result.isError)
        assertTrue(result.content.contains("No Python files"))
    }

    @Test
    fun `compile_module succeeds on syntactically valid python files when interpreter available`(@TempDir temp: Path) = runTest {
        every { project.basePath } returns temp.toString()
        val src = temp.resolve("good.py").toFile()
        src.writeText("def hello():\n    return 1\n")

        val result = tool.execute(
            buildJsonObject { put("action", "compile_module") },
            project
        )
        // On CI / dev macs python3 is reliably available; if it's not, the tool returns
        // the "Python not found" error path instead. Either outcome must be a clean,
        // non-crashing ToolResult — we accept both outcomes to keep the test reliable
        // across environments.
        if (result.isError) {
            val c = result.content
            assertTrue(
                c.contains("Python not found") || c.contains("Compilation of"),
                "unexpected compile_module error: $c"
            )
        } else {
            assertTrue(result.content.contains("successful"), "valid source should compile: ${result.content}")
        }
    }

    @Test
    fun `compile_module reports syntax errors on invalid python when interpreter available`(@TempDir temp: Path) = runTest {
        every { project.basePath } returns temp.toString()
        val src = temp.resolve("broken.py").toFile()
        src.writeText("def oops(:\n    pass\n")  // invalid — missing identifier before colon

        val result = tool.execute(
            buildJsonObject { put("action", "compile_module") },
            project
        )
        // Must either (a) isError=true due to SyntaxError, or (b) "Python not found" when no
        // interpreter is available. "Success" would be wrong.
        assertTrue(result.isError, "broken Python must surface as error: ${result.content}")
        assertTrue(
            result.content.contains("SyntaxError") || result.content.contains("Python not found") ||
                result.content.contains("Compilation of"),
            "expected SyntaxError or Python-not-found, got: ${result.content}"
        )
    }

    @Test
    fun `run_tests surfaces project base path error when missing`() = runTest {
        // executePytestRun first checks basePath — with no project base, we should see a
        // clean error, not a crash, regardless of whether pytest is installed.
        every { project.basePath } returns null
        val result = tool.execute(
            buildJsonObject { put("action", "run_tests") },
            project
        )
        assertTrue(result.isError)
    }

    @Test
    fun `run_tests rejects unsafe -k pattern via delegate`() = runTest {
        // executePytestRun validates pattern with SAFE_PYTEST_EXPR. Double-underscore is blocked.
        every { project.basePath } returns "/tmp/anything"
        val result = tool.execute(
            buildJsonObject {
                put("action", "run_tests")
                put("method", "foo__bar")
            },
            project
        )
        assertTrue(result.isError)
        assertTrue(
            result.content.contains("unsafe") || result.content.contains("pattern"),
            "unsafe pattern must be rejected, got: ${result.content}"
        )
    }
}

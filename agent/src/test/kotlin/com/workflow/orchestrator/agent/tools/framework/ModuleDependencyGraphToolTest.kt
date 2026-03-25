package com.workflow.orchestrator.agent.tools.framework

import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.framework.ModuleDependencyGraphTool.DependencyInfo
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ModuleDependencyGraphToolTest {
    private val tool = ModuleDependencyGraphTool()

    @Test
    fun `tool name is module_dependency_graph`() {
        assertEquals("module_dependency_graph", tool.name)
    }

    @Test
    fun `description mentions dependency graph`() {
        assertTrue(tool.description.contains("dependency graph"))
    }

    @Test
    fun `description mentions circular dependencies`() {
        assertTrue(tool.description.contains("circular dependencies"))
    }

    @Test
    fun `description mentions transitive`() {
        assertTrue(tool.description.contains("transitive"))
    }

    @Test
    fun `has four parameters`() {
        val props = tool.parameters.properties
        assertEquals(4, props.size)
        assertTrue(props.containsKey("module"))
        assertTrue(props.containsKey("transitive"))
        assertTrue(props.containsKey("include_libraries"))
        assertTrue(props.containsKey("detect_cycles"))
    }

    @Test
    fun `no required parameters`() {
        assertTrue(tool.parameters.required.isEmpty())
    }

    @Test
    fun `module parameter is string type`() {
        assertEquals("string", tool.parameters.properties["module"]?.type)
    }

    @Test
    fun `transitive parameter is boolean type`() {
        assertEquals("boolean", tool.parameters.properties["transitive"]?.type)
    }

    @Test
    fun `include_libraries parameter is boolean type`() {
        assertEquals("boolean", tool.parameters.properties["include_libraries"]?.type)
    }

    @Test
    fun `detect_cycles parameter is boolean type`() {
        assertEquals("boolean", tool.parameters.properties["detect_cycles"]?.type)
    }

    @Test
    fun `allowedWorkers includes ANALYZER and REVIEWER`() {
        assertEquals(setOf(WorkerType.ANALYZER, WorkerType.REVIEWER), tool.allowedWorkers)
    }

    @Test
    fun `toToolDefinition produces valid schema`() {
        val def = tool.toToolDefinition()
        assertEquals("function", def.type)
        assertEquals("module_dependency_graph", def.function.name)
        assertTrue(def.function.description.isNotBlank())
        assertEquals("object", def.function.parameters.type)
        assertEquals(4, def.function.parameters.properties.size)
        assertTrue(def.function.parameters.required.isEmpty())
    }

    // --- Cycle detection unit tests (pure logic, no IntelliJ APIs) ---

    @Test
    fun `detectCycles returns empty for acyclic graph`() {
        val adjacency = mapOf(
            "core" to emptyList(),
            "jira" to listOf(DependencyInfo("core")),
            "bamboo" to listOf(DependencyInfo("core")),
            "agent" to listOf(DependencyInfo("core"))
        )

        val cycles = tool.detectCycles(adjacency)
        assertTrue(cycles.isEmpty())
    }

    @Test
    fun `detectCycles finds simple two-node cycle`() {
        val adjacency = mapOf(
            "a" to listOf(DependencyInfo("b")),
            "b" to listOf(DependencyInfo("a"))
        )

        val cycles = tool.detectCycles(adjacency)
        assertTrue(cycles.isNotEmpty())
        // At least one cycle contains both a and b
        assertTrue(cycles.any { it.containsAll(listOf("a", "b")) })
    }

    @Test
    fun `detectCycles finds three-node cycle`() {
        val adjacency = mapOf(
            "a" to listOf(DependencyInfo("b")),
            "b" to listOf(DependencyInfo("c")),
            "c" to listOf(DependencyInfo("a"))
        )

        val cycles = tool.detectCycles(adjacency)
        assertTrue(cycles.isNotEmpty())
        assertTrue(cycles.any { it.size == 3 && it.containsAll(listOf("a", "b", "c")) })
    }

    @Test
    fun `detectCycles handles self-loop`() {
        val adjacency = mapOf(
            "a" to listOf(DependencyInfo("a"))
        )

        val cycles = tool.detectCycles(adjacency)
        assertTrue(cycles.isNotEmpty())
    }

    @Test
    fun `detectCycles handles empty graph`() {
        val adjacency = emptyMap<String, List<DependencyInfo>>()

        val cycles = tool.detectCycles(adjacency)
        assertTrue(cycles.isEmpty())
    }

    @Test
    fun `detectCycles handles disconnected components with one cycle`() {
        val adjacency = mapOf(
            "core" to emptyList(),
            "jira" to listOf(DependencyInfo("core")),
            "x" to listOf(DependencyInfo("y")),
            "y" to listOf(DependencyInfo("x"))
        )

        val cycles = tool.detectCycles(adjacency)
        assertTrue(cycles.isNotEmpty())
        // The cycle should involve x and y, not core/jira
        assertTrue(cycles.any { it.containsAll(listOf("x", "y")) })
    }

    @Test
    fun `detectCycles ignores deps to unknown modules`() {
        val adjacency = mapOf(
            "a" to listOf(DependencyInfo("unknown_module")),
            "b" to emptyList()
        )

        val cycles = tool.detectCycles(adjacency)
        assertTrue(cycles.isEmpty())
    }

    @Test
    fun `detectCycles handles complex graph without cycles`() {
        // Diamond dependency: a -> b, a -> c, b -> d, c -> d
        val adjacency = mapOf(
            "a" to listOf(DependencyInfo("b"), DependencyInfo("c")),
            "b" to listOf(DependencyInfo("d")),
            "c" to listOf(DependencyInfo("d")),
            "d" to emptyList()
        )

        val cycles = tool.detectCycles(adjacency)
        assertTrue(cycles.isEmpty())
    }

    @Test
    fun `DependencyInfo default scope is COMPILE`() {
        val dep = DependencyInfo("core")
        assertEquals("COMPILE", dep.scope)
    }

    @Test
    fun `DependencyInfo preserves custom scope`() {
        val dep = DependencyInfo("test-utils", "TEST")
        assertEquals("TEST", dep.scope)
    }
}

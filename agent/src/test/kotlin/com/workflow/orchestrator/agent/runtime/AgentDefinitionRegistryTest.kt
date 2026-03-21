package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.project.Project
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AgentDefinitionRegistryTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var project: Project
    private lateinit var registry: AgentDefinitionRegistry

    @BeforeEach
    fun setUp() {
        project = mockk(relaxed = true)
        every { project.basePath } returns tempDir.absolutePath
        registry = AgentDefinitionRegistry(project)
    }

    @Test
    fun `parses agent definition from markdown file`() {
        val agentDir = File(tempDir, ".workflow/agents").also { it.mkdirs() }
        File(agentDir, "code-reviewer.md").writeText("""
---
name: code-reviewer
description: Reviews code for quality
tools: read_file, search_code, glob_files
model: sonnet
max-turns: 15
skills: systematic-debugging
memory: project
---
You are a code reviewer. Analyze code and provide feedback.
        """.trimIndent())

        registry.scan()

        val agent = registry.getAgent("code-reviewer")
        assertNotNull(agent)
        assertEquals("code-reviewer", agent!!.name)
        assertEquals("Reviews code for quality", agent.description)
        assertEquals(listOf("read_file", "search_code", "glob_files"), agent.tools)
        assertEquals("sonnet", agent.model)
        assertEquals(15, agent.maxTurns)
        assertEquals(listOf("systematic-debugging"), agent.skills)
        assertEquals(AgentDefinitionRegistry.MemoryScope.PROJECT, agent.memory)
        assertEquals(AgentDefinitionRegistry.AgentScope.PROJECT, agent.scope)
        assertTrue(agent.systemPrompt.contains("You are a code reviewer"))
    }

    @Test
    fun `uses filename as name when name field is absent`() {
        val agentDir = File(tempDir, ".workflow/agents").also { it.mkdirs() }
        File(agentDir, "my-agent.md").writeText("""
---
description: A test agent
---
Do something useful.
        """.trimIndent())

        registry.scan()

        val agent = registry.getAgent("my-agent")
        assertNotNull(agent)
        assertEquals("my-agent", agent!!.name)
    }

    @Test
    fun `skips files without description`() {
        val agentDir = File(tempDir, ".workflow/agents").also { it.mkdirs() }
        File(agentDir, "invalid.md").writeText("""
---
name: invalid
---
No description means this should be skipped.
        """.trimIndent())

        registry.scan()

        assertNull(registry.getAgent("invalid"))
        assertTrue(registry.getAllAgents().isEmpty())
    }

    @Test
    fun `skips files without frontmatter`() {
        val agentDir = File(tempDir, ".workflow/agents").also { it.mkdirs() }
        File(agentDir, "no-frontmatter.md").writeText("Just plain markdown content.")

        registry.scan()

        assertTrue(registry.getAllAgents().isEmpty())
    }

    @Test
    fun `parses disallowed-tools`() {
        val agentDir = File(tempDir, ".workflow/agents").also { it.mkdirs() }
        File(agentDir, "restricted.md").writeText("""
---
name: restricted
description: Agent with disallowed tools
disallowed-tools: run_command, edit_file
---
Restricted agent.
        """.trimIndent())

        registry.scan()

        val agent = registry.getAgent("restricted")
        assertNotNull(agent)
        assertEquals(listOf("run_command", "edit_file"), agent!!.disallowedTools)
        assertNull(agent.tools) // no allowlist
    }

    @Test
    fun `defaults maxTurns to 10 when not specified`() {
        val agentDir = File(tempDir, ".workflow/agents").also { it.mkdirs() }
        File(agentDir, "default.md").writeText("""
---
name: default-agent
description: Uses default max turns
---
Default agent.
        """.trimIndent())

        registry.scan()

        val agent = registry.getAgent("default-agent")
        assertNotNull(agent)
        assertEquals(10, agent!!.maxTurns)
    }

    @Test
    fun `buildDescriptionIndex returns formatted list`() {
        val agentDir = File(tempDir, ".workflow/agents").also { it.mkdirs() }
        File(agentDir, "alpha.md").writeText("""
---
name: alpha
description: First agent
---
Alpha body.
        """.trimIndent())
        File(agentDir, "beta.md").writeText("""
---
name: beta
description: Second agent
---
Beta body.
        """.trimIndent())

        registry.scan()

        val index = registry.buildDescriptionIndex()
        assertTrue(index.contains("alpha"))
        assertTrue(index.contains("beta"))
        assertTrue(index.contains("First agent"))
        assertTrue(index.contains("Second agent"))
    }

    @Test
    fun `buildDescriptionIndex returns empty string when no agents`() {
        registry.scan()
        assertEquals("", registry.buildDescriptionIndex())
    }

    @Test
    fun `getAllAgents returns sorted list`() {
        val agentDir = File(tempDir, ".workflow/agents").also { it.mkdirs() }
        File(agentDir, "zebra.md").writeText("""
---
name: zebra
description: Last alphabetically
---
Zebra.
        """.trimIndent())
        File(agentDir, "aardvark.md").writeText("""
---
name: aardvark
description: First alphabetically
---
Aardvark.
        """.trimIndent())

        registry.scan()

        val agents = registry.getAllAgents()
        assertEquals(2, agents.size)
        assertEquals("aardvark", agents[0].name)
        assertEquals("zebra", agents[1].name)
    }

    @Test
    fun `handles empty agents directory`() {
        File(tempDir, ".workflow/agents").mkdirs()
        registry.scan()
        assertTrue(registry.getAllAgents().isEmpty())
    }

    @Test
    fun `handles missing agents directory`() {
        // No .workflow/agents directory at all
        registry.scan()
        assertTrue(registry.getAllAgents().isEmpty())
    }

    @Test
    fun `parses memory scope correctly`() {
        val agentDir = File(tempDir, ".workflow/agents").also { it.mkdirs() }
        for ((scope, expected) in listOf(
            "user" to AgentDefinitionRegistry.MemoryScope.USER,
            "project" to AgentDefinitionRegistry.MemoryScope.PROJECT,
            "local" to AgentDefinitionRegistry.MemoryScope.LOCAL
        )) {
            File(agentDir, "$scope-agent.md").writeText("""
---
name: ${scope}-agent
description: Agent with $scope memory
memory: $scope
---
Body.
            """.trimIndent())
        }

        registry.scan()

        assertEquals(AgentDefinitionRegistry.MemoryScope.USER, registry.getAgent("user-agent")!!.memory)
        assertEquals(AgentDefinitionRegistry.MemoryScope.PROJECT, registry.getAgent("project-agent")!!.memory)
        assertEquals(AgentDefinitionRegistry.MemoryScope.LOCAL, registry.getAgent("local-agent")!!.memory)
    }

    @Test
    fun `invalid memory scope results in null`() {
        val agentDir = File(tempDir, ".workflow/agents").also { it.mkdirs() }
        File(agentDir, "invalid-mem.md").writeText("""
---
name: invalid-mem
description: Agent with invalid memory
memory: foobar
---
Body.
        """.trimIndent())

        registry.scan()

        assertNull(registry.getAgent("invalid-mem")!!.memory)
    }

    @Test
    fun `memory directory resolved for project scope`() {
        val def = AgentDefinitionRegistry.AgentDefinition(
            name = "reviewer",
            description = "test",
            systemPrompt = "test",
            memory = AgentDefinitionRegistry.MemoryScope.PROJECT,
            filePath = "/tmp/test.md",
            scope = AgentDefinitionRegistry.AgentScope.PROJECT
        )

        val dir = registry.getMemoryDirectory(def, project)
        assertNotNull(dir)
        assertTrue(dir!!.path.contains(".workflow/agent-memory/reviewer"))
    }

    @Test
    fun `memory directory resolved for user scope`() {
        val def = AgentDefinitionRegistry.AgentDefinition(
            name = "helper",
            description = "test",
            systemPrompt = "test",
            memory = AgentDefinitionRegistry.MemoryScope.USER,
            filePath = "/tmp/test.md",
            scope = AgentDefinitionRegistry.AgentScope.USER
        )

        val dir = registry.getMemoryDirectory(def, project)
        assertNotNull(dir)
        assertTrue(dir!!.path.contains(".workflow-orchestrator/agent-memory/helper"))
    }

    @Test
    fun `memory directory resolved for local scope`() {
        val def = AgentDefinitionRegistry.AgentDefinition(
            name = "local-agent",
            description = "test",
            systemPrompt = "test",
            memory = AgentDefinitionRegistry.MemoryScope.LOCAL,
            filePath = "/tmp/test.md",
            scope = AgentDefinitionRegistry.AgentScope.PROJECT
        )

        val dir = registry.getMemoryDirectory(def, project)
        assertNotNull(dir)
        assertTrue(dir!!.path.contains(".workflow/agent-memory-local/local-agent"))
    }

    @Test
    fun `memory directory returns null when memory not enabled`() {
        val def = AgentDefinitionRegistry.AgentDefinition(
            name = "no-memory",
            description = "test",
            systemPrompt = "test",
            memory = null,
            filePath = "/tmp/test.md",
            scope = AgentDefinitionRegistry.AgentScope.PROJECT
        )

        val dir = registry.getMemoryDirectory(def, project)
        assertNull(dir)
    }

    @Test
    fun `memory directory returns null when project has no basePath`() {
        val noBaseProject = mockk<Project>(relaxed = true)
        every { noBaseProject.basePath } returns null

        val def = AgentDefinitionRegistry.AgentDefinition(
            name = "reviewer",
            description = "test",
            systemPrompt = "test",
            memory = AgentDefinitionRegistry.MemoryScope.PROJECT,
            filePath = "/tmp/test.md",
            scope = AgentDefinitionRegistry.AgentScope.PROJECT
        )

        val dir = registry.getMemoryDirectory(def, noBaseProject)
        assertNull(dir)
    }

    @Test
    fun `scan clears previous agents on rescan`() {
        val agentDir = File(tempDir, ".workflow/agents").also { it.mkdirs() }
        File(agentDir, "first.md").writeText("""
---
name: first
description: First agent
---
First.
        """.trimIndent())

        registry.scan()
        assertEquals(1, registry.getAllAgents().size)

        // Delete the file and add a different one
        File(agentDir, "first.md").delete()
        File(agentDir, "second.md").writeText("""
---
name: second
description: Second agent
---
Second.
        """.trimIndent())

        registry.scan()
        assertEquals(1, registry.getAllAgents().size)
        assertNull(registry.getAgent("first"))
        assertNotNull(registry.getAgent("second"))
    }
}

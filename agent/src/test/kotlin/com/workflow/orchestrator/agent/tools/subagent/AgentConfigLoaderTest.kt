// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.agent.tools.subagent

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class AgentConfigLoaderTest {

    @TempDir
    lateinit var tempDir: Path

    private lateinit var loader: AgentConfigLoader

    @BeforeEach
    fun setUp() {
        AgentConfigLoader.resetForTests()
        loader = AgentConfigLoader.getInstance()
    }

    @AfterEach
    fun tearDown() {
        AgentConfigLoader.resetForTests()
    }

    // ── YAML Parsing ──────────────────────────────────────────────

    @Test
    fun `parse valid config with all fields`() {
        val yaml = """
            ---
            name: "Code Reviewer"
            description: "Reviews code for quality and best practices"
            tools: "read_file, search_code, glob_files"
            skills: "code-review, linting"
            modelId: "anthropic/claude-sonnet"
            ---
            You are a code reviewer. Analyze the code and provide feedback.
        """.trimIndent()

        val config = loader.parseAgentConfigFromYaml(yaml)

        assertEquals("Code Reviewer", config.name)
        assertEquals("Reviews code for quality and best practices", config.description)
        assertEquals(listOf("read_file", "search_code", "glob_files"), config.tools)
        assertEquals(listOf("code-review", "linting"), config.skills)
        assertEquals("anthropic/claude-sonnet", config.modelId)
        assertEquals("You are a code reviewer. Analyze the code and provide feedback.", config.systemPrompt)
    }

    @Test
    fun `parse config with only required fields`() {
        val yaml = """
            ---
            name: "Simple Agent"
            description: "Does simple things"
            tools: "read_file"
            ---
            System prompt here.
        """.trimIndent()

        val config = loader.parseAgentConfigFromYaml(yaml)

        assertEquals("Simple Agent", config.name)
        assertEquals("Does simple things", config.description)
        assertEquals(listOf("read_file"), config.tools)
        assertNull(config.skills)
        assertNull(config.modelId)
        assertEquals("System prompt here.", config.systemPrompt)
    }

    @Test
    fun `parse config with single-quoted values`() {
        val yaml = """
            ---
            name: 'Quoted Agent'
            description: 'A description'
            tools: 'read_file, write_file'
            ---
            Prompt body.
        """.trimIndent()

        val config = loader.parseAgentConfigFromYaml(yaml)
        assertEquals("Quoted Agent", config.name)
        assertEquals(listOf("read_file", "write_file"), config.tools)
    }

    @Test
    fun `parse config with unquoted values`() {
        val yaml = """
            ---
            name: Bare Agent
            description: No quotes here
            tools: read_file, search_code
            ---
            Prompt.
        """.trimIndent()

        val config = loader.parseAgentConfigFromYaml(yaml)
        assertEquals("Bare Agent", config.name)
        assertEquals("No quotes here", config.description)
        assertEquals(listOf("read_file", "search_code"), config.tools)
    }

    @Test
    fun `parse config with multiline system prompt`() {
        val yaml = """
            ---
            name: "Multi-line"
            description: "Has a long prompt"
            tools: "read_file"
            ---
            # Header

            You are a helpful assistant.

            ## Rules
            - Be concise
            - Be accurate
        """.trimIndent()

        val config = loader.parseAgentConfigFromYaml(yaml)
        assertTrue(config.systemPrompt.contains("# Header"))
        assertTrue(config.systemPrompt.contains("## Rules"))
        assertTrue(config.systemPrompt.contains("- Be concise"))
    }

    @Test
    fun `reject content without frontmatter`() {
        val content = "Just plain text, no frontmatter."
        val ex = assertThrows(IllegalArgumentException::class.java) {
            loader.parseAgentConfigFromYaml(content)
        }
        assertTrue(ex.message!!.contains("Missing YAML frontmatter delimiter"))
    }

    @Test
    fun `reject content with unclosed frontmatter`() {
        val content = """
            ---
            name: "Agent"
            description: "Test"
            tools: "read_file"
            No closing delimiter.
        """.trimIndent()
        val ex = assertThrows(IllegalArgumentException::class.java) {
            loader.parseAgentConfigFromYaml(content)
        }
        assertTrue(ex.message!!.contains("closing"))
    }

    @Test
    fun `reject config missing name`() {
        val yaml = """
            ---
            description: "Test"
            tools: "read_file"
            ---
            Prompt.
        """.trimIndent()
        val ex = assertThrows(IllegalArgumentException::class.java) {
            loader.parseAgentConfigFromYaml(yaml)
        }
        assertTrue(ex.message!!.contains("name"))
    }

    @Test
    fun `reject config missing description`() {
        val yaml = """
            ---
            name: "Agent"
            tools: "read_file"
            ---
            Prompt.
        """.trimIndent()
        val ex = assertThrows(IllegalArgumentException::class.java) {
            loader.parseAgentConfigFromYaml(yaml)
        }
        assertTrue(ex.message!!.contains("description"))
    }

    @Test
    fun `config with missing tools gets empty list`() {
        val yaml = """
            ---
            name: "Agent"
            description: "Test"
            ---
            Prompt.
        """.trimIndent()
        val config = loader.parseAgentConfigFromYaml(yaml)
        assertTrue(config.tools.isEmpty(), "Missing tools field should result in empty list")
    }

    @Test
    fun `reject config with empty body`() {
        val yaml = """
            ---
            name: "Agent"
            description: "Test"
            tools: "read_file"
            ---
        """.trimIndent()
        val ex = assertThrows(IllegalArgumentException::class.java) {
            loader.parseAgentConfigFromYaml(yaml)
        }
        assertTrue(ex.message!!.contains("body"))
    }

    // ── Disk Loading ──────────────────────────────────────────────

    @Test
    fun `load multiple configs from directory`() {
        writeConfigFile(tempDir, "reviewer.yaml", "My Reviewer", "Reviews code", "read_file, search_code")
        writeConfigFile(tempDir, "explorer.yml", "My Explorer", "Explores code", "glob_files")

        loader.loadFromDisk(tempDir)

        // User configs loaded (bundled agents also present)
        assertNotNull(loader.getCachedConfig("My Reviewer"))
        assertNotNull(loader.getCachedConfig("My Explorer"))
        // At least 2 user + 8 bundled
        assertTrue(loader.getAllCachedConfigs().size >= 10)
    }

    @Test
    fun `ignore non-yaml files`() {
        writeConfigFile(tempDir, "valid.yaml", "Valid Agent", "Does things", "read_file")
        Files.writeString(tempDir.resolve("readme.txt"), "Not a config")
        Files.writeString(tempDir.resolve("config.json"), "{}")

        loader.loadFromDisk(tempDir)

        // User config loaded, .txt and .json ignored
        assertNotNull(loader.getCachedConfig("Valid Agent"))
        // json and txt should NOT produce configs — only yaml/yml/md are scanned
        val allNames = loader.getAllCachedConfigs().map { it.name.lowercase() }
        assertFalse(allNames.contains("readme"))
        assertFalse(allNames.contains("config"))
    }

    @Test
    fun `load from nonexistent directory creates it`() {
        val subDir = tempDir.resolve("sub/agents")
        assertFalse(Files.exists(subDir))

        loader.loadFromDisk(subDir)

        assertTrue(Files.isDirectory(subDir))
        // No user configs in the new empty dir, but bundled agents are loaded
        val bundledCount = loader.getAllCachedConfigs().count { it.bundled }
        assertTrue(bundledCount >= 8, "Bundled agents should be loaded even with empty user dir")
    }

    @Test
    fun `skip malformed config files gracefully`() {
        writeConfigFile(tempDir, "good.yaml", "Good Agent", "Works fine", "read_file")
        Files.writeString(tempDir.resolve("bad.yaml"), "not valid yaml frontmatter at all")

        loader.loadFromDisk(tempDir)

        // Good config loaded, bad one skipped (bundled agents also present)
        assertNotNull(loader.getCachedConfig("Good Agent"))
        // bad.yaml should not produce a config
        val allNames = loader.getAllCachedConfigs().map { it.name }
        assertFalse(allNames.any { it.contains("bad", ignoreCase = true) })
    }

    // ── Case-Insensitive Lookup ───────────────────────────────────

    @Test
    fun `getCachedConfig is case insensitive`() {
        writeConfigFile(tempDir, "agent.yaml", "My Agent", "Does stuff", "read_file")
        loader.loadFromDisk(tempDir)

        assertNotNull(loader.getCachedConfig("My Agent"))
        assertNotNull(loader.getCachedConfig("my agent"))
        assertNotNull(loader.getCachedConfig("MY AGENT"))
        assertNotNull(loader.getCachedConfig("mY aGeNt"))
    }

    @Test
    fun `getCachedConfig returns null for unknown name`() {
        loader.loadFromDisk(tempDir)
        assertNull(loader.getCachedConfig("nonexistent"))
    }

    // ── Dynamic Tool Name Mapping ─────────────────────────────────

    @Test
    fun `generates prefixed tool names for loaded configs`() {
        writeConfigFile(tempDir, "helper.yaml", "Code Helper", "Helps with code", "read_file")
        loader.loadFromDisk(tempDir)

        val expected = SubagentToolName.build("Code Helper")
        val configsWithTools = loader.getAllCachedConfigsWithToolNames()

        assertTrue(configsWithTools.containsKey(expected))
        assertEquals("Code Helper", configsWithTools[expected]!!.name)
    }

    @Test
    fun `resolves agent name from tool name`() {
        writeConfigFile(tempDir, "scout.yaml", "Scout Agent", "Explores things", "glob_files")
        loader.loadFromDisk(tempDir)

        val toolName = SubagentToolName.build("Scout Agent")
        assertEquals("Scout Agent", loader.resolveSubagentNameForTool(toolName))
    }

    @Test
    fun `resolveSubagentNameForTool returns null for unknown tool`() {
        loader.loadFromDisk(tempDir)
        assertNull(loader.resolveSubagentNameForTool("use_subagent_unknown"))
    }

    @Test
    fun `isDynamicSubagentTool returns true for loaded agent tools`() {
        writeConfigFile(tempDir, "bot.yaml", "Test Bot", "A test bot", "read_file")
        loader.loadFromDisk(tempDir)

        val toolName = SubagentToolName.build("Test Bot")
        assertTrue(loader.isDynamicSubagentTool(toolName))
    }

    @Test
    fun `isDynamicSubagentTool returns false for unknown tools`() {
        loader.loadFromDisk(tempDir)
        assertFalse(loader.isDynamicSubagentTool("use_subagent_nope"))
        assertFalse(loader.isDynamicSubagentTool("read_file"))
    }

    @Test
    fun `rebuildDynamicToolMappings updates after config change`() {
        writeConfigFile(tempDir, "a.yaml", "Alpha", "First agent", "read_file")
        loader.loadFromDisk(tempDir)

        val alphaToolName = SubagentToolName.build("Alpha")
        assertTrue(loader.isDynamicSubagentTool(alphaToolName))

        // Simulate adding a second config and reloading
        writeConfigFile(tempDir, "b.yaml", "Beta", "Second agent", "search_code")
        loader.loadFromDisk(tempDir)

        val betaToolName = SubagentToolName.build("Beta")
        assertTrue(loader.isDynamicSubagentTool(alphaToolName))
        assertTrue(loader.isDynamicSubagentTool(betaToolName))
    }

    // ── Listener ──────────────────────────────────────────────────

    @Test
    fun `add and remove change listener`() {
        var callCount = 0
        val listener = AgentConfigChangeListener { callCount++ }

        loader.addChangeListener(listener)
        loader.removeChangeListener(listener)

        // No way to trigger listener without file watch; just verify no exceptions
        assertEquals(0, callCount)
    }

    // ── Singleton ─────────────────────────────────────────────────

    @Test
    fun `getInstance returns same instance`() {
        val a = AgentConfigLoader.getInstance()
        val b = AgentConfigLoader.getInstance()
        assertSame(a, b)
    }

    @Test
    fun `resetForTests creates fresh instance`() {
        val first = AgentConfigLoader.getInstance()
        AgentConfigLoader.resetForTests()
        val second = AgentConfigLoader.getInstance()
        assertNotSame(first, second)
    }

    // ── Bundled Agents ─────────────────────────────────────────────

    @Test
    fun `bundled agents are loaded from resources`() {
        loader.loadFromDisk(tempDir)

        val bundled = loader.getAllCachedConfigs().filter { it.bundled }
        assertEquals(8, bundled.size, "Should load 8 bundled specialist agents")

        val names = bundled.map { it.name }.toSet()
        assertTrue("code-reviewer" in names)
        assertTrue("spring-boot-engineer" in names)
        assertTrue("devops-engineer" in names)
        assertTrue("security-auditor" in names)
    }

    @Test
    fun `user config overrides bundled agent with same name`() {
        // Create a user config with the same name as a bundled agent
        writeConfigFile(tempDir, "code-reviewer.yaml", "code-reviewer", "My custom reviewer", "read_file")

        loader.loadFromDisk(tempDir)

        val config = loader.getCachedConfig("code-reviewer")
        assertNotNull(config)
        assertEquals("My custom reviewer", config!!.description)
        assertFalse(config.bundled, "User override should not be marked as bundled")
    }

    @Test
    fun `bundled agents have maxTurns set`() {
        loader.loadFromDisk(tempDir)

        val springBoot = loader.getCachedConfig("spring-boot-engineer")
        assertNotNull(springBoot)
        assertEquals(32, springBoot!!.maxTurns)

        val codeReviewer = loader.getCachedConfig("code-reviewer")
        assertNotNull(codeReviewer)
        assertEquals(25, codeReviewer!!.maxTurns)
    }

    @Test
    fun `parse config with max-turns field`() {
        val yaml = """
            ---
            name: "Custom Agent"
            description: "Test"
            tools: "read_file"
            max-turns: 15
            ---
            Prompt.
        """.trimIndent()

        val config = loader.parseAgentConfigFromYaml(yaml)
        assertEquals(15, config.maxTurns)
    }

    // ── Helpers ───────────────────────────────────────────────────

    private fun writeConfigFile(
        dir: Path,
        filename: String,
        name: String,
        description: String,
        tools: String,
    ) {
        val content = """
            ---
            name: "$name"
            description: "$description"
            tools: "$tools"
            ---
            System prompt for $name.
        """.trimIndent()
        Files.writeString(dir.resolve(filename), content)
    }
}

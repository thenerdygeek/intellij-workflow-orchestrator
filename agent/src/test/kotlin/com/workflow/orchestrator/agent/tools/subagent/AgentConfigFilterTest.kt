// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.agent.tools.subagent

import com.workflow.orchestrator.agent.ide.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AgentConfigFilterTest {

    private lateinit var loader: AgentConfigLoader

    private val sampleConfigs = listOf(
        makeConfig("code-reviewer", "Code review"),
        makeConfig("spring-boot-engineer", "Spring Boot development"),
        makeConfig("python-engineer", "Python web development"),
        makeConfig("test-automator", "Test generation"),
    )

    @BeforeEach
    fun setUp() {
        AgentConfigLoader.resetForTests()
        loader = AgentConfigLoader.getInstance()
    }

    @AfterEach
    fun tearDown() {
        AgentConfigLoader.resetForTests()
    }

    @Test
    fun `null ideContext returns all configs`() {
        val filtered = loader.filterByIdeContext(sampleConfigs, null)
        assertEquals(4, filtered.size)
    }

    @Test
    fun `IntelliJ includes spring-boot-engineer, excludes python-engineer`() {
        val context = makeIdeContext(IdeProduct.INTELLIJ_ULTIMATE, supportsJava = true, supportsPython = false)
        val filtered = loader.filterByIdeContext(sampleConfigs, context)
        val names = filtered.map { it.name }
        assertTrue("spring-boot-engineer" in names)
        assertFalse("python-engineer" in names)
        assertTrue("code-reviewer" in names)
        assertTrue("test-automator" in names)
    }

    @Test
    fun `PyCharm includes python-engineer, excludes spring-boot-engineer`() {
        val context = makeIdeContext(IdeProduct.PYCHARM_COMMUNITY, supportsJava = false, supportsPython = true)
        val filtered = loader.filterByIdeContext(sampleConfigs, context)
        val names = filtered.map { it.name }
        assertTrue("python-engineer" in names)
        assertFalse("spring-boot-engineer" in names)
        assertTrue("code-reviewer" in names)
    }

    @Test
    fun `WebStorm excludes both language-specific agents`() {
        val context = makeIdeContext(IdeProduct.OTHER, supportsJava = false, supportsPython = false)
        val filtered = loader.filterByIdeContext(sampleConfigs, context)
        val names = filtered.map { it.name }
        assertFalse("spring-boot-engineer" in names)
        assertFalse("python-engineer" in names)
        assertTrue("code-reviewer" in names)
        assertTrue("test-automator" in names)
    }

    @Test
    fun `IntelliJ with Python plugin includes both language agents`() {
        val context = makeIdeContext(IdeProduct.INTELLIJ_ULTIMATE, supportsJava = true, supportsPython = true)
        val filtered = loader.filterByIdeContext(sampleConfigs, context)
        val names = filtered.map { it.name }
        assertTrue("spring-boot-engineer" in names)
        assertTrue("python-engineer" in names)
    }

    @Test
    fun `getFilteredConfigs delegates to filterByIdeContext`() {
        // When no configs are loaded and no ideContext, getFilteredConfigs returns empty
        val result = loader.getFilteredConfigs(null)
        assertTrue(result.isEmpty(), "No configs loaded yet, should be empty")
    }

    @Test
    fun `filtering is case-insensitive on agent name`() {
        val configs = listOf(
            makeConfig("Spring-Boot-Engineer", "Spring Boot"),
            makeConfig("PYTHON-ENGINEER", "Python"),
            makeConfig("code-reviewer", "Review"),
        )
        val context = makeIdeContext(IdeProduct.OTHER, supportsJava = false, supportsPython = false)
        val filtered = loader.filterByIdeContext(configs, context)
        val names = filtered.map { it.name }
        assertFalse("Spring-Boot-Engineer" in names)
        assertFalse("PYTHON-ENGINEER" in names)
        assertTrue("code-reviewer" in names)
    }

    @Test
    fun `user-defined agents with non-language names always pass filter`() {
        val configs = listOf(
            makeConfig("my-custom-agent", "Custom agent for docs"),
            makeConfig("spring-boot-engineer", "Spring Boot"),
        )
        val context = makeIdeContext(IdeProduct.OTHER, supportsJava = false, supportsPython = false)
        val filtered = loader.filterByIdeContext(configs, context)
        val names = filtered.map { it.name }
        assertTrue("my-custom-agent" in names)
        assertFalse("spring-boot-engineer" in names)
    }

    private fun makeConfig(name: String, description: String) = AgentConfig(
        name = name,
        description = description,
        tools = emptyList(),
        skills = null,
        modelId = null,
        systemPrompt = "Test prompt for $name",
        bundled = true,
    )

    private fun makeIdeContext(product: IdeProduct, supportsJava: Boolean, supportsPython: Boolean) = IdeContext(
        product = product,
        productName = product.name,
        edition = Edition.COMMUNITY,
        languages = buildSet {
            if (supportsJava) { add(Language.JAVA); add(Language.KOTLIN) }
            if (supportsPython) add(Language.PYTHON)
        },
        hasJavaPlugin = supportsJava,
        hasPythonPlugin = supportsPython,
        hasPythonCorePlugin = supportsPython,
        hasSpringPlugin = false,
        detectedFrameworks = emptySet(),
        detectedBuildTools = emptySet(),
    )
}

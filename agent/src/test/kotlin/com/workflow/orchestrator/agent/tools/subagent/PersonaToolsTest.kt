// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.agent.tools.subagent

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Validates the tiered tool lists of all 11 bundled persona YAML files.
 * These tests enforce the architectural rules for the sub-agent 3-tier tool system.
 */
class PersonaToolsTest {

    private lateinit var loader: AgentConfigLoader

    @BeforeEach
    fun setUp() {
        AgentConfigLoader.resetForTests()
        loader = AgentConfigLoader.getInstance()
        loader.loadFromDisk()
    }

    @AfterEach
    fun tearDown() {
        AgentConfigLoader.resetForTests()
    }

    private val WRITE_TOOLS = setOf(
        "edit_file", "create_file", "revert_file", "format_code", "optimize_imports", "refactor_rename"
    )

    @ParameterizedTest
    @ValueSource(strings = [
        "code-reviewer", "architect-reviewer", "devops-engineer", "explorer",
        "general-purpose", "performance-engineer", "refactoring-specialist",
        "security-auditor", "spring-boot-engineer", "test-automator", "python-engineer"
    ])
    fun `persona core tools contain tool_search`(personaName: String) {
        val config = loader.getCachedConfig(personaName)
            ?: fail("Persona '$personaName' not found in loaded configs")

        assertTrue(config.tools.contains("tool_search"),
            "Persona '$personaName': tool_search must be in core tools, got core=${config.tools}")
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "code-reviewer", "architect-reviewer", "devops-engineer", "explorer",
        "general-purpose", "performance-engineer", "refactoring-specialist",
        "security-auditor", "spring-boot-engineer", "test-automator", "python-engineer"
    ])
    fun `persona core tools contain think`(personaName: String) {
        val config = loader.getCachedConfig(personaName)
            ?: fail("Persona '$personaName' not found")

        assertTrue(config.tools.contains("think"),
            "Persona '$personaName': think must be in core tools, got core=${config.tools}")
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "code-reviewer", "architect-reviewer", "devops-engineer", "explorer",
        "general-purpose", "performance-engineer", "refactoring-specialist",
        "security-auditor", "spring-boot-engineer", "test-automator", "python-engineer"
    ])
    fun `persona core tools do not exceed 20`(personaName: String) {
        val config = loader.getCachedConfig(personaName)
            ?: fail("Persona '$personaName' not found")

        assertTrue(config.tools.size <= 20,
            "Persona '$personaName': core tools must be ≤20, got ${config.tools.size}: ${config.tools}")
    }

    @ParameterizedTest
    @ValueSource(strings = ["code-reviewer", "architect-reviewer", "explorer", "security-auditor"])
    fun `read-only personas have no write tools in core or deferred`(personaName: String) {
        val config = loader.getCachedConfig(personaName)
            ?: fail("Persona '$personaName' not found")

        val coreWriteTools = config.tools.intersect(WRITE_TOOLS)
        val deferredWriteTools = config.deferredTools.intersect(WRITE_TOOLS)

        assertTrue(coreWriteTools.isEmpty(),
            "Read-only persona '$personaName' must not have write tools in core: $coreWriteTools")
        assertTrue(deferredWriteTools.isEmpty(),
            "Read-only persona '$personaName' must not have write tools in deferred: $deferredWriteTools")
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "code-reviewer", "architect-reviewer", "devops-engineer", "explorer",
        "general-purpose", "performance-engineer", "refactoring-specialist",
        "security-auditor", "spring-boot-engineer", "test-automator", "python-engineer"
    ])
    fun `persona does not include agent tool in core or deferred`(personaName: String) {
        val config = loader.getCachedConfig(personaName)
            ?: fail("Persona '$personaName' not found")

        assertFalse(config.tools.contains("agent"),
            "Persona '$personaName': agent tool must never be in core (depth-1 enforcement)")
        assertFalse(config.deferredTools.contains("agent"),
            "Persona '$personaName': agent tool must never be in deferred (depth-1 enforcement)")
    }
}

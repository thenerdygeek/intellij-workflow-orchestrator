package com.workflow.orchestrator.agent.tools

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ToolCategoryRegistryTest {

    @Test
    fun `all tools are assigned to exactly one category`() {
        val toolToCategories = mutableMapOf<String, MutableList<String>>()
        for (cat in ToolCategoryRegistry.CATEGORIES) {
            for (tool in cat.tools) {
                toolToCategories.getOrPut(tool) { mutableListOf() }.add(cat.id)
            }
        }
        val duplicates = toolToCategories.filter { it.value.size > 1 }
        assertTrue(duplicates.isEmpty(), "Tools in multiple categories: $duplicates")
    }

    @Test
    fun `getAllToolNames returns all tools across all categories`() {
        val allNames = ToolCategoryRegistry.getAllToolNames()
        val expected = ToolCategoryRegistry.CATEGORIES.flatMap { it.tools }.toSet()
        assertEquals(expected, allNames)
    }

    @Test
    fun `getCategoryForTool returns correct category`() {
        val cat = ToolCategoryRegistry.getCategoryForTool("read_file")
        assertNotNull(cat)
        assertEquals("core", cat!!.id)
    }

    @Test
    fun `getCategoryForTool returns null for unknown tool`() {
        assertNull(ToolCategoryRegistry.getCategoryForTool("nonexistent_tool"))
    }

    @Test
    fun `getActivatableCategories excludes always-active categories`() {
        val activatable = ToolCategoryRegistry.getActivatableCategories()
        assertTrue(activatable.none { it.alwaysActive })
        assertTrue(activatable.isNotEmpty())
    }

    @Test
    fun `getAlwaysActiveTools returns only tools from always-active categories`() {
        val alwaysActive = ToolCategoryRegistry.getAlwaysActiveTools()
        assertTrue(alwaysActive.contains("read_file"))
        assertTrue(alwaysActive.contains("edit_file"))
        // Non-core meta-tools should not be in always-active
        assertFalse(alwaysActive.contains("jira"))
        assertFalse(alwaysActive.contains("bamboo_builds"))
        assertFalse(alwaysActive.contains("bamboo_plans"))
    }

    @Test
    fun `getToolsInCategory returns tools for valid category`() {
        val tools = ToolCategoryRegistry.getToolsInCategory("jira")
        assertTrue(tools.contains("jira"))
    }

    @Test
    fun `getToolsInCategory returns empty for unknown category`() {
        val tools = ToolCategoryRegistry.getToolsInCategory("nonexistent")
        assertTrue(tools.isEmpty())
    }

    @Test
    fun `every category has an id, displayName, and at least one tool`() {
        for (cat in ToolCategoryRegistry.CATEGORIES) {
            assertTrue(cat.id.isNotBlank(), "Category missing id")
            assertTrue(cat.displayName.isNotBlank(), "Category ${cat.id} missing displayName")
            assertTrue(cat.tools.isNotEmpty(), "Category ${cat.id} has no tools")
        }
    }

    @Test
    fun `only core category is always-active`() {
        val alwaysActiveCategories = ToolCategoryRegistry.CATEGORIES.filter { it.alwaysActive }
        assertEquals(1, alwaysActiveCategories.size)
        assertEquals("core", alwaysActiveCategories.first().id)
    }
}

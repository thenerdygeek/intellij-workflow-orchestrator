package com.workflow.orchestrator.agent.loop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PlanParserTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parse numbered list`() {
        val plan = """
            Here is the plan:
            1. Read UserService.kt to understand the interface
            2. Add the new method to the service interface
            3. Implement in UserServiceImpl.kt
            4. Write unit tests
        """.trimIndent()

        val steps = PlanParser.parse(plan)
        assertEquals(4, steps.size)
        assertEquals("1", steps[0].id)
        assertEquals("Read UserService.kt to understand the interface", steps[0].title)
        assertEquals("2", steps[1].id)
        assertEquals("Add the new method to the service interface", steps[1].title)
        assertEquals("pending", steps[0].status)
    }

    @Test
    fun `parse numbered list with descriptions`() {
        val plan = """
            1. Read the file
               Check the current content and imports
            2. Edit the file
               Add the new method signature
        """.trimIndent()

        val steps = PlanParser.parse(plan)
        assertEquals(2, steps.size)
        assertEquals("Read the file", steps[0].title)
        assertTrue(steps[0].description.contains("Check the current content"))
        assertEquals("Edit the file", steps[1].title)
        assertTrue(steps[1].description.contains("Add the new method"))
    }

    @Test
    fun `parse markdown headers`() {
        val plan = """
            ### Analyze the code
            Look at existing patterns

            ### Implement changes
            Make the modifications

            ### Test
            Run the test suite
        """.trimIndent()

        val steps = PlanParser.parse(plan)
        assertEquals(3, steps.size)
        assertEquals("Analyze the code", steps[0].title)
        assertEquals("Implement changes", steps[1].title)
        assertEquals("Test", steps[2].title)
    }

    @Test
    fun `parse step-prefixed headers`() {
        val plan = """
            ## Step 1: Read the code
            ## Step 2: Make changes
            ## Step 3: Test
        """.trimIndent()

        val steps = PlanParser.parse(plan)
        assertEquals(3, steps.size)
        assertEquals("Read the code", steps[0].title)
        assertEquals("Make changes", steps[1].title)
    }

    @Test
    fun `parse bullet points`() {
        val plan = """
            - Read UserService.kt
            - Add method to interface
            - Implement in module
            - Write tests
        """.trimIndent()

        val steps = PlanParser.parse(plan)
        assertEquals(4, steps.size)
        assertEquals("Read UserService.kt", steps[0].title)
        assertEquals("1", steps[0].id)
    }

    @Test
    fun `fallback to single step for unstructured text`() {
        val plan = "Just review the code and make sure it compiles correctly."

        val steps = PlanParser.parse(plan)
        assertEquals(1, steps.size)
        assertEquals(plan, steps[0].title)
    }

    @Test
    fun `parseToJson produces valid JSON`() {
        val plan = """
            1. Read the file
            2. Edit the file
            3. Test
        """.trimIndent()

        val result = PlanParser.parseToJson(plan)
        val parsed = json.parseToJsonElement(result).jsonObject

        assertTrue(parsed.containsKey("summary"))
        assertTrue(parsed.containsKey("steps"))
        val steps = parsed["steps"]!!.jsonArray
        assertEquals(3, steps.size)
        assertEquals("1", steps[0].jsonObject["id"]?.jsonPrimitive?.content)
        assertEquals("Read the file", steps[0].jsonObject["title"]?.jsonPrimitive?.content)
        assertEquals("pending", steps[0].jsonObject["status"]?.jsonPrimitive?.content)
    }

    @Test
    fun `summary extracted from preamble`() {
        val plan = """
            Here is my implementation plan for the feature:
            1. Step one
            2. Step two
        """.trimIndent()

        val result = PlanParser.parseToJson(plan)
        val parsed = json.parseToJsonElement(result).jsonObject
        val summary = parsed["summary"]?.jsonPrimitive?.content ?: ""
        assertTrue(summary.contains("implementation plan"), "Summary should contain preamble text, got: $summary")
    }

    @Test
    fun `summary defaults to step count when no preamble`() {
        val plan = """
            1. Step one
            2. Step two
        """.trimIndent()

        val result = PlanParser.parseToJson(plan)
        val parsed = json.parseToJsonElement(result).jsonObject
        val summary = parsed["summary"]?.jsonPrimitive?.content ?: ""
        assertTrue(summary.contains("2 steps"), "Summary should mention step count, got: $summary")
    }

    @Test
    fun `numbered list with bold markers`() {
        val plan = """
            1. **Read the code**
            2. **Edit the file**
        """.trimIndent()

        val steps = PlanParser.parse(plan)
        assertEquals(2, steps.size)
        assertEquals("Read the code", steps[0].title)
        assertEquals("Edit the file", steps[1].title)
    }

    @Test
    fun `empty plan produces single step`() {
        val plan = ""

        val steps = PlanParser.parse(plan)
        assertEquals(1, steps.size)
    }

    @Test
    fun `parenthesis numbered list`() {
        val plan = """
            1) First step
            2) Second step
        """.trimIndent()

        val steps = PlanParser.parse(plan)
        assertEquals(2, steps.size)
        assertEquals("First step", steps[0].title)
    }
}

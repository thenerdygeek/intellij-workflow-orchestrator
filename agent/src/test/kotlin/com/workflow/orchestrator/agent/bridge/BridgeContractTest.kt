package com.workflow.orchestrator.agent.bridge

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

// Bridge Contract Tests - Kotlin Side
//
// Tests that Kotlin can parse and produce JSON matching the shared contract
// fixtures in test/resources/contracts/. The React side has matching tests
// (bridge-contracts.test.ts) that produce and consume the SAME fixtures.
// If either side changes the format, tests break on the OTHER side.
class BridgeContractTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun loadContract(name: String): String {
        return javaClass.classLoader.getResource("contracts/$name")?.readText()
            ?: throw IllegalStateException("Contract fixture not found: contracts/$name")
    }

    // ── Plan Revise Contract (v2 only — v1 format removed) ──

    // ── Plan Data Contract ──

    @Test
    fun `plan-data payload has required fields`() {
        val contract = json.parseToJsonElement(loadContract("plan-data.json")).jsonObject
        val payload = contract["payload"]!!.jsonObject
        val requiredFields = contract["required_fields"]!!.jsonArray.map { it.jsonPrimitive.content }

        for (field in requiredFields) {
            assertTrue(payload.containsKey(field), "Missing required field: $field")
        }
    }

    @Test
    fun `plan-data steps have required fields`() {
        val contract = json.parseToJsonElement(loadContract("plan-data.json")).jsonObject
        val steps = contract["payload"]!!.jsonObject["steps"]!!.jsonArray
        val stepRequiredFields = contract["step_required_fields"]!!.jsonArray.map { it.jsonPrimitive.content }

        for (step in steps) {
            for (field in stepRequiredFields) {
                assertTrue(step.jsonObject.containsKey(field),
                    "Step missing required field: $field")
            }
        }
    }

    @Test
    fun `plan-data can be serialized by Kotlin and roundtripped`() {
        val contract = json.parseToJsonElement(loadContract("plan-data.json")).jsonObject
        val payload = contract["payload"]!!

        // Simulate what PlanManager.json.encodeToString does
        val serialized = payload.toString()
        val deserialized = json.parseToJsonElement(serialized)

        assertEquals(
            payload.jsonObject["goal"]!!.jsonPrimitive.content,
            deserialized.jsonObject["goal"]!!.jsonPrimitive.content
        )
        assertEquals(
            payload.jsonObject["steps"]!!.jsonArray.size,
            deserialized.jsonObject["steps"]!!.jsonArray.size
        )
    }

    // ── Plan Step Update Contract ──

    @Test
    fun `plan-step-update valid statuses are strings`() {
        val contract = json.parseToJsonElement(loadContract("plan-step-update.json")).jsonObject
        val validStatuses = contract["valid_statuses"]!!.jsonArray.map { it.jsonPrimitive.content }

        assertTrue(validStatuses.contains("pending"))
        assertTrue(validStatuses.contains("running"))
        assertTrue(validStatuses.contains("completed"))
        assertTrue(validStatuses.contains("failed"))
    }

    @Test
    fun `plan-step-update valid calls have string stepId and status`() {
        val contract = json.parseToJsonElement(loadContract("plan-step-update.json")).jsonObject
        val validCalls = contract["valid_calls"]!!.jsonArray

        for (call in validCalls) {
            val stepId = call.jsonObject["stepId"]!!.jsonPrimitive.content
            val status = call.jsonObject["status"]!!.jsonPrimitive.content

            assertTrue(stepId.isNotBlank(), "stepId must not be blank")
            assertTrue(status.isNotBlank(), "status must not be blank")
        }
    }

    // ── Edit Stats Contract ──

    @Test
    fun `edit-stats has numeric fields`() {
        val contract = json.parseToJsonElement(loadContract("edit-stats.json")).jsonObject
        val stats = contract["edit_stats"]!!.jsonObject["valid_call"]!!.jsonObject

        assertTrue(stats["added"] is JsonPrimitive)
        assertTrue(stats["removed"] is JsonPrimitive)
        assertTrue(stats["files"] is JsonPrimitive)
        assertEquals(45, stats["added"]!!.jsonPrimitive.int)
        assertEquals(12, stats["removed"]!!.jsonPrimitive.int)
        assertEquals(3, stats["files"]!!.jsonPrimitive.int)
    }

    @Test
    fun `checkpoints payload has required fields`() {
        val contract = json.parseToJsonElement(loadContract("edit-stats.json")).jsonObject
        val checkpoints = contract["checkpoints"]!!.jsonObject["valid_payload"]!!.jsonArray

        for (cp in checkpoints) {
            val obj = cp.jsonObject
            assertTrue(obj.containsKey("id"))
            assertTrue(obj.containsKey("description"))
            assertTrue(obj.containsKey("timestamp"))
            assertTrue(obj.containsKey("iteration"))
            assertTrue(obj.containsKey("filesModified"))
            assertTrue(obj.containsKey("totalLinesAdded"))
            assertTrue(obj.containsKey("totalLinesRemoved"))

            // Type checks
            assertTrue(obj["id"] is JsonPrimitive)
            assertTrue(obj["timestamp"]!!.jsonPrimitive.longOrNull != null)
            assertTrue(obj["filesModified"] is JsonArray)
        }
    }

    @Test
    fun `checkpoints can be serialized and deserialized by Kotlin`() {
        val contract = json.parseToJsonElement(loadContract("edit-stats.json")).jsonObject
        val checkpoints = contract["checkpoints"]!!.jsonObject["valid_payload"]!!

        val serialized = checkpoints.toString()
        val deserialized = json.parseToJsonElement(serialized).jsonArray

        assertEquals(2, deserialized.size)
        assertEquals("cp-a1b2c3", deserialized[0].jsonObject["id"]!!.jsonPrimitive.content)
    }

    // ── Plan Revise V2 Contract (per-line comments) ──

    @Test
    fun `plan-revise-v2 valid payloads parse with comments array and markdown string`() {
        val contract = json.parseToJsonElement(loadContract("plan-revise-v2.json")).jsonObject
        val validPayloads = contract["valid_payloads"]!!.jsonArray

        for (testCase in validPayloads) {
            val payload = testCase.jsonObject["payload"]!!.jsonPrimitive.content
            val expectedCount = testCase.jsonObject["expected_comment_count"]!!.jsonPrimitive.int

            val parsed = json.parseToJsonElement(payload).jsonObject

            // Must have "comments" array and "markdown" string
            assertTrue(parsed.containsKey("comments"), "Missing 'comments' key")
            assertTrue(parsed.containsKey("markdown"), "Missing 'markdown' key")

            val comments = parsed["comments"]!!.jsonArray
            assertEquals(expectedCount, comments.size,
                "Comment count mismatch for: ${testCase.jsonObject["name"]}")

            val markdown = parsed["markdown"]!!.jsonPrimitive.content
            assertTrue(markdown.isNotEmpty(), "Markdown should not be empty")
        }
    }

    @Test
    fun `plan-revise-v2 comments have line number content and comment text`() {
        val contract = json.parseToJsonElement(loadContract("plan-revise-v2.json")).jsonObject
        val validPayloads = contract["valid_payloads"]!!.jsonArray

        for (testCase in validPayloads) {
            val payload = testCase.jsonObject["payload"]!!.jsonPrimitive.content
            val parsed = json.parseToJsonElement(payload).jsonObject
            val comments = parsed["comments"]!!.jsonArray

            for (comment in comments) {
                val obj = comment.jsonObject
                assertTrue(obj.containsKey("line"), "Comment missing 'line'")
                assertTrue(obj.containsKey("content"), "Comment missing 'content'")
                assertTrue(obj.containsKey("comment"), "Comment missing 'comment'")

                // line is a number
                assertTrue(obj["line"]!!.jsonPrimitive.intOrNull != null,
                    "Comment 'line' must be a number")
                // content and comment are strings
                assertTrue(obj["content"] is JsonPrimitive, "Comment 'content' must be a string")
                assertTrue(obj["comment"] is JsonPrimitive, "Comment 'comment' must be a string")
            }
        }
    }

    @Test
    fun `plan-revise-v2 can be roundtripped through Kotlin JSON`() {
        val contract = json.parseToJsonElement(loadContract("plan-revise-v2.json")).jsonObject
        val testCase = contract["valid_payloads"]!!.jsonArray[0]
        val payload = testCase.jsonObject["payload"]!!.jsonPrimitive.content

        val parsed = json.parseToJsonElement(payload)
        val serialized = parsed.toString()
        val reparsed = json.parseToJsonElement(serialized).jsonObject

        assertEquals(
            parsed.jsonObject["comments"]!!.jsonArray.size,
            reparsed["comments"]!!.jsonArray.size
        )
        assertEquals(
            parsed.jsonObject["markdown"]!!.jsonPrimitive.content,
            reparsed["markdown"]!!.jsonPrimitive.content
        )
    }
}
// EOF

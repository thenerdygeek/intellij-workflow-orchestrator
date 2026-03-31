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

    // ── Plan Revise Contract ──

    @Test
    fun `plan-revise valid payloads parse as Map of String to String`() {
        val contract = json.parseToJsonElement(loadContract("plan-revise.json")).jsonObject
        val validPayloads = contract["valid_payloads"]!!.jsonArray

        for (testCase in validPayloads) {
            val payload = testCase.jsonObject["payload"]!!.jsonPrimitive.content
            val expectedKeys = testCase.jsonObject["expected_keys"]!!.jsonArray.map { it.jsonPrimitive.content }

            // This is exactly what AgentController.setCefPlanCallbacks does
            // when it receives the revise payload from JCEF
            val parsed = json.parseToJsonElement(payload).jsonObject
            val comments = parsed.entries.associate { (k, v) -> k to v.jsonPrimitive.content }

            assertEquals(expectedKeys.sorted(), comments.keys.sorted(),
                "Keys mismatch for test case: ${testCase.jsonObject["name"]}")

            // Verify values are strings
            for ((_, value) in comments) {
                assertTrue(value is String, "Values must be strings")
            }
        }
    }

    @Test
    fun `plan-revise invalid payloads fail to parse`() {
        val contract = json.parseToJsonElement(loadContract("plan-revise.json")).jsonObject
        val invalidPayloads = contract["invalid_payloads"]!!.jsonArray

        for (testCase in invalidPayloads) {
            val payload = testCase.jsonObject["payload"]!!.jsonPrimitive.content

            assertThrows(Exception::class.java, {
                if (payload.isBlank()) throw IllegalArgumentException("Blank payload")
                val parsed = json.parseToJsonElement(payload)
                // If it parses but isn't an object, that's also invalid
                parsed.jsonObject
            }, "Should fail for: ${testCase.jsonObject["name"]}")
        }
    }

    @Test
    fun `plan-revise comment keys follow section ID pattern`() {
        val validKeyPattern = Regex("^(goal|approach|testing|step-\\d+)$")
        val contract = json.parseToJsonElement(loadContract("plan-revise.json")).jsonObject
        val validPayloads = contract["valid_payloads"]!!.jsonArray

        for (testCase in validPayloads) {
            val payload = testCase.jsonObject["payload"]!!.jsonPrimitive.content
            val parsed = json.parseToJsonElement(payload).jsonObject

            for (key in parsed.keys) {
                assertTrue(validKeyPattern.matches(key),
                    "Key '$key' doesn't match section ID pattern (goal|approach|testing|step-N)")
            }
        }
    }

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
}
// EOF

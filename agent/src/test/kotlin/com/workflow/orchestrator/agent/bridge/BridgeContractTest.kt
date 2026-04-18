package com.workflow.orchestrator.agent.bridge

import com.workflow.orchestrator.agent.loop.Task
import com.workflow.orchestrator.agent.loop.TaskStatus
import kotlinx.serialization.encodeToString
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

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun loadContract(name: String): String {
        return javaClass.classLoader.getResource("contracts/$name")?.readText()
            ?: throw IllegalStateException("Contract fixture not found: contracts/$name")
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
    fun `plan-data payload has no steps field`() {
        val contract = json.parseToJsonElement(loadContract("plan-data.json")).jsonObject
        val payload = contract["payload"]!!.jsonObject
        assertFalse(payload.containsKey("steps"),
            "plan-data payload must not contain 'steps' — progress is tracked via task_* bridges")
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
    }

    // ── Task Create Contract ──

    @Test
    fun `task-create payload has required fields`() {
        val contract = json.parseToJsonElement(loadContract("task-create.json")).jsonObject
        val payload = contract["payload"]!!.jsonObject
        val requiredFields = contract["required_fields"]!!.jsonArray.map { it.jsonPrimitive.content }

        for (field in requiredFields) {
            assertTrue(payload.containsKey(field), "Missing required field: $field")
        }
    }

    @Test
    fun `task-create status is a valid status`() {
        val contract = json.parseToJsonElement(loadContract("task-create.json")).jsonObject
        val payload = contract["payload"]!!.jsonObject
        val validStatuses = contract["valid_statuses"]!!.jsonArray.map { it.jsonPrimitive.content }
        val status = payload["status"]!!.jsonPrimitive.content

        assertTrue(validStatuses.contains(status), "status '$status' not in valid_statuses: $validStatuses")
    }

    @Test
    fun `task-create can be encoded by Kotlin Task and matches contract required fields`() {
        val contract = json.parseToJsonElement(loadContract("task-create.json")).jsonObject
        val requiredFields = contract["required_fields"]!!.jsonArray.map { it.jsonPrimitive.content }
        val validStatuses = contract["valid_statuses"]!!.jsonArray.map { it.jsonPrimitive.content }

        val task = Task(
            id = "task-abc123",
            subject = "Fix auth bug in login flow",
            description = "Users see 500 on /login when cookies expire mid-request.",
            activeForm = "Fixing auth bug",
            status = TaskStatus.PENDING,
        )

        val encoded = json.parseToJsonElement(json.encodeToString(task)).jsonObject

        for (field in requiredFields) {
            assertTrue(encoded.containsKey(field), "Encoded Task missing required field: $field")
        }

        val encodedStatus = encoded["status"]!!.jsonPrimitive.content
        assertTrue(validStatuses.contains(encodedStatus),
            "Encoded status '$encodedStatus' not in valid_statuses: $validStatuses")
    }

    // ── Task Update Contract ──

    @Test
    fun `task-update payload has required fields`() {
        val contract = json.parseToJsonElement(loadContract("task-update.json")).jsonObject
        val payload = contract["payload"]!!.jsonObject
        val requiredFields = contract["required_fields"]!!.jsonArray.map { it.jsonPrimitive.content }

        for (field in requiredFields) {
            assertTrue(payload.containsKey(field), "Missing required field: $field")
        }
    }

    @Test
    fun `task-update status is a valid status`() {
        val contract = json.parseToJsonElement(loadContract("task-update.json")).jsonObject
        val payload = contract["payload"]!!.jsonObject
        val validStatuses = contract["valid_statuses"]!!.jsonArray.map { it.jsonPrimitive.content }
        val status = payload["status"]!!.jsonPrimitive.content

        assertTrue(validStatuses.contains(status), "status '$status' not in valid_statuses: $validStatuses")
    }

    @Test
    fun `task-update can be encoded by Kotlin Task with in_progress status`() {
        val contract = json.parseToJsonElement(loadContract("task-update.json")).jsonObject
        val requiredFields = contract["required_fields"]!!.jsonArray.map { it.jsonPrimitive.content }
        val validStatuses = contract["valid_statuses"]!!.jsonArray.map { it.jsonPrimitive.content }

        val task = Task(
            id = "task-abc123",
            subject = "Fix auth bug in login flow",
            description = "Users see 500 on /login when cookies expire mid-request.",
            activeForm = "Fixing auth bug",
            status = TaskStatus.IN_PROGRESS,
            owner = "coder-agent",
        )

        val encoded = json.parseToJsonElement(json.encodeToString(task)).jsonObject

        for (field in requiredFields) {
            assertTrue(encoded.containsKey(field), "Encoded Task missing required field: $field")
        }

        val encodedStatus = encoded["status"]!!.jsonPrimitive.content
        assertTrue(validStatuses.contains(encodedStatus),
            "Encoded status '$encodedStatus' not in valid_statuses: $validStatuses")
        assertEquals("in_progress", encodedStatus)
    }

    // ── Task List Contract ──

    @Test
    fun `task-list payload is an array with required fields per task`() {
        val contract = json.parseToJsonElement(loadContract("task-list.json")).jsonObject
        val tasks = contract["payload"]!!.jsonArray
        val requiredFields = contract["required_fields_per_task"]!!.jsonArray.map { it.jsonPrimitive.content }

        assertTrue(tasks.size > 0, "task-list payload must contain at least one task")
        for (task in tasks) {
            for (field in requiredFields) {
                assertTrue(task.jsonObject.containsKey(field),
                    "Task in list missing required field: $field")
            }
        }
    }

    @Test
    fun `task-list can be encoded by Kotlin list of Tasks`() {
        val contract = json.parseToJsonElement(loadContract("task-list.json")).jsonObject
        val requiredFields = contract["required_fields_per_task"]!!.jsonArray.map { it.jsonPrimitive.content }

        val tasks = listOf(
            Task(id = "task-1", subject = "Write tests", description = "Add coverage for auth flow",
                status = TaskStatus.COMPLETED),
            Task(id = "task-2", subject = "Deploy fix", description = "Push to staging",
                status = TaskStatus.PENDING, blockedBy = listOf("task-1")),
        )

        val encoded = json.parseToJsonElement(json.encodeToString(tasks)).jsonArray

        assertEquals(2, encoded.size)
        for (taskEl in encoded) {
            for (field in requiredFields) {
                assertTrue(taskEl.jsonObject.containsKey(field),
                    "Encoded task missing required field: $field")
            }
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

package com.workflow.orchestrator.agent.tools.integration

import com.workflow.orchestrator.core.model.jira.FieldSchema
import com.workflow.orchestrator.core.model.jira.FieldValue
import com.workflow.orchestrator.core.model.jira.MissingFieldsError
import com.workflow.orchestrator.core.model.jira.StatusCategory
import com.workflow.orchestrator.core.model.jira.StatusRef
import com.workflow.orchestrator.core.model.jira.TransitionError
import com.workflow.orchestrator.core.model.jira.TransitionField
import com.workflow.orchestrator.core.model.jira.TransitionInput
import com.workflow.orchestrator.core.model.jira.TransitionOutcome
import com.workflow.orchestrator.core.services.ToolResult
import com.workflow.orchestrator.core.services.jira.TicketTransitionService
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [JiraTool.executeTransitionForTest] — verifies field coercion and
 * MissingFields error surfacing without requiring IntelliJ service infrastructure.
 */
class JiraToolTransitionTest {

    private val tool = JiraTool()

    // ---- Test helpers ----

    private val successOutcome = TransitionOutcome(
        key = "ABC-1",
        fromStatus = StatusRef("1", "To Do", StatusCategory.TO_DO),
        toStatus = StatusRef("3", "In Progress", StatusCategory.IN_PROGRESS),
        transitionId = "21",
        appliedFields = emptyMap()
    )

    private fun successResult() = ToolResult(
        data = successOutcome,
        summary = "Transitioned ABC-1 to In Progress",
        isError = false
    )

    // ---- Field coercion tests (via parseFieldsJson) ----

    @Test
    fun `parseFieldsJson coerces assignee object to UserRef`() {
        val fieldsJson = buildJsonObject {
            putJsonObject("assignee") { put("name", "jdoe") }
        }
        val result = tool.parseFieldsJson(fieldsJson)
        assertEquals(FieldValue.UserRef("jdoe"), result["assignee"])
    }

    @Test
    fun `parseFieldsJson coerces labels array to LabelList`() {
        val fieldsJson = buildJsonObject {
            putJsonArray("labels") {
                add(kotlinx.serialization.json.JsonPrimitive("bug"))
                add(kotlinx.serialization.json.JsonPrimitive("backend"))
            }
        }
        val result = tool.parseFieldsJson(fieldsJson)
        assertEquals(FieldValue.LabelList(listOf("bug", "backend")), result["labels"])
    }

    @Test
    fun `parseFieldsJson coerces priority object with id to Option`() {
        val fieldsJson = buildJsonObject {
            putJsonObject("priority") { put("id", "2") }
        }
        val result = tool.parseFieldsJson(fieldsJson)
        assertEquals(FieldValue.Option("2"), result["priority"])
    }

    @Test
    fun `parseFieldsJson coerces multi-select array of id-objects to Options`() {
        val fieldsJson = buildJsonObject {
            putJsonArray("components") {
                add(buildJsonObject { put("id", "10001") })
                add(buildJsonObject { put("id", "10002") })
            }
        }
        val result = tool.parseFieldsJson(fieldsJson)
        assertEquals(FieldValue.Options(listOf("10001", "10002")), result["components"])
    }

    @Test
    fun `parseFieldsJson coerces cascading object to Cascade with child`() {
        val fieldsJson = buildJsonObject {
            putJsonObject("customfield_10100") {
                put("value", "Hardware")
                putJsonObject("child") { put("value", "Server") }
            }
        }
        val result = tool.parseFieldsJson(fieldsJson)
        assertEquals(FieldValue.Cascade("Hardware", "Server"), result["customfield_10100"])
    }

    @Test
    fun `parseFieldsJson coerces string primitive to Text`() {
        val fieldsJson = buildJsonObject {
            put("summary", "New summary text")
        }
        val result = tool.parseFieldsJson(fieldsJson)
        assertEquals(FieldValue.Text("New summary text"), result["summary"])
    }

    @Test
    fun `parseFieldsJson coerces numeric primitive to Number`() {
        val fieldsJson = buildJsonObject {
            put("story_points", 5)
        }
        val result = tool.parseFieldsJson(fieldsJson)
        assertEquals(FieldValue.Number(5.0), result["story_points"])
    }

    @Test
    fun `parseFieldsJson handles multiple fields in one call`() {
        val fieldsJson = buildJsonObject {
            putJsonObject("assignee") { put("name", "jdoe") }
            putJsonArray("labels") {
                add(kotlinx.serialization.json.JsonPrimitive("bug"))
            }
        }
        val result = tool.parseFieldsJson(fieldsJson)
        assertEquals(2, result.size)
        assertEquals(FieldValue.UserRef("jdoe"), result["assignee"])
        assertEquals(FieldValue.LabelList(listOf("bug")), result["labels"])
    }

    // ---- executeTransitionForTest — field capture ----

    @Test
    fun `passes fields from JSON to TransitionInput`() = runTest {
        val svc = mockk<TicketTransitionService>()
        val captured = slot<TransitionInput>()

        coEvery { svc.executeTransition("ABC-1", capture(captured)) } returns successResult()

        val fieldsJson = buildJsonObject {
            putJsonObject("assignee") { put("name", "jdoe") }
            putJsonArray("labels") { add(kotlinx.serialization.json.JsonPrimitive("bug")) }
        }

        val result = tool.executeTransitionForTest(
            key = "ABC-1",
            transitionId = "21",
            fieldsJson = fieldsJson,
            comment = "moving",
            transitionSvc = svc
        )

        assertFalse(result.isError)
        assertEquals(FieldValue.UserRef("jdoe"), captured.captured.fieldValues["assignee"])
        assertEquals(FieldValue.LabelList(listOf("bug")), captured.captured.fieldValues["labels"])
        assertEquals("21", captured.captured.transitionId)
        assertEquals("moving", captured.captured.comment)
    }

    @Test
    fun `passes null fields as empty map to TransitionInput`() = runTest {
        val svc = mockk<TicketTransitionService>()
        val captured = slot<TransitionInput>()

        coEvery { svc.executeTransition("ABC-1", capture(captured)) } returns successResult()

        tool.executeTransitionForTest(
            key = "ABC-1",
            transitionId = "21",
            fieldsJson = null,
            comment = null,
            transitionSvc = svc
        )

        assertTrue(captured.captured.fieldValues.isEmpty())
    }

    // ---- executeTransitionForTest — MissingFields error ----

    @Test
    fun `returns MissingFields payload when required fields absent`() = runTest {
        val svc = mockk<TicketTransitionService>()
        val missing = MissingFieldsError(
            transitionId = "31",
            transitionName = "Done",
            fields = listOf(
                TransitionField(
                    id = "assignee",
                    name = "Assignee",
                    required = true,
                    schema = FieldSchema.User(multi = false),
                    allowedValues = emptyList(),
                    autoCompleteUrl = null,
                    defaultValue = null
                )
            ),
            guidance = "ask_followup_question"
        )

        coEvery { svc.executeTransition(any(), any()) } returns ToolResult(
            data = successOutcome,
            summary = "missing_required_fields",
            isError = true,
            payload = TransitionError.MissingFields(missing)
        )

        val result = tool.executeTransitionForTest(
            key = "ABC-1",
            transitionId = "31",
            fieldsJson = null,
            comment = null,
            transitionSvc = svc
        )

        assertTrue(result.isError)
        val payload = result.payload as TransitionError.MissingFields
        assertEquals(missing, payload.payload)
        assertEquals("missing_required_fields", result.summary)
    }

    @Test
    fun `returns InvalidTransition payload on invalid transition`() = runTest {
        val svc = mockk<TicketTransitionService>()

        coEvery { svc.executeTransition(any(), any()) } returns ToolResult(
            data = successOutcome,
            summary = "Transition not available",
            isError = true,
            payload = TransitionError.InvalidTransition("Transition '99' not available from current status")
        )

        val result = tool.executeTransitionForTest(
            key = "ABC-1",
            transitionId = "99",
            fieldsJson = null,
            comment = null,
            transitionSvc = svc
        )

        assertTrue(result.isError)
        val payload = result.payload as TransitionError.InvalidTransition
        assertTrue(payload.reason.contains("99"))
    }

    // ---- Description and parameter schema ----

    @Test
    fun `description mentions fields parameter and MissingFields retry pattern`() {
        val desc = JiraTool().description
        assertTrue(desc.contains("fields?"), "description should document the fields? param")
        assertTrue(desc.contains("MissingFields"), "description should mention MissingFields retry")
        assertTrue(desc.contains("ask_followup_question"), "description should reference ask_followup_question")
    }

    @Test
    fun `parameter schema includes fields as optional object`() {
        val tool = JiraTool()
        val fieldsProp = tool.parameters.properties["fields"]
        assertTrue(fieldsProp != null, "parameters should have 'fields' property")
        assertEquals("object", fieldsProp!!.type)
        // fields is not in required list
        assertFalse(tool.parameters.required.contains("fields"))
    }
}

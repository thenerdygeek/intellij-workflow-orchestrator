package com.workflow.orchestrator.jira.api

import com.workflow.orchestrator.core.model.jira.FieldValue
import com.workflow.orchestrator.core.model.jira.TransitionInput
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TransitionInputSerializerTest {

    private val ser = TransitionInputSerializer()

    private fun json(map: Map<String, FieldValue>, comment: String? = null): String {
        val input = TransitionInput("31", map, comment)
        // Serializer returns a JsonObject; stringify it stably for asserts.
        return Json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), ser.buildBody(input))
    }

    @Test fun `user ref becomes name object`() {
        assertEquals("""{"transition":{"id":"31"},"fields":{"assignee":{"name":"jdoe"}}}""",
            json(mapOf("assignee" to FieldValue.UserRef("jdoe"))))
    }

    @Test fun `labels become array of strings`() {
        assertEquals("""{"transition":{"id":"31"},"fields":{"labels":["bug","p1"]}}""",
            json(mapOf("labels" to FieldValue.LabelList(listOf("bug", "p1")))))
    }

    @Test fun `single option becomes id object`() {
        assertEquals("""{"transition":{"id":"31"},"fields":{"priority":{"id":"1"}}}""",
            json(mapOf("priority" to FieldValue.Option("1"))))
    }

    @Test fun `multi options become array of id objects`() {
        assertEquals("""{"transition":{"id":"31"},"fields":{"cf":[{"id":"1"},{"id":"2"}]}}""",
            json(mapOf("cf" to FieldValue.Options(listOf("1", "2")))))
    }

    @Test fun `cascade with child emits parent and child`() {
        assertEquals("""{"transition":{"id":"31"},"fields":{"cf":{"value":"p","child":{"value":"c"}}}}""",
            json(mapOf("cf" to FieldValue.Cascade("p", "c"))))
    }

    @Test fun `cascade without child emits only parent`() {
        assertEquals("""{"transition":{"id":"31"},"fields":{"cf":{"value":"p"}}}""",
            json(mapOf("cf" to FieldValue.Cascade("p", null))))
    }

    @Test fun `version multi emits id array`() {
        assertEquals("""{"transition":{"id":"31"},"fields":{"fixVersions":[{"id":"100"},{"id":"101"}]}}""",
            json(mapOf("fixVersions" to FieldValue.VersionRefs(listOf("100", "101")))))
    }

    @Test fun `text and number emit primitives`() {
        assertEquals("""{"transition":{"id":"31"},"fields":{"txt":"hi","num":3.5}}""",
            json(mapOf("txt" to FieldValue.Text("hi"), "num" to FieldValue.Number(3.5))))
    }

    @Test fun `date emits iso string`() {
        assertEquals("""{"transition":{"id":"31"},"fields":{"d":"2026-04-22"}}""",
            json(mapOf("d" to FieldValue.Date("2026-04-22"))))
    }

    @Test fun `comment becomes update add body comment`() {
        assertEquals(
            """{"transition":{"id":"31"},"fields":{"assignee":{"name":"a"}},"update":{"comment":[{"add":{"body":"hello"}}]}}""",
            json(mapOf("assignee" to FieldValue.UserRef("a")), comment = "hello")
        )
    }

    @Test fun `empty fields map emits no fields key`() {
        assertEquals("""{"transition":{"id":"31"}}""", json(emptyMap()))
    }
}

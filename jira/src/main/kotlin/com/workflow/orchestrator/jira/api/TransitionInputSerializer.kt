package com.workflow.orchestrator.jira.api

import com.workflow.orchestrator.core.model.jira.FieldValue
import com.workflow.orchestrator.core.model.jira.TransitionInput
import kotlinx.serialization.json.*

class TransitionInputSerializer {

    fun buildBody(input: TransitionInput): JsonObject = buildJsonObject {
        putJsonObject("transition") {
            put("id", input.transitionId)
        }
        if (input.fieldValues.isNotEmpty()) {
            putJsonObject("fields") {
                input.fieldValues.forEach { (id, value) -> writeValue(id, value) }
            }
        }
        if (!input.comment.isNullOrBlank()) {
            putJsonObject("update") {
                putJsonArray("comment") {
                    addJsonObject { putJsonObject("add") { put("body", input.comment) } }
                }
            }
        }
    }

    private fun JsonObjectBuilder.writeValue(id: String, value: FieldValue) {
        when (value) {
            is FieldValue.Text -> put(id, value.value)
            is FieldValue.Number -> put(id, value.value)
            is FieldValue.Date -> put(id, value.iso)
            is FieldValue.DateTime -> put(id, value.iso)
            is FieldValue.UserRef -> putJsonObject(id) { put("name", value.name) }
            is FieldValue.UserRefs -> putJsonArray(id) { value.names.forEach { n -> addJsonObject { put("name", n) } } }
            is FieldValue.GroupRef -> putJsonObject(id) { put("name", value.name) }
            is FieldValue.Option -> putJsonObject(id) { put("id", value.id) }
            is FieldValue.Options -> putJsonArray(id) { value.ids.forEach { v -> addJsonObject { put("id", v) } } }
            is FieldValue.Cascade -> putJsonObject(id) {
                put("value", value.parentId)
                if (value.childId != null) putJsonObject("child") { put("value", value.childId) }
            }
            is FieldValue.VersionRef -> putJsonObject(id) { put("id", value.id) }
            is FieldValue.VersionRefs -> putJsonArray(id) { value.ids.forEach { v -> addJsonObject { put("id", v) } } }
            is FieldValue.ComponentRef -> putJsonObject(id) { put("id", value.id) }
            is FieldValue.ComponentRefs -> putJsonArray(id) { value.ids.forEach { v -> addJsonObject { put("id", v) } } }
            is FieldValue.LabelList -> putJsonArray(id) { value.labels.forEach { add(it) } }
        }
    }
}

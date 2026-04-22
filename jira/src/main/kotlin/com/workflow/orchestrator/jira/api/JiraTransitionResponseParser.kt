package com.workflow.orchestrator.jira.api

import com.workflow.orchestrator.core.model.jira.FieldOption
import com.workflow.orchestrator.core.model.jira.FieldSchema
import com.workflow.orchestrator.core.model.jira.SelectSource
import com.workflow.orchestrator.core.model.jira.StatusCategory
import com.workflow.orchestrator.core.model.jira.StatusRef
import com.workflow.orchestrator.core.model.jira.TransitionField
import com.workflow.orchestrator.core.model.jira.TransitionMeta
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class JiraTransitionResponseParser(private val json: Json = Json { ignoreUnknownKeys = true }) {

    fun parse(raw: String): List<TransitionMeta> {
        val root = json.parseToJsonElement(raw).jsonObject
        val arr = root["transitions"]?.jsonArray ?: return emptyList()
        return arr.map { parseTransition(it.jsonObject) }
    }

    private fun parseTransition(n: JsonObject): TransitionMeta {
        val to = n["to"]!!.jsonObject
        val toStatus = StatusRef(
            id = to["id"]!!.jsonPrimitive.content,
            name = to["name"]!!.jsonPrimitive.content,
            category = mapCategory(to["statusCategory"]?.jsonObject?.get("key")?.jsonPrimitive?.contentOrNull)
        )
        val fieldsObj = n["fields"]?.jsonObject
        val fields = fieldsObj?.entries?.map { (id, node) -> parseField(id, node.jsonObject) } ?: emptyList()
        return TransitionMeta(
            id = n["id"]!!.jsonPrimitive.content,
            name = n["name"]!!.jsonPrimitive.content,
            toStatus = toStatus,
            hasScreen = fields.isNotEmpty(),
            fields = fields
        )
    }

    private fun parseField(id: String, n: JsonObject): TransitionField {
        val required = n["required"]?.jsonPrimitive?.booleanOrNull ?: false
        val name = n["name"]?.jsonPrimitive?.contentOrNull ?: id
        val schemaNode = n["schema"]?.jsonObject
        val autoCompleteUrl = n["autoCompleteUrl"]?.jsonPrimitive?.contentOrNull
        val allowed = parseAllowedValues(n["allowedValues"])
        val schema = mapSchema(schemaNode, allowed, autoCompleteUrl)
        return TransitionField(
            id = id, name = name, required = required,
            schema = schema, allowedValues = allowed,
            autoCompleteUrl = autoCompleteUrl, defaultValue = null
        )
    }

    private fun parseAllowedValues(n: JsonElement?): List<FieldOption> {
        val arr = n?.jsonArray ?: return emptyList()
        return arr.map { v ->
            val obj = v.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: obj["value"]?.jsonPrimitive?.contentOrNull ?: ""
            val display = obj["name"]?.jsonPrimitive?.contentOrNull ?: obj["value"]?.jsonPrimitive?.contentOrNull ?: id
            FieldOption(id = id, value = display, iconUrl = obj["iconUrl"]?.jsonPrimitive?.contentOrNull)
        }
    }

    private fun mapSchema(
        schema: JsonObject?,
        allowed: List<FieldOption>,
        autoCompleteUrl: String?
    ): FieldSchema {
        if (schema == null) return FieldSchema.Unknown("missing")
        val type = schema["type"]?.jsonPrimitive?.contentOrNull ?: return FieldSchema.Unknown("missing")
        val items = schema["items"]?.jsonPrimitive?.contentOrNull
        val system = schema["system"]?.jsonPrimitive?.contentOrNull
        val custom = schema["custom"]?.jsonPrimitive?.contentOrNull

        return when (type) {
            "user" -> FieldSchema.User(multi = false)
            "group" -> FieldSchema.Group(multi = false)
            "version" -> FieldSchema.Version(multi = false)
            "component" -> FieldSchema.Component(multi = false)
            "priority" -> FieldSchema.Priority
            "number" -> FieldSchema.Number
            "date" -> FieldSchema.Date
            "datetime" -> FieldSchema.DateTime
            "array" -> when (items) {
                "user" -> FieldSchema.User(multi = true)
                "group" -> FieldSchema.Group(multi = true)
                "version" -> FieldSchema.Version(multi = true)
                "component" -> FieldSchema.Component(multi = true)
                "string" -> if (system == "labels") FieldSchema.Labels else FieldSchema.MultiSelect(sourceFor(allowed, autoCompleteUrl))
                "option" -> FieldSchema.MultiSelect(sourceFor(allowed, autoCompleteUrl))
                else -> FieldSchema.Unknown("array<${items ?: "?"}>")
            }
            "string" -> when {
                system == "labels" -> FieldSchema.Labels
                custom?.contains("cascadingselect") == true -> FieldSchema.CascadingSelect
                allowed.isNotEmpty() -> FieldSchema.SingleSelect(SelectSource.AllowedValues)
                autoCompleteUrl != null -> FieldSchema.SingleSelect(SelectSource.AutoCompleteUrl)
                else -> FieldSchema.Text
            }
            "option" -> FieldSchema.SingleSelect(sourceFor(allowed, autoCompleteUrl))
            "any" -> FieldSchema.Unknown("any")
            else -> FieldSchema.Unknown(type)
        }
    }

    private fun sourceFor(allowed: List<FieldOption>, autoCompleteUrl: String?): SelectSource = when {
        allowed.isNotEmpty() -> SelectSource.AllowedValues
        autoCompleteUrl != null -> SelectSource.AutoCompleteUrl
        else -> SelectSource.AllowedValues
    }

    private fun mapCategory(key: String?): StatusCategory = when (key) {
        "new", "undefined" -> StatusCategory.TO_DO
        "indeterminate" -> StatusCategory.IN_PROGRESS
        "done" -> StatusCategory.DONE
        else -> StatusCategory.UNKNOWN
    }
}

package com.workflow.orchestrator.jira.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.workflow.orchestrator.core.model.jira.FieldOption
import com.workflow.orchestrator.core.model.jira.FieldSchema
import com.workflow.orchestrator.core.model.jira.SelectSource
import com.workflow.orchestrator.core.model.jira.StatusCategory
import com.workflow.orchestrator.core.model.jira.StatusRef
import com.workflow.orchestrator.core.model.jira.TransitionField
import com.workflow.orchestrator.core.model.jira.TransitionMeta

class JiraTransitionResponseParser(private val mapper: ObjectMapper) {

    fun parse(json: String): List<TransitionMeta> {
        val root = mapper.readTree(json)
        val tr = root.get("transitions") ?: return emptyList()
        return tr.map { parseTransition(it) }
    }

    private fun parseTransition(n: JsonNode): TransitionMeta {
        val to = n.get("to")
        val toStatus = StatusRef(
            id = to.get("id").asText(),
            name = to.get("name").asText(),
            category = mapCategory(to.get("statusCategory")?.get("key")?.asText())
        )
        val fieldsNode = n.get("fields")
        val fields = fieldsNode?.fields()?.asSequence()?.map { (id, node) -> parseField(id, node) }?.toList() ?: emptyList()
        return TransitionMeta(
            id = n.get("id").asText(),
            name = n.get("name").asText(),
            toStatus = toStatus,
            hasScreen = fields.isNotEmpty(),
            fields = fields
        )
    }

    private fun parseField(id: String, n: JsonNode): TransitionField {
        val required = n.get("required")?.asBoolean(false) ?: false
        val name = n.get("name")?.asText(id) ?: id
        val schemaNode = n.get("schema")
        val autoCompleteUrl = n.get("autoCompleteUrl")?.asText()
        val allowed = parseAllowedValues(n.get("allowedValues"))
        val schema = mapSchema(schemaNode, allowed, autoCompleteUrl)
        return TransitionField(
            id = id, name = name, required = required,
            schema = schema,
            allowedValues = allowed,
            autoCompleteUrl = autoCompleteUrl,
            defaultValue = null
        )
    }

    private fun parseAllowedValues(n: JsonNode?): List<FieldOption> {
        if (n == null || !n.isArray) return emptyList()
        return n.map { v ->
            val id = v.get("id")?.asText() ?: v.get("value")?.asText() ?: ""
            val display = v.get("name")?.asText() ?: v.get("value")?.asText() ?: id
            FieldOption(id = id, value = display, iconUrl = v.get("iconUrl")?.asText())
        }
    }

    private fun mapSchema(
        schema: JsonNode?,
        allowed: List<FieldOption>,
        autoCompleteUrl: String?
    ): FieldSchema {
        if (schema == null) return FieldSchema.Unknown("missing")
        val type = schema.get("type")?.asText() ?: return FieldSchema.Unknown("missing")
        val items = schema.get("items")?.asText()
        val system = schema.get("system")?.asText()
        val custom = schema.get("custom")?.asText()

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

package com.workflow.orchestrator.core.model.jira

enum class StatusCategory { TO_DO, IN_PROGRESS, DONE, UNKNOWN }

data class StatusRef(val id: String, val name: String, val category: StatusCategory)

data class FieldOption(val id: String, val value: String, val iconUrl: String? = null)

data class TransitionField(
    val id: String,
    val name: String,
    val required: Boolean,
    val schema: FieldSchema,
    val allowedValues: List<FieldOption>,
    val autoCompleteUrl: String?,
    val defaultValue: Any?
)

data class TransitionMeta(
    val id: String,
    val name: String,
    val toStatus: StatusRef,
    val hasScreen: Boolean,
    val fields: List<TransitionField>
)

sealed class FieldSchema {
    object Text : FieldSchema()
    object Number : FieldSchema()
    object Date : FieldSchema()
    object DateTime : FieldSchema()
    object Labels : FieldSchema()
    object Priority : FieldSchema()
    data class SingleSelect(val sourceHint: SelectSource) : FieldSchema()
    data class MultiSelect(val sourceHint: SelectSource) : FieldSchema()
    object CascadingSelect : FieldSchema()
    data class User(val multi: Boolean) : FieldSchema()
    data class Group(val multi: Boolean) : FieldSchema()
    data class Version(val multi: Boolean) : FieldSchema()
    data class Component(val multi: Boolean) : FieldSchema()
    data class Unknown(val rawType: String) : FieldSchema()
}

enum class SelectSource { AllowedValues, AutoCompleteUrl, ProjectLookup }

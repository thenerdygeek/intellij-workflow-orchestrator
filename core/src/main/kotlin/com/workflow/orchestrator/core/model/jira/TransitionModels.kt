package com.workflow.orchestrator.core.model.jira

enum class StatusCategory { TO_DO, IN_PROGRESS, DONE, UNKNOWN }

data class StatusRef(val id: String, val name: String, val category: StatusCategory)

data class FieldOption(val id: String, val value: String, val iconUrl: String? = null)

sealed class FieldValue {
    data class Text(val value: String) : FieldValue()
    data class Number(val value: Double) : FieldValue()
    data class Date(val iso: String) : FieldValue()
    data class DateTime(val iso: String) : FieldValue()
    data class Option(val id: String) : FieldValue()
    data class Options(val ids: List<String>) : FieldValue()
    data class Cascade(val parentId: String, val childId: String?) : FieldValue()
    data class UserRef(val name: String) : FieldValue()
    data class UserRefs(val names: List<String>) : FieldValue()
    data class GroupRef(val name: String) : FieldValue()
    data class VersionRef(val id: String) : FieldValue()
    data class VersionRefs(val ids: List<String>) : FieldValue()
    data class ComponentRef(val id: String) : FieldValue()
    data class ComponentRefs(val ids: List<String>) : FieldValue()
    data class LabelList(val labels: List<String>) : FieldValue()
}

data class TransitionField(
    val id: String,
    val name: String,
    val required: Boolean,
    val schema: FieldSchema,
    val allowedValues: List<FieldOption>,
    val autoCompleteUrl: String?,
    val defaultValue: FieldValue?
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

data class TransitionInput(
    val transitionId: String,
    val fieldValues: Map<String, FieldValue>,
    val comment: String?
)

data class TransitionOutcome(
    val key: String,
    val fromStatus: StatusRef,
    val toStatus: StatusRef,
    val transitionId: String,
    val appliedFields: Map<String, FieldValue>
)

data class MissingFieldsError(
    val kind: String = "missing_required_fields",
    val transitionId: String,
    val transitionName: String,
    val fields: List<TransitionField>,
    val guidance: String
)

sealed class TransitionError {
    data class MissingFields(val payload: MissingFieldsError) : TransitionError()
    data class InvalidTransition(val reason: String) : TransitionError()
    data class RequiresInteraction(val meta: TransitionMeta) : TransitionError()
    data class Network(val cause: Throwable) : TransitionError()
    data class Forbidden(val reason: String) : TransitionError()
}

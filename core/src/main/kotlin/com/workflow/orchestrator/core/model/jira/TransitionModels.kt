package com.workflow.orchestrator.core.model.jira

import kotlinx.serialization.Serializable

@Serializable
enum class StatusCategory { TO_DO, IN_PROGRESS, DONE, UNKNOWN }

@Serializable
data class StatusRef(val id: String, val name: String, val category: StatusCategory)

@Serializable
data class FieldOption(val id: String, val value: String, val iconUrl: String? = null)

@Serializable
sealed class FieldValue {
    @Serializable data class Text(val value: String) : FieldValue()
    @Serializable data class Number(val value: Double) : FieldValue()
    @Serializable data class Date(val iso: String) : FieldValue()
    @Serializable data class DateTime(val iso: String) : FieldValue()
    @Serializable data class Option(val id: String) : FieldValue()
    @Serializable data class Options(val ids: List<String>) : FieldValue()
    @Serializable data class Cascade(val parentId: String, val childId: String?) : FieldValue()
    @Serializable data class UserRef(val name: String) : FieldValue()
    @Serializable data class UserRefs(val names: List<String>) : FieldValue()
    @Serializable data class GroupRef(val name: String) : FieldValue()
    @Serializable data class VersionRef(val id: String) : FieldValue()
    @Serializable data class VersionRefs(val ids: List<String>) : FieldValue()
    @Serializable data class ComponentRef(val id: String) : FieldValue()
    @Serializable data class ComponentRefs(val ids: List<String>) : FieldValue()
    @Serializable data class LabelList(val labels: List<String>) : FieldValue()
}

@Serializable
enum class SelectSource { AllowedValues, AutoCompleteUrl, ProjectLookup }

@Serializable
sealed class FieldSchema {
    @Serializable object Text : FieldSchema()
    @Serializable object Number : FieldSchema()
    @Serializable object Date : FieldSchema()
    @Serializable object DateTime : FieldSchema()
    @Serializable object Labels : FieldSchema()
    @Serializable object Priority : FieldSchema()
    @Serializable data class SingleSelect(val sourceHint: SelectSource) : FieldSchema()
    @Serializable data class MultiSelect(val sourceHint: SelectSource) : FieldSchema()
    @Serializable object CascadingSelect : FieldSchema()
    @Serializable data class User(val multi: Boolean) : FieldSchema()
    @Serializable data class Group(val multi: Boolean) : FieldSchema()
    @Serializable data class Version(val multi: Boolean) : FieldSchema()
    @Serializable data class Component(val multi: Boolean) : FieldSchema()
    @Serializable data class Unknown(val rawType: String) : FieldSchema()
}

@Serializable
data class TransitionField(
    val id: String,
    val name: String,
    val required: Boolean,
    val schema: FieldSchema,
    val allowedValues: List<FieldOption>,
    val autoCompleteUrl: String?,
    val defaultValue: FieldValue?
)

@Serializable
data class TransitionMeta(
    val id: String,
    val name: String,
    val toStatus: StatusRef,
    val hasScreen: Boolean,
    val fields: List<TransitionField>
)

@Serializable
data class TransitionInput(
    val transitionId: String,
    val fieldValues: Map<String, FieldValue>,
    val comment: String?
)

@Serializable
data class TransitionOutcome(
    val key: String,
    val fromStatus: StatusRef,
    val toStatus: StatusRef,
    val transitionId: String,
    val appliedFields: Map<String, FieldValue>
)

@Serializable
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

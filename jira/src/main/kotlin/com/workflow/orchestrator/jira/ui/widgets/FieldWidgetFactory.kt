package com.workflow.orchestrator.jira.ui.widgets

import com.intellij.openapi.diagnostic.Logger
import com.workflow.orchestrator.core.model.jira.FieldSchema
import com.workflow.orchestrator.core.model.jira.FieldValue
import com.workflow.orchestrator.core.model.jira.SelectSource
import com.workflow.orchestrator.core.model.jira.TransitionField

/**
 * Factory that maps a [TransitionField] to the appropriate [FieldWidget] implementation.
 *
 * Dispatch order follows [FieldSchema] exhaustiveness.  The [onChange] callback is forwarded
 * to entity-picker widgets that accept it; simple/option widgets fire their own listeners
 * internally and the caller can poll [FieldWidget.currentValue] at form-submit time.
 */
object FieldWidgetFactory {

    private val LOG = Logger.getInstance(FieldWidgetFactory::class.java)

    fun build(
        field: TransitionField,
        ctx: WidgetContext,
        onChange: (FieldValue?) -> Unit
    ): FieldWidget = when (val s = field.schema) {

        FieldSchema.Text          -> TextFieldWidget(field)
        FieldSchema.Number        -> NumberFieldWidget(field)
        FieldSchema.Date          -> DatePickerWidget(field)
        FieldSchema.DateTime      -> DateTimePickerWidget(field)
        FieldSchema.Labels        -> LabelPickerWidget(field, ctx, onChange)
        FieldSchema.Priority      -> SingleSelectWidget(field)

        is FieldSchema.SingleSelect -> when (s.sourceHint) {
            SelectSource.AllowedValues  -> SingleSelectWidget(field)
            SelectSource.AutoCompleteUrl -> AutoCompleteWidget(field, ctx, multi = false, onChange)
            SelectSource.ProjectLookup  -> error("unexpected SelectSource.ProjectLookup for SingleSelect")
        }

        is FieldSchema.MultiSelect -> when (s.sourceHint) {
            SelectSource.AllowedValues  -> MultiSelectWidget(field)
            SelectSource.AutoCompleteUrl -> AutoCompleteWidget(field, ctx, multi = true, onChange)
            SelectSource.ProjectLookup  -> error("unexpected SelectSource.ProjectLookup for MultiSelect")
        }

        FieldSchema.CascadingSelect -> CascadingSelectWidget(field)

        is FieldSchema.User      -> UserPickerWidget(field, ctx, multi = s.multi, onChange)
        is FieldSchema.Group     -> GroupPickerWidget(field, ctx, multi = s.multi, onChange)
        is FieldSchema.Version   -> VersionPickerWidget(field, ctx, multi = s.multi, onChange)
        is FieldSchema.Component -> ComponentPickerWidget(field, ctx, multi = s.multi, onChange)

        is FieldSchema.Unknown   -> {
            LOG.warn("Unknown Jira field schema: ${s.rawType}, rendering as text")
            TextFieldWidget(field)
        }
    }
}

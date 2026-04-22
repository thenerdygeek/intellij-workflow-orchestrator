package com.workflow.orchestrator.jira.ui.widgets

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.jira.FieldValue
import com.workflow.orchestrator.core.model.jira.TransitionField
import com.workflow.orchestrator.core.services.jira.JiraSearchService
import javax.swing.JComponent

/**
 * Common contract for all transition-field widgets.
 * Each widget renders one [TransitionField] and can report back its current value.
 */
interface FieldWidget {
    /** The field metadata this widget represents. */
    val field: TransitionField

    /** The Swing component to embed in the form. */
    val component: JComponent

    /** Returns the current user-supplied value, or null when the field is empty. */
    fun currentValue(): FieldValue?

    /**
     * Validates the current input.
     * @return null when valid; a human-readable error string when invalid.
     */
    fun validate(): String?

    /** Pre-populates the widget with an existing value (e.g. the field's defaultValue). */
    fun setInitial(value: FieldValue?)
}

/**
 * Shared context passed to every widget so they can reach project services and
 * schedule coroutines that are automatically cancelled when the parent is disposed.
 */
data class WidgetContext(
    val project: Project,
    val ticketKey: String,
    val projectKey: String,
    val search: JiraSearchService,
    val disposable: Disposable
)

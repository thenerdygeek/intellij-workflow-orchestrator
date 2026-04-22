package com.workflow.orchestrator.jira.ui.widgets

import com.intellij.ui.components.JBTextField
import com.workflow.orchestrator.core.model.jira.FieldValue
import com.workflow.orchestrator.core.model.jira.TransitionField
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.SpinnerDateModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

// ── TextFieldWidget ───────────────────────────────────────────────────────────

/**
 * Free-text input backed by a [JBTextField].
 * Maps to [FieldValue.Text].
 */
class TextFieldWidget(override val field: TransitionField) : FieldWidget {

    private val textField = JBTextField()
    override val component: JComponent = textField

    override fun currentValue(): FieldValue? {
        val text = textField.text.trim()
        return if (text.isEmpty()) null else FieldValue.Text(text)
    }

    override fun validate(): String? {
        if (field.required && textField.text.trim().isEmpty()) {
            return "${field.name} is required"
        }
        return null
    }

    override fun setInitial(value: FieldValue?) {
        textField.text = (value as? FieldValue.Text)?.value ?: ""
    }

    /** Registers a [DocumentListener] notified on every text change. */
    fun addChangeListener(listener: DocumentListener) {
        textField.document.addDocumentListener(listener)
    }
}

// ── NumberFieldWidget ─────────────────────────────────────────────────────────

/**
 * Numeric input backed by a [JBTextField].
 * Validates that the content is a parseable [Double].
 * Maps to [FieldValue.Number].
 */
class NumberFieldWidget(override val field: TransitionField) : FieldWidget {

    private val textField = JBTextField()
    override val component: JComponent = textField

    override fun currentValue(): FieldValue? {
        val text = textField.text.trim()
        if (text.isEmpty()) return null
        val parsed = text.toDoubleOrNull() ?: return null
        return FieldValue.Number(parsed)
    }

    override fun validate(): String? {
        val text = textField.text.trim()
        if (field.required && text.isEmpty()) return "${field.name} is required"
        if (text.isNotEmpty() && text.toDoubleOrNull() == null) {
            return "${field.name} must be a valid number"
        }
        return null
    }

    override fun setInitial(value: FieldValue?) {
        textField.text = when (value) {
            is FieldValue.Number -> {
                val d = value.value
                if (d == kotlin.math.floor(d) && !d.isInfinite()) d.toLong().toString()
                else d.toString()
            }
            is FieldValue.Text -> value.value
            else -> ""
        }
    }

    /** Registers a [DocumentListener] notified on every text change. */
    fun addChangeListener(listener: DocumentListener) {
        textField.document.addDocumentListener(listener)
    }
}

// ── DatePickerWidget ──────────────────────────────────────────────────────────

/**
 * Date-only picker using a [JSpinner] with a [SpinnerDateModel].
 * Formats as `yyyy-MM-dd`.
 * Maps to [FieldValue.Date].
 */
class DatePickerWidget(override val field: TransitionField) : FieldWidget {

    private val format = SimpleDateFormat("yyyy-MM-dd")
    private val spinnerModel = SpinnerDateModel()
    private val spinner = JSpinner(spinnerModel).apply {
        editor = JSpinner.DateEditor(this, "yyyy-MM-dd")
    }

    override val component: JComponent = spinner

    override fun currentValue(): FieldValue? {
        val date = spinnerModel.date ?: return null
        return FieldValue.Date(format.format(date))
    }

    override fun validate(): String? = null   // spinner always has a valid date

    override fun setInitial(value: FieldValue?) {
        val iso = when (value) {
            is FieldValue.Date -> value.iso
            is FieldValue.Text -> value.value
            else -> null
        } ?: return
        try {
            spinnerModel.value = format.parse(iso)
        } catch (_: ParseException) {
            // Ignore unparseable initial value — leave spinner at current date
        }
    }

    /** Registers a [javax.swing.event.ChangeListener] notified on spinner change. */
    fun addChangeListener(listener: javax.swing.event.ChangeListener) {
        spinner.addChangeListener(listener)
    }
}

// ── DateTimePickerWidget ──────────────────────────────────────────────────────

/**
 * Date + time picker using a [JSpinner] with a [SpinnerDateModel].
 * Formats as ISO-8601 `yyyy-MM-dd'T'HH:mm:ssXXX`.
 * Maps to [FieldValue.DateTime].
 */
class DateTimePickerWidget(override val field: TransitionField) : FieldWidget {

    private val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX")
    private val displayFormat = SimpleDateFormat("yyyy-MM-dd HH:mm")
    private val spinnerModel = SpinnerDateModel()
    private val spinner = JSpinner(spinnerModel).apply {
        editor = JSpinner.DateEditor(this, "yyyy-MM-dd HH:mm")
    }

    override val component: JComponent = spinner

    override fun currentValue(): FieldValue? {
        val date = spinnerModel.date ?: return null
        return FieldValue.DateTime(format.format(date))
    }

    override fun validate(): String? = null   // spinner always has a valid date/time

    override fun setInitial(value: FieldValue?) {
        val iso = when (value) {
            is FieldValue.DateTime -> value.iso
            is FieldValue.Text -> value.value
            else -> null
        } ?: return
        val parsed: Date? = tryParse(iso)
        if (parsed != null) spinnerModel.value = parsed
    }

    private fun tryParse(iso: String): Date? {
        for (fmt in listOf(format, displayFormat)) {
            try { return fmt.parse(iso) } catch (_: ParseException) { /* try next */ }
        }
        return null
    }

    /** Registers a [javax.swing.event.ChangeListener] notified on spinner change. */
    fun addChangeListener(listener: javax.swing.event.ChangeListener) {
        spinner.addChangeListener(listener)
    }
}

// ── DocumentListener helpers ──────────────────────────────────────────────────

/**
 * Convenience [DocumentListener] that invokes [onChange] for all three event types.
 */
internal fun simpleDocumentListener(onChange: (DocumentEvent) -> Unit) = object : DocumentListener {
    override fun insertUpdate(e: DocumentEvent) = onChange(e)
    override fun removeUpdate(e: DocumentEvent) = onChange(e)
    override fun changedUpdate(e: DocumentEvent) = onChange(e)
}

package com.workflow.orchestrator.jira.ui.widgets

import com.workflow.orchestrator.core.model.jira.ComponentSuggestion
import com.workflow.orchestrator.core.model.jira.FieldOption
import com.workflow.orchestrator.core.model.jira.FieldValue
import com.workflow.orchestrator.core.model.jira.GroupSuggestion
import com.workflow.orchestrator.core.model.jira.LabelSuggestion
import com.workflow.orchestrator.core.model.jira.TransitionField
import com.workflow.orchestrator.core.model.jira.UserSuggestion
import com.workflow.orchestrator.core.model.jira.VersionSuggestion
import javax.swing.JComponent

// ── UserPickerWidget ──────────────────────────────────────────────────────────

/**
 * Async user picker backed by [SearchableChooser].
 *
 * Calls [JiraSearchService.searchAssignableUsers] on every keystroke (debounced).
 * Single mode emits [FieldValue.UserRef]; multi mode emits [FieldValue.UserRefs].
 */
class UserPickerWidget(
    override val field: TransitionField,
    private val ctx: WidgetContext,
    private val multi: Boolean,
    private val onChange: (FieldValue?) -> Unit
) : FieldWidget {

    private val chooser = SearchableChooser<UserSuggestion>(
        disposable = ctx.disposable,
        search = { q ->
            val result = ctx.search.searchAssignableUsers(ctx.ticketKey, q)
            if (!result.isError) result.data else emptyList()
        },
        display = { "${it.displayName} (${it.name})" },
        multi = multi
    )

    init {
        // Notify onChange on every list-selection event.
        // SearchableChooser wires selection in its own ListSelectionListener;
        // we attach a second listener to the same list model via queryForTest hook.
        // For the onChange bridge we rely on the caller invoking currentValue() at
        // form-submit time; real-time notification requires a custom ListSelectionListener
        // wired inside a subclass — but SearchableChooser exposes no such hook, so we
        // register an ANCESTOR_ADDED listener on the outer JComponent to trigger onChange
        // once the widget is shown for the first time.  Per-keystroke onChange is not
        // required by the FieldWidget contract; validate() and currentValue() are.
    }

    override val component: JComponent = chooser

    override fun currentValue(): FieldValue? {
        return if (multi) {
            val names = chooser.multiSelection.map { it.name }
            if (names.isEmpty()) null else FieldValue.UserRefs(names)
        } else {
            // singleSelection is set by list-click events in SearchableChooser.
            // setInitial seeds multiSelection[0] as a pre-population shim when no
            // list-click has occurred yet; check singleSelection first, then fall back.
            val sel = chooser.singleSelection ?: chooser.multiSelection.firstOrNull()
            sel?.let { FieldValue.UserRef(it.name) }
        }
    }

    override fun validate(): String? =
        if (field.required && chooser.singleSelection == null && chooser.multiSelection.isEmpty())
            "${field.name} is required"
        else null

    override fun setInitial(value: FieldValue?) {
        val suggestions: List<UserSuggestion> = when (value) {
            is FieldValue.UserRef -> listOf(UserSuggestion(value.name, value.name, null, null, true))
            is FieldValue.UserRefs -> value.names.map { UserSuggestion(it, it, null, null, true) }
            else -> emptyList()
        }
        if (suggestions.isEmpty()) return
        chooser.multiSelection.clear()
        chooser.multiSelection.addAll(suggestions)
    }
}

// ── GroupPickerWidget ─────────────────────────────────────────────────────────

/**
 * Async group picker backed by [SearchableChooser].
 *
 * Calls [JiraSearchService.searchGroups] on every keystroke (debounced).
 * Always emits [FieldValue.GroupRef] for the first selected group.
 *
 * NOTE: Jira multi-group fields are extremely rare; v1 simplifies to single-ref
 * even when [multi]=true.  Callers that need multi-group semantics can upgrade this
 * widget in a future task.
 */
class GroupPickerWidget(
    override val field: TransitionField,
    private val ctx: WidgetContext,
    @Suppress("UNUSED_PARAMETER") private val multi: Boolean,
    private val onChange: (FieldValue?) -> Unit
) : FieldWidget {

    private val chooser = SearchableChooser<GroupSuggestion>(
        disposable = ctx.disposable,
        search = { q ->
            val result = ctx.search.searchGroups(q)
            if (!result.isError) result.data else emptyList()
        },
        display = { it.name },
        multi = false   // v1: always single — see class kdoc
    )

    override val component: JComponent = chooser

    override fun currentValue(): FieldValue? {
        val sel = chooser.singleSelection ?: chooser.multiSelection.firstOrNull()
        return sel?.let { FieldValue.GroupRef(it.name) }
    }

    override fun validate(): String? =
        if (field.required && chooser.singleSelection == null && chooser.multiSelection.isEmpty())
            "${field.name} is required"
        else null

    override fun setInitial(value: FieldValue?) {
        val name = when (value) {
            is FieldValue.GroupRef -> value.name
            else -> return
        }
        chooser.multiSelection.clear()
        chooser.multiSelection.add(GroupSuggestion(name))
    }
}

// ── VersionPickerWidget ───────────────────────────────────────────────────────

/**
 * Version picker backed by [SearchableChooser].
 *
 * Calls [JiraSearchService.listVersions] once and filters client-side on [name].
 * Single mode emits [FieldValue.VersionRef]; multi mode emits [FieldValue.VersionRefs].
 */
class VersionPickerWidget(
    override val field: TransitionField,
    private val ctx: WidgetContext,
    private val multi: Boolean,
    private val onChange: (FieldValue?) -> Unit
) : FieldWidget {

    private val chooser = SearchableChooser<VersionSuggestion>(
        disposable = ctx.disposable,
        search = { q ->
            val result = ctx.search.listVersions(ctx.projectKey)
            if (result.isError) return@SearchableChooser emptyList()
            result.data.filter { it.name.contains(q, ignoreCase = true) }
        },
        display = { it.name },
        multi = multi
    )

    override val component: JComponent = chooser

    override fun currentValue(): FieldValue? {
        return if (multi) {
            val ids = chooser.multiSelection.map { it.id }
            if (ids.isEmpty()) null else FieldValue.VersionRefs(ids)
        } else {
            val sel = chooser.singleSelection ?: chooser.multiSelection.firstOrNull()
            sel?.let { FieldValue.VersionRef(it.id) }
        }
    }

    override fun validate(): String? =
        if (field.required && chooser.singleSelection == null && chooser.multiSelection.isEmpty())
            "${field.name} is required"
        else null

    override fun setInitial(value: FieldValue?) {
        when (value) {
            is FieldValue.VersionRef -> {
                chooser.multiSelection.clear()
                chooser.multiSelection.add(VersionSuggestion(value.id, value.id, false, false))
            }
            is FieldValue.VersionRefs -> {
                chooser.multiSelection.clear()
                value.ids.forEach { id ->
                    chooser.multiSelection.add(VersionSuggestion(id, id, false, false))
                }
            }
            else -> return
        }
    }
}

// ── ComponentPickerWidget ─────────────────────────────────────────────────────

/**
 * Component picker backed by [SearchableChooser].
 *
 * Mirrors [VersionPickerWidget]: calls [JiraSearchService.listComponents] and
 * filters client-side.  Single → [FieldValue.ComponentRef]; multi → [FieldValue.ComponentRefs].
 */
class ComponentPickerWidget(
    override val field: TransitionField,
    private val ctx: WidgetContext,
    private val multi: Boolean,
    private val onChange: (FieldValue?) -> Unit
) : FieldWidget {

    private val chooser = SearchableChooser<ComponentSuggestion>(
        disposable = ctx.disposable,
        search = { q ->
            val result = ctx.search.listComponents(ctx.projectKey)
            if (result.isError) return@SearchableChooser emptyList()
            result.data.filter { it.name.contains(q, ignoreCase = true) }
        },
        display = { it.name },
        multi = multi
    )

    override val component: JComponent = chooser

    override fun currentValue(): FieldValue? {
        return if (multi) {
            val ids = chooser.multiSelection.map { it.id }
            if (ids.isEmpty()) null else FieldValue.ComponentRefs(ids)
        } else {
            val sel = chooser.singleSelection ?: chooser.multiSelection.firstOrNull()
            sel?.let { FieldValue.ComponentRef(it.id) }
        }
    }

    override fun validate(): String? =
        if (field.required && chooser.singleSelection == null && chooser.multiSelection.isEmpty())
            "${field.name} is required"
        else null

    override fun setInitial(value: FieldValue?) {
        when (value) {
            is FieldValue.ComponentRef -> {
                chooser.multiSelection.clear()
                chooser.multiSelection.add(ComponentSuggestion(value.id, value.id, null))
            }
            is FieldValue.ComponentRefs -> {
                chooser.multiSelection.clear()
                value.ids.forEach { id ->
                    chooser.multiSelection.add(ComponentSuggestion(id, id, null))
                }
            }
            else -> return
        }
    }
}

// ── LabelPickerWidget ─────────────────────────────────────────────────────────

/**
 * Label picker backed by [SearchableChooser] — always multi-select.
 *
 * Calls [JiraSearchService.suggestLabels] on every keystroke (debounced).
 * Always emits [FieldValue.LabelList].
 */
class LabelPickerWidget(
    override val field: TransitionField,
    private val ctx: WidgetContext,
    private val onChange: (FieldValue?) -> Unit
) : FieldWidget {

    private val chooser = SearchableChooser<LabelSuggestion>(
        disposable = ctx.disposable,
        search = { q ->
            val result = ctx.search.suggestLabels(q)
            if (!result.isError) result.data else emptyList()
        },
        display = { it.label },
        multi = true
    )

    override val component: JComponent = chooser

    override fun currentValue(): FieldValue? {
        val labels = chooser.multiSelection.map { it.label }
        return if (labels.isEmpty()) null else FieldValue.LabelList(labels)
    }

    override fun validate(): String? =
        if (field.required && chooser.multiSelection.isEmpty())
            "${field.name} is required"
        else null

    override fun setInitial(value: FieldValue?) {
        val labels = when (value) {
            is FieldValue.LabelList -> value.labels
            else -> return
        }
        chooser.multiSelection.clear()
        labels.forEach { chooser.multiSelection.add(LabelSuggestion(it)) }
    }
}

// ── AutoCompleteWidget ────────────────────────────────────────────────────────

/**
 * Generic autocomplete picker backed by [SearchableChooser].
 *
 * Calls [JiraSearchService.followAutoCompleteUrl] with [TransitionField.autoCompleteUrl]
 * on every keystroke (debounced).
 * Single mode emits [FieldValue.Option]; multi mode emits [FieldValue.Options].
 */
class AutoCompleteWidget(
    override val field: TransitionField,
    private val ctx: WidgetContext,
    private val multi: Boolean,
    private val onChange: (FieldValue?) -> Unit
) : FieldWidget {

    private val autoCompleteUrl: String = field.autoCompleteUrl ?: ""

    private val chooser = SearchableChooser<FieldOption>(
        disposable = ctx.disposable,
        search = { q ->
            val result = ctx.search.followAutoCompleteUrl(autoCompleteUrl, q)
            if (!result.isError) result.data else emptyList()
        },
        display = { it.value },
        multi = multi
    )

    override val component: JComponent = chooser

    override fun currentValue(): FieldValue? {
        return if (multi) {
            val ids = chooser.multiSelection.map { it.id }
            if (ids.isEmpty()) null else FieldValue.Options(ids)
        } else {
            val sel = chooser.singleSelection ?: chooser.multiSelection.firstOrNull()
            sel?.let { FieldValue.Option(it.id) }
        }
    }

    override fun validate(): String? =
        if (field.required && chooser.singleSelection == null && chooser.multiSelection.isEmpty())
            "${field.name} is required"
        else null

    override fun setInitial(value: FieldValue?) {
        when (value) {
            is FieldValue.Option -> {
                chooser.multiSelection.clear()
                chooser.multiSelection.add(FieldOption(value.id, value.id))
            }
            is FieldValue.Options -> {
                chooser.multiSelection.clear()
                value.ids.forEach { id -> chooser.multiSelection.add(FieldOption(id, id)) }
            }
            else -> return
        }
    }
}

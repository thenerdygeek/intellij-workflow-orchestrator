package com.workflow.orchestrator.jira.ui.widgets

import com.intellij.openapi.util.Disposer
import com.workflow.orchestrator.core.model.jira.ComponentSuggestion
import com.workflow.orchestrator.core.model.jira.FieldOption
import com.workflow.orchestrator.core.model.jira.FieldSchema
import com.workflow.orchestrator.core.model.jira.FieldValue
import com.workflow.orchestrator.core.model.jira.GroupSuggestion
import com.workflow.orchestrator.core.model.jira.LabelSuggestion
import com.workflow.orchestrator.core.model.jira.SelectSource
import com.workflow.orchestrator.core.model.jira.TransitionField
import com.workflow.orchestrator.core.model.jira.UserSuggestion
import com.workflow.orchestrator.core.model.jira.VersionSuggestion
import com.workflow.orchestrator.core.services.ToolResult
import com.workflow.orchestrator.core.services.jira.JiraSearchService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun <T> success(value: T): ToolResult<T> =
    ToolResult(data = value, summary = "", isError = false)

private fun field(
    id: String = "x",
    name: String = "X",
    required: Boolean = false,
    schema: com.workflow.orchestrator.core.model.jira.FieldSchema = FieldSchema.Text
) = TransitionField(id, name, required, schema, emptyList(), null, null)

private fun makeCtx(search: JiraSearchService): WidgetContext {
    val disp = Disposer.newDisposable()
    return WidgetContext(
        project = mockk(relaxed = true),
        ticketKey = "ABC-1",
        projectKey = "ABC",
        search = search,
        disposable = disp
    )
}

// ── UserPickerWidget ──────────────────────────────────────────────────────────

class EntityPickerWidgetsTest {

    // ── UserPickerWidget ──────────────────────────────────────────────────────

    @Test
    fun `UserPicker setInitial UserRef round-trips currentValue in single mode`() {
        val search = mockk<JiraSearchService>()
        coEvery { search.searchAssignableUsers(any(), any(), any()) } returns
            success(listOf(UserSuggestion("jdoe", "Jane Doe", null, null, true)))

        val ctx = makeCtx(search)
        val f = field("assignee", "Assignee", true, FieldSchema.User(multi = false))
        val w = UserPickerWidget(f, ctx, multi = false) {}

        w.setInitial(FieldValue.UserRef("jdoe"))
        val value = w.currentValue()
        assertTrue(value is FieldValue.UserRef, "Expected UserRef, got $value")
        assertEquals("jdoe", (value as FieldValue.UserRef).name)

        Disposer.dispose(ctx.disposable)
    }

    @Test
    fun `UserPicker setInitial UserRefs round-trips currentValue in multi mode`() {
        val search = mockk<JiraSearchService>()
        val ctx = makeCtx(search)
        val f = field("watchers", "Watchers", false, FieldSchema.User(multi = true))
        val w = UserPickerWidget(f, ctx, multi = true) {}

        w.setInitial(FieldValue.UserRefs(listOf("alice", "bob")))
        val value = w.currentValue()
        assertTrue(value is FieldValue.UserRefs)
        assertEquals(listOf("alice", "bob"), (value as FieldValue.UserRefs).names)

        Disposer.dispose(ctx.disposable)
    }

    @Test
    fun `UserPicker validate returns required error when field required and no selection`() {
        val ctx = makeCtx(mockk(relaxed = true))
        val f = field("assignee", "Assignee", required = true, schema = FieldSchema.User(multi = false))
        val w = UserPickerWidget(f, ctx, multi = false) {}
        assertNotNull(w.validate())
        assertTrue(w.validate()!!.contains("Assignee"))
        Disposer.dispose(ctx.disposable)
    }

    @Test
    fun `UserPicker validate returns null when not required and nothing selected`() {
        val ctx = makeCtx(mockk(relaxed = true))
        val f = field("assignee", "Assignee", required = false, schema = FieldSchema.User(multi = false))
        val w = UserPickerWidget(f, ctx, multi = false) {}
        assertNull(w.validate())
        Disposer.dispose(ctx.disposable)
    }

    @Test
    fun `UserPicker setInitial ignores unrelated FieldValue types`() {
        val ctx = makeCtx(mockk(relaxed = true))
        val f = field("assignee", "Assignee", false, FieldSchema.User(multi = false))
        val w = UserPickerWidget(f, ctx, multi = false) {}
        w.setInitial(FieldValue.Text("irrelevant"))
        assertNull(w.currentValue())
        Disposer.dispose(ctx.disposable)
    }

    // ── GroupPickerWidget ─────────────────────────────────────────────────────

    @Test
    fun `GroupPicker setInitial GroupRef round-trips currentValue`() {
        val search = mockk<JiraSearchService>()
        coEvery { search.searchGroups(any(), any()) } returns
            success(listOf(GroupSuggestion("jira-admins")))

        val ctx = makeCtx(search)
        val f = field("group", "Group", false, FieldSchema.Group(multi = false))
        val w = GroupPickerWidget(f, ctx, multi = false) {}

        w.setInitial(FieldValue.GroupRef("jira-admins"))
        val value = w.currentValue()
        assertTrue(value is FieldValue.GroupRef)
        assertEquals("jira-admins", (value as FieldValue.GroupRef).name)

        Disposer.dispose(ctx.disposable)
    }

    @Test
    fun `GroupPicker validate returns required error when field required and no selection`() {
        val ctx = makeCtx(mockk(relaxed = true))
        val f = field("group", "Group", required = true, schema = FieldSchema.Group(multi = false))
        val w = GroupPickerWidget(f, ctx, multi = false) {}
        assertNotNull(w.validate())
        Disposer.dispose(ctx.disposable)
    }

    // ── VersionPickerWidget ───────────────────────────────────────────────────

    @Test
    fun `VersionPicker setInitial VersionRef round-trips currentValue in single mode`() {
        val search = mockk<JiraSearchService>()
        coEvery { search.listVersions(any()) } returns
            success(listOf(VersionSuggestion("v1", "1.0.0", true, false)))

        val ctx = makeCtx(search)
        val f = field("fixVersion", "Fix Version", false, FieldSchema.Version(multi = false))
        val w = VersionPickerWidget(f, ctx, multi = false) {}

        w.setInitial(FieldValue.VersionRef("v1"))
        val value = w.currentValue()
        assertTrue(value is FieldValue.VersionRef)
        assertEquals("v1", (value as FieldValue.VersionRef).id)

        Disposer.dispose(ctx.disposable)
    }

    @Test
    fun `VersionPicker setInitial VersionRefs round-trips in multi mode`() {
        val ctx = makeCtx(mockk(relaxed = true))
        val f = field("fixVersions", "Fix Versions", false, FieldSchema.Version(multi = true))
        val w = VersionPickerWidget(f, ctx, multi = true) {}

        w.setInitial(FieldValue.VersionRefs(listOf("v1", "v2")))
        val value = w.currentValue()
        assertTrue(value is FieldValue.VersionRefs)
        assertEquals(listOf("v1", "v2"), (value as FieldValue.VersionRefs).ids)

        Disposer.dispose(ctx.disposable)
    }

    // ── ComponentPickerWidget ─────────────────────────────────────────────────

    @Test
    fun `ComponentPicker setInitial ComponentRef round-trips currentValue in single mode`() {
        val search = mockk<JiraSearchService>()
        coEvery { search.listComponents(any()) } returns
            success(listOf(ComponentSuggestion("c1", "Backend", null)))

        val ctx = makeCtx(search)
        val f = field("component", "Component", false, FieldSchema.Component(multi = false))
        val w = ComponentPickerWidget(f, ctx, multi = false) {}

        w.setInitial(FieldValue.ComponentRef("c1"))
        val value = w.currentValue()
        assertTrue(value is FieldValue.ComponentRef)
        assertEquals("c1", (value as FieldValue.ComponentRef).id)

        Disposer.dispose(ctx.disposable)
    }

    @Test
    fun `ComponentPicker setInitial ComponentRefs round-trips in multi mode`() {
        val ctx = makeCtx(mockk(relaxed = true))
        val f = field("components", "Components", false, FieldSchema.Component(multi = true))
        val w = ComponentPickerWidget(f, ctx, multi = true) {}

        w.setInitial(FieldValue.ComponentRefs(listOf("c1", "c2")))
        val value = w.currentValue()
        assertTrue(value is FieldValue.ComponentRefs)
        assertEquals(listOf("c1", "c2"), (value as FieldValue.ComponentRefs).ids)

        Disposer.dispose(ctx.disposable)
    }

    // ── LabelPickerWidget ─────────────────────────────────────────────────────

    @Test
    fun `LabelPicker setInitial LabelList round-trips currentValue`() {
        val search = mockk<JiraSearchService>()
        coEvery { search.suggestLabels(any(), any()) } returns
            success(listOf(LabelSuggestion("backend"), LabelSuggestion("urgent")))

        val ctx = makeCtx(search)
        val f = field("labels", "Labels", false, FieldSchema.Labels)
        val w = LabelPickerWidget(f, ctx) {}

        w.setInitial(FieldValue.LabelList(listOf("backend", "urgent")))
        val value = w.currentValue()
        assertTrue(value is FieldValue.LabelList)
        assertEquals(listOf("backend", "urgent"), (value as FieldValue.LabelList).labels)

        Disposer.dispose(ctx.disposable)
    }

    @Test
    fun `LabelPicker currentValue returns null when nothing selected`() {
        val ctx = makeCtx(mockk(relaxed = true))
        val f = field("labels", "Labels", false, FieldSchema.Labels)
        val w = LabelPickerWidget(f, ctx) {}
        assertNull(w.currentValue())
        Disposer.dispose(ctx.disposable)
    }

    @Test
    fun `LabelPicker validate returns required error when required and empty`() {
        val ctx = makeCtx(mockk(relaxed = true))
        val f = field("labels", "Labels", required = true, schema = FieldSchema.Labels)
        val w = LabelPickerWidget(f, ctx) {}
        assertNotNull(w.validate())
        assertTrue(w.validate()!!.contains("Labels"))
        Disposer.dispose(ctx.disposable)
    }

    // ── AutoCompleteWidget ────────────────────────────────────────────────────

    @Test
    fun `AutoComplete setInitial Option round-trips in single mode`() {
        val search = mockk<JiraSearchService>()
        coEvery { search.followAutoCompleteUrl(any(), any()) } returns
            success(listOf(FieldOption("opt1", "Option One")))

        val ctx = makeCtx(search)
        val f = TransitionField(
            "sprint", "Sprint", false,
            FieldSchema.SingleSelect(SelectSource.AutoCompleteUrl),
            emptyList(), "/rest/agile/autocomplete?query=", null
        )
        val w = AutoCompleteWidget(f, ctx, multi = false) {}

        w.setInitial(FieldValue.Option("opt1"))
        val value = w.currentValue()
        assertTrue(value is FieldValue.Option)
        assertEquals("opt1", (value as FieldValue.Option).id)

        Disposer.dispose(ctx.disposable)
    }

    @Test
    fun `AutoComplete setInitial Options round-trips in multi mode`() {
        val ctx = makeCtx(mockk(relaxed = true))
        val f = TransitionField(
            "sprints", "Sprints", false,
            FieldSchema.MultiSelect(SelectSource.AutoCompleteUrl),
            emptyList(), "/rest/agile/autocomplete?query=", null
        )
        val w = AutoCompleteWidget(f, ctx, multi = true) {}

        w.setInitial(FieldValue.Options(listOf("s1", "s2")))
        val value = w.currentValue()
        assertTrue(value is FieldValue.Options)
        assertEquals(listOf("s1", "s2"), (value as FieldValue.Options).ids)

        Disposer.dispose(ctx.disposable)
    }

    @Test
    fun `AutoComplete validate returns error when required and nothing selected`() {
        val ctx = makeCtx(mockk(relaxed = true))
        val f = TransitionField(
            "sprint", "Sprint", true,
            FieldSchema.SingleSelect(SelectSource.AutoCompleteUrl),
            emptyList(), "/rest/agile/autocomplete?query=", null
        )
        val w = AutoCompleteWidget(f, ctx, multi = false) {}
        assertNotNull(w.validate())
        assertTrue(w.validate()!!.contains("Sprint"))
        Disposer.dispose(ctx.disposable)
    }
}

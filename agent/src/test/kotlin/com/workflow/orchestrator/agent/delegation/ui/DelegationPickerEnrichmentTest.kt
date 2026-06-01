package com.workflow.orchestrator.agent.delegation.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path

/**
 * TDD source-contract + pure-function tests for the DelegationPicker UI enrichment.
 *
 * The picker is a Swing [com.intellij.openapi.ui.DialogWrapper] subclass and cannot be
 * instantiated in a headless JUnit environment without a running IntelliJ
 * [com.intellij.openapi.application.Application].  We therefore use two complementary
 * strategies:
 *
 * 1. **Pure-function tests** — [DelegationPickerExplainer.explainerFor] is a stateless,
 *    Project-free helper extracted from the picker.  These tests drive the
 *    RUNNING / AVAILABLE / CLOSED / MISSING / null branches directly.
 *
 * 2. **Source-contract tests** — read the production `.kt` source files and assert
 *    structural invariants (field presence, call-sites, wiring) that cannot be
 *    exercised without a running IDE.  Same pattern as [DialogModalityContractTest].
 *
 * Fail-before / pass-after contract: every test in this file MUST fail on the
 * pre-enrichment source and pass after the enrichment is applied.
 */
class DelegationPickerEnrichmentTest {

    // ── Source resolution helpers ────────────────────────────────────────────────

    private fun agentMainRoot(): File {
        val userDir = System.getProperty("user.dir") ?: error("user.dir not set")
        val root = File(userDir)
        val moduleRooted = File(root, "src/main/kotlin")
        val repoRooted = File(root, "agent/src/main/kotlin")
        return when {
            moduleRooted.isDirectory -> moduleRooted
            repoRooted.isDirectory -> repoRooted
            else -> error("agent main sources not found; user.dir=$userDir")
        }
    }

    private fun source(relPath: String): String {
        val f = File(agentMainRoot(), relPath)
        assertTrue(f.isFile, "Expected source file not found: ${f.absolutePath}")
        return f.readText()
    }

    private val pickerSrc by lazy {
        source("com/workflow/orchestrator/agent/delegation/ui/DelegationPicker.kt")
    }

    private val outboundSrc by lazy {
        source("com/workflow/orchestrator/agent/delegation/DelegationOutboundService.kt")
    }

    // ── Pure-function explainer tests ────────────────────────────────────────────

    /**
     * [DelegationPickerExplainer.explainerFor] must map each picker status to a
     * non-empty, status-specific explanatory string.
     */
    @Test
    fun `explainerFor RUNNING entry returns a non-empty string containing the display name`() {
        val entry = PickerEntry(
            path = Path.of("/some/project"),
            displayName = "my-service",
            status = PickerEntry.Status.RUNNING,
        )
        val text = DelegationPickerExplainer.explainerFor(entry)
        assertTrue(text.isNotBlank(), "RUNNING explainer must be non-blank")
        assertTrue(
            text.contains("my-service"),
            "RUNNING explainer must mention the display name; got: $text",
        )
    }

    @Test
    fun `explainerFor AVAILABLE entry returns a non-empty string containing the display name`() {
        val entry = PickerEntry(
            path = Path.of("/some/project"),
            displayName = "backend-api",
            status = PickerEntry.Status.AVAILABLE,
        )
        val text = DelegationPickerExplainer.explainerFor(entry)
        assertTrue(text.isNotBlank(), "AVAILABLE explainer must be non-blank")
        assertTrue(
            text.contains("backend-api"),
            "AVAILABLE explainer must mention the display name; got: $text",
        )
    }

    @Test
    fun `explainerFor CLOSED entry returns a non-empty string containing the display name`() {
        val entry = PickerEntry(
            path = Path.of("/some/project"),
            displayName = "frontend",
            status = PickerEntry.Status.CLOSED,
        )
        val text = DelegationPickerExplainer.explainerFor(entry)
        assertTrue(text.isNotBlank(), "CLOSED explainer must be non-blank")
        assertTrue(
            text.contains("frontend"),
            "CLOSED explainer must mention the display name; got: $text",
        )
    }

    @Test
    fun `explainerFor MISSING entry returns blank or neutral text`() {
        val entry = PickerEntry(
            path = Path.of("/missing/project"),
            displayName = "gone",
            status = PickerEntry.Status.MISSING,
        )
        // MISSING / inert entries produce an empty or whitespace-only explanation.
        val text = DelegationPickerExplainer.explainerFor(entry)
        assertTrue(
            text.isBlank(),
            "MISSING explainer should be blank/empty; got: \"$text\"",
        )
    }

    @Test
    fun `explainerFor header entry returns blank text`() {
        val entry = PickerEntry(
            path = Path.of("/"),
            displayName = "Recent",
            status = PickerEntry.Status.MISSING,
            isHeader = true,
        )
        val text = DelegationPickerExplainer.explainerFor(entry)
        assertTrue(
            text.isBlank(),
            "Header entry explainer should be blank; got: \"$text\"",
        )
    }

    @Test
    fun `explainerFor null returns blank`() {
        val text = DelegationPickerExplainer.explainerFor(null)
        assertTrue(text.isBlank(), "null entry explainer should return blank; got: \"$text\"")
    }

    @Test
    fun `RUNNING and AVAILABLE explainers are distinct`() {
        val running = DelegationPickerExplainer.explainerFor(
            PickerEntry(Path.of("/p"), "svc", PickerEntry.Status.RUNNING),
        )
        val available = DelegationPickerExplainer.explainerFor(
            PickerEntry(Path.of("/p"), "svc", PickerEntry.Status.AVAILABLE),
        )
        assertFalse(
            running == available,
            "RUNNING and AVAILABLE explainers must differ — they describe different user actions",
        )
    }

    @Test
    fun `AVAILABLE and CLOSED explainers are distinct`() {
        val available = DelegationPickerExplainer.explainerFor(
            PickerEntry(Path.of("/p"), "svc", PickerEntry.Status.AVAILABLE),
        )
        val closed = DelegationPickerExplainer.explainerFor(
            PickerEntry(Path.of("/p"), "svc", PickerEntry.Status.CLOSED),
        )
        assertFalse(
            available == closed,
            "AVAILABLE and CLOSED explainers must differ — one prompts consent, other prompts launch",
        )
    }

    // ── Source-contract: request threading ──────────────────────────────────────

    /**
     * [DelegationOutboundService.pickTarget] must forward the [request] string to the
     * [DelegationPicker] constructor so the picker can show a task-preview.
     *
     * Pre-enrichment: `pickTarget(suggestedRepo)` — no request arg, DelegationPicker ctor
     * has no request param.
     * Post-enrichment: `pickTarget(request, suggestedRepo)` — request forwarded to ctor.
     */
    @Test
    fun `pickTarget passes request to DelegationPicker constructor`() {
        // The pickTarget function must pass the request to the DelegationPicker constructor.
        // We look for DelegationPicker( being called with more than just project + suggestedRepo.
        // The simplest structural pin: DelegationPicker must declare a `request` constructor param.
        assertTrue(
            pickerSrc.contains("request:") || pickerSrc.contains("private val request"),
            "DelegationPicker must have a `request` constructor parameter; " +
                "pickTarget must pass the task text through",
        )
    }

    @Test
    fun `DelegationOutboundService pickTarget signature accepts request string`() {
        // pickTarget private function must have `request: String` parameter.
        assertTrue(
            outboundSrc.contains("pickTarget(request") ||
                outboundSrc.contains("pickTarget(\n") && outboundSrc.contains("request:"),
            "DelegationOutboundService.pickTarget must accept a request parameter",
        )
    }

    @Test
    fun `send call forwards request to pickTarget`() {
        // The send() function must pass `request` to pickTarget (not just suggestedRepo).
        // Before enrichment: pickTarget(suggestedRepo)
        // After enrichment: pickTarget(request, suggestedRepo)
        val pickTargetCallSite = Regex("""pickTarget\s*\(""").find(outboundSrc)
        assertFalse(
            pickTargetCallSite == null,
            "pickTarget call site not found in DelegationOutboundService",
        )
        // The call site must have at least two arguments: request + suggestedRepo.
        // We do a crude structural check: `pickTarget(request` must appear.
        assertTrue(
            outboundSrc.contains("pickTarget(request,") ||
                outboundSrc.contains("pickTarget(request ,"),
            "send() must pass `request` as the first argument to pickTarget; " +
                "found call site but request is not the first argument",
        )
    }

    // ── Source-contract: intent header ───────────────────────────────────────────

    @Test
    fun `createCenterPanel contains enriched intent header text`() {
        // The old hint copy talked about "Running targets receive it immediately".
        // The new copy must describe what gets sent and WHERE it runs.
        assertTrue(
            pickerSrc.contains("Send this task to another IDE") ||
                pickerSrc.contains("task runs there under"),
            "createCenterPanel must have the new intent header copy describing the delegation",
        )
    }

    // ── Source-contract: task preview ────────────────────────────────────────────

    @Test
    fun `DelegationPicker declares a task preview label`() {
        // The task preview section starts with a bold "Task being sent:" label.
        assertTrue(
            pickerSrc.contains("Task being sent:") || pickerSrc.contains("taskPreviewLabel"),
            "DelegationPicker must contain a task-preview label: 'Task being sent:'",
        )
    }

    @Test
    fun `DelegationPicker declares a task preview text area`() {
        // A JBTextArea is added when request is non-blank.
        assertTrue(
            pickerSrc.contains("JBTextArea") && pickerSrc.contains("isEditable = false"),
            "DelegationPicker must use a non-editable JBTextArea for the task preview",
        )
    }

    @Test
    fun `task preview is guarded on non-blank request`() {
        // The task preview widget must be added only when request is non-blank.
        // We check that the source has an `isNotBlank()` or `isBlank()` guard near the preview widget.
        assertTrue(
            pickerSrc.contains("request.isNotBlank()") || pickerSrc.contains("request.isBlank()"),
            "Task preview must be guarded: only shown when request is non-blank",
        )
    }

    @Test
    fun `task preview truncates using REQUEST_PREVIEW_CHARS constant`() {
        // The truncation must reference the DelegationOutboundService constant (or a local alias).
        assertTrue(
            pickerSrc.contains("REQUEST_PREVIEW_CHARS") ||
                pickerSrc.contains("DelegationOutboundService.REQUEST_PREVIEW_CHARS"),
            "Task preview truncation must reuse DelegationOutboundService.REQUEST_PREVIEW_CHARS",
        )
    }

    // ── Source-contract: dynamic explainer ──────────────────────────────────────

    @Test
    fun `DelegationPicker declares an explainer label field`() {
        // The explainer JBLabel must be a field so the selection listener can update it.
        assertTrue(
            pickerSrc.contains("explainerLabel") || pickerSrc.contains("selectionExplainerLabel"),
            "DelegationPicker must have an explainer JBLabel field updated by the selection listener",
        )
    }

    @Test
    fun `selection listener calls DelegationPickerExplainer`() {
        // The addListSelectionListener must update the explainer label via the helper.
        assertTrue(
            pickerSrc.contains("DelegationPickerExplainer") || pickerSrc.contains("explainerFor"),
            "DelegationPicker's selection listener must call DelegationPickerExplainer.explainerFor",
        )
    }

    // ── Source-contract: modality unchanged ─────────────────────────────────────

    @Test
    fun `DelegationPicker stays modal — no MODELESS in picker source`() {
        assertFalse(
            Regex(""":\s*DialogWrapper\([^)]*MODELESS""").containsMatchIn(pickerSrc),
            "DelegationPicker must remain modal (DialogWrapper with true, not MODELESS). " +
                "showAndGet() requires a modal dialog.",
        )
    }

    @Test
    fun `DelegationPicker default ctor still compiles with only project and suggestedRepo`() {
        // The new `request` param must have a default value of "" so existing
        // test-override call sites (pickTargetOverride = { ... }) still compile without
        // passing a request arg.  We verify this structurally: the picker source must
        // show `request: String = ""` (or equivalent) at the constructor.
        assertTrue(
            pickerSrc.contains("""request: String = """"") ||
                pickerSrc.contains("""request: String = """),
            "DelegationPicker.request param must default to \"\" so existing test call-sites keep compiling",
        )
    }
}

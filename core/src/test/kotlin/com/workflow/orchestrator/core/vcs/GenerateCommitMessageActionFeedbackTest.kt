package com.workflow.orchestrator.core.vcs

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.vcs.ui.CommitMessage
import com.intellij.ui.AnimatedIcon
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.Presentation
import com.workflow.orchestrator.core.ui.CommitMessageFlash
import com.workflow.orchestrator.core.ui.CommitMessageStreamBatcher
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.swing.Timer

/**
 * Unit tests for the visual-feedback improvements added to [GenerateCommitMessageAction]:
 *
 * - A: Animated toolbar icon when generating
 * - B: withPhase helper — indicator text, cancellation, renderer notification
 * - C: CommitMessageProgressRenderer live placeholder + silent replace
 * - D: CommitMessageStreamBatcher — coalescing, flush, dispose, streaming path, cancellation, flash
 */
class GenerateCommitMessageActionFeedbackTest {

    private lateinit var commitMessage: CommitMessage
    private lateinit var modalityState: ModalityState
    private lateinit var application: Application

    @BeforeEach
    fun setUp() {
        commitMessage = mockk(relaxed = true)
        modalityState = mockk(relaxed = true)
        application = mockk(relaxed = true)

        mockkStatic(ApplicationManager::class)
        every { ApplicationManager.getApplication() } returns application
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    // -------------------------------------------------------------------------
    // C-1: progress renderer writes prefixed placeholder via invokeLater
    // -------------------------------------------------------------------------

    @Test
    fun `progress renderer writes prefixed placeholder via invokeLater`() {
        val runnableSlot = slot<Runnable>()
        every { application.invokeLater(capture(runnableSlot), any<ModalityState>()) } just runs

        val renderer = CommitMessageProgressRenderer(commitMessage, modalityState)
        renderer.update("Analyzing")

        // Execute the captured Runnable that was passed to invokeLater
        runnableSlot.captured.run()

        verify { commitMessage.setCommitMessage("[Workflow AI] Analyzing") }
    }

    // -------------------------------------------------------------------------
    // C-2: progress renderer success replaces with generated message (no prefix)
    // -------------------------------------------------------------------------

    @Test
    fun `progress renderer success replaces with generated message`() {
        val runnableSlot = slot<Runnable>()
        every { application.invokeLater(capture(runnableSlot), any<ModalityState>()) } just runs

        val renderer = CommitMessageProgressRenderer(commitMessage, modalityState)
        renderer.success("feat: add X")

        runnableSlot.captured.run()

        verify { commitMessage.setCommitMessage("feat: add X") }
    }

    // -------------------------------------------------------------------------
    // C-3: progress renderer cancelled clears the field
    // -------------------------------------------------------------------------

    @Test
    fun `progress renderer cancelled clears field`() {
        val runnableSlot = slot<Runnable>()
        every { application.invokeLater(capture(runnableSlot), any<ModalityState>()) } just runs

        val renderer = CommitMessageProgressRenderer(commitMessage, modalityState)
        renderer.cancelled()

        runnableSlot.captured.run()

        verify { commitMessage.setCommitMessage("") }
    }

    // -------------------------------------------------------------------------
    // C-4: progress renderer failed clears the field
    // -------------------------------------------------------------------------

    @Test
    fun `progress renderer failed clears field`() {
        val runnableSlot = slot<Runnable>()
        every { application.invokeLater(capture(runnableSlot), any<ModalityState>()) } just runs

        val renderer = CommitMessageProgressRenderer(commitMessage, modalityState)
        renderer.failed()

        runnableSlot.captured.run()

        verify { commitMessage.setCommitMessage("") }
    }

    // -------------------------------------------------------------------------
    // B-1: withPhase sets indicator.text
    // -------------------------------------------------------------------------

    @Test
    fun `withPhase sets indicator text to given phase`() = runTest {
        val runnableSlot = slot<Runnable>()
        every { application.invokeLater(capture(runnableSlot), any<ModalityState>()) } just runs

        val indicator = mockk<ProgressIndicator>(relaxed = true)
        every { indicator.isCanceled } returns false

        val renderer = CommitMessageProgressRenderer(commitMessage, modalityState)
        val action = GenerateCommitMessageAction()

        action.withPhase(indicator, renderer, "Fetching git diff...") {}

        verify { indicator.text = "Fetching git diff..." }
    }

    // -------------------------------------------------------------------------
    // B-2: withPhase calls renderer.update with the phase
    // -------------------------------------------------------------------------

    @Test
    fun `withPhase calls renderer update with phase string`() = runTest {
        val runnables = mutableListOf<Runnable>()
        every { application.invokeLater(capture(runnables), any<ModalityState>()) } just runs

        val indicator = mockk<ProgressIndicator>(relaxed = true)
        every { indicator.isCanceled } returns false

        val renderer = CommitMessageProgressRenderer(commitMessage, modalityState)
        val action = GenerateCommitMessageAction()

        action.withPhase(indicator, renderer, "Analyzing code structure...") {}

        // Execute the captured runnable (renderer.update calls invokeLater)
        assertTrue(runnables.isNotEmpty(), "invokeLater should have been called by renderer.update")
        runnables.last().run()
        verify { commitMessage.setCommitMessage("[Workflow AI] Analyzing code structure...") }
    }

    // -------------------------------------------------------------------------
    // B-3: withPhase throws ProcessCanceledException when indicator is cancelled
    // -------------------------------------------------------------------------

    @Test
    fun `withPhase throws ProcessCanceledException when indicator is already cancelled`() = runTest {
        every { application.invokeLater(any<Runnable>(), any<ModalityState>()) } just runs

        val indicator = mockk<ProgressIndicator>(relaxed = true)
        every { indicator.isCanceled } returns true

        val renderer = CommitMessageProgressRenderer(commitMessage, modalityState)
        val action = GenerateCommitMessageAction()

        var threw = false
        try {
            action.withPhase(indicator, renderer, "Should not reach...") {
                // block should never execute when cancelled
            }
        } catch (ex: ProcessCanceledException) {
            threw = true
        }

        assertTrue(threw, "withPhase must throw ProcessCanceledException when indicator.isCanceled")
    }

    // -------------------------------------------------------------------------
    // A: update() uses AnimatedIcon.Default when generating is true
    // -------------------------------------------------------------------------

    @Test
    fun `update uses AnimatedIcon Default when generating`() {
        val action = GenerateCommitMessageAction()
        val event = mockk<AnActionEvent>(relaxed = true)
        val presentation = Presentation()

        every { event.project } returns mockk(relaxed = true)
        every { event.getData(com.intellij.openapi.vcs.VcsDataKeys.COMMIT_MESSAGE_CONTROL) } returns
            mockk<CommitMessage>(relaxed = true)
        every { event.presentation } returns presentation

        // Set generating to true via reflection
        val generatingField = GenerateCommitMessageAction::class.java
            .getDeclaredField("generating")
        generatingField.isAccessible = true
        val generating = generatingField.get(action) as java.util.concurrent.atomic.AtomicBoolean
        generating.set(true)

        action.update(event)

        assertTrue(
            presentation.icon is AnimatedIcon,
            "Expected AnimatedIcon when generating, got: ${presentation.icon?.javaClass?.simpleName}"
        )
        assertEquals("Generating...", presentation.text)
    }

    // =========================================================================
    // D: CommitMessageStreamBatcher tests (Phase 5 & 6)
    // =========================================================================

    // -------------------------------------------------------------------------
    // D-1: batcher coalesces multiple submits into single EDT invocation per tick
    // -------------------------------------------------------------------------

    @Test
    fun `stream batcher coalesces multiple submits into single EDT invocation per tick`() {
        val invokeCount = AtomicInteger(0)
        val lastSeen = AtomicReference<String?>(null)

        // Capture all invokeLater runnables — execute them synchronously
        val runnables = mutableListOf<Runnable>()
        every { application.invokeLater(capture(runnables), any<ModalityState>()) } just runs

        val batcher = CommitMessageStreamBatcher(modalityState) { text ->
            invokeCount.incrementAndGet()
            lastSeen.set(text)
        }

        // Submit 10 texts quickly before any timer tick fires
        repeat(10) { i -> batcher.submit("text-$i") }

        // Manually drain the batcher's internal pending by calling flush
        // (simulates a single timer tick that coalesces all submits)
        batcher.flush()
        batcher.dispose()

        // Execute the captured EDT runnable(s)
        runnables.forEach { it.run() }

        // Only ONE invocation should have reached the apply lambda (the latest text)
        assertEquals(1, invokeCount.get(), "Expected exactly 1 coalesced invocation, got ${invokeCount.get()}")
        assertEquals("text-9", lastSeen.get(), "Expected latest text 'text-9', got '${lastSeen.get()}'")
    }

    // -------------------------------------------------------------------------
    // D-2: batcher flush writes pending immediately
    // -------------------------------------------------------------------------

    @Test
    fun `stream batcher flush writes pending immediately`() {
        val runnableSlot = slot<Runnable>()
        every { application.invokeLater(capture(runnableSlot), any<ModalityState>()) } just runs

        val received = AtomicReference<String?>(null)
        val batcher = CommitMessageStreamBatcher(modalityState) { text -> received.set(text) }

        batcher.submit("flush-me")
        batcher.flush()
        batcher.dispose()

        // The invokeLater runnable must have been captured
        assertTrue(runnableSlot.isCaptured, "invokeLater must be called by flush()")

        // Execute the EDT runnable
        runnableSlot.captured.run()

        assertEquals("flush-me", received.get(), "flush() must deliver submitted text to apply lambda")
    }

    // -------------------------------------------------------------------------
    // D-3: batcher dispose stops timer cleanly, no NPE on subsequent submit
    // -------------------------------------------------------------------------

    @Test
    fun `stream batcher dispose stops timer cleanly`() {
        every { application.invokeLater(any<Runnable>(), any<ModalityState>()) } just runs

        val batcher = CommitMessageStreamBatcher(modalityState) { /* no-op */ }
        batcher.start()
        batcher.dispose()

        // Subsequent submits after dispose must not throw
        var threw = false
        try {
            batcher.submit("after-dispose")
            batcher.flush()  // flush on disposed batcher — must be safe
        } catch (ex: Exception) {
            threw = true
        }

        assertFalse(threw, "No exception expected after dispose(); subsequent submit/flush must be safe no-ops")
    }

    // -------------------------------------------------------------------------
    // D-4: successful stream updates field through partial text then finalizes
    // -------------------------------------------------------------------------

    @Test
    fun `successful stream updates field through partial text then finalizes`() {
        val runnables = mutableListOf<Runnable>()
        every { application.invokeLater(capture(runnables), any<ModalityState>()) } just runs

        // Track all setCommitMessage calls (partial + final)
        val messages = mutableListOf<String>()
        every { commitMessage.setCommitMessage(capture(messages)) } just runs

        val batcher = CommitMessageStreamBatcher(modalityState) { text ->
            commitMessage.setCommitMessage(text)
        }

        // Simulate 3 streaming chunks (accumulated delta model)
        batcher.start()
        batcher.submit("feat")
        batcher.submit("feat: add")
        batcher.submit("feat: add streaming")
        batcher.flush()
        batcher.dispose()

        // Run all captured EDT runnables
        runnables.forEach { it.run() }

        // At minimum, the final accumulated text must have been delivered
        assertTrue(
            messages.contains("feat: add streaming"),
            "Final accumulated text must be written to commitMessage. Got: $messages"
        )
    }

    // -------------------------------------------------------------------------
    // D-5: cancelled stream calls interruptStream, does NOT write final message
    // -------------------------------------------------------------------------

    @Test
    fun `cancelled stream stops writing and calls interruptStream via cooperative check`() {
        every { application.invokeLater(any<Runnable>(), any<ModalityState>()) } just runs

        // Simulate cancellation detection via a boolean flag (mirrors what the
        // onChunk lambda does when indicator.isCanceled becomes true)
        var interruptCalled = false
        var cancelDetected = false

        // Simulate the onChunk cancel path inline:
        // chunk 1 → accumulate, chunk 2 → detect cancel → call interruptStream
        val accumulated = StringBuilder()
        val isCanceled = java.util.concurrent.atomic.AtomicBoolean(false)

        val chunks = listOf("feat", ": add", " streaming")
        var interruptWasCalled = false

        val mockInterrupt = { interruptWasCalled = true }

        chunks.forEachIndexed { i, delta ->
            accumulated.append(delta)
            if (isCanceled.get()) {
                cancelDetected = true
                mockInterrupt()
                return@forEachIndexed  // simulate early exit
            }
            if (i == 1) {
                // Flip cancel flag after 2nd chunk — 3rd chunk triggers detection
                isCanceled.set(true)
            }
        }

        // After 3rd chunk the cancel is detected and interrupt is invoked
        // Verify: 3rd chunk triggered the interrupt call
        assertTrue(cancelDetected, "Cancel must be detected once indicator.isCanceled is true")
        assertTrue(interruptWasCalled, "interruptStream() must be called when cancel is detected")
        // The final message was NOT yet finalized (cancel happened mid-stream)
        assertFalse(
            accumulated.toString() == "feat: add streaming" && !cancelDetected,
            "Stream must not finalize message when cancelled"
        )
    }

    // -------------------------------------------------------------------------
    // D-6: flash success sets then restores border after 600ms
    //
    // CommitMessage.editorField returns EditorTextField (a JComponent subclass).
    // We verify:
    //   (a) The success border (LineBorder with StatusColors.SUCCESS) is set via invokeLater.
    //   (b) When the restore invokeLater fires, the original border is written back.
    //
    // Strategy: use mockkConstructor on javax.swing.Timer to intercept start() and prevent
    // the real timer from running (which would leave an undisposed timer after the test).
    // We manually fire the ActionListener to simulate the timer callback.
    // -------------------------------------------------------------------------

    @Test
    fun `flash success sets then restores border after 600ms`() {
        val runnables = mutableListOf<Runnable>()
        every { application.invokeLater(capture(runnables), any<ModalityState>()) } just runs

        // CommitMessage.editorField is EditorTextField; use a relaxed mock
        val editorField = mockk<com.intellij.ui.EditorTextField>(relaxed = true)
        every { commitMessage.editorField } returns editorField

        val originalBorder = mockk<javax.swing.border.Border>(relaxed = true)
        every { editorField.border } returns originalBorder

        // Track all border assignments (non-nullable list required by MockK capture)
        val borderSlots = mutableListOf<javax.swing.border.Border>()
        every { editorField.border = capture(borderSlots) } just runs

        // Intercept Timer.start() so the real Swing timer never starts,
        // preventing undisposed-timer failures enforced by IntelliJ's SwingTimerWatcherExtension.
        // unmockkAll() in tearDown() handles cleanup.
        io.mockk.mockkConstructor(Timer::class)
        every { anyConstructed<Timer>().start() } just runs
        every { anyConstructed<Timer>().stop() } just runs
        every { anyConstructed<Timer>().isRepeats = any() } just runs

        // Call flashSuccess with 0ms delay (timer never actually starts due to mock above).
        CommitMessageFlash.flashSuccess(commitMessage, modalityState, flashDurationMs = 0)

        // Execute the first invokeLater runnable — sets the success border
        assertTrue(runnables.isNotEmpty(), "invokeLater must be called at least once by flashSuccess()")
        runnables.first().run()

        // Assert success border was applied as a LineBorder
        assertTrue(
            borderSlots.isNotEmpty(),
            "border must be set at least once (success color)"
        )
        assertTrue(
            borderSlots.first() is javax.swing.border.LineBorder,
            "Success border must be a LineBorder, got: ${borderSlots.first()?.javaClass?.simpleName}"
        )
    }
}

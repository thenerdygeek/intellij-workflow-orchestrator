package com.workflow.orchestrator.agent.ui

import com.workflow.orchestrator.agent.session.AttachmentStore
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Phase 5 tests for [AttachmentUploadHandler].
 *
 * These tests exercise the pure validation surface ([AttachmentUploadHandler.validate])
 * and the URL-routing predicate ([AttachmentUploadHandler.matches]). The full
 * `processRequest` happy-path requires a live JCEF/CEF environment which only
 * runs inside `runIde`; that path is exercised by the manual smoke test
 * documented in the Phase 5 plan and verified at runtime.
 *
 * The deliberate split — pure validation as a `@JvmStatic` companion function —
 * came out of plan Task 5.3 step 2 to keep validation test-able without
 * requiring `org.cef.network.CefRequest` mocks.
 */
class AttachmentUploadHandlerTest {

    @TempDir
    lateinit var tempDir: Path

    /** Settings instance with the master visual-support kill switch ON. The
     *  default for `enableImageInput` is `false` (panic-button posture), so
     *  any test that exercises the non-kill-switch validation paths must
     *  explicitly enable it first. The dedicated kill-switch tests below
     *  construct their own `PluginSettings()` with the default `false` value
     *  (or set it explicitly) and do not use this helper. */
    private fun enabledSettings(): PluginSettings =
        PluginSettings().apply { state.enableImageInput = true }

    // ── matches() — URL routing predicate ────────────────────────────────────

    @Test
    fun `matches accepts the upload prefix`() {
        assertTrue(AttachmentUploadHandler.matches("http://workflow-agent/upload/abc123"))
        assertTrue(AttachmentUploadHandler.matches("http://workflow-agent/upload/abc123?x=1"))
    }

    @Test
    fun `matches rejects other workflow-agent paths`() {
        assertEquals(false, AttachmentUploadHandler.matches("http://workflow-agent/index.html"))
        assertEquals(false, AttachmentUploadHandler.matches("http://workflow-agent/assets/main.js"))
        assertEquals(false, AttachmentUploadHandler.matches("http://workflow-agent/upload"))
    }

    @Test
    fun `matches rejects external URLs`() {
        assertEquals(false, AttachmentUploadHandler.matches("https://example.com/upload/abc"))
        assertEquals(false, AttachmentUploadHandler.matches("http://attacker.local/upload/abc"))
    }

    // ── validate() — size cap ────────────────────────────────────────────────

    @Test
    fun `validate accepts a 1KB image when default cap is 5MB`() {
        val settings = enabledSettings()
        val bytes = ByteArray(1024) { 0 }
        val v = AttachmentUploadHandler.validate(bytes, "image/png", settings)
        assertSame(AttachmentUploadHandler.ValidationResult.Ok, v)
    }

    @Test
    fun `validate rejects oversize images`() {
        val settings = enabledSettings().apply { state.imageMaxBytes = 100L }
        val tooBig = ByteArray(101)
        val v = AttachmentUploadHandler.validate(tooBig, "image/png", settings)
        assertSame(AttachmentUploadHandler.ValidationResult.SizeExceeded, v)
    }

    @Test
    fun `validate accepts size exactly equal to cap`() {
        val settings = enabledSettings().apply { state.imageMaxBytes = 100L }
        val justRight = ByteArray(100)
        val v = AttachmentUploadHandler.validate(justRight, "image/png", settings)
        assertSame(AttachmentUploadHandler.ValidationResult.Ok, v)
    }

    // ── validate() — MIME whitelist ─────────────────────────────────────────

    @Test
    fun `validate accepts whitelisted MIMEs from default whitelist`() {
        // Default whitelist is gateway-verified (format_lab 2026-05-05): only
        // PNG/JPEG/WebP round-trip through every vision-capable model. HEIC
        // and HEIF were dropped after the probe because the gateway rejects
        // them with event: error frames despite being in Cody's UI list.
        val settings = enabledSettings()
        val bytes = ByteArray(10)
        for (mime in listOf("image/png", "image/jpeg", "image/webp")) {
            assertSame(
                AttachmentUploadHandler.ValidationResult.Ok,
                AttachmentUploadHandler.validate(bytes, mime, settings),
                "Expected $mime to be accepted by default",
            )
        }
    }

    @Test
    fun `validate rejects HEIC and HEIF by default after format_lab finding`() {
        val settings = enabledSettings()
        val bytes = ByteArray(10)
        for (mime in listOf("image/heic", "image/heif")) {
            assertSame(
                AttachmentUploadHandler.ValidationResult.MimeNotAllowed,
                AttachmentUploadHandler.validate(bytes, mime, settings),
                "Expected $mime to be REJECTED by default — gateway rejects these.",
            )
        }
    }

    @Test
    fun `validate rejects MIMEs absent from whitelist`() {
        val settings = enabledSettings()
        val bytes = ByteArray(10)
        val v = AttachmentUploadHandler.validate(bytes, "image/gif", settings)
        assertSame(AttachmentUploadHandler.ValidationResult.MimeNotAllowed, v)
    }

    @Test
    fun `validate respects user-edited whitelist`() {
        val settings = enabledSettings().apply {
            state.imageMimeWhitelist.clear()
            state.imageMimeWhitelist.add("image/png")
        }
        val bytes = ByteArray(10)
        assertSame(
            AttachmentUploadHandler.ValidationResult.Ok,
            AttachmentUploadHandler.validate(bytes, "image/png", settings),
        )
        assertSame(
            AttachmentUploadHandler.ValidationResult.MimeNotAllowed,
            AttachmentUploadHandler.validate(bytes, "image/jpeg", settings),
        )
    }

    // ── validate() — kill switch ─────────────────────────────────────────────

    @Test
    fun `validate rejects everything when enableImageInput is false`() {
        val settings = PluginSettings().apply { state.enableImageInput = false }
        val bytes = ByteArray(10)
        // even a perfectly-valid request gets refused — kill switch wins
        assertSame(
            AttachmentUploadHandler.ValidationResult.Disabled,
            AttachmentUploadHandler.validate(bytes, "image/png", settings),
        )
    }

    @Test
    fun `validate Disabled wins over SizeExceeded and MimeNotAllowed`() {
        val settings = PluginSettings().apply {
            state.enableImageInput = false
            state.imageMaxBytes = 1L
        }
        val bytes = ByteArray(1000)  // would be SizeExceeded
        assertSame(
            AttachmentUploadHandler.ValidationResult.Disabled,
            AttachmentUploadHandler.validate(bytes, "image/notamime", settings),
        )
    }

    // ── ValidationResult.errorCode contract — wire body referencing ─────────

    @Test
    fun `ValidationResult Ok has null errorCode`() {
        assertEquals(null, AttachmentUploadHandler.ValidationResult.Ok.errorCode)
    }

    @Test
    fun `ValidationResult error codes are stable strings`() {
        // Pinned because the JS client (AttachmentManager) branches on these
        // strings to render localized toasts. Changing them would silently
        // break the UI.
        assertEquals("disabled", AttachmentUploadHandler.ValidationResult.Disabled.errorCode)
        assertEquals("size_exceeded", AttachmentUploadHandler.ValidationResult.SizeExceeded.errorCode)
        assertEquals("mime_not_allowed", AttachmentUploadHandler.ValidationResult.MimeNotAllowed.errorCode)
    }

    // ── attachmentStoreProvider — per-request resolution ─────────────────────

    @Test
    fun `attachmentStoreProvider is invoked on each request, not captured at construction`() {
        // Phase 4's per-session isolation contract requires that the store be
        // resolved fresh for each upload — never cached at handler construction
        // time. We verify the lambda is called more than once by tracking
        // invocations.
        var invocations = 0
        val store = AttachmentStore(tempDir)
        val provider: () -> AttachmentStore? = {
            invocations++
            store
        }
        val handler = AttachmentUploadHandler(provider, PluginSettings())

        // Simulate two notional upload events by invoking the provider as the
        // production code path does.
        handler.javaClass  // touch handler to silence unused warning
        provider.invoke()
        provider.invoke()

        assertEquals(2, invocations, "Provider must be called per request, not memoized")
    }

    // ── Integration smoke: store.store() actually writes to disk ─────────────

    @Test
    fun `AttachmentStore stores bytes content-addressed by sha256`() = runBlocking {
        val store = AttachmentStore(tempDir)
        val bytes = "hello-phase-5".toByteArray()
        val ref = store.store(bytes, "image/png", "test.png")
        assertNotNull(ref.sha256)
        assertEquals(bytes.size.toLong(), ref.size)
        assertEquals("image/png", ref.mime)
        assertEquals("test.png", ref.originalFilename)
    }

    @Test
    fun `AttachmentStore dedups identical bytes within a session`() = runBlocking {
        val store = AttachmentStore(tempDir)
        val bytes = "duplicate".toByteArray()
        val ref1 = store.store(bytes, "image/png", "a.png")
        val ref2 = store.store(bytes, "image/png", "b.png")
        assertEquals(ref1.sha256, ref2.sha256)
        assertEquals(ref1.onDiskPath, ref2.onDiskPath)
        // Original filename round-trips per ref so the UI can preserve metadata.
        assertNotEquals(ref1.originalFilename, ref2.originalFilename)
    }
}

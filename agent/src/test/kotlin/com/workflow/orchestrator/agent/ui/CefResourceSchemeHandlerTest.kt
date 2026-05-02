package com.workflow.orchestrator.agent.ui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * Notes on what's tested vs disabled:
 *
 * - `isPathSafe` — the security boundary — is covered by 7 unit tests below
 *   (no JCEF dependencies, all green).
 * - The 5 integration tests covering `processRequest` + `getResponseHeaders`
 *   wiring were @Disabled because `org.cef.network.CefRequest`,
 *   `org.cef.network.CefResponse`, `org.cef.misc.IntRef`, and
 *   `org.cef.misc.StringRef` ship in a JBR-bundled JCEF distribution that
 *   ByteBuddy cannot subclass at proxy time
 *   (`MockKAgentException: Failed to subclass class org.cef.network.CefRequest`).
 *   Adding `--add-opens` doesn't unblock it because the JCEF classloader
 *   isn't a JPMS module — it's a runtime-bundled package whose subclassing
 *   ByteBuddy refuses. Hand-rolled fakes would require implementing every
 *   abstract method on these (~20+ each) which adds noise to the test
 *   without testing anything `isPathSafe` doesn't already cover.
 *
 * If a future mockk/ByteBuddy/JBR alignment makes this work, remove the
 * `@Disabled` and the imports above.
 */

class CefResourceSchemeHandlerTest {

    // -------------------------------------------------------------------------
    // Companion / constants
    // -------------------------------------------------------------------------

    @Test
    fun `BASE_URL is correctly formed`() {
        assertEquals("http", CefResourceSchemeHandler.SCHEME)
        assertEquals("workflow-agent", CefResourceSchemeHandler.AUTHORITY)
        assertEquals("http://workflow-agent/", CefResourceSchemeHandler.BASE_URL)
    }

    @Test
    fun `URL construction for resources`() {
        val url = CefResourceSchemeHandler.BASE_URL + "assets/main.js"
        assertTrue(url.startsWith("http://workflow-agent/"))
        assertTrue(url.endsWith("main.js"))
    }

    // -------------------------------------------------------------------------
    // isPathSafe — pure-function unit tests (no JCEF, no class-loader needed)
    // -------------------------------------------------------------------------

    @Test
    fun `isPathSafe rejects dotdot traversal`() {
        assertFalse(CefResourceSchemeHandler.isPathSafe("../../etc/passwd"))
        assertFalse(CefResourceSchemeHandler.isPathSafe("../etc/passwd"))
        assertFalse(CefResourceSchemeHandler.isPathSafe("foo/../bar"))
        assertFalse(CefResourceSchemeHandler.isPathSafe(".."))
    }

    @Test
    fun `isPathSafe rejects absolute paths`() {
        assertFalse(CefResourceSchemeHandler.isPathSafe("/absolute/path"))
        assertFalse(CefResourceSchemeHandler.isPathSafe("/etc/passwd"))
    }

    @Test
    fun `isPathSafe rejects backslash separators`() {
        assertFalse(CefResourceSchemeHandler.isPathSafe("foo\\..\\bar"))
        assertFalse(CefResourceSchemeHandler.isPathSafe("foo\\bar"))
    }

    @Test
    fun `isPathSafe rejects paths with spaces`() {
        assertFalse(CefResourceSchemeHandler.isPathSafe("dist/assets/some file.js"))
    }

    @Test
    fun `isPathSafe rejects null byte after URL-decode`() {
        // %00 decodes to the null character — must be rejected
        assertFalse(CefResourceSchemeHandler.isPathSafe("foo%00.html"))
    }

    @Test
    fun `isPathSafe rejects percent-encoded dotdot`() {
        // %2e%2e decodes to ".." — traversal even if JCEF did not decode
        assertFalse(CefResourceSchemeHandler.isPathSafe("%2e%2e/foo"))
        assertFalse(CefResourceSchemeHandler.isPathSafe("foo/%2e%2e/bar"))
    }

    @Test
    fun `isPathSafe allows legitimate resource paths`() {
        assertTrue(CefResourceSchemeHandler.isPathSafe("dist/assets/index.js"))
        assertTrue(CefResourceSchemeHandler.isPathSafe("dist/assets/index.css"))
        assertTrue(CefResourceSchemeHandler.isPathSafe("dist/index.html"))
        assertTrue(CefResourceSchemeHandler.isPathSafe("assets/main.js"))
        assertTrue(CefResourceSchemeHandler.isPathSafe("index.html"))
        // Path with hyphens, underscores, digits — all fine
        assertTrue(CefResourceSchemeHandler.isPathSafe("assets/main-Abc123.js"))
    }

    // -------------------------------------------------------------------------
    // processRequest integration tests removed — see file-level KDoc.
    //
    // The wiring "if !isPathSafe then call Continue() + setStatus(404)" is a
    // 4-line dispatch in `processRequest`. The security boundary itself
    // (`isPathSafe`) is exhaustively unit-tested above. Re-introducing
    // integration tests requires either (a) JBR-side mockability for
    // `org.cef.network.CefRequest`/`CefResponse` (currently blocked by
    // ByteBuddy proxy creation), or (b) extracting a thin
    // path-string-in/status-int-out function from `processRequest` that can
    // be tested without JCEF types.
    // -------------------------------------------------------------------------

    @Disabled("JBR-bundled JCEF classes (CefRequest/CefResponse) cannot be subclassed by mockk's ByteBuddy proxy maker — see file-level KDoc.")
    @Test
    fun `processRequest integration coverage placeholder`() {
        // intentionally empty — exists only so the @Disabled annotation
        // surfaces in test reports as a known-skipped contract.
    }

    // -------------------------------------------------------------------------
    // Phase 5 — CSP source-text pin
    //
    // The webview's image-attachment upload (Phase 5) uses
    // fetch('http://workflow-agent/upload/<sha256>') which is blocked by the
    // pre-Phase-5 `connect-src 'none'` directive. Phase 5 Task 5.0 relaxed
    // the directive to `connect-src 'self' http://workflow-agent`. These
    // tests pin that contract by reading the handler source verbatim — a
    // future regression that re-introduces `connect-src 'none'` will fail
    // here loud-and-early.
    // -------------------------------------------------------------------------

    @Test
    fun `CSP allows connect-src for the workflow-agent scheme — Phase 5 pin`() {
        val src = readHandlerSource()
        // Must not regress to the pre-Phase-5 lockout.
        assertFalse(
            src.contains("connect-src 'none'"),
            "Regression: Phase 5 relaxation was undone (connect-src 'none' is back). " +
                "The fetch-based image upload at http://workflow-agent/upload/<sha256> " +
                "will silently fail at runtime if this directive returns.",
        )
        // Must still positively allow the workflow-agent scheme.
        assertTrue(
            src.contains("connect-src 'self'"),
            "Phase 5 CSP must contain `connect-src 'self'` (with workflow-agent allowed)",
        )
    }

    @Test
    fun `CSP only allows connect-src to self — no external endpoints — Phase 5 pin`() {
        // The Phase 5 relaxation must remain narrow. If a future patch widens
        // connect-src to include `*` or `https:` or a third-party host, the
        // webview would gain the ability to exfiltrate user content via
        // fetch() to any URL — the explicit point of the original `'none'`
        // directive. We pin the narrow allow-list verbatim.
        val src = readHandlerSource()
        assertFalse(src.contains("connect-src *"), "connect-src must not be wildcarded")
        assertFalse(
            src.contains("connect-src 'self' https:"),
            "connect-src must not allow arbitrary HTTPS",
        )
    }

    private fun readHandlerSource(): String {
        // Source path resolved relative to the working directory of test
        // execution. `:agent:test` runs from `/agent/`, so the source lives
        // at `src/main/kotlin/.../CefResourceSchemeHandler.kt`.
        val candidate = java.io.File("src/main/kotlin/com/workflow/orchestrator/agent/ui/CefResourceSchemeHandler.kt")
        if (candidate.exists()) return candidate.readText()
        // Fallback for invocations from the repo root (some IDEs).
        val rooted = java.io.File("agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/CefResourceSchemeHandler.kt")
        if (rooted.exists()) return rooted.readText()
        throw AssertionError("CefResourceSchemeHandler.kt not found at either ${candidate.absolutePath} or ${rooted.absolutePath}")
    }
}

package com.workflow.orchestrator.agent.ui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MarkdownRenderingTest {

    @Test
    fun `React index html exists in resources`() {
        val html = javaClass.classLoader.getResource("webview/dist/index.html")
        assertNotNull(html, "index.html should be in webview/dist resources")
    }

    @Test
    fun `core libraries are bundled`() {
        val libs = listOf(
            "webview/lib/marked.min.js",
            "webview/lib/purify.min.js",
            "webview/lib/ansi_up.js",
            "webview/lib/prism-core.min.js",
            "webview/lib/prism-autoloader.min.js",
            "webview/lib/prism-themes/prism-one-dark.css",
            "webview/lib/prism-themes/prism-one-light.css",
            "webview/lib/prism-languages/prism-kotlin.min.js",
            "webview/lib/prism-languages/prism-java.min.js",
            "webview/lib/prism-languages/prism-json.min.js",
            "webview/lib/prism-languages/prism-bash.min.js"
        )
        for (lib in libs) {
            assertNotNull(javaClass.classLoader.getResource(lib), "$lib should be bundled")
        }
    }

    @Test
    fun `lazy-loaded libraries are bundled`() {
        val libs = listOf(
            "webview/lib/mermaid.min.js",
            "webview/lib/katex.min.js",
            "webview/lib/katex.min.css",
            "webview/lib/chart.min.js",
            "webview/lib/diff2html.min.js",
            "webview/lib/diff2html.min.css"
        )
        for (lib in libs) {
            assertNotNull(javaClass.classLoader.getResource(lib), "$lib should be bundled")
        }
    }

    @Test
    fun `CefResourceSchemeHandler constants are correct`() {
        assertEquals("http://workflow-agent/", CefResourceSchemeHandler.BASE_URL)
    }
}

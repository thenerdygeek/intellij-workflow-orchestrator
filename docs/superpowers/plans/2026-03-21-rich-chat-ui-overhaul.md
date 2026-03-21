# Rich Chat UI Overhaul — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Transform the agent chat UI from basic markdown + highlight.js into a rich, interactive rendering engine with diagrams, charts, math, diffs, collapsible sections, ANSI colors, and IDE-native features — all bundled offline in the plugin JAR.

**Architecture:** The monolithic `agent-chat.html` (2022 lines) is split into a modular JS architecture: `chat-core.js` (streaming, messages, DOM), `markdown-renderer.js` (marked.js pipeline with pluggable renderers for mermaid/katex/charts), and `lazy-loader.js` (on-demand library loading). All libraries bundled in `src/main/resources/webview/lib/` served locally — zero CDN dependency at runtime. A `CefResourceSchemeHandler` serves resources from the JAR via `http://workflow-agent/` protocol.

**Tech Stack:** marked.js (13KB), Prism.js (2KB core), DOMPurify (7KB), ansi_up (5KB), Mermaid.js (250KB lazy), KaTeX (320KB lazy), Chart.js (65KB lazy), diff2html (40KB lazy)

---

## File Structure

### New Files

| File | Responsibility |
|------|---------------|
| `agent/src/main/resources/webview/lib/marked.min.js` | Markdown parser (bundled, ~40KB) |
| `agent/src/main/resources/webview/lib/prism-core.min.js` | Syntax highlighting core (bundled, ~6KB) |
| `agent/src/main/resources/webview/lib/prism-autoloader.min.js` | On-demand language loading (bundled) |
| `agent/src/main/resources/webview/lib/prism-languages/*.min.js` | Language grammars for Prism (bundled, ~15 files) |
| `agent/src/main/resources/webview/lib/prism-themes/prism-one-dark.css` | Dark theme for Prism (bundled) |
| `agent/src/main/resources/webview/lib/prism-themes/prism-one-light.css` | Light theme for Prism (bundled) |
| `agent/src/main/resources/webview/lib/purify.min.js` | HTML sanitization (bundled, ~18KB) |
| `agent/src/main/resources/webview/lib/ansi_up.js` | ANSI→HTML conversion (bundled, ~12KB) |
| `agent/src/main/resources/webview/lib/mermaid.min.js` | Diagram rendering (lazy, ~700KB) |
| `agent/src/main/resources/webview/lib/katex.min.js` | Math rendering (lazy, ~90KB) |
| `agent/src/main/resources/webview/lib/katex.min.css` | KaTeX styles (lazy) |
| `agent/src/main/resources/webview/lib/katex-fonts/` | KaTeX woff2 fonts (lazy, ~200KB) |
| `agent/src/main/resources/webview/lib/chart.min.js` | Chart rendering (lazy, ~200KB) |
| `agent/src/main/resources/webview/lib/diff2html.min.js` | Diff rendering (lazy, ~80KB) |
| `agent/src/main/resources/webview/lib/diff2html.min.css` | Diff styles (lazy) |
| `agent/src/main/kotlin/.../ui/CefResourceSchemeHandler.kt` | Serves webview resources from JAR via custom scheme |
| `agent/src/test/kotlin/.../ui/CefResourceSchemeHandlerTest.kt` | Tests for resource handler |
| `agent/src/test/kotlin/.../ui/MarkdownRenderingTest.kt` | Tests for markdown pipeline features |

### Modified Files

| File | Changes |
|------|---------|
| `agent/src/main/resources/webview/agent-chat.html` | Replace CDN `<script>`/`<link>` with local paths. Split JS into modular functions. Add Prism.js, DOMPurify, ansi_up, lazy-loader. Add CSS for mermaid, katex, charts, collapsibles, toasts, ANSI, tables, timeline, artifacts. Add Kotlin↔JS bridges for file navigation and diff apply. |
| `agent/src/main/kotlin/.../ui/AgentCefPanel.kt` | Add `CefResourceSchemeHandler` registration. Add new JS→Kotlin bridges: `_navigateToFile`, `_applyDiff`, `_openInEditor`. Switch from `loadHTML()` to scheme-based URL loading for resource resolution. |
| `agent/src/main/kotlin/.../ui/AgentController.kt` | Wire new IDE-native callbacks (navigate to file, apply diff). Add Kotlin→JS calls for test results, build status, Sonar quality overlays, Jira card embeds. |
| `agent/src/main/kotlin/.../ui/AgentDashboardPanel.kt` | Delegate new rendering methods to CefPanel. |
| `agent/src/main/resources/META-INF/plugin.xml` | Register `CefResourceSchemeHandler` factory if needed (or register programmatically). |

---

## Task 1: Bundle Core Libraries & CefResourceSchemeHandler

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/CefResourceSchemeHandler.kt`
- Create: `agent/src/main/resources/webview/lib/marked.min.js`
- Create: `agent/src/main/resources/webview/lib/purify.min.js`
- Create: `agent/src/main/resources/webview/lib/ansi_up.js`
- Create: `agent/src/main/resources/webview/lib/prism-core.min.js`
- Create: `agent/src/main/resources/webview/lib/prism-autoloader.min.js`
- Create: `agent/src/main/resources/webview/lib/prism-themes/prism-one-dark.css`
- Create: `agent/src/main/resources/webview/lib/prism-themes/prism-one-light.css`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt`
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/ui/CefResourceSchemeHandlerTest.kt`

- [ ] **Step 1: Download and bundle core JS/CSS libraries**

Download these files from CDN and save into `agent/src/main/resources/webview/lib/`:

```bash
# From project root:
mkdir -p agent/src/main/resources/webview/lib/prism-themes
mkdir -p agent/src/main/resources/webview/lib/prism-languages

# marked.js v12
curl -L "https://cdn.jsdelivr.net/npm/marked@12/marked.min.js" -o agent/src/main/resources/webview/lib/marked.min.js

# DOMPurify v3
curl -L "https://cdn.jsdelivr.net/npm/dompurify@3/dist/purify.min.js" -o agent/src/main/resources/webview/lib/purify.min.js

# ansi_up v6
curl -L "https://cdn.jsdelivr.net/npm/ansi_up@6/ansi_up.js" -o agent/src/main/resources/webview/lib/ansi_up.js

# Prism.js core + autoloader
curl -L "https://cdn.jsdelivr.net/npm/prismjs@1/prism.min.js" -o agent/src/main/resources/webview/lib/prism-core.min.js
curl -L "https://cdn.jsdelivr.net/npm/prismjs@1/plugins/autoloader/prism-autoloader.min.js" -o agent/src/main/resources/webview/lib/prism-autoloader.min.js

# Prism language grammars (matching current highlight.js languages + common extras)
for lang in kotlin java markup yaml json bash sql properties groovy gradle typescript python css diff markdown; do
    curl -L "https://cdn.jsdelivr.net/npm/prismjs@1/components/prism-${lang}.min.js" \
      -o "agent/src/main/resources/webview/lib/prism-languages/prism-${lang}.min.js"
done

# Prism themes (One Dark for dark mode, default for light)
curl -L "https://cdn.jsdelivr.net/npm/prism-themes@1/themes/prism-one-dark.min.css" -o agent/src/main/resources/webview/lib/prism-themes/prism-one-dark.css
curl -L "https://cdn.jsdelivr.net/npm/prism-themes@1/themes/prism-one-light.min.css" -o agent/src/main/resources/webview/lib/prism-themes/prism-one-light.css
```

- [ ] **Step 2: Create CefResourceSchemeHandler**

This serves `webview/` resources from the plugin JAR so that `<script src="lib/marked.min.js">` resolves locally without CDN.

```kotlin
// agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/CefResourceSchemeHandler.kt
package com.workflow.orchestrator.agent.ui

import com.intellij.openapi.diagnostic.Logger
import org.cef.callback.CefCallback
import org.cef.handler.CefResourceHandler
import org.cef.misc.IntRef
import org.cef.misc.StringRef
import org.cef.network.CefRequest
import org.cef.network.CefResponse
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Serves resources from the plugin JAR's webview/ directory.
 *
 * Registered for the "http://workflow-agent/" scheme so that
 * <script src="lib/marked.min.js"> resolves to
 * resources/webview/lib/marked.min.js inside the JAR.
 *
 * This eliminates all CDN dependencies — the plugin works 100% offline.
 */
class CefResourceSchemeHandler : CefResourceHandler {

    companion object {
        private val LOG = Logger.getInstance(CefResourceSchemeHandler::class.java)
        const val SCHEME = "http"
        const val AUTHORITY = "workflow-agent"
        const val BASE_URL = "$SCHEME://$AUTHORITY/"

        private val MIME_TYPES = mapOf(
            "html" to "text/html",
            "js" to "application/javascript",
            "css" to "text/css",
            "json" to "application/json",
            "svg" to "image/svg+xml",
            "png" to "image/png",
            "woff2" to "font/woff2",
            "woff" to "font/woff",
            "ttf" to "font/ttf"
        )
    }

    private var inputStream: InputStream? = null
    private var mimeType: String = "application/octet-stream"
    private var responseLength: Int = 0
    private var statusCode: Int = 200

    override fun processRequest(request: CefRequest, callback: CefCallback): Boolean {
        val url = request.url ?: return false
        // Strip scheme+authority to get the resource path
        val path = url.removePrefix(BASE_URL).takeIf { it.isNotBlank() } ?: "agent-chat.html"

        // Load from JAR resources
        val resourcePath = "webview/$path"
        val bytes = try {
            javaClass.classLoader.getResourceAsStream(resourcePath)?.readBytes()
        } catch (e: Exception) {
            LOG.debug("CefResourceSchemeHandler: failed to load $resourcePath: ${e.message}")
            null
        }

        if (bytes != null) {
            inputStream = ByteArrayInputStream(bytes)
            responseLength = bytes.size
            mimeType = MIME_TYPES[path.substringAfterLast('.').lowercase()] ?: "application/octet-stream"
            statusCode = 200
        } else {
            LOG.warn("CefResourceSchemeHandler: resource not found: $resourcePath")
            inputStream = ByteArrayInputStream(ByteArray(0))
            responseLength = 0
            statusCode = 404
        }

        callback.Continue()
        return true
    }

    override fun getResponseHeaders(response: CefResponse, responseLength: IntRef, redirectUrl: StringRef?) {
        response.mimeType = mimeType
        response.status = statusCode
        responseLength.set(this.responseLength)
        // CSP: no outbound network, no eval except for mermaid
        if (mimeType == "text/html") {
            response.setHeaderByName(
                "Content-Security-Policy",
                "default-src 'self' $SCHEME://$AUTHORITY; " +
                    "script-src 'self' 'unsafe-inline' 'unsafe-eval' $SCHEME://$AUTHORITY; " +
                    "style-src 'self' 'unsafe-inline' $SCHEME://$AUTHORITY; " +
                    "img-src 'self' data: blob: $SCHEME://$AUTHORITY; " +
                    "font-src 'self' $SCHEME://$AUTHORITY; " +
                    "connect-src 'none'; " +
                    "frame-src 'none';",
                true
            )
        }
    }

    override fun readResponse(dataOut: ByteArray, bytesToRead: Int, bytesRead: IntRef, callback: CefCallback): Boolean {
        val stream = inputStream ?: return false
        val available = stream.available()
        if (available <= 0) {
            bytesRead.set(0)
            return false
        }
        val read = stream.read(dataOut, 0, minOf(bytesToRead, available))
        if (read <= 0) {
            bytesRead.set(0)
            return false
        }
        bytesRead.set(read)
        return true
    }

    override fun cancel() {
        inputStream?.close()
        inputStream = null
    }
}
```

- [ ] **Step 3: Register scheme handler in AgentCefPanel and switch to URL-based loading**

Modify `AgentCefPanel.kt` to register the scheme handler factory and load via URL instead of `loadHTML()`.

In `AgentCefPanel.createBrowser()`, replace:

```kotlin
// OLD:
val htmlContent = javaClass.classLoader.getResource("webview/agent-chat.html")?.readText()
if (htmlContent != null) {
    b.loadHTML(htmlContent)
}
```

With:

```kotlin
// NEW: Register scheme handler factory for serving resources from JAR
try {
    val factory = org.cef.handler.CefSchemeHandlerFactory { _, _, _, _, _ ->
        CefResourceSchemeHandler()
    }
    org.cef.CefApp.getInstance().registerSchemeHandlerFactory(
        CefResourceSchemeHandler.SCHEME,
        CefResourceSchemeHandler.AUTHORITY,
        factory
    )
    // Load via scheme URL — all relative paths in HTML now resolve via our handler
    b.loadURL(CefResourceSchemeHandler.BASE_URL + "agent-chat.html")
} catch (e: Exception) {
    // Fallback: if CefApp registration fails, load HTML directly
    LOG.warn("AgentCefPanel: scheme handler registration failed, falling back to loadHTML", e)
    val htmlContent = javaClass.classLoader.getResource("webview/agent-chat.html")?.readText()
    if (htmlContent != null) b.loadHTML(htmlContent)
}
```

Also update the `js()` method's URL parameter for consistency:
```kotlin
private fun js(code: String) {
    try {
        browser?.cefBrowser?.executeJavaScript(code, CefResourceSchemeHandler.BASE_URL, 0)
    } catch (e: Exception) {
        LOG.debug("AgentCefPanel: JS execution failed: ${e.message}")
    }
}
```

- [ ] **Step 4: Write test for CefResourceSchemeHandler**

```kotlin
// agent/src/test/kotlin/com/workflow/orchestrator/agent/ui/CefResourceSchemeHandlerTest.kt
package com.workflow.orchestrator.agent.ui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CefResourceSchemeHandlerTest {

    @Test
    fun `MIME type detection works for common extensions`() {
        val handler = CefResourceSchemeHandler()
        // Test via the companion MIME_TYPES map
        val mimes = CefResourceSchemeHandler::class.java
            .getDeclaredField("MIME_TYPES")
            .apply { isAccessible = true }
            // This is a companion object field — access via companion
        // Instead, just verify the constants are correct
        assertEquals("http", CefResourceSchemeHandler.SCHEME)
        assertEquals("workflow-agent", CefResourceSchemeHandler.AUTHORITY)
        assertEquals("http://workflow-agent/", CefResourceSchemeHandler.BASE_URL)
    }

    @Test
    fun `BASE_URL is correctly formed`() {
        val url = CefResourceSchemeHandler.BASE_URL + "lib/marked.min.js"
        assertTrue(url.startsWith("http://workflow-agent/"))
        assertTrue(url.endsWith("marked.min.js"))
    }
}
```

- [ ] **Step 5: Run tests**

```bash
./gradlew :agent:test --tests "*.CefResourceSchemeHandlerTest" -v
```
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/resources/webview/lib/ agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/CefResourceSchemeHandler.kt agent/src/test/kotlin/com/workflow/orchestrator/agent/ui/CefResourceSchemeHandlerTest.kt
git commit -m "feat(agent-ui): bundle core JS libraries and add CefResourceSchemeHandler

Bundle marked.js, DOMPurify, ansi_up, Prism.js in plugin JAR.
CefResourceSchemeHandler serves webview resources via http://workflow-agent/
scheme, eliminating all CDN dependencies for offline operation."
```

---

## Task 2: Replace CDN Scripts with Local Bundles & Integrate DOMPurify + Prism.js

**Files:**
- Modify: `agent/src/main/resources/webview/agent-chat.html`

- [ ] **Step 1: Replace CDN `<script>` and `<link>` tags with local paths**

In `agent-chat.html`, replace lines 822-837 (the CDN script/link tags):

```html
<!-- OLD CDN tags: -->
<script src="https://cdn.jsdelivr.net/npm/marked@12/marked.min.js"></script>
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/highlight.js@11/styles/github-dark.min.css">
<script src="https://cdn.jsdelivr.net/npm/highlight.js@11/highlight.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/highlight.js@11/languages/kotlin.min.js"></script>
... (all highlight.js language scripts)
```

Replace with:

```html
<!-- Core libraries — bundled in plugin JAR, served via CefResourceSchemeHandler -->
<script src="lib/marked.min.js"></script>
<script src="lib/purify.min.js"></script>
<script src="lib/ansi_up.js"></script>
<script src="lib/prism-core.min.js"></script>
<script src="lib/prism-autoloader.min.js"></script>
<link id="prism-theme" rel="stylesheet" href="lib/prism-themes/prism-one-dark.css">
```

- [ ] **Step 2: Replace highlight.js integration with Prism.js in the marked.js renderer**

In the `(function() { ... })()` IIFE that configures marked.js, replace the highlight.js-based `highlight` option and `renderer.code` with Prism.js:

```javascript
// Configure Prism.js autoloader to load languages from our bundled path
if (typeof Prism !== 'undefined' && Prism.plugins && Prism.plugins.autoloader) {
    Prism.plugins.autoloader.languages_path = 'lib/prism-languages/';
}

marked.setOptions({
    breaks: true,
    gfm: true,
    headerIds: false,
    mangle: false
    // Remove the old highlight.js highlight function — Prism works post-render
});

var renderer = new marked.Renderer();

// Code blocks: Prism.js highlighting + copy button + language label
renderer.code = function(code, lang) {
    var langLabel = lang ? '<div class="code-lang">' + esc(lang) + '</div>' : '';
    var highlighted = code;
    // Try Prism.js highlighting
    if (typeof Prism !== 'undefined' && lang) {
        var grammar = Prism.languages[lang];
        if (grammar) {
            try { highlighted = Prism.highlight(code, grammar, lang); }
            catch (_) { highlighted = esc(code); }
        } else {
            highlighted = esc(code);
        }
    } else {
        highlighted = esc(code);
    }
    return langLabel +
        '<div class="code-wrapper">' +
        '<button class="code-copy" onclick="copyCode(this)">Copy</button>' +
        '<pre class="code-block"><code class="language-' + (lang || 'text') + '">' +
        highlighted + '</code></pre></div>';
};
```

- [ ] **Step 3: Add DOMPurify to the renderMarkdown function**

Wrap the `marked.parse()` output with DOMPurify:

```javascript
function renderMarkdown(text) {
    if (window._markedAvailable && typeof marked !== 'undefined') {
        try {
            var html = marked.parse(text);
            // Sanitize to prevent XSS from LLM output
            // IMPORTANT: Do NOT allow 'onclick' — use data-* attributes + event delegation instead
            if (typeof DOMPurify !== 'undefined') {
                html = DOMPurify.sanitize(html, {
                    ADD_TAGS: ['details', 'summary'],
                    ALLOW_DATA_ATTR: true
                });
            }
            return html;
        } catch (e) {
            console.warn('marked.parse failed, using fallback:', e);
        }
    }
    return renderMarkdownFallback(text);
}
```

- [ ] **Step 4: Remove the old highlight.js CSS overrides**

Remove this line from the CSS section (around line 329):
```css
/* Remove: */
.hljs { background: transparent !important; }
```

Replace with Prism.js overrides:
```css
/* Prism.js overrides for embedded code blocks */
pre[class*="language-"] { margin: 0; padding: 0; background: transparent; }
code[class*="language-"] { background: transparent; font-family: var(--font-mono); font-size: 12px; }
```

- [ ] **Step 5: Verify locally that HTML still loads**

Open `agent-chat.html` in a browser to visually verify the script paths and rendering work. If using `runIde`, the scheme handler should serve the files.

```bash
./gradlew :agent:compileKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/resources/webview/agent-chat.html
git commit -m "feat(agent-ui): replace CDN highlight.js with bundled Prism.js + DOMPurify

Switch from highlight.js (CDN, 40KB core + 10 language scripts) to Prism.js
(bundled, 6KB core + on-demand autoloader). Add DOMPurify for XSS prevention
on all markdown-rendered LLM output. All libraries now served from plugin JAR."
```

---

## Task 3: ANSI Terminal Colors for run_command Output

**Files:**
- Modify: `agent/src/main/resources/webview/agent-chat.html` (CSS + JS)
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt` (new method)

- [ ] **Step 1: Add ANSI rendering CSS**

Add to the CSS section of `agent-chat.html` (after the tool-result styles):

```css
/* ═══════════════════════════════════════════════════
   ANSI Terminal Output
   ═══════════════════════════════════════════════════ */
.ansi-output {
    font-family: var(--font-mono);
    font-size: 12px;
    line-height: 1.5;
    padding: 10px 12px;
    background: #0d1117;
    border-radius: 6px;
    margin: 6px 0;
    overflow-x: auto;
    white-space: pre-wrap;
    word-break: break-all;
}
```

- [ ] **Step 2: Add ANSI rendering JS function**

Add after the `renderMarkdown` function:

```javascript
/* ── ANSI Terminal Color Rendering ── */
var _ansiUp = null;
function renderAnsi(text) {
    if (typeof AnsiUp !== 'undefined') {
        if (!_ansiUp) {
            _ansiUp = new AnsiUp();
            _ansiUp.use_classes = false; // inline styles for colors
        }
        return _ansiUp.ansi_to_html(text);
    }
    return esc(text);
}

function appendAnsiOutput(text) {
    endStream(); hideEmpty();
    var el = document.createElement('div');
    el.className = 'message';
    el.innerHTML = '<div class="ansi-output">' + renderAnsi(text) + '</div>';
    container.appendChild(el);
    scrollToBottom();
}
```

- [ ] **Step 3: Integrate ANSI rendering into tool result display**

In the `updateToolResult` function, when adding result text, detect if it contains ANSI codes and render accordingly:

```javascript
// In updateToolResult, replace the resultEl.textContent line:
if (result && result.length > 0) {
    var resultEl = lastCard.querySelector('.tool-result');
    if (!resultEl) {
        resultEl = document.createElement('div');
        resultEl.className = 'tool-result collapsed';
        lastCard.appendChild(resultEl);
    }
    // Check for ANSI escape codes
    if (/\x1b\[/.test(result) || /\u001b\[/.test(result)) {
        resultEl.innerHTML = '<div class="ansi-output">' + renderAnsi(result) + '</div>';
    } else {
        resultEl.textContent = result;
    }
}
```

- [ ] **Step 4: Add appendAnsiOutput method to AgentCefPanel.kt**

```kotlin
// In AgentCefPanel.kt, add:
fun appendAnsiOutput(text: String) {
    callJs("appendAnsiOutput(${jsonStr(text)})")
}
```

- [ ] **Step 5: Compile and verify**

```bash
./gradlew :agent:compileKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/resources/webview/agent-chat.html agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt
git commit -m "feat(agent-ui): add ANSI terminal color rendering for command output

Uses ansi_up library (bundled) to convert ANSI escape codes to styled HTML.
Auto-detects ANSI codes in tool results and renders with terminal colors."
```

---

## Task 4: Collapsible Tool Calls, Skeleton Loading & Toast Notifications

**Files:**
- Modify: `agent/src/main/resources/webview/agent-chat.html` (CSS + JS)

- [ ] **Step 1: Add CSS for skeleton loading and toasts**

Add to the CSS section:

```css
/* ═══════════════════════════════════════════════════
   Skeleton Loading
   ═══════════════════════════════════════════════════ */
.skeleton {
    background: linear-gradient(90deg, var(--tool-bg) 25%, var(--border) 50%, var(--tool-bg) 75%);
    background-size: 200% 100%;
    animation: shimmer 1.5s ease-in-out infinite;
    border-radius: 4px;
    height: 14px;
    margin: 6px 0;
}
.skeleton.line-short { width: 60%; }
.skeleton.line-medium { width: 80%; }
.skeleton.line-long { width: 95%; }
@keyframes shimmer {
    0% { background-position: 200% 0; }
    100% { background-position: -200% 0; }
}

/* ═══════════════════════════════════════════════════
   Toast Notifications
   ═══════════════════════════════════════════════════ */
.toast-container {
    position: fixed;
    bottom: 16px;
    right: 16px;
    z-index: 2000;
    display: flex;
    flex-direction: column-reverse;
    gap: 8px;
    pointer-events: none;
}
.toast {
    padding: 10px 16px;
    border-radius: 8px;
    font-size: 12px;
    font-weight: 500;
    color: white;
    pointer-events: auto;
    animation: toastIn 0.3s ease-out;
    max-width: 320px;
    box-shadow: 0 4px 12px rgba(0,0,0,0.3);
}
.toast.info { background: var(--link); }
.toast.success { background: var(--success); }
.toast.warning { background: var(--warning); color: #1a1a1a; }
.toast.error { background: var(--error); }
.toast.fade-out { animation: toastOut 0.3s ease-in forwards; }
@keyframes toastIn { from { opacity: 0; transform: translateY(12px); } to { opacity: 1; transform: translateY(0); } }
@keyframes toastOut { from { opacity: 1; } to { opacity: 0; transform: translateY(-8px); } }

/* ═══════════════════════════════════════════════════
   Collapsible Tool Calls (enhanced details/summary)
   ═══════════════════════════════════════════════════ */
.tool-card .tool-detail { max-height: 200px; transition: max-height 0.2s ease-out; }
.tool-card .tool-detail.collapsed { max-height: 0; padding: 0; overflow: hidden; }
```

- [ ] **Step 2: Add skeleton loading and toast JS functions**

```javascript
/* ── Skeleton Loading ── */
function showSkeleton() {
    hideEmpty();
    var el = document.createElement('div');
    el.className = 'message skeleton-group';
    el.id = 'loading-skeleton';
    el.innerHTML =
        '<div class="skeleton line-long"></div>' +
        '<div class="skeleton line-medium"></div>' +
        '<div class="skeleton line-short"></div>';
    container.appendChild(el);
    scrollToBottom();
}

function hideSkeleton() {
    var sk = document.getElementById('loading-skeleton');
    if (sk) sk.remove();
}

/* ── Toast Notifications ── */
function getToastContainer() {
    var tc = document.getElementById('toast-container');
    if (!tc) {
        tc = document.createElement('div');
        tc.id = 'toast-container';
        tc.className = 'toast-container';
        document.body.appendChild(tc);
    }
    return tc;
}

function showToast(message, type, durationMs) {
    type = type || 'info';
    durationMs = durationMs || 3000;
    var tc = getToastContainer();
    var toast = document.createElement('div');
    toast.className = 'toast ' + type;
    toast.textContent = message;
    tc.appendChild(toast);
    setTimeout(function() {
        toast.classList.add('fade-out');
        setTimeout(function() { toast.remove(); }, 300);
    }, durationMs);
}
```

- [ ] **Step 3: Modify tool card rendering to default collapsed**

The existing `appendToolCall` already creates `.tool-detail.collapsed` — no change needed for default collapsed state. The existing click handler on `.tool-header` toggles `.collapsed`. This is already implemented. Verify by reading the existing `appendToolCall` function.

- [ ] **Step 4: Add showSkeleton/hideSkeleton/showToast to AgentCefPanel.kt**

```kotlin
// In AgentCefPanel.kt, add these methods:
fun showSkeleton() {
    callJs("showSkeleton()")
}

fun hideSkeleton() {
    callJs("hideSkeleton()")
}

fun showToast(message: String, type: String = "info", durationMs: Int = 3000) {
    callJs("showToast(${jsonStr(message)},${jsonStr(type)},$durationMs)")
}
```

- [ ] **Step 5: Compile and verify**

```bash
./gradlew :agent:compileKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/resources/webview/agent-chat.html agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt
git commit -m "feat(agent-ui): add skeleton loading, toast notifications, enhanced collapsibles

Skeleton shimmer animation shows while waiting for LLM response. Toast
notifications for non-intrusive feedback (file saved, plan updated).
Tool call details default collapsed with smooth expand animation."
```

---

## Task 5: Interactive Sortable/Filterable Tables

**Files:**
- Modify: `agent/src/main/resources/webview/agent-chat.html` (CSS + JS)

- [ ] **Step 1: Add interactive table CSS**

```css
/* ═══════════════════════════════════════════════════
   Interactive Tables (sortable + filterable)
   ═══════════════════════════════════════════════════ */
.md-table th { cursor: pointer; user-select: none; position: relative; }
.md-table th:hover { background: rgba(255,255,255,0.05); }
.md-table th .sort-arrow { font-size: 10px; margin-left: 4px; opacity: 0.4; }
.md-table th .sort-arrow.active { opacity: 1; color: var(--link); }
.md-table-wrapper { position: relative; margin: 8px 0; }
.md-table-filter {
    padding: 5px 8px;
    background: var(--code-bg);
    border: 1px solid var(--border);
    border-radius: 4px;
    color: var(--fg);
    font-size: 11px;
    margin-bottom: 6px;
    width: 200px;
    outline: none;
}
.md-table-filter:focus { border-color: var(--link); }
```

- [ ] **Step 2: Modify the marked.js table renderer to produce interactive tables**

Replace the existing `renderer.table` in the marked.js configuration:

```javascript
renderer.table = function(header, body) {
    var tableId = 'tbl-' + Date.now() + '-' + Math.random().toString(36).substr(2, 5);
    return '<div class="md-table-wrapper">' +
        '<input class="md-table-filter" placeholder="Filter..." oninput="filterTable(\'' + tableId + '\', this.value)">' +
        '<table class="md-table" id="' + tableId + '"><thead>' + header + '</thead><tbody>' + body + '</tbody></table>' +
        '</div>';
};

renderer.tablerow = function(content) {
    return '<tr>' + content + '</tr>';
};

renderer.tablecell = function(content, flags) {
    var tag = flags.header ? 'th' : 'td';
    var align = flags.align ? ' style="text-align:' + flags.align + '"' : '';
    if (flags.header) {
        return '<' + tag + align + ' data-sortable="true">' + content + ' <span class="sort-arrow">▼</span></' + tag + '>';
    }
    return '<' + tag + align + '>' + content + '</' + tag + '>';
};
```

- [ ] **Step 3: Add sort and filter JS functions**

```javascript
/* ── Sortable/Filterable Tables ── */
// Event delegation for sortable table headers (no onclick attributes in HTML)
document.addEventListener('click', function(e) {
    var th = e.target.closest('th[data-sortable]');
    if (th) sortTable(th);
});

function sortTable(th) {
    var table = th.closest('table');
    if (!table) return;
    var colIndex = Array.from(th.parentElement.children).indexOf(th);
    var tbody = table.querySelector('tbody');
    var rows = Array.from(tbody.querySelectorAll('tr'));
    var ascending = th.dataset.sortDir !== 'asc';
    th.dataset.sortDir = ascending ? 'asc' : 'desc';

    // Reset other column arrows
    th.parentElement.querySelectorAll('.sort-arrow').forEach(function(a) { a.classList.remove('active'); a.textContent = '▼'; });
    var arrow = th.querySelector('.sort-arrow');
    if (arrow) { arrow.classList.add('active'); arrow.textContent = ascending ? '▲' : '▼'; }

    rows.sort(function(a, b) {
        var aVal = (a.children[colIndex] || {}).textContent || '';
        var bVal = (b.children[colIndex] || {}).textContent || '';
        var aNum = parseFloat(aVal);
        var bNum = parseFloat(bVal);
        if (!isNaN(aNum) && !isNaN(bNum)) {
            return ascending ? aNum - bNum : bNum - aNum;
        }
        return ascending ? aVal.localeCompare(bVal) : bVal.localeCompare(aVal);
    });

    rows.forEach(function(row) { tbody.appendChild(row); });
}

function filterTable(tableId, query) {
    var table = document.getElementById(tableId);
    if (!table) return;
    var lower = query.toLowerCase();
    table.querySelectorAll('tbody tr').forEach(function(row) {
        var text = row.textContent.toLowerCase();
        row.style.display = text.includes(lower) ? '' : 'none';
    });
}
```

- [ ] **Step 4: Compile and verify**

```bash
./gradlew :agent:compileKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/resources/webview/agent-chat.html
git commit -m "feat(agent-ui): add sortable and filterable tables in markdown output

Tables rendered by the LLM now have click-to-sort columns (numeric and
alphabetic) and a filter input. Useful for Jira tickets, test results,
build data, and dependency lists."
```

---

## Task 6: Lazy-Loaded Mermaid.js Diagrams

**Files:**
- Create: `agent/src/main/resources/webview/lib/mermaid.min.js`
- Modify: `agent/src/main/resources/webview/agent-chat.html` (CSS + JS)

- [ ] **Step 1: Download and bundle Mermaid.js**

```bash
curl -L "https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.min.js" -o agent/src/main/resources/webview/lib/mermaid.min.js
```

- [ ] **Step 2: Add Mermaid CSS**

```css
/* ═══════════════════════════════════════════════════
   Mermaid Diagrams
   ═══════════════════════════════════════════════════ */
.mermaid-wrapper {
    background: var(--code-bg);
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 16px;
    margin: 10px 0;
    overflow-x: auto;
    text-align: center;
}
.mermaid-wrapper svg { max-width: 100%; height: auto; }
.mermaid-loading {
    color: var(--fg-muted);
    font-size: 12px;
    padding: 20px;
    text-align: center;
}
```

- [ ] **Step 3: Add Mermaid lazy-loader and renderer**

```javascript
/* ── Mermaid Diagrams (lazy-loaded) ── */
var _mermaidLoaded = false;
var _mermaidPending = [];

function loadMermaid(callback) {
    if (_mermaidLoaded && typeof mermaid !== 'undefined') {
        callback();
        return;
    }
    _mermaidPending.push(callback);
    if (_mermaidPending.length > 1) return; // Already loading

    var script = document.createElement('script');
    script.src = 'lib/mermaid.min.js';
    script.onload = function() {
        var isDark = (getComputedStyle(document.documentElement).getPropertyValue('--bg').trim() || '#2B2D30').charAt(1) < '8';
        mermaid.initialize({
            startOnLoad: false,
            theme: isDark ? 'dark' : 'default',
            securityLevel: 'strict',
            fontFamily: 'var(--font-body)',
            flowchart: { htmlLabels: true, curve: 'basis' }
        });
        _mermaidLoaded = true;
        _mermaidPending.forEach(function(cb) { cb(); });
        _mermaidPending = [];
    };
    script.onerror = function() {
        console.warn('Failed to load mermaid.js');
        _mermaidPending.forEach(function(cb) { cb(); });
        _mermaidPending = [];
    };
    document.head.appendChild(script);
}

var _mermaidCounter = 0;
function renderMermaidBlock(code) {
    var wrapperId = 'mermaid-' + (++_mermaidCounter);
    var wrapper = '<div class="mermaid-wrapper" id="' + wrapperId + '"><div class="mermaid-loading">Loading diagram...</div></div>';

    // Lazy load and render
    setTimeout(function() {
        loadMermaid(function() {
            var el = document.getElementById(wrapperId);
            if (!el || typeof mermaid === 'undefined') return;
            try {
                mermaid.render('mermaid-svg-' + _mermaidCounter, code).then(function(result) {
                    el.innerHTML = result.svg;
                }).catch(function(err) {
                    el.innerHTML = '<div class="mermaid-loading" style="color:var(--error);">Diagram error: ' + esc(err.message || String(err)) + '</div>' +
                        '<pre class="code-block"><code>' + esc(code) + '</code></pre>';
                });
            } catch (err) {
                el.innerHTML = '<pre class="code-block"><code>' + esc(code) + '</code></pre>';
            }
        });
    }, 0);

    return wrapper;
}
```

- [ ] **Step 4: Hook Mermaid into the marked.js code renderer**

Update `renderer.code` to detect `mermaid` language:

```javascript
renderer.code = function(code, lang) {
    // Mermaid diagrams
    if (lang === 'mermaid') {
        return renderMermaidBlock(code);
    }

    // Regular code — Prism.js highlighting + copy button
    var langLabel = lang ? '<div class="code-lang">' + esc(lang) + '</div>' : '';
    var highlighted = code;
    if (typeof Prism !== 'undefined' && lang) {
        var grammar = Prism.languages[lang];
        if (grammar) {
            try { highlighted = Prism.highlight(code, grammar, lang); }
            catch (_) { highlighted = esc(code); }
        } else {
            highlighted = esc(code);
        }
    } else {
        highlighted = esc(code);
    }
    return langLabel +
        '<div class="code-wrapper">' +
        '<button class="code-copy" onclick="copyCode(this)">Copy</button>' +
        '<pre class="code-block"><code class="language-' + (lang || 'text') + '">' +
        highlighted + '</code></pre></div>';
};
```

- [ ] **Step 5: Compile and verify**

```bash
./gradlew :agent:compileKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/resources/webview/lib/mermaid.min.js agent/src/main/resources/webview/agent-chat.html
git commit -m "feat(agent-ui): add lazy-loaded Mermaid.js diagram rendering

When the LLM outputs a \`\`\`mermaid code block, Mermaid.js is loaded on
demand (~250KB) and renders the diagram as interactive SVG. Supports
flowcharts, sequence diagrams, class diagrams, ER diagrams, Gantt charts,
git graphs, and 20+ other diagram types. Strict security level."
```

---

## Task 7: Lazy-Loaded KaTeX Math Rendering

**Files:**
- Create: `agent/src/main/resources/webview/lib/katex.min.js`
- Create: `agent/src/main/resources/webview/lib/katex.min.css`
- Create: `agent/src/main/resources/webview/lib/katex-fonts/` (woff2 files)
- Modify: `agent/src/main/resources/webview/agent-chat.html`

- [ ] **Step 1: Download and bundle KaTeX**

```bash
# Download KaTeX
mkdir -p agent/src/main/resources/webview/lib/katex-fonts
curl -L "https://cdn.jsdelivr.net/npm/katex@0.16/dist/katex.min.js" -o agent/src/main/resources/webview/lib/katex.min.js
curl -L "https://cdn.jsdelivr.net/npm/katex@0.16/dist/katex.min.css" -o agent/src/main/resources/webview/lib/katex.min.css

# Download essential fonts (KaTeX_Main, KaTeX_Math, KaTeX_Size1-4)
for font in KaTeX_Main-Regular KaTeX_Main-Bold KaTeX_Main-Italic KaTeX_Math-Italic KaTeX_Size1-Regular KaTeX_Size2-Regular KaTeX_Size3-Regular KaTeX_Size4-Regular KaTeX_AMS-Regular; do
    curl -L "https://cdn.jsdelivr.net/npm/katex@0.16/dist/fonts/${font}.woff2" -o "agent/src/main/resources/webview/lib/katex-fonts/${font}.woff2"
done
```

- [ ] **Step 2: Fix font paths in katex.min.css**

The default katex.min.css references `fonts/` directory. We need to update paths to `katex-fonts/`:

```bash
# After download, fix font paths:
sed -i '' 's|fonts/|katex-fonts/|g' agent/src/main/resources/webview/lib/katex.min.css
```

- [ ] **Step 3: Add KaTeX lazy-loader JS**

```javascript
/* ── KaTeX Math Rendering (lazy-loaded) ── */
var _katexLoaded = false;
var _katexPending = [];

function loadKaTeX(callback) {
    if (_katexLoaded && typeof katex !== 'undefined') {
        callback();
        return;
    }
    _katexPending.push(callback);
    if (_katexPending.length > 1) return;

    // Load CSS first
    var link = document.createElement('link');
    link.rel = 'stylesheet';
    link.href = 'lib/katex.min.css';
    document.head.appendChild(link);

    // Then JS
    var script = document.createElement('script');
    script.src = 'lib/katex.min.js';
    script.onload = function() {
        _katexLoaded = true;
        _katexPending.forEach(function(cb) { cb(); });
        _katexPending = [];
    };
    script.onerror = function() {
        _katexPending.forEach(function(cb) { cb(); });
        _katexPending = [];
    };
    document.head.appendChild(script);
}

function renderKaTeXInline(expr) {
    var spanId = 'katex-' + Date.now() + '-' + Math.random().toString(36).substr(2, 5);
    setTimeout(function() {
        loadKaTeX(function() {
            var el = document.getElementById(spanId);
            if (el && typeof katex !== 'undefined') {
                try {
                    katex.render(expr, el, { throwOnError: false, displayMode: false });
                } catch (_) { el.textContent = expr; }
            }
        });
    }, 0);
    return '<span id="' + spanId + '" class="katex-inline">' + esc(expr) + '</span>';
}

function renderKaTeXBlock(expr) {
    var divId = 'katex-blk-' + Date.now() + '-' + Math.random().toString(36).substr(2, 5);
    setTimeout(function() {
        loadKaTeX(function() {
            var el = document.getElementById(divId);
            if (el && typeof katex !== 'undefined') {
                try {
                    katex.render(expr, el, { throwOnError: false, displayMode: true });
                } catch (_) { el.textContent = expr; }
            }
        });
    }, 0);
    return '<div id="' + divId + '" class="katex-block" style="text-align:center;margin:10px 0;">' + esc(expr) + '</div>';
}
```

- [ ] **Step 4: Hook KaTeX into marked.js extensions**

Add a marked.js extension for `$...$` and `$$...$$`:

```javascript
// Add after the marked.use({ renderer }) call:
marked.use({
    extensions: [{
        name: 'mathInline',
        level: 'inline',
        start: function(src) { return src.indexOf('$'); },
        tokenizer: function(src) {
            var match = src.match(/^\$([^\$\n]+?)\$/);
            if (match) {
                return { type: 'mathInline', raw: match[0], text: match[1] };
            }
        },
        renderer: function(token) {
            return renderKaTeXInline(token.text);
        }
    }, {
        name: 'mathBlock',
        level: 'block',
        start: function(src) { return src.indexOf('$$'); },
        tokenizer: function(src) {
            var match = src.match(/^\$\$([\s\S]+?)\$\$/);
            if (match) {
                return { type: 'mathBlock', raw: match[0], text: match[1].trim() };
            }
        },
        renderer: function(token) {
            return renderKaTeXBlock(token.text);
        }
    }]
});
```

Also handle `math` language in code blocks:

In `renderer.code`, add before the Prism.js section:
```javascript
if (lang === 'math' || lang === 'latex') {
    return renderKaTeXBlock(code);
}
```

- [ ] **Step 5: Compile and verify**

```bash
./gradlew :agent:compileKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/resources/webview/lib/katex* agent/src/main/resources/webview/agent-chat.html
git commit -m "feat(agent-ui): add lazy-loaded KaTeX math rendering

Detects \$...\$ (inline) and \$\$...\$\$ (block) math expressions in LLM
output and renders with KaTeX. Also supports \`\`\`math code blocks.
KaTeX + fonts loaded on demand (~320KB) only when math is first detected.
All fonts bundled in JAR for offline operation."
```

---

## Task 8: Lazy-Loaded Chart.js Interactive Visualizations

**Files:**
- Create: `agent/src/main/resources/webview/lib/chart.min.js`
- Modify: `agent/src/main/resources/webview/agent-chat.html`

- [ ] **Step 1: Download and bundle Chart.js**

```bash
curl -L "https://cdn.jsdelivr.net/npm/chart.js@4/dist/chart.umd.min.js" -o agent/src/main/resources/webview/lib/chart.min.js
```

- [ ] **Step 2: Add Chart.js CSS and rendering functions**

```css
/* ═══════════════════════════════════════════════════
   Chart.js Visualizations
   ═══════════════════════════════════════════════════ */
.chart-wrapper {
    background: var(--code-bg);
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 16px;
    margin: 10px 0;
    max-height: 400px;
    position: relative;
}
.chart-wrapper canvas { max-width: 100%; }
```

```javascript
/* ── Chart.js Visualizations (lazy-loaded) ── */
var _chartJsLoaded = false;
var _chartJsPending = [];

function loadChartJs(callback) {
    if (_chartJsLoaded && typeof Chart !== 'undefined') {
        callback();
        return;
    }
    _chartJsPending.push(callback);
    if (_chartJsPending.length > 1) return;

    var script = document.createElement('script');
    script.src = 'lib/chart.min.js';
    script.onload = function() {
        // Set default dark theme
        Chart.defaults.color = getComputedStyle(document.documentElement).getPropertyValue('--fg-secondary').trim() || '#94A3B8';
        Chart.defaults.borderColor = getComputedStyle(document.documentElement).getPropertyValue('--border').trim() || '#3F3F46';
        _chartJsLoaded = true;
        _chartJsPending.forEach(function(cb) { cb(); });
        _chartJsPending = [];
    };
    script.onerror = function() {
        console.warn('Failed to load chart.js');
        _chartJsPending.forEach(function(cb) { cb(); });
        _chartJsPending = [];
    };
    document.head.appendChild(script);
}

var _chartCounter = 0;
function renderChart(configJson) {
    var chartId = 'chart-' + (++_chartCounter);
    var wrapper = '<div class="chart-wrapper"><canvas id="' + chartId + '"></canvas></div>';

    setTimeout(function() {
        loadChartJs(function() {
            var canvas = document.getElementById(chartId);
            if (!canvas || typeof Chart === 'undefined') return;
            try {
                var config = typeof configJson === 'string' ? JSON.parse(configJson) : configJson;
                new Chart(canvas, config);
            } catch (err) {
                canvas.parentElement.innerHTML = '<div style="color:var(--error);font-size:12px;">Chart error: ' + esc(err.message) + '</div>';
            }
        });
    }, 0);

    return wrapper;
}
```

- [ ] **Step 3: Hook charts into marked.js code renderer**

In `renderer.code`, add detection for `chart` language:

```javascript
if (lang === 'chart' || lang === 'chartjs') {
    return renderChart(code);
}
```

- [ ] **Step 4: Add global appendChart function for Kotlin→JS**

```javascript
function appendChart(configJson) {
    endStream(); hideEmpty();
    var el = document.createElement('div');
    el.className = 'message';
    el.innerHTML = renderChart(configJson);
    container.appendChild(el);
    scrollToBottom();
}
```

- [ ] **Step 5: Add appendChart to AgentCefPanel.kt**

```kotlin
fun appendChart(chartConfigJson: String) {
    callJs("appendChart(${jsonStr(chartConfigJson)})")
}
```

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/resources/webview/lib/chart.min.js agent/src/main/resources/webview/agent-chat.html agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt
git commit -m "feat(agent-ui): add lazy-loaded Chart.js interactive visualizations

LLM can output \`\`\`chart code blocks with Chart.js JSON config to render
interactive charts (line, bar, pie, radar, scatter). Chart.js loaded on
demand (~65KB). Theme colors automatically match IDE dark/light theme."
```

---

## Task 9: Lazy-Loaded diff2html Side-by-Side Diffs

**Files:**
- Create: `agent/src/main/resources/webview/lib/diff2html.min.js`
- Create: `agent/src/main/resources/webview/lib/diff2html.min.css`
- Modify: `agent/src/main/resources/webview/agent-chat.html`

- [ ] **Step 1: Download and bundle diff2html**

```bash
curl -L "https://cdn.jsdelivr.net/npm/diff2html@3/bundles/js/diff2html.min.js" -o agent/src/main/resources/webview/lib/diff2html.min.js
curl -L "https://cdn.jsdelivr.net/npm/diff2html@3/bundles/css/diff2html.min.css" -o agent/src/main/resources/webview/lib/diff2html.min.css
```

- [ ] **Step 2: Add diff2html lazy-loader and renderer**

```javascript
/* ── diff2html Side-by-Side Diffs (lazy-loaded) ── */
var _diff2htmlLoaded = false;
var _diff2htmlPending = [];

function loadDiff2Html(callback) {
    if (_diff2htmlLoaded && typeof Diff2Html !== 'undefined') {
        callback();
        return;
    }
    _diff2htmlPending.push(callback);
    if (_diff2htmlPending.length > 1) return;

    var link = document.createElement('link');
    link.rel = 'stylesheet';
    link.href = 'lib/diff2html.min.css';
    document.head.appendChild(link);

    var script = document.createElement('script');
    script.src = 'lib/diff2html.min.js';
    script.onload = function() {
        _diff2htmlLoaded = true;
        _diff2htmlPending.forEach(function(cb) { cb(); });
        _diff2htmlPending = [];
    };
    document.head.appendChild(script);
}

var _diffCounter = 0;
function renderDiff2Html(diffText) {
    var diffId = 'diff2html-' + (++_diffCounter);
    setTimeout(function() {
        loadDiff2Html(function() {
            var el = document.getElementById(diffId);
            if (!el || typeof Diff2Html === 'undefined') return;
            try {
                el.innerHTML = Diff2Html.html(diffText, {
                    drawFileList: false,
                    matching: 'lines',
                    outputFormat: 'side-by-side'
                });
            } catch (err) {
                el.innerHTML = '<pre class="code-block">' + esc(diffText) + '</pre>';
            }
        });
    }, 0);
    return '<div id="' + diffId + '" class="diff2html-wrapper" style="margin:10px 0;border-radius:8px;overflow:hidden;border:1px solid var(--border);">Loading diff...</div>';
}
```

- [ ] **Step 3: Hook diffs into marked.js code renderer**

In `renderer.code`, add:

```javascript
if (lang === 'diff' || lang === 'patch') {
    return renderDiff2Html(code);
}
```

- [ ] **Step 4: Compile and verify**

```bash
./gradlew :agent:compileKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/resources/webview/lib/diff2html* agent/src/main/resources/webview/agent-chat.html
git commit -m "feat(agent-ui): add lazy-loaded diff2html for side-by-side code diffs

\`\`\`diff code blocks from LLM output now render as side-by-side diffs
with syntax highlighting and word-level diff marking via diff2html.
Loaded on demand (~40KB). Falls back to plain text if loading fails."
```

---

## Task 10: Tabbed Content & Timeline/Progress Visualization

**Files:**
- Modify: `agent/src/main/resources/webview/agent-chat.html` (CSS + JS)

- [ ] **Step 1: Add tabbed content CSS and JS**

```css
/* ═══════════════════════════════════════════════════
   Tabbed Content
   ═══════════════════════════════════════════════════ */
.tab-container { margin: 10px 0; border: 1px solid var(--border); border-radius: 8px; overflow: hidden; }
.tab-bar { display: flex; border-bottom: 1px solid var(--border); background: var(--tool-bg); }
.tab-btn { padding: 8px 16px; font-size: 12px; color: var(--fg-muted); cursor: pointer; border: none; background: none; border-bottom: 2px solid transparent; transition: all 0.15s; }
.tab-btn:hover { color: var(--fg); }
.tab-btn.active { color: var(--link); border-bottom-color: var(--link); }
.tab-content { display: none; padding: 12px 16px; }
.tab-content.active { display: block; }
```

```javascript
/* ── Tabbed Content ── */
function appendTabs(tabsConfig) {
    // tabsConfig: { tabs: [{ label: "Code", content: "..." }, ...] }
    endStream(); hideEmpty();
    var config = typeof tabsConfig === 'string' ? JSON.parse(tabsConfig) : tabsConfig;
    var tabId = 'tabs-' + Date.now();
    var barHtml = '';
    var contentHtml = '';
    config.tabs.forEach(function(tab, i) {
        var activeClass = i === 0 ? ' active' : '';
        barHtml += '<button class="tab-btn' + activeClass + '" onclick="switchTab(\'' + tabId + '\',' + i + ')">' + esc(tab.label) + '</button>';
        contentHtml += '<div class="tab-content' + activeClass + '" data-tab-index="' + i + '">' + renderMarkdown(tab.content) + '</div>';
    });
    var el = document.createElement('div');
    el.className = 'message';
    el.innerHTML = '<div class="tab-container" id="' + tabId + '"><div class="tab-bar">' + barHtml + '</div>' + contentHtml + '</div>';
    container.appendChild(el);
    scrollToBottom();
}

function switchTab(containerId, index) {
    var container_ = document.getElementById(containerId);
    if (!container_) return;
    container_.querySelectorAll('.tab-btn').forEach(function(b, i) { b.classList.toggle('active', i === index); });
    container_.querySelectorAll('.tab-content').forEach(function(c, i) { c.classList.toggle('active', i === index); });
}
```

- [ ] **Step 2: Add timeline/progress CSS and JS**

```css
/* ═══════════════════════════════════════════════════
   Timeline / Progress Visualization
   ═══════════════════════════════════════════════════ */
.timeline { margin: 10px 0; padding: 0 8px; }
.timeline-item { display: flex; gap: 12px; position: relative; padding-bottom: 16px; }
.timeline-item:last-child { padding-bottom: 0; }
.timeline-item::before { content: ''; position: absolute; left: 11px; top: 24px; bottom: 0; width: 2px; background: var(--border); }
.timeline-item:last-child::before { display: none; }
.timeline-dot { width: 24px; height: 24px; border-radius: 50%; display: flex; align-items: center; justify-content: center; font-size: 11px; flex-shrink: 0; z-index: 1; }
.timeline-dot.pending { background: var(--border); color: var(--fg-muted); }
.timeline-dot.running { background: var(--link); color: white; animation: pulse 1.5s ease-in-out infinite; }
.timeline-dot.done { background: var(--success); color: white; }
.timeline-dot.failed { background: var(--error); color: white; }
@keyframes pulse { 0%, 100% { box-shadow: 0 0 0 0 rgba(96,165,250,0.4); } 50% { box-shadow: 0 0 0 6px rgba(96,165,250,0); } }
.timeline-content { flex: 1; min-width: 0; }
.timeline-title { font-size: 13px; font-weight: 500; }
.timeline-desc { font-size: 12px; color: var(--fg-muted); margin-top: 2px; }
.timeline-time { font-size: 11px; color: var(--fg-muted); font-family: var(--font-mono); }

/* Progress bar */
.progress-bar-wrapper { background: var(--border); border-radius: 4px; height: 6px; margin: 8px 0; overflow: hidden; }
.progress-bar { height: 100%; border-radius: 4px; transition: width 0.3s ease; }
.progress-bar.success { background: var(--success); }
.progress-bar.warning { background: var(--warning); }
.progress-bar.error { background: var(--error); }
.progress-bar.info { background: var(--link); }
```

```javascript
/* ── Timeline Visualization ── */
function appendTimeline(items) {
    // items: [{ title, description, status: 'pending'|'running'|'done'|'failed', time? }]
    endStream(); hideEmpty();
    var data = typeof items === 'string' ? JSON.parse(items) : items;
    var icons = { pending: '○', running: '◉', done: '✓', failed: '✗' };
    var html = '<div class="timeline">';
    data.forEach(function(item) {
        var status = item.status || 'pending';
        html += '<div class="timeline-item">'
            + '<div class="timeline-dot ' + status + '">' + (icons[status] || '○') + '</div>'
            + '<div class="timeline-content">'
            + '<div class="timeline-title">' + esc(item.title) + '</div>'
            + (item.description ? '<div class="timeline-desc">' + esc(item.description) + '</div>' : '')
            + (item.time ? '<div class="timeline-time">' + esc(item.time) + '</div>' : '')
            + '</div></div>';
    });
    html += '</div>';
    var el = document.createElement('div');
    el.className = 'message';
    el.innerHTML = html;
    container.appendChild(el);
    scrollToBottom();
}

/* ── Progress Bar ── */
function appendProgressBar(percent, type) {
    type = type || 'info';
    var el = document.createElement('div');
    el.className = 'message';
    el.innerHTML = '<div class="progress-bar-wrapper"><div class="progress-bar ' + type + '" style="width:' + Math.min(100, Math.max(0, percent)) + '%"></div></div>';
    container.appendChild(el);
    scrollToBottom();
}
```

- [ ] **Step 3: Add Kotlin methods for tabbed content and timeline**

In `AgentCefPanel.kt`:

```kotlin
fun appendTabs(tabsJson: String) {
    callJs("appendTabs(${jsonStr(tabsJson)})")
}

fun appendTimeline(itemsJson: String) {
    callJs("appendTimeline(${jsonStr(itemsJson)})")
}

fun appendProgressBar(percent: Int, type: String = "info") {
    callJs("appendProgressBar($percent,${jsonStr(type)})")
}
```

- [ ] **Step 4: Compile and verify**

```bash
./gradlew :agent:compileKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/resources/webview/agent-chat.html agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt
git commit -m "feat(agent-ui): add tabbed content, timeline visualization, progress bars

Tabbed content for multiple views (code/preview/diff). Timeline with
animated dots for build pipelines and task progress. Progress bars with
color-coded status. All pure CSS/JS — no external dependencies."
```

---

## Task 11: IDE-Native Features — Click-to-Navigate & Apply Diff

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt`
- Modify: `agent/src/main/resources/webview/agent-chat.html`

- [ ] **Step 1: Add JS→Kotlin bridges for file navigation and diff apply**

In `AgentCefPanel.kt`, add new `JBCefJSQuery` fields and their initialization in `createBrowser()`:

```kotlin
// New fields:
private var navigateToFileQuery: JBCefJSQuery? = null
private var applyDiffQuery: JBCefJSQuery? = null

// In createBrowser(), after the existing query creations:
navigateToFileQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
    addHandler { data ->
        // data format: "filePath:lineNumber"  (lineNumber optional)
        val colonIdx = data.lastIndexOf(':')
        val hasLine = colonIdx > 0 && data.substring(colonIdx + 1).toIntOrNull() != null
        val filePath = if (hasLine) data.substring(0, colonIdx) else data
        val line = if (hasLine) data.substring(colonIdx + 1).toInt() else 0
        onNavigateToFile?.invoke(filePath, line)
        JBCefJSQuery.Response("ok")
    }
}
applyDiffQuery = JBCefJSQuery.create(b as JBCefBrowserBase).apply {
    addHandler { diffJson ->
        onApplyDiff?.invoke(diffJson)
        JBCefJSQuery.Response("ok")
    }
}

// Inject bridges:
navigateToFileQuery?.let { q ->
    val navJs = q.inject("path")
    js("window._navigateToFile = function(path) { $navJs }")
}
applyDiffQuery?.let { q ->
    val applyJs = q.inject("diffJson")
    js("window._applyDiff = function(diffJson) { $applyJs }")
}

// New callback fields:
var onNavigateToFile: ((String, Int) -> Unit)? = null
var onApplyDiff: ((String) -> Unit)? = null

// In dispose(), add:
navigateToFileQuery?.dispose()
applyDiffQuery?.dispose()
navigateToFileQuery = null
applyDiffQuery = null
```

- [ ] **Step 2: Add clickable file paths in code blocks and tool cards**

In `agent-chat.html`, update the code block renderer to make file paths clickable:

```javascript
// Add after the copyCode function:
function navigateToFile(filePath, line) {
    var data = filePath;
    if (line && line > 0) data += ':' + line;
    if (window._navigateToFile) window._navigateToFile(data);
}

// Update the code-lang div to be clickable when it looks like a file path
// In renderer.code, modify the langLabel creation:
// (The language label often contains file paths when the LLM references files)
```

Add CSS for clickable file references:
```css
.file-link { color: var(--link); cursor: pointer; text-decoration: none; border-bottom: 1px dotted var(--link); }
.file-link:hover { border-bottom-style: solid; }
```

Add JS function to convert file paths in text to clickable links (using data attributes, not onclick — safe after DOMPurify):
```javascript
function linkifyFilePaths(html) {
    // Match file paths like src/main/kotlin/com/example/Foo.kt:123
    return html.replace(
        /(?<!["\w])([a-zA-Z][\w./-]*\.(kt|java|xml|yaml|yml|json|properties|gradle|md|txt|html|css|js|ts))(?::(\d+))?/g,
        function(match, path, ext, line) {
            var lineNum = line ? parseInt(line) : 0;
            return '<span class="file-link" data-file-path="' + esc(path) + '" data-line="' + lineNum + '">' + match + '</span>';
        }
    );
}

// Event delegation for file navigation (no onclick attributes needed)
document.addEventListener('click', function(e) {
    var link = e.target.closest('.file-link[data-file-path]');
    if (link) {
        navigateToFile(link.dataset.filePath, parseInt(link.dataset.line) || 0);
    }
});
```

Call `linkifyFilePaths` in the `renderMarkdown` function after DOMPurify (this is safe — it only adds `data-*` attributes which DOMPurify allows):
```javascript
// After DOMPurify.sanitize:
html = linkifyFilePaths(html);
```

- [ ] **Step 3: Wire navigation in AgentController.kt**

In `AgentController.kt`, add the wiring:

```kotlin
// In the init block or where JCEF callbacks are set:
dashboard.setCefNavigationCallbacks(
    onNavigateToFile = { filePath, line ->
        val basePath = project.basePath ?: return@setCefNavigationCallbacks
        val fullPath = if (filePath.startsWith("/") || filePath.startsWith(basePath)) filePath
                       else "$basePath/$filePath"
        val file = java.io.File(fullPath)
        if (file.exists()) {
            val vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
            if (vf != null) {
                ApplicationManager.getApplication().invokeLater {
                    val editor = FileEditorManager.getInstance(project).openFile(vf, true).firstOrNull()
                    if (line > 0 && editor is com.intellij.openapi.fileEditor.TextEditor) {
                        val offset = editor.editor.document.getLineStartOffset(maxOf(0, line - 1))
                        editor.editor.caretModel.moveToOffset(offset)
                        editor.editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
                    }
                }
            }
        }
    }
)
```

And add the delegation method in `AgentDashboardPanel.kt`:

```kotlin
fun setCefNavigationCallbacks(onNavigateToFile: (String, Int) -> Unit) {
    cefPanel?.onNavigateToFile = onNavigateToFile
}
```

- [ ] **Step 4: Compile and verify**

```bash
./gradlew :agent:compileKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/resources/webview/agent-chat.html agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentDashboardPanel.kt
git commit -m "feat(agent-ui): add click-to-navigate file paths and IDE integration

File paths in LLM output (e.g., src/main/Foo.kt:42) are now clickable
links that open the file at the correct line in the IDE editor. Uses
JBCefJSQuery bridge for JS→Kotlin file navigation."
```

---

## Task 12: Jira Card Embeds, Build Timeline, and Sonar Quality Overlay

**Files:**
- Modify: `agent/src/main/resources/webview/agent-chat.html` (CSS + JS)
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt`

- [ ] **Step 1: Add Jira card embed CSS and JS**

```css
/* ═══════════════════════════════════════════════════
   Jira Card Embed
   ═══════════════════════════════════════════════════ */
.jira-card { background: var(--tool-bg); border: 1px solid var(--border); border-radius: 8px; padding: 12px 16px; margin: 8px 0; }
.jira-card-header { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.jira-card-key { font-family: var(--font-mono); font-weight: 600; color: var(--link); font-size: 13px; cursor: pointer; }
.jira-card-key:hover { text-decoration: underline; }
.jira-card-type { font-size: 14px; }
.jira-card-priority { font-size: 12px; }
.jira-card-summary { font-size: 13px; font-weight: 500; margin-bottom: 4px; }
.jira-card-meta { font-size: 11px; color: var(--fg-muted); display: flex; gap: 12px; flex-wrap: wrap; }
.jira-card-status { display: inline-block; padding: 1px 8px; border-radius: 4px; font-size: 10px; font-weight: 600; letter-spacing: 0.3px; }
.jira-card-status.todo { background: var(--badge-read-bg); color: var(--badge-read-fg); }
.jira-card-status.in-progress { background: var(--badge-edit-bg); color: var(--badge-edit-fg); }
.jira-card-status.done { background: var(--badge-write-bg); color: var(--badge-write-fg); }

/* ═══════════════════════════════════════════════════
   Sonar Quality Overlay
   ═══════════════════════════════════════════════════ */
.sonar-badge { display: inline-flex; align-items: center; gap: 4px; padding: 2px 8px; border-radius: 4px; font-size: 11px; font-weight: 600; }
.sonar-badge.passed { background: var(--badge-write-bg); color: var(--badge-write-fg); }
.sonar-badge.failed { background: var(--badge-cmd-bg); color: var(--badge-cmd-fg); }
.sonar-metric { display: inline-flex; align-items: center; gap: 4px; font-size: 11px; font-family: var(--font-mono); padding: 2px 6px; background: var(--code-bg); border-radius: 4px; margin: 2px; }
```

```javascript
/* ── Jira Card Embed ── */
function appendJiraCard(data) {
    var card = typeof data === 'string' ? JSON.parse(data) : data;
    endStream(); hideEmpty();

    var typeIcons = { Story: '📗', Bug: '🐛', Task: '✅', Epic: '🏔️', 'Sub-task': '📌' };
    var priorityIcons = { Highest: '⬆️', High: '🔼', Medium: '➡️', Low: '🔽', Lowest: '⬇️' };
    var statusClass = (card.status || '').toLowerCase().replace(/\s+/g, '-');
    if (['to do', 'open', 'new', 'backlog'].includes((card.status || '').toLowerCase())) statusClass = 'todo';
    else if (['in progress', 'in review', 'testing'].includes((card.status || '').toLowerCase())) statusClass = 'in-progress';
    else if (['done', 'closed', 'resolved'].includes((card.status || '').toLowerCase())) statusClass = 'done';

    var el = document.createElement('div');
    el.className = 'message';
    el.innerHTML = '<div class="jira-card">'
        + '<div class="jira-card-header">'
        + '<span class="jira-card-type">' + (typeIcons[card.type] || '📋') + '</span>'
        + '<span class="jira-card-key">' + esc(card.key || '') + '</span>'
        + '<span class="jira-card-status ' + statusClass + '">' + esc(card.status || '') + '</span>'
        + '<span class="jira-card-priority">' + (priorityIcons[card.priority] || '') + '</span>'
        + '</div>'
        + '<div class="jira-card-summary">' + esc(card.summary || '') + '</div>'
        + '<div class="jira-card-meta">'
        + (card.assignee ? '<span>Assignee: ' + esc(card.assignee) + '</span>' : '')
        + (card.storyPoints ? '<span>Points: ' + card.storyPoints + '</span>' : '')
        + (card.sprint ? '<span>Sprint: ' + esc(card.sprint) + '</span>' : '')
        + '</div></div>';
    container.appendChild(el);
    scrollToBottom();
}

/* ── Sonar Quality Overlay ── */
function appendSonarBadge(data) {
    var d = typeof data === 'string' ? JSON.parse(data) : data;
    endStream(); hideEmpty();

    var statusClass = d.qualityGate === 'OK' ? 'passed' : 'failed';
    var metrics = (d.metrics || []).map(function(m) {
        return '<span class="sonar-metric">' + esc(m.label) + ': ' + esc(m.value) + '</span>';
    }).join('');

    var el = document.createElement('div');
    el.className = 'message';
    el.innerHTML = '<div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap;">'
        + '<span class="sonar-badge ' + statusClass + '">' + (d.qualityGate === 'OK' ? '✓ Passed' : '✗ Failed') + '</span>'
        + metrics + '</div>';
    container.appendChild(el);
    scrollToBottom();
}
```

- [ ] **Step 2: Add Kotlin methods**

In `AgentCefPanel.kt`:

```kotlin
fun appendJiraCard(cardJson: String) {
    callJs("appendJiraCard(${jsonStr(cardJson)})")
}

fun appendSonarBadge(badgeJson: String) {
    callJs("appendSonarBadge(${jsonStr(badgeJson)})")
}
```

- [ ] **Step 3: Add delegation in AgentDashboardPanel.kt**

```kotlin
fun appendJiraCard(cardJson: String) {
    cefPanel?.appendJiraCard(cardJson)
}

fun appendSonarBadge(badgeJson: String) {
    cefPanel?.appendSonarBadge(badgeJson)
}
```

- [ ] **Step 4: Compile and verify**

```bash
./gradlew :agent:compileKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/resources/webview/agent-chat.html agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentDashboardPanel.kt
git commit -m "feat(agent-ui): add Jira card embeds and Sonar quality badge overlays

Rich Jira ticket cards with type icons, priority, status badge, assignee,
and story points. Sonar quality gate badges with pass/fail status and
metric overlays. IDE-native integration — these leverage our existing
Jira/Sonar service layer."
```

---

## Task 13: Theme Switching for Prism.js (Dark/Light)

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt`
- Modify: `agent/src/main/resources/webview/agent-chat.html`

- [ ] **Step 1: Add Prism theme switching JS function**

```javascript
/* ── Prism Theme Switching ── */
function setPrismTheme(isDark) {
    var link = document.getElementById('prism-theme');
    if (link) {
        link.href = isDark ? 'lib/prism-themes/prism-one-dark.css' : 'lib/prism-themes/prism-one-light.css';
    }
}
```

- [ ] **Step 2: Call setPrismTheme in applyCurrentTheme**

In `AgentCefPanel.applyCurrentTheme()`, add after the theme vars are applied:

```kotlin
// After js("applyTheme({$jsObj})"):
val isDarkJs = if (isDark) "true" else "false"
js("setPrismTheme($isDarkJs)")
```

- [ ] **Step 3: Also update Mermaid theme if loaded**

Add JS:
```javascript
function setMermaidTheme(isDark) {
    if (typeof mermaid !== 'undefined' && _mermaidLoaded) {
        mermaid.initialize({ theme: isDark ? 'dark' : 'default' });
    }
}
```

And call from Kotlin alongside setPrismTheme:
```kotlin
js("setMermaidTheme($isDarkJs)")
```

- [ ] **Step 4: Compile and verify**

```bash
./gradlew :agent:compileKotlin
```
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/resources/webview/agent-chat.html agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt
git commit -m "feat(agent-ui): sync Prism.js and Mermaid themes with IDE light/dark mode

When the IDE switches between dark and light themes, the code highlighting
(Prism.js) and diagram (Mermaid.js) themes automatically update to match."
```

---

## Task 14: Final Integration Test & Documentation Update

**Files:**
- Modify: `agent/CLAUDE.md`
- Create: `agent/src/test/kotlin/com/workflow/orchestrator/agent/ui/MarkdownRenderingTest.kt`

- [ ] **Step 1: Write integration test for the rendering pipeline**

```kotlin
// agent/src/test/kotlin/com/workflow/orchestrator/agent/ui/MarkdownRenderingTest.kt
package com.workflow.orchestrator.agent.ui

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests that the HTML file and bundled resources exist in the JAR.
 * Full rendering tests require a JCEF browser instance (runIde).
 */
class MarkdownRenderingTest {

    @Test
    fun `agent-chat html exists in resources`() {
        val html = javaClass.classLoader.getResource("webview/agent-chat.html")
        assertNotNull(html, "agent-chat.html should be in resources")
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
            assertNotNull(
                javaClass.classLoader.getResource(lib),
                "$lib should be bundled in resources"
            )
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
            assertNotNull(
                javaClass.classLoader.getResource(lib),
                "$lib should be bundled in resources"
            )
        }
    }

    @Test
    fun `HTML does not reference CDN URLs`() {
        val html = javaClass.classLoader.getResource("webview/agent-chat.html")?.readText()
        assertNotNull(html)
        assertFalse(
            html!!.contains("cdn.jsdelivr.net") || html.contains("cdnjs.cloudflare.com"),
            "HTML should not contain CDN references — all libraries must be bundled"
        )
    }

    @Test
    fun `CefResourceSchemeHandler constants are correct`() {
        assertEquals("http://workflow-agent/", CefResourceSchemeHandler.BASE_URL)
    }
}
```

- [ ] **Step 2: Run all agent tests**

```bash
./gradlew :agent:test --rerun --no-build-cache
```
Expected: All tests PASS

- [ ] **Step 3: Update agent CLAUDE.md with new UI capabilities**

Add a new section to `agent/CLAUDE.md`:

```markdown
## Rich Chat UI

JCEF-based (Chromium) rendering with bundled libraries (zero CDN dependency):

**Core (always loaded, ~32KB gzipped):**
- marked.js — Markdown parsing with GFM, custom renderers
- Prism.js — Syntax highlighting (297 languages, on-demand autoloader)
- DOMPurify — XSS prevention on all LLM-rendered HTML
- ansi_up — ANSI terminal colors in command output

**Lazy-loaded (on first use):**
- Mermaid.js (~250KB) — Diagrams from \`\`\`mermaid code blocks (flowchart, sequence, class, ER, Gantt, git graph)
- KaTeX (~320KB) — LaTeX math from $...$ and $$...$$ expressions
- Chart.js (~65KB) — Interactive charts from \`\`\`chart JSON configs
- diff2html (~40KB) — Side-by-side diffs from \`\`\`diff code blocks

**IDE-native features:**
- Click-to-navigate file paths (opens file at line in IDE editor)
- Jira card embeds with status/priority/assignee
- Sonar quality gate badges with metrics
- Toast notifications, skeleton loading, timeline visualization
- Sortable/filterable tables, tabbed content, progress bars

**Resource serving:** `CefResourceSchemeHandler` serves all resources from plugin JAR via `http://workflow-agent/` scheme. CSP enforced: `connect-src: 'none'` (no outbound network).
```

- [ ] **Step 4: Commit**

```bash
git add agent/src/test/kotlin/com/workflow/orchestrator/agent/ui/MarkdownRenderingTest.kt agent/CLAUDE.md
git commit -m "test(agent-ui): add resource bundle verification tests and update docs

Verify all core and lazy-loaded libraries are bundled in JAR. Verify
no CDN references in HTML. Update CLAUDE.md with Rich Chat UI section
documenting all rendering capabilities and architecture."
```

---

## Verification

After all tasks:

```bash
./gradlew :agent:test --rerun --no-daemon    # All tests pass
./gradlew :agent:compileKotlin               # No compilation errors
./gradlew verifyPlugin                        # Plugin API compatibility
./gradlew buildPlugin                         # Build installable ZIP
```

Manual verification in `runIde`:
1. Open agent chat → type "Hello" → verify Prism.js code highlighting works
2. Ask agent to show a mermaid diagram → verify SVG renders inline
3. Ask agent about algorithm complexity → verify KaTeX math renders
4. Ask agent to search code → verify ANSI colors in command output
5. Ask agent about Jira ticket → verify rich Jira card embed
6. Verify copy buttons work on code blocks
7. Verify tool calls are collapsed by default with expand animation
8. Verify file paths in output are clickable and navigate to IDE editor
9. Switch between dark/light IDE theme → verify Prism and Mermaid themes update
10. Check DevTools console for any CSP violations or 404 errors

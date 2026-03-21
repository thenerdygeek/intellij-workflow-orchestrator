# Rich Chat UI Technologies Research

Research date: 2026-03-21

## Part 1: How Enterprise AI Coding Tools Render Rich Output

---

### 1. Claude (Anthropic) - Artifacts

**What they are:** Dedicated side panels for substantial, standalone content that appears alongside the conversation. Available on all plans (Free, Pro, Max, Team, Enterprise).

**Artifact types:**
- **Documents** - Markdown or plain text
- **Code snippets** - Syntax highlighted, multiple languages
- **Single-page HTML websites** - Full HTML/CSS/JS apps
- **SVG images** - Vector graphics rendered live
- **Diagrams and flowcharts** - Mermaid and custom
- **Interactive React components** - Full React apps with state

**Available libraries inside artifacts:**
- React (primary framework)
- Recharts (data visualization / charts)
- Lucide-react (icon library)
- Lodash (utility functions)
- PapaParse (CSV parsing)
- Shadcn/UI (component library)
- D3.js (data visualization)
- Pyodide (Python in WebAssembly, via CDN)
- Fabric.js (canvas manipulation)
- Standard HTML/CSS/JavaScript

**Sandboxing model:**
- Artifacts run in an **isolated iframe** on a separate domain (`claudeusercontent.com`)
- **Content Security Policy** permits: `unsafe-eval`, `unsafe-inline`, CDN resources from `cdnjs.cloudflare.com` and `cdn.jsdelivr.net`, blob: for workers
- Cannot make external API calls, submit forms, or link to other pages
- No access to user's system, browser storage, or Claude's backend

**Key takeaway:** Artifacts are the gold standard for rich AI output. The iframe sandbox + CSP approach is directly applicable to JCEF.

---

### 2. ChatGPT (OpenAI) - Canvas + Code Interpreter

**Canvas:**
- Side panel for collaborative editing of code and documents
- Inline editing with AI suggestions
- Version history with undo/redo
- Code can be run directly in the canvas
- Writing mode supports formatting, length adjustments, reading level changes

**Code Interpreter / Data Analysis:**
- Server-side Python execution in a sandboxed Jupyter environment
- Generates matplotlib/seaborn charts rendered as PNG images
- Interactive data tables for CSV/Excel analysis
- LaTeX math rendering using KaTeX in chat messages
- File upload and download capabilities
- Generated charts are static images (not interactive)

**Chat rendering features:**
- Markdown with GFM (tables, task lists, strikethrough)
- Syntax-highlighted code blocks with copy button
- LaTeX math (inline `$...$` and block `$$...$$`) via KaTeX
- Image display (DALL-E generated, uploaded, chart outputs)
- Collapsible "thinking" sections for reasoning models
- Streaming text with cursor animation

**Key takeaway:** ChatGPT's strength is server-side code execution producing static visualizations. Their chat markdown rendering with KaTeX math and copy buttons is polished.

---

### 3. Cursor IDE

**Chat rendering:**
- Standard markdown with syntax-highlighted code blocks
- **Inline diff previews** - Shows proposed code changes as diffs directly in the editor
- File references with clickable links to source
- "Apply" buttons on code blocks to directly apply changes to files
- Streaming responses with token-by-token rendering
- Collapsible tool call sections (file reads, searches, etc.)

**Notable features:**
- **Windsurf Previews** (competitor feature) - Live website preview in IDE, click elements to modify
- Code lens integrations for inline AI actions
- Terminal command execution with output display

**Key takeaway:** Cursor focuses on code-centric rendering: diffs, apply buttons, file references. Not heavy on visualizations.

---

### 4. GitHub Copilot Chat (VS Code / JetBrains)

**Rendering capabilities:**
- Markdown with GFM
- Syntax-highlighted code blocks
- **Inline diffs** for reviewing applied changes
- Image support (screenshots, UI mockups as context)
- Image carousel for multiple tool result images
- `#`-mentions for files, folders, and symbols (rendered as links)
- Streaming text responses
- Participant icons (@workspace, @terminal, etc.)

**JetBrains-specific:**
- Uses native Swing components (not JCEF) for the chat panel
- More limited rendering than VS Code version
- No Mermaid diagram support confirmed
- Code blocks with copy buttons

**Key takeaway:** Copilot Chat is conservative -- standard markdown + code blocks + diffs. The VS Code version is richer than JetBrains.

---

### 5. Windsurf / Cline / Aider

**Windsurf (Cascade):**
- **Live Previews** - Renders website directly in IDE, click-to-edit elements
- Inline code diff visualization
- Linter error auto-detection and display
- Code actions via codelenses
- Deep codebase context shown in responses

**Cline:**
- **Diff view editor** - Users can edit/revert changes directly in diff view
- Real-time linter/compiler error display
- Terminal output rendering from executed commands
- Browser screenshot capture with console logs
- **Checkpoint visualization** - Workspace snapshots at each step with "Compare" and "Restore" buttons
- File Timeline integration for tracking modifications
- Human-in-the-loop approval UI at each step

**Aider:**
- Terminal-based (no GUI chat panel)
- Colored terminal output with ANSI codes
- Git diff integration for showing changes
- Markdown rendering in terminal (via rich library)
- Minimal rich UI -- focuses on terminal workflow

**Key takeaway:** Cline's checkpoint/diff visualization is innovative. Windsurf's live preview is unique. Aider proves terminal-only can work.

---

## Part 2: Web Technologies for JCEF Chat Panel

---

### 2.1 Markdown Rendering: marked.js

| Attribute | Detail |
|-----------|--------|
| **Bundle size** | ~40 KB minified, ~13 KB gzipped |
| **CDN** | `https://cdn.jsdelivr.net/npm/marked/marked.min.js` |
| **JCEF compatible** | Yes, pure JS, no DOM dependencies at parse time |
| **Security** | Built-in sanitization options; pair with DOMPurify for XSS prevention |
| **Performance** | Very fast -- designed for "parsing markdown without blocking for long periods" |
| **Compliance** | Markdown 1.0 (100%), CommonMark 0.31 (98%), GFM 0.29 (97%) |
| **Extension system** | `marked.use()` for custom renderers, tokenizers, hooks |
| **Integration** | Custom renderer for code blocks can trigger syntax highlighting, Mermaid, KaTeX |

**How to extend for rich rendering:**
```
marked.use({
  renderer: {
    code(code, lang) {
      if (lang === 'mermaid') return renderMermaid(code);
      if (lang === 'math') return renderKaTeX(code);
      return highlightCode(code, lang);
    }
  }
});
```

---

### 2.2 Syntax Highlighting: Prism.js vs highlight.js

**Prism.js (recommended):**

| Attribute | Detail |
|-----------|--------|
| **Bundle size** | Core: ~2 KB min+gz; each language: ~0.3-0.5 KB; themes: ~1 KB |
| **CDN** | `https://cdn.jsdelivr.net/npm/prismjs/` (also cdnjs, unpkg) |
| **Languages** | 297 languages |
| **JCEF compatible** | Yes, pure JS + CSS |
| **Security** | No eval, no innerHTML concerns |
| **Performance** | Lightweight, supports Web Worker parallelism |
| **Integration** | Autoloader plugin fetches language definitions on-demand |
| **Special** | Semantic HTML5 markup, plugin architecture |

**highlight.js (alternative):**

| Attribute | Detail |
|-----------|--------|
| **Bundle size** | Core: ~40 KB min+gz with common languages; full: ~600 KB |
| **CDN** | `https://cdnjs.cloudflare.com/ajax/libs/highlight.js/11.11.1/` |
| **Languages** | 192 languages, 512 themes |
| **JCEF compatible** | Yes |
| **Performance** | Auto-detection adds overhead; explicit language hints recommended |

**Recommendation:** Prism.js for its tiny core size and on-demand language loading. Load only the languages the agent actually outputs.

---

### 2.3 Mermaid.js - Diagrams from Markdown

| Attribute | Detail |
|-----------|--------|
| **Bundle size** | ~200-250 KB minified (large due to bundled diagram renderers + D3 subset) |
| **CDN** | `https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.esm.min.mjs` |
| **JCEF compatible** | Yes, renders to SVG in the DOM |
| **Diagram types** | 25+ types: Flowchart, Sequence, Class, State, ER, Gantt, Pie, Git Graph, Mindmap, Timeline, Sankey, XY Chart, Kanban, Architecture, Radar, Treemap, Venn, and more |
| **Security** | Built-in sandboxed iframe mode that prevents JS execution in diagram code; XSS mitigation |
| **Performance** | Rendering is synchronous and can block UI for complex diagrams; use `mermaid.render()` (async) |
| **Integration** | Detect `language-mermaid` code blocks in marked.js custom renderer, replace with SVG output |

**Security consideration for JCEF:** Use Mermaid's `securityLevel: 'strict'` or `securityLevel: 'sandbox'` to prevent script injection through diagram syntax.

**Lazy loading strategy:** Mermaid is large. Load it only when a mermaid code block is detected in the response. Use dynamic `import()`.

---

### 2.4 Chart Libraries: Chart.js vs ECharts vs D3.js

**Chart.js (recommended for simplicity):**

| Attribute | Detail |
|-----------|--------|
| **Bundle size** | ~65 KB min+gz (with tree-shaking, can be smaller) |
| **CDN** | `https://cdn.jsdelivr.net/npm/chart.js` |
| **Renders via** | Canvas (GPU-accelerated) |
| **Chart types** | Line, Bar, Radar, Doughnut/Pie, Polar Area, Bubble, Scatter |
| **JCEF compatible** | Yes, uses Canvas API |
| **Performance** | Good for up to ~10K data points; Canvas is faster than SVG for large datasets |
| **Integration** | Create from JSON config; agent outputs chart config, UI renders it |
| **Interactivity** | Tooltips, zoom, pan (via plugins), click events |

**ECharts (recommended for advanced visualizations):**

| Attribute | Detail |
|-----------|--------|
| **Bundle size** | Full: ~350 KB min+gz; custom builds available via online builder |
| **CDN** | `https://cdn.jsdelivr.net/npm/echarts/dist/echarts.min.js` |
| **Renders via** | Canvas (default) or SVG (v4.0+), configurable |
| **Chart types** | 20+ types including: Line, Bar, Scatter, Pie, Candlestick, Boxplot, Heatmap, Map, Graph/Network, Treemap, Sunburst, Parallel, Funnel, Gauge, Sankey, plus custom series |
| **JCEF compatible** | Yes |
| **Performance** | Handles millions of data points via incremental rendering; TypedArray support for memory efficiency; WebSocket streaming support |
| **Integration** | JSON option config; `echarts.init(dom).setOption(config)` |
| **Special** | Themes, responsive, animation, toolbox (save as image, data view, zoom) |

**D3.js (for bespoke visualizations):**

| Attribute | Detail |
|-----------|--------|
| **Bundle size** | Full: ~90 KB min+gz; modular -- import only what you need |
| **CDN** | `https://cdn.jsdelivr.net/npm/d3@7` |
| **Renders via** | SVG (primary), Canvas, HTML |
| **JCEF compatible** | Yes |
| **Performance** | SVG rendering limits scalability to ~5K elements; Canvas mode better for large datasets |
| **Integration** | Imperative API -- requires code to generate visualizations, not just config |
| **Use case** | Custom/bespoke visualizations that Chart.js/ECharts can't handle |

**Recommendation:** Chart.js for standard charts (agent outputs JSON config). ECharts if you need advanced features like network graphs, treemaps, or Gantt. D3 only if building custom visualization types.

---

### 2.5 KaTeX - LaTeX Math Rendering

| Attribute | Detail |
|-----------|--------|
| **Bundle size** | JS: ~120 KB min+gz; CSS + fonts: ~200 KB additional |
| **CDN** | `https://cdn.jsdelivr.net/npm/katex@0.16/dist/katex.min.js` + `.css` |
| **JCEF compatible** | Yes, all major browsers supported |
| **Performance** | Much faster than MathJax (~100x); no reflow/relayout. Renders in a single pass |
| **Security** | No `eval()`, safe for user-provided LaTeX; some commands restricted by default |
| **Integration** | `katex.render(expression, element)` or `katex.renderToString(expression)` for HTML string |
| **Font loading** | Requires KaTeX fonts (woff2); use `font-display: block` to prevent FOUT. Can prefetch |
| **Markdown integration** | Detect `$...$` (inline) and `$$...$$` (block) in marked.js tokenizer extension |

**MathJax alternative:** Heavier (~500 KB), but supports more LaTeX commands and accessibility features. KaTeX is preferred for performance.

**Lazy loading:** Only load KaTeX when math expressions are detected in the response.

---

### 2.6 Excalidraw - Hand-drawn Diagrams

| Attribute | Detail |
|-----------|--------|
| **Bundle size** | Very large: ~1.5-2 MB (React dependency, fonts, icons) |
| **CDN** | Not practical via CDN; npm package `@excalidraw/excalidraw` |
| **JCEF compatible** | Yes, but requires React build pipeline |
| **Performance** | Heavy initial load; good runtime performance for drawing |
| **Integration** | React component `<Excalidraw />` with props for initial data, callbacks |
| **Embeddable** | Yes, supports iframe embedding with special tracking |
| **Security** | Standard React sandboxing |

**Verdict:** Too heavy for an IDE plugin chat panel. Better to use Mermaid for diagrams or render Excalidraw-style output as static SVG.

---

### 2.7 Monaco Editor - Embedded Code Editor

| Attribute | Detail |
|-----------|--------|
| **Bundle size** | ~2-4 MB (includes workers for languages, themes, etc.) |
| **CDN** | Not officially supported via CDN; npm `monaco-editor` |
| **JCEF compatible** | Yes, but requires web workers (http/https, not file://) |
| **Features** | Full VS Code editor: IntelliSense, syntax highlighting, code folding, minimap, find/replace, multi-cursor |
| **Diff editor** | Built-in side-by-side and inline diff view |
| **Integration** | `monaco.editor.create(container, { value, language })` |
| **Special** | Monarch tokenizers for custom languages, customizable themes |

**Verdict:** Way too heavy for displaying code in chat messages. Use Prism.js for display. Only consider Monaco if building an in-chat code editor feature (like Canvas).

---

### 2.8 diff2html - Code Diff Rendering

| Attribute | Detail |
|-----------|--------|
| **Bundle size** | ~30-40 KB min+gz (depends on highlight.js inclusion) |
| **CDN** | Available via npm; CDN via jsdelivr |
| **JCEF compatible** | Yes |
| **Features** | Line-by-line and side-by-side views, syntax highlighting (via highlight.js), smart line matching, word-level diff |
| **Integration** | Takes unified diff string, outputs HTML. `Diff2Html.html(diffString, options)` |
| **Used by** | Exercism, Codacy, Jenkins, many others |
| **Security** | Sanitizes output; no eval |

**Alternative: Custom diff rendering** using CSS with `+`/`-` line coloring. Simpler but less feature-rich.

---

### 2.9 ansi_up - Terminal ANSI Color Rendering

| Attribute | Detail |
|-----------|--------|
| **Bundle size** | Very small: ~5-8 KB minified (single file, zero dependencies) |
| **CDN** | Available via npm |
| **JCEF compatible** | Yes, pure JS |
| **Features** | Converts ANSI SGR codes to styled HTML SPANs; bold, italic, underline, faint; 256-color support; URL codes; HTML escaping |
| **Integration** | `new AnsiUp().ansi_to_html(text)` |
| **Security** | Escapes HTML reserved characters by default |

**Alternative: xterm.js** for full terminal emulation (used by VS Code terminal). Much heavier (~200 KB) but supports cursor movement, scrollback, selection, etc.

---

### 2.10 xterm.js - Terminal Emulation

| Attribute | Detail |
|-----------|--------|
| **Bundle size** | ~200 KB min+gz |
| **CDN** | npm `@xterm/xterm` |
| **JCEF compatible** | Yes |
| **Features** | Full terminal emulation, ANSI color support, cursor, scrollback, selection, search, links, Unicode, ligatures |
| **Used by** | VS Code, Azure Cloud Shell, JupyterLab, Hyper |
| **Integration** | `new Terminal(); terminal.open(container); terminal.write(data)` |
| **Performance** | GPU-accelerated WebGL renderer option |

**Verdict:** Overkill for displaying command output in chat. Use ansi_up for ANSI-to-HTML conversion. Reserve xterm.js only if building a full terminal widget.

---

### 2.11 Additional UI Components (Pure CSS/JS, No Library Needed)

These features require no external library -- just well-crafted HTML/CSS/JS:

**Collapsible sections:**
```html
<details><summary>Tool call: read_file</summary>content</details>
```
- Native HTML5, zero JS needed, works everywhere including JCEF
- Animated with CSS transitions on `max-height`

**Copy buttons on code blocks:**
- Small JS handler: `navigator.clipboard.writeText(codeContent)`
- Position absolutely in top-right of code block
- Visual feedback: icon changes to checkmark for 2 seconds

**Tabbed content:**
- CSS-only tabs using radio inputs, or minimal JS
- Useful for showing different views (code/preview/diff)

**Skeleton loading / streaming placeholders:**
- CSS animation: `@keyframes shimmer` with gradient background
- Replace with actual content as tokens stream in
- ~0 KB, pure CSS

**Toast notifications:**
- CSS slide-in/fade-out animation
- Position: fixed bottom-right
- Auto-dismiss with `setTimeout`
- ~0 KB external dependency

**Interactive/sortable tables:**
- Basic: CSS `table-layout: fixed` with `overflow: auto`
- Sortable: ~2 KB of JS for click-to-sort headers
- Filterable: ~1 KB of JS for search input filtering

**Timeline/progress visualization:**
- CSS flexbox/grid with colored segments
- Animated progress with CSS transitions
- Use for build pipeline status, task progress

**Image rendering:**
- Native `<img>` tag; base64 data URIs for inline images
- Lightbox effect with CSS overlay on click
- Lazy loading with `loading="lazy"`

---

## Part 3: Security Considerations for JCEF

### Content Security Policy (CSP)
For a JCEF browser panel serving local resources:
```
Content-Security-Policy:
  default-src 'self';
  script-src 'self' 'unsafe-inline' 'unsafe-eval'
    https://cdn.jsdelivr.net;
  style-src 'self' 'unsafe-inline'
    https://cdn.jsdelivr.net;
  img-src 'self' data: blob:;
  font-src 'self' https://cdn.jsdelivr.net;
  connect-src 'none';
  frame-src 'none';
```

- `unsafe-eval` needed for Mermaid.js (uses `new Function()`)
- `unsafe-inline` needed for dynamically injected styles and scripts
- `connect-src: 'none'` prevents any outbound network requests from the webview
- `frame-src: 'none'` prevents iframe creation (unless you want artifact-style sandboxing)

### Communication channel security
- All JS<->Kotlin communication via JBCefJSQuery (validated JSON only)
- Never inject unescaped user content into `executeJavaScript()`
- Always JSON-serialize data before injection
- Sanitize HTML output with DOMPurify before rendering markdown

### Resource loading
- Serve all libraries from plugin JAR via CefSchemeHandlerFactory (no CDN in production)
- CDN loading acceptable during development but should be bundled for release
- Custom scheme handler maps `http://myapp/*` to resources in JAR

---

## Part 4: Priority Matrix

### High Impact / Low Effort (Do First)

| Feature | Effort | Why High Impact |
|---------|--------|-----------------|
| **Markdown rendering (marked.js)** | 1-2 days | Foundation for ALL rich output. ~13 KB gzipped. |
| **Syntax-highlighted code blocks (Prism.js)** | 0.5 day | 2 KB core. Massive readability improvement for code. |
| **Copy buttons on code blocks** | 0.5 day | Zero dependencies. Users expect this. |
| **Collapsible sections** | 0.5 day | Native HTML5 `<details>`. Perfect for tool calls, thinking. |
| **Streaming text with cursor** | 1 day | CSS animation. Critical for perceived responsiveness. |
| **Skeleton loading** | 0.5 day | Pure CSS. Professional feel during streaming. |
| **Theme integration (dark/light)** | 1 day | CSS variables from IDE theme. Already researched in JCEF ref. |
| **ANSI terminal colors (ansi_up)** | 0.5 day | ~5 KB. Great for displaying command output. |
| **Toast notifications** | 0.5 day | Pure CSS/JS. Non-intrusive status feedback. |

**Total: ~6-7 days for all "quick wins"**

### High Impact / High Effort (Do Next)

| Feature | Effort | Why High Impact |
|---------|--------|-----------------|
| **Mermaid diagrams** | 2-3 days | ~250 KB but lazy-loadable. Huge for architecture visualization. |
| **KaTeX math rendering** | 1-2 days | ~320 KB with fonts. Important if agent discusses algorithms/formulas. |
| **Chart.js visualizations** | 2-3 days | ~65 KB. Agent can output chart configs for data visualization. |
| **Diff viewer (diff2html or custom)** | 2-3 days | ~40 KB. Critical for code change review in agent workflow. |
| **Interactive tables (sort/filter)** | 1-2 days | Custom JS. Great for Jira tickets, build results, test results. |
| **Tabbed content** | 1 day | Custom JS. Multiple views: code/preview/diff. |
| **Sandboxed HTML rendering** | 3-5 days | Like Claude artifacts. iframe sandbox for agent-generated apps. |
| **Timeline/progress visualization** | 1-2 days | CSS + minimal JS. Build pipelines, task progress. |

**Total: ~14-22 days for all "high effort" items**

### Low Impact (Nice to Have, Defer)

| Feature | Effort | Why Low Priority |
|---------|--------|------------------|
| **Excalidraw** | 5+ days | ~2 MB. Too heavy. Mermaid covers most diagram needs. |
| **Monaco Editor** | 5+ days | ~4 MB. Overkill for code display. Only if building Canvas-like editing. |
| **xterm.js terminal** | 3+ days | ~200 KB. ansi_up covers 90% of needs. |
| **ECharts** | 3+ days | ~350 KB. Chart.js sufficient unless needing exotic chart types. |
| **D3.js** | 5+ days | ~90 KB but requires custom code per visualization. |
| **SVG animations** | 2+ days | Nice polish but not functional. CSS animations sufficient. |
| **Full image lightbox** | 1 day | Useful but rare in coding agent context. |

---

## Part 5: Recommended Technology Stack for JCEF Chat Panel

### Core (bundle with plugin JAR):
1. **marked.js** (~13 KB gz) - Markdown parsing
2. **Prism.js** (~2 KB core + ~5 KB common languages gz) - Syntax highlighting
3. **DOMPurify** (~7 KB gz) - HTML sanitization
4. **ansi_up** (~5 KB gz) - Terminal color rendering
5. **Custom CSS** - Theme integration, collapsibles, copy buttons, skeleton loading, toasts

**Core total: ~32 KB gzipped**

### Lazy-loaded (load on first use):
6. **Mermaid.js** (~250 KB gz) - When first mermaid code block detected
7. **KaTeX** (~320 KB gz) - When first math expression detected
8. **Chart.js** (~65 KB gz) - When first chart config detected
9. **diff2html** (~40 KB gz) - When first diff block detected

### Architecture for JCEF integration:
```
Kotlin (Plugin) side:
  AgentBrowser.kt          -- JBCefBrowser wrapper
  ResourceSchemeHandler.kt -- Serves HTML/CSS/JS from JAR
  ThemeController.kt       -- Pushes IDE theme as CSS variables
  MessageBridge.kt         -- JBCefJSQuery JSON protocol

JavaScript (Webview) side:
  index.html               -- Shell with CSS variables
  chat.js                  -- Message handling, streaming, rendering pipeline
  markdown-renderer.js     -- marked.js + custom renderers for code/mermaid/math/charts
  theme.js                 -- Applies IDE theme CSS variables
  lib/
    marked.min.js
    prism.min.js + languages/
    purify.min.js
    ansi_up.min.js
  lazy/
    mermaid.min.js         -- Loaded on demand
    katex.min.js + .css    -- Loaded on demand
    chart.min.js           -- Loaded on demand
    diff2html.min.js       -- Loaded on demand
```

### Key design decisions:
1. **Bundle all JS in plugin JAR** - No CDN dependency in production. Serve via CefSchemeHandlerFactory.
2. **Lazy load heavy libraries** - Only mermaid/katex/chart.js/diff2html when first needed.
3. **Use marked.js custom renderers** - Single rendering pipeline with pluggable output types.
4. **Stream via executeJavaScript** - Token-by-token streaming with `window.appendToken()`.
5. **Bidirectional JSON protocol** - JBCefJSQuery for JS->Kotlin, executeJavaScript for Kotlin->JS.
6. **CSS variables for theming** - Push IDE theme colors as CSS custom properties.
7. **DOMPurify all rendered HTML** - Prevent XSS from model output.

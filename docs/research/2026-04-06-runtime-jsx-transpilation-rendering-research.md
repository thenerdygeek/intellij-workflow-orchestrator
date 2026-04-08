# Runtime JSX Transpilation & Rendering in the Browser

**Date:** 2026-04-06
**Purpose:** Architectural decision research for rendering LLM-generated JSX/React code as live, interactive components in a JCEF-based browser environment (similar to Claude.ai Artifacts).

---

## Table of Contents

1. [Executive Summary](#executive-summary)
2. [Transpiler Comparison](#transpiler-comparison)
   - [Sucrase](#1-sucrase)
   - [SWC (WASM)](#2-swc-wasm)
   - [Babel Standalone](#3-babel-standalone)
   - [esbuild-wasm](#4-esbuild-wasm)
3. [Higher-Level Frameworks](#higher-level-frameworks)
   - [react-runner](#5-react-runner)
   - [Sandpack](#6-sandpack)
   - [Renderify](#7-renderify)
   - [OpenUI](#8-openui)
4. [How Claude.ai Artifacts Works](#how-claudeai-artifacts-works)
5. [How Vercel v0.dev Works](#how-vercel-v0dev-works)
6. [How LibreChat Artifacts Works](#how-librechat-artifacts-works)
7. [Security & Sandboxing](#security--sandboxing)
8. [Providing React as a Global](#providing-react-as-a-global)
9. [Error Handling](#error-handling)
10. [JCEF Compatibility](#jcef-compatibility)
11. [Recommendation](#recommendation)

---

## Executive Summary

There are four tiers of solutions for rendering LLM-generated JSX at runtime in a browser:

| Tier | Approach | Complexity | Bundle Size | Best For |
|------|----------|-----------|-------------|----------|
| **1. Lightweight** | Sucrase + `new Function()` | Low | ~120 KB gzipped | Fast, minimal artifact preview |
| **2. Integrated** | react-runner (uses Sucrase) | Low | ~130 KB gzipped | Drop-in React component rendering |
| **3. Full sandbox** | Sandpack (CodeSandbox bundler) | Medium | External iframe bundler | Full IDE-like preview with npm deps |
| **4. WASM-powered** | SWC/esbuild WASM | High | 8-16 MB WASM | Server-grade transpilation in browser |

**Claude.ai uses react-runner (Sucrase-based)** inside a sandboxed iframe on a separate domain. This is the proven production approach for the exact use case described.

**Recommended approach for JCEF:** react-runner inside a sandboxed iframe, with React/libraries provided as globals, communicating via postMessage. This gives the best balance of size (~130 KB), speed (~20x faster than Babel), security (cross-origin iframe isolation), and simplicity.

---

## Transpiler Comparison

### 1. Sucrase

**What it is:** A super-fast alternative to Babel focused exclusively on modern syntax transforms (JSX, TypeScript, Flow). It does NOT target old browsers -- it assumes a modern runtime.

**Package size:**
- npm unpacked: ~1.1 MB (190 files, includes source maps and CJS/ESM builds)
- Minified browser bundle: ~280 KB
- Minified + gzipped: ~80-90 KB (estimated; there is a dedicated `sucrase-browser` npm package)
- The `sucrase-browser` package exists specifically for browser usage

**API:**
```javascript
import { transform } from "sucrase";

const result = transform(code, {
  transforms: ["jsx", "typescript"],
  jsxRuntime: "classic",       // "classic" | "automatic" | "preserve"
  production: false,
  filePath: "component.tsx",
});
const transpiledCode = result.code;
```

**Supported transforms:**
- `jsx` -- converts JSX to `React.createElement` (classic) or `jsx`/`jsxs` (automatic)
- `typescript` -- strips type annotations, handles enums, decorators
- `flow` -- strips Flow type annotations
- `imports` -- converts ESM to CommonJS (usually NOT needed in browser)
- `react-hot-loader` -- HMR support
- `jest` -- jest.mock hoisting

**Performance:**
- ~637,000 lines/second (benchmark from Sucrase repo)
- ~20x faster than Babel
- ~8x faster than TypeScript compiler
- Benchmark: 100K lines of code in ~470ms (vs Babel's ~9,600ms)

**JSX + TypeScript:** Full support. Both can be enabled simultaneously via `transforms: ["jsx", "typescript"]`.

**Limitations:**
- Does NOT do bundling or import resolution
- Does NOT transform for old browsers (no polyfills, no downleveling)
- No plugin system (unlike Babel)
- Assumes modern JS runtime (Chrome 61+, Firefox 60+, Safari 10.1+)

**JCEF compatibility:** Excellent. Pure JavaScript, no WASM dependency, fast parse/transform. JCEF (Chromium-based) is a modern runtime.

---

### 2. SWC (WASM)

**What it is:** A Rust-based JavaScript/TypeScript compiler compiled to WebAssembly for browser use via `@swc/wasm-web`.

**Package size:**
- npm unpacked: ~15.8 MB (5 files)
- The WASM binary itself: ~12-14 MB uncompressed
- Gzipped WASM: ~5-7 MB (WASM compresses well, typically <50% of original)
- JS wrapper: ~50 KB

**API:**
```javascript
import initSwc, { transformSync } from "@swc/wasm-web";

// Must initialize WASM first (async)
await initSwc();

const result = transformSync(code, {
  jsc: {
    parser: {
      syntax: "typescript",
      tsx: true,
      decorators: false,
      dynamicImport: true,
    },
    transform: {
      react: {
        runtime: "automatic",  // or "classic"
        pragma: "React.createElement",
        pragmaFrag: "React.Fragment",
        development: false,
        useBuiltins: false,
      }
    },
    target: "es2022",
  }
});
const transpiledCode = result.code;
```

**Performance:**
- 20x faster than Babel on single thread, 70x on four cores (native)
- WASM version is significantly slower than native -- roughly on par with Sucrase in browser
- First load penalty: WASM compilation + download time for ~5-7 MB gzipped binary
- After initialization, transforms are fast

**JSX + TypeScript:** Full support via `jsc.parser.syntax: "typescript"` with `tsx: true`.

**Limitations:**
- Large WASM binary (~12-14 MB) must be downloaded and compiled
- WASM compilation on first load adds several hundred ms to seconds
- More complex API surface than Sucrase
- `transformSync` blocks the main thread; no async transform in WASM build

**JCEF compatibility:** Works (Chromium supports WASM), but the large binary size is a concern for an embedded environment. Loading 5-7 MB of WASM for transpilation seems excessive when Sucrase achieves comparable in-browser performance at ~90 KB.

---

### 3. Babel Standalone

**What it is:** The full Babel compiler packaged as a single file for browser use. Includes all presets and plugins.

**Package size:**
- npm unpacked: ~38.4 MB (7 files)
- Minified browser bundle (`babel.min.js`): ~2.8-3.2 MB
- Minified + gzipped: ~900 KB - 1.1 MB
- There was a proposal for a JSX-only minimal build (issue #14567) but the prototype still came out at ~1.08 MB minified -- Babel's parser/core cannot be effectively tree-shaken

**API:**
```html
<script src="https://unpkg.com/@babel/standalone/babel.min.js"></script>
<script type="text/babel" data-presets="react,typescript">
  // JSX code here -- automatically transpiled
</script>
```

Or programmatic:
```javascript
const result = Babel.transform(code, {
  presets: ["react", "typescript"],
  filename: "component.tsx",
});
const transpiledCode = result.code;
```

**Performance:**
- ~39,000 lines/second
- ~20x SLOWER than Sucrase
- Benchmark: 100K lines in ~9,600ms
- Still fast enough for single-component transpilation (a 500-line component: ~13ms)

**JSX + TypeScript:** Full support. Includes all Babel presets and plugins for React JSX, TypeScript, Flow, and every proposal-stage syntax.

**Limitations:**
- Enormous bundle size (~1 MB gzipped) -- unacceptable for an embedded environment
- Cannot produce a smaller JSX-only build (parser is monolithic)
- Slower than all alternatives
- Overkill: includes Stage 1 proposals, decorators, module transforms that are never needed for this use case

**JCEF compatibility:** Works technically, but the ~1 MB gzipped download and slower performance make it the worst choice for an embedded browser.

---

### 4. esbuild-wasm

**What it is:** The esbuild bundler/transpiler compiled to WebAssembly for browser use.

**Package size:**
- npm unpacked: ~14.5 MB (15 files)
- WASM binary: ~11 MB uncompressed
- Gzipped WASM: ~4-5 MB
- JS wrapper: ~100 KB

**API:**
```javascript
import * as esbuild from "esbuild-wasm";

await esbuild.initialize({
  wasmURL: "https://unpkg.com/esbuild-wasm@0.28.0/esbuild.wasm",
});

// Simple transform (single file, no bundling)
const result = await esbuild.transform(jsxCode, {
  loader: "tsx",
  target: "es2020",
  jsx: "transform",        // or "automatic"
  jsxFactory: "React.createElement",
  jsxFragment: "React.Fragment",
});
const transpiledCode = result.code;

// Full build with import resolution (via plugins)
const buildResult = await esbuild.build({
  entryPoints: ["app.tsx"],
  bundle: true,
  write: false,
  plugins: [virtualFilePlugin],
  external: ["react", "react-dom"],
});
```

**Performance:**
- Native esbuild: fastest bundler available
- WASM version: ~10x slower than native (per esbuild docs)
- Still faster than Babel, roughly comparable to Sucrase for simple transforms
- Advantage: can do full bundling + tree-shaking + import resolution in browser

**JSX + TypeScript:** Full support via `loader: "tsx"` and JSX configuration options.

**Unique advantage:** Only browser-side option that can do **full bundling** (not just transpilation). Can resolve imports, tree-shake, and produce optimized output. Useful if artifacts need to import multiple files or npm packages.

**Limitations:**
- Large WASM binary (~4-5 MB gzipped)
- Go's WASM compilation is single-threaded
- Memory usage can be high for large bundles (reported 4+ GB for some npm packages)
- `transform()` cannot resolve imports -- need `build()` with plugins for that
- Complex plugin API for virtual file systems

**JCEF compatibility:** Works, but same concern as SWC about binary size. The bundling capability is its differentiator, but for single-component artifacts this is overkill.

---

## Transpiler Summary Table

| Feature | Sucrase | SWC WASM | Babel Standalone | esbuild-wasm |
|---------|---------|----------|-----------------|--------------|
| **Bundle (gzipped)** | ~90 KB | ~5-7 MB | ~1 MB | ~4-5 MB |
| **JSX support** | Yes | Yes | Yes | Yes |
| **TypeScript** | Yes (strip) | Yes (full) | Yes (full) | Yes (strip) |
| **Speed (lines/s)** | ~637K | ~500K* | ~39K | ~600K* |
| **Init time** | Instant | 200-500ms WASM | Instant (JS) | 200-500ms WASM |
| **Import resolution** | No | No | No | Yes (via build) |
| **Plugin system** | No | Yes | Yes | Yes |
| **API simplicity** | Simple | Medium | Simple | Medium |
| **JCEF fit** | Excellent | Poor (size) | Poor (size) | Poor (size) |

*WASM in-browser speed estimates; native is much faster.

---

## Higher-Level Frameworks

### 5. react-runner

**What it is:** A library that wraps Sucrase to transpile and render React code at runtime. **This is what Claude.ai uses for Artifacts.**

**Package size:**
- react-runner itself: ~56 KB unpacked
- Dependency: Sucrase (~1.1 MB unpacked, ~90 KB gzipped browser bundle)
- Total gzipped: ~100-130 KB (excluding React itself)

**API:**
```javascript
import { Runner, useRunner } from "react-runner";

// Component approach
<Runner
  code={code}
  scope={scope}
  onRendered={({ error }) => { /* handle error */ }}
/>

// Hook approach (with caching)
const { element, error } = useRunner({ code, scope });
```

**Scope (providing libraries):**
```javascript
const scope = {
  // Direct globals
  React,
  useState: React.useState,
  useEffect: React.useEffect,
  // etc.

  // Import resolution
  import: {
    "recharts": Recharts,
    "lucide-react": LucideIcons,
    "@/components/ui/button": { Button },
    "./utils": importCode(utilsSource, baseScope),
  }
};
```

**Key features:**
- Handles `import` statements via scope.import mapping
- Supports function components, class components, inline JSX
- TypeScript support (via Sucrase transforms: ["jsx", "typescript"])
- Error handling via `error` return value
- SSR support
- Multi-file support via `importCode()` utility
- Browser targets: Chrome >61, Edge >16, Firefox >60, Safari >10.1

**How it works internally:**
1. Transpiles code with Sucrase (JSX + optional TypeScript)
2. Wraps transpiled code in a function scope with provided globals
3. Executes the function to get the exported component
4. Renders the component via React

**JCEF compatibility:** Excellent. Small bundle, pure JS, fast transpilation, battle-tested (used by Claude.ai).

---

### 6. Sandpack

**What it is:** CodeSandbox's open-source in-browser bundler/runtime, packaged as React components.

**Package size:**
- `@codesandbox/sandpack-client`: ~67 MB unpacked (includes Nodebox runtime)
- `@codesandbox/sandpack-react`: ~20 MB unpacked
- The actual bundler runs in a **separate iframe** loaded from CodeSandbox CDN or self-hosted
- The bundler iframe uses code-splitting and loads transpilers on demand
- Latest bundler rewritten in Rust/WASM: "hot start time of 500ms" for Vite templates

**Architecture:**
1. Parent page includes `sandpack-client` or `sandpack-react`
2. An iframe is created pointing to the bundler URL (CDN or self-hosted)
3. Source files are sent to the iframe via postMessage
4. The bundler transpiles, resolves imports, fetches npm dependencies from CDN
5. The bundled code executes inside the iframe
6. Hot module replacement (HMR) for live updates

**Self-hosting:**
```javascript
new SandpackClient(iframe, sandboxInfo, {
  bundlerURL: "https://your-hosted-bundler.example.com",
});
```

Self-hosting requires cloning the `codesandbox-client` repo, building with `yarn build:sandpack`, and deploying the `www/` output.

**Key features:**
- Full npm dependency resolution (fetches from CDN)
- Multiple template support (React, Vue, Svelte, Vanilla, Node.js)
- Hot Module Replacement
- Code splitting (transpilers loaded on demand)
- Web Worker-based transpilation
- Service Worker isolation between previews
- Built-in CodeMirror editor
- Error overlay

**Limitations:**
- Heavy: external iframe + CDN dependency (or complex self-hosting)
- Latency: npm dependency fetching adds noticeable delay
- Overkill for single-component rendering
- Complex architecture (Service Workers, Web Workers, CDN fetching)
- Used by LibreChat for its artifacts feature

**JCEF compatibility:** Works, but adds significant complexity. The external iframe loading and CDN dependency fetching may have latency issues in an embedded environment. Self-hosting the entire CodeSandbox bundler is a large operational burden.

---

### 7. Renderify

**What it is:** A runtime engine specifically designed for rendering LLM-generated UI in the browser. Open source, February 2026.

**Architecture:** Seven-package monorepo (ir, security, runtime, core, llm, SDK, CLI).

**Transpilation:** Uses Babel Standalone with LRU caching (256 entries).

**Import resolution:** JSPM CDN-based. Pipeline: lexical extraction (es-module-lexer) -> JSPM resolution -> recursive dependency materialization -> import rewriting to blob URLs -> dynamic import().

**React strategy:** Redirects React imports to Preact compat (~3-4 KB gzipped), avoiding the full React bundle.

**Sandboxing:** Three modes with automatic fallback: sandbox-worker (Web Worker), sandbox-iframe, sandbox-shadowrealm. All modes enforce execution budgets via Promise.race().

**Security:** Seven-layer defense including static pattern analysis, structural limits, tag blocklist, module allowlist, manifest integrity, execution budgets, sandbox isolation.

**Streaming:** Supports progressive rendering as LLM tokens arrive, using FNV-1a hashing for efficient change detection.

**Limitations:**
- Uses Babel Standalone (large bundle)
- JSPM dependency fetching adds latency
- Complex seven-package architecture may be overkill
- Relatively new (February 2026)

---

### 8. OpenUI

**What it is:** A full-stack Generative UI framework with a custom streaming-first language for LLM-generated UI.

**Approach:** Unlike the others, OpenUI does NOT transpile arbitrary JSX. Instead, it defines a custom compact language ("OpenUI Lang") that the LLM outputs, and a registered component library that constrains what can be rendered. Components must be pre-defined with Zod schemas.

**Token efficiency:** 45-67% fewer tokens than equivalent JSON-based approaches.

**Key insight:** By restricting to a pre-defined component vocabulary (not arbitrary JSX), OpenUI avoids the transpilation/security problem entirely. The LLM generates a compact DSL, and the renderer maps it to registered React components.

**Not applicable** if the goal is rendering arbitrary LLM-generated JSX code (like Claude Artifacts). Relevant if the goal is a structured, constrained generative UI system.

---

## How Claude.ai Artifacts Works

Based on reverse engineering by Reid Barber and community analysis:

**Architecture:**
1. Claude generates JSX/TSX code as part of the artifact response
2. The code is sent via `window.postMessage()` to a sandboxed iframe
3. The iframe is hosted on `https://www.claudeusercontent.com` (different domain = cross-origin isolation)
4. Inside the iframe, a Next.js application runs
5. **react-runner** (which uses Sucrase internally) transpiles and renders the JSX
6. The rendered component appears in the artifact preview panel

**Available libraries pre-loaded in the iframe:**
- React 18 + ReactDOM
- Tailwind CSS (for styling)
- Shadcn/UI (Radix Primitives-based component library)
- Recharts (charting)
- Lucide React (icons)
- Three.js (3D graphics)
- DOMPurify (HTML sanitization)
- React Hot Loader
- React Zoom Pan Pinch

**Security model:**
- Cross-origin iframe (different domain) prevents parent DOM access
- DOMPurify for HTML sanitization
- External scripts restricted to `https://cdnjs.cloudflare.com` only
- No localStorage/sessionStorage access
- No external API calls
- Console output captured and forwarded to parent
- 20 MB storage limit per artifact (Pro/Enterprise)

**Constraints in system prompt:**
- Components must have no required props (or provide defaults)
- Must use default export
- No arbitrary Tailwind values (e.g., no `h-[600px]`)
- No web images; only placeholders

**Communication:** Parent -> iframe via postMessage (code to render). Iframe -> parent via postMessage (render status, errors, console output).

---

## How Vercel v0.dev Works

**Architecture (evolved significantly):**
- **Pre-2025:** Browser-based preview, likely using transpilation similar to Sandpack
- **2025+:** Moved to **Vercel Sandbox** -- lightweight VMs, not just browser-based preview
- Each prompt generates code in a real Next.js environment
- The sandbox can run server-side code, handle APIs, databases, authentication
- Code is generated as full Next.js projects (App Router or Pages Router)
- Uses Tailwind CSS + shadcn/ui for styling

**Key difference from Claude Artifacts:** v0.dev is no longer purely browser-based. It runs actual server-side environments (micro-VMs), not just in-browser transpilation. This is a fundamentally heavier architecture but supports full-stack applications.

**Not directly applicable** to an embedded JCEF environment, but informative as a direction the industry is moving.

---

## How LibreChat Artifacts Works

**Architecture:**
- Uses CodeSandbox's **Sandpack** library as the rendering engine
- The bundler runs in a sandboxed iframe
- Default: connects to CodeSandbox public CDN (`*.codesandbox.io`)
- CSP requirement: `frame-src 'self' https://*.codesandbox.io`

**Self-hosting option:**
- LibreChat provides a forked `codesandbox-client` repo with telemetry removed
- Configuration: `SANDPACK_BUNDLER_URL=http://your-bundler-url`
- Service Worker isolation between previews
- Full CORS policy management required

**Artifact types:** React components, HTML/JavaScript, Mermaid diagrams.

**Trade-offs vs Claude approach:**
- Sandpack is heavier but supports full npm dependency resolution
- External CDN dependency (or complex self-hosting)
- More mature bundling pipeline but more operational complexity

---

## Security & Sandboxing

### Recommended Architecture: Cross-Origin Sandboxed iframe

The industry-standard approach (used by Claude.ai, CodePen, JSFiddle, Sandpack):

```html
<!-- Host page (JCEF browser) -->
<iframe
  id="artifact-preview"
  src="https://artifact-sandbox.local/renderer.html"
  sandbox="allow-scripts"
  referrerpolicy="no-referrer"
  style="width: 100%; height: 100%; border: none;"
></iframe>
```

**Key sandbox attributes:**
- `allow-scripts` -- permit JavaScript execution (required)
- **Do NOT include** `allow-same-origin` -- keeps iframe in opaque origin, preventing parent access
- **Do NOT include** `allow-top-navigation` -- prevents iframe from navigating parent
- **Do NOT include** `allow-popups` -- prevents window.open

**Without `allow-same-origin`**, the iframe:
- Cannot access parent's DOM, cookies, or localStorage
- Cannot read parent's URL or location
- Gets an opaque `null` origin
- Can only communicate via `postMessage`

### Communication Protocol

```javascript
// === HOST (JCEF page) ===

// Send code to render
iframe.contentWindow.postMessage({
  type: "render",
  code: jsxCode,
  libraries: ["recharts", "lucide-react"],
}, "*");

// Receive results
window.addEventListener("message", (event) => {
  // Validate origin for non-opaque iframe sources
  if (event.data.type === "render-success") {
    // Component rendered successfully
  } else if (event.data.type === "render-error") {
    showError(event.data.error);
  } else if (event.data.type === "console") {
    forwardToConsole(event.data.messages);
  }
});

// === SANDBOX (iframe) ===

window.addEventListener("message", (event) => {
  if (event.data.type === "render") {
    try {
      const { element, error } = renderComponent(event.data.code);
      if (error) {
        parent.postMessage({ type: "render-error", error: error.message }, "*");
      } else {
        parent.postMessage({ type: "render-success" }, "*");
      }
    } catch (e) {
      parent.postMessage({ type: "render-error", error: e.message }, "*");
    }
  }
});
```

### For JCEF Specifically

JCEF loads HTML via `loadURL()` or `loadHTML()`. Two approaches:

**Option A: Data URL / loadHTML (simpler, no server)**
```kotlin
val html = buildArtifactRendererHtml(reactVersion, libraries)
cefBrowser.loadHTML(html, "https://artifact-sandbox.local")
```
The iframe content is embedded inline. The `sandbox` attribute still provides isolation.

**Option B: Resource handler (more control)**
Register a custom scheme handler for `artifact-sandbox://` that serves the renderer HTML and libraries from bundled resources. This gives full control over CSP headers.

### Alternative: new Function() with Scope Isolation

For lighter-weight scenarios (no full iframe), code can be executed via `new Function()`:

```javascript
"use strict";
const renderFn = new Function(
  "React", "useState", "useEffect", "useRef",
  "exports",
  transpiledCode + "\nreturn exports.default || exports;"
);

const Component = renderFn(
  React, React.useState, React.useEffect, React.useRef,
  {}
);
```

**Risks:** No DOM isolation, no origin separation, prototype pollution possible. Should only be used inside an already-sandboxed iframe, never on the host page.

### Defense Layers (Recommended)

1. **Cross-origin iframe** -- primary isolation barrier
2. **CSP on iframe document** -- `script-src 'unsafe-eval'` (needed for new Function), `connect-src 'none'`, `img-src data: blob:`
3. **DOMPurify** -- sanitize any HTML output
4. **Object.freeze prototypes** -- prevent prototype pollution in sandbox
5. **Execution timeout** -- `Promise.race()` with timeout to kill infinite loops
6. **Static analysis** -- scan for `eval(`, `fetch(`, `document.cookie`, etc. before transpilation

---

## Providing React as a Global

The transpiled code needs React, hooks, and possibly other libraries. Three approaches:

### Approach 1: window.React Global (Claude.ai's Approach)

The iframe's HTML pre-loads React and libraries as `<script>` tags:

```html
<!-- Inside sandbox iframe -->
<script src="https://esm.sh/react@18.3.1/umd/react.production.min.js"></script>
<script src="https://esm.sh/react-dom@18.3.1/umd/react-dom.production.min.js"></script>
<script src="https://cdn.tailwindcss.com"></script>
<!-- Additional libraries loaded as UMD globals -->

<script>
  // React is now available as window.React
  // Transpiled code using React.createElement works immediately
</script>
```

**Pros:** Simple, no double-bundling, fast.
**Cons:** Global namespace pollution, version conflicts if host also uses React.
**For JCEF:** This is fine -- the iframe is isolated, so globals don't leak to the host.

### Approach 2: Import Maps (Modern Browsers)

```html
<script type="importmap">
{
  "imports": {
    "react": "https://esm.sh/react@18.3.1",
    "react-dom": "https://esm.sh/react-dom@18.3.1",
    "react-dom/client": "https://esm.sh/react-dom@18.3.1/client",
    "react/jsx-runtime": "https://esm.sh/react@18.3.1/jsx-runtime",
    "recharts": "https://esm.sh/recharts@2.15.0?deps=react@18.3.1",
    "lucide-react": "https://esm.sh/lucide-react@0.460.0?deps=react@18.3.1"
  }
}
</script>
```

**Pros:** Standard ESM, clean import syntax, no globals.
**Cons:** Requires `jsxRuntime: "automatic"` in transpiler (generates `import from "react/jsx-runtime"`), requires network access for CDN, import maps are static (cannot be updated after page load).
**For JCEF:** Viable but adds complexity. Network dependency for CDN fetches may cause latency.

### Approach 3: Scope Injection (react-runner's Approach)

Pass libraries via the scope object, mapped to import specifiers:

```javascript
const scope = {
  import: {
    react: React,
    "react-dom": ReactDOM,
    recharts: Recharts,
    "lucide-react": LucideIcons,
  }
};

// react-runner intercepts import statements in transpiled code
// and resolves them against the scope.import map
```

**Pros:** No network dependency, no globals needed, version controlled.
**Cons:** Must pre-bundle all allowed libraries into the iframe.
**For JCEF:** Best approach. Bundle React + allowed libraries into the iframe HTML. No CDN dependency, no import maps, predictable behavior.

### Recommended for JCEF

**Approach 3 (scope injection) inside Approach 1 (global scripts).** Pre-load React and libraries as UMD globals in the sandbox iframe, then pass them via react-runner's scope. This avoids CDN dependency while keeping imports clean:

```html
<!-- Sandbox iframe HTML (served from JCEF resource handler) -->
<script>/* React 18 UMD production build, inlined */</script>
<script>/* ReactDOM 18 UMD production build, inlined */</script>
<script>/* Recharts UMD build, inlined */</script>
<script>/* Lucide React UMD build, inlined */</script>
<script>/* react-runner + Sucrase, inlined */</script>

<script>
  const scope = {
    import: {
      "react": window.React,
      "react-dom": window.ReactDOM,
      "recharts": window.Recharts,
      "lucide-react": window.LucideIcons,
    }
  };

  window.addEventListener("message", (event) => {
    renderWithScope(event.data.code, scope);
  });
</script>
```

---

## Error Handling

### Three Categories of Errors

**1. Transpilation errors (Sucrase parse failures)**
```javascript
try {
  const result = transform(code, { transforms: ["jsx", "typescript"] });
  return { code: result.code, error: null };
} catch (e) {
  return {
    code: null,
    error: {
      type: "transpilation",
      message: e.message,
      // Sucrase provides line/column info in error messages
      line: extractLineFromError(e),
      column: extractColumnFromError(e),
    }
  };
}
```

**2. Runtime errors (component execution failures)**
```javascript
// react-runner returns errors via the hook
const { element, error } = useRunner({ code, scope });

if (error) {
  // error is a JavaScript Error object
  // error.message contains the runtime error description
  parent.postMessage({
    type: "render-error",
    error: {
      type: "runtime",
      message: error.message,
      stack: error.stack,
    }
  }, "*");
}
```

**3. React render errors (caught by Error Boundary)**
```jsx
class ArtifactErrorBoundary extends React.Component {
  state = { hasError: false, error: null };

  static getDerivedStateFromError(error) {
    return { hasError: true, error };
  }

  componentDidCatch(error, errorInfo) {
    // Forward to parent
    parent.postMessage({
      type: "render-error",
      error: {
        type: "react-render",
        message: error.message,
        componentStack: errorInfo.componentStack,
      }
    }, "*");
  }

  render() {
    if (this.state.hasError) {
      return <div className="error-fallback">
        <h3>Component Error</h3>
        <pre>{this.state.error?.message}</pre>
      </div>;
    }
    return this.props.children;
  }
}

// Wrap the rendered artifact
<ArtifactErrorBoundary>
  <Runner code={code} scope={scope} />
</ArtifactErrorBoundary>
```

### Console Capture

Intercept console methods inside the sandbox to forward to the host:

```javascript
const originalConsole = { ...console };
["log", "warn", "error", "info"].forEach(method => {
  console[method] = (...args) => {
    originalConsole[method](...args);
    parent.postMessage({
      type: "console",
      method,
      args: args.map(arg =>
        typeof arg === "object" ? JSON.stringify(arg) : String(arg)
      ),
    }, "*");
  };
});
```

### Infinite Loop Protection

Use execution timeouts and/or code instrumentation:

```javascript
// Approach 1: Promise.race timeout
const renderWithTimeout = (code, scope, timeoutMs = 5000) => {
  return Promise.race([
    new Promise((resolve) => {
      const { element, error } = renderComponent(code, scope);
      resolve({ element, error });
    }),
    new Promise((_, reject) => {
      setTimeout(() => reject(new Error("Execution timeout")), timeoutMs);
    }),
  ]);
};

// Approach 2: Inject loop counters (more reliable for sync infinite loops)
function instrumentLoops(code) {
  let counter = 0;
  const MAX_ITERATIONS = 100000;
  // Inject counter increment at start of every loop body
  return code.replace(
    /(while\s*\([^)]*\)\s*\{|for\s*\([^)]*\)\s*\{)/g,
    `$1 if(++__loopCounter > ${MAX_ITERATIONS}) throw new Error("Infinite loop detected");`
  );
}
```

---

## JCEF Compatibility

JCEF (Java Chromium Embedded Framework) is a Chromium-based browser embedded in IntelliJ. Key considerations:

**What works in JCEF:**
- Full JavaScript execution (ES2022+)
- WebAssembly (Chromium has native WASM support)
- iframe sandboxing (standard Chromium security model)
- postMessage between frames
- eval() and new Function() (unless blocked by CSP)
- CSS (Tailwind, etc.)
- SVG rendering
- Canvas / WebGL (Three.js)

**JCEF-specific considerations:**
- `JBCefBrowser.loadHTML()` can load inline HTML content
- `JBCefBrowser.loadURL()` can load from file:// or custom scheme
- `JBCefJSQuery` enables Java -> JS and JS -> Java communication
- Custom scheme handlers can serve resources from the plugin's bundled files
- No network dependency needed -- all resources can be bundled into the plugin

**Recommended JCEF integration:**

```kotlin
// Kotlin side (IntelliJ plugin)
val browser = JBCefBrowser()
val renderQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)

// Handle render results from JS
renderQuery.addHandler { result ->
  // Process render status, errors, console output
  JBCefJSQuery.Response(null) // no response needed
}

// Load the sandbox HTML (bundled with plugin)
val sandboxHtml = loadResourceAsString("/artifact-sandbox.html")
browser.loadHTML(sandboxHtml, "https://artifact-sandbox.local")

// When LLM generates JSX code:
fun renderArtifact(jsxCode: String) {
  val escapedCode = jsxCode.escapeForJavaScript()
  browser.cefBrowser.executeJavaScript(
    "window.postMessage({ type: 'render', code: `$escapedCode` }, '*');",
    "https://artifact-sandbox.local",
    0
  )
}
```

**All four transpilers work in JCEF**, but Sucrase / react-runner is the clear winner for size and simplicity.

---

## Recommendation

### Primary Recommendation: react-runner + Sucrase in Sandboxed iframe

This is the **Claude.ai proven approach** adapted for JCEF:

**Architecture:**
```
[IntelliJ Plugin] --JBCefJSQuery--> [JCEF Browser]
                                         |
                                    [Host Page]
                                         |
                                    postMessage
                                         |
                                  [Sandboxed iframe]
                                    - React 18 (UMD)
                                    - react-runner + Sucrase
                                    - Tailwind CSS
                                    - Shadcn/UI
                                    - Recharts
                                    - Lucide React
                                    - Error Boundary
```

**Total iframe bundle (gzipped estimates):**
| Library | Gzipped Size |
|---------|-------------|
| React 18 + ReactDOM | ~42 KB |
| react-runner + Sucrase | ~100-130 KB |
| Tailwind CSS (CDN) | ~30 KB |
| Recharts | ~60 KB |
| Lucide React (tree-shaken) | ~20 KB |
| Shadcn/UI components | ~30-50 KB |
| **Total** | **~280-330 KB** |

All of this can be inlined into a single HTML file served from plugin resources. No network dependency.

**Why this over alternatives:**
- **vs Babel Standalone:** 10x smaller, 20x faster
- **vs SWC/esbuild WASM:** 50-100x smaller, no WASM loading delay
- **vs Sandpack:** Much simpler, no CDN dependency, no Service Worker complexity, no self-hosting burden
- **vs Renderify:** Simpler architecture, smaller bundle, proven in production at Claude.ai scale
- **vs OpenUI:** Supports arbitrary JSX (not just pre-defined components)

### Alternative: Upgrade Path to Sandpack

If the feature evolves to need:
- Full npm dependency resolution (user imports arbitrary packages)
- Multi-file projects (not just single components)
- Node.js server-side preview
- Full IDE experience in artifact panel

Then Sandpack becomes the right choice (as LibreChat chose). But for the initial Claude Artifacts-like feature, react-runner is the right starting point.

---

## Sources

### Transpilers
- [Sucrase GitHub](https://github.com/alangpierce/sucrase)
- [SWC WASM Web documentation](https://swc.rs/docs/usage/wasm)
- [SWC Configuration](https://swc.rs/docs/configuration/compilation)
- [@babel/standalone documentation](https://babeljs.io/docs/babel-standalone)
- [Babel standalone JSX-only build proposal](https://github.com/babel/babel/issues/14567)
- [esbuild-wasm getting started](https://esbuild.github.io/getting-started/)
- [Compiling React in the browser with esbuild-wasm](https://www.cacoos.com/blog/compiling-in-the-browser)

### Frameworks
- [react-runner GitHub](https://github.com/nihgwu/react-runner)
- [Sandpack documentation](https://sandpack.codesandbox.io/)
- [Sandpack bundler hosting guide](https://sandpack.codesandbox.io/docs/guides/hosting-the-bundler)
- [Sandpack Rust transpiler journey](https://codesandbox.io/blog/the-journey-to-a-faster-sandpack-transpiler)
- [Renderify: Runtime engine for LLM-generated UI](https://dev.to/unadlib/renderify-a-runtime-engine-for-rendering-llm-generated-ui-instantly-in-the-browser-1amf)
- [OpenUI: Generative UI framework](https://github.com/thesysdev/openui)

### Production References
- [Reverse engineering Claude Artifacts](https://www.reidbarber.com/blog/reverse-engineering-claude-artifacts)
- [Claude Artifact Runner (community)](https://github.com/claudio-silva/claude-artifact-runner)
- [LibreChat Artifacts documentation](https://www.librechat.ai/docs/features/artifacts)
- [LibreChat CodeSandbox client fork](https://github.com/LibreChat-AI/codesandbox-client)
- [Vercel v0.dev documentation](https://v0.app/docs/faqs)

### Security
- [JavaScript sandbox architecture deep dive](https://alexgriss.tech/en/blog/javascript-sandboxes/)
- [Building secure code sandbox with iframe isolation](https://medium.com/@muyiwamighty/building-a-secure-code-sandbox-what-i-learned-about-iframe-isolation-and-postmessage-a6e1c45966df)
- [MDN Content-Security-Policy](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Content-Security-Policy)

### JCEF
- [IntelliJ JCEF documentation](https://plugins.jetbrains.com/docs/intellij/embedded-browser-jcef.html)
- [JCEF GitHub](https://github.com/chromiumembedded/java-cef)

### Import Maps & ESM
- [ESM.sh CDN](https://esm.sh/)
- [Import maps guide](https://www.honeybadger.io/blog/import-maps/)
- [ES Modules + Import Maps](https://www.stevendcoffey.com/blog/esmodules-importmaps-modern-js-stack/)

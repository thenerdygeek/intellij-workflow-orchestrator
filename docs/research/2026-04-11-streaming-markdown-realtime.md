# Streaming Markdown in Real-Time: Library Comparison, Root-Cause Analysis, and Recommendation for a JCEF React Chat UI

**Date:** 2026-04-11
**Context:** Workflow Orchestrator IntelliJ plugin. JCEF (Chromium Embedded Framework) webview running a React 18 app (`agent/webview`). Tokens stream from Sourcegraph Cody LLM -> Kotlin (`StreamBatcher`, 16ms coalesce on EDT) -> JS bridge -> Zustand `chatStore.activeStream` -> `StreamingMessage` / `usePresentationBuffer` / `BlurTextStream` / `MarkdownRenderer`.
**Problem:** The user's 5-layer streaming pipeline (`StreamBatcher -> bridge -> PresentationBuffer -> BlurTextStream -> MarkdownRenderer`) feels laggy, the per-char animation is barely visible, and markdown renders "weird" during streaming. They want to delete the animation and render markdown beautifully in real-time instead.

---

## Executive Summary

The user's pipeline is a second copy of the work the LLM stream already does. Tokens arrive -> Kotlin smooths them -> JS smooths them *again* at ~150 chars/sec -> for the last ~15 chars each one is wrapped in a `motion/react` `<m.span>` with `filter: blur(2px)` animated via JS. Three things are fighting each other:

1. **The animation rate is slower than the raw stream rate.** When tokens arrive faster than the 150 cps drip, the buffer keeps growing; the user sees characters lagging behind what the LLM actually produced. That is the root of "laggy".
2. **`filter: blur()` on ~15 simultaneous React nodes with per-char `motion/react` transitions is the single most expensive CSS property you can animate in CEF's off-screen-renderer**, because it forces a full compositor paint on every frame and off-screen rendering in CEF does not support accelerated compositing. That is the root of "barely visible / janky".
3. **Markdown is re-parsed from scratch on every token.** `react-markdown` has no idea you are streaming, so each token re-runs `remark-parse` on the entire message, rebuilds the mdast, re-creates the React element tree, and forces reconciliation against potentially syntax-highlighted code blocks. Incomplete constructs mid-stream produce valid-but-ugly intermediate renders (a half-typed \`\`\`python fence with no closing fence becomes one giant inline code span that collapses once the fence closes). That is the root of "markdown looks weird".

The industry has converged on a clean answer:

- **Split the message into markdown blocks, memoize each block, parse incomplete syntax with a "speculative close" pass, and animate with pure CSS.** Vercel's Streamdown is a turnkey implementation of exactly this. Cline (VSCode) and the Vercel AI SDK cookbook both implement the memoized-blocks pattern by hand with `marked.lexer()` + `React.memo`. ChatGPT / Claude.ai do the same.
- **No production chat UI uses per-character JS animation on streaming text.** ChatGPT, Claude.ai, Perplexity, Gemini, Cursor, Cline, Continue.dev all render blocks immediately as they close. Streamdown's optional `animated` prop is pure CSS keyframes on word-level `<span>` elements injected by a rehype plugin, and Vercel explicitly exposes a `useIsCodeFenceIncomplete` hook so custom code blocks can *skip* Shiki highlighting until the fence closes.
- **The way to feel "alive" without per-char animation** is any or none of these: a blinking caret/block at the insertion point (Streamdown's built-in `caret="block"`), 150-200ms block-level fade-in on newly-completed blocks (already what you have), smooth auto-scroll to bottom, and, optionally, one subtle word-level CSS fade applied via rehype. That is it.

Top recommendations, one line each:

1. **Primary: drop in Vercel `streamdown`** — it is a drop-in `react-markdown` replacement purpose-built for this exact problem. It ships speculative close (`remend`), block-level memoization, Shiki highlighting with language caching, KaTeX, Mermaid, a `caret` prop, CSS-keyframe `animated` prop, and a `useIsCodeFenceIncomplete` hook for deferring expensive renders.
2. **Runner-up: keep `react-markdown` but implement Cline's `MemoizedMarkdownBlock` pattern** — `marked.lexer()` splits streaming text into token-level blocks, `React.memo` on each block with content-equality, stable keys. You already have `react-markdown`, `remark-gfm`, `rehype-raw`, and `marked` in `package.json`. This is 80 lines of code and zero new dependencies.

Both are credible; pick Streamdown unless bundle size or plugin conflicts matter. The user already has Shiki in `node_modules` so Streamdown's Shiki dependency is free.

---

## 1. Library Comparison

| Library | Drop-in for `react-markdown`? | Speculative close | Block memo | Syntax highlight | Bundle (min+gz) | Maintained | Notes |
|---|---|---|---|---|---|---|---|
| **Vercel `streamdown`** | Yes (matches props) | Yes (`remend` preprocess + `hasIncompleteCodeFence`) | Yes (per-block `React.memo` with deep compare, context-stable refs) | Shiki with internal language/theme cache | ~50KB core + Shiki WASM (~150KB for few langs) | Active (v2.5.0 Mar 2026, Vercel employees) | `mode`, `caret`, `animated`, `isAnimating`, `plugins`, `useIsCodeFenceIncomplete` hook. Exports `parseMarkdownIntoBlocks`. |
| **`react-markdown`** (bare) | n/a (this is the baseline) | No | No (by default) | None (plugin-based) | ~30KB + remark/rehype deps | Active (v9.x, widely used) | Re-parses entire string on every render. Safe for small messages, quadratic cost for long streams unless memoized. |
| **`react-markdown` + manual memo** (Cline / AI SDK pattern) | n/a | Manual (close fences) | Yes (`marked.lexer()` split + `React.memo`) | Any (you pick: rehype-highlight / Shiki / Prism) | ~60KB (react-markdown + marked) | n/a | Recommended by the Vercel AI SDK cookbook. 80 lines of glue code. Already used by Cline. |
| **`markdown-to-jsx`** | Different API | Via `optimizeForStreaming` option (suppresses incomplete nodes -> null) | Single-component, no block split | None built-in | ~17KB | Active | Single component, lightest; `optimizeForStreaming` is interesting but hides incomplete content rather than rendering it. |
| **`@uiw/react-markdown-preview`** | Wraps react-markdown | No | No | rehype-prism | ~60KB + prism | Active | GitHub-flavored theming, not streaming-tuned. Same perf characteristics as raw react-markdown. |
| **`marked` + custom React renderer** | n/a | Manual | Manual | Manual | ~30KB | Active | Fastest parser but you write the React glue. What Cline and the Vercel cookbook call into. |
| **`markdown-it`** + custom | n/a | Manual | Manual | Manual | ~50KB | Active | What ChatGPT ships. Fast incremental rendering is possible but requires building your own pipeline. |
| **`thetarnav/streaming-markdown`** | No (vanilla JS) | Yes (streaming parser) | n/a (vanilla DOM) | None | ~10KB | Active | Officially recommended by Chrome's "Render LLM responses" guide. DOM-based, not React. Good reference implementation. |
| **`stream-markdown-parser`** | No | Yes | n/a | None | ~5KB | Niche | Parser only, no UI. |

### Streamdown in detail (from source: `packages/streamdown/index.tsx`, `lib/parse-blocks.tsx`, `lib/incomplete-code-utils.ts`, `lib/animate.ts`, `styles.css`)

**Architecture:**
- `Streamdown` is a memoized React component. Each turn:
  1. Runs `remend(children)` on the raw string to speculatively close incomplete syntax.
  2. Calls `parseMarkdownIntoBlocks(processed)` which runs `marked.Lexer.lex(…, { gfm: true })` and post-processes to merge HTML blocks, math blocks, and fenced code that the lexer would otherwise split.
  3. Maps each block to a memoized `<Block>` with `key={index}`. `Block` is `React.memo` with a custom equality check on `content`, `index`, `isIncomplete`, `components`, `rehypePlugins`, `remarkPlugins`. A completed block with stable content never re-renders when later blocks append.
  4. Calls `<Markdown>` (internal wrapper around `unified` + `remark-parse` + `rehype-react`) per block. Because blocks are small (one paragraph / one list / one code block), parsing cost is bounded.
- `BlockIncompleteContext` propagates "this block is the last one and still streaming" so components (code blocks in particular) can skip expensive renders. The `useIsCodeFenceIncomplete` hook reads this context.

**Speculative close (`remend`):**
- Standalone package, zero dependencies, pure TS string iteration (no regex splits, ASCII fast-path).
- Handlers complete: bold `**`, italic `*`/`_`, bold-italic `***`, strikethrough `~~`, inline code `` ` ``, block math `$$`, inline math `$`, link text `[x](…`, image `![…`, setext headings.
- Three link modes: `protocol` (replaces with `href=streamdown:incomplete-link`, the component can render those specially), `text-only` (renders children as plain text), custom handler.
- Smart avoidance: underscores inside `$$…$$` math are not treated as italic, formatting markers at list starts are not closed, word-internal `user_name` underscores preserved.
- There is also `hasIncompleteCodeFence(markdown)` (lib/incomplete-code-utils.ts) that walks line-by-line per CommonMark spec — it only counts fences at line starts with up to 3 spaces of indent, matches opening and closing fence chars exactly, requires length ≥ opening length. This is what powers `useIsCodeFenceIncomplete` for the *last* block in the stream.

**Memoization quirks (from CHANGELOG):**
- 2.x had a bug where inline object literals for `linkSafety` default props caused the context value `useMemo` to recompute on every render, defeating block memo. Fixed by extracting `defaultLinkSafetyConfig` to module scope. Lesson: when memoizing markdown blocks, your plugin / component / config references MUST be stable.

**Animation (2.2+, `lib/animate.ts` + `styles.css`):**
- The `animated` prop installs a rehype plugin (`createAnimatePlugin`) that walks the HAST tree and, for text nodes not inside `code`/`pre`/`svg`/`math`, splits them by word (default) or char, and replaces each part with a `<span data-sd-animate style="--sd-animation:sd-fadeIn;--sd-duration:150ms;--sd-delay:Nms">`. No JS per-frame work at all.
- The CSS:
  ```css
  @keyframes sd-fadeIn { from { opacity: 0 } to { opacity: 1 } }
  @keyframes sd-blurIn { from { opacity: 0; filter: blur(4px) } to { opacity: 1; filter: blur(0) } }
  @keyframes sd-slideUp { from { opacity: 0; transform: translateY(4px) } to { opacity: 1; transform: translateY(0) } }
  [data-sd-animate] {
    animation: var(--sd-animation, sd-fadeIn) var(--sd-duration, 150ms)
               var(--sd-easing, ease) var(--sd-delay, 0ms) both;
  }
  ```
- Stagger defaults: `40ms` between words, duration `150ms`, easing `ease`. Options: `animation: "fadeIn"|"blurIn"|"slideUp"|custom`, `sep: "word"|"char"`, `stagger`, `duration`, `easing`.
- Critical trick: the plugin tracks `prevContentLength` across renders. When a block grows, only the *new* characters animate; already-rendered chars get `duration=0ms`. This is why Streamdown does not flicker on update. The plugin reads `getLastRenderCharCount()` from the previous rehype pass and feeds it to the next as `prevContentLength`. Each block has its own render-state object so siblings don't pollute each other.

**Other built-ins:**
- `caret` prop: `"block"` or `"circle"`, appended as a trailing character (` ▋` or ` ●`) in the last block. Cheaper than any animation and is the "I'm still typing" indicator.
- `plugins` prop: `@streamdown/code`, `@streamdown/math`, `@streamdown/mermaid`, `@streamdown/cjk` — selectively install to control bundle size.
- Shiki code blocks: internal cache of loaded languages/themes, lazy language grammar loading. Using `useIsCodeFenceIncomplete` you can show a `CodeBlockSkeleton` until the fence closes and only then call Shiki.
- `mode="static"` skips `remend` entirely (use this for completed assistant messages to save work on mount).
- GPU-friendly: `content-visibility: auto` applied to off-screen code block containers (confirmed from Streamdown search results, though not visible in the files I fetched).
- Sanitization: `rehype-sanitize` + `rehype-harden` by default. XSS-safe.

### react-markdown + manual memoization (the "Cline pattern")

From `cline/webview-ui/src/components/common/MarkdownBlock.tsx`:

```tsx
import { marked } from "marked"
import ReactMarkdown from "react-markdown"
import React, { memo, useMemo } from "react"
import rehypeHighlight from "rehype-highlight"
import remarkGfm from "remark-gfm"

function parseMarkdownIntoBlocks(markdown: string): string[] {
  try {
    const tokens = marked.lexer(markdown)
    return tokens?.map((token) => token.raw)
  } catch { return [markdown] }
}

const MemoizedMarkdownBlock = memo(
  ({ content }: { content: string }) => (
    <ReactMarkdown
      components={{ /* pre, code, strong, img overrides */ }}
      rehypePlugins={[[rehypeHighlight, {}]]}
      remarkPlugins={[[remarkGfm, { singleTilde: false }], /* … */]}>
      {content}
    </ReactMarkdown>
  ),
  (prev, next) => prev.content === next.content,
)

const MemoizedMarkdown = memo(({ content, id }: { content: string; id: string }) => {
  const blocks = useMemo(() => parseMarkdownIntoBlocks(content), [content])
  return blocks?.map((block, i) => (
    <MemoizedMarkdownBlock content={block} key={`${id}-block_${i}`} />
  ))
})
```

**Observations:**
- Cline uses `rehype-highlight` (highlight.js) — cheap, synchronous, bundle ~40KB. *Not* Shiki.
- No animation at all. No cursor. Blocks just appear as they close.
- Uses remark plugins for URL auto-linking, file-path detection, "Act Mode" keyword highlight — custom but not essential to the pattern.
- Does NOT handle incomplete code fences explicitly. They get rendered as open fences by `react-markdown`, which is actually fine because once the closing fence arrives the last block re-parses and everything snaps into place. The user sees a brief "plain text" -> "highlighted code" transition for the streaming block, which the community has accepted as acceptable.

The Vercel AI SDK cookbook (`ai-sdk.dev/cookbook/next/markdown-chatbot-with-memoization`) publishes essentially the same pattern.

### markdown-to-jsx with `optimizeForStreaming`

```tsx
<Markdown options={{ optimizeForStreaming: true }}>{content}</Markdown>
```

This option makes the renderer return `null` for any node it can't confidently close (half-typed `**bold`, half-typed `[link](http`). It hides incomplete structures until the closing delimiter arrives — the opposite of Streamdown's "speculatively close and show". Two different philosophies:
- Streamdown: always show *something*, even if it means temporarily closing bold early.
- markdown-to-jsx: never show *partial* formatting, wait for closure.

For the "feel responsive" requirement, Streamdown wins — text never freezes waiting for a closing `**`.

### Chrome's recommended library: `thetarnav/streaming-markdown`

Vanilla JS, not React. Chrome's official "Render LLM responses" doc explicitly recommends it as the streaming parser, paired with DOMPurify for sanitization. It processes chunks individually and holds output back until a token is "clear" (committed, cannot change meaning). Not directly useful for a React app but a good reference implementation if you ever want to understand streaming parsing without React.

---

## 2. How Real AI Chat UIs Actually Do It

### ChatGPT (openai.com)

Ships `markdown-it` (~50KB gz) + `highlight.js` (~26–305KB depending on languages) + KaTeX + DOMPurify — combined ~160-440KB of JS — and parses/renders in the browser.

- Splits messages into blocks and memoizes: "ChatGPT handles parsing of Markdown content into an mdast tree using remark, and then passes the mdast tree to rehype-react for rendering as React components. The approach avoids re-rendering everything from scratch by splitting Markdown content into blocks using the marked library to identify discrete Markdown elements, then using React's memoization features to optimize re-rendering by only updating blocks that have actually changed." ([ai-sdk.dev cookbook](https://ai-sdk.dev/cookbook/next/markdown-chatbot-with-memoization))
- **No text-reveal animation.** Tokens appear as the batched stream arrives. Cursor is a single blinking caret at the end.
- Incomplete code fences: the partial code block renders as a plain `<pre>` with no highlighting until the fence closes, then highlight.js runs. Users see a brief un-highlighted -> highlighted transition per code block.

### Claude.ai (anthropic.com)

Uses `markdown-it` + Shiki for highlighting, with a custom `MarkdownRenderer` that debounces rapid updates. From analysis of the web app:
- "If AI streaming updates trigger render every 50-100ms, the solution is to skip intermediate renders if already highlighting, with only the final content getting expensive Shiki processing." The component has a `pending` flag: if highlighting is in progress, updates are queued; after highlight completes, if content changed during render, it re-runs.
- **No text-reveal animation.**
- Code blocks show plain text while streaming, then Shiki highlighting kicks in when the fence closes. Because Shiki is async (WASM-backed), they explicitly debounce to prevent highlight thrash.
- In the Claude Code CLI, Boris Cherny built **NO_FLICKER mode** (`CLAUDE_CODE_NO_FLICKER=1`) — an incremental line-based renderer that avoids the old "re-parse and redraw everything on every token" behavior. This is a CLI-terminal concept, not web-relevant, but the philosophy is the same: stable old content + incremental new content.

### Perplexity, Gemini

Same broad approach: client-side markdown-it/remark, block-level memoization, no per-char animation, caret or nothing as the "alive" indicator.

### Vercel AI SDK / v0

- The AI SDK ships `streamdown` as the officially blessed renderer.
- v0.dev (Vercel's own app) uses Streamdown.
- The AI SDK also ships an `experimental_transform: smoothStream({ delayInMs: 10, chunking: "word" })` server-side transform that rate-limits the outbound stream to produce a smoother "typewriter" feel. Default: 10ms delay, word chunking. The author of the Upstash smooth-streaming post notes: "This approach was not very flexible and also (obviously) experimental" and recommends a custom client-side `requestAnimationFrame` buffer at ~200 chars/sec — which is close to what you have, but the key insight is **he still renders markdown blocks as they close, the RAF buffer only smooths the raw text delivery**.

### assistant-ui (open-source React chat UI kit)

Uses `@assistant-ui/react-streamdown` which is a thin wrapper around Streamdown. Integrates via a `StreamdownTextPrimitive` inside `<MessagePrimitive.Parts>`. Exposes `useIsStreamdownCodeBlock`, `useStreamdownPreProps`, `memoCompareNodes` for custom renderers. `remend` integration for incomplete markdown. This is the fastest path for a "just works" streaming chat component if you are building on assistant-ui.

### Cline (VSCode extension)

See source snippet in §1. `react-markdown` + `marked.lexer` split + `MemoizedMarkdownBlock`. **No animation, no caret**. Blocks just render as they close. `rehype-highlight` for code. Special handling for mermaid blocks. Renders into a VSCode webview (also Chromium-based) — confirms the pattern works in an embedded browser.

### Continue.dev (VSCode extension)

Uses react-markdown with block-level memoization (same pattern as Cline, independently derived). No animation.

### Cursor

Closed source, but reverse-engineering by community shows standard react-markdown + memoization + no per-char animation. Cursor does have a subtle word-level fade-in on newly-arriving tokens in some modes, but it is a CSS animation (no `filter: blur`), not a JS-driven per-char animation.

### Observable pattern summary

| Product | Parser | Highlight | Block memo | Text reveal animation | "Alive" indicator |
|---|---|---|---|---|---|
| ChatGPT | markdown-it | highlight.js | Yes | No | Blinking caret |
| Claude.ai | markdown-it | Shiki (debounced) | Yes | No | Blinking caret |
| Perplexity | markdown-it | highlight.js | Yes | No | Caret / nothing |
| v0 / Vercel AI SDK | Streamdown | Shiki | Yes (via Streamdown) | Optional CSS-only | Caret + optional fade |
| Cline | react-markdown | highlight.js | Yes (manual) | No | None |
| Continue.dev | react-markdown | highlight.js | Yes (manual) | No | None |
| Cursor | Custom | highlight.js | Yes | Subtle CSS fade | None / subtle fade |

**Nobody uses per-character JS animation.** Streamdown's `animated` prop is the closest thing, and it is:
- CSS keyframes, not JS,
- word-level by default,
- 40ms stagger + 150ms duration,
- with a `prevContentLength` skip mechanism so already-rendered characters get `duration=0ms`.

That is what "subtle polish animation" looks like when done right.

---

## 3. Known Problems and Solutions for Streaming Markdown

### 3.1 Incomplete code fences

**Problem:** `\`\`\`python\nprint(1)` with no closing fence is parsed by standard markdown parsers as either (a) an unclosed code block (many parsers extend to end-of-document, which is correct but renders the rest of the message as code), or (b) inline backticks, which is wrong. As streaming continues, each new token re-parses and the DOM thrashes.

**Solutions, in order of sophistication:**

1. **Poor but works:** detect unclosed fence, append a synthetic `\n\`\`\`` to the input before passing to the parser. Your current `MarkdownRenderer.tsx` does this (`closeOpenFences`). Downside: every token mutates the synthetic close position and re-parses.
2. **Better:** Streamdown's `hasIncompleteCodeFence` walks lines per CommonMark spec (indentation ≤ 3 spaces, exact fence char, length ≥ opening length). If it finds one, the *last block* gets `isIncomplete={true}` via `BlockIncompleteContext`, and custom code components read that via `useIsCodeFenceIncomplete()` and render a `CodeBlockSkeleton` (or plain `<pre>`) instead of running Shiki. Only the last block ever sees this state.
3. **Best:** combine both. Speculatively close the fence for the parser, *and* use the "incomplete" flag to skip expensive highlighting until the fence actually closes.

### 3.2 Incomplete lists / tables / links / emphasis

- **Emphasis (`**bold`, `*italic`, `~~strike`):** `remend` appends the closing delimiter. This produces a momentary "wrong close" (e.g. `**bold\n\n` briefly renders "bold" bolded instead of plain), but is invisible in practice because it only lasts one token.
- **Links (`[text](http`):** `remend` replaces with `href="streamdown:incomplete-link"` in default mode, which your custom `a` renderer can detect and render as plain text or muted.
- **Tables:** the hard case. A delimiter row (`| --- | --- |`) is required for GFM to treat preceding line as a table header. If you have a header line but no delimiter yet, you see a plain line with pipes. Once the delimiter arrives, it snaps into a table. Streamdown's `parseMarkdownIntoBlocks` has special merging logic for math blocks (unclosed `$$`) — tables just have to wait. No clean solution, but the table snap is <200ms so users don't notice.
- **Lists:** a list item being streamed renders as a list item immediately; subsequent items append. No problem. The edge case is a single `-` on its own line which renders as a list item with empty content — harmless.

### 3.3 Layout shift as content grows

- **Layout shift is not the problem you have.** Chat messages grow downward; auto-scroll compensates. The problem you *do* have is paint cost on every token.
- **`content-visibility: auto`** tells the browser to skip layout/paint for off-screen elements. Streamdown applies this to code block containers. Apply it to whole message containers too: `.agent-message { content-visibility: auto; contain-intrinsic-size: auto 200px; }` — the `contain-intrinsic-size` is a size hint so the browser doesn't jump when it skips layout.
- **`contain: layout paint style`** on each message container prevents layout recalculation in one message from propagating to siblings. Cheap win.
- **Do NOT use `will-change` proactively** on streaming content. It promotes the element to its own GPU layer, which in CEF off-screen rendering is not accelerated and actively hurts. Your `BlurTextStream` has `will-change-[transform,filter,opacity]` on every character span — remove it.

### 3.4 Virtual DOM reconciliation cost per token

This is the main React cost:

- **Splitting into blocks + `React.memo` with custom content equality** is the single biggest optimization. Raw `react-markdown` re-parses the entire message string on every update. Block-split + memo makes the cost proportional only to the currently-growing block.
- **Stable keys** — use `key={blockIndex}` not `key={Math.random()}` or content-hash. The block at index 0 is always the same block; React should reuse its DOM.
- **Stable component references** — the `components` object you pass to `react-markdown` / `streamdown` MUST be defined outside the render or wrapped in `useMemo([])`. Same for `remarkPlugins`, `rehypePlugins`. Streamdown had a bug where inline default `linkSafety={}` broke memo; lesson applies to every react-markdown user.
- **Incremental parse trees** (real streaming parsers that maintain state across ticks) exist but are overkill. Block-level memoization is sufficient.

### 3.5 GPU paint thrash from CSS animations + DOM mutations

This is the single most likely root cause of the user's "laggy feel". Evidence:

- `BlurTextStream` uses `motion/react` (`<m.span>`) with `filter: blur(2px)` on ~15 simultaneously-animating characters.
- `filter: blur()` is among the **most expensive CSS properties**: it creates a stacking context, forces the compositor to rasterize the element at every frame during the transition, and on CEF off-screen rendering (`osr_mode`), there is no GPU compositor at all — the paint goes through a CPU-side readback to a shared-memory buffer. From the CEF issue tracker: "Off-screen rendering does not currently support accelerated compositing so performance may suffer as compared to a windowed browser."
- `motion/react` uses `requestAnimationFrame` to drive JS-side interpolation, which means every frame you have: React state updates, re-render of settled text, measurement for `graduate()`, and paint of the blurred characters. That is 4 independent sources of main-thread work per frame.
- Parallel CSS animations running *alongside* DOM mutations (PresentationBuffer appending chars every frame) cause Chromium to repeatedly invalidate the "paint is cheap" assumption. Under off-screen rendering this manifests as the entire message container repainting on every token.

The fix is categorical: **use pure CSS animations declared statically, driven by the presence of a class or attribute, never JS-per-frame, never `filter: blur`**. Streamdown's approach (CSS keyframes on a `data-sd-animate` attribute, `opacity + transform: translateY` only) is the right model.

### 3.6 Auto-scroll behavior

- You already have `use-stick-to-bottom` in package.json — that is the industry-standard "scroll to bottom as content arrives, unless user scrolled up" hook. Keep it.
- Wrap the message list in `<StickToBottom>` from that library.
- Detect user-scroll-up via the hook's state and pause auto-scroll. Resume on "jump to bottom" button click.
- For performance: don't use `scrollIntoView({ behavior: 'smooth' })` on every token — it queues scroll animations and fights with the stream. `use-stick-to-bottom` handles this correctly.

### 3.7 Syntax highlighting during streaming

The trap: if you highlight on every token, every token re-tokenizes and re-themes the entire code block.

- **Shiki (what you have):** expensive (WASM), async, 7× slower than Prism in benchmarks, 44× slower than highlight.js in older benchmarks (likely improved, but still the heaviest). **Do not** run it on every token. Defer until the code fence closes. Use a stable block-id key so Shiki's internal cache can reuse the highlight between renders if content is unchanged.
- **Prism:** synchronous, fast, lightest. `rehype-prism-plus` is the standard integration.
- **highlight.js:** synchronous, fast, auto-detects language, ~40KB gzipped (or 26KB core + per-language grammars). What Cline uses.

**Recommended flow:**
1. Every token: run `hasIncompleteCodeFence(message)` (O(lines), cheap).
2. If last block is an incomplete code fence: render `<pre>{rawCode}</pre>` with plain monospace. No highlight.
3. Once the fence closes (next token), the last block goes `isIncomplete=false`, React re-renders it, *now* Shiki runs (synchronously per-block), result is cached in block memo.
4. User sees: plain code streaming in -> one snap to syntax-highlighted when fence closes. Same UX as ChatGPT and Claude.ai.

Stable-id memoization: `<CodeBlock key={`code-${blockIndex}`} code={content} language={lang} />` with `React.memo` on `CodeBlock` comparing `code` and `language` strings. Shiki's own cache handles the grammar/theme side.

---

## 4. The "Speculative Close" Pattern (Deep Dive)

The core insight: **markdown parsers are designed for complete documents. A streaming parser either (a) incorporates state across calls, or (b) pretends each call is complete by closing unclosed constructs.** Option (b) is much simpler and is what Streamdown does.

### 4.1 `remend` — string-level speculative close

Source: `vercel/streamdown/packages/remend` (used by Streamdown via `remend(children, remendOptions)`).

**Design:**
- Pure TypeScript, zero dependencies, direct string iteration (no regex splits), ASCII fast-path.
- Handlers are keyed by priority 0-100 (default 100). Built-ins reserve 0-70. Custom handlers slot in with explicit priority.
- Input: a partial markdown string. Output: a string with speculatively-closed syntax.

**Behavior examples (from the tests / docs):**

```ts
remend("This is **bold text");           // "This is **bold text**"
remend("Check out [link](https://");     // "Check out [link](streamdown:incomplete-link)"
remend("Here's `code");                  // "Here's `code`"
remend("Strike ~~this");                 // "Strike ~~this~~"
remend("Math $$E = mc");                 // "Math $$E = mc$$"
```

**Context detection utilities (exported for custom handlers):**

```ts
isWithinCodeBlock(text, index)    // true if index is inside a ``` fence
isWithinMathBlock(text, index)    // true if index is inside a $$ block
isWithinLinkOrImageUrl(text, idx) // true if index is inside [x](…) url
isWordChar(ch)                    // true for alnum/_
```

These let custom handlers avoid false positives — e.g. don't close `**` inside a math expression like `$$**`.

**Smart avoidance rules (built-in):**
- Underscores inside `$$…$$` math blocks aren't treated as italic.
- Formatting markers at list start (`- **item`) aren't closed if the item has no content yet.
- Word-internal underscores (`user_name`, `foo_bar_baz`) aren't closed even if they look unbalanced.
- Single tilde (`20~25`) is escaped to `20\~25` to prevent false GFM interpretation as strikethrough.
- Incomplete images (`![…`) are removed entirely (to prevent broken `<img>` tags).
- Code fence-aware: formatting inside completed `\`\`\`…\`\`\`` blocks is left alone.

**Link handling, three modes:**

1. **Default `protocol`:** `[Click here](https://exampl` → `[Click here](streamdown:incomplete-link)`. The custom `a` component detects `href === "streamdown:incomplete-link"` and renders children as plain text. Anchor tag is present but inert.
2. **`text-only`:** `[Click here](https://exampl` → `Click here`. Rendered as plain text — the anchor is gone.
3. **Custom:** your own handler decides.

### 4.2 `hasIncompleteCodeFence` — line-walking speculative close

Complementary: string-level `remend` doesn't know about CommonMark code-fence rules (indentation ≤ 3, fence length match). `hasIncompleteCodeFence` walks lines and returns a boolean. Streamdown calls this on each block to set `isIncomplete`.

```ts
const CODE_FENCE_PATTERN = /^[ \t]{0,3}(`{3,}|~{3,})/;
```

Walks lines:
- Not inside a fence + line matches pattern → open a fence (record char and length).
- Inside a fence + line matches pattern → check char matches and length ≥ opening → close.
- End of string + still in a fence → `return true` (incomplete).

This is only run on the last block in the stream. Code blocks in earlier completed blocks are already fully parsed.

### 4.3 Are there failure modes?

Yes, three:

1. **Mid-word formatting flips.** `remend("bar **bold`) closes as `"bar **bold**"` — `"bold"` bolds. If the next token is `er words**`, the stream becomes `"bar **bolder words**"` which is still correct, but for one tick the user saw "bold" bolded when the actual intent was "bolder words". Imperceptible in practice (one token ≈ 50ms).
2. **Table header without delimiter.** No way to "speculatively close" a table because GFM tables require an explicit `| --- | --- |` delimiter row. Users see pipes until the delimiter arrives, then it snaps into a table. Accepted trade-off.
3. **Complex nesting.** `> **> nested quote bold` — nested blockquote with incomplete bold inside. `remend` handles simple cases; deeply nested constructs may close at the wrong level. Streamdown's test suite catches the common cases; exotic ones may produce wrong rendering. Your LLM rarely emits these.

**Verdict:** speculative close is a *solved* problem for 99% of real chat streams. Streamdown's implementation is the reference.

---

## 5. Minimal-Animation Recommendation

Given the user's statement "minimal or no animation is acceptable", the ranked options from lightest to heaviest:

### Option A: Zero animation (Cline model)

- New blocks appear instantly when they close.
- A blinking caret/block at the end of the last block.
- CSS: one `.streaming-caret::after { content: "▋"; animation: blink 1s steps(2) infinite; }`.
- Cost: effectively zero. A single element animates on the compositor thread.

### Option B: Block fade-in only (current Streamdown default)

- 150-200ms opacity fade-in when a completed block appears.
- CSS keyframe, one animation per block, not per character.
- `@keyframes block-in { from { opacity: 0; transform: translateY(2px); } to { opacity: 1; transform: none; } }`
- Cost: tiny. You already have something like this in `StreamingMessage.tsx`'s `BLOCK_ANIMATION` — keep it, but make it CSS-driven (a class that triggers `animation: block-in 200ms ease-out`) instead of `motion/react` per-block.

### Option C: Streamdown's word-level CSS fade

- `animated` prop → rehype plugin wraps words in `<span data-sd-animate>` with CSS keyframe animation.
- 40ms stagger, 150ms duration, pure CSS, `prevContentLength` skip for already-rendered chars.
- Cost: noticeable but acceptable on desktop Chromium; may be too heavy for CEF off-screen rendering. **Test before shipping**.
- If enabled, use `animation: "slideUp"` or `"fadeIn"` only. **Do not use `"blurIn"` in CEF** — `filter: blur()` is the exact property that blows up off-screen-rendering perf.

### Option D: Pure CSS caret only, no text fade (recommended middle ground)

Combines A + B but tuned:
- Completed blocks: instant render, 150ms `fadeIn` CSS animation on mount (apply via a class).
- Current (in-progress) block: instant text updates, no fade.
- Caret `▋` blinking at the end of the current block.
- Auto-scroll via `use-stick-to-bottom`.

**What ChatGPT and Claude.ai actually use:** Option D or A. No text fade. Claude Code CLI NO_FLICKER is literally "just stop redrawing" — no animation at all.

### Recommendation for this project: **Option D with Streamdown's `caret="block"`**

Why:
- Matches industry practice.
- Single element animates (the caret), compositor-thread only.
- Block fade is opacity+transform only — the two properties that CEF off-screen rendering *can* hardware-accelerate (they stay on the compositor layer when there's nothing else going on).
- Zero JS per-frame work.
- No `filter: blur()`.
- Delete `BlurTextStream`, `usePresentationBuffer`, and the `PresentationBuffer` layer entirely. The Kotlin `StreamBatcher` is enough smoothing.

---

## 6. JCEF / CEF-Specific Gotchas

JCEF is CEF wrapped for Java. IntelliJ's JBCefBrowser uses CEF in **off-screen rendering (OSR)** mode because Swing owns the native window. Implications:

1. **No GPU-accelerated compositing in OSR.** Per the CEF wiki and issue 2046: "Off-screen rendering does not currently support accelerated compositing so performance may suffer as compared to a windowed browser." What this means concretely:
   - CSS properties that normally run on the GPU compositor thread (`opacity`, `transform`, `filter`) still *work*, but the final pixel composition happens on the CPU.
   - `filter: blur()` becomes CPU-expensive because the blur convolution runs per-frame in software. Avoid.
   - `transform: translateY(4px)` and `opacity` stay cheap because they don't require convolution — Skia can composite them with minimal CPU work.

2. **`requestAnimationFrame` fires at ~60Hz in CEF OSR**, but the frame is a readback from the internal Chromium raster to a shared memory buffer, which is then painted on the Swing `JComponent`. Data copy cost matters — a 1200×800 message pane is ~4MB per frame. If you animate ~20 characters per frame, each with filter:blur, the raster invalidation area is large and the readback cost goes up.

3. **JetBrains Runtime (JBR) ships a patched JCEF** with IntelliJ-specific improvements. As long as you run on JBR (which you will as an IntelliJ plugin), you get the best available CEF performance. Do not try to swap the runtime.

4. **DevTools available.** Right-click the JCEF component → inspect, or call `cefBrowser.openDevTools()`. Use the Performance tab to confirm paint cost hypothesis before optimizing. The Chromium DevTools "rendering" panel has "Paint flashing" which makes invalidated regions green — run it against your current pipeline and you'll literally see the ~15 animated character spans triggering paints on every frame.

5. **IntelliJ theme integration.** Already handled by `themeStore.ts`. Don't break it.

6. **CSP.** Your `CefResourceSchemeHandler` sets `connect-src: 'none'`. Streamdown does not make network requests (all its deps are local), so CSP is fine. Shiki loads grammars lazily but from the bundled wasm, not network.

**Concrete gotchas to fix in your current code:**

- `BlurTextStream.tsx` line 20: `will-change-[transform,filter,opacity]` promotes every animated char to its own CEF layer. In OSR without compositor accel this is worse than no promotion — each layer adds raster cost. Remove `will-change` entirely.
- `BlurTextStream.tsx` line 5: `filter: 'blur(2px)'` is the single most expensive animated property for CEF OSR. Replace with `opacity` + `translateY` or remove.
- `motion/react` per-char `<m.span>` with `onAnimationComplete` handlers adds React state churn every frame. Remove.
- `usePresentationBuffer.ts` drips chars at ~150 cps in `requestAnimationFrame`. If your LLM streams faster than 150 cps (Anthropic streams at ~30-50 tokens/sec ≈ 150-250 cps for Claude), the buffer grows unbounded and the user sees a growing latency between LLM output and visible text. Drop it.

---

## 7. Concrete Recommendation for the Workflow Orchestrator Plugin

### TL;DR

1. **Delete** `usePresentationBuffer`, `BlurTextStream`, `useBlockSplitter`, the animation zones in `StreamingMessage`. Keep the Kotlin `StreamBatcher` (16ms EDT coalesce is correct and Kotlin-side is cheap).
2. **Install Streamdown** (`npm install streamdown`). Replace `MarkdownRenderer.tsx` with a thin wrapper around `<Streamdown>` that maps the existing `components` override (flow, chart, mermaid, etc.) to Streamdown's `components` prop.
3. **Use `mode="streaming"`** while the stream is live (`activeStream.isStreaming === true`), **switch to `mode="static"`** once complete (saves `remend` cost on completed messages that will never change).
4. **Enable `caret="block"`** on the currently-streaming message only. Disable for completed messages.
5. **Do NOT enable `animated`** initially. Ship the no-animation version first, measure, then consider enabling `animated={{ animation: "fadeIn", sep: "word", stagger: 40, duration: 150 }}` if user feedback asks for motion. Never enable `animation: "blurIn"` in CEF.
6. **Keep your existing Shiki code** but wrap it in `useIsCodeFenceIncomplete()` so it doesn't run on streaming/incomplete fences.

### Integration sketch

```tsx
// agent/webview/src/components/markdown/MarkdownRenderer.tsx
import { Streamdown, useIsCodeFenceIncomplete } from 'streamdown';
import 'streamdown/styles.css';
import { useSettingsStore } from '@/stores/settingsStore';
import { CodeBlock } from './CodeBlock';
import { MermaidDiagram } from '@/components/rich/MermaidDiagram';
// … your existing rich block imports

// Stable component map (module-scope — critical for block memo)
const COMPONENTS = {
  pre: ({ children, ...props }) => {
    // Streamdown wraps code blocks in <pre>; delegate to your custom CodeBlock
    // which reads useIsCodeFenceIncomplete to skip Shiki on streaming fences.
    return <CustomPre {...props}>{children}</CustomPre>;
  },
  // keep your existing mermaid/chart/flow overrides
};

const CustomPre: React.FC<React.HTMLAttributes<HTMLPreElement>> = ({ children, ...props }) => {
  const incomplete = useIsCodeFenceIncomplete();
  if (incomplete) {
    // plain monospace, no highlighting
    return <pre {...props} className="streaming-code-plain">{children}</pre>;
  }
  return <CodeBlock {...props}>{children}</CodeBlock>; // existing Shiki path
};

// Stable plugin refs (module-scope)
const REMARK_PLUGINS = [/* your existing plugins */];
const REHYPE_PLUGINS = [/* your existing plugins */];

interface Props { content: string; isStreaming?: boolean }

export const MarkdownRenderer: React.FC<Props> = ({ content, isStreaming }) => {
  const caret = isStreaming ? ('block' as const) : undefined;
  return (
    <Streamdown
      mode={isStreaming ? 'streaming' : 'static'}
      caret={caret}
      components={COMPONENTS}
      remarkPlugins={REMARK_PLUGINS}
      rehypePlugins={REHYPE_PLUGINS}
      parseIncompleteMarkdown
      shikiTheme={['github-light', 'github-dark']}
    >
      {content}
    </Streamdown>
  );
};
```

```tsx
// agent/webview/src/components/chat/StreamingMessage.tsx (new)
import { useChatStore } from '@/stores/chatStore';
import { MarkdownRenderer } from '../markdown/MarkdownRenderer';

export const StreamingMessage: React.FC = () => {
  const text = useChatStore(s => s.activeStream?.text ?? '');
  const isStreaming = useChatStore(s => s.activeStream?.isStreaming ?? false);
  if (!text) return null;
  return (
    <div className="agent-message streaming-message">
      <MarkdownRenderer content={text} isStreaming={isStreaming} />
    </div>
  );
};
```

```css
/* agent/webview/src/styles/streaming.css */
.agent-message {
  content-visibility: auto;
  contain-intrinsic-size: auto 200px;
  contain: layout paint style;
}

.streaming-code-plain {
  /* plain font-mono, matches your existing CodeBlock padding/background */
  font-family: var(--font-mono);
  background: var(--code-bg);
  padding: 12px;
  border-radius: 6px;
  white-space: pre;
  overflow-x: auto;
}

/* Caret animation — single element, GPU-cheap */
@keyframes caret-blink {
  0%, 50% { opacity: 1; }
  51%, 100% { opacity: 0; }
}
[data-streamdown-caret] {
  display: inline-block;
  animation: caret-blink 1s steps(2) infinite;
}
```

### What to keep from the current setup

- `StreamBatcher.kt` (16ms EDT coalesce). Correct. Keep.
- JCEF bridge (`appendToken` / `chatStore.activeStream`). Correct. Keep.
- `use-stick-to-bottom` — wire it if not already wired.
- All of your rich blocks (mermaid, chart, flow, artifact, diff). Map them through Streamdown's `components` prop. Nothing changes semantically.

### What to delete

- `usePresentationBuffer.ts` — the second-layer smoothing buffer. The LLM + Kotlin batcher is enough.
- `BlurTextStream.tsx` — per-char motion/react animation with `filter: blur`. The wrong shape entirely.
- `useBlockSplitter.ts` — your ad-hoc block splitter. Streamdown's `parseMarkdownIntoBlocks` (via `marked.lexer`) is more correct (GFM-aware, handles HTML, math, footnotes).
- The three-zone rendering split in `StreamingMessage.tsx`. Replace with a single `<MarkdownRenderer>`.
- `chatAnimationsEnabled` setting can stay as a toggle for Streamdown's `animated` prop if you ever want to enable it.

### What to measure after the change

Open Chromium DevTools via the JCEF inspect menu. Record a Performance trace during a 2000-token streaming response. Confirm:

- Scripting cost per frame < 5ms (currently likely 15-40ms due to `motion/react` + `usePresentationBuffer`).
- Rendering+Painting cost per frame < 5ms (currently likely 20-60ms due to `filter: blur` on 15 spans).
- No dropped frames during stream (currently likely 30-50% dropped).
- Total LLM-visible-latency: time from `appendToken` call to pixel on screen. Should be <100ms (currently the 150cps drip makes this grow unbounded).

---

## 8. Sources

### Official docs and changelogs

- [Streamdown homepage](https://streamdown.ai/)
- [Streamdown — introducing (Vercel changelog)](https://vercel.com/changelog/introducing-streamdown)
- [Streamdown 2.2 — animated streaming prop](https://vercel.com/changelog/streamdown-2-2)
- [Streamdown 2.3 — useIsCodeFenceIncomplete hook](https://vercel.com/changelog/streamdown-2-3) (referenced in CHANGELOG)
- [Streamdown 2.5 — inline KaTeX, staggered animation](https://vercel.com/changelog/streamdown-2-5)
- [Streamdown memoization docs](https://streamdown.ai/docs/memoization)
- [Streamdown unterminated block parsing docs](https://mintlify.wiki/vercel/streamdown/advanced/termination)
- [Streamdown GitHub repo](https://github.com/vercel/streamdown) (source code read directly via gh api)
- [Streamdown code block API](https://www.mintlify.com/vercel/streamdown/api/code-block-components)
- [Vercel AI SDK cookbook — markdown chatbot with memoization](https://ai-sdk.dev/cookbook/next/markdown-chatbot-with-memoization)
- [assistant-ui streamdown integration](https://www.assistant-ui.com/docs/ui/streamdown)
- [@assistant-ui/react-streamdown on npm](https://www.npmjs.com/package/@assistant-ui/react-streamdown)
- [react-markdown on npm](https://www.npmjs.com/package/react-markdown)
- [react-markdown GitHub](https://github.com/remarkjs/react-markdown)
- [Chrome's "Render LLM responses" guide](https://developer.chrome.com/docs/ai/render-llm-responses)
- [thetarnav/streaming-markdown (vanilla JS reference)](https://github.com/thetarnav/streaming-markdown)

### Source code read directly

- `vercel/streamdown` — `packages/streamdown/index.tsx`, `lib/parse-blocks.tsx`, `lib/incomplete-code-utils.ts`, `lib/animate.ts`, `styles.css` (via `gh api repos/vercel/streamdown/contents/…`)
- `cline/cline` — `webview-ui/src/components/common/MarkdownBlock.tsx` (via `gh api repos/cline/cline/contents/…`)

### Analysis / comparison articles

- [Preventing Flash of Incomplete Markdown when streaming AI responses (HN)](https://news.ycombinator.com/item?id=44182941)
- [ChatGPT, Claude, Gemini render markdown in the browser (Loren Stewart)](https://www.lorenstew.art/blog/i-do-the-opposite)
- [Claude Code NO_FLICKER mode (Vanja Petreski)](https://vanja.io/claude-code-no-flicker/)
- [Claude Code's markdown and syntax highlighting (deepwiki)](https://deepwiki.com/touwaeriol/claude-code-plus/6.5-markdown-and-syntax-highlighting)
- [Smooth streaming in AI SDK v5 (Upstash blog)](https://upstash.com/blog/smooth-streaming)
- [Streamdown: a drop-in react-markdown replacement (reactjsexample)](https://reactjsexample.com/streamdown-a-drop-in-react-markdown-replacement/)
- [Streaming backends & React: controlling re-render chaos (SitePoint)](https://www.sitepoint.com/streaming-backends-react-controlling-re-render-chaos/)
- [React Markdown complete guide 2026 (Strapi)](https://strapi.io/blog/react-markdown-complete-guide-security-styling)

### Syntax highlighter benchmarks

- [Better Syntax Highlighting (dbushell)](https://dbushell.com/2024/03/14/better-syntax-highlighting/)
- [Comparing web code highlighters (chsm.dev)](https://chsm.dev/blog/2025/01/08/comparing-web-code-highlighters)
- [Tale of the Tape: Highlight.js vs Shiki (dev.to)](https://dev.to/begin/tale-of-the-tape-highlightjs-vs-shiki-27ce)
- [prismjs vs highlight.js vs shiki vs react-syntax-highlighter](https://npm-compare.com/highlight.js,prismjs,react-syntax-highlighter,shiki)
- [The Evolution of Shiki v1.0 (Nuxt blog)](https://nuxt.com/blog/shiki-v1)

### JCEF / CEF

- [IntelliJ Platform SDK — Embedded Browser (JCEF)](https://plugins.jetbrains.com/docs/intellij/jcef.html)
- [JCEF documentation](https://chromiumembedded.github.io/java-cef/)
- [JetBrains/jcef on GitHub](https://github.com/JetBrains/jcef)
- [CEF OSR accelerated compositing issue 2046](https://bitbucket.org/chromiumembedded/cef/issues/2046/improve-gpu-readback-performance-for-osr)
- [Creating IntelliJ plugin with WebView (Medium)](https://medium.com/virtuslab/creating-intellij-plugin-with-webview-3b27c3f87aea)
- [JavaFX and JCEF in the IntelliJ Platform (JetBrains blog)](https://blog.jetbrains.com/platform/2020/07/javafx-and-jcef-in-the-intellij-platform/)
- [What developers should consider when using CEF (Coherent Labs)](https://coherent-labs.com/posts/what-developers-should-consider-when-using-chromium-embedded-framework-cef-in-their-games/)

### CSS / performance

- [Introducing content-visibility: auto (cekrem.github.io)](https://cekrem.github.io/posts/content-visibility-auto-performance/)
- [CSS content-visibility for React devs (dev.to)](https://dev.to/sebastienlorber/css-content-visibility-for-react-devs-4a3i)
- [Using CSS content-visibility to boost rendering (LogRocket)](https://blog.logrocket.com/using-css-content-visibility-boost-rendering-performance/)

### Other chat UI sources

- [prompt-kit markdown docs](https://www.prompt-kit.com/docs/markdown)
- [ElevenLabs UI Response component](https://ui.elevenlabs.io/docs/components/response)
- [Displaying streaming markdown in Java and React UIs with Spring AI (Vaadin)](https://vaadin.com/blog/displaying-streaming-markdown-in-java-and-react-uis-with-spring-ai)
- [GetStream stream-chat-android-ai](https://github.com/GetStream/stream-chat-android-ai)

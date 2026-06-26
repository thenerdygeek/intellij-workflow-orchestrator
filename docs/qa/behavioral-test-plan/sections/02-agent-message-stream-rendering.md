# Agent Chat — Message Stream & Rendering

Behavioral test plan for the **agent chat message stream, markdown/code rendering, and scrolling**.
Scenario prefix = `MSG`. Run the plugin via `./gradlew runIde` on a licensed Windows IntelliJ
Ultimate box. Open the **Workflow** tool window (bottom-docked) and the **agent chat** tab, then
drive a live agent session (Sourcegraph tokens are configured, so real model responses stream in).

This area is the JCEF/React webview under `agent/webview/`. The tester needs no backend writes —
everything here is read/observe. The only places an action can leave the read-only world are flagged
with **⛔ Write note** (the code-block **Apply** button, external-link **Open Link**, and IDE file
navigation from `file:`/`class:`/`symbol:` links). Do **not** click those except where the scenario
explicitly says to, and never against a real ticket/repo.

How to provoke each surface deterministically:
- Ask the agent for content that exercises a feature, e.g. *"Reply with a markdown table, a numbered
  list, a blockquote, an inline-code span, and a fenced Kotlin code block."* or *"Write ~300 lines of
  prose so the chat overflows."* The model controls output, so re-ask if a shape doesn't appear.
- A **thinking/reasoning** block appears only with a reasoning-capable model (the model picker shows a
  🧠 badge). Pick one to test MSG-20/MSG-21.
- **Theme matrix:** toggle IDE theme (Settings ▸ Appearance ▸ Theme → Dark vs. a Light theme like
  IntelliJ Light); the webview re-themes live via CSS variables. **Size matrix:** narrow the tool
  window to ~320 px and also widen it; test long/unbroken content for wrap.

Grounding map (cited inline per scenario):
- `agent/webview/src/components/chat/` — `ChatView.tsx`, `MessageList.tsx`, `AgentMessage.tsx`,
  `ChatFooter.tsx`, `WorkingIndicator.tsx`, `ToastStack.tsx`
- `agent/webview/src/components/markdown/` — `MarkdownRenderer.tsx`, `CodeBlock.tsx`, `ChatLink.tsx`,
  `ChatLinkifier.ts`, `LinkConfirmModal.tsx`
- `agent/webview/src/components/agent/ThinkingView.tsx`,
  `agent/webview/src/components/ui/prompt-kit/{reasoning.tsx,scroll-button.tsx}`,
  `agent/webview/src/components/ui/copy-button.tsx`, `hooks/useShiki.ts`,
  `stores/chatStore.ts`, `bridge/types.ts`
- Kotlin bridge: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt`
- e2e oracles: `agent/webview/e2e/{chat-scroll.spec.ts,chat-copy.spec.ts}`

---

## A. First render, token streaming & finalize

### [MSG-1] Empty / first-render chat state
- **Component(s):** `ChatView.tsx`, `MessageList.tsx` (Virtuoso `role="log"`)
- **Preconditions:** Brand-new chat (click **New Chat**); no messages yet.
- **Steps:** 1. Open the agent tab with no session running. 2. Observe the message region before typing.
- **Expected — visual:** The scroll region is present and empty — only the 12 px top `HeaderSpacer`
  (`MessageList.tsx:45`) and the `ChatFooter` (mounted once via Virtuoso `components.Footer`,
  `MessageList.tsx:81-85`). No stray bubble, no spinner, no scroll-to-bottom button.
- **Expected — behavioral:** `renderItems.length === 0` so Virtuoso renders nothing in the body; the
  input bar is focused and ready. No console errors.
- **✅ Checks (tick each):**
  - [ ] Empty region renders with no leftover content from a prior session
  - [ ] Scroll-to-bottom button is hidden (it only shows when not at bottom)
  - [ ] WorkingIndicator is absent (only shows when `busy`)
- **🐞 Bug signals:** A collapsed 0 px scroller (the "never wrap Virtuoso in a flex container" trap),
  a phantom empty bubble, or the scroll-to-bottom chevron showing at rest.
- **Theme/size matrix:** light + dark; narrow + wide.

### [MSG-2] Token-by-token streaming append (placeholder grows in place)
- **Component(s):** `ChatFooter.tsx` (streaming bubble `:99-106`), `AgentMessage.tsx` (`isStreaming`),
  `chatStore.appendToken` (`chatStore.ts:985`), Kotlin `StreamBatcher` (`AgentController.kt:500-501`)
- **Preconditions:** Send any prompt; watch the assistant reply arrive.
- **Steps:** 1. Send *"Explain what a binary search is in two paragraphs."* 2. Watch the reply build.
- **Expected — visual:** A single assistant bubble (avatar "A" + "Agent" label) whose text grows
  smoothly token-batch by token-batch (~16 ms coalesced batches, not per-character jitter). Markdown
  formats progressively.
- **Expected — behavioral:** The first token creates the streaming placeholder
  (`streamingText`/`streamingMsgTs`); each later batch concatenates (`chatStore.ts:1036`). The bubble
  is the same `AgentMessage` component used for finalized messages — **no mount/unmount flash** when it
  later finalizes.
- **✅ Checks (tick each):**
  - [ ] Text appends incrementally, smooth (batched), no per-glyph stutter
  - [ ] Exactly one growing assistant bubble (not a new bubble per batch)
  - [ ] No flicker / re-layout when streaming flips to finalized
- **🐞 Bug signals:** New bubble per token; visible flash at finalize; frozen text then a sudden full
  dump (batcher stall); duplicated partial+final bubbles.
- **Theme/size matrix:** light + dark.

### [MSG-3] Streaming finalize + post-stream scroll-to-top of a tall reply
- **Component(s):** `ChatView.tsx` post-stream effect (`:45-66`), `MessageList.scrollToIndexStart`
  (`:68-74`), `chatStore.endStream` (`:1040`)
- **Preconditions:** Get a **tall** assistant reply (taller than ~60% of the viewport). Ask for a long
  answer, e.g. *"List 25 git tips, each with a one-line explanation."*
- **Steps:** 1. Send the prompt. 2. Let the full reply stream and finalize.
- **Expected — visual:** When streaming **ends**, if the just-finalized message is taller than 60% of
  the viewport, the list smooth-scrolls so that message's **top** is in view (so the user reads from
  the start), instead of being left pinned at the bottom.
- **Expected — behavioral:** The effect fires only on the streaming→idle transition (`!wasStreaming ||
  isStreaming` guard, `:49`), defers one `requestAnimationFrame`, measures
  `item.offsetHeight > viewportHeight * 0.6` (`:61`), and calls `scrollToIndexStart(lastIndex)`
  (smooth, `align:'start'`). A short reply does **not** trigger the jump.
- **✅ Checks (tick each):**
  - [ ] Tall finalized reply auto-scrolls to its TOP (smooth)
  - [ ] Short reply does NOT scroll-to-top (stays where it was)
  - [ ] Scroll target is the last render item (tool groups collapse correctly so the index is right)
- **🐞 Bug signals:** Jump-to-top on every (even short) message; jumping to the wrong message; jarring
  instant (non-smooth) jump; scroll fires mid-stream.
- **Theme/size matrix:** light + dark; narrow (more replies count as "tall") + wide.

### [MSG-4] Streaming caret / animating indicator
- **Component(s):** `MarkdownRenderer.tsx` (`mode='streaming'`, `caret='block'`, `isAnimating`,
  `:464-468`)
- **Preconditions:** A reply mid-stream.
- **Steps:** 1. Send a prompt. 2. Observe the trailing edge of the growing text while it streams.
- **Expected — visual:** A block caret animates at the end of the streaming text; it disappears once
  the message finalizes (static mode).
- **Expected — behavioral:** `isStreaming` drives `mode`/`caret`/`isAnimating`; finalized messages
  render in static mode with no caret.
- **✅ Checks (tick each):**
  - [ ] Block caret visible during stream, gone after finalize
  - [ ] No caret on previously-finalized messages
- **🐞 Bug signals:** Caret stuck after finalize; caret on every message; no caret at all during stream.
- **Theme/size matrix:** light + dark.

---

## B. Markdown rendering

### [MSG-5] Headings, paragraphs, emphasis, inline code
- **Component(s):** `MarkdownRenderer.tsx` (`PNode :420`, inline `CodeNode :291-298`), Streamdown + GFM
- **Preconditions:** Ask for mixed markdown: *"Reply with an `# H1`, an `## H2`, a paragraph with
  **bold**, *italic*, and an `inline code` span."*
- **Steps:** 1. Send it. 2. Inspect the finalized render.
- **Expected — visual:** Proper heading sizes/weights; paragraphs with `my-1.5` spacing; bold/italic
  render; inline code shows a rounded `--code-bg` chip in monospace 12 px.
- **Expected — behavioral:** Inline code (no language class, no newline) renders the `<code>` chip
  branch, not a block.
- **✅ Checks (tick each):**
  - [ ] Headings, bold, italic render distinctly
  - [ ] Inline code is a single chip (not a full code block)
  - [ ] Spacing between paragraphs is consistent
- **🐞 Bug signals:** Raw `#`/`**` markup leaking; inline code promoted to a block; unreadable contrast
  on the code chip in light theme.
- **Theme/size matrix:** light + dark.

### [MSG-6] Ordered + unordered lists (incl. nesting)
- **Component(s):** `MarkdownRenderer.tsx` (`UlNode :404`, `OlNode :412`)
- **Preconditions:** Ask for a numbered list and a bulleted list, with one nested sub-list.
- **Steps:** 1. Send the prompt. 2. Inspect.
- **Expected — visual:** `<ul>` uses disc bullets (`ml-4 list-disc`), `<ol>` decimal numbering
  (`list-decimal`); nested list indents another level; `space-y-0.5` between items.
- **Expected — behavioral:** Ordered list numbering is sequential and continues correctly.
- **✅ Checks (tick each):**
  - [ ] Bulleted list shows discs; numbered list shows sequential numbers
  - [ ] Nested list indents correctly
  - [ ] No marker doubling or lost indentation
- **🐞 Bug signals:** Numbers restart at 1 each item; bullets missing; nested list flattened.
- **Theme/size matrix:** light + dark; narrow (wrapping list items).

### [MSG-7] GFM tables + horizontal overflow
- **Component(s):** `MarkdownRenderer.tsx` (`TableNode :360` wraps in `overflow-x-auto`, `ThNode`,
  `TdNode`), `remarkGfm`
- **Preconditions:** Ask for a 4-column markdown table, and separately a table with very wide cells.
- **Steps:** 1. Send a normal table prompt. 2. Send a wide-cell table (long URLs/values). 3. Narrow the
  tool window.
- **Expected — visual:** Bordered table; header row uses `--toolbar-bg` with semibold header text; row
  separators via `--divider-subtle`. A too-wide table gets a **horizontal scrollbar** inside its
  rounded border rather than overflowing the chat.
- **Expected — behavioral:** The `<div class="overflow-x-auto">` wrapper contains the scroll; the chat
  column width is unaffected.
- **✅ Checks (tick each):**
  - [ ] Table renders with header/borders
  - [ ] Wide table scrolls horizontally inside its own container (chat width unchanged)
  - [ ] In a narrow tool window the table doesn't break the layout
- **🐞 Bug signals:** Table blows out the chat width; no horizontal scroll; header styling missing;
  GFM pipe table rendered as raw text.
- **Theme/size matrix:** light + dark; narrow + wide (overflow is the point).

### [MSG-8] Blockquotes, horizontal rule, and raw HTML allow-list (kbd/sub/details)
- **Component(s):** `MarkdownRenderer.tsx` (`BlockquoteNode :389`, `HrNode :400`, `SANITIZE_SCHEMA`
  allowing `details/summary/kbd/sub`, `:149-171`; `rehypeRaw` before `rehypeSanitize` `:175-182`)
- **Preconditions:** Ask: *"Show a blockquote, a horizontal rule, a `<kbd>Ctrl+C</kbd>` hint, `H<sub>2</sub>O`,
  and a `<details><summary>More</summary>hidden</details>` block."*
- **Steps:** 1. Send it. 2. Toggle the `<details>` disclosure.
- **Expected — visual:** Blockquote has a left accent border + italic muted text; `<hr>` is a subtle
  divider; `<kbd>` renders as a key hint; `<sub>` lowers the "2"; `<details>` expands/collapses.
- **Expected — behavioral:** Allowed tags survive sanitization; `<script>`/`<iframe>`/`on*` handlers are
  stripped (GitHub `defaultSchema`).
- **✅ Checks (tick each):**
  - [ ] Blockquote, hr render with the expected styling
  - [ ] `<kbd>`, `<sub>`, `<details>` survive and behave
  - [ ] No script/iframe execution (paste a `<script>` via the prompt — it must be inert)
- **🐞 Bug signals:** Allowed tags shown as escaped text; OR a `<script>`/`<img onerror>` actually
  executing (XSS — high severity).
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** None, but XSS is a security concern — flag any executing markup immediately.

### [MSG-9] ASCII / box-drawing art auto-fencing
- **Component(s):** `MarkdownRenderer.tsx` `autoFenceAsciiArt` (`:43-93`, `pre.ascii-art` passthrough
  in `CustomPre :195-211`)
- **Preconditions:** Ask: *"Draw a small box-drawing diagram (├ │ └ ─ ┌ ┐) of a 3-node pipeline."*
- **Steps:** 1. Send it. 2. Inspect alignment.
- **Expected — visual:** The box-drawing lines render in a monospace `<pre class="ascii-art">` with
  alignment preserved — not collapsed into proportional-font paragraphs.
- **Expected — behavioral:** Lines with ≥3 box-drawing chars (`:38-41`) are buffered and wrapped; lines
  already inside a fence or `<pre>` are left alone.
- **✅ Checks (tick each):**
  - [ ] Box-drawing diagram keeps column alignment (monospace)
  - [ ] Non-art prose around it is unaffected
- **🐞 Bug signals:** Diagram misaligned / proportional font; surrounding text swallowed into the pre.
- **Theme/size matrix:** light + dark.

### [MSG-10] Inline & block math ($…$ / ```math)
- **Component(s):** `MarkdownRenderer.tsx` `PNode` inline-math split (`:420-439`), `MathBlock` route
  for ```math (`:262-263`)
- **Preconditions:** Ask: *"Give an inline formula like `$E = mc^2$` in a sentence, and a display math
  fenced block ```math with an integral."*
- **Steps:** 1. Send it. 2. Inspect both.
- **Expected — visual:** Inline `$…$` renders as inline KaTeX within the paragraph; the ```math fence
  renders a centered display equation.
- **Expected — behavioral:** Paragraphs containing `$` are split on `/(\$[^$]+\$)/` and each `$…$`
  segment goes through `MathBlock` (`displayMode=false`); the fenced `math` language routes to
  `MathBlock` (`displayMode=true`).
- **✅ Checks (tick each):**
  - [ ] Inline math renders inline (not on its own line)
  - [ ] Block math renders centered/display
  - [ ] A lone `$` in normal prose doesn't corrupt the paragraph
- **🐞 Bug signals:** `$E = mc^2$` shown as literal text; a stray `$` eating the rest of a paragraph.
- **Theme/size matrix:** light + dark.

---

## C. Fenced code blocks (syntax highlight, label, copy, scroll) — PRIORITY

### [MSG-11] Fenced code block — syntax highlight + language label
- **Component(s):** `MarkdownRenderer.tsx` `CodeNode` (`:223`), `CodeBlock.tsx`, `useShiki.ts`
- **Preconditions:** Ask: *"Show a fenced ```kotlin code block (~15 lines) with a class and a function."*
- **Steps:** 1. Send it. 2. Inspect the finalized block.
- **Expected — visual:** A bordered code card; header strip with the **language label** ("KOTLIN",
  uppercase, `:148-150`) on the left, and a toolbar (`#`, copy, apply) on the right; body shows
  Shiki-highlighted tokens in the IDE-appropriate theme (`DARK_THEME`/`LIGHT_THEME`).
- **Expected — behavioral:** `useShiki` highlights async; while loading it shows a 2-line skeleton
  (`CodeBlock.tsx:181-185`). Unknown/unshipped languages fall back to plain monospace
  (`useShiki.ts plainTextFallback`) — the label still shows.
- **✅ Checks (tick each):**
  - [ ] Language label matches the fence (KOTLIN/JAVA/etc.); bare fence shows "code"
  - [ ] Syntax colors render (keywords/strings distinct) after the brief skeleton
  - [ ] An unsupported language renders as readable plain monospace (no crash)
- **🐞 Bug signals:** Label wrong/missing; permanent skeleton (Shiki never resolves); white-on-white in
  light theme; whole block unhighlighted for a common language.
- **Theme/size matrix:** light + dark (highlight theme must flip with IDE theme).

### [MSG-12] Code-block COPY button + "Copied" feedback — PRIORITY
- **Component(s):** `CodeBlock.tsx` (`CopyButton text={code}` `:163`), `copy-button.tsx`,
  Kotlin `kotlinBridge.copyToClipboard`; oracle `e2e/chat-copy.spec.ts`
- **Preconditions:** A finalized fenced code block in chat. Best with a **long** block (>200 lines) so
  truncation bugs surface.
- **Steps:** 1. Hover the code card; locate the copy icon in the header. 2. Click it. 3. Paste into an
  external editor (or the chat input) and compare. 4. Watch the icon.
- **Expected — visual:** Copy icon (rectangle glyph) flips to a green checkmark for ~2 s; tooltip/aria
  flips to "Copied!"/"Copied" then back to "Copy code" (`copy-button.tsx:82-95`).
- **Expected — behavioral:** The **entire** code string is copied — not a preview/truncated slice
  (e2e pins the full ~15K string with no truncation, `chat-copy.spec.ts:28-36`). In JCEF it routes
  through the Kotlin clipboard bridge (`copy-button.tsx:52-60`), not `navigator.clipboard`.
- **✅ Checks (tick each):**
  - [ ] Pasted content is byte-for-byte the full code block (verify the LAST line is present)
  - [ ] Checkmark + "Copied" state appears, then reverts after ~2 s
  - [ ] Works on a very long block (no truncation)
  - [ ] Clicking copy does NOT trigger Apply or any other toolbar action
- **🐞 Bug signals:** Only the visible/first lines copied; copy silently does nothing (bridge error
  swallowed — check the devtools console for `[CopyButton] clipboard bridge copy failed`); checkmark
  sticks forever; copies the wrong block when several are present.
- **Theme/size matrix:** light + dark; long content.

### [MSG-13] Code-block long-line HORIZONTAL scroll — PRIORITY
- **Component(s):** `CodeBlock.tsx` body `overflow-x-auto` (`:187, :201`)
- **Preconditions:** Ask: *"Show a code block with one ~300-character single line that does not wrap."*
- **Steps:** 1. Send it. 2. Try to scroll the code body horizontally. 3. Narrow the tool window.
- **Expected — visual:** The long line stays on one line and the code body gets its own **horizontal
  scrollbar**; the line does NOT wrap and does NOT widen the chat column.
- **Expected — behavioral:** Both the outer card and the inner code container are `overflow-x-auto`;
  line-number gutter (if toggled) stays fixed while code scrolls.
- **✅ Checks (tick each):**
  - [ ] Long line is horizontally scrollable inside the code card
  - [ ] Line is not wrapped; chat column width is unchanged
  - [ ] In a narrow window the block still contains its own scroll
- **🐞 Bug signals:** Long line wraps; code block stretches the whole chat; no horizontal scroll; the
  copy/apply header scrolls away with the code.
- **Theme/size matrix:** light + dark; narrow + wide.

### [MSG-14] Code-block line-numbers toggle + Apply button
- **Component(s):** `CodeBlock.tsx` line-numbers toggle (`#`, `:153-160`), Apply (`:166-176`,
  `handleApply → _applyCode` `:122-127`)
- **Preconditions:** A finalized code block.
- **Steps:** 1. Click the `#` (line numbers) button. 2. Click it again to toggle off. 3. (Optional, see
  write note) Click Apply.
- **Expected — visual:** `#` toggles a left line-number gutter (right-aligned, muted). Apply is a
  down-arrow glyph.
- **Expected — behavioral:** Line numbers reflect `code.split('\n')` count and align with rows; the
  toggle is local component state (survives re-render but resets on remount).
- **✅ Checks (tick each):**
  - [ ] Line numbers appear/disappear on toggle and align with code lines
  - [ ] Gutter doesn't scroll horizontally with the code
- **🐞 Bug signals:** Misaligned numbers; gutter overlapping code; toggle no-op.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** **Apply** calls `_applyCode(code, language)` which can insert/modify code in the
  active IDE editor. Do **not** click Apply against real project files during read-only QA — only note
  the button's presence, or test on a scratch file you can discard.

### [MSG-15] Incomplete fence while streaming → plain, then swaps to Shiki on close
- **Component(s):** `MarkdownRenderer.tsx` `CodeNode` `useIsCodeFenceIncomplete` → `streaming-code-plain`
  (`:250-251`); `CodeBlock` empty-streaming skeleton (`CodeBlock.tsx:132-142`)
- **Preconditions:** Ask for a long fenced code block so you can watch it stream.
- **Steps:** 1. Send the prompt. 2. Watch the code block while it is still streaming (fence not yet
  closed). 3. Watch the moment the closing ``` arrives.
- **Expected — visual:** While the fence is open, code renders as plain monospace `<pre
  class="streaming-code-plain">` (no Shiki, no header toolbar). When the fence closes, it swaps to the
  full Shiki `CodeBlock` with header + copy/apply.
- **Expected — behavioral:** Shiki (slow) is intentionally skipped for the open fence to keep streaming
  smooth; the swap happens once.
- **✅ Checks (tick each):**
  - [ ] Open fence streams as plain monospace (no jank, no toolbar)
  - [ ] On close it becomes the highlighted CodeBlock with copy/apply header
  - [ ] No duplicate code block left behind after the swap
- **🐞 Bug signals:** Heavy stutter while a code block streams (Shiki running per token); the block
  never upgrading to highlighted; a leftover plain block beside the highlighted one.
- **Theme/size matrix:** light + dark.

### [MSG-16] Code-block line highlight / annotation meta
- **Component(s):** `MarkdownRenderer.tsx` `remarkCodeMeta` (`:99-110`), `CodeBlock.parseCodeMeta` +
  `applyLineDecorations` + `injectAnnotationIcons` (`CodeBlock.tsx:10-95`)
- **Preconditions:** Ask: *"Show a fenced js block with meta ```js highlight={2,4-5} annotation={2:\"bug here\"}."*
  (Model cooperation varies — re-ask if meta is dropped.)
- **Alternative repro (deterministic):** If the live model won't emit the exact fenced-meta syntax,
  use the **dev showcase build** to render the highlight/annotation meta deterministically (it hand-crafts
  the `data-meta` payload rather than relying on the model), then run the same checks there.
- **Steps:** 1. Send it. 2. Inspect the highlighted lines and the annotation icon. 3. Hover the icon.
- **Expected — visual:** Lines 2 and 4-5 get a highlight background (`code-line-highlight`); line 2 has
  an annotation icon with a tooltip ("bug here").
- **Expected — behavioral:** Meta survives remark→HAST→CodeBlock via `data-meta`
  (`MarkdownRenderer.tsx:247`); decorations apply only when `highlights`/`annotations` are non-empty.
- **✅ Checks (tick each):**
  - [ ] Specified lines are visually highlighted
  - [ ] Annotation icon + tooltip render on the annotated line
  - [ ] Blocks without meta are unaffected
- **🐞 Bug signals:** Highlight on wrong lines (off-by-one); annotation tooltip empty/escaped wrong;
  meta string shown as literal text in the label.
- **Theme/size matrix:** light + dark.

---

## D. Links & link-confirm modal

### [MSG-17] Auto-linkify file paths + Jira keys
- **Component(s):** `ChatLinkifier.ts` (`JIRA_RE`, `FILE_RE`, `:6-13`), wired as `remarkChatLinkify`
- **Preconditions:** Ask: *"Mention the file `src/main/App.kt:42` and the ticket `WORK-1234` in a
  sentence."*
- **Steps:** 1. Send it. 2. Inspect the rendered sentence.
- **Expected — visual:** `App.kt:42` and `WORK-1234` become underlined links (`--link` color); the
  surrounding prose is normal text.
- **Expected — behavioral:** File matches become `file:` hrefs (with `:line`); Jira keys become `jira:`
  hrefs. Text inside code/inline-code/existing-links is **not** linkified (`SKIP_PARENT_TYPES`).
- **✅ Checks (tick each):**
  - [ ] File path (with `:line`) becomes a link
  - [ ] Jira key becomes a link
  - [ ] A path written inside `inline code` is NOT linkified
- **🐞 Bug signals:** Linkifying inside code; over-matching ordinary words; `:42` split off the link.
- **Theme/size matrix:** light + dark.

### [MSG-18] External/web link → LinkConfirmModal (confirm / cancel / copy)
- **Component(s):** `MarkdownRenderer.AnchorNode` → `ChatLink.tsx` → `LinkConfirmModal.tsx`
- **Preconditions:** Ask the agent to include an `https://…` URL and a `jira:WORK-1` link.
- **Steps:** 1. Click an external `https://` link. 2. Read the dialog. 3. Press **Esc** (cancel).
  4. Re-open, click **Copy URL**. 5. Re-open, focus check: just press **Enter** without clicking.
- **Expected — visual:** A modal titled **"Open in browser?"** with a kind badge (Web/Jira/Unknown),
  a description, the resolved URL in a monospace box, and three buttons: **Cancel** / **Copy URL** /
  **Open Link** (`LinkConfirmModal.tsx:186-201`).
- **Expected — behavioral:** **Cancel is auto-focused** (`:90-93`) so Enter/Space defaults to the SAFE
  action (does NOT open the link). Esc or click-outside closes (`onEscapeKeyDown`/`onPointerDownOutside`).
  **Copy URL** copies the resolved browser URL (jira → `…/browse/TICKET`) and shows "Copied" ~350 ms then
  closes. The URL is fetched via the `_resolveLink` bridge; if the bridge is missing it shows an
  "Unknown" resolution gracefully.
- **✅ Checks (tick each):**
  - [ ] Modal opens on external/jira link click (link never opens directly)
  - [ ] Cancel is focused; pressing Enter does NOT open the link
  - [ ] Esc and click-outside both close without navigating
  - [ ] Copy URL copies the resolved URL and flips to "Copied"
  - [ ] Jira link resolves to a browse URL (badge = Jira)
- **🐞 Bug signals:** Link opens with no confirm; Enter opens the link (wrong default focus); Copy
  copies the raw `jira:` scheme instead of the browse URL; modal stuck open after Esc.
- **Theme/size matrix:** light + dark; long URL (must wrap with `break-all`).
- **⛔ Write note:** **Open Link** launches an external browser via `_openLink`. Do NOT click Open Link
  against a real URL during QA unless intended.

### [MSG-19] IDE-local links (file:/class:/symbol:) navigate directly — no modal
- **Component(s):** `MarkdownRenderer.AnchorNode` (`IDE_LOCAL_SCHEMES :304`, symbol branch `:313-330`,
  file/class branch `:334-349`), `_navigateToFile` / `_openLink` bridges
- **Preconditions:** Get a `file:` link in chat (MSG-17) for a file that exists in the open project.
- **Steps:** 1. Click a `file:App.kt:42` link. 2. Click a `symbol:` link if present.
- **Expected — visual:** **No** confirm modal. The IDE editor opens/focuses the target file (and line
  for `:line`).
- **Expected — behavioral:** `file:`/`class:` call `_openLink`; `symbol:` calls `_navigateToFile`
  with `canonical:line+1` from the stamped `data-canonical`/`data-line`.
- **✅ Checks (tick each):**
  - [ ] file:/class:/symbol: links open the editor with NO browser-confirm modal
  - [ ] Line number is honored (caret lands on the right line)
- **🐞 Bug signals:** IDE-local link routed through the browser modal; navigation to the wrong line
  (off-by-one); dead link with no feedback.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** This navigates/opens an editor tab (read-only — opens a file, does not modify it),
  but note it changes IDE editor focus.

---

## E. Thinking / reasoning block

### [MSG-20] Live streaming thinking block (open by default, shimmer)
- **Component(s):** `ChatFooter.tsx` streaming `ThinkingView` (`:93-97`), `ThinkingView.tsx`,
  prompt-kit `reasoning.tsx`; Kotlin `thinkingStreamBatcher` (`AgentController.kt:505-518`)
- **Preconditions:** Pick a **reasoning-capable** model (🧠 badge in the picker). Send a prompt that
  makes it think, e.g. *"Carefully reason step by step about how to reverse a linked list."*
- **Steps:** 1. Send it. 2. Watch the thinking block as the model reasons (before prose).
- **Expected — visual:** A reasoning row with a Brain icon and a shimmering **"Thinking…"** label;
  the block is **expanded** while streaming, showing the reasoning text growing live with a shimmer.
  It appears ABOVE the prose stream (LLM emits `<thinking>` first).
- **Expected — behavioral:** Rendered with `isStreaming={true}`; auto-opened; not auto-collapsed while
  streaming. Deltas arrive batched (16 ms) — smooth, not per-token jitter.
- **✅ Checks (tick each):**
  - [ ] Thinking block is expanded and streaming live during reasoning
  - [ ] "Thinking…" label shimmers; Brain icon present
  - [ ] Reasoning text is smooth (batched), appears above the prose reply
- **🐞 Bug signals:** Thinking text dumped all-at-once at the end; block collapsed during streaming;
  reasoning leaking into the main prose bubble; per-character stutter.
- **Theme/size matrix:** light + dark.

### [MSG-21] Finalized thinking — auto-collapse, expand/collapse, "Thought for Ns", copy
- **Component(s):** `ChatView.renderItem` REASONING branch (`:192-198`), `ThinkingView.tsx`
  (auto-collapse `:56-64`, label `:31-38`, copy `:101-108`)
- **Preconditions:** Continue from MSG-20 until the reasoning block finalizes.
- **Steps:** 1. Let thinking finish. 2. Watch it auto-collapse. 3. Click the header to expand.
  4. Hover the header and click **Copy thinking**. 5. Re-collapse.
- **Expected — visual:** ~600 ms after finalizing, the block **auto-collapses** to a one-line summary
  reading **"Thought for Ns"** (e.g. "Thought for 7s", or "Thought for <1s"). Clicking the header
  expands it again (chevron rotates); a **Copy thinking** button shows on hover.
- **Expected — behavioral:** Duration prefers the stamped `thinkingDurationMs` (survives resume/remount)
  over the internal timer. Re-expand/collapse is user-controlled after auto-collapse. Copy puts the
  full reasoning text on the clipboard (same `CopyButton` semantics as MSG-12).
- **✅ Checks (tick each):**
  - [ ] Block auto-collapses ~600 ms after finishing
  - [ ] Label reads "Thought for Ns" (sensible duration)
  - [ ] Expand/collapse toggles on header click (chevron rotates)
  - [ ] Copy thinking copies the FULL reasoning text
- **🐞 Bug signals:** Never collapses / collapses instantly; duration "0s"/wrong; chevron stuck; copy
  empty or truncated; collapsed height not animating (jumpy).
- **Theme/size matrix:** light + dark.

---

## F. SCROLLING — stick / release / scroll-to-bottom / no-jump / restore — PRIORITY

> The scroller is `react-virtuoso` configured in `MessageList.tsx`: `followOutput="auto"` (`:102`),
> `atBottomThreshold={120}` (`:103`), `atBottomStateChange` feeds `ChatView.isAtBottom` (`:104,341`),
> `increaseViewportBy top/bottom 400` (`:105`), `defaultItemHeight={96}` (`:101`), stable
> `computeItemKey` (`ChatView.computeItemKey :305-309`). The scroll-to-bottom affordance is
> `<ScrollButton>` (`ChatView.tsx:344-349`, `scroll-button.tsx`).

### [MSG-22] Auto-scroll STICKS to bottom while streaming (when already at bottom)
- **Component(s):** `MessageList.tsx` `followOutput="auto"`, `ChatView` `ScrollButton`/`isAtBottom`
- **Preconditions:** A fresh chat scrolled to the bottom.
- **Steps:** 1. Stay parked at the bottom. 2. Send a long prompt (e.g. 25-item list). 3. Do NOT touch
  the scrollbar while it streams.
- **Expected — visual:** As new tokens/rows stream in, the viewport stays pinned to the bottom — newest
  content is always visible without manual scrolling. The scroll-to-bottom button stays hidden.
- **Expected — behavioral:** `followOutput="auto"` keeps the list at the bottom because the user is at
  bottom; `isAtBottom` stays true (within the 120 px threshold) so `ScrollButton` is hidden
  (`scroll-button.tsx:30-34`).
- **✅ Checks (tick each):**
  - [ ] Viewport stays pinned to bottom through the whole stream
  - [ ] Scroll-to-bottom button stays hidden while pinned
  - [ ] No need to manually scroll to see the newest tokens
- **🐞 Bug signals:** Viewport lags behind (newest text below the fold while pinned); jitter/jumping;
  button flashing while at bottom.
- **Theme/size matrix:** light + dark; narrow + wide.

### [MSG-23] RELEASE auto-scroll when the user scrolls up mid-stream
- **Component(s):** `MessageList.tsx` `followOutput="auto"` + `atBottomStateChange`,
  `ChatView.isAtBottom`
- **Preconditions:** A reply actively streaming (start MSG-22, then act mid-stream).
- **Steps:** 1. While a long reply streams, scroll UP (wheel or drag the thumb) to read earlier content.
  2. Keep reading; let the stream continue.
- **Expected — visual:** The viewport **stops** auto-following — it stays where you scrolled, it does
  NOT yank you back to the bottom on each new token. The scroll-to-bottom button appears.
- **Expected — behavioral:** `followOutput="auto"` only follows when at the bottom; scrolling up flips
  `isAtBottom` → false, releasing the follow and revealing `<ScrollButton>` (`opacity-100`,
  pointer-events enabled).
- **✅ Checks (tick each):**
  - [ ] Scrolling up mid-stream releases the auto-follow (no snap-back)
  - [ ] Your read position is stable while tokens keep arriving below
  - [ ] Scroll-to-bottom button appears once you leave the bottom
- **🐞 Bug signals:** Viewport yanks back to bottom on every token (most painful bug); position drifts
  while reading; button never appears.
- **Theme/size matrix:** light + dark; narrow + wide.

### [MSG-24] Scroll-to-bottom button appears, hides, and re-pins on click
- **Component(s):** `ChatView.tsx` `<ScrollButton onClick=scrollToBottom>` (`:344-349`),
  `MessageList.scrollToBottom` (smooth, `index:'LAST'`, `align:'end'`, `:61-67`), `scroll-button.tsx`
- **Preconditions:** A chat with enough history to scroll (or use MSG-23's released state).
- **Steps:** 1. Scroll up so the button shows. 2. Inspect its position/animation. 3. Click it. 4. Watch
  it hide once at bottom.
- **Expected — visual:** A circular chevron-down button (`h-10 w-10 rounded-full`) centered near the
  bottom (`bottom-4 left-1/2`). When not at bottom it's visible (`opacity-100 translate-y-0`); when at
  bottom it's hidden (`opacity-0 translate-y-4 pointer-events-none`). Clicking smooth-scrolls to the
  very bottom, then the button fades out.
- **Expected — behavioral:** Click calls `scrollToBottom()` → Virtuoso `scrollToIndex({index:'LAST',
  align:'end', behavior:'smooth'})`; on reaching bottom `atBottomStateChange(true)` hides the button.
- **✅ Checks (tick each):**
  - [ ] Button appears only when scrolled away from bottom
  - [ ] Click smooth-scrolls all the way to the last message
  - [ ] Button fades out once at bottom (and is non-interactive when hidden)
- **🐞 Bug signals:** Button always visible (even at bottom); click jumps instantly (not smooth) or
  lands short of the last message; hidden button still clickable.
- **Theme/size matrix:** light + dark.

### [MSG-25] No scroll-jump when a new row is appended below the viewport
- **Component(s):** `MessageList.tsx` Virtuoso stable `computeItemKey` + `defaultItemHeight`; oracle
  `e2e/chat-scroll.spec.ts` ("position stays put when a row is appended at the bottom")
- **Preconditions:** A long chat; scroll to roughly the middle and read.
- **Steps:** 1. Scroll to ~50% and note where you are. 2. Trigger more content below (a tool call
  completing, a new assistant message, or steering) without scrolling. 3. Observe your position.
- **Expected — visual:** Your scroll position does NOT move when content is appended **below** the
  viewport — you keep reading the same line. (The e2e pins `|after - before| <= 4 px`.)
- **Expected — behavioral:** Stable per-item keys let Virtuoso keep its measured-height cache, so
  appends below don't reflow the visible region or move the thumb under you.
- **✅ Checks (tick each):**
  - [ ] Reading position holds steady when rows are added below
  - [ ] The scrollbar thumb doesn't jump/teleport on append
  - [ ] Tool-call group additions (which collapse multiple rows) don't shift your view
- **🐞 Bug signals:** Reading position jumps when a message/tool result lands below; thumb drifts; the
  list briefly blanks/reflows on append.
- **Theme/size matrix:** light + dark; narrow + wide.

### [MSG-26] Virtualization correctness — both ends reachable, thumb maps to position
- **Component(s):** `MessageList.tsx` (`defaultItemHeight={96}`, `computeItemKey`); oracle
  `e2e/chat-scroll.spec.ts` ("both ends reachable", "~50% lands near the middle")
- **Preconditions:** A long chat (60+ rows of mixed heights: prose, code, tables, tool chains).
- **Steps:** 1. Drag the scrollbar to the very bottom. 2. Drag to the very top. 3. Drag to ~50% and
  note the visible region. 4. Drag to ~25%.
- **Expected — visual:** The very last message is reachable at the bottom and the very first at the top
  (no overshoot that hides an end). 50% lands near the middle of the conversation; 25% lands strictly
  higher up than 50% (monotonic thumb→position mapping).
- **Expected — behavioral:** With a sane `defaultItemHeight` and stable keys, the height estimate
  doesn't wildly over/undershoot, so the thumb tracks the cursor while dragging.
- **✅ Checks (tick each):**
  - [ ] Last message reachable at bottom; first message reachable at top
  - [ ] Dragging to ~50% lands mid-conversation
  - [ ] 25% is higher than 50% (no inverted/erratic mapping)
  - [ ] Thumb roughly tracks the cursor while dragging (no big snap)
- **🐞 Bug signals:** Can't reach the last/first message; thumb overshoots and snaps back; 50% lands at
  the very top/bottom; blank gaps while fast-scrolling.
- **Theme/size matrix:** light + dark; narrow + wide.

### [MSG-27] Scroll position on session switch (retain/restore) — PRIORITY, verify behavior
- **Component(s):** `bridge/jcef-bridge.ts` `loadSessionState` → `chatStore.hydrateFromUiMessages`
  (`:2627`); `MessageList.tsx` Virtuoso (no `restoreStateFrom`/`initialTopMostItemIndex` is wired —
  see Open Questions)
- **Preconditions:** Two sessions in History, each with enough content to scroll. In one, scroll to the
  middle and leave it there.
- **Steps:** 1. In session A, scroll to the middle. 2. Open History; switch to session B. 3. Switch
  back to session A. 4. Observe where session A lands.
- **Expected — visual:** **Concrete outcome — session A lands at the BOTTOM; the per-session mid-scroll
  offset is NOT preserved.** `MessageList` wires no `restoreStateFrom`/`initialTopMostItemIndex` and
  `hydrateFromUiMessages` replaces `messages[]` wholesale, so there is **no scroll-offset persistence** —
  the list resets to Virtuoso's default for a freshly-replaced item set (`followOutput="auto"` → bottom).
  **PASS = lands at bottom**, no stale/blank content. This is a **KNOWN GAP — README §4 item S6 — not a
  fresh regression.** Still **REPORT** if the behavior differs from "lands at bottom" (e.g. it lands
  mid-scroll, at the top, blank, or shows session B's content).
- **Expected — behavioral:** On switch, `hydrateFromUiMessages` replaces `messages[]` wholesale;
  Virtuoso re-renders from scratch. There is no scroll-offset save/restore in the webview.
- **✅ Checks (tick each):**
  - [ ] Session A lands at the **bottom** after the round-trip (mid-scroll offset NOT preserved — known S6 gap); report if it differs
  - [ ] No crash, no blank list, no stale content from session B bleeding in
  - [ ] Switching is responsive (no long freeze on large sessions)
- **🐞 Bug signals:** Stale messages from the other session shown; blank/0-height list after switch;
  content from B appended to A. **NOTE:** "doesn't restore mid-scroll" is EXPECTED given current
  source — file as a UX gap (see Open Questions), not a regression.
- **Theme/size matrix:** light + dark.

---

## G. Whole-message copy, overflow, working indicator, toasts

### [MSG-28] Copy WHOLE message + hover affordances (timestamp, revert)
- **Component(s):** `AgentMessage.tsx` `CopyButton text={content}` (`:333-340`), hover timestamp
  (`:343-350`), user-message `UserMessageRevertButton` (`:287-289`)
- **Preconditions:** A finalized assistant message and a user message in chat.
- **Steps:** 1. Hover an assistant bubble; click the top-right **Copy message** button. 2. Paste and
  compare. 3. Hover a user message and observe the time-travel/revert affordance and timestamp.
- **Expected — visual:** Copy button appears on hover (top-right of the bubble); flips to checkmark on
  copy. Timestamp (HH:MM) fades in on hover at the bottom of the bubble. User messages show a
  "⟲ Time-travel here" revert affordance above on hover.
- **Expected — behavioral:** Copy captures the whole `message.text` (markdown source), available even
  while streaming (captures what's streamed so far). Timestamp from `message.ts`.
- **✅ Checks (tick each):**
  - [ ] Copy message copies the FULL message text (verify the end is present)
  - [ ] Copy button + timestamp appear only on hover; checkmark feedback fires
  - [ ] User-message hover shows the revert/time-travel affordance + timestamp
- **🐞 Bug signals:** Copy truncates; copy/timestamp always visible (no hover gating); copies the wrong
  bubble; revert affordance on assistant messages.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** The user-message **⟲ Time-travel here** affordance reverts files + truncates chat
  history when clicked — observe its presence only; do NOT click it during read-only QA.

### [MSG-29] Very long / unbroken message wrap & overflow
- **Component(s):** `AgentMessage.tsx` bubble (`max-w-[85%]`, `whitespace-normal [overflow-wrap:anywhere]`,
  `:234`), `UserContent` (`whitespace-pre-wrap`, `:120/:124`)
- **Preconditions:** Send a user message containing a very long unbroken token (e.g. a 200-char string
  with no spaces, or a long URL). Also ask the agent for a very long paragraph.
- **Steps:** 1. Send the long-unbroken user message. 2. Get a long assistant paragraph. 3. Narrow the
  tool window to ~320 px.
- **Expected — visual:** Bubbles cap at 85% width; long unbroken strings break (`overflow-wrap:anywhere`)
  instead of forcing a horizontal scrollbar on the whole chat; user message preserves its own line
  breaks (`whitespace-pre-wrap`).
- **Expected — behavioral:** No element forces the chat column wider than the tool window.
- **✅ Checks (tick each):**
  - [ ] Long unbroken token wraps inside the bubble (no page-wide horizontal scroll)
  - [ ] Bubbles respect the 85% max width
  - [ ] User message keeps its newlines; in a narrow window everything stays readable
- **🐞 Bug signals:** A long string blowing out the chat width; clipped text; bubble overlapping the
  avatar; horizontal scrollbar on the whole message list.
- **Theme/size matrix:** light + dark; narrow + wide (overflow is the point).

### [MSG-30] WorkingIndicator animation while the loop runs (+ reduced-motion)
- **Component(s):** `ChatFooter.tsx` `{busy && <WorkingIndicator/>}` (`:200`), `WorkingIndicator.tsx`
  (wave `Loader` + `TextShimmer` typewriter, reduced-motion branch `:299-310`)
- **Preconditions:** An active agent turn (`busy=true`).
- **Steps:** 1. Send a prompt and watch the indicator between/around tool calls. 2. (Optional) Enable
  OS "reduce motion" (Windows: Settings ▸ Accessibility ▸ Visual effects ▸ Animation effects OFF) and
  re-run.
- **Expected — visual:** A green wave loader + a shimmering, rotating witty phrase that animates with a
  backspace-then-type transition when the phrase changes. With reduced motion ON, a static green dot +
  plain (non-shimmer, non-wave) phrase text instead.
- **Expected — behavioral:** The phrase is locked per session (`workingFallbackPhrase`) so per-iteration
  remounts don't re-roll it; a smart phrase (`smartWorkingPhrase`) overrides the fallback when present.
  The indicator disappears when `busy` clears (turn ends).
- **✅ Checks (tick each):**
  - [ ] Animated wave + shimmer phrase appears while the agent is working
  - [ ] Phrase doesn't flicker/re-randomize on every iteration
  - [ ] Indicator vanishes when the turn completes
  - [ ] Reduced-motion shows the static dot + plain text (no shimmer/wave)
- **🐞 Bug signals:** Indicator stuck after the turn ends; phrase re-rolling every second; shimmer
  ignoring reduced-motion; indicator missing entirely during work.
- **Theme/size matrix:** light + dark; reduced-motion ON/OFF.

### [MSG-31] ToastStack — appear, auto-dismiss, stack, manual dismiss
- **Component(s):** `ToastStack.tsx`, `chatStore.showToast`/`dismissToast` (`:1823-1835`); oracle
  `__tests__/toast-stack.test.tsx`
- **Preconditions:** Trigger a toast. Easiest paths: attach an oversized image (>5 MB) or a disallowed
  MIME via the input attach button (fires "Image too large…" / mime toasts), or send an image with
  vision disabled in settings.
- **Steps:** 1. Trigger one toast. 2. Trigger two or three quickly to see stacking. 3. Let an
  auto-dismiss toast expire. 4. Click the **×** on a sticky toast.
- **Expected — visual:** Toasts stack bottom-right (`fixed bottom-3 right-3`, `flex-col gap-2`), max
  width 360 px, with a left/border color by type (error=red, warning=amber, success=green, info=default)
  and a **×** dismiss button. Region is `role="region" aria-label="Notifications"`; each toast is
  `role="alert"`.
- **Expected — behavioral:** A toast with `durationMs > 0` auto-dismisses after that delay
  (`setTimeout`); `durationMs <= 0` stays until the user clicks ×. Multiple toasts coexist (newest
  appended at the bottom of the stack). Long messages wrap (`whitespace-pre-wrap break-words`).
- **✅ Checks (tick each):**
  - [ ] Toast appears bottom-right with type-appropriate border color
  - [ ] Multiple toasts stack (don't overwrite each other)
  - [ ] Auto-dismiss toast disappears after its duration
  - [ ] × removes a toast immediately
- **🐞 Bug signals:** Toasts never appear (the historical "no renderer" bug — every showToast
  swallowed); they overlap/replace instead of stacking; auto-dismiss never fires; long toast text
  overflows the card.
- **Theme/size matrix:** light + dark; long toast message (wrap).

---

## Open questions / unverifiable from source

1. **Per-session scroll restore (MSG-27).** No webview code persists a per-session scroll offset or
   passes a Virtuoso state snapshot through `hydrateFromUiMessages` (`chatStore.ts:2627`); `MessageList`
   wires no `restoreStateFrom`/`initialTopMostItemIndex`. So switching sessions almost certainly does
   **not** restore mid-scroll position — the prompt lists this as a thing to verify, but the source
   suggests it is unimplemented. Tester should record the actual landing position and treat
   non-restoration as a UX gap, not a regression.
2. **"View in Editor" tab scrolling (out of band).** `ChatView` has an `editorTabMode` path
   (`ChatView.tsx:316-329`) that bypasses Virtuoso entirely and renders a plain flex column in a
   separate JCEF browser. Scrolling there is native (not virtualized) and is a different surface from
   the tool-window list; scenarios MSG-22..27 target the tool-window Virtuoso list. If the tester opens
   a "View in Editor" mirror, scroll behavior will differ by design — note it but don't grade it against
   the Virtuoso scenarios.
3. **Reasoning availability is model-dependent.** MSG-20/MSG-21 require a reasoning-capable model
   emitting `<thinking>`; if no such model is configured, the thinking block won't appear and those
   scenarios can't be exercised.
4. **Code-meta (MSG-16) and exact markdown shapes depend on the model** honoring the requested syntax;
   re-ask if the model drops `highlight={…}`/`annotation={…}` meta. The deterministic **dev-showcase**
   fallback is now promoted into the MSG-16 scenario body (see its "Alternative repro" bullet).
5. **Toast trigger surfaces (MSG-31)** are driven by input/attachment validation and settings; the
   exact set of user-triggerable toasts in this area is limited — attachment validation is the most
   reliable trigger.

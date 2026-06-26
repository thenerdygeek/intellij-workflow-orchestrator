# Agent Chat — Input & Composer

Manual behavioral test plan for the Agent chat **input bar & composer** (the bottom composer of the "Workflow" tool-window Agent tab, rendered in JCEF/React). Every scenario below traces to webview source under `agent/webview/src/components/input/` plus `ChatFooter.tsx`; `file:line` is cited for non-obvious behavior. Run on licensed Windows IntelliJ Ultimate via `./gradlew runIde` with all connectors + Sourcegraph tokens configured. **Read-only rule:** never let the agent run a write tool against real project files; one throwaway scratch file is the only permitted write. When a control sends a turn to the live agent, use a trivial read-only prompt (e.g. `Reply with just OK`) and press **Stop** if it begins any tool work.

Element handles used below (from source): the composer is the `rounded-xl border` box (`InputBar.tsx:1098-1102`); the editor is the `contenteditable` `rich-input` div; **Send** = `button[aria-label="Send (Enter)"]`; **Stop** = `button[aria-label="Stop"]`; **+** = `button[aria-label="Add context or skill"]`.

## Composer container, typing & send

### [IN-1] Default placeholder & resting layout
- **Component(s):** `InputBar.tsx:551-569`, `RichInput.tsx:784-809`
- **Preconditions:** Fresh Agent tab, no session running (not busy), input empty.
- **Steps:** 1. Open the Workflow tool window → Agent tab. 2. Observe the empty composer without clicking it.
- **Expected — visual:** Rounded-xl bordered box using `--input-bg`/`--input-border`. Greyed placeholder reads exactly `Ask anything... (@ context, # ticket, / skill)`. Left action row shows **+**, Model chip, **Plan**, optional **Skills**, **···**. Right side shows only the round Send arrow (no Stop).
- **Expected — behavioral:** Placeholder is rendered via CSS `:empty:before` on the contenteditable (`data-placeholder`), so the caret is not displaced.
- **✅ Checks (tick each):**
  - [ ] Placeholder string is character-exact (ellipsis is literal `...`).
  - [ ] Placeholder uses muted color `--fg-muted`, not full-contrast text.
  - [ ] Send arrow is present and visibly disabled (dimmed, not filled).
  - [ ] No Stop button visible while idle.
- **🐞 Bug signals:** placeholder blank/clipped; placeholder overlaps the action chips; full-contrast (looks like real typed text); box border invisible in one theme.
- **Theme/size matrix:** light + dark; narrow tool window vs wide.

### [IN-2] Type text and send with Enter
- **Component(s):** `RichInput.tsx:596-599` (Enter), `InputBar.tsx:973-1058` (handleSend), `chatStore.ts:1969-1986`
- **Preconditions:** Idle session, tokens configured. Use a read-only prompt.
- **Steps:** 1. Click the composer. 2. Type `Reply with just OK`. 3. Press **Enter**. 4. As soon as any tool activity appears, press **Stop**.
- **Expected — visual:** While typing, Send arrow becomes filled (`backgroundColor: var(--fg)`, `color: var(--bg)`; `InputBar.tsx:648-651`). On Enter the input clears to empty + placeholder returns; the typed text appears as a user bubble in the transcript.
- **Expected — behavioral:** Enter (no Shift) calls `onSubmit`; the message dispatches via `sendMessage`. Editor clears (`ri.clear()` at `InputBar.tsx:1054`).
- **✅ Checks (tick each):**
  - [ ] Send arrow turns filled/enabled once text is present.
  - [ ] Enter sends and the composer empties (placeholder reappears).
  - [ ] The exact typed text shows in the user message bubble.
  - [ ] Caret returns to the now-empty composer (focus retained).
- **🐞 Bug signals:** Enter inserts a newline instead of sending; input keeps the text after send; double-send; Send stays disabled despite text.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** Sending starts the agent loop; keep the prompt read-only and press **Stop** if it attempts any tool. `⛔ SKIP-EXECUTION (write op)` only if you accidentally prompt a write — then Cancel/Stop.

### [IN-3] Multiline with Shift+Enter (no send)
- **Component(s):** `RichInput.tsx:596-599`
- **Preconditions:** Idle, composer focused.
- **Steps:** 1. Type `line one`. 2. Press **Shift+Enter**. 3. Type `line two`. 4. Verify nothing sent. 5. Press **Enter** to send, then **Stop**.
- **Expected — visual:** After Shift+Enter the composer grows to two visible lines; both lines retained. On Enter the multi-line text reaches the bubble with the newline preserved.
- **Expected — behavioral:** Shift+Enter does NOT call onSubmit (mirrors `chat-input.spec.ts:59-76`). The newline is kept; `extractText` joins lines with `\n` (`RichInput.tsx:331-351`).
- **✅ Checks (tick each):**
  - [ ] Shift+Enter inserts a newline, does not send.
  - [ ] Composer auto-grows to fit the second line (no scroll-clip of line one).
  - [ ] Enter then sends both lines; bubble shows the line break.
- **🐞 Bug signals:** Shift+Enter sends; second line overlaps the action row; composer doesn't grow and line two is hidden.
- **Theme/size matrix:** light + dark; long content (5+ lines) to check growth vs max-height.

### [IN-4] Empty-input Send disabled; enabled on first character
- **Component(s):** `InputBar.tsx:1088` (`canSend`), `:641-654`
- **Preconditions:** Idle, empty composer.
- **Steps:** 1. Confirm Send is disabled. 2. Type one character. 3. Delete it back to empty.
- **Expected — visual:** Send disabled (dim) when empty; filled when ≥1 non-space char; returns to disabled when emptied.
- **Expected — behavioral:** `canSend = (hasText || attachments>0) && !locked && !outerCompacting && (!busy || steeringMode)`. `hasText` updates from `onChange` (`InputBar.tsx:856`).
- **✅ Checks (tick each):**
  - [ ] Send disabled at empty.
  - [ ] Send enables on first character.
  - [ ] Send re-disables when input is cleared by hand.
  - [ ] A whitespace-only input (just spaces) leaves Send enabled but Enter/click does nothing (`handleSend` early-returns on `!text.trim()` with no mentions/attachments, `InputBar.tsx:995`).
- **🐞 Bug signals:** Send stays enabled at empty; Send never enables; stale enabled state after clear.
- **Theme/size matrix:** light + dark.

### [IN-5] Click-to-focus & focus/blur placeholder
- **Component(s):** `InputBar.tsx:1101` (box onClick → focus), `RichInput.tsx:501-513` (updateEmptyState)
- **Preconditions:** Idle, empty.
- **Steps:** 1. Click empty padding area inside the box (not directly on text). 2. Type a few chars. 3. Click elsewhere in the IDE to blur. 4. Click back.
- **Expected — visual:** Clicking anywhere in the box focuses the editor and shows the caret. On blur with empty content the placeholder shows again; on blur with content the text stays.
- **Expected — behavioral:** Box `onClick` calls `richInputRef.focus()`. `updateEmptyState` only sets `data-empty` when empty AND not focused (`RichInput.tsx:506`).
- **✅ Checks (tick each):**
  - [ ] Clicking blank area inside the box focuses the editor.
  - [ ] Placeholder hides while focused-and-empty? (Note: placeholder relies on `:empty`; focused empty still shows it since pseudo-element persists — verify it does not flicker.)
  - [ ] Typed content survives blur/refocus.
- **🐞 Bug signals:** click in box doesn't focus; placeholder flickers on focus; content lost on blur.
- **Theme/size matrix:** light + dark.

### [IN-6] Send ↔ Stop button swap while busy
- **Component(s):** `InputBar.tsx:627-655`, `ActionToolbar.tsx` is NOT this (see Open Questions); `jcef-bridge setBusy`
- **Preconditions:** Idle. Use a read-only prompt that takes a moment.
- **Steps:** 1. Send `List the top-level files in this project` (read-only). 2. While the agent is working, observe the right side of the action row. 3. Click **Stop**.
- **Expected — visual:** While busy a red destructive round **Stop** button (filled square icon) appears to the LEFT of the Send arrow (`InputBar.tsx:628-639`). The Send arrow is present but disabled (since `busy && !steeringMode` makes `canSend` false). Clicking Stop halts the run; Stop disappears when not busy.
- **Expected — behavioral:** Stop calls `window._cancelTask` (`InputBar.tsx:1060`). Send disabled during busy non-steering.
- **✅ Checks (tick each):**
  - [ ] Stop button appears only while busy.
  - [ ] Stop is red/destructive styling and round.
  - [ ] Send arrow is disabled during busy (non-steering).
  - [ ] Clicking Stop ends the run and removes the Stop button.
- **🐞 Bug signals:** Stop never appears; Stop persists after completion; Stop and Send overlap; clicking Stop does nothing.
- **Theme/size matrix:** light + dark; narrow (buttons must not wrap/clip).
- **⛔ Write note:** Read-only prompt only; Stop ends the turn before any write.

## Undo / redo (custom stack)

### [IN-7] Ctrl/Cmd+Z undo of a typing burst; redo
- **Component(s):** `RichInput.tsx:533-603` (undo/redo + handleKeyDown), `:159-184` (coalesce 400ms / max 100)
- **Preconditions:** Idle, empty. (This stack is custom — must NOT fall back to native contentEditable undo.)
- **Steps:** 1. Type `first ` and pause ~0.5s. 2. Type `second`. 3. Press **Ctrl+Z** (Cmd+Z on the Mac keymap if applicable). 4. Press **Ctrl+Shift+Z** (or **Ctrl+Y**) to redo.
- **Expected — visual:** Undo removes only the `second` burst, leaving `first `. Redo restores `second`. Caret parks at end after each (`applySnapshot` collapses to end, `:518-531`).
- **Expected — behavioral:** Consecutive typing within 400ms coalesces into one undo step (`UNDO_COALESCE_MS`); the 0.5s pause forces a new step (mirrors `chat-input.spec.ts:78-90`). Both `Z`/`z` and `Y`/`y` handled; `preventDefault` always fires so the keystroke never leaks to the IDE (`:568`).
- **✅ Checks (tick each):**
  - [ ] Ctrl/Cmd+Z removes the most recent burst, not the whole field.
  - [ ] A burst typed without pause undoes as one unit.
  - [ ] Ctrl+Shift+Z and Ctrl+Y both redo.
  - [ ] Undo/redo of a chip insertion restores/removes the whole chip (chips are discrete steps, `:444`).
  - [ ] The IDE editor underneath does NOT also undo (keystroke captured).
- **🐞 Bug signals:** undo nukes entire input; native browser undo fights the custom stack (double-undo); redo does nothing; IDE editor undoes a code change; caret jumps to start.
- **Theme/size matrix:** light + dark (logic only; verify in both for no visual glitch).

## @-mention dropdown (files / folders / symbols)

### [IN-8] `@` opens mention dropdown; empty query shows Open Tabs
- **Component(s):** `MentionDropdown.tsx:76-84` (debounce 200ms), `:163-237`, `InputBar.tsx:855-868`
- **Preconditions:** Idle. Have 1-2 editor tabs open in the IDE so "Open Tabs" is non-empty.
- **Steps:** 1. Open ≥1 file in the editor. 2. In the composer type `@` (nothing after). 3. Observe the floating panel above the input.
- **Expected — visual:** Panel floats above the composer (`absolute bottom-full`), elevated surface with shadow. Header `Open Tabs`; rows show open editor files (active file first) with file icons. A bottom hint row reads `Type to search` + files/folders/symbols legend (`MentionDropdown.tsx:227-237`).
- **Expected — behavioral:** Empty query still requests `all:` and returns open tabs (`:81`). 200ms debounce before request.
- **✅ Checks (tick each):**
  - [ ] `@` alone opens the panel above the input.
  - [ ] "Open Tabs" header present; active editor file listed first.
  - [ ] Legend row with files/folders/symbols icons visible.
  - [ ] Panel does not get clipped by the tool-window bottom edge.
- **🐞 Bug signals:** dropdown opens below/off-screen; empty-query shows "No results found." despite open tabs; clipped/scrolled-out-of-view; wrong-theme low contrast on the elevated surface.
- **Theme/size matrix:** light + dark; narrow window (panel min-width 420px may overflow — check horizontal clip).

### [IN-9] `@`-filter, grouping & relevance, select inserts chip
- **Component(s):** `MentionDropdown.tsx:101-134` (filter/score/group), `relevanceScore` `:31-51`, `InputBar.tsx:947-951` (insert)
- **Preconditions:** Idle. Know a filename in the project.
- **Steps:** 1. Type `@` then a few chars of a known file's name. 2. Observe grouped sections. 3. Click a result (or press Enter).
- **Expected — visual:** Group headers `Files` / `Folders` / `Symbols` (only non-empty groups; `typeLabels`). Up to 5 per group when filtering, 8 when empty (`maxPerGroup`). Best name-prefix matches rank top. Selected row gets a left accent bar + tint. Selecting inserts an inline colored chip (blue file/folder, violet symbol) replacing the `@query` text, caret after the chip.
- **Expected — behavioral:** Staleness gate drops results whose echoed query ≠ current query (`:102`); relevance gate drops score-0 when query non-empty (`:106`). Chip tracked by unique id (`RichInput.tsx:429`).
- **✅ Checks (tick each):**
  - [ ] Group headers appear and match the result types present.
  - [ ] Name matches outrank path-only matches.
  - [ ] Selecting a result inserts a chip and removes the typed `@query`.
  - [ ] Chip shows correct icon/color for its type.
  - [ ] No stale results flash from a previous query while typing fast.
- **🐞 Bug signals:** results lag/flash a prior query; duplicate rows; wrong group; chip inserted but `@query` text left behind (double); chip color wrong/invisible in a theme.
- **Theme/size matrix:** light + dark; wide (long paths in the right-aligned description must truncate, `:212-218`).

### [IN-10] Mention keyboard navigation
- **Component(s):** `useDropdownKeyboard.ts:73-121`, wired `InputBar.tsx:822-850`
- **Preconditions:** Mention dropdown open with ≥3 results.
- **Steps:** 1. Type `@` + chars to get several results. 2. Press **ArrowDown** repeatedly past the end. 3. Press **ArrowUp** past the top. 4. Press **Enter** (or **Tab**) on a highlighted item. 5. Re-open, press **Esc**.
- **Expected — visual:** Highlight moves with arrows and wraps (down past last → first; up past first → last). Highlighted row auto-scrolls into view (`:59-71`). Enter/Tab selects the highlighted row. Esc closes the dropdown with no insertion.
- **Expected — behavioral:** Arrow nav uses modulo wrap (`:82-89`). Enter/Tab on empty list dismisses and lets Enter bubble to submit (`:97-100`). Esc also clears any pending typed `#ticket` (`InputBar.tsx:813-820`).
- **✅ Checks (tick each):**
  - [ ] ArrowDown/Up move highlight and wrap at both ends.
  - [ ] Highlighted item scrolls into view when navigating a long list.
  - [ ] Enter selects highlighted; Tab also selects.
  - [ ] Esc dismisses without inserting; typing continues normally after.
  - [ ] Highlight index matches the visually highlighted row (no off-by-one vs what gets inserted).
- **🐞 Bug signals:** highlight desync from inserted item; no wrap; highlighted row scrolled out of view; Esc leaves dropdown stuck open; Enter both selects AND submits.
- **Theme/size matrix:** light + dark.

### [IN-11] Mention chip removal & duplicate same-named files
- **Component(s):** `RichInput.tsx:281-306` (getMentions dedupe by path), event-delegated × `:633-647`
- **Preconditions:** Idle. Two files share a base name in different folders (if available); else use one file.
- **Steps:** 1. Insert a file chip via `@`. 2. (If two same-named files exist) insert the second. 3. Click the chip's `×`. 4. Type text and send a read-only prompt; Stop.
- **Expected — visual:** Chip shows label + `×`; clicking `×` removes the chip inline (text reflows). Two same-named files render as two distinct chips.
- **Expected — behavioral:** Removed chip is excluded from the send payload (mirrors `chat-mentions.spec.ts:66-79`); two different paths with same name are both kept (dedupe is by `path||label`, `:298`).
- **✅ Checks (tick each):**
  - [ ] `×` removes the chip; surrounding text intact.
  - [ ] Removed mention is not in the sent turn.
  - [ ] Two same-named different-path files both survive to the payload.
  - [ ] Undo (Ctrl+Z) after `×` restores the chip.
- **🐞 Bug signals:** `×` removes wrong chip (same-label collision); both same-named files collapse to one; chip removal leaves orphaned `@text`.
- **Theme/size matrix:** light + dark.

## /-skill dropdown

### [IN-12] `/` opens skill dropdown; filter & select
- **Component(s):** `SkillDropdown.tsx:14-110`, `InputBar.tsx:865-868, 967-971`
- **Preconditions:** Idle. At least the bundled skills are loaded (skillsList populated).
- **Steps:** 1. Type `/`. 2. Type a couple chars of a known skill name. 3. Click a skill (or Enter).
- **Expected — visual:** Panel with `Skills` header; each row shows `/skillname` (monospace) + muted description right-aligned, sparkles icon (amber). Selected row left-accent + tint. Selecting inserts a `/skill` chip (amber).
- **Expected — behavioral:** Client-side filter on name OR description (`:25-31`). `/` after a chip still triggers because the boundary regex tolerates the U+200B filler (`RichInput.tsx:487` comment). Empty list → `No skills found.`
- **✅ Checks (tick each):**
  - [ ] `/` opens the Skills panel.
  - [ ] Filtering narrows by name and by description text.
  - [ ] Selecting inserts a `/skill` chip and clears `/query`.
  - [ ] Typing `/` immediately after an inserted chip still opens the panel.
  - [ ] `No skills found.` shown for a non-matching query.
- **🐞 Bug signals:** `/` inside a path (`foo/bar`) wrongly opens the panel (should NOT — needs boundary); panel doesn't open after a chip; description text overflows row.
- **Theme/size matrix:** light + dark; long descriptions (truncate at `max-w-[280px]`).

## #-ticket dropdown, typing & paste

### [IN-13] `#` opens ticket dropdown (loading skeleton, status badges)
- **Component(s):** `TicketDropdown.tsx:33-185` (debounce 200ms, skeleton, badges), `InputBar.tsx:861-864, 953-965`
- **Preconditions:** Jira token configured (test box has it). Idle.
- **Steps:** 1. Type `#`. 2. Wait for results. 3. Type part of a ticket key/summary. 4. Click a ticket (or Enter).
- **Expected — visual:** Panel with `Tickets` header. While loading + empty, a 3-row pulsing skeleton shows (`:94-117`). Result rows: ticket icon, monospace key (blue), summary, and a right-aligned status pill colored per status (`To Do`/`In Progress`/`In Review`/`Done`/`Closed`, `:14-20`). Selecting inserts a green **valid** ticket chip with a one-shot success glow (`RichInput.tsx:434-440`).
- **Expected — behavioral:** Ticket search hits Jira (read-only) via `_searchTickets`, debounced 200ms (`:61-64`). `No tickets found.` when empty after load.
- **✅ Checks (tick each):**
  - [ ] Skeleton pulse shows during the initial fetch.
  - [ ] Ticket key, summary, and status pill all render; pill color matches status.
  - [ ] Selecting inserts a green chip that briefly glows.
  - [ ] `No tickets found.` for a nonsense query.
- **🐞 Bug signals:** skeleton never resolves (stuck loading); status pill wrong color/missing; glow never fires; key not monospace; results from an old query persist.
- **Theme/size matrix:** light + dark; wide (long summaries truncate, `:161-164`).
- **⛔ Write note:** Ticket search is a Jira READ; no write. Do NOT proceed to any transition/comment.

### [IN-14] Typed `#KEY` auto-chips on space → pending → validated
- **Component(s):** `InputBar.tsx:869-890` (auto-chip on space), `validateTicket` `:896-938`, `RichInput.tsx:190-215` (status update)
- **Preconditions:** Jira configured. Idle. Know one real ticket key and use one fake key (e.g. `ZZZ-9999`).
- **Steps:** 1. Type a real `#PROJ-123` then press **Space**. 2. Watch the chip. 3. Repeat with a fake `#ZZZ-9999 ` (trailing space).
- **Expected — visual:** On space the typed key becomes a chip in **pending** (amber) state, then flips to **valid** (green, with tooltip `KEY: summary`) on success, OR is stripped back to raw `#KEY ` text on invalid/timeout. Real key → green; fake key → reverts to plain text (`removeChipById`, `:921`).
- **Expected — behavioral:** Regex `^[A-Za-z][A-Za-z0-9]+-\d+$` gates auto-chipping. Validation has a 5s timeout that strips the chip (`:902-908`). On valid, `updateChipStatus(...,'valid', "KEY: summary")`.
- **✅ Checks (tick each):**
  - [ ] Space after a key-shaped token creates an amber pending chip.
  - [ ] Valid key flips to green with a `KEY: summary` tooltip on hover.
  - [ ] Invalid key reverts to raw `#KEY ` text (not a stuck pending chip).
  - [ ] A non-key token (e.g. `#hello`) does not auto-chip.
- **🐞 Bug signals:** pending chip never resolves (>5s); valid chip strips itself; tooltip missing; amber/green colors indistinguishable in one theme.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** Validation is a Jira READ only.

### [IN-15] Paste `#PROJ-123` → chip; double-`#` guard
- **Component(s):** `RichInput.tsx:650-773` (handlePaste), `PASTED_TICKET_PATTERN` `:45`, `InputBar.tsx:941-945`
- **Preconditions:** Jira configured. Idle.
- **Steps:** 1. Copy `see #PROJ-123 please` to the clipboard. 2. Paste into the empty composer. 3. Separately: type `#` then paste `#PROJ-123`. 4. Send a read-only prompt; Stop.
- **Expected — visual:** Pasted text becomes plain text with the `#KEY` rendered as a pending→validated ticket chip inline (surrounding words remain). Typing `#` then pasting `#KEY` yields a single chip with NO orphaned leading `#` (no `##`).
- **Expected — behavioral:** Paste is forced plain-text (`e.preventDefault`); ticket patterns auto-chipped; a partial `#…` trigger before the caret is consumed so no double-# (`:697-704`; mirrors `chat-ticket-paste.spec.ts`). Sent payload contains `#PROJ-123` once and exactly one PROJ-123 mention.
- **✅ Checks (tick each):**
  - [ ] Pasted `#KEY` inside a sentence becomes a chip; words around it survive.
  - [ ] Type-`#`-then-paste yields one chip, payload text has no `##`.
  - [ ] Rich/HTML clipboard content pastes as plain text (no formatting injected).
  - [ ] Ticket recorded exactly once in the mentions payload.
- **🐞 Bug signals:** `##KEY` in the sent body; duplicate ticket mention; pasted HTML styling leaks into the editor; chip created but validation never runs.
- **Theme/size matrix:** light + dark.

### [IN-16] Escape cancels a typed `#ticket` (no ghost mention)
- **Component(s):** `InputBar.tsx:813-820` (handleDismiss clears prevTicketQueryRef), `:983-992` (send guard)
- **Preconditions:** Idle.
- **Steps:** 1. Type `#PROJ-7` (no space, no select). 2. Press **Esc**. 3. Type ` and more`. 4. Send (read-only); Stop.
- **Expected — visual:** Esc closes the ticket dropdown. The `#PROJ-7` stays as literal text (not a chip).
- **Expected — behavioral:** Esc resets `prevTicketQueryRef`, so on send `#PROJ-7` is NOT pushed as a ticket mention (mirrors `chat-edge-cases.spec.ts:59-69`).
- **✅ Checks (tick each):**
  - [ ] Esc closes the ticket dropdown.
  - [ ] `#PROJ-7` remains as plain text after Esc.
  - [ ] Sent payload has no PROJ-7 ticket mention (it ships as literal text only).
- **🐞 Bug signals:** dropdown stays open after Esc; a ghost ticket mention is still sent; the `#PROJ-7` text gets deleted.
- **Theme/size matrix:** light + dark.

## + (Plus) context/skill menu

### [IN-17] `+` menu opens triggers & file picker
- **Component(s):** `InputBar.tsx:576-618`
- **Preconditions:** Idle. (Image attachments may be disabled by default — see IN-21.)
- **Steps:** 1. Click **+**. 2. Note the items. 3. Click **File** (inserts `@`). 4. Re-open, click **Ticket** (inserts `#`). 5. Re-open, click **Skill** (inserts `/`). 6. Re-open, click **Attach file…**.
- **Expected — visual:** Menu (opens upward, `side="top"`) lists: **File** `@`, **Folder** `@`, **Symbol** `@`, **Ticket** `#`, **Skill** `/`, **Attach file…** (paperclip). Each shows an icon + right-aligned trigger hint. File/Folder/Symbol/Ticket/Skill insert the respective trigger char into the composer and open the matching dropdown. **Attach file…** opens the native IntelliJ FileChooser.
- **Expected — behavioral:** Trigger items call `insertTrigger` (`RichInput.tsx:242-247`). Attach calls `window._pickAttachment` (`InputBar.tsx:613`). A 250ms Radix click-through guard prevents the opening click from auto-selecting (`:489-495`).
- **✅ Checks (tick each):**
  - [ ] All six items present with correct trigger hints.
  - [ ] File/Folder/Symbol insert `@` and open the mention dropdown.
  - [ ] Ticket inserts `#` and opens the ticket dropdown.
  - [ ] Skill inserts `/` and opens the skill dropdown.
  - [ ] **Attach file…** opens the native OS/IntelliJ file chooser.
  - [ ] Menu opens upward and is not clipped by the window bottom.
- **🐞 Bug signals:** menu opens downward off-screen; item auto-selected on the opening click; Attach opens an HTML dialog instead of native chooser; trigger inserted but dropdown doesn't open.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** In the FileChooser, **Cancel** out (or pick the throwaway scratch file). `⛔ SKIP-EXECUTION (write op)` is not applicable (attaching is staging only, no backend write), but do not send a turn that asks the agent to modify the file.

## Attachments — chips, drag-drop, paste, compress, vision gate

### [IN-18] Attach a file → file chip
- **Component(s):** `InputBar.tsx:736-743` (`_addAttachmentChip`), `ChipPreview.tsx:24-45` (file chip), `AttachmentManager.ts:193-209`
- **Preconditions:** Idle. Use the throwaway scratch file (any small non-image, e.g. a `.txt`/`.pdf`).
- **Steps:** 1. **+** → **Attach file…** → pick the scratch file. 2. Observe the chip row above the editor. 3. Hover the chip. 4. Click `×`.
- **Expected — visual:** A file chip (NOT an image thumbnail): FileText icon + truncated filename, bordered, `maxWidth 180px`. Title tooltip shows `filename • N KB`. On hover an `×` button fades in; clicking removes the chip.
- **Expected — behavioral:** File chips render as text+icon (no `<img>`); dedupe by sha256 (`AttachmentManager.ts:197`). Removing revokes the URL and drops it (`:212-219`).
- **✅ Checks (tick each):**
  - [ ] File chip shows filename text + file icon (no image thumbnail).
  - [ ] Tooltip shows `filename • KB`.
  - [ ] `×` appears on hover and removes the chip.
  - [ ] Attaching the same file twice does not create a duplicate chip.
- **🐞 Bug signals:** file chip rendered as broken `<img>`; KB size wrong/`NaN`; `×` not hoverable on narrow window; duplicate chips.
- **Theme/size matrix:** light + dark; narrow (chip truncation).

### [IN-19] Image attachment → thumbnail chip
- **Component(s):** `ChipPreview.tsx:46-80`, `InputBar.tsx:679` (imageEnabled gate)
- **Preconditions:** Image input ENABLED (Settings → Tools → Workflow Orchestrator → AI Agent → Multimodal). Idle. Use a small PNG/JPEG/WebP scratch image.
- **Steps:** 1. Ensure image support is on (see IN-21 note). 2. Attach a small image via **+** → Attach file… (or drag-drop). 3. Hover the chip; click `×`.
- **Expected — visual:** 64×64 rounded thumbnail (object-fit cover) with the image rendered. `×` at top-right corner fades in on hover. Tooltip `filename • KB`.
- **Expected — behavioral:** Image chips use `<img src=thumbnailUrl>` (ObjectURL or `workflow-agent/attachments/<sha>`); file chips do not (mirrors `chat-attachments.spec.ts:35-53`).
- **✅ Checks (tick each):**
  - [ ] Image renders as a 64×64 thumbnail (not a filename row).
  - [ ] Thumbnail aspect handled by cover (no stretch).
  - [ ] `×` overlay removes the chip and frees the preview.
  - [ ] `alt`/title equals the original filename.
- **🐞 Bug signals:** thumbnail blank/broken; thumbnail stretched; `×` off the corner / unclickable; thumbnail persists after remove.
- **Theme/size matrix:** light + dark.

### [IN-20] Drag-drop overlay appears/leaves
- **Component(s):** `DropOverlay.tsx`, `InputBar.tsx:1103` (`dropActive`), bridge `_setDropActive` (`jcef-bridge.ts:1150`)
- **Preconditions:** Idle.
- **Steps:** 1. Drag a file from the OS file manager over the Agent panel (do not drop yet). 2. Observe the overlay. 3. Drag back out (or drop).
- **Expected — visual:** While an OS drag is over the panel, a dashed accent-border overlay covers the composer box with centered text `Drop files to attach` (`DropOverlay.tsx:16-18`). It disappears when the drag leaves.
- **Expected — behavioral:** The JVM `AttachmentDropTarget` pushes `_setDropActive(true/false)` because JCEF can't see CSS `:drag-over` for OS drags (mirrors `chat-attachments.spec.ts:55-68`). Dropping a valid file adds a chip via `_addAttachmentChip`.
- **✅ Checks (tick each):**
  - [ ] Overlay with dashed border + "Drop files to attach" appears on drag-over.
  - [ ] Overlay disappears on drag-out.
  - [ ] Dropping a file produces the correct chip (file vs image).
  - [ ] Overlay does not block the existing chips/typing once the drag ends.
- **🐞 Bug signals:** overlay never appears; overlay stuck after drop/leave; overlay text low-contrast in one theme; drop adds no chip or a wrong-kind chip.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** Drop only the throwaway scratch file; do not instruct the agent to modify it.

### [IN-21] Paste image, oversize compress modal, vision-model gate
- **Component(s):** `RichInput.tsx:650-672` (image paste), `AttachmentManager.ts:89-140` (validate+compress), `useCompressQueue.ts`, `CompressConfirmModal` `InputBar.tsx:1166-1244`, vision gate `:999-1024`
- **Preconditions:** Image input ENABLED. Idle. Have a screenshot in the clipboard; and a >5MB image for the oversize path.
- **Steps:** 1. Copy a screenshot (e.g. Snipping Tool). 2. Click the composer, press **Ctrl+V**. 3. For oversize: paste/attach a >5MB image and watch the modal. 4. With an image attached, ensure a NON-vision model is selected in the Model chip, type text, click Send.
- **Expected — visual:** (a) Pasted image becomes a thumbnail chip (no text pasted). (b) Oversize image raises a centered modal `Image exceeds size cap` showing `filename`, original KB, and the cap KB, with **Cancel (skip image)** and **Compress & attach** (Enter=compress, Esc=cancel; `:1177-1184`). (c) Sending an image with a non-vision model raises a warning toast `… doesn't support image input. Switch to a vision-capable model.` and does NOT send (chip stays).
- **Expected — behavioral:** Image paste runs before the text path and suppresses text (`RichInput.tsx:658-672`). Default settings: maxBytes 5MB, whitelist png/jpeg/webp, maxPerTurn 2, **enabled default false** (`InputBar.tsx:33-43`) — so on a default box paste may be declined with toast `Image input is disabled in settings.` (`AttachmentManager.ts:91-94`). Compress prompts are serialized via the queue (`useCompressQueue.ts`).
- **✅ Checks (tick each):**
  - [ ] With image support ON, paste creates an image chip and pastes no stray text.
  - [ ] With image support OFF (default), paste shows the "disabled in settings" toast and falls back to normal text paste.
  - [ ] Oversize image shows the compress modal with correct filename + KB numbers.
  - [ ] Enter confirms compress; Esc / outside-click / Cancel skips the image entirely.
  - [ ] >2 images in one turn rejected with `At most 2 image(s) per turn.`
  - [ ] Non-whitelisted type (e.g. gif) rejected with a type toast.
  - [ ] Sending an image on a non-vision model shows the vision warning and blocks send; switching to a vision model then sends.
- **🐞 Bug signals:** image paste also injects metadata/text; modal numbers wrong/`NaN`; second oversize paste hangs forever (queue bug #7); Esc on modal also dismisses the whole composer; vision toast never fires and send goes through silently.
- **Theme/size matrix:** light + dark; the modal overlay must dim the panel and center in both.
- **⛔ Write note:** Attaching/compressing is staging only; do not have the agent act on the image. Press **Cancel** on the compress modal for the skip path.

### [IN-22] Attachment-only message (no text) is sendable
- **Component(s):** `InputBar.tsx:1086-1088` (canSend with attachments), `:993-995`
- **Preconditions:** Idle. Use the throwaway scratch file as a `kind:'file'` attachment.
- **Steps:** 1. Confirm Send disabled (empty). 2. Attach the scratch file (no text typed). 3. Observe Send. 4. (Optional) send a read-only prompt and Stop — or just remove the chip.
- **Expected — visual:** With a chip present and zero text, the Send arrow becomes enabled/filled (mirrors `chat-edge-cases.spec.ts:35-44`).
- **Expected — behavioral:** `canSend` includes `attachments.length > 0`. A file attachment does not require vision (`InputBar.tsx:1005-1007`).
- **✅ Checks (tick each):**
  - [ ] Send enables with only an attachment and no text.
  - [ ] Removing the only chip re-disables Send.
- **🐞 Bug signals:** Send stays disabled with an attachment present; Send enabled but click does nothing.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** If you send, use it only to confirm dispatch, then **Stop**; the attached file is read-only context.

## Model / Plan / Skills / More chips

### [IN-23] Model chip dropdown (capacity strip, capability badges, fallback)
- **Component(s):** `ModelChip` `InputBar.tsx:146-261`, `ModelPickerRow` `:88-111`
- **Preconditions:** Idle. Models loaded (Sourcegraph token configured).
- **Steps:** 1. Click the Model chip. 2. Inspect rows. 3. Pick a different model. 4. Re-open to confirm the active row is highlighted.
- **Expected — visual:** Chip shows provider logo + model name + ChevronDown; a Brain icon when the model is a thinking model (`:212`). Dropdown rows show provider logo + name + capability badges (👁 vision 🔧 tools 🧠 reasoning ⚠ deprecated) and a capacity strip `NNNK context · NNK per-message` (`:101-108`). Active model row tinted. Empty catalog → `No models available`.
- **Expected — behavioral:** Selecting calls `window._changeModel`; 250ms click-through guard (`:244`). If the list is empty on open it re-pulls (`:182`). A fallback-active model shows an amber border + Zap icon + tooltip (`:186-219`).
- **✅ Checks (tick each):**
  - [ ] Chip shows current model name + provider logo.
  - [ ] Dropdown rows show capacity strip and capability badges correctly.
  - [ ] Selecting a model updates the chip label.
  - [ ] Active model row is visibly highlighted.
  - [ ] (If observable) fallback state shows amber border + Zap + tooltip.
- **🐞 Bug signals:** chip reads `Model` / empty; dropdown empty despite configured token; badges wrong/misaligned; capacity numbers off (e.g. `0K`); selection silently auto-fires on open.
- **Theme/size matrix:** light + dark; wide (badge strip alignment).
- **⛔ Write note:** Changing the model writes a local plugin setting, not a backend — allowed, but restore the original model after.

### [IN-24] Plan chip toggle + composer placeholder/lock changes
- **Component(s):** `PlanChip` `InputBar.tsx:265-290`, placeholder `:554-558`, disabled `:561`
- **Preconditions:** Idle.
- **Steps:** 1. Click **Plan**. 2. Observe chip state + transcript. 3. Click **Plan** again to disable.
- **Expected — visual:** When plan mode active, the Plan chip is accent-colored with a tinted background (`:279-282`); tooltip toggles `Enable/Disable plan mode`. (Plan mode itself is reflected in the agent's behavior; see plan-card sections elsewhere.)
- **Expected — behavioral:** Toggles `setInputMode` locally + `window._togglePlanMode` (`:266-271`). `planActive = inputState.mode === 'plan'` (`:1089`).
- **✅ Checks (tick each):**
  - [ ] Plan chip highlights when active and reverts when off.
  - [ ] Tooltip text reflects current state.
  - [ ] Toggling does not clear typed text in the composer.
- **🐞 Bug signals:** chip state doesn't reflect actual mode; toggle wipes input; highlight color unreadable in one theme.
- **Theme/size matrix:** light + dark.

### [IN-25] Skills chip & More (···) menus
- **Component(s):** `SkillsChip` `InputBar.tsx:294-337`, `MoreChip` `:341-379`
- **Preconditions:** Idle. Skills loaded.
- **Steps:** 1. Click **Skills** chip. 2. Click a skill to activate. 3. Click **···**. 4. Inspect the four actions.
- **Expected — visual:** Skills dropdown lists `/name` + description; selecting activates the skill (`_activateSkill`). The Skills chip is hidden entirely when no skills exist (`:305`). The **···** menu lists: New conversation, Undo last action, View traces, Settings.
- **Expected — behavioral:** Each chip has the 250ms click-through guard. More actions call `_newChat` / `_requestUndo` / `_requestViewTrace` / `_openSettings`.
- **✅ Checks (tick each):**
  - [ ] Skills chip lists available skills with descriptions; activating one works.
  - [ ] Skills chip is absent when no skills are loaded.
  - [ ] **···** menu shows all four actions; Settings opens the settings page; New conversation starts a fresh chat.
  - [ ] Menus open upward without clipping.
- **🐞 Bug signals:** Skills chip shown but empty; activating a skill no-ops; More actions wired to wrong handlers; menu clipped.
- **Theme/size matrix:** light + dark.
- **⛔ Write note:** "New conversation" discards the current draft/session — only invoke deliberately. Activating a skill is not a backend write.

## Steering & queued messages while the agent runs

### [IN-26] Steer the running agent (queue a message)
- **Component(s):** `InputBar.tsx:539-548` (steer indicator + placeholder `:556`), `ChatFooter.tsx:162-198` (queued cards), `chatStore.ts:2050-2060`
- **Preconditions:** Idle. Use a read-only prompt that runs for a few seconds.
- **Steps:** 1. Send `List all source directories` (read-only). 2. While it's working and steering is enabled, observe the composer. 3. Type `also summarize the README` and press Enter. 4. Observe the queued card. 5. Click the card's `×` (cancel). 6. Press **Stop** to end the run.
- **Expected — visual:** During busy+steering the composer shows a small banner `▲ Type to steer — message arrives after current step` and placeholder `Steer the agent...` (`InputBar.tsx:540-548, 556`). The input stays editable (not greyed). Submitting a steer adds a "Queued" card in the transcript footer (pulsing dot, the queued text, and a `×` cancel button, `ChatFooter.tsx:162-198`).
- **Expected — behavioral:** Steering is enabled by Kotlin (`setSteeringMode(true)`); input editable because `disabled = busy && !steeringMode` is false. `canSend` allows send when `steeringMode`. The `×` calls `_cancelSteering(id)`.
- **✅ Checks (tick each):**
  - [ ] Steer banner + "Steer the agent..." placeholder show while busy+steering.
  - [ ] Composer remains editable during the run (not greyed out).
  - [ ] Submitting a steer adds a Queued card with the typed text.
  - [ ] Queued card shows a pulsing indicator and a cancel `×`.
- **🐞 Bug signals:** input greyed during steering; steer banner missing; queued card never appears or shows wrong text; Send disabled despite steering; queued message duplicates.
- **Theme/size matrix:** light + dark; long queued text wraps in the card.
- **⛔ Write note:** Keep both prompts read-only; **Stop** before any tool write.

### [IN-27] Cancel a queued steer restores text to the input
- **Component(s):** `chatStore.ts` `restoredInputText`, `InputBar.tsx:1074-1083` (restore effect)
- **Preconditions:** A queued steering message exists (from IN-26), agent still busy or just finished.
- **Steps:** 1. With a queued card present, click its `×`. 2. Observe the composer.
- **Expected — visual:** The queued card disappears; its text is pushed back into the composer (caret at end, focused) so the user can edit/re-send.
- **Expected — behavioral:** Cancelling sets `restoredInputText`; the effect calls `setText` + `focus` and clears the flag (`InputBar.tsx:1076-1083`).
- **✅ Checks (tick each):**
  - [ ] Cancel removes the queued card.
  - [ ] The cancelled text reappears in the composer.
  - [ ] Composer is focused with caret at end; Send is enabled (hasText true).
- **🐞 Bug signals:** cancelled text lost; composer not focused; Send stays disabled after restore (regression #12 family); duplicated text appended to existing draft.
- **Theme/size matrix:** light + dark.

## Hint, usage indicator, compaction, delegation banner

### [IN-28] Next-step ghost hint → Right Arrow accept
- **Component(s):** `RichInput.tsx:581-594, 775-797` (hint + Right Arrow), `InputBar.tsx:466-482` (hintActive + accept)
- **Preconditions:** Complete a turn whose `attempt_completion` carries a `nextStep` (e.g. ask the agent something simple read-only and let it finish; the completion card may suggest a next step). Composer empty.
- **Steps:** 1. After a completion that yields a next-step suggestion, observe the empty composer. 2. Press **Right Arrow**. 3. Observe Send.
- **Expected — visual:** When the input is empty and a hint exists, the placeholder is replaced by italic ghost-text of the suggestion followed by a `▶` glyph (`InputBar.tsx:559`). Right Arrow promotes the hint into real editable text; Send enables.
- **Expected — behavioral:** Hint suppressed during compaction/busy-non-steering/locked (`:473`). Right Arrow on empty input calls `onAcceptHint` → `setText(hint)` (fires onChange so Send enables, fix #12) + focus (`:474-482`; mirrors `chat-edge-cases.spec.ts:46-57`).
- **✅ Checks (tick each):**
  - [ ] Ghost hint shows as italic placeholder with a `▶` when input is empty.
  - [ ] Right Arrow promotes the hint to real text.
  - [ ] Send enables immediately after accepting the hint (no extra keystroke needed).
  - [ ] Typing any character hides the hint (real input takes over).
- **🐞 Bug signals:** hint shown but Right Arrow does nothing; accepted hint leaves Send disabled (#12 regression); hint persists while busy; `▶` glyph garbled.
- **Theme/size matrix:** light + dark.

### [IN-29] Context usage indicator (numeric / percent / color)
- **Component(s):** `UsageIndicator.tsx:22-82` (poll 1s, color thresholds), bridge `getContextUsage` (`jcef-bridge.ts:1102`)
- **🔗 Cross-ref:** Pairs with **HDR-7** (sections/04 — "Below-input UsageIndicator: format, thresholds, and agreement with the meter"). **Per README §4 S3, `UsageIndicator` appears NOT mounted in the live app** (referenced only by `HarnessApp.tsx`/showcase; `App.tsx` and `InputBar.tsx` never render it) — so **first confirm whether it renders at all** before checking the rest of this scenario; if absent, that is the known S3 gap, not a fresh regression.
- **Preconditions:** Idle or mid-session.
- **Steps:** 1. Look directly below the composer for a small line `context: NNK / NNNK used (NN%)`. 2. Send a few turns and watch it update. 3. Trigger or wait for compaction and watch it refresh.
- **Expected — visual:** Small text reading e.g. `context: 23K / 132K used (17%)`. Color: grey <50%, amber 50–80%, red >80% (`:67-68`). Hover title shows exact token counts.
- **Expected — behavioral:** Polls every 1s, pauses when `document.hidden`, and refreshes immediately on a `wf-context-usage-refresh` event after compaction/handoff (`:51-64`). Falls back to `0 / 132K` when bridge missing.
- **✅ Checks (tick each):**
  - [ ] The indicator is PRESENT below the input (see ⚠ Open Questions — source shows it is only mounted in the test harness, not `App.tsx`).
  - [ ] Numeric used/max and percent update as the conversation grows.
  - [ ] Color shifts grey→amber→red across the 50%/80% thresholds.
  - [ ] After a compaction the value drops and the bar refreshes promptly (<1s).
  - [ ] Never displays `Infinity%` or `NaN`.
- **🐞 Bug signals:** indicator absent entirely (likely not wired into the live app — REPORT); value stuck/stale; percent and used/max disagree; wrong color band; `NaN`/`Infinity`.
- **Theme/size matrix:** light + dark.

### [IN-30] Compaction disables the composer
- **Component(s):** `InputBar.tsx:463` (`compacting`), placeholder `:555`, disabled `:561`, canSend `:1088`
- **Preconditions:** A session large enough to trigger manual/auto compaction (or use the Compact action if available).
- **Steps:** 1. Trigger context compaction — click the **Compact control: the chevrons / "Compact context" icon in the context-meter button group in the chat header** (same control exercised in HDR-9, sections/04 — "Manual Compact button → overlay, marker, and meter RESET"; the agent must be idle, as compact is blocked mid-run). 2. Observe the composer during the LLM-summary round-trip.
- **Expected — visual:** Placeholder becomes `Compacting context...`; the editor is disabled (dimmed, `opacity-60 cursor-not-allowed`, `RichInput.tsx:802`); Send is disabled.
- **Expected — behavioral:** `compacting = compactionState.active` disables input + send so state can't mutate mid-compaction (`InputBar.tsx:463-465, 561, 1088`).
- **✅ Checks (tick each):**
  - [ ] Placeholder reads `Compacting context...` during compaction.
  - [ ] Editor is visibly disabled and not typeable.
  - [ ] Send is disabled during compaction.
  - [ ] Input re-enables when compaction completes.
- **🐞 Bug signals:** composer still typeable during compaction; placeholder not updated; input stays disabled after compaction ends (stuck).
- **Theme/size matrix:** light + dark.

### [IN-31] Delegation question banner above the composer
- **Component(s):** `DelegationQuestionBanner.tsx`, `InputBar.tsx:1090, 1094-1096`
- **Preconditions:** A cross-IDE delegated session where IDE-B forwards a question (requires a delegation setup; otherwise verify only that the banner is absent in normal use).
- **Steps:** 1. In a delegated-question state, observe above the composer. 2. Type an answer.
- **Expected — visual:** A warning-tinted banner with an outbox icon reading `Question forwarded to {repo}. Type an answer here to short-circuit and answer it yourself.` (`DelegationQuestionBanner.tsx:45-51`).
- **Expected — behavioral:** Shown only when `delegationQuestionPending.active` (`InputBar.tsx:1094`); the repo label falls back to `the delegator` when unknown.
- **✅ Checks (tick each):**
  - [ ] Banner appears only during a pending delegated question.
  - [ ] Repo name (or `the delegator`) renders in the banner.
  - [ ] Banner does not appear in ordinary (non-delegated) sessions.
  - [ ] Typing an answer in the composer is possible while the banner shows.
- **🐞 Bug signals:** banner shown in normal sessions; banner persists after the question is answered; repo label blank; banner overlaps the composer.
- **Theme/size matrix:** light + dark.

## Open questions / unverified

- **UsageIndicator may not be wired into the live app.** Source shows `<UsageIndicator>` is imported/rendered only in `agent/webview/src/harness/HarnessApp.tsx` and `showcase.tsx`; `App.tsx:107-133` mounts `TopBar → SkillBanner → DelegationBanner → ChatView → DebugPanel → EditStatsBar → InputBar` with **no** `UsageIndicator` (and `InputBar.tsx` does not render it either). The CLAUDE.md describes it "below the chat input," so either it is mounted somewhere not found in this read, or it regressed out of the production tree. IN-29 instructs the tester to report whether it actually appears in `runIde`. **Needs runIde confirmation.**
- **`ContextChip.tsx` and `ActionToolbar.tsx` appear to be showcase-only.** Both are imported only by `showcase.tsx` (`ContextChip` at `showcase.tsx:34/181`, `ActionToolbar` at `:33/173`); neither is referenced by `InputBar.tsx`, `ChatFooter.tsx`, or `App.tsx`. The live composer renders mention chips inline inside `RichInput` and attachment chips via `ChipPreview`, and has its own action row — so these two components are likely not visible in the running plugin. No scenarios were written against them; verify they are indeed dead/showcase-only or locate their real mount point.
- **Steering availability is Kotlin-driven.** Whether the composer is editable mid-run depends on `setSteeringMode(true)` being pushed by the Kotlin side at the right loop boundary (`jcef-bridge.ts:374-377`, `:413-415`). IN-26 assumes steering is enabled during a normal busy turn; if a given build keeps `steeringMode=false` while busy, the composer will be greyed instead — note which behavior the build exhibits.
- **Image attachment default-OFF.** `IMAGE_DEFAULT_SETTINGS.enabled = false` (`InputBar.tsx:42`); IN-19/IN-21 require enabling Multimodal image input first. If the Settings bridge (`refreshImageSettings`/`__applyImageSettings`) does not push a fresh value, the static default governs until reload — verify the paperclip/drag/paste honor the live setting without a reload.
- **Next-step hint dependence.** IN-28 only triggers when a completion actually carries a `nextStep`; not all completions do. If no hint appears, the scenario can't be exercised — confirm by completing a task whose `attempt_completion` includes a suggestion.

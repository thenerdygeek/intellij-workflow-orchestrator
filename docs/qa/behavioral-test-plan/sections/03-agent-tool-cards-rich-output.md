# Agent Chat — Tool Cards & Rich Output

This section covers everything the agent chat renders for a **tool call** and for **rich
artifacts** emitted inside assistant prose, completion cards, or tool output. It is a
USER-PRIORITY area: the tester must verify that **EXPAND works**, that the **right
information is visible per tool type**, that **streaming output appears and scrolls**, and
that **counts / durations / diff-stats are correct**.

How to read this doc:

- Every scenario is grounded in source. Key files:
  - Tool cards: `agent/webview/src/components/agent/ToolCallChain.tsx`
  - Terminal (CMD body): `agent/webview/src/components/ui/tool-ui/terminal.tsx`
  - Approval previews: `agent/webview/src/components/agent/{ApprovalView,CommandPreview,EditDiffView}.tsx`
  - Streaming edit: `agent/webview/src/components/agent/StreamingEditPreviewView.tsx`
  - Bottom diff bar: `agent/webview/src/components/agent/EditStatsBar.tsx`
  - Completion / async cards: `agent/webview/src/components/agent/{CompletionCard,AsyncEventCard}.tsx`
  - Rich renderers: `agent/webview/src/components/rich/*` dispatched by code-fence language in
    `agent/webview/src/components/markdown/MarkdownRenderer.tsx:256-285`
  - Tool-call data shape: `agent/webview/src/bridge/types.ts:48-84` (`ToolCall`)
  - Streaming plumbing: `AgentController.kt:535-542` (run_command first-flush) →
    `jcef-bridge.ts:560` `appendToolOutput` → `chatStore.ts:2007` → `toolOutputStreams[id]`.

- **Tool-call grouping:** consecutive `say='TOOL'` messages are merged into ONE
  `<ToolCallChain>` (`ChatView.tsx:75-118`). Each row is a collapsible
  `ChainOfThoughtStep`. A row is collapsed by default (`defaultOpen={false}`) EXCEPT a
  RUNNING command tool, which is force-open (`ToolCallChain.tsx:354-356`).

- **Category badge** (`ToolCallChain.tsx:38-65`) is derived from the tool name:
  READ / WRITE / EDIT / CMD / SEARCH / TOOL. The category drives WHICH body renders:
  CMD → `<Terminal>`; EDIT or WRITE **with** a `diff` → `<DiffHtml>`; everything else →
  generic `<ToolCallDetails>` (input JSON + output text).

- **Setup for the whole section:** open the Workflow tool window → Agent chat, ensure a
  Sourcegraph token is configured, and drive the agent with prompts that exercise each tool.
  All write/command tools must target the throwaway **scratch file** or use **read-only
  commands** (see each scenario's ⛔ Write note).

---

## Generic tool card (all non-CMD, non-diff tools)

### [TOOL-1] Collapsed row shows badge, name, target, status, duration
- **Component(s):** `ToolCallChain.tsx` (`ToolCallItem`, `StatusIcon`, `CATEGORY_STYLES`, `extractTarget`)
- **Preconditions:** Agent chat open; ask the agent to read the scratch file (`read_file`).
- **Steps:**
  1. Send: "Read the file <scratch path> and summarise it."
  2. Watch the tool row appear, then settle after completion.
- **Expected — visual:** One compact row: a colored category badge `READ`, the mono tool name
  `read_file`, then a truncated mono target (the path, max-width ~150px, ellipsised), a green
  check status icon when done, and a right-aligned mono duration (e.g. `1.2s` / `840ms`).
- **Expected — behavioral:** Row is collapsed by default. The badge color matches the READ
  theme tokens (`--badge-read-bg/fg`). Target is the path's last 37 chars when >40 chars.
- **✅ Checks (tick each):**
  - [ ] Category badge text + color correct (`READ` blue-ish)
  - [ ] Tool name in monospace, target truncated with ellipsis (not overflowing the row)
  - [ ] Status icon: green check (COMPLETED)
  - [ ] Duration shown, right-aligned, monospace tabular
- **🐞 Bug signals:** Missing badge; target overflows / wraps the row; duration absent or
  shows `NaN`/`undefined`; badge color identical across categories.
- **Theme/size matrix:** light + dark; very long path (overflow ellipsis)
- **⛔ Write note:** read-only (`read_file` on scratch file only).

### [TOOL-2] Status icon cycles PENDING → RUNNING → COMPLETED
- **Component(s):** `ToolCallChain.tsx` `StatusIcon` (lines 121-132)
- **Preconditions:** Drive a tool that takes a moment (e.g. `search_code` over the repo).
- **Steps:**
  1. Ask: "Search the codebase for the string `ToolCallChain`."
  2. Observe the leading icon through the lifecycle.
- **Expected — visual:** PENDING = grey clock; RUNNING = spinning loader (accent color);
  COMPLETED = green check; (ERROR = red X — see TOOL-5).
- **Expected — behavioral:** The spinner animates while running; the row's trigger is marked
  active (`isActive={isRunning}`).
- **✅ Checks (tick each):**
  - [ ] Spinner visibly rotates while RUNNING
  - [ ] Transitions to green check on completion (no stuck spinner)
  - [ ] Clock icon appears for any queued/pending tool before it starts
- **🐞 Bug signals:** Spinner never resolves; icon stays grey after completion; no animation.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** read-only.

### [TOOL-3] Expand / collapse a generic card reveals Input + Output
- **Component(s):** `ToolCallChain.tsx` `ToolCallDetails` (171-280); `ChainOfThought*`
- **Preconditions:** A completed `read_file` or `search_code` row from TOOL-1/2.
- **Steps:**
  1. Click anywhere on the collapsed row (the chevron is at far right).
  2. Inspect the expanded panel.
  3. Click again to collapse.
- **Expected — visual:** Expanded panel shows an **Input** block (pretty-printed JSON of the
  args, max-height ~150px, own scroll) and an **Output** block (tool result/output text,
  max-height ~300px, own scroll). Each block has an uppercase label and a hover-revealed
  copy button.
- **Expected — behavioral:** EXPAND toggles open/closed. Re-collapse hides the panel. Input
  only renders when args parse to a non-empty object.
- **✅ Checks (tick each):**
  - [ ] Click toggles the panel open AND closed (works both directions)
  - [ ] Input shows formatted JSON of the call args
  - [ ] Output shows the tool result text
  - [ ] "Copy input" / "Copy output" buttons appear on hover and copy the right text
- **🐞 Bug signals:** Click does nothing / panel won't open; panel won't re-collapse; Input
  shows raw unparsed string; copy button copies wrong/empty content.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** read-only.

### [TOOL-4] Scroll inside the Output region (long output) + sticky-bottom
- **Component(s):** `ToolCallChain.tsx` `ToolCallDetails` scroll effects (192-273)
- **Preconditions:** A tool whose output exceeds ~300px (e.g. `search_code` with many hits, or
  `read_file` on a long file).
- **Steps:**
  1. Expand the row.
  2. Scroll within the Output box; then scroll back to the bottom.
- **Expected — visual:** Output box is height-capped (~300px) with its own vertical scrollbar;
  page does NOT scroll the whole chat when scrolling inside it.
- **Expected — behavioral:** While at/near the bottom (≤32px), new output keeps the view
  pinned to the bottom; if the user scrolls up, the view stays put (no yanking back down).
- **✅ Checks (tick each):**
  - [ ] Output region has an independent inner scrollbar (capped height)
  - [ ] Scrolling inside it does not move the outer chat
  - [ ] Horizontal scroll works for very wide lines (no clipping of content)
- **🐞 Bug signals:** No inner scroll (card grows unbounded); wide lines clipped with no
  horizontal scroll; auto-scroll fights the user when they scroll up.
- **Theme/size matrix:** light + dark + long/overflow output
- **⛔ Write note:** read-only.

### [TOOL-5] ERROR tool — red X, "Error" label, red output box
- **Component(s):** `ToolCallChain.tsx` (`isError` branches 184-273; error result box 450-458)
- **Preconditions:** Drive a tool that fails (e.g. `read_file` on a non-existent path).
- **Steps:**
  1. Ask: "Read the file /tmp/does-not-exist-xyz.kt" (read-only, path will fail validation).
  2. Inspect the row + expand it.
- **Expected — visual:** Status icon = red X. Expanded output label reads **Error** (red), and
  the output box uses the red/removed background (`--diff-rem-bg`) with red text. A separate
  red error box also appears below the panel when `status==ERROR` and a `result` string exists.
- **Expected — behavioral:** The card still expands; "Copy error" button present.
- **✅ Checks (tick each):**
  - [ ] Red X status icon
  - [ ] Output labelled "Error" in red, red-tinted output background
  - [ ] Standalone red error message box rendered below
- **🐞 Bug signals:** Error rendered as normal (no red); error text missing; card un-expandable.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** read-only (intentionally-failing read; never a real path).

### [TOOL-6] Live elapsed timer for a RUNNING non-CMD tool
- **Component(s):** `ToolCallChain.tsx` `LiveElapsedTimer` (101-117), elapsed slot (422-434)
- **Preconditions:** A tool that runs long enough to see the timer tick (e.g. `agent` spawn or
  a slow `search_code`).
- **Steps:**
  1. Trigger a longer-running non-CMD tool.
  2. Watch the right-side timer while RUNNING.
- **Expected — visual:** While RUNNING, the right edge shows a live mono accent-colored elapsed
  counter that updates ~10×/sec (e.g. `0.4s`, `1.1s`…). After completion it freezes to the
  final `durationMs`.
- **Expected — behavioral:** The non-CMD tool has NO "/ Nm Ss" timeout suffix (only run_command
  exposes that — see TOOL-11).
- **✅ Checks (tick each):**
  - [ ] Live counter increments smoothly while RUNNING
  - [ ] No `/ ...` timeout suffix on non-command tools
  - [ ] Final duration matches roughly the observed elapsed time
- **🐞 Bug signals:** Timer frozen at 0 while running; timer keeps running after completion;
  spurious `/ Xm` suffix on a non-command tool.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** read-only.

### [TOOL-7] "auto-approved · {reason}" badge
- **Component(s):** `ToolCallChain.tsx` (377-388); fields `autoApproved`/`autoApproveReason`
  (`types.ts:75-83`)
- **Preconditions:** Enable run_command auto-approval (Settings → AI Agent) OR a session prefix
  allow-list so a command runs without an approval prompt; alternatively a tool covered by the
  "auto-approve safe commands" setting.
- **Steps:**
  1. Approve a `run_command` prefix once "for this session" (e.g. `git status`).
  2. Run the same prefixed command again so it auto-approves.
- **Expected — visual:** A small low-contrast pill reads `auto-approved · git status` (or
  `auto-approved` alone if no reason), placed after the target, with a tooltip
  `Auto-approved: {reason}`.
- **Expected — behavioral:** Badge only appears when the call actually skipped the approval gate.
- **✅ Checks (tick each):**
  - [ ] Badge text matches the reason
  - [ ] Tooltip on hover shows "Auto-approved: …"
  - [ ] Badge absent for normally-approved calls
- **🐞 Bug signals:** Badge on a manually-approved call; missing reason when one exists; badge
  overlaps the target text.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** run_command must be a read-only command (e.g. `git status`, `ls`).

### [TOOL-8] Universal Stop + Move-to-background on a RUNNING non-suppressed tool
- **Component(s):** `ToolCallChain.tsx` (`STOP_SUPPRESSED_TOOLS` 32; Stop 395-406; bg 407-418)
- **Preconditions:** A RUNNING tool that is NOT run_command / background_process /
  ask_user_input / agent (e.g. a long `search_code` or `find_references`).
- **Steps:**
  1. Trigger a long-running search.
  2. While RUNNING, hover the row and locate the two right-side icon buttons.
  3. Click the square Stop button.
- **Expected — visual:** While RUNNING, a red square **Stop** button and a grey
  **Move to background** (down-arrow) button appear at the right of the row, before the
  elapsed timer.
- **Expected — behavioral:** Stop cancels just that tool and feeds `[Stopped by user]` to the
  loop (the agent continues). Move-to-background detaches it. Buttons disappear once the tool
  is no longer RUNNING. These buttons are SUPPRESSED for run_command, background_process,
  ask_user_input, agent.
- **✅ Checks (tick each):**
  - [ ] Stop + Move-to-background buttons present while RUNNING
  - [ ] Stop ends the tool; the agent loop continues (not a full session abort)
  - [ ] Buttons absent on run_command rows (it has its own in-terminal Stop)
- **🐞 Bug signals:** Stop kills the whole session; buttons appear on suppressed tools; buttons
  linger after completion; clicking Stop also toggles the card open.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** read-only tool only.

### [TOOL-9] Hover affordances — copy-summary button + duration hides under it
- **Component(s):** `ToolCallChain.tsx` (headline copy 465-470; elapsed `group-hover:invisible` 422)
- **Preconditions:** Any completed tool row.
- **Steps:**
  1. Hover over a completed row.
  2. Observe the right side near the chevron.
- **Expected — visual:** On hover a "Copy tool call summary" button appears (positioned left of
  the chevron). The duration text becomes invisible while hovering (so it doesn't sit under the
  copy button). Moving the mouse away restores the duration.
- **Expected — behavioral:** Clicking the copy button copies `tool_name(target)` and does NOT
  toggle the card (stopPropagation).
- **✅ Checks (tick each):**
  - [ ] Copy-summary button appears on hover, copies `name(target)`
  - [ ] Duration hides on hover, reappears on mouse-out (no visual overlap)
  - [ ] Clicking copy does not expand/collapse the card
- **🐞 Bug signals:** Copy button overlaps the chevron; copy toggles the card; duration and
  copy button render on top of each other.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** read-only.

### [TOOL-10] Reverted tool — "reverted" badge, dimmed + strikethrough
- **Component(s):** `ToolCallChain.tsx` (`isRolledBack` 332, 341-352, 371-373)
- **Preconditions:** An `edit_file`/`create_file` on the scratch file that you then revert
  (via EditStatsBar per-file ⟲, see TOOL-21, or `revert_file`).
- **Steps:**
  1. Have the agent edit the scratch file.
  2. Revert that change.
  3. Inspect the original tool row.
- **Expected — visual:** The reverted row is dimmed to ~40% opacity, the tool name + target are
  struck through, and a small amber `reverted` badge sits at the top-right corner.
- **Expected — behavioral:** Badge derives from `ToolCall.rolledBack === true`.
- **✅ Checks (tick each):**
  - [ ] Row visibly dimmed
  - [ ] Name + target strikethrough
  - [ ] Amber "reverted" corner badge present
- **🐞 Bug signals:** No dimming/strikethrough after revert; badge missing; badge on a
  non-reverted row.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** edits/reverts target the scratch file ONLY.

### [TOOL-11] imageRefs "N images attached" badge (VERIFY — may be unrendered)
- **Component(s):** `types.ts:60-65` (doc says tool-result row renders the badge);
  `ChatView.tsx:95,113` (imageRefs threaded into ToolCall); `chatStore.ts:1183`
- **Preconditions:** A tool that returns image attachments — `jira.download_attachment` on an
  image MIME (requires Jira configured) with `enableToolImageAutoload` ON.
- **Steps:**
  1. Configure Jira; ask the agent to download an image attachment from a ticket.
  2. Inspect the completed tool-result row.
- **Expected — visual (per the type doc):** A small "N images attached from tool" badge below
  the text output.
- **Expected — behavioral:** Image bytes never travel the bridge — only metadata
  (sha256/mime/size) drives the badge.
- **✅ Checks (tick each):**
  - [ ] Badge text shows the correct image count
  - [ ] Badge appears below the tool output, not over it
  - [ ] No raw base64 / broken-image markup
- **🐞 Bug signals:** **Source review found NO component that renders this badge** — `imageRefs`
  is plumbed into `ToolCall`/`chatStore`/`ChatView` but `ToolCallChain`/`ToolCallDetails`
  never read `tc.imageRefs`. Strongly flag if the documented badge does not appear. (See Open
  questions.)
- **Theme/size matrix:** light + dark
- **⛔ Write note:** read-only (download into the per-session attachments dir).

---

## run_command — terminal card & live streaming

### [TOOL-12] Live stdout streams line-by-line into the terminal body
- **Component(s):** `ToolCallChain.tsx` `TerminalContent` (284-321); `terminal.tsx`;
  stream path `AgentController.kt:535-542` → `appendToolOutput` → `toolOutputStreams[id]`
- **Preconditions:** Approve a read-only command that emits output over time.
- **Steps:**
  1. Ask the agent to run `for i in 1 2 3 4 5; do echo line $i; sleep 1; done` (read-only).
  2. Watch the terminal body as it runs.
- **Expected — visual:** Lines append incrementally (roughly one per second) into the terminal
  output area while the spinner/running state is active — not all-at-once at the end.
- **Expected — behavioral:** The store key `toolOutputStreams[toolCallId]` accumulates chunks;
  the first flush is logged (`run_command[id]: first flush to UI`). On completion, the final
  full `output` replaces the live stream.
- **✅ Checks (tick each):**
  - [ ] Output appears progressively while RUNNING (not just on completion)
  - [ ] Final output is complete and consistent after the command exits
  - [ ] Command label in the header is the actual command string
- **🐞 Bug signals:** Terminal stays empty until the command finishes; lines arrive in the wrong
  order; output duplicated or truncated mid-stream.
- **Theme/size matrix:** light + dark + long/overflow output
- **⛔ Write note:** run_command MUST be read-only (echo/sleep loop, `ls`, `git status`).

### [TOOL-13] run_command auto-expands while running, collapsible after
- **Component(s):** `ToolCallChain.tsx` (`forceOpen={isRunning && isCmdTool}` 354-356)
- **Preconditions:** Same echo/sleep command.
- **Steps:**
  1. Run the streaming command.
  2. Observe the card open/closed state during and after.
- **Expected — visual:** The CMD card is forced OPEN while running (you see the live terminal).
  After completion it becomes a normal collapsible row.
- **Expected — behavioral:** After completion, clicking the row collapses/expands it.
- **✅ Checks (tick each):**
  - [ ] Card is open automatically while the command runs
  - [ ] Card can be collapsed AND re-expanded after completion
- **🐞 Bug signals:** Card stays collapsed while running (live output hidden); card cannot be
  collapsed afterward.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** read-only command.

### [TOOL-14] Live elapsed + timeout cap "/ Nm Ss"
- **Component(s):** `ToolCallChain.tsx` (`extractRunCommandTimeoutLabel` 91-97; LiveElapsedTimer 101-117);
  `toolTimeoutSeconds` (`types.ts:66-74`, computed by `RunCommandTool.resolveTimeoutSeconds`)
- **Preconditions:** A run_command that runs for several seconds.
- **Steps:**
  1. Run a `sleep 8 && echo done` command (read-only).
  2. Watch the right-side timer while it runs.
  3. After completion, hover the frozen duration.
- **Expected — visual:** While RUNNING the timer reads `elapsed / cap`, e.g. `0:03 / 10m 0s`
  (the cap from `toolTimeoutSeconds`). After completion, the duration is plain, and hovering it
  shows a `Limit: 10m 0s` tooltip.
- **Expected — behavioral:** The cap matches the actual per-call timeout the in-tool monitor
  enforces (default run_command 600s unless configured). Non-command tools never show this.
- **✅ Checks (tick each):**
  - [ ] Running indicator shows `elapsed / cap` form
  - [ ] Cap value is plausible (matches run_command timeout)
  - [ ] Hover tooltip on the final duration shows the limit
- **🐞 Bug signals:** No cap suffix on run_command; cap is `0`/`NaN`/`Infinity`; cap suffix
  appears on non-command tools.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** read-only `sleep`/`echo`.

### [TOOL-15] ANSI color rendering in command output
- **Component(s):** `terminal.tsx` (`ansiUp`, `hasAnsi`, `displayHtml` 83-93); also `AnsiOutput.tsx`
- **Preconditions:** A command that emits ANSI color codes.
- **Steps:**
  1. Run `printf '\033[31mRED\033[0m \033[32mGREEN\033[0m\n'` (read-only) OR `git -c color.ui=always status`.
  2. Inspect the rendered colors.
- **Expected — visual:** ANSI escapes render as actual colored spans (RED red, GREEN green) —
  not as literal `\033[31m` text. Plain (non-ANSI) output gets heuristic token highlighting
  instead.
- **Expected — behavioral:** The Copy button copies the **ANSI-stripped** plain text (no escape
  codes), per `stripAnsi(output)`.
- **✅ Checks (tick each):**
  - [ ] Colors render (no raw escape sequences visible)
  - [ ] Copy yields clean text with no `\033[` codes
  - [ ] Colors are legible in BOTH light and dark themes
- **🐞 Bug signals:** Literal escape codes shown; colors unreadable on one theme; copy includes
  escape sequences.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** read-only color-emitting command.

### [TOOL-16] Long output collapses to last N lines + "Show all N lines"
- **Component(s):** `terminal.tsx` (`maxCollapsedLines=10`, `needsCollapse`, `displayOutput` 62-67; toggle 172-187)
- **Preconditions:** A command producing >10 output lines.
- **Steps:**
  1. Run `seq 1 60` (read-only) so 60 lines are produced.
  2. After completion, observe the collapsed terminal, then click "Show all 60 lines".
- **Expected — visual:** Collapsed, the terminal shows only the **last 10** lines (most recent).
  A centered "Show all 60 lines" toggle is shown; clicking expands to the full output (height
  cap rises to ~300px) and the toggle reads "Show less".
- **Expected — behavioral:** The line count in the toggle equals the real total. Toggle works
  both ways.
- **✅ Checks (tick each):**
  - [ ] Collapsed shows the LAST 10 lines (tail), not the first
  - [ ] "Show all N lines" count is correct; toggles to "Show less"
  - [ ] Expanded view scrolls within its own ~300px region
- **🐞 Bug signals:** Collapsed shows the head instead of the tail; wrong line count; toggle
  one-way only; expanded output unbounded.
- **Theme/size matrix:** light + dark + long output
- **⛔ Write note:** read-only `seq`/`yes | head`.

### [TOOL-17] Running tail-bound (very large live output) — "… N earlier lines (live view)"
- **Component(s):** `terminal.tsx` (`RUNNING_HIGHLIGHT_TAIL=400`, `hiddenLineCount` 72-81, 155-163)
- **Preconditions:** A command emitting >400 lines while RUNNING.
- **Steps:**
  1. Run `for i in $(seq 1 600); do echo row $i; done` (read-only; fast).
  2. While it streams + the card is expanded, watch the top of the output region.
- **Expected — visual:** While RUNNING + expanded with >400 lines, a small italic header reads
  "… N earlier lines (live view)" and only the last 400 lines are syntax/ANSI-highlighted (perf
  bound). Once finished, the full output is available via the collapse toggle.
- **Expected — behavioral:** This is a perf guard — no content is lost, just not highlighted
  live above the tail window.
- **✅ Checks (tick each):**
  - [ ] "… N earlier lines (live view)" header appears for very large live output
  - [ ] No UI freeze/jank while the large output streams
  - [ ] Full output reachable after completion
- **🐞 Bug signals:** Beachball / dropped frames during large streaming output; header missing
  and the whole buffer is re-highlighted each chunk (lag).
- **Theme/size matrix:** dark (default); light
- **⛔ Write note:** read-only line generator.

### [TOOL-18] Exit code badge, duration, red border on non-zero exit
- **Component(s):** `terminal.tsx` (`exitCode`, `isError`, header 118-133, border 107)
- **Preconditions:** One success command and one failing command.
- **Steps:**
  1. Run `true` (read-only) — expect exit 0.
  2. Run `false` (read-only) — expect exit 1.
- **Expected — visual:** Success: green `exit 0` badge, normal border. Failure: red `exit 1`
  badge, the whole terminal gets a red border, copy still works. A duration (`ms`/`s`) shows in
  the header.
- **Expected — behavioral:** `exitCode` is `0` for COMPLETED, `1` for ERROR (from
  `TerminalContent`); RUNNING shows no exit code.
- **✅ Checks (tick each):**
  - [ ] `exit 0` green on success; `exit N` red on failure
  - [ ] Red border around the failing terminal card
  - [ ] Duration shown in header (`< 1000ms → "NNNms"`, else `"N.Ns"`)
- **🐞 Bug signals:** Exit badge always 0; no red styling on failure; duration missing/wrong.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** read-only `true`/`false`.

### [TOOL-19] In-terminal Stop (kill) for a running command
- **Component(s):** `terminal.tsx` (`onKill`, Stop button 139-149); `TerminalContent.handleKill` (291-293)
- **Preconditions:** A long-running read-only command.
- **Steps:**
  1. Run `sleep 60` (read-only).
  2. Click the square Stop button in the terminal header.
- **Expected — visual:** While RUNNING the terminal header shows a red square Stop button (the
  universal row Stop is suppressed for run_command — this in-terminal one is the only Stop).
- **Expected — behavioral:** Click kills the process, captures partial output, and the loop
  continues with `[Stopped by user]`.
- **✅ Checks (tick each):**
  - [ ] Stop button present in the terminal header while RUNNING
  - [ ] No duplicate Stop at the row level (suppressed for run_command)
  - [ ] Process terminates promptly; partial output retained
- **🐞 Bug signals:** Two Stop buttons; Stop doesn't kill the process; whole session aborts.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** read-only `sleep`.

### [TOOL-20] "No output" empty state + custom command labels
- **Component(s):** `terminal.tsx` (empty state 192-196); `TerminalContent` label (296-306)
- **Preconditions:** A command with no stdout; and a `run_tests`/`compile_module` call.
- **Steps:**
  1. Run `true` (read-only; no output).
  2. (Java project) ask to run a specific test class via `run_tests`.
- **Expected — visual:** Empty-output terminal shows italic "No output". For `run_tests` the
  header label reads `run_tests <ClassName>#<method>`; for `compile_module` it reads
  `compile <module>` (not raw JSON args).
- **Expected — behavioral:** Label derived from parsed args.
- **✅ Checks (tick each):**
  - [ ] "No output" shown when stdout is empty
  - [ ] run_tests / compile_module show friendly labels, not JSON
- **🐞 Bug signals:** Blank terminal with no "No output" note; raw `{"command":...}` shown as label.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** read-only `true`; run_tests only if a Java test project is loaded.

---

## edit_file / create_file — diff preview, stats, revert

### [TOOL-21] edit_file unified diff renders via DiffHtml (side-by-side, word-level)
- **Component(s):** `ToolCallChain.tsx` (`hasDiff`, DiffHtml branch 331, 440-445); `DiffHtml.tsx`
- **Preconditions:** Ask the agent to make a small edit to the scratch file.
- **Steps:**
  1. Ask: "In <scratch file> change the greeting text to 'hello world'."
  2. Approve, then expand the completed `edit_file` row.
- **Expected — visual:** EDIT badge (purple). Expanded body shows a diff: first a raw colored
  diff appears instantly, then it upgrades to diff2html **side-by-side** with word-level
  highlights. A clickable filename header (`path:startLine`) sits on top. Theme colors are
  applied (no bright-white diff2html default background).
- **Expected — behavioral:** Clicking the filename navigates to that file:line in the IDE.
  Diff area is height-capped (~400px) with its own scroll.
- **✅ Checks (tick each):**
  - [ ] Diff shows added (green) and removed (red) lines correctly
  - [ ] Side-by-side layout with word-level intra-line highlighting
  - [ ] Filename header is clickable and opens the file at the right line
  - [ ] Diff respects the IDE theme (no jarring white block in dark mode)
- **🐞 Bug signals:** Diff never upgrades past the raw fallback; light-themed diff2html block in
  dark mode; filename not clickable; diff overflows the card (no inner scroll).
- **Theme/size matrix:** light + dark + long diff (scroll)
- **⛔ Write note:** edit_file targets the SCRATCH FILE ONLY.

### [TOOL-22] DiffHtml falls back to raw diff on load timeout
- **Component(s):** `DiffHtml.tsx` (`withTimeout` 13-20, `loadDiff2Html` 5s 22-43, raw fallback 363-403)
- **Preconditions:** Any edit_file diff. (Timeout is hard to force; verify the immediate raw
  render and the error path instead.)
- **Steps:**
  1. Expand an edit_file diff and observe the very first frame.
  2. (If diff2html fails to load) confirm the permanent raw fallback.
- **Expected — visual:** A colored raw diff (`+`/`-`/`@@` lines) is shown immediately, before
  diff2html resolves. If diff2html errors or times out (5s), the raw diff stays permanently
  (still readable, with the clickable filename header).
- **Expected — behavioral:** No infinite skeleton/spinner — there is always a readable diff.
- **✅ Checks (tick each):**
  - [ ] A readable raw diff is visible from the first frame (no blank/skeleton-only state)
  - [ ] If diff2html never loads, the raw diff remains (no perpetual loader)
- **🐞 Bug signals:** Blank diff region / endless spinner; diff disappears if diff2html fails.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** scratch file only.

### [TOOL-23] create_file — VERIFY whether a WRITE diff renders (categorization gap)
- **Component(s):** `ToolCallChain.tsx` (`CATEGORY_MAP` 38-51 — note `create_file` is ABSENT;
  `hasDiff` requires category EDIT/WRITE, 331)
- **Preconditions:** Ask the agent to create a NEW scratch file.
- **Steps:**
  1. Ask: "Create a new file <scratch dir>/qa-scratch-new.txt with two lines of content."
  2. Approve, then expand the completed `create_file` row.
- **Expected — visual (current source behavior):** Because `create_file` is NOT in
  `CATEGORY_MAP`, it falls to the **TOOL** category. Its badge will read `TOOL` (grey), and the
  body renders via the generic `ToolCallDetails` (input JSON + output text) — **not** the
  DiffHtml diff used for edit_file (since `hasDiff` only fires for EDIT/WRITE).
- **Expected — behavioral:** The new file's content is shown as output text, not a green
  added-lines diff.
- **✅ Checks (tick each):**
  - [ ] Note the badge actually shown for create_file (expected `TOOL`, not `WRITE`/`EDIT`)
  - [ ] Note whether a green +lines diff renders OR just plain input/output text
  - [ ] File is actually created at the scratch path
- **🐞 Bug signals:** This is a likely **inconsistency to confirm**: create_file should arguably
  show a WRITE badge + diff like edit_file but currently does not. Flag if the QA owner expects
  parity with edit_file. (`delete_file` is similarly absent → TOOL category.)
- **Theme/size matrix:** light + dark
- **⛔ Write note:** create the file in the SCRATCH directory ONLY.

### [TOOL-24] +/- line stats on the diff header
- **Component(s):** `DiffHtml.tsx` (diff2html stats); `EditDiffView.tsx` (showcase variant computes
  `-removeCount / +addCount`, 82-124)
- **Preconditions:** An edit_file change that adds and removes lines.
- **Steps:**
  1. Make an edit that removes 1 line and adds 2.
  2. Inspect the diff header / stats.
- **Expected — visual:** Added lines green, removed lines red; counts reflect the actual change.
  (In the live chat the diff is DiffHtml; the standalone `EditDiffView` with a `-N / +N` header
  + Applied/Rejected/Pending status pill is currently only wired in the dev showcase — see
  Open questions.)
- **Expected — behavioral:** Counts match the real number of +/- lines.
- **✅ Checks (tick each):**
  - [ ] Added/removed line counts are correct
  - [ ] Green = additions, red = removals
- **🐞 Bug signals:** Counts inverted or wrong; colors swapped.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** scratch file only.

### [TOOL-25] Streaming edit preview — live diff grows during generation
- **Component(s):** `StreamingEditPreviewView.tsx`; `chatStore.streamingEdits`; gated by
  `enableStreamingEditPreview` (default ON)
- **Preconditions:** Streaming Edit Preview enabled (Settings → AI Agent ▸ Advanced ▸ Tool
  Calling). Ask for a **sizeable edit (≥10 changed lines across ≥1 file)** so the `<new_string>`
  streams visibly.
- **Steps:**
  1. Ask the agent to rewrite a block in the scratch file — a **sizeable edit of ≥10 changed lines**
     so the live diff has enough content to visibly grow.
  2. Watch the chat WHILE the LLM is still emitting the edit (before the approval card).
- **Expected — visual:** A card labelled "streaming edit" with the file path, a pulsing accent
  "live" dot, and a `<DiffHtml>` diff that grows as `<new_string>` streams in. When the tool
  call closes, the streaming card disappears (replaced by the static approval/result diff —
  same DiffHtml, byte-identical).
- **Expected — behavioral:** The real file is never touched during streaming. Updates throttle
  to ~100ms. On cancel/new-chat the preview clears.
- **✅ Checks (tick each):**
  - [ ] Live diff appears and grows during generation
  - [ ] "live" pulse shows while `status==='streaming'`, gone when finalized
  - [ ] No duplicate diff cards (streaming card gone once approval card shows)
  - [ ] Cancelling the run clears the preview cleanly
- **🐞 Bug signals:** Two diff cards rendering the same change at once; preview persists after
  completion; preview shows for a tool other than edit_file; the file changes mid-stream.
- **Theme/size matrix:** light + dark + long diff
- **⛔ Write note:** scratch file only; if disabled, this scenario is N/A (note it).

### [TOOL-26] run_command approval preview (CommandPreview chips)
- **Component(s):** `ApprovalView.tsx` (53); `CommandPreview.tsx`
- **Preconditions:** A run_command that requires approval (auto-approve OFF).
- **Steps:**
  1. Ask the agent to run a read-only command requiring approval (e.g. `git log -1`).
  2. Inspect the approval card BEFORE approving.
- **Expected — visual:** A bordered preview with the formatted command (bash CodeBlock) and a
  row of chips: the resolved **shell**, the **cwd**, optional `separate-stderr`, and each
  `KEY=value` env chip.
- **Expected — behavioral:** Command preview only renders for `run_command`; edit/create show
  a DiffHtml preview instead (`!isRunCommand && diffContent`).
- **✅ Checks (tick each):**
  - [ ] Command shown formatted (not raw-escaped)
  - [ ] Shell + cwd chips present and plausible
  - [ ] env chips render `KEY=value` for any provided env vars
- **🐞 Bug signals:** No chips; cwd blank; command preview shown for a non-command tool.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** read-only command; do not approve anything that writes.

### [TOOL-27] Bottom diff bar (EditStatsBar) — aggregate +/- , files, expand, revert
- **Component(s):** `EditStatsBar.tsx`; `types.ts AggregateDiff` (290-294)
- **Preconditions:** At least one edit to the scratch file in this session.
- **Steps:**
  1. Make 1–2 edits to the scratch file.
  2. Find the bottom bar; click the `▾` expand caret; click a file path; click a per-file `⟲`.
  3. Click "⟲ Revert all" and observe the inline confirm.
- **Expected — visual:** A sticky bottom bar shows `+TotalAdded −TotalRemoved`, a divider, and
  `K files`. Expand caret reveals per-file rows (path + `+N −M` + `⟲`). "⟲ Revert all" swaps to
  an inline "Revert all changes? Confirm / Cancel" (NOT a native dialog — JCEF can't
  `window.confirm`), auto-dismissing after ~4s.
- **Expected — behavioral:** Clicking a file path opens it in the IDE
  (`kotlinBridge.navigateToFile`). Per-file `⟲` reverts just that file. Bar is hidden entirely
  when there are zero changes.
- **✅ Checks (tick each):**
  - [ ] Aggregate +/- totals and file count correct
  - [ ] Expand shows per-file rows with correct individual +/- counts
  - [ ] File path click opens the file; per-file `⟲` reverts only that file
  - [ ] "Revert all" uses inline two-step confirm (no native dialog), auto-dismisses
- **🐞 Bug signals:** Bar shows for zero changes; totals don't match per-file sums; native
  `window.confirm` (would silently no-op in JCEF); revert-all fires without confirm.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** all edits/reverts on the SCRATCH FILE ONLY.

---

## read_file / list_files / search — what the card surfaces

### [TOOL-28] read_file card — path target + syntax-highlighted content
- **Component(s):** `ToolCallChain.tsx` (`detectLanguage` 147-164, Shiki highlight 178-184)
- **Preconditions:** Read a Kotlin/TS file from the project (read-only).
- **Steps:**
  1. Ask: "Read <a .kt file in the repo>."
  2. Expand the row.
- **Expected — visual:** READ badge; target = the path. Output is syntax-highlighted by Shiki
  for recognized extensions (`.kt → kotlin`, `.ts → typescript`, etc.) when ≤50K chars;
  larger/unknown falls back to plain monospace with wrapping.
- **Expected — behavioral:** Language detection is from the file extension in the args.
- **✅ Checks (tick each):**
  - [ ] Path is the target; READ badge
  - [ ] File content syntax-highlighted (colored tokens) for a known extension
  - [ ] Very large file falls back to plain text (no freeze)
- **🐞 Bug signals:** No highlighting on a known type; highlighting attempted on a huge file
  causing lag; content blank.
- **Theme/size matrix:** light + dark + large file
- **⛔ Write note:** read-only.

### [TOOL-29] search_code / Grep card — SEARCH badge, query target, match output
- **Component(s):** `ToolCallChain.tsx` (`CATEGORY_MAP` search 48; `extractTarget` `query`/`pattern` 70)
- **Preconditions:** Search for a token across the repo.
- **Steps:**
  1. Ask: "Search the codebase for `RichBlock`."
  2. Inspect collapsed row + expanded output.
- **Expected — visual:** SEARCH badge (cyan); target = the query/pattern. Expanded output lists
  matches (paths + lines / match counts as returned by the tool).
- **Expected — behavioral:** Target picks up `query` or `pattern` from args.
- **✅ Checks (tick each):**
  - [ ] SEARCH badge + query as target
  - [ ] Match list / counts visible in expanded output, scrollable if long
- **🐞 Bug signals:** Wrong badge; target empty; output truncated with no scroll.
- **Theme/size matrix:** light + dark + many matches (scroll)
- **⛔ Write note:** read-only.

### [TOOL-30] glob_files / list — READ badge, glob target, file list
- **Component(s):** `ToolCallChain.tsx` (`glob_files: 'READ'` 39; `extractTarget` `glob`/`path` 70)
- **Preconditions:** Glob for files.
- **Steps:**
  1. Ask: "List all `*.tsx` files under agent/webview/src/components/rich."
  2. Inspect the row.
- **Expected — visual:** READ badge; target = the glob/path; expanded output is the file list.
- **✅ Checks (tick each):**
  - [ ] READ badge + glob/path as target
  - [ ] File list visible in expanded output
- **🐞 Bug signals:** Target empty; list missing.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** read-only.

---

## Rich artifacts — tables, charts, diagrams, images, etc.

> These render when assistant prose / completion text / tool output contains a fenced code block
> with a recognized language (`MarkdownRenderer.tsx:256-285`): ```table, ```chart, ```mermaid,
> ```flow, ```math, ```diff/```patch, ```ansi, ```output, ```progress, ```timeline, ```image,
> ```react/```artifact, ```html-interactive/```visualization/```viz. Drive the agent to emit
> these (e.g. "render the data as a ```table block"), or use `render_artifact` for artifacts.
> All share the `RichBlock` chrome (TOOL-41).

### [TOOL-31] DataTable — sort (asc/desc/off), search filter, scroll, counts
- **Component(s):** `DataTable.tsx`
- **Preconditions:** Get the agent to emit a ```table fenced block with several rows/columns.
- **Steps:**
  1. Click a column header three times (asc → desc → unsorted).
  2. Type in the Search box; clear it.
  3. Scroll horizontally if wide.
- **Expected — visual:** Header shows a sort indicator (up/down/neutral). Search box filters
  rows case-insensitively and shows "X of N". Zebra row striping. "No matching rows" when the
  filter excludes everything.
- **Expected — behavioral:** Numeric columns sort numerically; text columns alphabetically.
  Third click clears sorting.
- **✅ Checks (tick each):**
  - [ ] Sort toggles asc → desc → off, indicator updates
  - [ ] Numeric vs text sort behave correctly
  - [ ] Search filters rows + shows "X of N"; empty result shows "No matching rows"
  - [ ] Wide table scrolls horizontally; invalid JSON shows "Failed to parse table data"
- **🐞 Bug signals:** Sort indicator doesn't change; numbers sort as strings (10 before 2);
  search doesn't filter; table overflows with no scroll.
- **Theme/size matrix:** light + dark + wide/long table
- **⛔ Write note:** N/A (LLM-emitted markdown; no tool write).

### [TOOL-32] ChartView — render, loading placeholder, theme colors, retry, incremental update
- **Component(s):** `ChartView.tsx`; `chartUtils.ts` (`updateChart` global, deep-merge)
- **Preconditions:** Agent emits a ```chart block (Chart.js JSON, e.g. a bar chart).
- **Steps:**
  1. Observe the chart render (a "Loading {type} chart…" placeholder shows first).
  2. Toggle IDE theme and confirm axis/legend colors update.
  3. (If a chart `id` + incremental `action:"update"` is emitted) confirm it updates in place.
- **Expected — visual:** Animated chart (easeOutQuart). Axis ticks, grid, legend, and title use
  theme-derived colors (`fg`/`fg-muted`/grid). On invalid JSON, an error state with Retry.
- **Expected — behavioral:** Canvas stays in the DOM (opacity fades in). ResizeObserver keeps it
  responsive. Incremental updates deep-merge into the existing chart without re-creating it.
- **✅ Checks (tick each):**
  - [ ] Chart renders with animation; loading placeholder shown first
  - [ ] Legend/axis colors legible in BOTH themes (re-themes on toggle)
  - [ ] Invalid chart JSON → error state with working Retry
  - [ ] Resizing the panel resizes the chart
- **🐞 Bug signals:** Permanent loading placeholder; black-on-black legend in dark mode; chart
  doesn't resize; Retry does nothing.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** N/A (LLM-emitted).

### [TOOL-33] MermaidDiagram — render, zoom/pan, sequence animation + PlayControls, error/retry
- **Component(s):** `MermaidDiagram.tsx`; `PlayControls.tsx`
- **Preconditions:** Agent emits a ```mermaid block (a flowchart and, separately, a
  `sequenceDiagram`).
- **Steps:**
  1. Scroll-wheel over the diagram to zoom; drag to pan; double-click to reset.
  2. For a sequence diagram, use the play/step controls.
  3. Emit a deliberately invalid mermaid block, then click Retry. **Concrete repro prompt to paste:**
     ask the agent to *"render this exact mermaid block, verbatim and unmodified:*
     ` ```mermaid ` / `graph TD; A--;` / ` ``` ` *(it is intentionally malformed — do not fix it)"* so the
     parse-error path fires deterministically.
- **Expected — visual:** SVG renders. Wheel zooms (0.25×–4×), a `%` indicator shows when ≠100%.
  Sequence diagrams reveal messages step-by-step with Play/Prev/Next/Reset controls and a
  `step / total` counter. Invalid syntax → error state with Retry.
- **Expected — behavioral:** Strict security level (no script execution). Zoom/pan is local to
  the block (doesn't scroll the chat).
- **✅ Checks (tick each):**
  - [ ] Diagram renders; wheel zoom + drag pan + double-click reset work
  - [ ] Sequence diagram animates message-by-message via PlayControls; counter correct
  - [ ] Invalid mermaid → error + Retry (Retry re-renders)
- **🐞 Bug signals:** Diagram never renders; zoom scrolls the whole chat; play controls
  inert; error has no recovery.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** N/A (LLM-emitted).

### [TOOL-34] FlowDiagram — dagre layout, animated path + PlayControls, zoom/pan, error
- **Component(s):** `FlowDiagram.tsx`; `PlayControls.tsx`
- **Preconditions:** Agent emits a ```flow block (nodes/edges JSON; include `animated:true` and
  optionally `highlightPath`).
- **Steps:**
  1. Confirm the dagre-laid-out node/edge graph.
  2. For an animated flow, step through with PlayControls; watch the traveling dot + node sweep
     + the per-step caption.
  3. Hover a node with a `tooltip`.
  4. Emit a flow missing `nodes`/`edges`; confirm the error message.
- **Expected — visual:** Boxed nodes with arrowed edges, optional dashed group boxes. Animated
  flows show a traveling dot along the active edge, a glow sweep on the active node, dimmed
  unvisited nodes, and a caption. Zoom `%` indicator when ≠100%.
- **Expected — behavioral:** Errors: `FlowConfig must have a "nodes"/"edges" array`.
- **✅ Checks (tick each):**
  - [ ] Auto-layout looks sane (no overlapping nodes); edges have arrowheads
  - [ ] Animation steps highlight node+edge; caption + counter correct
  - [ ] Node tooltips show on hover; zoom/pan work
  - [ ] Missing nodes/edges → clear error
- **🐞 Bug signals:** Nodes pile on top of each other; animation stuck; tooltip never shows.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** N/A (LLM-emitted).

### [TOOL-35] ImageView — thumbnail → zoom dialog, caption, load error
- **Component(s):** `ImageView.tsx`
- **Preconditions:** Agent emits an ```image block (`{"src":...,"alt":...,"caption":...}`).
- **Steps:**
  1. Confirm the thumbnail (max-height ~400px, contain).
  2. Click it to open the fullscreen dialog (zoom).
  3. Confirm the caption below. Try an image with a broken src.
- **Expected — visual:** A clickable (cursor-zoom-in) thumbnail with a loading placeholder until
  loaded; clicking opens a large dialog (max 85vh). Caption centered below. Broken src → an
  "Failed to load image" state; invalid JSON → "Invalid image JSON".
- **Expected — behavioral:** Blob URLs are revoked on unmount (no leak).
- **✅ Checks (tick each):**
  - [ ] Thumbnail shows, placeholder while loading
  - [ ] Click opens a zoom dialog; ESC/click-out closes it
  - [ ] Caption rendered; broken image → graceful error (no broken-img icon spam)
- **🐞 Bug signals:** Thumbnail never loads; click doesn't zoom; caption missing; broken image
  shows the browser default broken-image glyph.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** N/A (LLM-emitted).

### [TOOL-36] TimelineView, ProgressView, MathBlock — render + status/colors + invalid JSON
- **Component(s):** `TimelineView.tsx`, `ProgressView.tsx`, `MathBlock.tsx`
- **Preconditions:** Agent emits ```timeline, ```progress, and ```math blocks.
- **Steps:**
  1. Timeline: confirm events on a vertical line with time + label + status-colored dots.
  2. Progress: confirm overall % bar + per-phase rows with status icons (check/spinner/clock/X)
     and durations.
  3. Math: confirm a KaTeX-rendered block formula; and an inline `$...$` formula in prose.
  4. For each, feed a malformed block and confirm the parse-error fallback.
- **Expected — visual:** Timeline dots colored by status (info/success/warning/error). Progress
  shows computed overall % when omitted; running phase has a spinner + %; completed phases are
  struck-through/dimmed. Math renders proper notation (not raw LaTeX).
- **Expected — behavioral:** Each parses JSON (math takes raw LaTeX) and shows a "Failed to
  parse …" pre block on bad input; math shows the raw latex in red on KaTeX error.
- **✅ Checks (tick each):**
  - [ ] Timeline: events in order, status colors correct, descriptions show
  - [ ] Progress: overall % + phase icons + durations correct
  - [ ] Math: block + inline both render via KaTeX
  - [ ] Malformed input for each → graceful parse-error state (no crash)
- **🐞 Bug signals:** Raw JSON/LaTeX shown instead of rendered output; wrong status colors;
  overall % `NaN`; a malformed block blanks the whole message.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** N/A (LLM-emitted).

### [TOOL-37] CollapsibleOutput — sectioned (`### header`) output, per-section collapse, line counts
- **Component(s):** `CollapsibleOutput.tsx`
- **Preconditions:** Agent emits an ```output block that contains `### Section` headers and ANSI.
- **Steps:**
  1. Confirm each `### header` becomes its own collapsible section with a "N lines" badge.
  2. Collapse/expand individual sections; the first is open by default.
  3. Confirm a header-less ```output block renders as one scrollable block.
- **Expected — visual:** Each section header row has a chevron + title + "N lines" badge; body
  is ANSI-colored, height-capped (~300px) with scroll. First section open, rest collapsed.
- **Expected — behavioral:** Single-section/header-less output renders directly (no per-section
  chrome), ~400px scroll.
- **✅ Checks (tick each):**
  - [ ] Sections split on `### ` headers; correct "N lines" counts
  - [ ] Each section collapses/expands independently; first open by default
  - [ ] ANSI colors render inside sections
- **🐞 Bug signals:** Sections not split; line counts wrong; ANSI shown as raw codes; sections
  won't toggle.
- **Theme/size matrix:** light + dark + long output
- **⛔ Write note:** N/A (LLM-emitted).

### [TOOL-38] AnsiOutput — terminal-output block, copy strips ANSI
- **Component(s):** `AnsiOutput.tsx`
- **Preconditions:** Agent emits an ```ansi block with color codes.
- **Steps:**
  1. Confirm a "Terminal Output" header bar + colored body.
  2. Click Copy; paste elsewhere.
- **Expected — visual:** Bordered block, "Terminal Output" header with a copy button; colored
  body (max-height ~400px, scroll). Plain text gets heuristic highlighting.
- **Expected — behavioral:** Copy yields ANSI-stripped text.
- **✅ Checks (tick each):**
  - [ ] Colors render; header + copy present
  - [ ] Copy output has no `\033[` escape codes
- **🐞 Bug signals:** Raw escape codes; copy includes codes; no scroll on long output.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** N/A (LLM-emitted).

### [TOOL-39] InteractiveHtml — sandboxed iframe with injected theme
- **Component(s):** `InteractiveHtml.tsx`
- **Preconditions:** Agent emits a ```html-interactive / ```visualization / ```viz block.
- **Steps:**
  1. Confirm the content renders inside a sandboxed iframe (`allow-scripts` only).
  2. Toggle theme; confirm CSS variables update inside the iframe.
- **Expected — visual:** Iframe-hosted content (fixed height ~400px) with the page background
  and `--*` theme variables injected; `color-scheme` matches the IDE theme.
- **Expected — behavioral:** postMessages from the iframe are validated (string `type`, length
  bounds) and forwarded to Kotlin via `_interactiveHtmlMessage`.
- **✅ Checks (tick each):**
  - [ ] Content renders in an iframe; respects theme
  - [ ] No network access / no escape from the sandbox
- **🐞 Bug signals:** Blank iframe; ignores theme; arbitrary network fetch succeeds.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** N/A (LLM-emitted; sandboxed, no network).

### [TOOL-40] ArtifactRenderer (render_artifact) — sandbox render, loading, error+missingSymbols, retry, self-repair
- **Component(s):** `ArtifactRenderer.tsx`; sandbox `artifact-sandbox.html` / `sandbox-main.ts`
- **Preconditions:** Ask the agent to use `render_artifact` to draw an interactive React
  component (e.g. a small dashboard). Then ask it to render one that references an undefined
  symbol to exercise the error path.
- **Steps:**
  1. Trigger a valid `render_artifact`; watch loading → rendered.
  2. Trigger one that uses an unimported/undefined symbol.
  3. Use Retry on the error card.
- **Expected — visual:** A spinner "Loading interactive content…" overlay over a sandboxed
  iframe; on success the iframe sizes to the reported height and the component is interactive.
  On error, a "Failed to render: [phase] message" state with Show source / Retry.
- **Expected — behavioral:** The tool **suspends** until the iframe reports the outcome
  (self-repair loop): a render error (with parsed `missingSymbols`) is fed back to the LLM as
  the tool result so it can correct. Exactly-once reporting per renderId (stale iframe messages
  rejected).
- **✅ Checks (tick each):**
  - [ ] Valid artifact renders + is interactive; iframe height fits content
  - [ ] Error artifact shows phase + message; "Show source" reveals the source
  - [ ] Retry re-attempts; the agent's NEXT turn reflects the render error (self-correction)
- **🐞 Bug signals:** Permanent spinner (tool never resumes); error not surfaced to the LLM (it
  thinks render succeeded); duplicate result reports; iframe overflows/clips.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** N/A (render_artifact draws in a sandbox; data must be inline, no writes).

### [TOOL-41] RichBlock chrome — copy/expand/open-in-tab/fullscreen, disabled-type raw source, show-more
- **Component(s):** `RichBlock.tsx` (shared wrapper for all rich renderers)
- **Preconditions:** Any rich block from TOOL-31..40.
- **Steps:**
  1. Use the header buttons: Copy source, Expand/Collapse, Open in editor tab, Fullscreen.
  2. For a tall block, use the gradient "Show more" overlay.
  3. (If a visualization type is disabled in settings) confirm it shows raw source instead.
- **Expected — visual:** Header shows the type icon+label, then Copy / Expand / Open-in-tab /
  Fullscreen actions. Height-capped blocks show a gradient fade + "Show more". Fullscreen opens
  a large dialog. Disabled type → plain raw-source `<pre>`.
- **Expected — behavioral:** "Open in editor tab" mirrors the block into a separate editor tab.
  Theme changes force a re-render (themeKey).
- **✅ Checks (tick each):**
  - [ ] Copy source copies the block's raw source
  - [ ] Expand/Collapse + "Show more" work (height cap respected when collapsed)
  - [ ] Fullscreen dialog opens and closes
  - [ ] Open-in-editor-tab opens a mirror tab
- **🐞 Bug signals:** Buttons inert; "Show more" never reveals the rest; fullscreen won't close;
  disabled type still tries (and fails) to render.
- **Theme/size matrix:** light + dark + tall block (show-more)
- **⛔ Write note:** N/A (LLM-emitted).

---

## Completion & async-event cards

### [TOOL-42] CompletionCard — done / review / heads_up kinds
- **Component(s):** `CompletionCard.tsx`; `ChatView.tsx:200-206` (COMPLETION_RESULT);
  `CompletionData` (`types.ts:467-480`)
- **Preconditions:** Let the agent finish a task (triggers `attempt_completion`). Different
  outcomes produce different `kind`s.
- **Steps:**
  1. Finish a simple task → expect a `done` card.
  2. (If produced) a `review` card with a `verifyHow` command, and a `heads_up` card with a
     `discovery` callout.
- **Expected — visual:** A bordered, tinted card with a kind-specific icon + accent:
  done = green "Task Completed"; review = amber "Please Review" (with the verify pill shown
  ABOVE the body); heads_up = blue "Heads Up" (with a "Discovery" callout below the body).
  Body is markdown-rendered (so it can contain rich blocks). A verify pill (mono command + copy)
  shows below the body for done/heads_up.
- **Expected — behavioral:** Copy-result button (top-right, hover). If `nextStep` is set it
  appears as ghost-text in the chat input (Right-Arrow to accept) — verify separately.
- **✅ Checks (tick each):**
  - [ ] Correct icon/label/accent per kind (done/review/heads_up)
  - [ ] `verifyHow` pill renders with a working Copy; positioned above body for review, below otherwise
  - [ ] heads_up shows the Discovery callout
  - [ ] Body markdown renders (lists/code/rich blocks)
- **🐞 Bug signals:** Wrong color/label; verify pill missing when `verifyHow` present; markdown
  shown raw; discovery callout missing for heads_up.
- **Theme/size matrix:** light + dark
- **⛔ Write note:** read-only; let a benign task complete on the scratch file.

### [TOOL-43] AsyncEventCard — background/monitor timeline event, status color, expand details, spill path
- **Component(s):** `AsyncEventCard.tsx`; `ChatView.tsx:176-182`; `UiMessageAsyncEventData`
  (`types.ts:429-439`)
- **Preconditions:** Start a background process or a `monitor` that emits an event while the
  session is focused (e.g. `background_process` running a read-only command that exits, or a
  shell `monitor` whose filter matches a line).
- **Steps:**
  1. Start a background read-only command that completes; observe the timeline card.
  2. Click the card to expand details; note the spill-path line if present.
- **Expected — visual:** A compact card with a Background/Monitor icon + badge, the `sourceId`
  (mono), a status dot, and a one-line summary. Status colors: SUCCESS green, FAILURE amber,
  NOTABLE grey, ALERT red. Below the header, the `label` (mono, truncated). Click to expand a
  details `<pre>` (max-height ~240px scroll); a spill path appends "Full output: {path}".
- **Expected — behavioral:** These cards are UI-only (invisible to the LLM); deduped by id on
  resume.
- **✅ Checks (tick each):**
  - [ ] Correct kind badge (Background vs Monitor) + status color
  - [ ] Summary line + sourceId shown collapsed
  - [ ] Expand reveals details; "(no output)" when empty; spill path appended when present
- **🐞 Bug signals:** Wrong status color; details won't expand; spill path missing when output
  was spilled; duplicate cards after a resume.
- **Theme/size matrix:** light + dark + long details (scroll)
- **⛔ Write note:** background command must be read-only (e.g. `sleep 2; echo done`); monitor
  filter watches a read-only command.

---

## Open questions / uncertainties (flag to the QA owner)

1. **imageRefs "N images attached" badge appears UNRENDERED (TOOL-11).** `types.ts:60-65/419`
   documents a "N images attached from tool" badge on the tool-result row, and `imageRefs` is
   plumbed through `jcef-bridge.ts` → `chatStore` → `ChatView` into the `ToolCall` object — but
   no component (`ToolCallChain`/`ToolCallItem`/`ToolCallDetails`) actually reads `tc.imageRefs`
   to render a badge. Either the doc is stale or the badge was dropped. Needs a product decision
   + a real `jira.download_attachment` image to confirm in-IDE.

2. **create_file (and delete_file) are absent from `CATEGORY_MAP` (TOOL-23).** They fall to the
   generic `TOOL` category, so they get a grey `TOOL` badge and render via `ToolCallDetails`
   (input/output text) — NOT the WRITE badge + DiffHtml diff that `edit_file` gets (`hasDiff`
   only fires for EDIT/WRITE categories). Confirm whether create_file SHOULD show a diff card
   like edit_file. (`write_file`/`Write` ARE mapped to WRITE, but the actual core tool is named
   `create_file`.)

3. **EditDiffView is showcase-only (TOOL-24).** The standalone `EditDiffView` (its own LCS diff
   + `-N / +N` header + Applied/Rejected/Pending status pill) is referenced only by
   `showcase.tsx` and tests — the live chat edit/approval path renders diffs via `DiffHtml`
   (in `ToolCallChain`, `ApprovalView`, `StreamingEditPreviewView`). The tester won't see
   `EditDiffView` in normal use; verifying it requires the dev showcase page. Confirm intent.

4. **Streaming edit preview is feature-flagged.** `enableStreamingEditPreview` (default ON,
   Settings → AI Agent ▸ Advanced ▸ Tool Calling). If a tester has it OFF, TOOL-25 is N/A —
   note the setting state in results.

5. **diff2html / chart.js / mermaid / KaTeX / dagre are lazy-loaded over the custom
   `http://workflow-agent` JCEF scheme** with timeouts (diff2html 5s, chart.js 8s). On a slow
   or sandboxed Windows box, watch for the raw-diff fallback (TOOL-22) or persistent loading
   placeholders — distinguish "expected fallback" from "broken chunk loading".

6. **Rich blocks require LLM cooperation.** Tables/charts/diagrams render only when the model
   emits the exact fenced language. If a scenario can't be provoked, note it rather than marking
   a failure; consider the dev showcase (`showcase.tsx`) for deterministic samples if available
   to the tester.

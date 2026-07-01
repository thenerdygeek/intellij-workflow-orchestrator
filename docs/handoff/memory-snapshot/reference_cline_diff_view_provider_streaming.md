---
name: cline-diff-view-provider-streaming
description: "Cline's DiffViewProvider streaming-edit algorithm — researched 2026-05-21 from cline/cline@main. Streams into a SEPARATE diff editor (right-side DocumentContent), not the actual file; 100ms throttle, line-batched range-replace, partial-marker pop guard. Real file is only touched at saveChanges(), which is gated by approval (auto-approve just auto-fires saveChanges)."
metadata: 
  node_type: memory
  type: reference
  originSessionId: 247bae41-48b3-4558-b20f-4db2cbaf460c
---

**Cline (cline/cline@main) streaming-edit algorithm — source-level reference**

Researched 2026-05-21 while designing our [[edit-file-streaming-preview-shipped]] port. Anchored to the snapshot fetched on that date.

## Parser

`src/core/assistant-message/parse-assistant-message.ts` (`parseAssistantMessageV2` after PR #5425 removed V1).

- **Re-parse the entire accumulated buffer on every SSE chunk** — not incremental. Stateless string walker. `src/core/task/index.ts:2942-2947`:
  ```ts
  assistantMessage += chunk.text
  this.taskState.assistantMessageContent = parseAssistantMessageV2(assistantMessage)
  ```
- Single-pass index walker with three state pointers (`currentTextContentStart`, `currentToolUseStart`, `currentParamValueStart`). Detects tag boundaries via `startsWith(tag, i - tag.length + 1)`.
- The moment the opening tag of a tool closes, a partial `ToolUse` is pushed:
  ```ts
  currentToolUse = { type: "tool_use", name: ..., params: {}, partial: true, call_id: nanoid(8) }
  ```
- Partial XML across SSE chunks just means the next pass sees more characters — the previous result is thrown away and re-parsed. Param values are sliced when their closing tag arrives; before that they're accumulating.

## DiffViewProvider

`src/integrations/editor/DiffViewProvider.ts` — abstract base, host-specific subclasses for VS Code and JetBrains. **Streams into a side diff editor — NOT the real file.** Real file is only touched at `saveChanges()`.

- **100ms throttle** via `UPDATE_THROTTLE_MS = 100`. `update(accumulatedContent, isFinal)`:
  ```ts
  if (timeSinceLastUpdate < DiffViewProvider.UPDATE_THROTTLE_MS) return
  ```
- **Line-batched range-replace**, not insertion:
  ```ts
  const accumulatedLines = accumulatedContent.split("\n")
  if (!isFinal) accumulatedLines.pop()  // drop trailing partial line while streaming
  const diffLines = accumulatedLines.slice(this.streamedLines.length)
  ```
  Replaces the range `[0, currentLine+1]` every tick. Comment in source: HTML auto-closing tags otherwise corrupt earlier lines, so the whole prefix is replaced rather than appended.
- Small diffs (≤5 lines) jump via `scrollEditorToLine(currentLine)`; larger ones animate via `scrollAnimation(startLine, endLine)`. That's the "lines streaming in" effect — it's batched range-replace + scroll animation, **not** char-by-char or line-by-line insertion.
- `saveChanges()` (lines 337-404): writes the right-side content to the real file, captures pre/post text for user-edit + auto-format diffs, closes the diff editor.
- `revertChanges()` (lines 406-447): if file is new, force-delete + remove created directories; else restore `originalContent` snapshot taken in `open()` (line 47). Cancel-on-interrupt path.

## SEARCH/REPLACE streaming (`replace_in_file`)

`src/core/assistant-message/diff.ts` — `constructNewFileContent`. Different streaming pattern than `write_to_file`:

- Markers: `------- SEARCH` / `=======` / `+++++++ REPLACE`.
- SEARCH block matched the instant `=======` arrives: exact `indexOf` → `lineTrimmedFallbackMatch` → `blockAnchorFallbackMatch` (3+ lines, first+last as anchors) → full-file scan → throw.
- REPLACE lines stream incrementally to the result string the moment they appear: `result += line + "\n"`.
- **Partial-marker pop guard** (lines 290-303): if the last buffered line starts with `-`, `=`, or `+` but isn't a complete marker, pop it. Prevents half-arrived `------- SEARC` from corrupting state mid-chunk.
- Caller swallows errors when `block.partial` (`WriteToFileToolHandler.ts:501-514`) — skips flicker until the diff is complete.

## Approval timing

`src/core/task/tools/handlers/WriteToFileToolHandler.ts:73-88` + `autoApprove.ts`:

- **`handlePartial()`** fires on every partial parser pass. Diff editor opens for **both** ask and auto-approve modes — the partial render is identical.
- Difference between modes: chat row is `say` (auto-approve) vs `ask` (manual). Approval gates the **`saveChanges()`** call in `execute()`, not the stream.
- `shouldAutoApproveTool` returns `[localApprove, externalApprove]`. `shouldAutoApproveToolWithPath` decides if the file is in-workspace. YOLO mode (`autoApprove.ts:43`) and `autoApproveAllToggled` short-circuit to true.

## Cancellation

`task/index.ts:2961-2969` checks `taskState.abort` after each chunk → `this.api.abort?.()` + `abortStream("user_cancelled")`. File-side rollback is `DiffViewProvider.revertChanges()`. New files → force-delete + remove created dirs in reverse. Existing files → write `originalContent` snapshot back via `replaceText(originalContent, {startLine: 0, endLine: lineCount})` + `saveDocument()`.

## What we ported vs deferred for our plugin

Our [[edit-file-streaming-preview-shipped]] port:
- **Ported**: re-parse-on-every-chunk (our `AssistantMessageParser.parse()` already had this — see `core/src/main/kotlin/.../AssistantMessageParser.kt`). 100ms throttle in `StreamingEditTracker`. Original-content snapshot at `open` time. `cancelAll` on stream-interrupt.
- **Adapted**: stream surface is **JCEF chat** (not a separate IntelliJ diff editor). Reuses our existing `DiffHtml.tsx` rendering. Simpler, lower-risk, no new IntelliJ diff-view API surface.
- **Deferred**: editor-pane streaming via `DiffManager.showDiff` + `DocumentContent` on the right. The "lines appear in IntelliJ's editor pane live" experience would require this. Would compose on top of the current chat preview (both surfaces showing the same stream), or replace it entirely. Decide later based on user feedback on v1.
- **Don't port**: Cline uses `fs.readFile` + `iconv-lite` for encoding detection. IntelliJ already resolves charset via `VirtualFile.charset` / `LoadTextUtil` — reuse that. Cline's `await fs.writeFile(absolutePath, "")` pre-create trick is also unnecessary; IntelliJ has `VfsUtil.createChildData(parent, name)`.

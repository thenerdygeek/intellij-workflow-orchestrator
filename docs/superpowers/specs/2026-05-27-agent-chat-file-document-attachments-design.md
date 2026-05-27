# Design — Agent chat file & document attachments

**Date:** 2026-05-27
**Module:** `:agent` (with a settings field in `:core`)
**Status:** Approved (brainstorming complete) — pending implementation plan

## Problem

The agent chat input was supposed to let users attach files via a "plus" button
and via drag-and-drop, with a context chip per attachment. Neither works today.

Root cause (two layers):

1. **JCEF bridging gap.** The webview is a React app running inside JCEF
   (embedded Chromium). The React picker (`<input type="file">` triggered from
   the plus menu) and the drag-drop handlers (`onDrop`/`onDragOver` on the input
   container) are correct *for a browser*, but JCEF does not bridge them to the
   host OS:
   - Clicking `<input type="file">` only opens a chooser if a `CefDialogHandler`
     is registered on the `jbCefClient`. None is registered
     (`AgentCefPanel.createBrowser()` registers a load handler and the JS-query
     pool size, never `addDialogHandler`). The click reaches a dead end.
   - Dragging an OS file into embedded Chromium does **not** produce an HTML5
     `drop` event with `dataTransfer.files`. That requires a Swing
     `java.awt.dnd.DropTarget` on the browser component to forward the drag.
     There is no `DropTarget` anywhere in `:agent` main sources.
   - The in-page **paste** path works because clipboard data never leaves the
     Chromium layer (`RichInput.tsx` `onPaste`).

2. **Image-only pipeline.** Even with the bridges fixed, the attach pipeline is
   hard-limited to three image MIME types (`image/png`, `image/jpeg`,
   `image/webp`) on every layer (file-input `accept`, drag filter, client + server
   validation). Documents/text files cannot be attached at all.

> Note: image attach is *also* gated behind the "Enable visual support" master
> switch (`PluginSettings.enableImageInput`, default `false`, "panic-button
> posture"). The reporting user already has it enabled, so the gate is not their
> blocker — the JCEF bridging gap is. The gate remains relevant for the
> image→vision routing decision below.

## Goals

- Plus-button "Attach file…" opens a real file chooser inside the IDE.
- OS drag-and-drop of files onto the chat input works, with a clear drop-zone
  overlay shown while a file is dragged over the input.
- A context chip appears per attachment (thumbnail for images, file-type icon +
  filename for other files).
- Accept **any** readable file, not just images. Route by type:
  - **Image** (png/jpeg/webp) → existing vision path (BrainRouter → `/stream`).
  - **Everything else** → land the file at a session path the agent can read;
    the agent reads it on demand with `read_file` (txt/md/xml/json/source/logs)
    or `read_document` (pdf/docx/xlsx/pptx/rtf/odt/epub/html/csv).

## Non-goals

- OCR for scanned-image PDFs (already out of scope in `read_document` v1).
- Eager extraction / inlining of document content at send time (explicitly
  decided against — see "Document ingestion").
- A separate "Enable file attachments" master switch (file attach is always on).
- Editor-pane / external upload targets — attachments are session-scoped only.

## Chosen approach

**Acquire files on the JVM, store on disk, push only metadata to the webview.**

Both the plus-button and drag-drop funnel through a single JVM ingestion path.
Bytes never cross the JCEF bridge; only chip metadata (name, sha256, mime, kind,
size, path) does. This reuses `AttachmentStore`, `AttachmentReadHandler` (image
thumbnail serving), the existing chip UI, and the send payload.

### Alternatives rejected

- **Wire a `CefDialogHandler` to fix the HTML `<input type=file>`.** Drag-drop
  still cannot work through HTML in JCEF, so a Swing `DropTarget` is required
  regardless. Fixing the HTML input would leave two divergent acquisition paths;
  unifying on the JVM is cleaner.
- **base64 bytes JVM→JS over the bridge.** Violates the project rule that
  multi-MB binary bytes never travel through `JBCefJSQuery` (documents can be
  large). The existing image path already avoids this via a `fetch` POST to a
  scheme handler; the JVM-acquisition path avoids it by storing on disk.

## Components

### New (Kotlin, `:agent`)

1. **`AttachmentPicker`** — wraps `com.intellij.openapi.fileChooser.FileChooser
   .chooseFiles` with an all-files `FileChooserDescriptor`
   (`withFileFilter` = none; multi-select allowed). Invoked from a new
   `pickAttachmentQuery` JCEF bridge exposed to JS as `window._pickAttachment()`.
   Runs on EDT. Returns selected `VirtualFile`s to the ingest service.

2. **`AttachmentDropTarget`** — a `java.awt.dnd.DropTarget` installed on
   `AgentCefPanel`'s `b.component` (the heavyweight JCEF Swing component),
   accepting `DataFlavor.javaFileListFlavor`. On drop, extracts `List<File>` and
   hands it to the ingest service. This is the piece that makes OS drag-drop
   actually fire. The DropTarget supersedes the React `onDrop`/`onDragOver`
   handlers for OS-originated file drops; those React handlers may be left in
   place for in-page drags or removed (implementation detail — they are inert for
   OS files because the event never reaches Chromium).

   **Visual feedback (required).** Because OS drags are intercepted by Swing
   *before* Chromium, the browser cannot show a CSS `:drag-over` state on its
   own — the feedback must be driven from the JVM DropTarget callbacks:
   - `dragEnter` (only when the transfer offers `javaFileListFlavor`) →
     `dtde.acceptDrag(DnDConstants.ACTION_COPY)` and push `_setDropActive(true)`.
   - `dragExit` and `drop` → push `_setDropActive(false)`.
   - A non-file drag (e.g. text) → `dtde.rejectDrag()` and no overlay.
   The webview renders a **drop-zone overlay** in response to `_setDropActive`:
   a dashed accent border over the chat input region plus a centered label
   ("Drop files to attach") and a subtle scrim. The overlay is purely a UI
   affordance — it never holds bytes. Reset is guaranteed on both `drop` and
   `dragExit`; if a drag leaves the IDE window without an exit event, the next
   `dragEnter`/`drop` re-syncs the state, and `_setDropActive(false)` is also
   pushed defensively at drop-completion regardless of success.

3. **`AttachmentIngestService`** (new class, or a focused method group on
   `AgentController` — final placement decided in the plan) — shared by picker +
   drop. For each acquired file:
   - **Classify**: image (mime in image whitelist) vs file (everything else).
   - **Validate size**: image cap = `imageMaxBytes` (5 MB default); file cap =
     `fileMaxBytes` (50 MB default). Enforce per-turn caps (`imagesPerTurnCap` = 2,
     `filesPerTurnCap` = 5).
   - **Gate images**: if `enableImageInput` is false and the file is an image →
     toast "Enable visual support in Settings to attach images.", skip the file.
   - **Read bytes off-EDT** (`Dispatchers.IO`).
   - **Store**:
     - Image → `AttachmentStore.store(bytes, mime, name)` →
       `{sessionDir}/attachments/<sha>.<ext>` (unchanged vision path).
     - File → store under `{sessionDir}/attachments/` preserving the **original
       filename** (so the on-disk extension helps `read_document`/`read_file`
       type detection and the path is human-meaningful). Compute sha256 for the
       chip key and within-session dedup.
   - **Push chip metadata** to JS via a new `_addAttachmentChip(json)` bridge:
     `{sha256, mime, size, originalFilename, kind, path?}`. `path` is the
     absolute session path, present only for `kind: 'file'`.
   - No active session (sessionDir null) → toast "Start a chat before attaching
     files.", skip.

### Changed (webview, `agent/webview/`)

4. **`PendingAttachment`** (`AttachmentManager.ts`) gains `kind: 'image' |
   'file'` and `path?: string`. A JVM-sourced attachment is added to the
   manager's list via a new entry point (the `_addAttachmentChip` bridge calls
   into the manager) — it bypasses the JS-side `attachFile` byte-handling because
   the bytes already live on disk; the chip is metadata-only. JS-side
   `attachFile` (paste path) is unchanged and produces `kind: 'image'`.

5. **`ChipPreview.tsx`** renders:
   - `kind: 'image'` → thumbnail (unchanged; image source is the ObjectURL for
     pasted images, or `http://workflow-agent/attachments/<sha>` for JVM-stored
     images served by `AttachmentReadHandler`).
   - `kind: 'file'` → a file-type icon (by extension/mime) + filename + size,
     with the same remove "×" affordance.

5b. **Drop-zone overlay** — a new lightweight component (e.g.
   `DropOverlay.tsx`) plus a `chatStore` boolean `dropActive`, set by the
   `_setDropActive(active)` bridge. When `dropActive` is true the overlay
   renders over the input region: dashed accent-colored border, a centered
   "Drop files to attach" label, and a low-opacity scrim using the existing
   theme CSS variables. No pointer interaction; it is dismissed when
   `dropActive` flips false. This is the *only* drag affordance the user sees,
   since the React `onDragOver` path is inert for OS files.

6. **`InputBar.tsx` plus-menu**: replace the gated "Image" item with a single
   **"Attach file…"** entry that calls `window._pickAttachment()`. Accepts
   everything; routing happens by type on the JVM side. The existing
   File/Folder/Symbol/Skill items (which are @-mention pickers, unrelated to
   upload) are untouched. The hidden HTML `<input type="file">` and the
   JS-triggered file picker (`handlePickImage`/`handleFilePicked`) are removed —
   acquisition is now JVM-native.

### Changed (Kotlin send path, `AgentController` / `AgentCefPanel`)

7. **Send payload + routing.** `attachmentsForTurn` carries `kind` and `path`.
   On send:
   - **Image** attachments → existing vision path (sha256 refs hydrated by
     `BrainRouter` → `/stream`). Unchanged.
   - **File** attachments → append a marker block to the outgoing user message
     text, one line per file:
     `[Attached file: <absolute session path>]` plus a one-line hint that the
     agent can read it with `read_file` (text) or `read_document` (binary/office
     formats). When at least one file attachment is present, **auto-activate the
     deferred `read_document` tool** for the session (so the agent doesn't have
     to `tool_search` for it first). `read_file` is a core tool and always
     available.

### Settings (`:core`)

8. Add two project-level `PluginSettings` fields:
   - `fileMaxBytes` (default `52_428_800` = 50 MB) — per non-image attachment cap.
   - `filesPerTurnCap` (default `5`) — non-image attachments per user turn.
   Surface both in `MultimodalSettingsConfigurable` (no new master switch).
   Image fields (`imageMaxBytes`, `imagesPerTurnCap`, `imageMimeWhitelist`,
   `enableImageInput`) are unchanged. These two new values are pushed to the
   webview through the existing `_getImageSettings` / `__applyImageSettings`
   channel (extend the JSON payload) so the JS-side per-turn cap checks for the
   paste path stay in sync; the authoritative validation for picker/drop runs on
   the JVM in `AttachmentIngestService`.

## Data flow

```
pick (plus → "Attach file…")  OR  OS drag-drop onto chat input
        │ _pickAttachment()                     │ AttachmentDropTarget
        ▼                                        ▼
              AttachmentIngestService (JVM)
              classify · validate (size, per-turn, image gate) · read bytes off-EDT · store
        ├─ image (png/jpeg/webp) → AttachmentStore → {sessionDir}/attachments/<sha>.<ext>
        └─ any other file        → {sessionDir}/attachments/<originalName>
        │
        ▼ _addAttachmentChip({sha,mime,size,name,kind,path?})
   webview chip list (ChipPreview: thumbnail | file-icon)
        │
        ▼ Send  → payload [{kind, path, sha256, mime, size, originalFilename}]
   AgentController.buildUserMessage:
        ├─ images → vision refs → BrainRouter → /stream            (unchanged)
        └─ files  → append "[Attached file: <path>]" + read hint
                    + auto-activate deferred read_document
        ▼
   AgentLoop → agent calls read_file / read_document on demand
```

## Error handling

| Condition | Behaviour |
|---|---|
| No active session (`sessionDir == null`) | Toast "Start a chat before attaching files."; file skipped. |
| File exceeds cap | Toast with the limit; file skipped. |
| Per-turn cap reached | Toast; extra files skipped. |
| Image dropped while `enableImageInput == false` | Toast "Enable visual support in Settings to attach images."; image skipped. |
| Unreadable/unsupported binary attached as file | Attached anyway. `read_document` errors at read time; the agent surfaces the error (per the "don't restrict formats" requirement). |
| Bytes-write failure (disk) | Toast "Couldn't store attachment: <reason>."; no chip pushed. |

## Testing

**Kotlin (`:agent`):**
- `AttachmentIngestService`: classification (image vs file), size validation,
  per-turn cap, image gate when visual support off, session-path storage with
  original filename preserved, sha dedup, `read_document` auto-activation when a
  file attachment is present.
- Source-text / MockK pins: `AttachmentDropTarget` is installed on the JCEF
  component in `createBrowser()`; the `pickAttachmentQuery` bridge is registered
  and injected as `window._pickAttachment`.
- `AttachmentDropTarget` callback behaviour: `dragEnter` with a file-list
  transfer accepts the drag and pushes `_setDropActive(true)`; a non-file drag
  is rejected and pushes no overlay; `dragExit` and `drop` push
  `_setDropActive(false)`.

**Webview (vitest):**
- `ChipPreview` renders a file chip (icon + filename) for `kind: 'file'` and a
  thumbnail for `kind: 'image'`.
- `_addAttachmentChip` adds a metadata-only chip without byte handling.
- Send payload carries `kind` and `path` for file attachments.
- `DropOverlay` renders when `chatStore.dropActive` is true and is absent when
  false; `_setDropActive` toggles the store flag.

**Reachability:**
- A dropped/picked file lands under `{sessionDir}/attachments/` (tier-2,
  agent-readable per Storage Tiers) and `read_document` can open it by absolute
  path.

## Open implementation details (resolved during planning, not blocking)

- Final home of `AttachmentIngestService` (standalone class vs `AgentController`
  method group).
- Whether to retain the now-redundant React `onDrop`/`onDragOver` handlers or
  remove them.
- File-type icon set for the file chip (reuse existing webview icon assets).
- Exact storage filename scheme for the file kind (sha-prefixed dir vs original
  name at attachments root) to avoid collisions between two files with the same
  name in one turn.

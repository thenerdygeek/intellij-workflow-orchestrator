# Image-attachment debugging — context handoff

**Branch:** `feature/context-compaction`
**Latest release for testing:** `0.83.52-alpha` (built but unreleased at handoff time;
release the ZIP from `build/distributions/intellij-workflow-orchestrator-0.83.52-alpha.zip`)
**Date:** 2026-05-04

## What problem are we solving

User on Windows reported: clicking the **+ → Image** button, picking a PNG, and
clicking **Open** appeared to do nothing. After fixes, the chip rendered but
the image didn't reach the LLM and the bubble didn't show the image. We
traced the failures, fixed five distinct root causes across five releases,
and added end-to-end pipeline plumbing in 0.83.52.

The user is on Windows; Mac is the dev box. All bugs were reproducible on
Windows but not on Mac because secure-context / CEF-thread behavior differs
slightly between platforms.

## Releases shipped this session (each fixed one root cause)

| Version | Root cause | Fix |
|---|---|---|
| 0.83.47-alpha | (none — first attempt at logging) | Added `[multimodal:attach]` console.log gates in JS; `LOG.info` in `AttachmentUploadHandler` |
| 0.83.48-alpha | **Vite stripped `console.*`** in production builds → all 0.83.47 logs invisible | Flipped `terserOptions.compress.drop_console` from `true` to `false` in `agent/webview/vite.config.ts` |
| 0.83.49-alpha | **`crypto.subtle === undefined`** — webview origin `http://workflow-agent` is not a "secure context", so Chromium refuses SubtleCrypto | Added pure-JS FIPS 180-4 SHA-256 fallback in `AttachmentManager.sha256Hex`; verified against Node `crypto` for 7 vectors including 55/56/64-byte boundaries |
| 0.83.50-alpha | **`runBlockingCancellable` IllegalStateException** on CEF network thread — no IDE Job/ProgressIndicator there | Added `AttachmentStore.storeBlocking` + `readBlocking` (synchronous JDK file I/O variants); `AttachmentUploadHandler` and `attachmentExistsQuery` use them |
| 0.83.51-alpha | **Session-ID race**: upload arrives ~12ms BEFORE `executeTask` creates the session → `currentSessionDirProvider` returned null → 400 `no_active_session` | `AgentController.setCurrentSessionDirProvider` lazily allocates `currentSessionId = UUID()` on first call; `executeTask` already passes `sessionId = currentSessionId` so AgentService reuses the same ID |
| **0.83.52-alpha** | **Sha256 list never reached Kotlin** → `BrainRouter.hasImageParts()` always false → text-only completions endpoint → LLM literally never saw the image. Bubble had no field to render attachments either. | Full end-to-end plumbing — see "0.83.52 changes" below |

## 0.83.52 changes (the big one — verify everything works)

End-to-end plumbing for attachment metadata + bubble rendering. Many files;
each change is small. **All Kotlin compiles, all TS typechecks.**

### JS
- `agent/webview/src/components/input/InputBar.tsx` — `handleSend` snapshots
  `manager.list()` BEFORE `clear()`, passes as 3rd arg to
  `chatStore.sendMessage(text, mentions, attachmentsForTurn)`.
- `agent/webview/src/components/input/AttachmentManager.ts` — pure-JS sha256
  fallback already there from 0.83.49.
- `agent/webview/src/stores/chatStore.ts` — `sendMessage(text, mentions, attachments?)`,
  `startSession(task, mentions?, attachments?)`, `addUserMessage(text, mentions?, attachments?)`.
- `agent/webview/src/bridge/types.ts` — added `attachments?: ImageRef[]` to `UiMessage`.
- `agent/webview/src/bridge/jcef-bridge.ts` — extended
  `kotlinBridge.sendMessageWithMentions(text, mentionsJson, attachmentsJson?)`.
  Added 4 new Kotlin→JS bridge functions: `startSessionWithAttachments`,
  `startSessionWithMentionsAndAttachments`, `appendUserMessageWithAttachments`,
  `appendUserMessageWithMentionsAndAttachments`.
- `agent/webview/src/components/chat/AgentMessage.tsx` — USER_MESSAGE branch
  renders `<img src="http://workflow-agent/attachments/{sha256}">` strip when
  `message.attachments` is non-empty.

### Kotlin
- `agent/src/main/kotlin/.../session/AttachmentStore.kt` — synchronous variants
  `storeBlocking`/`readBlocking`/`findExtensionForBlocking` (suspend variants
  delegate to them).
- `agent/src/main/kotlin/.../session/UiMessage.kt` — added
  `attachments: List<ContentBlock.ImageRef>? = null`.
- `agent/src/main/kotlin/.../ui/AttachmentReadHandler.kt` — **NEW FILE**.
  Serves `GET http://workflow-agent/attachments/<sha256>` from disk for
  `<img>` thumbnails. Validates sha256 = 64 lowercase hex chars (anti-traversal).
  Sets correct Content-Type via `findExtensionForBlocking`.
- `agent/src/main/kotlin/.../ui/WorkflowAgentSchemeRegistrar.kt` — added
  `setReadHandlerFactory` and `/attachments/*` dispatch.
- `agent/src/main/kotlin/.../ui/AgentCefPanel.kt` — installs the read handler
  factory; extended `startSession`, `startSessionWithMentions`,
  `appendUserMessage`, `appendUserMessageWithMentions` with optional
  `attachmentsJson` param. `onSendMessageWithMentions` callback signature
  now `(String, String, String?) -> Unit`. The `sendMessageWithMentionsQuery`
  parses `attachments` out of the JSON payload.
- `agent/src/main/kotlin/.../ui/AgentDashboardPanel.kt` — passthrough wrappers
  extended with optional `attachmentsJson` param. `setCefMentionCallbacks`
  signature updated.
- `agent/src/main/kotlin/.../ui/AgentController.kt`:
  - `parseAttachments(attachmentsJson)` helper → `List<ContentBlock.ImageRef>`.
  - `attachmentsToJson(...)` helper for the bubble path.
  - `executeTaskWithMentions(text, mentionsJson, attachmentsJson?)`.
  - `executeTask(task, ..., attachments)` — new optional param.
  - `executeTaskInternal(..., attachments)` — threads through.
  - `displayUserMessage(text, mentionsJson, uiMessageOverride, attachments)`.
  - `setCurrentSessionDirProvider` lazily allocates session ID + dir
    (from 0.83.51).
  - `service.executeTask(task = task, sessionId = currentSessionId,
    attachments = attachments, ...)` call site.
- `agent/src/main/kotlin/.../AgentService.kt` — `executeTask(task, sessionId,
  attachments = emptyList(), ...)`. Builds user `ApiMessage` content as
  `Text(task) + ImageRef(...)*`, sets `attachments` on persisted `UiMessage`.

### Latent fix (uncommitted, not version-bumped)
- `AgentController.kt` no-options branch of `ask_followup_question` setup: added
  `AskQuestionsTool.uiRenderConfirmed = true` after `flushStreamBuffer()`
  (and in the catch-fallback path). The streaming-token path bypasses
  `showQuestions`, so the tool's 10s watchdog used to fire `[UI_RENDER_FAILED]`
  even though the question rendered. **This change is in the file; it ships
  in 0.83.52.**

## Known limitations (acceptable for v1)

1. **Pending-question reply / plan-mode reply with attachments** — bubble
   renders the thumbnails but the image bytes do NOT reach the LLM (those
   paths use `pending.complete(text: String)` and
   `userInputChannel.send(text: String)` — no payload type for image refs).
   AgentController logs a `WARN` when this happens. Bytes remain on disk so
   a follow-up turn could attach them.
2. **Fallback (non-JCEF) panel** — doesn't render attachments. Acceptable
   because the fallback is a degraded mode anyway.

## How to verify the fix on Windows

1. Install `0.83.52-alpha` (the ZIP is in `build/distributions/`).
2. Restart the IDE.
3. Open the agent chat panel.
4. Right-click in the panel → **Open DevTools** → Console tab.
5. Click **+ → Image** → pick a PNG → click **Open**.
6. Verify in DevTools console:
   - `[multimodal:attach] handlePickImage: triggering file picker`
   - `[multimodal:attach] AttachmentManager.attachFile: sha256=… (path= pure-js)`
   - `[multimodal:attach] AttachmentManager.attachFile: SUCCESS — added chip`
   - `[multimodal:attach] handleSend: passing 1 attachments to sendMessage` (when Send clicked)
7. Verify in `idea.log`:
   - `AttachmentUploadHandler: stored sha256=… size=…`
   - `[Agent] User turn carries 1 image attachment(s): …/image/png/…B`
8. Verify in the chat UI: the user-message bubble shows the image
   thumbnail above the text.
9. Verify the LLM response describes the image correctly (not "I don't see
   any image").

If any of those fail, that's the next bug to investigate.

## Memory + reference files

The session updated these auto-memory files (paths under
`/Users/subhankarhalder/.claude/projects/-Users-subhankarhalder-Desktop-Programs-scripts-IntelijPlugin/memory/`):

- `project_image_attach_pipeline.md` — overall pipeline + lessons learned.

## Useful commands for next session

```bash
# Rebuild webview (Vite drop_console is now off so console.* survives)
cd agent/webview && rm -rf ../src/main/resources/webview/dist && npm run build

# Compile only :agent (fast)
./gradlew :agent:compileKotlin

# Full plugin ZIP
./gradlew clean buildPlugin
# Output: build/distributions/intellij-workflow-orchestrator-<version>-alpha.zip

# Tail Windows idea.log for AttachmentUploadHandler / attachmentExists / [Agent]
# (paste the user's logs back; the [Agent] User turn carries N image attachment(s)
# log line is the smoking gun for whether the wire path works)
```

## Worth remembering for the next session

- The user's Windows JCEF runs from `http://workflow-agent`, which is **not** a
  secure context. SubtleCrypto, `runBlockingCancellable`, and any other
  "secure-context required" or "IDE-coroutine-context required" API will fail
  silently or throw on the CEF network thread. Always have a non-coroutine,
  non-SubtleCrypto fallback.
- Vite's `drop_console: true` was hiding **all** debug logs. The fix is
  permanent in `agent/webview/vite.config.ts`. Any future debug log will be
  visible without re-flipping a flag.
- The `WorkflowAgentSchemeRegistrar.DispatchingFactory` is the canonical place
  to add new in-process HTTP endpoints. Three handlers now: static assets,
  upload (POST), read (GET).
- `BrainRouter.hasImageParts()` checks `ChatMessage.parts: List<ContentPart>?`
  for `ContentPart.Image`. The conversion from `ApiMessage.content` (which
  carries `ContentBlock.ImageRef`) lives in
  `agent/session/ApiMessage.kt:toChatMessage()`. So the agent-side data model
  is `ContentBlock.ImageRef`; the brain-side model is `ContentPart.Image`.
- The session-dir provider is lazy-allocating now. Future code must NOT
  assume `currentSessionId == null` means "no session in progress" — it just
  means "no session has been promoted from the lazy slot yet". The promotion
  happens on first upload OR first `executeTask`.

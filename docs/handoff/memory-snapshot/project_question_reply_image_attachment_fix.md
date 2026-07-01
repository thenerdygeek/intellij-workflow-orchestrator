---
name: project_question_reply_image_attachment_fix
description: Images attached while ANSWERING a pending question (or plan-mode reply) were silently dropped; fixed by carrying refs through to ToolResult.imageRefs / addUserMessageWithParts
metadata: 
  node_type: memory
  type: project
  originSessionId: 1689cfba-e2f2-4c15-bd39-0410cdf5134a
---

**FIXED 2026-06-04 (uncommitted, on `main`): image attachments on a question-answer / plan-mode reply silently dropped.**

Symptom (live, Windows test build): user attaches image while the agent has a pending `ask_followup_question`, sends "Can you see this image?" → agent replies "I don't see a **new** image in your current message" even though the chat bubble shows the image. Log smoking gun: `AgentController: N attachment(s) on pending-question reply will not reach the LLM (rendered in bubble only)` + later iteration sends only the OLDER images already in history.

**Root cause:** the answer transports are text-only by type — `AskQuestionsTool.pendingQuestions: CompletableDeferred<String>` and the plan-mode `userInputChannel: Channel<String>`. Attachments were rendered in the (optimistic, frontend) bubble but never threaded to the LLM. NOT the vision gate / `enableImageInput` / `splitAttachmentsJson` / byte-MISS — those all work (proven by iteration-3 `imageEnabled=true hasImage=true → image-or-mixed-stream`, bytes hydrated).

**UX trap (why it surprised the user):** a *simple* `ask_followup_question` (no option chips) is visually identical to normal chat — you answer by just typing. So an image-bearing "normal" message is consumed as the answer and dropped. (A wizard/options question's Cancel button DOES clear the pending deferred + stop the loop, so the user's "after cancel it prints normally" memory was correct for that case.)

**Fix (TDD, both paths):**
- Path A — `AskQuestionsTool`: new `pendingAnswerImageRefs` field, set by `AgentController` before `pending.complete(task)`, drained into the returned `ToolResult.imageRefs` (the existing image→`ContentBlock.ImageRef` seam `jira.download_attachment` uses) and cleared on every exit path. Tests in `AskFollowupQuestionFlowTest`.
- Path B — `AgentLoop`: new `pendingChannelImageRefs: (() -> List<ContentBlock.ImageRef>)?` provider; `addReceivedUserMessageToContext()` helper routes all 3 channel-receive sites through `addUserMessageWithParts` when images present. Threaded via `AgentService.executeTask`/`resumeSession` as EXPLICIT per-call data (like `userInputChannel`), deliberately NOT through the parity-locked `SessionUiCallbacks` bundle — delegated sessions are act-only so plan replies can't occur there. `AgentController.pendingReplyImageRefs` AtomicReference, cleared with `pendingUiMessageOverride`. Test in `PlanModeLoopTest`.

Full `:agent` suite GREEN only after `--no-build-cache --rerun-tasks` — adding the `AgentLoop` ctor param re-triggered the documented build-cache `NoSuchMethodError` trap (see CLAUDE.md). Windows smoke + commit PENDING.

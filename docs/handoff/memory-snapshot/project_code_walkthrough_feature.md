---
name: project_code_walkthrough_feature
description: Agent-driven code-walkthrough feature (guided code tours via the walkthrough meta-tool) — shipped to PR
metadata: 
  node_type: memory
  type: project
  originSessionId: 0debaa6c-2815-4166-9e23-5cfa4edae4ce
---

# Code Walkthrough — agent-driven guided code tours (PR #56, 2026-06-11)

New `:agent` feature: the `walkthrough` meta-tool lets the LLM stream guided code-tour steps
(file + line range + markdown) into a project-level `WalkthroughService` that drives a
`RangeHighlighter` + a draggable JBPopup callout the user pages through with Back/Ask/Next.
Producer/consumer (tool returns immediately; user paces; loading state auto-advances on next
`append`). Ephemeral (no persistence/replay).

**⚠ v2 UX iteration (2026-06-11, after in-IDE testing) CHANGED the Q&A + tool surface — current truth:**
- **Ask → main chat** (NOT inline). v1's in-popup ask field had a JBPopup focus bug (couldn't type).
  v2: clicking Ask arms a one-shot step-ref in `AgentController.armWalkthroughQuestionContext(stepRef)`
  + `dashboard.focusInput()`; the ref is captured+cleared at the TOP of `executeTaskInternal` and
  applied ONLY on the fresh-turn path (after all 5 short-circuit returns) — prefixes the MODEL text
  `[Walkthrough · file:lines] ` while `displayText` stays the user's raw words. Kotlin-only, no webview change.
- **`update_step` action REPLACED `answer`.** `walkthrough(action=update_step, step=<1-based>, body_md, mode=append|replace)`
  lets the agent revise/enrich a shown step; re-renders only if it's the current step (else silent, seen on Back).
- **Syntax-highlighted code blocks** via `HtmlSyntaxInfoUtil.appendHighlightedByLexerAndEncodedAsHtmlCodeSnippet`
  (takes a `Language`, resolve via `Language.findLanguageByID`/extension) + inline `<style>` (JEditorPane honors
  inline `style=`, ignores `class=`). Try/catch falls back to styled-uncolored on unknown lang.
- REMOVED in v2: `QuestionEnvelope`, state-machine `pendingQuestion`/`askQuestion`/`answerDelivered`,
  service `submitQuestion`/`deliverAnswer`/`canAsk`, UI `showAnswering`/`showAnswer`/`showAnswerFallbackNote`,
  controller `isChatAwaitingUserReply`. KEPT: `generationPaused`/`setGenerationPaused` + the loop-parked
  pause hooks (orthogonal to Ask).

**⚠ .3 popup-visibility fix (2026-06-11, after .2 in-IDE test):** the callout never appeared past step 1
(file opened+highlighted, no box). TWO causes in `WalkthroughCalloutPopup.showAt`: (1) it REUSED one
JBPopup instance across steps — **a JBPopup can't be reliably re-shown once shown/hidden**; (2) it
positioned via `RelativePoint(editor.contentComponent, point).screenPoint` on a just-opened editor whose
component isn't laid out → `getLocationOnScreen()` throws. Fix: **recreate a fresh popup per step**
(`cancelPopupWindow()` then `ensurePopup()` then `showInBestPositionFor(editor)` — platform editor-aware
positioning; navigator already put the caret on the step's start line). LESSON for any editor-anchored
JBPopup feature: don't reuse+move a popup across locations; recreate, and use `showInBestPositionFor`
not manual `screenPoint`. Also added `action=cancel` (force teardown→fresh start), `start` REPLACES an
active tour, and `toolStatusLine()` reports lifecycle phase (ACTIVE-generating/ACTIVE-complete/ENDED)
not ambiguous "queue complete".

**⚠ .4 CSS-leak fix (2026-06-11, after .3 in-IDE test):** the callout showed raw CSS text
(`body { margin: 0;}pre {…`). Cause: the `<style>` block was inside `<body>`. **Swing's HTML 3.2
`HTMLEditorKit` only APPLIES a `<style>` that lives in `<head>`** — anywhere else it renders as literal
text. Fix: `WalkthroughMarkdown.toHtml` now emits a full `<html><head><style>…</style></head><body>…</body></html>`
document and `renderStep` sets `bodyPane.text` to it directly (don't re-wrap). LESSON for any JEditorPane
HTML: put `<style>` in `<head>`; inline `style=` attrs work anywhere but `<style>`/`class=` need head.

**Status:** ✅ **MERGED to main** (PR #56 squash → `28e5a5cca`, 2026-06-11). RCs v0.86.0-walkthrough.1–.6
covered the in-IDE smoke (user found+I fixed: can't-type Ask→chat-routing, vanishing callout→popup-per-step,
CSS leak→style-in-head, resizable callout). Merge gate: all 5 CI checks green AFTER deleting the colliding
`WalkthroughFixtureTest` (a 2nd `BasePlatformTestCase` class hit the headless "Indexing timeout" #51 alongside
`EditFilePersistenceFixtureTest`) and replacing it with PURE tests (`validateStepsWith`+`StepFileProbe`,
`clampLineRange`). Worktree `.claude/worktrees/new-feature` + remote branch may still exist (cleanup optional). Worktree `.claude/worktrees/new-feature` alive.
Built via brainstorming→spec→subagent-driven TDD (per-task spec+quality review) + a v2 adversarial-reviewed
plan (`docs/superpowers/plans/2026-06-11-code-walkthrough-v2-ux.md`, gitignored) executed as one coherent
cluster; both rounds had final seam review = SHIP. Spec `docs/superpowers/specs/2026-06-11-code-walkthrough-design.md`.

**Verified:** walkthrough unit suite green (8 classes), `WalkthroughFixtureTest` green in isolation,
`:agent:detekt` green (baseline untouched), `verifyPlugin` green. **PENDING:** CI full `:agent:test`
on an unloaded runner (full single-JVM suite flakes on the pre-existing 2-fixture-class indexing
collision — see [[project_agent_platform_fixture_tests]] 2026-06-11 extension, issue #51) + in-IDE
manual smoke (popup drag, flip-above, loading→auto-advance, Ask round-trip, cancel-mid-tour, new-chat
ends tour, light/dark, plan-mode).

**Key design facts (non-obvious):**
- Sub-agent exclusion is the **name filter** in `SpawnAgentTool.resolveConfigToolsTiered` (both filter
  chains, alongside `render_artifact`), NOT `allowedWorkers` — that gates nothing at the sub-agent boundary.
- `onComplete` calls `markGenerationEnded()` BEFORE the `when(result)` dispatch — the SessionHandoff branch
  early-returns before the cleanup footer, so a footer placement would leak a permanent "Writing next step…" spinner.
- `ask_followup_question` has TWO show callbacks (simple `showSimpleQuestionCallback` + wizard
  `showQuestionsCallback`); BOTH must pause the tour, and the wizard resolve path (`onSubmitted`/`onCancelled`,
  which bypass `executeTask`) must clear it — else a wizard question mid-tour wedges the box.
- `walkthrough` is deferred ("Code Intelligence") and NOT in `WRITE_TOOLS` → plan-mode legal. Interactive-controller
  guard blocks delegated/background runs (they never reach `onComplete`, so auto-finish couldn't hold).
- Steps arrive as a JSON-array **string** (BrainRouter primitive serialization — same trap as `run_maven_goal`
  [[project_tool_param_schema_parser_whitelist_trap]]); validation runs in `readAction` via a `validateSteps(project, steps, resolve)`
  seam using non-refreshing `findFileByPath`.

# Plan Approval Context Clearing

**Goal:** When the user approves a plan, offer a programmatic choice to clear research context before execution — freeing context budget for implementation work.

**Architecture:** Intercept `approvePlan()` to show a system-level question wizard (reusing existing `showQuestions` JCEF bridge), then conditionally clear `ContextManager.messages` while preserving all anchors before feeding the approval instruction into the loop.

---

## Flow

1. User clicks **Approve** on plan card (or plan editor tab)
2. `approvePlan()` sets `pendingApprovalChoice = true`
3. Calls `dashboard.showQuestions()` with single question:
   - **Option A:** "Approve & Clear Context (recommended)" — _Clears research history. Keeps plan, active skill, facts, and guardrails._
   - **Option B:** "Just Approve" — _Keeps full conversation context._
4. Existing `onQuestionsSubmitted` callback checks `pendingApprovalChoice` flag → routes to `handleApprovalChoice()` instead of tool resolution
5. If Option A:
   - `contextManager.clearMessages()` — wipes `messages` list
   - Rebuilds file-read indices (empty)
   - Invalidates token cache
6. Common path: `AgentService.planModeActive.set(false)`, send approval instruction via `executeTask()`
7. LLM resumes with clean (or full) context + all anchors intact

## What's Preserved (Separate Fields/Anchors)

| Data | Storage | Survives clear? |
|------|---------|----------------|
| System prompt | Rebuilt each turn by `PromptAssembler` | Yes |
| Active skill (writing-plans) | `ContextManager.activeSkillContent` | Yes |
| Plan markdown | `ContextManager.activePlanPath` + `plan.md` on disk | Yes |
| Facts store | `factsAnchor` (compression-proof) | Yes |
| Guardrails | `guardrailsAnchor` (compression-proof) | Yes |
| Core memory | Loaded from disk (`core-memory.json`) | Yes |
| Task progress | `ContextManager.taskProgressMarkdown` | Yes |

## What Gets Cleared

- All `read_file` results from research phase
- `search_code` / `find_references` / `find_definition` exploration
- Plan discussion messages (user ↔ assistant back-and-forth)
- `plan_mode_respond` iterations and revisions
- Any compaction summaries (`lastSummary`)

## Files Modified

| File | Change |
|------|--------|
| `AgentController.kt` | Add `pendingApprovalChoice` flag, modify `approvePlan()` to show question, add `handleApprovalChoice()` |
| `ContextManager.kt` | Add `clearMessages()` method |

## Edge Cases

- **Plan editor tab Approve button**: Wired to same `::approvePlan` — gets this behavior for free
- **No active question conflict**: Loop is suspended on `userInputChannel` during the choice — `AskQuestionsTool` has no pending deferred, so the callback routing via `pendingApprovalChoice` flag is safe
- **Clear + empty context**: After clearing, the first LLM call gets system prompt + anchors + approval message — enough context to proceed

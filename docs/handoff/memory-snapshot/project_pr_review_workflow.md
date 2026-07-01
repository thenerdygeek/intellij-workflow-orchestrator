---
name: PR Review Workflow — shipped in v0.83.14-beta (Phases 0-4 of 6)
description: Complete context for the PR review workflow feature. Covers what shipped, where code lives, pending polish items, and how to pick up improvements or bug fixes in a new session
type: project
originSessionId: 87f699de-c64e-4b0f-91f4-53890c45be4f
---
# PR Review Workflow — Shipped Status (2026-04-22)

## Released

**v0.83.14-beta** on branch `feature/telemetry-and-logging` at commit `c6ab45f9`.
Release: https://github.com/thenerdygeek/intellij-workflow-orchestrator/releases/tag/v0.83.14-beta

## Scope

5-phase project (Phases 0-4 shipped; Phase 5 polish and Phase 6 release pending). Feature: Bitbucket PR comment workflow + AI-driven code review with local findings staging, integrated into the plugin's existing PR tab.

## What a new session needs to read first

1. **Design spec (authoritative)**: `docs/superpowers/specs/2026-04-22-pr-review-workflow-design.md`
2. **Phase 0 audit**: `docs/superpowers/audits/2026-04-22-bitbucket-tools-audit.md` + `-details.md` + `-decision-memo.md`
3. **Completion docs per phase**:
   - `docs/superpowers/audits/2026-04-22-phase1-completion.md`
   - `docs/superpowers/audits/2026-04-22-phase2-completion.md`
   - `docs/superpowers/audits/2026-04-22-phase3-completion.md`
   - `docs/superpowers/audits/2026-04-22-phase4-completion.md`
4. **Plans (all executed)**: `docs/superpowers/plans/2026-04-22-pr-review-phase{0,1,2,3,4}-*.md`
5. **Reference memory**: `reference_bitbucket_pr_comment_api.md` (DC + Cloud API endpoints) and `reference_bitbucket_tools_audit_result.md` (Phase 0 summary)

## Architecture (short version)

- **`:core`**: `BitbucketService` (interface + 6 new comment methods); `PrComment` domain model + `PrCommentState/Severity/Anchor` types at `core/model/PrComment.kt`; `PrReviewFinding`, `FindingSeverity`, `AnchorSide`, `PrReviewFindingsStore` + `PrReviewFindingsStoreImpl` at `core/prreview/`; new `WorkflowEvent.PrCommentsUpdated`
- **`:pullrequest`**: `BitbucketServiceImpl` (implements 6 new comment methods + mapper + Phase-0 fixes); `PrReviewTaskBuilder`, `PrReviewSessionRegistry` at `pullrequest/service/`; UI at `pullrequest/ui/`: `CommentsViewModel`, `CommentsTabPanel`, `CommentRowRenderer`, `AiReviewViewModel`, `AiReviewTabPanel`, `FindingRowRenderer`, modified `PrDetailPanel` with tabs
- **`:agent`**: `bitbucket_review` extended with 6 new actions in `agent/tools/integration/BitbucketReviewTool.kt`; new `ai_review` meta-tool at `agent/tools/builtin/AiReviewTool.kt` (registered in `AgentService`, added to `AgentLoop.HOOK_EXEMPT`); `code-reviewer` persona updated at `agent/src/main/resources/agents/code-reviewer.md`; snapshot regenerated at `agent/src/test/resources/subagent-prompt-snapshots/code-reviewer-intellij-ultimate.txt`; `AgentController.startPrReviewSession` wrapper; `AgentChatRedirect` EP extended so `:pullrequest` can start a session without depending on `:agent`

## Key Phase-0 findings that informed implementation

- 9 defects fixed (6 F-HIGH + 3 F-MED): `fileType` hardcoding, missing `srcPath`, pagination loops on changes/activities/commits, username threading on `get_my_prs`/`get_reviewing_prs`, `approved` field on reviewer status, `merge_pr` strategy names, diff size cap
- `bitbucket_review` is NOT in `AgentLoop.APPROVAL_TOOLS` — write actions inherit no approval gate (consistent with existing 3 write actions; design choice to revisit later if needed)
- `BitbucketServiceImpl` is platform-dependent — not directly unit-testable; service-layer logic verified via client-layer MockWebServer tests + code inspection
- `ToolResult` construction idiom: `ToolResult.success(data, summary)` for success; `ToolResult(data=..., summary="msg", isError=true)` for errors — no `.error()` factory
- `BitbucketService.addPrComment` / `addInlineComment` take `(prId: Int, ..., repoName: String? = null)` — NO `projectKey`/`repoSlug` — unlike Phase 1 comment methods which do take them (inconsistency predates Phase 1)

## Phase 5 polish items (deferred — pick any for a new session)

1. **Jira ticket resolution** in `PrReviewTaskBuilder` — currently passes `jiraTicket = null`. `JiraTicketProvider.getTicketContext(key)` is the API in `:core`; PR key parsing from branch/title is the missing piece. Not blocking.
2. **DC 409 3-way merge modal** — `editPrComment` already surfaces `STALE_VERSION` error; UI needs a modal that fetches latest + shows ours/theirs/original diff.
3. **Streaming finding updates** — MVP uses manual refresh in AI Review tab. SmartPoller wire-up (mirror `CommentsTabPanel`) would give near-live updates.
4. **Unread-count badges on PrListPanel rows** — `WorkflowEvent.PrCommentsUpdated` already publishes `unreadCount` (currently always 0). Wire up last-view timestamp tracking in `PrListPanel`.
5. **Snapshot viewer** — for inline comments, a "View original" button that shows side-by-side diff at `fromHash`/`toHash`. Not in MVP.
6. **Second-review archival** — "Run again" flow currently creates a new session; prior-session findings should be visually archived + accessible via a "Show prior runs" dropdown.
7. **Inline finding editing in AI Review tab** — MVP supports push/discard only.
8. **Edit/delete comment UI in Comments tab** — service methods ready; UI buttons not wired.
9. **`bitbucket_review` write-action approval** — entire `bitbucket_review` tool could be added to `AgentLoop.APPROVAL_TOOLS` for defense-in-depth.

## Known UI risks (report if you see them)

These are most likely places for bugs because they're Swing and untested end-to-end:
- `PrDetailPanel` toggle-button layout after Phase 3 refactor — 5 toggles (Description, Activity, Files, Commits, AI Review) + Comments was added as 5th or 6th; verify selection state + card swapping
- `CommentsTabPanel` starts poller on `componentShown` — verify actual visibility triggers it; may need to listen to toggle-button selection instead
- `AiReviewTabPanel` rebuilds when PR changes — verify no leaks from `scope.cancel()`
- `Run AI review` button currently tries to activate the `Workflow` tool window — verify that's the correct tool window ID for focusing the agent chat tab
- `addPrComment` / `addInlineComment` return `ToolResult<Unit>` so `markPushed` stores empty string as `bitbucketCommentId` — findings are marked pushed but without a click-through URL

## Pending: Phase 6 already done for this version

Released as v0.83.14-beta. For next release after improvements: bump to 0.83.15-beta via `gradle.properties`, clean build, `gh release create` with ZIP.

## How to continue in a new session

If user reports a UI bug: read this memory + the referenced plan / completion doc for the phase where the bug lives, inspect the specific component, fix in place, run the relevant module test, commit with fix prefix. Don't re-audit — Phase 0 work is authoritative.

If user asks for a Phase 5 polish item: pick from the list above, write a focused plan in `docs/superpowers/plans/` if >3 tasks, execute via subagents.

Commit branch is `feature/telemetry-and-logging` — not `main`. Remote is `thenerdygeek/intellij-workflow-orchestrator`.

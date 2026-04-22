# Phase 4 Completion Summary (2026-04-22)

## Work landed

- `PrReviewTaskBuilder` — assembles initial prompt (diff cap at 327_680 chars + Jira block + seeded task)
- `PrReviewSessionRegistry` — disk-backed prId→sessionId map, restart-safe, atomic writes
- `AiReviewViewModel` — headless findings orchestration + push to Bitbucket + discard
- `AiReviewTabPanel` + `FindingRowRenderer` — Swing UI for findings list + action buttons (run, refresh, push selected, push all kept, discard)
- `PrDetailPanel` — Run AI review button wired end-to-end; `AiReviewSubPanel` placeholder replaced
- `AgentChatRedirect.startPrReviewSession` added to core interface (default body for backward compat)
- `AgentChatRedirectImpl` + `AgentController.startPrReviewSession` wrapper — cross-module session start

## Architecture decisions

- **Cross-module call path:** `:pullrequest` → `AgentChatRedirect` EP (`:core`) → `AgentChatRedirectImpl` (`:agent`) → `AgentController.startPrReviewSession`. Preserves the no-cross-module-dependency rule.
- **markPushed bitbucketCommentId:** `addPrComment`/`addInlineComment` both return `ToolResult<Unit>` (no comment ID). The field is set to `""` for MVP; Phase 5 polish can wire a richer API if a POST that returns the created comment is added.
- **`aiReviewTabPanel` is lazily rebuilt** per PR, same pattern as `commentsTabPanel`. Disposed in `PrDetailPanel.dispose()`.
- **Plugin.xml:** `PrReviewSessionRegistry` and `PrReviewFindingsStore`/`PrReviewFindingsStoreImpl` registered as project services.

## Deferred to Phase 5 polish

- Jira ticket resolution in the builder (currently `jiraTicket = null`)
- DC 409 3-way merge modal on edit
- Streaming finding updates (MVP polls on Refresh click)
- Unread badges on PR list
- Snapshot viewer for inline comments
- Second-review archival
- Per-persona system-prompt switching (persona guidance is embedded in initialMessage for MVP)

## Manual verification required

User should runIde and confirm:
1. Selecting a PR → AI Review tab shows "No AI review run for this PR yet." empty state
2. "Run AI review" prompts confirm → kicks off session → Agent tab shows new session running
3. After session completes, findings appear in AI Review tab (click Refresh)
4. "Push selected" and "Push all kept" post comments to Bitbucket
5. "Discard selected" removes finding from the active list

## Gate results

- `:core:test` — BUILD SUCCESSFUL
- `:pullrequest:test` — BUILD SUCCESSFUL (13 tests: 5 PrReviewTaskBuilder + 4 PrReviewSessionRegistry + 4 AiReviewViewModel + existing tests)
- `:agent:test` — 3025 tests completed, 1 failed (pre-existing `RunCommandStreamingWiringTest` failure only)
- `verifyPlugin` — BUILD SUCCESSFUL (3 IDE variants)
- `buildPlugin` — BUILD SUCCESSFUL

## Commits

1. `a0c7ef4d` feat(pullrequest): PrReviewTaskBuilder — assembles PR review initial prompt
2. `2efff428` feat(pullrequest): PrReviewSessionRegistry — disk-backed prId→sessionId map
3. `39479288` feat(ui): AiReviewViewModel — findings refresh + push + discard
4. `4c101338` feat(ui): AiReviewTabPanel + FindingRowRenderer — findings list + push/discard
5. `6a09aba8` feat(agent): add startPrReviewSession wrapper to AgentController
6. `58334934` feat(ui): Run AI review button — builds prompt + starts agent session

## Next step

Phase 5 polish — Jira integration in builder, streaming findings, unread badges, second-review archival.

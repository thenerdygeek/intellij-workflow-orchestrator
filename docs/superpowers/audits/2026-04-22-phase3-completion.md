# Phase 3 Completion Summary (2026-04-22)

## Work landed

- `CommentsViewModel` — headless state + service orchestration (5 unit tests + 1 EventBus test)
- `CommentRowRenderer` — JBList cell renderer with author, timestamp, state pill, optional anchor
- `CommentsTabPanel` — Refresh / Reply / Toggle Resolved / Post-general buttons; JBList + bottom form; SmartPoller (30s/1.5×/5m) starts on componentShown, stops on componentHidden
- `PrDetailPanel` — Comments toggle-button tab added to existing toggle row (Description / Activity / Files / Commits / **Comments** / AI Review); `rebuildCommentsTab()` called on each `showPr()` and `showPrDetail()`; `CommentsTabPanel` closed on `dispose()`
- `WorkflowEvent.PrCommentsUpdated` — new event type, published on every successful refresh (unreadCount=0 for MVP)

## Architecture notes

- `CommentsViewModel` is framework-free (no Swing imports). `EventBus` injected as optional param for testability.
- `EventBus` is a project-level service (`project.getService(EventBus::class.java)`). Tests instantiate directly (`EventBus()`).
- `addPrComment` / `replyToComment` on `BitbucketService` take `prId: Int, text: String, repoName: String?` — not the `projectKey/repoSlug/prId/text` pattern used by the Phase 1 comment-list methods. Adapted accordingly.
- `SmartPoller` constructor: `(name, baseIntervalMs, maxIntervalMs, scope, action: suspend () -> Boolean)`. Action returns `true` if data changed.
- `CommentsTabPanel` uses both `poller.start()/stop()` and `poller.setVisible(true/false)` on visibility change for correct backoff behaviour.

## Deferred to Phase 5 polish

- Unread-count tracking + badges on PrListPanel rows
- Snapshot viewer (side-by-side diff)
- DC 409 3-way merge modal on edit
- Threading UI beyond flat list
- Filtering toolbar
- Keyboard shortcuts
- Optimistic local updates (MVP refreshes after each action)

## Manual verification required

User should `runIde` and confirm:
- Selecting a PR in the PR tab shows the tabbed detail panel with Comments toggle
- Switching to Comments tab loads data (or shows error if Bitbucket not configured)
- Reply / Toggle Resolved / Post-general actions work end-to-end
- Switching away from Comments tab stops the poller; switching back starts it

## Gate results

- `:core:test` — BUILD SUCCESSFUL
- `:pullrequest:test` — BUILD SUCCESSFUL (6 tests in CommentsViewModelTest)
- `:agent:test` — 3025 tests, 1 pre-existing failure (`RunCommandStreamingWiringTest`) only
- `verifyPlugin` — SUCCESS (pre-existing override-only warnings, unrelated to Phase 3)
- `buildPlugin` — ZIP produced

## Commit SHAs

| Task | SHA | Message |
|---|---|---|
| 1 | `2d3291c8` | feat(ui): CommentsViewModel — headless state + service orchestration |
| 2 | `3ad2fba8` | feat(ui): CommentRowRenderer for JBList |
| 3 | `68a43c4d` | feat(ui): CommentsTabPanel — JBList + refresh/reply/resolve/post actions |
| 4 | `807dd130` | feat(ui): PrDetailPanel tabbed layout — Overview / Comments / AI Review |
| 5 | `7eb2f158` | feat(ui): SmartPoller auto-refresh on Comments tab visibility |
| 6 | `3fab8ba3` | feat(ui): CommentsViewModel publishes PrCommentsUpdated event on refresh |

## Next step

Plan 4 (Phase 4): AI Review sub-tab + "Run AI review" button + `PrReviewTaskBuilder` + `PrReviewSessionRegistry`.

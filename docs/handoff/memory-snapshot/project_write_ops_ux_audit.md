---
name: 2026-05-07 write-ops UI-parity audit (Jira+Bitbucket+Bamboo)
description: Read-only audit of every plugin write op vs. probe data + native-UI parity. 17 findings, 2 P0, 9 P1. Sonar excluded (shipped), Nexus deferred.
type: project
originSessionId: 274de309-f044-421c-a300-450bfa0a16f8
---
# 2026-05-07 Write-Ops UI-Parity Audit

Index doc: `docs/research/2026-05-07-write-ops-ux-audit.md`
Per-service docs:
- `docs/research/2026-05-07-jira-write-ops-audit.md` (DC 10.3.16)
- `docs/research/2026-05-07-bitbucket-pr-write-ops-audit.md` (DC 9.4.16)
- `docs/research/2026-05-07-bamboo-write-ops-audit.md` (DC 10.2.14)

## Headline findings (P0)

1. **Bamboo `triggerBuild` silently drops user variables** — `bamboo/.../api/BambooApiClient.kt:142-162` posts JSON body; server expects `?bamboo.variable.X=Y` query params. Returns 200, ignores input. Affects manual trigger AND `:automation` queue trigger.
2. **Bitbucket GET→PUT version race** — `addReviewer`/`removeReviewer`/`updateTitle`/merge in `pullrequest/.../service/PrActionService.kt` read `version` once and never refresh on 409.

## Headline findings (P1)

3. **Jira `startWork` bypasses MissingFields preflight** — `jira/.../service/JiraServiceImpl.kt:650` posts `fields={}`. Dialog path uses `TicketTransitionService.executeTransition` correctly; shortcut path doesn't.
4. **Comment visibility unconfigured** — `QuickCommentPanel` + handover `JiraCommentPanel` post `{body}` only.
5. **`TimeLogPanel` discards user-picked date** — `JiraService.logWork` signature drops it.
6. **Default reviewers ignore ref-matchers** — `core/.../BitbucketBranchClient.kt:107` DTO missing `sourceRefMatcher`/`targetRefMatcher`; `:1075` unions all conditions.
7. **AI inline comments unpinned** — no `diffType`/`fromHash`/`toHash`, so EFFECTIVE-anchored comments float on new commits.
8. **`PrDetailPanel.showAddReviewerPopup` skips repo-permission filter** — line 1648 calls `getUsers(query)` instead of `getUsers(query, projectKey, repoSlug)`.
9. **`ManualStageDialog` discards `isPassword`** — secrets shown/submitted as plain text.
10. **`StageListPanel` enables Run for all manual stages** — ignores plan stage order.
11. **"Rerun Failed Jobs" lacks confirmation, X-Atlassian-Token header, and proper status check** — accepts `200..399` so HTML auth-redirect = success.

## Gold-standard surfaces (do not regress)

- Sprint-tab `TicketTransitionDialog` (`expand=transitions.fields` + per-widget validation + `JiraSearchService` autocomplete)
- Agent `jira(action="transition")` (typed `MissingFields` payload to LLM)
- `CreatePrDialog.searchUsers` (REPO_READ filter + 300 ms debounce)
- `ManualStageDialog.init` preflight (fetch is correct, only submission is broken)

**Why:** User asked to verify our write paths match what Atlassian web UIs do — autocomplete, required-field enforcement, conflict detection. Audit caught silent failures that wouldn't surface in tests because servers return 200 to malformed inputs.

**How to apply:** Don't propose new write ops without preflight. If you see a "shortcut" wrapper around a transition/PR action, verify it goes through the same preflight as the dialog path, otherwise it's a silent rule-violation surface. Recommend a one-shot `--writes-allowlist` Bamboo probe before fixing finding #1, since the read-only probe didn't exercise mutations.

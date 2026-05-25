# Queued during Phase 6a-2 (security P1s)

Issues observed but NOT fixed per "do not fix outside these 5" constraint.

---

## sonar: incomplete HTML strip still in `HtmlEscape.escapeHtml` downstream callers

`IssueDetailPanel.kt:192-194` (F-1) already has `HtmlEscape.escapeHtml` applied to
`issue.message`, `issue.severity`, and `hotspot.message` — these appear to have been
addressed in a previous pass. The F-11 fix here was specifically the `displayRuleInfo`
regex being incomplete (`<[^>]*>` → `<.*?>` with DOT_MATCHES_ALL).

**No incidental changes needed. Existing F-1 test confirms coverage.**

---

## pullrequest: `CommentsViewModel._comments` unsynchronized MutableList (F-10)

The `_comments: MutableList<PrComment>` in `CommentsViewModel.kt:26` is a standard
`ArrayList`. Concurrent `refresh()` calls from the poller and user click can race on
`_comments.clear() + addAll()`. The fix (F-10) is to swap to an atomic list reference.
**Queued — not in scope for this phase.**

---

## jira: `searchIssues` has similar JQL injection risk to F-12

`JiraApiClient.searchIssues` (line 104) builds JQL from `text` with `escapeJql` but
does not have a length cap or control-char rejection. Lower risk than `searchTickets`
because it goes through a server-side query builder rather than raw forwarding.
**Queued — evaluate in a follow-up audit pass.**

---

## agent-ui: AgentPlanEditor + ToolDocsEditor have same F-7 load-handler leak

The F-7 fix was applied only to `AgentVisualizationEditor`. The audit notes the
same anonymous `addLoadHandler`/no-`removeLoadHandler` pattern in
`AgentPlanEditor.kt:101-107` and `ToolDocsEditor.kt:89-95`. Apply the same
named-field + `removeLoadHandler` in `dispose()` pattern to both files.
**Queued — observed during Phase 6b F-7 fix, same fix pattern as F-7.**

---

## PromptBodyRedactor: `***REDACTED***` vs `[REDACTED]` marker inconsistency

The new diff-specific patterns (AWS key, PEM header, assignment secret) use `[REDACTED]`
while the legacy JSON/auth patterns use `***REDACTED***`. This was a deliberate choice to
avoid breaking existing tests, but a future cleanup commit could normalize to one marker
(update SourcegraphDebugRedactionTest to match).
**Queued — cosmetic, low priority.**

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

## sonar: IssueListPanel.fixWithAgent still uses project.basePath (same F-13 pattern)

`IssueListPanel.kt:321-322` — `fixWithAgent(issue: MappedIssue)` builds the absolute path
via `File(project.basePath, issue.filePath)`.  `MappedIssue` already carries `projectKey`,
so the fix is identical to the F-13 fix in `IssueDetailPanel`: call `resolveSonarRepoRoot`
(or an equivalent helper in `IssueListPanel`) with `(issue.filePath, issue.projectKey)`.
Observed during Phase 6d-3 F-12/F-13 pass; out of scope for this phase.
**Queued — apply same pattern as IssueDetailPanel.resolveSonarRepoRoot.**

---

## PromptBodyRedactor: `***REDACTED***` vs `[REDACTED]` marker inconsistency

The new diff-specific patterns (AWS key, PEM header, assignment secret) use `[REDACTED]`
while the legacy JSON/auth patterns use `***REDACTED***`. This was a deliberate choice to
avoid breaking existing tests, but a future cleanup commit could normalize to one marker
(update SourcegraphDebugRedactionTest to match).
**Queued — cosmetic, low priority.**

---

## RESOLUTION (2026-05-25, Tier-A incidentals pass)

- **jira searchIssues JQL** — FIXED. `JiraApiClient.searchIssues` now caps free-text length (`MAX_SEARCH_TEXT_LENGTH=500`) and rejects control chars before building JQL (mirrors jira:F-12).
- **agent-ui AgentPlanEditor + ToolDocsEditor F-7 leak** — FIXED. Both now hold the `CefLoadHandlerAdapter` in a named field and call `removeLoadHandler(loadHandler, cefBrowser)` in `dispose()` (same as AgentVisualizationTab).
- **sonar IssueListPanel.fixWithAgent** — FIXED. Now routes through a `resolveSonarRepoRoot` wrapper over `IssueDetailPanel.resolveRepoRoot` instead of `project.basePath` (multi-repo correctness).
- **pullrequest CommentsViewModel `_comments`** — ALREADY DONE. Class already uses `commentsMutex` + snapshot `comments` (KDoc cites the F-10 fix).
- **PromptBodyRedactor marker inconsistency** — deferred (Tier D, cosmetic).

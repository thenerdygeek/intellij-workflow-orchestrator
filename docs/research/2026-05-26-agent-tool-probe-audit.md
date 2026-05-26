# Agent integration-tool audit vs probe behavior (2026-05-26)

Triggered after fixing three Jira agent-tool bugs (get_sprint_issues literal `currentUser()`
match; my_worklogs/get_worklogs `+0000` datetime parse; my_worklogs displayName-vs-username).
Four read-only Opus audits (jira / bamboo / sonar / bitbucket) hunted the same failure classes:

- **A** server-side filter bypassed/re-implemented client-side
- **B** sentinel not resolved (literal compare)
- **C** datetime/number/format parse mismatch
- **D** identity field mismatch (displayName vs username vs key/id)
- **E** inert schema param (declared, never read)
- **F** deviation from probe-confirmed endpoint/param/field

Verdicts are the auditors' analysis (read-only); CONFIRMED still benefits from a live smoke.

## CONFIRMED (will misbehave)

1. **Jira `get_worklogs` author filter — Pattern D (structural).** `WorklogData` keeps only
   `displayName` (`JiraServiceImpl.kt:462` maps `w.author?.displayName`; model `JiraModels.kt:167`).
   The `author` param is documented as "username (DC) / accountId (Cloud)" but those can never
   match → 0 results. Same class as the my_worklogs fix. Fix: carry `name`/`accountId` in
   `WorklogData`, match against all.
2. **Jira `get_worklogs`/`my_worklogs` — Pattern A: 20-row page cap before filter.**
   `JiraApiClient.getWorklogs` defaults `maxResults=20`; date/author filter runs on that truncated
   page → silently misses worklogs on tickets with >20 entries. Fix: page to exhaustion (or raise cap)
   when a filter is supplied.
3. **Bamboo `get_running_builds` — Pattern A/B.** `BambooApiClient.kt:273` calls
   `?includeAllStates=true&max-results=5` then client-side keeps `lifeCycleState in [InProgress,Queued,Pending]`.
   `max-results=5` is applied *before* the filter → a running build outside the 5 most-recent is dropped
   ("no running builds" when one runs). Fix: drop `includeAllStates`, or exclude terminal states
   (`Finished`/`NotBuilt`) and raise/paginate the cap.
4. **Sonar `current_user` isAdmin — Pattern D (one-liner).** `SonarModels.kt:400`
   `isAdmin = globalPermissions.isNotEmpty()`. A token with only `scan` reports admin, contradicting the
   tool's own "403 on analysis_tasks ⇒ non-admin" guidance. Fix: `globalPermissions.contains("admin")`.
5. **Bitbucket `get_blocker_comment_count` — Pattern E/C.** `BitbucketServiceImpl.kt:1160` reads
   `result.data.size`; DC's `?count=true` returns `{"count":N}` (no `size`) → always 0. The existing test
   pins a hand-written `{"size":3}` mock, not probe truth. Fix: add `count` to the DTO, prefer
   `count ?: values.size`; re-probe the live body to confirm the field name.

## SUSPECTED (verify against a live server)

6. **Jira `parseStartedDateTime` bare-date — Pattern C.** Bare `YYYY-MM-DD` promoted to start-of-day at
   the *local IDE* offset, then compared as an instant against worklog `started` in the *Jira* offset →
   off-by-one-day at since/until boundaries when zones differ. Fix: compare on `LocalDate` for bare bounds.
7. **Sonar `branch_quality_report` gate recompute — Pattern A/B (higher impact).** `SonarServiceImpl.kt:905`
   ignores the server `projectStatus.status` and recomputes the gate from conditions whose `metricKey`
   starts with `new_`; a failing condition not so prefixed is dropped → reports passing when failing.
   Fix: trust the server status; treat the `new_` partition as presentation only.
8. **Sonar `branch_quality_report` duplication self-match — Pattern D.** `SonarServiceImpl.kt:1104` matches
   dup blocks by `key == componentKey OR name == basename`; basename collides across modules → wrong/all
   blocks attributed, inflating `duplicatedLineRanges`. Fix: match strictly on `key`.
9. **Sonar `source_lines` / branch report — Pattern F.** No `from`/`to` range → SonarQube caps lines
   server-side; big files truncate silently (probe always sends a range). Fix: page or derive range.
10. **Bitbucket `add_inline_comment` — Pattern F.** Tool never passes `diffType`/`toHash` though the
    plumbing exists; agent comments use `EFFECTIVE` default and float off their line when commits land.
    Fix: pass `diffType=COMMIT` + `toRef.latestCommit`, or expose the params.
11. **Bitbucket `merge_pr` — Pattern F (minor).** No pre-validation against `getMergeStrategies`
    (which has the correct repo→project 404 fallback) → opaque 400 on a disabled strategy.

## MINOR / presentation

12. Bamboo `repo_name` inert on get_plan_branches/get_running_builds (Pattern E) — advertises filtering
    that never happens (service drops the arg).
13. Bamboo `get_build` commit `date` fetched + mapped but never rendered.
14. Bamboo `result_key` accepted as undocumented alias on stop/cancel/get_artifacts.
15. Sonar `issues` hardcodes `status="OPEN"` (flattens CONFIRMED/REOPENED); inconsistent with issues_paged.
16. Sonar `issues` `file` filter assumes `projectKey:path`; breaks in multi-module (no module segment).
17. Bitbucket raw epoch-millis leak: `PrComment` + `ParticipantData` have no `toString()`, so
    get_comment/list_comments/get_pr_participants surface raw millis + nested objects to the LLM.
18. Bitbucket `get_my_prs`/`get_reviewing_prs` use per-repo role filter (server-side, correct) but only the
    active repo — intentional single-repo scope, not the dashboard endpoint. Capability gap, not a bug.
19. Sonar UI `Instant.parse` of `+0000` (QualityDashboardPanel/IssueDetailPanel/IssueListPanel) is latent
    but try/catch-guarded and out of the agent path.

## Cross-tool takeaways
- **Worklog identity/paging (Jira #1,#2)** is the same class we just fixed and is the most certain.
- **Sonar (#4) and Bamboo (#3)** each have one clear CONFIRMED logic bug.
- **Bitbucket** is the cleanest (probe P0s already fixed); #5 is the only CONFIRMED and needs a body re-probe.
- Sonar/Bitbucket largely avoid Pattern C because they keep server timestamps as raw strings/Long and
  don't parse them in the agent path — the Jira tool parsed, which is why it broke.

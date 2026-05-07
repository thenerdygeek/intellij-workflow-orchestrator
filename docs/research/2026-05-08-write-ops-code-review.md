# PR 1-7 Code Review — 2026-05-08

## Summary

Reviewed 7 commits fixing the 2026-05-07 write-ops audit findings across Bamboo, Bitbucket, Jira, and the Automation tab.
**0 P0** (ship-blocker) findings, **2 P1** (fix-soon) findings, **3 P2** (nice-to-have) findings.

The headline finding — Bamboo's `queueBuild` silently dropping every variable for months — is correctly fixed via
`HttpFormPost` + `FormBody.Builder`, with a wire-shape integration test confirming the URL-encoded round-trip
including JSON special characters. The `modifyPullRequest` retry-on-409 path is also solid: cache invalidation happens
in the right place, between attempt 1 and attempt 2. The `SingleCallerOfTransitionIssueTest` architectural guard is
well-constructed and would catch a direct-bypass regression.

**Verdict: ship with the two P1 items tracked for immediate follow-up.** Neither P1 blocks functionality today —
the merge precondition mis-diagnosis produces a misleading error message (not a silent failure) and the parallel
fetch runs sequentially (slower but correct). Both should be fixed in the next cycle.

---

## P0 findings (ship-blockers)

None.

---

## P1 findings (fix soon)

### P1-1 — Merge 409 precondition failure mis-diagnosed as version race

**File:** `core/src/main/kotlin/com/workflow/orchestrator/core/bitbucket/BitbucketBranchClient.kt:1998-2005`

**Issue:** `mergePullRequest()` maps every 409 to `VALIDATION_ERROR` with the message
`"PR #prId version conflict or merge preconditions not met — refresh and retry"`.
`fetchAndMerge()` then promotes any `VALIDATION_ERROR` whose `.message.contains("version conflict")`
to `STALE_VERSION` to trigger the retry. Because the hardcoded message contains "version conflict"
in **all** 409 cases (version race AND merge veto), a real precondition failure (e.g., required CI
build is red, PR has unresolved blockers) causes one unnecessary refetch+retry. After the retry
also fails with 409, `mergePullRequestWithRetry` returns `STALE_VERSION` and the UI shows
`"PR was updated by someone else — refresh and try again"`, which is factually wrong and will
confuse the user.

This is the same structural problem as the analogous `fetchAndPut` path for PR modifications,
except `updatePullRequest`'s 409 message only says "version conflict" (no "or preconditions"),
so that path is unaffected.

**Repro:** configure a Bitbucket PR with a required build status that is failing; attempt to
merge in the plugin. The user sees "updated by someone else" instead of "merge preconditions not met".

**Fix:** Either (a) parse the Bitbucket 409 body (`errors[*].message`) to distinguish "stale version"
from "veto precondition" before translating to `STALE_VERSION`, or (b) prefer a sentinel
`ErrorType.MERGE_PRECONDITION` for the `"merge preconditions"` half of the 409. The simplest
safe fix is to change the `mergePullRequest()` 409 mapping to a narrower message (`"PR version
conflict"`, dropping the "or merge preconditions" clause) and keep the `fetchAndMerge` check as-is,
accepting that real precondition failures will still cause one spurious retry but at least the
final message will be STALE_VERSION rather than a wrong claim about concurrent editing.
The correct long-term fix is to inspect the Bitbucket error body.

```kotlin
// mergePullRequest() line 1919 — current (causes retry on all 409s):
409 -> ApiResult.Error(ErrorType.VALIDATION_ERROR,
    "PR #$prId version conflict or merge preconditions not met — refresh and retry")

// Minimal fix: make the message unambiguously a version-race signal so only
// version races get promoted to STALE_VERSION:
409 -> {
    val body = it.body?.string().orEmpty()
    if (body.contains("stale", ignoreCase = true) ||
        body.contains("version", ignoreCase = true)) {
        ApiResult.Error(ErrorType.VALIDATION_ERROR,
            "PR #$prId version conflict — refresh and retry")
    } else {
        ApiResult.Error(ErrorType.VALIDATION_ERROR,
            "PR #$prId cannot be merged — check merge preconditions")
    }
}
```

The test at `BitbucketBranchClientMergeWithRetryTest` only covers version-conflict 409 bodies;
a test for a precondition-failure 409 (different error body) should be added.

---

### P1-2 — `getCommentVisibilityOptions` parallel fetch is actually sequential

**File:** `jira/src/main/kotlin/com/workflow/orchestrator/jira/service/JiraServiceImpl.kt:1741-1742`

**Issue:** The implementation wraps each `async { }` block in its own `coroutineScope { }`:

```kotlin
val rolesDeferred = coroutineScope { async { api.getProjectRoles(projectKey) } }
val groupsDeferred = coroutineScope { async { api.getRawString("/rest/api/2/groups/picker?query=") } }
val rolesResult = rolesDeferred.await()
val groupsResult = groupsDeferred.await()
```

`coroutineScope { }` is a suspending function that does not return until all child coroutines complete.
So line 1741 launches the roles async job, `coroutineScope` waits for it to finish, and only then
does line 1742 start the groups job. The two network calls execute **serially**, not in parallel,
adding one full round-trip latency to every first-load of the comment visibility dropdown.

The code comment (`// Roles: ... Groups: ... in parallel`) and the `core/CLAUDE.md` entry
(`fetches /project/{key}/role + /groups/picker in parallel`) are both incorrect.

**Fix:**

```kotlin
val (rolesResult, groupsResult) = coroutineScope {
    val rolesDeferred = async { api.getProjectRoles(projectKey) }
    val groupsDeferred = async { api.getRawString("/rest/api/2/groups/picker?query=") }
    rolesDeferred.await() to groupsDeferred.await()
}
```

This is functionally equivalent for the caller (same results, same error-handling logic below)
but issues both HTTP requests concurrently. On a Jira server with 50ms per-request latency,
the current code spends 100ms; the fixed version spends ~50ms.

---

## P2 findings (nice to have)

### P2-1 — `triggerBuild` and `triggerManualStage` miss `AUTH_REDIRECT` hint in `BambooServiceImpl`

**File:** `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BambooServiceImpl.kt:165-173, 390-395`

`stopBuild` and `rerunFailedJobs` explicitly handle `ErrorType.AUTH_REDIRECT` in their `when` hint
branches with the actionable message `"Your Bamboo session has expired — re-authenticate in Settings."`.
`triggerBuild` and `triggerManualStage` fall through to `else -> "Check Bamboo connection in Settings."`.

The user still sees the correct `summary` text from `postForm` (`"Server returned HTML — your session
may have expired. Re-authenticate in Settings."`), so this is not a silent failure — just a less
tailored `hint`. Add `ErrorType.AUTH_REDIRECT -> "Your Bamboo session has expired — re-authenticate in Settings."` to both `when` blocks.

---

### P2-2 — Module CLAUDE.md files not updated for write-ops architecture changes

**Files:** `jira/CLAUDE.md`, `bamboo/CLAUDE.md`

Per `feedback_update_docs_immediately.md`: docs must be updated in the same commit as architecture changes.
`core/CLAUDE.md` was correctly updated (in commits e65f7de4 and 02db7724).
`jira/CLAUDE.md` has no mention of `TicketTransitionService` as the canonical transition write path (PR 4 finding),
nor the `addComment` visibility/`logWork started` changes (PR 5).
`bamboo/CLAUDE.md` has no mention of the `queueBuild` → `postForm` form-encoding fix (PR 2) or the
`X-Atlassian-Token` header requirement on PUT/DELETE writes (PR 7).

These are documentation gaps only — no production behavior is affected.

---

### P2-3 — `RefMatcher.matches` for `PATTERN` type compiles the same regex twice

**File:** `core/src/main/kotlin/com/workflow/orchestrator/core/bitbucket/BitbucketBranchClient.kt:168-169`

```kotlin
RefMatcherType.PATTERN -> globToRegex(id).matches(displayBranch) ||
    globToRegex(id).matches(branch)
```

`globToRegex(id)` constructs a new `Regex` object on each call. For the common case
(`displayBranch` matches), this compiles the pattern twice per `matches()` invocation.
Extract the result: `val r = globToRegex(id); r.matches(displayBranch) || r.matches(branch)`.
This is a minor allocation waste — branch matching only happens during PR creation prefetch, so
the real-world impact is negligible.

---

## What works well (don't regress)

**1. `HttpFormPost` + `BambooApiClientTest` wire-shape coverage is exemplary.**
The test at `BambooApiClientTest.queueBuild URL-encodes special characters in variable values`
round-trips a real JSON value (`{"svc":"1.2.3","env":"prod"}`) through `FormBody.Builder`,
URL-decodes the captured request body, and asserts exact equality. This is the right test pattern
for encoding bugs — it proves the value survives the encode/decode cycle, not just that it doesn't
start with `{`. Do not replace this with a shallower assertion.

**2. `modifyPullRequest` cache invalidation on 409 is load-bearing and correct.**
The explicit `HttpResponseCache.invalidateByPrefix(...)` call between attempt 1 and attempt 2
in `modifyPullRequest` is the critical piece: without it, the second GET would serve the same
stale cached version that caused the first 409, creating an infinite loop (both attempts use the
same stale version, both 409). The comment explaining this is clear. Do not remove or move this
invalidation call.

**3. `SingleCallerOfTransitionIssueTest` architectural guard is well-constructed.**
Using a regex scan on source text (rather than reflection or bytecode analysis) keeps the test
fast, platform-independent, and unambiguous. The `.transitionIssue\s*\(` pattern correctly excludes
function definitions, KDoc references, and log messages. The test fails with a clear message
naming the offending file and the canonical fix. The module scan list (`jira`, `agent`, `handover`,
`automation`, `core`) covers every module that transitively depends on `:jira` — `:pullrequest` and
`:bamboo` can't reach `JiraApiClient` directly due to the module graph, so omitting them is correct.

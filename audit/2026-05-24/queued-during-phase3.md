# Issues queued during Phase 3 (C2-C7) — 2026-05-24

## Q1 — BitbucketApiClientTest.getPullRequestsForBranch stale assertion
**File**: `core/src/test/kotlin/com/workflow/orchestrator/core/bitbucket/BitbucketApiClientTest.kt:103`
**What**: The test asserted the raw (unencoded) `at=refs/heads/...` query param. After C5 switched `getPullRequestsForBranch` to `HttpUrl.addQueryParameter`, OkHttp encodes slashes as `%2F`, breaking the assertion.
**Status**: Fixed in a followup commit immediately after C5 (same Phase 3 session). Not queued — already resolved.

## Q2 — `CopyOnWriteArrayList.removeAll { predicate }` is unsafe (iterator-remove path)
**File**: `agent/src/main/kotlin/com/workflow/orchestrator/agent/hooks/HookManager.kt` (unregister)
**What**: Kotlin's `MutableList.removeAll { predicate }` extension calls `iterator.remove()`, which `CopyOnWriteArrayList`'s snapshot iterator rejects with `ArrayIndexOutOfBoundsException` under concurrent load. Fixed inline during C7 by switching to `removeIf(Predicate)` (COWAL's atomic path).
**Status**: Fixed inline during C7. Not queued — already resolved.

## Q3 — `searchFiles` used `Thread.currentThread().isInterrupted` for cancellation
**File**: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/SearchCodeTool.kt`
**What**: The walk used `Thread.currentThread().isInterrupted` checks which do not propagate structured coroutine cancellation from `AgentLoop.withTimeoutOrNull(120s)`. Fixed inline during C4 by converting `searchFiles` to `suspend` and using `coroutineContext.ensureActive()`.
**Status**: Fixed inline during C4. Not queued — already resolved.

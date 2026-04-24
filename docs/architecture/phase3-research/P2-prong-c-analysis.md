# P2 — Prong C analysis (SmartPoller dedup)

**Run:** 2026-04-25 on `refactor/cleanup-perf-caching` after Prong A completion.
**Outcome:** Prong C as originally designed is **not needed**. MutableStateFlow's
built-in equality semantics already provide the same dedup that we'd get from a
bespoke SHA-256 mechanism at the poller layer.

---

## Original hypothesis (from strategy doc §4 Prong C)

> Polls still happen, but the UI doesn't re-render if the response bytes actually
> differ. Hash the response, per-poller; on byte-identical result, skip the
> `onResult` callback.

Rationale was: the 4 active SmartPoller instances (`BuildMonitor`, `PrList`,
`AutomationMonitor`, `InsightsPanel`) tick on a schedule; each tick was assumed
to drive a UI rebuild regardless of whether the data actually changed.

---

## What the code actually does

Each poller's state is exposed via `MutableStateFlow<T>`:

| Poller | State type | File |
|---|---|---|
| BuildMonitor | `BuildState?` | `bamboo/service/BuildMonitorService.kt:87` |
| PrList (my / reviewing / allRepo) | `List<BitbucketPrDetail>` | `pullrequest/service/PrListService.kt:33-40` |
| AutomationMonitor | (state flow in `MonitorPanel`) | `automation/ui/MonitorPanel.kt:116` |
| InsightsPanel | (state flow in panel) | `core/toolwindow/insights/InsightsPanel.kt:44` |

`MutableStateFlow.value = x` invokes `Objects.equals(current.value, x)` before
emitting — **the framework itself skips emit + subscriber notification when the
new value equals the current one**. For data classes with all-value-equality
fields, this is exact value equality, not reference. So a poll that returns a
structurally identical DTO triggers zero downstream UI work:

```
pollOnce()                 ← HTTP call always fires (handled by Prong A caching)
  _stateFlow.value = dto   ← equality check inside MutableStateFlow
                              equal? → no emit, UI listener not called
                              differ? → emit, UI listener called, rebuild
```

The dedup Prong C was designed to add **already exists one layer up**.

## Why Prong A still matters here

Prong A's HTTP cache doesn't become redundant — it still:
1. Serves from memory on fresh hit (saves network call)
2. Short-circuits Jackson parsing on stale-match (saves CPU + allocs)

Without Prong A, every poll tick would: network-call → Jackson-parse → produce
DTO → StateFlow equality → skip emit. That's still parsing overhead on every
tick. With Prong A, a stable endpoint produces: cache-hit-fresh → return cached
response → downstream caller still parses, but does so from cached bytes; or
stale-match → refetch → identical hash → reuse, same bytes, same parse.

## Remaining gap (small, measured-later)

`CommentsTabPanel` uses a `SmartPoller` (line 55) but its state is on a panel
field rather than a public `MutableStateFlow`. If the panel directly swaps
Swing components on every tick regardless of content equality, the Prong C
hypothesis could still apply there. This is one of the 5 poller consumers;
the other 4 are covered by StateFlow. **Deferred — will only act on this
site if measurement post-Phase-3 shows user-visible lag on the Comments tab.**

## Decision

- **No changes to SmartPoller or its callers as part of Phase 3.**
- Prong C is subsumed by MutableStateFlow semantics for 4 of 5 pollers.
- The 5th caller (CommentsTabPanel) is flagged for later evaluation —
  Phase 4 if EDT profiling shows churn there.

## What's left of Phase 3

With Prong C absorbed, the work remaining is:

1. **Done** — Phase 2 tail: HttpClientFactory routing migration (5 commits)
2. **Done** — Prong B: RepoContextResolver memoization (1 commit)
3. **Done** — Prong A: CachingInterceptor scaffold + activation + stale-match +
   mutation invalidation (7 commits)
4. **Deferred** — Jira ad-hoc cache consolidation. Low priority: Prong A does
   not replace `TicketKeyCache` (different purpose: ticket-key validity cache,
   not HTTP response cache), `SprintPaginationCache` (cross-IDE persistence,
   different domain), or `IssueDetailCache` (parsed-DTO cache, one layer above
   Prong A). Consolidating the last would need wrapping Prong A with a parsed-
   object cache; net win is small. Re-evaluate after baseline measurement in
   Phase 4.
5. **Not needed** — Prong C (this analysis).
6. **Pending** — Documentation commit (strategy doc + research index).

## Supporting evidence — MutableStateFlow equality behavior

From `kotlinx.coroutines.flow.StateFlow` KDoc:
> Values in state flow are conflated using `Object.equals` comparison in a
> similar way to [distinctUntilChanged] operator.

Source: `kotlinx-coroutines-core:1.8.0` / `StateFlow.kt`.

This is standard, documented, and relied on throughout the Kotlin ecosystem
(Android Jetpack Compose, Ktor clients, every Arrow example). Not a surprise;
just a primitive we'd forgotten was doing the job.

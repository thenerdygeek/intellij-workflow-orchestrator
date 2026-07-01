---
name: project_flaky_ci_tests_stabilization
description: Flaky timing-dependent CI Tests job makes green an unreliable merge gate — tracked in GitHub issue
metadata: 
  node_type: memory
  type: project
  originSessionId: 03af3cf4-6e28-40e6-8385-0ef0d97fc3e2
---

**OPEN 2026-06-09: GitHub issue #51** — the CI **Tests** job is intermittently red from timing-dependent tests, so "Tests green" is NOT a trustworthy merge gate. Proven flaky (not a real regression) because they failed on **PR #11, which changed only `.github/workflows` YAML** — code-independent failure = nondeterminism.

Surfaced during the 2026-06-09 dependabot triage; forced merging the safe dependabot PRs on the *other* CI jobs (Build/detekt/Konsist/Coverage) instead of Tests.

4 affected tests + fix archetype:
- `DelegationTransientRetentionTest` (`:agent`) — real wall-clock + real Unix-socket round-trips, tight margins (`retentionMillis=150` vs `delay(600)`; `200ms` window asserted with `delay(50)`). Fix: injectable retention clock seam, or much wider margins.
- `DelegationInboundProjectCloseTest` (`:agent`) — real-time `runBlocking` close race.
- `SessionDocumentArtifactServiceTest` (`:agent`) — single-flight extraction race via `gate.await()`/`Deferred`; already flagged flaky in earlier handoffs. Fix: explicit `Job.join()` sync point.
- `TemplateEditorCardTest` (`:handover`) — uses virtual time (`advanceUntilIdle`) but the 300ms debounce job likely runs on a real `Dispatchers.Default` not bound to the `TestScope`. Fix: inject `StandardTestDispatcher` for the debounce.

Acceptance: each green across 20 consecutive `--rerun-tasks` runs; no `delay`/`Thread.sleep` value gating a pass/fail assertion; Tests job green on a docs-only PR. Related: [[project_enterprise_roadmap]] (notes the SessionDocumentArtifactServiceTest flakiness).

---
name: project-network-connectivity-resilience-shipped
description: Shipped connectivity-resilience feature (offline fail-fast + poller pause/resume) on bugfix; manual VPN smoke still pending
metadata: 
  node_type: memory
  type: project
  originSessionId: 14ee482d-7c0e-4f25-bacf-bc4bdbe7d45f
---

**SHIPPED + PUSHED 2026-05-26 on `bugfix` (`a0e6d997d`..`c6fd52890`; pushed to origin/bugfix @ `d0d52d2a6`).** Brainstorm→spec→plan→subagent-driven impl→2 sonnet reviews.

**FOLLOW-UP same session (`2c78b38fc`): retry-backoff audit + fix.** User reported "3 retries happen instantly." Audited all 11 retry sites: 4 LLM-recovery paths (output-length truncation, upstream gateway-timeout, context-overflow replay, empty-`choice` guard) did a bare delay-less `continue`; the 4 "delayed" sites used full jitter `[0,computed]` (could draw ~0ms). Fix: `computeBackoffMs` → equal jitter `[computed/2, computed]` (floor); paced all 4 delay-less paths; capped the 2 unbounded ones (`MAX_TRUNCATED_RETRIES=8`, `MAX_UPSTREAM_TIMEOUT_RETRIES=5`, reset on clean response). LLM clients have NO internal retry — all retry logic is centralized in `AgentLoop`. Pinned by `AgentLoopRetryPacingTest` + updated `AgentLoopBackoffHelperTest`.

**What:** Single `:core` connectivity authority so the agent stops burning its retry budget into a dead VPN tunnel after laptop unlock, and pollers stop hammering while offline.
- `core/network/NetworkStateService` — `@Service(Service.Level.APP)`, cs-injected. `StateFlow<NetworkState>` (ONLINE/OFFLINE/RECONNECTING). Fed by: reactive `NetworkStateReportingInterceptor` on `HttpClientFactory.baseClient` (covers all factory clients = the pollers); one active `NetworkReachabilityProbe` backoff loop (HEAD on its OWN OkHttpClient, bypasses RetryInterceptor); a clock-gap wake watchdog (`isWakeGap`).
- Agent: `FailureReason.OFFLINE` + a fail-fast branch at the `TIMEOUT_ERRORS` retry seam (`AgentLoop.kt` ~1097) calling `networkProbe.checkNow(llmProbeUrl)` BEFORE `apiRetryCount++`. Reuses the existing Retry pill (`AgentController` `isOffline` caption → `retryLastTask()`).
- Pollers: `SmartPoller` `awaitOnline()` gate + reset-backoff + jittered stagger on reconnect.

**Decisions (user-chosen):** agent FAILS FAST (manual Retry), not auto-resume. Shared core service covering BOTH agent + pollers. ApplicationActivationListener + per-host reachability map deferred (YAGNI). Sourcegraph/BitbucketBranchClient stay isolated from HttpClientFactory → agent path covered by active `checkNow`, not reactive reporting (by design).

**Two gotchas hit (both pre-documented patterns):**
1. **SmartPoller trailing-lambda trap:** adding `networkProbe` as the LAST ctor param broke `:bamboo`/etc. because call sites pass `action` as a trailing lambda (binds to last param). Fix: `networkProbe` MUST precede `action`. `:core:test` alone won't catch it — only a cross-module compile does.
2. **Build-cache NoSuchMethodError:** reordering the `suspend () -> Boolean` param triggered the documented stale-`Function0` trap (CLAUDE.md Rebase section). Fix: `:core:clean :core:test --rerun --no-build-cache`. See [[project-audit-remediation-campaign]] sibling note style.

**Verified:** `:core:test` 1134 pass, `:agent:test` pass, `verifyPlugin` exit 0 (clean rebuilds). **STILL PENDING:** manual VPN smoke (lock/unlock, drop tunnel mid-turn) — needs a real IDE+VPN. Spec: `docs/superpowers/specs/2026-05-26-network-connectivity-resilience-design.md`; plan (gitignored, force-added): `docs/superpowers/plans/2026-05-26-network-connectivity-resilience.md`.

⚠ This branch has CONCURRENT background commit activity (automation/bamboo auto-commits interleaved with mine — a pre-commit hook / Ralph-style loop in the user's env). Functionally benign but history is interleaved.

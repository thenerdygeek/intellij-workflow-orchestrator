# Phase 4 — Enterprise Product Features (Spec)

**Date:** 2026-06-07
**Status:** Spec for review (autonomous-run output). Several items are **product/legal decisions that
are yours, not mine** — flagged ⚠ DECISION below. The decision-free plumbing can proceed; the
decisions gate the rest.
**Depends on:** Phase 2 seams (config layer + capability/feature-flag framework) for the
config-driven controls.

---

## ⚠ DECISIONS REQUIRED FROM YOU (gating)

1. **Telemetry default: opt-in vs opt-out.** The plugin sends code to an external LLM. Any usage
   telemetry must have a default stance. Enterprises typically require **opt-in** (off by default).
   Recommendation: opt-in. — *Your call.*
2. **Privacy-disclosure wording.** A user-facing statement that "code and IDE context are sent to the
   configured LLM provider," where it's shown (first-run notice? settings page?), and what (if
   anything) is logged locally. Legal-adjacent. — *Your call / your wording.*
3. **Which admin policies ship in the base.** Candidate set below; pick the base subset (the rest can
   live in forks). — *Your call.*

Until these are decided, I can build the **decision-free plumbing** (audit-log subsystem; the
policy *mechanism* with no opinionated defaults; proxy/endpoint config surfacing). I will not pick a
telemetry default or write privacy/legal copy unattended.

---

## Items

### 1. Audit-logging subsystem (decision-free plumbing — can start)
Formalize the existing `agent/.../observability/AgentFileLogger.kt` (JSONL) into a real audit trail:
- Structured records for write-ops / API calls / agent actions (typed event schema in `:core`).
- Configurable retention (currently logs/ kept 7 days — make it a setting).
- Tamper-resistance *posture* documented (append-only, hash-chain optional later — note, don't
  over-build).
- Surfaced via `ToolResult`-returning core service so the agent/UI can query it.

### 2. Admin / policy controls (mechanism = decision-free; default policy set = ⚠ DECISION)
Org-level policy, **config-driven via the Phase 2 capability/feature-flag framework + typed config**:
- Disable agent shell execution.
- Restrict the tool set (allow-list).
- Force approval gates (no auto-approve).
- Lock settings centrally (admin-set, user-read-only).
Build the *enforcement mechanism* reading from the capability framework; the *default policy values*
shipped in the base are ⚠ DECISION #3.

### 3. Telemetry & privacy (⚠ DECISION #1 + #2)
- Explicit opt-in/opt-out control (default = DECISION #1).
- Data-handling disclosure (wording = DECISION #2).
- No telemetry beyond user-invoked LLM calls unless explicitly enabled.

### 4. Network / proxy / on-prem hardening (mostly decision-free)
- Surface corporate proxy config (the IDE proxy is already used by `HttpClientFactory`; expose +
  document it).
- Configurable LLM endpoints (on-prem / self-hosted gateways).
- Offline / air-gapped degradation modes (graceful disable, clear messaging) — builds on the
  existing `NetworkStateService`.

### 5. Compatibility matrix (decision-free)
- Document + test the supported server versions (Jira / Bamboo / Bitbucket / Sonar). There is prior
  probe work in memory (`project_*_version_probe_findings`); consolidate into a tested matrix.

## Suggested order
1. Audit-logging subsystem (decision-free, high enterprise value, builds on existing logger).
2. Proxy/endpoint hardening + compatibility matrix (decision-free).
3. Policy *mechanism* on the Phase 2 capability framework (decision-free).
4. — pause for DECISIONS #1–3 —
5. Telemetry/privacy + default policy set (after decisions).

## Out of scope (lives in forks, via Phase 2 seams)
- Company SSO/SAML/licensing implementations (the base ships the `AuthProvider` seam, Phase 2).

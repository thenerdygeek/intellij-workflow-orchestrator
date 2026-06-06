# Enterprise-Grade Roadmap — Workflow Orchestrator Plugin

**Date:** 2026-06-06
**Status:** Approved roadmap (execution deferred until `perf/token-context-optimization` PR merges)
**Author:** Subhankar Halder (with Claude)

---

## Framing

Three contextual facts shape every decision in this roadmap:

1. **Solo developer.** Governance stays lightweight — docs that help future-you and fork
   maintainers, not pull-request ceremony or contributor onboarding machinery.
2. **Public core + per-company forks.** The base plugin is a personal/public tool;
   company-specific variants are *forked from it and modified per company*. The dominant
   architectural goal is therefore **clean extension seams**: company-variable behavior must
   live behind extension points and config so forks are thin overlays and upstream merges do
   not conflict.
3. **Stated priorities** (from scoping): architecture/maintainability, enterprise product
   features, and governance/compliance are the headline. CI/CD is included **only as the
   enforcement enabler** the other work depends on — not as a primary goal in itself.

Phases are ordered by dependency: **safety nets first, invasive surgery only once nets exist,
product features and polish last.**

---

## Current state (assessment summary, 2026-06-06)

Established via codebase exploration across four dimensions:

| Dimension | State | Notes |
|---|---|---|
| **Architecture** | Strong | Enforced layering (`api/→service/→ui/→listeners/`), EventBus cross-talk, feature modules depend on `:core` only, no circular deps. 8 extension points already declared. Weakness: god-classes; rule is convention-only (not test-enforced). |
| **Security** | Strong | PasswordSafe credential store, `UrlSafetyGuard` (SSRF), `PathValidator`, `CommandSafetyAnalyzer`, TLS via IDE truststore, SHA-256 `verification-metadata.xml` + 13 lockfiles. Weakness: no `SECURITY.md`, no CVE scanning. |
| **Testing** | Broad | ~1,100 Kotlin tests (JUnit5/MockK/Turbine) + 138 TS tests (Vitest/RTL). Kover configured. Weakness: no coverage gates, no architecture tests, uneven per-module (`:agent`/`:core` excellent; `:pullrequest`/`:sonar`/`:automation`/`:web` thin). |
| **CI/CD & Release** | Weakest | Fully manual local builds. No pipeline, no automated gates, no SCA. `CHANGELOG.md` empty despite changelog plugin wired. Signing + pluginVerifier + publishing configured but env-var/manual. |

**God-class hotspots** (also the merge-conflict epicenters a fork would touch):
`AgentController.kt` 5,403 LOC · `AgentService.kt` 4,169 LOC · `AgentLoop.kt` 2,762 LOC ·
`PrDetailPanel.kt` 2,521 LOC · large tools (`SonarTool` 2.4K, `JavaRuntimeExecTool` 2.2K,
`RuntimeExecTool` 2.1K, `JiraTool` 1.9K).

---

## Phase 0 — Enforcement Foundation

**Enabler · low risk · ~1 week**

The architecture work in Phase 3 cannot be done safely without regression nets. This phase is
cheap and high-signal, and several later items (arch tests, coverage gates) only have teeth
inside a pipeline.

| Item | What | Why |
|---|---|---|
| CI pipeline | GitHub Actions: build + `test` + `verifyPlugin` + Kover report on push/PR | Builds are manual-only today; nothing catches regressions |
| Static analysis | Wire **detekt** + **ktlint** (or spotless) with a baseline file | No enforced style/lint across 388K LOC |
| Coverage gates | Kover *thresholds*, per-module + ratcheting (high bar `:agent`/`:core`, lower for thin modules) | Kover runs but enforces nothing |
| Architecture tests | **Konsist** (or ArchUnit) compiling-in "feature modules → `:core` only" + the layering rule | Rule is convention-only; a fork could silently violate it |
| Dependency CVE scanning | OWASP dependency-check or Dependabot (pairs with existing lockfiles) | Transitive CVEs currently undetected |

**Deliverable:** green CI that fails on lint, coverage drop, arch-rule violation, or known CVE.

---

## Phase 1 — Governance & Compliance Docs

**Low risk · ~3 days**

Lightweight for a solo dev, but the fork guide is foundational to the whole model.

- `SECURITY.md` — vulnerability disclosure policy (none today; the agent runs shell + sends code to an LLM).
- `THREAT_MODEL.md` — explicit trust-boundary model (shell execution, file writes, LLM egress, credential handling).
- `FORKING.md` — **keystone doc**: stable extension seam vs. internal surface, how to overlay company customizations, how to merge upstream cleanly.
- `CONTRIBUTING.md` (light) + `CODEOWNERS` — guide fork maintainers even though solo.
- `docs/adr/` — ADR framework; backfill the major past decisions.
- Populate `CHANGELOG.md` — currently 0 bytes despite the changelog plugin being wired.

---

## Phase 2 — Forkability & Extensibility Seams

**Medium risk · ~2 weeks**

The architectural investment that makes the public-core/per-company-fork model actually work.
Lean into the existing 8 EPs + optional-dependency config-file pattern rather than inventing new
machinery.

- **Stable API surface** — explicitly mark which EPs/interfaces are stable (fork-facing) vs. internal; annotate and document.
- **Config-driven behavior** — externalize company-variable values (server URLs, auth schemes, feature toggles) into a typed config layer so forks override *config*, not *code*.
- **Capability/feature-flag framework** — forks and admins enable/disable features without code edits.
- **Auth provider seam** — an `AuthProvider` extension point so company forks plug in SSO/SAML/licensing **without** touching `:core`. Base ships token auth; forks overlay the rest.

---

## Phase 3 — Architecture Decomposition

**High risk · ~3–4 weeks · gated on Phase 0**

The invasive tier — safe now because the nets exist. These god-classes are the merge-conflict
epicenters, so decomposing them serves the fork model directly.

| Target | LOC | Decomposition |
|---|---|---|
| `AgentController.kt` | 5,403 | Extract JCEF bridge → `AgentJcefBridge`; split lifecycle/wiring |
| `AgentService.kt` | 4,169 | Extract `ToolRegistry` into a standalone service; split session management |
| `AgentLoop.kt` | 2,762 | Separate ReAct state machine from I/O orchestration |
| `PrDetailPanel.kt` | 2,521 | Extract table models + detail sections + card components |
| Large tools (Sonar/Runtime/Jira) | 1.5–2.4K each | Base class + per-concern subclasses |

**Discipline:** write characterization tests *before* each extraction (TDD / systematic-debugging
patterns). Raise coverage in thin modules (`:pullrequest`, `:sonar`, `:automation`, `:web`)
opportunistically as they are touched.

---

## Phase 4 — Enterprise Product Features

**Medium risk · ~3 weeks**

Generic, base-level features enterprises demand. Company-specific implementations (SSO,
licensing) live in forks via the Phase 2 seams.

- **Audit logging subsystem** — formalize the existing `AgentFileLogger` JSONL into a real audit trail: structured records of write-ops/API-calls/agent-actions, configurable retention, tamper-resistance posture.
- **Admin/policy controls** — org-level policy (disable agent shell, restrict tool set, force approval gates), config-driven so admins/forks lock down centrally.
- **Telemetry & privacy** — explicit opt-in/opt-out, a data-handling disclosure (code *is* sent to an external LLM — enterprises need this stated and controllable).
- **Network/proxy/on-prem hardening** — surface corporate proxy config, configurable LLM endpoints, offline/air-gapped degradation modes.
- **Compatibility matrix** — documented + tested server-version support (Jira/Bamboo/Bitbucket/Sonar).

---

## Phase 5 — Release & Supply-Chain Automation

**Low risk · ~1 week · supporting**

Completes the posture; lowest urgency for a solo dev, so it lands last.

- **Automated release** — tag → CI builds signed plugin → `gh release` (+ optional Marketplace publish).
- **SBOM generation** (CycloneDX) for compliance asks.
- Maintain `CHANGELOG.md` via the changelog plugin as part of release.
- Reproducible-build verification on top of the existing `verification-metadata.xml`.

---

## Sequencing

```
Phase 0 (nets) ──┬──> Phase 3 (refactor, needs nets)
                 ├──> Phase 2 (seams) ──> Phase 4 (product features, use seams)
Phase 1 (docs) ──┘
Phase 5 (release) — anytime after Phase 0, best last
```

---

## Out of scope (lives in forks)

- SSO/SAML implementations
- Per-company licensing / entitlement
- Company-specific API customizations

The base provides the **seams** (Phase 2), not these implementations.

---

## Execution note

Roadmap work begins **after the `perf/token-context-optimization` PR merges**. Each phase will
get its own implementation plan (via the writing-plans workflow) at the time it is picked up, so
the plan reflects the codebase state then rather than now.

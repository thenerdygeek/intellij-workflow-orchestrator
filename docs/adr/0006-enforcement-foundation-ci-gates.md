# ADR 0006: Enforcement foundation — CI + lint + arch + CVE gates

- **Status:** Accepted
- **Date:** 2026-06-07
- **Deciders:** Subhankar Halder

## Context

Architectural rules (layering, fork seams) and quality bars were convention-only and
unenforced; builds were manual. Roadmap Phase 3 decomposition is unsafe without regression
nets. (Phase 0, merged via PR #10.)

## Decision

Add a GitHub Actions pipeline with four gates: build-and-verify, full test suite, detekt
lint (per-module `detekt-baseline.xml`), and `:konsist` architecture tests enforcing
module boundaries + layering. Dependabot covers CVEs. The coverage gate is **deferred**
(Kover instrumentation conflicts with MockK suspend-function mocks in `:agent`).

## Consequences

- CI fails on lint, architecture-rule violations, or known CVEs — forks inherit the same nets.
- The full suite runs on Linux for the first time, surfacing (and fixing) cross-platform
  traps (verification-metadata trust groups, hardcoded `java.home`, macOS-coupled snapshot
  tests, async races).
- Coverage enforcement remains TODO; resume path is documented in the Phase 0 plan.

## Alternatives considered

- Coverage gate now: deferred — Kover×MockK conflict breaks `:agent` tests; scoped fix later.
- ArchUnit instead of Konsist: rejected — Konsist is Kotlin-native and reads cleaner here.

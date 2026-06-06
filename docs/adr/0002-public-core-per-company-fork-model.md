# ADR 0002: Public core + per-company fork distribution

- **Status:** Accepted
- **Date:** 2026-06-07 (backfilled)
- **Deciders:** Subhankar Halder

## Context

The plugin is a public/personal tool, but companies need private customizations (server
URLs, auth schemes, SSO, licensing) that cannot live in the public repo. We must support
those without fragmenting the codebase or blocking upstream improvements.

## Decision

Distribute a **public core** that companies **fork and customize**. Company-variable
behavior lives behind **extension points** and **configuration**, so forks are thin
overlays and upstream merges stay clean. The base ships generic seams, not company
implementations.

## Consequences

- Clean extension seams become the dominant architectural priority (roadmap Phase 2).
- The 11 `:core` extension points are the stable fork-facing surface; see
  [FORKING.md](../../FORKING.md).
- God-classes that forks would touch are the merge-conflict epicenters and are scheduled
  for decomposition (roadmap Phase 3).
- SSO/SAML/licensing are explicitly out of scope for the base — they live in forks.

## Alternatives considered

- Config-only (no forks): rejected — cannot express company-specific code (SSO, licensing).
- Plugin-of-plugins marketplace model: rejected — too heavy for a solo-maintained tool.

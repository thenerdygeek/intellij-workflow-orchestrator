# ADR 0001: Module layering & feature-modules-depend-on-core-only

- **Status:** Accepted
- **Date:** 2026-06-07 (backfilled; decision predates this record)
- **Deciders:** Subhankar Halder

## Context

The plugin is split into multiple Gradle submodules: `:core` plus feature modules
`:jira`, `:bamboo`, `:sonar`, `:pullrequest`, `:automation`, `:handover`, `:agent`,
`:document`, and `:web` — alongside `:mock-server` (test infrastructure) and `:konsist`
(architecture enforcement). Without a dependency rule, feature modules would inevitably
reach into one another, producing a tangled graph that is impossible to fork cleanly.

## Decision

- Feature modules depend **only on `:core`**. Cross-module communication goes through
  `EventBus` (`SharedFlow<WorkflowEvent>`).
- Within a module, code is layered `api/ → service/ → ui/ → listeners/`.
- `:agent` depends **only on `:core`** as well.

## Consequences

- Forks can overlay a single feature behind a `:core` extension point without dragging in
  other modules.
- The rule was convention-only and silently violable until Phase 0 added the `:konsist`
  architecture tests that enforce it in CI (see [ADR-0006](0006-enforcement-foundation-ci-gates.md)).
- Some behavior must be expressed as events rather than direct calls — a deliberate cost.

## Alternatives considered

- A single monolithic module: rejected — no fork isolation, no enforced boundaries.
- Allowing feature-to-feature deps: rejected — produces merge-conflict-prone coupling.

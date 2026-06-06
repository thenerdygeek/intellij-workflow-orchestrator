# ADR 0004: Agent service architecture (core interface → ToolResult)

- **Status:** Accepted
- **Date:** 2026-06-07 (backfilled)
- **Deciders:** Subhankar Halder

## Context

`:agent` depends only on `:core`, so it cannot call methods on feature-module clients or
UI panels directly. Without a contract, exposing feature behavior to the agent would
either break the dependency rule or duplicate logic.

## Decision

New agent-reachable behavior follows a fixed shape:
**core interface → `ToolResult<T>` (with `T` in `core/model/`, carrying a meaningful
`.summary`) → feature-module implementation → agent tool wrapper.**

## Consequences

- The agent reaches feature behavior through `:core` interfaces only — the layering rule
  ([ADR-0001](0001-module-layering.md)) holds.
- Every agent-facing result is a typed `ToolResult<T>` with a human-readable summary,
  which the agent loop and UI both consume uniformly.
- There is a checklist cost per new tool (interface? `ToolResult<T>`? `T` in `core/model/`?
  meaningful summary? impl in feature module?).

## Alternatives considered

- Agent calls feature clients directly: rejected — violates the module dependency rule.
- Untyped string results: rejected — loses summaries and type safety the UI relies on.

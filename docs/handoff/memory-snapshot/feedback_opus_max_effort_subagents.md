---
name: Opus (max effort) for exploration/research/analysis subagents
description: When dispatching subagents for exploration, research, or judgment-heavy analysis, use Opus with MAX effort (not high). Sonnet stays for mechanical work.
type: feedback
originSessionId: ce1ef373-a40b-4315-b385-0edac045e40b
---
When dispatching subagents on this user's projects, use Opus with **max** effort for any task that requires exploration, research, verification, or judgment. The user explicitly corrected "high effort → max effort" on 2026-04-24.

Sonnet still applies for purely mechanical work (per `feedback_sonnet_for_small_tasks.md`): bulk renames, import sweeps, formatting, deterministic refactors with no judgment.

**Why:** the user is willing to pay Opus + max-effort token costs to get a high-rigor second opinion, especially on dead-code or correctness verification where Sonnet's first sweep produced confidently wrong claims (e.g., the Commit-5 dead-fallback sweep flagged 5 candidates; Opus verification disqualified 4 of them after reading the full file contents).

**How to apply:**
- Subagent for exploration / research / verification / "is this dead?" / "is this correct?" → `model: opus` with high reasoning/max-effort prompting (push the agent to read full files, cite line numbers, check git log, verify each claim).
- Subagent for mechanical bulk work (e.g., remove these N imports, rename X to Y across files) → `model: sonnet`.
- For pure-deletion commits where there's nothing to review, skip the subagent entirely.
- "high effort" is the wrong phrase — say **max effort** in the prompt and pick `opus`.

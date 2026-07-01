---
name: Iterative self-improvement patterns across 10 agentic tools
description: Cross-tool analysis of retry/review/self-improvement loops — Aider reflection, SWE-agent RetryAgent+Chooser, OmX Ralph, Goose recipe retry, Sweep CI loop, OpenHands stuck detection, AutoCodeRover generator validation, Plandex build-validate-fix
type: reference
---

Full research at `docs/research/2026-04-03-iterative-self-improvement-patterns-synthesis.md`.

Key patterns: Aider (simplest reflection loop, 3 triggers, max 3), SWE-agent (most sophisticated — outer retry with config diversity + o1 Chooser judge, budget-aware), OmX (skill re-injection + notify hook + auto-expand, NOT code-level loop), Goose (stateless wipe — recipe retry with shell checks), Sweep (history-accumulated CI fix loop, 5-pass consensus review), OpenHands (stuck detection with 5 scenarios, recovery not retry), AutoCodeRover (generator-based validation, cleanest separation), Plandex (per-file 3 attempts, parallel strategy racing).

Critical insight: context strategy is the fundamental design choice — wipe vs retain vs accumulate vs clean-slate-with-judge. Auto-expand prevents premature termination. Config diversity improves results. History accumulation enables learning.

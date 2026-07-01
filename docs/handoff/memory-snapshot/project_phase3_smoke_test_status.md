---
name: project_phase3_smoke_test_status
description: Phase 3 runIde smoke-test scenarios + run status; only S-D1 (saved model) tested on Windows so far
metadata: 
  node_type: memory
  type: project
  originSessionId: ac73cd09-203e-4cd4-baf3-39f23801e97f
---

# Phase 3 smoke-test status (release v0.86.0-phase3.1)

The 5 Phase 3 PRs (#26/#27/#28/#29/#30) were MERGED to main and RELEASED as `v0.86.0-phase3.1`
(GitHub release with ZIP attached) BEFORE any runIde smoke test — smoke was deferred per user.
Full Windows-ready checklist (setup→action→expected, PowerShell commands inlined) lives in
`PHASE3-SMOKE-TESTS.md` at repo root (committed on main). See [[project_enterprise_roadmap]].

## What's been tested on Windows
- ✅ **S-D1 — saved model honored** (BrainFactory / the 5x-over-billing guard): pick non-default
  model → API uses it, not auto-swapped to Opus. **PASSED.**
- ☐ **Everything else NOT yet run.** Highest-value remaining: S-E1 (monitor re-arm on resume),
  S-F1 (background completion live), S-F2 (background completion idle auto-wake), S-D4 (missing-URL
  error). Then the rest (S-D2/D3/D5, S-E2/E3/E4/E5/E6, S-F3/F4, S-B1).

## Windows gotcha for the monitor/background scenarios
ShellResolver picks Git Bash → PowerShell → cmd. The doc's old `bash -c '...'` examples only work
with Git Bash installed; use PowerShell forms (e.g. monitor command
`powershell -Command \"while ($true) { echo tick; Start-Sleep 2 }\"` with `filter="tick"`, inner
quotes escaped). Monitor `filter` is case-sensitive (prefix `(?i)`).

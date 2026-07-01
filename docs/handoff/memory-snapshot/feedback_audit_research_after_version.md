---
name: API audit — research happens AFTER version is known
description: Multi-step audit pattern — inventory + v0 probe with versions-only first; deep API research only AFTER user reports the actual server version
type: feedback
originSessionId: ecc35573-422c-4d12-a441-3aec01d69276
---
For multi-service API audits (Jira → Bitbucket → Nexus on `fix/automation-handover-quality-tabs`), the strict workflow is:

1. Inventory existing call sites
2. Write a v0 probe driver with `--versions-only` and `--discover` modes scaffolded
3. User runs `--versions-only`, reports the actual server version + edition
4. **THEN** research the API docs **scoped to that version** (skip endpoints introduced after it; skip Cloud-only or unrelated features)
5. Expand the probe with researched endpoints relevant to the user's version
6. User runs `--discover` then full sweep
7. Compile recommendations doc
8. Implement in one commit

**Why:** Nexus 3 / Jira 10 / Bitbucket DC all have heavy version-gating. Researching the entire 3.x or 10.x API surface up front wastes effort on endpoints that may not exist on the user's instance, and produces probe noise (many 404s) when the full sweep eventually runs.

**How to apply:** When kicking off the next service in the audit (or any similar multi-step audit), do NOT spawn a research agent immediately after writing the v0 probe. Wait for the user's `--versions-only` output. Only then expand the probe. Same applied to Jira (probe → user reported 10.3.16 → expanded for Jira 10) and Bitbucket (per user, 2026-05-07: "another session is now researching to write probe scripts based on the version it probed first for bitbucket").

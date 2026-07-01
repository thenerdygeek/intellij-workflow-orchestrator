---
name: SHIPPED 2026-05-07 — API audit & probe sequence (Jira + Bitbucket; Nexus deferred)
description: Jira and Bitbucket integrations shipped; Nexus probe shipped but integration intentionally deferred per user
type: project
originSessionId: feacf7be-5fbc-4569-915b-23840cb9b110
---
**STATUS: SHIPPED for Jira + Bitbucket. Nexus integration intentionally deferred.** Safe to delete this memory once Nexus is either picked up or formally dropped.

**Implementation commits on `fix/automation-handover-quality-tabs`:**
- Jira: `56d0dd0 feat(jira): unify HTTP funnel and adopt validated endpoints (Server-only)`
- Bitbucket: `b9ed7cbe feat(bitbucket): adopt validated DC 9.4 endpoints + Bamboo bridge phase 1`
- Nexus: probe shipped (`328d811`, `c8c5c2c`, plus docker-path fixes `5dd1814`/`5b5797`/`48cb8d8`/`adb856d`/`1fe8b0f`); **no integration commit** — user marked low-priority, may skip

**Branch:** `fix/automation-handover-quality-tabs` was the API-audit branch — Jira + Bitbucket + Nexus probes all landed here, sequenced. When/if Nexus integration is decided, it lands here too; otherwise this branch is ready to PR.

---

**Original sequence (strict):** 1) Jira  →  2) Bitbucket  →  3) Nexus

**Constraints (from user, 2026-05-06):**
- Atlassian flavour: **Server / Data Center only** (not Cloud) for now
- Probes must be **read-only**; mutating calls (transitions, branch create, comments, merges) are explicitly out of scope until we figure out a safe write strategy
- Probes run on **user's Windows laptop** with **personal token**
- Each probe supports a **version-detection mode** (`--versions-only`):
  - Jira: `GET /rest/api/2/serverInfo`
  - Bitbucket DC: `GET /rest/api/1.0/application-properties`
  - Nexus 3: `GET /service/rest/v1/status` and `/service/rest/v1/system/about`

**Audit goal per service (from user):**
1. Verify every existing call still works
2. Recommend swaps where a better endpoint exists
3. Surface useful endpoints that would unlock new features

**Architecture instruction (from user):**
"Make changes in all the appropriate places (sprint tab, commit message generation, agent tools, etc.) and unify the architecture if not already." → every call funnels through the service's `*ApiClient`, every service goes through `:core` interface returning `ToolResult<T>`, agent tool wrappers return the same.

**Confirmed instances:**
- Jira **10.3.16** Data Center (probed 2026-05-06)
- Bitbucket **9.4.16** Data Center (probed 2026-05-07)
- Nexus 3 — version captured in probe bundles; integration deferred

**Per-session starting points (kept for backref):**
- Jira: `docs/research/2026-05-06-jira-audit-handoff.md`
- Bitbucket: `docs/research/2026-05-06-bitbucket-audit-brief.md` + `docs/research/2026-05-07-bitbucket-9.4-api-surface.md`
- Nexus: `docs/research/2026-05-06-nexus-audit-brief.md`

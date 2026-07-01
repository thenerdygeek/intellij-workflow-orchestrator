---
name: SHIPPED 2026-05-07 — Bitbucket DC audit + integration
description: Bitbucket 9.4.16 DC audited, validated endpoints adopted in commit b9ed7cbe; capability map identifies installed plugins
type: project
originSessionId: 4623c111-3fa0-4bd7-962b-e5eccaeb438c
---
**STATUS: SHIPPED.** Implementation commit landed: `b9ed7cbe feat(bitbucket): adopt validated DC 9.4 endpoints + Bamboo bridge phase 1`. Supporting probe/audit/docs commits: `759ab99`, `71048d1`, `5d2961e`, `a2584f6`, `ff332fb`, `9d7da17`. Safe to delete this memory once user confirms no follow-up wave is planned.

---

2026-05-07 — version probe of user's Bitbucket completed. Bundle at `tools/atlassian-probe/Result_Bitbucket/bundle-versions-only-uncompressed.txt`.

**Instance:** Bitbucket Data Center **9.4.16** (build 9004016, built 2026-01-12). LTS line. Application name `stash` (Atlassian's internal Bitbucket Server name; normal).

**Edition is DC, NOT Server** despite probe summary saying "Server" — the heuristic was wrong because `/admin/license` returned 401 for non-admin token. Correct signal: `repository-mirroring` is in `/rest/capabilities` (DC-exclusive feature, never shipped on Server SKU). v1 probe should detect via capabilities, not `/admin/license`.

**Confirmed installed plugins (via `/rest/capabilities`, no admin needed):**
- `code-insights` → `/rest/insights/latest/capabilities` — Sonar findings on PR is a viable adopt, not "defer"
- `dev-status-detail-pullrequest`, `smart-commit-producer` → Jira link plugin installed → `commits/{sha}/jira-issues` will return real data, not 404
- `build` → `/rest/api/latest/build/capabilities` — rich build status supported
- `deployment` → `/rest/api/latest/deployment/capabilities` — NEW finding (not in v0 probe), deployment status API exists
- `webhooks`, `repository-mirroring`, `remote-link-aggregation`, `bitbucket-remote-event-support`

**Research deliverable:** `docs/research/2026-05-07-bitbucket-9.4-api-surface.md` — checklist for revising probe v1. 41/49 v0 endpoints validated; 3 wrong-path or deprecated; 1 fictional (`build_status_v2` — there is no `/rest/build-status/2.0/`, the rich path is `/rest/api/latest/.../commits/{cid}/builds`).

**Top-3 new endpoints worth probing in v1:**
1. `GET /rest/api/latest/projects/{p}/repos/{r}/commits/{cid}/builds` — canonical rich build status (replaces both v0 `build_status_v1` and the fictional `build_status_v2`); includes `testResults`, `parent`, `ref` — direct Bamboo↔Bitbucket bridge.
2. `GET /rest/api/latest/projects/{p}/repos/{r}/commits/{cid}/deployments` — entire deployment surface, completely missing from v0; gated by confirmed `deployment` capability.
3. `GET /rest/insights/latest/capabilities` — solves base-path-shift for Code Insights so the probe doesn't hardcode `/rest/insights/1.0/`.

**Major 8.x→9.4 surprises:**
- `POST /rest/api/1.0/tasks` REMOVED in 9.0 (PR-scoped GET `tasks` still readable for legacy data)
- `GET /rest/mirroring/latest/mirrorServers/{id}/token` REMOVED in 9.x for security
- No webhooks v2 in DC (Cloud-only)
- Basic auth disabled by default in 9.0+ (PAT-only — which we already use)

**v0 probe wrong paths fixed in v1 + adopted in `b9ed7cbe`:**
- `repo_required_builds`: `/rest/api/1.0/.../required-builds` → `/rest/required-builds/latest/projects/{p}/repos/{r}/conditions`
- `build_status_v2`: renamed to `commit_builds_rich`, points at `/rest/api/latest/.../commits/{cid}/builds`

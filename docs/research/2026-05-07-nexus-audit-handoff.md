# Nexus API Audit — Context Handoff

> **Date:** 2026-05-07
> **Branch:** `fix/automation-handover-quality-tabs`
> **Stage:** recommendations doc landed; awaiting user decisions before implementation commit
> **Predecessors on this branch:** Jira commits + Bitbucket commits already on branch (separate sessions)

If you're picking up this audit from a fresh session, **read this file first**. It carries the full state without needing the prior conversation context.

---

## Where we are in the audit workflow

```
1. Inventory call sites              ✅ done
2. Probe v0 + commit                 ✅ done (commit c8c5c2c3)
3. User runs --versions-only         ✅ done — Nexus 3.90.1-01 PRO confirmed
4. API research scoped to 3.90       ✅ done (docs/research/2026-05-07-nexus-3.90-api-surface.md)
5. Probe v1 expansion + commit       ✅ done (commit 328d8111)
6. Probe v1.1 fix (firewall→iq)      ✅ done (commit 6991cbf7)
7. User runs --discover              ✅ done — picked maven repo + saw 56 repos
8. User runs full sweep              ✅ done — 21 of 58 endpoints succeeded
9. Recommendations doc               ✅ done (docs/research/2026-05-06-nexus-recommendations.md)
10. User decisions (§7 of recs doc)  ⏳ AWAITING USER
11. Implementation commit            ⏳ pending
12. Audit cleanup                    ⏳ pending (drop ACTIVE memories, propose PR/merge)
```

---

## Key files (recipe for context recovery)

Read these in order if rehydrating a session:

1. **`/Users/subhankarhalder/.claude/projects/.../memory/project_api_audit_in_progress.md`** — branch policy + per-service sequencing
2. **`/Users/subhankarhalder/.claude/projects/.../memory/project_nexus_version_probe_findings.md`** — full sweep findings + probe state
3. **`docs/research/2026-05-06-nexus-audit-brief.md`** — original brief
4. **`docs/research/2026-05-07-nexus-3.90-api-surface.md`** — version-scoped API surface (47 net-new candidates, 478 lines)
5. **`docs/research/2026-05-06-nexus-recommendations.md`** — recommendations (just landed; awaits user decisions)
6. **`tools/nexus-probe/probe_nexus.py`** — the probe driver (1,800+ lines, v1.1)
7. **`tools/atlassian-probe/Result_Nexus/bundle-sweep-maven-repo-compressed.txt`** — full-sweep raw bundle (paste back via `bundle.py unpack`)

---

## Confirmed about the user's instance

| Property | Value |
|---|---|
| Nexus version | **3.90.1-01** (released 2025) |
| Edition | **PRO** |
| Server header | `Nexus/3.90.1-01 (PRO)` (set on every response — primary version-detect) |
| Token scope | non-admin / read-only |
| Auth shape used | `--basic-token` (pre-encoded base64 from PluginSettings PasswordSafe) |
| Admin REST URL | `https://zo-zqau.sw.rfb.com/` (redacted; same as `--url`) |
| Docker registry URL | unknown — `/v2/*` returns 404 HTML on the admin URL |
| Repos visible | **56 total**: 16 maven2, 10 docker, 16 raw, 4 apt, 2 pypi, 1 nuget |
| HA enabled | NO — `/v1/system/node` returned 403 (also possibly admin-gated) |
| Replication via REST | NO — `/v1/replication/*` returns 404 (not in swagger.json) |
| Sonatype Firewall | YES — `/v1/malicious-risk/risk-on-disk` returned 200 + body |
| IQ Server | configured (6 `/iq/*` paths in swagger) but admin-gated for our token |
| OpenAPI inventory | **236 paths total** (105 repositories, 43 security, 15 blobstores, etc.) |

---

## Probe v1.1 capabilities (what's wired today)

- **3 modes:** `--versions-only`, `--discover`, full sweep
- **3 auth shapes:** `--user/--password`, Nexus user-token, `--basic-token`
- **2 auth schemes:** Basic (REST) + bearer-challenge (Docker `/v2/*`)
- **HEAD-only methods + `_request` validator** — read-only enforced at two layers
- **Response-header capture** — Server, Link, WWW-Authenticate, etc., persisted in raw/<name>.json
- **Version-detect ladder** — Server header → /status/check → swagger.json → rapture HTML scrape
- **OCI Accept-header dance** — 4 parallel HEADs against same manifest (catches multi-arch bug)
- **3.88 wildcard test** — ?q=neo* + ?q=*neo* (the latter returns 400 on user's instance — strong signal)
- **Per-format direct paths** — /repository/{r}/.../maven-metadata.xml + .sha256 sidecar
- **Hash reverse-lookup** — /v1/search?sha1=
- **65+ endpoints** in a full sweep

---

## Probe v1.2 anomalies to fix (deferred)

Small follow-up commit; not blocking implementation:

| Endpoint | Issue | Fix |
|---|---|---|
| `GET /v1/recovery-mode` | 405 (POST/DELETE only on 3.90) | Drop or convert to HEAD |
| `GET /v1/repositories/{r}/health-check` | 405 | Use `/health-check/summary` instead |
| `GET /service/rest/metrics/ping` | 406 (text/plain not application/json) | Add `expect_json=False` |
| `GET /v1/repositories/maven2/hosted/{r}` | 404 (per-format config) | Verify path against swagger.json |

---

## Three user decisions blocking implementation

From `docs/research/2026-05-06-nexus-recommendations.md` §7:

1. **Module placement** — `:core/nexus/` (cross-tab) or `:automation/api/` (alongside `DockerRegistryClient`)?
2. **UI surface** — Automation tab "Artifacts" sub-panel? Handover tab artifact-reference enrichment? Sprint tab "linked artifacts"? Agent-only (no UI)?
3. **Settings split** — add `nexusRestUrl` field separately, or reuse `nexusUrl` and assume admin URL?

---

## Implementation scope (~6 endpoints, single commit)

| # | Endpoint | Service method | Confirmed working? |
|---|---|---|---|
| 1 | `GET /v1/repositories` | `listRepositories()` | ✅ 200 |
| 2 | `GET /v1/components?repository={r}` (+ continuationToken) | `browseComponents(repo, token)` | ✅ 200 |
| 3 | `GET /v1/assets/{id}` | `getAsset(id)` | ✅ 200 |
| 4 | `GET /v1/search?sha1={h}` | `findByHash(sha1)` | ✅ 200 |
| 5 | `GET /repository/{r}/{g}/{a}/maven-metadata.xml` | `latestMavenVersion(repo, group, artifact)` | ✅ 200 |
| 6 | `GET /v1/malicious-risk/risk-on-disk` | `getMaliciousRiskCount()` | ✅ 200 (non-admin readable) |

Auth: HTTP Basic via `HttpClientFactory.sharedPool` + `AuthInterceptor`. Token from PasswordSafe.
Wraps as 6 agent tools. Tests + module CLAUDE.md + verifyPlugin before commit.
Commit message: `feat(nexus): add Nexus REST artifact browsing client (DC-only)`.
No Co-Authored-By trailer.

---

## Branch state

```
fix/automation-handover-quality-tabs
├── ...Jira commits...
├── ...Bitbucket commits...
├── c8c5c2c3 feat(nexus-audit): read-only probe_nexus.py (v0)
├── 328d8111 feat(nexus-audit): probe v1 + 3.90 Pro API research + README
├── 6991cbf7 fix(nexus-audit): swap phantom /v1/firewall/* for real /v1/iq/* + /v1/malicious-risk/*
└── (next) feat(nexus): add Nexus REST artifact browsing client (DC-only)
```

When all three audits ship: PR / merge / rebase as one cohesive API-audit unit per branch policy.

---

## Continuation prompt for a fresh session

If you're starting a new session, paste this verbatim:

> I'm continuing the Nexus API audit on `fix/automation-handover-quality-tabs`. The recommendations doc just landed at `docs/research/2026-05-06-nexus-recommendations.md`. Read this handoff first: `docs/research/2026-05-07-nexus-audit-handoff.md`. Then read the recommendations doc to see the three open user decisions. Don't start implementation until I've answered all three.

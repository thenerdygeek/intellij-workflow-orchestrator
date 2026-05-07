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

## User decisions (answered 2026-05-07)

From `docs/research/2026-05-06-nexus-recommendations.md` §7:

1. ✅ **Module placement: `:core/nexus/`** — user wants it in `:core` so future agent tools can consume the service directly. Both the client impl AND the service interface go in `:core/nexus/`. (Implementation deviation from the recs doc, which had impl in `:automation/api/`. Update the impl plan accordingly.)
2. ⏸ **UI surface: deferred** — user said "mostly in automation and handover" but wants a **separate discussion on UI requirements** before any UI commit. Implementation commit ships **client + service + agent tools only — NO UI changes**.
3. ❓ **Settings split: still open** — user questioned the recommendation. Awaiting their answer to one of:
   - Option 1: add `connections.nexusRestUrl` alongside `connections.nexusUrl` (backwards-compatible, recommended)
   - Option 2: repurpose `nexusUrl` as admin URL, infer Docker URL via `/v1/repositories` (breaks existing Docker config on upgrade — avoid)
   - Option 3: auto-detect by probing `${nexusUrl}/service/rest/v1/status` at startup (single settings box in common case)

## Docker probe — pending in a different session

Per user (2026-05-07): they're running the optional Docker probe and will paste results in a **different session**. The current probe (v1.1) does NOT need updating for the Docker run.

Command:
```bat
python tools\nexus-probe\probe_nexus.py ^
    --url https://zo-zqau.sw.rfb.com/ ^
    --docker-registry-url <DOCKER_REGISTRY_URL> ^
    --basic-token <YOUR_BASE64_BLOB> ^
    --docker-repo <YOUR_PICK>
```

What it'll exercise:
- ~50 endpoints total
- Re-runs admin REST (gives a fresh data point — most should match the prior bundle)
- Full Docker advanced suite: OCI 4-Accept dance, blob HEAD, `/v2/_catalog` + `/v2/.../tags` pagination, OCI `/referrers` (unverified for 3.90)

When the bundle lands, that session should:
1. Unpack and analyse like the Maven sweep
2. Append a "Docker enhancements" section to `docs/research/2026-05-06-nexus-recommendations.md` with the OCI media-type findings (which the plugin's current single-Accept logic likely mishandles for multi-arch tags)
3. Update memory + handoff with what was learned

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

**Per user decision (2026-05-07): ALL of this lives in `:core/nexus/`** — both
the client impl and the service interface. NO UI changes in this commit
(UI deferred until separate requirements discussion). Wait for the user's
settings-split answer (or Docker probe results, whichever comes first)
before implementing.

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

### Variant A — receiving the Docker probe bundle

> I'm continuing the Nexus API audit on `fix/automation-handover-quality-tabs`. I just ran the Docker-side probe and the bundle is at `tools/atlassian-probe/Result_Nexus/bundle-<name>-compressed.txt`. Read `docs/research/2026-05-07-nexus-audit-handoff.md` first for full context (including the 3 user decisions — only #3 is still open). Then unpack the bundle, analyse the Docker advanced suite (OCI Accept dance, blob HEAD, `/v2/_catalog`/tags pagination, referrers), and append a "Docker enhancements" section to `docs/research/2026-05-06-nexus-recommendations.md`. Update the handoff + memory after.

### Variant B — answering the settings-split question

> I'm continuing the Nexus API audit. I'm answering decision #3 (settings split) — see `docs/research/2026-05-07-nexus-audit-handoff.md` for the three options. My answer: [option N, with reasoning].

### Variant C — kicking off the implementation commit

> I'm ready to land the Nexus implementation. Read `docs/research/2026-05-07-nexus-audit-handoff.md` for the full plan. Per user decisions: all in `:core/nexus/`, NO UI changes. Settings split: [N]. Implement the 6-endpoint scope from the handoff §"Implementation scope", commit as `feat(nexus): add Nexus REST artifact browsing client (DC-only)`, no Co-Authored-By.

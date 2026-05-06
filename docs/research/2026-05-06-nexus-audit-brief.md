# Nexus API Audit — Session Brief

**Branch:** `fix/automation-handover-quality-tabs` (same branch as Jira and Bitbucket; do not create new branches)
**Status:** Not started. This is **session 3** of a 3-step audit (Jira → Bitbucket → **Nexus**).
**Predecessors:** Jira and Bitbucket commits should already be on the branch.

---

## 1. Mission

Audit Nexus / Docker-registry connectivity, propose new features the plugin currently does without, and (if approved) wire them in. Different shape from Jira/Bitbucket: this is **mostly an additive audit**, not a verification of existing code — the plugin uses very little of Nexus today.

---

## 2. Toolkit

The `tools/atlassian-probe/` redact + bundle scripts are reusable. Probe driver lives in a sibling directory because Nexus is a different product family with different auth.

| Tool | Path | Reuse as-is? |
|---|---|---|
| `redact.py` | `tools/atlassian-probe/redact.py` | Yes. Add Nexus-specific custom-word markers (Docker repo names, image tags) via `CUSTOM_REDACT_WORDS` if needed. |
| `bundle.py` | `tools/atlassian-probe/bundle.py` | Yes. |
| Nexus probe driver | needs to be written: `tools/nexus-probe/probe_nexus.py` (separate dir) | **Author this.** Different auth model than Atlassian probes — see §3. |

Why a sibling directory: Nexus auth is **HTTP Basic** (Nexus REST) and **OAuth bearer-challenge** (Docker Registry v2), neither of which is the Atlassian Bearer-PAT pattern the existing scripts assume. A sibling dir keeps the per-product probes cleanly separated.

---

## 3. What the plugin actually uses today

**Single client:** `automation/src/main/kotlin/com/workflow/orchestrator/automation/api/DockerRegistryClient.kt`

**Two endpoints, both Docker Registry HTTP API v2:**
```
GET {registryUrl}/v2/{repoName}/tags/list?n=100      # paginated tag listing
GET {registryUrl}/v2/{repoName}/manifests/{tag}      # tag manifest (digest)
```

**Auth:** OAuth bearer-challenge — first request returns `WWW-Authenticate: Bearer realm=...,service=...`, client follows to the realm to get a token, retries the original request. Memory entry `project_docker_registry_isolation.md` says this client is **deliberately isolated** from `HttpClientFactory` because the factory's `AuthInterceptor` would break the challenge flow.

**No native Nexus REST today.** The plugin doesn't call `/service/rest/v1/...` at all — that's the entire opportunity surface for this audit.

### Files touching the registry

```
automation/src/main/kotlin/.../api/DockerRegistryClient.kt          # the client
automation/src/main/kotlin/.../model/DockerRegistryDtos.kt          # DTOs
automation/src/main/kotlin/.../service/DockerTagService.kt          # service layer (if it exists)
automation/src/main/kotlin/.../ui/AutomationDashboardPanel.kt       # UI consumer
core/src/main/kotlin/.../auth/AuthTestService.kt                    # Nexus connection test (isolated)
```

`AuthTestService` already pings `GET /v2/` to verify connectivity — that endpoint must exist for any auth flow to work.

---

## 4. Candidate endpoints to probe

This is where the audit's value lies. The user said: *"we save our snapshots and docker tags and release jars in nexus"* — meaning Nexus 3 holds Docker images **and** Maven/Java artifacts. The plugin currently only sees the Docker side.

### Native Nexus REST API v1 (Nexus 3)

| Endpoint | Why probe / potential feature |
|---|---|
| `GET /service/rest/v1/status` | Connectivity dashboard; confirms instance is Nexus 3 |
| `GET /service/rest/v1/system/about` | Version detection (mirrors Atlassian's `/serverInfo`) |
| `GET /service/rest/v1/repositories` | Repo list — lets the user pick an artifact repo, not just docker repos |
| `GET /service/rest/v1/components?repository={repo}` | List components (artifacts) — could power a "find latest snapshot" UI |
| `GET /service/rest/v1/components/{id}` | Component detail incl. download URL — direct release-jar access |
| `GET /service/rest/v1/search?repository={repo}&format=maven2&group=...` | Search Maven artifacts by group/name/version — replaces "manually browse Nexus website" |
| `GET /service/rest/v1/search/assets?repository={repo}&maven.groupId=...` | Asset-level search — gives `downloadUrl` for each match |
| `GET /service/rest/v1/assets?repository={repo}&continuationToken=...` | Browse repo assets with pagination |
| `GET /service/rest/v1/repositories/{repo}/health-check` | Health for a specific repo |
| `GET /service/rest/v1/security/realms/active` | Auth schemes available — confirms HTTP-Basic vs token |

### Docker Registry v2 (already partially used)

| Endpoint | Why probe |
|---|---|
| `GET /v2/` | Connectivity (already used) |
| `GET /v2/_catalog?n=100` | List **all** Docker repos in the registry — useful for "tag picker" UI |
| `GET /v2/{repo}/tags/list?n=100` | Tag list (already used) |
| `GET /v2/{repo}/manifests/{tag}` (HEAD vs GET) | HEAD is cheaper for digest checks |

### Auth-test endpoint

`GET /service/rest/v1/security/users` — requires admin permissions; the response (200 vs 403) tells the user whether their token has read-write or read-only Nexus access.

---

## 5. Recommendations the audit will likely produce

These are pre-written hypotheses; the probe will confirm or deny each:

1. **"Browse our snapshots and release jars from inside the IDE"** — wire a Nexus REST client (separate from `DockerRegistryClient`, same isolation reasoning) that talks to `/service/rest/v1/components` and `/service/rest/v1/search`. New service in `:core` (e.g., `core/nexus/NexusArtifactClient.kt`), thin UI in the Automation tab.
2. **"Find which Docker tags exist matching a pattern"** — already partially possible via current `tags/list`; could be improved by `/v2/_catalog` to enumerate repos first.
3. **"Confirm a tag exists before triggering automation"** — replace blind tag input with autocomplete backed by `tags/list`. Probe + design only; don't build the UI in this audit.
4. **"Show artifact download URLs in a Sprint or PR detail panel"** — high reach, but cross-tab UI work that should be deferred to a follow-up.

---

## 6. Step-by-step plan for this session

```bash
# 1. Pull (Jira + Bitbucket commits should be on the branch)

# 2. Build tools/nexus-probe/probe_nexus.py
#    Modes:
#      --versions-only   /service/rest/v1/status + /system/about
#      --discover        /service/rest/v1/repositories → list repos for user to pick
#      Full sweep        all endpoints in §4

#    Auth handling:
#      --user/--password (HTTP Basic for /service/rest/v1/...)
#      --docker-registry (Docker Registry URL, defaults to same host)

# 3. Hand the user a discover command, get repo names back

# 4. Full sweep, redact, bundle (using the Atlassian-probe scripts even
#    though the data is from a different product — the redactor is
#    payload-shape-agnostic, just need to add Docker repo names to
#    CUSTOM_REDACT_WORDS)

# 5. Compile docs/research/2026-05-06-nexus-recommendations.md

# 6. Get user approval — Nexus is largely additive, so the user picks
#    which capabilities to build. Resist scope creep: this audit closes
#    when a Nexus client is wired up + the agreed endpoints are exposed
#    through the existing :automation surfaces.

# 7. Implement in ONE commit:
#    - New NexusArtifactClient (HTTP Basic)
#    - Wire it through a service interface in :core
#    - Add automation-tab UI hook only if the approved scope includes it
#    - Update :automation/CLAUDE.md and :core/CLAUDE.md
#    - ./gradlew :automation:test :core:test verifyPlugin before commit

# 8. Push. Audit complete.

# 9. Final cleanup:
#    - Delete the ACTIVE memory file (project_api_audit_in_progress.md)
#    - Remove its line from MEMORY.md index
#    - PR / merge / rebase the branch as one cohesive API-audit unit
```

Commit message: `feat(nexus): add Nexus REST artifact browsing client (DC-only)` — no Co-Authored-By trailer.

---

## 7. User constraints — already in memory

Same as Jira/Bitbucket: read-only probes, one commit per service, autonomous on architecture / consult on UI, work on **this branch**. Nexus/Docker token: user runs probes from Windows with personal credentials.

---

## 8. Things NOT to touch in this session

- Jira / Bitbucket / Sonar / Bamboo modules
- `DockerRegistryClient` auth flow — it's intentionally isolated (memory `project_docker_registry_isolation.md`); do NOT migrate it to `HttpClientFactory`
- `AuthTestService.kt` — same, isolated by design
- The dirty agent WIP files in the user's working tree
- Nexus 2 endpoints — the user is on Nexus 3; if they aren't, document it but don't try to support both

---

## 9. After this commit lands

1. Delete memory file `project_api_audit_in_progress.md` and remove its index line in `MEMORY.md` (per the original "remove this file when all 3 are done" instruction at the top of that file).
2. The `fix/automation-handover-quality-tabs` branch now contains three logically grouped commits (Jira, Bitbucket, Nexus). Coordinate with the user on whether to PR / merge / rebase as one unit.

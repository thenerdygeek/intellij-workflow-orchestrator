# Nexus Repository 3.90.1-01 Pro — API Audit Recommendations

> **Date:** 2026-05-07
> **Target:** Nexus Repository Manager 3.90.1-01 Pro (DC, single-host)
> **Token scope:** non-admin / read-only — confirmed by probe v1
> **Source bundles:**
> - `tools/atlassian-probe/Result_Nexus/bundle-versions-only-uncompressed.txt` (5 endpoints)
> - `tools/atlassian-probe/Result_Nexus/bundle-discover-compressed.txt` (10 endpoints)
> - `tools/atlassian-probe/Result_Nexus/bundle-sweep-maven-repo-compressed.txt` (58 endpoints)
> **Companion:**
> - [`docs/research/2026-05-06-nexus-audit-brief.md`](2026-05-06-nexus-audit-brief.md)
> - [`docs/research/2026-05-07-nexus-3.90-api-surface.md`](2026-05-07-nexus-3.90-api-surface.md)

---

## 1. Top-line verdict

The plugin currently uses Nexus only via the Docker Registry v2 API
(`DockerRegistryClient` → `/v2/{repo}/tags/list`, `/v2/{repo}/manifests/{tag}`).

The audit confirms **5 high-value swaps** and **3 net-new feature wins** the
plugin can adopt by adding a Nexus REST client on the existing
`connections.nexusUrl` host. None of the wins require admin scope on the
user's token. The user's instance also exposes Sonatype Firewall data at a
non-admin endpoint — a bonus surface beyond the brief.

**Recommended scope for the implementation commit:** new
`core/nexus/NexusArtifactClient.kt` (HTTP Basic, separate isolation from
`DockerRegistryClient`), thin `:core` service interface returning
`ToolResult<T>`, and 6 initial REST endpoints wired through. UI hook to
discuss with the user (Automation tab artifact sub-panel vs Handover tab
artifact-reference enrichment).

---

## 2. Confirmed swaps — proven 200s on this instance

Each one replaces a multi-call dance the plugin would otherwise need.

### 2.1 `/repository/{repo}/{groupPath}/{artifactId}/maven-metadata.xml` — find latest version

`GET /repository/jmtgdw4ap_ocvmelpvq/com/atj/xhcbbg/y2xe-zbba-aetkbme/maven-metadata.xml`
→ **200, 88 ms, application/xml** (with sibling `.sha256` also 200).

Maven/Gradle's native path. Repo-read perm only — non-admin token works.
Returns `<release>`, `<latest>`, `<versioning><versions><version>` — one
XML fetch beats the multi-call `/v1/search?sort=version&direction=desc`
dance.

**Recommendation:** for "what's the latest release / snapshot of `g:a`",
prefer this path over the JSON search API. Use `.sha256` sidecar for
integrity validation.

### 2.2 `/v1/assets/{id}` — direct asset access with full checksum trio

`GET /service/rest/v1/assets/{base64-asset-id}` → **200, 71 ms** with:

```json
{
  "downloadUrl": "https://...",
  "path": "/com/atj/.../...0.0.1-20250916.132447-332.pom",
  "checksum": {
    "md5": "...", "sha1": "853c5f53...",
    "sha256": "2fd28327...", "sha512": "03496567..."
  },
  "fileSize": 15702,
  "lastModified": "2025-09-16T13:25:41.039+00:00",
  "blobStoreName": "default",
  "uploader": "tvc41460",
  "uploaderIp": "92.120.147.244",
  "maven2": {"groupId": "com.atj.xhcbbg", "artifactId": "...", "version": "...", "extension": "pom"}
}
```

**Recommendation:** the plugin can power "show me artifact details" UI with
one call. The `uploader` + `uploaderIp` fields are bonus audit data, but
the plugin should NOT surface them in user-facing UI without explicit
opt-in (PII concern).

### 2.3 `/v1/search/assets/download?sort=version&direction=desc` — one-call download

`GET /service/rest/v1/search/assets/download?repository=X&format=maven2&sort=version&direction=desc`
→ **302, 212 ms, redirect to a binary URL.**

The redirect *is* the answer — Nexus computes the matching asset and
returns its absolute downloadUrl. Replaces "search → pick → fetch URL"
with one round-trip.

**Note:** the probe's summary marks this as "FAIL 302" because
`allow_redirects=False` is the probe's safety policy (redirects can mask
auth-scheme drift). For the plugin, the implementation should
explicitly handle 302 by capturing `Location:` and either
following it or returning the URL to the user.

**Recommendation:** wire as the "download latest jar / pom" primitive.

### 2.4 `?sort=version&direction=desc` on `/v1/search` — newest-first ordering

`GET /v1/search?repository=X&format=maven2&sort=version&direction=desc`
→ **200, 959 ms.** Items returned in descending semver order. Removes
client-side sort; halves response handling complexity.

**Recommendation:** apply this sort to every "find latest" query. Ditto
`prerelease=false` (also 200) for "skip snapshots".

### 2.5 Wildcard search semantics — `?q=*X*` returns 400, not silent empty

`GET /v1/search?q=neo*` → **200**
`GET /v1/search?q=*neo*` → **400 Bad Request**

This is a stronger signal than the research predicted. On 3.88+ SQL
search, leading-or-middle wildcards are **rejected with 400**, not
silently returning an empty array. The plugin can use 400 as an explicit
"that query is invalid on this Nexus version" signal and either rewrite
the query or surface the error.

**Recommendation:** plugin's eventual autocomplete UI must enforce
trailing-only wildcards client-side (3.88+ contract); on a 400 from the
server, surface a friendly "leading wildcards aren't supported on
your Nexus version" message rather than treating it as a generic search
failure.

---

## 3. Confirmed net-new features — proven on non-admin token

### 3.1 Hash-based reverse lookup — `/v1/search?sha1={hash}` (or `sha256=`)

`GET /v1/search?sha1=853c5f53403d5e96c4feaf1a62e56dec616b8d19` → **200,
239 ms.** Returns matching components with full asset metadata.

Answers "I have this jar in my .m2 / classpath / Docker layer — what is
it?" — a use-case the plugin doesn't currently support. Particularly
valuable when triaging conflict resolution in builds and verifying
artifact provenance.

**Recommendation:** wire as a `right-click jar → identify in Nexus`
intention or as an agent tool (`identify_artifact_by_hash`).

### 3.2 Sonatype Firewall risk visibility — `/v1/malicious-risk/risk-on-disk`

`GET /v1/malicious-risk/risk-on-disk` → **200, 251 ms** with body:

```json
{"totalCount": 0, "hdsExceptionThrown": true}
```

**Surprise: this endpoint is non-admin readable** on this instance, even
though the OpenAPI/Sonatype docs imply admin gating. Returns the count of
known-malicious artifacts currently sitting in proxy repos. The
`hdsExceptionThrown: true` field suggests a backend health flag (Health
Data Service may have errored — flag for investigation).

**Recommendation:** if `totalCount > 0`, the plugin can show a
"Repository security risk" badge in the Automation tab. This is a real
safety surface for the team. The companion `/malicious-risk/enabledRegistries`
endpoint **does** require admin (returned 403), so the plugin can only
display the count, not the per-registry breakdown.

### 3.3 Cross-format browsing — beyond just docker

`GET /v1/repositories` returns **56 repos** across 6 formats:
maven2 (16), docker (10), raw (16), apt (4), pypi (2), nuget (1).

The plugin's current "Nexus settings" only exposes the docker side. There
is meaningful demand for browsing the rest — large repos like
`IIOT_TEST` (6.9 TB raw), `bl-rfp-inv-nexus` (1 TB maven2),
`o3ba-gtzbxs-ybgbae` (15 TB docker hosted) suggest active multi-format
artifact storage.

**Recommendation:** the new `NexusArtifactClient` should be format-agnostic.
Initial UI scope: maven2 only (matches the "Sprint / PR / Build" workflow).
Raw / apt / pypi support deferred until the user requests it.

---

## 4. Recommended implementation scope

### 4.1 New module surface

```
core/src/main/kotlin/com/workflow/orchestrator/core/
  ├── services/NexusArtifactService.kt       (interface, returns ToolResult<T>)
  ├── model/nexus/NexusModels.kt              (NexusRepoSummary, NexusComponent, NexusAsset)
  └── ...

automation/src/main/kotlin/com/workflow/orchestrator/automation/
  ├── api/NexusArtifactClient.kt              (impl — HTTP Basic, OkHttp via HttpClientFactory.sharedPool)
  └── service/NexusArtifactServiceImpl.kt     (wraps the client, returns ToolResult)
```

Why `:automation`: the existing Nexus integration (`DockerRegistryClient`)
already lives there, and the artifact-browser UI naturally belongs near
the Docker tag picker. Alternative: split client into `:core/nexus/` if
the user wants the Sprint or Handover tab to consume artifact data — flag
for the user.

### 4.2 First-pass endpoint set

Six endpoints — every one a 200 in the audit:

| # | Endpoint | Service method | Purpose |
|---|---|---|---|
| 1 | `GET /v1/repositories` | `listRepositories(): ToolResult<List<NexusRepoSummary>>` | Repo picker |
| 2 | `GET /v1/components?repository={r}&continuationToken={ct}` | `browseComponents(repo, token): ToolResult<NexusComponentPage>` | Browsing |
| 3 | `GET /v1/assets/{id}` | `getAsset(id): ToolResult<NexusAsset>` | Drill-down + downloadUrl |
| 4 | `GET /v1/search?sha1={h}` | `findByHash(sha1): ToolResult<List<NexusComponent>>` | Reverse lookup |
| 5 | `GET /repository/{r}/{g}/{a}/maven-metadata.xml` | `latestMavenVersion(repo, group, artifact): ToolResult<MavenMetadata>` | "Find latest" |
| 6 | `GET /v1/malicious-risk/risk-on-disk` | `getMaliciousRiskCount(): ToolResult<Int>` | Firewall badge |

### 4.3 Auth + isolation policy

- **`Authorization: Basic <base64>`** — same scheme as
  `DockerRegistryClient`. Token comes from PluginSettings via PasswordSafe
  (already wired — `connections.nexusToken`).
- **Use `HttpClientFactory.sharedPool`** with the standard
  `AuthInterceptor` — unlike `DockerRegistryClient`, the new client has
  no bearer-challenge requirement, so the shared interceptor is safe.
- **Do NOT migrate `DockerRegistryClient`** — it stays isolated per
  `project_docker_registry_isolation.md` (its bearer-challenge flow
  conflicts with `AuthInterceptor`).
- **`AuthTestService`'s `/v2/` ping** stays as-is for the connection
  test — the new REST client uses `/v1/status` instead, but only after
  PluginSettings explicitly distinguishes "Nexus REST URL" from "Docker
  registry URL" (see §4.5).

### 4.4 Agent tools

Wrap the six service methods as agent tools, mirroring the existing Jira
/ Bitbucket pattern:

```
nexus_list_repositories
nexus_browse_components
nexus_get_asset
nexus_find_by_hash
nexus_latest_maven_version
nexus_malicious_risk_count
```

All return the same `ToolResult<T>` and produce a meaningful `.summary`
for the agent's chain-of-thought.

### 4.5 Settings UI change — split nexusUrl into two fields

The plugin currently has one `connections.nexusUrl` setting which today
points at the **Docker registry**. The audit confirmed the admin REST URL
and the Docker registry URL are different on this user's instance, and
this is the common Sonatype deployment topology.

**Recommendation:** add a `connections.nexusRestUrl` field alongside the
existing `connections.nexusUrl` (which becomes "Nexus Docker Registry
URL"). For backwards compatibility, leave the existing field alone; if
`nexusRestUrl` is empty, derive it from `nexusUrl` (often they're the
same root).

This is the only PluginSettings change required, but it's user-facing —
flag for the user before implementing.

---

## 5. Out of scope for this audit

Items the audit confirmed exist but are **not** recommended for
inclusion in the implementation commit:

| Surface | Reason |
|---|---|
| Docker registry expansion (`/v2/_catalog`, OCI accept dance, blob HEAD) | Needs a separate run with `--docker-registry-url`. The user has 10 docker repos but the registry sits on a different host than the admin URL. Defer to a follow-up commit. |
| All admin-only endpoints (security, blobstores, tasks, capabilities, email, config) | 403 for non-admin token. Useful for capability mapping but not user-facing. |
| Replication API (`/v1/replication/*`) | Returned 404 — confirmed not exposed via REST on this instance. Replication is admin-UI-only here. |
| HA cluster (`/v1/system/node`) | 403 — Pro+HA either not enabled or admin-gated. |
| IQ Server integration (`/v1/iq/audit`) | 403 — admin-gated. Plugin can detect IQ presence via the catch-all `/swagger.json` which lists 6 `/v1/iq/*` paths. |
| Pro tags (`/v1/tags`) | 403 — admin-gated despite Pro license. |
| Cleanup policies (`/v1/cleanup-policies`) | 403 — admin-gated despite Pro license. |
| Recovery mode (`/v1/recovery-mode`) | 405 — endpoint exists but rejects GET (POST/DELETE only on 3.90). Plugin can probe it once at startup if needed. |

---

## 6. Probe v1.2 anomalies — known issues to fix in a small follow-up

Not blocking the recommendations, but worth a small fix-up commit before
the next audit run on a different instance:

| Symptom | Endpoint | Fix |
|---|---|---|
| 405 Method Not Allowed | `GET /v1/recovery-mode` | Endpoint is POST/DELETE on 3.90 — drop the GET probe or change to a `_rest_head` so we just confirm path existence. |
| 405 Method Not Allowed | `GET /v1/repositories/{r}/health-check` | Same — RHC is admin-write-only on 3.90. The summary endpoint is `/v1/repositories/{r}/health-check/summary`. |
| 406 Not Acceptable | `GET /service/rest/metrics/ping` | Probe sends `Accept: application/json` by default; ping returns `text/plain pong`. Add `expect_json=False` so Accept negotiation succeeds. |
| 404 | `GET /v1/repositories/maven2/hosted/{r}` | Per-format config path may have changed in 3.90 — verify against `swagger.json` (`/v1/repositories/{format}/{type}/{name}` is documented but returned 404 here; possibly admin-gated). |

---

## 7. Decisions for the user before implementation starts

Three choices to make before I cut the implementation commit:

1. **Module placement** — `:core/nexus/` (cross-tab) or `:automation/api/` (alongside `DockerRegistryClient`)?
2. **UI surface** — Automation tab "Artifacts" sub-panel? Handover tab artifact-reference enrichment? Sprint tab "linked artifacts"? Or agent-only (no UI; tools are exposed only through chat)?
3. **Settings split** — accept the `nexusRestUrl` + `nexusUrl` split (§4.5), or keep one field and assume the user puts the admin URL there (Docker registry derived per-call)?

Once the user confirms, the implementation commit lands as one cohesive
unit per `project_api_audit_in_progress.md` policy.

---

## 8. After the implementation commit

Per `project_api_audit_in_progress.md` cleanup steps:

1. Delete `project_api_audit_in_progress.md` (and its index line in
   `MEMORY.md`)
2. Delete `project_nexus_version_probe_findings.md`
3. Delete `project_bitbucket_version_probe_findings.md` (if Bitbucket also
   complete)
4. PR / merge / rebase the `fix/automation-handover-quality-tabs` branch
   as one cohesive API-audit unit (Jira + Bitbucket + Nexus commits).

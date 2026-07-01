---
name: DEFERRED 2026-05-07 — Nexus probe + impl (low priority, may skip)
description: Nexus probing/impl deprioritized to bottom of backlog 2026-05-07. May be skipped entirely. Do NOT proactively pick up; only resume if user explicitly asks.
type: project
originSessionId: ecc35573-422c-4d12-a441-3aec01d69276
---
**Status:** ⏸ **DEFERRED 2026-05-07 — priority dropped to bottom of backlog after design discussion.** May be skipped entirely. Do not proactively suggest continuing; only resume if user explicitly raises Nexus again. Probe scaffolding (3 commits) is on `fix/automation-handover-quality-tabs` and remains usable if/when needed.

**Why it was deprioritized:** the workflow lock-in (see below) collapsed the impl scope to ~2 settings fields + a thin wrapper around existing `DockerRegistryClient`. Other items on the user's plate now outrank this small remaining piece. If skipped permanently, the probe additions can stay (read-only tooling, no plugin runtime impact) — only the recommendations doc + this memory need cleanup.

## Confirmed
- **Version:** 3.90.1-01 (release year 2025; recent — many newer endpoints likely available)
- **Edition:** Pro (HA, replication, advanced security, repository health check, cleanup policy automation, etc. all in scope)
- **Token scope:** non-admin / read-only — `/security/realms/active` returned 403; expected. Many admin-only endpoints will 403 in the full sweep, which is fine: the response code itself is informative for capability mapping.
- **Auth:** plugin owner used `--basic-token` (pre-encoded base64 blob from PasswordSafe). Auth path verified — no 401s, just permission-driven 403s.
- ~~**REST URL ≠ Docker registry URL:**~~ **SUPERSEDED 2026-05-07** — user confirmed they have ONE Nexus URL, not two. The original 404 on `/v2/` was misread as "wrong host"; actually means Docker is exposed via a SUB-PATH (path-based connector). See "Nexus path-based Docker" section below.

## Open items
1. ~~User to share the **Docker registry URL**~~ **RESOLVED 2026-05-07** — single URL, no separation. Probe defaults `--docker-registry-url` to `--url`.
2. User to confirm whether their non-admin token has any privileges beyond default read (affects which feature endpoints succeed).

## Full-sweep results (2026-05-07, run 2)
- 58 endpoints probed, **21 successes** (36%), 37 failures (mostly 403 admin markers — the informative outcome)
- Repos: 56 visible (16 maven2 hosted/group/proxy, 10 docker, 16 raw, 4 apt, 2 pypi, 1 nuget). The plugin only sees the docker side today.
- **Confirmed swaps** (200s): /repository/{r}/{group}/{artifact}/maven-metadata.xml + .sha256 sidecar; /v1/search?sort=version&direction=desc; /v1/search/assets/download (302 = success); HEAD vs GET manifest.
- **Confirmed net-new**: /v1/search?sha1={hash} reverse-lookup; /v1/assets/{id} full checksum trio + downloadUrl in one call; /v1/malicious-risk/risk-on-disk **non-admin readable** (Sonatype Firewall surface).
- **Wildcard semantics** (3.88+ SQL search): ?q=neo* → 200, ?q=*neo* → **400 Bad Request** (not silent empty — even cleaner signal for plugin autocomplete).
- **Probe v1.2 anomalies** to fix: /recovery-mode + /health-check return 405 (GET not allowed on this version); /metrics/ping returns 406 (Accept-header bug — sends application/json but ping returns text/plain); /repositories/maven2/hosted/{r} returns 404 (per-format config path syntax wrong).
- **Confirmed absent**: /replication/* not exposed via REST; HA cluster not enabled (/system/node 403); Replication module not licensed/enabled.

## Recommendations doc landed at
`docs/research/2026-05-06-nexus-recommendations.md` (2026-05-07).

## Workflow LOCKED 2026-05-07 (supersedes earlier scope)

Walked through the actual workflow with the user; original 6-endpoint REST scope was over-built. Real flow:

1. **Source of truth**: Bamboo plan variable `DockerTagsAsJson` (a single JSON blob `{service:tag}`) on the user's branch CI plan AND on a **6 AM daily automation reference plan** that pre-pins all services to latest release tags.
2. **Plugin merges** daily-reference (base) + branch-build (override) → final tag map. Never asks Nexus for "latest of 50 services."
3. **Nexus is consulted ONLY for the current repo**. Two calls: `HEAD /v2/<path>/manifests/<tag>` (verify) and `GET /v2/<path>/tags/list` (fallback "use latest release"). Both already implemented in `automation/api/DockerRegistryClient.kt`.
4. **Settings split resolved**:
   - Per-repo new field on `RepoConfig`: `nexusDockerFolderPath` (e.g. `company-team/service-a-repo`) — added in implementation commit. JSON keys differ from Nexus paths so this is required.
   - Project-level new field on `PluginSettings`: `dailyAutomationReferencePlanKey` (Bamboo plan key for the 6 AM build).
   - Existing `RepoConfig.dockerTagKey`, `RepoConfig.bambooPlanKey`, `PluginSettings.bambooBuildVariableName="DockerTagsAsJson"` already cover the rest. `connections.nexusUrl` continues to point at the Docker registry.
5. **Out of scope for this commit**: the entire Maven REST API, repo browsing, malicious-risk endpoints, search/sha1 reverse lookup. The big recommendations doc at `docs/research/2026-05-06-nexus-recommendations.md` is now over-scoped — keep on file but DO NOT implement.

## Nexus path-based Docker — confirmed via UI browse URL (2026-05-07)

User opened the Nexus admin UI, navigated into a Docker tag, and shared the URL: `<host>/#browse/browse:docker-group:v2%2Fcompany-team%2Frepo-name%2Ftags`.

**Decoded:** Inside the Nexus repo named `docker-group`, the asset is stored at path `v2/company-team/repo-name/tags`. This confirms TWO things:
1. Nexus's internal storage layout for a Docker repo starts with `v2/...` (the Docker registry v2 spec's path layout, kept verbatim as the storage tree).
2. The Docker repo this user pulls from is a Nexus **group** repo named `docker-group` (or similar — name redacted in conversation but structure is `<name>:`).

**Inferred Docker registry HTTP route:** `<nexus-host>/repository/docker-group/v2/<image>/manifests/<tag>` — Nexus 3's path-based connector pattern. That's the URL Docker clients (and our probe) need to hit. The admin UI's `#browse/browse:` is JavaScript routing (never sent to server); the real client URL goes through `/repository/<repo-name>/<storage-path>`.

**Caveat:** Path-based Docker is an OPT-IN feature on each Docker repo (Nexus admin sets connector type to "Path"). If the next probe still 404s with `--docker-base-path /repository/docker-group`, path-based isn't enabled and we need either: (a) admin to enable it, (b) the dedicated connector port (not currently known).

## Probe state (2026-05-07, 3 commits this session)

- `48cb8d84` feat: `--docker-only` mode added (5-call workflow scope: `/v2/`, tags/list, manifest HEAD+GET, bogus-tag HEAD).
- `5b957b29` fix: filename sanitization — `docker_repo` containing `/` was being used verbatim in `raw/<name>.json` and breaking with ENOENT. Fix replaces `/` with `_` for filenames; URL paths unchanged. Applies to `run_docker_only`, `run_full`, and `run_discover`.
- `5dd1814c` fix: TWO bugs surfaced when probe ran against real Nexus (2026-05-07 11:21 run):
  - **Encoding bug**: `urllib.parse.quote(docker_repo, safe="")` encoded `/` as `%2F` — wrong per Docker Registry v2 spec (slashes are literal path-component separators inside `<name>`). Plugin's `DockerRegistryClient.kt` doesn't url-encode at all. Fixed: `safe="/"` in all four Docker-repo quote sites.
  - **/v2/ not at root**: added `--docker-base-path` flag so probe can prepend `/repository/<docker-group>` (or any prefix) to every Docker request. Threaded through `NexusProbe.__init__`, `_docker_request`, `_docker_head_accept`. Default empty preserves existing behavior.
- **Last `--docker-only` run** (2026-05-07 11:21): all 5 endpoints 404'd with HTML "Sonatype Nexus Repository" pages (= admin UI 404, not Docker registry's structured 404). Diagnosed as the two bugs above. Output bundle at `tools/atlassian-probe/Result_Nexus/bundle-docker-tag-uncompressed.txt`.
- **Awaiting** re-run with `--docker-base-path /repository/<docker-group-name>` + path-encoding fix to confirm path-based Docker works on this Nexus.

## Implementation scope (post-probe)

Thin wrapper around existing `automation/api/DockerRegistryClient.kt` + two new settings fields + a small UI surface in Automation tab:
- New per-repo field: `RepoConfig.nexusDockerFolderPath` (e.g. `company-team/service-a-repo`).
- New project-level field: `PluginSettings.dailyAutomationReferencePlanKey` (Bamboo plan key for the 6 AM build).
- NO new `core/nexus/NexusArtifactClient.kt` — original audit memo's 6-endpoint REST scope is over-built for this workflow.
- If the probe confirms path-based Docker works, the plugin needs one tweak too: `connections.nexusUrl` users should set the URL to `<host>/repository/<docker-group>/` (with the path baked in) so `DockerRegistryClient`'s existing `${registryUrl}/v2/...` URL build works without changes. Decision pending probe result.

## Probe v0 issues to fix in v0.1
- `/service/rest/v1/system/about` is a **phantom endpoint** — does not exist in Nexus 3 public REST. Drop it. Replace version-detect with: (a) `Server:` HTTP header capture on every response, (b) HTML-scrape of `/` for the cache-buster query param `?<version>` on rapture asset URLs.
- Capture HTTP response headers in `raw/<name>.json` — currently only status + body are persisted; headers carry version, auth challenge, and rate-limit info.
- Document `--url` (Nexus admin REST) vs `--docker-registry-url` (Docker `/v2/*`) split clearly in `--help` and README.

## Why "FAIL" rows in v0 summary aren't all real failures
- `/status` and `/status/writable` returned 200 with empty body — that IS the spec-compliant Nexus health response. The probe's "OK 200" framing was correct; the empty-body framing borrowed from the Atlassian probes (which return JSON) caused some apparent confusion.
- `/realms/active` 403 confirms token works AND lacks admin scope; the response code is the signal we wanted.
- `/v2/` 404 HTML body LEAKED the version — `?3.90.1-01` cache-buster on a `/static/rapture/resources/...` asset URL. "rapture" is Sonatype's Nexus 3 UI framework codename.

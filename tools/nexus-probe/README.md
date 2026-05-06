# Nexus API Probe

Read-only HTTP probe for Sonatype Nexus Repository Manager 3 — both the JSON
REST API and the Docker Registry v2 API. Server / Data Center only — Cloud
out of scope.

| Script | Status | Coverage |
|---|---|---|
| `probe_nexus.py` | ✅ ready (v0.1, 2026-05-07) | Version-detect ladder + 65+ candidate endpoints across system, security, repo admin, components, assets, search, format-specific, Docker v2 (incl. OCI multi-arch), Pro-only + 3.90-new |

This script lives in a sibling directory to `tools/atlassian-probe/` because
Nexus is a different product family. The redact + bundle helpers in
`tools/atlassian-probe/` are **payload-shape-agnostic** and reusable as-is on
the output here.

## Install

```bash
pip install requests
```

That's the only dependency.

## Authentication — three input shapes

All resolve to `Authorization: Basic <base64>` over the wire. Pick whichever
is most convenient.

| Shape | Args | When to use |
|---|---|---|
| Plain user + password | `--user me --password p4ss` | Local Nexus user with a regular password |
| Nexus user token | `--user <token-name> --password <token-pass-code>` | "Settings → User Token" in the Nexus UI generates a token-name + pass-code pair; Nexus treats them as username + password at the wire level |
| Pre-encoded base64 blob | `--basic-token <base64-of-user:pass>` | Pasting the value the IntelliJ plugin stores in PasswordSafe (PluginSettings → Connections → Nexus). Accepts a leading `Basic ` prefix and strips it. |

`--basic-token` is mutually exclusive with `--user` / `--password`.

## Run — Nexus

### Quick: just detect your version

```bash
python probe_nexus.py --url https://nexus.company.com \
    --user my.user --password <PAT> --versions-only
```

Hits 7 endpoints + captures the `Server:` HTTP response header on every
response. The `Server: Nexus/X.Y.Z-NN (EDITION)` header is set on **every**
Nexus response (including 401/403s), so version detection works even before
auth succeeds.

The probe also runs a 4-step fallback ladder, in order:

1. `Server:` header parsed via regex (primary)
2. `GET /service/rest/v1/status/check` (renamed from `/service/metrics/healthcheck` in 3.81)
3. `GET /service/rest/swagger.json` — `info.version` field; documented as
   "does not require any privilege to access"
4. HTML-scrape of `/` for the rapture cache-buster (`?3.90.1-01` on
   `/static/rapture/...` asset URLs)

Open `Result_N/summary.md` afterwards; the **Version detection** section
shows version + edition + the source. Paste that section back to the plugin
owner so the recommendations doc can be scoped to your version.

### Full sweep

```bash
python probe_nexus.py \
    --url https://nexus.company.com \
    --user my.user --password <PAT> \
    --maven-repo maven-releases \
    --docker-repo my-images
```

Picks any real repo names you have read access to. The sweep auto-derives
sub-arguments from the first response page:

- A real `groupId` + `artifactId` → exercises `maven-metadata.xml` and the
  `.sha256` sidecar at the format-handler path (`/repository/{repo}/...`)
- A real asset id → exercises `/v1/assets/{id}` for direct downloadUrl
- A real SHA1 → exercises `/v1/search?sha1=...` (reverse-lookup feature)
- A real Docker digest → exercises `/v2/{repo}/blobs/{digest}` HEAD and
  `/v2/{repo}/referrers/{digest}` (OCI 1.1 — unverified for 3.90)

Anything you omit is skipped, not failed.

The sweep also fires **two empirical version probes** that don't need any
flag:

- `?q=neo*` AND `?q=*neo*` — distinguishes 3.88+ SQL search (trailing
  wildcards only) from pre-3.88 Elasticsearch (any wildcards).
- All 4 Docker manifest Accept variants (`v2 manifest`, `manifest list`,
  `OCI image-index`, `OCI image-manifest`) — answers "does this tag point at
  a multi-arch image?" Critical because the IntelliJ plugin currently picks
  one Accept and silently mishandles multi-arch images.

### Discover mode (find your repo names)

If you don't already know which repo names to use:

```bash
python probe_nexus.py --url https://nexus.company.com \
    --user my.user --password <PAT> --discover
```

This:

1. Runs versions-only first (so you also get version-detect data)
2. Lists `/v1/repositories` and `/v2/_catalog`
3. Picks the first hosted maven2 repo and the first Docker repo
4. Hits one component listing + one tag list to confirm reachability with
   real names
5. Writes `Result_N/discover.md` with a copy-paste full-sweep command seeded
   with the picked names

If you have no maven2 / no docker repos, the digest section calls that out
explicitly and the suggested command falls back to placeholder names.

### Separate Docker registry hostname

Many Nexus deployments expose the Docker registry on a different host or
port from the admin REST API:

```bash
python probe_nexus.py \
    --url https://nexus.company.com \
    --docker-registry-url https://docker.company.com \
    --user my.user --password <PAT> \
    --maven-repo maven-releases --docker-repo my-images
```

If the admin URL `/v2/` returns a 404 with HTML body (Nexus's web 404
template) instead of an empty 200 / 401, you're hitting the admin host
without the Docker connector — pass the registry URL separately.

### Self-signed certificates

```bash
python probe_nexus.py --url https://internal-nexus/ ... --no-verify
```

## What gets written

```
tools/nexus-probe/
└── Result_N/                      # auto-incremented per run
    ├── summary.md                 # markdown overview, paste this back
    ├── discover.md                # only in --discover mode
    └── raw/
        ├── rest_status.json
        ├── rest_status_writable.json
        ├── rest_status_check.json
        ├── rest_swagger_json.json
        ├── rest_root_html.json
        ├── docker_v2_root.json
        ├── repositories_all.json
        ├── components_<repo>.json
        ├── assets_<repo>.json
        ├── search_*_<repo>.json
        ├── docker_tags_<repo>.json
        ├── docker_manifest_*_<repo>.json   # 4 OCI Accept variants
        └── ...
```

Each `raw/*.json` carries:

- request metadata (method, path, category, auth_scheme)
- response metadata (status, elapsed_ms, payload_kind)
- **selected response headers** (`Server`, `Link`, `WWW-Authenticate`,
  `Location`, `Content-Type`, `Content-Length`, `ETag`, `Date`,
  `Docker-Content-Digest`, `Docker-Distribution-Api-Version`)
- the **full parsed body** (or first 500 chars for non-JSON / large
  responses)
- inline notes (auth challenge details, version-detection markers, etc.)

Diff `raw/` across runs to detect endpoint behaviour drift.

## Redacting before you share

Probe responses contain real artifact names, repo names, hostnames, user
display names, etc. Before sharing externally, run the **same redactor
shared with the Atlassian probes**:

```bash
python ../atlassian-probe/redact.py --in Result_1
# writes Result_1_redacted/ next to it; original is untouched
```

The redactor is shape-agnostic — it operates on string values, not JSON
schemas — so it works on Nexus output identically to Jira/Bitbucket output.

What stays:
- Status codes, timings, request paths
- JSON shape — every key, every nesting level, every type, presence/absence of fields
- Server: header (it's a product version, not company info)
- Repo formats (maven2/docker/raw/npm), repo types (hosted/proxy/group)

What gets replaced (stable per-run mapping):
- Hostnames → `nexus.redacted.example`
- Email addresses → `user-N@redacted.example`
- Free-text *length* preserved as `<redacted-text:N-chars>` so payload
  size remains visible

### Custom word list — Nexus-specific

Add to the `CUSTOM_REDACT_WORDS` list near the top of
`tools/atlassian-probe/redact.py`:

```python
CUSTOM_REDACT_WORDS: list[str] = [
    # ... existing entries ...
    # Nexus-specific identifiers worth scrubbing:
    "AcmeCorp",          # parent org
    "MyProduct",         # product name appearing in artifact names
    "internal-team",     # team name in repo URLs
    "<docker-repo>",     # specific Docker repo names if sensitive
    "<maven-group-id>",  # com.acme.proprietary, etc.
]
```

These are matched case-insensitively at word-boundaries and replaced with a
length-mirrored random string. Mapping is regenerated each run; never
written to disk.

## Bundling for one-shot sharing

```bash
python ../atlassian-probe/bundle.py pack --in Result_1_redacted
# writes Result_1_redacted.bundle.txt (plain, human-readable)
#
# Or with --compress for clipboard-too-big bundles:
python ../atlassian-probe/bundle.py pack --in Result_1_redacted --compress
# writes Result_1_redacted.bundle.b64.txt (gzip + base64, ~5–40× smaller)
```

The plain bundle is multipart UTF-8 with a UUID boundary and per-file
SHA256. The compressed bundle is gzip-then-base64 with an outer SHA256.
On the receiving side:

```bash
python ../atlassian-probe/bundle.py unpack --in Result_1_redacted.bundle.txt
# (.bundle.b64.txt also works — auto-detects compressed vs plain)
```

Unpack verifies SHA256 and refuses to write any file whose hash doesn't
round-trip — paste truncation or accidental edits are caught.

If everything is too big even compressed, just paste `summary.md` directly
— it's only 100–300 lines.

## Safety

- **Never mutates.** Only `GET` and `HEAD`. No POST/PUT/PATCH/DELETE for
  any path — even paths the script knows about (component upload, repo
  create, scheduled task run, etc. are inventoried in `summary.md` but
  never called).
- **Token is in-memory only.** Never written to any output file.
  Authorization headers are never logged.
- **`raw/*.json` may contain artifact + user data.** Run the redactor before
  sharing externally.
- **No retries on auth errors.** A 401 stops dead instead of locking your
  account.
- **All requests carry `User-Agent: WorkflowOrchestrator-Probe/1.0
  (read-only)`** so admins can audit probe traffic in Nexus's
  request log.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `/v2/` returns 404 with HTML body | Nexus admin URL doesn't serve the Docker registry — pass `--docker-registry-url <REG_URL>` separately. The admin UI returns its 404 template (which leaks the version in the rapture cache-buster — that's actually how probe v0 detected the user's 3.90.1-01 even when `/system/about` failed). |
| `/v1/security/realms/active` returns 403 | Token is non-admin. Expected — most security/admin endpoints will 403. The 403 itself is informative: it confirms auth works AND the token lacks admin scope. |
| All endpoints return 401 | `--user` / `--password` mismatch, or `--basic-token` is wrong. Try the same credentials with `curl -u user:pass https://nexus/.../v1/status` to isolate. |
| `/v1/system/about` returns 404 | This endpoint **does not exist** in Nexus 3's public REST namespace. Probe v0 incorrectly listed it (carryover from the Atlassian probe template); v0.1 dropped it. Version detection now uses the `Server:` header. |
| `?q=*foo*` silently returns empty array | You're on Nexus 3.88+ with SQL search — only trailing wildcards (`foo*`) work. Plugin autocomplete must respect this. |
| Self-signed cert errors | Add `--no-verify` |
| Multi-arch Docker tag returns Content-Type `vnd.oci.image.index.v1+json` | Expected — a multi-arch tag points at an OCI image-index, not a v2 manifest. Plugin should detect this Content-Type and follow into one of the per-arch manifests via `manifests[].digest`. |

## Why a separate Python script (and not Kotlin)?

Probes are throwaway diagnostic tools, not plugin code. Python keeps the
dependency footprint to one library, mirrors the existing
`tools/sourcegraph-probe/` and `tools/atlassian-probe/` patterns, and runs
identically on Mac and Windows without a JDK. The plugin's actual HTTP
clients (`HttpClientFactory` in `:core` and `DockerRegistryClient` in
`:automation`) are unaffected — the probe only verifies the *API surface*,
not the plugin's wiring.

## Provenance & companion docs

- `docs/research/2026-05-06-nexus-audit-brief.md` — original audit brief
- `docs/research/2026-05-07-nexus-3.90-api-surface.md` — version-scoped API
  surface research (47 net-new candidates, 110+ writes inventoried)

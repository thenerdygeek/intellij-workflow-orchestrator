# SonarQube API Probe

Read-only HTTP probe for SonarQube Server / Community Build / Data Center —
the same API surface the IntelliJ plugin's `:sonar` module consumes. Cloud
(SonarCloud) is **out of scope** — different host, different auth, different
endpoint paths.

| Script | Status | Coverage |
|---|---|---|
| `probe_sonar.py` | ✅ ready (v0, 2026-05-07) | Version + **edition** detection ladder + all 14 endpoints in `SonarApiClient.kt` + 6 candidate endpoints (analyses history, metric history, quality gate listing, languages, issue tags, current user) |

This script lives in its own directory because SonarQube is a different
product family from Atlassian (Jira/Bitbucket/Bamboo) and Sonatype (Nexus).
The redact + bundle helpers in `tools/atlassian-probe/` are
**payload-shape-agnostic** and reusable as-is on the output here.

## Install

```bash
pip install requests
```

That's the only dependency.

## Authentication

SonarQube uses **user tokens**. Generate one at:

> *Sonar UI → top-right avatar → My Account → Security → Generate Tokens*

Pass it via `--token`. The probe sends it as
`Authorization: Bearer <token>`, matching the plugin's
`SonarApiClient.kt` (which uses `HttpClientFactory(ServiceType.SONARQUBE)`
→ `Authorization: Bearer ...` per `:core` § Auth).

> **Why not HTTP Basic?** Sonar's legacy auth (token-as-username with empty
> password, base64-wrapped as Basic) is still accepted by the server but
> the plugin uses Bearer. The probe matches the plugin so any auth quirk
> we surface here also applies to plugin behaviour.

## Run

### 1. Quick: detect version + edition

```bash
python probe_sonar.py --url https://sonar.company.com \
    --token <YOUR_USER_TOKEN> --versions-only
```

Hits 4 endpoints. The crucial one is `/api/navigation/global` — it returns
the `edition` field, which is **the feature gate the plugin lives or dies
by**:

| `edition` | Plugin impact |
|---|---|
| `community` | `branch=` silently ignored on issues / measures / gate; `/api/hotspots/search` returns 404; `/api/project_branches/list` returns only main; new-code period is project-scoped only |
| `developer` | Full branch + new-code-period support; hotspots available |
| `enterprise` | Developer + governance + portfolios (none used by plugin yet) |
| `datacenter` | Enterprise + HA endpoints (plugin is HA-agnostic) |

Open `Result_N/summary.md` afterwards; the **Version + edition detection**
section spells out the implications. **Paste that section back to me** so
the recommendations doc can be scoped to your tier.

> SonarQube 25.x renamed Community Edition → "Community Build". The
> `edition` field still reports `community` for it.

### 2. Discover mode — find your project key + branch + CE task id

If you don't already know the key + branch + CE task id to seed the full
sweep:

```bash
python probe_sonar.py --url https://sonar.company.com \
    --token <YOUR_USER_TOKEN> --discover \
    --project-key MY_PROJECT_KEY    # scope to one project (recommended)
```

Without `--project-key`, discover walks the first 5 visible projects
alphabetically — usually unrelated on large instances, so always pass the
flag when you know it.

This:

1. Runs versions-only first (so you also get version + edition)
2. Lists `/api/components/search?qualifiers=TRK` for the global walk
   (skipped when `--project-key` is set)
3. For each candidate project: `/api/project_branches/list` +
   `/api/ce/activity?ps=5`
4. Captures the most recent CE task id + branch from the first project
5. Writes `Result_N/discover.md` with a copy-paste full-sweep command
   seeded from those values

### 3. Full sweep

```bash
python probe_sonar.py --url https://sonar.company.com \
    --token <YOUR_USER_TOKEN> \
    --project-key MY_PROJECT_KEY \
    --branch main \
    --file-key 'MY_PROJECT_KEY:src/main/java/com/example/Foo.java' \
    --rule-key java:S1135
```

Probes every endpoint `SonarApiClient.kt` calls, plus a handful of
candidates. Endpoints that need a missing CLI arg are reported as `SKIP`
in summary.md, not failed.

| Arg | Enables |
|---|---|
| `--project-key X` | branches list, gate, issues, measures (file + project), CE activity, new-code period, hotspots |
| `--branch X` | branch param on issues / measures / gate / new-code-period (silently ignored on Community) |
| `--file-key X` | duplications, sources/lines |
| `--rule-key X` | rules/show (defaults to `java:S1135` — Sonar Way bundled rule, present on every install) |
| `--ce-task-id X` | ce/task by id (without it, the sweep auto-lifts a task id from `ce/activity` if any) |

File keys follow the `<projectKey>:<path-from-repo-root>` convention. You
can find one in the SonarQube UI by opening any file in the Code tab —
the URL contains it as the `id=` query param.

### Self-signed certificates

```bash
python probe_sonar.py --url https://internal-sonar/ ... --no-verify
```

## What gets written

```
tools/sonar-probe/
└── Result_N/                      # auto-incremented per run
    ├── summary.md                 # markdown overview, paste this back
    ├── discover.md                # only in --discover mode
    └── raw/
        ├── server_version.json
        ├── system_status.json
        ├── navigation_global.json
        ├── auth_validate.json
        ├── components_search.json
        ├── project_branches_list.json
        ├── quality_gate_status.json
        ├── issues_search.json
        ├── issues_search_new_code.json
        ├── measures_component_tree.json
        ├── measures_component.json
        ├── ce_activity.json
        ├── ce_task.json
        ├── new_code_period_show.json
        ├── hotspots_search.json
        ├── duplications_show.json
        ├── sources_lines.json
        ├── rules_show.json
        ├── languages_list.json
        ├── qualitygates_list.json
        ├── project_analyses_search.json
        ├── measures_search_history.json
        ├── issues_tags.json
        └── users_current.json
```

Each `raw/*.json` carries:

- request metadata (method, path, category)
- response metadata (status, elapsed_ms, payload_kind)
- the **full parsed body** (or first 500 chars for plain-text /
  unparseable responses)
- inline notes (edition gate hints, version markers, etc.)

Diff `raw/` across runs to detect endpoint behaviour drift across Sonar
upgrades.

## Redacting before you share

Probe responses contain real project keys, branch names, file paths, user
display names, etc. Before sharing externally, run the **same redactor
shared with the Atlassian probes**:

```bash
python ../atlassian-probe/redact.py --in Result_1
# writes Result_1_redacted/ next to it; original is untouched
```

The redactor is shape-agnostic — it operates on string values, not JSON
schemas — so it works on Sonar output identically to Jira/Bitbucket/Bamboo
output.

What stays:
- Status codes, timings, request paths
- JSON shape — every key, every nesting level, every type
- Sonar version + edition (these are product facts, not company info)
- Edition-related implications

What gets replaced (stable per-run mapping):
- Hostnames → `sonar.redacted.example`
- Email addresses → `user-N@redacted.example`
- Free-text *length* preserved as `<redacted-text:N-chars>` so payload
  size remains visible

### Custom word list — Sonar-specific

Add to the `CUSTOM_REDACT_WORDS` list near the top of
`tools/atlassian-probe/redact.py`:

```python
CUSTOM_REDACT_WORDS: list[str] = [
    # ... existing entries ...
    # Sonar-specific identifiers worth scrubbing:
    "AcmeCorp",            # parent org
    "MyProduct",           # product name appearing in project keys
    "com.acme",            # group id appearing in file component keys
    "internal-team",       # team name in branch names
]
```

Matched case-insensitively at word boundaries and replaced with a
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

If everything is too big even compressed, just paste `summary.md` directly
— it's only 80–200 lines depending on which sweeps ran.

## Safety

- **Never mutates.** Only `GET`. No POST/PUT/DELETE — and nothing to
  inventory either, because the plugin's `:sonar` module makes **zero**
  writes against SonarQube. It's a pure read consumer (gate, issues,
  measures, hotspots, duplications, sources, rules, CE).
- **Token is in-memory only.** Never written to any output file.
  Authorization headers are never logged.
- **`raw/*.json` may contain project + branch + file path data.** Run the
  redactor before sharing externally.
- **No retries on auth errors.** A 401 stops dead instead of looping.
- **All requests carry `User-Agent: WorkflowOrchestrator-SonarProbe/1.0
  (read-only)`** so admins can audit probe traffic in SonarQube's
  access log.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| All endpoints return 401 | Token expired or revoked. Generate a new one in My Account → Security. |
| `/api/hotspots/search` returns 404 | You're on Community edition. Confirm via `edition` in `Result_N/raw/navigation_global.json`. |
| Branch param silently ignored (responses are identical with and without `--branch`) | Community edition — branch features are gated to Developer+. |
| `/api/server/version` returns HTML | URL is wrong — you're hitting the SonarQube web shell, not the API. Drop any trailing `/dashboard` etc. from the URL. |
| `/api/users/current` returns 403 | Some Sonar instances disable the endpoint via plugin policy. Non-fatal. |
| `Content-Type` mismatch on `/api/server/version` | Some reverse proxies inject `text/html` even though the body is plain text. Probe captures the body verbatim regardless. |
| Self-signed cert errors | Add `--no-verify` |

## Why a separate Python script (and not Kotlin)?

Probes are throwaway diagnostic tools, not plugin code. Python keeps the
dependency footprint to one library, mirrors the existing
`tools/atlassian-probe/`, `tools/nexus-probe/`, and
`tools/sourcegraph-probe/` patterns, and runs identically on Mac and
Windows without a JDK. The plugin's actual HTTP client
(`HttpClientFactory(ServiceType.SONARQUBE)`) is unaffected — the probe
only verifies the *API surface*, not the plugin's wiring.

## Workflow lock

Per the project's audit pattern (see project memory
`feedback_audit_research_after_version.md`):

1. **First run** — `--versions-only` against your live server. Paste back
   the **Version + edition detection** block from `Result_N/summary.md`.
2. **Then** — deep API research happens against your specific
   version + edition. Without that, recommendations are guesses.
3. **Then** — full sweep with `--project-key` + `--branch` etc. to confirm
   each endpoint behaves as the research expects.
4. **Then** — integration commit on `fix/automation-handover-quality-tabs`
   adopting whatever new endpoints are warranted.

Skipping step 1 is the workflow violation that motivated this lock.

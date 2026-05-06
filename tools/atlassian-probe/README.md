# Atlassian API Probe

Read-only HTTP probes for the Atlassian endpoints the Workflow Orchestrator IntelliJ plugin
talks to. Server / Data Center only — Cloud is out of scope.

Each product has its own script, runnable independently:

| Script | Status | Coverage |
|---|---|---|
| `probe_jira.py` | ✅ ready | Jira Server REST v2 + Agile + dev-status (internal) + candidate v3 + recommendations |
| `probe_bitbucket.py` | ⏳ step 2 | Bitbucket Data Center `/rest/api/1.0/` + build-status + default-reviewers |

Nexus has its own dir (`nexus-probe/`, step 3) because it's a different product family.

## Install

```bash
pip install requests
```

That's the only dependency.

## Run — Jira

### Quick: just detect your version

```bash
python probe_jira.py --url https://jira.company.com --token <PAT> --versions-only
```

This calls only `/rest/api/2/serverInfo`, `/rest/api/2/myself`, `/rest/api/2/myself?expand=…`,
and `/rest/api/2/mypermissions` — four GETs, no params required. Open `Result_N/summary.md`
afterwards; the **Version detection** section shows the Jira version + deployment type. Paste
that back into the chat so we know which feature set your DC supports.

### Full sweep

```bash
python probe_jira.py \
    --url https://jira.company.com \
    --token <PAT> \
    --issue-key PROJ-123 \
    --board-id 42 \
    --sprint-id 1234 \
    --project-key PROJ
```

Picks any real values you have access to. Anything you omit just skips the probes that need it
— no failure. The `--issue-key` enables the dev-status probes (which need the issue's numeric
id), so include one if you can.

### Discover mode (find your IDs)

If you don't already know your project key / board id / sprint id, run:

```bash
python probe_jira.py --url https://jira.company.com --token <PAT> --discover
```

This finds issues where you're assignee/reporter/watcher, extracts the project keys
from those issues (usually 1–3, never the entire org's ~200), and pulls boards
filtered to those projects + their sprints. Output goes to `Result_N/discover.md`
with a copy-paste command line at the bottom seeded with real values.

If you're a brand-new account with zero issues, it falls back to listing your
readable projects and tells you so explicitly.

### Self-signed certificates

```bash
python probe_jira.py --url https://internal-jira/ --token <PAT> --no-verify ...
```

## What gets written

```
tools/atlassian-probe/
└── Result_N/                 # auto-incremented
    ├── summary.md            # markdown overview, paste this back to me
    └── raw/
        ├── serverInfo.json
        ├── myself.json
        ├── search_jql_v2.json
        ├── ...
```

Each `raw/*.json` has the request metadata, response status / time, and the **full parsed body**
so we can diff against future probe runs and detect endpoint behaviour drift.

## Redacting before you share

The probe saves real Jira data (issue summaries, your username, comments, hostnames). Before
sharing the results, run `redact.py` to swap identifying values for stable placeholders:

```bash
python redact.py --in Result_1
# writes Result_1_redacted/ next to it; original is untouched
```

What stays (so analysis still works):
- Status codes, timings, request paths
- JSON shape — every key, every nesting level, every type, presence/absence of fields
- Server version info (version, buildNumber, deploymentType — these are product versions, not company info)
- Field IDs (customfield_*, system field IDs)
- Enum values: status.name, priority.name, issuetype.name, resolution.name, etc.
- Numeric IDs, booleans, timestamps
- Free-text *length* (replaced with `<redacted-text:142-chars>` so payload size is still visible)

What gets replaced (with stable per-run mapping — same value always maps to same placeholder):
- Your Jira hostname → `jira.redacted.example`
- Email addresses → `user-N@redacted.example`
- Issue keys (`MYPROJ-1234`) → `KEY-001` (project component preserved consistently)
- Project keys (`MYPROJ`) → `PROJ`
- User names / display names in user contexts → `user-N` / `User N`
- Avatar URLs → `https://redacted.example/avatar/N`
- Commit hashes → `<commit-N>`
- Dev-status branch displayIds → `<branch-N>`

### Custom word list (you can edit this)

Open `redact.py` and look for the `CUSTOM_REDACT_WORDS` list near the top:

```python
CUSTOM_REDACT_WORDS: list[str] = [
    "AcmeCorp",
    "MyProduct",
    "Redroom",
]
```

Any word you list there is replaced — case-insensitively, word-boundary
matched — with a same-length random string. Letters become random letters
(case mirrored), digits become random digits, hyphens / dots / spaces are
kept verbatim. The replacement is **stable within one run** (same word →
same fake string everywhere) and regenerated on every run.

Examples:
- `"AcmeCorp"` (8 chars) → `"TovqGjyu"`
- `"redroom"` (7 chars, lowercase) → matches because of case-insensitive lookup, and reuses the same replacement chosen for `"Redroom"`
- `"acmecorp-internal"` → only `acmecorp` is matched (hyphen is a word boundary), produces `"TovqGjyu-internal"`
- `"release-2026.05.06"` (not in the list) → kept verbatim

Add company / product / code / repo / team names that would identify your
environment in free-text fields. You don't need to add things already
covered by the structured redactors (hostnames, emails, issue keys,
display names, free-text descriptions).

The mapping itself is **never written to disk** — there's no "key file" you have to keep
secret. Once redacted, the original values are unrecoverable from the output. A
`redaction_report.json` is written with **counts only** (e.g., "12 emails redacted") so you
can sanity-check coverage without leaking values.

```
Result_1_redacted/
├── summary.md             # share this
├── redaction_report.json  # counts only, safe to share
└── raw/                   # share specific failures from here
    ├── serverInfo.json
    └── ...
```

## Safety

- **Never mutates.** Only `GET`. No transitions, comments, worklogs, branch creates, watchers, etc.
  Even if you pass `--issue-key`, the script will not touch any state on that issue.
- **Token is in-memory only.** Never written to any output file. The script logs URLs but never
  the `Authorization` header.
- **`raw/*.json` may contain ticket data.** Run `redact.py` before sharing externally.
- **No retries on auth errors.** A 401 stops dead instead of locking your account.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| All endpoints return 401 | PAT scopes wrong; or your IDP enforces SSO and PAT auth is disabled — try `Authorization: Bearer` against `/rest/api/2/myself` from curl to isolate |
| `/rest/dev-status/1.0/...` returns 404 across all `dataType` values | The Application Links between Jira and your Bitbucket / Bamboo aren't set up — Dev Status panel will be empty in Jira's own UI too |
| `/rest/api/3/search/jql` returns 404 / 405 | Expected on most DC versions today — Cloud-only at the time of writing |
| `serverInfo` says `deploymentType: Cloud` | This script targets Server only; please re-run against your DC base URL |
| Self-signed cert errors | Add `--no-verify` |

## Why a separate Python script (and not Kotlin)?

Probes are throwaway diagnostic tools, not plugin code. Python keeps the dependency footprint
to one library, mirrors the existing `tools/sourcegraph-probe/` pattern, and runs identically
on Mac and Windows without a JDK. The plugin's actual HTTP client (`HttpClientFactory` in
`:core`) is unaffected — the probe only verifies the *API surface*, not the plugin's wiring.

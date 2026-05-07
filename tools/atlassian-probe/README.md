# Atlassian API Probe

Read-only HTTP probes for the Atlassian endpoints the Workflow Orchestrator IntelliJ plugin
talks to. Server / Data Center only — Cloud is out of scope.

Each product has its own script, runnable independently:

| Script | Status | Coverage |
|---|---|---|
| `probe_jira.py` | ✅ ready | Jira Server REST v2 + Agile + dev-status (internal) + candidate v3 + recommendations |
| `probe_bitbucket.py` | ✅ ready | Bitbucket DC `/rest/api/1.0/` + capabilities + build-status + insights + Code Insights candidates |
| `probe_bamboo.py` | ✅ ready | Bamboo Server / DC `/rest/api/latest/` — all 24 read-only `BambooApiClient` calls + 8 feature-discovery candidates (deploy, agent, queue, jiraIssues, comments, changes, labels) |

Nexus has its own dir (`nexus-probe/`) because it's a different product family.

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

## Run — Bamboo

### Quick: just detect your version

```bash
python probe_bamboo.py --url https://bamboo.company.com --token <PAT> --versions-only
```

This calls only `/rest/api/latest/info`, `/rest/api/latest/serverInfo` (alias
check, usually 404), `/rest/api/latest/currentUser`, and
`/rest/api/latest/info/configurationProperties` (admin-gated, expected 401 on
non-admin tokens). Open `Result_N/summary.md` afterwards; the **Version
detection** section shows the Bamboo version, build, and state. Paste that
back into the chat so we know which feature set the deployment supports
before doing deep API research.

### Full sweep

```bash
python probe_bamboo.py \
    --url https://bamboo.company.com \
    --token <PAT> \
    --plan-key PROJ-PLAN \
    --result-key PROJ-PLAN-JOBSHORT-123 \
    --project-key PROJ \
    --branch-name feature/foo \
    --commit-sha abc123def
```

Pick any real values you have access to. Anything you omit is recorded as
`SKIP` in `summary.md` so the gap is visible — no failure. Use a **job-level**
`--result-key` (e.g. `PROJ-PLAN-JOBSHORT-123`, not the plan-level
`PROJ-PLAN-123`) to verify the build-log download endpoint returns the real
~30KB log; plan-level keys yield a tiny wrapper.

Coverage: 4 version probes + ~24 read-only `BambooApiClient` calls + 8
candidate endpoints we don't currently use (deployment projects, agent
list, build queue, Jira-issues per build, build comments, build VCS
changes, build labels, label list).

### Discover mode (find your IDs)

If you don't already know which plan / result / project / branch / commit
SHA values to feed the full sweep, run:

```bash
# Recommended on large instances — scope to your project
python probe_bamboo.py --url https://bamboo.company.com --token <PAT> --discover \
    --project-key MYPROJ

# Or scope to a single plan you already know about
python probe_bamboo.py --url https://bamboo.company.com --token <PAT> --discover \
    --plan-key MYPROJ-CI

# No scope (first 5 projects alphabetically — usually unrelated to your work)
python probe_bamboo.py --url https://bamboo.company.com --token <PAT> --discover
```

Bamboo's REST API has no per-user filter equivalent to Jira's
`assignee=currentUser()`, so without a scope flag the walk lists the
first 5 projects alphabetically — fine on small instances, useless on
ones with hundreds of projects. Pass `--project-key` (or `--plan-key`
for a single plan) to scope the walk to what you actually work on.

This walks `/project` → `/project/{key}?expand=plans.plan` → `/plan/{key}/branch`
→ `/result/{plan}` → `/result/{key}?expand=vcsRevisions` and writes
`Result_N/discover.md` with:

- **Top projects** you can read
- **Candidate plans** (up to 5) showing the latest build state, branch
  count, and a `dockerTagsAsJson?` flag — plans with that variable set are
  likely **automation suite** plans (Automation tab); plans without are
  usually **service CI** plans (Build tab). The plugin's
  `ConflictDetectorService` and `TagBuilderService` hard-code that variable
  name, so it's a reliable disambiguator.
- **Sample IDs** from the most recent build — including a job-level result
  key when one exists, so the build-log probe gets a real ~30KB log
- **Copy-paste commands** for both Unix and Windows seeded with the values
  found above

If your PAT can't see any projects (zero permissions), the digest still
writes — it just shows the empty state and skips the suggested command.

### Self-signed certificates

```bash
python probe_bamboo.py --url https://internal-bamboo/ --token <PAT> --no-verify ...
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

## Bundling for one-shot sharing

Probe results are 30+ files. Pasting them into chat one by one is annoying.
After redacting, run:

```bash
python bundle.py pack --in Result_2_redacted
# writes Result_2_redacted.bundle.txt (plain text)
```

If the resulting bundle is too big for your clipboard / chat input (typical
limit is around 100KB–1MB; a full sweep with 30+ files is ~3MB plain), add
`--compress`:

```bash
python bundle.py pack --in Result_2_redacted --compress
# writes Result_2_redacted.bundle.b64.txt — gzip + base64, typically 5–40× smaller
```

JSON compresses extremely well (lots of repeated keys), so a 3MB plain
bundle typically shrinks to ~80KB compressed. Paste the entire compressed
file the same way. On the receiving side:

```bash
python bundle.py unpack --in Result_2_redacted.bundle.txt
# (or .bundle.b64.txt — auto-detects compressed vs plain)
```

The plain bundle is human-readable UTF-8 multipart with a UUID boundary and
per-file SHA256. The compressed bundle is gzip-then-base64 (76-column
wrapped) with an outer SHA256 over the original. Either way, unpack
verifies integrity and refuses to write any file whose hash doesn't
round-trip — paste truncation or accidental edits are caught.

```
Result_2_redacted/                 ← input dir
├── summary.md
├── redaction_report.json
└── raw/...

         │ python bundle.py pack --in Result_2_redacted [--compress]
         ▼
Result_2_redacted.bundle.txt        (plain, human-readable)
or
Result_2_redacted.bundle.b64.txt    (compressed, ~5–40× smaller)

         │ paste into chat → save back as a file
         │ python bundle.py unpack --in <file>
         ▼
Result_2_redacted.unpacked/         ← byte-for-byte identical
├── summary.md
├── redaction_report.json
└── raw/...
```

### Still too big for your clipboard?

Worst case, just paste `summary.md` directly — it's only 100–300 lines and
fits anywhere. The receiver can request specific `raw/<name>.json` files
one at a time as the analysis needs them.

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

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

## Safety

- **Never mutates.** Only `GET`. No transitions, comments, worklogs, branch creates, watchers, etc.
  Even if you pass `--issue-key`, the script will not touch any state on that issue.
- **Token is in-memory only.** Never written to any output file. The script logs URLs but never
  the `Authorization` header.
- **`raw/*.json` may contain ticket data.** Inspect before sharing externally — it includes
  whatever your token can read (issue summaries, comments, user names).
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

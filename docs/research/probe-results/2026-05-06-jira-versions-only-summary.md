# Jira probe results — https://jira.redacted.example

- **Run at:** 2026-05-06T19:58:34+0530
- **Args:** `{"url": "https://jira.redacted.example/", "issue_key": null, "board_id": null, "sprint_id": null, "project_key": null, "no_verify": false, "versions_only": true}`
- **Total endpoints probed:** 4
- **Successful (2xx):** 4
- **Failed (4xx/5xx/error):** 0

## Version detection

- **version:** `10.3.16`
- **versionNumbers:** `[10, 3, 16]`
- **buildNumber:** `10030016`
- **deploymentType:** `Server`  ← must say `Server` (not `Cloud`)
- **buildDate:** `2026-01-07T00:00:00.000+0100`
- **scmInfo:** `d130bae65421e3c63021a7b379141874f1f8608f`


## Version endpoints

| Status | Endpoint | Description | Time | Notes |
|---|---|---|---|---|
| ✅ 200 | `GET /rest/api/2/serverInfo` | Server version + deployment type | 1249ms | Use this to fill in the Jira version question once you run the probe |
| ✅ 200 | `GET /rest/api/2/myself` | Connection check + current-user identity | 203ms |  |

## Candidate endpoints

| Status | Endpoint | Description | Time | Notes |
|---|---|---|---|---|
| ✅ 200 | `GET /rest/api/2/myself?expand=groups,applicationRoles` | Current user + groups + applicationRoles (candidate) | 246ms | Useful for showing role badges in the onboarding banner |
| ✅ 200 | `GET /rest/api/2/mypermissions` | Global permissions for the connected user (candidate) | 302ms | Use to gate transition/comment buttons in UI before user clicks |

## Raw responses

Each endpoint's full response (parsed JSON or text snippet) is saved to `raw/<name>.json`.
These can be diffed against future probe runs to detect schema drift.

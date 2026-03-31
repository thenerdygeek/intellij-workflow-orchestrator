# Bridge Contract Fixtures

These JSON fixtures define the contract between React (webview) and Kotlin (plugin).
Both sides test against the SAME fixtures to ensure format compatibility.

## How it works

1. Each bridge function has a fixture file: `{function-name}.json`
2. React tests (Vitest): import the fixture → assert the component sends this exact format
3. Kotlin tests (JUnit): load the fixture → assert the handler can parse this exact format
4. If EITHER side changes the format, tests break on the OTHER side

## Adding a new bridge contract

1. Create `{function-name}.json` in this directory
2. Symlink or copy to `agent/webview/src/__tests__/contracts/`
3. Write React test asserting the component produces this format
4. Write Kotlin test asserting the handler can parse this format

## Files

- `plan-revise.json` — _revisePlan(comments) payload
- `plan-approve.json` — _approvePlan() call (no payload)
- `plan-data.json` — renderPlan(json) payload (Kotlin → React)
- `plan-step-update.json` — updatePlanStep(stepId, status) payload
- `edit-stats.json` — updateEditStats(added, removed, files) payload
- `checkpoints.json` — updateCheckpoints(json) payload

# Handoff — Bamboo "agent can't see queued/running builds" (continue in a fresh session)

**Written:** 2026-06-06 · **Branch:** `perf/token-context-optimization` (pushed through `12a1c50b5`).

---
## ✅ RESOLVED 2026-06-06 (root cause found in the probe, exactly as this handoff demanded)

**Root cause = malformed `expand` param on the COLLECTION endpoint — NOT branch keys, NOT `/queue`, NOT `includeAllStates`.**
`getRunningAndQueuedBuilds` sent `expand=stages.stage.results.result` to `/result/{key}`. Bamboo only populates the top-level `results.result[]` array when the expand is prefixed with `results.result.`; without it the server returns just `results:{size:N}` with **no array**, so the client deserialised an empty list → every caller reported "no running builds." The user's anchor was right all along: `includeAllStates=true` was fine.

**Evidence (already in the committed probe bundle — no fresh probe needed):** same plan key `GWAB-S4XMTOTLPPWAXBK` on real DC 10.2.14 —
- `bundle-repo/raw/result_running_queued.json` (bare expand) → `results:{size:5}`, **no `result` array**.
- `bundle-repo/raw/result_recent.json` (`results.result.` prefix) → `result[]` with 10 populated entries.

**Fix (TDD, one line):** expand → `results.result.stages.stage.results.result` (matches the proven-working `getRecentResults`). The single API-layer change covers all four consumers (`get_running_builds`, `build_status`, `recent_builds`, `BambooMonitorSource`) — they all route through this method. New wire-contract regression test `getRunningAndQueuedBuilds expands the results result collection not just nested stages` in `BambooApiClientTest` pins the prefix (the old mocks never asserted the expand string). `:bamboo:test` full `--rerun-tasks` = 315 tests, 0 failures. Also updated `agent/src/main/resources/api-docs/bamboo.json` provenance + COLLECTION-EXPAND-TRAP gotcha, and `bamboo/CLAUDE.md`.

**Fix #2 (2026-06-06, same branch) — running build displayed as "unknown".** Even once the API returned the live build, the agent showed its state as "unknown". Root cause: Bamboo carries an *outcome* (`state`: Successful/Failed/Unknown) and a *phase* (`lifeCycleState`: Finished/InProgress/Queued/Pending/NotBuilt). A running build reports `state="Unknown"` (no outcome yet) while `lifeCycleState="InProgress"` — and a *stopped/not-fully-built* build ALSO reports `state="Unknown"` with `lifeCycleState="NotBuilt"`. The `:core`-model mappers in `BambooServiceImpl` collapsed state via `dto.state.ifBlank { lifeCycleState }`, which only falls back when `state` is *blank* — `"Unknown"` is non-blank, so the meaningless outcome leaked through for BOTH running and stopped builds. Ground truth: `tools/atlassian-probe/Result_Bamboo/manual-captures/running-vs-stopped-build.xml` (real DC; build 12 = InProgress/running, build 11 = NotBuilt/stopped, both `buildState=Unknown`; `lifeCycleState` is the discriminator — `buildDurationInSeconds=0` is only a correlate). Fix: new private `BambooServiceImpl.collapseBuildState(state, lifeCycleState)` — keep a real outcome (Successful/Failed); when the outcome is `"Unknown"`/blank, surface the lifecycle phase instead. Applied at all 5 collapse sites (getRunningBuilds, getRecentBuilds, getLatestBuild/mapBuildResult, stage, job). The raw `lifeCycleState` is still preserved verbatim on the model. Pinned by 2 new tests in `BambooMonitorFieldsTest` (running→"InProgress", stopped→"NotBuilt"). The running *filter* already keyed on `lifeCycleState` (TERMINAL={Finished,NotBuilt}), so detection was already correct — this is display-only. `:bamboo:test` full `--rerun-tasks` = 317 tests, 0 failures.

**REMAINING (the only open gate): live confirmation before `.9`.** Per lesson #4, passing unit tests ≠ working. Trigger a real build and ask the agent "is a build running for branch X?" (or browser-probe `…/result/{BRANCH_KEY}?includeAllStates=true&max-results=25&expand=results.result.stages.stage.results.result` and confirm the `result[]` array is now populated with the in-flight build). Only then cut `0.86.0-token-ctx.9`. `.9` is HELD pending this.

Everything below is the original (pre-resolution) handoff, kept for context.

---

## 0. Why this handoff exists
The previous session **drifted from the original Bamboo probe results** while chasing this bug — repeatedly re-deriving Bamboo API behavior instead of grounding in what the probe already validated, and at one point wrongly pivoting toward the `/queue` resource. The user's correction is the anchor for the next session:

> `includeAllStates=true` on `/result/{key}` is for **ALL** lifecycle states (Queued / Pending / InProgress / Finished) — not a queue-only thing.

**Do NOT re-invent Bamboo endpoint behavior. Re-read the probe artifacts (§4) FIRST, then gather real-server evidence, then fix at the API layer, then verify against the user's real Bamboo (not just unit tests).**

## 1. The symptom (user-reported, real)
- Asking the agent about a Bamboo build shows only the **last finished** build; an already **queued/running** build is invisible.
- The user called `get_running_builds` **directly with plan key + branch** and it still missed the running build (this eliminated the "LLM picked the wrong action" theory).
- Browser probe of `GET /rest/api/latest/result/<branchKey>?includeAllStates=true&max-results=25&expand=stages.stage.results.result` returned **404 Not Found**. ⚠ UNCONFIRMED whether the user used the *real* branch plan key or a placeholder — re-confirm the real key before drawing conclusions (see §5 step 1).

## 2. Established facts to RESPECT (from the original probe — do not forget/re-derive)
- **Branch builds live ONLY under the branch plan key (the resolved `chainKey`, e.g. `PROJ-PLAN42`), never the master plan key.** Resolve `branch → chainKey` via `ChainKeyResolver`. There is a **"no fallback to master" contract** in `ChainKeyResolver` — never query/fall-back to the master plan key for a branch's builds (it returns the WRONG branch's results). See memory `feedback_bamboo_branch_chainkey_no_master_fallback`, `project_bamboo_api_probe_findings`.
- `includeAllStates=true` returns all states; the live-build filter excludes only terminal states (`TERMINAL_LIFECYCLE_STATES = {"Finished","NotBuilt"}`).
- The probe validated v0 endpoints against the **real DC server** (DC 10.2.14-era). Trust the probe shapes over assumptions; `BambooProbeShapeContractTest.kt` pins the response shapes.

## 3. What was changed already (committed + PUSHED on this branch)
| Commit | What | Status |
|---|---|---|
| `8377187bb` (= release `v0.86.0-token-ctx.8`) | build_status/recent_builds composite (getLatestBuild+getRunningBuilds); BambooMonitorSource composite poll; integration tests | Released; **partially right** — branch resolution good, but the running-builds endpoint behavior vs real Bamboo is UNVERIFIED |
| `b9b2c248a` | `get_running_builds` now resolves `branch → chainKey` (it previously ignored `branch` and queried the master key) | Pushed; correct in direction, **unverified end-to-end** |
| `12a1c50b5` | **Removed** the wrong master-plan-key fallback (added in `.8`) from `activeBuildsOrWarning` (build_status/recent_builds), `BambooMonitorSource.pickRunningBuild`, tests, and CLAUDE.md. Running-check now queries the resolved branch `chainKey` ONLY. | Pushed |

`.8` is released; **`.9` has NOT been cut** — hold it until the real-Bamboo behavior is confirmed.

⚠ Build-cache trap hit during this work: removing the `masterPlanKey` default param caused a `NoSuchMethodError` at runtime in a stale-compiled test (`BambooBuildsToolTest`). Run `:agent:test` for these changes with `--no-build-cache --rerun-tasks` (documented project trap).

⚠ Concurrent session owns uncommitted files in the tree (`SystemPrompt.kt`, `AskQuestionsTool*`, `prompt-snapshots/*`, `subagent-prompt-snapshots/*`, webview `QuestionView`/`types.ts`/`question-header`). NEVER `git add -A`; stage explicitly.

## 4. Authoritative references — READ THESE FIRST
- **Probe script:** `tools/atlassian-probe/probe_bamboo.py` — the source of truth for which endpoints/params return what on the real server. Re-run with `--versions-only` / against the user's server if needed.
- **Probe shape contract test:** `bamboo/src/test/kotlin/com/workflow/orchestrator/bamboo/service/BambooProbeShapeContractTest.kt` — pins the validated response shapes.
- **Audit docs:** `docs/research/2026-05-07-bamboo-write-ops-audit.md`, `docs/research/2026-05-07-bamboo-audit-recommendations.md`.
- **Plan-identity design:** `docs/superpowers/specs/2026-05-27-bamboo-plan-identity-design.md` (chainKey/branch-key model).
- **Memory:** `project_bamboo_api_probe_findings`, `project_bamboo_audit_in_progress`, `feedback_bamboo_branch_chainkey_no_master_fallback`.

## 5. The open question + recommended next steps (evidence-first, no guessing)
**Open question:** Where does Bamboo expose an in-flight (queued/running) build for a branch, and under which key/endpoint, on the user's real server? The 404 on `/result/{branchKey}` is the key unknown. Leading possibilities (DO NOT pick one without evidence):
- (a) The branch key used in the probe was wrong/placeholder → re-confirm the real key.
- (b) `/result/{key}` 404s when a plan has **zero finished results** (a branch whose only build is the first, still-running one) — even with `includeAllStates`. If so, the result feed isn't where a first-ever running build lives.
- (c) The original probe established a DIFFERENT correct endpoint/param combination for in-flight builds that the current `getRunningAndQueuedBuilds` doesn't match — RE-READ THE PROBE.

**Steps:**
1. **Re-read `probe_bamboo.py` + `BambooProbeShapeContractTest` + memory** to recover exactly what the probe validated for running/queued builds and `includeAllStates`. The user believes the answer is already there.
2. **Get real evidence from the user (browser probes, authenticated session returns XML by default; append `.json` for JSON):**
   - Real branch keys: `…/rest/api/latest/plan/<MASTER_KEY>/branch?max-results=100`
   - Branch result feed with the CONFIRMED key: `…/rest/api/latest/result/<BRANCH_KEY>?includeAllStates=true&max-results=25&expand=stages.stage.results.result`
   - Master result feed: `…/rest/api/latest/result/<MASTER_KEY>?includeAllStates=true&max-results=25`
   - Determine: does the running build appear, under which key, and what is its `lifeCycleState`?
3. **Fix at the API layer** (`BambooApiClient.getRunningAndQueuedBuilds`) per the confirmed endpoint/behavior, keeping the resolve-branch→chainKey + no-master-fallback contract. Ensure all four consumers stay consistent: `get_running_builds`, `build_status`, `recent_builds` (BambooBuildsTool), and `BambooMonitorSource`.
4. **Verify against the user's real Bamboo**, not only unit tests. KEY LESSON: unit tests mock `BambooService` and have passed green repeatedly while the real behavior was broken — passing tests here are NOT proof. Confirm with a real probe/agent run before cutting `.9`.
5. Cut `0.86.0-token-ctx.9` only after real-world confirmation (bump `gradle.properties`, `:agent:test --no-build-cache` + `verifyPlugin`, `clean buildPlugin`, push, `gh release create`). The monitor (`BambooMonitorSource`) fix rides the same release.

## 6. Key files + lines
- `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/api/BambooApiClient.kt` — `getRunningAndQueuedBuilds` (line ~284: `/result/{planKey}?includeAllStates=true&max-results=25&expand=…`; filter at ~294); `TERMINAL_LIFECYCLE_STATES` (~692); `getLatestResult` (~102, the finished-only `/result/{key}/latest`); generic `get()` (logs `[Bamboo:API] GET …` at debug).
- `bamboo/src/main/kotlin/com/workflow/orchestrator/bamboo/service/BambooServiceImpl.kt` — `getRunningBuilds` (~810; note `repoName` param is accepted but unused), `getLatestBuild` (~89).
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BambooBuildsTool.kt` — `get_running_builds` handler (resolves branch via `ChainKeyResolver.getInstance()?.resolveChainKey(project, planKey, branch)`), `executeBuildStatusForTest` / `activeBuildsOrWarning` (now single-key, line ~1110), `executeGetRunningBuildsForTest` (~1233), the `branch` param docs.
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/monitor/BambooMonitorSource.kt` — `fetch()` (~48), `pickRunningBuild(chainKey)` (~67, single-key, no master fallback), `resolveChainKey` (branch→key via getPlanBranches).
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/monitor/BambooDiff.kt` — build/stage/job diff incl. buildNumber transition.
- Tests: `BambooBuildsToolRunningVisibilityTest.kt`, `BambooBuildsToolGetRunningBranchTest.kt`, `BambooMonitorSourceTest.kt`, `BambooDiffTest.kt` (all assert no-master-fallback; `--no-build-cache` when signatures changed).

## 7. Lessons (do not repeat)
- Re-read the probe before changing Bamboo endpoint logic; the user has had to correct the branch-key model and `includeAllStates` semantics more than once.
- Never add a master-plan-key fallback (contract violation).
- Passing unit tests ≠ working — they mock the service. Validate against real Bamboo before claiming the fix works or releasing.
- `includeAllStates=true` returns ALL states from `/result/{key}` — the running-build path is about the right key + the right endpoint, not about `/queue`.

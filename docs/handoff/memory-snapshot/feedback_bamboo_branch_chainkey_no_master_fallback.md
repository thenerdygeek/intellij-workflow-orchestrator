---
name: feedback-bamboo-branch-chainkey-no-master-fallback
description: Bamboo branch builds live ONLY under the branch plan key (chainKey); resolve branch→chainKey for ALL plan-key build queries and NEVER fall back to the master plan key
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 42186784-96fc-4dfb-bf1d-fbc9ceab7d17
---

Bamboo branch builds live **only** under the branch plan key (the resolved `chainKey`, e.g. `PROJ-PLAN42`), **never** the master plan key. This is foundational to this project's Bamboo integration and was established in the original Bamboo probe (see [[project_bamboo_api_probe_findings]], [[project_bamboo_audit_in_progress]]).

Therefore: EVERY Bamboo build-query path (build_status, recent_builds, **get_running_builds**, the `BambooMonitorSource` monitor, BuildMonitorService) MUST resolve `branch → chainKey` via `ChainKeyResolver.resolveChainKey(project, planKey, branch)` and query **that key only**. There is a documented **"no fallback to master" contract in `ChainKeyResolver`** — querying or falling back to the master plan key for a branch's builds returns the WRONG branch's results and is a bug.

**Why:** Recurring lapse the user flagged ("did you forget this again?"). `get_running_builds` shipped ignoring `branch` (queried master → branch's running build invisible), and worse, a **master-plan-key fallback** was wrongly added in `v0.86.0-token-ctx.8` (`activeBuildsOrWarning` + `BambooMonitorSource.pickRunningBuild`) — a hedge against an unconfirmed "branch collection 404" hypothesis that directly violates the no-master-fallback contract. Both removed.

**How to apply:** When touching ANY Bamboo plan-key build-query tool/source, audit EVERY action for branch resolution — not just the obvious ones. Resolve branch→chainKey; query that key only; never add a master fallback. The non-silent "could not verify running builds" warning on a genuine API error is fine; silently/explicitly substituting master builds is not. Also: a Bamboo `/result/{key}?includeAllStates=true` query returns InProgress/Queued/finished for THAT key; confirm with a real probe before assuming queued-not-started builds appear there vs `/rest/api/latest/queue`.

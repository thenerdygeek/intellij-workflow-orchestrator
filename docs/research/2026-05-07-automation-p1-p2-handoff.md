# `:automation` P1+P2 mop-up — handoff for next session

**Branch**: `fix/automation-handover-quality-tabs` (pull before starting; multiple sessions are working on this branch)
**Spec**: `docs/research/2026-05-07-automation-handover-audit.md` — read it. This handoff summarises but does not replicate it.
**Predecessor commits**:
- `a2e1deeb fix(automation): land P0 audit findings — registry path, settings preservation, polling lifecycle` — closed A-P0-2..5 + A-P1-1
- `213fe148 refactor(automation): remove Docker registry tag-validation flow` — L2 Nexus removal
- `bc50576c refactor(core): purge remaining Nexus connection infrastructure (L3)`
- `9c6f625f feat(handover): wire Jira Comment panel + delete Macro and sidebar transition` — :handover Phase 1 (different scope; do not touch)

This handoff covers the deferred :automation work only. Handover phases 2-5 are tracked separately in `docs/research/2026-05-07-handover-wireup-plan.md`.

---

## CRITICAL — sonar isolation

Another Claude session owns `:sonar`, `tools/sonar-probe/`, and `docs/research/*sonar*` on this branch. **Do not touch any of those.** The parallel session has already shipped `ac0e070e feat(sonar): adopt validated 25.x endpoints + 3 R-FIX bug fixes`; further sonar work is theirs. Brief any subagent you dispatch with the same constraint.

---

## TL;DR

Six P1 bugs and three P2 bugs remain in `:automation` after the prior session's work. All fixes are mechanical except A-P1-3 and A-P1-4 which have a delete-or-wire decision (see §"Decisions still open" below). Single commit on the existing branch. No new features.

| ID | One-liner | File |
|---|---|---|
| A-P1-2 | `QueueStatusPanel` is decorative — no observer of `queueService.stateFlow` | `automation/ui/QueueStatusPanel.kt` |
| A-P1-3 | `ConflictDetectorService` has no UI consumer (delete-or-wire) | `automation/service/ConflictDetectorService.kt` |
| A-P1-4 | `TagHistoryService.saveHistory/getHistory/loadAsBaseline` dead (delete-or-wire) | `automation/service/TagHistoryService.kt` |
| A-P1-5 | `TagHistoryService` SQLite connection leaks across IDE life | `automation/service/TagHistoryService.kt` |
| A-P1-6 | `AutomationPanel.onSuiteSelected` race — stale `invokeLater` after suite switch | `automation/ui/AutomationPanel.kt` |
| A-P1-8 | `SuiteConfigPanel.persistVariables` silently no-ops when `getSuiteConfig` returns null | `automation/ui/SuiteConfigPanel.kt` |
| A-P2-2 | `TagBuilderService.semverPattern` matches pre-release tags as releases (scoring inflation) | `automation/service/TagBuilderService.kt` |
| A-P2-5 | `AutomationPanel.scope = CoroutineScope(...)` violates Phase 4 service-injected scope convention | `automation/ui/AutomationPanel.kt` |
| A-P2-6 | Verify default `bambooBuildVariableName` casing matches live server (probe shows `DockerTagsAsJSON`) | already addressed in `a2e1deeb`; verify and close out |

P2 bugs intentionally **dropped** from this scope (no longer applicable after L2 deletion):
- A-P0-1 — gone (validation flow deleted)
- A-P2-1 — subsumed by A-P1-3 (delete-or-wire)
- A-P2-3 — gone (duplicate URL builders deleted)
- A-P2-4 — gone (`TagValidationBeforeRunProvider` deleted)

A-P1-7 (Bamboo trigger body shape) is **suspect-unverified** per audit §6 and `2026-05-07-bamboo-audit-recommendations.md`. Resolves only with a sandbox-plan trigger probe, which we have not run. Do NOT speculate-fix it; leave it parked.

---

## Decisions still open (resolve as you go)

### 1. ConflictDetectorService: delete or wire?

The audit says no UI consumer. Verify with `grep -rn "ConflictDetectorService" --include="*.kt"` before deciding.
- **If genuinely unused**: delete the service + its tests + the registration in `META-INF/automation.xml` (or wherever).
- **If wired but not consumed**: that's a half-implementation; finish or delete. Recommended default is delete unless a UI surface obviously wants it.

### 2. TagHistoryService dead methods: delete or wire?

The audit lists `saveHistory`, `getHistory`, `loadAsBaseline` as never called from production. Same rule:
- **If genuinely unused**: delete the methods + their tests. Keep the service itself if other live methods exist (e.g., the queue-recovery `getActiveQueueEntries()` — that's actively used per the L3 commit's deprecation note).
- **If a half-finished feature**: prefer delete over speculative wire-up.

### 3. SQLite connection lifecycle (A-P1-5)

Inspect `TagHistoryService` for connection management. Expected fix: wrap each connection in `try-with-resources` (`use { }` in Kotlin) and ensure no field-level connection survives across method calls. If the service registers as `@Service(Service.Level.PROJECT)`, register a `Disposable` on the connection if a long-lived one is genuinely required.

### 4. AutomationPanel scope (A-P2-5)

Current code (per audit): `scope = CoroutineScope(Dispatchers.IO + SupervisorJob())`. Project-wide convention (per `:core/CLAUDE.md` Phase 4 service-injected scope rule): inject `cs: CoroutineScope` via the @Service constructor.

`AutomationPanel` is a **UI panel**, not a `@Service`, so the strict service-injected pattern does not apply directly. The accepted pattern for non-service classes is "consolidate fire-and-forget launches onto a single field scope owned by the panel's `Disposable` lifecycle" (e.g., `AgentController.controllerScope`). Confirm `AutomationPanel implements Disposable` and the scope is registered with `Disposer.register(this) { scope.cancel() }`. If yes, current code is fine. If not, register the cancellation to ensure no leaks on dispose.

---

## Per-bug expectations

**A-P1-2 — QueueStatusPanel observer**
- Subscribe to `queueService.stateFlow` (collect on the panel's scope).
- Render queue position, wait-time estimate, current entry's plan key.
- Enable Cancel button only when there's an active entry; on click, call `queueService.cancel(entryId)`.
- Empty state: "Queue idle." When the flow emits an empty list.

**A-P1-3 / A-P1-4** — see decisions §1 and §2.

**A-P1-5 — SQLite leak**
- Audit symptom: connection leaks across IDE life.
- Verify with grep for `Connection` and `getConnection` in `TagHistoryService`.
- Fix per Kotlin idiom: `connection.use { conn -> ... }` blocks per method. No field-level `Connection`.

**A-P1-6 — Suite-selected race**
- Symptom: when a user clicks suite A, then quickly clicks suite B, the suite-A handler's `invokeLater` block fires after B is selected, overwriting B's state.
- Fix: capture the selection at handler entry; before the `invokeLater` updates UI state, check that the captured selection still matches `currentSuitePlanKey`. If not, drop the update.
- Alternative (cleaner): cancel the pending coroutine when suite changes. Use `Job` references stored per active fetch.

**A-P1-8 — SuiteConfigPanel silent no-op**
- Audit: `persistVariables` returns silently when `getSuiteConfig(planKey)` returns null.
- Fix: log a warning at minimum. Better: create a new `SuiteConfig(planKey, variables)` and persist it (the absent config is the bug — user's edits should still save).
- Consider whether this can happen in practice (e.g., if the user's settings file was corrupted or the suite was deleted from another tab while editing).

**A-P2-2 — Pre-release semver scoring**
- Current regex: `^\d+\.\d+\.\d+.*$` matches `1.2.3-rc1`, `1.2.3-SNAPSHOT`, etc. as release tags, inflating baseline scores.
- Fix: tighten to `^\d+\.\d+\.\d+(\.\d+)?$` (allow optional 4th segment for some Maven-style versions; reject anything with `-` or `+` modifiers).
- Add a regression test in `TagBuilderServiceTest`.

**A-P2-6 — `bambooBuildVariableName` casing verification**
- The `a2e1deeb` commit aligned the default to `DockerTagsAsJSON` (capital JSON) per the live server probe (`bundle-{automation,repo}.unpacked/raw/plan_variables_via_context.json`).
- Run `grep -rn "DockerTagsAsJson\|dockerTagsAsJson" --include="*.kt"` and verify all production sites (services + extractors) match the canonical casing OR use case-insensitive lookup.
- If everything matches, close A-P2-6 with a one-line commit-message note. No code change expected.

---

## Constraints

- Single commit on `fix/automation-handover-quality-tabs`. Pull first; the parallel sonar session may have shipped more.
- NO Co-Authored-By trailer.
- NO new public Settings UI in this commit (none of the bugs require it).
- NO third-party dependency changes.
- Touch only `:core`, `:automation` if needed (no other modules).
- Run `./gradlew :core:test :automation:test :bamboo:test` separately (not chained — pre-existing Gradle wiring issue surfaces if chained).
- Tests: add for new behaviour, delete for removed behaviour, fix pre-existing tests that encoded the buggy assumption.
- Match the prior commit's style: `fix(automation): land P1+P2 mop-up — queue observer, sqlite leak, suite race, ...`

---

## Verification protocol per bug

Each bug closes when:
1. Code change addresses the audit's described symptom (cite the audit's §3 / §4 line in the commit body).
2. A test exercises the fix (or confirms the deletion).
3. Audit doc is updated: append a one-line "Closed in `<commit-hash>`" to each bug's heading in `docs/research/2026-05-07-automation-handover-audit.md`. (Same convention the Bamboo audit doc uses for closed items.)

---

## Useful commands for the picking-up session

```bash
# Pull latest
git pull origin fix/automation-handover-quality-tabs

# Read the audit doc end-to-end
cat docs/research/2026-05-07-automation-handover-audit.md

# Confirm sonar working tree is dirty (parallel session in progress)
git status sonar/

# Confirm prior commits landed
git log --oneline a2e1deeb~1..HEAD

# Find dead-code candidates
grep -rn "ConflictDetectorService\|TagHistoryService" --include="*.kt" automation/ core/

# Test runs (separately)
./gradlew :core:test
./gradlew :automation:test
./gradlew :bamboo:test
```

---

## What's NOT in this scope

- Any :handover work — covered by `docs/research/2026-05-07-handover-wireup-plan.md` Phases 2-5
- Any :sonar work — parallel session
- Any :bamboo work — already shipped
- A-P1-7 trigger body shape — suspect-unverified, parked
- New Bamboo endpoints not adopted in `59c9ea8d`
- Any UI redesign or settings UI additions

---

## After this commit

Once the P1+P2 mop-up lands, `:automation` is fully closed against the audit doc. The remaining open work on this branch:
- :handover Phase 2 (Copyright + QA Clipboard)
- :handover Phase 3 (Time Log)
- :handover Phase 4 (AI Review)
- :handover Phase 5 (constructor canonicalisation, H-P1-2)

The user may want a PR opened for the branch after :automation closes; ask before opening.

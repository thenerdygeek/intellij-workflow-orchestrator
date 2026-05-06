# Jira API Audit — Session Handoff

**Branch:** `fix/automation-handover-quality-tabs`
**As of:** 2026-05-06
**Status:** All tooling built; full-sweep probe results captured and bundled. Awaiting analysis + implementation.

You are picking up an in-progress audit that an earlier session set up. Everything you need to finish Jira is on disk and on this branch. Do **not** start fresh — the inventory, probe scripts, and result bundle already exist.

---

## 1. Mission (one sentence)

Read the user's full-sweep probe results, compile a recommendations doc that maps each existing plugin call site to **keep / swap / new feature**, get user approval, and implement everything in a **single commit** for the `:jira` module.

The user has explicitly said: **one commit per service** (Jira → Bitbucket → Nexus, in that order, separate sessions). Do **not** touch Bitbucket or Nexus in this session.

---

## 2. What's already done (don't redo)

| Artifact | Path | Purpose |
|---|---|---|
| Call-site inventory | `docs/research/2026-05-06-jira-api-call-site-inventory.md` | Maps every UI/agent feature to its Jira endpoint + risk register + candidate endpoints. **Read this first.** |
| Version-only probe result | `docs/research/probe-results/2026-05-06-jira-versions-only-summary.md` | Confirms Jira 10.3.16 Data Center. |
| Version-only raw response | `docs/research/probe-results/2026-05-06-jira-versions-only-serverInfo.json` | Full `/serverInfo` body. |
| Read-only probe script | `tools/atlassian-probe/probe_jira.py` | 38 endpoints across version/existing/internal/candidate categories. Supports `--versions-only`, `--discover`, full sweep. |
| Redactor | `tools/atlassian-probe/redact.py` | Replaces hostnames, emails, issue keys, user names, free-text with stable per-run placeholders. SystemRandom-backed. Custom-word marker list at top of file. |
| Bundler | `tools/atlassian-probe/bundle.py` | Pack/unpack a result dir into a single text file. `--compress` mode gzip+base64s (~5–40× shrink). |
| **Full-sweep bundle (compressed)** | `tools/atlassian-probe/Result_Jira/bundle.txt` | **The user's redacted probe output. 45 files. Unpack this first.** |

### Architectural gap already surfaced

`jira/tasks/JiraTaskRepository.kt` builds its own `OkHttpClient` (line 32) and bypasses `JiraApiClient`. Three rogue calls to `/rest/api/2/issue/{id}`, `/rest/api/2/search`, `/rest/api/2/myself`. Per the user's "unify the architecture" instruction, this gets fixed in the implementation commit. `core/auth/AuthTestService.kt` is **deliberately isolated** — do NOT migrate it (memory: `feedback_auth_test_isolation.md`).

### Confirmed environment

- **Jira 10.3.16, Server / Data Center**, build 10030016, build date 2026-01-07
- The user's instance is recent enough that the new Cloud-style search endpoints (`/rest/api/3/search/jql`, `/rest/api/2/search/approximate-count`) **may** be backported — the probe will tell you definitively.

---

## 3. First steps (do these in order)

```bash
# 1. Unpack the bundle (already in repo)
cd tools/atlassian-probe
python bundle.py unpack --in Result_Jira/bundle.txt
# → produces Result_Jira/bundle.unpacked/ with summary.md + raw/*.json

# 2. Read the bundle's summary.md
cat Result_Jira/bundle.unpacked/summary.md
```

The `summary.md` is the executive overview — every probe with status code, timing, and one-line description. **Most decisions can be made from this table alone.** Read the inventory doc (`docs/research/2026-05-06-jira-api-call-site-inventory.md`) alongside it; cross-reference each summary row against the corresponding call sites listed there.

---

## 4. Concrete deliverable from this session

A new file: **`docs/research/2026-05-06-jira-recommendations.md`** — structured as:

| Plugin call site | Today's endpoint | Probe result | Recommendation | Effort |
|---|---|---|---|---|
| `JiraTaskRepository.getTask` | `GET /rest/api/2/issue/{id}` | ✅ 200, 142ms | Keep endpoint; refactor to funnel through `JiraApiClient` | M |
| `BranchChangeTicketDetector.validateKeys` | `GET /rest/api/2/search?jql=key in (...)` (chunks of 100) | (check probe) | Swap to `POST /rest/api/2/search` if probe confirmed → drop the chunking | S |
| `MentionSearchProvider.searchTickets` | manual board+sprint walk | (check probe `issue_picker.json`) | Swap to `GET /rest/api/2/issue/picker` if probe confirmed | M |
| ... | ... | ... | ... | ... |

For each row, decide:
- **Keep**: endpoint works, no improvement available
- **Swap-for-X**: a better endpoint exists; specify the migration
- **Add-Y**: a new feature is enabled by an endpoint we weren't using
- **Risk**: internal endpoints (`/rest/dev-status/`, `/rest/api/1.0/labels/suggest`) — flag if they failed, plan a fallback

**Endpoints to look at especially closely** (these were specifically added for Jira 10):
- `search_jql_v3_post.json` — if 200, plan a v2-search → v3-search migration story
- `search_v2_post.json` — if 200, kill the 100-key JQL chunking in `validateTicketKeys`
- `search_approximate_count.json` — if 200, propose Sprint tab badge counter
- `issue_picker.json` — if 200, propose replacing `MentionSearchProvider`'s manual walk

After writing the doc, **show it to the user** and ask which items to adopt. Do not proceed to implementation without explicit approval — the user said "consult only on UI mockups, otherwise autonomous" but recommendations spanning swaps + new features deserve a confirm.

---

## 5. Implementation (after user approval)

A single commit on this branch covering:
1. Migrate `JiraTaskRepository` to `JiraApiClient` (the architectural gap).
2. Apply approved endpoint swaps (one commit, one diff).
3. Wire approved new-feature paths.
4. Update `:jira/CLAUDE.md` and `:core/CLAUDE.md` if the call surface changes.
5. Build + verifyPlugin before commit.

Commit message style: `feat(jira): unify HTTP funnel and adopt validated endpoints (Server-only)` — conventional, no AI co-author trailer (see memory `feedback_no_coauthor.md`).

---

## 6. User constraints — already memorized; key reminders

Pulled from memory `project_api_audit_in_progress.md`. The user is on Windows with a personal Jira token; probes are read-only-only; "one commit per service" is non-negotiable; default to autonomous on architecture, consult on UI; current branch is `fix/automation-handover-quality-tabs` (do **not** create worktrees or branch off main).

---

## 7. Sequence after this session

After the Jira commit lands and is pushed, **separate sessions** handle:
- **Bitbucket** — needs `tools/atlassian-probe/probe_bitbucket.py` (not yet written), a similar inventory doc, similar bundle workflow, similar recommendations doc, and a single Bitbucket commit
- **Nexus** — same pattern under `tools/nexus-probe/` (separate dir; different product family)

The active memory file `project_api_audit_in_progress.md` tracks this sequence. **When all three services are done, delete that memory file** per the original instruction.

---

## 8. Things NOT to touch in this session

- `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt` — dirty in user's working tree, unrelated WIP
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/loop/AgentLoop.kt` — same
- `agent/webview/src/components/chat/MessageList.tsx` etc. — same
- Bitbucket / Nexus / Sonar / Bamboo / Automation modules
- `core/auth/AuthTestService.kt` — intentionally isolated from the funnel
- Any rebase or main-branch operation — stay on `fix/automation-handover-quality-tabs`

---

## 9. Quick-reference index of in-flight commits on this branch

```
f72d06f5  feat(jira-audit): bundle.py --compress for clipboard-too-big bundles
8921fd8a  fix(jira-audit): use SystemRandom for redaction
96085826  fix(jira-audit): broaden custom-word matching — substring + extended run charset
c98d27be  fix(jira-audit): redact custom words as prefixes that extend through alphanumeric runs
b9a48b58  feat(jira-audit): bundle.py — single-file pack/unpack for probe results
e61384c1  feat(jira-audit): --discover mode that scopes to your actual work
595d59a6  feat(jira-audit): probe Jira 10 search APIs + save user's version output
3de07f49  fix(jira-audit): redactor consistency + edge-case handling
edd974a0  docs(jira-audit): add redact.py for sharing probe output safely
e88190d1  docs(jira-audit): inventory Jira API call sites + add read-only probe
```

The implementation commit will be the next one on top of `f72d06f5`.

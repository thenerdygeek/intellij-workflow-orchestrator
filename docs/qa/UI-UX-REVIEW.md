# Workflow Tool-Window — UI/UX Review (2026-06-29)

Scope: the eight tabs of the bottom-docked **Workflow** tool window — Sprint · PR · Build · Quality · Automation · Agent · Handover · Insights — observed live in the runIde sandbox across **first-run/empty, loading, populated, and error** states. Grounded in `docs/architecture/ui-structure.md` (the canonical UI spec, incl. the `EmptyStatePanel` standard, `StatusColors`, and the notification/context-menu rules). Emphasis is on the **first-run / empty-state experience**, which is the most consistent friction across the product, with populated-state notes where seen.

This is a UX review, not a bug list — but a few findings are also defects and are marked **(also a bug)**.

---

## TL;DR — the five highest-leverage changes

1. **Replace eight independent "Connect … in Settings" dead-ends with one guided first-run flow.** Today every tab, on its own, tells the user to go configure a different backend. That's eight separate cul-de-sacs for what is really one onboarding task. A single **Setup checklist** (the "Start Setup" affordance, promoted) that shows all six connections, their status, and a one-click path to each — reachable from any empty tab — collapses the funnel.
2. **Make "Connect"/"Open Settings" deep-link to the _specific_ connection page**, pre-scrolled to the right backend (Sprint→Jira, Quality→SonarQube, Agent→Sourcegraph), not the generic root settings node. Every empty state currently routes to the same place, forcing the user to re-find the field.
3. **Never show raw class names or stack-trace fragments to users.** The **Insights** tab renders *"Failed to load Insights tab. `com.workflow.orchestrator.core.services.SessionHistoryReader [Plugin: com.workflow.orchestrator.plugin]`"* **(also a bug)**. Error states need a human sentence + a relevant action (**Retry** / **Report**), and must not offer "Open Settings" for a code-level failure. _[Lead update 2026-06-30: the underlying load failure was traced to a **corrupted sandbox jar** (IntelliJ mmap `ZipException: invalid distance too far back` reading `SessionHistoryReader.class`), an artifact of jars being rewritten under the running sandbox — **not** a plugin/code defect (the on-disk jar passes `unzip -t`; a fresh build is clean). Insights loads normally on a clean relaunch. The friendly-error + Retry ask still stands as **defense-in-depth** for `WorkflowToolWindowFactory.materializeByTitle`. See Insights per-tab note.]_
4. **Refresh tabs automatically when a connection is added.** After credentials were entered and applied, the **Sprint** tab still read "Connect to Jira in Settings" until manually refreshed **(also a bug-adjacent)**. A connection-state change should re-poll the affected tabs (or at least swap the empty state for a "Connected — Refresh" affordance).
5. **Standardize empty-state copy and actions through `EmptyStatePanel`.** The live messages have drifted from the documented spec and from each other (see table below). One voice, one layout, one primary action verb.

---

## Cross-cutting themes (the big wins)

### A. First-run is a maze of single-backend dead-ends
Each tab independently gates on its own backend and sends the user to Settings:

| Tab | Live empty/first-run message | Action shown |
|---|---|---|
| Sprint | "No tickets assigned. Connect to Jira in Settings to get started." | Open Settings |
| PR | "No pull request services configured. Connect Bitbucket in Settings to get started." | Open Settings |
| Build | "Build: loading…" → "No PR for this branch — pick one in the PR tab →" / "No stages found." | (cross-link) |
| Quality | "No quality data available. Connect to SonarQube in Settings to get started." | Open Settings |
| Automation | "No automation suites configured. Connect to Bamboo and configure suites in Settings." | Open Settings |
| Agent | "No Sourcegraph connection configured. Connect to Sourcegraph in Settings to use Agent features." | Open Settings |
| Handover | "No handover services configured. Connect Jira and Bitbucket in Settings to get started." | Open Settings |
| Insights | **"Failed to load Insights tab. com.workflow.orchestrator.core.services.SessionHistoryReader […]"** (error, not empty) | Open Settings |

The user has to visit Settings up to six times, discovering one missing dependency per tab. **Recommendation:** a single first-run surface (promote the existing "Welcome → Start Setup" onboarding into a persistent **Setup / Connections checklist**) listing all six services with live status dots and inline "Connect" buttons. Each tab's empty state then says *"Jira isn't connected yet"* with **one** button that opens that checklist (or deep-links to that row), instead of a generic Settings trip. This turns N dead-ends into one path with visible progress.

### B. Empty-state copy & actions are inconsistent (and drift from the spec)
`ui-structure.md` documents canonical empty messages (e.g. Build = *"No builds found. Push your changes to trigger a CI build."*, PR = *"No pull requests found. Connect to Bitbucket in Settings."*). The **live** strings differ ("No pull request **services** configured…", "Connect **to Bamboo and configure suites**…"). Beyond drift, the **voice** varies (some "to get started", some not), and the **action** is sometimes a button ([Open Settings]) and sometimes a text link ("go to Settings", "pick one in the PR tab →"). **Recommendation:** route every empty state through `EmptyStatePanel` with a fixed shape: *one-line title · one-line subtitle · one primary action (verb = "Connect …") · optional secondary text-link ("Learn more")*. Keep the copy in one place so it can't drift from the spec.

### C. Error and loading states are unpolished
- **Insights** dumps a fully-qualified class name + plugin id as the user-facing error **(also a bug)**. Replace with *"Couldn't load Insights. [Retry] · [Report]"* and log the FQCN internally only.
- A transient **"IDE error occurred — See details and submit report"** balloon appears on Sprint/Handover (from the Jira active-ticket fetch; traces to the issue-link deserialization brittleness, REQ-6). Users shouldn't see platform error balloons for an expected "no active ticket" state.
- **Build** sits on *"Build: loading…"* with **no spinner or skeleton**, and **Sprint** earlier showed *"Loading sprint tickets…"* as plain text. Use a determinate/indeterminate progress affordance and a **timeout → empty/error** transition so "loading…" can't appear stuck.

### D. The onboarding tooltip fights the layout
The "Welcome to Workflow Orchestrator! … Start Setup / Got It" GotIt tooltip rendered **overlapping the tab bar / partially covering the first tab and the empty-state button** on open. **Recommendation:** anchor it below the tab strip (or to the Settings gear), never over the tabs; and make "Start Setup" the entry to theme-A's checklist rather than a one-time dismissable nudge.

### E. Good patterns already in place (keep / extend)
- The centered `EmptyStatePanel` (message + single primary button) on **Quality / Agent / Sprint** is clean and on-spec — the fix is consistency, not redesign.
- **Cross-tab references** ("No PR for this branch — pick one in the PR tab →", "configure suites in Settings") are genuinely helpful; just upgrade them to **deep-links** that land on the exact destination.
- **Automation** and **Handover** populated states are information-rich and well-organized (sub-tabs Configure/Monitor and Checks/Actions/Share; DOCKER TAGS / VARIABLES grouping; the closure-comment builder). These are the strong end-states the empty states should be selling.

---

## Per-tab findings

### Sprint
- **Empty:** on-spec `EmptyStatePanel` ("No tickets assigned. Connect to Jira… [Open Settings]"). **Issue:** stayed in this state **after** Jira creds were applied — no auto-refresh on connect (theme D/4). **Fix:** re-poll on connection change; deep-link the button to Settings→Jira.
- **Populated (spec):** ticket list + detail (Start Work / Transition / Log Time). Ensure the "IDE error occurred" balloon (REQ-6) is replaced by an inline, friendly "No active sprint/ticket" state.

### PR
- **Empty:** "Connect Bitbucket in Settings". When configured (seen on A-alone), shows **My PRs / Reviewing / All** filters + a **+** create flow; the **Create PR** dialog is well-formed (Source→Target, Tickets chips, Title, Description, Reviewers). **Fix:** copy drift ("services configured" vs spec); deep-link to Settings→Bitbucket; the create dialog's auto-picked ticket chips were noisy (pulled many tickets) — consider limiting/curating.

### Build
- **Observed:** "Build: loading…" + "No PR for this branch — pick one in the PR tab →" + empty **LOG / TESTS / ARTIFACTS** sub-tabs + "No stages found." **Issues:** (1) loading with no spinner/skeleton; (2) several simultaneously-empty sub-panels read as "broken" rather than "nothing yet"; (3) the branch→PR dependency is implicit. **Fix:** a single contextual empty state ("No CI build for `feature/plugin-split` yet — builds appear after you push or link a PR") instead of four empty sub-areas; spinner + timeout on loading.

### Quality
- **Empty:** clean, on-spec ("No quality data available. Connect to SonarQube… [Open Settings]"). **Fix:** deep-link to Settings→SonarQube; otherwise exemplary — use as the template for the others.

### Automation
- **Empty:** "No automation suites configured. Connect to Bamboo…". **Populated:** SUITE/BRANCH selectors, **Trigger ▾** (Trigger Customized… / Trigger All Stages), Configure/Monitor sub-tabs, DOCKER TAGS (build variable `DockerTagsAsJSON`), VARIABLES. **Issues:** (1) two overlapping "nothing here" signals at once — "No suites configured — go to Settings" **and** "⚠ No CI build found for branch 'No build context for this PR's branch yet'" — confusing redundancy; (2) **Trigger ▾ → "Trigger Customized…" silently no-ops when there's no suite/stage** (nothing happens, no toast) — should be disabled with a tooltip ("Add a Bamboo suite first") or open a guided add-suite step; (3) the Settings → Automation "Project" picker shows the raw error **"Failed: …resource not found"** — surface a friendly "Couldn't list Bamboo projects — check the connection" instead. **Fix:** collapse the dual empty signals; disable actions that can't run, with a reason.

### Agent (JCEF)
- **Empty:** "No Sourcegraph connection configured… [Open Settings]". **Populated (seen earlier):** JCEF chat with streaming, tool-call cards, history, plan mode. (Separately tracked agent UX items: history loads blank-then-populates; scroll-to-bottom lands short on tall code blocks; session titles can leak `<thinking>`/code — see BUG-REPRO-RESULTS.) **Fix here:** deep-link to Settings→Sourcegraph; consider a one-line "what the Agent can do" value prop in the empty state (it's the highest-value tab and the emptiest pitch).

### Handover
- **Empty:** "No handover services configured. Connect Jira and Bitbucket…". **Populated:** "NO ACTIVE TICKET" header + **Checks / Actions / Share** sub-tabs — pre-handoff status checks (Copyright headers, PR, Build, Quality gate = WARN, suites, Docker tags), **Actions → Copyright header status** (changelist read → "No files to check"), **Share → Jira closure comment** builder (renders the suite table + PR link, with copy). Strong, dense end-state. **Issues:** (1) "NO ACTIVE TICKET — / Unknown" header is cryptic; (2) the Checks list shows mostly em-dashes ("—") with no legend — unclear if that means "not run", "n/a", or "pass". **Fix:** legend/iconography for check states; friendlier "no active ticket" guidance ("Start work on a ticket to populate handover").

### Insights
- **⚠ ROOT CAUSE (Lead update 2026-06-30): this is NOT a plugin defect — it's a corrupted sandbox jar.** The full stack ends in `Caused by: java.util.zip.ZipException: invalid distance too far back` from IntelliJ's mmap zip reader (`ImmutableZipEntry.getByteBuffer`) while loading `SessionHistoryReader.class` from `intellij-workflow-orchestrator.core.jar`. The class **is** present and inflates fine from disk (`unzip -t` passes; a freshly built `core.jar` is clean) — only the **running IDE's memory-mapped view** is corrupt, the signature of a jar rewritten under a sandbox that has it mmap'd (the same build-churn that stripped B's UI earlier). **On a clean relaunch, Insights loads normally.** Reclassify P1→environmental; re-test on a clean build. The defensive-UX items below remain valid regardless.
- **Observed:** **error**, not empty — *"Failed to load Insights tab. com.workflow.orchestrator.core.services.SessionHistoryReader [Plugin: com.workflow.orchestrator.plugin]"* + [Open Settings] **(also a bug)**. **Fix:** (1) catch the load failure and show *"Couldn't load Insights. [Retry] · [Report]"* — never the FQCN; (2) "Open Settings" is the wrong action for a code error; (3) investigate the `SessionHistoryReader` failure (likely no/locked session history) and fall back to an empty "No insights yet — run an agent session" state when there's simply no data.

---

## Prioritized recommendations

| # | Priority | Recommendation | Affects |
|---|---|---|---|
| 1 | **P1** | Unified first-run **Setup/Connections checklist** (promote "Start Setup"); every empty tab routes to it with one button | all tabs |
| 2 | **P1** | Fix **Insights** raw-error state → friendly message + Retry/Report; investigate `SessionHistoryReader` failure | Insights |
| 3 | **P1** | **Auto-refresh** tabs on connection change (Sprint stayed "Connect to Jira" after applying creds) | Sprint/all |
| 4 | **P2** | **Deep-link** every "Connect"/"Open Settings" button to the specific backend's settings page | all tabs |
| 5 | **P2** | **Standardize empty-state copy + action** via `EmptyStatePanel`; re-sync with `ui-structure.md` and keep strings in one place | all tabs |
| 6 | **P2** | **Disable** actions that can't run (Automation "Trigger Customized…" with no suite) with an explanatory tooltip instead of silent no-op | Automation |
| 7 | **P2** | Replace platform **"IDE error occurred"** balloon on Sprint/Handover with an inline, expected "no active ticket" state (REQ-6) | Sprint/Handover |
| 8 | **P3** | **Loading affordances**: spinner/skeleton + timeout for Build "loading…" and Sprint "Loading…"; collapse Build's four empty sub-panels into one contextual message | Build/Sprint |
| 9 | **P3** | Anchor the **onboarding tooltip** below the tab strip (stop it covering tabs/buttons) | tool window |
| 10 | **P3** | **Check-state legend** + clearer "NO ACTIVE TICKET" guidance in Handover | Handover |

---

## Alignment with the existing spec
These recommendations extend, not replace, `ui-structure.md`: keep `EmptyStatePanel`, `StatusColors`, JB components, SVG light/dark icons, ≤2 notification buttons, and ≤5 context-menu items. The asks are (a) make every empty/error state actually go through `EmptyStatePanel` with consistent copy, (b) give its action a precise destination, and (c) add the one thing the spec doesn't yet describe — a **cross-tab first-run/connections surface** so onboarding isn't rediscovered tab-by-tab.

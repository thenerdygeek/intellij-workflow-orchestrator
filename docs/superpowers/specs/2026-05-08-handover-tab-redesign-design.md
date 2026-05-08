# Handover tab redesign — design spec

**Date:** 2026-05-08
**Branch:** `fix/automation-handover-quality-tabs` (continuation)
**Status:** Approved by user
**Mockup:** `docs/superpowers/mockups/2026-05-08-handover-tab-3tab-mockup.html`

## Goal

Reshape the Handover tab from its current "context sidebar + four CardLayout panels" structure into a **3-tab layout** (Checks | Actions | Share) with a unified, template-driven Share surface that consolidates Jira-comment posting, formatted-for-Outlook email rendering, and one-click copy chips for individual placeholder values.

The redesign keeps the tab's role as the post-automation closure ritual but turns the Share surface into a **template authoring + render workspace** with multi-named templates, bundled-global-project layered storage, AI-summary placeholders, and a Jira-faithful live preview.

## Non-goals

- No SMTP/webhook senders. Email output stays as clipboard-only (HTML for Outlook + plain-text fallback).
- No standalone Slack action. A user can author a Slack-friendly template under the Email action; we do not ship a dedicated Slack send path.
- No PR-creation moves into Handover. PR creation lives in the PR tab.
- No Jira ticket transitions. Transitions live in the Sprint tab's `TicketTransitionDialog`.
- No SMTP credential storage in `PasswordSafe`.

## User-facing layout

A single tool-window panel with:

```
┌──────────────────────────────────────────────┐
│ AFTER8TE-912  Cache dev-status…  [In Progress] [⟳][⚙] │
├──────────────────────────────────────────────┤
│ [Checks 1⚠] [Actions] [Share]                          │  ← tab strip
├──────────────────────────────────────────────┤
│ ⚠ 1 check not green: Quality gate FAILED. …             │  ← persistent override warning
├──────────────────────────────────────────────┤
│                                                          │
│  active tab content                                      │
│                                                          │
└──────────────────────────────────────────────┘
```

### Tab content

| Tab | Cards | Notes |
|---|---|---|
| **Checks** | `Pre-handoff status checks` (8 rows, 2-col grid) + `Ritual checklist` (4 binary done/pending) | Read-only status. Default tab when any check is red. |
| **Actions** | `Copyright Fix` (file list + Fix All / Rescan) + `Time Log` (form + Log Work) | Small write actions, ungated. |
| **Share** *(default)* | `Jira Comment` editor + `Email` editor + `Quick Values` chip grid | Templated outputs + raw-value chips. Default tab when all checks are green. |

### Persistent override warning

If any check in the **Checks** tab is in failure or in-progress state, an amber banner is rendered between the tab strip and the tab content (visible on every tab). Banner text:

> ⚠ **N check(s) not green:** &lt;list&gt;. You can still hand off, but consider fixing first. [View &lt;Tab&gt; tab]

Each "View … tab" link routes to the upstream tab (Quality, Build, Automation) via the existing `WorkflowToolWindowFactory` content-manager API.

When a user clicks `Post Comment`, `Copy formatted`, `Fix All`, `Log Work`, or any chip while the warning is showing, an `WorkflowEvent.HandoverOverride(ticketId, failedChecks)` is emitted on the existing `EventBus`. No prompt, no friction — just an audit signal.

### Default tab heuristic

- All checks green → Share tab is preselected.
- Any check red or in-progress → Checks tab is preselected.

State is session-only; reopening the tool window starts fresh.

## Templates

### Storage layout

Three layered sources, merged at runtime:

```
classpath: /handover/templates/{action}/*.{wiki,html}              ← bundled, read-only
~/.workflow-orchestrator/handover/templates/{action}/*.{wiki,html} ← user globals
~/.workflow-orchestrator/{proj}/handover/templates/{action}/*      ← per-project overrides (by file name)
```

`{action}` is `jira` (wiki markup) or `email` (HTML). Mirrors the `:agent` module's `8 bundled personas + user YAML` pattern.

The merge: a file in the project directory shadows a same-named file in the user-global directory, which shadows a same-named bundled file. The picker dropdown shows the merged list with a `★` suffix on shadowed entries. A "Reveal in Files" affordance opens the resolved file in the IDE proper for power editing.

Hot-reload: 300ms debounced filesystem watcher (same primitive as `:agent`'s `~/.workflow-orchestrator/agents/`). Picker repopulates without panel rebuild.

### Bundled defaults

Shipped read-only on the classpath:

- `jira/standard-closure.wiki` — multi-suite handover comment with table.
- `jira/hotfix.wiki` — tighter single-line variant.
- `email/qa-handover.html` — formatted handover for QA distribution.
- `email/release-notes.html` — externally-facing release notes layout.

Read-only items in the picker show a 🔒 icon. Right-clicking offers `Duplicate to my templates` which copies the file to `~/.workflow-orchestrator/handover/templates/{action}/{name}-copy.{ext}` and selects it.

### Editor (inline split-pane)

Both action types use a vertical split-pane inside the action's card:

```
┌─[ Picker dropdown ]──[duplicate][delete] ●─┐
├─ WIKI MARKUP / HTML SOURCE ────────────────┤
│   editable code area, monospace            │
├─ PREVIEW · [Live preview (Jira) · cached]──┤
│   rendered output                          │
├─[ secondary ]──────────────────[ primary ]─┤
└─────────────────────────────────────────────┘
```

- **Source pane.** Plain text editor with syntax tinting:
  - Jira: light tint on `h1./h2./h3.`, `||/|`, `{color}…{color}`, `[…|…]` link form.
  - Email: HTML tag tint, attribute tint, placeholder tint (in success-color so the unresolved placeholders are visually distinct from literal text).
- **Preview pane.**
  - Jira: see "Jira preview rendering" below.
  - Email: rendered as HTML in a `JEditorPane` with `text/html` content type. (Avoids JCEF dependency for this single use.)
- **Auto-save.** Edits debounce (300ms) and write to disk on the next idle tick. A `●` (amber) dirty marker appears in the picker bar between the first keystroke and the disk write completing. No save button. The IDE editor's undo stack gives the user "discard my last 5 minutes" coverage; ⌘Z works inside the source pane.
- **Lifecycle.** `+ New template…` in the picker prompts for a name and creates a blank file. Right-click on a picker item exposes Rename / Delete / Reveal in Files. Bundled (read-only) entries grey out Rename/Delete; the only mutating action available is `Duplicate to my templates`.

### Placeholder catalogue

Resolved by `HandoverPlaceholderResolver` (new, in `:handover` since it's project-scoped). Sources:

| Placeholder | Source |
|---|---|
| `{ticket.id}`, `{ticket.summary}`, `{ticket.description}`, `{ticket.status}`, `{ticket.assignee}`, `{ticket.priority}`, `{ticket.acceptanceCriteria}` | `core.workflow.WorkflowContextService.activeTicketFlow` + `JiraTicketProvider.getTicketContext` |
| `{repo.name}`, `{repo.branch}`, `{repo.targetBranch}`, `{repo.commitsSinceTarget}` | `RepoContextResolver.resolveCurrentEditorRepoOrPrimary()` + `git.GitRepository` |
| `{docker.tag}` (primary repo), `{docker.tags}` (newline-separated), `{docker.tagsJson}` | `HandoverState.suiteResults.last().dockerTagsJson` parsed to per-repo entries |
| `{pr.id}`, `{pr.url}`, `{pr.title}`, `{pr.reviewers}`, `{pr.approvalState}` | `WorkflowContextService.focusPr` |
| `{build.url}`, `{build.planKey}`, `{build.number}`, `{build.status}`, `{build.duration}` | `HandoverState.buildStatus` |
| `{automation.suiteTable}` (renders as wiki table for Jira / HTML table for Email) | `HandoverState.suiteResults` formatted at render time |
| `{automation.passCount}`, `{automation.failCount}`, `{automation.url}` | same |
| `{quality.gateStatus}`, `{quality.coverage}`, `{quality.newIssues}`, `{quality.dashboardUrl}` | Sonar service responses cached in `HandoverState` |
| `{ai.changeSummary}`, `{ai.ticketSummary}` | `core.ai.TextGenerationService` (existing EP) — see "AI summaries" below |
| `{date.today}`, `{user.name}`, `{user.email}`, `{time.loggedToday}` | platform/`PluginSettings` |

`{automation.suiteTable}` is format-aware — when used inside a Jira template it renders as `||header||\n|cell|`; inside an Email template it renders as `<tr><th>…</th></tr>` rows.

Unresolved placeholders (e.g., a `{quality.coverage}` evaluated when no Sonar service is configured) render as the literal placeholder string in the source pane and as an italic muted "—" in the preview pane.

### AI summaries

`{ai.changeSummary}` and `{ai.ticketSummary}` are computed lazily and cached.

- **Inputs:**
  - `{ai.changeSummary}` — the diff against `{repo.targetBranch}` (gathered via `git.GitRepository.getDiff()`), capped to first 200 hunks / 2000 lines.
  - `{ai.ticketSummary}` — the ticket's `description` + `acceptanceCriteria` (already in `TicketContext`).
- **Cache key:** `(ticketId, branchHEAD-sha, summaryKind)`. Stored in-memory in `HandoverAiSummaryCache`. Invalidated on `BranchChanged` and `TicketChanged` `EventBus` events; also explicitly cleared by a refresh button on each summary chip in Quick Values.
- **Service:** existing `core.ai.TextGenerationService` extension point (the same one Build/PR tabs use for PR descriptions). User's already-configured model is reused — no new settings.
- **Failure mode:** If `TextGenerationService` is unavailable or throws, the placeholder resolves to a literal `(AI summary unavailable)` string. The preview pane shows the same string in muted italic. Surfaced via `WorkflowNotificationService` once per session, not per attempt.

### Jira preview rendering (hybrid)

The Jira preview pane uses two render paths in sequence:

1. **Local minimal renderer** runs synchronously on every keystroke (debounced 100ms). Covers `h1./h2./h3.`, `*bold*`, `_italic_`, `{color}…{color}`, `||header||/|cell|` tables, `[link|url]`. Anything else passes through as monospace text. Produces immediate feedback.
2. **Live Jira render** runs after a 500ms idle. POST to `/rest/api/1.0/render` with body:
   ```json
   {"rendererType":"atlassian-wiki-renderer","unrenderedMarkup":"<resolved>","issueKey":"AFTER8TE-912"}
   ```
   Substitution of `{ticket.id}`-style placeholders happens BEFORE the request — Jira sees fully-resolved markup. Returned HTML is rendered in a `JEditorPane`.

Cache by SHA-256 of the resolved markup; switching back to a previously-rendered version is free.

A small badge above the preview shows the current source:

| Badge | Meaning |
|---|---|
| `● Live preview (Jira) · cached` (green dot) | Last fetch succeeded; rendered HTML matches current resolved markup. |
| `○ Local preview — Jira render unavailable` (grey dot) | Either offline / auth failed / the 500ms idle hasn't fired yet. Local renderer is showing. |

If `/rest/api/1.0/render` returns 401/403, the badge stays grey for the rest of the session and `WorkflowNotificationService` surfaces "Jira live preview unavailable — check Jira credentials" once.

## Quick Values

A grid of clickable copy chips. Each chip has:

- **Key** (uppercase, secondary text): a short label, ≤16 chars.
- **Value** (monospace, foreground): the resolved placeholder, truncated to one line with ellipsis at ~60 chars.
- **Copy icon** (right edge): cosmetic; the whole chip is the click target.

Click → resolves the placeholder, places the unresolved value on the system clipboard via `ClipboardUtil.copyText`, flashes the chip's background to accent-color for 200ms, and emits `WorkflowEvent.HandoverChipCopied(chipKey)` for usage telemetry.

### Configurable chip set

The chip list comes from a new project-scoped setting `PluginSettings.quickClipboardChips: List<String>`. Defaults to:

```
["docker.tag", "docker.tagsJson", "pr.url", "build.url",
 "automation.url", "ticket.id", "ai.changeSummary", "ai.ticketSummary"]
```

Settings UI: a multi-select widget on a new "Handover" page under Tools → Workflow Orchestrator, listing every placeholder from the catalogue with checkboxes. User-configurable order (drag to reorder) deferred — for v1 the list renders in the catalogue's natural order.

## Soft gate

The locked decision: actions are never disabled by failed checks. The persistent warning banner is the only cue. Override events:

```kotlin
sealed class WorkflowEvent {
  …
  data class HandoverOverride(
    val ticketId: String,
    val action: HandoverAction,           // POST_JIRA | COPY_EMAIL | FIX_COPYRIGHT | LOG_WORK | COPY_CHIP
    val failedChecks: List<String>,        // ids of red checks at click time
    val timestamp: Instant
  ) : WorkflowEvent()
}
```

Emitted on every action click while one or more checks are red. Consumed initially by no one — it's a future-looking observability hook.

## Email clipboard format

`Toolkit.getDefaultToolkit().systemClipboard.setContents(transferable, null)` with a `Transferable` advertising both flavors:

- `text/html;class=java.lang.String` — the rendered HTML, with inline CSS (no `<style>` blocks; Outlook strips them) for tables, color cells, and basic typography.
- `text/plain;class=java.lang.String` — `Jsoup.parse(html).text()` fallback.

Outlook on Windows reads CF_HTML; Outlook for Mac reads `public.html`; Slack reads `text/plain`. One transferable, three render paths.

The plain-text fallback is mandatory because Microsoft Teams, Notepad, and most CLI tools paste only `text/plain`.

## Architecture

### Module placement

All new types in `:handover`. Touches:

- `core.services.JiraService` — adds `renderWikiMarkup(text: String, issueKey: String): ToolResult<String>` for the live preview path. Backed by `/rest/api/1.0/render`.
- `core.events.WorkflowEvent` — adds `HandoverOverride` and `HandoverChipCopied`.
- `core.settings.PluginSettings` — adds `quickClipboardChips: List<String>`.

### New types

```
:handover
├── service/
│   ├── HandoverPlaceholderResolver.kt         (project-scoped @Service)
│   ├── HandoverTemplateStore.kt               (layered file store + watcher)
│   ├── HandoverAiSummaryCache.kt              (cache by ticket+sha)
│   └── HandoverWikiPreviewRenderer.kt         (local minimal + live hybrid)
├── ui/
│   ├── HandoverPanel.kt                       (rewrite — host the 3-tab JBTabbedPane)
│   ├── HandoverTicketHeader.kt                (extracted from current context panel)
│   ├── HandoverOverrideBanner.kt              (new persistent banner widget)
│   ├── tabs/
│   │   ├── ChecksTab.kt                       (status grid + ritual checklist)
│   │   ├── ActionsTab.kt                      (Copyright + Time Log cards stacked)
│   │   └── ShareTab.kt                        (Jira + Email editors + chips)
│   ├── editor/
│   │   ├── TemplatePicker.kt                  (dropdown + duplicate/delete/dirty dot)
│   │   ├── TemplateEditorCard.kt              (split-pane editor + actions)
│   │   ├── JiraPreviewPane.kt                 (hybrid renderer host)
│   │   └── EmailPreviewPane.kt                (JEditorPane host)
│   └── chips/
│       └── QuickValueChipsPanel.kt
└── model/
    ├── HandoverTemplate.kt                    (id, name, action, source, isBundled, isOverride)
    └── HandoverPlaceholderValue.kt            (resolved string, isAvailable, source)
```

### Files removed

- `handover/src/main/kotlin/.../ui/HandoverContextPanel.kt` (superseded by `HandoverTicketHeader` + `ChecksTab`).
- `handover/src/main/kotlin/.../ui/HandoverToolbar.kt` (replaced by `JBTabbedPane`).
- `handover/src/main/kotlin/.../ui/panels/QaClipboardPanel.kt` (subsumed by Email template + Quick Values).
- `handover/src/main/kotlin/.../service/QaClipboardService.kt` (formatting moves into `HandoverPlaceholderResolver`).

### Files modified, kept

- `JiraCommentPanel.kt` — collapses into `TemplateEditorCard` for the Jira action.
- `CopyrightPanel.kt` — moves under `ActionsTab` unchanged. Rename to `CopyrightFixCard.kt` for naming consistency.
- `TimeLogPanel.kt` — moves under `ActionsTab` unchanged. Rename to `TimeLogCard.kt`.

## Threading

Following the project conventions in core/CLAUDE.md:

- `HandoverTemplateStore` watcher and reads run on `Dispatchers.IO`. UI subscribes via a `StateFlow<List<HandoverTemplate>>`.
- `HandoverPlaceholderResolver.resolve` is suspend; runs on `Dispatchers.IO`. Cached `{ai.*}` placeholders use a `CoroutineScope + SupervisorJob` field on `HandoverAiSummaryCache`.
- `HandoverWikiPreviewRenderer.renderLive` is suspend; calls `JiraService.renderWikiMarkup` on `Dispatchers.IO` and posts the result to the EDT for `JEditorPane.setText`.
- All UI updates wrap in `withContext(Dispatchers.EDT)` per existing pattern in `HandoverPanel`.

## Settings UI

A new "Handover" page under Tools → Workflow Orchestrator. Sections:

1. **Quick clipboard chips.** Multi-select of placeholders from the catalogue. Default = the 8-item list above.
2. **Templates.** Two read-only labels showing the resolved paths (`~/.workflow-orchestrator/handover/templates/jira/` and `…/email/`) with `Open in Files` buttons. Plus a `Reveal bundled templates` link for inspecting the read-only classpath set.
3. **AI summaries.** A single checkbox: `Compute {ai.changeSummary} and {ai.ticketSummary}`. When unchecked, those placeholders resolve to `(AI summaries disabled)` without ever calling `TextGenerationService`. Default: on.
4. **Override audit.** A read-only counter ("12 handover overrides this month") with a `Clear` link. Reads from a small in-memory ring buffer of the last 1000 `HandoverOverride` events.

## Testing

Following the project's `*Test.kt` co-located convention.

- `HandoverPlaceholderResolverTest` — covers every placeholder, missing-data fallbacks, format-aware switching for `{automation.suiteTable}`.
- `HandoverTemplateStoreTest` — bundled-only / global-only / project-only / merge-with-override scenarios; rename, delete, duplicate; bundled-readonly enforcement.
- `HandoverAiSummaryCacheTest` — cache hit, cache miss, invalidation on `BranchChanged` / `TicketChanged`, fallback when `TextGenerationService` throws.
- `HandoverWikiPreviewRendererTest` — local minimal renderer covers the 5 documented constructs; passthrough for unrecognized markup; live render path stubbed via mock `JiraService.renderWikiMarkup`.
- `JiraServiceImplRenderWikiMarkupTest` (in `:jira`) — POST body shape, 200/401/403 handling.
- `HandoverPanelTabSwitchingTest` — default-tab heuristic (red check → Checks; all green → Share); banner visibility persists across tab switches; `HandoverOverride` event fires on action clicks while banner is up.
- `QuickValueChipsPanelTest` — chip click puts value on clipboard; flash animation; `HandoverChipCopied` event fires; settings change repopulates the grid.

## Migration

This is a destructive UI replacement, not an additive layer. Approach:

1. **One commit per type to keep the diff legible.** Order: model → services → editor widgets → tab widgets → `HandoverPanel` rewrite → file deletions.
2. **`HandoverStateService` keeps the same flow shape** — the new tabs subscribe in the same place (`HandoverPanel.init { stateService.stateFlow.collect { … } }`); only the fan-out targets change.
3. **`PluginSettings.copyrightTemplate` stays.** Used by `CopyrightFixCard` unchanged.
4. **No data migration needed** — there are no persisted templates today; the bundled defaults populate on first run.

## Open questions

None — all clarifying questions resolved during brainstorming. Spec ready for implementation planning.

## References

- Brainstorming transcript: held in conversation; key decisions table reproduced in this spec under "User-facing layout" and "Templates."
- Mockup: `docs/superpowers/mockups/2026-05-08-handover-tab-3tab-mockup.html` (interactive — open in Chrome).
- Existing tab structure: `handover/src/main/kotlin/com/workflow/orchestrator/handover/ui/HandoverPanel.kt`.
- Bug fixed in this branch (related): `HandoverContextPanel.kt` field-init order NPE — fix in commit pending.

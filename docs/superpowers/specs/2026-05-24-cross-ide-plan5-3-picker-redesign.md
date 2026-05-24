# Plan 5.3 — Cross-IDE Delegation Picker UX Redesign

**Status:** Design pinned · ready for implementation
**Branch:** `feature/cross-ide-delegation` @ `89f6a8c6f`
**Worktree:** `.worktrees/cross-ide/`

## 1. Why

The picker today renders `PickerEntry.toString()` rows through `DefaultListCellRenderer` — bare text like `frontend-app (RUNNING)`. No visual hierarchy, no theme integration, no path context, no iconography. Smoke testing confirms it reads as a placeholder, not a finished surface.

The MODELESS fix in `89f6a8c6f` unblocks Launch & Delegate functionally; this plan addresses the look so smoke testing isn't held up by a "this is unusable, redo" reaction.

## 2. Goal

Replace the plain-text picker rows with a custom `ListCellRenderer` that surfaces what the user actually needs at-a-glance: repo identity, location, and reachability. Tighten the framing copy. Keep the change behavior-neutral — selection model, button enablement, auto-launch path, and accept-result wiring all stay identical.

## 3. Non-goals

- No new tools, no protocol changes, no service changes.
- No new icons/SVG assets in this pass — color + text + JBColor only (avoids asset-pipeline noise).
- No search/filter input. Recent projects are typically <30 entries; filter is YAGNI for now.
- No refresh button. The picker already runs `triggerDiscoveryAsync()` on open; mid-dialog refresh is a separate concern.
- No empty-state action link redesign. Status-quo behavior preserved.

## 4. Visual spec

### 4.1 Row layout (per non-header `PickerEntry`)

```
┌─────────────────────────────────────────────────────────────────┐
│  ●  frontend-app                                       running  │
│     /Users/me/work/frontend                                     │
└─────────────────────────────────────────────────────────────────┘
```

- **Status dot** (left, 8×8 px filled circle, JBColor-themed)
  - `running` → `StatusColors.SUCCESS` (green)
  - `closed`  → `StatusColors.SECONDARY_TEXT` (dim grey)
  - `discovered` → `StatusColors.INFO` (blue)
  - `missing` → `StatusColors.ERROR` (red)
- **Repo name** (right of dot, default fg, bold weight)
- **Path** (second line, `StatusColors.SECONDARY_TEXT`, ~85% font size, single-line clipped with ellipsis)
- **Status pill** (right-aligned, same-line as repo name)
  - Lowercase status text inside a rounded `JBColor` background pill (or just colored text — simpler is fine)
  - Color matches dot
- Row vertical padding: ~6 px top / 6 px bottom
- Row left padding: ~12 px
- Row right padding: ~12 px
- Row height: ~44 px (two lines + padding)

### 4.2 Section headers

Replace the current `Font.BOLD or Font.ITALIC` inline header with a styled banner:

```
RECENT ───────────────────────────────────────────
```

- Uppercased label (`RECENT` / `OTHER JETBRAINS INSTANCES`)
- ~80% font size, `StatusColors.SECONDARY_TEXT`
- Trailing `JSeparator` line filling the rest of the row width
- 12 px top padding when not the first header; 4 px otherwise
- 8 px bottom padding

Header strings:
- Existing `Recent` header → render as `RECENT`
- Existing `Discovered` header → render as `OTHER JETBRAINS INSTANCES`

### 4.3 Top hint

Replace the current label:

> Old: `Pick a target IDE for delegation (must be Running):`
> New: `Choose where to send this task. Running targets receive it immediately; closed ones can be launched first.`

Two lines max; wrap if needed. `StatusColors.SECONDARY_TEXT`. ~12 px top padding, ~8 px bottom padding before the list.

### 4.4 Failure / banner panels

Both `toolboxUnknownBanner` and `launchFailureLabel` get a rounded background panel and an inline icon character (use `⚠` or `✕` — Unicode is fine, no SVG required):

```
┌─────────────────────────────────────────────────────────────────┐
│  ⚠  Toolbox detected: IDE flavor unknown — launching with…     │
└─────────────────────────────────────────────────────────────────┘
```

- Background: `StatusColors.WARNING_BG` (toolbox banner) or `StatusColors.ERROR_BG` (failure label) if those exist; otherwise use `JBColor` with light + dark hex variants.
- Inset: `EmptyBorder(8, 12, 8, 12)`.
- Wrap text via `<html>` so long messages don't push the dialog wide.

### 4.5 Dialog dimensions

- Width: bump from `640` to `720` (more breathing room for paths)
- Height: keep `320` minimum; pack content above/below.

### 4.6 Buttons

Order and enablement stays identical. Just sanity-check that with MODELESS modality + the new height, the default action remains Delegate (OK) and `Launch & Delegate` is positioned next to it.

## 5. Files

### New

- `agent/src/main/kotlin/com/workflow/orchestrator/agent/delegation/ui/DelegationPickerCellRenderer.kt`
  - Implements `ListCellRenderer<PickerEntry>`
  - One class with the per-row layout described in §4.1 (regular rows) and §4.2 (header rows — detected via `entry.isHeader`)
  - Uses `JPanel` + `JLabel` composition. Wire JBColor + StatusColors for theming.

### Modified

- `agent/src/main/kotlin/com/workflow/orchestrator/agent/delegation/ui/DelegationPicker.kt`
  - Replace the inline `cellRenderer = object : DefaultListCellRenderer() { … }` with `cellRenderer = DelegationPickerCellRenderer()`
  - Replace `createCenterPanel`'s contents with the new hint copy + banner wrappers from §4.3 and §4.4
  - Bump `preferredSize` to `Dimension(720, 320)` in §4.5
  - Header strings updated in `populateRecentsSync` and `triggerDiscoveryAsync` to use the new `RECENT` / `OTHER JETBRAINS INSTANCES` labels (or, cleaner: keep the existing string keys and let the renderer uppercase them — implementer's call)

### Tests

- Existing `DelegationPickerSocketGlobTest` must keep passing — it doesn't exercise rendering, just discovery state.
- No new render tests required. Swing renderer tests are low-value relative to manual smoke.

## 6. Constraints

- **JB components only.** No external icon libraries, no SVG assets in this pass, no flat-laf overrides. Stick to `JBColor`, `JBLabel`, `JBPanel<*>`, `JBList`, `JBScrollPane`.
- **Theme parity.** Every color must be `JBColor(light, dark)` or sourced from `StatusColors` so both themes look right.
- **Width safe on Windows.** Paths can be long; the path label must truncate cleanly. Test by previewing with a 200-char path manually.
- **No behavior change.** Selection, button enablement, doOKAction guards, Launch & Delegate flow all stay identical. The renderer cannot fire side effects.

## 7. Verification

```bash
./gradlew :agent:compileKotlin
./gradlew :agent:test --tests "*Delegation*" --rerun-tasks --no-build-cache
```

Both must pass. The cross-cutting Plan 5 / 5.1 / 5.2 tests are still relevant and must stay green.

Manual smoke (parent handles, not the implementer):
1. Open delegation_send via the agent
2. Verify the picker opens with the new hint copy
3. Verify rows show as two-line cells with status dots + pills
4. Verify section headers render with separator lines
5. Verify Toolbox banner + failure banner have rounded backgrounds
6. Theme-flip the IDE (light ↔ dark) — both must read correctly

## 8. Commit

Single commit:

```
fix(cross-ide): redesign DelegationPicker rendering for smoke-build polish

- New DelegationPickerCellRenderer with status dot + repo name +
  ellipsized path + colored status pill
- Section headers as uppercased label + trailing separator
- Friendlier top hint copy
- Rounded info / failure banners with inline icon chars
- Bumped dialog width to 720

Behavior preserved. Selection model, button enablement, auto-launch
flow, doOKAction guards all unchanged. JB components only; both themes
covered via JBColor / StatusColors.
```

## 9. What this does NOT do

- Add icons (SVG asset pipeline — separate, optional follow-up)
- Add a search/filter input (YAGNI for typical recents counts)
- Redesign the Accept dialog on the IDE-B side (separate component)
- Redesign the answer-confirm dialog (separate component)
- Redesign the input banner (separate component)
- Change auto-launch behavior, modality, or discovery logic
